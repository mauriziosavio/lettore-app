package com.mauriziosavio.lettore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.Locale;

/**
 * MOTORE DI LETTURA NATIVO. Il WebView consegna l'intero libro al play; da lì
 * in poi legge questo servizio con il TTS di Android, frase per frase. Così la
 * lettura continua anche quando il sistema congela il WebView in background
 * (succedeva dopo ~1 minuto). Il WebView si risincronizza con gli eventi "pos"
 * e con getPos() quando torna visibile. Tutto è difensivo: mai far cadere l'app.
 */
public class ReadingService extends Service {
    private static final String CHANNEL_ID = "lettura";
    private static final int NOTIFICATION_ID = 1;
    static final String AZ_PLAY = "com.mauriziosavio.lettore.PLAY";
    static final String AZ_PAUSE = "com.mauriziosavio.lettore.PAUSE";
    static final String AZ_NEXT = "com.mauriziosavio.lettore.NEXT";
    static final String AZ_PREV = "com.mauriziosavio.lettore.PREV";
    static final String AZ_STOP = "com.mauriziosavio.lettore.STOP";

    private static ReadingService instance;

    /* coda consegnata dal plugin (stesso processo: niente limiti degli Intent) */
    private static List<String> qFrasi;
    private static int[] qPagine;
    private static int[] qPause;
    private static int qDa;
    private static float qRate = 1f;
    private static String qTitolo = "Lettore";
    private static int qNumPages;
    private static String qChiave = "";
    private static boolean qNuova = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat session;
    private AudioFocusRequest afr;
    private TextToSpeech tts;
    private boolean ttsPronto = false;

    private List<String> frasi;
    private int[] pagine;
    private int[] pause;
    private int pos = 0;
    private float rate = 1f;
    private int numPages = 0;
    private String chiave = "";
    private String title = "Lettore";
    private boolean playing = false;
    private int gen = 0;      // invalida i callback delle frasi superate
    private int errori = 0;
    private int ultimaPagina = -1;
    private long fineTimer = 0; // timer di spegnimento: istante (elapsedRealtime) in cui fermarsi
    private final Runnable timerRun = this::controllaTimer;
    private long guardiaScade = 0; // cane da guardia: entro quando deve arrivare onDone/onError
    private final Runnable guardia = this::controllaGuardia;
    private long statInizio = 0;   // statistiche: da quando sta leggendo questa sessione
    private long parlaInizio = 0;  // quando è partita la frase corrente (smaschera gli onDone senza audio)
    private int frasiLampo = 0;    // frasi "finite" troppo in fretta di fila (voce in rete assente)

    /* ============ API statiche usate dal plugin (stesso processo) ============ */

