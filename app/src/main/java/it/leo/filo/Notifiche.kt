package it.leo.filo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri

/**
 * Canali e costruzione delle notifiche.
 *
 * Da Android 10 un'applicazione in secondo piano non puo' leggere ne' scrivere
 * gli appunti: il testo ricevuto viene quindi presentato come notifica con
 * l'azione Testi.t("Copy"), e la richiesta di appunti come notifica con l'azione
 * Testi.t("Send"). Entrambe aprono [AppuntiActivity], che ha il fuoco e puo' operare
 * sugli appunti.
 */
object Notifiche {

    const val CANALE_PONTE = "ponte"
    const val CANALE_COSE = "cose"
    const val ID_PONTE = 1
    private var progressivo = 100

    fun prepara(c: Context) {
        val gestore = c.getSystemService(NotificationManager::class.java) ?: return

        // Il collegamento e' una riga che deve stare li' senza dare fastidio:
        // importanza minima, niente suono, niente comparsa.
        gestore.createNotificationChannel(
            NotificationChannel(CANALE_PONTE, c.getString(R.string.canale_ponte), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        gestore.createNotificationChannel(
            NotificationChannel(CANALE_COSE, c.getString(R.string.canale_trasferimenti), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun bandiere() =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ponte(c: Context, stato: String): Notification =
        Notification.Builder(c, CANALE_PONTE)
            .setSmallIcon(R.drawable.ic_filo)
            .setContentTitle("Filo")
            .setContentText(stato)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    c, 0, Intent(c, MainActivity::class.java), bandiere()
                )
            )
            .build()

    fun aggiornaPonte(c: Context, stato: String) {
        c.getSystemService(NotificationManager::class.java)?.notify(ID_PONTE, ponte(c, stato))
    }

    fun testoArrivato(c: Context, testo: String) {
        val copia = Intent(c, AppuntiActivity::class.java).apply {
            action = AppuntiActivity.AZIONE_SCRIVI
            putExtra(AppuntiActivity.EXTRA_TESTO, testo)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val n = Notification.Builder(c, CANALE_COSE)
            .setSmallIcon(R.drawable.ic_filo)
            .setContentTitle("Testo arrivato")
            .setContentText(testo.take(120))
            .setStyle(Notification.BigTextStyle().bigText(testo.take(800)))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, prossimo(), copia, bandiere()))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(c, R.drawable.ic_filo),
                    Testi.t("Copy"),
                    PendingIntent.getActivity(c, prossimo(), copia, bandiere()),
                ).build()
            )
            .build()
        mostra(c, n)
    }

    fun richiestaAppunti(c: Context, daChi: String, idSuo: String) {
        val manda = Intent(c, AppuntiActivity::class.java).apply {
            action = AppuntiActivity.AZIONE_LEGGI
            putExtra(AppuntiActivity.EXTRA_A, idSuo)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val n = Notification.Builder(c, CANALE_COSE)
            .setSmallIcon(R.drawable.ic_filo)
            .setContentTitle(Testi.t("{name} is asking for your clipboard", "name" to daChi))
            .setContentText(Testi.t("Tap to send it"))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, prossimo(), manda, bandiere()))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(c, R.drawable.ic_filo),
                    Testi.t("Send"),
                    PendingIntent.getActivity(c, prossimo(), manda, bandiere()),
                ).build()
            )
            .build()
        mostra(c, n)
    }

    fun fileArrivato(c: Context, nome: String, dove: String, uri: Uri?, mime: String) {
        val costruttore = Notification.Builder(c, CANALE_COSE)
            .setSmallIcon(R.drawable.ic_filo)
            .setContentTitle(nome)
            .setContentText("Arrivato · $dove")
            .setAutoCancel(true)
        if (uri != null) {
            val apri = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            costruttore.setContentIntent(PendingIntent.getActivity(c, prossimo(), apri, bandiere()))
        }
        mostra(c, costruttore.build())
    }

    fun avviso(c: Context, titolo: String, testo: String) {
        mostra(
            c,
            Notification.Builder(c, CANALE_COSE)
                .setSmallIcon(R.drawable.ic_filo)
                .setContentTitle(titolo)
                .setContentText(testo)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun prossimo(): Int = ++progressivo

    private fun mostra(c: Context, n: Notification) {
        c.getSystemService(NotificationManager::class.java)?.notify(prossimo(), n)
    }
}
