package __PACKAGE__;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.view.WindowManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * El despertador con Nova (3.5), para la parte web de Órbita: guardar la hora, saber si ha sonado, decir que la
 * música ya suena, posponer, apagar, subir el volumen y pedir los permisos que hacen falta.
 */
@CapacitorPlugin(
    name = "NovaAlarm",
    permissions = { @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" }) }
)
public class NovaAlarmPlugin extends Plugin {

    static volatile NovaAlarmPlugin instance;

    @Override
    public void load() {
        instance = this;
        Activity a = getActivity();
        if (a != null) checkIntent(a.getIntent());
        // por si se perdió (app actualizada, hora cambiada…): la alarma, siempre programada
        try {
            NovaAlarm.schedule(getContext());
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        checkIntent(intent);
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }

    private void checkIntent(Intent i) {
        if (i == null || !i.hasExtra(NovaAlarm.EXTRA_RING)) return;
        long ring = i.getLongExtra(NovaAlarm.EXTRA_RING, 0);
        i.removeExtra(NovaAlarm.EXTRA_RING);
        SharedPreferences p = NovaAlarm.prefs(getContext());
        if (ring <= 0 || p.getInt("ringState", 0) == NovaAlarm.DONE) return;
        overLock(true);
        JSObject o = new JSObject();
        o.put("type", "open");
        o.put("ring", ring);
        o.put("kind", p.getString("ringKind", "alarm"));
        notifyListeners("alarm", o, true);
    }

    /** Encima de la pantalla de bloqueo y con la pantalla encendida, solo mientras suena el despertador. */
    private void overLock(final boolean on) {
        final Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 27) {
                    a.setShowWhenLocked(on);
                    a.setTurnScreenOn(on);
                } else {
                    int f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
                    if (on) a.getWindow().addFlags(f);
                    else a.getWindow().clearFlags(f);
                }
                if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Throwable ignored) {
            }
        });
    }

    static void releaseLock() {
        NovaAlarmPlugin p = instance;
        if (p != null) p.overLock(false);
    }

    static void emit(String type, long ring, String kind) {
        NovaAlarmPlugin p = instance;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("type", type);
        o.put("ring", ring);
        if (kind != null) o.put("kind", kind);
        p.notifyListeners("alarm", o, true);
    }

    private JSObject state() {
        Context c = getContext();
        SharedPreferences p = NovaAlarm.prefs(c);
        JSObject r = new JSObject();
        r.put("on", p.getBoolean("on", false));
        r.put("hour", p.getInt("hour", 7));
        r.put("minute", p.getInt("minute", 30));
        int d = p.getInt("days", 0);
        JSArray days = new JSArray();
        for (int k = 0; k < 7; k++) if ((d & (1 << k)) != 0) days.put(k);
        r.put("days", days);
        r.put("snoozeMin", p.getInt("snoozeMin", 10));
        r.put("volume", p.getInt("volume", 60));
        r.put("next", p.getLong("next", -1));
        r.put("snoozeAt", p.getLong("snoozeAt", 0));
        r.put("ringAt", p.getLong("ringAt", 0));
        r.put("ringState", p.getInt("ringState", 0));
        r.put("ringKind", p.getString("ringKind", "alarm"));
        r.put("exact", NovaAlarm.canExact(c));
        r.put("notif", NovaAlarm.notificationsOk(c));
        r.put("fullScreen", NovaAlarm.fullScreenOk(c));
        r.put("now", System.currentTimeMillis());
        return r;
    }

    @PluginMethod
    public void get(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void set(PluginCall call) {
        SharedPreferences p = NovaAlarm.prefs(getContext());
        SharedPreferences.Editor e = p.edit();
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", p.getBoolean("on", false)));
        e.putBoolean("on", on);
        e.putInt("hour", Math.max(0, Math.min(23, call.getInt("hour", p.getInt("hour", 7)))));
        e.putInt("minute", Math.max(0, Math.min(59, call.getInt("minute", p.getInt("minute", 30)))));
        e.putInt("snoozeMin", Math.max(1, Math.min(60, call.getInt("snoozeMin", p.getInt("snoozeMin", 10)))));
        e.putInt("volume", Math.max(5, Math.min(100, call.getInt("volume", p.getInt("volume", 60)))));
        JSArray days = call.getArray("days");
        if (days != null) {
            int bits = 0;
            for (int k = 0; k < days.length(); k++) {
                int v = days.optInt(k, -1);
                if (v >= 0 && v <= 6) bits |= 1 << v;
            }
            e.putInt("days", bits);
        }
        if (!on) e.putLong("snoozeAt", 0);
        e.apply();
        NovaAlarm.schedule(getContext());
        call.resolve(state());
    }

    @PluginMethod
    public void ack(PluginCall call) {
        long ring = call.getData().optLong("ring", 0);
        if (ring > 0) NovaAlarm.ack(getContext(), ring, call.getString("title", ""));
        call.resolve(state());
    }

    @PluginMethod
    public void ring(PluginCall call) {
        long ring = call.getData().optLong("ring", 0);
        if (ring > 0) NovaAlarm.ringLoud(getContext(), ring);
        call.resolve();
    }

    @PluginMethod
    public void snooze(PluginCall call) {
        long at = NovaAlarm.snooze(getContext());
        overLock(false);
        JSObject r = state();
        r.put("at", at);
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        NovaAlarm.stop(getContext(), Boolean.TRUE.equals(call.getBoolean("keep", false)));
        overLock(false);
        call.resolve(state());
    }

    @PluginMethod
    public void wake(PluginCall call) {
        NovaAlarm.raiseVolume(getContext(), call.getInt("volume", NovaAlarm.prefs(getContext()).getInt("volume", 60)));
        call.resolve();
    }

    @PluginMethod
    public void requestNotif(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            call.resolve(state());
            return;
        }
        requestPermissionForAlias("notifications", call, "notifDone");
    }

    @PermissionCallback
    private void notifDone(PluginCall call) {
        call.resolve(state());
    }

    /** Los ajustes del sistema que pueden hacer falta: alarmas exactas, pantalla completa, avisos. */
    @PluginMethod
    public void openSettings(PluginCall call) {
        String what = call.getString("what", "app");
        Context c = getContext();
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        Intent i;
        if ("exact".equals(what) && Build.VERSION.SDK_INT >= 31) i = new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", pkg);
        else if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) i = new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", pkg);
        else if ("notif".equals(what) && Build.VERSION.SDK_INT >= 26) i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", c.getPackageName());
        else i = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
        call.resolve();
    }
}
