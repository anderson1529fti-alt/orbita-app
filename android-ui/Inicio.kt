package __PACKAGE__

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Fase 4, parte 2 · «Ahora» de la Órbita nativa: lo que antes leía la pantalla de la web (la cabecera, el teletipo, el
 * panel de la DJ, «Para ti hoy», la mezcla a medida y la mezcla hecha) lo prepara Android con las mismas piezas, y lo
 * que se toca en la pantalla llega aquí.
 */
object Inicio {
    private val main = Handler(Looper.getMainLooper())
    /** Lo que hace cada botón (la pantalla manda su número). */
    private val acts = HashMap<Int, () -> Unit>()
    private var seq = 0
    private fun k(fn: () -> Unit): Int { val id = ++seq; acts[id] = fn; return id }
    private var ideas: List<String> = emptyList()
    private var dirty = false
    private val keys = HashMap<String, String>()

    /** Algo cambió: se vuelve a preparar (una vez por fotograma como mucho). */
    fun mark() { if (dirty) return; dirty = true; main.post { dirty = false; sync(false) } }

    fun sync(force: Boolean) {
        if (!Nativo.ready) return
        if (force) keys.clear()
        if (acts.size > 3000) { acts.clear(); keys.clear() }
        put("top", { top() }) { UiState.hTop = it }
        put("tick", { tick() }) { UiState.hTick = it }
        put("dj", { dj() }) { o ->
            val was = UiState.hDj?.optString("inp", "") ?: ""
            val now = o.optString("inp", "")
            if (now != was) UiState.homeDraft = now
            UiState.hDj = o
        }
        put("bub", { bub() }) { UiState.hBub = it }
        put("slot", { slot() }) { UiState.hSlot = it }
        put("fy", { fy() }) { UiState.hFy = it }
        put("mixer", { mixer() }) { UiState.hMixer = it }
        put("out", { out() }) { UiState.hOut = it }
        vinyl()
    }

    /** (si no ha cambiado, no se toca: la pantalla no se vuelve a pintar entera) */
    private fun put(name: String, build: () -> JSONObject, set: (JSONObject) -> Unit) {
        val from = seq
        val o = try { build() } catch (t: Throwable) { Nativo.note("«Ahora» ($name): ${t.message}"); return }
        // la clave sin los números de los botones (cambian cada vez); si no ha cambiado, los botones nuevos sobran
        val key = o.toString().replace(Regex("\"k\":\\d+"), "")
        if (keys[name] == key) { if (seq > from) for (i in from + 1..seq) acts.remove(i); return }
        keys[name] = key; set(o)
    }

    private fun btn(t: String, primary: Boolean = false, small: Boolean = false, icon: Boolean = false, disabled: Boolean = false, on: Boolean = false, fn: () -> Unit): JSONObject =
        JSONObject().put("t", t).put("a", 0).put("sm", if (small) 1 else 0).put("p", if (primary) 1 else 0).put("i", if (icon) 1 else 0).put("d", if (disabled) 1 else 0).put("on", if (on) 1 else 0).put("k", k(fn))

    /** La portada de una canción como la ve «Ahora»: su id (Android ya sabe dónde está) o la inicial y el tono. */
    fun cover(t: Track?): JSONObject {
        if (t == null) return JSONObject().put("i", "♪").put("h", 280)
        if (Lib.coverFile(t) != null) return JSONObject().put("u", t.id)
        return JSONObject().put("i", t.title.trim().take(1).uppercase().ifEmpty { "♪" }).put("h", Momento.phHue(t))
    }

