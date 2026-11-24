package __PACKAGE__

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import java.io.Closeable
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fase 4, parte 2 · La letra de la canción, hecha por Android: la que trae el propio archivo (ID3, MP4 o FLAC), la de
 * LRCLIB.net o la que pegas tú; sincronizada con la música (el verso que suena se enciende y se va llenando, y se lleva
 * al 42 % de la caja) o tal cual si no lleva tiempos. Se guarda como antes («lyrics»), así que las de siempre siguen ahí.
 */
object Letras {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private const val ONLINE = true

    class Line(val t: Double, val text: String)
    /** Lo que se ve (cambia al cargar, editar o mover la letra). */
    var ver by mutableIntStateOf(0); private set
    var forId: String? = null; private set
    var rec: Map<String, Any?>? = null; private set
    var loading = false; private set
    private var gen = 0
    /** (para las pruebas) el verso que se ve encendido */
    @Volatile var shownIdx = -2
    /** (el dedo movió la letra: tres segundos y medio sin que vaya sola) */
    @Volatile var userScroll = 0L

    // ---------------------------------------------------------------- el formato LRC
    private val OFF_RE = Regex("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    fun parseLRC(txt: String): List<Line> {
        val out = ArrayList<Line>(); var offset = 0.0
        for (raw in txt.split(Regex("\\r?\\n"))) {
            val off = OFF_RE.find(raw); if (off != null) { offset = off.groupValues[1].toDouble() / 1000; continue }
            val tags = TAG_RE.findAll(raw).toList(); if (tags.isEmpty()) continue
            val text = raw.replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>"), "").trim()
            for (m in tags) out.add(Line(max(0.0, m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + (if (m.groupValues[3].isNotEmpty()) ("0." + m.groupValues[3]).toDouble() else 0.0) - offset), text))
        }
        return out.sortedBy { it.t }
    }
    fun looksLRC(s: String): Boolean = TAG_RE.containsMatchIn(s)
    /** «lyricsRec»: la letra guardable (sincronizada si lleva al menos tres tiempos). */
    fun mk(id: String, text: String, source: String): MutableMap<String, Any?>? {
        val s = text.trim(); if (s.isEmpty()) return null
        if (looksLRC(s)) { val lines = parseLRC(s); if (lines.size >= 3) return linkedMapOf("id" to id, "synced" to s, "plain" to lines.joinToString("\n") { it.text }, "source" to source, "ts" to System.currentTimeMillis()) }
        return linkedMapOf("id" to id, "plain" to s.replace("\r\n", "\n"), "source" to source, "ts" to System.currentTimeMillis())
    }
    fun source(r: Map<String, Any?>): String = when (Json.s(r["source"])) { "tag" -> "Letra incluida en el archivo"; "lrclib" -> "Letra de LRCLIB.net"; "manual" -> "Letra añadida por ti"; else -> "" }
    private fun saved(id: String): Map<String, Any?>? = Json.obj(Json.parseOr(Store.rec("lyrics", id), null))

    // ---------------------------------------------------------------- de dónde sale
    fun mainArtist(s: String): String = s.split(Regex("\\s*(?:,|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bcon\\b|\\by\\b)\\s*", RegexOption.IGNORE_CASE))[0].trim()
    private fun encQ(vararg kv: Pair<String, String>): String = kv.joinToString("&") { "${it.first}=${Online.enc(it.second)}" }

    /** «lyricsFromLrclib»: primero la exacta (con su álbum y duración); si no, la mejor de la búsqueda. */
    private fun fromLrclib(t: Track): MutableMap<String, Any?>? {
        if (t.net || t.title.isEmpty() || Regex("desconocido", RegexOption.IGNORE_CASE).containsMatchIn(t.artist)) return null
        val tn = "track_name" to Recos.cleanTitle(t.title); val an = "artist_name" to mainArtist(t.artist)
        fun use(r: Map<String, Any?>?): MutableMap<String, Any?>? = if (r != null && !Json.b(r["instrumental"]) && (Json.s(r["syncedLyrics"]).isNotEmpty() || Json.s(r["plainLyrics"]).isNotEmpty()))
            mk(t.id, Json.s(r["syncedLyrics"]).ifEmpty { Json.s(r["plainLyrics"]) }, "lrclib") else null
        return try {
            if (t.album.isNotEmpty() && t.duration > 0) {
                val r = try { Json.obj(Online.getJson("https://lrclib.net/api/get?" + encQ(tn, an, "album_name" to t.album, "duration" to Math.round(t.duration).toString()))) } catch (e: Throwable) { null }
                use(r)?.let { return it }
            }
            val list = (Json.list(Online.getJson("https://lrclib.net/api/search?" + encQ(tn, an))) ?: return null).mapNotNull { Json.obj(it) }
            if (list.isEmpty()) return null
            val d = t.duration
            val best = list.filter { r -> (Json.s(r["syncedLyrics"]).isNotEmpty() || Json.s(r["plainLyrics"]).isNotEmpty()) && (d <= 0 || Json.d(r["duration"]) <= 0 || abs(Json.d(r["duration"]) - d) <= 4) }
                .sortedWith(compareBy({ if (Json.s(it["syncedLyrics"]).isNotEmpty()) 0 else 1 }, { abs((Json.d(it["duration"]).takeIf { x -> x > 0 } ?: d) - d) })).firstOrNull()
            use(best)
        } catch (e: Throwable) { null }
    }

