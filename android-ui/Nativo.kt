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
        // (lo que la parte web dejó puesto en esta misma sesión no vale: aquí manda Android)
        UiState.covers.clear(); UiState.asked.clear(); UiState.coverOrder.clear()
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
                ui?.nativoBase(bgNow())
                Player.barSync()
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

    /** El fondo de ahora (como lo pide la web: su tono, su clase y si se mueve). */
    fun bgNow(): Bg {
        val l = UiState.look
        val mode = Prefs.str("lkBg", "aurora").let { if (it in listOf("aurora", "quieto", "portada", "negro")) it else "aurora" }
        val lite = Prefs.str("anim", "full") == "lite"
        return Bg(l.h, l.s, mode, if (mode == "negro") 0xFF000000.toInt() else 0xFF06050B.toInt(), if (mode == "portada") (Player.current()?.id ?: "") else "",
            mode == "aurora" && !lite, 0.0, android.os.SystemClock.uptimeMillis(), true)
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
            "play" -> {
                val a = o.optJSONArray("ids") ?: return
                Player.playQueue(List(a.length()) { a.optString(it) }, o.optInt("index", 0))
            }
            "like" -> { val t = Lib.byId(o.optString("id")) ?: return; t.liked = !t.liked; Lib.save(t); pushLibrary(); Player.barSync() }
            "toggle" -> Player.toggle()
            "next" -> Player.next(false)
            "prev" -> Player.prev()
            "seek" -> Player.seek(o.optDouble("pos", 0.0))
            "shuffle" -> Player.setShuffle(!Prefs.bool("shuffle", false))
            "repeat" -> Player.cycleRepeat()
            "tab" -> {
                val v = o.optString("view")
                if (v == "biblioteca") ui?.nativoShow(v) else toast("Esta pantalla aún no está en la Órbita nativa.")
            }
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
        tick()
    }

    // el tiempo de la barra, cuatro veces por segundo (lo que de verdad se oye)
    private fun tick() {
        main.postDelayed({
            try {
                if (current() != null) {
                    val p = NovaCore.position(slot)
                    Bar.pos = p.optDouble("pos", 0.0); Bar.dur = p.optDouble("dur", 0.0).let { if (it > 0) it else current()?.duration ?: 0.0 }
                    val pl = p.optBoolean("playing"); if (pl != Bar.playing) { Bar.playing = pl; playing = pl }
                }
            } catch (ignored: Throwable) { }
            tick()
        }, 250)
    }

    /** Lo que dice la barra de abajo: la canción, si suena, aleatorio, repetir. */
    fun barSync() {
        val t = current()
        Bar.track = t?.let { UiTrack(it.id, it.title, it.artist, it.album, it.folder, it.duration, it.addedAt, it.plays, it.lastPlayed, it.liked, it.demo, it.mine, it.dev, Lib.coverFile(it) != null, Momento.phHue(it).toFloat()) }
        Bar.shuffle = Prefs.bool("shuffle", false)
        Bar.repeat = Prefs.str("repeat", "off")
        Bar.playing = playing
    }

    fun toggle() {
        val t = current() ?: return
        if (playing) { NovaCore.pause(slot); playing = false } else { NovaCore.play(slot); playing = true }
        Bar.playing = playing
        Nativo.note((if (playing) "sigue: " else "pausa: ") + t.title)
    }

    /** La siguiente (a mano: también con «repetir toda la lista», al final vuelve a la primera). */
    fun next(auto: Boolean) {
        if (queue.isEmpty()) return
        if (qi + 1 < queue.size) { Bar.dir = 1; qi++; loadCurrent() }
        else if (Prefs.str("repeat", "off") == "all") { Bar.dir = 1; qi = 0; loadCurrent() }
    }

    /** La anterior (o la misma desde el principio, si ya lleva más de 3 segundos). */
    fun prev() {
        if (queue.isEmpty()) return
        val pos = try { NovaCore.position(slot).optDouble("pos", 0.0) } catch (t: Throwable) { 0.0 }
        if (pos > 3 || qi == 0) { NovaCore.eng().seek(slot, 0.0); return }
        Bar.dir = -1; qi--; loadCurrent()
    }

    fun seek(pos: Double) { if (current() == null) return; NovaCore.eng().seek(slot, maxOf(0.0, pos)); Bar.pos = pos }

    fun setShuffle(on: Boolean) {
        Prefs.set("shuffle", on)
        // (como en la web: lo que queda de la cola se baraja; lo que suena sigue)
        if (on && qi >= 0 && queue.size > qi + 2) { val rest = queue.subList(qi + 1, queue.size).shuffled(); queue = queue.subList(0, qi + 1) + rest; syncPlaylist() }
        barSync()
    }

    fun cycleRepeat() {
        val r = when (Prefs.str("repeat", "off")) { "off" -> "all"; "all" -> "one"; else -> "off" }
        Prefs.set("repeat", r)
        barSync()
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
        barSync()
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
