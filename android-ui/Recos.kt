package __PACKAGE__

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Fase 4, parte 2 · «Descubre» (lo que en la web era «4.1 · Recommendations»): canciones que no tienes y que
 * probablemente te gustarían. De lo que escuchas, Nova toma tus artistas, le pregunta a Deezer cuáles se les parecen y
 * elige sus mejores canciones con una muestra de 30 segundos. Sin claves ni cuentas: solo salen del móvil los nombres
 * de los artistas.
 */
object Recos {
    private const val DZ = "https://api.deezer.com"
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    @Volatile var busy = false; private set
    var err = ""; private set
    private var tried = 0L

    fun on(): Boolean = Prefs.bool("recOn", true)
    fun nova(): Boolean = Prefs.bool("recNova", true)
    fun list(): MutableList<MutableMap<String, Any?>> = (Json.list(Prefs.get("recList")) ?: emptyList()).mapNotNull { Json.obj(it)?.let { m -> LinkedHashMap(m) } }.toMutableList()
    private fun saveList(l: List<Map<String, Any?>>) = Prefs.set("recList", l)
    fun count(): Int = list().size
    private fun mapOf2(k: String): MutableMap<String, Any?> = LinkedHashMap(Json.obj(Prefs.get(k)) ?: emptyMap())

    fun cleanTitle(s: String): String = s.replace(Regex("\\s*[(\\[](?:feat|ft|con|with|official|oficial|video|v[ií]deo|audio|lyrics?|letra|remaster|hd|hq|4k|live|en vivo|visualizer)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+-\\s+(?:remaster|live|en vivo|official).*$", RegexOption.IGNORE_CASE), "").trim()
    fun recKey(title: String, artist: String): String = Biblio.norm(cleanTitle(title)) + "|" + Biblio.artistKey(artist)
    /** («inLibrary») si ya la tienes. */
    fun inLibrary(title: String, artist: String): Track? {
        val nt = Biblio.norm(title); val na = Biblio.norm(artist)
        return Lib.tracks.firstOrNull { Biblio.norm(it.title) == nt && (na.isEmpty() || Biblio.norm(it.artist).contains(na) || na.contains(Biblio.norm(it.artist))) } ?: Lib.tracks.firstOrNull { Biblio.norm(it.title) == nt }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val seen = mapOf2("recSeen"); seen.entries.removeAll { now - Json.d(it.value) > 45 * 864e5 }; Prefs.set("recSeen", seen)
        val ban = mapOf2("recBanA"); ban.entries.removeAll { now - Json.d(it.value) > 120 * 864e5 }; Prefs.set("recBanA", ban)
    }

