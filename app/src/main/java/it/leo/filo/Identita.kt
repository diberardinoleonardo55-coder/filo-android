package it.leo.filo

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Identita' del dispositivo: identificativo stabile, nome e certificato TLS.
 *
 * Il certificato serve al lato server ([Servitore]). Una chiave generata
 * nell'AndroidKeyStore viene creata insieme a un certificato autofirmato, che e'
 * quanto basta senza dipendenze di crittografia; la chiave privata non e'
 * esportabile e viene usata solo per firmare.
 *
 * Se la generazione non riesce, [pronta] resta falsa: l'applicazione continua a
 * funzionare come client ma non puo' ricevere connessioni.
 */
object Identita {

    private const val ALIAS = "filo-identita"
    private const val ETICHETTA = "Filo"

    /** Identificativo del dispositivo, generato una sola volta. */
    fun id(c: Context): String {
        val p = Impostazioni.prefs(c)
        var mio = p.getString("mio_id", "") ?: ""
        if (mio.isEmpty()) {
            mio = UUID.randomUUID().toString().replace("-", "").take(16)
            p.edit().putString("mio_id", mio).apply()
        }
        return mio
    }

    /** Nome mostrato agli altri dispositivi; modificabile per distinguere modelli uguali. */
    fun nome(c: Context): String =
        Impostazioni.prefs(c).getString("mio_nome", null)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() }

    fun impostaNome(c: Context, nome: String) =
        Impostazioni.prefs(c).edit().putString("mio_nome", nome.take(40)).apply()

    // --- il certificato ------------------------------------------------------

    @Volatile
    private var deposito: KeyStore? = null

    private fun keystore(): KeyStore {
        deposito?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val fine = Calendar.getInstance().apply { add(Calendar.YEAR, 50) }
            val generatore = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
            )
            generatore.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Filo"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotAfter(fine.time)
                    .build()
            )
            generatore.generateKeyPair()
            ks.load(null)
        }
        deposito = ks
        return ks
    }

    /** Vero se il dispositivo puo' accettare connessioni oltre a effettuarle. */
    fun pronta(): Boolean = try {
        certificato() != null
    } catch (e: Exception) {
        Log.w(ETICHETTA, "niente certificato: ${e.message}")
        false
    }

    fun certificato(): X509Certificate? =
        keystore().getCertificate(ALIAS) as? X509Certificate

    /** Impronta SHA-256 del certificato, memorizzata dai dispositivi abbinati. */
    fun impronta(): String {
        val cert = certificato() ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }
    }

    /** Contesto TLS usato da [Servitore] per la porta in ascolto. */
    fun contestoServer(): SSLContext {
        val fabbrica = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        fabbrica.init(keystore(), null)
        return SSLContext.getInstance("TLS").apply {
            init(fabbrica.keyManagers, null, null)
        }
    }
}
