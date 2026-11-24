package __PACKAGE__

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Fase 4, parte 2 · «Mis listas» y «Tú» de la Órbita nativa. Las piezas que antes leía la pantalla de la web (las
 * tarjetas de las listas, tu resumen, sus gráficas, lo que Nova sabe de ti) las prepara Android con los datos de aquí,
 * con los mismos nombres que usaba la web; lo que se toca en la pantalla llega aquí.
 */
object Paginas {
    val NAMES = setOf("listas", "tu")
    private const val BASE = 1_000_000
    private val main = Handler(Looper.getMainLooper())
    /** Lo que hace cada botón (la pantalla manda su número; los de aquí, por encima de [BASE]). */
    private val acts = HashMap<Int, () -> Unit>()
    private var seq = BASE
    fun owns(k: Int): Boolean = k > BASE
    private fun k(fn: () -> Unit): Int { if (seq > 2_000_000_000) seq = BASE; val id = ++seq; acts[id] = fn; return id }
    private val keys = HashMap<String, String>()
    private val dirty = HashSet<String>()
    private var posted = false
    /** Los botones de borrar que esperan el segundo toque («¿Borrar?»), hasta cuándo. */
    private val armed = HashMap<String, Long>()
    /** Las tablas de las gráficas que están abiertas («Ver los datos»). */
    private val open = HashSet<String>()

    /** Algo cambió en una pantalla («» = en las dos): se vuelve a preparar si está a la vista. */
    fun mark(name: String = "") {
        if (name.isEmpty()) dirty.addAll(NAMES) else dirty.add(name)
        if (posted) return; posted = true
        main.post {
            posted = false
            val d = dirty.toList(); dirty.clear()
            for (n in d) if (n == UiState.screen) sync(n, false)
        }
    }

    fun sync(name: String, force: Boolean) {
        if (!Nativo.ready) return
        if (force) keys.keys.removeAll { it.startsWith("$name.") }
        when (name) {
            "listas" -> { put(name, "head") { listasHead() }; put(name, "nova") { novaPart() }; put(name, "lists") { listsPart() } }
            "tu" -> { put(name, "head") { tuHead() }; put(name, "out") { tuOut() }; put(name, "mem") { tuMem() }; put(name, "links") { tuLinks() } }
        }
    }

    /** (si no ha cambiado, no se toca: la pantalla no se vuelve a pintar; los botones nuevos que no se usan, fuera) */
    private fun put(name: String, part: String, build: () -> JSONObject) {
        val from = seq
        val o = try { build() } catch (t: Throwable) { Nativo.note("«$name» ($part): ${t.message}"); return }
        val kk = "$name.$part"
        val key = o.toString().replace(Regex("\"k\":\\d+"), "")
        if (keys[kk] == key && UiState.pages[kk] != null) { if (seq >= from + 1) for (i in from + 1..seq) acts.remove(i); return }
        UiState.pages[kk]?.let { drop(it) }
        keys[kk] = key
        UiState.pages[kk] = o
    }
    private fun drop(v: Any?) {
        when (v) {
            is JSONObject -> { val it = v.keys(); while (it.hasNext()) { val n = it.next(); val x = v.opt(n); if (n == "k" && x is Number) acts.remove(x.toInt()) else drop(x) } }
            is JSONArray -> for (i in 0 until v.length()) drop(v.opt(i))
        }
    }

    fun onTap(o: JSONObject) {
        val kv = o.opt("k")
        if (kv is Number) acts[kv.toInt()]?.invoke()
        mark(UiState.screen)
    }

    /** «armOrRun»: el primer toque pregunta («¿Borrar?»); el segundo, en tres segundos, lo hace. */
    private fun armOrRun(key: String, run: () -> Unit) {
        val now = System.currentTimeMillis()
        if ((armed[key] ?: 0L) > now) { armed.remove(key); run(); return }
        armed[key] = now + 3000
        main.postDelayed({ if ((armed[key] ?: 0L) <= System.currentTimeMillis()) { armed.remove(key); mark() } }, 3050)
    }
    private fun isArmed(key: String): Boolean = (armed[key] ?: 0L) > System.currentTimeMillis()

