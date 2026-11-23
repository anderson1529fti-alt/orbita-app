package __PACKAGE__

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Fase 4, parte 2 · El análisis de cada canción (tempo, golpes, compases, silencios, final que se apaga, volumen,
 * tonalidad y lo que Nova usa para su mapa de tu música). Es «analyzeBuffer» de la parte web, número a número:
 * los análisis que ya estaban guardados siguen valiendo y los nuevos salen iguales. Sin nada de Android (se prueba
 * en el ordenador contra la web); la canción la descodifica [Analizador].
 */
object Analisis {
    const val V = 4

    /** JS «Math.round»: el .5 hacia arriba. */
    private fun jr(x: Double): Double = Math.floor(x + 0.5)
    private fun r1(x: Double) = jr(x * 1000) / 1000
    private fun r2(x: Double) = jr(x * 100) / 100

    fun analyze(c0: FloatArray, c1: FloatArray?, sr: Int): LinkedHashMap<String, Any> {
        val n = c0.size
        val hop = 128
        val nf = max(1, n / hop)
        val fps = sr.toDouble() / hop
        val dur = n.toDouble() / sr
        // cada 11,6 ms: la energía, la de los graves (paso bajo de un polo, ~150 Hz) y la de la pendiente (agudos)
        val ms = FloatArray(nf); val lo = FloatArray(nf)
        var lp = 0.0; var prevV = 0.0; var hfAll = 0.0; var allE = 0.0
        run {
            var i = 0
            for (f in 0 until nf) {
                var s = 0.0; var sl = 0.0
                for (k in 0 until hop) {
                    val v: Double = if (c1 != null) (c0[i].toDouble() + c1[i].toDouble()) * 0.5 else c0[i].toDouble()
                    s += v * v; lp += 0.082 * (v - lp); sl += lp * lp
                    val d = v - prevV; hfAll += d * d; prevV = v
                    i++
                }
                ms[f] = (s / hop).toFloat(); lo[f] = (sl / hop).toFloat(); allE += s
            }
        }
        var peak = 0.0; for (f in 0 until nf) if (ms[f] > peak) peak = ms[f].toDouble()
        if (peak < 1e-8) {
            val o = LinkedHashMap<String, Any>()
            o["dur"] = r1(dur); o["introStart"] = 0; o["introLoud"] = 0; o["fadeStart"] = r1(dur); o["outroEnd"] = r1(dur)
            o["rms"] = -60; o["bpm"] = 0; o["conf"] = 0; o["gridIn"] = 0; o["gridOut"] = 0
            return o
        }
        // silencio = más de ~42 dB por debajo de lo fuerte de la canción
        val sorted = ms.copyOf(); sorted.sort()
        val p95 = sorted[Math.floor(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].toDouble().let { if (it == 0.0) peak else it }
        val thr = max(1.6e-5, p95 * 6.3e-5)
        var fi = 0; while (fi < nf - 1 && ms[fi] < thr) fi++
        var fo = nf - 1; while (fo > fi && ms[fo] < thr) fo--
        val introStart = max(0.0, fi / fps - 0.02); val outroEnd = min(dur, (fo + 1) / fps + 0.05)
        var sum = 0.0; var cnt = 0; for (f in fi..fo) if (ms[f] > thr * 100) { sum += ms[f]; cnt++ }
        val rmsDb = if (cnt > 0) 10 * log10(sum / cnt + 1e-12) else -60.0
        // la energía de cada segundo: dónde se pone fuerte y dónde empieza a apagarse
        val W = max(1, jr(fps).toInt())
        val sm = FloatArray(nf)
        run { var acc = 0.0; for (f in 0 until nf) { acc += ms[f]; if (f >= W) acc -= ms[f - W]; sm[f] = (acc / min(f + 1, W)).toFloat() } }
        val b0 = Math.floor(fi + (fo - fi) * 0.2).toInt(); val b1 = max(b0 + 1, Math.floor(fi + (fo - fi) * 0.8).toInt())
        val body = sm.copyOfRange(b0, min(b1, nf).coerceAtLeast(b0)); body.sort()
        val med = if (body.isNotEmpty()) body[body.size shr 1].toDouble() else 0.0
        var ff = fo; while (ff > fi && sm[ff] < med * 0.3) ff--
        var fadeStart = min(outroEnd, max(introStart, (ff - W / 2.0) / fps))
        if (outroEnd - fadeStart < 1.5 || outroEnd - fadeStart > 30) fadeStart = outroEnd
        var fl = fi; while (fl < fo && sm[fl] < med * 0.3) fl++
        val introLoud = max(introStart, (fl - W / 2.0) / fps)
        // la fuerza de los golpes: subidas de la energía (en logaritmo), menos la media de alrededor
        val on = FloatArray(nf)
        run { var prev = ln(ms[0] + 1e-9); for (f in 1 until nf) { val l = ln(ms[f] + 1e-9); on[f] = max(0.0, l - prev).toFloat(); prev = l } }
        run {
            val M = max(2, jr(fps * 0.35).toInt()); val tmp = on.copyOf(); var acc = 0.0
            for (f in 0 until nf) { acc += tmp[f]; if (f >= M) acc -= tmp[f - M]; on[f] = max(0.0, tmp[f] - acc / min(f + 1, M)).toFloat() }
        }
        fun onAt(k: Int): Double = if (k in 0 until nf) on[k].toDouble() else 0.0
        // el tempo: autocorrelación de los golpes entre 70 y 170 BPM, tirando hacia ~110
        var bpm = 0.0; var conf = 0.0; var period = 0.0
        val s0 = min(fo, fi + Math.floor(4 * fps).toInt()); val s1 = min(fo, s0 + Math.floor(150 * fps).toInt()); val N = s1 - s0
        if (N > fps * 10) {
            val lmin = Math.floor(fps * 60 / 170).toInt(); val lmax = Math.ceil(fps * 60 / 70).toInt()
            val sc = DoubleArray(2 * lmax + 3)
            for (L in lmin - 1..2 * lmax + 2) { var s = 0.0; for (i in s0 until s1 - L) s += on[i].toDouble() * on[i + L]; sc[L] = s / max(1, N - L) }
            var mean = 0.0; var sd = 0.0; val cntL = lmax - lmin + 1
            for (L in lmin..lmax) mean += sc[L]; mean /= cntL
            for (L in lmin..lmax) sd += (sc[L] - mean) * (sc[L] - mean)
            sd = sqrt(sd / cntL).let { if (it == 0.0 || it.isNaN()) 1e-12 else it }
            var best = Double.NEGATIVE_INFINITY; var bL = lmin
            for (L in lmin..lmax) {
                val b = 60 * fps / L; val q = log2(b / 110) / 0.6; val w = exp(-0.5 * q * q)
                val v = (sc[L] + 0.5 * sc[2 * L]) * (0.6 + 0.4 * w)
                if (v > best) { best = v; bL = L }
            }
            val y0 = sc[bL - 1]; val y1 = sc[bL]; val y2 = sc[bL + 1]; val den = y0 - 2 * y1 + y2
            period = bL + (if (den != 0.0) max(-0.5, min(0.5, 0.5 * (y0 - y2) / den)) else 0.0)
            conf = (sc[bL] - mean) / sd
            // la duración exacta del golpe, con un peine sobre todo el trozo analizado
            var bestS = -1.0; var bp = period
            var p = period * 0.985
            while (p <= period * 1.015) {
                var ph = 0.0
                while (ph < p) {
                    var s = 0.0; var x = s0 + ph
                    while (x < s1) { s += onAt(jr(x).toInt()); x += p }
                    if (s > bestS) { bestS = s; bp = p }
                    ph += 1.0
                }
                p += 0.02
            }
            period = bp; bpm = 60 * fps / period
        }
        // la rejilla de golpes: el golpe más cercano a «anchor», ajustado en una ventana corta alrededor
        fun beatNear(fa: Int, fb: Int, anchor: Int): Double {
            if (period == 0.0) return anchor / fps
            var bestS = -1.0; var bestPh = 0.0
            var ph = 0.0
            while (ph < period) {
                var s = 0.0; var x = fa + ph
                while (x < fb) { val k = jr(x).toInt(); s += onAt(k) + 0.5 * (onAt(k - 1) + onAt(k + 1)); x += period }
                if (s > bestS) { bestS = s; bestPh = ph }
                ph += 0.5
            }
            val first = fa + bestPh; val k = jr((anchor - first) / period)
            return max(0.0, first + k * period) / fps
        }
        val gridIn = beatNear(fi, min(fo, fi + Math.floor(10 * fps).toInt()), fi)
        val outAnchor = max(fi, fo - Math.floor(12 * fps).toInt())
        val gridOut = beatNear(max(fi, outAnchor - Math.floor(6 * fps).toInt()), min(fo, outAnchor + Math.floor(6 * fps).toInt()), outAnchor)
        // los compases: de cada 4 golpes, el «uno» es el que lleva más ataque de graves
        val lon = FloatArray(nf)
        for (f in 1 until nf) lon[f] = max(0.0, sqrt(lo[f].toDouble()) - sqrt(lo[f - 1].toDouble())).toFloat()
        fun lonAt(k: Int): Double = if (k in 0 until nf) lon[k].toDouble() else 0.0
        fun barPhase(g: Double, fromF: Int, toF: Int): Pair<Double, Double> {
            if (period == 0.0) return g to 0.0
            val sc = DoubleArray(4); val beatF = period; val g0 = g * fps
            for (k in 0 until 4) { var x = g0 + k * beatF; while (x < toF) { if (x >= fromF) { val j = jr(x).toInt(); sc[k] += lonAt(j) + 0.5 * (lonAt(j - 1) + lonAt(j + 1)) }; x += 4 * beatF } }
            var bi = 0; for (k in 1 until 4) if (sc[k] > sc[bi]) bi = k
            val srt = sc.sortedDescending()
            return (g0 + bi * beatF) / fps to (if (srt[0] > 0) (srt[0] - srt[1]) / srt[0] else 0.0)
        }
        val dIn = barPhase(gridIn, fi, min(fo, fi + Math.floor(40 * fps).toInt()))
        val dOut = barPhase(gridOut, max(fi, fo - Math.floor(40 * fps).toInt()), fo)
        // para el mapa de tu música: energía, brillo y cuánto ritmo lleva
        var peaks = 0; for (f in s0 + 1 until s1 - 1) if (on[f] > 0.35 && on[f] >= on[f - 1] && on[f] > on[f + 1]) peaks++
        val rate = if (N > 0) peaks / (N / fps) else 0.0
        fun c01(x: Double) = max(0.0, min(1.0, x))
        val bright = c01((log10(hfAll / (allE + 1e-12) + 1e-6) + 2) / 1.6)
        val energy = c01(0.45 * c01((rmsDb + 28) / 19) + 0.35 * c01((rate - 1) / 6) + 0.2 * c01((bpm - 70) / 80) * (if (conf >= 1.5) 1.0 else 0.5))
        // 4.1 · la tonalidad (para mezclas que encajan): las notas que más suenan, comparadas con las 24 tonalidades
        var key = -1; var keyConf = 0.0
        try {
            val KN = 4096; val KH = KN shr 1
            val k0 = Math.floor(introStart * sr).toInt(); val k1 = min(n - KN, Math.floor(outroEnd * sr).toInt() - KN); val span = k1 - k0
            val nw = if (span > 0) min(180, span / 2048) else 0
            if (nw >= 16) {
                val win = DoubleArray(KN) { (0.5 - 0.5 * cos(2 * Math.PI * it / (KN - 1))).toFloat().toDouble() }
                val pcOf = IntArray(KH) { -1 }; val wOf = FloatArray(KH)
                for (b in 1 until KH) {
                    val f = b.toDouble() * sr / KN; if (f < 60 || f > 2100) continue
                    val m = 12 * log2(f / 440) + 69; val nr = jr(m); if (abs(m - nr) > 0.4) continue
                    pcOf[b] = (((nr.toInt() % 12) + 12) % 12); wOf[b] = (1 - abs(m - nr) * 1.5).toFloat()
                }
                val re = DoubleArray(KN); val im = DoubleArray(KN); val fc = DoubleArray(12); val chroma = DoubleArray(12)
                fun fftK() {
                    var j = 0
                    for (i in 1 until KN) {
                        var bit = KN shr 1
                        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                        j = j xor bit
                        if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
                    }
                    var len = 2
                    while (len <= KN) {
                        val ang = -2 * Math.PI / len; val wr = cos(ang); val wi = kotlin.math.sin(ang)
                        var i = 0
                        while (i < KN) {
                            var cr = 1.0; var ci = 0.0
                            for (q in 0 until len / 2) {
                                val u = i + q; val v = u + len / 2
                                val xr = re[v] * cr - im[v] * ci; val xi = re[v] * ci + im[v] * cr
                                re[v] = re[u] - xr; im[v] = im[u] - xi; re[u] += xr; im[u] += xi
                                val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                            }
                            i += len
                        }
                        len = len shl 1
                    }
                }
                val stepK = span / nw
                for (w in 0 until nw) {
                    val off = k0 + w * stepK
                    for (i in 0 until KN) { val j = off + i; re[i] = (if (c1 != null) (c0[j].toDouble() + c1[j]) * 0.5 else c0[j].toDouble()) * win[i]; im[i] = 0.0 }
                    fftK(); java.util.Arrays.fill(fc, 0.0); var tot = 0.0
                    for (b in 1 until KH) { val pc = pcOf[b]; if (pc < 0) continue; val v = sqrt(sqrt(re[b] * re[b] + im[b] * im[b])) * wOf[b]; fc[pc] += v; tot += v }
                    if (tot > 1e-6) for (p in 0 until 12) chroma[p] += fc[p] / tot
                }
                val MAJ = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
                val MIN = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
                fun pear(prof: DoubleArray, r: Int): Double {
                    var ma = 0.0; var mb = 0.0
                    for (i in 0 until 12) { ma += chroma[(i + r) % 12]; mb += prof[i] }
                    ma /= 12; mb /= 12
                    var s = 0.0; var sa = 0.0; var sb = 0.0
                    for (i in 0 until 12) { val x = chroma[(i + r) % 12] - ma; val y = prof[i] - mb; s += x * y; sa += x * x; sb += y * y }
                    return s / sqrt(sa * sb + 1e-12)
                }
                val sc = ArrayList<Pair<Int, Double>>()
                for (r in 0 until 12) { sc.add(r to pear(MAJ, r)); sc.add(12 + r to pear(MIN, r)) }
                // (como «sort» de JS: estable, de mayor a menor)
                val srt = sc.sortedWith { a, b -> b.second.compareTo(a.second) }
                key = srt[0].first; keyConf = max(0.0, min(1.0, (srt[0].second - srt[1].second) * 4 + (srt[0].second - 0.5)))
            }
        } catch (t: Throwable) { key = -1; keyConf = 0.0 }
        val o = LinkedHashMap<String, Any>()
        o["key"] = key; o["keyConf"] = r2(keyConf); o["dur"] = r1(dur); o["introStart"] = r1(introStart); o["introLoud"] = r1(introLoud)
        o["fadeStart"] = r1(fadeStart); o["outroEnd"] = r1(outroEnd); o["rms"] = jr(rmsDb * 10) / 10; o["bpm"] = jr(bpm * 10) / 10
        o["conf"] = jr(conf * 100) / 100; o["gridIn"] = r1(gridIn); o["gridOut"] = r1(gridOut); o["downIn"] = r1(dIn.first); o["downOut"] = r1(dOut.first)
        o["barSure"] = r2(min(dIn.second, dOut.second)); o["energy"] = r2(energy); o["bright"] = r2(bright); o["rate"] = r2(rate)
        return o
    }
}