    /** «getLyrics»: la guardada; si no, la del archivo o la de LRCLIB (y lo que no se encontró se vuelve a buscar en 7 días). */
    fun get(t: Track, force: Boolean): Map<String, Any?> {
        if (!force) {
            val c = saved(t.id)
            if (c != null && !(Json.b(c["none"]) && (System.currentTimeMillis() - Json.d(c["ts"]) > 7 * 864e5 || (Json.b(c["offline"]) && ONLINE)))) return c
        }
        var r: MutableMap<String, Any?>? = try { TagLyrics.read(t)?.let { mk(t.id, it, "tag") } } catch (e: Throwable) { null }
        if (r == null) r = fromLrclib(t)
        if (r == null) r = linkedMapOf("id" to t.id, "none" to true, "offline" to !ONLINE, "ts" to System.currentTimeMillis())
        val prev = saved(t.id)
        if (prev != null && Json.s(prev["source"]) == "manual" && !force) return prev
        if (prev != null && prev["shift"] is Number) r["shift"] = prev["shift"]
        Store.putRec("lyrics", t.id, Json.str(r))
        return r
    }

    /** La letra de la que suena (si no está ya). */
    fun ensure(t: Track?) {
        if (t == null || t.net) { if (forId != t?.id || rec != null) { gen++; forId = t?.id; rec = null; loading = false; ver++ }; return }
        if (forId == t.id && (rec != null || loading)) return
        load(t, false)
    }
    private fun load(t: Track, force: Boolean) {
        val my = ++gen; forId = t.id; rec = null; loading = true; ver++
        io.execute {
            val r = try { get(t, force) } catch (e: Throwable) { linkedMapOf<String, Any?>("id" to t.id, "none" to true, "ts" to System.currentTimeMillis()) }
            main.post { if (my != gen) return@post; rec = r; loading = false; ver++ }
        }
    }
    /** «ly-retry»: se borra lo guardado y se busca otra vez. */
    fun retry(t: Track) { Store.delRec("lyrics", t.id); load(t, true) }
    /** «ly-shift»: adelantar o retrasar la letra medio segundo. */
    fun shift(t: Track, d: Double) {
        val r = LinkedHashMap(rec ?: return)
        val s = Math.round((Json.d(r["shift"]) + d) * 10) / 10.0
        r["shift"] = s; Store.putRec("lyrics", t.id, Json.str(r)); rec = r; ver++
        val a = abs(s); val txt = (if (a == Math.floor(a)) a.toLong().toString() else a.toString()).replace('.', ',')
        Nativo.toast("Letra ${if (s >= 0) "retrasada" else "adelantada"} $txt s")
    }
    /** Lo pegado en «Editar» (vacío: se borra). */
    fun save(id: String, text: String) {
        val txt = text.trim()
        if (txt.isEmpty()) { Store.delRec("lyrics", id); if (forId == id) Player.current()?.let { load(it, false) }; return }
        val r = mk(id, txt.take(20000), "manual") ?: return
        Store.putRec("lyrics", id, Json.str(r))
        if (forId == id) { gen++; rec = r; loading = false; ver++ }
        Nativo.toast(if (r.containsKey("synced")) "Letra guardada: irá sincronizada" else "Letra guardada")
    }
    /** «setLyricsOpen» (con la letra abierta, el visualizador se cierra). */
    fun setOpen(on: Boolean) {
        if (on && Ventanas.vizOn) VizCtl.setNP(false)
        Ventanas.lyOn = on; Prefs.set("lyOn", on)
        if (on) ensure(Player.current())
    }
}

