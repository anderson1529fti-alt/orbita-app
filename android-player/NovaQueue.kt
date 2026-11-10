package __PACKAGE__

import org.json.JSONArray
import org.json.JSONObject

/**
 * 4.2 · La cola del reproductor, en Kotlin. Es la misma lógica que tenía NovaSession.java (3.5 b3 – 4.1): la canción
 * que suena, las que vienen (con sus fundidos), repetir y las vueltas de la lista. No usa nada de Android, para poder
 * probarla sola.
 */
class PItem {
    var src = ""
    var id = ""            // 4.2: la canción de la app (para reconocerla al volver a abrir Órbita)
    var title = ""
    var artist = ""
    var album = ""
    var dur = 0.0
    var xf = 0.0
    var at = 0.0
    var st = 0.0
    var swap = -1.0
    var rate = 1.0
    var gridA = 0.0
    var beatA = 0.0
    var gridB = 0.0
    var beatB = 0.0
    var style = 0
    var g = Float.NaN
    /** Único para cada canción de la cola (la misma canción dos veces son dos): lo usa el aviso de Android. */
    var uid: Long = nextUid()

    fun engineItem(): NovaAudioEngine.Item {
        val it = NovaAudioEngine.Item(src, xf, at, st, g)
        it.style = style; it.swap = swap; it.rate = rate
        it.gridA = gridA; it.beatA = beatA; it.gridB = gridB; it.beatB = beatB
        return it
    }

    /** Otra igual, con su propio uid (para repetir la lista). */
    fun copy(): PItem {
        val o = PItem()
        o.src = src; o.id = id; o.title = title; o.artist = artist; o.album = album
        o.dur = dur; o.xf = xf; o.at = at; o.st = st; o.swap = swap; o.rate = rate
        o.gridA = gridA; o.beatA = beatA; o.gridB = gridB; o.beatB = beatB; o.style = style; o.g = g
        return o
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("src", src); o.put("id", id); o.put("title", title); o.put("artist", artist); o.put("album", album)
        o.put("dur", dur); o.put("xf", xf); o.put("at", at); o.put("st", st); o.put("style", style)
        if (!g.isNaN()) o.put("g", g.toDouble())
        if (style == NovaAudioEngine.Item.DJ) { o.put("swap", swap); o.put("rate", rate); o.put("gridA", gridA); o.put("gridB", gridB); o.put("beatB", beatB) }
        if (beatA != 0.0) o.put("beatA", beatA)
        return o
    }

    companion object {
        private var serial = 0L
        @Synchronized private fun nextUid(): Long = ++serial

        /** Lo que manda la app (o lo guardado): mismas reglas y límites que en la 4.1. */
        fun fromJson(j: JSONObject): PItem? {
            val it = PItem()
            it.src = j.optString("src", "")
            if (it.src.isEmpty()) return null
            it.id = j.optString("id", "")
            it.title = j.optString("title", ""); it.artist = j.optString("artist", ""); it.album = j.optString("album", "")
            it.dur = num(j, "dur", 0.0); it.xf = num(j, "xf", 0.0); it.at = num(j, "at", 0.0); it.st = num(j, "st", 0.0)
            val g = num(j, "g", Double.NaN)
            it.g = if (g.isNaN()) Float.NaN else g.toFloat()
            it.style = j.optInt("style", 0).coerceIn(0, 3)   // 3 = eco (4.1)
            it.swap = num(j, "swap", -1.0); it.rate = num(j, "rate", 1.0).coerceIn(0.85, 1.15)
            it.gridA = num(j, "gridA", 0.0); it.beatA = num(j, "beatA", 0.0); it.gridB = num(j, "gridB", 0.0); it.beatB = num(j, "beatB", 0.0)
            return it
        }

        private fun num(j: JSONObject, k: String, d: Double): Double {
            val v = j.opt(k)
            return if (v is Number) v.toDouble() else d
        }
    }
}

