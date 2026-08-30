package it.leo.filo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L'unica schermata dell'app.
 *
 * Regola che vale per tutto quello che c'è qui dentro: niente indirizzi, niente
 * porte, niente impronte. Chi guarda deve capire in un secondo con chi è
 * collegato e se sta passando qualcosa.
 *
 * In cima c'e' la vista a costellazione: il dispositivo locale al centro e un
 * filo verso ogni dispositivo abbinato, rappresentato da un monitor o da un
 * telefono secondo il tipo. Il tocco su una figura o sul suo filo seleziona il
 * destinatario, riportato nelle etichette dei pulsanti sottostanti.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(salvato: Bundle?) {
        super.onCreate(salvato)
        Testi.collega(this)
        Notifiche.prepara(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accento,
                    background = Sfondo,
                    surface = Carta,
                    onSurface = Testo,
                )
            ) {
                Surface(color = Sfondo, modifier = Modifier.fillMaxSize()) { Schermata() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Aprire l'app è anche il modo di rimettere in piedi il ponte se il
        // sistema lo aveva chiuso mentre nessuno guardava.
        if (Impostazioni.ponteAcceso(this) || Impostazioni.rispondeAncheLui(this)) {
            PonteService.accendi(this)
        }
    }
}

@Composable
private fun Schermata() {
    val contesto = LocalContext.current
    var quanti by remember { mutableStateOf(Compagni.quanti(contesto)) }

    DisposableEffect(Unit) {
        val ascoltatore = { quanti = Compagni.quanti(contesto) }
        Compagni.ascolta(ascoltatore)
        onDispose { Compagni.smettiDiAscoltare(ascoltatore) }
    }

    val chiediPermesso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        // Due permessi che si escludono a vicenda, uno per epoca.
        //
        // Da Android 13 servono le notifiche: senza, il testo che arriva non
        // avrebbe nessun pulsante "Copia" da premere, ed è l'unico modo che il
        // sistema concede per scrivere negli appunti da fuori.
        //
        // Fino ad Android 9 serve invece il permesso di scrittura, perché lì i
        // file arrivati si scrivono a mano nella memoria condivisa: da Android
        // 10 ci pensa MediaStore e il permesso non esiste più.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(contesto, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                chiediPermesso.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(contesto, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                chiediPermesso.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    var abbinando by remember { mutableStateOf(false) }

    if (quanti == 0 || abbinando) {
        Abbinamento(
            primoInAssoluto = quanti == 0,
            onFatto = {
                abbinando = false
                quanti = Compagni.quanti(contesto)
            },
        )
    } else {
        Casa(onAbbina = { abbinando = true })
    }
}

// --- casa --------------------------------------------------------------------

