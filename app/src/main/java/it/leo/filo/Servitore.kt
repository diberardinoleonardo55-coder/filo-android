package it.leo.filo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocket

/**
 * Lato server: HTTPS, un token per dispositivo abbinato, otto endpoint.
 *
 * Gli endpoint sono gli stessi del lato Python, quindi chi chiama non distingue
 * il tipo di dispositivo che risponde.
 *
 * L'implementazione HTTP e' minimale: le richieste provengono da un solo
 * protocollo noto e ogni risposta chiude la connessione (`Connection: close`),
 * evitando la gestione del riuso.
 */
class Servitore(private val contesto: Context, private val porta: Int = 8787) {

    companion object {
        private const val ETICHETTA = "Filo"
        const val DURATA_ABBINAMENTO = 180_000L

        // --- la finestrella dell'abbinamento, aperta da chi mostra il codice --
        @Volatile
        private var codice: String? = null

        @Volatile
        private var scadenza = 0L

        fun apriAbbinamento(): String {
            val nuovo = (0 until 6).map { (0..9).random() }.joinToString("")
            codice = nuovo
            scadenza = System.currentTimeMillis() + DURATA_ABBINAMENTO
            return nuovo
        }

        fun codiceVivo(): String? =
            if (codice != null && System.currentTimeMillis() < scadenza) codice else null

        fun secondiRimasti(): Int =
            ((scadenza - System.currentTimeMillis()) / 1000).coerceAtLeast(0).toInt()

        fun chiudiAbbinamento() {
            codice = null
            scadenza = 0
        }

        fun cartaDiIdentita(c: Context): JSONObject = JSONObject()
            .put("app", "filo")
            .put("id", Identita.id(c))
            .put("nome", Identita.nome(c))
            .put("tipo", "telefono")
            .put("porta", 8787)
            .put("impronta", Identita.impronta())
            .put("versione", 2)
    }

    @Volatile
    private var presa: ServerSocket? = null
    private val lavoranti = Executors.newCachedThreadPool()

    @Volatile
    var acceso = false
        private set

    fun avvia() {
        if (acceso) return
        Thread({
            try {
                val fabbrica = Identita.contestoServer().serverSocketFactory
                val s = fabbrica.createServerSocket(porta) as SSLServerSocket
                presa = s
                acceso = true
                Log.i(ETICHETTA, "in ascolto sulla porta $porta")
                while (!s.isClosed) {
                    val cliente = try {
                        s.accept()
                    } catch (e: Exception) {
                        break
                    }
                    lavoranti.execute { servi(cliente) }
                }
            } catch (e: Exception) {
                // Niente porta: l'app continua a funzionare chiamando gli altri,
                // solo non può essere chiamata.
                Log.w(ETICHETTA, "non riesco ad aprire la porta: ${e.message}")
            } finally {
                acceso = false
            }
        }, "filo-servitore").start()
    }

    fun ferma() {
        acceso = false
        try {
            presa?.close()
        } catch (e: Exception) {
            // sta chiudendo comunque
        }
        lavoranti.shutdownNow()
    }

    // --- una conversazione ---------------------------------------------------

    private fun servi(cliente: Socket) {
        cliente.soTimeout = 60_000
        try {
            val dentro = BufferedInputStream(cliente.getInputStream())
            val fuori = BufferedOutputStream(cliente.getOutputStream())
            val riga = leggiRiga(dentro) ?: return
            val pezzi = riga.split(" ")
            if (pezzi.size < 2) return
            val metodo = pezzi[0]
            val strada = pezzi[1]
            val intestazioni = leggiIntestazioni(dentro)
            smista(metodo, strada, intestazioni, dentro, fuori, cliente)
            fuori.flush()
        } catch (e: Exception) {
            Log.w(ETICHETTA, "conversazione interrotta: ${e.message}")
        } finally {
            try {
                cliente.close()
            } catch (e: Exception) {
                // gia' chiuso
            }
        }
    }

    private fun leggiRiga(dentro: InputStream): String? {
        val cassetto = ByteArrayOutputStream()
        while (true) {
            val b = dentro.read()
            if (b < 0) return if (cassetto.size() == 0) null else cassetto.toString("US-ASCII")
            if (b == '\n'.code) break
            if (b != '\r'.code) cassetto.write(b)
            if (cassetto.size() > 8192) return null
        }
        return cassetto.toString("US-ASCII")
    }

    private fun leggiIntestazioni(dentro: InputStream): Map<String, String> {
        val mappa = HashMap<String, String>()
        while (true) {
            val riga = leggiRiga(dentro) ?: break
            if (riga.isEmpty()) break
            val due = riga.split(":", limit = 2)
            if (due.size == 2) mappa[due[0].trim().lowercase()] = due[1].trim()
        }
        return mappa
    }

    // --- le strade -----------------------------------------------------------

