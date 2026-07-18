package com.mauriziosavio.lettore;

import android.os.Build;
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
        // Di serie Android "rinuncia" alla priorità del renderer quando l'app non è
        // visibile e ne congela il JavaScript dopo ~1 minuto: con schermo spento
        // nessuno passerebbe più la frase successiva alla voce. Qui gli diciamo di
        // tenere il renderer importante anche in background (il servizio in primo
        // piano fa il resto).
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
        } catch (Throwable ignored) { }
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
