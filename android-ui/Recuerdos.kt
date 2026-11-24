package __PACKAGE__

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Fase 4, parte 2 · Lo que Nova recuerda de ti (lo que en la web era «3.0 · Nova remembers»): lo que le pides para siempre
 * («no me pongas reguetón por la mañana», «me encanta el rap»), los estilos que le enseñas, tus rutinas al abrir la app,
 * sus recuerdos («hace un año no parabas de escuchar…») y el resumen de tu semana.
 */
object Recuerdos {
    private val main = Handler(Looper.getMainLooper())
    private const val DAY = 864e5

    // ---------------------------------------------------------------- cuándo vale una regla
    private val WHEN_RE: List<Pair<Regex, Map<String, Any?>>> = listOf(
        Regex("(por|de|en|a) (la|las) mananas?|cada manana|todas las mananas|al despertar(me)?|de buena manana") to mapOf("franja" to "manana"),
        Regex("(al )?mediodia") to mapOf("franja" to "mediodia"),
        Regex("(por|de|en|a) (la|las) tardes?|cada tarde|todas las tardes") to mapOf("franja" to "tarde"),
        Regex("(por|de|en|a) (la|las) noches?|cada noche|todas las noches") to mapOf("franja" to "noche"),
        Regex("(de|por la|a la|en la) madrugada") to mapOf("franja" to "madrugada"),
        Regex("(el|los) fin(es)? de semana|el finde|los findes") to mapOf("dows" to listOf(6, 0)),
        Regex("entre semana|los dias de diario|de lunes a viernes") to mapOf("dows" to listOf(1, 2, 3, 4, 5))
    ) + listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado").mapIndexed { i, d -> Regex("(el|los|cada|todos los) ${d}s?") to mapOf<String, Any?>("dows" to listOf(i)) }

    class When(val w: Map<String, Any?>?, val rest: String)

    fun parseWhen(q: String): When {
        val w = LinkedHashMap<String, Any?>(); var rest = q
        for ((re, v) in WHEN_RE) {
            if (re.containsMatchIn(rest)) {
                v["franja"]?.let { w["franja"] = it }
                v["dows"]?.let { w["dows"] = it }
                rest = re.replaceFirst(rest, " ")
            }
        }
        return When(if (w.isNotEmpty()) w else null, rest.replace(Regex("\\s+"), " ").trim())
    }

    private val FRANJA_TXT = mapOf("manana" to "por las mañanas", "mediodia" to "a mediodía", "tarde" to "por las tardes", "noche" to "por las noches", "madrugada" to "de madrugada")
    private fun dows(w: Map<String, Any?>?): List<Int>? = Json.list(w?.get("dows"))?.map { Json.l(it).toInt() }

    fun whenText(w: Map<String, Any?>?): String {
        if (w == null) return ""
        val ds = dows(w)
        val d = if (ds != null) (if (ds.size == 2 && 0 in ds && 6 in ds) "el fin de semana" else if (ds.size == 5) "entre semana" else "los " + ds.joinToString(" y ") { i -> Momento.DAYS[i] + (if (Momento.DAYS[i].endsWith("s")) "" else "s") }) else ""
        val fr = Json.s(w["franja"])
        val f = if (fr.isNotEmpty()) (FRANJA_TXT[fr] ?: "").let { if (d.isNotEmpty()) it.replaceFirst(Regex("^por las "), "por la ").replace(Regex("s$"), "") else it } else ""
        return listOf(d, f).filter { it.isNotEmpty() }.joinToString(" ")
    }
    /** (dos reglas para el mismo momento) */
    private fun sameWhen(a: Map<String, Any?>?, b: Map<String, Any?>?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return Json.s(a["franja"]) == Json.s(b["franja"]) && dows(a) == dows(b)
    }

    fun lc1(x: String): String = if (x.isEmpty()) x else x.substring(0, 1).lowercase() + x.substring(1)

    /** Una preferencia, dicha para leerla («No poner reguetón por las mañanas»). */
    fun describePref(p: Map<String, Any?>): String {
        val wm = Json.obj(p["when"])
        val w = if (wm != null) " " + whenText(wm) else ""
        if (Json.s(p["kind"]) == "energy") return "${if (w.isNotEmpty()) Cerebro.cap(whenText(wm)) + ", " else ""}música ${if (Json.d(p["energy"]) < 0.5) "tranquila" else "con energía"}"
        val n = Json.s(Json.obj(p["target"])?.get("name"))
        return if (Json.s(p["kind"]) == "avoid") "No poner $n$w" else "Más $n$w"
    }

