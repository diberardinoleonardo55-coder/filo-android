package it.leo.filo

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Scrittura dei file ricevuti nella memoria condivisa.
 *
 * Immagini in Immagini/Filo, video in Film/Filo, il resto in Download/Filo,
 * cosi' i contenuti risultano visibili alle altre applicazioni.
 *
 * Da Android 10 si usa MediaStore; nelle versioni precedenti la scrittura e'
 * diretta, seguita da una notifica al media scanner.
 */
object Salvataggio {

    private const val ETICHETTA = "Filo"
    const val CARTELLA = "Filo"

    data class Salvato(val uri: Uri?, val dove: String)

    private fun famiglia(mime: String) = when {
        mime.startsWith("image/") -> "immagine"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "altro"
    }

    fun salva(c: Context, nome: String, mime: String, versa: (OutputStream) -> Boolean): Salvato? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) conMediaStore(c, nome, mime, versa)
        else aMano(c, nome, mime, versa)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun conMediaStore(
        c: Context,
        nome: String,
        mime: String,
        versa: (OutputStream) -> Boolean,
    ): Salvato? {
        val (raccolta, cartella) = when (famiglia(mime)) {
            "immagine" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_PICTURES}/$CARTELLA"
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/$CARTELLA"
            "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MUSIC}/$CARTELLA"
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_DOWNLOADS}/$CARTELLA"
        }

        val valori = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, cartella)
            // In sospeso finche' non e' scritto tutto: cosi' la galleria non
            // mostra mai un video mezzo scaricato.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = c.contentResolver.insert(raccolta, valori) ?: return null
        return try {
            val fatto = c.contentResolver.openOutputStream(uri)?.use { versa(it) } ?: false
            if (!fatto) {
                c.contentResolver.delete(uri, null, null)
                null
            } else {
                c.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                Salvato(uri, cartella)
            }
        } catch (e: Exception) {
            Log.w(ETICHETTA, "salvataggio fallito: ${e.message}")
            try {
                c.contentResolver.delete(uri, null, null)
            } catch (e2: Exception) {
                // voce non eliminabile: nessuna azione ulteriore
            }
            null
        }
    }

    private fun aMano(
        c: Context,
        nome: String,
        mime: String,
        versa: (OutputStream) -> Boolean,
    ): Salvato? {
        val radice = when (famiglia(mime)) {
            "immagine" -> Environment.DIRECTORY_PICTURES
            "video" -> Environment.DIRECTORY_MOVIES
            "audio" -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val cartella = File(Environment.getExternalStoragePublicDirectory(radice), CARTELLA)
        if (!cartella.exists() && !cartella.mkdirs()) return null

        var file = File(cartella, nome)
        var contatore = 2
        while (file.exists()) {
            val punto = nome.lastIndexOf('.')
            file = if (punto > 0) {
                File(cartella, nome.substring(0, punto) + " ($contatore)" + nome.substring(punto))
            } else {
                File(cartella, "$nome ($contatore)")
            }
            contatore++
        }

        return try {
            val fatto = FileOutputStream(file).use { versa(it) }
            if (!fatto) {
                file.delete()
                null
            } else {
                MediaScannerConnection.scanFile(c, arrayOf(file.absolutePath), arrayOf(mime), null)
                Salvato(null, "$radice/$CARTELLA")
            }
        } catch (e: Exception) {
            Log.w(ETICHETTA, "salvataggio fallito: ${e.message}")
            file.delete()
            null
        }
    }
}
