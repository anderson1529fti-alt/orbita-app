package __PACKAGE__

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.getcapacitor.PermissionState
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.roundToInt

/** 4.3 · Una canción de «Mi música», tal como la manda la app. */
class UiTrack(
    val id: String, val title: String, val artist: String, val album: String, val folder: String,
    val dur: Double, val added: Double, val plays: Int, val last: Double,
    val liked: Boolean, val demo: Boolean, val mine: Boolean, val dev: Boolean, val cover: Boolean, val hue: Float
) {
    /** Lo que se busca (igual que en la app: sin acentos, en minúsculas, solo letras y números). */
    val key: String = UiState.norm("$title $artist $album $folder")
    val sub: String = artist + (if (album.isNotEmpty()) " · $album" else "") + (if (dev && folder.isNotEmpty()) " · 📁 $folder" else "")
}

/** Un recuadro con texto y un botón (la música del móvil, las pistas de demostración). */
class UiBox(val title: String, val text: String, val button: String, val act: String, val primary: Boolean, val enabled: Boolean)

/** El aspecto que el usuario ha elegido en «Personaliza Órbita» y el color del momento. */
class UiLook(val h: Float = 276f, val s: Float = 1f, val glowK: Float = 1f, val round: Float = 1f, val tk: Float = 1f, val titles: String = "", val black: Boolean = false)

/** Un mensaje de la conversación: de quien usa la app, o de Nova. */
class UiMsg(val me: Boolean, val text: String)

/**
 * 5.0 · El marco de la capa de las ventanas: lo que se toca en el hueco del vídeo (el videoclip del reproductor) no es
 * de Android, es del vídeo de la web que se ve por él; ese toque, entero (de que baja el dedo a que sube), va a la web.
 */
internal class WinFrame(ctx: android.content.Context, private val web: () -> View?) : android.widget.FrameLayout(ctx) {
    private var through = false
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val h = UiState.ytHole()
            through = h != null && h.contains(androidx.compose.ui.geometry.Offset(ev.x, ev.y))
            if (through) UiState.ytFwd++
            else {
                // 5.3 · la ventana de más arriba en ese punto: si es «de paso» (el resumen del año, la bienvenida, el
                // despertador, el aviso de importar), lo que no es de aquí (escribir, una barra que se arrastra) le llega
                // entero a la web, que la tiene debajo; fuera de las ventanas «de paso» pequeñas, lo de debajo (la
                // pantalla nativa), como si no estuvieran
                val w = UiState.winAt(ev.x, ev.y, resources.displayMetrics.density)
                if (w != null && w.pass) {
                    if (super.dispatchTouchEvent(ev)) return true
                    through = true; UiState.passFwd++
                } else if (w == null && UiState.wins.isNotEmpty() && UiState.wins.all { it.pass }) return false
            }
        }
        if (!through) return super.dispatchTouchEvent(ev)
        val wv = web()
        if (wv != null) {
            val e = android.view.MotionEvent.obtain(ev)
            e.offsetLocation(x - wv.x, y - wv.y)
            try { wv.dispatchTouchEvent(e) } catch (ignored: Throwable) { } finally { e.recycle() }
        }
        val a = ev.actionMasked
        if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) through = false
        return true
    }
}

/** Lo que enseñan las pantallas nativas. Lo escribe la app (por el plugin) y lo lee Compose. */
object UiState {
    /** 5.3 · ¿la ventana de arriba es «de paso»? (lo que se toca en ella va a la web) · cuántos toques se le han pasado */
    fun winPass(): Boolean = wins.lastOrNull()?.pass == true
    /** La ventana de más arriba que tiene ese punto (en px de la capa), o null. */
    internal fun winAt(x: Float, y: Float, d: Float): WDef? {
        for (w in wins.asReversed()) for (f in w.fixed) if (x >= f.x * d && x < (f.x + f.w) * d && y >= f.y * d && y < (f.y + f.h) * d) return w
        return null
    }
    @Volatile var passFwd = 0
    /** 5.0 · el dedo empezó en la letra del reproductor: no baja el reproductor (como en la web: .np-lyrics se salta). */
    @Volatile var npNoDrag = false
    // ---- 5.0 · el videoclip: dónde tiene la web su vídeo y dónde está su sitio en el reproductor nativo (px de la capa)
    var ytDock by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var ytSlot by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    @Volatile var ytSlotR = 0f
    @Volatile var ytFwd = 0
    /** El hueco por el que se ve (y se toca) el vídeo: donde coinciden su sitio en el reproductor y el vídeo de la web. */
    fun ytHole(): androidx.compose.ui.geometry.Rect? {
        val a = ytDock ?: return null
        val b = ytSlot ?: return null
        val r = a.intersect(b)
        return if (r.width >= 2f && r.height >= 2f) r else null
    }
    var look by mutableStateOf(UiLook())
    var tracks by mutableStateOf<List<UiTrack>>(emptyList())
    var filter by mutableStateOf("all")
    var sort by mutableStateOf("recent")
    var search by mutableStateOf("")
    var nowId by mutableStateOf("")
    var meta by mutableStateOf("")
    var warn by mutableStateOf("")          // «N canciones tienen poca calidad…» (la parte en negrita)
    var warnText by mutableStateOf("")
    var dev by mutableStateOf<UiBox?>(null)
    var drop by mutableStateOf("")
    var demos by mutableStateOf<UiBox?>(null)
    var showDev by mutableStateOf(false)     // el filtro «Del móvil»
    var toast by mutableStateOf("")
    var toastN by mutableStateOf(0)
    var toastMs = 2600L
    // ---- 4.4 · la conversación con Nova
    /** Qué pantalla se dibuja: «biblioteca» o «chat». */
    var screen by mutableStateOf("biblioteca")
    var chatName by mutableStateOf("Nova")
    var chatNow by mutableStateOf("")
    var chatMsgs by mutableStateOf<List<UiMsg>>(emptyList())
    var chatChips by mutableStateOf<List<String>>(emptyList())
    var chatMic by mutableStateOf(false)
    /** Lo que se está escribiendo (se conserva si la pantalla se aparta un momento). */
    var chatDraft by mutableStateOf("")
    /** Los márgenes de la pantalla de la web (arriba, derecha, abajo, izquierda): cuentan la barra de estado y los bordes. */
    var chatPad by mutableStateOf(listOf(14f, 16f, 14f, 16f))
    val chatList = LazyListState()
    // ---- 4.5 · «Ahora»: cada parte, tal como la lee la app de su pantalla de siempre
    var hTop by mutableStateOf<JSONObject?>(null)
    var hTick by mutableStateOf<JSONObject?>(null)
    var hDj by mutableStateOf<JSONObject?>(null)
    var hBub by mutableStateOf<JSONObject?>(null)
    var hSlot by mutableStateOf<JSONObject?>(null)
    var hFy by mutableStateOf<JSONObject?>(null)
    var hMixer by mutableStateOf<JSONObject?>(null)
    var hOut by mutableStateOf<JSONObject?>(null)
    /** El disco: qué suena y por dónde va (y cuándo llegó el dato, para seguir contando desde ahí). */
    var hVin by mutableStateOf<JSONObject?>(null)
    @Volatile var hVinAt = 0L
    var homeDraft by mutableStateOf("")
    // ---- 4.6 · las pantallas que siguen («Mis listas»…): cada parte de cada una, con la clave «pantalla.parte»
    val PAGES = setOf("listas", "tu")
    val pages = mutableStateMapOf<String, JSONObject>()
    private val pageScrolls = HashMap<String, ScrollState>()
    fun pageScroll(name: String): ScrollState = pageScrolls.getOrPut(name) { ScrollState(0) }
    // ---- 4.7 · «Buscar» y «Ajustes»: la pantalla entera, tal como la dibuja la web (MirrorScreen.kt)
    val MIRROR = setOf("buscar", "ajustes", "voz")
    /** Dónde va cada parte de cada una, y lo que mide la pantalla entera (en «px» de la web). */
    internal val mirOrder = mutableStateMapOf<String, List<MPos>>()
    internal val mirTotal = mutableStateMapOf<String, Float>()
    /** Las piezas de cada parte, con la clave «pantalla.parte». */
    internal val mirParts = mutableStateMapOf<String, List<MNode>>()
    private val mirLists = HashMap<String, LazyListState>()
    fun mirList(name: String): LazyListState = mirLists.getOrPut(name) { LazyListState() }
    /** Dónde empieza la fila «i» de una pantalla copiada (la primera, arriba del todo). */
    fun mirTop(name: String, i: Int): Float { val o = mirOrder[name] ?: return 0f; return if (i <= 0 || i >= o.size) 0f else o[i].y }
    /** Por dónde va (en «px» de la web, contados como la web: desde arriba del todo). */
    fun mirY(name: String, dpr: Float): Float { val ls = mirList(name); return mirTop(name, ls.firstVisibleItemIndex) + ls.firstVisibleItemScrollOffset / maxOf(0.5f, dpr) }
    /** Llevarla a una altura (la que diga la web). */
    fun mirGo(name: String, y: Float, dpr: Float) {
        val o = mirOrder[name] ?: return
        if (o.isEmpty()) return
        var i = 0
        while (i + 1 < o.size && o[i + 1].y <= y) i++
        mirList(name).requestScrollToItem(i, ((y - mirTop(name, i)) * dpr).roundToInt().coerceAtLeast(0))
    }
    // ---- 4.8 · las ventanas y el reproductor a pantalla completa (WindowsScreen.kt), en su propia capa
    internal var wins by mutableStateOf<List<WDef>>(emptyList())
    /** Las piezas de cada parte de cada ventana, con la clave «ventana.parte». */
    internal val winParts = mutableStateMapOf<String, List<MNode>>()
    private val winLists = HashMap<String, LazyListState>()
    fun winList(id: String): LazyListState = winLists.getOrPut(id) { LazyListState() }
    /** Transparente mientras se baja el reproductor con el dedo (la web lo mueve, debajo). */
    var winAlpha by mutableStateOf(1f)
    /** (Para las pruebas) el número del último envío de la web que se ha puesto. */
    @Volatile var winSeq = 0
    /** Quitar la capa de las ventanas (la pone el plugin). */
    var winGone: () -> Unit = {}
    private fun winOrder(id: String): List<MPos>? = wins.firstOrNull { it.id == id }?.sc?.order
    fun winTop(id: String, i: Int): Float { val o = winOrder(id) ?: return 0f; return if (i <= 0 || i >= o.size) 0f else o[i].y }
    fun winY(id: String, dpr: Float): Float { val ls = winList(id); return winTop(id, ls.firstVisibleItemIndex) + ls.firstVisibleItemScrollOffset / maxOf(0.5f, dpr) }
    fun winGo(id: String, y: Float, dpr: Float) {
        val o = winOrder(id) ?: return
        if (o.isEmpty()) return
        var i = 0
        while (i + 1 < o.size && o[i + 1].y <= y) i++
        winList(id).requestScrollToItem(i, ((y - winTop(id, i)) * dpr).roundToInt().coerceAtLeast(0))
    }
    /** Cada vez que la pantalla nativa se aparta (para que no se quede a la vista nada suyo: una lista desplegada). */
    var hideN by mutableStateOf(0)
    val homeList = LazyListState()