    private fun btn(t: String, primary: Boolean = false, small: Boolean = false, icon: Boolean = false, armedOn: Boolean = false, fn: () -> Unit): JSONObject =
        JSONObject().put("t", t).put("a", if (armedOn) 1 else 0).put("sm", if (small) 1 else 0).put("p", if (primary) 1 else 0).put("i", if (icon) 1 else 0).put("d", 0).put("on", 0).put("k", k(fn))
    private fun arr(l: List<JSONObject>): JSONArray { val a = JSONArray(); for (x in l) a.put(x); return a }
    private fun cover(t: Track?): JSONObject = Inicio.cover(t)

    // ---------------------------------------------------------------- «Mis listas»
    private fun listasHead(): JSONObject {
        val n = Lib.playlists.size
        return JSONObject().put("title", "Mis listas").put("meta", if (n > 0) "$n ${if (n == 1) "lista" else "listas"}" else "")
    }

    /** Las listas que Nova mantiene al día con lo que escuchas de verdad («novaLists»). */
    class NovaList(val id: String, val name: String, val desc: String, val ids: List<String>)

    fun novaLists(): List<NovaList> {
        val now = System.currentTimeMillis()
        val ev = synchronized(Memoria) { Memoria.events.filter { !it.net && now - it.ts < 120 * 864e5 } }
        fun count(pred: (Memoria.Ev) -> Boolean): List<String> {
            val m = LinkedHashMap<String, Int>()
            for (e in ev) if (!e.skip && (e.pct >= 0.5 || e.sec >= 60) && pred(e)) m[e.id] = (m[e.id] ?: 0) + 1
            return m.entries.sortedByDescending { it.value }.map { it.key }.filter { Lib.byId(it) != null && it !in Cerebro.ban }
        }
        val pool = Cerebro.pool().filter { !it.demo }
        fun en(t: Track): Double? = Cerebro.vec(t)?.get(0)
        return listOf(
            NovaList("n-finde", "Tus noches de fin de semana", "Lo que pones los viernes y sábados por la noche.", count { (it.dow == 5 || it.dow == 6) && (it.hour >= 20 || it.hour < 4) }),
            NovaList("n-mananas", "Tus mañanas", "Lo que más escuchas antes de mediodía.", count { it.hour in 6..11 }),
            NovaList("n-noches", "Tus noches", "Lo que más escuchas cuando ya es tarde.", count { it.hour >= 22 || it.hour < 5 }),
            NovaList("n-nunca", "Las que nunca saltas", "Siempre las escuchas enteras.", pool.filter { Biblio.plays(it) >= 3 && it.skips <= 0 }.sortedByDescending { Biblio.plays(it) }.map { it.id }),
            NovaList("n-energia", "Para entrenar", "Tus canciones con más energía.", pool.filter { en(it)?.let { e -> e >= 0.62 } == true }.sortedByDescending { en(it)!! + (if (Biblio.liked(it)) 0.2 else 0.0) }.map { it.id }),
            NovaList("n-calma", "Para bajar revoluciones", "Lo más tranquilo de tu música.", pool.filter { en(it)?.let { e -> e <= 0.38 } == true }.sortedBy { en(it)!! }.map { it.id })
        ).map { NovaList(it.id, it.name, it.desc, it.ids.take(30)) }.filter { it.ids.size >= 5 }
    }

    private fun card(covers: List<Track?>, name: String, desc: String, meta: String, wrap: Boolean, btns: List<JSONObject>): JSONObject {
        val cv = JSONArray(); for (t in covers.take(4)) cv.put(cover(t))
        return JSONObject().put("covers", cv).put("name", name).put("desc", desc).put("meta", meta).put("wrap", if (wrap) 1 else 0).put("btns", arr(btns))
    }

    private fun novaPart(): JSONObject {
        val ls = novaLists()
        if (ls.isEmpty()) return JSONObject().put("off", 1)
        val cards = ls.map { l ->
            card(l.ids.take(4).map { Lib.byId(it) }, l.name, l.desc, "${l.ids.size} canciones", false,
                listOf(btn("Escuchar", primary = true, small = true, icon = true) { playNova(l.id) }.put("s", 16)))
        }
        return JSONObject().put("eye", "Hechas por ${Nova.name()} · se actualizan solas").put("cards", arr(cards))
    }

    private fun playNova(id: String) {
        val l = novaLists().firstOrNull { it.id == id } ?: return
        Player.playQueue(l.ids, 0, "nova")
        Lib.byId(l.ids[0])?.let { Ordenes.say("${l.name}. Empezamos con ${it.title}.") }
    }