    // ---------------------------------------------------------------- arriba: la hora, el día y los avisos
    private fun top(): JSONObject {
        val m = Momento.moment()
        val ban = JSONArray()
        Ajustes.banner()?.let { u ->
            ban.put(JSONObject().put("b", "Nueva versión de Órbita: ${u.version}").put("t", u.notes.ifEmpty { "Descárgala e instálala encima: tus canciones y ajustes se conservan." })
                .put("btns", JSONArray().put(btn("Descargar", primary = true, small = true) { Nativo.openExternal(u.url) })
                    .put(btn("Luego", small = true) { Prefs.set("skipUpdate", u.version); mark() })))
        }
        if (Biblio.devState() != "granted" && !Prefs.bool("devWelcomeDone", false)) {
            ban.put(JSONObject().put("b", "Conecta la música de tu móvil").put("t", "Dale acceso y Órbita reproducirá todas tus canciones directamente, sin copiarlas ni ocupar espacio extra.")
                .put("btns", JSONArray().put(btn("Dar acceso", primary = true, small = true) { Prefs.set("devWelcomeDone", true); Biblio.connect(); mark() })
                    .put(btn("Luego", small = true) { Prefs.set("devWelcomeDone", true); mark() })))
        }
        Recap.hint()?.let { (head, text) ->
            ban.put(JSONObject().put("b", head).put("t", text)
                .put("btns", JSONArray().put(btn("Verlo", primary = true, small = true) { Recap.open(0) })
                    .put(btn("Luego", small = true) { Prefs.set("recapHintOff", Recap.year()); mark() })))
        }
        val hero = Prefs.str("heroMode", "vinyl")
        val o = JSONObject().put("ban", ban).put("clock", m.hh).put("alarm", Alarma.pill()).put("day", m.day).put("frag", m.franja.phrase).put("hint", m.hint)
            .put("hero", hero).put("lite", if (Nativo.lite()) 1 else 0)
        if (hero == "art") o.put("art", cover(Player.current()))
        return o
    }

    private fun tick(): JSONObject {
        if (!Prefs.bool("ticker", true)) return JSONObject().put("off", 1)
        val m = Momento.moment(); val t = Player.current()
        val parts = listOf(if (t != null) "Ahora suena: ${t.title} · ${t.artist}" else "Órbita FM", "${m.day} ${m.franja.phrase}", "Pídele lo que quieras a la DJ",
            "Elige la voz que más te guste", "Importa carpetas enteras", if (t != null) "Calidad: ${Biblio.quality(t)?.label ?: "directo"}" else "Sonido directo del archivo")
        return JSONObject().put("items", JSONArray(parts))
    }

    // ---------------------------------------------------------------- el panel de la DJ
    private fun sel(v: String, opts: List<Pair<String, String>>): JSONObject = JSONObject().put("v", v).put("o", JSONArray(opts.map { JSONArray().put(it.first).put(it.second) }))

    private fun dj(): JSONObject {
        if (ideas.isEmpty()) ideas = Cerebro.shuffle(IDEAS).take(4)
        val ai = Ia.ready()
        return JSONObject().put("role", Nova.djRole()).put("name", Nova.djName()).put("mine", if (Nova.who() != "nova") 1 else 0).put("talk", if (Voz.speaking) 1 else 0)
            .put("voice", if (Nova.voiceOn()) 1 else 0).put("intros", if (Nova.intros()) 1 else 0).put("mic", if (Oido.available()) 1 else 0)
            .put("ph", "Pídele algo: «algo para el gimnasio», «tristes pero bonitas»…").put("inp", Ordenes.draft)
            .put("chips", JSONArray(Ordenes.chips.map { c -> JSONObject().put("t", c).put("k", k { Ordenes.run(c) }) }))
            .put("ideas", JSONArray(ideas.map { s -> JSONObject().put("t", s).put("k", k { Ordenes.draft = s; Mezclas.create(request = s, autoplay = true) }) }))
            .put("roul", if (Nova.spinning || Mezclas.busy) 1 else 0).put("radio", if (Radio.on && Player.source == "radio") 1 else 0).put("all", if (Todo.on()) 1 else 0).put("allOn", 1)
            .put("ai", if (ai) "IA lista · con tu clave de la API" else "Sin IA: la DJ elige al azar (añade tu clave en Ajustes)").put("aiOn", if (ai) 1 else 0)
            .put("who", sel(Nova.who(), listOf("nova" to "Nova (voz del dispositivo)", "mine" to "Mi voz (mis grabaciones)", "mix" to "Las dos, por turnos")))
            .put("mix", sel(Mezcla.Aud.mix, Mezcla.MIX_ORDER.map { it to Mezcla.MIX_STYLES[it]!!.first }))
            .put("vbtn", Voz.label()).put("vbtnOn", 1)
    }

