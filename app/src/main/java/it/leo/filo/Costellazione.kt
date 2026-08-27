package it.leo.filo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Vista a costellazione: il dispositivo locale al centro, i compagni intorno.
 *
 * Da ogni compagno parte un filo verso il centro; lo stato del filo indica la
 * condizione del collegamento e, durante un trasferimento, l'avanzamento e la
 * direzione. I trasferimenti sono indipendenti per dispositivo, quindi piu'
 * fili possono essere attivi insieme.
 *
 * Un tocco su una figura o sul suo filo seleziona il dispositivo.
 *
 * Il tempo dell'animazione avanza in `withFrameNanos` invece che con una
 * InfiniteTransition, cosi' la velocita' puo' variare senza far ripartire
 * l'animazione.
 */

private const val PEZZI = 34
private const val QUANTI_PUNTINI = 6

data class InVista(
    val id: String,
    val nome: String,
    val tipo: String,
    val inLinea: Boolean,
)

@Composable
fun Costellazione(
    compagni: List<InVista>,
    scelto: String?,
    passaggi: Map<String, StatoPonte.Passaggio>,
    onTocca: (String) -> Unit,
    modifier: Modifier = Modifier,
    altezza: Int = 250,
) {
    var fase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var ultimo = 0L
        while (true) {
            withFrameNanos { adesso ->
                if (ultimo != 0L) {
                    val dt = ((adesso - ultimo) / 1_000_000_000.0).toFloat().coerceAtMost(0.1f)
                    fase = (fase + dt * 0.3f) % 1f
                }
                ultimo = adesso
            }
        }
    }

    val misuratore = rememberTextMeasurer()
    var posti by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var centro by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(altezza.dp)
            .pointerInput(compagni.size) {
                detectTapGestures { punto ->
                    chiToccato(punto, centro, posti)?.let(onTocca)
                }
            }
    ) {
        val io = Offset(size.width / 2, size.height * (if (compagni.size == 1) 0.42f else 0.44f))
        val quanti = compagni.size
        val nuovi = HashMap<String, Offset>()
        if (quanti == 1) {
            nuovi[compagni[0].id] = Offset(size.width * 0.74f, size.height * 0.42f)
        } else if (quanti > 1) {
            val rx = size.width * 0.33f
            val ry = size.height * 0.32f
            // Le posizioni partono mezzo passo dopo la verticale: nessun compagno
            // cade sopra o sotto il centro, dove l'etichetta si sovrapporrebbe.
            val primo = -PI.toFloat() / 2 + PI.toFloat() / quanti
            compagni.forEachIndexed { i, c ->
                val angolo = primo + 2 * PI.toFloat() * i / quanti
                nuovi[c.id] = Offset(io.x + rx * cos(angolo), io.y + ry * sin(angolo))
            }
        }
        val ioVero = if (quanti == 1) Offset(size.width * 0.26f, size.height * 0.42f) else io
        posti = nuovi
        centro = ioVero

        val spenta = lerp(Carta, Tenue, 0.3f)
        compagni.forEach { c ->
            val posto = nuovi[c.id] ?: return@forEach
            val passaggio = passaggi[c.id]
            val passa = passaggio != null
            val quota = passaggio?.quota ?: 0f
            val versoIlCompagno = passaggio?.versoIlCompagno ?: true
            val acceso = if (c.inLinea) 1f else 0f
            val eScelto = c.id == scelto

            val corda = lerp(spenta, Accento, acceso * (if (eScelto) 1f else 0.45f))
            drawPath(
                tratto(ioVero, posto, 0f, 1f),
                if (passa) spenta else corda,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )

            if (passa && quota > 0f) {
                val pieno =
                    if (versoIlCompagno) tratto(ioVero, posto, 0f, quota)
                    else tratto(ioVero, posto, 1f - quota, 1f)
                drawPath(pieno, lerp(Sfondo, Accento, 0.3f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                drawPath(pieno, lerp(Accento, AccentoVivo, 0.5f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(pieno, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }

            if (c.inLinea || passa) {
                val quantiPuntini = if (passa) QUANTI_PUNTINI else 1
                val velocita = if (passa) 2.4f else 1f
                for (i in 0 until quantiPuntini) {
                    var t = (fase * velocita + i.toFloat() / quantiPuntini) % 1f
                    if (!versoIlCompagno) t = 1f - t
                    val o = puntoDelFilo(ioVero, posto, t)
                    val raggio = (if (passa) 3.5f else 2.5f).dp.toPx()
                    drawCircle(lerp(Sfondo, Accento, 0.45f), raggio * 2f, o)
                    drawCircle(if (passa) Color.White else AccentoVivo, raggio, o)
                }
            }

            if (eScelto) {
                // Alone dietro alla figura selezionata: indica il destinatario corrente.
                drawCircle(lerp(Sfondo, Accento, 0.18f), 30.dp.toPx(), posto)
            }
            val inchiostro = lerp(
                lerp(Carta, Tenue, 0.6f), Testo, acceso * 0.75f + (if (eScelto) 0.25f else 0f)
            )
            faccia(c.tipo, posto, inchiostro)

            val misurato = misuratore.measure(
                c.nome.take(14),
                TextStyle(fontSize = 11.sp, color = if (eScelto) Testo else TestoTenue),
            )
            drawText(
                misurato,
                topLeft = Offset(posto.x - misurato.size.width / 2, posto.y + 26.dp.toPx()),
            )
        }

        faccia("telefono", ioVero, Testo)
    }
}

/** Individua l'elemento toccato: prima le figure, poi i fili. */
private fun chiToccato(punto: Offset, io: Offset, posti: Map<String, Offset>): String? {
    posti.forEach { (id, posto) ->
        if (hypot(punto.x - posto.x, punto.y - posto.y) < 90f) return id
    }
    posti.forEach { (id, posto) ->
        for (i in 0..PEZZI) {
            val p = puntoDelFilo(io, posto, i.toFloat() / PEZZI)
            if (hypot(punto.x - p.x, punto.y - p.y) < 34f) return id
        }
    }
    return null
}

/** Punto della curva di Bezier con curvatura perpendicolare alla congiungente. */
private fun puntoDelFilo(da: Offset, a: Offset, t: Float): Offset {
    val dx = a.x - da.x
    val dy = a.y - da.y
    val lunghezza = hypot(dx, dy).coerceAtLeast(1f)
    val px = -dy / lunghezza
    val py = dx / lunghezza
    val pancia = minOf(70f, lunghezza * 0.16f)
    val c1 = Offset(da.x + dx * 0.3f + px * pancia, da.y + dy * 0.3f + py * pancia)
    val c2 = Offset(da.x + dx * 0.7f + px * pancia, da.y + dy * 0.7f + py * pancia)
    val u = 1 - t
    val b0 = u * u * u
    val b1 = 3 * u * u * t
    val b2 = 3 * u * t * t
    val b3 = t * t * t
    return Offset(
        b0 * da.x + b1 * c1.x + b2 * c2.x + b3 * a.x,
        b0 * da.y + b1 * c1.y + b2 * c2.y + b3 * a.y,
    )
}

private fun DrawScope.tratto(da: Offset, a: Offset, dal: Float, al: Float): Path {
    val p = Path()
    var primo = true
    for (i in 0..PEZZI) {
        val t = i.toFloat() / PEZZI
        if (t in dal..al) {
            val o = puntoDelFilo(da, a, t)
            if (primo) {
                p.moveTo(o.x, o.y)
                primo = false
            } else {
                p.lineTo(o.x, o.y)
            }
        }
    }
    return p
}

private fun DrawScope.faccia(tipo: String, dove: Offset, colore: Color) {
    if (tipo == "pc") {
        val l = 40.dp.toPx()
        val a = 28.dp.toPx()
        drawRoundRect(
            color = colore,
            topLeft = Offset(dove.x - l / 2, dove.y - a / 2 - 4.dp.toPx()),
            size = Size(l, a),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(2.dp.toPx()),
        )
        drawLine(
            colore,
            Offset(dove.x, dove.y + a / 2 - 4.dp.toPx()),
            Offset(dove.x, dove.y + a / 2 + 3.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            colore,
            Offset(dove.x - 8.dp.toPx(), dove.y + a / 2 + 3.dp.toPx()),
            Offset(dove.x + 8.dp.toPx(), dove.y + a / 2 + 3.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    } else {
        val l = 19.dp.toPx()
        val a = 33.dp.toPx()
        drawRoundRect(
            color = colore,
            topLeft = Offset(dove.x - l / 2, dove.y - a / 2),
            size = Size(l, a),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(2.dp.toPx()),
        )
        drawLine(
            colore,
            Offset(dove.x - 3.5f.dp.toPx(), dove.y + a / 2 - 5.dp.toPx()),
            Offset(dove.x + 3.5f.dp.toPx(), dove.y + a / 2 - 5.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
