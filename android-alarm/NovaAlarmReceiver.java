package __PACKAGE__;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Lo que llega del despertador: que suena, el respaldo de los 40 s y los botones del aviso. */
public class NovaAlarmReceiver extends BroadcastReceiver {
    // guardado aquí para que no se libere antes de tiempo
    private static PowerManager.WakeLock wake;

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        try {
            if (NovaAlarm.ACTION_FIRE.equals(a)) {
                // unos segundos despierto para que dé tiempo a abrir Órbita y empezar la música
                try {
                    PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        if (wake == null) {
                            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orbita:despertador");
                            wake.setReferenceCounted(false);
                        }
                        wake.acquire(30_000L);
                    }
                } catch (Throwable ignored) {
                }
                String kind = intent.getStringExtra("kind");
                if (kind == null) kind = "alarm";
                long ring = NovaAlarm.fire(c, kind);
                NovaAlarmPlugin.emit("fire", ring, kind);
            } else if (NovaAlarm.ACTION_BACKUP.equals(a)) {
                long ring = intent.getLongExtra("ring", 0);
                if (ring == NovaAlarm.prefs(c).getLong("ringAt", -1) && NovaAlarm.prefs(c).getInt("ringState", 0) == NovaAlarm.RINGING) {
                    NovaAlarm.ringLoud(c, ring);
                    NovaAlarmPlugin.emit("backup", ring, null);
                }
            } else if (NovaAlarm.ACTION_SNOOZE.equals(a)) {
                long at = NovaAlarm.snooze(c);
                NovaAlarmPlugin.emit("snooze", at, null);
                NovaAlarmPlugin.releaseLock();
            } else if (NovaAlarm.ACTION_STOP.equals(a)) {
                NovaAlarm.stop(c, false);
                NovaAlarmPlugin.emit("stop", 0, null);
                NovaAlarmPlugin.releaseLock();
            }
        } catch (Throwable ignored) {
            // un despertador nunca debe cerrar la app
        }
    }
}