    private fun smista(
        metodo: String,
        strada: String,
        intestazioni: Map<String, String>,
        dentro: InputStream,
        fuori: OutputStream,
        cliente: Socket,
    ) {
        val senzaDomanda = strada.substringBefore("?")
        val pezzi = senzaDomanda.split("/").filter { it.isNotEmpty() }
        val primo = pezzi.firstOrNull() ?: "chi"

        if (metodo == "GET" && (primo == "chi")) {
            val carta = cartaDiIdentita(contesto).put("abbinamento", codiceVivo() != null)
            return json(fuori, carta)
        }

        if (metodo == "POST" && primo == "abbina") {
            return abbina(dentro, intestazioni, fuori, cliente)
        }

        // Da qui in giù serve essere qualcuno che conosco.
        val compagno = Compagni.daToken(contesto, intestazioni["x-filo-token"] ?: "")
        if (compagno == null) {
            return json(fuori, JSONObject().put("errore", "non abbinato"), 401)
        }
        compagno.saluta(cliente.inetAddress?.hostAddress)
        Compagni.aggiorna(contesto, compagno)

        when {
            metodo == "GET" && primo == "eventi" -> eventi(compagno, fuori)
            metodo == "GET" && primo == "scarica" && pezzi.size == 2 ->
                scarica(compagno, pezzi[1], fuori)
            metodo == "POST" && primo == "consegnato" && pezzi.size == 2 -> {
                val voce = CodaUscita.trova(pezzi[1])
                if (voce != null && voce.a == compagno.id) CodaUscita.segnaConsegnata(pezzi[1])
                json(fuori, JSONObject().put("ok", true))
            }
            metodo == "POST" && primo == "carica" -> carica(compagno, intestazioni, dentro, fuori)
            metodo == "POST" && primo == "appunti" -> {
                val testo = String(corpo(intestazioni, dentro), Charsets.UTF_8)
                Diario.annota(contesto, false, "Testo da ${compagno.nome}", testo.take(90))
                // Scrivere negli appunti da qui non si può: da Android 10 serve
                // avere il fuoco. Quindi diventa una notifica da toccare.
                Notifiche.testoArrivato(contesto, testo)
                json(fuori, JSONObject().put("ok", true))
            }
            metodo == "POST" && primo == "prendi-appunti" -> {
                Notifiche.richiestaAppunti(contesto, compagno.nome, compagno.id)
                json(fuori, JSONObject().put("ok", true))
            }
            else -> json(fuori, JSONObject().put("errore", "strada sconosciuta"), 404)
        }
    }

    private fun abbina(
        dentro: InputStream,
        intestazioni: Map<String, String>,
        fuori: OutputStream,
        cliente: Socket,
    ) {
        val richiesta = try {
            JSONObject(String(corpo(intestazioni, dentro), Charsets.UTF_8))
        } catch (e: Exception) {
            return json(fuori, JSONObject().put("errore", "richiesta illeggibile"), 400)
        }
        val atteso = codiceVivo()
        val dato = richiesta.optString("codice")
        if (atteso == null || dato.isEmpty() || dato != atteso) {
            Thread.sleep(1000)  // un codice a sei cifre va difeso dalla forza bruta
            return json(fuori, JSONObject().put("errore", "codice sbagliato o scaduto"), 403)
        }
        val idSuo = richiesta.optString("id")
        val improntaSua = richiesta.optString("impronta")
        if (idSuo.isEmpty() || improntaSua.isEmpty()) {
            return json(fuori, JSONObject().put("errore", "dati incompleti"), 400)
        }

        chiudiAbbinamento()
        val tokenPerMe = Compagni.nuovoToken()
        val compagno = Compagni.aggiungi(
            contesto,
            id = idSuo,
            nome = richiesta.optString("nome", "senza nome").take(60),
            tipo = richiesta.optString("tipo", "pc"),
            impronta = improntaSua,
            improntaVista = "",   // lui ha chiamato me: il suo certificato non l'ho visto
            indirizzo = richiesta.optString("indirizzo").ifEmpty {
                cliente.inetAddress?.hostAddress ?: ""
            },
            porta = richiesta.optInt("porta", 8787),
            tokenUscita = richiesta.optString("token"),  // me l'ha dato lui
            tokenEntrata = tokenPerMe,
        )
        Diario.annota(contesto, false, "Abbinato: ${compagno.nome}")
        json(fuori, cartaDiIdentita(contesto).put("token", tokenPerMe))
    }

    private fun eventi(compagno: Compagni.Compagno, fuori: OutputStream) {
        val voci = CodaUscita.attendi(compagno.id)
        val vettore = JSONArray()
        voci.forEach { v ->
            val o = JSONObject()
                .put("id", v.id)
                .put("tipo", v.tipo)
                .put("nome", v.nome)
                .put("mime", v.mime)
                .put("dimensione", v.dimensione)
                .put("richiesto", v.richiesto)
            if (v.testo != null) o.put("testo", v.testo)
            vettore.put(o)
        }
        json(fuori, JSONObject().put("voci", vettore))
    }

