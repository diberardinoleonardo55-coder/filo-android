package it.leo.filo

import android.net.Uri
import java.util.UUID

/**
 * Coda delle voci da consegnare, separata per destinatario.
 *
 * Una voce e' un file, un testo o una richiesta di appunti. Chi riceve la
 * ritira con GET /eventi, che resta in attesa fino ad [ATTESA_MASSIMA] e
 * ritorna appena la coda del richiedente non e' vuota.
 *
 * Le voci di tipo file contengono l'Uri e non i byte: il contenuto viene letto
 * durante il download, cosi' la dimensione del file non incide sulla memoria.
 */
object CodaUscita {

    const val ATTESA_MASSIMA = 25_000L

    class Voce(
        val id: String,
        val a: String,
        val tipo: String,           // "file" | "testo" | "richiesta"
        val nome: String,
        val mime: String,
        val dimensione: Long,
        val testo: String? = null,
        val uri: Uri? = null,
        val richiesto: Boolean = false,
        val quando: Long = System.currentTimeMillis(),
    ) {
        @Volatile
        var consegnata = false
    }

    private val voci = mutableListOf<Voce>()
    private val serratura = Object()

    private fun aggiungi(v: Voce): Voce {
        synchronized(serratura) {
            voci.add(v)
            pulisci()
            // Sblocca le chiamate /eventi in attesa, rendendo la consegna immediata.
            serratura.notifyAll()
        }
        return v
    }

    private fun pulisci() {
        val limite = System.currentTimeMillis() - 6 * 3600_000
        voci.removeAll { it.consegnata || it.quando < limite }
    }

    fun mettiFile(a: String, nome: String, mime: String, dimensione: Long, uri: Uri): Voce =
        aggiungi(
            Voce(UUID.randomUUID().toString().take(16), a, "file", nome, mime, dimensione, uri = uri)
        )

    fun mettiTesto(a: String, testo: String, richiesto: Boolean = false): Voce =
        aggiungi(
            Voce(
                UUID.randomUUID().toString().take(16), a, "testo", "appunti",
                "text/plain", testo.length.toLong(), testo = testo, richiesto = richiesto,
            )
        )

    fun chiediAppunti(a: String): Voce =
        aggiungi(
            Voce(UUID.randomUUID().toString().take(16), a, "richiesta", "appunti", "text/plain", 0)
        )

    // --- ritiro --------------------------------------------------------------

    fun perQuesto(a: String): List<Voce> =
        synchronized(serratura) { voci.filter { it.a == a && !it.consegnata } }

    /** Attende una voce per il destinatario indicato; lista vuota alla scadenza. */
    fun attendi(a: String, scadenza: Long = ATTESA_MASSIMA): List<Voce> {
        val fine = System.currentTimeMillis() + scadenza
        synchronized(serratura) {
            while (true) {
                val pronte = voci.filter { it.a == a && !it.consegnata }
                if (pronte.isNotEmpty()) return pronte
                val resto = fine - System.currentTimeMillis()
                if (resto <= 0) return emptyList()
                try {
                    serratura.wait(resto)
                } catch (e: InterruptedException) {
                    return emptyList()
                }
            }
        }
    }

    fun trova(id: String): Voce? = synchronized(serratura) { voci.firstOrNull { it.id == id } }

    fun segnaConsegnata(id: String) {
        synchronized(serratura) {
            voci.firstOrNull { it.id == id }?.consegnata = true
            pulisci()
        }
    }

    fun inAttesa(a: String): Int = perQuesto(a).size

    fun svuota() = synchronized(serratura) {
        voci.clear()
        serratura.notifyAll()
    }
}
