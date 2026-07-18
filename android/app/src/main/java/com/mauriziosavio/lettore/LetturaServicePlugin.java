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
 * JS → nativo: play(coda completa), pause, salta, getPos, stop.
 * Nativo → JS: evento "mediaAction" {azione: pos|stato|fine, i, playing}.
 */
@CapacitorPlugin(name = "LetturaService")
public class LetturaServicePlugin extends Plugin {

    private static LetturaServicePlugin instance;

    @Override
    public void load() {
        instance = this;
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

    /** Posizione corrente del motore nativo: {i, playing, chiave}. */
    @PluginMethod
    public void getPos(PluginCall call) {
        JSObject o = new JSObject();
        try {
            String[] chiave = new String[]{""};
            int[] r = ReadingService.posizione(getContext(), chiave);
            o.put("i", r[0]);
            o.put("playing", r[1] == 1);
            o.put("chiave", chiave[0]);
        } catch (Throwable ignored) {
            o.put("i", -1); o.put("playing", false); o.put("chiave", "");
        }
        call.resolve(o);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try { getContext().stopService(new Intent(getContext(), ReadingService.class)); } catch (Throwable ignored) { }
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
