package com.mauriziosavio.lettore;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Ponte JS ⇄ servizio di lettura.
 * JS → nativo: start/setState/stop per il mini-lettore.
 * Nativo → JS: evento "mediaAction" {azione: play|pause|next|prev} dai controlli media.
 */
@CapacitorPlugin(name = "LetturaService")
public class LetturaServicePlugin extends Plugin {

    private static LetturaServicePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    static void sendAction(String azione) {
        LetturaServicePlugin p = instance;
        if (p != null) {
            JSObject o = new JSObject();
            o.put("azione", azione);
            p.notifyListeners("mediaAction", o);
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        // Su Android 13+ la notifica del servizio è visibile solo col permesso:
        // lo chiediamo al primo avvio; il servizio parte comunque.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9001);
        }
        ReadingService.update(getContext(),
            call.getBoolean("playing", true),
            call.getString("title", ""),
            call.getString("sub", ""));
        call.resolve();
    }

    @PluginMethod
    public void setState(PluginCall call) {
        ReadingService.update(getContext(),
            call.getBoolean("playing"),
            call.getString("title"),
            call.getString("sub"));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), ReadingService.class));
        call.resolve();
    }
}
