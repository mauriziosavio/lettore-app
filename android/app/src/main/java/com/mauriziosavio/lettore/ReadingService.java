package com.mauriziosavio.lettore;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Servizio in primo piano attivo mentre Lettore legge: tiene il processo (e il
 * WebView che decide la frase successiva) al riparo da freeze e risparmio
 * batteria, con un wake lock parziale così la CPU resta attiva a schermo spento.
 */
public class ReadingService extends Service {
    private static final String CHANNEL_ID = "lettura";
    private static final int NOTIFICATION_ID = 1;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Lettura in corso", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene attiva la lettura ad alta voce");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        Notification n = b
            .setContentTitle("Lettore")
            .setContentText("Lettura ad alta voce in corso")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lettore:lettura");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(6 * 60 * 60 * 1000L); // limite di sicurezza: 6 ore
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
