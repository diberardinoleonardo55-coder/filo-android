package it.leo.filo

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Riquadro nelle impostazioni rapide: invia gli appunti al dispositivo scelto.
 *
 * Va aggiunto manualmente all'elenco dei riquadri.
 *
 * Da Android 14 `startActivityAndCollapse(Intent)` solleva un'eccezione: dalla
 * API 34 in poi si usa la variante con PendingIntent.
 */
class Riquadro : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (Compagni.quanti(this@Riquadro) > 0) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = getString(R.string.manda_al_pc)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val destinazione = if (Compagni.quanti(this) > 0) {
            Intent(this, AppuntiActivity::class.java).apply {
                action = AppuntiActivity.AZIONE_LEGGI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, destinazione,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(destinazione)
        }
    }
}
