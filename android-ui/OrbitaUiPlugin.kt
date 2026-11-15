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

/** Lo que enseñan las pantallas nativas. Lo escribe la app (por el plugin) y lo lee Compose. */
object UiState {
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
@CapacitorPlugin(name = "OrbitaUi")
class OrbitaUiPlugin : Plugin() {
    private var host: ComposeView? = null
    private var x = 0f
    private var y = 0f
    private var w = 0f
    private var h = 0f
    private var dpr = 1f

    override fun load() {
        UiState.emit = { _, o -> try { notifyListeners("ui", o) } catch (ignored: Throwable) { } }
    }

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
        r.put("available", true); r.put("v", 4)
        try {
            r.put("covers", UiState.covers.size); r.put("songs", UiState.tracks.size)
            r.put("screen", UiState.screen); r.put("msgs", UiState.chatMsgs.size); r.put("shown", host?.visibility == View.VISIBLE)
            r.put("home", UiState.hTop != null && UiState.hDj != null); r.put("homeY", UiState.homeList.firstVisibleItemIndex * 100000 + UiState.homeList.firstVisibleItemScrollOffset)
        } catch (ignored: Throwable) { }
        call.resolve(r)
    }

    /** Enseña la pantalla nativa en el hueco que dice la app (en píxeles de la web). */
    @PluginMethod
    fun show(call: PluginCall) {
        readRect(call)
        val top = call.getBoolean("top", false) == true
        val screen = when (call.getString("screen", "biblioteca")) { "chat" -> "chat"; "inicio" -> "inicio"; else -> "biblioteca" }
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
                if (top) { if (screen == "inicio") UiState.homeList.requestScrollToItem(0) else if (screen == "biblioteca") UiState.list.requestScrollToItem(0) }
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
                val st = if (UiState.screen == "inicio") UiState.homeList else UiState.list
                if (y >= 0f) {
                    // (pruebas) a una altura exacta, medida como en la web: desde arriba del todo
                    st.dispatchRawDelta(-1.0e7f)
                    st.dispatchRawDelta(y * dpr)
                } else st.requestScrollToItem(if (end) maxOf(0, st.layoutInfo.totalItemsCount - 1) else 0)
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
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "image/*"))
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