    /** Por dónde va la lista (fuera de la pantalla, para que la app pueda subirla al entrar en «Mi música»). */
    val list = LazyListState()
    val covers = mutableStateMapOf<String, ImageBitmap>()
    val asked = HashSet<String>()
    /** Las portadas guardadas, de la más antigua a la más nueva: al pasar de COVERS_MAX se sueltan las más antiguas. */
    val coverOrder = ArrayDeque<String>()
    const val COVERS_MAX = 250

    fun dropCover(id: String) { covers.remove(id); asked.remove(id); coverOrder.remove(id) }

    /** Avisos a la app: qué ha tocado el usuario. */
    var emit: (String, JSObject) -> Unit = { _, _ -> }

    fun send(type: String, fill: (JSObject) -> Unit = {}) {
        val o = JSObject()
        o.put("type", type)
        fill(o)
        emit(type, o)
    }

    private val MARKS = Regex("[\\u0300-\\u036f]")
    private val OTHER = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")
    fun norm(s: String): String = SPACES.replace(OTHER.replace(MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), ""), " "), " ").trim()

    /** La lista que se ve: el mismo filtro, la misma búsqueda y el mismo orden que la pantalla de siempre. */
    fun filtered(all: List<UiTrack>, filter: String, sort: String, search: String): List<UiTrack> {
        val q = norm(search)
        val out = all.filter { t ->
            (filter != "liked" || t.liked) && (filter != "mine" || t.mine) && (filter != "dev" || t.dev) && (q.isEmpty() || t.key.contains(q))
        }
        val coll = android.icu.text.Collator.getInstance(java.util.Locale("es"))
        coll.strength = android.icu.text.Collator.PRIMARY
        (coll as? android.icu.text.RuleBasedCollator)?.numericCollation = true
        val cmp: Comparator<UiTrack> = when (sort) {
            "title" -> Comparator { a, b -> coll.compare(a.title, b.title) }
            "artist" -> Comparator { a, b ->
                var c = coll.compare(a.artist, b.artist)
                if (c == 0) c = coll.compare(a.album, b.album)
                if (c == 0) c = coll.compare(a.title, b.title)
                c
            }
            "plays" -> Comparator { a, b -> if (b.plays != a.plays) b.plays.compareTo(a.plays) else b.last.compareTo(a.last) }
            else -> Comparator { a, b -> if (a.demo != b.demo) (if (a.demo) 1 else 0) - (if (b.demo) 1 else 0) else b.added.compareTo(a.added) }
        }
        return out.sortedWith(cmp)   // estable, como en la app
    }
}

/**
 * 4.3 · Fase 2: las pantallas nativas de Órbita (Kotlin y Compose). La primera es «Mi música». Se dibujan encima de
 * la zona de contenido de la app de siempre (que sigue poniendo el fondo, el reproductor de abajo y las pestañas) y le
 * cuentan a la app lo que toca el usuario. Si algo falla, la app vuelve sola a la pantalla de siempre.
 */
@CapacitorPlugin(name = "OrbitaUi", permissions = [Permission(alias = "mic", strings = [android.Manifest.permission.RECORD_AUDIO])])
class OrbitaUiPlugin : Plugin() {
    private var host: ComposeView? = null
    private var x = 0f
    private var y = 0f
    private var w = 0f
    private var h = 0f
    private var dpr = 1f

    // ---------------------------------------------------------------- fase 4, parte 2 · los datos, en Android
    /** Pasa los datos de la parte web a Android (si no se ha hecho ya; «force»: otra vez, desde cero). */
    @PluginMethod
    fun dataMigrate(call: PluginCall) {
        val force = call.getBoolean("force", false) ?: false
        activity.runOnUiThread {
            try { Migra.run(activity, force) { ok, msg -> call.resolve(dataInfoObj().apply { put("ok", ok); put("msg", msg) }) } }
            catch (t: Throwable) { call.reject(t.message ?: "error") }
        }
    }

    @PluginMethod
    fun dataInfo(call: PluginCall) { Thread { try { call.resolve(dataInfoObj()) } catch (t: Throwable) { call.reject(t.message ?: "error") } }.start() }

    private fun dataInfoObj(): JSObject {
        Store.init(context)
        val r = JSObject()
        r.put("done", Migra.done()); r.put("state", Migra.state); r.put("detail", Migra.detail); r.put("progress", Migra.progress); r.put("what", Migra.what); r.put("ms", Migra.ms)
        r.put("kv", Store.kvAll().keys.count { !it.startsWith("_") })
        val st = JSObject(); for (s in Store.stores()) st.put(s, Store.count(s)); r.put("stores", st)
        r.put("events", Store.evCount())
        val (bn, bb) = Store.blobStats(); r.put("blobs", bn); r.put("bytes", bb)
        Store.kv(Migra.KEY)?.let { try { r.put("migrado", org.json.JSONObject(it)) } catch (ignored: Throwable) { } }
        return r
    }

