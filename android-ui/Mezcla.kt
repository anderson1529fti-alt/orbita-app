package __PACKAGE__

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fase 4, parte 2 · Cómo se pasa de una canción a la siguiente (las mezclas automáticas) y el sonido: lo mismo que
 * «planMix», «naFade», «keyCompat» y «normGainFor» de la parte web, número a número. Sin nada de Android.
 */
object Mezcla {
    /** Un análisis guardado (lo que devuelve [Analisis.analyze], leído de vuelta). */
    class Ana(private val m: Map<String, Any?>) {
        private fun d(k: String, def: Double): Double = (m[k] as? Number)?.toDouble() ?: def
        private fun has(k: String) = m[k] is Number
        val introStart = d("introStart", 0.0); val introLoud = d("introLoud", 0.0)
        val outroEnd: Double? = if (has("outroEnd")) d("outroEnd", 0.0) else null
        val fadeStart: Double? = if (has("fadeStart")) d("fadeStart", 0.0) else null
        val bpm = d("bpm", 0.0); val conf = d("conf", 0.0)
        val gridIn = if (has("gridIn")) d("gridIn", 0.0) else Double.NaN
        val gridOut = if (has("gridOut")) d("gridOut", 0.0) else Double.NaN
        val downIn = if (has("downIn")) d("downIn", 0.0) else Double.NaN
        val downOut = if (has("downOut")) d("downOut", 0.0) else Double.NaN
        val barSure = d("barSure", 0.0)
        val rms = if (has("rms")) d("rms", 0.0) else Double.NaN
        val key = if (has("key")) d("key", -1.0).toInt() else -1
        val keyConf = d("keyConf", 0.0)
        val energy = d("energy", 0.0); val bright = d("bright", 0.0); val rate = d("rate", 0.0)
    }

    /** Los ajustes de sonido (los de la web: «aDirect», «eq», «mixStyle», «mixLen»…). */
    object Aud {
        val direct get() = Prefs.bool("aDirect", false)
        val level get() = Prefs.bool("aLevel", true)
        val mix: String get() = Prefs.get("mixStyle") as? String ?: (if ((Prefs.get("xfade") as? Number)?.toDouble() == 0.0) "corte" else "auto")
        val mixLen: Double get() = Prefs.num("mixLen", 8.0)
    }

    private fun jr(x: Double): Double = Math.floor(x + 0.5)

