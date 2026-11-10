package __PACKAGE__

import android.os.Build
import android.util.Base64
import android.webkit.WebView
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * El motor de sonido de Android para Órbita, visto desde la parte web de la app. 4.2: en Kotlin, y ya no es el dueño
 * del motor: el reproductor (NovaCore) vive aparte y sigue sonando aunque se cierre la app. Mismos métodos y mismos
 * avisos que en la 4.1, más «attach» (al abrir la app, recoger lo que suena).
 */
@CapacitorPlugin(name = "NovaAudio")
class NovaAudioPlugin : Plugin(), NovaCore.Sink {

    companion object {
        /** 3.6 · Los botones del widget (OrbitaWidget lo busca por su nombre): los hace el motor al momento. */
        @JvmStatic
        fun widgetButton(action: String): Boolean = try { NovaCore.widgetButton(action) } catch (t: Throwable) { false }
    }

    override fun load() {
        NovaCore.init(context)
        NovaCore.sink = this
    }

    override fun handleOnDestroy() {
        try { if (NovaCore.sink === this) NovaCore.appGone() } catch (ignored: Throwable) { }
        super.handleOnDestroy()
    }

    // ---------------------------------------------------------------- los avisos del motor, a la app
    override fun audio(o: JSONObject) { notifyListeners("audio", JSObject.fromJSONObject(o)) }

    override fun spectrum(b64: String) {
        val o = JSObject()
        o.put("b", b64)
        notifyListeners("spectrum", o)
    }

    private fun slot(call: PluginCall): Int = (call.getInt("slot", 0) ?: 0).coerceIn(0, NovaAudioEngine.SLOTS - 1)

    private fun num(call: PluginCall, k: String, d: Float): Float {
        val o = call.data?.opt(k)
        return if (o is Number) o.toFloat() else d
    }

    private fun bool(call: PluginCall, k: String, d: Boolean): Boolean = call.getBoolean(k, d) == true

    private fun ok(call: PluginCall, r: JSONObject? = null) {
        if (r == null) call.resolve() else call.resolve(JSObject.fromJSONObject(r))
    }

    // ---------------------------------------------------------------- lo que pide la app
    @PluginMethod
    fun status(call: PluginCall) = ok(call, NovaCore.status())

    /** 4.2 · Al abrir la app: lo que suena (si la música siguió con Órbita cerrada), para recogerlo tal cual. */
    @PluginMethod
    fun attach(call: PluginCall) = ok(call, NovaCore.attach())

    /** 3.9 · La app (despierta) ya se encarga de este aviso de otra app: el motor no hace nada por su cuenta. */
    @PluginMethod
    fun focusAck(call: PluginCall) {
        NovaCore.focusAck(call.getInt("id", 0) ?: 0)
        call.resolve()
    }

    @PluginMethod
    fun load(call: PluginCall) {
        val src = call.getString("src")
        if (src.isNullOrEmpty()) { call.reject("sin canción", "NO_SRC"); return }
        NovaCore.load(slot(call), src, num(call, "at", 0f).toDouble(), bool(call, "play", false))
        call.resolve()
    }

    @PluginMethod
    fun play(call: PluginCall) { NovaCore.play(slot(call)); call.resolve() }

    @PluginMethod
    fun pause(call: PluginCall) { NovaCore.pause(slot(call)); call.resolve() }

    @PluginMethod
    fun stop(call: PluginCall) { NovaCore.stop(slot(call)); call.resolve() }

    @PluginMethod
    fun seek(call: PluginCall) { NovaCore.eng().seek(slot(call), num(call, "pos", 0f).toDouble()); call.resolve() }

    @PluginMethod
    fun setVolume(call: PluginCall) { NovaCore.eng().setVolume(slot(call), num(call, "v", 1f)); call.resolve() }

    /** 3.5b4: el volumen igualado de la canción de este plato (cada canción el suyo, también durante un fundido). */
    @PluginMethod
    fun setSongGain(call: PluginCall) { NovaCore.eng().setSongGain(slot(call), num(call, "g", 1f)); call.resolve() }

    @PluginMethod
    fun setNorm(call: PluginCall) { NovaCore.eng().setNorm(slot(call), num(call, "g", 1f)); call.resolve() }

    @PluginMethod
    fun setMaster(call: PluginCall) { NovaCore.eng().setMaster(num(call, "v", 1f)); call.resolve() }

    @PluginMethod
    fun setEq(call: PluginCall) {
        val g = FloatArray(10)
        try {
            val a = call.getArray("gains")
            if (a != null) for (i in 0 until minOf(10, a.length())) g[i] = a.getDouble(i).toFloat()
        } catch (ignored: Throwable) {
        }
        NovaCore.eng().setEq(g, bool(call, "on", true))
        call.resolve()
    }

    @PluginMethod
    fun setDirect(call: PluginCall) { NovaCore.eng().setDirect(bool(call, "on", false)); call.resolve() }

    @PluginMethod
    fun setNext(call: PluginCall) { NovaCore.eng().setNext(slot(call), call.getString("src")); call.resolve() }

