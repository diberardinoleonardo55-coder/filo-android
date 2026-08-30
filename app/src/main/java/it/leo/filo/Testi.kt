package it.leo.filo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lingua dell'interfaccia.
 *
 * I testi nel codice sorgente sono in inglese e valgono come chiave: [t] li
 * restituisce tal quali quando la lingua e' l'inglese, e li cerca nel
 * dizionario quando e' un'altra. Una traduzione mancante non lascia un buco:
 * resta la frase inglese.
 *
 * La lingua e' uno stato osservabile, cosi' cambiandola le schermate gia'
 * composte si ridisegnano da sole. Vale anche fuori da una composizione, dove
 * viene semplicemente letta.
 */
object Testi {

    const val PREDEFINITA = "en"

    /** codice della lingua -> nome della lingua nella lingua stessa */
    val LINGUE = linkedMapOf("en" to "English", "it" to "Italiano")

    private var corrente by mutableStateOf(PREDEFINITA)
    private var collegata = false

    /** Prende la lingua dalle preferenze. Va chiamata a ogni punto di ingresso. */
    fun collega(c: Context) {
        if (collegata) return
        val scelta = Impostazioni.lingua(c)
        corrente = if (LINGUE.containsKey(scelta)) scelta else PREDEFINITA
        collegata = true
    }

    fun attuale(): String = corrente

    /** Cambia lingua e la salva. Le schermate si ridisegnano da sole. */
    fun imposta(c: Context, codice: String) {
        if (!LINGUE.containsKey(codice)) return
        Impostazioni.impostaLingua(c, codice)
        corrente = codice
    }

    /**
     * Il testo nella lingua scelta, con i campi sostituiti.
     *
     * I campi si scrivono con le graffe: la traduzione puo' metterli in un
     * ordine diverso senza toccare il codice che la chiama.
     */
    fun t(chiave: String, vararg campi: Pair<String, Any?>): String {
        var frase = DIZIONARI[corrente]?.get(chiave) ?: chiave
        for ((nome, valore) in campi) {
            frase = frase.replace("{$nome}", valore?.toString() ?: "")
        }
        return frase
    }

    private val IT = mapOf(
        // --- schermata principale
        "Pair a device" to "Abbina un dispositivo",
        "Stay connected: yes" to "Resta collegato: sì",
        "Stay connected: no" to "Resta collegato: no",
        "Answer calls: yes" to "Rispondi alle chiamate: sì",
        "Answer calls: no" to "Rispondi alle chiamate: no",
        "Unpair {name}" to "Scollega {name}",
        "Language" to "Lingua",
        "Sending " to "Mando ",
        "Receiving " to "Ricevo ",
        "{done} of {total}" to "{done} di {total}",
        "No device" to "Nessun dispositivo",
        "Pair one to get started" to "Abbinane uno per cominciare",
        "connected over the USB cable" to "collegato dal cavo USB",
        "connected" to "collegato",
        "not answering — it must be on the same network"
            to "non risponde — dev'essere sulla stessa rete",
        "  ·  tap another one to talk to it" to "  ·  tocca un altro per parlargli",
        "Send clipboard" to "Manda gli appunti",
        "Send clipboard to {name}" to "Manda gli appunti a {name}",
        "Send photos, videos or files" to "Manda foto, video o file",
        "Send files to {name}" to "Manda file a {name}",
        "Ask for clipboard" to "Chiedi gli appunti",
        "Ask {name} for the clipboard" to "Chiedi gli appunti a {name}",
        "Asked {name}" to "Chiesto a {name}",
        "{name} is not answering" to "{name} non risponde",
        "What goes through shows up here." to "Qui compare quello che passa.",

        // --- abbinamento
        "Let's connect Filo" to "Colleghiamo Filo",
        "On the other device open Filo, tap Search and type:"
            to "Sull'altro dispositivo apri Filo, tocca Cerca e scrivi:",
        "expired" to "scaduto",
        "expires in {minutes}:{seconds}" to "scade fra {minutes}:{seconds}",
        "tap for a new code" to "tocca per un codice nuovo",
        "This phone cannot open the port: use the opposite direction, "
            to "Questo telefono non riesce ad aprire la porta: usa il verso opposto, ",
        "search for the other device yourself." to "cerca tu l'altro dispositivo.",
        "or" to "oppure",
        "Searching…" to "Sto cercando…",
        "Search for a device" to "Cerca un dispositivo",
        "Nobody in sight. You must be on the same Wi-Fi network, "
            to "Nessuno in vista. Dovete essere sulla stessa rete Wi-Fi, ",
        "and Filo must be open on the other device."
            to "e sull'altro dispositivo Filo dev'essere aperto.",
        "computer" to "computer",
        "phone" to "telefono",
        "Type the six digits it shows." to "Scrivi le sei cifre che mostra.",
        "Pair" to "Abbina",
        "The code has six digits." to "Il codice ha sei cifre.",
        "Code rejected. On the other device it expires after three "
            to "Codice rifiutato. Sull'altro dispositivo scade dopo tre ",
        "minutes: bring it up again and try once more."
            to "minuti: fallo comparire di nuovo e riprova.",
        "Choose another one" to "Scegline un altro",
        "Never mind" to "Lascia stare",

        // --- notifiche
        "Copy" to "Copia",
        "Send" to "Manda",
        "{name} is asking for your clipboard" to "{name} chiede i tuoi appunti",
        "Tap to send it" to "Tocca per mandarglieli",

        // --- avvisi brevi
        "Filo: pair a device first" to "Filo: prima abbina un dispositivo",
        "The clipboard is empty" to "Gli appunti sono vuoti",
        "Sending the text…" to "Mando il testo…",
        "Filo: there is nothing to send" to "Filo: non c'e' niente da mandare",

        // --- diario
        "Paired: {name}" to "Abbinato: {name}",
        "Text from {peer}" to "Testo da {peer}",
        "Text → {peer}" to "Testo → {peer}",
        "{name} → {peer}" to "{name} → {peer}",
        "{name} ← {peer}" to "{name} ← {peer}",
        "Text queued for {peer}" to "Testo in coda per {peer}",
        "{name} queued for {peer}" to "{name} in coda per {peer}",
        "not answering" to "non risponde",
        "Cannot save {name}" to "Non riesco a salvare {name}",
        "cannot find {peer}" to "non trovo {peer}",
        "connected to {peer} over the cable" to "collegato a {peer} col cavo",
    )

    private val DIZIONARI = mapOf("it" to IT)
}
