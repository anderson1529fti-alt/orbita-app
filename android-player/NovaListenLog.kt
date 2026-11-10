package __PACKAGE__

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 3.6 · Lo que suena con la pantalla apagada también cuenta. El motor apunta cada canción que pone (cuántos segundos
 * sonó, cuándo y cómo acabó) aunque la parte web de la app esté dormida o cerrada; la app se lo lleva al despertar y lo
 * suma a «Tú», a «Tu año en Órbita» y a lo que Nova aprende. Solo se guarda en el móvil.
 * 4.2: pasado a Kotlin tal cual (mismos datos y mismo formato que la 4.1).
 */
class NovaListenLog(private val sp: SharedPreferences) {
    companion object {
        const val PREFS = "orbita_escuchas"
        private const val MAX = 400
    }

    private val log: JSONArray = try { JSONArray(sp.getString("log", "[]")) } catch (t: Throwable) { JSONArray() }
    private var seq: Long = sp.getLong("seq", 0)
    // la que está sonando ahora
    private var src: String? = null
    private var title = ""
    private var artist = ""
    private var slot = -1
    private var dur = 0.0
    private var sec = 0.0
    private var lastPos = -1.0
    private var savedSec = 0.0
    private var start = 0L

    init {
        // la que sonaba cuando se cerró la app (o Android la cerró): cuenta hasta donde llegó
        try {
            val cur = sp.getString("cur", null)
            if (cur != null) {
                val o = JSONObject(cur)
                if (o.optDouble("sec", 0.0) >= 1) add(o.optString("src"), o.optString("title"), o.optString("artist"), o.optDouble("dur", 0.0),
                    o.optLong("start", 0), o.optLong("ts", System.currentTimeMillis()), o.optDouble("sec", 0.0), "corte")
                sp.edit().remove("cur").apply()
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Cuatro veces por segundo, lo que suena en el plato del aviso. Los segundos salen de lo que avanza la canción
     * (como en la app: un salto de más de 3 s es mover la barra, no escuchar).
     */
    @Synchronized fun tick(slot: Int, src: String?, playing: Boolean, pos: Double, dur: Double, title: String?, artist: String?) {
        if (src.isNullOrEmpty()) { if (this.src != null) close("app"); return }
        if (this.src == null || slot != this.slot || src != this.src) {
            // en el mismo plato la cambió el motor solo; en el otro plato, la puso la app
            if (this.src != null) close(if (slot != this.slot) "app" else "auto")
            open(slot, src, dur, title, artist)
        }
        if (dur > 0) this.dur = dur
        if (!title.isNullOrEmpty()) { this.title = title; this.artist = artist ?: "" }
        if (playing && lastPos >= 0) {
            val d = pos - lastPos
            if (d > 0 && d < 3) sec += d
        }
        lastPos = if (playing) pos else -1.0
        // cada 10 s escuchados se guarda la de ahora (por si Android cierra la app sin avisar)
        if (sec - savedSec >= 10) { savedSec = sec; saveCur(System.currentTimeMillis()) }
    }

    /** La canción de ahora termina ya, por un botón («boton»), porque la app pone otra («app») o al cerrar. */
    @Synchronized fun end(how: String) {
        if (src != null) close(how)
    }

    /** Cuando la canción vuelve a empezar sola (repetir una): una escucha nueva. */
    @Synchronized fun restart(how: String) {
        val s = src ?: return
        val sl = slot; val t = title; val a = artist; val d = dur
        close(how)
        open(sl, s, d, t, a)
    }

    /** Lo que la app aún no tiene: borra lo que ya se llevó (hasta «after») y da el resto. */
    @Synchronized fun take(after: Long): JSONArray {
        val keep = JSONArray()
        for (i in 0 until log.length()) {
            val o = log.optJSONObject(i)
            if (o != null && o.optLong("seq", 0) > after) keep.put(o)
        }
        if (keep.length() != log.length()) {
            while (log.length() > 0) log.remove(log.length() - 1)
            for (i in 0 until keep.length()) log.put(keep.opt(i))
            sp.edit().putString("log", log.toString()).apply()
        }
        val out = JSONArray()
        for (i in 0 until log.length()) out.put(log.opt(i))
        return out
    }

    @Synchronized fun seq(): Long = seq

    // ---------------------------------------------------------------- por dentro
    private fun open(slot: Int, src: String, dur: Double, title: String?, artist: String?) {
        this.slot = slot; this.src = src; this.dur = dur; this.sec = 0.0; this.lastPos = -1.0
        this.title = title ?: ""; this.artist = artist ?: ""
        this.start = System.currentTimeMillis(); this.savedSec = 0.0
    }

    private fun close(how: String) {
        val s = src
        src = null; slot = -1
        if (s != null && sec >= 1) add(s, title, artist, dur, start, System.currentTimeMillis(), sec, how)
        sec = 0.0; lastPos = -1.0
        sp.edit().remove("cur").apply()
    }

    private fun add(src: String, title: String, artist: String, dur: Double, start: Long, ts: Long, sec: Double, how: String) {
        try {
            val o = JSONObject()
            o.put("seq", ++seq)
            o.put("src", src); o.put("title", title); o.put("artist", artist)
            o.put("dur", Math.round(dur * 10) / 10.0); o.put("start", start); o.put("ts", ts)
            o.put("sec", Math.round(sec * 10) / 10.0); o.put("end", how)
            log.put(o)
            while (log.length() > MAX) log.remove(0)
            sp.edit().putString("log", log.toString()).putLong("seq", seq).apply()
        } catch (ignored: Throwable) {
        }
    }

    private fun saveCur(now: Long) {
        try {
            val o = JSONObject()
            o.put("src", src); o.put("title", title); o.put("artist", artist); o.put("dur", dur)
            o.put("start", start); o.put("ts", now); o.put("sec", sec)
            sp.edit().putString("cur", o.toString()).apply()
        } catch (ignored: Throwable) {
        }
    }
}