    /** Un artista, un estilo o una canción tuya, por cómo la nombras. */
    fun resolveTarget(x: String): Map<String, Any?>? {
        val q = Biblio.norm(x).replace(Regex("^(a |al |el |la |los |las |de |del |canciones de |musica de |cosas de |temas de )+"), "")
            .replace(Regex(" (espanol|espanola|latino|latina|de ahora|antiguo|viejo|clasico)$"), "").trim()
        if (q.isEmpty()) return null
        Cerebro.genreOf(q)?.let { return linkedMapOf("type" to "genre", "value" to it, "name" to it) }
        val pool = Biblio.visible().filter { !it.net }
        val a = pool.firstOrNull { Biblio.artistKey(it.artist) == q } ?: (if (q.length >= 4) pool.firstOrNull { Biblio.artistKey(it.artist).let { k -> k.isNotEmpty() && k.contains(q) } } else null)
        if (a != null) return linkedMapOf("type" to "artist", "value" to Biblio.artistKey(a.artist), "name" to a.artist)
        val tr = pool.firstOrNull { Biblio.norm(it.title) == q } ?: (if (q.length >= 5) pool.firstOrNull { Biblio.norm(it.title).contains(q) } else null)
        if (tr != null) return linkedMapOf("type" to "track", "value" to tr.id, "name" to "«${tr.title}»")
        return null
    }

    private fun energyWord(q: String): Double? =
        if (Regex("(tranquil|suave|calm|relaj|lent|chill|dormir)").containsMatchIn(q)) 0.22
        else if (Regex("(movid|animad|marcha|energ|fiesta|ritmo|cana|potente|perreo)").containsMatchIn(q)) 0.82 else null

    fun routines(): List<Map<String, Any?>> = Json.list(Prefs.get("routines"))?.mapNotNull { Json.obj(it) } ?: emptyList()
    fun saveRoutines(l: List<Map<String, Any?>>) { Prefs.set("routines", l); Paginas.mark("tu") }
    private fun savePrefs(l: List<Map<String, Any?>>) { Cerebro.savePrefs(l); Paginas.mark("tu") }
    private fun say(s: String) = Ordenes.say(s)
    private fun ahead(keep: (Track) -> Boolean) {
        val q = Player.queue; val qi = Player.qi; if (qi < 0) return
        Player.replaceAhead(q.drop(qi + 1).filter { id -> val t = Lib.byId(id); t == null || keep(t) })
    }