    private class Prof(val key: String, val name: String, val w: Double)
    /** Tus artistas, por lo que te importan. */
    private fun profile(): List<Prof> {
        val st = Memoria.stats(60)
        val arts = st.artists.filter { it.name.isNotEmpty() && it.key.isNotEmpty() && !Regex("demo", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.take(30)
        val likes = HashMap<String, Int>()
        for (t in Cerebro.pool(free = true)) if (Biblio.liked(t) && !t.demo) { val k = Biblio.artistKey(t.artist); if (k.isNotEmpty()) likes[k] = (likes[k] ?: 0) + 1 }
        val out = LinkedHashMap<String, Prof>(); val mx = arts.firstOrNull()?.sec?.takeIf { it > 0 } ?: 1.0
        for (a in arts) out[a.key] = Prof(a.key, a.name, a.sec / mx + 0.25 * minOf(3, likes[a.key] ?: 0))
        // (aún pocas escuchas: los artistas con más canciones en tu biblioteca)
        if (out.size < 4) {
            val cnt = LinkedHashMap<String, Pair<String, Int>>()
            for (t in Cerebro.pool(free = true)) { if (t.demo) continue; val k = Biblio.artistKey(t.artist); if (k.isNotEmpty() && t.artist.isNotEmpty()) cnt[k] = (cnt[k]?.first ?: t.artist) to ((cnt[k]?.second ?: 0) + 1) }
            val top = cnt.entries.sortedByDescending { it.value.second }.take(12); val m2 = top.firstOrNull()?.value?.second ?: 1
            for (c in top) if (!out.containsKey(c.key)) out[c.key] = Prof(c.key, c.value.first, 0.4 * c.value.second / m2 + 0.25 * minOf(3, likes[c.key] ?: 0))
        }
        // y los artistas nuevos que te gustaron de las recomendaciones
        for ((k, g0) in mapOf2("recGood")) { val g = Json.obj(g0) ?: continue; val n = Json.s(g["name"]); if (n.isNotEmpty() && !out.containsKey(k)) out[k] = Prof(k, n, minOf(0.8, 0.3 * Json.d(g["n"], 1.0))) }
        return out.values.sortedByDescending { it.w }
    }

    private fun dzArtistId(name: String): Long {
        val cache = mapOf2("dzArt"); val k = Biblio.norm(name)
        if (cache.containsKey(k)) return Json.l(cache[k])
        val j = Json.obj(Online.getJson("$DZ/search/artist?q=${Online.enc(name)}&limit=5"))
        val list = (Json.list(j?.get("data")) ?: emptyList()).mapNotNull { Json.obj(it) }
        val hit = list.firstOrNull { Biblio.norm(Json.s(it["name"])) == k } ?: list.firstOrNull { val n = Biblio.norm(Json.s(it["name"])); n.length >= 3 && (n.contains(k) || k.contains(n)) }
        cache[k] = if (hit != null) Json.l(hit["id"]) else 0L
        if (cache.size > 400) { val drop = cache.keys.take(cache.size - 400); for (x in drop) cache.remove(x) }
        Prefs.set("dzArt", cache)
        return Json.l(cache[k])
    }

    /** «recRefresh»: busca las recomendaciones del día (si hace falta, o si se pide). */
    fun refresh(force: Boolean, done: ((Boolean) -> Unit)? = null) {
        if (busy || !Online.on() || !on()) { done?.invoke(false); return }
        if (!force && count() >= 4 && System.currentTimeMillis() - Prefs.num("recAt", 0.0) < 20 * 3600e3) { done?.invoke(false); return }
        val prof = profile()
        if (prof.isEmpty()) { err = "Escucha unas cuantas canciones y te recomiendo otras parecidas."; Inicio.mark(); done?.invoke(false); return }
        busy = true; err = ""; Inicio.mark()
        prune()
        net.execute {
            var ok = false
            try {
                // las semillas: tus 4 artistas más importantes y 2 más al azar (cada día, otro ángulo)
                val seeds = prof.take(4) + prof.drop(4).take(10).shuffled().take(2)
                val lib = Cerebro.pool(free = true).map { Biblio.artistKey(it.artist) }.toHashSet()
                val ban = mapOf2("recBanA"); val good = mapOf2("recGood"); val seen = mapOf2("recSeen")
                class C(val id: Long, val name: String, val key: String, var s: Double, val from: MutableList<String>, val known: Boolean) { var own = false }
                val cand = LinkedHashMap<Long, C>()
                for (sd in seeds) {
                    val id = try { dzArtistId(sd.name) } catch (t: Throwable) { 0L }
                    if (id == 0L) continue
                    val rel = try { Json.obj(Online.getJson("$DZ/artist/$id/related?limit=20")) } catch (t: Throwable) { null }
                    (Json.list(rel?.get("data")) ?: emptyList()).mapNotNull { Json.obj(it) }.forEachIndexed { i, a ->
                        val k = Biblio.artistKey(Json.s(a["name"])); if (k.isEmpty() || ban.containsKey(k)) return@forEachIndexed
                        val aid = Json.l(a["id"])
                        val c = cand.getOrPut(aid) { C(aid, Json.s(a["name"]), k, 0.0, ArrayList(), k in lib) }
                        c.s += sd.w * (1 - i / 24.0); if (sd.name !in c.from) c.from.add(sd.name)
                    }
                    // y canciones de ese mismo artista que aún no tienes
                    val own = cand.getOrPut(id) { C(id, sd.name, sd.key, 0.0, mutableListOf(sd.name), true) }
                    own.own = true; own.s += sd.w * 0.3
                }
                val arts = cand.values.map { c -> c.s *= (1 + 0.45 * (c.from.size - 1)) * (if (c.known && !c.own) 0.75 else 1.0) * (if (good.containsKey(c.key)) 1.3 else 1.0); c }.sortedByDescending { it.s }.take(16)
                val out = ArrayList<Map<String, Any?>>()
                for (a in arts) {
                    if (out.size >= 12) break
                    val top = try { Json.obj(Online.getJson("$DZ/artist/${a.id}/top?limit=10")) } catch (t: Throwable) { null }
                    val songs = (Json.list(top?.get("data")) ?: emptyList()).mapNotNull { Json.obj(it) }.filter { x ->
                        val title = Json.s(x["title_short"]).ifEmpty { Json.s(x["title"]) }; val artist = Json.s(Json.obj(x["artist"])?.get("name")).ifEmpty { a.name }
                        Json.s(x["preview"]).isNotEmpty() && x["readable"] != false && inLibrary(title, artist) == null && !seen.containsKey(recKey(title, artist)) && out.none { o -> o["key"] == recKey(title, artist) }
                    }
                    for (x in songs.take(5).shuffled().take(if (a.own) 1 else 2)) {
                        val artist = Json.s(Json.obj(x["artist"])?.get("name")).ifEmpty { a.name }; val title = Json.s(x["title_short"]).ifEmpty { Json.s(x["title"]) }
                        val al = Json.obj(x["album"])
                        out.add(linkedMapOf("id" to "dz:" + Json.l(x["id"]), "key" to recKey(title, artist), "title" to title, "artist" to artist, "album" to Json.s(al?.get("title")),
                            "art" to Json.s(al?.get("cover_medium")).ifEmpty { Json.s(al?.get("cover")) }, "artBig" to Json.s(al?.get("cover_xl")).ifEmpty { Json.s(al?.get("cover_big")) },
                            "url" to Json.s(x["preview"]), "full" to Json.d(x["duration"]), "link" to Json.s(x["link"]), "akey" to a.key, "from" to a.from.take(3), "own" to a.own, "s" to a.s,
                            "why" to (if (a.own) "De ${a.name}, que escuchas mucho: esta aún no la tienes" else if (a.from.size > 1) "Porque escuchas ${a.from.take(2).joinToString(" y ")}" else "Si te gusta ${a.from[0]}")))
                    }
                }
                if (out.isNotEmpty()) { saveList(out); Prefs.set("recAt", System.currentTimeMillis()); ok = true }
                else err = "Hoy no he encontrado nada nuevo que encaje contigo. Mañana lo vuelvo a intentar."
            } catch (t: Throwable) { err = "No he podido buscar recomendaciones ahora. Lo intento más tarde." }
            main.post { busy = false; Inicio.mark(); done?.invoke(ok || count() > 0) }
        }
    }

    /** La canción de internet de una recomendación (su muestra de 30 segundos). */
    fun track(r: Map<String, Any?>): Track {
        val id = Json.s(r["id"])
        val t = Lib.net[id] ?: Track(id).also { Lib.net[id] = it }
        t.net = true; t.kind = "preview"; t.title = Json.s(r["title"]); t.artist = Json.s(r["artist"]); t.album = Json.s(r["album"])
        t.art = Json.s(r["art"]); t.artBig = Json.s(r["artBig"]); t.url = Json.s(r["url"]); t.duration = 30.0
        t.urlAt = Json.l(r["uat"]).takeIf { it > 0 } ?: Prefs.num("recAt", 1.0).toLong()
        t.extra["full"] = Json.d(r["full"]); t.extra["link"] = Json.s(r["link"]); t.extra["rec"] = true
        return t
    }

    /** La dirección de una muestra caduca (Deezer dice cuándo: exp=…): si no está fresca, se pide otra justo antes. */
    fun sampleUrl(t: Track, force: Boolean): String {
        val m = Regex("exp=(\\d{9,11})").find(t.url)
        val stale = if (m != null) m.groupValues[1].toLong() * 1000 - System.currentTimeMillis() < 90e3 else t.id.startsWith("dz:") && System.currentTimeMillis() - t.urlAt > 10 * 60e3
        if (!force && !stale) return t.url
        val u = try {
            if (t.id.startsWith("dz:")) Json.s(Json.obj(Online.getJson("$DZ/track/${Online.enc(t.id.substring(3))}"))?.get("preview"))
            else if (t.id.startsWith("it:")) Json.s(Json.list(Json.obj(Online.getJson("https://itunes.apple.com/lookup?id=${Online.enc(t.id.substring(3))}"))?.get("results"))?.firstOrNull()?.let { Json.obj(it) }?.get("previewUrl"))
            else ""
        } catch (e: Throwable) { Nativo.note("muestra: no pude pedir su dirección nueva (${e.message})"); "" }
        if (u.isEmpty()) return t.url
        t.url = u; t.urlAt = System.currentTimeMillis()
        val l = list(); val r = l.firstOrNull { Json.s(it["id"]) == t.id }; if (r != null) { r["url"] = u; r["uat"] = t.urlAt; saveList(l) }
        return u
    }

    private fun shown() {
        val now = System.currentTimeMillis(); val seen = mapOf2("recSeen"); var ch = false
        for (r in list().take(5)) { val k = Json.s(r["key"]); if (!seen.containsKey(k)) { seen[k] = now; ch = true } }
        if (ch) Prefs.set("recSeen", seen)
    }

    /** Una muestra: ahora (lo demás sigue después) o, si no suena nada, todas las muestras. */
    fun play(i: Int, next: Boolean = false) {
        val l = list(); val r = l.getOrNull(i) ?: return
        val t = track(r)
        if (Player.current() == null || Player.queue.isEmpty()) { Player.playQueue(l.map { track(it).id }, i, "online"); return }
        if (next) { Player.playNext(t.id); Nativo.toast("Después de esta: «${t.title}» (muestra de 30 s)"); return }
        Player.playNext(t.id); Player.next(true)
    }
    fun like(i: Int) {
        val l = list(); val r = l.getOrNull(i) ?: return
        Deseos.add(Json.s(r["title"]), Json.s(r["artist"]), Json.s(r["why"]))
        val good = mapOf2("recGood"); val ak = Json.s(r["akey"])
        val g = LinkedHashMap(Json.obj(good[ak]) ?: mapOf("name" to Json.s(r["artist"]), "n" to 0)); g["n"] = Json.d(g["n"]) + 1; good[ak] = g; Prefs.set("recGood", good)
        r["liked"] = true; saveList(l)
        Nativo.toast("«${Json.s(r["title"])}» guardada en Por conseguir. Te buscaré más como esta.", 3600); Nativo.haptic(longArrayOf(0, 8)); Inicio.mark()
    }
    fun no(i: Int) {
        val l = list(); val r = l.getOrNull(i) ?: return
        val ban = mapOf2("recBanA"); ban[Json.s(r["akey"])] = System.currentTimeMillis(); Prefs.set("recBanA", ban)
        saveList(l.filter { Json.s(it["akey"]) != Json.s(r["akey"]) })
        Nativo.toast("Vale: no te recomiendo más de ${Json.s(r["artist"])}.", 3200); Nativo.haptic(longArrayOf(0, 6)); Inicio.mark()
    }
    fun yt(i: Int) { val r = list().getOrNull(i) ?: return; Tubo.find("${Json.s(r["artist"])} ${Json.s(r["title"])}") }
    /** Lo que se hizo con una muestra cuenta: oída hasta el final, probablemente gustó. */
    fun heard(t: Track?, sec: Double) {
        if (t == null || t.extra["rec"] != true || sec < 24) return
        val l = list(); val r = l.firstOrNull { Json.s(it["id"]) == t.id } ?: return
        if (Json.b(r["heard"])) return
        r["heard"] = true
        val good = mapOf2("recGood"); val ak = Json.s(r["akey"])
        val g = LinkedHashMap(Json.obj(good[ak]) ?: mapOf("name" to Json.s(r["artist"]), "n" to 0)); g["n"] = Json.d(g["n"]) + 0.5; good[ak] = g; Prefs.set("recGood", good)
        saveList(l)
    }

    /** La tarjeta de «Para ti hoy» (null si no va). */
    fun card(k: (() -> Unit) -> Int): JSONObject? {
        if (!on() || !Online.on()) return null
        val l = list()
        // (de vez en cuando, las de hoy)
        if (!busy && System.currentTimeMillis() - Prefs.num("recAt", 0.0) > 20 * 3600e3 && System.currentTimeMillis() - tried > 30 * 60000) { tried = System.currentTimeMillis(); main.post { refresh(false) } }
        fun note(t: String) = JSONObject().put("e", "note").put("t", t)
        fun eye(t: String) = JSONObject().put("e", "eye").put("t", t)
        if (l.isEmpty()) {
            if (busy) return JSONObject().put("daily", 0).put("grid", JSONObject.NULL).put("xl", JSONObject.NULL).put("body", JSONArray().put(eye("Descubre")).put(note("Buscando canciones nuevas para ti…")))
            if (err.isNotEmpty()) return JSONObject().put("daily", 0).put("grid", JSONObject.NULL).put("xl", JSONObject.NULL).put("body", JSONArray().put(eye("Descubre")).put(note(err)))
            return null
        }
        shown()
        val rows = JSONArray()
        l.take(5).forEachIndexed { i, r ->
            val t = track(r)
            val acts = JSONArray()
                .put(JSONObject().put("t", if (Json.b(r["liked"])) "♥" else "♡").put("on", if (Json.b(r["liked"])) 1 else 0).put("k", k { like(i) }).put("l", "Me gusta: guardarla en Por conseguir"))
                .put(JSONObject().put("t", "✕").put("on", 0).put("k", k { no(i) }).put("l", "No es para mí: no recomendar más de ${Json.s(r["artist"])}"))
                .put(JSONObject().put("t", "yt").put("on", 0).put("k", k { yt(i) }).put("l", "Ver en YouTube"))
            rows.put(JSONObject().put("c", if (t.art.isNotEmpty()) JSONObject().put("u", t.id) else Inicio.cover(t)).put("t", t.title).put("s", "${t.artist} · ${Json.s(r["why"])}").put("k", k { play(i) })
                .put("play", if (Player.current()?.id == t.id) 1 else 0).put("liked", if (Json.b(r["liked"])) 1 else 0).put("acts", acts))
        }
        val bb = JSONArray()
            .put(JSONObject().put("t", "Escuchar las muestras").put("a", 0).put("sm", 1).put("p", 1).put("i", 1).put("d", 0).put("on", 0).put("k", k { Player.playQueue(list().map { track(it).id }, 0, "online") }))
            .put(JSONObject().put("t", if (busy) "Buscando…" else "Otras").put("a", 0).put("sm", 1).put("p", 0).put("i", 0).put("d", if (busy) 1 else 0).put("on", 0).put("k", k { refresh(true) }))
        return JSONObject().put("daily", 0).put("grid", JSONObject.NULL).put("xl", JSONObject.NULL)
            .put("body", JSONArray().put(eye("Descubre · muestras de 30 s")).put(JSONObject().put("e", "h3").put("t", "Podrían gustarte")).put(JSONObject().put("e", "mini").put("rows", rows)).put(JSONObject().put("e", "btns").put("b", bb)))
    }

    /** Nova propone una de vez en cuando: «has escuchado mucho a X; esta podría gustarte. ¿Te pongo 30 segundos?» */
    fun maybeOffer(t: Track) {
        if (!on() || !nova() || !Online.on() || t.net || !Nativo.awake) return
        val l = list(); if (l.isEmpty()) return
        if (System.currentTimeMillis() - Prefs.num("recOfferAt", 0.0) < 4 * 3600e3) return
        val session = synchronized(Memoria) { Memoria.events.filter { System.currentTimeMillis() - it.ts < 90 * 60000 }.sumOf { it.sec } }
        if (session < 15 * 60 || Math.random() > 0.35) return
        val r = l.firstOrNull { !Json.b(it["liked"]) && !Json.b(it["offered"]) && !Json.b(it["heard"]) } ?: return
        Prefs.set("recOfferAt", System.currentTimeMillis()); r["offered"] = true; saveList(l)
        main.postDelayed({
            if (Player.current() !== t || !Player.playing || Voz.speaking) return@postDelayed
            val by = Json.list(r["from"])?.firstOrNull()?.let { Json.s(it) }
            Ordenes.say(if (by != null) "Has escuchado mucho a $by últimamente. Creo que te gustaría «${Json.s(r["title"])}», de ${Json.s(r["artist"])}. ¿Te pongo una muestra de 30 segundos después de esta?" else "Tengo una canción que creo que te gustaría: «${Json.s(r["title"])}», de ${Json.s(r["artist"])}. ¿Te pongo una muestra después de esta?")
            Charla.offer = mapOf("rec" to Json.s(r["id"]), "until" to System.currentTimeMillis() + 120000)
        }, 28000)
    }
    fun offerDo(id: String, now: Boolean = false) {
        val i = list().indexOfFirst { Json.s(it["id"]) == id }; if (i < 0) return
        play(i, !now)
        if (!now) Ordenes.say("Hecho: suena después de esta. Si te gusta, dímelo y la guardo.")
    }
    /** «recomiéndame algo». */
    fun ask() {
        if (!Online.on()) { Ordenes.say("Para recomendarte canciones nuevas necesito internet."); return }
        if (!on()) { Ordenes.say("Tienes las recomendaciones apagadas. Puedes encenderlas en Ajustes › Personaliza Órbita › «Descubre»."); return }
        val go = {
            val l = list(); val r = l.firstOrNull { !Json.b(it["liked"]) && !Json.b(it["heard"]) } ?: l.firstOrNull()
            if (r == null) Ordenes.say(err.ifEmpty { "Ahora mismo no encuentro nada nuevo que encaje contigo." })
            else {
                val by = Json.list(r["from"])?.firstOrNull()?.let { Json.s(it) }
                Ordenes.say("${if (by != null) "Como escuchas $by, " else ""}te propongo «${Json.s(r["title"])}», de ${Json.s(r["artist"])}. ¿Te pongo una muestra?")
                Charla.offer = mapOf("rec" to Json.s(r["id"]), "now" to true, "until" to System.currentTimeMillis() + 120000)
            }
        }
        if (count() == 0) { Nova.setBubble("Buscando algo para ti…", Nova.name()); refresh(true) { go() } } else go()
    }
}

/** «Por conseguir»: las canciones que guardas desde la búsqueda o las recomendaciones. */
object Deseos {
    fun all(): List<Map<String, Any?>> = Store.recs("wishlist").mapNotNull { (_, v) -> Json.obj(Json.parseOr(v, null)) }.sortedBy { Json.d(it["addedAt"]) }
    fun add(title: String, artist: String, why: String) {
        if (all().any { Biblio.norm(Json.s(it["title"])) == Biblio.norm(title) && Biblio.norm(Json.s(it["artist"])) == Biblio.norm(artist) }) return
        val id = Listas.uid("w")
        Store.putRec("wishlist", id, Json.str(linkedMapOf("id" to id, "title" to title, "artist" to artist, "why" to why, "addedAt" to System.currentTimeMillis())))
        Ver.bump()
    }
    fun del(id: String) { Store.delRec("wishlist", id); Ver.bump() }
}
