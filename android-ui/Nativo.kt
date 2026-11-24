package __PACKAGE__

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Fase 4, parte 2 · Órbita sin la parte web: la app la lleva Android de principio a fin.
 * Arranca con los datos ya en Android ([Store]: si aún no se habían pasado, primero se pasan), enseña las pantallas
 * nativas de siempre con lo que dice [Lib], atiende lo que se toca en ellas (lo que antes iba a la web) y lleva el
 * reproductor ([Player], con el motor de sonido de siempre).
 *
 * Mientras se termina, convive con la app de siempre: solo se enciende si se pide («_modo» = "nativo").
 */
object Nativo {
    @Volatile var on = false; private set
    @Volatile var ready = false; private set
    var act: Activity? = null; private set
    var ui: OrbitaUiPlugin? = null; private set
    private val main = Handler(Looper.getMainLooper())
    val bg = Executors.newSingleThreadExecutor()
    /** Lo último que ha pasado (para las pruebas y el diagnóstico). */
    val log = ArrayList<String>()
    @Synchronized fun note(s: String) { log.add(s); if (log.size > 200) log.subList(0, log.size - 150).clear() }

    private var prevEmit: ((String, com.getcapacitor.JSObject) -> Unit)? = null
    private var prevSink: NovaCore.Sink? = null

    /** (las pruebas) de vuelta a la app de siempre: lo que recibía la web, otra vez a la web. */
    fun stop() {
        if (!on) return
        on = false; ready = false
        prevEmit?.let { UiState.emit = it }
        NovaCore.sink = prevSink
    }

    fun start(act: Activity, ui: OrbitaUiPlugin) {
        if (on) return
        on = true; this.act = act; this.ui = ui
        Store.init(act)
        // (lo que la parte web dejó puesto en esta misma sesión no vale: aquí manda Android)
        UiState.covers.clear(); UiState.asked.clear(); UiState.coverOrder.clear()
        prevEmit = UiState.emit; prevSink = NovaCore.sink
        UiState.emit = { type, o -> main.post { try { onUi(type, o) } catch (t: Throwable) { note("ui $type: ${t.message}") } } }
        if (!Migra.done()) { note("traspaso de los datos"); Migra.run(act, false) { ok, msg -> note("traspaso: $ok $msg"); boot() } }
        else boot()
    }

    private fun boot() {
        bg.execute {
            try { Lib.load() } catch (t: Throwable) { note("biblioteca: ${t.message}") }
            try { Memoria.load() } catch (t: Throwable) { note("memoria: ${t.message}") }
            main.post {
                look()
                pushLibrary()
                Biblio.boxes()
                Player.init()
                // (si el motor seguía sonando con la app cerrada, se sigue con eso; si no, lo último, en pausa)
                if (!Player.adopt()) Player.resumeSession(false)
                ui?.nativoShow(Prefs.str("startView", "inicio").let { if (it == "biblioteca") it else "biblioteca" })
                ready = true
                ui?.nativoBase(bgNow())
                Player.barSync()
                note("listo: ${Lib.tracks.size} canciones")
                if (Biblio.devState() == "granted") main.postDelayed({ Biblio.syncDevice(false) }, 1500)
            }
        }
    }

    // ---------------------------------------------------------------- lo que se ve
    fun toast(text: String, ms: Long = 2600) { main.post { UiState.toast = text; UiState.toastMs = ms; UiState.toastN++ } }

    fun look() {
        val (h, s) = Momento.tone(Prefs.str("theme", "auto"), Player.current(), Prefs.num("customHue", 200.0), Prefs.num("customSat", 0.9))
        val round = when (Prefs.str("lkRound", "redondo")) { "suave" -> 0.55f; "recto" -> 0.18f; else -> 1f }
        val tk = when (Prefs.str("lkText", "m")) { "s" -> 0.92f; "l" -> 1.1f; else -> 1f }
        UiState.look = UiLook(h.toFloat(), maxOf(0.2, s).toFloat(), Prefs.num("lkGlow", 1.0).toFloat(), round, tk, Prefs.str("lkTitles", "orbita"), Prefs.str("lkBg", "aurora") == "negro")
    }

    /** El fondo de ahora (como lo pide la web: su tono, su clase y si se mueve). */
    internal fun bgNow(): Bg {
        val l = UiState.look
        val mode = Prefs.str("lkBg", "aurora").let { if (it in listOf("aurora", "quieto", "portada", "negro")) it else "aurora" }
        val lite = Prefs.str("anim", "full") == "lite"
        return Bg(l.h, l.s, mode, if (mode == "negro") 0xFF000000.toInt() else 0xFF06050B.toInt(), if (mode == "portada") (Player.current()?.id ?: "") else "",
            mode == "aurora" && !lite, 0.0, android.os.SystemClock.uptimeMillis(), true)
    }

    fun pushLibrary() {
        UiState.tracks = Biblio.visible().map { Player.uiTrack(it) }
        UiState.filter = UiState.filter.ifEmpty { "all" }
        UiState.sort = Prefs.str("sort", "recent")
        UiState.nowId = Player.current()?.id ?: ""
    }