    // ---------------------------------------------------------------- «memoryCommand»
    /** Lo que se le dice para siempre. Devuelve si era una de esas cosas. */
    fun command(q: String, raw: String): Boolean {
        fun m(re: String) = Regex(re).find(q)
        if (Regex("^(que (recuerdas|sabes) de mi|que te he (dicho|pedido)|que recuerdas)$").containsMatchIn(q)) { say(recallText()); return true }
        if (Regex("^(olvida(lo)? todo|borra todo lo que te (he )?dich[oa]|olvida todo lo que te (he )?dich[oa])$").containsMatchIn(q)) {
            savePrefs(emptyList()); saveRoutines(emptyList())
            say("Hecho: he olvidado todas tus preferencias y rutinas. Lo que escuchas lo sigo aprendiendo."); return true
        }
        val fg = m("^(olvida|borra|elimina) (lo de |la regla de |la rutina de |que )?(.+)$")
        if (fg != null && (Cerebro.prefList().isNotEmpty() || routines().isNotEmpty())) {
            val k = fg.groupValues[3]
            val p = Cerebro.prefList().firstOrNull { x ->
                val tn = Biblio.norm(Json.s(Json.obj(x["target"])?.get("name")))
                Biblio.norm(describePref(x)).contains(k) || (Json.obj(x["target"]) != null && (tn.contains(k) || k.contains(tn))) || Biblio.norm(Json.s(x["said"])).contains(k)
            }
            if (p != null) { savePrefs(Cerebro.prefList().filter { it !== p }); say("Olvidado: ${lc1(describePref(p))}."); return true }
            val rs = routines(); val r = rs.firstOrNull { x -> Biblio.norm(Json.s(x["said"])).contains(k) || Biblio.norm(Json.s(x["request"])).contains(k) }
            if (r != null) { saveRoutines(rs.filter { it !== r }); say("Rutina borrada."); return true }
        }
        // «X es reguetón» / «X hace trap»
        val tg = m("^(.+?) (es|hace|canta|toca) (de )?(.+)$")
        if (tg != null && Cerebro.genreOf(tg.groupValues[4]) != null && !Regex("^(esta|esto|eso|esa)$").containsMatchIn(tg.groupValues[1])) {
            val tr = resolveTarget(tg.groupValues[1])
            if (tr != null && tr["type"] == "artist") {
                val all = LinkedHashMap(Cerebro.taughtGenres()); val g = Cerebro.genreOf(tg.groupValues[4])!!; val v = Json.s(tr["value"])
                all[v] = (LinkedHashSet((Json.list(all[v]) ?: emptyList()).map { Json.s(it) }) + g).toList()
                Prefs.set("artistGenres", all); Cerebro.genresChanged()
                say("Apuntado: ${Json.s(tr["name"])} es $g."); return true
            }
        }
        // las rutinas: «cada mañana ponme algo suave», «los viernes por la noche, al abrir, pon modo fiesta»
        val isRoutine = Regex("(^| )(cada|todos los|todas las|siempre que abra|cuando abra|al abrir|nada mas abrir)( |$)").containsMatchIn(q) &&
            Regex("(pon(me|nos)?|despiertame con|empieza con|arranca con|quiero (oir|escuchar)) ").containsMatchIn(q)
        val pw = parseWhen(q); val wh = pw.w; val rest = pw.rest
        if (isRoutine && wh != null) {
            val act = Regex("^(cada|todos los|todas las) ").replaceFirst(
                Regex("(siempre que|cuando) abra (la app|orbita)|al abrir( la app| orbita)?|nada mas abrir").replace(
                    Regex("^.*?(pon(me|nos)?|despiertame con|empieza con|arranca con|quiero (oir|escuchar)) ").replaceFirst(rest, ""), ""), "").trim()
            if (act.length >= 3) {
                val rs = routines().filter { !sameWhen(Json.obj(it["when"]), wh) }.toMutableList()
                rs.add(linkedMapOf("id" to "r" + System.currentTimeMillis(), "when" to wh, "request" to act, "said" to raw, "last" to ""))
                saveRoutines(rs)
                say("Hecho: ${whenText(wh)}, cuando abras Órbita, te pondré $act. Puedes borrarlo en «Tú»."); return true
            }
        }
        // evitar / más / energía, con hora o sin ella
        val av = Regex("^(no me pongas|nunca (me )?pongas|no quiero (oir |escuchar )?|evita|odio|no soporto|no me gusta(n)?|no me apetece) (mas )?(.+)$").find(rest)
        val bo = Regex("^(me (encanta|encantan|gusta mucho|gustan mucho|flipa|flipan)|prefiero|pon(me)? mas|quiero mas|mas|me apetece mas) (.+)$").find(rest)
        val forever = Regex("(siempre|a partir de ahora)").containsMatchIn(q)
        val en = if (wh != null || forever) Regex("^(pon(me)?|quiero|me gusta|prefiero|me apetece) (cosas |algo |musica |canciones )?(.+)$").find(rest) else null
        val enX = en?.groupValues?.last()
        if (en != null && enX != null && energyWord(enX) != null && resolveTarget(enX) == null) {
            val p = linkedMapOf<String, Any?>("id" to "p" + System.currentTimeMillis(), "kind" to "energy", "energy" to energyWord(enX), "when" to wh, "said" to raw)
            savePrefs(Cerebro.prefList().filter { x -> !(Json.s(x["kind"]) == "energy" && sameWhen(Json.obj(x["when"]), wh)) } + p)
            say("Apuntado: ${lc1(describePref(p))}."); return true
        }
        val mm = av ?: bo
        if (mm != null) {
            val x = mm.groupValues.last()
            if (Regex("^(esta|esto|eso|esa)( cancion)?$").containsMatchIn(x)) return false
            val tr = resolveTarget(x)
            if (tr == null) {
                if (wh == null && !Regex("(siempre|nunca|a partir de ahora)").containsMatchIn(q)) return false
                say("No sé qué es «$x» en tu música. Prueba con el nombre de un artista o un estilo, como reguetón, rap o rock."); return true
            }
            val probe = mapOf<String, Any?>("target" to tr)
            // «pon más rap» es para ahora; «me encanta el rap», o con una hora o «siempre», es para siempre
            val lasting = av != null || Regex("^(me (encanta|gusta mucho|gustan mucho|flipa)|prefiero)").containsMatchIn(rest) || wh != null || forever
            if (!lasting) {
                val cur = Player.current() ?: return false
                val ahead = Player.queue.drop(maxOf(0, Player.qi))
                val ids = Cerebro.rank(Cerebro.pool(free = true).filter { Cerebro.prefMatches(probe, it) }, cur, explore = 0.3).map { it.t.id }.filter { it !in ahead }.take(4)
                if (ids.isEmpty()) {
                    val up = Player.queue.drop(Player.qi + 1)
                    val mine = up.filter { id -> Lib.byId(id)?.let { Cerebro.prefMatches(probe, it) } == true }
                    if (mine.isEmpty()) { say("No tengo más ${Json.s(tr["name"])} para ponerte ahora."); return true }
                    Player.replaceAhead(mine + up.filter { it !in mine })
                    say("Adelanto ${if (mine.size == 1) "la canción" else "las ${mine.size} canciones"} de ${Json.s(tr["name"])} que ${if (mine.size == 1) "venía" else "venían"}."); return true
                }
                Player.replaceAhead(ids + Player.queue.drop(Player.qi + 1))
                say("Marchando más ${Json.s(tr["name"])}: ${if (ids.size == 1) "una canción" else "${ids.size} canciones"} a continuación."); return true
            }
            val p = linkedMapOf<String, Any?>("id" to "p" + System.currentTimeMillis(), "kind" to (if (av != null) "avoid" else "boost"), "target" to tr, "when" to wh, "said" to raw)
            savePrefs(Cerebro.prefList().filter { y -> val yt = Json.obj(y["target"]); !(yt != null && Json.s(yt["type"]) == Json.s(tr["type"]) && Json.s(yt["value"]) == Json.s(tr["value"]) && sameWhen(Json.obj(y["when"]), wh)) } + p)
            if (av != null) {
                val now = Cerebro.whenActive(wh)
                val cur = Player.current()
                if (cur != null && Cerebro.prefMatches(p, cur) && now && Player.source != "playlist") ahead { !Cerebro.prefMatches(p, it) }
                else ahead { !(now && Cerebro.prefMatches(p, it)) }
            }
            val unknownStyle = tr["type"] == "genre" && Cerebro.pool(free = true).none { Json.s(tr["value"]) in Cerebro.trackGenres(it) }
            say("Apuntado: ${lc1(describePref(p))}.${if (unknownStyle) " Aún no sé qué canciones tuyas son ${Json.s(tr["value"])}: lo voy aprendiendo con internet, y también puedes decirme «tal artista es ${Json.s(tr["value"])}»." else ""}")
            return true
        }
        return false
    }

