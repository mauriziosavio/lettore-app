package com.mauriziosavio.lettore;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Ponte JS ⇄ motore di lettura nativo.
 * JS → nativo: play(coda completa), pause, salta, getPos, stop, timer, voci, setVoce.
 * Nativo → JS: evento "mediaAction" {azione: pos|stato|fine, i, playing}.
 */
@CapacitorPlugin(name = "LetturaService")
public class LetturaServicePlugin extends Plugin {

    private static volatile LetturaServicePlugin instance;

    /* TTS di servizio del plugin: serve solo per elencare le voci e far sentire
       l'anteprima quando si cambia voce da fermi (il motore vero vive nel servizio).
       Tutto lo stato qui sotto è protetto dal lock di vttsAttesa. */
    private volatile android.speech.tts.TextToSpeech vtts;
    private volatile boolean vttsPronto = false;
    private boolean vttsAvvio = false;      // inizializzazione in corso
    private boolean pluginDistrutto = false;
    private final List<Runnable> vttsAttesa = new ArrayList<>();

    @Override
    public void load() {
        instance = this;
    }

    /** Esegue r quando il TTS di servizio è inizializzato (o subito, se fallisce). */
    private void conVoce(Runnable r) {
        boolean subito = false, crea = false;
        synchronized (vttsAttesa) {
            if (vtts != null && vttsPronto) {
                subito = true;
            } else {
                vttsAttesa.add(r);
                if (!vttsAvvio) { vttsAvvio = true; crea = true; }
            }
        }
        if (subito) { try { r.run(); } catch (Throwable ignored) { } return; }
        if (!crea) return;
        /* onInit può arrivare anche DENTRO il costruttore (motore non legabile), prima
           che il riferimento sia assegnato: chi dei due arriva per secondo conclude */
        final android.speech.tts.TextToSpeech[] creato = new android.speech.tts.TextToSpeech[1];
        final int[] esito = new int[]{0}; // 0 = in attesa, 1 = pronto, 2 = fallito
        try {
            android.speech.tts.TextToSpeech t = new android.speech.tts.TextToSpeech(
                getContext().getApplicationContext(), st -> {
                    android.speech.tts.TextToSpeech fatto = null;
                    boolean ok;
                    synchronized (vttsAttesa) {
                        esito[0] = st == android.speech.tts.TextToSpeech.SUCCESS ? 1 : 2;
                        ok = esito[0] == 1;
                        fatto = creato[0];
                    }
                    if (fatto != null) initVoceFinita(fatto, ok);
                });
            boolean giaConcluso;
            boolean riuscito;
            synchronized (vttsAttesa) {
                creato[0] = t;
                vtts = t;
                giaConcluso = esito[0] != 0;
                riuscito = esito[0] == 1;
            }
            if (giaConcluso) initVoceFinita(t, riuscito);
        } catch (Throwable e) {
            initVoceFinita(null, false);
        }
    }

    /** Fine dell'inizializzazione del TTS di servizio: se fallita lo chiude e lo
     *  dimentica (al prossimo uso si ritenta); poi esegue le richieste in attesa. */
    private void initVoceFinita(android.speech.tts.TextToSpeech t, boolean ok) {
        List<Runnable> coda;
        boolean chiudi;
        synchronized (vttsAttesa) {
            vttsAvvio = false;
            chiudi = !ok || t == null || pluginDistrutto;
            if (!chiudi) {
                vtts = t;
                vttsPronto = true;
            } else {
                if (vtts == t) vtts = null;
                vttsPronto = false;
            }
            coda = new ArrayList<>(vttsAttesa);
            vttsAttesa.clear();
        }
        if (chiudi && t != null) { try { t.shutdown(); } catch (Throwable ignored) { } }
        for (Runnable x : coda) { try { x.run(); } catch (Throwable ignored) { } }
    }

    @Override
    protected void handleOnDestroy() {
        android.speech.tts.TextToSpeech t;
        synchronized (vttsAttesa) {
            pluginDistrutto = true;
            t = vtts;
            vtts = null;
            vttsPronto = false;
        }
        try { if (t != null) { t.stop(); t.shutdown(); } } catch (Throwable ignored) { }
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }

    static void emit(String azione, int i, boolean playing) {
        emit(azione, i, playing, ReadingService.chiaveCorrente());
    }

    /** Ogni evento porta la chiave del libro caricato nel servizio: il JS ignora quelli di un altro libro. */
    static void emit(String azione, int i, boolean playing, String chiave) {
        try {
            LetturaServicePlugin p = instance;
            if (p != null) {
                JSObject o = new JSObject();
                o.put("azione", azione);
                if (i >= 0) o.put("i", i);
                o.put("playing", playing);
                o.put("chiave", chiave != null ? chiave : "");
                p.notifyListeners("mediaAction", o);
            }
        } catch (Throwable ignored) { }
    }

