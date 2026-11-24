package de.narrhalla.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Vordergrund-Dienst: hält Prozess und Audio wach, solange er läuft.
 * Eine MediaSession sorgt dafür, dass Sperrbildschirm, Kopfhörer- und Bluetooth-Box-Tasten
 * (Play/Pause/Weiter) an die App gehen.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "keepalive";
    private static final String ACT = "de.narrhalla.player.ACTION_";
    private PowerManager.WakeLock lock;
    private MediaSession session;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, KeepAliveService.class).setAction(ACT + action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void ensureSession() {
        if (session != null) return;
        session = new MediaSession(this, "NarrhallaPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { KeepAlivePlugin.sendAction("play"); }
            @Override public void onPause() { KeepAlivePlugin.sendAction("pause"); }
            @Override public void onStop() { KeepAlivePlugin.sendAction("stop"); }
            @Override public void onSkipToNext() { KeepAlivePlugin.sendAction("next"); }
            @Override public void onSkipToPrevious() { KeepAlivePlugin.sendAction("replay"); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a != null && a.startsWith(ACT)) {
            KeepAlivePlugin.sendAction(a.substring(ACT.length()));
            return START_NOT_STICKY;
        }
        String text = intent != null && intent.getStringExtra("text") != null ? intent.getStringExtra("text") : "Bluetooth bleibt verbunden";
        String title = intent != null && intent.getStringExtra("title") != null ? intent.getStringExtra("title") : "Narrhalla Player";
        boolean playing = intent != null && intent.getBooleanExtra("playing", false);

        ensureSession();
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build());

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Wiedergabe aktiv", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setOngoing(true)
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Von vorn", actionIntent("replay", 1)).build())
            .addAction(playing
                ? new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pause", actionIntent("pause", 2)).build()
                : new Notification.Action.Builder(android.R.drawable.ic_media_play, "Abspielen", actionIntent("play", 3)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stopp", actionIntent("stop", 4)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);

        if (lock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "narrhalla:keepalive");
            lock.setReferenceCounted(false);
            // Sicherheitsnetz: spätestens nach 4 Stunden freigeben
            lock.acquire(4 * 60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    /** App weggewischt: nichts mehr wach halten. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }
}
