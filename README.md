<h1 align="center">Filo — Android</h1>

<p align="center">
  <b>Trasferimento diretto di file, immagini e testo fra i dispositivi di una rete locale.</b><br>
  Nessun servizio esterno, nessun account: i dispositivi comunicano<br>
  direttamente sulla rete locale o tramite cavo USB.
</p>

<p align="center">
  <a href="https://github.com/diberardinoleonardo55-coder/filo-android/releases/tag/apk-latest">
    <b>Scarica l'APK</b>
  </a>
  &nbsp;·&nbsp;
  <a href="https://github.com/diberardinoleonardo55-coder/filo-pc">Versione per Windows</a>
</p>

<p align="center">
  <img src="doc/icona.png" width="480" alt="Le misure contenute nell'icona">
</p>

---

## Descrizione

Applicazione Android del progetto
**[filo-pc](https://github.com/diberardinoleonardo55-coder/filo-pc)**. Le due
implementazioni usano lo stesso protocollo e non hanno ruoli distinti: ogni
dispositivo accetta connessioni e ne effettua, quindi puo' essere abbinato a un
computer, a un altro dispositivo Android o a piu' dispositivi insieme,
selezionando di volta in volta il destinatario.

| Direzione | Modalita' |
|---|---|
| invio di file | *Condividi → Filo* da qualsiasi applicazione, oppure *Manda file* |
| invio di testo | *Manda gli appunti*, o il riquadro nelle impostazioni rapide |
| ricezione di file | salvataggio automatico: immagini in `Immagini/Filo`, video in `Film/Filo`, altro in `Download/Filo` |
| ricezione di testo | notifica con azione **Copia** |
| richiesta di appunti | notifica con azione **Manda** |

Kotlin e Jetpack Compose. Nessuna libreria di rete: `HttpsURLConnection`,
`SSLServerSocket` e `DatagramSocket`.

---

## Vista a costellazione

In cima alla schermata il dispositivo locale e' al centro, i dispositivi
abbinati intorno, rappresentati da un monitor o da un telefono secondo il tipo;
da ognuno parte un filo verso il centro. La vista e' la stessa
dell'implementazione per Windows:

<p align="center">
  <img src="doc/costellazione.png" width="430" alt="Vista a costellazione, nella versione per Windows">
</p>

<p align="center"><i>(immagine ripresa dalla versione per Windows; su Android il layout e' verticale)</i></p>

| Stato del filo | Significato |
|---|---|
| spento, con ampia curvatura | dispositivo non raggiungibile |
| acceso, con un punto in movimento | raggiungibile e inattivo |
| teso, illuminato in proporzione | trasferimento in corso |

La direzione del movimento indica il verso del trasferimento; ogni filo ha il
proprio avanzamento, quindi piu' trasferimenti sono visibili contemporaneamente.

Il tocco su una figura o sul suo filo seleziona il destinatario, riportato nelle
etichette dei pulsanti sottostanti.

---

## Lato server

**[`Servitore.kt`](app/src/main/java/it/leo/filo/Servitore.kt)** implementa gli
stessi otto endpoint del lato Python, quindi chi effettua la chiamata non
distingue il tipo di dispositivo che risponde.

```
GET  /chi              POST /abbina           GET  /eventi?dopo=N
GET  /scarica/<id>     POST /consegnato/<id>  POST /carica
POST /appunti          POST /prendi-appunti
```

L'implementazione HTTP e' minimale: le richieste provengono da un solo
protocollo noto e ogni risposta chiude la connessione (`Connection: close`),
evitando la gestione del riuso.

**[`Identita.kt`](app/src/main/java/it/leo/filo/Identita.kt)** genera il
certificato. Una chiave creata nell'AndroidKeyStore viene generata insieme a un
certificato autofirmato, sufficiente allo scopo senza dipendenze di
crittografia; la chiave privata non e' esportabile e viene usata solo per
firmare.

Se la generazione non riesce l'applicazione continua a funzionare come client e
la schermata di abbinamento lo segnala, indicando di avviare l'abbinamento
dall'altro dispositivo.

---

## Scelte di progetto

**1. Richieste con `dopo=0`.**
Le voci gia' ritirate vengono rimosse dal mittente alla conferma, quindi non
serve mantenere un contatore e il riavvio di un dispositivo non comporta
perdite. Quando un ritiro fallisce la voce viene riproposta subito: la pausa di
cinque secondi in `PonteService` evita il ciclo a vuoto.

**2. Verifica per impronta.**
Il controllo del nome host e' disattivato perche' il certificato riporta `Filo`
e non l'indirizzo, che varia. Le impronte accettate sono due, quella dichiarata
e quella osservata al primo collegamento: un intermediario che ispeziona il
traffico TLS presenta un certificato rigenerato.

**3. L'indirizzo non identifica il dispositivo.**
Quando un dispositivo non risponde piu' viene ripetuta la scoperta e si accetta
il primo con l'impronta corrispondente, aggiornando l'indirizzo memorizzato.

**4. Loopback solo per i dispositivi di tipo PC.**
Un computer puo' esporre la propria porta sul dispositivo tramite `adb reverse`;
per un dispositivo Android l'indirizzo 127.0.0.1 corrisponderebbe a se' stesso.

---

## Accesso agli appunti

Da Android 10 un'applicazione in secondo piano non puo' leggere ne' scrivere gli
appunti: e' una limitazione di sistema, senza permesso associato, valida per
tutte le applicazioni che non sono la tastiera predefinita.

[`AppuntiActivity`](app/src/main/java/it/leo/filo/AppuntiActivity.kt) e'
un'attivita' senza interfaccia che viene aperta, ottiene il fuoco, opera sugli
appunti e termina. E' usata dal riquadro delle impostazioni rapide e dalle
azioni delle notifiche. L'operazione avviene in `onWindowFocusChanged` e non in
`onResume`, perche' il fuoco arriva successivamente.

---

## Note tecniche

<details>
<summary><b>Permessi sugli Uri condivisi</b></summary>

<br>

Il permesso di lettura concesso da chi condivide segue `intent.data` e la
`ClipData`, non gli extra: passando gli Uri solo negli extra il contenuto non e'
leggibile. Vedi `PonteService.mandaRoba()`.

</details>

<details>
<summary><b><code>startActivityAndCollapse(Intent)</code></b></summary>

<br>

Dalla API 34 solleva un'eccezione: va usata la variante con `PendingIntent`.
Vedi `Riquadro.onClick()`.

</details>

<details>
<summary><b>Invio di file di grandi dimensioni</b></summary>

<br>

Senza `setFixedLengthStreamingMode` o `setChunkedStreamingMode`,
`HttpURLConnection` mantiene in memoria l'intero corpo prima dell'invio.

</details>

<details>
<summary><b><code>IS_PENDING</code> durante la scrittura</b></summary>

<br>

Senza questo flag il file compare nella galleria prima del completamento del
trasferimento.

</details>

<details>
<summary><b>Tipo MIME dei file ricevuti</b></summary>

<br>

Il tipo dichiarato dal mittente e' spesso `application/octet-stream`, che
porterebbe le immagini nella cartella dei download: `indovinaMime()` deduce il
tipo dall'estensione del nome.

</details>

<details>
<summary><b>Valori catturati in un ciclo di animazione</b></summary>

<br>

Il ciclo `withFrameNanos` viene avviato una sola volta: leggendo direttamente i
parametri della composizione resterebbero quelli del primo fotogramma. Serve
`rememberUpdatedState`.

</details>

---

## Struttura

| file | contenuto |
|---|---|
| `Servitore.kt` | server HTTP: lato che risponde |
| `Rete.kt` | lato che effettua le chiamate |
| `Identita.kt` | identificativo, nome e certificato del dispositivo |
| `Compagni.kt` | registro dei dispositivi abbinati, token e impronte |
| `CodaUscita.kt` | code di uscita per destinatario, con attesa lunga |
| `Scoperta.kt` | scoperta e risposta via broadcast UDP |
| `PonteService.kt` | servizio in primo piano: un thread per dispositivo, piu' il server |
| `Costellazione.kt` | vista a costellazione: fili, figure, selezione |
| `Salvataggio.kt` | scrittura dei file ricevuti tramite MediaStore |
| `AppuntiActivity.kt` | attivita' senza interfaccia per gli appunti |
| `CondividiActivity.kt` | voce nel menu Condividi di sistema |
| `Riquadro.kt` | riquadro nelle impostazioni rapide |

---

## Compilazione

L'APK viene prodotto da GitHub Actions a ogni push, come artifact e come release
`apk-latest`, che fornisce un collegamento stabile al file.

La chiave di firma proviene dai segreti del repository (`CHIAVE_JKS`,
`CHIAVE_PASSWORD`) e deve restare invariata: Android rifiuta un aggiornamento
firmato con una chiave diversa da quella dell'applicazione installata. Il
`versionCode` deriva dal numero di esecuzione della build.

In locale servono JDK 17, Android SDK 34 e Gradle 8.7:

```bash
gradle assembleRelease
```

Senza i segreti l'APK viene prodotto non firmato.