    fun recallText(): String {
        val ps = Cerebro.prefList(); val rs = routines(); val st = Memoria.stats(30); val out = ArrayList<String>()
        if (ps.isNotEmpty()) out.add("Me has pedido: " + Cerebro.listES(ps.take(5).map { lc1(describePref(it)) }) + ".")
        if (rs.isNotEmpty()) out.add("Rutinas: " + Cerebro.listES(rs.take(3).map { "${whenText(Json.obj(it["when"]))}, ${Json.s(it["request"])}" }) + ".")
        st.artists.firstOrNull()?.let { out.add("Este mes lo que más escuchas es ${it.name}.") }
        memories().firstOrNull()?.let { out.add(it.text) }
        return if (out.isNotEmpty()) out.joinToString(" ") else "Aún sé poco de ti. Dime cosas como «no me pongas reguetón por la mañana» o «me encanta el rap», y lo recordaré."
    }

    // ---------------------------------------------------------------- los recuerdos
    class Mem(val kind: String, val id: String?, val text: String)

    fun memories(): List<Mem> {
        val out = ArrayList<Mem>(); val now = System.currentTimeMillis()
        val ev = synchronized(Memoria) { Memoria.events.toList() }
        val yr = ev.filter { !it.net && !it.skip && Math.abs((now - it.ts) / DAY - 365) <= 10 }
        if (yr.size >= 3) {
            val m = LinkedHashMap<String, Int>(); for (e in yr) m[e.id] = (m[e.id] ?: 0) + 1
            val top = m.entries.sortedByDescending { it.value }.first(); val t = Lib.byId(top.key)
            if (t != null && top.value >= 2 && (t.lastPlayed <= 0 || now - t.lastPlayed > 60 * DAY))
                out.add(Mem("year", top.key, "Hace un año no parabas de escuchar «${t.title}»${if (Biblio.artistKey(t.artist).isNotEmpty()) " de " + t.artist else ""}."))
        }
        val fav = Memoria.stats(0).artists.firstOrNull()
        if (fav != null && fav.plays >= 8) {
            var last = 0L
            for (i in ev.indices.reversed()) { val e = ev[i]; if (!e.net && Biblio.artistKey(e.artist) == fav.key) { last = e.ts; break } }
            if (last > 0 && now - last > 30 * DAY) out.add(Mem("artist", null, "Hace ${Math.round((now - last) / DAY)} días que no pones a ${fav.name}, tu artista de siempre."))
        }
        val forgot = Lib.tracks.filter { !it.demo && !it.net && Biblio.liked(it) && it.lastPlayed > 0 && now - it.lastPlayed > 45 * DAY }.minByOrNull { it.lastPlayed }
        if (forgot != null) out.add(Mem("fav", forgot.id, "Tienes olvidada una favorita: «${forgot.title}». Hace ${Math.round((now - forgot.lastPlayed) / DAY)} días que no suena."))
        val first = ev.firstOrNull { !it.net }
        if (first != null && now - first.ts > 30 * DAY) { val t = Lib.byId(first.id); if (t != null) out.add(Mem("first", t.id, "La primera canción que escuchaste conmigo fue «${t.title}», el ${Recap.fmtDia(first.ts)}.")) }
        return out
    }

