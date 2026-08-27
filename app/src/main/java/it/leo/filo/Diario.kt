package it.leo.filo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Elenco delle ultime operazioni, mostrato nella schermata principale.
 *
 * Viene salvato come JSON nelle preferenze: le voci sono poche e brevi, e non
 * giustificano un database.
 */
object Diario {

    private const val CHIAVE = "diario"
    private const val QUANTE = 60

    /** verso = true per le operazioni in uscita. */
    data class Voce(
        val verso: Boolean,
        val testo: String,
        val dettaglio: String,
        val quando: Long,
    )

    private val ascoltatori = mutableListOf<() -> Unit>()

    fun ascolta(f: () -> Unit) {
        ascoltatori.add(f)
    }

    fun smettiDiAscoltare(f: () -> Unit) {
        ascoltatori.remove(f)
    }

    fun leggi(c: Context): List<Voce> {
        val grezzo = Impostazioni.prefs(c).getString(CHIAVE, "[]") ?: "[]"
        return try {
            val vettore = JSONArray(grezzo)
            (0 until vettore.length()).map { i ->
                val o = vettore.getJSONObject(i)
                Voce(
                    verso = o.optBoolean("su"),
                    testo = o.optString("testo"),
                    dettaglio = o.optString("dettaglio"),
                    quando = o.optLong("quando"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun annota(c: Context, versoIlPc: Boolean, testo: String, dettaglio: String = "") {
        val vecchie = leggi(c)
        val vettore = JSONArray()
        vettore.put(
            JSONObject()
                .put("su", versoIlPc)
                .put("testo", testo)
                .put("dettaglio", dettaglio)
                .put("quando", System.currentTimeMillis())
        )
        vecchie.take(QUANTE - 1).forEach {
            vettore.put(
                JSONObject()
                    .put("su", it.verso)
                    .put("testo", it.testo)
                    .put("dettaglio", it.dettaglio)
                    .put("quando", it.quando)
            )
        }
        Impostazioni.prefs(c).edit().putString(CHIAVE, vettore.toString()).apply()
        ascoltatori.toList().forEach { it() }
    }

    fun svuota(c: Context) {
        Impostazioni.prefs(c).edit().remove(CHIAVE).apply()
        ascoltatori.toList().forEach { it() }
    }
}
