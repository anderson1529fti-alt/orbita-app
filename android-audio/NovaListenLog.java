package __PACKAGE__;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 3.6 · Lo que suena con la pantalla apagada también cuenta. El motor apunta cada canción que pone (cuántos segundos
 * sonó, cuándo y cómo acabó) aunque la parte web de la app esté dormida; la app se lo lleva al despertar y lo suma a
 * «Tú», a «Tu año en Órbita» y a lo que Nova aprende. Solo se guarda en el móvil.
 */
final class NovaListenLog {
    private static final String PREFS = "orbita_escuchas";
    private static final int MAX = 400;

    private final SharedPreferences sp;
    private final JSONArray log;
    private long seq;
    // la que está sonando ahora
    private String src, title = "", artist = "";
    private int slot = -1;
    private double dur, sec, lastPos = -1, savedSec;
    private long start;

    NovaListenLog(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray a;
        try { a = new JSONArray(sp.getString("log", "[]")); } catch (Throwable t) { a = new JSONArray(); }
        log = a;
        seq = sp.getLong("seq", 0);
        // la que sonaba cuando se cerró la app (o Android la cerró): cuenta hasta donde llegó
        try {
            String cur = sp.getString("cur", null);
            if (cur != null) {
                JSONObject o = new JSONObject(cur);
                if (o.optDouble("sec", 0) >= 1) add(o.optString("src"), o.optString("title"), o.optString("artist"), o.optDouble("dur", 0),
                    o.optLong("start", 0), o.optLong("ts", System.currentTimeMillis()), o.optDouble("sec", 0), "corte");
                sp.edit().remove("cur").apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Cuatro veces por segundo, lo que suena en el plato del aviso. Los segundos salen de lo que avanza la canción
     * (como en la app: un salto de más de 3 s es mover la barra, no escuchar).
     */
    synchronized void tick(int slot, String src, boolean playing, double pos, double dur, String title, String artist) {
        if (src == null || src.isEmpty()) { if (this.src != null) close("app"); return; }
        if (this.src == null || slot != this.slot || !src.equals(this.src)) {
            // en el mismo plato la cambió el motor solo; en el otro plato, la puso la app
            if (this.src != null) close(slot != this.slot ? "app" : "auto");
            open(slot, src, dur, title, artist);
        }
        if (dur > 0) this.dur = dur;
        if (title != null && !title.isEmpty()) { this.title = title; this.artist = artist == null ? "" : artist; }
        if (playing && lastPos >= 0) {
            double d = pos - lastPos;
            if (d > 0 && d < 3) sec += d;
        }
        lastPos = playing ? pos : -1;
        // cada 10 s escuchados se guarda la de ahora (por si Android cierra la app sin avisar)
        if (sec - savedSec >= 10) { savedSec = sec; saveCur(System.currentTimeMillis()); }
    }

    /** La canción de ahora termina ya, por un botón («boton»), porque la app pone otra («app») o al cerrar. */
    synchronized void end(String how) {
        if (src != null) close(how);
    }

    /** Cuando la canción vuelve a empezar sola (repetir una): una escucha nueva. */
    synchronized void restart(String how) {
        if (src == null) return;
        int sl = slot; String s = src, t = title, a = artist; double d = dur;
        close(how);
        open(sl, s, d, t, a);
    }

    /** Lo que la app aún no tiene: borra lo que ya se llevó (hasta «after») y da el resto. */
    synchronized JSONArray take(long after) {
        JSONArray keep = new JSONArray();
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o != null && o.optLong("seq", 0) > after) keep.put(o);
        }
        if (keep.length() != log.length()) {
            while (log.length() > 0) log.remove(log.length() - 1);
            for (int i = 0; i < keep.length(); i++) log.put(keep.opt(i));
            sp.edit().putString("log", log.toString()).apply();
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < log.length(); i++) out.put(log.opt(i));
        return out;
    }

    synchronized long seq() { return seq; }

    // ---------------------------------------------------------------- por dentro
    private void open(int slot, String src, double dur, String title, String artist) {
        this.slot = slot; this.src = src; this.dur = dur; this.sec = 0; this.lastPos = -1;
        this.title = title == null ? "" : title; this.artist = artist == null ? "" : artist;
        this.start = System.currentTimeMillis(); this.savedSec = 0;
    }

    private void close(String how) {
        String s = src; src = null; slot = -1;
        if (s != null && sec >= 1) add(s, title, artist, dur, start, System.currentTimeMillis(), sec, how);
        sec = 0; lastPos = -1;
        sp.edit().remove("cur").apply();
    }

    private void add(String src, String title, String artist, double dur, long start, long ts, double sec, String how) {
        try {
            JSONObject o = new JSONObject();
            o.put("seq", ++seq);
            o.put("src", src); o.put("title", title); o.put("artist", artist);
            o.put("dur", Math.round(dur * 10) / 10.0); o.put("start", start); o.put("ts", ts);
            o.put("sec", Math.round(sec * 10) / 10.0); o.put("end", how);
            log.put(o);
            while (log.length() > MAX) log.remove(0);
            sp.edit().putString("log", log.toString()).putLong("seq", seq).apply();
        } catch (Throwable ignored) {
        }
    }

    private void saveCur(long now) {
        try {
            JSONObject o = new JSONObject();
            o.put("src", src); o.put("title", title); o.put("artist", artist); o.put("dur", dur);
            o.put("start", start); o.put("ts", now); o.put("sec", sec);
            sp.edit().putString("cur", o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