    private fun bub(): JSONObject {
        val shown = min(Nova.bubText.length, Nova.bubShown)
        return JSONObject().put("who", Nova.bubWho).put("t", Nova.bubText.take(shown)).put("c", if (shown < Nova.bubText.length) 1 else 0)
    }

    private fun slot(): JSONObject = if (!Nova.slotOn) JSONObject().put("off", 1) else JSONObject().put("t", Nova.slotText).put("s", Nova.slotSub).put("l", if (Nova.slotLanded) 1 else 0)

    // ---------------------------------------------------------------- «Para ti hoy»
    private fun eye(t: String) = JSONObject().put("e", "eye").put("t", t)
    private fun h3(t: String) = JSONObject().put("e", "h3").put("t", t)
    private fun sub(t: String) = JSONObject().put("e", "sub").put("t", t)
    private fun note(t: String) = JSONObject().put("e", "note").put("t", t)
    private fun btns(vararg b: JSONObject) = JSONObject().put("e", "btns").put("b", JSONArray(b.toList()))
    private fun card(daily: Boolean, grid: List<Track?>?, xl: Track?, body: List<JSONObject>): JSONObject {
        val o = JSONObject().put("daily", if (daily) 1 else 0)
        o.put("grid", if (grid != null) JSONArray(grid.take(4).let { g -> g + List(max(0, 4 - g.size)) { null } }.map { cover(it) }) else JSONObject.NULL)
        o.put("xl", if (xl != null) cover(xl) else JSONObject.NULL)
        o.put("body", JSONArray(body))
        return o
    }
    private fun mini(title: String, list: List<Track>, play: (Track) -> Unit): JSONObject {
        val cur = Player.current()
        val rows = JSONArray(list.map { t -> JSONObject().put("c", cover(t)).put("t", t.title).put("s", t.artist).put("k", k { play(t) })
            .put("play", if (cur?.id == t.id && Player.playing) 1 else 0).put("liked", 0).put("acts", JSONArray()) })
        return card(false, null, null, listOf(eye(title), JSONObject().put("e", "mini").put("rows", rows)))
    }

    private fun fy(): JSONObject {
        val cards = JSONArray()
        val s = Json.obj(Prefs.get("session")); val q = Json.list(s?.get("q"))?.map { Json.s(it) }
        val cont = q?.getOrNull(Json.l(s?.get("i")).toInt())?.let { Lib.byId(it) }
        if (cont != null && !Player.playedOnce) {
            val pos = Json.d(s?.get("pos"))
            cards.put(card(false, null, cont, listOf(eye("Continúa donde lo dejaste"), h3(cont.title), sub(cont.artist + if (pos > 0) " · en el ${Cerebro.fmt(pos)}" else ""),
                btns(btn("Seguir escuchando", primary = true, small = true, icon = true) { Player.resumeSession(true) }))))
        }
        val d = Cerebro.buildDaily()
        if (d != null) {
            val items = d.ids.mapNotNull { Lib.byId(it) }; val total = items.sumOf { it.duration }
            cards.put(card(true, items, null, listOf(eye((if (d.ai) "Mezcla del día · por ${Nova.name()}" else "Mezcla del día") + " · ${items.size} canciones · ${Cerebro.fmtLong(total)}"), h3(d.name), note(d.desc),
                btns(btn("Escuchar", primary = true, small = true, icon = true) { Cerebro.playDaily(0) }, btn("Guardar lista", small = true) { Listas.saveIds(d.name, d.ids) },
                    btn("Otra", small = true) { Cerebro.buildDaily(true); mark() }))))
            val so = d.song?.let { Lib.byId(it.id) }
            if (so != null) cards.put(card(false, null, so, listOf(eye("Canción del día"), h3(so.title), sub(so.artist), note(d.song.why),
                btns(btn("Escuchar", primary = true, small = true, icon = true) { Player.playQueue(listOf(so.id), 0, "library") }))))
            if (!d.ai && !d.aiTried && Ia.ready()) Ia.daily(d)
        }
        Todo.card()?.let { c ->
            val pct = if (c.n > 0) Math.round(c.done * 100.0 / c.n) else 0
            val body = arrayListOf(eye("Todo mi Órbita · ${c.n} canciones" + if (c.cycle > 1) " · vuelta ${c.cycle}" else ""), h3("Todas tus canciones"), sub("Sin repetir ninguna hasta que suenen todas, en un orden que fluye"))
            if (c.done > 0) { body.add(note("")); body.add(note("Van ${c.done} de ${c.n}" + if (c.on) " · sonando ahora" else "")) }
            @Suppress("UNUSED_VARIABLE") val unused = pct
            val bs = arrayListOf(btn(if (c.done > 0 && !c.on) "Seguir" else if (c.on) "Sonando" else "Empezar", primary = true, small = true, icon = true) { Todo.startAll() })
            if (c.done > 0) bs.add(btn("Empezar de nuevo", small = true) { Todo.startAll(true) })
            body.add(JSONObject().put("e", "btns").put("b", JSONArray(bs)))
            cards.put(card(true, c.next.map { Lib.byId(it) }, null, body))
        }
        try { Recos.card { fn -> k(fn) }?.let { cards.put(it) } } catch (t: Throwable) { Nativo.note("descubre: ${t.message}") }
        val red = Cerebro.rediscover(); val nw = Cerebro.newArrivals()
        if (red.isNotEmpty()) cards.put(mini("Redescubre", red) { t -> Player.playQueue(red.map { it.id }, red.indexOf(t), "library") })
        if (nw.isNotEmpty()) cards.put(mini("Recién llegadas", nw) { t -> Player.playQueue(nw.map { it.id }, nw.indexOf(t), "library") })
        if (cards.length() == 0) return JSONObject().put("off", 1)
        return JSONObject().put("date", Cerebro.cap(Momento.moment().day)).put("cards", cards)
    }

