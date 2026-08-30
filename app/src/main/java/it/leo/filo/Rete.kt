package it.leo.filo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import it.leo.filo.Testi.t

/**
 * Lato client: chiamate verso un dispositivo abbinato.
 *
 * Tre vincoli dell'implementazione:
 *
 * 1. la verifica del certificato confronta l'impronta e non la catena di firma;
 *    il controllo del nome host e' disattivato perche' il certificato riporta
 *    "Filo" e non l'indirizzo, che varia;
 * 2. le impronte accettate sono due, quella dichiarata e quella osservata al
 *    primo collegamento, perche' un intermediario che ispeziona il traffico TLS
 *    presenta un certificato rigenerato;
 * 3. l'indirizzo 127.0.0.1 viene provato solo per i dispositivi di tipo PC, che
 *    possono esporre la propria porta tramite `adb reverse`; per un telefono
 *    corrisponderebbe al dispositivo locale.
 */
object Rete {

    private const val ETICHETTA = "Filo"
    private const val VIA_CAVO = "127.0.0.1"
    private const val VALIDITA_INDIRIZZO = 15_000L

    class ImprontaSbagliata(messaggio: String) : Exception(messaggio)

    data class Voce(
        val id: String,
        val tipo: String,
        val nome: String,
        val dimensione: Long,
        val mime: String,
        val testo: String?,
        val richiesto: Boolean,
    )

    private val buoni = HashMap<String, Pair<String, Long>>()   // id -> (indirizzo, scadenza)
    private val viaCavo = HashSet<String>()

    fun passaDalCavo(id: String) = viaCavo.contains(id)

    fun scordaIndirizzi() {
        buoni.clear()
        viaCavo.clear()
    }

    // --- TLS -----------------------------------------------------------------

    private fun improntaDi(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }

    private fun fabbrica(c: Context, compagno: Compagni.Compagno?) =
        SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf(object : X509TrustManager {
                    override fun checkClientTrusted(catena: Array<X509Certificate>, t: String) {}

                    override fun checkServerTrusted(catena: Array<X509Certificate>, t: String) {
                        if (catena.isEmpty()) throw CertificateException("nessun certificato")
                        val vista = improntaDi(catena[0])
                        if (compagno == null) return          // primo incontro: ci si fida
                        if (vista in compagno.impronteBuone()) return
                        if (compagno.imparaImpronta(vista)) {
                            Compagni.aggiorna(c, compagno)
                            return
                        }
                        throw CertificateException("non e' ${compagno.nome}")
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }),
                null,
            )
        }.socketFactory

    private fun apri(
        c: Context,
        indirizzo: String,
        porta: Int,
        compagno: Compagni.Compagno?,
        strada: String,
        metodo: String = "GET",
        attesaLettura: Int = 12_000,
    ): HttpsURLConnection {
        val conn = URL("https://$indirizzo:$porta$strada").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = fabbrica(c, compagno)
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        conn.requestMethod = metodo
        conn.connectTimeout = 4_000
        conn.readTimeout = attesaLettura
        conn.useCaches = false
        if (compagno != null) conn.setRequestProperty("X-Filo-Token", compagno.tokenUscita)
        return conn
    }

    // --- dove sta adesso -----------------------------------------------------

    /** Il "chi sei?" a un indirizzo qualsiasi: è il primo incontro. */
    fun carta(c: Context, indirizzo: String, porta: Int): JSONObject? = try {
        val conn = apri(c, indirizzo, porta, null, "/chi", attesaLettura = 4_000)
        val corpo = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(corpo).put("indirizzo", indirizzo)
    } catch (e: Exception) {
        null
    }

    /**
     * L'indirizzo buono per questo compagno adesso: il cavo, l'ultimo noto,
     * oppure una ricerca in broadcast se ha cambiato numero.
     */
    fun indirizzo(c: Context, compagno: Compagni.Compagno): String? {
        val adesso = System.currentTimeMillis()
        buoni[compagno.id]?.let { (dove, scade) -> if (adesso < scade) return dove }

        // Il cavo vale solo per un PC: sul telefono, 127.0.0.1 saremmo noi.
        if (compagno.tipo == "pc" && vivo(c, VIA_CAVO, compagno.porta, compagno)) {
            viaCavo.add(compagno.id)
            buoni[compagno.id] = VIA_CAVO to (adesso + VALIDITA_INDIRIZZO)
            return VIA_CAVO
        }
        viaCavo.remove(compagno.id)

        if (compagno.indirizzo.isNotEmpty() && vivo(c, compagno.indirizzo, compagno.porta, compagno)) {
            buoni[compagno.id] = compagno.indirizzo to (adesso + VALIDITA_INDIRIZZO)
            return compagno.indirizzo
        }

        // Sparito: forse il router gli ha dato un altro numero.
        val ritrovato = Scoperta.cerca(c, 1_800).firstOrNull { it.optString("id") == compagno.id }
        if (ritrovato != null) {
            val dove = ritrovato.optString("indirizzo")
            compagno.dati.put("indirizzo", dove)
            Compagni.aggiorna(c, compagno)
            buoni[compagno.id] = dove to (adesso + VALIDITA_INDIRIZZO)
            Log.i(ETICHETTA, "${compagno.nome} si e' spostato su $dove")
            return dove
        }
        buoni.remove(compagno.id)
        return null
    }

    private fun vivo(c: Context, dove: String, porta: Int, compagno: Compagni.Compagno): Boolean =
        try {
            val conn = apri(c, dove, porta, compagno, "/chi", attesaLettura = 2_000)
            conn.connectTimeout = if (dove == VIA_CAVO) 1_200 else 2_500
            val corpo = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(corpo).optString("id") == compagno.id
        } catch (e: Exception) {
            false
        }

    // --- abbinamento ---------------------------------------------------------

    /** Io scrivo il codice che l'altro mostra: lo scambio parte da qui. */
    fun abbina(c: Context, carta: JSONObject, codice: String): Compagni.Compagno? {
        val mioToken = Compagni.nuovoToken()
        val richiesta = JSONObject()
            .put("codice", codice)
            .put("id", Identita.id(c))
            .put("nome", Identita.nome(c))
            .put("tipo", "telefono")
            .put("impronta", Identita.impronta())
            .put("porta", 8787)
            .put("token", mioToken)   // quello che dovrà presentarmi lui

        val indirizzo = carta.optString("indirizzo")
        val porta = carta.optInt("porta", 8787)
        var vista = ""
        return try {
            val conn = apri(c, indirizzo, porta, null, "/abbina", "POST", 10_000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val byte = richiesta.toString().toByteArray()
            conn.setFixedLengthStreamingMode(byte.size)
            conn.outputStream.use { it.write(byte) }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            (conn.serverCertificates.firstOrNull() as? X509Certificate)?.let {
                vista = improntaDi(it)
            }
            val sua = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val compagno = Compagni.aggiungi(
                c,
                id = sua.getString("id"),
                nome = sua.optString("nome", "senza nome"),
                tipo = sua.optString("tipo", "pc"),
                impronta = sua.optString("impronta", vista),
                improntaVista = vista,
                indirizzo = indirizzo,
                porta = sua.optInt("porta", porta),
                tokenUscita = sua.getString("token"),  // quello che presento io
                tokenEntrata = mioToken,
            )
            Diario.annota(c, false, t("Paired: {name}", "name" to compagno.nome))
            scordaIndirizzi()
            compagno
        } catch (e: Exception) {
            Log.w(ETICHETTA, "abbinamento fallito: ${e.message}")
            null
        }
    }

    // --- il giro delle novità ------------------------------------------------

    /** La chiamata appesa. null = non l'ho trovato (diverso da "non ha niente"). */
    fun eventi(c: Context, compagno: Compagni.Compagno): List<Voce>? {
        val dove = indirizzo(c, compagno) ?: return null
        return try {
            val conn = apri(c, dove, compagno.porta, compagno, "/eventi?dopo=0", attesaLettura = 45_000)
            val esito = conn.responseCode
            if (esito != 200) {
                conn.disconnect()
                // 401 vuol dire "non ti conosco piu'": è un fatto, non un guasto.
                return if (esito == 401) emptyList() else null
            }
            val corpo = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val vettore: JSONArray = corpo.optJSONArray("voci") ?: JSONArray()
            (0 until vettore.length()).map { i ->
                val o = vettore.getJSONObject(i)
                Voce(
                    id = o.getString("id"),
                    tipo = o.getString("tipo"),
                    nome = o.optString("nome", "senza nome"),
                    dimensione = o.optLong("dimensione"),
                    mime = o.optString("mime", "application/octet-stream"),
                    testo = if (o.has("testo")) o.getString("testo") else null,
                    richiesto = o.optBoolean("richiesto"),
                )
            }
        } catch (e: ImprontaSbagliata) {
            throw e
        } catch (e: Exception) {
            buoni.remove(compagno.id)
            null
        }
    }

    fun scarica(
        c: Context,
        compagno: Compagni.Compagno,
        voce: Voce,
        dove: OutputStream,
        avanzamento: (Long) -> Unit = {},
    ): Boolean {
        val indirizzo = indirizzo(c, compagno) ?: return false
        return try {
            val conn = apri(
                c, indirizzo, compagno.porta, compagno, "/scarica/${voce.id}", attesaLettura = 60_000
            )
            if (conn.responseCode != 200) {
                conn.disconnect()
                return false
            }
            var fatti = 0L
            conn.inputStream.use { dentro ->
                val pezzo = ByteArray(64 * 1024)
                while (true) {
                    val quanti = dentro.read(pezzo)
                    if (quanti <= 0) break
                    dove.write(pezzo, 0, quanti)
                    fatti += quanti
                    avanzamento(fatti)
                }
            }
            conn.disconnect()
            true
        } catch (e: Exception) {
            Log.w(ETICHETTA, "scaricamento fallito: ${e.message}")
            false
        }
    }

    fun conferma(c: Context, compagno: Compagni.Compagno, id: String) {
        vuoto(c, compagno, "/consegnato/$id")
    }

    fun chiediAppunti(c: Context, compagno: Compagni.Compagno): Boolean =
        vuoto(c, compagno, "/prendi-appunti")

    private fun vuoto(c: Context, compagno: Compagni.Compagno, strada: String): Boolean {
        val dove = indirizzo(c, compagno) ?: return false
        return try {
            val conn = apri(c, dove, compagno.porta, compagno, strada, "POST")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
            val esito = conn.responseCode == 200
            conn.disconnect()
            esito
        } catch (e: Exception) {
            buoni.remove(compagno.id)
            false
        }
    }

    // --- spingere roba di là -------------------------------------------------

    fun spingiTesto(c: Context, compagno: Compagni.Compagno, testo: String): Boolean {
        val dove = indirizzo(c, compagno) ?: return false
        return try {
            val conn = apri(c, dove, compagno.porta, compagno, "/appunti", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val byte = testo.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(byte.size)
            conn.outputStream.use { it.write(byte) }
            val esito = conn.responseCode == 200
            conn.disconnect()
            esito
        } catch (e: Exception) {
            buoni.remove(compagno.id)
            false
        }
    }

    /**
     * Manda un file leggendolo a pezzi.
     *
     * Senza setFixedLengthStreamingMode (o setChunkedStreamingMode)
     * HttpURLConnection si tiene il file intero in memoria prima di spedirlo, e
     * un video da mezzo giga chiude l'app con un OutOfMemory.
     */
    fun spingiFile(
        c: Context,
        compagno: Compagni.Compagno,
        nome: String,
        dimensione: Long,
        dentro: InputStream,
        avanzamento: (Long) -> Unit = {},
    ): Boolean {
        val dove = indirizzo(c, compagno) ?: return false
        return try {
            val conn = apri(c, dove, compagno.porta, compagno, "/carica", "POST", 30_000)
            conn.setRequestProperty("X-Filo-Nome", URLEncoder.encode(nome, "UTF-8"))
            conn.doOutput = true
            if (dimensione > 0) conn.setFixedLengthStreamingMode(dimensione)
            else conn.setChunkedStreamingMode(0)
            var fatti = 0L
            conn.outputStream.use { fuori ->
                val pezzo = ByteArray(64 * 1024)
                while (true) {
                    val quanti = dentro.read(pezzo)
                    if (quanti <= 0) break
                    fuori.write(pezzo, 0, quanti)
                    fatti += quanti
                    avanzamento(fatti)
                }
            }
            val esito = conn.responseCode == 200
            conn.disconnect()
            esito
        } catch (e: Exception) {
            Log.w(ETICHETTA, "invio fallito: ${e.message}")
            buoni.remove(compagno.id)
            false
        }
    }
}