    private fun listsPart(): JSONObject {
        val pls = Lib.playlists.sortedByDescending { Json.d(it["createdAt"]) }
        if (pls.isEmpty()) return JSONObject().put("empty", JSONObject().put("h", "Aún no has guardado ninguna lista")
            .put("t", "Crea una mezcla y pulsa «Guardar lista». Aquí quedarán con el momento en que nacieron.").put("btn", btn("Ir a Ahora") { Nativo.go("inicio") }))
        val cards = pls.map { p ->
            val id = Json.s(p["id"])
            val items = (Json.list(p["trackIds"]) ?: emptyList()).mapNotNull { Lib.byId(Json.s(it)) }
            val covers = ArrayList<Track?>(items.take(4)); while (covers.size < 4) covers.add(null)
            val total = items.sumOf { it.duration }
            val meta = "${items.size} canciones · ${Cerebro.fmt(total)} · ${Json.s(p["moment"])}${if (Json.s(p["source"]) == "ai") " · Nova" else ""}"
            val del = "pl-del:$id"
            card(covers, Json.s(p["name"]), Json.s(p["description"]), meta, true, listOf(
                btn("Escuchar", primary = true, small = true, icon = true) { playList(id, false) }.put("s", 16),
                JSONObject().put("ic", "pl-shuffle").put("armed", 0).put("t", "").put("l", "Escuchar en aleatorio").put("k", k { playList(id, true) }),
                JSONObject().put("ic", "pl-del").put("armed", if (isArmed(del)) 1 else 0).put("t", if (isArmed(del)) "¿Borrar?" else "").put("l", "Borrar lista").put("k", k { armOrRun(del) { deleteList(id) } })
            ))
        }
        return JSONObject().put("cards", arr(cards))
    }

    private fun playList(id: String, shuffle: Boolean) {
        val p = Lib.playlists.firstOrNull { Json.s(it["id"]) == id } ?: return
        if (shuffle && !Prefs.bool("shuffle", false)) { Prefs.set("shuffle", true); Player.barSync() }
        val intros = (Json.obj(p["intros"]) ?: emptyMap()).mapValues { Json.s(it.value) }
        val greet = Json.s(p["greeting"])
        Player.playQueue((Json.list(p["trackIds"]) ?: emptyList()).map { Json.s(it) }, 0, "mix", if (greet.isNotEmpty()) greet to "saludo" else null, intros)
    }

    private fun deleteList(id: String) {
        Lib.playlists.removeAll { Json.s(it["id"]) == id }
        Store.delRec("playlists", id)
        Nativo.onLists()
        Nativo.toast("Lista borrada")
    }

    // ---------------------------------------------------------------- «Tú»
    private fun range(): Int = Prefs.num("tuRange", 30.0).toInt()

    private fun tuHead(): JSONObject {
        val r = range()
        val seg = JSONArray()
        for ((v, t) in listOf(7 to "Semana", 30 to "Mes", 0 to "Siempre")) seg.put(JSONObject().put("t", t).put("on", if (r == v) 1 else 0).put("k", k { Prefs.set("tuRange", v); mark("tu") }))
        val all = Memoria.stats(0)
        return JSONObject().put("title", "Tú").put("meta", if (Memoria.events.isNotEmpty()) "${all.n} escuchas guardadas" else "").put("range", seg)
    }

    private fun h2(t: String) = JSONObject().put("e", "h2").put("t", t)
    private fun note(t: String) = JSONObject().put("e", "note").put("t", t).put("link", JSONObject.NULL)
    private fun eye(t: String) = JSONObject().put("e", "eye").put("t", t)
    private fun panel(items: List<JSONObject>, glow: Boolean = false) = JSONObject().put("e", "panel").put("glow", if (glow) 1 else 0).put("items", arr(items))
    private fun list(rows: List<JSONObject>) = JSONObject().put("e", "list").put("rows", arr(rows))
    private fun row(t: String, s: String, c: JSONObject?, n: String? = null, v: String = "", pad: Boolean = false, play: Boolean = false, btn: JSONObject? = null): JSONObject {
        val o = JSONObject().put("e", "row").put("pad", if (pad) 1 else 0).put("n", n ?: "").put("hasN", if (n != null) 1 else 0)
            .put("c", c ?: JSONObject.NULL).put("hasC", if (c != null) 1 else 0).put("t", t).put("s", s).put("v", v).put("play", if (play) 1 else 0)
        o.put("btn", btn ?: JSONObject.NULL)
        return o
    }
    private fun rowBtn(l: String, t: String = "", small: Boolean = false, x: Boolean = false, fn: () -> Unit) =
        JSONObject().put("k", k(fn)).put("l", l).put("t", t).put("small", if (small) 1 else 0).put("x", if (x) 1 else 0)