/** La letra que trae el propio archivo (como «jsmediatags»: ID3 «USLT», MP4 «©lyr» y FLAC «LYRICS»). */
object TagLyrics {
    fun read(t: Track): String? {
        if (t.net) return null
        val src = Lib.src(t) ?: return null
        val (ch, cl) = open(src) ?: return null
        try {
            val head = ByteBuffer.allocate(12); ch.position(0); ch.read(head); head.flip()
            if (head.remaining() < 4) return null
            val b = ByteArray(head.remaining()); head.get(b)
            return when {
                b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> id3(ch)
                b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(ch)
                String(b, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> flac(ch)
                else -> null
            }?.trim()?.ifEmpty { null }
        } catch (e: Throwable) { return null } finally { try { cl.close() } catch (ignored: Throwable) { } }
    }
    private fun open(src: String): Pair<FileChannel, Closeable>? = try {
        if (src.startsWith("content:")) {
            val pfd = Nativo.act?.contentResolver?.openFileDescriptor(Uri.parse(src), "r") ?: return null
            val fis = FileInputStream(pfd.fileDescriptor); fis.channel to Closeable { try { fis.close() } finally { pfd.close() } }
        } else { val raf = RandomAccessFile(src, "r"); raf.channel to raf }
    } catch (e: Throwable) { null }
    private fun bytes(ch: FileChannel, at: Long, n: Int): ByteArray {
        val bb = ByteBuffer.allocate(n); ch.position(at)
        while (bb.hasRemaining()) { if (ch.read(bb) <= 0) break }
        return bb.array().copyOf(bb.position())
    }

    // ID3v2
    private fun syncsafe(b: ByteArray, o: Int) = ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)
    private fun be(b: ByteArray, o: Int, n: Int): Int { var v = 0; for (k in 0 until n) v = (v shl 8) or (b[o + k].toInt() and 0xff); return v }
    private fun id3(ch: FileChannel): String? {
        val h = bytes(ch, 0, 10); if (h.size < 10) return null
        val ver = h[3].toInt(); val flags = h[5].toInt(); val size = syncsafe(h, 6)
        if (size <= 0 || size > 64_000_000) return null
        val tag = bytes(ch, 10, size)
        var pos = 0
        if (flags and 0x40 != 0 && tag.size >= 4) pos = if (ver >= 4) syncsafe(tag, 0) else be(tag, 0, 4) + 4
        val hl = if (ver == 2) 6 else 10
        while (pos + hl <= tag.size) {
            if (tag[pos].toInt() == 0) break
            val id = String(tag, pos, if (ver == 2) 3 else 4, Charsets.ISO_8859_1)
            val fs = if (ver == 2) be(tag, pos + 3, 3) else if (ver >= 4) syncsafe(tag, pos + 4) else be(tag, pos + 4, 4)
            val ds = pos + hl; if (fs <= 0 || ds + fs > tag.size) break
            if (id == "USLT" || id == "ULT") return uslt(tag.copyOfRange(ds, ds + fs))
            pos = ds + fs
        }
        return null
    }
    private fun uslt(d: ByteArray): String? {
        if (d.size < 5) return null
        val enc = d[0].toInt(); var p = 4
        val wide = enc == 1 || enc == 2
        // (la descripción, hasta su final: 0 o 00 00)
        if (wide) { while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2; p += 2 } else { while (p < d.size && d[p].toInt() != 0) p++; p += 1 }
        if (p > d.size) return null
        val body = d.copyOfRange(p, d.size)
        return when (enc) { 0 -> String(body, Charsets.ISO_8859_1); 1 -> String(body, Charsets.UTF_16); 2 -> String(body, Charsets.UTF_16BE); else -> String(body, Charsets.UTF_8) }.trimEnd('\u0000')
    }

