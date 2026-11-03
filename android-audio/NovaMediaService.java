package __PACKAGE__;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Mantiene Órbita en primer plano mientras suena con el motor de Android (lo que hace un reproductor de música de
 * verdad): así Android no la congela con la pantalla apagada. También recibe los botones del aviso.
 */
public class NovaMediaService extends Service {
    static final String PREFIX = "orbita.musica.";
    static volatile NovaMediaService running;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = this;
        NovaSession s = NovaSession.current;
        try {
            Notification n = s != null ? s.build() : new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NovaSession.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NovaSession.NOTIF_ID, n);
        } catch (Throwable ignored) {
        }
        String a = intent != null ? intent.getAction() : null;
        if (a != null && a.startsWith(PREFIX)) {
            NovaAudioPlugin p = NovaAudioPlugin.instance;
            if (p != null) p.button(a.substring(PREFIX.length()), 0);
        }
        if (s == null) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // si se cierra Órbita desde las recientes estando en pausa, el aviso se va; si está sonando, sigue
        NovaSession s = NovaSession.current;
        if (s == null || !s.playing) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }
}
