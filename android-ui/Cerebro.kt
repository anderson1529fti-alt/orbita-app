package __PACKAGE__

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Fase 4, parte 2 · El cerebro pequeño de Nova (lo de «Nova's brain», «Nova remembers», «Todo mi Órbita» y la mezcla
 * del día de la parte web): aprende de lo que escuchas y saltas, entiende «no me pongas…», ordena, recomienda y
 * prepara mezclas. Todo en el móvil, con los mismos números que la web.
 */
object Cerebro {
    // ---------------------------------------------------------------- lo que recuerda
    val ban: MutableSet<String> = LinkedHashSet()
    private var w: DoubleArray? = null
    var n = 0; private set
    private var at = 0
    var target: Double? = null
    val said = ArrayList<String>()
    var saidHour = false
    private var loaded = false

    fun load() {
        ban.clear(); ban.addAll(Json.list(Prefs.get("banned"))?.map { Json.s(it) } ?: emptyList())
        w = Json.list(Prefs.get("brainW"))?.map { Json.d(it) }?.toDoubleArray()
        n = Prefs.num("brainN", 0.0).toInt(); at = Prefs.num("brainAt", 0.0).toInt()
        loaded = true
    }

    // ---------------------------------------------------------------- utilidades (las de la web)
    fun pick(l: List<String>): String = l[Random.nextInt(l.size)]
    fun <T> shuffle(l: List<T>): List<T> = l.shuffled()
    /** «seeded»: un número de 0 a 1 que sale siempre igual para el mismo texto (FNV-1a de 32 bits). */
    fun seeded(s: String): Double {
        var h = 2166136261L.toInt()
        for (ch in s) { h = h xor ch.code; h *= 16777619 }
        return (h.toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
    fun cap(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
    fun argmax(a: DoubleArray): Int { var b = 0; for (i in a.indices) if (a[i] > a[b]) b = i; return b }
    fun listES(a: List<String>): String = if (a.size < 2) (a.firstOrNull() ?: "") else a.dropLast(1).joinToString(", ") + " y " + a.last()
    fun fmtLong(s0: Double): String {
        val s = Math.round(s0).toLong(); if (s < 60) return "$s s"
        val h = s / 3600; val m = Math.round((s % 3600) / 60.0)
        return if (h > 0) "$h h" + (if (m > 0) " $m min" else "") else "$m min"
    }
    fun fmt(s: Double): String { if (!s.isFinite() || s <= 0) return "0:00"; val r = Math.round(s); return "${r / 60}:${(r % 60).toString().padStart(2, '0')}" }
    fun franjaOf(h: Int): Momento.Franja = Momento.FRANJAS.firstOrNull { h >= it.from && h < it.to } ?: Momento.FRANJAS[0]
    fun nameOf(t: Track): String = if (t.demo) "${t.title}, una pista de prueba" else if (t.mine) "${t.title}, cantada por ti" else if (t.artist.isNotEmpty() && t.artist != "Artista desconocido") "${t.title}, de ${t.artist}" else t.title
    private fun hourNow(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    // ---------------------------------------------------------------- «no me pongas…» y los estilos
    val GENRES: List<Pair<String, Regex>> = listOf(
        "reguetón" to Regex("reguet|reggaet|dembow|perreo"), "trap" to Regex("\\btrap\\b"), "rap" to Regex("\\brap\\b|hip hop|rapero|rapera|\\bmc\\b"),
        "pop" to Regex("\\bpop\\b"), "rock" to Regex("\\brock\\b"), "indie" to Regex("\\bindie\\b"), "electrónica" to Regex("electronic|techno|\\bhouse\\b|\\bedm\\b|\\bdance\\b"),
        "flamenco" to Regex("flamenc|rumba"), "salsa" to Regex("\\bsalsa\\b"), "bachata" to Regex("bachata"), "cumbia" to Regex("cumbia"), "corridos" to Regex("corrido|regional mexican"),
        "r&b" to Regex("\\br b\\b|rhythm and blues|\\bsoul\\b|\\brnb\\b"), "metal" to Regex("\\bmetal\\b"), "jazz" to Regex("\\bjazz\\b"), "clásica" to Regex("clasica|classical|orquesta|sinfoni"),
        "k-pop" to Regex("\\bk pop\\b|\\bkpop\\b"), "lo-fi" to Regex("\\blo fi\\b|\\blofi\\b"), "punk" to Regex("\\bpunk\\b"), "folk" to Regex("\\bfolk\\b|cantautor")
    )
    fun genreOf(q: String): String? { val n = " " + Biblio.norm(q) + " "; return GENRES.firstOrNull { it.second.containsMatchIn(n) }?.first }
    private var genreVer = 0
    private val genreMemo = HashMap<String, Set<String>>()
    fun genresChanged() { genreVer++; genreMemo.clear() }
    fun taughtGenres(): Map<String, Any?> = Json.obj(Prefs.get("artistGenres")) ?: emptyMap()
    /** Lo que se sabe del artista (de Wikipedia, guardado en el móvil). */
    fun cachedFact(name: String): Map<String, Any?>? { val k = Biblio.artistKey(name); if (k.isEmpty()) return null; val c = Json.obj(Json.obj(Prefs.get("facts"))?.get(k)); return Json.obj(c?.get("f")) }
    fun trackGenres(t: Track?): Set<String> {
        if (t == null) return emptySet()
        val key = t.id + "|" + genreVer
        genreMemo[key]?.let { return it }
        val out = LinkedHashSet<String>(); val ak = Biblio.artistKey(t.artist)
        if (ak.isNotEmpty()) for (g in Json.list(taughtGenres()[ak]) ?: emptyList()) out.add(Json.s(g))
        val f = if (!t.net) cachedFact(t.artist) else null
        val txt = " " + Biblio.norm(t.genre + " " + (if (f != null) Json.s(f["desc"]) + " " + Json.s(f["text"]).take(240) else "")) + " "
        for ((g, re) in GENRES) if (re.containsMatchIn(txt)) out.add(g)
        genreMemo[key] = out; return out
    }

    /** Cuándo vale una regla («por las mañanas», «el finde»…). */
    fun whenActive(w: Map<String, Any?>?, c: Calendar = Calendar.getInstance()): Boolean {
        if (w == null) return true
        val fr = Json.s(w["franja"]); if (fr.isNotEmpty() && Momento.moment(c).franja.id != fr) return false
        val d = Json.list(w["dows"])?.map { Json.l(it).toInt() }; if (d != null && (c.get(Calendar.DAY_OF_WEEK) - 1) !in d) return false
        return true
    }
    private var prefsMemo: List<Map<String, Any?>>? = null
    fun prefList(): List<Map<String, Any?>> = prefsMemo ?: (Json.list(Prefs.get("prefs"))?.mapNotNull { Json.obj(it) } ?: emptyList()).also { prefsMemo = it }
    fun savePrefs(l: List<Map<String, Any?>>) { prefsMemo = l; Prefs.set("prefs", l) }
    fun prefMatches(p: Map<String, Any?>, t: Track): Boolean {
        val tg = Json.obj(p["target"]) ?: return false; val v = Json.s(tg["value"]); if (v.isEmpty()) return false
        return when (Json.s(tg["type"])) { "artist" -> Biblio.artistKey(t.artist) == v; "track" -> t.id == v; "genre" -> v in trackGenres(t); else -> false }
    }
    fun prefBlocked(t: Track, c: Calendar = Calendar.getInstance()): Boolean = prefList().any { Json.s(it["kind"]) == "avoid" && whenActive(Json.obj(it["when"]), c) && prefMatches(it, t) }
    fun prefBoost(t: Track, c: Calendar = Calendar.getInstance()): Double = prefList().count { Json.s(it["kind"]) == "boost" && whenActive(Json.obj(it["when"]), c) && prefMatches(it, t) } * 0.9
    fun prefEnergy(c: Calendar = Calendar.getInstance()): Double? = prefList().firstOrNull { Json.s(it["kind"]) == "energy" && whenActive(Json.obj(it["when"]), c) }?.let { Json.d(it["energy"]) }

    // ---------------------------------------------------------------- el mapa de tu música
    /** «poolTracks»: las que pueden sonar (sin las vetadas, y sin las de «no me pongas…» ahora, si quedan bastantes). */
    fun pool(free: Boolean = false): List<Track> {
        val base = Biblio.visible().filter { !it.net && it.id !in ban }
        if (free) return base
        val c = Calendar.getInstance(); val out = base.filter { !prefBlocked(it, c) }
        return if (out.size >= min(3, base.size)) out else base
    }

    fun vec(t: Track?): DoubleArray? {
        val a = Analizador.get(t) ?: return null
        val raw = Analizador.rawOf(t) ?: return null
        if (raw["energy"] !is Number) return null
        return doubleArrayOf(a.energy, if (raw["bright"] is Number) a.bright else 0.5, min(1.0, max(0.0, ((if (a.bpm != 0.0) a.bpm else 110.0) - 70) / 100)), min(1.0, (if (a.rate != 0.0) a.rate else 3.0) / 8))
    }
    fun vdist(a: DoubleArray, b: DoubleArray): Double = Math.sqrt((a[0] - b[0]).let { it * it } + ((a[1] - b[1]) * 0.6).let { it * it } + (a[2] - b[2]).let { it * it } + ((a[3] - b[3]) * 0.5).let { it * it })

    private var transCache: HashMap<String, Double>? = null
    private var transLen = -1
    fun transitions(): Map<String, Double> {
        val ev = Memoria.events
        transCache?.let { if (transLen == ev.size) return it }
        val m = HashMap<String, Double>()
        synchronized(Memoria) {
            for (i in 1 until ev.size) {
                val a = ev[i - 1]; val b = ev[i]
                if (a.net || b.net || a.id == b.id || b.ts - a.ts > 25 * 60000) continue
                val good = !b.skip && (b.pct >= 0.6 || b.sec >= 90); val bad = b.skip
                if (!good && !bad) continue
                val k = a.id + ">" + b.id; m[k] = (m[k] ?: 0.0) + (if (good) 1.0 else -1.2)
            }
            transLen = ev.size
        }
        transCache = m; return m
    }
    fun transScore(a: String?, b: String?): Double = if (a != null && b != null) tanh((transitions()[a + ">" + b] ?: 0.0) / 2) else 0.0

    // ---------------------------------------------------------------- qué canciones se saltan (regresión logística)
    private val W0 = doubleArrayOf(-1.3, -0.9, -0.5, 2.4, -0.9, -0.9, 1.6, -1.6, 0.5, 0.25, 0.15)
    class Ctx(val now: Long, val hour: Int, val prev: String?, val prevArtist: String, val vec: DoubleArray?, val aff: Map<String, Double>)
    fun ctx(prev: Track?): Ctx {
        val st = Memoria.stats(30); val amax = st.artists.firstOrNull()?.sec?.takeIf { it != 0.0 } ?: 1.0
        return Ctx(System.currentTimeMillis(), hourNow(), prev?.id, if (prev != null) Biblio.artistKey(prev.artist) else "", vec(prev), st.artists.take(40).associate { it.key to it.sec / amax })
    }
    fun feat(t: Track, c: Ctx, hour: Int = c.hour): DoubleArray {
        val v = vec(t); val cv = c.vec; val p = Biblio.plays(t).toDouble(); val sk = t.skips.toDouble()
        var hm = 0.0
        val hs = t.hours
        if (hs != null && hs.size == 24) { val tot = hs.sum(); if (tot >= 2) hm = (hs[hour] + 0.5 * (hs[(hour + 23) % 24] + hs[(hour + 1) % 24])) / tot }
        val ak = Biblio.artistKey(t.artist)
        return doubleArrayOf(1.0, if (Biblio.liked(t)) 1.0 else 0.0, ln(1 + p) / 3, sk / (p + sk + 1), hm, c.aff[ak] ?: 0.0, if (v != null && cv != null) vdist(v, cv) else 0.3,
            transScore(c.prev, t.id), if (t.lastPlayed > 0 && c.now - t.lastPlayed < 864e5) 1.0 else 0.0, if (p <= 1) 1.0 else 0.0, if (c.prevArtist.isNotEmpty() && ak == c.prevArtist) 1.0 else 0.0)
    }
    private fun weights(): DoubleArray = w?.takeIf { it.size == W0.size } ?: W0
    private fun sigmoid(z: Double) = 1 / (1 + exp(-z))
    private fun dot(a: DoubleArray, x: DoubleArray): Double { var s = 0.0; for (i in x.indices) s += a.getOrElse(i) { 0.0 } * x[i]; return s }
    fun pSkip(t: Track, c: Ctx): Double = sigmoid(dot(weights(), feat(t, c)))

    fun train(): Boolean {
        val ev = synchronized(Memoria) { ArrayList(Memoria.events) }
        class Row(val e: Memoria.Ev, val t: Track, val prev: Track?, val y: Double)
        val rows = ArrayList<Row>()
        for (i in max(1, ev.size - 3000) until ev.size) {
            val e = ev[i]; if (e.net) continue
            val y = if (e.skip) 1.0 else if (e.pct >= 0.6 || e.sec >= 120) 0.0 else continue
            val t = Lib.byId(e.id) ?: continue
            val pe = ev.getOrNull(i - 1); val prev = if (pe != null && !pe.net && e.ts - pe.ts < 25 * 60000) Lib.byId(pe.id) else null
            rows.add(Row(e, t, prev, y))
        }
        if (rows.size < 25) return false
        val st = Memoria.stats(30); val amax = st.artists.firstOrNull()?.sec?.takeIf { it != 0.0 } ?: 1.0
        val aff = st.artists.take(40).associate { it.key to it.sec / amax }
        val X = rows.map { r -> feat(r.t, Ctx(r.e.ts, r.e.hour, r.prev?.id, if (r.prev != null) Biblio.artistKey(r.prev.artist) else "", vec(r.prev), aff), r.e.hour) }
        val Y = rows.map { it.y }
        val ww = W0.copyOf(); val lr = 0.06; val lam = 0.02
        repeat(8) { for (i in X.indices) { val err = sigmoid(dot(ww, X[i])) - Y[i]; for (j in ww.indices) ww[j] -= lr * (err * X[i][j] + lam * (ww[j] - W0[j])) } }
        w = DoubleArray(ww.size) { Math.round(ww[it] * 1000) / 1000.0 }; n = rows.size; at = ev.size
        Prefs.set("brainW", w!!.toList()); Prefs.set("brainN", n); Prefs.set("brainAt", at)
        return true
    }
    fun maybeTrain() { if (Memoria.events.size - at >= 8) Biblio.io.execute { try { train() } catch (ignored: Throwable) { } } }

    // ---------------------------------------------------------------- elegir lo que sigue
    class Scored(val t: Track, val s: Double)
    fun rank(pool: List<Track>, prev: Track?, target: Double? = null, exclude: Set<String> = emptySet(), explore: Double = 0.35, strength: Double = 1.6): List<Scored> {
        val c = ctx(prev); val pa = Analizador.get(prev)
        return pool.filter { !it.net && it.id !in ban && it.id !in exclude && (prev == null || it.id != prev.id) }.map { t ->
            var s = (1 - pSkip(t, c)) * 2 + prefBoost(t)
            val v = vec(t)
            if (target != null && v != null) s -= abs(v[0] - target) * strength else if (target != null) s -= 0.25 * strength
            val a = Analizador.get(t)
            if (a != null && pa != null && a.bpm != 0.0 && pa.bpm != 0.0 && a.conf >= 2 && pa.conf >= 2) { var r = a.bpm / pa.bpm; if (r > 1.6) r /= 2 else if (r < 0.625) r *= 2; if (abs(r - 1) <= 0.07) s += 0.35 }
            if (a != null && pa != null) s += (Mezcla.keyCompat(Mezcla.anaKey(pa), Mezcla.anaKey(a)) - 0.5) * 0.5
            s += Random.nextDouble() * explore
            Scored(t, s)
        }.sortedByDescending { it.s }
    }

    fun similarTo(t: Track, k: Int = 15): List<Track> {
        val v = vec(t); val c = ctx(t)
        return pool().filter { it.id != t.id }.map { x ->
            val cv = vec(x); var s = (1 - pSkip(x, c)) * 0.6
            s += if (v != null && cv != null) (1.2 - vdist(v, cv) * 2) else -0.3
            s += 0.6 * (transScore(t.id, x.id) + transScore(x.id, t.id))
            if (Biblio.artistKey(x.artist).isNotEmpty() && Biblio.artistKey(x.artist) == Biblio.artistKey(t.artist)) s += 0.3
            Scored(x, s + Random.nextDouble() * 0.15)
        }.sortedByDescending { it.s }.take(k).map { it.t }
    }

    /** La radio que se adapta: saltar aleja la energía de lo saltado; escuchar entera la acerca. */
    fun radioNext(prev: Track?, k: Int = 6): List<String> {
        val pl = pool()
        val recent = HashSet<String>(); recent.addAll(Biblio.history().take(min(12, pl.size / 2))); recent.addAll(Player.queue.subList(max(0, Player.qi - 30), min(Player.queue.size, Player.qi + 1)))
        var ranked = rank(pl, prev, target, recent)
        if (ranked.isEmpty()) ranked = rank(pl, prev, target)
        val out = ArrayList<String>(); val seen = HashSet<String>()
        for (x in ranked) { val a = Biblio.artistKey(x.t.artist).ifEmpty { x.t.id }; if (a in seen && ranked.size > 12) continue; out.add(x.t.id); seen.add(a); if (out.size >= k) break }
        return out
    }
    fun radioBatch(avoid: List<String> = emptyList()): List<String> {
        val prev = avoid.lastOrNull()?.let { Lib.byId(it) } ?: Player.current()
        return radioNext(prev, 8).filter { it !in avoid }
    }

    fun onBegin(t: Track) {
        try { Todo.onBegin(t) } catch (ignored: Throwable) { }
        if (t.net) return
        try { Recos.maybeOffer(t) } catch (ignored: Throwable) { }
        val v = vec(t); val pe = prefEnergy()
        if (target == null && (v != null || pe != null)) target = pe ?: v!![0]
        if (Player.source == "radio") Player.replaceAhead(radioNext(t, 6))
    }

    fun onListen(ev: Memoria.Ev) {
        val v = vec(Lib.byId(ev.id)) ?: return
        if (target == null) target = v[0]
        val tg = target!!
        if (ev.skip) target = max(0.05, min(0.95, tg + (if (v[0] >= tg) -0.12 else 0.12)))
        else if (ev.pct >= 0.6) target = tg + (v[0] - tg) * 0.3
        maybeTrain()
    }

    fun banTrack(t: Track) {
        if (t.net) return
        ban.add(t.id); Prefs.set("banned", ban.toList())
        Nativo.toast("Vale: «${t.title}» no volverá a sonar en la radio ni en las mezclas. Puedes deshacerlo en «Tú».", 4200)
        if (Player.current()?.id == t.id) { Player.dropAhead(t.id); Player.next(false) }
    }
    fun unban(id: String) { ban.remove(id); Prefs.set("banned", ban.toList()) }

    fun moreLikeThis() {
        val t = Player.current(); if (t == null || t.net) { Nativo.toast("Pon una canción de tu biblioteca primero"); return }
        val sims = similarTo(t, 15).map { it.id }; if (sims.isEmpty()) { Nativo.toast("Aún no conozco bien tu música: escucha unas cuantas canciones más"); return }
        Player.replaceAhead(sims); Player.setSource("mix"); Player.intros = emptyMap(); Player.mixEndless = mapOf("like" to t.id)
        if (Radio.on) Radio.set(false, true)
        Nova.say(pick(listOf("A partir de ahora, canciones que suenan como ${t.title}.", "Marchando más música en la onda de ${t.title}.", "Si te gusta esta, lo que viene te va a encantar.")), "siguiente")
        Nativo.toast("Cola cambiada: canciones parecidas a esta, sin fin")
    }

    // ---------------------------------------------------------------- lo que se pide con palabras (sin IA)
    class Intent(val re: Regex, val energy: Double, val bright: Double?, val name: String)
    private val INTENTS = listOf(
        Intent(Regex("dormir|sueño|siesta|relaj|calm|tranquil|suave|chill|lluvia|leer|meditar|paz"), 0.18, null, "Para bajar revoluciones"),
        Intent(Regex("estudi|concentr|trabaj|foco|programar|oficina|deberes"), 0.35, null, "Para concentrarte"),
        Intent(Regex("triste|llorar|melanc|bajón|bajon|desamor|nostalg|gris|solo|sola"), 0.3, 0.35, "Para los días grises"),
        Intent(Regex("romant|enamorad|en pareja|con mi pareja|cena con velas|san valentin"), 0.4, 0.6, "Para dos"),
        Intent(Regex("feliz|alegr|anim|buen rollo|positiv|verano|sonreír|sonreir|happy"), 0.7, 0.7, "Para venirse arriba"),
        Intent(Regex("fiesta|bailar|baile|perreo|viernes|discoteca|party|finde"), 0.85, null, "Modo fiesta"),
        Intent(Regex("gym|gimnas|entren|correr|cardio|pesas|motiva|deporte|running"), 0.92, null, "Para entrenar"),
        Intent(Regex("movid|ritmo|energ|subir|caña|potente|fuerte|duro|a tope"), 0.8, null, "Con energía"),
        Intent(Regex("coche|conducir|viaje|carretera"), 0.6, null, "Para la carretera"),
        Intent(Regex("cocinar|limpiar|casa|ducha"), 0.6, null, "Para casa")
    )
    class Req { var energy: Double? = null; var bright: Double? = null; var name = ""; var liked = false; var fresh = false; var forgotten = false
        val artists = ArrayList<String>(); val notArtists = ArrayList<String>(); var count: Int? = null; var minutes: Int? = null
        val genres = ArrayList<String>(); val notGenres = ArrayList<String>() }
    private val NEG = Regex("(sin|nada de|menos|excepto|no pongas|ni)\\s*$")
    fun parseRequest(text: String, pool: List<Track>): Req {
        val q = " " + Biblio.norm(text) + " "; val o = Req()
        for (it in INTENTS) if (it.re.containsMatchIn(q)) { o.energy = it.energy; if (it.bright != null) o.bright = it.bright; o.name = it.name; break }
        if (Regex("(mas|muy) (tranquil|suave|lent)").containsMatchIn(q) && o.energy == null) o.energy = 0.2
        if (Regex("rapid|acelerad").containsMatchIn(q)) o.energy = max(o.energy ?: 0.0, 0.8)
        if (Regex("favorit|las que me gustan|mis preferidas").containsMatchIn(q)) o.liked = true
        if (Regex("nuev|no he escuchado|descubr|sin escuchar|recien").containsMatchIn(q)) o.fresh = true
        if (Regex("olvidad|antigu|hace tiempo|viejas|clasicos mios|redescubr").containsMatchIn(q)) o.forgotten = true
        Regex("(\\d{1,3})\\s*(canciones|temas|cancion)").find(q)?.let { o.count = min(60, it.groupValues[1].toInt()) }
        run {
            val W = mapOf("una" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4)
            val hm = Regex(" (\\d{1,2}|una|un|dos|tres|cuatro) (horas?|h) ").find(q); val mm = Regex("(\\d{1,3})\\s*(min|minutos) ").find(q)
            if (hm != null) o.minutes = (W[hm.groupValues[1]] ?: hm.groupValues[1].toInt()) * 60 + (if (q.contains(" y media ")) 30 else 0)
            else if (q.contains(" hora y media ")) o.minutes = 90 else if (mm != null) o.minutes = mm.groupValues[1].toInt() else if (q.contains(" media hora ")) o.minutes = 30
            o.minutes?.let { o.minutes = min(300, it) }
        }
        val artists = pool.map { it.artist }.filter { Biblio.artistKey(it).length >= 3 }.distinct()
        for (a in artists) {
            val k = Biblio.artistKey(a); val i = q.indexOf(" $k "); if (i < 0) continue
            val before = q.substring(max(0, i - 14), i)
            if (NEG.containsMatchIn(before)) o.notArtists.add(k) else o.artists.add(k)
        }
        for ((g, re) in GENRES) { val mm = re.find(q) ?: continue; val before = q.substring(max(0, mm.range.first - 14), mm.range.first); if (NEG.containsMatchIn(before)) o.notGenres.add(g) else o.genres.add(g) }
        return o
    }

    /** Una mezcla (como la de la web: nombre, descripción, saludo, canciones, presentaciones…). */
    class Mix(var source: String, var name: String, var desc: String, var greeting: String, var ids: List<String>, var why: Map<String, String>, var intros: Map<String, String>,
              var discover: List<Map<String, String>>, var moment: String, var mood: String, var scenario: String?) {
        var request: String? = null; var note: String? = null; var endless = false; var refill: Map<String, Any?>? = null
    }

    fun arrangeArc(list: List<Track>, key: String): List<Track> {
        val withE = list.map { t -> t to (Analizador.rawOf(t)?.get("energy") as? Number)?.toDouble() }
        if (withE.count { it.second != null } < list.size * 0.6) return withE.map { it.first }.sortedBy { seeded(key + "o" + it.id) }
        val sorted = withE.map { it.first to (it.second ?: 0.5) }.sortedBy { it.second }
        val up = ArrayList<Track>(); val down = ArrayList<Track>()
        sorted.forEachIndexed { i, x -> if (i % 3 == 2) down.add(x.first) else up.add(x.first) }
        return up + down.reversed()
    }

    fun intentMix(text: String, pool0: List<Track>, n: Int, m: Momento.Moment): Mix {
        var pool = pool0
        val free = pool(free = true); val rq = parseRequest(text, free)
        if (rq.artists.isNotEmpty() || rq.genres.isNotEmpty()) { val own = pool.all { !it.demo }; pool = if (own) free.filter { !it.demo } else free }
        val now = System.currentTimeMillis()
        var sel = pool.filter { Biblio.artistKey(it.artist) !in rq.notArtists }
        if (rq.artists.isNotEmpty()) sel = sel.filter { t -> Biblio.artistKey(t.artist) in rq.artists }
        var gNote = ""
        if (rq.genres.isNotEmpty()) { val g = sel.filter { t -> rq.genres.any { x -> x in trackGenres(t) } }; if (g.size >= 2) sel = g else gNote = "Aún no sé qué canciones tuyas son ${rq.genres[0]}: lo voy aprendiendo, y puedes enseñarme diciendo «tal artista es ${rq.genres[0]}»." }
        if (rq.notGenres.isNotEmpty()) sel = sel.filter { t -> rq.notGenres.none { x -> x in trackGenres(t) } }
        if (rq.liked) sel = sel.filter { Biblio.liked(it) }
        if (rq.fresh) sel = sel.filter { Biblio.plays(it) <= 1 }
        if (rq.forgotten) sel = sel.filter { t -> Biblio.plays(t) >= 2 && t.lastPlayed > 0 && now - t.lastPlayed > 14 * 864e5 }
        var note = ""
        if (sel.size < 2) { sel = pool.filter { Biblio.artistKey(it.artist) !in rq.notArtists }; note = "No había suficientes canciones con esos filtros, así que he ampliado un poco." }
        val target = rq.energy
        val bright = rq.bright
        if (bright != null) sel = sel.map { t -> t to (vec(t)?.get(1) ?: 0.5) }.sortedBy { abs(it.second - bright) }.take(max(n * 2, 12)).map { it.first }
        val want = rq.count ?: n
        if (target != null) { val known = sel.filter { vec(it) != null }; if (known.size >= min(want, 4)) sel = known }
        if (target != null) { val near = sel.filter { t -> val v = vec(t); v == null || abs(v[0] - target) <= 0.32 }; if (near.size >= 3) sel = near }
        val ranked = rank(sel, if (sel.size <= 3) null else Player.current(), target, explore = 0.5, strength = if (target != null) 4.5 else 1.6).map { it.t }
        var picked: List<Track>
        val mins = rq.minutes
        if (mins != null) { val l = ArrayList<Track>(); var tot = 0.0; for (t in ranked) { if (tot >= mins * 60) break; l.add(t); tot += if (t.duration > 0) t.duration else 200.0 }; picked = l }
        else picked = ranked.take(want)
        val en = { t: Track -> vec(t)?.get(0) ?: 0.5 }
        val ids = (if (target != null && target <= 0.35) picked.sortedByDescending(en) else if (target != null && target >= 0.75) picked.sortedBy(en) else arrangeArc(picked, "req$now")).map { it.id }
        val artistName = if (rq.artists.size == 1) pool.firstOrNull { t -> Biblio.artistKey(t.artist) == rq.artists[0] }?.artist ?: "" else ""
        if (gNote.isNotEmpty()) note = (if (note.isNotEmpty()) "$note " else "") + gNote
        val name = if (artistName.isNotEmpty()) "Solo $artistName" else if (rq.genres.isNotEmpty() && gNote.isEmpty()) "Solo ${rq.genres[0]}" else if (rq.liked) "Tus favoritas"
            else if (rq.fresh) "Por descubrir" else if (rq.forgotten) "Las que tenías olvidadas" else rq.name.ifEmpty { "Tu petición de ${m.day}" }
        val first = ids.firstOrNull()?.let { Lib.byId(it) }
        val mix = Mix("local", name, "Elegida por ${Nova.name()} con lo que ha aprendido de ti: «$text».",
            if (first != null) "${pick(listOf("Marchando", "Hecho", "Vamos allá", "Aquí lo tienes"))}: ${name.lowercase()}. Empezamos con ${nameOf(first)}." else "",
            ids, emptyMap(), emptyMap(), emptyList(), m.label, "auto", null)
        if (note.isNotEmpty()) mix.note = note
        return mix
    }

    val MOOD_ENERGY = mapOf<String, Double?>("energia" to 0.8, "calma" to 0.22, "foco" to 0.35, "fiesta" to 0.85, "gris" to 0.3, "gym" to 0.92, "nostalgia" to null)
    fun momentEnergy(m: Momento.Moment): Double = when (m.franja.id) { "madrugada" -> 0.2; "manana" -> 0.5; "mediodia" -> 0.62; "tarde" -> 0.55; "noche" -> if (m.weekend) 0.8 else 0.35; else -> 0.5 }
    fun moodLabel(id: String): String = (Momento.MOODS.firstOrNull { it.id == id } ?: Momento.MOODS[0]).label

    fun localMix(pool: List<Track>, m: Momento.Moment, mood: String, scenario: String?, n: Int, minutes: Int = 0): Mix {
        val now = System.currentTimeMillis()
        var sel = pool
        if (mood == "nostalgia") { val f = pool.filter { Biblio.plays(it) >= 2 && it.lastPlayed > 0 && now - it.lastPlayed > 14 * 864e5 }; if (f.size >= min(4, n)) sel = f }
        val t0 = if (mood == "auto") (prefEnergy() ?: momentEnergy(m)) else MOOD_ENERGY[mood]
        val target = t0?.let { max(0.05, min(0.95, it + (if (mood == "auto") Online.weatherBias() else 0.0))) }
        val recent = Biblio.history().take(5).toSet()
        val ranked = rank(sel, Player.current(), target, recent, 0.6).map { it.t }
        val ordered = if (ranked.size >= n || minutes > 0) ranked else rank(sel, Player.current(), target, explore = 0.6).map { it.t }
        var chosen = ordered.take(n)
        if (minutes > 0) { val l = ArrayList<Track>(); var tot = 0.0; for (t in ordered) { if (tot >= minutes * 60) break; l.add(t); tot += if (t.duration > 0) t.duration else 200.0 }; chosen = l }
        val ids = arrangeArc(chosen, "mix$now").map { it.id }
        val names = if (scenario != null) listOf(cap(scenario)) else listOf("${pick(listOf("Sesión", "Ruta", "Mezcla", "Vuelta"))} de ${m.day} ${m.franja.phrase}", "${cap(m.day)} en órbita",
            if (mood == "auto") "Cara B ${m.franja.phrase}" else "Modo ${moodLabel(mood).lowercase()}")
        val first = ids.firstOrNull()?.let { Lib.byId(it) }
        return Mix("local", pick(names), "Elegida por ${Nova.name()} en tu móvil, con lo que ha aprendido de lo que escuchas y saltas.",
            if (first != null) "Te he preparado una mezcla para este ${m.day}. Abrimos con ${nameOf(first)}." else "", ids, emptyMap(), emptyMap(), emptyList(), m.label, mood, scenario)
    }

    // ---------------------------------------------------------------- la mezcla del día
    class Song(val id: String, val why: String)
    class Daily(val key: String, val pool: Int, val ids: List<String>, var name: String, var desc: String, var greeting: String, var intros: Map<String, String>, val song: Song?, var ai: Boolean, var aiTried: Boolean) {
        fun toMap(): Map<String, Any?> = linkedMapOf("key" to key, "pool" to pool, "ids" to ids, "name" to name, "desc" to desc, "greeting" to greeting, "intros" to intros,
            "song" to song?.let { linkedMapOf("id" to it.id, "why" to it.why) }, "ai" to ai, "aiTried" to aiTried)
        companion object {
            fun from(m: Map<String, Any?>?): Daily? {
                if (m == null) return null
                val s = Json.obj(m["song"])
                return Daily(Json.s(m["key"]), Json.l(m["pool"]).toInt(), Json.list(m["ids"])?.map { Json.s(it) } ?: emptyList(), Json.s(m["name"]), Json.s(m["desc"]), Json.s(m["greeting"]),
                    (Json.obj(m["intros"]) ?: emptyMap()).mapValues { Json.s(it.value) }, s?.let { Song(Json.s(it["id"]), Json.s(it["why"])) }, Json.b(m["ai"]), Json.b(m["aiTried"]))
            }
        }
    }

    fun buildDaily(force: Boolean = false): Daily? {
        if (!Nativo.ready) return null
        val key = Memoria.dayKey()
        var pool = pool()
        if (pool.count { !it.demo } >= 6) pool = pool.filter { !it.demo }
        val cached = Daily.from(Json.obj(Prefs.get("daily")))
        if (!force && cached != null && cached.key == key && cached.ids.isNotEmpty() && cached.ids.count { Lib.byId(it) != null } >= min(4, cached.ids.size) && abs(cached.pool - pool.size) <= max(3.0, pool.size * 0.2)) return cached
        if (pool.size < 3) return null
        val salt = if (force) java.lang.Long.toString((Math.random() * 1e9).toLong(), 36).take(5) else ""
        val seed = key + salt
        val st = Memoria.stats(30); val h = hourNow(); val now = System.currentTimeMillis()
        val artistRank = st.artists.take(12).mapIndexed { i, a -> a.key to 1 - i / 12.0 }.toMap()
        val bc = ctx(null)
        val rec = st.all.associateBy { it.id }
        class Sc(val t: Track, val s: Double, val fresh: Boolean)
        val scored = pool.map { t ->
            val r = rec[t.id]; val p = (r?.plays ?: 0).toDouble(); val sk = (r?.skips ?: 0).toDouble()
            var s = ln(1 + p) * 0.9 + (if (Biblio.liked(t)) 1.1 else 0.0) + (artistRank[Biblio.artistKey(t.artist)] ?: 0.0) * 0.8
            s -= (sk / max(1.0, p + sk)) * 1.6 + (if (Memoria.skipProne(t)) 0.6 else 0.0)
            val hs = t.hours
            if (hs != null && hs.size == 24) { val tot = hs.sum(); if (tot >= 3) s += ((hs[h] + 0.5 * (hs[(h + 23) % 24] + hs[(h + 1) % 24])) / tot) * 1.2 }
            val ago = if (t.lastPlayed > 0) (now - t.lastPlayed) / 864e5 else 999.0
            if (ago < 1) s -= 1.6 else if (ago < 3) s -= 0.6 else if (ago > 20 && Biblio.plays(t) >= 3) s += 0.5
            s -= pSkip(t, bc) * 1.4
            s += seeded(seed + t.id) * 1.1
            Sc(t, s, Biblio.plays(t) <= 1)
        }.sortedByDescending { it.s }
        val n = min(20, max(6, Math.round(pool.size * 0.35).toInt()))
        val out = ArrayList<Track>(); val perArtist = HashMap<String, Int>()
        fun take(list: List<Sc>, k0: Int) {
            var k = k0
            for (x in list) {
                if (out.size >= n || k <= 0) break
                val a = Biblio.artistKey(x.t.artist).ifEmpty { x.t.id }
                if (x.t in out || ((perArtist[a] ?: 0) >= 2 && pool.size > 30)) continue
                out.add(x.t); perArtist[a] = (perArtist[a] ?: 0) + 1; k--
            }
        }
        take(scored.filter { !it.fresh }, Math.round(n * 0.7).toInt()); take(scored.filter { it.fresh }, n - out.size); take(scored, n - out.size)
        val ids = arrangeArc(out, seed).map { it.id }
        val m = Momento.moment()
        // la canción del día
        var song: Song? = null
        run {
            val cand = scored.take(30).filter { !it.t.demo || scored.size < 8 }.filter { it.t.lastPlayed == 0.0 || now - it.t.lastPlayed > 2 * 864e5 }
            val x = cand.sortedByDescending { it.s + (if (Biblio.liked(it.t)) 0.4 else 0.0) }.firstOrNull() ?: scored.firstOrNull() ?: return@run
            val t = x.t; val r = st.all.firstOrNull { it.id == t.id }; val ago = if (t.lastPlayed > 0) Math.round((now - t.lastPlayed) / 864e5).toInt() else 0
            val top = st.artists.firstOrNull()?.key == Biblio.artistKey(t.artist)
            val why = if (r != null && r.plays >= 5) "La has escuchado ${r.plays} veces este mes."
                else if (Biblio.liked(t) && ago > 6) "Es de tus favoritas y hace $ago días que no suena."
                else if (top) "${t.artist} es lo que más escuchas últimamente."
                else if (x.fresh) "Apenas la has escuchado: dale una oportunidad."
                else if (ago > 14) "Hace $ago días que no la pones." else "Encaja con tu día de hoy."
            song = Song(t.id, why)
        }
        val d = Daily(key, pool.size, ids, "Tu mezcla del ${m.day}",
            if (Memoria.events.size > 20) "Lo que más te gusta, lo que te pega a esta hora y alguna que casi no escuchas." else "Tus favoritas y canciones que aún no has escuchado mucho. Cuanto más escuches, mejor te conoceré.",
            "", emptyMap(), song, false, false)
        Prefs.set("daily", d.toMap()); return d
    }

    fun rediscover(nmax: Int = 4): List<Track> { val now = System.currentTimeMillis(); return Biblio.visible().filter { !it.net && !it.demo && Biblio.plays(it) >= 4 && it.lastPlayed > 0 && now - it.lastPlayed > 21 * 864e5 && !Memoria.skipProne(it) }.sortedByDescending { Biblio.plays(it) }.take(nmax) }
    fun newArrivals(nmax: Int = 4): List<Track> { val now = System.currentTimeMillis(); return Biblio.visible().filter { !it.net && !it.demo && it.addedAt > 0 && now - it.addedAt < 14 * 864e5 && Biblio.plays(it) <= 1 }.sortedByDescending { it.addedAt }.take(nmax) }

    fun playDaily(i: Int = 0) {
        val d = buildDaily() ?: return
        val ids = d.ids.filter { Lib.byId(it) != null }; if (ids.isEmpty()) return
        val first = Lib.byId(ids[min(i, ids.size - 1)])!!
        val talk = if (i == 0) (d.greeting.ifEmpty { "${d.name}. Empezamos con ${nameOf(first)}." }) else null
        Player.playQueue(ids, i, "daily", talk?.let { it to "saludo" }, d.intros)
    }

    // ---------------------------------------------------------------- lo que dice de ti
    fun profileText(): String {
        if (Memoria.events.size < 5) return ""
        val st = Memoria.stats(30); val out = ArrayList<String>()
        if (st.artists.isNotEmpty()) out.add("Artistas que más escucha: " + st.artists.take(6).joinToString(", ") { it.name } + ".")
        if (st.tracks.isNotEmpty()) out.add("Canciones que más pone últimamente: " + st.tracks.take(6).joinToString("; ") { "${it.title} (${it.artist})" } + ".")
        if (st.sec > 900) out.add("Suele escuchar música ${franjaOf(argmax(st.hours)).phrase}.")
        val sk = Memoria.stats(0).all.filter { it.skips >= 2 && it.skips >= it.plays }.take(5)
        if (sk.isNotEmpty()) out.add("Suele saltarse: " + sk.joinToString("; ") { it.title } + ".")
        return out.joinToString(" ")
    }

    fun pickFresh(opts: List<String>): String {
        val fresh = opts.filter { it !in said }; val line = pick(if (fresh.isNotEmpty()) fresh else opts)
        said.add(0, line); while (said.size > 14) said.removeAt(said.size - 1); return line
    }

    fun memoryLine(t: Track): String? {
        if (Memoria.events.size < 10 || Math.random() > 0.35) return null
        val st = Memoria.stats(30); val r = st.all.firstOrNull { it.id == t.id }; val opts = ArrayList<String>()
        if (st.tracks.firstOrNull()?.id == t.id && st.tracks[0].plays >= 4) opts.add("Tu canción más escuchada del mes: ${t.title}.")
        else if (r != null && r.plays >= 5) opts.add("Esta ya la has puesto ${r.plays} veces este mes: ${t.title}.")
        if (t.prevPlayed > 0 && System.currentTimeMillis() - t.prevPlayed > 30 * 864e5 && Biblio.plays(t) >= 3) opts.add("Hacía mucho que no sonaba ${nameOf(t)}.")
        val a0 = st.artists.firstOrNull()
        if (a0 != null && a0.key == Biblio.artistKey(t.artist) && a0.plays >= 5) opts.add("De tu artista del mes, ${t.artist}: ${t.title}.")
        return if (opts.isNotEmpty()) pick(opts) else null
    }

    fun contextLine(t: Track): String? {
        val prev = Player.queue.getOrNull(Player.qi - 1)?.let { Lib.byId(it) }; val opts = ArrayList<String>()
        val v = vec(t); val pv = vec(prev)
        if (v != null && pv != null && v[0] - pv[0] > 0.22) opts.addAll(listOf("Subimos revoluciones: ${t.title}.", "Toca acelerar. ${nameOf(t)}."))
        if (v != null && pv != null && pv[0] - v[0] > 0.22) opts.addAll(listOf("Bajamos un poco el ritmo con ${t.title}.", "Respiramos un momento: ${nameOf(t)}."))
        val art = Biblio.artistKey(t.artist)
        if (art.isNotEmpty() && prev != null && Biblio.artistKey(prev.artist) == art) opts.addAll(listOf("Seguimos con ${t.artist}, que hoy no hay quien lo pare.", "Repite ${t.artist}: ${t.title}."))
        if (Biblio.plays(t) <= 1 && !t.demo) opts.addAll(listOf("Esta es nueva para ti: ${nameOf(t)}.", "Estreno: ${t.title}. Ya me dirás."))
        if (prev != null && transScore(prev.id, t.id) > 0.5) opts.add("Después de ${prev.title} siempre te pega ${t.title}.")
        if (hourNow() < 5) opts.add("Para los que seguimos despiertos: ${t.title}.")
        val now = System.currentTimeMillis()
        val session = synchronized(Memoria) { Memoria.events.filter { now - it.ts < 90 * 60000 }.sumOf { it.sec } }
        if (session > 3600 && !saidHour) { saidHour = true; opts.clear(); opts.add("Llevas más de una hora de música conmigo. Seguimos con ${t.title}.") }
        return if (opts.isNotEmpty() && Math.random() < 0.55) pickFresh(opts) else null
    }

    fun memFacts(): List<String> {
        val st = Memoria.stats(30); val all = Memoria.stats(0); val f = ArrayList<String>()
        if (all.n == 0) return f
        st.artists.firstOrNull()?.let { a -> f.add("Tu artista del mes es ${a.name}" + (st.artists.getOrNull(1)?.let { ", seguido de ${it.name}" } ?: "") + ".") }
        st.tracks.firstOrNull()?.let { if (it.plays >= 2) f.add("La canción que más has puesto este mes es «${it.title}» (${it.plays} veces).") }
        if (st.sec > 600) { val ph = argmax(st.hours); f.add("Escuchas más música ${franjaOf(ph).phrase}, sobre las $ph:00.") }
        if (st.sec > 1800) { val pd = argmax(st.dows); f.add("Tu día con más música es el ${Momento.DAYS[pd]}.") }
        val sk = all.all.filter { it.skips >= 2 && it.skips >= it.plays }.take(3)
        if (sk.isNotEmpty()) f.add("Sueles saltarte ${listES(sk.map { "«${it.title}»" })}, así que te las pongo menos.")
        val likes = Lib.tracks.count { !it.demo && Biblio.liked(it) }
        if (likes > 0) f.add("Tienes $likes ${if (likes == 1) "favorita" else "favoritas"}: salen más a menudo en tus mezclas y en la radio.")
        if (all.radioSec > 300) f.add("Has escuchado ${fmtLong(all.radioSec)} de radio por internet.")
        if (n >= 25) f.add("He aprendido de $n canciones que escuchaste enteras o saltaste: ya sé mejor qué ponerte y a qué hora.")
        val tr = transitions().entries.filter { it.value >= 2 }.maxByOrNull { it.value }
        if (tr != null) { val (x, y) = tr.key.split(">").map { Lib.byId(it) }; if (x != null && y != null) f.add("Te gusta mucho escuchar «${y.title}» justo después de «${x.title}».") }
        return f
    }

    /** Lo analizado de tu música (para «Tú»). */
    fun analysedShare(): Pair<Int, Int> { val own = Lib.tracks.filter { !it.net && !it.demo }; return own.count { Analizador.get(it) != null } to own.size }

    // ---------------------------------------------------------------- analizar toda la biblioteca, poco a poco
    private var bgDone = 0
    fun bgAnalysis(delay: Long = 8000) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!Nativo.on) return@postDelayed
            val charging = Nativo.charging()
            if (!Nativo.awake || Biblio.job != null || Voz.speaking) { bgAnalysis(15000); return@postDelayed }
            if (!charging && bgDone >= 24) return@postDelayed
            val todo = Lib.tracks.filter { !it.net && !it.anaFail && (it.duration == 0.0 || it.duration <= 900) && (it.size == 0L || it.size < 60_000_000L) && Analizador.get(it) == null && !Analizador.busy(it.id) }
            if (todo.isEmpty()) return@postDelayed
            val t = todo.maxByOrNull { Biblio.plays(it) }!!; bgDone++
            Analizador.schedule(t) { }
            Analizador.after { if (Analizador.get(t) == null) t.anaFail = true; bgAnalysis(if (charging) 4000 else 12000) }
        }, delay)
    }
}