    // ---------------------------------------------------------------- la tonalidad (rueda de Camelot)
    val KEY_NAMES = listOf("Do", "Do♯", "Re", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")
    private val CAM_MAJ = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
    private val CAM_MIN = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
    fun anaKey(a: Ana?): Int = if (a != null && a.key >= 0 && a.keyConf >= 0.1) a.key else -1
    fun keyName(k: Int): String = if (k < 0) "" else KEY_NAMES[k % 12] + (if (k >= 12) " menor" else " mayor")
    /** 1 la misma · 0,85 vecina o relativa · 0,5 no se sabe · 0,35 a dos pasos · 0 chocan. */
    fun keyCompat(ka: Int, kb: Int): Double {
        if (ka < 0 || kb < 0) return 0.5
        val an = if (ka < 12) CAM_MAJ[ka] else CAM_MIN[ka - 12]; val am = if (ka < 12) 'B' else 'A'
        val bn = if (kb < 12) CAM_MAJ[kb] else CAM_MIN[kb - 12]; val bm = if (kb < 12) 'B' else 'A'
        val d0 = abs(an - bn) % 12; val d = min(d0, 12 - d0)
        return if (am == bm) when (d) { 0 -> 1.0; 1 -> 0.85; 2 -> 0.35; else -> 0.0 } else when (d) { 0 -> 0.85; 1 -> 0.5; else -> 0.0 }
    }

    // ---------------------------------------------------------------- el volumen igualado
    const val TARGET_DB = -10.0
    fun normGain(a: Ana?, level: Boolean = Aud.level, direct: Boolean = Aud.direct): Double {
        if (!level || direct || a == null || !a.rms.isFinite()) return 1.0
        return 10.0.pow(max(-9.0, min(4.0, TARGET_DB - a.rms)) / 20)
    }

    // ---------------------------------------------------------------- el plan de una mezcla
    class Plan(val style: String, var start: Double, var len: Double, val bStart: Double, val rateB: Double, var swap: Double, var cut: Double,
               val aHold: Double, val beat: Double, val hk: Double, val gridA: Double, val gridB: Double, val beatB: Double, val bpmA: Double, val bpmB: Double)

    /** «planMix»: cómo pasar de a (que dura durA) a b, con la clase de mezcla y la duración elegidas. */
    fun plan(aA: Ana?, bA: Ana?, durA: Double, mixSel: String = Aud.mix, mixLenSel: Double = Aud.mixLen, direct: Boolean = Aud.direct): Plan {
        val aIntro = aA?.introStart ?: 0.0
        val aOutro = aA?.outroEnd ?: durA; val aFade = aA?.fadeStart ?: (if (aA == null) durA else null)
        val aBpm = aA?.bpm ?: 0.0; val aConf = aA?.conf ?: 0.0
        val bIntro = bA?.introStart ?: 0.0; val bBpm = bA?.bpm ?: 0.0; val bConf = bA?.conf ?: 0.0
        @Suppress("UNUSED_VARIABLE") val unused = aIntro
        val outEnd = min(durA, if (aOutro == 0.0 || aOutro.isNaN()) durA else aOutro)
        val fadeLen = if (aFade != null && aFade != 0.0) outEnd - aFade else 0.0; val hasFade = fadeLen > 2
        val tempoOK = aConf >= 2 && bConf >= 2 && aBpm > 0 && bBpm > 0
        var ratio = if (tempoOK) aBpm / bBpm else 1.0
        if (tempoOK) { if (ratio > 1.6) ratio /= 2 else if (ratio < 0.625) ratio *= 2 }
        val dev = abs(ratio - 1); val close = tempoOK && (dev <= 0.07 || (dev <= 0.1 && aConf >= 3 && bConf >= 3))
        val beatA = aBpm > 0 && aConf >= 1.5; val beatB = bBpm > 0 && bConf >= 1.5
        val hk = keyCompat(anaKey(aA), anaKey(bA))
        val want = max(2.0, min(16.0, mixLenSel))
        var style = mixSel; var len = want
        if (direct) style = if (style == "corte") "corte" else "suave"
        else if (style == "auto") style = if (close) (if (hk == 0.0) "filtro" else "dj") else (if (beatA && beatB) (if (hk == 0.0 && aConf >= 2) "eco" else "filtro") else "suave")
        else if (style == "dj" && !tempoOK) style = "suave"
        else if (style == "filtro" && !beatA) style = "suave"
        val beat = if (aBpm > 0) 60 / aBpm else 0.5; val bar = 4 * beat
        val gridOut = aA?.gridOut ?: Double.NaN; val downOut = aA?.downOut ?: Double.NaN
        val barsA = aBpm > 0 && downOut.isFinite() && (aA?.barSure ?: 0.0) >= 0.15
        fun snap(x: Double) = if (aBpm > 0 && gridOut.isFinite()) gridOut + jr((x - gridOut) / beat) * beat else x
        fun snapBar(x: Double) = if (barsA) downOut + jr((x - downOut) / bar) * bar else snap(x)
        val minStart = max(durA * 0.4, 1.0)
        var bStart = max(0.0, bIntro - 0.03); var start: Double; var swap = 0.0; var cut = 0.0; var rateB = 1.0; var aHold = 0.0
        val bOne: Double? = if (bBpm > 0) (if ((bA?.downIn ?: Double.NaN).isFinite() && (bA?.barSure ?: 0.0) >= 0.15) bA!!.downIn else (bA?.gridIn ?: Double.NaN)) else null
        fun barsFor(): Double { var n = max(2.0, jr(want / bar)); if (n > 2 && n % 2 != 0.0) n++; if (hk == 0.0) n = min(n, 4.0); return min(16.0, n) }
        when (style) {
            "corte" -> { len = 0.08; start = outEnd - len }
            "suave" -> {
                if (hasFade && mixSel != "suave") len = max(len, min(10.0, fadeLen + 2))
                start = outEnd - len
                if (hasFade) aHold = max(0.0, min(0.6, (aFade!! - start) / len))
            }
            "dj" -> {
                val E = if (hasFade) min(outEnd, aFade!! + 2 * beat) else outEnd
                val bars = barsFor()
                start = snapBar(E - bars * bar)
                if (start + 2 * beat > E) start -= bar
                len = max(4.0, min(E, start + bars * bar) - start)
                swap = max(1.5, if (barsA) jr(bars / 2) * bar else snap(start + len / 2) - start)
                if (bOne != null && bOne.isFinite()) bStart = max(0.0, bOne)
                rateB = if (close) ratio else 1.0
            }
            "filtro" -> {
                val E = if (hasFade) min(outEnd, aFade!! + 2 * beat) else outEnd; val bars = barsFor()
                start = snapBar(E - bars * bar - 0.2)
                if (E - start < min(6.0, want)) start -= bar
                len = max(min(4.0, want), min(want + bar, E - start))
                if (bOne != null && bOne.isFinite()) bStart = max(0.0, bOne)
            }
            else -> { start = min(snap(outEnd - 0.3), outEnd - 0.1); cut = 0.02; len = 2.8 }
        }
        if (start < minStart) { val d = minStart - start; start = minStart; len = max(0.08, len - d); swap = min(swap, len / 2); cut = min(cut, len / 2) }
        return Plan(style, start, len, bStart, rateB, swap, cut, aHold, beat, hk, gridOut, bA?.gridIn ?: Double.NaN, if (bBpm > 0) 60 / bBpm else 0.0, aBpm, bBpm)
    }

    /** Lo que el motor necesita para pasar de p a t («naFade»): fundido, dónde empieza, desde dónde entra y su estilo. */
    class Fade(var xf: Double = 0.0, var at: Double = 0.0, var st: Double = 0.0, var style: Int? = null, var beatA: Double? = null, var rate: Double? = null,
               var swap: Double? = null, var gridA: Double? = null, var gridB: Double? = null, var beatB: Double? = null)

    fun fade(pA: Ana?, tA: Ana?, durA: Double, albumPair: Boolean, mixSel: String = Aud.mix, mixLenSel: Double = Aud.mixLen, direct: Boolean = Aud.direct): Fade {
        if (albumPair) return Fade()
        val st = if (tA != null && tA.introStart > 0.3) max(0.0, tA.introStart - 0.03) else 0.0
        if (mixSel == "corte") {
            val oe = pA?.outroEnd
            val end = if (pA != null && oe != null && oe != 0.0) min(if (durA != 0.0) durA else oe, oe) else 0.0
            return Fade(0.0, if (end > 0 && durA - end > 0.4) end else 0.0, st)
        }
        if (durA == 0.0 || pA == null) return Fade(max(2.0, min(16.0, mixLenSel)), 0.0, st, 0)
        val pl = plan(pA, tA, durA, mixSel, mixLenSel, direct)
        val djOk = pl.style == "dj" && abs(pl.rateB - 1) <= 0.03
        val style = if (djOk) 2 else if (pl.style == "eco") 3 else if (pl.style == "dj" || pl.style == "filtro") 1 else 0
        if (pl.style == "dj" && !djOk) pl.len = min(pl.len, 6.0)
        val o = Fade(max(1.0, min(16.0, if (pl.style == "eco") 4.0 else pl.len)),
            if (pl.style == "eco") max(1.0, pl.start - 2 * (if (pl.beat != 0.0) pl.beat else 0.5)) else if (pl.start > 1) pl.start else 0.0,
            max(0.0, pl.bStart), style)
        if (style == 3) o.beatA = if (pl.beat != 0.0) pl.beat else 0.5
        if (style == 2) { o.rate = if (pl.rateB != 0.0) pl.rateB else 1.0; o.swap = pl.swap; o.gridA = if (pl.gridA.isFinite()) pl.gridA else 0.0; o.beatA = pl.beat; o.gridB = if (pl.gridB.isFinite()) pl.gridB else 0.0; o.beatB = pl.beatB }
        return o
    }

    // ---------------------------------------------------------------- el ecualizador
    val EQ_BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
    class Eq(val id: String, val name: String, val g: IntArray?)
    val EQ_PRESETS = listOf(
        Eq("plano", "Normal", intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        Eq("graves", "Aumento de graves", intArrayOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)),
        Eq("voces", "Aumento de voz", intArrayOf(-2, -2, -1, 0, 2, 3, 3, 2, 1, 0)),
        Eq("agudos", "Aumento de agudos", intArrayOf(0, 0, 0, 0, 0, 1, 2, 2, 3, 5)),
        Eq("pop", "Pop", intArrayOf(-1, 1, 3, 3, 2, 0, -1, -1, 0, 1)),
        Eq("rock", "Rock", intArrayOf(4, 3, 2, 0, -1, -1, 1, 2, 3, 4)),
        Eq("reggaeton", "Reguetón", intArrayOf(6, 5, 3, 1, 0, -1, 0, 1, 2, 2)),
        Eq("hiphop", "Hip hop", intArrayOf(5, 4, 2, 1, -1, -1, 1, 0, 1, 2)),
        Eq("electronica", "Electrónica", intArrayOf(5, 4, 2, 0, -1, 0, 1, 2, 4, 4)),
        Eq("latina", "Latina", intArrayOf(3, 2, 1, 0, -1, 0, 1, 3, 3, 2)),
        Eq("acustico", "Acústico", intArrayOf(2, 2, 1, 1, 1, 1, 2, 2, 2, 1)),
        Eq("clasica", "Clásica", intArrayOf(3, 2, 1, 0, 0, 0, -1, 0, 2, 3)),
        Eq("jazz", "Jazz", intArrayOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3)),
        Eq("auriculares", "Auriculares", intArrayOf(3, 2, 1, 0, 0, 0, 1, 1, 0, -1)),
        Eq("altavoz", "Altavoz del móvil", intArrayOf(-6, -4, -1, 1, 1, 1, 2, 2, 1, 0)),
        Eq("noche", "Noche (suave)", intArrayOf(-1, -1, 0, 0, 0, 0, -1, -2, -3, -4)),
        Eq("custom", "Personalizado", null)
    )