    /** Las portadas que pide la pantalla: del archivo, al tamaño al que se ven (las grandes, de la portada grande). */
    private fun covers(ids: List<String>, sideDp: Int = 46, tag: String = "") {
        val d = act?.resources?.displayMetrics?.density ?: 2.6f
        val side = maxOf(32, Math.ceil(sideDp * d.toDouble()).toInt())
        bg.execute {
            val got = ArrayList<Pair<String, androidx.compose.ui.graphics.ImageBitmap>>()
            for (id in ids) {
                val t = Lib.byId(id) ?: continue
                val f = (if (sideDp > 100) Lib.bigCoverFile(t) else Lib.coverFile(t)) ?: continue
                try {
                    val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.absolutePath, o)
                    var s = 1; while (o.outWidth / (s * 2) >= side && o.outHeight / (s * 2) >= side) s *= 2
                    val o2 = BitmapFactory.Options(); o2.inSampleSize = s
                    val b = BitmapFactory.decodeFile(f.absolutePath, o2) ?: continue
                    val sc = if (b.width > side * 2 || b.height > side * 2) android.graphics.Bitmap.createScaledBitmap(b, side, side, true) else b
                    got.add(id + tag to sc.asImageBitmap())
                } catch (ignored: Throwable) { }
            }
            main.post {
                for ((id, bmp) in got) { if (!UiState.covers.containsKey(id)) UiState.coverOrder.addLast(id); UiState.covers[id] = bmp }
                while (UiState.coverOrder.size > UiState.COVERS_MAX) { val old = UiState.coverOrder.removeFirst(); UiState.covers.remove(old); UiState.asked.remove(old) }
            }
        }
    }

    // ---------------------------------------------------------------- lo que se toca (lo que antes iba a la web)
    fun onUi(type: String, o: JSONObject) {
        when (type) {
            "needCovers" -> { val a = o.optJSONArray("ids"); if (a != null) covers(List(a.length()) { a.optString(it) }) }
            "needArt" -> { val a = o.optJSONArray("urls"); if (a != null) covers(List(a.length()) { a.optString(it) }, o.optInt("side", 46), o.optString("tag", "")) }
            "baseLive" -> {}
            // ---- «Mi música»
            "play" -> {
                val a = o.optJSONArray("ids") ?: return
                Player.playQueue(List(a.length()) { a.optString(it) }, o.optInt("index", 0))
            }
            "like" -> Biblio.like(o.optString("id"))
            "del" -> Biblio.delete(o.optString("id"))
            "edit" -> Ventanas.openEdit(o.optString("id"))
            "add" -> Biblio.pick()
            "dev" -> if (o.optString("act") == "sync") Biblio.syncDevice(true) else Biblio.connect()
            "demos" -> Biblio.toggleDemos()
            "pref" -> {
                if (o.has("filter")) UiState.filter = o.optString("filter", "all")
                if (o.has("sort")) { UiState.sort = o.optString("sort", "recent"); Prefs.set("sort", UiState.sort) }
            }
            // ---- la barra de abajo
            "toggle" -> Player.toggle()
            "next" -> Player.next(false)
            "prev" -> Player.prev()
            "seek" -> Player.seek(o.optDouble("pos", 0.0))
            "shuffle" -> Player.setShuffle(!Prefs.bool("shuffle", false))
            "repeat" -> Player.cycleRepeat()
            "sleep-open" -> Ventanas.open("sleep")
            "np-open" -> Ventanas.open("np")
            "tab" -> go(o.optString("view"))
            // ---- (pruebas)
            "test-seek" -> NovaCore.eng().seek(Player.slot, o.optDouble("pos", 0.0))
            // (las pruebas vuelven a la app de siempre: la web, otra vez a la vista, y lo nativo, fuera)
            "test-web" -> { try { NovaCore.pause(0); NovaCore.pause(1) } catch (ignored: Throwable) { }; ui?.nativoOff() }
            else -> if (!Ventanas.onUi(type, o)) note("sin hacer todavía: $type")
        }
    }

    /** Cambiar de pestaña. */
    fun go(view: String) {
        val v = if (view in listOf("inicio", "buscar", "biblioteca", "listas", "tu", "ajustes", "voz")) view else "biblioteca"
        if (v != "biblioteca") { toast("Esta pantalla aún no está en la Órbita nativa."); return }
        Prefs.set("lastView", v)
        ui?.nativoShow(v)
    }

    // ---------------------------------------------------------------- lo que pasa (y lo que hay que volver a enseñar)
    /** La app se ve (o está en segundo plano). */
    @Volatile var awake = true

    fun libraryChanged() { pushLibrary(); Biblio.boxes(); if (Player.has()) Player.barSync() }
    fun onTrack(t: Track) { UiState.nowId = t.id }
    fun onQueue() { Ventanas.refresh("np") }
    fun onSleep() { Player.barSync(); Ventanas.refresh("sleep") }
    fun onImport() { Ventanas.refresh("imp") }
    fun onMemory() {}
    fun onNova() {}

    /** Lo de arriba del todo del móvil, en «px» de CSS (la capa de las ventanas va donde la web: ya por debajo). */
    fun topInset(): Float = 0f

    /** El fondo (con la portada de la que suena, si va con portada). */
    fun bgSync() { if (ready) ui?.nativoBase(bgNow()) }
}