    private fun scarica(compagno: Compagni.Compagno, id: String, fuori: OutputStream) {
        val voce = CodaUscita.trova(id)
        if (voce == null || voce.a != compagno.id) {
            return json(fuori, JSONObject().put("errore", "voce scaduta"), 404)
        }
        if (voce.tipo == "testo") {
            return rispondi(fuori, 200, "text/plain; charset=utf-8", (voce.testo ?: "").toByteArray())
        }
        val uri = voce.uri ?: return json(fuori, JSONObject().put("errore", "niente file"), 410)
        val flusso = try {
            contesto.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return json(fuori, JSONObject().put("errore", "non riesco ad aprirlo"), 410)

        intestazione(fuori, 200, voce.mime, voce.dimensione)
        StatoPonte.iniziaPassaggio(compagno.id, voce.nome, versoIlCompagno = true, totale = voce.dimensione)
        var fatti = 0L
        flusso.use { dentro ->
            val pezzo = ByteArray(64 * 1024)
            while (true) {
                val quanti = dentro.read(pezzo)
                if (quanti <= 0) break
                fuori.write(pezzo, 0, quanti)
                fatti += quanti
                StatoPonte.avanza(compagno.id, fatti)
            }
        }
        fuori.flush()
        StatoPonte.finePassaggio(compagno.id)
        Diario.annota(contesto, true, "${voce.nome} → ${compagno.nome}", misura(voce.dimensione))
    }

    private fun carica(
        compagno: Compagni.Compagno,
        intestazioni: Map<String, String>,
        dentro: InputStream,
        fuori: OutputStream,
    ) {
        val nome = java.net.URLDecoder.decode(intestazioni["x-filo-nome"] ?: "file", "UTF-8")
        val lunghezza = intestazioni["content-length"]?.toLongOrNull() ?: 0L
        val mime = intestazioni["content-type"] ?: "application/octet-stream"
        StatoPonte.iniziaPassaggio(compagno.id, nome, versoIlCompagno = false, totale = lunghezza)
        var scritti = 0L
        val salvato = Salvataggio.salva(contesto, nome, indovinaMime(nome, mime)) { verso ->
            try {
                copia(dentro, verso, lunghezza) { fatti ->
                    scritti = fatti
                    StatoPonte.avanza(compagno.id, fatti)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        StatoPonte.finePassaggio(compagno.id)
        if (salvato == null) {
            Diario.annota(contesto, false, "Non riesco a salvare $nome")
            return json(fuori, JSONObject().put("errore", "non salvato"), 500)
        }
        Diario.annota(contesto, false, "$nome ← ${compagno.nome}", salvato.dove)
        Notifiche.fileArrivato(contesto, nome, salvato.dove, salvato.uri, indovinaMime(nome, mime))
        json(fuori, JSONObject().put("ok", true).put("byte", scritti))
    }

    /**
     * Il tipo che dice chi manda non basta: `/carica` porta spesso
     * `application/octet-stream`, e con quello una foto finirebbe in Download
     * invece che in Immagini. Il nome del file ne sa di più.
     */
    private fun indovinaMime(nome: String, dichiarato: String): String {
        val estensione = nome.substringAfterLast('.', "").lowercase()
        val daNome = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(estensione)
        if (!daNome.isNullOrEmpty()) return daNome
        return dichiarato.substringBefore(";").trim().ifEmpty { "application/octet-stream" }
    }

    // --- corpo e risposte ----------------------------------------------------

    private fun corpo(intestazioni: Map<String, String>, dentro: InputStream): ByteArray {
        val cassetto = ByteArrayOutputStream()
        copia(dentro, cassetto, intestazioni["content-length"]?.toLongOrNull() ?: 0L) {}
        return cassetto.toByteArray()
    }

    private fun copia(
        dentro: InputStream,
        fuori: OutputStream,
        quanti: Long,
        avanzamento: (Long) -> Unit,
    ) {
        val pezzo = ByteArray(64 * 1024)
        var fatti = 0L
        while (fatti < quanti) {
            val quantiOra = minOf(pezzo.size.toLong(), quanti - fatti).toInt()
            val letti = dentro.read(pezzo, 0, quantiOra)
            if (letti <= 0) break
            fuori.write(pezzo, 0, letti)
            fatti += letti
            avanzamento(fatti)
        }
    }

    private fun intestazione(fuori: OutputStream, codice: Int, tipo: String, lunghezza: Long) {
        val testa = buildString {
            append("HTTP/1.1 $codice ${if (codice == 200) "OK" else "NO"}\r\n")
            append("Content-Type: $tipo\r\n")
            append("Content-Length: $lunghezza\r\n")
            append("Connection: close\r\n\r\n")
        }
        fuori.write(testa.toByteArray(Charsets.US_ASCII))
    }

    private fun rispondi(fuori: OutputStream, codice: Int, tipo: String, corpo: ByteArray) {
        intestazione(fuori, codice, tipo, corpo.size.toLong())
        fuori.write(corpo)
        fuori.flush()
    }

    private fun json(fuori: OutputStream, oggetto: JSONObject, codice: Int = 200) {
        rispondi(fuori, codice, "application/json; charset=utf-8", oggetto.toString().toByteArray())
    }
}
