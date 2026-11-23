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

    fun start(act: Activity, ui: OrbitaUiPlugin) {
        if (on) return
        on = true; this.act = act; this.ui = ui
        Store.init(act)
        UiState.emit = { type, o -> main.post { try { onUi(type, o) } catch (t: Throwable) { note("ui $type: ${t.message}") } } }
        if (!Migra.done()) { note("traspaso de los datos"); Migra.run(act, false) { ok, msg -> note("traspaso: $ok $msg"); boot() } }
        else boot()
    }

    private fun boot() {
        bg.execute {
            try { Lib.load() } catch (t: Throwable) { note("biblioteca: ${t.message}") }
            main.post {
                look()
                pushLibrary()
                Player.init()
                ui?.nativoShow(Prefs.str("startView", "inicio").let { if (it == "biblioteca") it else "biblioteca" })
                ready = true
                note("listo: ${Lib.tracks.size} canciones")
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

    fun pushLibrary() {
        val hide = Prefs.bool("hideDemos", false)
        UiState.tracks = Lib.tracks.filter { !(it.demo && hide) }.map { t ->
            UiTrack(t.id, t.title, t.artist, t.album, t.folder, t.duration, t.addedAt, t.plays, t.lastPlayed, t.liked, t.demo, t.mine, t.dev,
                Lib.coverFile(t) != null, Momento.phHue(t).toFloat())
        }
        UiState.filter = UiState.filter.ifEmpty { "all" }
        UiState.sort = Prefs.str("sort", "recent")
        UiState.nowId = Player.current()?.id ?: ""
    }

    /** Las portadas que pide la pantalla: del archivo, al tamaño al que se ven. */
    private fun covers(ids: List<String>) {
        val d = act?.resources?.displayMetrics?.density ?: 2.6f
        val side = maxOf(32, Math.ceil(46.0 * d).toInt())
        bg.execute {
            val got = ArrayList<Pair<String, androidx.compose.ui.graphics.ImageBitmap>>()
            for (id in ids) {
                val t = Lib.byId(id) ?: continue
                val f = Lib.coverFile(t) ?: continue
                try {
                    val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.absolutePath, o)
                    var s = 1; while (o.outWidth / (s * 2) >= side && o.outHeight / (s * 2) >= side) s *= 2
                    val o2 = BitmapFactory.Options(); o2.inSampleSize = s
                    val b = BitmapFactory.decodeFile(f.absolutePath, o2) ?: continue
                    val sc = if (b.width > side * 2 || b.height > side * 2) android.graphics.Bitmap.createScaledBitmap(b, side, side, true) else b
                    got.add(id to sc.asImageBitmap())
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
            "play" -> {
                val a = o.optJSONArray("ids") ?: return
                Player.playQueue(List(a.length()) { a.optString(it) }, o.optInt("index", 0))
            }
            "like" -> { val t = Lib.byId(o.optString("id")) ?: return; t.liked = !t.liked; Lib.save(t); pushLibrary() }
            "pref" -> {
                if (o.has("filter")) UiState.filter = o.optString("filter", "all")
                if (o.has("sort")) { UiState.sort = o.optString("sort", "recent"); Prefs.set("sort", UiState.sort) }
            }
            "test-seek" -> NovaCore.eng().seek(Player.slot, o.optDouble("pos", 0.0))
            else -> note("sin hacer todavía: $type")
        }
    }
}

/**
 * El reproductor (lo de «Playback» de la parte web): qué suena, la cola y el motor de sonido de siempre ([NovaCore]),
 * que sigue la lista solo (también con la pantalla apagada).
 */
object Player {
    var queue: List<String> = emptyList(); private set
    var qi = -1; private set
    var slot = 0; private set
    @Volatile var playing = false; private set
    private val main = Handler(Looper.getMainLooper())

    fun current(): Track? = queue.getOrNull(qi)?.let { Lib.byId(it) }

    fun init() {
        NovaCore.sink = object : NovaCore.Sink {
            override fun audio(o: JSONObject) { main.post { onAudio(o) } }
            override fun spectrum(b64: String) {}
        }
    }

    fun playQueue(ids: List<String>, start: Int) {
        val q = ids.filter { Lib.byId(it) != null }
        if (q.isEmpty()) return
        queue = q; qi = start.coerceIn(0, q.size - 1)
        loadCurrent()
    }

    private fun loadCurrent() {
        val t = current() ?: return
        val src = Lib.src(t)
        if (src == null) { Nativo.note("no encuentro «${t.title}»"); Nativo.toast("No encuentro el archivo de esta canción."); return }
        NovaCore.load(slot, src, 0.0, true)
        syncPlaylist()
        begin(t)
    }

    /** La cola, para que el motor siga solo (y el aviso de Android sepa qué viene). */
    private fun syncPlaylist() {
        val items = ArrayList<PItem>()
        var k = qi
        while (k < queue.size && items.size < 40) {
            val t = Lib.byId(queue[k]) ?: break
            val src = Lib.src(t) ?: break
            items.add(PItem().apply { this.src = src; id = t.id; title = t.title; artist = t.artist; album = t.album; dur = t.duration })
            k++
        }
        if (items.isNotEmpty()) NovaCore.setPlaylist(items, 0, slot, 0, 0)
    }

    /** Lo de cada vez que empieza una canción. */
    private fun begin(t: Track) {
        Nativo.note("suena: ${t.title}")
        t.plays += 1; t.lastPlayed = System.currentTimeMillis().toDouble(); Lib.save(t)
        UiState.nowId = t.id
        playing = true
        Nativo.look()
    }

    private fun onAudio(o: JSONObject) {
        when (o.optString("type")) {
            "jump", "gapless" -> {
                val src = o.optString("src")
                val k = (qi + 1 until queue.size).firstOrNull { Lib.byId(queue[it])?.let { t -> Lib.src(t) } == src } ?: queue.indexOfFirst { id -> Lib.byId(id)?.let { Lib.src(it) } == src }
                if (k >= 0 && k != qi) { qi = k; current()?.let { begin(it) } }
            }
            "state" -> if (o.optInt("slot", slot) == slot) playing = o.optBoolean("playing")
            "ended" -> if (o.optInt("slot", slot) == slot && qi + 1 >= queue.size) playing = false
        }
    }
}
