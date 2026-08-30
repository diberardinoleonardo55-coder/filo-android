package it.leo.filo

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors

/**
 * Lo stato del ponte, in chiaro per la UI.
 *
 * È scritto dai fili del servizio e letto da Compose: [mutableStateOf] regge le
 * scritture da qualunque thread e sveglia la ricomposizione da solo.
 */
object StatoPonte {
    var testo by mutableStateOf("spento")
    var collegato by mutableStateOf(false)
    var viaCavo by mutableStateOf(false)
    var rispondeAncheLui by mutableStateOf(false)

    /**
     * Cosa sta passando, **per ogni compagno**: è questo che muove i fili della
     * costellazione. Uno per filo, così due trasferimenti verso due compagni
     * diversi si vedono insieme invece di darsi il cambio.
     */
    class Passaggio(val nome: String, val versoIlCompagno: Boolean, val totale: Long) {
        var fatti by mutableStateOf(0L)

        val quota: Float
            get() = if (totale > 0) (fatti.toFloat() / totale).coerceIn(0f, 1f) else 0f
    }

    val passaggi = mutableStateMapOf<String, Passaggio>()

    fun iniziaPassaggio(a: String, nome: String, versoIlCompagno: Boolean, totale: Long) {
        passaggi[a] = Passaggio(nome, versoIlCompagno, totale)
    }

    fun avanza(a: String, fatti: Long) {
        passaggi[a]?.fatti = fatti
    }

    /**
     * La voce resta per circa un secondo dopo la fine del trasferimento:
     * altrimenti i file piccoli comparirebbero e sparirebbero nello stesso
     * fotogramma dell'animazione.
     */
    fun finePassaggio(a: String) {
        Handler(Looper.getMainLooper()).postDelayed({ passaggi.remove(a) }, 900)
    }

    /** Quello da raccontare a parole, se ce n'è più d'uno si prende il primo. */
    fun primoInCorso(): Pair<String, Passaggio>? =
        passaggi.entries.firstOrNull()?.let { it.key to it.value }
}

/**
 * Il servizio che tiene i fili tesi: uno per compagno, più la porta aperta.
 *
 * Due mestieri insieme, ed è quello che permette al telefono di parlare con un
 * altro telefono:
 *
 * - **chiama**: per ogni compagno un filo di esecuzione tiene una `/eventi`
 *   appesa fino a 25 secondi, così quello che il compagno mette in coda arriva
 *   subito;
 * - **risponde**: [Servitore] tiene aperta la porta e [Scoperta] fa trovare il
 *   telefono sulla rete, esattamente come fa il PC.
 *
 * Le richieste usano sempre `dopo=0`: le voci gia' ritirate vengono rimosse
 * dal mittente alla conferma, quindi non serve mantenere un contatore e il
 * riavvio di un dispositivo non comporta perdite.
 */
class PonteService : Service() {

    companion object {
        const val AZIONE_MANDA = "it.leo.filo.MANDA"
        const val AZIONE_FERMA = "it.leo.filo.FERMA"
        const val AZIONE_SVEGLIA = "it.leo.filo.SVEGLIA"
        const val EXTRA_A = "a"

        fun accendi(c: Context) {
            // Anche senza compagni: senza servizio la porta resta chiusa, e con
            // la porta chiusa nessuno puo' abbinarsi a questo telefono.
            manda(c, Intent(c, PonteService::class.java))
        }

        fun spegni(c: Context) {
            c.startService(Intent(c, PonteService::class.java).setAction(AZIONE_FERMA))
        }

        /** Da chiamare dopo un abbinamento o un cambio di compagno scelto. */
        fun sveglia(c: Context) {
            manda(c, Intent(c, PonteService::class.java).setAction(AZIONE_SVEGLIA))
        }

        private fun manda(c: Context, i: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Exception) {
                // Da Android 12 un servizio in primo piano non si puo' avviare
                // da fermo in sottofondo. Non e' un guasto: si riaccendera'
                // quando l'utente aprira' l'app.
                Log.w("Filo", "il ponte non e' partito adesso: ${e.message}")
            }
        }

        /**
         * Manda al compagno indicato (o a quello scelto) i contenuti indicati.
         *
         * Gli Uri vanno nella ClipData e non solo negli extra: il permesso di
         * lettura concesso da chi condivide segue `intent.data` e la ClipData,
         * **non** gli extra. Messi solo negli extra, l'app riceve un Uri che
         * non ha il diritto di aprire e il file arriva vuoto.
         */
        fun mandaRoba(c: Context, roba: List<Uri>, a: String? = null) {
            if (roba.isEmpty()) return
            val i = Intent(c, PonteService::class.java).setAction(AZIONE_MANDA)
            val clip = ClipData.newUri(c.contentResolver, "filo", roba[0])
            roba.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a?.let { i.putExtra(EXTRA_A, it) }
            manda(c, i)
        }