    /** Una gráfica de barras («barsChart»): las barras, dónde se toca cada una, su dato y la tabla. */
    private fun chart(id: String, vals: DoubleArray, labels: List<String>, tickEvery: Int = 1, bw: Double = 16.0, gap: Double = 4.0, maxW: Double = 560.0, name: String = ""): JSONObject {
        val n = vals.size; val mx = vals.maxOrNull() ?: 0.0; val w = n * (bw + gap) - gap; val base = 96.0; val top = 6.0
        val hi = if (mx > 0) Cerebro.argmax(vals) else -1
        val bars = JSONArray(); val hits = JSONArray()
        fun r1(x: Double) = Math.round(x * 10) / 10.0
        fun r2(x: Double) = Math.round(x * 100) / 100.0
        vals.forEachIndexed { i, v ->
            val x = i * (bw + gap); val h = if (v > 0) max(3.0, v / mx * (base - top)) else 0.0; val y = base - h
            if (h > 0) bars.put(JSONObject().put("x", x).put("y", r1(y)).put("hi", if (i == hi) 1 else 0))
            hits.put(JSONObject().put("x", x - gap / 2).put("w", bw + gap).put("l", labels[i]).put("v", Cerebro.fmtLong(v)))
        }
        val xs = JSONArray()
        labels.forEachIndexed { i, l -> if (i % tickEvery == 0 && l.isNotEmpty()) xs.put(JSONObject().put("t", l).put("x", r2((i * (bw + gap) + bw / 2) / w * 100))) }
        val opened = id in open
        val o = JSONObject().put("e", "chart").put("W", w).put("H", base).put("maxW", maxW).put("bw", bw).put("bars", bars).put("hits", hits)
            .put("peak", if (hi >= 0) JSONObject().put("t", Cerebro.fmtLong(vals[hi])).put("x", r2((hi * (bw + gap) + bw / 2) / w * 100)) else JSONObject.NULL)
            .put("xs", xs).put("sum", JSONObject().put("t", "Ver los datos").put("k", k { if (!open.remove(id)) open.add(id); mark("tu") }).put("open", if (opened) 1 else 0).put("m", 0))
            .put("th", JSONArray().put(name).put("Tiempo"))
        val rows = JSONArray()
        if (opened) vals.forEachIndexed { i, v -> rows.put(JSONArray().put(labels[i]).put(Cerebro.fmtLong(v))) }
        o.put("rows", rows)
        return o
    }