    /** Para las pruebas: lo guardado (los ajustes, un almacén, las escuchas, o la huella de un archivo). */
    @PluginMethod
    fun dataDump(call: PluginCall) {
        Thread {
            try {
                Store.init(context)
                val r = JSObject(); val items = JSArray()
                when (call.getString("kind", "kv")) {
                    "kv" -> for ((k, v) in Store.kvAll()) items.put(JSArray().put(k).put(v))
                    "rec" -> for ((k, v) in Store.recs(call.getString("store") ?: "", call.getString("after"), call.getInt("limit", 300) ?: 300)) items.put(JSArray().put(k).put(v))
                    "ev" -> for ((n, v) in Store.events(call.getLong("after", 0L) ?: 0L, call.getInt("limit", 2000) ?: 2000)) items.put(JSArray().put(n).put(v))
                    "hash" -> { val f = Store.file(call.getString("rel") ?: ""); r.put("sha", if (f.isFile) Store.sha256(f) else ""); r.put("size", if (f.isFile) f.length() else -1L) }
                }
                r.put("items", items)
                call.resolve(r)
            } catch (t: Throwable) { call.reject(t.message ?: "error") }
        }.start()
    }

    // ---------------------------------------------------------------- fase 4, parte 2 · Órbita sin la parte web
    /** La página de arranque de la Órbita nativa lo pide: desde aquí todo lo lleva Android ([Nativo]). */
    @PluginMethod
    fun nativo(call: PluginCall) {
        activity.runOnUiThread {
            try {
                Nativo.start(activity, this)
                // (la web no enseña nada en la Órbita nativa: que no se quede con los toques de lo que hay debajo)
                bridge.webView.visibility = View.INVISIBLE
                call.resolve(natInfoObj())
            } catch (t: Throwable) { call.reject(t.message ?: "error") }
        }
    }

    /** Para las pruebas: cómo va la Órbita nativa. */
    @PluginMethod
    fun natInfo(call: PluginCall) { activity.runOnUiThread { try { call.resolve(natInfoObj()) } catch (t: Throwable) { call.reject(t.message ?: "error") } } }

    /** Para las pruebas: «Ahora» tal como lo prepara Android (las mismas piezas que mandaba la web). */
    @PluginMethod
    fun natHome(call: PluginCall) {
        activity.runOnUiThread {
            try {
                Inicio.sync(false)
                val r = JSObject()
                r.put("top", UiState.hTop); r.put("dj", UiState.hDj); r.put("mixer", UiState.hMixer); r.put("out", UiState.hOut); r.put("fy", UiState.hFy); r.put("tick", UiState.hTick)
                r.put("bub", UiState.hBub); r.put("slot", UiState.hSlot); r.put("vin", UiState.hVin)
                call.resolve(r)
            } catch (t: Throwable) { call.reject(t.message ?: "error") }
        }
    }

    /** Para las pruebas: «Mis listas» o «Tú» tal como los prepara Android (las mismas piezas que mandaba la web). */
    @PluginMethod
    fun natPage(call: PluginCall) {
        val name = call.getString("name", "") ?: ""
        activity.runOnUiThread {
            try {
                Paginas.sync(name, true)
                val r = JSObject()
                for ((k, v) in UiState.pages) if (k.startsWith("$name.")) r.put(k.substring(name.length + 1), v)
                call.resolve(r)
            } catch (t: Throwable) { call.reject(t.message ?: "error") }
        }
    }

    /** Para las pruebas: mueve una pantalla nativa hasta «y» (en puntos), para sacar sus capturas. */
    @PluginMethod
    fun natScroll(call: PluginCall) {
        val name = call.getString("name", "") ?: ""
        val y = call.getDouble("y", 0.0) ?: 0.0
        activity.runOnUiThread {
            try {
                val st = UiState.pageScroll(name)
                val px = (y * activity.resources.displayMetrics.density).toFloat()
                st.dispatchRawDelta(px - st.value)
                val r = JSObject(); r.put("y", st.value / activity.resources.displayMetrics.density); r.put("max", st.maxValue / activity.resources.displayMetrics.density)
                call.resolve(r)
            } catch (t: Throwable) { call.reject(t.message ?: "error") }
        }
    }

    /** Para las pruebas: lo que se toca en una pantalla nativa, como si se hubiera tocado. */
    @PluginMethod
    fun natUi(call: PluginCall) {
        val type = call.getString("type", "") ?: ""
        val o = call.getObject("o", JSObject()) ?: JSObject()
        activity.runOnUiThread { try { Nativo.onUi(type, o); call.resolve() } catch (t: Throwable) { call.reject(t.message ?: "error") } }
    }

