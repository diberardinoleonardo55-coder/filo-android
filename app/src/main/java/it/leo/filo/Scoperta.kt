package it.leo.filo

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Scoperta dei dispositivi sulla rete locale via broadcast UDP.
 *
 * [rispondi] mantiene un socket in ascolto sulla porta [PORTA] e risponde con
 * la carta d'identita' a chi invia "FILO?"; [cerca] invia la richiesta e
 * raccoglie le risposte.
 *
 * Il broadcast diretto non richiede configurazione ne' servizi esterni e
 * funziona su una singola rete di livello 2, comprese le reti Wi-Fi che
 * alternano 2,4 e 5 GHz.
 */
object Scoperta {

    private const val ETICHETTA = "Filo"
    const val PORTA = 8788
    private val DOMANDA = "FILO?".toByteArray()

    @Volatile
    private var orecchio: DatagramSocket? = null

    // --- farsi trovare -------------------------------------------------------

    fun rispondi(c: Context) {
        if (orecchio != null) return
        Thread({
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(PORTA))
                }
                orecchio = s
                val buffer = ByteArray(1024)
                while (!s.isClosed) {
                    val pacco = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(pacco)
                    } catch (e: Exception) {
                        break
                    }
                    val domanda = String(pacco.data, 0, pacco.length).trim()
                    if (!domanda.startsWith("FILO?")) continue
                    val risposta = Servitore.cartaDiIdentita(c).toString().toByteArray()
                    try {
                        s.send(DatagramPacket(risposta, risposta.size, pacco.address, pacco.port))
                    } catch (e: Exception) {
                        // un pacchetto perso non e' un guasto
                    }
                }
            } catch (e: Exception) {
                Log.w(ETICHETTA, "non riesco a farmi trovare: ${e.message}")
            } finally {
                orecchio = null
            }
        }, "filo-scoperta").start()
    }

    fun smettiDiRispondere() {
        try {
            orecchio?.close()
        } catch (e: Exception) {
            // sta chiudendo comunque
        }
        orecchio = null
    }

    // --- cercare gli altri ---------------------------------------------------

    private fun indirizziDiBroadcast(c: Context): List<InetAddress> {
        val fuori = mutableListOf<InetAddress>()
        try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val rete = cm?.activeNetwork
            val proprieta = rete?.let { cm.getLinkProperties(it) }
            proprieta?.linkAddresses?.forEach { la ->
                val ip = la.address
                if (ip is Inet4Address && la.prefixLength in 1..31) {
                    val byte = ip.address
                    var numero = 0
                    for (b in byte) numero = (numero shl 8) or (b.toInt() and 0xFF)
                    val maschera = (-1 shl (32 - la.prefixLength))
                    val broadcast = numero or maschera.inv()
                    fuori.add(
                        InetAddress.getByAddress(
                            byteArrayOf(
                                (broadcast ushr 24).toByte(),
                                (broadcast ushr 16).toByte(),
                                (broadcast ushr 8).toByte(),
                                broadcast.toByte(),
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(ETICHETTA, "niente indirizzo di broadcast: ${e.message}")
        }
        // Il 255.255.255.255 non basta da solo: diverse versioni di Android e
        // diversi router lo lasciano cadere. Si mandano tutti e due.
        fuori.add(InetAddress.getByName("255.255.255.255"))
        return fuori
    }

    /** Grida "chi c'è?" e raccoglie chi risponde, tolto sé stesso. */
    fun cerca(c: Context, quantoAspetto: Long = 2_500): List<JSONObject> {
        val mio = Identita.id(c)
        val trovati = LinkedHashMap<String, JSONObject>()
        var presa: DatagramSocket? = null
        try {
            val s = DatagramSocket()
            presa = s
            s.broadcast = true
            s.soTimeout = 350
            val fine = System.currentTimeMillis() + quantoAspetto
            var prossimoGrido = 0L
            val destinazioni = indirizziDiBroadcast(c)
            while (System.currentTimeMillis() < fine) {
                if (System.currentTimeMillis() > prossimoGrido) {
                    prossimoGrido = System.currentTimeMillis() + 600
                    destinazioni.forEach {
                        try {
                            s.send(DatagramPacket(DOMANDA, DOMANDA.size, it, PORTA))
                        } catch (e: Exception) {
                            // una scheda che non fa broadcast non ferma le altre
                        }
                    }
                }
                try {
                    val buffer = ByteArray(4096)
                    val pacco = DatagramPacket(buffer, buffer.size)
                    s.receive(pacco)
                    val carta = JSONObject(String(pacco.data, 0, pacco.length))
                    if (carta.optString("app") != "filo") continue
                    val id = carta.optString("id")
                    if (id.isEmpty() || id == mio) continue
                    carta.put("indirizzo", pacco.address.hostAddress ?: continue)
                    trovati[id] = carta
                } catch (e: Exception) {
                    // scaduto il tempo di attesa: si rilancia il grido
                }
            }
        } catch (e: Exception) {
            Log.w(ETICHETTA, "ricerca fallita: ${e.message}")
        } finally {
            presa?.close()
        }
        return trovati.values.toList()
    }
}