    private fun tuOut(): JSONObject {
        val r = range()
        val st = Memoria.stats(r)
        val lbl = if (r == 7) "esta semana" else if (r == 30) "este mes" else "desde que usas Órbita"
        val blocks = JSONArray()
        if (st.n == 0) {
            blocks.put(JSONObject().put("e", "empty").put("h", "Aún no hay nada $lbl")
                .put("t", "Cada canción que escuchas entera o a medias queda aquí: cuánto tiempo, a qué hora y qué te saltas. Con eso ${Nova.name()} prepara tu mezcla del día.")
                .put("btn", btn("Ir a Ahora") { Nativo.go("inicio") }))
            return JSONObject().put("blocks", blocks)
        }
        recapCard()?.let { blocks.put(it) }
        val streak = Memoria.streakDays()
        val stats = JSONArray()
            .put(JSONObject().put("b", "${st.plays}").put("s", "canciones escuchadas"))
            .put(JSONObject().put("b", "${st.artists.size}").put("s", if (st.artists.size == 1) "artista" else "artistas"))
            .put(JSONObject().put("b", "${st.days}").put("s", if (st.days == 1) "día con música" else "días con música"))
        if (streak > 1) stats.put(JSONObject().put("b", "$streak").put("s", "días seguidos"))
        blocks.put(JSONObject().put("e", "hero").put("eye", "Has escuchado $lbl").put("big", Cerebro.fmtLong(st.sec)).put("stats", stats))
        val now = Player.current()?.id
        val topT = st.tracks.take(5).mapIndexed { i, x ->
            val t = Lib.byId(x.id)
            row(x.title, x.artist, cover(t), "${i + 1}", if (x.plays > 0) "${x.plays} ${if (x.plays == 1) "vez" else "veces"}" else Cerebro.fmtLong(x.sec), pad = true, play = t != null && t.id == now,
                btn = if (t != null) rowBtn("Escuchar ${x.title}") { playRadioFrom(x.id) } else null)
        }
        val amax = st.artists.firstOrNull()?.sec ?: 1.0
        val topA = st.artists.take(5).mapIndexed { i, a ->
            JSONObject().put("e", "art").put("n", "${i + 1}").put("t", a.name).put("w", Math.round(max(3.0, a.sec / amax * 100) * 10) / 10.0).put("v", Cerebro.fmtLong(a.sec))
        }
        val grid = JSONArray()
            .put(panel(listOf(h2("Tus canciones"), if (topT.isNotEmpty()) list(topT) else note("Aún no hay canciones aquí."))))
            .put(panel(listOf(h2("Tus artistas"), if (topA.isNotEmpty()) list(topA) else note("Sin artistas todavía."))))
        blocks.put(JSONObject().put("e", "grid").put("items", grid))
        val ph = Cerebro.argmax(st.hours); val pd = Cerebro.argmax(st.dows)
        blocks.put(panel(listOf(JSONObject().put("e", "head").put("t", "Cuándo escuchas").put("r", "Más ${Cerebro.franjaOf(ph).phrase}, sobre las $ph:00").put("tog", JSONObject.NULL),
            chart("h", st.hours, (0 until 24).map { "$it:00" }, tickEvery = 6, name = "Hora"))))
        val order = listOf(1, 2, 3, 4, 5, 6, 0)
        blocks.put(panel(listOf(JSONObject().put("e", "head").put("t", "Tu semana").put("r", "Tu día: ${Momento.DAYS[pd]}").put("tog", JSONObject.NULL),
            chart("d", order.map { st.dows[it] }.toDoubleArray(), listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"), bw = 16.0, gap = 22.0, maxW = 380.0, name = "Día"))))
        val sk = st.all.filter { it.skips >= 2 }.sortedByDescending { it.skips }.take(4)
        if (sk.isNotEmpty()) blocks.put(panel(listOf(h2("Las que te saltas"),
            note("${Nova.name()} las pone menos en tus mezclas y en la radio. Si te vuelven a gustar, márcalas como favoritas."),
            list(sk.map { x -> row(x.title, x.artist, cover(Lib.byId(x.id)), null, "${x.skips} ${if (x.skips == 1) "salto" else "saltos"}", pad = true) }))))
        return JSONObject().put("blocks", blocks)
    }

    /** «tu-play»: esa canción y, después, la radio. */
    private fun playRadioFrom(id: String) {
        if (Lib.byId(id) == null) return
        Player.playQueue(listOf(id) + Cerebro.radioBatch(listOf(id)), 0, "radio")
        Radio.set(true, true)
    }

    private fun recapCard(): JSONObject? {
        val y = Recap.year(); val s = Recap.stats(y); if (!s.ok) return null
        val prev = Recap.stats(y - 1)
        val btns = JSONArray().put(btn("Ver tu año", primary = true) { Recap.open(y) })
        if (prev.ok) btns.put(btn("Ver ${y - 1}", small = true) { Recap.open(y - 1) })
        return JSONObject().put("e", "recap").put("eye", "Resumen del año").put("h", "Tu $y en Órbita")
            .put("t", "${Recap.hoursTxt(s.sec)} de música, tu canción y tu artista del año, tus récords… contado por ${Nova.name()}.").put("btns", btns)
    }

    private fun tuMem(): JSONObject {
        val name = Nova.name()
        val items = ArrayList<JSONObject>()
        val memOn = Memoria.on()
        items.add(JSONObject().put("e", "head").put("t", "Lo que $name sabe de ti").put("r", "")
            .put("tog", JSONObject().put("t", "Recordar lo que escucho").put("on", if (memOn) 1 else 0).put("k", k { toggleMem() })))
        val facts = Cerebro.memFacts()
        val fa = JSONArray()
        if (facts.isNotEmpty()) for (f in facts) fa.put(JSONObject().put("t", f).put("note", 0))
        else fa.put(JSONObject().put("t", "Todavía no sé nada de ti. Escucha unas cuantas canciones y empezaré a conocer tus gustos.").put("note", 1))
        items.add(JSONObject().put("e", "facts").put("items", fa))
        // lo que sabe de cómo suenan tus canciones, y las que no quieres oír más
        val (an, tot) = Cerebro.analysedShare()
        val brain = ArrayList<JSONObject>()
        if (tot > 0) brain.add(note(if (an >= tot) "Conozco cómo suenan tus $tot canciones (ritmo, energía y compases)." else "Conozco cómo suenan $an de tus $tot canciones. Las demás las analizo poco a poco mientras usas la app, y más rápido si el móvil está cargando."))
        val bans = Cerebro.ban.toList().mapNotNull { Lib.byId(it) }
        if (bans.isNotEmpty()) {
            brain.add(eye("No me las pongas más"))
            brain.add(list(bans.map { t -> row(t.title, t.artist, cover(t), btn = rowBtn("", "Volver a ponerla", small = true) { Cerebro.unban(t.id); mark("tu"); Nativo.toast("Vuelve a estar en la radio y las mezclas") }) }))
        }
        items.add(JSONObject().put("e", "stack").put("items", arr(brain)))
        // lo que le has pedido, tus rutinas y sus recuerdos
        val prefs = ArrayList<JSONObject>()
        val ps = Cerebro.prefList(); val rs = Recuerdos.routines(); val mm = Recuerdos.memories()
        if (ps.isNotEmpty()) {
            prefs.add(eye("Lo que me has pedido"))
            prefs.add(list(ps.map { p ->
                val pid = Json.s(p["id"]); val said = Json.s(p["said"])
                row(Recuerdos.describePref(p), if (said.isNotEmpty()) "«$said»" else "", null, btn = rowBtn("Olvidar", x = true) { Cerebro.savePrefs(Cerebro.prefList().filter { Json.s(it["id"]) != pid }); mark("tu"); Nativo.toast("Olvidado") })
            }))
        }
        if (rs.isNotEmpty()) {
            prefs.add(eye("Tus rutinas al abrir Órbita"))
            prefs.add(list(rs.map { r ->
                val rid = Json.s(r["id"])
                row("${Cerebro.cap(Recuerdos.whenText(Json.obj(r["when"])))}: ${Json.s(r["request"])}", "", null, btn = rowBtn("Borrar rutina", x = true) { Recuerdos.saveRoutines(Recuerdos.routines().filter { Json.s(it["id"]) != rid }); Nativo.toast("Rutina borrada") })
            }))
        }
        if (mm.isNotEmpty()) {
            prefs.add(eye("Recuerdos"))
            val ma = JSONArray(); for (m in mm) ma.put(JSONObject().put("t", m.text).put("note", 0))
            prefs.add(JSONObject().put("e", "facts").put("items", ma))
        }
        prefs.add(JSONObject().put("e", "note").put("t", "Enséñale a $name: «no me pongas reguetón por la mañana», «me encanta el rap», «cada noche ponme algo tranquilo», «tal artista es trap».")
            .put("link", JSONObject().put("t", "Abrir la conversación").put("k", k { Nativo.go("chat") })))
        items.add(JSONObject().put("e", "stack").put("items", arr(prefs)))
        items.add(note("Se guarda solo en este dispositivo: qué canciones escuchas, cuánto rato, a qué hora y cuáles te saltas. Con eso se hacen tu mezcla del día, la radio y las presentaciones."))
        val fk = "memForget"
        items.add(JSONObject().put("e", "btns").put("b", JSONArray().put(btn(if (isArmed(fk)) "¿Borrar?" else "Borrar mi historial", small = true, armedOn = isArmed(fk)) { armOrRun(fk) { forget() } })))
        return panel(items, glow = true)
    }

    private fun toggleMem() {
        val on = !Memoria.on(); Prefs.set("memOn", on)
        val n = Nova.name()
        Nativo.toast(if (on) "$n vuelve a recordar lo que escuchas" else "$n deja de apuntar lo que escuchas. Lo que ya sabe se queda hasta que lo borres.", 3800)
    }

    /** «Borrar mi historial»: las escuchas y lo que cada canción sabía de ellas; las favoritas y las listas, igual. */
    private fun forget() {
        Memoria.forget()
        Prefs.set("daily", null)
        mark("tu"); Inicio.mark()
        Nativo.toast("Historial borrado. Tus favoritas y tus listas siguen igual.", 3600)
    }

    private fun tuLinks(): JSONObject = JSONObject().put("b", JSONArray()
        .put(btn("Tu voz: graba tus frases de DJ", small = true) { Nativo.go("voz") })
        .put(btn("Ajustes", small = true) { Nativo.go("ajustes") }))
}
