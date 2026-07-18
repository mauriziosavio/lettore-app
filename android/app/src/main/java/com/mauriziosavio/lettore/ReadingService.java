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
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

/**
 * Servizio in primo piano attivo mentre Lettore legge: tiene il processo (e il
 * WebView che decide la frase successiva) al riparo da freeze e risparmio
 * batteria, con un wake lock parziale così la CPU resta attiva a schermo spento.
 * Espone una MediaSession: il mini-lettore di Android (notifica e schermata di
 * blocco) mostra pausa/riprendi e frase precedente/successiva.
 */
public class ReadingService extends Service {
    private static final String CHANNEL_ID = "lettura";
    private static final int NOTIFICATION_ID = 1;
    static final String AZ_PLAY = "com.mauriziosavio.lettore.PLAY";
    static final String AZ_PAUSE = "com.mauriziosavio.lettore.PAUSE";
    static final String AZ_NEXT = "com.mauriziosavio.lettore.NEXT";
    static final String AZ_PREV = "com.mauriziosavio.lettore.PREV";

    private static ReadingService instance;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat session;
    private boolean playing = true;
    private String title = "Lettore";
    private String sub = "Lettura ad alta voce";

    /** Aggiorna lo stato del mini-lettore; avvia il servizio se non è in piedi. */
    static void update(Context c, Boolean playing, String title, String sub) {
        ReadingService s = instance;
        if (s != null) {
            if (playing != null) s.playing = playing;
            if (title != null && !title.isEmpty()) s.title = title;
            if (sub != null && !sub.isEmpty()) s.sub = sub;
            s.refresh();
        } else {
            Intent i = new Intent(c, ReadingService.class);
            if (playing != null) i.putExtra("playing", playing.booleanValue());
            if (title != null) i.putExtra("title", title);
            if (sub != null) i.putExtra("sub", sub);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Lettura in corso", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mini-lettore e lettura in sottofondo");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        // I controlli media di sistema (schermata di blocco, tendina) parlano con
        // questa sessione: ogni comando viene girato al WebView tramite il plugin.
        session = new MediaSessionCompat(this, "lettore");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { LetturaServicePlugin.sendAction("play"); }
            @Override public void onPause() { LetturaServicePlugin.sendAction("pause"); }
            @Override public void onStop() { LetturaServicePlugin.sendAction("pause"); }
            @Override public void onSkipToNext() { LetturaServicePlugin.sendAction("next"); }
            @Override public void onSkipToPrevious() { LetturaServicePlugin.sendAction("prev"); }
        });
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lettore:lettura");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String az = intent != null ? intent.getAction() : null;
        if (AZ_PLAY.equals(az)) { LetturaServicePlugin.sendAction("play"); return START_NOT_STICKY; }
        if (AZ_PAUSE.equals(az)) { LetturaServicePlugin.sendAction("pause"); return START_NOT_STICKY; }
        if (AZ_NEXT.equals(az)) { LetturaServicePlugin.sendAction("next"); return START_NOT_STICKY; }
        if (AZ_PREV.equals(az)) { LetturaServicePlugin.sendAction("prev"); return START_NOT_STICKY; }
        if (intent != null) {
            playing = intent.getBooleanExtra("playing", playing);
            String t = intent.getStringExtra("title");
            if (t != null && !t.isEmpty()) title = t;
            String s = intent.getStringExtra("sub");
            if (s != null && !s.isEmpty()) sub = s;
        }
        refresh();
        return START_NOT_STICKY;
    }

    private PendingIntent azione(String az, int rc) {
        Intent i = new Intent(this, ReadingService.class).setAction(az);
        return PendingIntent.getService(this, rc, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private void refresh() {
        session.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sub)
            .build());
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
            .build());

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
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        // CPU sveglia solo mentre legge davvero
        if (playing) wakeLock.acquire(6 * 60 * 60 * 1000L); // limite di sicurezza: 6 ore
        else if (wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // app chiusa dalle recenti: il WebView non c'è più, inutile restare in piedi
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
