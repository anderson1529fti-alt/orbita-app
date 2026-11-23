package __PACKAGE__

import java.io.File

/**
 * Fase 4, parte 2 · Las canciones, listas y ajustes de Órbita, ya en Android (leídos de [Store]).
 * Son los mismos registros que guardaba la parte web («trackRecord»), con los mismos nombres; lo que un registro
 * traiga y aquí no se use se guarda igual (nada se pierde al volver a escribirlo).
 */
class Track(val id: String) {
    var title = ""; var artist = ""; var album = ""; var genre = ""
    var duration = 0.0; var addedAt = 0.0
    var liked = false; var plays = 0; var lastPlayed = 0.0
    var cover: Map<String, Any?>? = null; var thumb: Map<String, Any?>? = null
    var coverHue: Double? = null; var coverSat: Double? = null
    var size = 0L; var codec = ""; var mine = false; var srcKey = ""
    var dev = false; var uri = ""; var mime = ""; var folder = ""
    var artChecked = false; var edited = false
    var skips = 0; var listenSec = 0.0; var hours: MutableList<Double>? = null
    /** Lo que el registro traía de más (de otra versión): se escribe igual que llegó. */
    val extra = LinkedHashMap<String, Any?>()
    // (no se guardan: lo que la app sabe de la canción mientras está abierta)
    var demo = false
    var prevPlayed = 0.0
    /** De internet (muestra, radio, YouTube): no está en la biblioteca. */
    var net = false
    var kind = ""; var url = ""; var art = ""; var artBig = ""; var urlAt = 0L

    fun toRecord(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["id"] = id; m["title"] = title; m["artist"] = artist; m["album"] = album
        m["genre"] = if (genre.isEmpty()) Json.Undefined else genre
        m["duration"] = duration; m["addedAt"] = addedAt; m["liked"] = liked; m["plays"] = plays; m["lastPlayed"] = lastPlayed
        m["cover"] = cover ?: Json.Undefined; m["thumb"] = thumb ?: Json.Undefined
        m["coverHue"] = coverHue ?: Json.Undefined; m["coverSat"] = coverSat ?: Json.Undefined
        m["size"] = size; m["codec"] = codec; m["mine"] = mine; m["srcKey"] = if (srcKey.isEmpty()) Json.Undefined else srcKey
        m["dev"] = dev; m["uri"] = if (uri.isEmpty()) Json.Undefined else uri; m["mime"] = if (mime.isEmpty()) Json.Undefined else mime
        m["folder"] = if (folder.isEmpty()) Json.Undefined else folder
        m["artChecked"] = artChecked; m["edited"] = edited; m["skips"] = skips; m["listenSec"] = listenSec; m["hours"] = hours
        for ((k, v) in extra) if (!m.containsKey(k)) m[k] = v
        return m
    }

    companion object {
        private val KNOWN = setOf("id", "title", "artist", "album", "genre", "duration", "addedAt", "liked", "plays", "lastPlayed", "cover", "thumb",
            "coverHue", "coverSat", "size", "codec", "mine", "srcKey", "dev", "uri", "mime", "folder", "artChecked", "edited", "skips", "listenSec", "hours")

        fun from(m: Map<String, Any?>): Track? {
            val id = Json.s(m["id"]); if (id.isEmpty()) return null
            val t = Track(id)
            t.title = Json.s(m["title"]); t.artist = Json.s(m["artist"]); t.album = Json.s(m["album"]); t.genre = Json.s(m["genre"])
            t.duration = Json.d(m["duration"]); t.addedAt = Json.d(m["addedAt"])
            t.liked = Json.b(m["liked"]); t.plays = Json.l(m["plays"]).toInt(); t.lastPlayed = Json.d(m["lastPlayed"])
            t.cover = Json.obj(m["cover"]); t.thumb = Json.obj(m["thumb"])
            t.coverHue = (m["coverHue"] as? Number)?.toDouble(); t.coverSat = (m["coverSat"] as? Number)?.toDouble()
            t.size = Json.l(m["size"]); t.codec = Json.s(m["codec"]); t.mine = Json.b(m["mine"]); t.srcKey = Json.s(m["srcKey"])
            t.dev = Json.b(m["dev"]); t.uri = Json.s(m["uri"]); t.mime = Json.s(m["mime"]); t.folder = Json.s(m["folder"])
            t.artChecked = Json.b(m["artChecked"]); t.edited = Json.b(m["edited"])
            t.skips = Json.l(m["skips"]).toInt(); t.listenSec = Json.d(m["listenSec"])
            t.hours = Json.list(m["hours"])?.map { Json.d(it) }?.toMutableList()
            for ((k, v) in m) if (k !in KNOWN) t.extra[k] = v
            return t
        }
    }
}