/** «Todo mi Órbita»: todas tus canciones, sin repetir ninguna hasta que suenen todas, en un orden que fluye. */
object Todo {
    private var o: MutableMap<String, Any?>? = null
    private var nextRound: MutableMap<String, Any?>? = null
    private var loaded = false

    private fun st(): MutableMap<String, Any?>? { if (!loaded) { o = Json.obj(Prefs.get("todo"))?.toMutableMap(); loaded = true }; return o }
    private fun order(x: Map<String, Any?>?): MutableList<String> = (Json.list(x?.get("order"))?.map { Json.s(it) } ?: emptyList()).toMutableList()
    private fun pos(x: Map<String, Any?>?): Int = Json.l(x?.get("pos")).toInt().let { if (x?.get("pos") == null) -1 else it }
    private fun save() { o?.let { Prefs.set("todo", it) } }

    fun pool(): List<Track> { val all = Cerebro.pool(free = true); val own = all.filter { !it.demo }; return if (own.size >= 2) own else all }
    private fun alive(id: String): Boolean { val t = Lib.byId(id); return t != null && !t.net && id !in Cerebro.ban }

    private fun transition(a: Feat, b: Feat, wave: Double): Double {
        var s = 0.0
        if (a.ar == b.ar) s -= 2
        if (a.v != null && b.v != null) { s += (1 - min(1.0, abs(a.v[0] - b.v[0]) * 2.5)) * 0.8; s += (1 - min(1.0, abs(a.v[1] - b.v[1]) * 2)) * 0.2; s -= abs(b.v[0] - wave) * 0.45 }
        if (a.bpm != 0.0 && b.bpm != 0.0) { var r = a.bpm / b.bpm; if (r > 1.6) r /= 2 else if (r < 0.625) r *= 2; val d = abs(r - 1); s += if (d <= 0.04) 0.8 else if (d <= 0.08) 0.4 else 0.0 }
        s += (Mezcla.keyCompat(a.k, b.k) - 0.5) * 1.2
        return s
    }
    private class Feat(val v: DoubleArray?, val bpm: Double, val k: Int, val ar: String)

