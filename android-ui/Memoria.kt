package __PACKAGE__

import java.util.Calendar

/**
 * Fase 4, parte 2 · La memoria de lo que escuchas (lo de «Listening memory» y «Nova's memory» de la parte web): cada
 * escucha (cuánto sonó, si se saltó, a qué hora, de dónde venía) se guarda igual que en la web, y de ahí salen «Tú»,
 * «Tu año» y lo que Nova aprende.
 */
object Memoria {
    /** Una escucha (los mismos campos que «makeEvent»). */
    class Ev(val ts: Long, val id: String, val sec: Double, val pct: Double, val skip: Boolean, val hour: Int, val dow: Int, val src: String,
             val title: String, val artist: String, val net: Boolean) {
        fun toMap(): Map<String, Any?> = linkedMapOf("ts" to ts, "id" to id, "sec" to Math.round(sec), "pct" to pct, "skip" to skip, "hour" to hour, "dow" to dow,
            "src" to src, "title" to title, "artist" to artist, "net" to net)
        companion object {
            fun from(m: Map<String, Any?>): Ev? {
                val ts = Json.l(m["ts"]); val id = Json.s(m["id"]); if (ts == 0L || id.isEmpty()) return null
                return Ev(ts, id, Json.d(m["sec"]), Json.d(m["pct"]), Json.b(m["skip"]), Json.l(m["hour"]).toInt(), Json.l(m["dow"]).toInt(), Json.s(m["src"]),
                    Json.s(m["title"]), Json.s(m["artist"]), Json.b(m["net"]))
            }
        }
    }

    val events = ArrayList<Ev>()
    @Volatile var gen = 0; private set
    fun on(): Boolean = Prefs.bool("memOn", true)

    /** «loadMemory»: todas las escuchas guardadas, de la más antigua a la más nueva. */
    @Synchronized fun load() {
        events.clear()
        for ((_, v) in Store.events()) { val e = Json.obj(Json.parseOr(v, null))?.let { Ev.from(it) } ?: continue; events.add(e) }
        events.sortBy { it.ts }
        if (events.size > 30000) events.subList(0, events.size - 25000).clear()
        gen++
    }

    /** «makeEvent». */
    fun makeEvent(id: String, acc: Double, manual: Boolean, title: String, artist: String, dur: Double, net: Boolean, src: String, ts: Long): Ev {
        val c = Calendar.getInstance(); c.timeInMillis = ts
        val pct = if (dur > 0) minOf(1.0, acc / dur) else 0.0
        return Ev(ts, id, Math.round(acc).toDouble(), Math.round(pct * 100) / 100.0, manual && !net && pct < 0.4 && acc < 60, c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.DAY_OF_WEEK) - 1, src, title, artist, net)
    }

    /** «recordEvent»: se guarda, se suma a la canción y se lo cuenta a Nova. */
    fun record(ev: Ev) {
        if (!on()) return
        synchronized(this) { events.add(ev); if (events.size > 30000) events.subList(0, events.size - 25000).clear(); gen++ }
        try { Store.addEvent(Json.str(ev.toMap())) } catch (ignored: Throwable) { }
        val t = Lib.byId(ev.id)
        if (t != null && !t.net) {
            t.listenSec += ev.sec
            if (ev.skip) t.skips += 1
            if (ev.sec >= 30) { val h = if (t.hours?.size == 24) t.hours!! else MutableList(24) { 0.0 }; h[ev.hour] = h[ev.hour] + 1; t.hours = h }
            Biblio.save(t)
        }
        Nativo.onMemory()
        Nova.onListen(ev)
    }

    fun onLike(t: Track) { Nova.onLike(t) }

    // ---------------------------------------------------------------- lo que se cuenta («memStats»)
    fun dayKey(ts: Long = System.currentTimeMillis()): String { val c = Calendar.getInstance(); c.timeInMillis = ts; return "${c.get(Calendar.YEAR)}-${Momento.pad(c.get(Calendar.MONTH) + 1)}-${Momento.pad(c.get(Calendar.DAY_OF_MONTH))}" }

    class TrackStat(val id: String, val title: String, val artist: String) { var sec = 0.0; var plays = 0; var skips = 0; var last = 0L }
    class ArtistStat(val key: String, val name: String) { var sec = 0.0; var plays = 0; val songs = HashSet<String>() }
    class Stats(val n: Int, val sec: Double, val plays: Int, val skips: Int, val radioSec: Double, val tracks: List<TrackStat>, val artists: List<ArtistStat>,
                val hours: DoubleArray, val dows: DoubleArray, val days: Int, val first: Long, val all: List<TrackStat>)

    private val statCache = HashMap<String, Stats>()
    @Synchronized fun stats(days: Int): Stats {
        val ck = "$days:${events.size}:$gen"
        statCache[ck]?.let { return it }
        if (statCache.size > 20) statCache.clear()
        val from = if (days > 0) System.currentTimeMillis() - days * 864e5.toLong() else 0L
        val tr = LinkedHashMap<String, TrackStat>(); val ar = LinkedHashMap<String, ArtistStat>()
        val hours = DoubleArray(24); val dows = DoubleArray(7); val dset = HashSet<String>()
        var sec = 0.0; var plays = 0; var skips = 0; var n = 0; var radioSec = 0.0; var first = System.currentTimeMillis()
        for (i in events.indices.reversed()) {
            val e = events[i]; if (e.ts < from) break
            n++; sec += e.sec; hours[e.hour] += e.sec; dows[e.dow] += e.sec; dset.add(dayKey(e.ts)); first = e.ts
            if (e.net) { radioSec += e.sec; continue }
            val full = e.sec >= 30 || e.pct >= 0.5; if (full) plays++; if (e.skip) skips++
            val r = tr.getOrPut(e.id) { TrackStat(e.id, e.title, e.artist).also { it.last = e.ts } }
            r.sec += e.sec; if (full) r.plays++; if (e.skip) r.skips++
            val k = Biblio.artistKey(e.artist)
            if (k.isNotEmpty()) { val a = ar.getOrPut(k) { ArtistStat(k, e.artist) }; a.sec += e.sec; if (full) a.plays++; a.songs.add(e.id) }
        }
        val tracks = tr.values.filter { it.plays > 0 || it.sec >= 10 }.sortedWith(compareByDescending<TrackStat> { it.plays }.thenByDescending { it.sec })
        val artists = ar.values.sortedByDescending { it.sec }
        val out = Stats(n, sec, plays, skips, radioSec, tracks, artists, hours, dows, dset.size, first, tr.values.toList())
        statCache[ck] = out
        return out
    }

    @Synchronized fun streakDays(): Int {
        val days = events.map { dayKey(it.ts) }.toHashSet()
        val c = Calendar.getInstance(); var n = 0
        if (dayKey(c.timeInMillis) !in days) c.add(Calendar.DAY_OF_MONTH, -1)
        while (dayKey(c.timeInMillis) in days) { n++; c.add(Calendar.DAY_OF_MONTH, -1) }
        return n
    }

    fun skipProne(t: Track): Boolean = t.skips >= 2 && t.skips > t.plays * 0.5
}