    // MP4 (moov › udta › meta › ilst › ©lyr › data)
    private fun mp4(ch: FileChannel): String? {
        val len = ch.size(); var at = 0L
        while (at + 8 <= len) {
            val h = bytes(ch, at, 16); if (h.size < 8) return null
            var sz = be(h, 0, 4).toLong() and 0xffffffffL; val type = String(h, 4, 4, Charsets.ISO_8859_1); var hd = 8
            if (sz == 1L && h.size >= 16) { sz = ByteBuffer.wrap(h, 8, 8).long; hd = 16 } else if (sz == 0L) sz = len - at
            if (sz < hd) return null
            if (type == "moov") { if (sz > 48_000_000) return null; return findLyr(bytes(ch, at + hd, (sz - hd).toInt()), 0) }
            at += sz
        }
        return null
    }
    private fun findLyr(b: ByteArray, depth: Int): String? {
        var p = 0
        while (p + 8 <= b.size) {
            val sz = be(b, p, 4); val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (sz < 8 || p + sz > b.size) return null
            val inner = b.copyOfRange(p + 8, p + sz)
            when {
                type == "udta" || type == "ilst" -> findLyr(inner, depth + 1)?.let { return it }
                type == "meta" && inner.size > 4 -> findLyr(inner.copyOfRange(4, inner.size), depth + 1)?.let { return it }
                b[p + 4] == 0xA9.toByte() && type.substring(1) == "lyr" -> {
                    // («data»: tipo y país, y el texto)
                    if (inner.size >= 16 && String(inner, 4, 4, Charsets.ISO_8859_1) == "data") { val ds = be(inner, 0, 4); return String(inner, 16, min(ds, inner.size) - 16, Charsets.UTF_8) }
                }
            }
            p += sz
        }
        return null
    }

    // FLAC (VORBIS_COMMENT)
    private fun flac(ch: FileChannel): String? {
        var at = 4L
        for (k in 0 until 64) {
            val h = bytes(ch, at, 4); if (h.size < 4) return null
            val last = h[0].toInt() and 0x80 != 0; val type = h[0].toInt() and 0x7f; val n = be(h, 1, 3)
            if (type == 4) {
                val d = ByteBuffer.wrap(bytes(ch, at + 4, n)).order(ByteOrder.LITTLE_ENDIAN)
                val vl = d.int; d.position(d.position() + vl); val cnt = d.int
                for (i in 0 until cnt) {
                    if (d.remaining() < 4) break
                    val l = d.int; if (l < 0 || l > d.remaining()) break
                    val s = ByteArray(l); d.get(s); val kv = String(s, Charsets.UTF_8); val eq = kv.indexOf('=')
                    if (eq > 0) { val key = kv.substring(0, eq).uppercase(); if (key == "LYRICS" || key == "UNSYNCEDLYRICS") return kv.substring(eq + 1) }
                }
                return null
            }
            if (last) return null
            at += 4 + n
        }
        return null
    }
}

// ================================================================ la caja de la letra (en «Ahora suena»)
private val LY_BASE = Color(1f, 1f, 1f, 0.34f)
private val LY_SUNG = Color(1f, 1f, 1f, 0.55f)
private val LY_DIM = Color(1f, 1f, 1f, 0.45f)

@Composable
internal fun NpLyrics(sk: Skin, t: Track?) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val v = Letras.ver
    LaunchedEffect(t?.id) { Letras.ensure(t) }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Color.Black.copy(alpha = 0.42f)).border(sk.b, p.line2, shape)) {
        val r = Letras.rec
        val empty: @Composable (content: @Composable () -> Unit) -> Unit = { content ->
            Column(Modifier.fillMaxWidth().weight(1f).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
        when {
            t == null -> empty { T("Pon una canción para ver su letra.", 15f * sk.tk, p.fg, align = TextAlign.Center) }
            t.net -> empty { T(if (t.kind == "radio") "Las radios no traen la letra de lo que suena." else "Los avances de internet no traen letra.", 15f * sk.tk, p.fg, align = TextAlign.Center) }
            Letras.loading || r == null || Letras.forId != t.id -> empty { EqBars(sk); T("Buscando la letra…", 15f * sk.tk, p.fg, align = TextAlign.Center) }
            Json.b(r["none"]) -> empty {
                T("No he encontrado la letra de «${t.title}».", 15f * sk.tk, p.fg, align = TextAlign.Center)
                T("Puedes pegarla tú: si lleva tiempos como [00:12.30] irá sincronizada con la música.", 13.44f * sk.tk, p.muted, align = TextAlign.Center)
                HBtn("Añadir letra", sk, kind = 1) { Ventanas.openLyEdit(t) }
                LyLink("Buscar otra vez", sk) { Letras.retry(t) }
            }
            else -> {
                val synced = Json.s(r["synced"]).isNotEmpty()
                Box(Modifier.fillMaxWidth().weight(1f)) { if (synced) LySynced(r, sk) else LyPlain(Json.s(r["plain"]), sk) }
                // las herramientas de abajo
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).drawWithContent { drawContent(); drawRect(p.line, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, sk.b.toPx())) }
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Note(Letras.source(r), sk, Modifier.weight(1f))
                    if (synced) { LyLink("−0,5 s", sk) { Letras.shift(t, -0.5) }; LyLink("+0,5 s", sk) { Letras.shift(t, 0.5) } }
                    LyLink("Editar", sk) { Ventanas.openLyEdit(t) }
                }
            }
        }
    }
}

