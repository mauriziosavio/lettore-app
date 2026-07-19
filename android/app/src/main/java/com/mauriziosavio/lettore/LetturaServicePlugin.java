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

    private static LetturaServicePlugin instance;

    /* TTS di servizio del plugin: serve solo per elencare le voci e far sentire
       l'anteprima quando si cambia voce da fermi (il motore vero vive nel servizio). */
    private android.speech.tts.TextToSpeech vtts;
    private boolean vttsPronto = false;
    private final List<Runnable> vttsAttesa = new ArrayList<>();

    @Override
    public void load() {
        instance = this;
    }

    /** Esegue r quando il TTS di servizio è inizializzato (o subito, se fallisce). */
    private void conVoce(Runnable r) {
        try {
            if (vtts != null) {
                if (vttsPronto) r.run(); else vttsAttesa.add(r);
                return;
            }
            vttsAttesa.add(r);
            vtts = new android.speech.tts.TextToSpeech(getContext(), st -> {
                vttsPronto = st == android.speech.tts.TextToSpeech.SUCCESS;
                List<Runnable> coda = new ArrayList<>(vttsAttesa);
                vttsAttesa.clear();
                for (Runnable x : coda) { try { x.run(); } catch (Throwable ignored) { } }
            });
        } catch (Throwable t) {
            List<Runnable> coda = new ArrayList<>(vttsAttesa);
            vttsAttesa.clear();
            for (Runnable x : coda) { try { x.run(); } catch (Throwable ignored) { } }
        }
    }

    static void emit(String azione, int i, boolean playing) {
        try {
            LetturaServicePlugin p = instance;
            if (p != null) {
                JSObject o = new JSObject();
                o.put("azione", azione);
                if (i >= 0) o.put("i", i);
                o.put("playing", playing);
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
            ReadingService.caricaCoda(getContext(), frasi, pagine, pause,
                call.getInt("da", 0),
                call.getDouble("rate", 1.0).floatValue(),
                call.getString("titolo", "Lettore"),
                call.getInt("numPages", 0),
                call.getString("chiave", ""));
        } catch (Throwable ignored) { }
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        try { ReadingService.pausaStatica(); } catch (Throwable ignored) { }
        call.resolve();
    }

    /** {a: indice (-1 = invariato), rate: opzionale, leggi: true = avvia la voce}. */
    @PluginMethod
    public void salta(PluginCall call) {
        try {
            ReadingService.saltaStatica(getContext(),
                call.getInt("a", -1),
                call.getDouble("rate", 0.0).floatValue(),
                Boolean.TRUE.equals(call.getBoolean("leggi", false)));
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
        try { ReadingService.fermaStatica(); } catch (Throwable ignored) { }
        try { getContext().stopService(new Intent(getContext(), ReadingService.class)); } catch (Throwable ignored) { }
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