    // ---------------------------------------------------------------- la mezcla a medida
    private fun mixer(): JSONObject {
        val b = { t: String, on: Boolean, fn: () -> Unit -> JSONObject().put("t", t).put("on", if (on) 1 else 0).put("k", k(fn)) }
        val moods = JSONArray(Momento.MOODS.map { m -> b(m.label, Mezclas.mood == m.id) { Mezclas.mood = m.id; Prefs.set("mood", m.id); mark() } })
        val lens = JSONArray(listOf("10" to "10", "20" to "20", "30" to "30", "50" to "50", "60m" to "1 h", "inf" to "Sin fin").map { (v, t) -> b(t, Mezclas.len == v) { Mezclas.len = v; Prefs.set("len", v); mark() } })
        return JSONObject().put("moment", Momento.moment().label).put("moods", moods).put("lens", lens)
    }

    private fun out(): JSONObject {
        if (Mezclas.busy) {
            val r = Mezclas.thinkingNote
            return JSONObject().put("e", "think").put("b", "Nova está escuchando tu biblioteca…").put("t", r).put("stop", btn("Parar", small = true) { Mezclas.cancel() })
        }
        val mx = Mezclas.mix
        if (mx != null) {
            val items = mx.ids.mapNotNull { Lib.byId(it) }; val total = items.sumOf { it.duration }
            val ps = JSONArray()
            if (mx.desc.isNotEmpty()) ps.put(JSONObject().put("t", mx.desc).put("n", 0))
            mx.request?.let { ps.put(JSONObject().put("t", "Pedido: «$it»").put("n", 1)) }
            val acts = JSONArray().put(btn("Reproducir", primary = true, icon = true) { Mezclas.play(0) }).put(btn("Guardar lista") { Listas.saveIds(mx.name, mx.ids) }).put(btn("Otra versión") { Mezclas.again() })
            val cur = Player.current()
            val its = JSONArray(items.mapIndexed { i, t -> JSONObject().put("n", Momento.pad(i + 1)).put("c", cover(t)).put("t", t.title).put("s", t.artist).put("w", mx.why[t.id] ?: "")
                .put("d", Cerebro.fmt(t.duration)).put("play", if (cur?.id == t.id && Player.source == "mix") 1 else 0).put("k", k { Mezclas.play(i) }) })
            val o = JSONObject().put("e", "mix").put("eye", (if (mx.source == "ai") "Mezcla de Nova (IA)" else "Mezcla aleatoria") + " · ${mx.moment} · ${items.size} canciones · ${Cerebro.fmt(total)}")
                .put("name", mx.name).put("ps", ps).put("acts", acts).put("items", its)
            if (mx.discover.isNotEmpty()) o.put("disc", JSONObject().put("eye", "Para descubrir fuera de tu biblioteca").put("rows", JSONArray(mx.discover.map { d ->
                JSONObject().put("b", d["title"] ?: "").put("r", if ((d["artist"] ?: "").isNotEmpty()) " · ${d["artist"]}" else "").put("w", d["why"] ?: "")
                    .put("links", JSONArray(listOf("YouTube", "Spotify", "SoundCloud").map { s -> JSONObject().put("t", s).put("k", k { Nativo.openExternal(Mezclas.link(s, d["title"] ?: "", d["artist"] ?: "")) }) }))
            })))
            o.put("note", mx.note ?: "")
            return o
        }
        val own = Lib.tracks.count { !it.demo }
        return JSONObject().put("e", "empty").put("h", "Aquí aparecerá tu mezcla").put("t", if (own > 0) "Tienes $own ${if (own == 1) "canción" else "canciones"}. Pídele algo a la DJ, gira la ruleta o crea una mezcla a medida."
            else "Ya puedes probar con las cuatro pistas de demostración. Cuando subas tu música en Biblioteca, la DJ pinchará tus canciones.")
    }