/** Las barritas de «Buscando…». */
@Composable
private fun EqBars(sk: Skin) {
    val p = sk.pal
    val still = Nativo.reduceMotion()
    val ph = rememberInfiniteTransition(label = "lyeq").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "e").value
    androidx.compose.foundation.Canvas(Modifier.size(41.dp, 28.dp)) {
        val dl = floatArrayOf(0f, 0.3f, 0.6f, 0.15f, 0.45f)
        for (i in 0 until 5) {
            val k = (ph + dl[i]) % 1f
            val h = (if (still) 14f else 6f + 22f * (0.5f - 0.5f * kotlin.math.cos(k * 2f * Math.PI.toFloat()))).dp.toPx()
            drawRoundRect(p.grad, Offset(i * 9.dp.toPx(), size.height - h), androidx.compose.ui.geometry.Size(5.dp.toPx(), h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx() * sk.round))
        }
    }
}
/** «link» de la letra (pequeño, con borde; no se hunde). */
@Composable
private fun LyLink(t: String, sk: Skin, onClick: () -> Unit) {
    val p = sk.pal
    val shape = sk.r(9f)
    Box(Modifier.press(scale = 1f, onClick = onClick).clip(shape).border(sk.b, p.line2, shape).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) {
        T(t, 12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** Sin tiempos: tal cual, para leerla. */
@Composable
private fun LyPlain(text: String, sk: Skin) {
    val p = sk.pal
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        for (l in text.split("\n")) {
            T(l.ifEmpty { " " }, 16.32f * sk.tk, p.fg, Modifier.padding(bottom = 4.dp), weight = FontWeight.SemiBold, lh = 1.25f)
        }
    }
}

/** Sincronizada: el verso que suena, encendido y llenándose, al 42 % de la caja. */
@Composable
private fun LySynced(r: Map<String, Any?>, sk: Skin) {
    val dens = LocalDensity.current.density
    val shift = Json.d(r["shift"])
    val lines = remember(r) { Letras.parseLRC(Json.s(r["synced"])).map { Letras.Line(it.t + shift, it.text) } }
    // el verso de ahora (cada décima, con lo que lleva sonando)
    var idx by remember(r) { mutableIntStateOf(-2) }
    var fillD by remember(r) { mutableStateOf(0f) }
    var fillT0 by remember(r) { mutableStateOf(0L) }
    LaunchedEffect(r) {
        while (true) {
            val time = Player.position()
            var lo = 0; var hi = lines.size - 1; var i = -1
            while (lo <= hi) { val m = (lo + hi) / 2; if (lines[m].t <= time + 0.15) { i = m; lo = m + 1 } else hi = m - 1 }
            if (i != idx) {
                idx = i; Letras.shownIdx = i
                val ln = lines.getOrNull(i)
                if (ln != null && ln.text.isNotEmpty() && Player.playing && !Nativo.lite()) {
                    val nx = lines.drop(i + 1).firstOrNull { it.t > ln.t }
                    fillD = (min(12.0, max(0.6, (nx?.t ?: (ln.t + 4)) - time))).toFloat(); fillT0 = System.currentTimeMillis()
                } else fillD = 0f
            }
            kotlinx.coroutines.delay(100)
        }
    }
    val scroll = rememberScrollState()
    val tops = remember(r) { HashMap<Int, Float>() }
    var boxH by remember { mutableStateOf(0f) }
    val first = remember(r) { BooleanArray(1) { true } }
    LaunchedEffect(idx, boxH) {
        if (idx < 0 || boxH <= 0f) return@LaunchedEffect
        if (SystemClock.uptimeMillis() - Letras.userScroll < 3500) return@LaunchedEffect
        val top = tops[idx] ?: return@LaunchedEffect
        val target = (top - boxH * 0.42f).roundToInt().coerceIn(0, scroll.maxValue)
        if (first[0] || Nativo.reduceMotion()) { first[0] = false; scroll.scrollTo(target); return@LaunchedEffect }
        val dist = abs(target - scroll.value) / dens
        scroll.animateScrollTo(target, tween((140f + sqrt(dist) * 16f).roundToInt().coerceIn(180, 650), easing = FastOutSlowInEasing))
    }
    // el dedo la mueve: tres segundos y medio sin ir sola; y lo que sobra no baja el reproductor
    val conn = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput) Letras.userScroll = SystemClock.uptimeMillis(); return Offset.Zero }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
        }
    }
    val hold = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            UiState.npNoDrag = true
            try { do { val ev = awaitPointerEvent(PointerEventPass.Initial) } while (ev.changes.any { it.pressed }) } finally { UiState.npNoDrag = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { boxH = it.size.height.toFloat() }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn) }) {
        val w = maxWidth
        Column(Modifier.fillMaxSize().then(hold).nestedScroll(conn).verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, top = w * 0.42f, bottom = w * 0.46f)) {
            lines.forEachIndexed { i, ln ->
                Box(Modifier.padding(bottom = 14.dp).onGloballyPositioned { tops[i] = it.positionInParent2() }) {
                    LyVerse(ln, i, idx, if (i == idx) fillD else 0f, fillT0, sk) { Player.seek(max(0.0, ln.t - 0.05)); Letras.userScroll = 0L }
                }
            }
        }
    }
}
private fun androidx.compose.ui.layout.LayoutCoordinates.positionInParent2(): Float = this.parentLayoutCoordinates?.let { it.localPositionOf(this, Offset.Zero).y } ?: 0f