/** Los ajustes (lo que la web tenía en localStorage «orbita:*»): el mismo texto JSON, leído y escrito igual. */
object Prefs {
    fun raw(k: String): String? = if (Store.ready) Store.kv(k) else null
    fun get(k: String): Any? = Json.parseOr(raw(k), null)
    fun str(k: String, d: String): String = (get(k) as? String) ?: d
    fun bool(k: String, d: Boolean): Boolean = (get(k) as? Boolean) ?: d
    fun num(k: String, d: Double): Double = (get(k) as? Number)?.toDouble() ?: d
    fun set(k: String, v: Any?) { if (Store.ready) Store.setKv(k, Json.str(v)) }
    fun del(k: String) { if (Store.ready) Store.setKv(k, null) }
}

/** La biblioteca: las canciones (del móvil, importadas y de prueba), las listas, las grabaciones y lo que quieres. */
object Lib {
    val tracks = ArrayList<Track>()
    private val byId = HashMap<String, Track>()
    var playlists: MutableList<Map<String, Any?>> = ArrayList()
    var loaded = false; private set

    @Synchronized fun load() {
        tracks.clear(); byId.clear(); bySrc.clear()
        for ((_, v) in Store.recs("tracks")) {
            val t = Json.obj(Json.parseOr(v, null))?.let { Track.from(it) } ?: continue
            tracks.add(t); byId[t.id] = t
        }
        for (t in Demos.entries()) { tracks.add(t); byId[t.id] = t }
        playlists = Store.recs("playlists").mapNotNull { Json.obj(Json.parseOr(it.second, null)) }.toMutableList()
        loaded = true
    }

    fun byId(id: String): Track? = byId[id] ?: net[id]
    /** Las de internet que se han tocado (muestras, radios, vídeos), mientras la app está abierta. */
    val net = java.util.concurrent.ConcurrentHashMap<String, Track>()
    @Synchronized fun add(t: Track) { if (byId.containsKey(t.id)) return; tracks.add(t); byId[t.id] = t; bySrc.clear() }
    @Synchronized fun remove(id: String) { val t = byId.remove(id) ?: return; tracks.remove(t); bySrc.clear() }
    private val bySrc = HashMap<String, Track>()
    /** La canción de un archivo o dirección del motor. */
    @Synchronized fun bySrc(src: String): Track? {
        if (bySrc.isEmpty()) for (t in tracks) { val s = if (t.dev) t.uri else if (t.demo) Demos.file(t.id)?.absolutePath else null; if (!s.isNullOrEmpty()) bySrc[s] = t }
        return bySrc[src] ?: tracks.firstOrNull { !it.dev && !it.demo && src(it) == src }?.also { bySrc[src] = it }
    }

    fun save(t: Track) { if (!t.demo) Store.putRec("tracks", t.id, Json.str(t.toRecord())) }

    /** Lo que suena: el archivo o la dirección que entiende el motor (las del móvil, por su content://). */
    fun src(t: Track): String? {
        if (t.net) return t.url.ifEmpty { null }
        if (t.demo) return Demos.file(t.id)?.absolutePath
        if (t.dev && t.uri.isNotEmpty()) return t.uri
        val a = Store.rec("audio", t.id) ?: return null
        val rel = Json.s(Json.obj(Json.parseOr(a, null))?.get("\$blob")); if (rel.isEmpty()) return null
        val f = Store.file(rel)
        return if (f.isFile) f.absolutePath else null
    }

    /** El archivo de la portada grande (o de la pequeña si no hay grande). */
    fun bigCoverFile(t: Track): File? {
        for (m in listOf(t.cover, t.thumb)) { val rel = Json.s(m?.get("\$blob")); if (rel.isNotEmpty()) { val f = Store.file(rel); if (f.isFile) return f } }
        return null
    }

    /** El archivo de la portada pequeña (o de la grande si no hay pequeña). */
    fun coverFile(t: Track): File? {
        for (m in listOf(t.thumb, t.cover)) { val rel = Json.s(m?.get("\$blob")); if (rel.isNotEmpty()) { val f = Store.file(rel); if (f.isFile) return f } }
        return null
    }
}
