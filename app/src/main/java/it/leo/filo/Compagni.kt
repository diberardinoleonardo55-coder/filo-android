package it.leo.filo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Registro dei dispositivi abbinati.
 *
 * Un abbinamento e' simmetrico: nella stessa chiamata i due dispositivi si
 * scambiano impronta del certificato, indirizzo, porta e un token per parte. Il
 * token inviato e' quello che l'altro dovra' presentare; quello ricevuto e'
 * quello da presentare a lui.
 *
 * Di ogni dispositivo si conservano due impronte, quella dichiarata e quella
 * osservata al primo collegamento riuscito: vedi [Compagno.impronteBuone].
 */
object Compagni {

    private const val CHIAVE = "compagni"
    private const val SCELTO = "scelto"

    private val ascoltatori = mutableListOf<() -> Unit>()

    fun ascolta(f: () -> Unit) {
        ascoltatori.add(f)
    }

    fun smettiDiAscoltare(f: () -> Unit) {
        ascoltatori.remove(f)
    }

    private fun avvisa() = ascoltatori.toList().forEach { it() }

    class Compagno(val dati: JSONObject) {
        val id: String get() = dati.optString("id")
        val nome: String get() = dati.optString("nome", "senza nome")
        val tipo: String get() = dati.optString("tipo", "pc")
        val indirizzo: String get() = dati.optString("indirizzo")
        val porta: Int get() = dati.optInt("porta", 8787)

        /** Impronta dichiarata dal dispositivo. */
        val impronta: String get() = dati.optString("impronta").uppercase()

        /** Impronta osservata sul certificato ricevuto, memorizzata al primo collegamento. */
        val improntaVista: String get() = dati.optString("impronta_vista").uppercase()

        fun impronteBuone(): Set<String> =
            setOf(impronta, improntaVista).filter { it.isNotEmpty() }.toSet()

        /** Memorizza l'impronta osservata solo se non e' gia' presente. */
        fun imparaImpronta(vista: String): Boolean {
            if (improntaVista.isNotEmpty() || vista.isEmpty()) return false
            dati.put("impronta_vista", vista.uppercase())
            return true
        }

        /** Token da presentare nelle chiamate verso questo dispositivo. */
        val tokenUscita: String get() = dati.optString("token_uscita")

        /** Token che questo dispositivo deve presentare. */
        val tokenEntrata: String get() = dati.optString("token_entrata")

        val visto: Long get() = dati.optLong("visto")

        val inLinea: Boolean get() = System.currentTimeMillis() - visto < 70_000

        fun saluta(indirizzo: String? = null) {
            dati.put("visto", System.currentTimeMillis())
            if (!indirizzo.isNullOrEmpty() && indirizzo != "127.0.0.1" &&
                indirizzo != dati.optString("indirizzo")
            ) {
                dati.put("indirizzo", indirizzo)
            }
        }
    }

    fun nuovoToken(): String {
        val byte = ByteArray(32)
        SecureRandom().nextBytes(byte)
        return byte.joinToString("") { "%02x".format(it) }
    }

    // --- lettura -------------------------------------------------------------

    private fun elenco(c: Context): JSONArray = try {
        JSONArray(Impostazioni.prefs(c).getString(CHIAVE, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun salvaElenco(c: Context, vettore: JSONArray) {
        Impostazioni.prefs(c).edit().putString(CHIAVE, vettore.toString()).apply()
        avvisa()
    }

    fun tutti(c: Context): List<Compagno> {
        val v = elenco(c)
        return (0 until v.length()).map { Compagno(v.getJSONObject(it)) }
    }

    fun quanti(c: Context): Int = elenco(c).length()

    fun trova(c: Context, id: String): Compagno? = tutti(c).firstOrNull { it.id == id }

    /** Individua il dispositivo chiamante dal token presentato. */
    fun daToken(c: Context, token: String): Compagno? {
        if (token.isEmpty()) return null
        return tutti(c).firstOrNull { confronto(it.tokenEntrata, token) }
    }

    /** Confronto a tempo costante, per non esporre il token a un attacco temporale. */
    private fun confronto(uno: String, due: String): Boolean {
        if (uno.isEmpty() || uno.length != due.length) return false
        var differenza = 0
        for (i in uno.indices) differenza = differenza or (uno[i].code xor due[i].code)
        return differenza == 0
    }

    // --- scelta --------------------------------------------------------------

    fun scelto(c: Context): Compagno? {
        val id = Impostazioni.prefs(c).getString(SCELTO, "") ?: ""
        return trova(c, id) ?: tutti(c).firstOrNull()
    }

    fun scegli(c: Context, id: String) {
        Impostazioni.prefs(c).edit().putString(SCELTO, id).apply()
        avvisa()
    }

    // --- scrittura -----------------------------------------------------------

    fun aggiungi(
        c: Context,
        id: String,
        nome: String,
        tipo: String,
        impronta: String,
        improntaVista: String,
        indirizzo: String,
        porta: Int,
        tokenUscita: String,
        tokenEntrata: String,
    ): Compagno {
        val vecchi = tutti(c).filter { it.id != id }
        val voce = JSONObject()
            .put("id", id)
            .put("nome", nome)
            .put("tipo", if (tipo == "pc") "pc" else "telefono")
            .put("impronta", impronta.uppercase())
            .put("impronta_vista", improntaVista.uppercase())
            .put("indirizzo", indirizzo)
            .put("porta", porta)
            .put("token_uscita", tokenUscita)
            .put("token_entrata", tokenEntrata)
            .put("visto", System.currentTimeMillis())
        val vettore = JSONArray()
        vecchi.forEach { vettore.put(it.dati) }
        vettore.put(voce)
        salvaElenco(c, vettore)
        scegli(c, id)
        return Compagno(voce)
    }

    fun salva(c: Context) {
        val vettore = JSONArray()
        tutti(c).forEach { vettore.put(it.dati) }
        Impostazioni.prefs(c).edit().putString(CHIAVE, vettore.toString()).apply()
    }

    /** Salva le modifiche a un dispositivo gia' presente in elenco. */
    fun aggiorna(c: Context, compagno: Compagno) {
        val vettore = JSONArray()
        tutti(c).forEach { vettore.put(if (it.id == compagno.id) compagno.dati else it.dati) }
        Impostazioni.prefs(c).edit().putString(CHIAVE, vettore.toString()).apply()
    }

    fun dimentica(c: Context, id: String) {
        val vettore = JSONArray()
        tutti(c).filter { it.id != id }.forEach { vettore.put(it.dati) }
        salvaElenco(c, vettore)
        if (Impostazioni.prefs(c).getString(SCELTO, "") == id) {
            val primo = tutti(c).firstOrNull()
            Impostazioni.prefs(c).edit().putString(SCELTO, primo?.id ?: "").apply()
        }
    }
}