    private fun natInfoObj(): JSObject {
        val r = JSObject()
        r.put("on", Nativo.on); r.put("ready", Nativo.ready); r.put("songs", UiState.tracks.size); r.put("lib", Lib.tracks.size)
        r.put("own", Lib.tracks.count { !it.demo }); r.put("demos", Lib.tracks.count { it.demo })
        try {
            val b = JSObject(); val c = Buscar.cur
            b.put("online", if (c == null) -1 else if (c.onLoading) -2 else c.online?.size ?: -3); b.put("onErr", c?.onErr ?: "")
            b.put("radios", if (Buscar.radio.loading) -2 else Buscar.radio.list?.size ?: -1); b.put("rErr", Buscar.radio.err); b.put("tab", Buscar.tab)
            r.put("buscar", b)
            val tk = TuVoz.takes(); val last = tk.maxByOrNull { it.addedAt }
            val vz = JSObject(); vz.put("n", tk.size); vz.put("rec", TuVoz.rec != null)
            if (last != null) { val l = JSObject(); l.put("start", last.start); l.put("end", last.end); l.put("duration", last.duration); l.put("peaks", last.peaks.size); l.put("label", last.label); vz.put("last", l) }
            r.put("voz", vz); r.put("speaking", Voz.speaking)
        } catch (ignored: Throwable) { }
        r.put("covers", UiState.covers.size); r.put("screen", UiState.screen); r.put("shown", host?.visibility == View.VISIBLE)
        r.put("now", UiState.nowId); r.put("qi", Player.qi); r.put("queue", JSArray(Player.queue.take(50))); r.put("playing", Player.playing)
        r.put("pos", try { NovaCore.position(Player.slot) } catch (t: Throwable) { JSObject() })
        r.put("hue", UiState.look.h.toDouble()); r.put("toast", UiState.toast); r.put("toastN", UiState.toastN)
        r.put("log", JSArray(synchronized(Nativo) { ArrayList(Nativo.log) }.takeLast(30)))
        r.put("migra", Migra.state); r.put("migraDetail", Migra.detail)
        r.put("barTitle", Bar.track?.title ?: ""); r.put("barPlaying", Bar.playing); r.put("barPos", Bar.pos); r.put("barDur", Bar.dur)
        r.put("shuffle", Bar.shuffle); r.put("repeat", Bar.repeat); r.put("baseLive", Base.live); r.put("baseOn", Base.on)
        r.put("inset", Bar.inset); r.put("dens", activity.resources.displayMetrics.density.toDouble()); r.put("webH", bridge.webView.height); r.put("webY", bridge.webView.y.toDouble())
        val rs = JSObject(); for ((k, v) in Bar.rects) rs.put(k, JSArray(listOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3].toDouble()))); r.put("rects", rs)
        r.put("wins", JSArray(Ventanas.stack)); r.put("npP", Ventanas.npP.value.toDouble()); r.put("meta", UiState.meta); r.put("drop", UiState.drop.isNotEmpty())
        r.put("devBox", UiState.dev?.title ?: ""); r.put("devText", UiState.dev?.text ?: ""); r.put("sleep", Bar.sleep); r.put("source", Player.source)
        return r
    }

    /** Enseña una pantalla nativa, sin la web: a todo lo ancho, encima de la barra de abajo. */
    internal fun nativoShow(screen: String) {
        ensureHost()
        val wv = bridge.webView
        val dens = activity.resources.displayMetrics.density
        val inset = try { androidx.core.view.ViewCompat.getRootWindowInsets(wv)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0 } catch (t: Throwable) { 0 }
        dpr = dens; x = 0f; y = 0f; w = wv.width / dens
        h = maxOf(100f, wv.height / dens - (Bar.PLAYER + Bar.NAV + inset / dens))
        place()
        UiState.screen = screen
        host?.visibility = View.VISIBLE
    }

    /** (las pruebas, al volver a la app de siempre) la web a la vista y las capas nativas fuera */
    internal fun nativoOff() {
        Nativo.stop()
        bridge.webView.visibility = View.VISIBLE
        natWinHost?.visibility = View.GONE
        Base.on = false; baseHost?.visibility = View.GONE
        host?.visibility = View.GONE
    }

    /** Fase 4, parte 2 · La capa de las ventanas nativas (el reproductor, el temporizador…): encima de todo, del tamaño de la web. */
    private var natWinHost: View? = null
    internal fun nativoWin(show: Boolean) {
        if (natWinHost == null) {
            if (!show) return
            val cv = ComposeView(activity)
            cv.setContent { NatWindows() }
            cv.translationZ = 50f
            val wv = bridge.webView
            (wv.parent as ViewGroup).addView(cv, ViewGroup.LayoutParams(maxOf(1, wv.width), maxOf(1, wv.height)))
            natWinHost = cv
            wv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> try { placeNatWin() } catch (ignored: Throwable) { } }
        }
        placeNatWin()
        natWinHost?.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) try { (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(natWinHost?.windowToken, 0) } catch (ignored: Throwable) { }
    }
    private fun placeNatWin() {
        val v = natWinHost ?: return
        val wv = bridge.webView
        val lp = v.layoutParams
        if (lp.width != wv.width || lp.height != wv.height) { lp.width = maxOf(1, wv.width); lp.height = maxOf(1, wv.height); v.layoutParams = lp }
        v.x = wv.x; v.y = wv.y
    }

    /** El fondo y la barra de abajo de la Órbita nativa (la barra, de Android de verdad: se toca y se lee). */
    internal fun nativoBase(bg: Bg) {
        ensureBaseHost(); placeBase()
        val wv = bridge.webView
        val dens = activity.resources.displayMetrics.density
        val inset = try { androidx.core.view.ViewCompat.getRootWindowInsets(wv)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0 } catch (t: Throwable) { 0 }
        Bar.inset = (inset / dens).toDouble()
        Base.bg = bg; Base.fixed = emptyList(); Base.parts.clear(); Base.on = true
        baseHost?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        baseHost?.visibility = View.VISIBLE
    }

    override fun load() {
        try { Store.init(context) } catch (ignored: Throwable) { }
        UiState.emit = { _, o -> try { notifyListeners("ui", o) } catch (ignored: Throwable) { } }
        // 5.3 · el grano del fondo, preparado ya (unos milisegundos, fuera del hilo de la pantalla)
        try { val d = activity.resources.displayMetrics.density; Thread({ try { Noise.make(d) } catch (ignored: Throwable) { } }, "orbita-grano").start() } catch (ignored: Throwable) { }
        // 5.1 · lo que va entendiendo y cómo acabó, a la app (lo que se va entendiendo, como mucho cada 150 ms)
        var partAt = 0L
        Ear.onPartial = { t ->
            val now = android.os.SystemClock.uptimeMillis()
            if (now - partAt >= 150) { partAt = now; UiState.send("earPart") { it.put("text", t) } }
        }
        Ear.onEnd = { text, alts, code ->
            earBack.isEnabled = false
            val a = JSArray(); for (x in alts) a.put(x)
            UiState.send("earEnd") { it.put("text", text); it.put("alts", a); it.put("code", code) }
        }
        // «atrás» mientras escucha: deja de escuchar (y nada más)
        try { activity.onBackPressedDispatcher.addCallback(activity, earBack) } catch (ignored: Throwable) { }
    }

    private val earBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { Ear.cancel(); isEnabled = false }
    }

    // la app se va (o se apaga la pantalla) mientras escucha: deja de escuchar (pedir el permiso también la deja en
    // pausa un momento: eso no cuenta)
    override fun handleOnResume() {
        super.handleOnResume()
        try { if (Nativo.on) Nativo.onBack() } catch (ignored: Throwable) { }
    }

    override fun handleOnPause() {
        super.handleOnPause()
        try { if (Nativo.on) Nativo.onAway() } catch (ignored: Throwable) { }
        if (!Ear.asking && Ear.step != "perm" && (Ear.on || Ear.busy)) { Ear.cancel(); earBack.isEnabled = false }
    }

    // la app se cierra: suelta el reconocimiento de voz
    override fun handleOnDestroy() {
        try { Ear.release() } catch (ignored: Throwable) { }
        try { Mind.release() } catch (ignored: Throwable) { }
        super.handleOnDestroy()
    }

    // ---------------------------------------------------------------- 5.1 · Nova te escucha
    private var earHost: ComposeView? = null
    private var earCall: PluginCall? = null
    // (antes de preguntar por el micrófono: ¿ya se le había dicho que no una vez? Si se le vuelve a decir que no, Android
    // ya no pregunta más, y Órbita se acuerda para ir directa a la ventana de Google, sin asomar el recuadro)
    private var micWhy = false
    private fun earPrefs() = context.getSharedPreferences("orbita_oido", android.content.Context.MODE_PRIVATE)
    private fun micRationale(): Boolean = try { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.RECORD_AUDIO) } catch (t: Throwable) { false }
    private fun ensureEarHost() {
        if (earHost != null) return
        val v = ComposeView(activity)
        v.setContent { EarLayer() }
        v.visibility = View.GONE
        v.translationZ = 45f   // (por encima de las ventanas)
        val wv = bridge.webView
        (wv.parent as ViewGroup).addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        earHost = v; Ear.host = v
    }

    /**
     * Escucha: el recuadro de abajo con el aro de Nova y lo que va entendiendo; al acabar llega «earEnd» con lo dicho.
     * Contesta enseguida {ok: true}, o {ok: false, code} si no puede (sin reconocimiento de voz en el móvil, o sin
     * permiso del micrófono): entonces la app usa la ventana de Google de siempre.
     */
    @PluginMethod
    fun listen(call: PluginCall) {
        val who = call.getString("who", "Nova") ?: "Nova"
        val lite = call.getBoolean("lite", false) == true
        activity.runOnUiThread {
            try {
                if (Ear.busy) { call.resolve(JSObject().put("ok", false).put("code", "busy")); return@runOnUiThread }
                if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) { call.resolve(JSObject().put("ok", false).put("code", "norec")); return@runOnUiThread }
                ensureEarHost()
                if (getPermissionState("mic") != PermissionState.GRANTED) {
                    if (earPrefs().getBoolean("nunca", false) && !micRationale()) { call.resolve(JSObject().put("ok", false).put("code", "denied")); return@runOnUiThread }
                    micWhy = micRationale()
                    Ear.ask(who, lite); earBack.isEnabled = true
                    earCall = call
                    requestPermissionForAlias("mic", call, "afterMic")
                    return@runOnUiThread
                }
                if (earPrefs().contains("nunca")) earPrefs().edit().remove("nunca").apply()
                earGo(call)
            } catch (t: Throwable) { call.resolve(JSObject().put("ok", false).put("code", "err")) }
        }
    }

    /** Fase 4, parte 2 · Escuchar desde la Órbita nativa (sin la web): lo mismo que «listen», con su permiso. */
    internal fun nativoListen(who: String, lite: Boolean, offline: Boolean): String {
        if (Ear.busy) return "busy"
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) return "norec"
        ensureEarHost()
        val go = { Ear.open(who, lite, false); Ear.start(context, "es-ES", offline); earBack.isEnabled = true }
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (earPrefs().getBoolean("nunca", false) && !micRationale()) return "denied"
            micWhy = micRationale()
            Ear.ask(who, lite); earBack.isEnabled = true
            Actos.launch(activity, androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), android.Manifest.permission.RECORD_AUDIO) { ok ->
                val wanted = Ear.asking
                if (ok && wanted) { Ear.asking = false; go(); return@launch }
                if (wanted && micWhy && !micRationale()) try { earPrefs().edit().putBoolean("nunca", true).apply() } catch (ignored: Throwable) { }
                Ear.close(); earBack.isEnabled = false
                if (wanted) Nativo.toast("Sin permiso para el micrófono no puedo escucharte.")
            }
            return "perm"
        }
        if (earPrefs().contains("nunca")) earPrefs().edit().remove("nunca").apply()
        go()
        return "ok"
    }

    @PermissionCallback
    private fun afterMic(call: PluginCall) {
        earCall = null
        // (si mientras tanto se canceló —«Cancelar», «atrás» o la app—, no escucha ni abre nada)
        val wanted = Ear.asking
        if (getPermissionState("mic") == PermissionState.GRANTED && wanted) { Ear.asking = false; earGo(call); return }
        if (wanted && micWhy && !micRationale()) try { earPrefs().edit().putBoolean("nunca", true).apply() } catch (ignored: Throwable) { }
        Ear.close(); earBack.isEnabled = false
        call.resolve(JSObject().put("ok", false).put("code", if (wanted) "denied" else "cancel"))
    }

    private fun earGo(call: PluginCall) {
        val lang = call.getString("lang", "es-ES") ?: "es-ES"
        val offline = call.getBoolean("offline", false) == true
        Ear.open(call.getString("who", "Nova") ?: "Nova", call.getBoolean("lite", false) == true, false)
        Ear.start(context, lang, offline)
        earBack.isEnabled = true
        call.resolve(JSObject().put("ok", true))
    }

    /**
     * 5.2 · El cerebro de comprensión de Nova, en Android: los mismos mensajes que usaba la parte web con su «worker»
     * («init», «embed», «bank», «classify», «tokenize» y «reset»). Los vectores van y vienen en base64.
     */
    @PluginMethod
    fun brain(call: PluginCall) { Mind.handle(context, call) }

    /** «Listo» desde la app: deja de escuchar y se queda con lo dicho. */
    @PluginMethod
    fun listenStop(call: PluginCall) { activity.runOnUiThread { Ear.finish() }; call.resolve() }

    /** Cancelar desde la app: nada le llega a Nova. */
    @PluginMethod
    fun listenCancel(call: PluginCall) { activity.runOnUiThread { Ear.cancel(); earBack.isEnabled = false }; call.resolve() }

    private fun num(call: PluginCall, k: String, d: Float): Float {
        val o = call.data?.opt(k)
        return if (o is Number) o.toFloat() else d
    }

    private fun readRect(call: PluginCall) {
        x = num(call, "x", x); y = num(call, "y", y); w = num(call, "w", w); h = num(call, "h", h); dpr = num(call, "dpr", dpr)
    }

    /** Los márgenes que manda la web con la pantalla (si los manda). */
    private fun readPad(call: PluginCall): List<Float>? {
        val a = call.getArray("pad") ?: return null
        if (a.length() != 4) return null
        return List(4) { i -> a.optDouble(i, 14.0).toFloat().coerceIn(0f, 200f) }
    }

    private fun place() {
        val v = host ?: return
        val wv = bridge.webView
        val pw = maxOf(1, (w * dpr).roundToInt())
        val ph = maxOf(1, (h * dpr).roundToInt())
        val lp = v.layoutParams
        if (lp.width != pw || lp.height != ph) { lp.width = pw; lp.height = ph; v.layoutParams = lp }
        v.x = wv.x + x * dpr
        v.y = wv.y + y * dpr
    }

    // ---- 4.8 · la capa de las ventanas: encima de todo, del tamaño de la web entera
    private var winHost: View? = null
    private fun ensureWinHost() {
        if (winHost != null) return
        val cv = ComposeView(activity)
        cv.setContent { OrbitaWindows() }
        // 5.0 · en un marco que pasa al vídeo de la web lo que se toca en su hueco
        val v = WinFrame(activity) { bridge.webView }
        v.addView(cv, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.visibility = View.GONE
        v.translationZ = 40f   // (siempre por encima de la pantalla nativa, aunque se cree antes que ella)
        val wv = bridge.webView
        (wv.parent as ViewGroup).addView(v, ViewGroup.LayoutParams(maxOf(1, wv.width), maxOf(1, wv.height)))
        winHost = v
        placeWin()
        // (cuando la web cambia de tamaño, por ejemplo al salir el teclado, la capa también)
        wv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> try { placeWin() } catch (ignored: Throwable) { } }
        UiState.winGone = { activity.runOnUiThread { hideWin(false) } }
    }
    private fun placeWin() {
        val v = winHost ?: return
        val wv = bridge.webView
        val lp = v.layoutParams
        if (lp.width != wv.width || lp.height != wv.height) { lp.width = maxOf(1, wv.width); lp.height = maxOf(1, wv.height); v.layoutParams = lp }
        v.x = wv.x; v.y = wv.y
    }
    private fun hideWin(clear: Boolean) {
        val v = winHost ?: return
        if (v.visibility != View.GONE) {
            v.visibility = View.GONE
            try {
                v.clearFocus()
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            } catch (ignored: Throwable) { }
        }
        UiState.winAlpha = 1f
        if (clear) { UiState.wins = emptyList(); UiState.winParts.clear(); UiState.ytDock = null }
    }

    // ---- 5.3 · la capa de abajo: el fondo y la barra de abajo. Justo encima de la web (debajo de la pantalla nativa, las
    // ventanas y el oído), del tamaño de la web entera
    private var baseHost: ComposeView? = null
    private fun ensureBaseHost() {
        if (baseHost != null) return
        val v = ComposeView(activity)
        v.setContent { OrbitaBase() }
        v.visibility = View.GONE
        // (lo que se toca aquí le llega a la web: lo que lee TalkBack, también lo de la web, que es lo que se toca)
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val wv = bridge.webView
        val parent = wv.parent as ViewGroup
        parent.addView(v, parent.indexOfChild(wv) + 1, ViewGroup.LayoutParams(maxOf(1, wv.width), maxOf(1, wv.height)))
        baseHost = v
        placeBase()
        wv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> try { placeBase() } catch (ignored: Throwable) { } }
    }
    private fun placeBase() {
        val v = baseHost ?: return
        val wv = bridge.webView
        val lp = v.layoutParams
        if (lp.width != wv.width || lp.height != wv.height) { lp.width = maxOf(1, wv.width); lp.height = maxOf(1, wv.height); v.layoutParams = lp }
        v.x = wv.x; v.y = wv.y
    }

    /**
     * 5.3 · El fondo y la barra de abajo: {on} (si se ve), {fixed} (dónde va cada parte de la barra), {parts} (lo que ha
     * cambiado de cada una) y {bg} (cómo es el fondo y por dónde va su movimiento).
     */
    @PluginMethod
    fun base(call: PluginCall) {
        val on = call.getBoolean("on", false) == true
        var fixed: List<WFix>? = null
        val parts = HashMap<Int, List<MNode>>()
        var bg: Bg? = null
        try {
            val fa = call.getArray("fixed")
            if (fa != null) {
                val out = ArrayList<WFix>()
                for (i in 0 until fa.length()) {
                    val a = fa.optJSONArray(i) ?: continue
                    out.add(WFix(a.optInt(0), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat(), a.optDouble(4, 0.0).toFloat()))
                }
                fixed = out
            }
            val po = call.getObject("parts")
            if (po != null) { val ks = po.keys(); while (ks.hasNext()) { val k = ks.next(); val o = po.optJSONObject(k) ?: continue; parts[k.toInt()] = mParsePart(o) } }
            val b = call.getObject("bg")
            if (b != null) bg = Bg(b.optDouble("h", 6.0).toFloat(), b.optDouble("s", 1.0).toFloat(), b.optString("mode", "aurora"), b.optLong("base", 0xFF06050BL).toInt(),
                b.optString("cover", ""), b.optBoolean("moving", false), b.optDouble("t", 0.0), android.os.SystemClock.uptimeMillis(), b.optBoolean("fade", true))
        } catch (t: Throwable) { call.reject("La barra de abajo no se entiende: " + t.message, "UI"); return }
        activity.runOnUiThread {
            try {
                ensureBaseHost(); placeBase()
                if (fixed != null) {
                    Base.fixed = fixed
                    val keep = fixed.map { it.k }.toSet()
                    for (k in Base.parts.keys.toList()) if (k !in keep) Base.parts.remove(k)
                }
                for ((k, v) in parts) Base.parts[k] = v
                if (bg != null) Base.bg = bg
                Base.syncs++
                Base.on = on
                baseHost?.visibility = if (on) View.VISIBLE else View.GONE
                call.resolve()
            } catch (t: Throwable) {
                call.reject("No he podido poner el fondo y la barra: " + t.message, "UI")
            }
        }
    }

    private fun ensureHost() {
        if (host != null) return
        val v = ComposeView(activity)
        v.setContent { OrbitaScreens() }
        v.visibility = View.GONE
        (bridge.webView.parent as ViewGroup).addView(v, ViewGroup.LayoutParams(1, 1))
        host = v
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val r = JSObject()
        r.put("available", true); r.put("v", 12)
        try {
            r.put("covers", UiState.covers.size); r.put("songs", UiState.tracks.size)
            r.put("screen", UiState.screen); r.put("msgs", UiState.chatMsgs.size); r.put("shown", host?.visibility == View.VISIBLE)
            r.put("pages", UiState.pages.size); r.put("y", (if (UiState.screen in UiState.PAGES) UiState.pageScroll(UiState.screen).value.toFloat() else if (UiState.screen in UiState.MIRROR) UiState.mirY(UiState.screen, dpr) else 0f).toDouble())
            r.put("mirror", UiState.mirParts.size)
            r.put("wins", UiState.wins.joinToString(",") { it.id }); r.put("winShown", winHost?.visibility == View.VISIBLE)
            // (para las pruebas: el último envío de la web que se ha puesto y lo que dice cada ventana, de arriba abajo)
            r.put("winSeq", UiState.winSeq); r.put("winTexts", JSArray(winTexts(40)))
            // 4.9 · los visualizadores: cuál se ve, cuántos fotogramas lleva y cómo (para las pruebas y el diagnóstico)
            r.put("vizActive", Viz.active); r.put("vizFrames", Viz.frames); r.put("vizDrawn", Viz.drawn); r.put("vizErr", Viz.err); r.put("vizTwo", Viz.two)
            r.put("vizCover", Viz.coverOk); r.put("vizYaw", Viz.yaw.toDouble()); r.put("vizPitch", Viz.pitch.toDouble()); r.put("vizZoom", Viz.zoom.toDouble())
            // 5.0 · la letra: qué caja se ve, qué verso está encendido, por dónde va y cuánto lleva de relleno
            UiState.ytHole().let { h -> r.put("ytHole", if (h == null) "" else "${h.left.roundToInt()},${h.top.roundToInt()},${h.width.roundToInt()}x${h.height.roundToInt()}") }
            // 5.1 · el oído: si escucha, en qué paso, lo entendido, cuánto se oye y cómo acabó la última vez
            r.put("earOn", Ear.on); r.put("earStep", Ear.step); r.put("earWords", Ear.words); r.put("earLevel", Ear.level.toDouble()); r.put("earMax", Ear.maxLevel.toDouble())
            r.put("earSessions", Ear.sessions); r.put("earLast", Ear.last); r.put("earPartials", Ear.partials); r.put("earLevels", Ear.levels); r.put("earPermCards", Ear.permCards); r.put("earAsking", Ear.asking)
            r.put("brainThreads", Mind.threads); r.put("brainLoad", Mind.loadMs); r.put("brainCalls", Mind.calls)
            // 5.3 · la capa de abajo (el fondo y la barra): si se ve, cuántas veces ha llegado, qué fondo dibuja y sus partes
            r.put("winPass", UiState.winPass()); r.put("passFwd", UiState.passFwd); r.put("animFrames", MAnimStat.frames); r.put("barFrames", MAnimStat.bars); r.put("rgLast", MAnimStat.rg); r.put("rcLast", MAnimStat.rc); r.put("baseShown", baseHost?.visibility == View.VISIBLE); r.put("baseOn", Base.on); r.put("baseLive", Base.live); r.put("baseSyncs", Base.syncs); r.put("baseFrames", Base.frames)
            r.put("baseDrawn", Base.drawn); r.put("baseParts", Base.fixed.size); r.put("noiseMs", Base.noiseMs); r.put("baseTexts", JSArray(baseTexts(30)))
            r.put("bgDrift", driftAt(Base.bg, android.os.SystemClock.uptimeMillis()).toDouble())
            r.put("ytDock", UiState.ytDock != null); r.put("ytSlot", UiState.ytSlot != null); r.put("ytFwd", UiState.ytFwd)
            r.put("lyDrawn", Ly.drawn); r.put("lyIdx", Ly.shown); r.put("lyY", Ly.y.toDouble()); r.put("lyFill", Ly.fill.toDouble()); r.put("lyTaps", Ly.taps); r.put("lyGen", Ly.g); r.put("lyAt", Ly.i)
            UiState.wins.firstOrNull { it.sc != null }?.let { r.put("winY", UiState.winY(it.id, dpr).toDouble()) }
            r.put("home", UiState.hTop != null && UiState.hDj != null); r.put("homeY", UiState.homeList.firstVisibleItemIndex * 100000 + UiState.homeList.firstVisibleItemScrollOffset)
        } catch (ignored: Throwable) { }
        call.resolve(r)
    }

    /** Enseña la pantalla nativa en el hueco que dice la app (en píxeles de la web). */
    @PluginMethod
    fun show(call: PluginCall) {
        readRect(call)
        val top = call.getBoolean("top", false) == true
        val asked = call.getString("screen", "biblioteca") ?: "biblioteca"
        val screen = if (asked == "chat" || asked == "inicio" || asked in UiState.PAGES || asked in UiState.MIRROR) asked else "biblioteca"
        val pad = readPad(call)
        activity.runOnUiThread {
            try {
                ensureHost(); place()
                if (pad != null && pad != UiState.chatPad) UiState.chatPad = pad
                if (UiState.screen != screen) {
                    // otra pantalla: se suelta el teclado de la anterior
                    try { host?.clearFocus() } catch (ignored: Throwable) { }
                    UiState.screen = screen
                }
                // al entrar desde otra pestaña, la lista empieza arriba (como la pantalla de siempre)
                if (top) {
                    if (screen == "inicio") UiState.homeList.requestScrollToItem(0)
                    else if (screen == "biblioteca") UiState.list.requestScrollToItem(0)
                    else if (screen in UiState.PAGES) UiState.pageScroll(screen).dispatchRawDelta(-1.0e7f)
                    else if (screen in UiState.MIRROR) UiState.mirList(screen).requestScrollToItem(0)
                }
                host?.visibility = View.VISIBLE
                call.resolve()
            } catch (t: Throwable) {
                call.reject("No he podido enseñar la pantalla: " + t.message, "UI")
            }
        }
    }

    @PluginMethod
    fun rect(call: PluginCall) {
        readRect(call)
        val pad = readPad(call)
        activity.runOnUiThread { try { place(); if (pad != null && pad != UiState.chatPad) UiState.chatPad = pad } catch (ignored: Throwable) { } }
        call.resolve()
    }

    @PluginMethod
    fun hide(call: PluginCall) {
        activity.runOnUiThread {
            UiState.hideN = UiState.hideN + 1
            val v = host
            if (v != null && v.visibility != View.GONE) {
                v.visibility = View.GONE
                try {
                    v.clearFocus()
                    (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                } catch (ignored: Throwable) { }
            }
        }
        call.resolve()
    }

    /** 4.4 · La conversación con Nova: quién es, qué suena, los mensajes, las sugerencias y si hay micrófono. */
    @PluginMethod
    fun chat(call: PluginCall) {
        val msgs = ArrayList<UiMsg>()
        val arr = call.getArray("msgs")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            msgs.add(UiMsg(o.optInt("me") == 1, o.optString("t")))
        }
        val chips = ArrayList<String>()
        val ca = call.getArray("chips")
        if (ca != null) for (i in 0 until ca.length()) { val c = ca.optString(i); if (c.isNotEmpty()) chips.add(c) }
        val name = call.getString("name", "Nova") ?: "Nova"
        val now = call.getString("now", "") ?: ""
        val mic = call.getBoolean("mic", false) == true
        val draft = call.getString("draft", "") ?: ""
        activity.runOnUiThread {
            UiState.chatName = name; UiState.chatNow = now; UiState.chatMsgs = msgs; UiState.chatChips = chips; UiState.chatMic = mic
            if (draft.isNotEmpty()) UiState.chatDraft = draft.take(240)
        }
        call.resolve()
    }

    /** 4.5 · «Ahora»: las partes que han cambiado (cada una entera). */
    @PluginMethod
    fun home(call: PluginCall) {
        val d = call.data
        activity.runOnUiThread {
            try {
                if (d.has("top")) UiState.hTop = d.optJSONObject("top")
                if (d.has("tick")) UiState.hTick = d.optJSONObject("tick")
                if (d.has("dj")) {
                    val o = d.optJSONObject("dj")
                    // lo que hay escrito en la caja de la app (si ella lo cambia: una idea que se tocó, o se vació al mandarlo)
                    val was = UiState.hDj?.optString("inp", "") ?: ""
                    val now = o?.optString("inp", "") ?: ""
                    if (now != was) UiState.homeDraft = now
                    UiState.hDj = o
                }
                if (d.has("bub")) UiState.hBub = d.optJSONObject("bub")
                if (d.has("slot")) UiState.hSlot = d.optJSONObject("slot")
                if (d.has("fy")) UiState.hFy = d.optJSONObject("fy")
                if (d.has("mixer")) UiState.hMixer = d.optJSONObject("mixer")
                if (d.has("out")) UiState.hOut = d.optJSONObject("out")
            } catch (ignored: Throwable) { }
        }
        call.resolve()
    }

    /** 4.6 · Las partes que han cambiado de una de las pantallas nuevas. */
    @PluginMethod
    fun page(call: PluginCall) {
        val name = call.getString("name", "") ?: ""
        val parts = call.getObject("parts")
        if (name.isEmpty() || parts == null) { call.resolve(); return }
        activity.runOnUiThread {
            try {
                val it = parts.keys()
                while (it.hasNext()) { val k = it.next(); val o = parts.optJSONObject(k); if (o != null) UiState.pages["$name.$k"] = o }
            } catch (ignored: Throwable) { }
        }
        call.resolve()
    }

    /**
     * 4.7 · «Buscar» y «Ajustes»: lo que ha cambiado de la pantalla de siempre (dónde va cada parte y las piezas de las
     * partes nuevas o cambiadas). Se lee aquí, fuera de la pantalla, y se enseña de una vez.
     */
    @PluginMethod
    fun mirror(call: PluginCall) {
        val name = call.getString("name", "") ?: ""
        if (name !in UiState.MIRROR) { call.resolve(); return }
        try {
            val oa = call.getArray("order")
            val order = ArrayList<MPos>()
            if (oa != null) for (i in 0 until oa.length()) {
                val a = oa.optJSONArray(i) ?: continue
                order.add(MPos(a.optInt(0), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat(), a.optDouble(4, 0.0).toFloat()))
            }
            val total = num(call, "h", 0f)
            val parts = call.getObject("parts")
            val parsed = HashMap<String, List<MNode>>()
            if (parts != null) {
                val it = parts.keys()
                while (it.hasNext()) { val k = it.next(); val o = parts.optJSONObject(k) ?: continue; parsed["$name.$k"] = mParsePart(o) }
            }
            activity.runOnUiThread {
                try {
                    for ((k, v) in parsed) UiState.mirParts[k] = v
                    if (oa != null && UiState.mirOrder[name] != order) {
                        UiState.mirOrder[name] = order
                        val keep = HashSet<String>()
                        for (p in order) keep.add("$name.${p.k}")
                        val gone = UiState.mirParts.keys.filter { it.startsWith("$name.") && it !in keep }
                        for (k in gone) UiState.mirParts.remove(k)
                    }
                    if (total > 0f && UiState.mirTotal[name] != total) UiState.mirTotal[name] = total
                } catch (ignored: Throwable) { }
            }
            call.resolve()
        } catch (t: Throwable) {
            call.reject("No he podido leer la pantalla: " + t.message, "UI")
        }
    }

    /** 4.7 · «Cambiar la hora» del despertador: el reloj de Android. Devuelve la hora elegida (o nada si se cancela). */
    @PluginMethod
    fun time(call: PluginCall) {
        val h = (call.getInt("h", 7) ?: 7).coerceIn(0, 23)
        val m = (call.getInt("m", 30) ?: 30).coerceIn(0, 59)
        activity.runOnUiThread {
            try {
                var done = false
                val d = android.app.TimePickerDialog(activity, { _, hh, mm ->
                    if (!done) { done = true; val r = JSObject(); r.put("h", hh); r.put("m", mm); call.resolve(r) }
                }, h, m, true)
                d.setOnDismissListener { if (!done) { done = true; call.resolve(JSObject()) } }
                d.show()
            } catch (t: Throwable) {
                call.reject("No he podido abrir el reloj: " + t.message, "TIME")
            }
        }
    }

    /**
     * 4.8 · Las ventanas que hay a la vista (de abajo arriba) y lo que ha cambiado de cada una: dónde va cada parte y las
     * piezas de las partes nuevas o cambiadas. Se lee aquí, fuera de la pantalla, y se enseña de una vez.
     */
    @PluginMethod
    fun win(call: PluginCall) {
        try {
            val ids = call.getArray("list")
            val list = ArrayList<String>()
            if (ids != null) for (i in 0 until ids.length()) list.add(ids.optString(i))
            val wins = call.getObject("wins")
            val defs = HashMap<String, WDef>()
            val parts = HashMap<String, List<MNode>>()
            if (wins != null) {
                val it = wins.keys()
                while (it.hasNext()) {
                    val id = it.next(); val o = wins.optJSONObject(id) ?: continue
                    defs[id] = wDef(id, o)
                    val ps = o.optJSONObject("parts")
                    if (ps != null) { val pk = ps.keys(); while (pk.hasNext()) { val k = pk.next(); val po = ps.optJSONObject(k) ?: continue; parts["$id.$k"] = mParsePart(po) } }
                }
            }
            val force = call.getBoolean("force", false) == true
            val seq = call.getInt("seq", 0) ?: 0
            activity.runOnUiThread {
                try {
                    ensureWinHost(); placeWin()
                    for ((k, v) in parts) UiState.winParts[k] = v
                    UiState.winSeq = seq
                    val cur = UiState.wins.associateBy { it.id }
                    val next = list.mapNotNull { defs[it] ?: cur[it] }
                    if (next != UiState.wins) UiState.wins = next
                    val keep = HashSet<String>()
                    for (w in next) { for (f in w.fixed) keep.add("${w.id}.${f.k}"); w.sc?.order?.forEach { keep.add("${w.id}.${it.k}") } }
                    for (k in UiState.winParts.keys.filter { it !in keep }) UiState.winParts.remove(k)
                    // una ventana que sale: por donde la tiene la web
                    for (w in next) { val sc = w.sc; if (sc != null && (force || cur[w.id] == null)) UiState.winGo(w.id, sc.y0, dpr) }
                    UiState.winAlpha = 1f
                    if (next.isEmpty()) hideWin(true) else winHost?.let { v -> if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; v.invalidate() } }
                } catch (t: Throwable) { android.util.Log.w("OrbitaUi", "ventanas: " + t) }
            }
            call.resolve()
        } catch (t: Throwable) {
            android.util.Log.w("OrbitaUi", "ventanas, al leer: " + t)
            call.reject("No he podido leer la ventana: " + t.message, "UI")
        }
    }

    @PluginMethod
    fun winHide(call: PluginCall) {
        activity.runOnUiThread { try { hideWin(true) } catch (ignored: Throwable) { } }
        call.resolve()
    }

    /** La web ha movido lo que se desliza en una ventana (o lo pide una prueba): la nativa va a la misma altura. */
    /** 5.0 · El videoclip: dónde está el vídeo de la web (en px de la web) o que ya no está. */
    @PluginMethod
    fun yt(call: PluginCall) {
        val on = call.getInt("on", 0) == 1
        val k = activity.resources.displayMetrics.density
        val r = if (on) androidx.compose.ui.geometry.Rect(num(call, "x", 0f) * k, num(call, "y", 0f) * k, (num(call, "x", 0f) + num(call, "w", 0f)) * k, (num(call, "y", 0f) + num(call, "h", 0f)) * k) else null
        activity.runOnUiThread { UiState.ytDock = r; winHost?.invalidate() }
        call.resolve()
    }

    /** 5.0 · La letra: qué verso se canta (de qué letra) y cuánto dura su relleno; Android la lleva, la enciende y la llena. */
    @PluginMethod
    fun ly(call: PluginCall) {
        val o = call.data
        try { android.os.Handler(android.os.Looper.getMainLooper()).post { try { Ly.set(o) } catch (ignored: Throwable) { } } } catch (ignored: Throwable) { }
        call.resolve()
    }

    /** 4.9 · Los visualizadores: qué lienzo se ve, el estilo y sus ajustes, los colores y la portada (los dibuja Android). */
    @PluginMethod
    fun viz(call: PluginCall) {
        try { Viz.set(call.data) } catch (t: Throwable) { Viz.err = "ajustes: " + t.toString().take(120) }
        call.resolve()
    }

    @PluginMethod
    fun winScroll(call: PluginCall) {
        val id = call.getString("id", "") ?: ""
        val y = num(call, "y", 0f)
        activity.runOnUiThread { try { UiState.winGo(id, y, dpr) } catch (ignored: Throwable) { } }
        call.resolve()
    }

    /** 4.5 · El disco de «Ahora»: qué suena, si suena y por dónde va. */
    @PluginMethod
    fun vinyl(call: PluginCall) {
        val d = call.data
        val at = android.os.SystemClock.uptimeMillis()
        activity.runOnUiThread { UiState.hVinAt = at; UiState.hVin = d }
        call.resolve()
    }

    /** Mover la lista: arriba del todo o al final. */
    @PluginMethod
    fun scroll(call: PluginCall) {
        val end = call.getString("to", "top") == "end"
        val y = num(call, "y", -1f)
        activity.runOnUiThread {
            try {
                if (UiState.screen in UiState.MIRROR) {
                    val n = UiState.screen
                    if (y >= 0f) UiState.mirGo(n, y, dpr)
                    else if (end) UiState.mirList(n).requestScrollToItem(maxOf(0, (UiState.mirOrder[n]?.size ?: 1) - 1), 1 shl 28)
                    else UiState.mirList(n).requestScrollToItem(0)
                    return@runOnUiThread
                }
                val pg = UiState.screen in UiState.PAGES
                val st: androidx.compose.foundation.gestures.ScrollableState = if (pg) UiState.pageScroll(UiState.screen) else if (UiState.screen == "inicio") UiState.homeList else UiState.list
                if (y >= 0f) {
                    // (pruebas) a una altura exacta, medida como en la web: desde arriba del todo
                    st.dispatchRawDelta(-1.0e7f)
                    st.dispatchRawDelta(y * dpr)
                } else if (pg) st.dispatchRawDelta(if (end) 1.0e7f else -1.0e7f)
                else {
                    val ls = if (UiState.screen == "inicio") UiState.homeList else UiState.list
                    ls.requestScrollToItem(if (end) maxOf(0, ls.layoutInfo.totalItemsCount - 1) else 0)
                }
            } catch (ignored: Throwable) { }
        }
        call.resolve()
    }

    @PluginMethod
    fun look(call: PluginCall) {
        val l = UiLook(num(call, "h", 276f), num(call, "s", 1f), num(call, "glowK", 1f), num(call, "round", 1f), num(call, "tk", 1f),
            call.getString("titles", "") ?: "", call.getBoolean("black", false) == true)
        activity.runOnUiThread { UiState.look = l }
        call.resolve()
    }

    private fun box(o: JSONObject?): UiBox? = if (o == null) null else
        UiBox(o.optString("title"), o.optString("text"), o.optString("button"), o.optString("act"), o.optBoolean("primary"), o.optBoolean("enabled", true))

    /** La biblioteca entera (se manda cuando cambia) y lo que la rodea. */
    @PluginMethod
    fun library(call: PluginCall) {
        try {
            val d = call.data
            val arr = d.optJSONArray("tracks")
            var list: ArrayList<UiTrack>? = null
            if (arr != null) {
                list = ArrayList(arr.length())
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!seen.add(o.optString("id"))) continue   // (cada canción, una sola vez: la lista no admite repetidas)
                    list.add(UiTrack(o.optString("id"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("f"),
                        o.optDouble("d", 0.0), o.optDouble("ad", 0.0), o.optInt("p", 0), o.optDouble("lp", 0.0),
                        o.optInt("l") == 1, o.optInt("dm") == 1, o.optInt("mn") == 1, o.optInt("dv") == 1, o.optInt("c") == 1, o.optDouble("h", 280.0).toFloat()))
                }
            }
            val dev = box(d.optJSONObject("dev"))
            val demos = box(d.optJSONObject("demos"))
            activity.runOnUiThread {
                if (list != null) {
                    // las portadas de canciones que ya no están (o que han cambiado) se sueltan
                    val changed = d.optJSONArray("coversChanged")
                    if (changed != null) for (i in 0 until changed.length()) UiState.dropCover(changed.optString(i))
                    UiState.tracks = list
                }
                if (d.has("filter")) UiState.filter = d.optString("filter", "all")
                if (d.has("sort")) UiState.sort = d.optString("sort", "recent")
                if (d.has("search")) UiState.search = d.optString("search", "")
                if (d.has("meta")) UiState.meta = d.optString("meta")
                if (d.has("warn")) { UiState.warn = d.optString("warn"); UiState.warnText = d.optString("warnText") }
                if (d.has("dev")) UiState.dev = dev
                if (d.has("demos")) UiState.demos = demos
                if (d.has("drop")) UiState.drop = d.optString("drop")
                if (d.has("showDev")) UiState.showDev = d.optBoolean("showDev")
                if (d.has("now")) UiState.nowId = d.optString("now")
            }
            call.resolve()
        } catch (t: Throwable) {
            call.reject("No he podido leer la biblioteca: " + t.message, "UI")
        }
    }

    @PluginMethod
    fun now(call: PluginCall) {
        val id = call.getString("id", "") ?: ""
        activity.runOnUiThread { UiState.nowId = id }
        call.resolve()
    }

    /** Las portadas que pidió la pantalla (las guarda la app; llegan como imágenes pequeñas). */
    @PluginMethod
    fun covers(call: PluginCall) {
        val arr = call.getArray("list")
        val got = ArrayList<Pair<String, ImageBitmap>>()
        // del tamaño al que se ven en la lista (46 de lado): así ocupan poca memoria aunque haya miles de canciones
        val side = maxOf(32, Math.ceil(num(call, "side", 46f).coerceIn(32f, 400f).toDouble() * context.resources.displayMetrics.density).toInt())
        if (arr != null) for (i in 0 until arr.length()) {
            try {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("data")
                val k = u.indexOf(";base64,")
                if (k < 0) continue
                val bmp = decodeArt(Base64.decode(u.substring(k + 8), Base64.DEFAULT), side) ?: continue
                got.add(o.optString("id") to bmp.asImageBitmap())
            } catch (ignored: Throwable) { }
        }
        activity.runOnUiThread {
            for ((id, bmp) in got) {
                if (!UiState.covers.containsKey(id)) UiState.coverOrder.addLast(id)
                UiState.covers[id] = bmp
            }
            while (UiState.coverOrder.size > UiState.COVERS_MAX) {
                val old = UiState.coverOrder.removeFirst()
                UiState.covers.remove(old); UiState.asked.remove(old)
            }
        }
        call.resolve()
    }

    /** Un aviso corto (los de la app quedarían tapados por la pantalla nativa). */
    @PluginMethod
    fun toast(call: PluginCall) {
        val t = call.getString("text", "") ?: ""
        val ms = num(call, "ms", 2600f).toLong().coerceIn(800L, 15000L)
        activity.runOnUiThread { UiState.toast = t; UiState.toastMs = ms; UiState.toastN++ }
        call.resolve()
    }

    /** «Añadir canciones»: el selector de archivos de Android. Devuelve las direcciones para que la app los importe. */
    @PluginMethod
    fun pick(call: PluginCall) {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            // (4.7: quien llama puede decir qué tipos y si es uno solo; si no, canciones e imágenes, varias)
            val ask = call.getArray("mime")
            val types = ArrayList<String>()
            if (ask != null) for (k in 0 until ask.length()) { val t = ask.optString(k, ""); if (t.contains('/')) types.add(t) }
            if (types.isEmpty()) { types.add("audio/*"); types.add("image/*") }
            if (!(types.size == 1 && types[0] == "*/*")) i.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, call.getBoolean("one", false) != true)
            startActivityForResult(call, i, "picked")
        } catch (t: Throwable) {
            call.reject("No he podido abrir el selector: " + t.message, "PICK")
        }
    }

    @ActivityCallback
    fun picked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val out = JSArray()
        try {
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
                else data.data?.let { uris.add(it) }
                val cr = context.contentResolver
                for (u in uris) {
                    var name = u.lastPathSegment ?: "archivo"
                    try {
                        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
                    } catch (ignored: Throwable) { }
                    val o = JSObject()
                    o.put("uri", u.toString()); o.put("name", name); o.put("type", cr.getType(u) ?: "")
                    out.put(o)
                }
            }
        } catch (ignored: Throwable) { }
        val r = JSObject()
        r.put("files", out)
        call.resolve(r)
    }
}
