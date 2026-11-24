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
        back.isEnabled = false
    }

    // ---------------------------------------------------------------- el botón de «atrás» del móvil (como en la app de siempre)
    /** Las pantallas por las que se ha pasado (como el historial de la web). */
    private val hist = ArrayList<String>()
    private val back = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!goBack()) {
                // (nada más atrás: fuera de la app, como siempre; la música sigue)
                isEnabled = false
                try { (act as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.onBackPressed() ?: act?.moveTaskToBack(true) } catch (ignored: Throwable) { act?.moveTaskToBack(true) }
                isEnabled = on
            }
        }
    }
    /** «popstate»: lo de encima se cierra; si no hay nada encima, la pantalla de antes. */
    fun goBack(): Boolean {
        if (Alarma.active) return true
        if (Ventanas.back()) return true
        if (UiState.screen == "chat") { val v = hist.removeLastOrNull() ?: "inicio"; show(v); return true }
        val v = hist.removeLastOrNull() ?: return false
        show(v)
        return true
    }

    fun start(act: Activity, ui: OrbitaUiPlugin) {
        if (on) return
        on = true; this.act = act; this.ui = ui
        Store.init(act)
        // (lo que la parte web dejó puesto en esta misma sesión no vale: aquí manda Android)
        UiState.covers.clear(); UiState.asked.clear(); UiState.coverOrder.clear()
        prevEmit = UiState.emit; prevSink = NovaCore.sink
        UiState.emit = { type, o -> main.post { try { onUi(type, o) } catch (t: Throwable) { note("ui $type: ${t.message}") } } }
        try { (act as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(act as androidx.activity.ComponentActivity, back); back.isEnabled = true } catch (ignored: Throwable) { }
        if (!Migra.done()) { note("traspaso de los datos"); Migra.run(act, false) { ok, msg -> note("traspaso: $ok $msg"); boot() } }
        else boot()
    }

    private fun boot() {
        bg.execute {
            try { Lib.load() } catch (t: Throwable) { note("biblioteca: ${t.message}") }
            try { Memoria.load() } catch (t: Throwable) { note("memoria: ${t.message}") }
            try { Cerebro.load() } catch (t: Throwable) { note("cerebro: ${t.message}") }
            main.post {
                look()
                pushLibrary()
                Biblio.boxes()
                Player.init()
                // (si el motor seguía sonando con la app cerrada, se sigue con eso; si no, lo último, en pausa)
                if (!Player.adopt()) Player.resumeSession(false)
                ready = true
                val start = Prefs.str("startView", "inicio").let { if (it == "biblioteca" || it == "inicio") it else "inicio" }
                if (start == "inicio") Inicio.sync(true)
                ui?.nativoShow(start)
                Cerebro.bgAnalysis()
                ui?.nativoBase(bgNow())
                Player.barSync()
                note("listo: ${Lib.tracks.size} canciones")
                if (Biblio.devState() == "granted") main.postDelayed({ Biblio.syncDevice(false) }, 1500)
                try { Alarma.boot() } catch (t: Throwable) { note("despertador: ${t.message}") }
                // (al rato: la bienvenida a quien acaba de instalarla; si no, lo que dice Nova al abrir, si no suena el despertador)
                if (Bienvenida.fresh()) main.postDelayed({ if (!Alarma.busy()) Bienvenida.open(false) }, 450)
                else {
                    if (!Bienvenida.fresh()) Prefs.set("onboarded", true)
                    main.postDelayed({ if (!Alarma.busy()) try { Recuerdos.openGreeting() } catch (t: Throwable) { note("saludo: ${t.message}") } }, 1200)
                }
                if (System.currentTimeMillis() - Prefs.num("lastUpdateCheck", 0.0) > 3 * 3600 * 1000) main.postDelayed({ Ajustes.checkUpdate(false) }, 6000)
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
                val f = (if (t.net) Online.cachedImage(if (sideDp > 100) t.artBig.ifEmpty { t.art } else t.art.ifEmpty { t.artBig }) else if (sideDp > 100) Lib.bigCoverFile(t) else Lib.coverFile(t)) ?: continue
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
            // ---- «Ahora»
            "hTap" -> { val kv = o.opt("k"); if (kv is Number && Paginas.owns(kv.toInt())) Paginas.onTap(o) else Inicio.onTap(o) }
            "hSel" -> Inicio.onSel(o.optString("sel"), o.optString("v"))
            "hAsk" -> Ordenes.run(o.optString("text"))
            "hFocus" -> {}
            "earPart" -> {}
            "earEnd" -> { val a = o.optJSONArray("alts"); Ordenes.fromVoice(o.optString("text"), if (a != null) List(a.length()) { a.optString(it) } else emptyList(), o.optString("code")) }
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
            "buscarRun" -> { Buscar.q = o.optString("q"); Buscar.run(Buscar.q) }
            "buscarTab" -> Buscar.pickTab(o.optString("tab"))
            // («Tu voz»: una toma de prueba hecha aquí: medio segundo de silencio, un segundo de tono y otro medio de silencio)
            "vozTest" -> {
                val sr = 44100; val n = sr * 2
                val bb = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
                bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16); bb.put("data".toByteArray()); bb.putInt(n * 2)
                for (i in 0 until n) bb.putShort(if (i in sr / 2 until sr * 3 / 2) (Math.sin(2 * Math.PI * 440 * i / sr) * 16000).toInt().toShort() else 0)
                val bytes = bb.array()
                TuVoz.importClips(listOf(TuVoz.Src({ bytes }, "prueba-voz.wav", "audio/wav")), "saludo")
            }
            "vozPlay" -> TuVoz.takes().maxByOrNull { it.addedAt }?.let { TuVoz.play(it) }
            "vozDel" -> { TuVoz.takes().filter { it.label.startsWith("Toma") && System.currentTimeMillis() - it.addedAt < 30 * 60000 }.forEach { TuVoz.del(it.id) }; Prefs.set("who", "nova") }
            // (las pruebas vuelven a la app de siempre: la web, otra vez a la vista, y lo nativo, fuera)
            "test-web" -> { try { NovaCore.pause(0); NovaCore.pause(1) } catch (ignored: Throwable) { }; ui?.nativoOff() }
            else -> if (!Ventanas.onUi(type, o)) note("sin hacer todavía: $type")
        }
    }

    /** Todo otra vez desde los datos (tras restaurar una copia). */
    fun restart() { if (!on) return; ready = false; UiState.covers.clear(); UiState.asked.clear(); boot() }

    /** Las pantallas que ya hace Android. */
    val VIEWS = listOf("biblioteca", "inicio", "listas", "tu", "ajustes", "buscar", "voz")

    /** Cambiar de pestaña (y apuntarlo, para volver con «atrás»). */
    fun go(view: String) {
        val v = if (view in listOf("inicio", "buscar", "biblioteca", "listas", "tu", "ajustes", "voz", "chat")) view else "biblioteca"
        if (v !in VIEWS) { toast("Esta pantalla aún no está en la Órbita nativa."); return }
        val cur = UiState.screen
        if (cur == v) { show(v); return }
        if (cur.isNotEmpty() && cur != "chat") { hist.add(cur); if (hist.size > 40) hist.removeAt(0) }
        show(v)
    }

    private fun show(v: String) {
        if (v == "inicio") Inicio.sync(true)
        if (v in Paginas.NAMES) { UiState.screen = v; Paginas.sync(v, true) }
        Prefs.set("lastView", v)
        Ver.bump()
        ui?.nativoShow(v)
    }

    // ---------------------------------------------------------------- lo que pasa (y lo que hay que volver a enseñar)
    /** La app se ve (o está en segundo plano). */
    @Volatile var awake = true; private set
    private var lastBack = 0L
    /** Fuera de Órbita (otra app, la pantalla apagada). */
    fun onAway() { awake = false; Tubo.onAway() }
    /** De vuelta: lo que esperaba (el vídeo), y la música del móvil, por si hay canciones nuevas. */
    fun onBack() {
        awake = true; Tubo.onBack()
        val now = System.currentTimeMillis()
        if (ready && Biblio.devState() == "granted" && now - lastBack > 60000) { lastBack = now; main.postDelayed({ Biblio.syncDevice(false) }, 800) }
        if (ready) { Alarma.changed(); Ver.bump() }
    }

    fun libraryChanged() { pushLibrary(); Biblio.boxes(); if (Player.has()) Player.barSync() }
    fun onTrack(t: Track) { UiState.nowId = t.id; Paginas.mark("tu") }
    fun onQueue() { Ventanas.refresh("np") }
    fun onSleep() { Player.barSync(); Ventanas.refresh("sleep") }
    fun onImport() { Ventanas.refresh("imp") }
    fun onMemory() { Paginas.mark("tu"); Inicio.mark() }
    fun onNova() { Inicio.mark() }
    fun onHome() { Inicio.mark() }
    fun onChat() {}
    fun onLists() { Paginas.mark("listas") }

    /** Animaciones reducidas (las del móvil o «Animaciones: ligeras» de Ajustes). */
    fun reduceMotion(): Boolean = try { android.provider.Settings.Global.getFloat(act?.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (t: Throwable) { false }
    fun lite(): Boolean = Prefs.str("anim", "full") == "lite" || reduceMotion()

    fun haptic(pattern: LongArray) {
        if (!Prefs.bool("haptics", true)) return
        try {
            val v = act?.getSystemService(android.os.Vibrator::class.java) ?: return
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (ignored: Throwable) { }
    }

    fun charging(): Boolean = try {
        val b = act?.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val st = b?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
    } catch (t: Throwable) { false }

    fun openExternal(url: String) {
        try { act?.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { toast("No he podido abrirlo.") }
    }

    /** «Sube el volumen» / «baja el volumen»: el del móvil, de 15 en 15 %. */
    fun volumeStep(dir: Int) {
        try {
            val am = act?.getSystemService(android.media.AudioManager::class.java) ?: return
            val mx = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val step = maxOf(1, Math.round(mx * 0.15f))
            val v = (cur + dir * step).coerceIn(0, mx)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
            toast("Volumen ${Math.round(v * 100f / maxOf(1, mx))} %")
        } catch (ignored: Throwable) { }
    }

    /** Lo de arriba del todo del móvil, en «px» de CSS (la capa de las ventanas va donde la web: ya por debajo). */
    fun topInset(): Float = 0f

    /** El fondo (con la portada de la que suena, si va con portada). */
    fun bgSync() { if (ready) ui?.nativoBase(bgNow()) }
}
