package __PACKAGE__

import java.util.Calendar

/**
 * Fase 4, parte 2 · «Tu año en Órbita»: las cuentas del año (lo que en la web hacía «recapStats»), la tarjeta de «Tú» y
 * el aviso de «Ahora» en diciembre.
 */
object Recap {
    val MESES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    private val NOVA_SRC = setOf("daily", "mix", "ai", "nova", "radio")

    class Day(val ts: Long) { var sec = 0.0 }
    class Streak(val n: Int, val from: Long, val to: Long)
    class PerDay(val id: String, val ts: Long) { var n = 0 }
    class Stats(val y: Int, val sec: Double, val plays: Int, val radioSec: Double, val novaSec: Double, val tracks: List<Memoria.TrackStat>,
                val artists: List<Memoria.ArtistStat>, val months: DoubleArray, val hours: DoubleArray, val dows: DoubleArray, val nDays: Int,
                val streak: Streak, val bigDay: Day?, val obs: PerDay?, val first: Memoria.Ev?) {
        val nSongs get() = tracks.size
        val nArtists get() = artists.size
        val ok get() = plays >= 10 && sec >= 1800
    }

    /** El día (como número) de una hora: la medianoche de ese día, en días. */
    fun dayIdx(ts: Long): Long {
        val c = Calendar.getInstance(); c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return Math.round(c.timeInMillis / 864e5)
    }
    fun fmtDia(ts: Long): String { val c = Calendar.getInstance(); c.timeInMillis = ts; return "${c.get(Calendar.DAY_OF_MONTH)} de ${MESES[c.get(Calendar.MONTH)]}" }

    /** Un número como lo escribe el móvil en español: con punto de los miles a partir de 10.000. */
    fun nES(n: Double): String {
        val v = Math.round(n)
        if (Math.abs(v) < 10000) return v.toString()
        val s = Math.abs(v).toString(); val sb = StringBuilder()
        for (i in s.indices) { if (i > 0 && (s.length - i) % 3 == 0) sb.append('.'); sb.append(s[i]) }
        return (if (v < 0) "-" else "") + sb
    }

    fun hours(s: Double): Pair<String, String> {
        if (s >= 36000) return nES(s / 3600) to "horas"
        if (s >= 3600) { val h = Math.round(s / 360) / 10.0; val t = if (h == Math.floor(h)) h.toLong().toString() else h.toString().replace('.', ','); return t to (if (h == 1.0) "hora" else "horas") }
        val m = Math.max(1L, Math.round(s / 60)); return m.toString() to (if (m == 1L) "minuto" else "minutos")
    }
    fun hoursTxt(s: Double): String { val (a, b) = hours(s); return "$a $b" }

    private val cache = HashMap<String, Stats>()

