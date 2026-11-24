package __PACKAGE__

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Fase 4, parte 2 · El reproductor (lo de «Playback», «naSyncNext» y compañía de la parte web), entero en Android.
 * Las canciones las toca el motor de siempre ([NovaCore]) con la cola entera y las mezclas planeadas aquí (también con
 * la pantalla apagada); los cambios a mano se hacen con un fundido corto entre sus dos «platos».
 */
object Player {
    const val MANUAL_XF = 0.8
    private const val NA_MAX = 120

    var queue: List<String> = emptyList(); private set
    var qi = -1; private set
    /** De dónde viene la cola: «library», «radio», «mix», «todo», «daily», «online»… */
    var source = "library"; private set
    /** El plato del motor que suena (0 o 1). */
    var slot = 0; private set
    @Volatile var playing = false; private set
    /** Lo que se dice al empezar la próxima canción (si lo pidió quien puso la cola). */
    var pendingTalk: Pair<String, String>? = null
    var intros: Map<String, String> = emptyMap()
    var mixEndless: Any? = null
    /** Ya ha sonado algo en esta sesión (la tarjeta «Continúa donde lo dejaste» se va). */
    var playedOnce = false; private set
    private val main = Handler(Looper.getMainLooper())
    private var gen = 0
    private var plKey = ""
    private var lastSave = 0L
    /** La escucha de ahora (lo que solo sabe la app: si se cambió a mano y de dónde venía). */
    private class Listen(val id: String, val at: Long, val src: String, val nat: String?) { var manual = false }
    private var listen: Listen? = null
    private val meta = ArrayList<Listen>()
    /** El temporizador para dormir: cuándo para (hora del móvil) y cuánto dura su fundido. */
    var sleepEnd = 0L; private set
    var sleepFade = 30000L; private set

    fun current(): Track? = queue.getOrNull(qi)?.let { Lib.byId(it) }
    fun has(): Boolean = current() != null

    fun init() {
        NovaCore.sink = object : NovaCore.Sink {
            override fun audio(o: JSONObject) { main.post { try { onAudio(o) } catch (t: Throwable) { Nativo.note("motor: ${t.message}") } } }
            override fun spectrum(b64: String) {}   // (los visualizadores lo leen directamente del motor)
        }
        applyEq()
        if (looping) return
        looping = true
        tick()
        main.postDelayed({ takeListens() }, 3000)
    }
    private var looping = false

    // ---------------------------------------------------------------- la barra y el tiempo
    private var tickN = 0
    private fun tick() {
        main.postDelayed({
            if (Nativo.on) try {
                val t = current()
                if (t != null) {
                    val p = NovaCore.position(slot)
                    Bar.pos = p.optDouble("pos", 0.0)
                    Bar.dur = p.optDouble("dur", 0.0).let { if (it > 0) it else t.duration }
                    val pl = p.optBoolean("playing")
                    if (pl != playing) { playing = pl; Bar.playing = pl }
                    if (Bar.dur > 0 && (t.duration == 0.0 || kotlin.math.abs(t.duration - Bar.dur) > 1)) { t.duration = Bar.dur; Lib.save(t) }
                }
                sleepTick()
                // «Ahora»: el disco (lo que suena, para que gire) y, cada 2 s, la cabecera y el panel de la DJ (la hora…)
                if (tickN % 2 == 0 && UiState.screen == "inicio") Inicio.vinyl()
                if (tickN % 8 == 0 && UiState.screen == "inicio") Inicio.mark()
                if (++tickN % 32 == 0) saveSession(false)
                if (tickN % 120 == 0) takeListens()
            } catch (ignored: Throwable) { }
            tick()
        }, 250)
    }

    fun uiTrack(it: Track): UiTrack = UiTrack(it.id, it.title, it.artist, it.album, it.folder, it.duration, it.addedAt, Biblio.plays(it), it.lastPlayed,
        Biblio.liked(it), it.demo, it.mine, it.dev, Lib.coverFile(it) != null, Momento.phHue(it).toFloat())