    fun makeOrder(ids: List<String>, first: String?): List<String> {
        val feat = LinkedHashMap<String, Feat>()
        for (id in ids) { val t = Lib.byId(id) ?: continue; val a = Analizador.get(t); feat[id] = Feat(Cerebro.vec(t), if (a != null && a.conf >= 1.5) a.bpm else 0.0, Mezcla.anaKey(a), Biblio.artistKey(t.artist).ifEmpty { id }) }
        val rest = feat.keys.toMutableList(); if (rest.isEmpty()) return emptyList()
        fun take(i: Int): String { val id = rest[i]; rest[i] = rest[rest.size - 1]; rest.removeAt(rest.size - 1); return id }
        val fi = if (first != null && feat.containsKey(first)) rest.indexOf(first) else Random.nextInt(rest.size)
        var cur = take(if (fi < 0) 0 else fi); val out = arrayListOf(cur); val recent = arrayListOf(feat[cur]!!.ar)
        val K = 48; val phase = Random.nextDouble() * 6.28
        while (rest.isNotEmpty()) {
            val cf = feat[cur]!!; val wave = 0.5 + 0.22 * sin(phase + out.size * 2 * Math.PI / 14)
            var best = -1; var bs = Double.NEGATIVE_INFINITY
            val n = min(K, rest.size)
            for (j in 0 until n) {
                val i = if (rest.size <= K) j else Random.nextInt(rest.size); val cf2 = feat[rest[i]]!!
                var sc = transition(cf, cf2, wave) + Random.nextDouble() * 0.3
                if (cf2.ar in recent) sc -= 1.5
                if (sc > bs) { bs = sc; best = i }
            }
            cur = take(best); out.add(cur)
            recent.add(0, feat[cur]!!.ar); while (recent.size > 3) recent.removeAt(recent.size - 1)
        }
        return out
    }