/** Un verso: encendido (blanco y un 4 % más grande), cantado o por cantar; el que suena, llenándose renglón tras renglón. */
@Composable
private fun LyVerse(ln: Letras.Line, i: Int, idx: Int, fd: Float, t0: Long, sk: Skin, onTap: () -> Unit) {
    val rm = Nativo.reduceMotion()
    val on = i == idx
    val gap = ln.text.isEmpty()
    val target = if (on) Color.White else if (i < idx) LY_SUNG else LY_BASE
    val col by animateColorAsState(target, if (rm) snap() else tween(250), label = "ly")
    val sc by animateFloatAsState(if (on) 1.04f else 1f, if (rm) snap() else tween(250), label = "lys")
    val fillOn = on && !gap && fd > 0f && !rm
    val prog = remember(i) { Animatable(0f) }
    LaunchedEffect(on, fillOn, t0, fd) {
        if (!fillOn) { prog.snapTo(if (on) 1f else 0f); return@LaunchedEffect }
        val total = fd * 1000f
        val p0 = ((System.currentTimeMillis() - t0) / total).coerceIn(0f, 1f)
        prog.snapTo(p0)
        if (p0 < 1f) prog.animateTo(1f, tween(((1f - p0) * total).roundToInt().coerceAtLeast(1), easing = LinearEasing))
    }
    var lay by remember { mutableStateOf<TextLayoutResult?>(null) }
    val st = style(17.92f * sk.tk, if (fillOn) LY_DIM else col, sk.display, FontWeight.ExtraBold, 1.25f, -0.02f)
    BasicText(if (gap) "♪" else ln.text, Modifier.heightIn(min = (17.92f * sk.tk * 0.6f).dp)
        .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, 0.5f) }
        .pointerInput(i) { detectTapGestures(onTap = { onTap() }) }
        .drawWithContent {
            drawContent()
            val l = lay ?: return@drawWithContent
            if (!fillOn) return@drawWithContent
            // (el relleno va por todos los renglones seguidos: el ancho de todos y dónde empieza cada uno)
            val n = l.lineCount; var all = 0f
            val w = FloatArray(n) { k -> (l.getLineRight(k) - l.getLineLeft(k)).also { all += it } }
            val at = prog.value * all; val soft = all * 0.06f
            var from = 0f
            for (k in 0 until n) {
                val left = l.getLineLeft(k); val x = at - from
                if (x > -soft) clipRect(left, l.getLineTop(k), left + min(w[k], x + soft), l.getLineBottom(k)) {
                    drawText(l, brush = Brush.horizontalGradient(0f to Color.White, 1f to LY_DIM, startX = left + x, endX = left + x + soft))
                }
                from += w[k]
            }
        }, style = st, onTextLayout = { lay = it })
}