        fun mandaTesto(c: Context, testo: String, a: String? = null) {
            val i = Intent(c, PonteService::class.java).setAction(AZIONE_MANDA)
            i.putExtra(Intent.EXTRA_TEXT, testo)
            a?.let { i.putExtra(EXTRA_A, it) }
            manda(c, i)
        }
    }

    @Volatile
    private var vivo = true
    private val fili = HashMap<String, Thread>()
    private val spedizioni = Executors.newSingleThreadExecutor()
    private var servitore: Servitore? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        Testi.collega(this)
        super.onCreate()
        Notifiche.prepara(this)
        startForeground(Notifiche.ID_PONTE, Notifiche.ponte(this, "in ascolto"))
        apriLaPorta()
        controllaFili()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        when (intent?.action) {
            AZIONE_FERMA -> {
                vivo = false
                servitore?.ferma()
                Scoperta.smettiDiRispondere()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            AZIONE_SVEGLIA -> {
                apriLaPorta()
                controllaFili()
            }

            AZIONE_MANDA -> {
                val a = intent.getStringExtra(EXTRA_A)
                    ?: Compagni.scelto(this)?.id
                    ?: return START_STICKY
                val testo = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!testo.isNullOrEmpty()) spedisciTesto(a, testo)
                val clip = intent.clipData
                if (clip != null) {
                    val roba = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    if (roba.isNotEmpty()) spedisci(a, roba)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        vivo = false
        servitore?.ferma()
        Scoperta.smettiDiRispondere()
        fili.values.forEach { it.interrupt() }
        spedizioni.shutdownNow()
        StatoPonte.testo = "spento"
        StatoPonte.collegato = false
        StatoPonte.rispondeAncheLui = false
        super.onDestroy()
    }

    // --- il lato che risponde ------------------------------------------------

    private fun apriLaPorta() {
        if (!Impostazioni.rispondeAncheLui(this)) {
            servitore?.ferma()
            servitore = null
            Scoperta.smettiDiRispondere()
            StatoPonte.rispondeAncheLui = false
            return
        }
        if (servitore == null) {
            servitore = Servitore(applicationContext).also { it.avvia() }
            Scoperta.rispondi(applicationContext)
        }
        // Il servitore ci mette un istante a legarsi alla porta.
        Handler(Looper.getMainLooper()).postDelayed(
            { StatoPonte.rispondeAncheLui = servitore?.acceso == true }, 800
        )
    }

    // --- il lato che chiama --------------------------------------------------

    private fun controllaFili() {
        val vivi = fili.filterValues { it.isAlive }.keys
        for (compagno in Compagni.tutti(this)) {
            if (compagno.id !in vivi) {
                val t = Thread({ giro(compagno.id) }, "filo-${compagno.nome}")
                fili[compagno.id] = t
                t.start()
            }
        }
    }

    private fun giro(idCompagno: String) {
        while (vivo) {
            val compagno = Compagni.trova(this, idCompagno) ?: return  // scollegato
            val voci = try {
                Rete.eventi(this, compagno)
            } catch (e: Exception) {
                null
            }
            if (voci == null) {
                aggiorna(compagno, collegato = false)
                if (!dormi(6_000)) return
                continue
            }
            compagno.saluta()
            Compagni.aggiorna(this, compagno)
            aggiorna(compagno, collegato = true)

            var storto = false
            for (v in voci) {
                if (!vivo) return
                if (!ritira(compagno, v)) storto = true
            }
            if (storto && !dormi(5_000)) return
        }
    }

    private fun ritira(compagno: Compagni.Compagno, voce: Rete.Voce): Boolean = when (voce.tipo) {
        "testo" -> {
            val testo = voce.testo ?: ""
            Diario.annota(this, false, Testi.t("Text from {peer}", "peer" to compagno.nome), testo.take(90))
            if (voce.richiesto) {
                // L'hai chiesto tu un istante fa dall'app: se l'app è ancora
                // davanti, questa apre il ponte invisibile e copia subito. Se
                // il sistema la blocca resta la notifica, che c'è comunque.
                try {
                    startActivity(
                        Intent(this, AppuntiActivity::class.java).apply {
                            action = AppuntiActivity.AZIONE_SCRIVI
                            putExtra(AppuntiActivity.EXTRA_TESTO, testo)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (e: Exception) {
                    Log.w("Filo", "copia immediata non permessa: ${e.message}")
                }
            }
            Notifiche.testoArrivato(this, testo)
            Rete.conferma(this, compagno, voce.id)
            true
        }

        "richiesta" -> {
            Notifiche.richiestaAppunti(this, compagno.nome, compagno.id)
            Rete.conferma(this, compagno, voce.id)
            true
        }

        "file" -> scaricaFile(compagno, voce)

        else -> {
            Rete.conferma(this, compagno, voce.id)
            true
        }
    }

    private fun scaricaFile(compagno: Compagni.Compagno, voce: Rete.Voce): Boolean {
        StatoPonte.iniziaPassaggio(compagno.id, voce.nome, versoIlCompagno = false, totale = voce.dimensione)
        val salvato = Salvataggio.salva(this, voce.nome, voce.mime) { fuori ->
            Rete.scarica(this, compagno, voce, fuori) { fatti -> StatoPonte.avanza(compagno.id, fatti) }
        }
        StatoPonte.finePassaggio(compagno.id)
        return if (salvato == null) {
            Diario.annota(this, false, Testi.t("Cannot save {name}", "name" to voce.nome))
            false
        } else {
            Diario.annota(
                this,
                false,
                Testi.t("{name} ← {peer}", "name" to voce.nome, "peer" to compagno.nome),
                salvato.dove,
            )
            Notifiche.fileArrivato(this, voce.nome, salvato.dove, salvato.uri, voce.mime)
            Rete.conferma(this, compagno, voce.id)
            true
        }
    }

    // --- mandare -------------------------------------------------------------

    private fun spedisciTesto(a: String, testo: String) {
        spedizioni.execute {
            val compagno = Compagni.trova(this, a) ?: return@execute
            // Prima si prova a spingerlo: se risponde adesso, arriva adesso.
            // Se non risponde resta in coda e se lo prenderà quando torna.
            if (Rete.spingiTesto(this, compagno, testo)) {
                Diario.annota(this, true, Testi.t("Text → {peer}", "peer" to compagno.nome), testo.take(90))
            } else {
                CodaUscita.mettiTesto(a, testo)
                Diario.annota(this, true, Testi.t("Text queued for {peer}", "peer" to compagno.nome), Testi.t("not answering"))
            }
        }
    }

    private fun spedisci(a: String, roba: List<Uri>) {
        spedizioni.execute {
            val compagno = Compagni.trova(this, a) ?: return@execute
            for (uri in roba) {
                val (nome, dimensione) = datiDi(uri)
                StatoPonte.iniziaPassaggio(a, nome, versoIlCompagno = true, totale = dimensione)
                val riuscito = try {
                    contentResolver.openInputStream(uri)?.use { dentro ->
                        Rete.spingiFile(this, compagno, nome, dimensione, dentro) { fatti ->
                            StatoPonte.avanza(a, fatti)
                        }
                    } ?: false
                } catch (e: Exception) {
                    Log.w("Filo", "non riesco a leggere $uri: ${e.message}")
                    false
                }
                StatoPonte.finePassaggio(a)
                if (riuscito) {
                    Diario.annota(
                        this,
                        true,
                        Testi.t("{name} → {peer}", "name" to nome, "peer" to compagno.nome),
                        misura(dimensione),
                    )
                } else {
                    // Non risponde: si mette in coda. Attenzione, il permesso su
                    // questo Uri vive quanto il servizio: se l'app viene chiusa
                    // prima che il compagno passi a ritirare, la voce non si
                    // potrà più aprire. È il prezzo di non copiare i file.
                    CodaUscita.mettiFile(a, nome, tipoDi(uri, nome), dimensione, uri)
                    Diario.annota(this, true, Testi.t("{name} queued for {peer}", "name" to nome, "peer" to compagno.nome),
                        Testi.t("not answering"),
                    )
                }
            }
        }
    }

    private fun tipoDi(uri: Uri, nome: String): String {
        contentResolver.getType(uri)?.let { return it }
        val estensione = nome.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(estensione)
            ?: "application/octet-stream"
    }

    private fun datiDi(uri: Uri): Pair<String, Long> {
        var nome = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var dimensione = 0L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursore ->
                if (cursore.moveToFirst()) {
                    if (!cursore.isNull(0)) nome = cursore.getString(0)
                    if (!cursore.isNull(1)) dimensione = cursore.getLong(1)
                }
            }
        } catch (e: Exception) {
            // Un file:// non risponde a quelle colonne: si guarda il file.
            if (uri.scheme == "file") {
                uri.path?.let { p ->
                    val f = File(p)
                    nome = f.name
                    dimensione = f.length()
                }
            }
        }
        return nome to dimensione
    }

    // --- utilità -------------------------------------------------------------

    private fun aggiorna(compagno: Compagni.Compagno, collegato: Boolean) {
        val scelto = Compagni.scelto(this)
        if (scelto == null || scelto.id != compagno.id) return   // la UI guarda quello scelto
        val stato = when {
            !collegato -> Testi.t("cannot find {peer}", "peer" to compagno.nome)
            Rete.passaDalCavo(compagno.id) -> Testi.t("connected to {peer} over the cable", "peer" to compagno.nome)
            else -> "collegato a ${compagno.nome}"
        }
        if (StatoPonte.testo != stato) {
            StatoPonte.testo = stato
            Notifiche.aggiornaPonte(this, stato)
        }
        StatoPonte.collegato = collegato
        StatoPonte.viaCavo = Rete.passaDalCavo(compagno.id)
    }

    private fun dormi(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

fun misura(byte: Long): String = when {
    byte <= 0 -> ""
    byte < 1024 -> "$byte B"
    byte < 1024 * 1024 -> "${byte / 1024} kB"
    else -> String.format("%.1f MB", byte / 1048576.0)
}