    fun sync(): MutableMap<String, Any?> {
        val ids = pool().map { it.id }
        var x = st()
        val ord = order(x)
        if (x == null || ord.isEmpty() || pos(x) >= ord.size - 1) {
            val cycle = if (ord.isNotEmpty()) Json.l(x?.get("cycle")).toInt().let { if (it == 0) 1 else it } + 1 else 1
            val last = ord.takeLast(10).toSet()
            val first = Cerebro.shuffle(ids.filter { it !in last }).firstOrNull()
            x = linkedMapOf("order" to makeOrder(ids, first), "pos" to -1, "cycle" to cycle, "ts" to System.currentTimeMillis())
            o = x; save(); return x
        }
        val have = ord.toHashSet(); val fresh = ids.filter { it !in have }
        if (fresh.isNotEmpty()) {
            val p = pos(x)
            for (id in Cerebro.shuffle(fresh)) { val at = p + 1 + Random.nextInt(ord.size - p); ord.add(min(at, ord.size), id) }
            x["order"] = ord; save()
        }
        return x
    }

    fun total(): Int { val x = st(); return if (x != null) order(x).count { alive(it) } else pool().size }

    fun after(id: String, k: Int = 12): List<String> {
        val x = st() ?: sync()
        val ord = order(x); val i0 = ord.indexOf(id)
        var i = if (i0 >= 0) i0 + 1 else pos(x) + 1
        val out = ArrayList<String>()
        while (i < ord.size && out.size < k) { if (alive(ord[i])) out.add(ord[i]); i++ }
        if (out.size < k) {
            if (nextRound == null) {
                val ids = pool().map { it.id }; val last = ord.takeLast(12).toSet()
                val first = Cerebro.shuffle(ids.filter { it !in last }).firstOrNull()
                nextRound = linkedMapOf("order" to makeOrder(ids, first), "pos" to -1, "cycle" to Json.l(x["cycle"]).toInt().let { if (it == 0) 1 else it } + 1, "ts" to System.currentTimeMillis())
            }
            for (y in order(nextRound)) { if (out.size >= k) break; if (alive(y) && y !in out) out.add(y) }
        }
        return out
    }