    /** 3.5b2: la cola de canciones siguientes (motores antiguos de la app). */
    @PluginMethod
    fun setQueue(call: PluginCall) {
        val arr = call.getArray("srcs")
        val list = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) { val x = arr.optString(i, ""); if (x.isNotEmpty()) list.add(x) }
        NovaCore.eng().setQueue(slot(call), list)
        call.resolve()
    }

    /**
     * 3.5b3: la cola entera, desde la que suena, con lo del aviso (título, artista, disco) y los fundidos. El motor la
     * sigue solo y los botones del aviso la usan para la siguiente y la anterior.
     */
    @PluginMethod
    fun setPlaylist(call: PluginCall) {
        try {
            val arr = call.getArray("items")
            val list = ArrayList<PItem>()
            if (arr != null) for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                PItem.fromJson(j)?.let { list.add(it) }
            }
            val ix = call.getInt("index", 0) ?: 0
            val index = maxOf(0, minOf(list.size - 1, ix))
            NovaCore.setPlaylist(list, index, slot(call), NovaQueue.repeatOf(call.getString("repeat", "off")), maxOf(0, call.getInt("cycle", 0) ?: 0))
        } catch (t: Throwable) {
            call.reject("No he podido poner la cola: " + t.message, "PLAYLIST")
            return
        }
        call.resolve()
    }

    /** 3.5b3: lo que suena fuera del motor (radio, YouTube…), para el aviso. Sus botones los atiende la app. */
    @PluginMethod
    fun setNowPlaying(call: PluginCall) {
        NovaCore.setExternal(call.getString("title", "") ?: "", call.getString("artist", "") ?: "", call.getString("album", "") ?: "",
            call.getString("art", null), bool(call, "playing", false), num(call, "pos", 0f).toDouble(), num(call, "dur", 0f).toDouble())
        call.resolve()
    }

    @PluginMethod
    fun sessionEnd(call: PluginCall) { NovaCore.sessionEnd(); call.resolve() }

    /**
     * 3.6 · Las escuchas que apuntó el motor y la app aún no tiene. «after»: la última que la app ya guardó (se borran
     * aquí las que ya tiene).
     */
    @PluginMethod
    fun takeListens(call: PluginCall) {
        val a = call.data?.opt("after")
        val after = if (a is Number) a.toLong() else 0L
        try {
            ok(call, NovaCore.takeListens(after))
        } catch (t: Throwable) {
            call.reject("No he podido leer las escuchas: " + t.message, "LISTENS")
        }
    }

    /** 3.6 · El temporizador para dormir: «at» (hora del móvil, en ms; 0 lo quita) y cuánto dura el fundido final. */
    @PluginMethod
    fun setSleep(call: PluginCall) {
        val a = call.data?.opt("at")
        val f = call.data?.opt("fade")
        NovaCore.setSleep(if (a is Number) a.toLong() else 0L, if (f is Number) f.toLong() else null)
        call.resolve()
    }

    /**
     * 3.5b2: que Android no duerma la parte web de Órbita en segundo plano. Con el motor de Android el WebView no
     * suena, y sin esto Android lo puede congelar: la canción acaba y nadie pone la siguiente.
     */
    @PluginMethod
    fun keepAlive(call: PluginCall) {
        val on = bool(call, "on", true)
        val wv: WebView? = bridge?.webView
        if (wv == null || Build.VERSION.SDK_INT < 26) { call.resolve(); return }
        wv.post {
            try {
                wv.setRendererPriorityPolicy(if (on) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND, !on)
            } catch (ignored: Throwable) {
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun viz(call: PluginCall) { NovaCore.eng().setViz(bool(call, "on", false)); call.resolve() }

    @PluginMethod
    fun focus(call: PluginCall) {
        val r = JSObject()
        r.put("granted", NovaCore.refocus())
        call.resolve(r)
    }

    @PluginMethod
    fun position(call: PluginCall) = ok(call, NovaCore.position(slot(call)))

    /** Las canciones que no están en el almacenamiento del móvil (importadas o de demostración) pasan por un archivo temporal. */
    @PluginMethod
    fun writeTemp(call: PluginCall) {
        val name = (call.getString("name", "cancion") ?: "cancion").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val data = call.getString("data", "") ?: ""
        val append = bool(call, "append", false)
        val done = bool(call, "done", false)
        Thread({
            try {
                val dir = File(context.cacheDir, "nova-audio")
                if (!dir.exists()) dir.mkdirs()
                val part = File(dir, "$name.part")
                FileOutputStream(part, append).use { fos -> if (data.isNotEmpty()) fos.write(Base64.decode(data, Base64.DEFAULT)) }
                val r = JSObject()
                if (done) {
                    val fin = File(dir, name)
                    if (fin.exists()) fin.delete()
                    if (!part.renameTo(fin)) throw Exception("no se pudo renombrar")
                    r.put("path", fin.absolutePath)
                    r.put("size", fin.length())
                    val all = dir.listFiles { _, n -> !n.endsWith(".part") }
                    if (all != null && all.size > 6) {
                        all.sortBy { it.lastModified() }
                        for (i in 0 until all.size - 6) if (all[i] != fin) all[i].delete()
                    }
                } else r.put("size", part.length())
                call.resolve(r)
            } catch (t: Throwable) {
                call.reject("No he podido preparar la canción: " + t.message, "TEMP_FAILED")
            }
        }, "orbita-temporal").start()
    }
}