    /** Karaoke: la voce sta pronunciando la parola che inizia al carattere "da" della frase i. */
    static void emitParola(int i, int da) {
        try {
            LetturaServicePlugin p = instance;
            if (p != null) {
                JSObject o = new JSObject();
                o.put("azione", "parola");
                o.put("i", i);
                o.put("da", da);
                o.put("chiave", ReadingService.chiaveCorrente());
                p.notifyListeners("mediaAction", o);
            }
        } catch (Throwable ignored) { }
    }

    /** Consegna l'intero libro al motore nativo e avvia la lettura da "da". */
    @PluginMethod
    public void play(PluginCall call) {
        try {
            // Su Android 13+ la notifica del servizio è visibile solo col permesso:
            // lo chiediamo al primo avvio; il servizio parte comunque.
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9001);
            }
        } catch (Throwable ignored) { }
        try {
            JSArray f = call.getArray("frasi");
            JSArray pg = call.getArray("pagine");
            JSArray pa = call.getArray("pause");
            List<String> frasi = new ArrayList<>();
            for (int i = 0; i < f.length(); i++) frasi.add(f.getString(i));
            int[] pagine = new int[pg != null ? pg.length() : 0];
            for (int i = 0; i < pagine.length; i++) pagine[i] = pg.getInt(i);
            int[] pause = new int[pa != null ? pa.length() : 0];
            for (int i = 0; i < pause.length; i++) pause[i] = pa.getInt(i);
            // capitoli per l'indice sfogliabile di Android Auto: [{i, t, p}]
            List<String> capT = new ArrayList<>();
            int[] capI = new int[0], capP = new int[0];
            try {
                JSArray ca = call.getArray("capitoli");
                if (ca != null) {
                    capI = new int[ca.length()];
                    capP = new int[ca.length()];
                    for (int i = 0; i < ca.length(); i++) {
                        org.json.JSONObject x = ca.getJSONObject(i);
                        capT.add(x.optString("t", "Capitolo " + (i + 1)));
                        capI[i] = x.optInt("i", 0);
                        capP[i] = x.optInt("p", 0);
                    }
                }
            } catch (Throwable ignored) { }
            // copertina (dataURL base64): diventa album art su Auto e notifica
            byte[] copertina = null;
            try {
                String cop = call.getString("copertina", "");
                if (cop != null && cop.startsWith("data:image")) {
                    int virgola = cop.indexOf(',');
                    if (virgola > 0) copertina = android.util.Base64.decode(
                        cop.substring(virgola + 1), android.util.Base64.DEFAULT);
                }
            } catch (Throwable ignored) { }
            ReadingService.caricaCoda(getContext(), frasi, pagine, pause,
                call.getInt("da", 0),
                call.getDouble("rate", 1.0).floatValue(),
                call.getString("titolo", "Lettore"),
                call.getInt("numPages", 0),
                call.getString("chiave", ""),
                capT, capI, capP, copertina);
        } catch (Throwable ignored) { }
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        try { ReadingService.pausaStatica(); } catch (Throwable ignored) { }
        call.resolve();
    }

    /** {a: indice (-1 = invariato), rate: opzionale, leggi: true = avvia la voce,
     *   chiave: opzionale, il libro a cui si riferisce (se diverso da quello nel servizio: ignorato)}. */
    @PluginMethod
    public void salta(PluginCall call) {
        try {
            String chiave = call.getString("chiave", "");
            ReadingService.saltaStatica(getContext(),
                call.getInt("a", -1),
                call.getDouble("rate", 0.0).floatValue(),
                Boolean.TRUE.equals(call.getBoolean("leggi", false)),
                chiave != null ? chiave : "");
        } catch (Throwable ignored) { }
        call.resolve();
    }

    /** Posizione corrente del motore nativo: {i, playing, chiave, timer (secondi rimasti)}. */
    @PluginMethod
    public void getPos(PluginCall call) {
        JSObject o = new JSObject();
        try {
            String[] chiave = new String[]{""};
            int[] r = ReadingService.posizione(getContext(), chiave);
            o.put("i", r[0]);
            o.put("playing", r[1] == 1);
            o.put("chiave", chiave[0]);
            o.put("timer", r.length > 2 ? r[2] : 0);
        } catch (Throwable ignored) {
            o.put("i", -1); o.put("playing", false); o.put("chiave", ""); o.put("timer", 0);
        }
        call.resolve(o);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        // fermaTutto() del servizio fa già pausa + stopForeground + stopSelf sul suo thread;
        // uno stopService da qui poteva far cadere l'app ("did not then call startForeground")
        try { ReadingService.fermaStatica(); } catch (Throwable ignored) { }
        call.resolve();
    }

    /** Statistiche di ascolto: {oggi, totale} in secondi e {libri} finiti. */
    @PluginMethod
    public void stats(PluginCall call) {
        JSObject o = new JSObject();
        try {
            android.content.SharedPreferences p =
                getContext().getSharedPreferences("lettura", android.content.Context.MODE_PRIVATE);
            String oggi = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
            long extra = ReadingService.statSessioneCorrente(); // sessione in corso, non ancora salvata
            long og = oggi.equals(p.getString("statGiorno", "")) ? p.getLong("statOggi", 0) : 0;
            o.put("oggi", og + extra);
            o.put("totale", p.getLong("statTot", 0) + extra);
            o.put("libri", p.getInt("statLibri", 0));
        } catch (Throwable ignored) {
            o.put("oggi", 0); o.put("totale", 0); o.put("libri", 0);
        }
        call.resolve(o);
    }

    /** Timer di spegnimento: {minuti} (anche frazionari); 0 = annulla. */
    @PluginMethod
    public void timer(PluginCall call) {
        try { ReadingService.timerStatico(call.getDouble("minuti", 0.0)); } catch (Throwable ignored) { }
        call.resolve();
    }

    /** Elenco delle voci italiane del TTS Android: {voci:[{nome, etichetta, predefinita}]}. */
    @PluginMethod
    public void voci(PluginCall call) {
        conVoce(() -> {
            JSObject o = new JSObject();
            JSArray arr = new JSArray();
            try {
                if (vttsPronto) {
                    android.speech.tts.Voice def = null;
                    try { def = vtts.getDefaultVoice(); } catch (Throwable ignored) { }
                    List<android.speech.tts.Voice> it = new ArrayList<>();
                    java.util.Set<android.speech.tts.Voice> tutte = vtts.getVoices();
                    if (tutte != null) for (android.speech.tts.Voice v : tutte) {
                        try {
                            if (v.getLocale() == null || !"it".equals(v.getLocale().getLanguage())) continue;
                            if (v.getFeatures() != null && v.getFeatures().contains(
                                android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                            it.add(v);
                        } catch (Throwable ignored) { }
                    }
                    // prima le voci sul dispositivo, poi quelle che richiedono internet; ordine stabile
                    java.util.Collections.sort(it, (a, b) -> {
                        int na = a.isNetworkConnectionRequired() ? 1 : 0;
                        int nb = b.isNetworkConnectionRequired() ? 1 : 0;
                        if (na != nb) return na - nb;
                        return a.getName().compareTo(b.getName());
                    });
                    int n = 0;
                    for (android.speech.tts.Voice v : it) {
                        n++;
                        boolean pre = def != null && v.getName().equals(def.getName());
                        String et = "Voce " + n
                            + (v.isNetworkConnectionRequired() ? " (con internet)" : "")
                            + (pre ? " — predefinita" : "");
                        JSObject j = new JSObject();
                        j.put("nome", v.getName());
                        j.put("etichetta", et);
                        j.put("predefinita", pre);
                        arr.put(j);
                    }
                }
            } catch (Throwable ignored) { }
            o.put("voci", arr);
            call.resolve(o);
        });
    }

    /** Salva e applica la voce scelta: {nome, anteprima}; con anteprima la fa sentire subito. */
    @PluginMethod
    public void setVoce(PluginCall call) {
        String nome = call.getString("nome", "");
        boolean anteprima = Boolean.TRUE.equals(call.getBoolean("anteprima", false));
        try { ReadingService.impostaVoce(getContext(), nome); } catch (Throwable ignored) { }
        if (anteprima && nome != null && !nome.isEmpty()) {
            final String scelta = nome;
            conVoce(() -> {
                try {
                    if (!vttsPronto) return;
                    for (android.speech.tts.Voice v : vtts.getVoices()) {
                        if (scelta.equals(v.getName())) { vtts.setVoice(v); break; }
                    }
                    vtts.speak("Ciao! Leggerò io il tuo libro.",
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "anteprima");
                } catch (Throwable ignored) { }
            });
        }
        call.resolve();
    }

    /** Restituisce (e cancella) l'eventuale traccia dell'ultimo crash. */
    @PluginMethod
    public void crash(PluginCall call) {
        JSObject o = new JSObject();
        String trace = "";
        try {
            java.io.File f = new java.io.File(getContext().getFilesDir(), "crash.txt");
            if (f.exists()) {
                byte[] b = new byte[(int) Math.min(f.length(), 6000)];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int n = in.read(b);
                    if (n > 0) trace = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                }
                f.delete();
            }
        } catch (Throwable ignored) { }
        o.put("trace", trace);
        call.resolve(o);
    }

    /** Rientri di sistema in px CSS (dp): {top, bottom} per non finire sotto la barra di stato. */
    @PluginMethod
    public void insets(PluginCall call) {
        int top = 0, bottom = 0;
        try {
            android.view.View v = getBridge().getWebView();
            androidx.core.view.WindowInsetsCompat w = androidx.core.view.ViewCompat.getRootWindowInsets(v);
            if (w != null) {
                androidx.core.graphics.Insets si = w.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                float dens = getContext().getResources().getDisplayMetrics().density;
                top = Math.round(si.top / dens);
                bottom = Math.round(si.bottom / dens);
            }
        } catch (Throwable ignored) { }
        JSObject o = new JSObject();
        o.put("top", top);
        o.put("bottom", bottom);
        call.resolve(o);
    }
}
