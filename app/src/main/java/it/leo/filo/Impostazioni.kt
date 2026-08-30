package it.leo.filo

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferenze dell'applicazione.
 *
 * I dispositivi abbinati sono in [Compagni] e l'identita' locale in [Identita];
 * qui restano le due opzioni di comportamento.
 */
object Impostazioni {

    private const val FILE = "filo"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Se il collegamento deve restare aperto anche con l'app chiusa. */
    fun ponteAcceso(c: Context) = prefs(c).getBoolean("ponte", true)

    fun impostaPonte(c: Context, acceso: Boolean) =
        prefs(c).edit().putBoolean("ponte", acceso).apply()

    /**
     * Se il telefono deve anche *rispondere* alle chiamate, e non solo farle.
     *
     * Serve per parlare con un altro telefono e per farsi trovare dal PC. Si
     * può spegnere: chi usa Filo solo verso il PC non ha bisogno di tenere una
     * porta aperta.
     */
    fun rispondeAncheLui(c: Context) = prefs(c).getBoolean("servitore", true)

    fun impostaRisposta(c: Context, acceso: Boolean) =
        prefs(c).edit().putBoolean("servitore", acceso).apply()

    /** La lingua dell'interfaccia; vedi [Testi]. */
    fun lingua(c: Context): String = prefs(c).getString("lingua", Testi.PREDEFINITA)
        ?: Testi.PREDEFINITA

    fun impostaLingua(c: Context, codice: String) =
        prefs(c).edit().putString("lingua", codice).apply()
}