    @Synchronized fun stats(y: Int): Stats {
        val key = "$y:${Memoria.events.size}:${Memoria.gen}"
        cache[key]?.let { return it }
        if (cache.size > 8) cache.clear()
        val ca = Calendar.getInstance(); ca.clear(); ca.set(y, 0, 1); val a = ca.timeInMillis
        val cb = Calendar.getInstance(); cb.clear(); cb.set(y + 1, 0, 1); val b = cb.timeInMillis
        val tr = LinkedHashMap<String, Memoria.TrackStat>(); val ar = LinkedHashMap<String, Memoria.ArtistStat>()
        val months = DoubleArray(12); val hours = DoubleArray(24); val dows = DoubleArray(7)
        val days = LinkedHashMap<Long, Day>(); val perDay = LinkedHashMap<String, PerDay>()
        var sec = 0.0; var plays = 0; var radioSec = 0.0; var novaSec = 0.0; var first: Memoria.Ev? = null
        val cal = Calendar.getInstance()
        synchronized(Memoria) {
            for (e in Memoria.events) {
                if (e.ts < a || e.ts >= b) continue
                cal.timeInMillis = e.ts
                sec += e.sec; months[cal.get(Calendar.MONTH)] += e.sec; hours[e.hour] += e.sec; dows[e.dow] += e.sec
                val di = dayIdx(e.ts); val dd = days.getOrPut(di) { Day(e.ts) }; dd.sec += e.sec
                if (e.src in NOVA_SRC) novaSec += e.sec
                val yt = e.id.startsWith("yt:")
                if (e.net && !yt) { radioSec += e.sec; continue }
                val full = e.sec >= 30 || e.pct >= 0.5; if (full) plays++
                if (first == null && full) first = e
                val r = tr.getOrPut(e.id) { Memoria.TrackStat(e.id, e.title, e.artist) }
                r.sec += e.sec
                if (full) { r.plays++; val pd = perDay.getOrPut("$di|${e.id}") { PerDay(e.id, e.ts) }; pd.n++ }
                val ak = Biblio.artistKey(e.artist)
                if (ak.isNotEmpty()) { val x = ar.getOrPut(ak) { Memoria.ArtistStat(ak, e.artist) }; x.sec += e.sec; if (full) x.plays++; x.songs.add(e.id) }
            }
        }
        val tracks = tr.values.filter { it.plays > 0 }.sortedWith(compareByDescending<Memoria.TrackStat> { it.plays }.thenByDescending { it.sec })
        val artists = ar.values.filter { it.plays > 0 }.sortedByDescending { it.sec }
        // la racha más larga de días seguidos, y el día con más música
        val keys = days.keys.sorted()
        var best = Streak(0, 0, 0); var run = 0; var start = 0L
        keys.forEachIndexed { i, k ->
            if (i > 0 && k == keys[i - 1] + 1) run++ else { run = 1; start = k }
            if (run > best.n) best = Streak(run, days[start]!!.ts, days[k]!!.ts)
        }
        var bigDay: Day? = null; for (d in days.values) if (bigDay == null || d.sec > bigDay.sec) bigDay = d
        var obs: PerDay? = null; for (pd in perDay.values) if (pd.n >= 3 && (obs == null || pd.n > obs.n)) obs = pd
        val out = Stats(y, sec, plays, radioSec, novaSec, tracks, artists, months, hours, dows, days.size, best, bigDay, obs, first)
        cache[key] = out
        return out
    }

    /** Qué año se enseña: este, o el anterior durante las primeras semanas de enero. */
    fun year(): Int {
        val c = Calendar.getInstance(); val y = c.get(Calendar.YEAR)
        return if (c.get(Calendar.MONTH) == 0 && c.get(Calendar.DAY_OF_MONTH) <= 20 && stats(y - 1).ok) y - 1 else y
    }

    /** El aviso de «Ahora» («Tu año en Órbita ya está listo»): en diciembre y a primeros de enero, una vez por año. */
    fun hint(): Pair<String, String>? {
        val c = Calendar.getInstance(); val y = year(); val s = stats(y)
        val season = c.get(Calendar.MONTH) == 11 || (c.get(Calendar.MONTH) == 0 && c.get(Calendar.DAY_OF_MONTH) <= 20)
        if (!(season && s.ok && Prefs.num("recapSeen", 0.0).toInt() != y && Prefs.num("recapHintOff", 0.0).toInt() != y)) return null
        return "Tu $y en Órbita ya está listo" to "${hoursTxt(s.sec)} de música, tu canción del año y mucho más. ${Nova.name()} te lo cuenta."
    }

    /** «openRecap»: el resumen del año, en su ventana. */
    fun open(y0: Int) {
        val y = if (y0 > 0) y0 else year()
        val s = stats(y)
        if (!s.ok) { Ordenes.say("Todavía no tengo bastante para tu resumen de $y. Escucha un poco más y te lo preparo."); return }
        Prefs.set("recapSeen", y)
        showY = y
        Ventanas.open("recap")
        Nativo.onHome()
    }
    /** El año que está abierto en la ventana. */
    var showY = 0
}
