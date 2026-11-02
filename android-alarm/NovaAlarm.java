package __PACKAGE__;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.util.Calendar;
import java.util.Locale;

/**
 * El despertador de Órbita (3.5). Guarda la hora en el móvil, la programa en Android como una alarma de verdad
 * (sale el icono del reloj arriba y suena aunque el móvil esté en reposo) y vuelve a programarla tras reiniciar.
 * Cuando suena, Órbita pone tu música; si en 40 segundos no lo ha conseguido, suena la alarma normal del móvil.
 */
final class NovaAlarm {
    static final String PREFS = "orbita_despertador";
    static final String ACTION_FIRE = "orbita.despertador.SUENA";
    static final String ACTION_BACKUP = "orbita.despertador.RESPALDO";
    static final String ACTION_SNOOZE = "orbita.despertador.POSPONER";
    static final String ACTION_STOP = "orbita.despertador.APAGAR";
    static final String EXTRA_RING = "orbitaAlarm";
    static final String CH_QUIET = "despertador";
    static final String CH_LOUD = "despertador_respaldo";
    static final int NOTIF_ID = 7301;
    static final int RC_ALARM = 7301, RC_SNOOZE = 7302, RC_SHOW = 7303, RC_BACKUP = 7304, RC_OPEN = 7305, RC_ACT_SNOOZE = 7306, RC_ACT_STOP = 7307;
    static final int RINGING = 1, PLAYING = 2, DONE = 3;
    static final long BACKUP_MS = 40_000L;
    private static final int PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    private NovaAlarm() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** La próxima vez que toca: hoy o los días siguientes (días: bit 0 = domingo … bit 6 = sábado; 0 = una sola vez). */
    static long nextTrigger(SharedPreferences p, long now) {
        int h = p.getInt("hour", 7), m = p.getInt("minute", 30), days = p.getInt("days", 0);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        for (int i = 0; i < 9; i++) {
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            boolean dayOk = days == 0 || (days & (1 << (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0;
            if (c.getTimeInMillis() > now + 1000 && dayOk) return c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return -1;
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        try {
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean notificationsOk(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    static boolean fullScreenOk(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return true;
        }
    }

    private static PendingIntent fireIntent(Context c, int rc, String kind) {
        Intent i = new Intent(c, NovaAlarmReceiver.class).setAction(ACTION_FIRE).putExtra("kind", kind);
        return PendingIntent.getBroadcast(c, rc, i, PI_FLAGS);
    }

    /** Abre Órbita; con «ring» además le dice que viene del despertador (y se ve encima de la pantalla de bloqueo). */
    static Intent openIntent(Context c, long ring) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setPackage(c.getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (ring > 0) i.putExtra(EXTRA_RING, ring);
        return i;
    }

    /** Programa (o quita) la próxima alarma y la de «posponer». Devuelve cuándo sonará la próxima, o -1. */
    static long schedule(Context c) {
        SharedPreferences p = prefs(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return -1;
        long now = System.currentTimeMillis();
        PendingIntent main = fireIntent(c, RC_ALARM, "alarm"), snz = fireIntent(c, RC_SNOOZE, "snooze");
        am.cancel(main);
        am.cancel(snz);
        long next = p.getBoolean("on", false) ? nextTrigger(p, now) : -1;
        if (next > 0) setAt(c, am, next, main);
        long sa = p.getLong("snoozeAt", 0);
        if (sa > now) setAt(c, am, sa, snz);
        else if (sa != 0) p.edit().putLong("snoozeAt", 0).apply();
        p.edit().putLong("next", next).apply();
        return next;
    }

    private static void setAt(Context c, AlarmManager am, long at, PendingIntent op) {
        PendingIntent show = PendingIntent.getActivity(c, RC_SHOW, openIntent(c, 0), PI_FLAGS);
        try {
            if (canExact(c)) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), op);
                return;
            }
        } catch (SecurityException ignored) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
    }

    /** Dentro de 40 s: si la música no ha empezado, que suene la alarma del móvil. */
    static void armBackup(Context c, long ring) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = backupIntent(c, ring);
        long at = System.currentTimeMillis() + BACKUP_MS;
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelBackup(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(backupIntent(c, 0));
    }

    private static PendingIntent backupIntent(Context c, long ring) {
        Intent i = new Intent(c, NovaAlarmReceiver.class).setAction(ACTION_BACKUP).putExtra("ring", ring);
        return PendingIntent.getBroadcast(c, RC_BACKUP, i, PI_FLAGS);
    }

    static Uri alarmSound() {
        Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        return u;
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_QUIET) == null) {
            NotificationChannel q = new NotificationChannel(CH_QUIET, "Despertador", NotificationManager.IMPORTANCE_HIGH);
            q.setDescription("Cuando suena el despertador y Órbita pone tu música");
            q.setSound(null, null);
            q.enableVibration(false);
            q.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(q);
        }
        if (nm.getNotificationChannel(CH_LOUD) == null) {
            NotificationChannel l = new NotificationChannel(CH_LOUD, "Despertador: sonido de respaldo", NotificationManager.IMPORTANCE_HIGH);
            l.setDescription("Si Órbita no puede poner tu música, suena la alarma del móvil");
            l.setSound(alarmSound(), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            l.enableVibration(true);
            l.setVibrationPattern(new long[] { 0, 600, 400, 600 });
            l.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(l);
        }
    }

    /** El aviso del despertador: a pantalla completa con el móvil bloqueado; con «Posponer» y «Apagar». */
    static void notifyRing(Context c, long ring, boolean loud, String text, boolean update) {
        channels(c);
        SharedPreferences p = prefs(c);
        int icon = c.getResources().getIdentifier("ic_stat_despertador", "drawable", c.getPackageName());
        if (icon == 0) icon = c.getApplicationInfo().icon;
        PendingIntent open = PendingIntent.getActivity(c, RC_OPEN, openIntent(c, ring), PI_FLAGS);
        PendingIntent snooze = PendingIntent.getBroadcast(c, RC_ACT_SNOOZE, new Intent(c, NovaAlarmReceiver.class).setAction(ACTION_SNOOZE), PI_FLAGS);
        PendingIntent stop = PendingIntent.getBroadcast(c, RC_ACT_STOP, new Intent(c, NovaAlarmReceiver.class).setAction(ACTION_STOP), PI_FLAGS);
        Calendar now = Calendar.getInstance();
        String time = String.format(Locale.US, "%d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        int min = p.getInt("snoozeMin", 10);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, loud ? CH_LOUD : CH_QUIET)
            .setSmallIcon(icon)
            .setContentTitle(greeting(now.get(Calendar.HOUR_OF_DAY)) + " · " + time)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(update)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Posponer " + min + " min", snooze)
            .addAction(0, "Apagar", stop);
        if (loud) {
            b.setSound(alarmSound(), AudioManager.STREAM_ALARM);
            b.setVibrate(new long[] { 0, 600, 400, 600 });
            b.setTimeoutAfter(15 * 60_000L);
        }
        Notification n = b.build();
        if (loud) n.flags |= Notification.FLAG_INSISTENT;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
        }
    }

    static String greeting(int h) {
        return h >= 5 && h < 13 ? "Buenos días" : h >= 13 && h < 21 ? "Buenas tardes" : "Buenas noches";
    }

    static void cancelNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    /** Ha sonado: se apunta, se programa la siguiente y sale el aviso. */
    static long fire(Context c, String kind) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit();
        if ("snooze".equals(kind)) e.putLong("snoozeAt", 0);
        else if (p.getInt("days", 0) == 0) e.putBoolean("on", false);
        e.putLong("ringAt", now).putString("ringKind", kind).putInt("ringState", RINGING).apply();
        schedule(c);
        notifyRing(c, now, false, "Órbita está poniendo tu música…", false);
        armBackup(c, now);
        return now;
    }

    /** La música ya suena: sin alarma de respaldo, y el aviso dice qué suena. */
    static void ack(Context c, long ring, String title) {
        SharedPreferences p = prefs(c);
        if (p.getLong("ringAt", -1) != ring || p.getInt("ringState", 0) != RINGING) return;
        p.edit().putInt("ringState", PLAYING).apply();
        cancelBackup(c);
        notifyRing(c, ring, false, title == null || title.isEmpty() ? "Suena tu música" : "Suena «" + title + "»", true);
    }

    /** Órbita no ha podido poner música: la alarma del móvil, ya. */
    static void ringLoud(Context c, long ring) {
        SharedPreferences p = prefs(c);
        if (p.getLong("ringAt", -1) != ring || p.getInt("ringState", 0) == DONE) return;
        cancelBackup(c);
        notifyRing(c, ring, true, "No he podido poner tu música: suena la alarma del móvil.", false);
    }

    static long snooze(Context c) {
        SharedPreferences p = prefs(c);
        long at = System.currentTimeMillis() + p.getInt("snoozeMin", 10) * 60_000L;
        p.edit().putLong("snoozeAt", at).putInt("ringState", DONE).apply();
        cancelBackup(c);
        cancelNotification(c);
        restoreVolume(c);
        schedule(c);
        return at;
    }

    /** Apagar. Con «keep» la música sigue sonando (ya te has levantado) y el volumen se queda como está. */
    static void stop(Context c, boolean keep) {
        SharedPreferences p = prefs(c);
        p.edit().putInt("ringState", DONE).apply();
        cancelBackup(c);
        cancelNotification(c);
        if (keep) p.edit().putInt("prevVol", -1).apply();
        else restoreVolume(c);
    }

    /** Que se oiga: el volumen de la música del móvil, como mínimo al porcentaje elegido (luego se devuelve). */
    static void raiseVolume(Context c, int pct) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        SharedPreferences p = prefs(c);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int want = Math.max(1, Math.round(max * Math.max(5, Math.min(100, pct)) / 100f));
        if (cur >= want) return;
        if (p.getInt("prevVol", -1) < 0) p.edit().putInt("prevVol", cur).apply();
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
        } catch (SecurityException ignored) {
        }
    }

    static void restoreVolume(Context c) {
        SharedPreferences p = prefs(c);
        int pv = p.getInt("prevVol", -1);
        if (pv < 0) return;
        p.edit().putInt("prevVol", -1).apply();
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, pv, 0);
        } catch (SecurityException ignored) {
        }
    }
}