    // ---------------------------------------------------------------- el disco (lo que suena, para que gire)
    private var vinKey = ""; private var vinSent = 0L
    fun vinyl() {
        val t = Player.current(); val playing = t != null && Player.playing
        val dur = if (t != null && Bar.dur > 0) Bar.dur else 0.0
        val v = JSONObject().put("id", t?.id ?: "").put("title", t?.title ?: "").put("play", if (playing) 1 else 0).put("own", if (playing && t?.net != true) 1 else 0)
            .put("dur", Math.round(dur * 100) / 100.0).put("art", if (t != null && Lib.coverFile(t) != null) t.id else "").put("lit", 1).put("rm", if (Nativo.reduceMotion()) 1 else 0)
            .put("slow", if (Nativo.lite()) 1 else 0)
        val key = v.toString(); val now = System.currentTimeMillis()
        if (key == vinKey && now - vinSent < 5000) return
        vinKey = key; vinSent = now
        v.put("pos", if (dur > 0) Math.round(min(dur, Bar.pos) * 100) / 100.0 else 0.0)
        UiState.hVin = v; UiState.hVinAt = android.os.SystemClock.uptimeMillis()
    }

    // ---------------------------------------------------------------- lo que se toca
    fun onTap(o: JSONObject) {
        val kv = o.opt("k")
        if (kv is Number) { acts[kv.toInt()]?.invoke(); mark(); return }
        when (o.optString("k")) {
            "#alarmPill" -> Nativo.go("ajustes")
            ".hero-top .gear" -> Nativo.go("ajustes")
            "#artWrap", "#vizWrap", "#vinylWrap" -> Ventanas.open("np")
            "#tVoice" -> { val v = !Nova.voiceOn(); Prefs.set("voiceOn", v); if (!v) Voz.stop() else Nova.djTalk("Vuelvo a tener voz.", "test") }
            "#tIntros" -> { val v = !Nova.intros(); Prefs.set("intros", v); Nativo.toast(if (v) "La DJ presentará cada canción" else "La DJ solo hablará al empezar") }
            "#openChat" -> Nativo.go("chat")
            "#djBubble" -> Nova.bubbleFinish()
            "#btnRoulette" -> Nova.spinRoulette()
            "#btnRadio" -> Nova.radioButton()
            "#btnAll" -> if (Todo.on()) Todo.stopAll() else Todo.startAll()
            "#voicePickBtn" -> Nativo.go("ajustes")
            "#djMic" -> Oido.listen()
            "#btnSurprise" -> Mezclas.create(surprise = true)
            "#btnMix" -> Mezclas.create()
            else -> Nativo.note("«Ahora»: sin hacer todavía: ${o.optString("k")}")
        }
        mark()
    }