    /** Lo que dice la barra de abajo: la canción, si suena, aleatorio, repetir y el temporizador. */
    fun barSync() {
        val t = current()
        Bar.track = t?.let { uiTrack(it) }
        Bar.shuffle = Prefs.bool("shuffle", false)
        Bar.repeat = Prefs.str("repeat", "off")
        Bar.playing = playing
        val left = sleepLeft()
        Bar.sleep = if (left != null && left > 0) min(99L, (left + 59999) / 60000).toString() else ""
    }

    // ---------------------------------------------------------------- poner música
    /** «playQueue»: una cola nueva desde la canción «start» (con aleatorio, la elegida primero y el resto barajado). */
    fun playQueue(ids0: List<String>, start: Int = 0, src: String = "library", talk: Pair<String, String>? = null, intros: Map<String, String> = emptyMap()) {
        val ids = ids0.filter { Lib.byId(it) != null }
        if (ids.isEmpty()) return
        var q = ids; var i = start.coerceIn(0, ids.size - 1)
        if (Prefs.bool("shuffle", false) && src !in listOf("radio", "online", "daily", "todo")) {
            val first = q[i]; q = listOf(first) + q.filterIndexed { k, _ -> k != i }.shuffled(); i = 0
        }
        markManual()
        queue = q; qi = i; source = src; mixEndless = null
        this.intros = intros; pendingTalk = talk
        loadCurrent(true, if (playing) MANUAL_XF else 0.0)
    }

    fun markManual() { listen?.manual = true }

    /** «loadCurrent»: pone la canción de ahora (con un fundido corto si ya sonaba otra) y le dice al motor lo que sigue. */
    fun loadCurrent(autoplay: Boolean, xf: Double = 0.0, at: Double = 0.0) {
        val t = current() ?: return
        val my = ++gen
        if (t.demo && Demos.file(t.id) == null) {
            Demos.render(t.id) { if (my == gen && current() === t) loadCurrent(autoplay, xf, at) }
            return
        }
        val src = Lib.src(t)
        if (src == null) {
            if (t.dev) {
                Nativo.toast("No encuentro «${t.title}» en el móvil. Paso a la siguiente.")
                if (qi + 1 < queue.size) { qi++; loadCurrent(autoplay, 0.0) }
                Biblio.syncDevice(false)
                return
            }
            Nativo.toast(if (t.demo) "Las pistas demo aún se están preparando, prueba en un segundo." else "No encuentro el archivo de esta canción.")
            return
        }
        Voz.stop()
        if (!autoplay) {
            NovaCore.load(slot, src, at, false)
            NovaCore.eng().setVolume(slot, 1f)
            applyNorm(slot, t)
            playing = false
            plKey = ""; syncPlaylist()
            begin(t, silent = true)
            return
        }
        val talk = pendingTalk; pendingTalk = null
        startOn(src, t, xf)
        plKey = ""; syncPlaylist()
        begin(t, talk = talk)
    }

