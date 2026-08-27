package it.leo.filo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast

/**
 * Attivita' senza interfaccia per l'accesso agli appunti.
 *
 * Da Android 10 gli appunti sono leggibili solo dall'applicazione che ha il
 * fuoco. Questa attivita' non disegna nulla: viene aperta, ottiene il fuoco,
 * legge o scrive gli appunti e termina. E' usata dal riquadro delle
 * impostazioni rapide e dalle azioni delle notifiche.
 *
 * L'operazione avviene in [onWindowFocusChanged] e non in `onResume`, perche'
 * il fuoco arriva successivamente e una lettura anticipata restituisce vuoto.
 */
class AppuntiActivity : Activity() {

    companion object {
        const val AZIONE_LEGGI = "it.leo.filo.LEGGI_APPUNTI"
        const val AZIONE_SCRIVI = "it.leo.filo.SCRIVI_APPUNTI"
        const val EXTRA_TESTO = "testo"
        const val EXTRA_A = "a"
    }

    private var fatto = false

    override fun onCreate(salvato: Bundle?) {
        super.onCreate(salvato)
        // Nessun setContentView: la finestra resta trasparente e vuota.
    }

    override fun onWindowFocusChanged(haIlFuoco: Boolean) {
        super.onWindowFocusChanged(haIlFuoco)
        if (!haIlFuoco || fatto) return
        fatto = true
        when (intent?.action) {
            AZIONE_SCRIVI -> scrivi(intent.getStringExtra(EXTRA_TESTO).orEmpty())
            else -> leggi()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun leggi() {
        val a = intent?.getStringExtra(EXTRA_A) ?: Compagni.scelto(this)?.id
        if (a == null) {
            avvisa("Filo: prima abbina un dispositivo")
            return
        }
        val appunti = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val pezzo = appunti?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val testo = pezzo?.coerceToText(this)?.toString().orEmpty()
        if (testo.isEmpty()) {
            avvisa("Gli appunti sono vuoti")
            return
        }
        PonteService.mandaTesto(this, testo, a)
        val dove = Compagni.trova(this, a)?.nome ?: "?"
        avvisa("Mando a $dove: ${testo.take(40)}")
    }

    private fun scrivi(testo: String) {
        if (testo.isEmpty()) return
        val appunti = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        appunti?.setPrimaryClip(ClipData.newPlainText("Filo", testo))
        avvisa("Copiato")
    }

    private fun avvisa(cosa: String) {
        Toast.makeText(applicationContext, cosa, Toast.LENGTH_SHORT).show()
    }
}