    fun onSel(sel: String, v: String) {
        when (sel) {
            "#whoSel" -> { Prefs.set("who", v); if (v != "nova" && Grabaciones.all().isEmpty()) Nova.setBubble("Aún no has grabado ninguna frase. Ve a «Tu voz» y graba tu saludo; mientras tanto hablo yo.") }
            "#mixSel" -> { Prefs.set("mixStyle", v); Player.resync() }
        }
        mark()
    }

    val IDEAS = listOf("Algo para el gimnasio", "Para estudiar sin distraerme", "Tristes pero bonitas", "Para cantar a gritos", "Para dormir", "Como si fuera viernes")
}

/** Las mezclas a medida y las que se piden («Crear mezcla», «Sorpréndeme», «algo para…»). */
object Mezclas {
    var mood = Prefs.str("mood", "auto")
    var len = Prefs.str("len", "20")
    var mix: Cerebro.Mix? = null
    @Volatile var busy = false; private set
    var thinkingNote = ""; private set
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    val SCENARIOS = listOf("banda sonora de un viaje en coche de noche", "tu día como si fuera una película indie", "cocinar algo nuevo sin receta",
        "lluvia contra la ventana", "el último día de vacaciones", "un videojuego de los 90", "domingo sin planes",
        "concierto en una azotea al atardecer", "entrenamiento de protagonista de anime", "paseo largo sin destino",
        "fiesta en la cocina con amigos", "estudiar en una biblioteca enorme y silenciosa", "tren hacia la costa",
        "recuperarse de una semana larga", "la escena final de una serie")

    fun moodFromText(s0: String): String {
        val s = s0.lowercase()
        val map = listOf(Regex("gym|gimnas|entren|correr|pesas|cardio") to "gym", Regex("estudi|concentr|trabaj|foco|leer") to "foco", Regex("dormir|relaj|calma|tranquil|chill") to "calma",
            Regex("fiesta|bailar|baile|viernes|perreo") to "fiesta", Regex("trist|llorar|gris|lluvia|bajón|bajon|melanc") to "gris", Regex("nostal|recuerd|antig|infancia") to "nostalgia",
            Regex("energ|subir|motiv|gritos|cantar") to "energia")
        for ((re, m) in map) if (re.containsMatchIn(s)) return m
        return "auto"
    }

    fun link(site: String, title: String, artist: String): String {
        val q = Online.enc("$title $artist".trim())
        return when (site) { "YouTube" -> "https://www.youtube.com/results?search_query=$q"; "Spotify" -> "https://open.spotify.com/search/$q"; else -> "https://soundcloud.com/search?q=$q" }
    }