    /** «startOn»: la canción nueva en el otro plato y un fundido de igual potencia (o en el mismo, si no sonaba nada). */
    private fun startOn(src: String, t: Track, xf: Double) {
        val old = slot
        val busy = playing && NovaCore.position(old).optBoolean("playing")
        cancelFades()
        if (!busy || xf <= 0) {
            val other = 1 - old
            try { NovaCore.eng().setVolume(other, 0f); NovaCore.pause(other) } catch (ignored: Throwable) { }
            NovaCore.eng().setVolume(old, 1f)
            applyNorm(old, t)
            NovaCore.load(old, src, 0.0, true)
            playing = true; Bar.playing = true
            return
        }
        val nw = 1 - old
        NovaCore.eng().setVolume(nw, 0f)
        applyNorm(nw, t)
        NovaCore.load(nw, src, 0.0, true)
        slot = nw
        playing = true; Bar.playing = true
        val ms = (xf * 1000).toLong(); val t0 = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val k = min(1.0, (SystemClock.uptimeMillis() - t0).toDouble() / max(1L, ms))
                try { NovaCore.eng().setVolume(old, cos(k * PI / 2).toFloat()); NovaCore.eng().setVolume(nw, sin(k * PI / 2).toFloat()) } catch (ignored: Throwable) { }
                if (k < 1) main.postDelayed(this, 25)
                else main.postDelayed({ if (slot != old) { try { NovaCore.pause(old); NovaCore.eng().setVolume(old, 1f) } catch (ignored: Throwable) { } } }, 100)
            }
        }
        fadeRun = step
        main.post(step)
    }

    private var fadeRun: Runnable? = null
    private fun cancelFades() { fadeRun?.let { main.removeCallbacks(it) }; fadeRun = null }

    /** Una canción que se borra: fuera de la cola (y si sonaba, se para). */
    fun removed(id: String) {
        if (current()?.id == id) { try { NovaCore.stop(0); NovaCore.stop(1) } catch (ignored: Throwable) { }; playing = false; Bar.playing = false }
        val ci = queue.indexOf(id)
        if (ci != -1) { val q = queue.toMutableList(); q.removeAt(ci); queue = q; if (qi >= ci) qi = max(0, qi - 1); if (queue.isEmpty()) qi = -1 }
        plKey = ""; barSync()
    }

    fun applyNorm(sl: Int, t: Track) { try { NovaCore.eng().setSongGain(sl, Mezcla.normGain(Analizador.get(t)).toFloat()) } catch (ignored: Throwable) { } }

    fun applyEq() {
        try {
            val g = Mezcla.eqGains()
            NovaCore.eng().setEq(FloatArray(10) { g[it].toFloat() }, !Mezcla.Aud.direct && Mezcla.eqActive())
            NovaCore.eng().setDirect(Mezcla.Aud.direct)
        } catch (ignored: Throwable) { }
    }

    /** Lo de cada vez que una canción pasa a ser la de ahora («beginTrack»). */
    fun begin(t: Track, talk: Pair<String, String>? = null, silent: Boolean = false, again: Boolean = false) {
        Nativo.note("suena: ${t.title}" + if (silent) " (en pausa)" else "")
        finishListen()
        if (!silent) {
            playedOnce = true
            listen = Listen(t.id, System.currentTimeMillis(), source, Lib.src(t))
            t.prevPlayed = t.lastPlayed
            Biblio.addPlay(t)
            val h = Biblio.history().filter { it != t.id }.toMutableList(); h.add(0, t.id); Prefs.set("history", h.take(30))
        }
        UiState.nowId = t.id
        barSync()
        Nativo.look()
        Nativo.bgSync()
        if (t.dev && !t.artChecked) Biblio.fetchArt(t)
        Analizador.get(t)?.let { if (!silent) applyNorm(slot, t) } ?: main.postDelayed({ Analizador.schedule(t) { if (current() === t) { applyNorm(slot, t); plKey = ""; syncPlaylist() } } }, 1500)
        val nx = queue.getOrNull(qi + 1)?.let { Lib.byId(it) }
        if (nx != null) main.postDelayed({ Analizador.schedule(nx) { plKey = ""; syncPlaylist() } }, 4000)
        if (!silent) Nova.onBegin(t, talk, again)
        saveSession(true)
        Nativo.onTrack(t)
    }

    // ---------------------------------------------------------------- la cola que sigue el motor
    private fun own(t: Track?): Boolean = t != null && !t.net && (if (t.demo) Demos.file(t.id) != null else Lib.src(t) != null)
    private fun albumPair(a: Track?, b: Track?): Boolean = a != null && b != null && a.dev && b.dev && Biblio.norm(a.album).isNotEmpty() &&
        Biblio.norm(a.album) == Biblio.norm(b.album) && Biblio.artistKey(a.artist) == Biblio.artistKey(b.artist)

    private fun item(t: Track, prev: Track?): PItem? {
        val src = Lib.src(t) ?: return null
        val j = JSONObject()
        j.put("src", src); j.put("id", t.id); j.put("title", t.title); j.put("artist", t.artist); j.put("album", t.album); j.put("dur", t.duration)
        j.put("g", Mezcla.normGain(Analizador.get(t)))
        val f = if (prev == null) Mezcla.Fade() else Mezcla.fade(Analizador.get(prev), Analizador.get(t), prev.duration, albumPair(prev, t))
        j.put("xf", f.xf); j.put("at", f.at); j.put("st", f.st)
        f.style?.let { j.put("style", it) }; f.beatA?.let { j.put("beatA", it) }; f.rate?.let { j.put("rate", it) }; f.swap?.let { j.put("swap", it) }
        f.gridA?.let { j.put("gridA", it) }; f.gridB?.let { j.put("gridB", it) }; f.beatB?.let { j.put("beatB", it) }
        return PItem.fromJson(j)
    }

    /** «naSyncNext»: unas pocas antes (para «anterior» en el aviso), esta y hasta 120 después, con su mezcla cada una. */
    fun syncPlaylist() {
        val t = current() ?: return
        if (!own(t)) return
        Radio.runway()
        var from = qi; while (from > 0 && qi - from < 3 && own(Lib.byId(queue[from - 1]))) from--
        val left = sleepLeft()
        val sleepTrack = left != null && sleepFade == 12000L
        val rp = if (sleepTrack) "off" else Prefs.str("repeat", "off")
        val L = queue.size
        val wrap = rp == "all" && L > 1 && source != "radio" && source != "todo" && mixEndless == null && queue.all { own(Lib.byId(it)) }
        val last = if (rp == "one" || sleepTrack) qi else qi + (if (wrap) min(NA_MAX, L) else NA_MAX)
        val items = ArrayList<PItem>(); var prevT: Track? = null
        val skipOthers = !Nativo.awake
        var k = from
        while (k <= last) {
            if (!wrap && k >= L) break
            val n = Lib.byId(queue[k % L])
            if (!own(n)) { if (skipOthers && k > qi) { k++; continue }; break }
            val it = item(n!!, prevT) ?: break
            items.add(it); prevT = n; k++
        }
        if (items.isEmpty()) return
        val index = qi - from
        val repeat = if (rp == "one") "one" else if (wrap) "all" else "off"
        val cycle = if (wrap && L <= NA_MAX) L else 0
        val key = "$slot|$index|$repeat$cycle|" + items.joinToString(",") { it.toJson().toString() }
        if (key == plKey) return
        plKey = key
        NovaCore.setPlaylist(items, index, slot, NovaQueue.repeatOf(repeat), cycle)
    }

    // ---------------------------------------------------------------- los botones
    fun toggle() {
        if (current() == null) { if (resumeSession(true)) return; Nova.spinRoulette(); return }
        if (playing) fadePause() else resume()
    }

    private var fadeGen = 0
    /** Pausa con un fundido corto; si mientras tanto se pide otra cosa (otra canción, «play»), manda eso. */
    fun fadePause(ms: Long = 160) {
        Voz.stop()
        val my = ++fadeGen; val k = slot; val t0 = SystemClock.uptimeMillis()
        playing = false; Bar.playing = false
        val step = object : Runnable {
            override fun run() {
                if (my != fadeGen || slot != k) return
                val x = min(1.0, (SystemClock.uptimeMillis() - t0).toDouble() / ms)
                try { NovaCore.eng().setVolume(k, (1 - x * x * (3 - 2 * x)).toFloat()) } catch (ignored: Throwable) { }
                if (x < 1) { main.postDelayed(this, 16); return }
                // (los dos platos: si una mezcla estaba a medias, también el que se iba)
                try { NovaCore.pause(0); NovaCore.pause(1); NovaCore.eng().setVolume(k, 1f) } catch (ignored: Throwable) { }
                saveSession(true)
            }
        }
        main.post(step)
    }

    fun resume() {
        val t = current() ?: return
        ++fadeGen
        if (Lib.src(t) == null && !t.demo) return
        try {
            NovaCore.eng().setVolume(slot, 0f)
            NovaCore.play(slot)
        } catch (ignored: Throwable) { }
        playing = true; Bar.playing = true
        val my = fadeGen; val t0 = SystemClock.uptimeMillis(); val k = slot
        val step = object : Runnable {
            override fun run() {
                if (my != fadeGen || slot != k) { try { NovaCore.eng().setVolume(k, 1f) } catch (ignored: Throwable) { }; return }
                val x = min(1.0, (SystemClock.uptimeMillis() - t0) / 240.0)
                try { NovaCore.eng().setVolume(k, (x * x * (3 - 2 * x)).toFloat()) } catch (ignored: Throwable) { }
                if (x < 1) main.postDelayed(this, 16)
            }
        }
        main.post(step)
    }

    /** La siguiente («auto»: porque acabó la anterior). */
    fun next(auto: Boolean) {
        if (queue.isEmpty()) return
        if (auto && Prefs.str("repeat", "off") == "one") { NovaCore.eng().seek(slot, 0.0); NovaCore.play(slot); return }
        if (!auto) markManual()
        Radio.onNext(auto)
        val xf = if (auto) 0.0 else if (playing) MANUAL_XF else 0.0
        Bar.dir = 1
        if (qi + 1 < queue.size) { qi++; loadCurrent(true, xf) }
        else if (Prefs.str("repeat", "off") == "all" || !auto) { qi = 0; loadCurrent(true, xf) }
        else { fadePause(); barSync() }
    }

    fun prev() {
        if (queue.isEmpty()) return
        val pos = try { NovaCore.position(slot).optDouble("pos", 0.0) } catch (t: Throwable) { 0.0 }
        if (pos > 3) { NovaCore.eng().seek(slot, 0.0); Bar.pos = 0.0; return }
        markManual()
        Bar.dir = -1
        qi = if (qi > 0) qi - 1 else if (Prefs.str("repeat", "off") == "all") queue.size - 1 else 0
        loadCurrent(true, if (playing) MANUAL_XF else 0.0)
    }

    fun seek(pos: Double) { if (current() == null) return; NovaCore.eng().seek(slot, max(0.0, pos)); Bar.pos = pos }

    /** Ir a una canción de la cola (desde «A continuación»). */
    fun jumpTo(i: Int) { if (i !in queue.indices) return; markManual(); Bar.dir = if (i > qi) 1 else -1; qi = i; loadCurrent(true, if (playing) MANUAL_XF else 0.0) }

    /** Subir una canción de la cola un puesto, o quitarla. */
    fun queueUp(i: Int) { if (i <= qi + 1 || i >= queue.size) return; val q = queue.toMutableList(); val x = q.removeAt(i); q.add(i - 1, x); queue = q; plKey = ""; syncPlaylist(); Nativo.onQueue() }
    fun queueDel(i: Int) { if (i <= qi || i >= queue.size) return; val q = queue.toMutableList(); q.removeAt(i); queue = q; plKey = ""; syncPlaylist(); Nativo.onQueue() }
    /** Que suene después de la de ahora, o al final de la cola. */
    fun playNext(id: String) { if (current() == null) { playQueue(listOf(id)); return }; val q = queue.toMutableList(); q.add(qi + 1, id); queue = q; plKey = ""; syncPlaylist(); Nativo.onQueue() }
    fun enqueue(ids: List<String>) { if (current() == null) { playQueue(ids); return }; queue = queue + ids; plKey = ""; syncPlaylist(); Nativo.onQueue() }
    /** Cambia lo que viene después de la de ahora (la radio, Nova…). */
    fun replaceAhead(ids: List<String>) { if (qi < 0) return; queue = queue.take(qi + 1) + ids; plKey = ""; syncPlaylist(); Nativo.onQueue() }
    fun append(ids: List<String>) { queue = queue + ids; plKey = ""; syncPlaylist() }
    /** Quita una canción de lo que viene. */
    fun dropAhead(id: String) { queue = queue.filterIndexed { i, x -> i <= qi || x != id }; plKey = ""; syncPlaylist() }
    /** Los ajustes de las mezclas cambiaron: el motor recibe la cola otra vez, con las mezclas nuevas. */
    fun resync() { plKey = ""; syncPlaylist() }
    fun setSource(s: String) { source = s }

    fun setShuffle(on: Boolean) {
        Prefs.set("shuffle", on)
        if (on && qi >= 0 && queue.size > qi + 2) { val rest = queue.subList(qi + 1, queue.size).shuffled(); queue = queue.subList(0, qi + 1) + rest; plKey = ""; syncPlaylist(); Nativo.onQueue() }
        barSync()
        Nativo.toast(if (on) "Aleatorio activado" else "Aleatorio desactivado")
    }

    fun cycleRepeat() {
        val r = when (Prefs.str("repeat", "off")) { "off" -> "all"; "all" -> "one"; else -> "off" }
        Prefs.set("repeat", r)
        plKey = ""; syncPlaylist()
        barSync()
        Nativo.toast(when (r) { "all" -> "Repetir toda la lista"; "one" -> "Repetir esta canción"; else -> "Repetir desactivado" })
    }

    // ---------------------------------------------------------------- lo que cuenta el motor
    private fun onAudio(o: JSONObject) {
        val type = o.optString("type")
        val sl = o.optInt("slot", -1)
        when (type) {
            "jump", "gapless" -> {
                if (sl >= 0 && sl != slot) return
                val src = o.optString("src")
                val auto = type == "gapless"
                val rp = Prefs.str("repeat", "off")
                val cand = if (auto && rp == "one") listOf(qi) else listOf((qi + 1) % max(1, queue.size), qi - 1)
                var idx = -1
                for (k in cand) if (k in queue.indices && Lib.byId(queue[k])?.let { Lib.src(it) } == src) idx = k
                if (idx < 0) idx = queue.indexOfFirst { id -> Lib.byId(id)?.let { Lib.src(it) } == src }
                Nativo.note((if (auto) "el motor pasó solo a la siguiente" else "el motor cambió de canción (botón)") + " → " + (if (idx < 0) "no está en la cola" else "#$idx"))
                val left = sleepLeft()
                if (auto && left != null && left < 4000) { sleepFinish(); return }
                if (idx < 0) return
                if (!auto) markManual()
                playing = true; Bar.playing = true
                if (auto && idx == qi) { current()?.let { begin(it, again = true) }; return }
                Bar.dir = if (idx >= qi || (idx == 0 && qi == queue.size - 1)) 1 else -1
                qi = idx
                current()?.let { begin(it) }
                plKey = ""; syncPlaylist()
            }
            "state" -> if (sl == slot) { playing = o.optBoolean("playing"); Bar.playing = playing; if (!playing) saveSession(true) }
            "ended" -> if (sl == slot) {
                val left = sleepLeft()
                if (left != null && left < 4000) { sleepFinish(); return }
                next(true)
            }
            "error" -> if (sl == slot) {
                val t = current()
                Nativo.toast(if (t != null) "No puedo reproducir «${t.title}» (formato no compatible). Paso a la siguiente." else "No puedo reproducir esta canción.")
                if (t != null && queue.size > 1) main.postDelayed({ next(false) }, 600)
            }
            "button" -> onButton(o)
            "noisy" -> {
                val fresh = !o.has("t") || System.currentTimeMillis() - o.optLong("t") < 4000
                if (o.optBoolean("handled")) { if (fresh) Nativo.toast("Auriculares desconectados: pausa"); playing = false; Bar.playing = false; return }
                if (fresh && playing) { fadePause(500); Nativo.toast("Auriculares desconectados: pausa") }
            }
            "sleep" -> if (sleepEnd > 0) sleepFinish()
            "focus" -> {
                // (otra app quiere sonar: el motor baja y pausa solo con un fundido, y vuelve solo si puede)
                if (o.optInt("value") == 1 && o.optBoolean("resumed")) { playing = true; Bar.playing = true }
            }
            "resumed" -> { adopt(); }
        }
    }

    /** Un botón del aviso, los auriculares o el reloj que el motor le pasa a la app (lo que no es suyo). */
    private fun onButton(e: JSONObject) {
        if (!e.optBoolean("forward")) return
        when (e.optString("action")) {
            "play" -> if (current() != null) resume() else toggle()
            "pause" -> fadePause()
            "playpause" -> toggle()
            "next" -> next(false)
            "prev" -> prev()
            "seek" -> if (e.has("pos")) seek(e.optDouble("pos"))
        }
    }

    // ---------------------------------------------------------------- las escuchas (lo que se oyó, también con la app dormida)
    private fun finishListen() {
        val l = listen ?: return; listen = null
        meta.add(l); while (meta.size > 40) meta.removeAt(0)
        main.postDelayed({ takeListens() }, 1500)
    }

    /** «naImport»: las escuchas que apuntó el motor, cada una una vez (con lo que solo sabía la app, si lo sabía). */
    fun takeListens() {
        try {
            val after = Prefs.num("naSeq", 0.0).toLong()
            val r = NovaCore.takeListens(after)
            val items = r.optJSONArray("items") ?: JSONArray()
            var seq = after; var added = 0
            val unseen = ArrayList<Pair<Track, JSONObject>>()
            for (i in 0 until items.length()) {
                val x = items.optJSONObject(i) ?: continue
                val xs = x.optLong("seq")
                if (xs <= seq) continue
                val l0 = listen
                if (l0 != null && l0.nat != null && l0.nat == x.optString("src") && l0.at < x.optLong("ts") - 500) break
                seq = xs
                val mi = meta.indexOfFirst { it.nat == x.optString("src") && it.at < x.optLong("ts") + 1000 && it.at > x.optLong("start") - 15000 }
                val m = if (mi >= 0) meta.removeAt(mi) else null
                val t = Lib.bySrc(x.optString("src"))
                if (t == null || !(x.optDouble("sec", 0.0) >= 1)) continue
                val end = x.optString("end")
                val manual = end == "boton" || (end == "app" && m != null && m.manual)
                if (m == null) unseen.add(t to x)
                Memoria.record(Memoria.makeEvent(t.id, x.optDouble("sec"), manual, t.title, t.artist, if (x.optDouble("dur", 0.0) > 0) x.optDouble("dur") else t.duration, false, m?.src ?: source, x.optLong("ts")))
                added++
            }
            if (unseen.isNotEmpty()) {
                for ((t, x) in unseen) { t.prevPlayed = t.lastPlayed; t.plays += 1; t.lastPlayed = max(t.lastPlayed, (if (x.has("start")) x.optLong("start") else x.optLong("ts")).toDouble()); Lib.save(t) }
                val ids = unseen.map { it.first.id }.reversed().distinct()
                val cur = current(); val h = Biblio.history()
                val head = if (cur != null && h.firstOrNull() == cur.id) listOf(cur.id) else emptyList()
                Prefs.set("history", (head + ids.filter { it !in head } + h.filter { it !in head && it !in ids }).take(30))
            }
            if (seq != after) Prefs.set("naSeq", seq)
            if (added > 0) Nativo.note("escuchas del motor: $added" + if (unseen.isNotEmpty()) " (${unseen.size} con la app dormida)" else "")
        } catch (t: Throwable) { Nativo.note("escuchas: ${t.message}") }
    }

    // ---------------------------------------------------------------- dónde se quedó (al volver a abrir)
    fun saveSession(force: Boolean) {
        if (!force && System.currentTimeMillis() - lastSave < 8000) return
        lastSave = System.currentTimeMillis()
        val t = current() ?: return
        if (t.net) return
        val pos = try { NovaCore.position(slot).optDouble("pos", 0.0) } catch (e: Throwable) { 0.0 }
        val o = LinkedHashMap<String, Any?>()
        o["q"] = queue.subList(max(0, qi - 50), min(queue.size, qi + 150))
        o["i"] = min(qi, 50); o["pos"] = Math.floor(pos).toLong(); o["src"] = source; o["ts"] = System.currentTimeMillis()
        Prefs.set("session", o)
    }

    /** Lo último que sonaba, en pausa donde se quedó (o sonando, si se pide). */
    fun resumeSession(play: Boolean): Boolean {
        val s = Json.obj(Prefs.get("session")) ?: return false
        val q = Json.list(s["q"])?.map { Json.s(it) } ?: return false
        val ids = q.filter { Lib.byId(it) != null }; if (ids.isEmpty()) return false
        val i = max(0, ids.indexOf(q.getOrNull(Json.l(s["i"]).toInt()) ?: ""))
        queue = ids; qi = i; source = Json.s(s["src"]).let { if (it == "radio" || it.isEmpty()) "library" else it }
        loadCurrent(false, 0.0, Json.d(s["pos"]))
        if (play) main.postDelayed({ resume() }, 300)
        return true
    }

    /** «naAdopt»: al abrir, si el motor ya estaba sonando (con la app cerrada), se sigue con eso tal cual. */
    fun adopt(): Boolean {
        val a = try { NovaCore.attach() } catch (t: Throwable) { return false }
        if (!a.optBoolean("active") || a.optBoolean("external")) return false
        val items = a.optJSONArray("items") ?: return false
        val ids = ArrayList<String>(); var cur = -1
        val idx = a.optInt("index", 0)
        for (k in 0 until items.length()) {
            val it = items.optJSONObject(k) ?: continue
            val t = Lib.byId(it.optString("id")) ?: Lib.bySrc(it.optString("src")) ?: continue
            if (k == idx) cur = ids.size
            ids.add(t.id)
        }
        if (cur < 0 || ids.isEmpty()) return false
        slot = a.optInt("slot", 0)
        queue = ids; qi = cur
        playing = a.optBoolean("playing"); Bar.playing = playing
        val s = Json.obj(Prefs.get("session")); source = Json.s(s?.get("src")).ifEmpty { "library" }
        plKey = ""
        current()?.let { t ->
            listen = Listen(t.id, System.currentTimeMillis(), source, Lib.src(t))
            UiState.nowId = t.id; barSync(); Nativo.look(); Nativo.bgSync()
        }
        Nativo.note("sigo con lo que sonaba: ${current()?.title}")
        return true
    }

    // ---------------------------------------------------------------- el temporizador para dormir
    fun sleepLeft(): Long? = if (sleepEnd > 0) sleepEnd - System.currentTimeMillis() else null

    /** «setSleep»: «off», «track» (al acabar esta) o los minutos. */
    fun setSleep(v: String) {
        if (v == "off") { sleepEnd = 0; NovaCore.setSleep(0, sleepFade); plKey = ""; syncPlaylist(); barSync(); Nativo.toast("Temporizador desactivado"); Nativo.onSleep(); return }
        if (v == "track") {
            if (current() == null || Bar.dur <= 0) { Nativo.toast("Pon una canción primero"); return }
            sleepEnd = System.currentTimeMillis() + (max(5.0, Bar.dur - Bar.pos) * 1000).toLong(); sleepFade = 12000
        } else { val m = v.toDoubleOrNull() ?: return; sleepEnd = System.currentTimeMillis() + (m * 60000).toLong(); sleepFade = 30000 }
        NovaCore.setSleep(sleepEnd, sleepFade); plKey = ""; syncPlaylist(); barSync()
        val m = v.toDoubleOrNull() ?: 0.0
        Nativo.toast(if (v == "track") "Me paro al terminar esta canción" else "Me paro dentro de " + (if (m >= 60) { val h = (m / 60); if (h % 1.0 == 0.5) "${h.toInt()} h 30" else "${h.toInt()} h" } else "${m.toInt()} min"))
        Nativo.onSleep()
    }

    private fun sleepTick() {
        val left = sleepLeft() ?: return
        // (el motor baja la música y la para solo; la app espera un momento a que lo haga)
        if (left <= 0 && left < -4000) sleepFinish()
        if (tickN % 4 == 0) barSync()
    }

    fun sleepFinish() {
        val late = sleepEnd > 0 && System.currentTimeMillis() - sleepEnd > 15000
        sleepEnd = 0; NovaCore.setSleep(0, sleepFade)
        fadePause()
        barSync(); Nativo.onSleep()
        if (late) return
        Nova.goodNight()
    }
}