    /** «weekSummary»: tu semana en música ("" si aún es poco). */
    fun weekSummary(): String {
        val st = Memoria.stats(7); if (st.n == 0 || st.sec < 600) return ""
        val now = System.currentTimeMillis(); val seen = HashSet<String>(); val news = HashSet<String>()
        synchronized(Memoria) { for (e in Memoria.events) { if (e.net) continue; if (now - e.ts > 7 * DAY) seen.add(e.id) else if (e.id !in seen) news.add(e.id) } }
        return "Tu semana en música: ${Cerebro.fmtLong(st.sec)}." + (st.artists.firstOrNull()?.let { " Tu artista fue ${it.name}" } ?: "") +
            (st.tracks.firstOrNull()?.let { " y la canción que más pusiste, «${it.title}»" } ?: "") + "." +
            (if (news.isNotEmpty()) " Descubriste ${news.size} ${if (news.size == 1) "canción nueva" else "canciones nuevas"}." else "")
    }

    // ---------------------------------------------------------------- al abrir la app
    /** (la fecha de hoy como la guardaba la web: la del meridiano de Greenwich) */
    private fun todayUtc(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    /** Una rutina para este momento, si la hay (y no suena nada). */
    fun openRoutine(): Boolean {
        if (Player.current() != null && Player.playing) return false
        val today = todayUtc(); val rs = routines().map { LinkedHashMap(it) }
        val r = rs.firstOrNull { Json.s(it["last"]) != today && Cerebro.whenActive(Json.obj(it["when"])) } ?: return false
        r["last"] = today; Prefs.set("routines", rs)
        val wt = whenText(Json.obj(r["when"])); val req = Json.s(r["request"])
        Ordenes.chatAdd("nova", "Rutina: $wt, $req.")
        if (Regex("^(la |el )?(modo )?radio$").containsMatchIn(Biblio.norm(req))) { if (!Radio.on) Nova.radioButton() }
        else Mezclas.create(request = req, autoplay = true)
        Nativo.toast("Rutina $wt: $req. Puedes borrarla en «Tú».", 4200)
        return true
    }

    /** «openGreeting»: al abrir, una rutina; si no, un recuerdo o el resumen de la semana (escrito, no dicho). */
    fun openGreeting() {
        if (openRoutine()) return
        val today = todayUtc(); if (Prefs.str("memoSaid", "") == today) return
        var line: String? = null
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY && Prefs.str("weekSaid", "") != today) { line = weekSummary().ifEmpty { null }; if (line != null) Prefs.set("weekSaid", today) }
        if (line == null) { val m = memories(); line = if (m.isNotEmpty()) m[kotlin.random.Random.nextInt(m.size)].text else null }
        val l = line ?: return
        Prefs.set("memoSaid", today)
        main.postDelayed({ if (Player.current() == null || !Player.playing) Nova.setBubble(l, Nova.name()); Ordenes.chatAdd("nova", l) }, 2500)
    }
}
