package __PACKAGE__

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fase 4, parte 2 · Lo que se sabe de cada canción ([Analisis]): se lee lo guardado o se analiza (la canción se
 * descodifica aquí, a 11 025 Hz como la web, en un hilo aparte y sin prisa), y se guarda igual que lo guardaba la web.
 */
object Analizador {
    private val cache = ConcurrentHashMap<String, Mezcla.Ana>()
    private val raw = ConcurrentHashMap<String, Map<String, Any?>>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "orbita-analisis").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    const val SR = 11025

    /** El análisis guardado de una canción (o null si no lo hay o es de otra versión del archivo). */
    fun get(t: Track?): Mezcla.Ana? {
        if (t == null || t.net) return null
        cache[t.id]?.let { return it }
        if (t.id in missing) return null
        val v = try { Store.rec("analysis", t.id) } catch (e: Throwable) { null }
        val m = Json.obj(Json.parseOr(v, null))
        if (m != null && Json.l(m["v"]).toInt() == Analisis.V && (t.size == 0L || Json.l(m["size"]) == 0L || Json.l(m["size"]) == t.size)) {
            val a = Mezcla.Ana(m); cache[t.id] = a; raw[t.id] = m; return a
        }
        missing.add(t.id)
        return null
    }

    fun rawOf(t: Track?): Map<String, Any?>? { if (t == null) return null; get(t); return raw[t.id] }

    /** «scheduleAnalysis»: la analiza si hace falta (las de más de 15 minutos, no) y avisa al acabar. */
    fun schedule(t: Track, done: (() -> Unit)? = null) {
        if (t.net || t.duration > 900 || get(t) != null || !pending.add(t.id)) return
        exec.execute {
            try {
                val src = Lib.src(t)
                if (src != null && (t.size == 0L || t.size <= 80_000_000L)) {
                    val pcm = decode(src)
                    if (pcm != null) {
                        val res = Analisis.analyze(pcm.first, pcm.second, SR)
                        res["v"] = Analisis.V; res["id"] = t.id; res["size"] = if (t.size > 0) t.size else 0L
                        Store.putRec("analysis", t.id, Json.str(res))
                        cache[t.id] = Mezcla.Ana(res); raw[t.id] = res; missing.remove(t.id)
                        Nativo.note("análisis: ${t.title} · ${res["bpm"]} BPM")
                        if (done != null) main.post(done)
                    }
                }
            } catch (e: Throwable) { Nativo.note("análisis de ${t.title}: ${e.message}") }
            pending.remove(t.id)
        }
    }

    /** La canción en muestras a 11 025 Hz (uno o dos canales), como la descodifica la web para analizarla. */
    fun decode(src: String): Pair<FloatArray, FloatArray?>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val ctx = Nativo.act ?: return null
            if (src.startsWith("content:") || src.startsWith("file:")) ex.setDataSource(ctx, Uri.parse(src), null) else ex.setDataSource(src)
            var tr = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { tr = i; fmt = f; break } }
            if (tr < 0 || fmt == null) return null
            ex.selectTrack(tr)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs > 900_000_000L) return null
            val c = MediaCodec.createDecoderByType(mime); codec = c
            c.configure(fmt, null, null, 0); c.start()
            val cap = ((if (durUs > 0) durUs / 1e6 else 600.0) * SR).toInt() + SR
            var L = FloatArray(cap); var R: FloatArray? = null
            var n = 0
            // (de la frecuencia de la canción a 11 025: la media de las muestras de cada hueco)
            var accL = 0.0; var accR = 0.0; var accN = 0; var pos = 0.0; var step = SR.toDouble() / inRate
            var float = false
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); step = SR.toDouble() / inRate
                    float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    if (ch >= 2 && R == null) R = FloatArray(L.size)
                    val bb = ob.slice().order(java.nio.ByteOrder.nativeOrder())
                    val frames = if (float) info.size / (4 * ch) else info.size / (2 * ch)
                    val fb = if (float) bb.asFloatBuffer() else null; val sb = if (!float) bb.asShortBuffer() else null
                    for (k in 0 until frames) {
                        val l: Float; val r: Float
                        if (fb != null) { l = fb.get(k * ch); r = if (ch >= 2) fb.get(k * ch + 1) else l }
                        else { l = sb!!.get(k * ch) / 32768f; r = if (ch >= 2) sb.get(k * ch + 1) / 32768f else l }
                        accL += l; accR += r; accN++
                        pos += step
                        if (pos >= 1.0) {
                            pos -= 1.0
                            if (n >= L.size) { L = L.copyOf(L.size + SR * 60); R = R?.copyOf(L.size) }
                            L[n] = (accL / accN).toFloat(); R?.let { it[n] = (accR / accN).toFloat() }
                            n++; accL = 0.0; accR = 0.0; accN = 0
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (n < SR) return null
            return L.copyOf(n) to R?.copyOf(n)
        } catch (e: Throwable) {
            Nativo.note("descodificar: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (ignored: Throwable) { }
            try { codec?.release() } catch (ignored: Throwable) { }
            try { ex.release() } catch (ignored: Throwable) { }
        }
    }
}