    /** Un ecualizador de antes de la 3.3 (5 bandas) pasa a las 10 con la misma curva. */
    fun eqMigrate(v: Any?): IntArray {
        val l = (v as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 }
        if (l != null && l.size == 10) return IntArray(10) { max(-12.0, min(12.0, jr(l[it]))).toInt() }
        if (l == null || l.size != 5) return IntArray(10)
        val of = doubleArrayOf(60.0, 250.0, 1000.0, 4000.0, 12000.0)
        return IntArray(10) { idx ->
            val f = EQ_BANDS[idx].toDouble()
            if (f <= of[0]) l[0].toInt() else if (f >= of[4]) l[4].toInt() else {
                var i = 0; while (f > of[i + 1]) i++
                val k = (kotlin.math.log2(f) - kotlin.math.log2(of[i])) / (kotlin.math.log2(of[i + 1]) - kotlin.math.log2(of[i]))
                jr(l[i] + (l[i + 1] - l[i]) * k).toInt()
            }
        }
    }

    fun eqMine(): List<Map<String, Any?>> = (Prefs.get("eqMine") as? List<*>)?.mapNotNull { Json.obj(it) } ?: emptyList()
    fun eqPreset(id: String): Eq? = EQ_PRESETS.firstOrNull { it.id == id } ?: if (id.startsWith("mine-")) eqMine().firstOrNull { "mine-" + Json.s(it["id"]) == id }?.let { Eq(id, Json.s(it["name"]), eqMigrate(it["g"])) } else null
    fun eqGains(): IntArray {
        if (!Prefs.bool("eqOn", true)) return IntArray(10)
        val id = Prefs.str("eq", "plano")
        if (id == "custom") return eqMigrate(Prefs.get("eqCustom"))
        return (eqPreset(id) ?: EQ_PRESETS[0]).g ?: IntArray(10)
    }
    fun eqName(): String = (eqPreset(Prefs.str("eq", "plano")) ?: EQ_PRESETS[0]).name
    fun eqActive(): Boolean = !Aud.direct && eqGains().any { it != 0 }

    val MIX_ORDER = listOf("auto", "dj", "filtro", "suave", "eco", "corte")
    val MIX_STYLES = mapOf(
        "auto" to ("Automática" to "Nova elige en cada cambio: mezcla de DJ si el ritmo encaja, filtro si las dos tienen golpe y un fundido si no. Cuánto dura, lo eliges tú abajo."),
        "dj" to ("DJ" to "Iguala el tempo, cuadra los golpes y pasa los graves de una canción a la otra, como en una cabina."),
        "suave" to ("Fundido" to "Una canción baja mientras la otra sube, sin silencios."),
        "eco" to ("Eco" to "La canción se despide con un eco al ritmo y entra la siguiente."),
        "filtro" to ("Filtro" to "La canción se va apagando hacia los graves mientras la siguiente entra desde los agudos, cuadrado al compás."),
        "corte" to ("Corte seco" to "Sin mezcla, pero quitando el silencio del final y del principio.")
    )
}
