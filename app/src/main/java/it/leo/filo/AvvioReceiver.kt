package it.leo.filo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Riavvio del servizio dopo il boot del dispositivo.
 *
 * ACTION_BOOT_COMPLETED e' una delle condizioni in cui il sistema consente
 * l'avvio di un servizio in primo piano. Se l'avvio non riesce, il servizio
 * riparte alla prima apertura dell'applicazione.
 */
class AvvioReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Compagni.quanti(c) > 0 && Impostazioni.ponteAcceso(c)) {
            PonteService.accendi(c)
        }
    }
}