@Composable
private fun Casa(onAbbina: () -> Unit) {
    val contesto = LocalContext.current
    val giro = rememberCoroutineScope()
    var voci by remember { mutableStateOf(Diario.leggi(contesto)) }
    var compagni by remember { mutableStateOf(Compagni.tutti(contesto)) }
    var scelto by remember { mutableStateOf(Compagni.scelto(contesto)) }
    var menuAperto by remember { mutableStateOf(false) }
    var ponte by remember { mutableStateOf(Impostazioni.ponteAcceso(contesto)) }
    var risponde by remember { mutableStateOf(Impostazioni.rispondeAncheLui(contesto)) }
    var soffio by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val ascoltatore = {
            voci = Diario.leggi(contesto)
            compagni = Compagni.tutti(contesto)
            scelto = Compagni.scelto(contesto)
        }
        Diario.ascolta(ascoltatore)
        Compagni.ascolta(ascoltatore)
        onDispose {
            Diario.smettiDiAscoltare(ascoltatore)
            Compagni.smettiDiAscoltare(ascoltatore)
        }
    }

    val scegliFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { scelti ->
        if (scelti.isNotEmpty()) PonteService.mandaRoba(contesto, scelti, scelto?.id)
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Filo", color = Testo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box {
                Text(
                    "⋯",
                    color = Tenue,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuAperto = true }
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
                DropdownMenu(expanded = menuAperto, onDismissRequest = { menuAperto = false }) {
                    DropdownMenuItem(
                        text = { Text(Testi.t("Pair a device")) },
                        onClick = {
                            menuAperto = false
                            onAbbina()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (ponte) Testi.t("Stay connected: yes") else Testi.t("Stay connected: no")) },
                        onClick = {
                            ponte = !ponte
                            Impostazioni.impostaPonte(contesto, ponte)
                            if (ponte) PonteService.accendi(contesto) else PonteService.spegni(contesto)
                            menuAperto = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (risponde) Testi.t("Answer calls: yes") else Testi.t("Answer calls: no"))
                        },
                        onClick = {
                            risponde = !risponde
                            Impostazioni.impostaRisposta(contesto, risponde)
                            PonteService.sveglia(contesto)
                            menuAperto = false
                        },
                    )
                    HorizontalDivider(color = Carta)
                    DropdownMenuItem(
                        text = { Text(Testi.t("Language"), color = Tenue, fontSize = 12.sp) },
                        onClick = {},
                        enabled = false,
                    )
                    Testi.LINGUE.forEach { (codice, comeSiChiama) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    comeSiChiama,
                                    color = if (Testi.attuale() == codice) Accento else Testo,
                                )
                            },
                            onClick = {
                                Testi.imposta(contesto, codice)
                                menuAperto = false
                            },
                        )
                    }

                    scelto?.let { c ->
                        DropdownMenuItem(
                            text = { Text(Testi.t("Unpair {name}", "name" to c.nome)) },
                            onClick = {
                                menuAperto = false
                                Compagni.dimentica(contesto, c.id)
                                Rete.scordaIndirizzi()
                                compagni = Compagni.tutti(contesto)
                                scelto = Compagni.scelto(contesto)
                            },
                        )
                    }
                }
            }
        }

        Costellazione(
            compagni = compagni.map {
                InVista(it.id, it.nome, it.tipo, it.inLinea || StatoPonte.passaggi.containsKey(it.id))
            },
            scelto = scelto?.id,
            passaggi = StatoPonte.passaggi,
            onTocca = { id ->
                // Toccare una faccia o il suo filo sceglie con chi parlare: da
                // qui in giù tutti i pulsanti si riferiscono a lui.
                Compagni.scegli(contesto, id)
                scelto = Compagni.scelto(contesto)
                PonteService.sveglia(contesto)
            },
        )

        val inCorso = StatoPonte.passaggi.entries.firstOrNull()
        val titolo: String
        val sotto: String
        if (inCorso != null) {
            val p = inCorso.value
            val conChi = compagni.firstOrNull { it.id == inCorso.key }?.nome ?: ""
            titolo = (if (p.versoIlCompagno) Testi.t("Sending ") else Testi.t("Receiving ")) + p.nome
            sotto = (
                if (p.totale > 0)
                    Testi.t("{done} of {total}", "done" to misura(p.fatti), "total" to misura(p.totale))
                else misura(p.fatti)
            ) +
                (if (conChi.isNotEmpty()) "  ·  $conChi" else "")
        } else {
            titolo = scelto?.nome ?: Testi.t("No device")
            sotto = when {
                scelto == null -> Testi.t("Pair one to get started")
                StatoPonte.collegato && StatoPonte.viaCavo -> Testi.t("connected over the USB cable")
                StatoPonte.collegato -> Testi.t("connected")
                else -> Testi.t("not answering — it must be on the same network")
            } + (if (compagni.size > 1) Testi.t("  ·  tap another one to talk to it") else "")
        }

        Text(
            titolo,
            color = Testo,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (soffio.isNotEmpty()) soffio else sotto,
            color = if (soffio.isNotEmpty()) Accento else Tenue,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (soffio.isNotEmpty()) {
            LaunchedEffect(soffio) {
                delay(2500)
                soffio = ""
            }
        }

        Spacer(Modifier.height(26.dp))

        val aChi = scelto?.nome ?: ""
        Grosso(
            if (aChi.isEmpty()) Testi.t("Send clipboard")
            else Testi.t("Send clipboard to {name}", "name" to aChi),
            principale = true,
        ) {
            contesto.startActivity(
                Intent(contesto, AppuntiActivity::class.java).apply {
                    action = AppuntiActivity.AZIONE_LEGGI
                    scelto?.let { putExtra(AppuntiActivity.EXTRA_A, it.id) }
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Grosso(
            if (aChi.isEmpty()) Testi.t("Send photos, videos or files")
            else Testi.t("Send files to {name}", "name" to aChi)
        ) {
            scegliFile.launch("*/*")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (aChi.isEmpty()) Testi.t("Ask for clipboard")
            else Testi.t("Ask {name} for the clipboard", "name" to aChi),
            color = Tenue,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                    val c = scelto ?: return@clickable
                    giro.launch {
                        val riuscito = withContext(Dispatchers.IO) { Rete.chiediAppunti(contesto, c) }
                        soffio = if (riuscito) Testi.t("Asked {name}", "name" to c.nome)
                        else Testi.t("{name} is not answering", "name" to c.nome)
                    }
                }
                .padding(vertical = 10.dp),
        )

        Spacer(Modifier.height(22.dp))

        Box(Modifier.weight(1f)) {
            if (voci.isEmpty()) {
                Text(
                    Testi.t("What goes through shows up here."),
                    color = Tenue.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn { items(voci) { v -> RigaDiario(v) } }
            }
        }
    }
}

@Composable
private fun RigaDiario(v: Diario.Voce) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (v.verso) "↑" else "↓", color = if (v.verso) Accento else Verde, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.testo, color = TestoTenue, fontSize = 14.sp, maxLines = 1)
            if (v.dettaglio.isNotEmpty()) {
                Text(v.dettaglio, color = Tenue, fontSize = 11.sp, maxLines = 1)
            }
        }
        Text(quando(v.quando), color = Tenue, fontSize = 10.sp)
    }
}

