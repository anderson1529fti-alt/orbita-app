package __PACKAGE__

import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fase 4, parte 2 · Las cuatro pistas de prueba (las que la web fabricaba con su sintetizador): las mismas notas,
 * acordes, tempos y sonidos, hechos aquí la primera vez que se ponen (y guardados en un archivo).
 */
object Demos {
    class Spec(val id: String, val title: String, val mood: String, val hue: Int, val bpm: Double, val cut: Double, val arp: Int, val kick: Boolean, val hat: Boolean, val wave: String, val prog: List<IntArray>)
    val DEMOS = listOf(
        Spec("demo-1", "Luz de las siete", "calma", 32, 84.0, 2600.0, 4, false, false, "triangle", listOf(intArrayOf(60, 64, 67, 71), intArrayOf(57, 60, 64, 67), intArrayOf(65, 69, 72, 76), intArrayOf(67, 71, 74, 77))),
        Spec("demo-2", "Andén 3", "energía", 6, 112.0, 3600.0, 8, true, true, "square", listOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(60, 64, 67), intArrayOf(55, 59, 62))),
        Spec("demo-3", "Lámpara de escritorio", "concentración", 200, 72.0, 1600.0, 2, false, false, "sine", listOf(intArrayOf(62, 65, 69, 72), intArrayOf(58, 62, 65, 69), intArrayOf(60, 64, 67, 70), intArrayOf(57, 61, 64, 67))),
        Spec("demo-4", "Viernes 23:59", "fiesta", 290, 124.0, 5200.0, 16, true, true, "sawtooth", listOf(intArrayOf(64, 67, 71), intArrayOf(60, 64, 67), intArrayOf(62, 66, 69), intArrayOf(59, 62, 66)))
    )
    const val DUR = 30
    private const val SR = 44100
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val waiting = HashMap<String, MutableList<() -> Unit>>()

    /** «addDemoEntries»: las canciones de prueba de la biblioteca (lo suyo —me gusta, escuchas— va aparte). */
    fun entries(): List<Track> {
        val stats = Json.obj(Prefs.get("demoStats")) ?: emptyMap()
        return DEMOS.map { s ->
            val st = Json.obj(stats[s.id]) ?: emptyMap()
            Track(s.id).apply {
                title = s.title; artist = "Órbita · demo"; album = "Pistas de prueba"; duration = DUR.toDouble(); addedAt = 0.0; demo = true
                extra["mood"] = s.mood; extra["hue"] = s.hue; coverHue = s.hue.toDouble()
                liked = Json.b(st["liked"]); plays = Json.l(st["plays"]).toInt(); lastPlayed = Json.d(st["lastPlayed"]); skips = Json.l(st["skips"]).toInt()
                listenSec = Json.d(st["listenSec"]); hours = Json.list(st["hours"])?.map { Json.d(it) }?.toMutableList()
                size = 44L + DUR * SR * 2; codec = "WAV"
            }
        }
    }

    private fun dir(): File = File(Store.root, "demo").apply { mkdirs() }
    /** El archivo ya hecho (o null si aún no). */
    fun file(id: String): File? { if (!Store.ready) return null; val f = File(dir(), "$id.wav"); return if (f.isFile && f.length() > 44) f else null }

    /** Lo fabrica (si no estaba) y avisa al acabar. */
    fun render(id: String, done: () -> Unit) {
        if (file(id) != null) { main.post(done); return }
        val s = DEMOS.firstOrNull { it.id == id } ?: return
        synchronized(waiting) {
            val l = waiting[id]
            if (l != null) { l.add(done); return }
            waiting[id] = mutableListOf(done)
        }
        exec.execute {
            try { write(File(dir(), "$id.wav"), synth(s)) } catch (t: Throwable) { Nativo.note("demo $id: ${t.message}") }
            val l = synchronized(waiting) { waiting.remove(id) } ?: emptyList<() -> Unit>()
            main.post { for (f in l) try { f() } catch (ignored: Throwable) { } }
        }
    }

    // ---------------------------------------------------------------- el sintetizador (como el «OfflineAudioContext» de la web)
    /** Una envolvente de volumen: valor fijo, rampa lineal o exponencial hasta cada punto (como un AudioParam). */
    private class Env(v0: Double) {
        private val ts = ArrayList<Double>(); private val vs = ArrayList<Double>(); private val kind = ArrayList<Int>()
        init { ts.add(0.0); vs.add(v0); kind.add(0) }
        fun set(v: Double, t: Double) { ts.add(t); vs.add(v); kind.add(0) }
        fun lin(v: Double, t: Double) { ts.add(t); vs.add(v); kind.add(1) }
        fun exp(v: Double, t: Double) { ts.add(t); vs.add(v); kind.add(2) }
        fun at(t: Double): Double {
            var i = 0
            while (i + 1 < ts.size && ts[i + 1] <= t) i++
            if (i + 1 >= ts.size) return vs[i]
            val k = kind[i + 1]; if (k == 0) return vs[i]
            val t0 = ts[i]; val t1 = ts[i + 1]; val v0 = vs[i]; val v1 = vs[i + 1]
            val x = if (t1 > t0) (t - t0) / (t1 - t0) else 1.0
            return if (k == 1) v0 + (v1 - v0) * x else v0 * (v1 / v0).pow(x)
        }
    }

    private class Biquad(type: String, f: Double, qDb: Double, sr: Int) {
        private var nb0 = 0.0; private var nb1 = 0.0; private var nb2 = 0.0; private var na1 = 0.0; private var na2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            // (la web: la Q de los pasos bajo y alto va en decibelios)
            val w0 = 2 * PI * min(f, sr / 2.0 - 1) / sr; val alpha = sin(w0) / (2 * 10.0.pow(qDb / 20)); val c = cos(w0)
            val b0: Double; val b1: Double; val b2: Double
            if (type == "lowpass") { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2 } else { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2 }
            val a0 = 1 + alpha
            nb0 = b0 / a0; nb1 = b1 / a0; nb2 = b2 / a0; na1 = -2 * c / a0; na2 = (1 - alpha) / a0
        }
        fun run(x: Double): Double { val y = nb0 * x + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
    }

    private class Voice(val start: Double, val stop: Double, val freq: Double, val wave: String, val env: Env, val toLp: Boolean, val sweep: Env? = null) { var ph = 0.0 }

    private fun mtof(n: Int) = 440 * 2.0.pow((n - 69) / 12.0)

    private fun osc(wave: String, ph: Double, dt: Double): Double = when (wave) {
        "sine" -> sin(2 * PI * ph)
        "triangle" -> 1 - 4 * abs(ph - 0.5)
        "square" -> { var v = if (ph < 0.5) 1.0 else -1.0; v += blep(ph, dt); v -= blep((ph + 0.5) % 1.0, dt); v }
        else -> { var v = 2 * ph - 1; v -= blep(ph, dt); v }
    }
    /** (para que las ondas cuadradas y de sierra no suenen ásperas: como las de la web, sin armónicos de más) */
    private fun blep(t0: Double, dt: Double): Double {
        var t = t0
        return if (t < dt) { t /= dt; t + t - t * t - 1 } else if (t > 1 - dt) { t = (t - 1) / dt; t * t + t + t + 1 } else 0.0
    }

    fun synth(s: Spec): FloatArray {
        val dur = DUR.toDouble(); val n = SR * DUR
        val master = Env(0.0001); master.lin(0.5, 1.2); master.set(0.5, dur - 2.5); master.lin(0.0001, dur)
        val lp = Biquad("lowpass", s.cut, 0.7, SR)
        val voices = ArrayList<Voice>()
        fun note(m: Int, t: Double, len: Double, type: String, vol: Double) {
            val g = Env(0.0001); g.set(0.0001, t); g.lin(vol, t + min(0.03, len / 4)); g.exp(0.0001, t + len)
            voices.add(Voice(t, t + len + 0.05, mtof(m), type, g, true))
        }
        fun kick(t: Double) {
            val f = Env(130.0); f.set(130.0, t); f.exp(42.0, t + 0.14)
            val g = Env(0.6); g.set(0.6, t); g.exp(0.0001, t + 0.28)
            voices.add(Voice(t, t + 0.3, 0.0, "sine", g, false, f))
        }
        val rnd = java.util.Random(7)
        val noise = DoubleArray((SR * 0.06).toInt()) { rnd.nextDouble() * 2 - 1 }
        val hats = ArrayList<Double>()
        val beat = 60 / s.bpm; val bar = beat * 4; val bars = ceil(dur / bar).toInt()
        for (b in 0 until bars) {
            val ch = s.prog[b % s.prog.size]; val t0 = b * bar
            for (m in ch) note(m, t0, bar * 1.02, "triangle", 0.05)
            note(ch[0] - 12, t0, bar * 0.95, "sine", 0.2)
            for (k in 0 until s.arp) { val m = ch[(k * (if (s.arp > 4) 3 else 1)) % ch.size] + 12; note(m, t0 + k * bar / s.arp, (bar / s.arp) * 0.85, s.wave, if (s.wave == "sine") 0.06 else 0.022) }
            if (s.kick && b > 0) for (k in 0 until 4) kick(t0 + k * beat)
            if (s.hat && b > 1) for (h in 0 until 8) if (h % 2 == 1) hats.add(t0 + h * beat / 2)
        }
        voices.sortBy { it.start }
        val out = FloatArray(n)
        val hp = Biquad("highpass", 7000.0, 1.0, SR)
        // el compresor (umbral −14 dB, proporción 3, rodilla 30, ataque 3 ms, salida 250 ms; con la ganancia de compensación)
        var envDb = 0.0
        val att = exp(-1.0 / (0.003 * SR)); val rel = exp(-1.0 / (0.25 * SR))
        val thr = -14.0; val ratio = 3.0; val knee = 30.0
        fun curve(x: Double): Double {
            val lo = thr - knee / 2; val hi = thr + knee / 2
            return when { x <= lo -> x; x >= hi -> thr + (x - thr) / ratio; else -> { val d = x - lo; x + (1 / ratio - 1) * d * d / (2 * knee) } }
        }
        val makeup = 10.0.pow(-curve(0.0) * 0.6 / 20)
        var active = ArrayList<Voice>(); var vi = 0; var hi = 0
        hats.sort()
        val hatVoices = ArrayList<Pair<Double, Int>>()   // (inicio, muestra) de los platillos que suenan
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            while (vi < voices.size && voices[vi].start <= t) { active.add(voices[vi]); vi++ }
            while (hi < hats.size && hats[hi] <= t) { hatVoices.add(hats[hi] to 0); hi++ }
            var toLp = 0.0; var direct = 0.0
            if (active.isNotEmpty()) {
                val keep = ArrayList<Voice>(active.size)
                for (v in active) {
                    if (t >= v.stop) continue
                    keep.add(v)
                    val f = v.sweep?.at(t) ?: v.freq
                    val dt = f / SR
                    val x = osc(v.wave, v.ph, dt) * v.env.at(t)
                    v.ph += dt; if (v.ph >= 1) v.ph -= Math.floor(v.ph)
                    if (v.toLp) toLp += x else direct += x
                }
                active = keep
            }
            if (hatVoices.isNotEmpty()) {
                val it = hatVoices.listIterator()
                while (it.hasNext()) {
                    val (t0, k) = it.next()
                    if (k >= noise.size) { it.remove(); continue }
                    val g = 0.07 * (0.0001 / 0.07).pow(min(1.0, (t - t0) / 0.05))
                    direct += hp.run(noise[k]) * g
                    it.set(t0 to k + 1)
                }
            }
            val m = (lp.run(toLp) + direct) * master.at(t)
            // (el compresor mira el nivel y baja lo que pasa del umbral)
            val lvl = 20 * kotlin.math.log10(abs(m) + 1e-9)
            val red = lvl - curve(lvl)
            envDb = if (red > envDb) att * envDb + (1 - att) * red else rel * envDb + (1 - rel) * red
            out[i] = (m * 10.0.pow(-envDb / 20) * makeup).toFloat()
        }
        return out
    }

    private fun write(f: File, d: FloatArray) {
        val tmp = File(f.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { r ->
            r.setLength(0)
            val n = d.size; val hdr = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            hdr.put("RIFF".toByteArray()); hdr.putInt(36 + n * 2); hdr.put("WAVE".toByteArray()); hdr.put("fmt ".toByteArray())
            hdr.putInt(16); hdr.putShort(1); hdr.putShort(1); hdr.putInt(SR); hdr.putInt(SR * 2); hdr.putShort(2); hdr.putShort(16)
            hdr.put("data".toByteArray()); hdr.putInt(n * 2)
            r.write(hdr.array())
            val buf = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (x in d) { val s = max(-1.0, min(1.0, x.toDouble())); buf.putShort((if (s < 0) s * 0x8000 else s * 0x7fff).toInt().toShort()) }
            r.write(buf.array())
        }
        tmp.renameTo(f)
    }
}