    /** «createMix»: con la IA (si hay clave) o con el cerebro del móvil. */
    fun create(surprise: Boolean = false, request: String = "", autoplay: Boolean = false, hint: String = "") {
        val pool = Cerebro.pool()
        if (pool.size < 2) { Nova.setBubble("Necesito al menos dos canciones. Súbelas en Biblioteca y lo intentamos."); return }
        if (busy) return
        val m = Momento.moment()
        val scenario = if (surprise) Cerebro.pick(SCENARIOS) else null
        val md = if (surprise) Cerebro.pick(Momento.MOODS.drop(1).map { it.id }) else if (request.isNotEmpty()) moodFromText(request) else mood
        if (surprise) { mood = md; Prefs.set("mood", md) }
        val reqN = if (request.isNotEmpty()) " " + Biblio.norm(request) + " " else ""
        val endless = len == "inf" || Regex("sin fin|infinit|sin parar|todo el rato|toda la (noche|tarde|manana)").containsMatchIn(reqN)
        val minutes = if (len == "60m" && request.isEmpty()) 60 else 0
        val n = min(if (endless) 25 else if (len == "60m") 18 else (len.toIntOrNull() ?: 10), pool.size)
        val my = ++gen
        fun finish(mx0: Cerebro.Mix?, note: String) {
            if (my != gen) return
            var mx = mx0
            if (mx == null) {
                mx = if (request.isNotEmpty()) Cerebro.intentMix(if (hint.isNotEmpty()) "$request $hint" else request, pool, n, m) else Cerebro.localMix(pool, m, md, scenario, n, minutes)
                if (request.isNotEmpty()) mx.request = request
                if (note.isNotEmpty()) mx.note = note + (mx.note?.let { " $it" } ?: "")
            }
            if (endless) { mx.endless = true; mx.refill = if (request.isNotEmpty()) mapOf("text" to (if (hint.isNotEmpty()) "$request $hint" else request)) else mapOf("mood" to md); mx.desc = (if (mx.desc.isNotEmpty()) mx.desc + " " else "") + "Sin fin: cuando se vaya acabando, Nova añade más canciones en la misma onda." }
            mix = mx; busy = false
            Inicio.mark()
            if (autoplay) play(0) else Nova.setBubble(mx.greeting.ifEmpty { "Lista: ${mx.name}. Dale a reproducir cuando quieras." })
            Ordenes.afterMix(mx, request)
        }
        if (Ia.ready()) {
            busy = true; thinkingNote = if (request.isNotEmpty()) "Petición: $request" else if (scenario != null) "Tema sorpresa: $scenario" else "Buscando el orden perfecto para este momento."
            Nova.setBubble(if (request.isNotEmpty()) "Marchando: «$request». Déjame buscar en tu biblioteca…" else if (scenario != null) "Tema sorpresa: $scenario. Buscando…" else "Buscando el orden perfecto para este momento…")
            Inicio.mark()
            Ia.mix(pool, m, md, scenario, n, request) { r, err ->
                main.post {
                    if (my != gen) return@post
                    if (err == "cancelled") { busy = false; Inicio.mark(); Nova.setBubble("Vale, lo dejo. ¿Probamos otra cosa?"); return@post }
                    finish(r, if (r == null) (Ia.errorNote(err) + " He hecho la mezcla al azar.") else "")
                }
            }
            return
        }
        finish(null, "")
    }

    fun cancel() { gen++; busy = false; Ia.cancel(); Inicio.mark(); Nova.setBubble("Vale, lo dejo. ¿Probamos otra cosa?") }
    fun again() { val mx = mix ?: return; create(request = mx.request ?: "", surprise = mx.scenario != null && mx.request == null) }

    fun play(i: Int) {
        val mx = mix ?: return
        val t = mx.ids.getOrNull(i)?.let { Lib.byId(it) }
        val talk = if (i == 0) (mx.greeting.ifEmpty { if (t != null) Nova.introFor(t) else "" }).ifEmpty { null } else null
        Nova.mixName = mx.name
        Player.playQueue(mx.ids, i, "mix", talk?.let { it to "saludo" }, mx.intros)
        Player.mixEndless = if (mx.endless) (mx.refill ?: emptyMap<String, Any?>()) else null
    }

    /** «mixRefill»: más canciones en la misma onda antes de que se acabe la cola (nunca las que acaban de sonar). */
    fun refill(): List<String> {
        val e = Player.mixEndless as? Map<*, *> ?: return emptyList()
        if (Player.source != "mix") return emptyList()
        val pool = Cerebro.pool(); if (pool.size < 3) return emptyList()
        val cands: List<String> = try {
            val like = e["like"] as? String; val text = e["text"] as? String
            if (like != null) { val seed = Lib.byId(like) ?: Player.current(); if (seed != null && !seed.net) Cerebro.similarTo(seed, 40).map { it.id } else emptyList() }
            else if (text != null) Cerebro.intentMix(text, pool, 40, Momento.moment()).ids
            else Cerebro.localMix(pool, Momento.moment(), (e["mood"] as? String) ?: "auto", null, 40).ids
        } catch (t: Throwable) { emptyList() }
        val q = Player.queue
        val recent = q.takeLast(min(60, max(8, (pool.size * 0.6).toInt()))).toSet()
        var ids = cands.filter { Lib.byId(it) != null && it !in recent }
        if (ids.size < 3) { val last = q.takeLast(5).toSet(); ids = cands.filter { Lib.byId(it) != null && it !in last } }
        return ids.distinct().take(10)
    }
}
