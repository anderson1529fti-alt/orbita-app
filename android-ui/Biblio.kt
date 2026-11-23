package __PACKAGE__

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fase 4, parte 2 · «Mi música» (lo de «Library», «Music on the phone», «Import» y «Tracks, covers, quality» de la
 * parte web): lo que dice la pantalla, la música del móvil, añadir canciones, borrar, me gusta y las portadas.
 */
object Biblio {
    private val main = Handler(Looper.getMainLooper())
    val io = Executors.newSingleThreadExecutor()

    // ---------------------------------------------------------------- lo de cada canción
    fun norm(s: String?): String {
        val n = Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
        return n.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
    fun artistKey(a: String?): String { val k = norm(a); return if (k.isEmpty() || k == "artista desconocido") "" else k }

    private fun demoStats(): MutableMap<String, Any?> = (Json.obj(Prefs.get("demoStats")) ?: emptyMap()).toMutableMap()
    fun plays(t: Track): Int = if (t.demo) Json.l(Json.obj(demoStats()[t.id])?.get("plays")).toInt() else t.plays
    fun liked(t: Track): Boolean = if (t.demo) Json.b(Json.obj(demoStats()[t.id])?.get("liked")) else t.liked

    /** «saveTrack»: las de prueba guardan lo suyo aparte (no son canciones de verdad). */
    fun save(t: Track) {
        if (t.net) return
        if (t.demo) {
            val m = demoStats()
            m[t.id] = linkedMapOf("plays" to t.plays, "liked" to t.liked, "lastPlayed" to t.lastPlayed, "skips" to t.skips, "listenSec" to t.listenSec, "hours" to t.hours)
            Prefs.set("demoStats", m); return
        }
        Lib.save(t)
    }

    fun addPlay(t: Track) { t.plays += 1; t.lastPlayed = System.currentTimeMillis().toDouble(); save(t) }
    fun history(): List<String> = Json.list(Prefs.get("history"))?.map { Json.s(it) } ?: emptyList()

    fun visible(): List<Track> { val hide = Prefs.bool("hideDemos", false); return Lib.tracks.filter { !(it.demo && hide) } }

    class Q(val label: String, val short: String, val tier: String, val kbps: Int)
    fun quality(t: Track): Q? {
        if (t.size <= 0 || t.duration <= 0 || t.codec.isEmpty()) return null
        if (t.codec == "WAV" || t.codec == "FLAC") return Q("${t.codec} · sin pérdida", t.codec + " · HI-FI", "lossless", 0)
        val kbps = Math.round(t.size * 8 / t.duration / 1000).toInt()
        val eff = t.codec in listOf("M4A", "AAC", "OPUS", "OGG", "WEBM")
        val hi = if (eff) 192 else 256; val lo = if (eff) 112 else 160
        if (kbps <= 0) return null
        val tier = if (t.mine) "alta" else if (kbps >= hi) "alta" else if (kbps >= lo) "media" else "baja"
        return Q("${t.codec} · $kbps kbps", "${t.codec} ${kbps}k", tier, kbps)
    }

    fun codecOf(type: String?, name: String?): String {
        val n = (name ?: "").lowercase(); val ty = (type ?: "").lowercase()
        return when {
            "flac" in ty || n.endsWith(".flac") -> "FLAC"
            "wav" in ty || "wave" in ty || n.endsWith(".wav") -> "WAV"
            "mpeg" in ty || "mp3" in ty || n.endsWith(".mp3") -> "MP3"
            n.endsWith(".aac") || "aac" in ty -> "AAC"
            "mp4" in ty || "m4a" in ty || n.endsWith(".m4a") || n.endsWith(".mp4") -> "M4A"
            "opus" in ty || n.endsWith(".opus") -> "OPUS"
            "ogg" in ty || n.endsWith(".ogg") || n.endsWith(".oga") -> "OGG"
            "webm" in ty || n.endsWith(".webm") -> "WEBM"
            else -> (n.substringAfterLast('.', "").ifEmpty { "audio" }).uppercase().take(5)
        }
    }

    // ---------------------------------------------------------------- lo que dice la pantalla
    fun dropText(): String = "**Pulsa «Añadir canciones» y selecciona muchas a la vez**: en el selector del móvil, mantén pulsada una canción y ve tocando las demás (o usa «Seleccionar todo»). Se guardan tal cual, sin recomprimir, con su título, artista y portada."

    /** «renderLibrary» + «renderDevice»: el resumen, el aviso de calidad, la caja de la música del móvil, la ayuda y las de prueba. */
    fun boxes() {
        val all = visible(); val own = Lib.tracks.filter { !it.demo }
        val total = all.sumOf { it.duration }
        UiState.meta = "${all.size} ${if (all.size == 1) "canción" else "canciones"} · " + (if (total >= 3600) String.format(java.util.Locale.US, "%.1f", total / 3600) + " h" else "${Math.round(total / 60)} min")
        val granted = devState() == "granted"
        UiState.drop = if (own.size > 6 || granted) "" else dropText()
        val low = own.count { quality(it)?.tier == "baja" }
        if (low > 0) {
            UiState.warn = "$low ${if (low == 1) "canción tiene" else "canciones tienen"} poca calidad de origen."
            UiState.warnText = "Están marcadas en rojo. Esa pérdida viene del propio archivo (suele pasar con conversores de YouTube) y la app no puede recuperarla. Para que suenen bien, consíguelas en MP3 a 256–320 kbps, M4A a 256 kbps o FLAC."
        } else { UiState.warn = ""; UiState.warnText = "" }
        val n = Lib.tracks.count { it.dev }
        UiState.dev = if (granted) UiBox("Música del móvil",
            if (scanning) "Buscando canciones…" else "$n ${if (n == 1) "canción" else "canciones"} conectadas. Se reproducen directamente desde tu almacenamiento, sin copiarlas. Las nuevas aparecen solas.",
            if (scanning) "Buscando…" else "Actualizar", "sync", false, !scanning)
        else UiBox("Conecta la música de tu móvil",
            if (devState() == "denied") "Has denegado el permiso. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio." else "Órbita puede reproducir todas las canciones que ya tienes en el móvil, sin copiarlas ni ocupar espacio extra.",
            "Conectar", "connect", true, true)
        val hide = Prefs.bool("hideDemos", false)
        UiState.demos = UiBox("", if (hide) "Las pistas demo están ocultas." else "Las pistas demo se generan en tu móvil para que puedas probar la app.", if (hide) "Mostrar demos" else "Ocultar demos", "demos", false, true)
        UiState.showDev = true
    }

    // ---------------------------------------------------------------- la música del móvil (sin copiarla)
    @Volatile var scanning = false; private set
    private var lastState = ""
    private fun perm(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun devState(): String {
        val a = Nativo.act ?: return "prompt"
        if (ContextCompat.checkSelfPermission(a, perm()) == PackageManager.PERMISSION_GRANTED) return "granted"
        return if (lastState == "denied") "denied" else "prompt"
    }

    /** «connectDevice»: pide el permiso y, si lo da, lee la música. */
    fun connect() {
        val a = Nativo.act as? ComponentActivity ?: return
        if (devState() == "granted") { syncDevice(true); return }
        Actos.launch(a, ActivityResultContracts.RequestPermission(), perm()) { ok ->
            lastState = if (ok) "granted" else "denied"
            if (ok) syncDevice(true)
            else Nativo.toast(if (lastState == "denied") "Sin permiso. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio." else "Necesito permiso para ver tu música.", 4800)
            boxes()
        }
    }

    private fun cleanTag(v: String?, d: String): String { val x = (v ?: "").trim(); return if (x.isEmpty() || Regex("^<?unknown>?$", RegexOption.IGNORE_CASE).matches(x)) d else x }

    private fun folderName(raw: String?, relative: Boolean): String {
        var p = raw ?: return ""
        if (!relative) { val s = p.lastIndexOf('/'); p = if (s > 0) p.substring(0, s) else "" }
        while (p.endsWith("/")) p = p.dropLast(1)
        val l = p.lastIndexOf('/'); return if (l >= 0) p.substring(l + 1) else p
    }

    /** «syncDevice»: las canciones del móvil (las nuevas entran, las que ya no están salen). */
    fun syncDevice(manual: Boolean) {
        val a = Nativo.act ?: return
        if (scanning) return
        if (devState() != "granted") { boxes(); return }
        scanning = true; boxes()
        io.execute {
            var msg: String? = null; var msg2: String? = null
            try {
                val hidden = (Json.list(Prefs.get("devHidden")) ?: emptyList()).map { Json.s(it) }.toSet()
                val modern = Build.VERSION.SDK_INT >= 29
                val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val folderCol = if (modern) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
                val cols = arrayListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_ADDED, folderCol)
                if (Build.VERSION.SDK_INT >= 30) cols.add("genre")
                class F(val id: String, val uri: String, val title: String?, val artist: String?, val album: String?, val dur: Double, val size: Long, val mime: String?, val name: String?, val added: Long, val folder: String, val genre: String?)
                val found = ArrayList<F>()
                a.contentResolver.query(base, cols.toTypedArray(), MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { c ->
                    val iG = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex("genre") else -1
                    while (c.moveToNext()) {
                        val dur = c.getLong(4); if (dur in 1 until 20000) continue
                        val id = c.getLong(0)
                        found.add(F(id.toString(), ContentUris.withAppendedId(base, id).toString(), c.getString(1), c.getString(2), c.getString(3), dur / 1000.0, c.getLong(5),
                            c.getString(6), c.getString(7), c.getLong(8) * 1000L, folderName(c.getString(9), modern), if (iG >= 0) c.getString(iG) else null))
                    }
                }
                val seen = HashSet<String>(); var added = 0
                val dirty = ArrayList<Track>()
                synchronized(Lib) {
                    for (f in found) {
                        val id = "dev:" + f.id; seen.add(id)
                        if (id in hidden) continue
                        val baseName = (f.name ?: "").replace(Regex("\\.[^.]+$"), "")
                        val title = cleanTag(f.title, baseName.ifEmpty { "Sin título" }); val artist = cleanTag(f.artist, "Artista desconocido"); val album = cleanTag(f.album, "")
                        val genre = if (!f.genre.isNullOrEmpty()) cleanTag(f.genre, "") else null
                        val t = Lib.byId(id)
                        if (t != null) {
                            var changed = false
                            if (!t.edited) {
                                if (t.title != title) { t.title = title; changed = true }
                                if (t.artist != artist) { t.artist = artist; changed = true }
                                if (t.album != album) { t.album = album; changed = true }
                                if (genre != null && t.genre != genre) { t.genre = genre; changed = true }
                            }
                            if (t.uri != f.uri || t.size != f.size) { t.uri = f.uri; t.size = f.size; if (f.dur > 0) t.duration = f.dur; changed = true }
                            if (changed) dirty.add(t)
                        } else {
                            val nt = Track(id)
                            nt.dev = true; nt.uri = f.uri; nt.mime = f.mime ?: ""; nt.title = title; nt.artist = artist; nt.album = album; if (genre != null) nt.genre = genre
                            nt.duration = f.dur; nt.size = f.size; nt.codec = codecOf(f.mime, f.name); nt.folder = f.folder
                            nt.addedAt = (if (f.added > 0) f.added else System.currentTimeMillis()).toDouble()
                            Lib.add(nt); dirty.add(nt); added++
                        }
                    }
                    Store.tx { for (t in dirty) Lib.save(t) }
                    val gone = Lib.tracks.filter { it.dev && it.id !in seen }
                    if (gone.isNotEmpty()) { Store.tx { for (t in gone) { Lib.remove(t.id); Store.delRec("tracks", t.id) } } }
                    val n = Lib.tracks.count { it.dev }
                    if (manual) msg = if (added > 0) "$added ${if (added == 1) "canción nueva" else "canciones nuevas"} del móvil" else "Tu música está al día ($n canciones)"
                    else if (added > 0) msg = "$added ${if (added == 1) "canción nueva" else "canciones nuevas"} en tu móvil"
                    if (gone.isNotEmpty() && manual) msg2 = "${gone.size} ya no están en el móvil y las he quitado"
                }
                Prefs.set("devConnected", true)
            } catch (t: Throwable) { if (manual) msg = "No he podido leer la música del móvil."; Nativo.note("móvil: ${t.message}") }
            main.post {
                scanning = false
                Nativo.libraryChanged()
                msg?.let { Nativo.toast(it) }; msg2?.let { m -> main.postDelayed({ Nativo.toast(m) }, 2700) }
                crawlArt()
            }
        }
    }

    // ---------------------------------------------------------------- las portadas
    /** Una imagen reducida (180 px como mucho) en JPEG, con su tono dominante («makeThumb» + «dominantTone»). */
    class Thumb(val jpeg: ByteArray?, val hue: Double?, val sat: Double?)
    fun makeThumb(bytes: ByteArray, size: Int = 180): Thumb {
        try {
            val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) return Thumb(null, null, null)
            var smp = 1; while (o.outWidth / (smp * 2) >= size && o.outHeight / (smp * 2) >= size) smp *= 2
            val o2 = BitmapFactory.Options(); o2.inSampleSize = smp
            val b0 = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return Thumb(null, null, null)
            val s = min(1.0, size.toDouble() / max(b0.width, b0.height))
            val w = max(1, (b0.width * s).roundToInt()); val h = max(1, (b0.height * s).roundToInt())
            val b = if (w != b0.width || h != b0.height) Bitmap.createScaledBitmap(b0, w, h, true) else b0
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            var sx = 0.0; var sy = 0.0; var wsum = 0.0; var ssum = 0.0; var n = 0
            var i = 0
            while (i < px.size) {
                val c = px[i]; val r = ((c shr 16) and 255) / 255.0; val g = ((c shr 8) and 255) / 255.0; val bl = (c and 255) / 255.0
                val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl)); val l = (mx + mn) / 2; val dd = mx - mn
                if (dd < 0.08 || l < 0.12 || l > 0.92) { n++; i += 4; continue }
                val sa = dd / (1 - abs(2 * l - 1))
                var hh = if (mx == r) ((g - bl) / dd) % 6 else if (mx == g) (bl - r) / dd + 2 else (r - g) / dd + 4
                hh *= 60; if (hh < 0) hh += 360
                val wg = sa * sa; sx += cos(hh * PI / 180) * wg; sy += sin(hh * PI / 180) * wg; wsum += wg; ssum += sa; n++
                i += 4
            }
            val bos = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 84, bos)
            if (wsum < 0.5) return Thumb(bos.toByteArray(), null, 0.3)
            var hh = atan2(sy, sx) * 180 / PI; if (hh < 0) hh += 360
            return Thumb(bos.toByteArray(), Math.round(hh).toDouble(), max(0.45, min(1.0, (ssum / max(1, n)) * 2.2)))
        } catch (t: Throwable) { return Thumb(null, null, null) }
    }

    /** Guarda una portada (la grande y la pequeña) en una canción. */
    fun setCover(t: Track, bytes: ByteArray?, type: String = "image/jpeg") {
        if (bytes == null) { t.cover = null; t.thumb = null; t.coverHue = null; t.coverSat = null; return }
        val big = Store.putBlob("tracks", t.id, "cover", bytes, type)
        val th = makeThumb(bytes)
        t.cover = big
        t.thumb = th.jpeg?.let { Store.putBlob("tracks", t.id, "thumb", it, "image/jpeg") }
        t.coverHue = th.hue; t.coverSat = th.sat
    }

    /** «fetchArt»: la portada de una canción del móvil (la de dentro del archivo o la que tenga Android). */
    fun fetchArt(t: Track, after: (() -> Unit)? = null) {
        if (!t.dev || t.artChecked) return
        t.artChecked = true
        val a = Nativo.act ?: return
        io.execute {
            var bytes: ByteArray? = null
            try {
                val uri = Uri.parse(t.uri)
                val mmr = MediaMetadataRetriever()
                try { mmr.setDataSource(a, uri); bytes = mmr.embeddedPicture } catch (ignored: Throwable) { } finally { try { mmr.release() } catch (ignored: Throwable) { } }
                var bmp: Bitmap? = bytes?.let { decodeScaled(it, 512) }
                if (bmp == null && Build.VERSION.SDK_INT >= 29) bmp = try { a.contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null) } catch (e: Throwable) { null }
                val out = bmp?.let { val bos = ByteArrayOutputStream(); it.compress(Bitmap.CompressFormat.JPEG, 86, bos); bos.toByteArray() }
                if (out != null) setCover(t, out)
                Lib.save(t)
                main.post {
                    if (out != null) {
                        UiState.covers.remove(t.id); UiState.asked.remove(t.id); UiState.covers.remove(t.id + "|L"); UiState.asked.remove(t.id + "|L")
                        Nativo.pushLibrary()
                        if (Player.current() === t) { Player.barSync(); Nativo.look(); Nativo.bgSync() }
                    }
                    after?.invoke()
                }
            } catch (e: Throwable) { t.artChecked = false; main.post { after?.invoke() } }
        }
    }

    private fun decodeScaled(data: ByteArray, size: Int): Bitmap? {
        val b = BitmapFactory.Options(); b.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(data, 0, data.size, b)
        var s = 1; while (b.outWidth / (s * 2) >= size && b.outHeight / (s * 2) >= size) s *= 2
        val o = BitmapFactory.Options(); o.inSampleSize = s
        return BitmapFactory.decodeByteArray(data, 0, data.size, o)
    }

    private var crawling = false
    /** «crawlArtwork»: las portadas de las canciones del móvil, de una en una y sin prisa (la que suena, primero). */
    fun crawlArt() {
        if (crawling) return
        val cur = Player.current()
        val t = if (cur != null && cur.dev && !cur.artChecked) cur else Lib.tracks.firstOrNull { it.dev && !it.artChecked } ?: return
        crawling = true
        fetchArt(t) { crawling = false; main.postDelayed({ crawlArt() }, 120) }
    }

    // ---------------------------------------------------------------- lo que se toca
    fun like(id: String) {
        val t = Lib.byId(id) ?: return
        t.liked = !liked(t)
        save(t)
        Nativo.pushLibrary()
        if (Player.current() === t) Player.barSync()
        Memoria.onLike(t)
        Nativo.toast(if (t.liked) "Añadida a favoritas" else "Quitada de favoritas", 1600)
    }

    /** «deleteTrack»: una importada se borra; una del móvil solo se quita de Órbita (el archivo sigue ahí). */
    fun delete(id: String) {
        val t = Lib.byId(id) ?: return
        if (t.demo) return
        if (t.dev) { val h = (Json.list(Prefs.get("devHidden")) ?: emptyList()).map { Json.s(it) }.toMutableSet(); h.add(id); Prefs.set("devHidden", h.toList()) }
        Player.removed(id)
        Lib.remove(id)
        Store.delRec("tracks", id)
        if (!t.dev) { Store.dropBlobs("audio", id); Store.delRec("audio", id) }
        Store.dropBlobs("tracks", id)
        Nativo.libraryChanged()
        Nativo.toast(if (t.dev) "Quitada de Órbita (el archivo sigue en tu móvil)" else "Canción borrada")
    }

    // ---------------------------------------------------------------- añadir canciones (desde el selector del móvil)
    private val AUDIO_RE = Regex("\\.(mp3|m4a|aac|wav|ogg|oga|opus|flac|webm|caf|3gp|amr)$", RegexOption.IGNORE_CASE)
    private val IMG_RE = Regex("\\.(jpe?g|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)
    @Volatile var job: Job? = null; private set
    class Job(val total: Int, val skipped: Int) { @Volatile var done = 0; @Volatile var added = 0; @Volatile var cancel = false }

    fun pick(mine: Boolean = false) {
        val a = Nativo.act as? ComponentActivity ?: return
        if (job != null) { Nativo.toast("Espera a que termine la importación en curso."); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "image/*")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        Actos.launch(a, ActivityResultContracts.StartActivityForResult(), i) { res ->
            val d = res.data ?: return@launch
            if (res.resultCode != android.app.Activity.RESULT_OK) return@launch
            val uris = ArrayList<Uri>()
            val clip = d.clipData
            if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) } else d.data?.let { uris.add(it) }
            if (uris.size > 2) Nativo.toast("Preparando ${uris.size} archivos…", 4000)
            importUris(uris, mine)
        }
    }

    private class In(val uri: Uri, val name: String, val type: String, val size: Long)

    fun importUris(uris: List<Uri>, mine: Boolean) {
        val a = Nativo.act ?: return
        io.execute {
            val ins = uris.mapNotNull { u ->
                try {
                    var name = u.lastPathSegment ?: "archivo"; var size = -1L
                    a.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) } }
                    In(u, name, a.contentResolver.getType(u) ?: "", size)
                } catch (t: Throwable) { null }
            }
            val audio = ins.filter { it.type.startsWith("audio/") || AUDIO_RE.containsMatchIn(it.name) || it.type == "video/webm" }
            if (audio.isEmpty()) { Nativo.toast("No he encontrado archivos de audio en lo que has elegido."); return@execute }
            // (una imagen elegida a la vez, como la portada de la carpeta)
            val img = ins.firstOrNull { it.type.startsWith("image/") || IMG_RE.containsMatchIn(it.name) }
            val folderCover = img?.let { try { a.contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes() } } catch (t: Throwable) { null } }
            val have = Lib.tracks.mapNotNull { it.srcKey.ifEmpty { null } }.toHashSet()
            val seen = HashSet<String>()
            val todo = audio.filter { val k = "${it.name}|${it.size}"; if (k in have || k in seen) false else { seen.add(k); true } }
            val skipped = audio.size - todo.size
            if (todo.isEmpty()) { Nativo.toast(if (skipped == 1) "Esa canción ya estaba en tu biblioteca" else "Esas canciones ya estaban en tu biblioteca"); return@execute }
            val j = Job(todo.size, skipped); job = j
            main.post { Nativo.onImport() }
            for (e in todo) {
                if (j.cancel) break
                try { importOne(e, folderCover, mine); j.added++ } catch (t: Throwable) { Nativo.note("importar ${e.name}: ${t.message}") }
                j.done++
                main.post { Nativo.onImport(); Nativo.libraryChanged() }
            }
            job = null
            val low = Lib.tracks.count { quality(it)?.tier == "baja" }
            main.post {
                Nativo.onImport(); Nativo.libraryChanged()
                Nativo.toast((if (j.added == 1) "1 canción añadida" else "${j.added} canciones añadidas") + (if (j.cancel) " (paraste la importación)" else "") +
                    (if (skipped > 0) " · $skipped repetidas omitidas" else "") + (if (low > 0) " · revisa la calidad" else ""), 4200)
                if (mine) Nova.bubble("¡Tienes ${if (j.added == 1) "una canción nueva cantada" else "${j.added} canciones nuevas cantadas"} por ti! La pincharé cuando toque.")
            }
        }
    }

    fun stopImport() { job?.cancel = true; Nativo.onImport() }

    private fun parseName(name: String): Pair<String, String> {
        val base = name.replace(Regex("\\.[^.]+$"), "").replace('_', ' ').replace(Regex("^\\s*\\d{1,3}[\\s.\\-_]+"), "").trim()
        val m = base.split(Regex("\\s+[-–—]\\s+"))
        return if (m.size >= 2) m[0].trim() to m.drop(1).joinToString(" - ").trim() else "" to base
    }

    private fun cap(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    private fun importOne(e: In, folderCover: ByteArray?, mine: Boolean) {
        val a = Nativo.act ?: return
        val id = "t" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e10).toLong(), 36).take(5)
        // el archivo, tal cual, a la carpeta de Órbita
        val rel = Store.blobPath("audio", id, "")
        val f = Store.file(rel); f.parentFile?.mkdirs()
        var size = 0L
        a.contentResolver.openInputStream(e.uri)?.use { inp -> f.outputStream().use { out -> size = inp.copyTo(out, 1 shl 16) } } ?: throw IllegalStateException("no se puede leer")
        var title: String? = null; var artist: String? = null; var album: String? = null; var pic: ByteArray? = null; var dur = 0.0
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            if (!mine) { title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE); artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST); album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM); pic = mmr.embeddedPicture }
            dur = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        } catch (ignored: Throwable) { } finally { try { mmr.release() } catch (ignored: Throwable) { } }
        val (pArtist, pTitle) = parseName(e.name)
        val t = Track(id)
        t.srcKey = "${e.name}|${if (e.size >= 0) e.size else size}"
        t.title = ((title?.trim()?.ifEmpty { null }) ?: (if (mine) cap(pTitle).ifEmpty { "Mi versión" } else pTitle).ifEmpty { "Sin título" }).trim()
        t.artist = if (mine) "Yo" else ((artist?.trim()?.ifEmpty { null }) ?: pArtist.ifEmpty { "Artista desconocido" }).trim()
        t.album = if (mine) "Cantadas por mí" else (album ?: "").trim()
        t.duration = dur; t.addedAt = System.currentTimeMillis().toDouble()
        t.size = size; t.codec = codecOf(e.type, e.name); t.mine = mine
        val cover = pic ?: folderCover
        if (cover != null) setCover(t, cover)
        Store.putRec("audio", id, Json.str(mapOf("\$blob" to rel, "type" to e.type, "size" to size, "name" to e.name, "lastModified" to System.currentTimeMillis())))
        Lib.save(t)
        synchronized(Lib) { Lib.add(t) }
    }

    /** Ocultar o enseñar las canciones de prueba. */
    fun toggleDemos() {
        val h = !Prefs.bool("hideDemos", false)
        Prefs.set("hideDemos", h)
        Nativo.libraryChanged()
    }
}

/** Las ventanas del sistema (el selector de archivos, los permisos…) desde fuera de una pantalla, con su respuesta. */
object Actos {
    private var n = 0
    fun <I, O> launch(a: ComponentActivity, c: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, cb: (O) -> Unit) {
        var l: androidx.activity.result.ActivityResultLauncher<I>? = null
        l = a.activityResultRegistry.register("orbita-nat-" + (n++), c) { r -> try { cb(r) } catch (t: Throwable) { Nativo.note("respuesta: ${t.message}") } finally { l?.unregister() } }
        try { l.launch(input) } catch (t: Throwable) { l.unregister(); Nativo.toast("No he podido abrirlo: ${t.message}") }
    }
}
