package com.mauriziosavio.lettore;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Registratore di crash: se l'app cade, il motivo finisce in crash.txt
        // e alla riapertura viene mostrato all'utente (via LetturaService.crash()).
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(
                    new java.io.File(getFilesDir(), "crash.txt"))) {
                e.printStackTrace(pw);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
        registerPlugin(LetturaServicePlugin.class);
        super.onCreate(savedInstanceState);
        // Edge-to-edge (Android 15+): comunica al WebView i rientri di sistema,
        // così l'header non finisce sotto la barra di stato/fotocamera.
        WebView wv = getBridge().getWebView();
        float dens = getResources().getDisplayMetrics().density;
        ViewCompat.setOnApplyWindowInsetsListener(wv, (v, insets) -> {
            Insets si = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            String js = "document.documentElement.style.setProperty('--sat','"
                + Math.round(si.top / dens) + "px');"
                + "document.documentElement.style.setProperty('--sab','"
                + Math.round(si.bottom / dens) + "px');";
            wv.evaluateJavascript(js, null);
            return insets;
        });
    }
}