    fun refill(): List<String> {
        if (Player.source != "todo") return emptyList()
        val last = Player.queue.lastOrNull() ?: return emptyList()
        val tail = Player.queue.takeLast(20).toSet()
        return after(last, 12).filter { it !in tail }
    }

    fun onBegin(t: Track) {
        if (Player.source != "todo" || t.net) return
        val x = st() ?: return
        val ord = order(x); val i = ord.indexOf(t.id); val p = pos(x)
        val nx = nextRound; val j = if (nx != null) order(nx).indexOf(t.id) else -1
        if (nx != null && j in 0 until 60 && (i < 0 || i <= p) && ord.drop(p + 1).none { alive(it) }) {
            nx["pos"] = j; o = nx; nextRound = null; save()
            if (Nova.voiceOn()) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (Player.current() === t) Nova.say("Ya han sonado todas tus canciones, las ${order(o).size}. Empezamos otra vuelta, en otro orden.") }, 4000)
            Nativo.onHome(); return
        }
        if (i >= 0 && i > p && i - p < 400) { x["pos"] = i; save(); Nativo.onHome() }
    }

    fun on(): Boolean = Player.source == "todo"

    fun startAll(fresh: Boolean = false) {
        if (fresh) { o = null; nextRound = null; loaded = true }
        val pl = pool()
        if (pl.size < 2) { Nova.say("Todavía tienes muy pocas canciones para esto. Añade música a la biblioteca y te las pongo todas."); return }
        val x = sync(); val ord = order(x); val n = ord.count { alive(it) }; val p = pos(x); val begun = p >= 0
        val ids = ArrayList<String>()
        var i = p + 1; while (i < ord.size && ids.size < 40) { if (alive(ord[i])) ids.add(ord[i]); i++ }
        if (ids.isEmpty()) { o = null; loaded = true; startAll(); return }
        val talk = if (begun) "Seguimos con todas tus canciones: van ${p + 1} de $n." else Cerebro.pickFresh(listOf(
            "Vamos con todas tus canciones: $n, sin repetir ninguna hasta que suenen todas. Las he ordenado para que una lleve a la otra.",
            "Todo tu Órbita: $n canciones, ninguna repetida. Te las pongo en un orden que fluye."))
        Player.playQueue(ids, 0, "todo", if (Nova.voiceOn()) talk to "saludo" else null)
        if (!Nova.voiceOn()) Nativo.toast(talk, 4200)
        Nativo.onHome()
    }

    fun stopAll() {
        if (Player.source != "todo") return
        Player.setSource("library"); Nativo.onHome()
        Nativo.toast("Todas tus canciones: en pausa. Cuando vuelvas, sigo por donde iba.", 3600)
    }

    /** Lo de la tarjeta de «Para ti hoy»: cuántas, cuántas van, cuál es la vuelta y las próximas. */
    class Card(val n: Int, val done: Int, val cycle: Int, val on: Boolean, val next: List<String>)
    fun card(): Card? {
        val pl = pool(); if (pl.size < 8) return null
        val x = st(); val n = if (x != null) total() else pl.size
        val done = if (x != null) max(0, min(n, pos(x) + 1)) else 0
        val on = on()
        val cur = Player.current()
        val nextIds = if (x != null) after(if (on && cur != null) cur.id else (order(x).getOrNull(pos(x)) ?: ""), 4) else Cerebro.shuffle(pl.map { it.id }).take(4)
        return Card(n, done, if (x != null) Json.l(x["cycle"]).toInt() else 1, on, nextIds)
    }
}
