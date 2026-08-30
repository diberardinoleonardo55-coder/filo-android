package it.leo.filo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Voce dell'applicazione nel menu Condividi di sistema.
 *
 * Riceve ACTION_SEND e ACTION_SEND_MULTIPLE, passa il contenuto a
 * [PonteService] e termina senza mostrare interfaccia.
 */
class CondividiActivity : Activity() {

    override fun onCreate(salvato: Bundle?) {
        super.onCreate(salvato)
        Testi.collega(this)

        if (Compagni.quanti(this) == 0) {
            Toast.makeText(this, Testi.t("Filo: pair a device first"), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val roba = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> unSolo()?.let { roba.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> roba.addAll(tanti())
        }

        val testo = intent?.getStringExtra(Intent.EXTRA_TEXT)
        when {
            roba.isNotEmpty() -> {
                PonteService.mandaRoba(this, roba)
                Toast.makeText(
                    this,
                    if (roba.size == 1) "Mando…" else "Mando ${roba.size} cose…",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            !testo.isNullOrEmpty() -> {
                PonteService.mandaTesto(this, testo)
                Toast.makeText(this, Testi.t("Sending the text…"), Toast.LENGTH_SHORT).show()
            }

            else -> Toast.makeText(this, Testi.t("Filo: there is nothing to send"), Toast.LENGTH_SHORT).show()
        }

        finish()
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun unSolo(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?
        }

    @Suppress("DEPRECATION")
    private fun tanti(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
}
