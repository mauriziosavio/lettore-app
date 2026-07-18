package com.mauriziosavio.lettore;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Ponte JS ⇄ servizio di lettura: LetturaService.start() / LetturaService.stop(). */
@CapacitorPlugin(name = "LetturaService")
public class LetturaServicePlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        // Su Android 13+ la notifica del servizio è visibile solo col permesso:
        // lo chiediamo al primo avvio; il servizio parte comunque.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9001);
        }
        Intent i = new Intent(getContext(), ReadingService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            getContext().startForegroundService(i);
        } else {
            getContext().startService(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), ReadingService.class));
        call.resolve();
    }
}