class NovaQueue {
    companion object {
        const val REP_OFF = 0
        const val REP_ONE = 1
        const val REP_ALL = 2
        fun repeatOf(s: String?): Int = when (s) { "one" -> REP_ONE; "all" -> REP_ALL; else -> REP_OFF }
        fun repeatName(r: Int): String = when (r) { REP_ONE -> "one"; REP_ALL -> "all"; else -> "off" }


        /** Lo guardado (null si no hay nada que poner). */
        fun parse(o: JSONObject?): Saved? {
            if (o == null) return null
            val a = o.optJSONArray("items") ?: return null
            val list = ArrayList<PItem>()
            var idx = o.optInt("index", 0)
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i)?.let { PItem.fromJson(it) }
                if (p == null) { if (i < idx) idx--; continue }
                list.add(p)
            }
            if (list.isEmpty()) return null
            val pos = o.optDouble("pos", 0.0)
            return Saved(list, idx.coerceIn(0, list.size - 1), repeatOf(o.optString("repeat", "off")), o.optInt("cycle", 0), if (pos.isNaN() || pos < 0) 0.0 else pos)
        }
    }

    /** Lo guardado: la lista, cuál sonaba y por dónde iba. */
    class Saved(val items: List<PItem>, val index: Int, val repeat: Int, val cycle: Int, val pos: Double)

    val items = ArrayList<PItem>()
    var index = -1
    var slot = 0
    /** Repetir (no, esta, toda la lista) y cuántas canciones forman una vuelta de la lista (0: no se sabe). */
    var repeat = REP_OFF
    var cycle = 0

    @Synchronized fun item(i: Int): PItem? = if (i >= 0 && i < items.size) items[i] else null
    @Synchronized fun now(): PItem? = item(index)
    @Synchronized fun isEmpty(): Boolean = items.isEmpty() || index < 0

    @Synchronized fun set(list: List<PItem>, idx: Int, slot: Int, repeat: Int, cycle: Int) {
        val old = ArrayList(items)
        val oldIdx = index
        items.clear(); items.addAll(list)
        this.index = if (list.isEmpty()) -1 else idx.coerceIn(0, list.size - 1)
        // las que ya estaban (en el mismo sitio respecto a la que suena) siguen siendo las mismas para Android: la app
        // vuelve a mandar la cola a menudo y el aviso no tiene por qué ver una cola nueva cada vez
        if (oldIdx >= 0 && index >= 0 && this.slot == slot) {
            // dónde estaba antes la que suena ahora (la más cercana a la que sonaba)
            val cur = list[index].src
            var at = -1
            for (j in old.indices) if (old[j].src == cur && (at < 0 || Math.abs(j - oldIdx) < Math.abs(at - oldIdx))) at = j
            if (at >= 0) {
                val shift = at - index
                for (k in list.indices) { val o = old.getOrNull(k + shift); if (o != null && o.src == list[k].src) list[k].uid = o.uid }
            }
        }
        this.slot = slot; this.repeat = repeat; this.cycle = maxOf(0, cycle)
    }

    @Synchronized fun clear() { items.clear(); index = -1 }

    /** La cola desde la canción i (las siguientes, para el motor). */
    @Synchronized fun after(i: Int): List<NovaAudioEngine.Item> {
        val out = ArrayList<NovaAudioEngine.Item>()
        if (repeat == REP_ONE) {
            // repetir esta: la misma otra vez, pegada y desde el principio
            val it = item(i)
            if (it != null) out.add(NovaAudioEngine.Item(it.src, 0.0, 0.0, 0.0, it.g))
            return out
        }
        for (k in i + 1 until items.size) out.add(items[k].engineItem())
        return out
    }

    /**
     * Repetir toda la lista: cuando quedan pocas, otra vuelta al final (la app manda la lista entera desde la que
     * suena). Así sigue sonando aunque la app no despierte. Devuelve si ha cambiado.
     */
    @Synchronized fun extend(): Boolean {
        if (repeat != REP_ALL || cycle <= 0 || cycle > items.size || index < items.size - 3) return false
        val again = items.subList(items.size - cycle, items.size).map { it.copy() }
        items.addAll(again)
        // las de hace mucho ya no hacen falta (solo unas pocas, para «anterior»)
        if (index > 60) {
            val cut = index - 3
            items.subList(0, cut).clear()
            index -= cut
        }
        return true
    }

    /** El motor ha pasado solo a otra canción de la cola (o un botón lo ha hecho): se busca en la cola. */
    @Synchronized fun onSource(slot: Int, src: String?): Boolean {
        if (slot != this.slot || src == null) return false
        var found = -1
        val here = item(index)
        if (repeat == REP_ONE && here != null && src == here.src) found = index
        var k = maxOf(0, index + 1)
        while (k < items.size && found < 0) { if (src == items[k].src) found = k; k++ }
        k = 0
        while (k < items.size && found < 0) { if (src == items[k].src) found = k; k++ }
        if (found >= 0) index = found
        return found >= 0
    }

    // ---------------------------------------------------------------- guardar y recuperar
    @Synchronized fun toJson(pos: Double): JSONObject {
        val o = JSONObject()
        o.put("v", 1); o.put("index", index); o.put("slot", slot); o.put("repeat", repeatName(repeat)); o.put("cycle", cycle)
        o.put("pos", pos); o.put("ts", System.currentTimeMillis())
        val a = JSONArray()
        for (it in items) a.put(it.toJson())
        o.put("items", a)
        return o
    }
}