private fun quando(millis: Long): String {
    val passati = System.currentTimeMillis() - millis
    return when {
        passati < 60_000 -> "adesso"
        passati < 3_600_000 -> "${passati / 60_000} min fa"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }
}

// --- abbinamento -------------------------------------------------------------

@Composable
private fun Abbinamento(primoInAssoluto: Boolean, onFatto: () -> Unit) {
    val contesto = LocalContext.current
    val giro = rememberCoroutineScope()

    var codice by remember { mutableStateOf(Servitore.apriAbbinamento()) }
    var restano by remember { mutableStateOf(Servitore.secondiRimasti()) }
    var cercando by remember { mutableStateOf(false) }
    var trovati by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var scelto by remember { mutableStateOf<JSONObject?>(null) }
    var scritto by remember { mutableStateOf("") }
    var messaggio by remember { mutableStateOf("") }

    // Perché l'altro possa scrivere questo codice, la porta dev'essere aperta.
    LaunchedEffect(Unit) {
        Impostazioni.impostaRisposta(contesto, true)
        PonteService.sveglia(contesto)
    }

    // Il conto alla rovescia, e l'occhio su chi arriva: se è l'altro a scrivere
    // il nostro codice, ce ne accorgiamo perché il compagno compare da solo.
    val quantiPrima = remember { Compagni.quanti(contesto) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            restano = Servitore.secondiRimasti()
            if (Compagni.quanti(contesto) > quantiPrima) {
                PonteService.sveglia(contesto)
                onFatto()
                return@LaunchedEffect
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(30.dp))
        Text(
            if (primoInAssoluto) Testi.t("Let's connect Filo") else Testi.t("Pair a device"),
            color = Testo,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        val pc = scelto
        if (pc == null) {
            Scheda {
                Text(
                    Testi.t("On the other device open Filo, tap Search and type:"),
                    color = Tenue, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (restano > 0) codice.toCharArray().joinToString(" ") else Testi.t("expired"),
                    color = if (restano > 0) Accento else Tenue,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (restano > 0)
                        Testi.t(
                            "expires in {minutes}:{seconds}",
                            "minutes" to restano / 60,
                            "seconds" to "%02d".format(restano % 60),
                        )
                    else Testi.t("tap for a new code"),
                    color = Tenue, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            codice = Servitore.apriAbbinamento()
                            restano = Servitore.secondiRimasti()
                        },
                )
                if (!StatoPonte.rispondeAncheLui) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        Testi.t("This phone cannot open the port: use the opposite direction, ") +
                            Testi.t("search for the other device yourself."),
                        color = Ambra, fontSize = 11.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                Testi.t("or"), color = Tenue, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Grosso(
                if (cercando) Testi.t("Searching…") else Testi.t("Search for a device"),
                principale = true,
            ) {
                if (cercando) return@Grosso
                cercando = true
                messaggio = ""
                giro.launch {
                    val visti = withContext(Dispatchers.IO) { Scoperta.cerca(contesto, 2_500) }
                    val gia = Compagni.tutti(contesto).map { it.id }.toSet()
                    trovati = visti.filter { it.optString("id") !in gia }
                    cercando = false
                    if (trovati.isEmpty()) {
                        messaggio = Testi.t("Nobody in sight. You must be on the same Wi-Fi network, ") +
                            Testi.t("and Filo must be open on the other device.")
                    }
                }
            }

            trovati.forEach { carta ->
                Spacer(Modifier.height(10.dp))
                Scheda(onClick = { scelto = carta }) {
                    Text(carta.optString("nome", "?"), color = Testo, fontSize = 17.sp)
                    Text(
                        if (carta.optString("tipo") == "pc") Testi.t("computer") else Testi.t("phone"),
                        color = Tenue, fontSize = 12.sp,
                    )
                }
            }
        } else {
            Text(
                pc.optString("nome", "?"),
                color = Testo, fontSize = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                Testi.t("Type the six digits it shows."),
                color = Tenue, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = scritto,
                onValueChange = { if (it.length <= 6) scritto = it.filter { c -> c.isDigit() } },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 30.sp,
                    letterSpacing = 12.sp,
                    textAlign = TextAlign.Center,
                    color = Testo,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = coloriCampo(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Grosso(Testi.t("Pair"), principale = true) {
                if (scritto.length != 6) {
                    messaggio = Testi.t("The code has six digits.")
                    return@Grosso
                }
                messaggio = ""
                giro.launch {
                    val compagno = withContext(Dispatchers.IO) {
                        Rete.abbina(contesto, pc, scritto)
                    }
                    if (compagno == null) {
                        messaggio = Testi.t("Code rejected. On the other device it expires after three ") +
                            Testi.t("minutes: bring it up again and try once more.")
                    } else {
                        PonteService.sveglia(contesto)
                        onFatto()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Sottile(Testi.t("Choose another one")) {
                scelto = null
                scritto = ""
            }
        }

        if (!primoInAssoluto) {
            Spacer(Modifier.height(16.dp))
            Sottile(Testi.t("Never mind")) { onFatto() }
        }

        if (messaggio.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(messaggio, color = Ambra, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

// --- pezzi comuni ------------------------------------------------------------

@Composable
private fun Grosso(testo: String, principale: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (principale) Accento else Carta,
            contentColor = if (principale) androidx.compose.ui.graphics.Color.White else TestoTenue,
        ),
    ) {
        Text(testo, fontSize = 15.sp)
    }
}

@Composable
private fun Sottile(testo: String, onClick: () -> Unit) {
    Text(
        testo,
        color = Tenue,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun Scheda(onClick: (() -> Unit)? = null, dentro: @Composable () -> Unit) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(Carta)
    Column((if (onClick != null) base.clickable { onClick() } else base).padding(18.dp)) {
        dentro()
    }
}

@Composable
private fun coloriCampo() = TextFieldDefaults.colors(
    focusedContainerColor = Carta,
    unfocusedContainerColor = Carta,
    focusedTextColor = Testo,
    unfocusedTextColor = Testo,
    focusedIndicatorColor = Accento,
    unfocusedIndicatorColor = CartaViva,
    cursorColor = Accento,
    focusedLabelColor = Tenue,
    unfocusedLabelColor = Tenue,
)