    static void caricaCoda(Context c, List<String> frasi, int[] pagine, int[] pause,
                           int da, float rate, String titolo, int numPages, String chiave) {
        try {
            synchronized (ReadingService.class) {
                qFrasi = frasi; qPagine = pagine; qPause = pause; qDa = da;
                qRate = rate; qTitolo = titolo; qNumPages = numPages; qChiave = chiave;
                qNuova = true;
            }
            ReadingService s = instance;
            if (s != null) {
                s.main.post(s::sincronizza);
            } else {
                Intent i = new Intent(c, ReadingService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            }
        } catch (Throwable ignored) { }
    }

    static void pausaStatica() {
        ReadingService s = instance;
        if (s != null) s.main.post(s::pausa);
    }

    static void saltaStatica(Context c, int a, float nuovoRate, boolean leggi) {
        ReadingService s = instance;
        if (s != null) {
            s.main.post(() -> s.salta(a, nuovoRate, leggi));
        } else if (a >= 0) {
            try { // servizio spento: aggiorna solo il segnalibro persistente
                SharedPreferences p = c.getSharedPreferences("lettura", MODE_PRIVATE);
                p.edit().putInt("pos", a).putBoolean("playing", false).apply();
            } catch (Throwable ignored) { }
        }
    }

    /** {i, playing, secondi di timer rimasti} — dal servizio vivo o, se spento, dal segnalibro salvato. */
    static int[] posizione(Context c, String[] chiaveOut) {
        ReadingService s = instance;
        if (s != null) {
            chiaveOut[0] = s.chiave;
            int t = s.fineTimer > 0 ? (int) Math.max(0, (s.fineTimer - SystemClock.elapsedRealtime()) / 1000) : 0;
            return new int[]{ s.pos, s.playing ? 1 : 0, t };
        }
        try {
            SharedPreferences p = c.getSharedPreferences("lettura", MODE_PRIVATE);
            chiaveOut[0] = p.getString("chiave", "");
            return new int[]{ p.getInt("pos", -1), 0, 0 };
        } catch (Throwable ignored) {
            chiaveOut[0] = "";
            return new int[]{ -1, 0, 0 };
        }
    }

    /** Timer di spegnimento: fra "minuti" mette in pausa da solo (0 = annulla). */
    static void timerStatico(double minuti) {
        ReadingService s = instance;
        if (s != null) s.main.post(() -> s.impostaTimer(minuti));
    }

    /** Salva la voce scelta e, se il servizio è vivo, la applica subito. */
    static void impostaVoce(Context c, String nome) {
        try {
            c.getSharedPreferences("lettura", MODE_PRIVATE).edit()
                .putString("voce", nome == null ? "" : nome).apply();
        } catch (Throwable ignored) { }
        ReadingService s = instance;
        if (s != null) s.main.post(s::applicaVoceScelta);
    }

    /** Stop dal mini-lettore o dal JS: pausa, segnalibro salvato, servizio e notifica chiusi. */
    static void fermaStatica() {
        ReadingService s = instance;
        if (s != null) s.main.post(s::fermaTutto);
    }

    /** Secondi di ascolto della sessione in corso (0 se fermo): per stats() del plugin. */
    static long statSessioneCorrente() {
        ReadingService s = instance;
        if (s == null || s.statInizio == 0) return 0;
        return Math.max(0, (SystemClock.elapsedRealtime() - s.statInizio) / 1000);
    }

    /* ============ ciclo di vita ============ */

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Lettura in corso", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Mini-lettore e lettura in sottofondo");
                ch.setShowBadge(false);
                getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
        } catch (Throwable ignored) { }
        try {
            session = new MediaSessionCompat(this, "lettore");
            session.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { main.post(() -> salta(-1, 0, true)); }
                @Override public void onPause() { main.post(ReadingService.this::pausa); }
                @Override public void onStop() { main.post(ReadingService.this::fermaTutto); }
                @Override public void onSkipToNext() { main.post(() -> spostati(1)); }
                @Override public void onSkipToPrevious() { main.post(() -> spostati(-1)); }
                @Override public void onCustomAction(String action, android.os.Bundle extras) {
                    if ("CHIUDI".equals(action)) main.post(ReadingService.this::fermaTutto);
                }
            });
            session.setActive(true);
        } catch (Throwable t) {
            session = null; // senza sessione niente mini-lettore, ma l'app vive
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lettore:lettura");
            wakeLock.setReferenceCounted(false);
        } catch (Throwable ignored) { }
        initTts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String az = intent != null ? intent.getAction() : null;
            if (AZ_PLAY.equals(az)) { salta(-1, 0, true); return START_NOT_STICKY; }
            if (AZ_PAUSE.equals(az)) { pausa(); return START_NOT_STICKY; }
            if (AZ_NEXT.equals(az)) { spostati(1); return START_NOT_STICKY; }
            if (AZ_PREV.equals(az)) { spostati(-1); return START_NOT_STICKY; }
            if (AZ_STOP.equals(az)) { fermaTutto(); return START_NOT_STICKY; }
        } catch (Throwable ignored) { }
        sincronizza();
        return START_NOT_STICKY;
    }

    /* ============ motore ============ */

    private void initTts() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(this, status -> main.post(() -> {
                ttsPronto = status == TextToSpeech.SUCCESS;
                if (!ttsPronto) return;
                try { tts.setLanguage(Locale.ITALIAN); } catch (Throwable ignored) { }
                applicaVoce(); // la voce italiana scelta dall'utente, se ne ha salvata una
                try {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                } catch (Throwable ignored) { }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { main.post(() -> fineFrase(id, false)); }
                    @Override public void onError(String id) { main.post(() -> fineFrase(id, true)); }
                    @Override public void onError(String id, int code) { main.post(() -> fineFrase(id, true)); }
                    @Override public void onRangeStart(String id, int start, int end, int frame) {
                        // karaoke: il WebView (se visibile) evidenzia la parola pronunciata
                        try {
                            if (id != null && id.startsWith("u" + gen + "-")) {
                                LetturaServicePlugin.emitParola(pos, start);
                            }
                        } catch (Throwable ignored) { }
                    }
                });
                if (playing) parla();
            }));
        } catch (Throwable ignored) { }
    }

    private void sincronizza() {
        try {
            boolean nuova;
            synchronized (ReadingService.class) {
                nuova = qNuova;
                if (nuova) {
                    frasi = qFrasi; pagine = qPagine; pause = qPause; pos = Math.max(0, qDa);
                    rate = qRate; title = qTitolo; numPages = qNumPages; chiave = qChiave;
                    qNuova = false;
                }
            }
            if (nuova) {
                gen++;
                errori = 0;
                frasiLampo = 0;
                playing = true;
                statParte();
                salva();
                if (ttsPronto) parla();
                else initTts();
                LetturaServicePlugin.emit("stato", pos, true); // conferma subito l'icona ⏸ nel JS
            }
        } catch (Throwable ignored) { }
        refresh();
    }

    private void parla() {
        try {
            if (!playing || frasi == null || pos < 0 || pos >= frasi.size() || !ttsPronto) return;
            tts.setSpeechRate(rate);
            String frase = frasi.get(pos);
            int esito = tts.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "u" + gen + "-" + pos);
            if (esito != TextToSpeech.SUCCESS) {
                /* motore occupato o non ancora legato (tipico a freddo): senza questo
                   retry non arriverebbe MAI un callback e la lettura resterebbe muta */
                final int g = gen;
                main.postDelayed(() -> { if (playing && g == gen) parla(); }, 800);
                return;
            }
            parlaInizio = SystemClock.elapsedRealtime();
            armaGuardia(frase.length());
            if (pagineCorrente() != ultimaPagina) refresh();
        } catch (Throwable ignored) { }
    }

    /** Le voci "in rete" senza connessione dicono subito onDone SENZA audio:
     *  se la frase "finisce" più in fretta di quanto qualunque voce potrebbe
     *  pronunciarla, è un finto successo. */
    private boolean finintaTroppoInFretta() {
        try {
            if (frasi == null || pos < 0 || pos >= frasi.size()) return false;
            int len = frasi.get(pos).length();
            if (len <= 15) return false; // le frasi cortissime possono finire davvero in fretta
            long durata = SystemClock.elapsedRealtime() - parlaInizio;
            long minimo = Math.max(350, (long) (len * 25 / Math.max(0.5f, rate)));
            return durata < minimo;
        } catch (Throwable ignored) { return false; }
    }

    /* Cane da guardia: se onDone/onError non arrivano entro il tempo stimato
       (motore TTS incantato), la frase corrente riparte da sola. */
    private void armaGuardia(int lunghezza) {
        try {
            long stima = 7000 + (long) (lunghezza * 100 / Math.max(0.5f, rate));
            guardiaScade = SystemClock.elapsedRealtime() + stima;
            main.removeCallbacks(guardia);
            main.postDelayed(guardia, stima);
        } catch (Throwable ignored) { }
    }

    private void controllaGuardia() {
        try {
            if (!playing || guardiaScade == 0) return;
            long resto = guardiaScade - SystemClock.elapsedRealtime();
            if (resto > 1000) { main.postDelayed(guardia, resto); return; } // sveglia in anticipo
            guardiaScade = 0;
            gen++; // l'eventuale callback in ritardo della frase persa non conta più
            if (tts != null) tts.stop();
            parla(); // riprova la stessa frase
        } catch (Throwable ignored) { }
    }

    private void fineFrase(String id, boolean errore) {
        try {
            if (!playing || frasi == null) return;
            if (id == null || !id.startsWith("u" + gen + "-")) return; // frase superata
            guardiaScade = 0;
            main.removeCallbacks(guardia); // il callback è arrivato: guardia a riposo
            if (errore) {
                errori++;
                final int g = gen; // mai arrendersi: il motore TTS può tornare
                main.postDelayed(() -> { if (playing && g == gen) parla(); }, errori < 3 ? 700 : 5000);
                return;
            }
            if (finintaTroppoInFretta()) {
                frasiLampo++;
                if (frasiLampo >= 3) {
                    /* la voce scelta non produce audio (tipico: voce in rete senza
                       connessione): torna alla voce di sistema e avvisa il JS */
                    frasiLampo = 0;
                    try { getSharedPreferences("lettura", MODE_PRIVATE).edit().remove("voce").apply(); } catch (Throwable ignored) { }
                    try { if (tts != null) tts.setLanguage(Locale.ITALIAN); } catch (Throwable ignored) { }
                    LetturaServicePlugin.emit("voceko", pos, playing);
                }
                final int g = gen;
                main.postDelayed(() -> { if (playing && g == gen) parla(); }, 900);
                return;
            }
            frasiLampo = 0;
            errori = 0;
            if (pos + 1 >= frasi.size()) {
                playing = false;
                statFerma();
                try { // libro finito: conta per le statistiche
                    SharedPreferences p = getSharedPreferences("lettura", MODE_PRIVATE);
                    p.edit().putInt("statLibri", p.getInt("statLibri", 0) + 1).apply();
                } catch (Throwable ignored) { }
                salva();
                refresh();
                LetturaServicePlugin.emit("fine", -1, false);
                return;
            }
            int attesa = (pause != null && pos < pause.length) ? Math.max(0, pause[pos]) : 200;
            final int g = gen;
            main.postDelayed(() -> {
                if (playing && g == gen) {
                    pos++;
                    salva();
                    LetturaServicePlugin.emit("pos", pos, playing);
                    parla();
                }
            }, attesa);
        } catch (Throwable ignored) { }
    }

    private void pausa() {
        try {
            gen++;
            playing = false;
            statFerma();
            guardiaScade = 0;
            main.removeCallbacks(guardia);
            if (tts != null) tts.stop();
            salva();
            refresh();
            LetturaServicePlugin.emit("stato", pos, false);
        } catch (Throwable ignored) { }
    }

    /** Stop vero e proprio: come pausa, ma la notifica sparisce e il servizio muore. */
    private void fermaTutto() {
        try { pausa(); } catch (Throwable ignored) { }
        try { fineTimer = 0; main.removeCallbacks(timerRun); } catch (Throwable ignored) { }
        try { stopForeground(true); } catch (Throwable ignored) { }
        try { stopSelf(); } catch (Throwable ignored) { }
    }

    /* ---- timer di spegnimento ---- */

    private void impostaTimer(double minuti) {
        try {
            main.removeCallbacks(timerRun);
            long ms = (long) (minuti * 60_000L);
            fineTimer = ms > 0 ? SystemClock.elapsedRealtime() + ms : 0;
            if (fineTimer > 0) main.postDelayed(timerRun, ms);
        } catch (Throwable ignored) { }
    }

    private void controllaTimer() {
        try {
            if (fineTimer == 0) return;
            long resto = fineTimer - SystemClock.elapsedRealtime();
            if (resto > 1000) { main.postDelayed(timerRun, resto); return; } // sveglia arrivata in anticipo
            fineTimer = 0;
            if (playing) pausa(); // segnalibro già salvato da pausa(); notifica resta per riprendere
        } catch (Throwable ignored) { }
    }

    /* ---- voce ---- */

    /** Applica la voce salvata nelle preferenze al motore TTS (silenziosamente). */
    private void applicaVoce() {
        try {
            if (tts == null) return;
            String nome = getSharedPreferences("lettura", MODE_PRIVATE).getString("voce", "");
            if (nome == null || nome.isEmpty()) return;
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (nome.equals(v.getName())) { tts.setVoice(v); break; }
            }
        } catch (Throwable ignored) { }
    }

    /** Cambio voce a caldo: se sta leggendo, la frase corrente riparte con la nuova voce. */
    private void applicaVoceScelta() {
        frasiLampo = 0; // nuova voce, nuova fiducia
        applicaVoce();
        try { if (playing && ttsPronto) salta(-1, 0, true); } catch (Throwable ignored) { }
    }

    /** a<0 = resta dov'è; rate<=0 = invariato; leggi = avvia/continua la voce. */
    private void salta(int a, float nuovoRate, boolean leggi) {
        try {
            gen++;
            if (a >= 0) pos = a;
            if (nuovoRate > 0) rate = nuovoRate;
            if (frasi != null && pos >= frasi.size()) pos = frasi.size() - 1;
            if (leggi || playing) {
                playing = true;
                statParte();
                if (tts != null) tts.stop();
                parla();
                LetturaServicePlugin.emit("stato", pos, true);
            }
            salva();
            refresh();
        } catch (Throwable ignored) { }
    }

    private void spostati(int delta) {
        try {
            if (frasi == null) return;
            int a = Math.max(0, Math.min(frasi.size() - 1, pos + delta));
            salta(a, 0, playing);
            LetturaServicePlugin.emit("pos", pos, playing);
        } catch (Throwable ignored) { }
    }

    private void salva() {
        try {
            getSharedPreferences("lettura", MODE_PRIVATE).edit()
                .putInt("pos", pos)
                .putString("chiave", chiave)
                .putBoolean("playing", playing)
                .apply();
        } catch (Throwable ignored) { }
    }

    /* ---- statistiche di ascolto (contate qui: valgono anche a schermo spento) ---- */

    private void statParte() {
        if (statInizio == 0) statInizio = SystemClock.elapsedRealtime();
    }

    private void statFerma() {
        try {
            if (statInizio == 0) return;
            long sec = (SystemClock.elapsedRealtime() - statInizio) / 1000;
            statInizio = 0;
            if (sec <= 0 || sec > 24 * 3600) return;
            SharedPreferences p = getSharedPreferences("lettura", MODE_PRIVATE);
            String oggi = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date());
            long og = oggi.equals(p.getString("statGiorno", "")) ? p.getLong("statOggi", 0) : 0;
            p.edit()
                .putLong("statTot", p.getLong("statTot", 0) + sec)
                .putString("statGiorno", oggi)
                .putLong("statOggi", og + sec)
                .apply();
        } catch (Throwable ignored) { }
    }

    private int pagineCorrente() {
        return (pagine != null && pos >= 0 && pos < pagine.length) ? pagine[pos] : 0;
    }

    /* ============ notifica / mini-lettore ============ */

    private PendingIntent azione(String az, int rc) {
        Intent i = new Intent(this, ReadingService.class).setAction(az);
        return PendingIntent.getService(this, rc, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private void refresh() {
        String sub;
        int pg = pagineCorrente();
        ultimaPagina = pg;
        if (pg > 0) sub = "Pagina " + pg + (numPages > 0 ? " di " + numPages : "");
        else sub = playing ? "Lettura in corso" : "In pausa";
        try {
            if (session != null) {
                session.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub)
                    .build());
                session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    /* su Android 13+ il mini-lettore mostra SOLO le azioni della sessione:
                       "Chiudi" deve stare qui, quello della notifica viene ignorato */
                    .addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        "CHIUDI", "Chiudi", R.drawable.ic_chiudi).build())
                    .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                    .build());
            }
        } catch (Throwable ignored) { }

        Notification n = null;
        try {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(sub)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, "Frase precedente", azione(AZ_PREV, 1)))
                .addAction(playing
                    ? new NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pausa", azione(AZ_PAUSE, 2))
                    : new NotificationCompat.Action(android.R.drawable.ic_media_play, "Leggi", azione(AZ_PLAY, 3)))
                .addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_next, "Frase successiva", azione(AZ_NEXT, 4)))
                .addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel, "Chiudi", azione(AZ_STOP, 5)))
                .setDeleteIntent(azione(AZ_STOP, 6)); // notifica scacciata via = stop
            if (session != null) {
                nb.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
            }
            n = nb.build();
        } catch (Throwable t) {
            n = null;
        }
        if (n == null) {
            try { // ripiego: notifica minima
                Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
                n = b.setContentTitle("Lettore")
                    .setContentText("Lettura ad alta voce")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .build();
            } catch (Throwable ignored) { }
        }
        try {
            if (n != null) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, n);
                }
            }
        } catch (Throwable ignored) { }
        try { // CPU sveglia solo mentre legge davvero
            if (wakeLock != null) {
                if (playing) wakeLock.acquire(6 * 60 * 60 * 1000L);
                else if (wakeLock.isHeld()) wakeLock.release();
            }
        } catch (Throwable ignored) { }
        try { // focus audio: mette in pausa l'eventuale musica mentre il Lettore legge
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && am != null) {
                if (playing) {
                    if (afr == null) {
                        afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setOnAudioFocusChangeListener(f -> { }).build();
                    }
                    am.requestAudioFocus(afr);
                } else if (afr != null) {
                    am.abandonAudioFocusRequest(afr);
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // app chiusa dalle recenti: fermati e salva il segnalibro
        try { pausa(); } catch (Throwable ignored) { }
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        try { statFerma(); } catch (Throwable ignored) { }
        try { main.removeCallbacks(timerRun); main.removeCallbacks(guardia); } catch (Throwable ignored) { }
        try { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } } catch (Throwable ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 26 && afr != null) {
                ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocusRequest(afr);
            }
        } catch (Throwable ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
