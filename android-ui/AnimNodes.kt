package __PACKAGE__

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 5.3 · Las animaciones de CSS de siempre que Android sabe hacer solo, por su nombre: el aro de Nova y la rueda que
 * espera («spin»), la luz que flota detrás del resumen del año, la bienvenida y el despertador («rcFloat»), las barras
 * de Nova («obBar») y cómo entran las cosas («rcIn», «rcPop»). La web dice cuál es, cuánto dura, su espera, cuántas
 * veces, si va y vuelve, su curva y por dónde iba; aquí se sigue desde ahí, con el reloj de Android, como lo haría CSS.
 */
@Immutable
internal class MAnim(
    val k: String, val d: Float, val dl: Float, val n: Float, val al: Boolean, val rv: Boolean,
    val e: FloatArray, val f: Int, val t: Double, val p: Boolean, val at: Long
) {
    /** Dónde va (de 0 a 1, ya con su curva), o null si ahora no hace nada (antes de empezar sin «backwards»…). */
    fun prog(now: Long): Float? {
        val time = t + if (p) 0.0 else (now - at).toDouble()
        val dur = max(1f, d).toDouble()
        val act = time - dl
        if (act < 0) return if ((f and 2) != 0) ease(dir(0, 0.0)) else null
        if (n >= 0f && act >= n * dur) {
            if ((f and 1) == 0) return null
            // (al final: donde acaba la última vuelta)
            val last = max(0, kotlin.math.ceil(n.toDouble()).toInt() - 1)
            val fr = n - floor(n.toDouble()).toFloat()
            return ease(dir(last, if (fr > 0f) fr.toDouble() else 1.0))
        }
        val it = floor(act / dur)
        return ease(dir(it.toInt(), (act - it * dur) / dur))
    }
    private fun dir(it: Int, fr: Double): Double {
        var x = fr
        if (rv) x = 1.0 - x
        if (al && it % 2 != 0) x = 1.0 - x
        return x
    }
    private fun ease(x: Double): Float = cubic(e, x.toFloat().coerceIn(0f, 1f))
    /** ¿Ya acabó y no va a cambiar más? (las que no acaban nunca, nunca) */
    fun over(now: Long): Boolean {
        if (n < 0f || p) return n >= 0f && p
        val time = t + (now - at).toDouble()
        return time - dl >= n * max(1f, d)
    }
}

/** (Para las pruebas) cuántos fotogramas han movido las piezas que se mueven solas, y las barras del resumen. */
internal object MAnimStat { @Volatile var frames = 0; @Volatile var bars = 0 }

/** La curva «cubic-bezier(x1, y1, x2, y2)» de CSS en x (buscando su t). */
internal fun cubic(e: FloatArray, x: Float): Float {
    if (e.size != 4) return x
    if (x <= 0f) return 0f; if (x >= 1f) return 1f
    val x1 = e[0]; val y1 = e[1]; val x2 = e[2]; val y2 = e[3]
    if (x1 == y1 && x2 == y2) return x   // (lineal)
    var lo = 0f; var hi = 1f; var t = x
    repeat(28) {
        val bx = 3f * (1f - t) * (1f - t) * t * x1 + 3f * (1f - t) * t * t * x2 + t * t * t
        if (bx < x) lo = t else hi = t
        t = (lo + hi) / 2f
    }
    return 3f * (1f - t) * (1f - t) * t * y1 + 3f * (1f - t) * t * t * y2 + t * t * t
}

internal fun mAnims(a: JSONArray?): List<MAnim> {
    if (a == null || a.length() == 0) return emptyList()
    val now = SystemClock.uptimeMillis()
    val out = ArrayList<MAnim>(a.length())
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val k = o.optString("k", ""); if (k.isEmpty()) continue
        val ea = o.optJSONArray("e")
        val e = if (ea != null && ea.length() == 4) FloatArray(4) { ea.optDouble(it, 0.0).toFloat() } else floatArrayOf(.25f, .1f, .25f, 1f)
        out.add(MAnim(k, o.optDouble("d", 0.0).toFloat(), o.optDouble("dl", 0.0).toFloat(), o.optDouble("n", 1.0).toFloat(), o.optInt("al") == 1, o.optInt("rv") == 1,
            e, o.optInt("f", 0), o.optDouble("t", 0.0), o.optInt("p") == 1, now))
    }
    return out
}

/** Lo que hace cada una, aplicado a la capa de la pieza (desde su centro, como «transform-origin» por defecto). */
internal fun GraphicsLayerScope.mApply(list: List<MAnim>, now: Long, d: Float, w: Float, h: Float) {
    for (a in list) {
        val e = a.prog(now) ?: continue
        when (a.k) {
            "spin" -> rotationZ += 360f * e
            // (translate3d(-4%, 3%, 0) scale(1.06): los % son de su propia caja)
            "rcFloat" -> { translationX += -0.04f * w * e; translationY += 0.03f * h * e; val s = 1f + 0.06f * e; scaleX *= s; scaleY *= s }
            // (de opacity 0, translateY(18px) scale(.98) a como es)
            "rcIn" -> { alpha *= e; translationY += 18f * d * (1f - e); val s = 0.98f + 0.02f * e; scaleX *= s; scaleY *= s }
            // (de rotate(-8deg) scale(.85), opacity 0 a como es)
            "rcPop" -> { alpha *= e; rotationZ += -8f * (1f - e); val s = 0.85f + 0.15f * e; scaleX *= s; scaleY *= s }
        }
    }
}

/** Una pieza que se mueve sola: su capa sigue el reloj (y, las barras de Nova, su alto, desde su centro). */
@Composable
internal fun MAnimBox(c: MNode, content: @Composable () -> Unit) {
    val clk = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(c.an) {
        while (true) {
            val now = withFrameMillis { SystemClock.uptimeMillis() }
            clk.longValue = now; MAnimStat.frames++
            if (c.an.all { it.over(now) }) break
        }
    }
    val d = LocalDensity.current.density
    val bar = c.an.firstOrNull { it.k == "obBar" }
    var m: Modifier = Modifier.graphicsLayer { mApply(c.an, clk.longValue, d, c.w * d, c.h * d) }
    if (bar != null) m = m.layout { ms, cs ->
        // (@keyframes obBar: de 6 px a 26 px de alto; la fila las tiene centradas)
        val full = if (cs.hasBoundedHeight) cs.maxHeight else (26f * d).roundToInt()
        val e = bar.prog(clk.longValue)
        val h = if (e == null) full else max(0, ((6f + 20f * e) * d).roundToInt())
        val p = ms.measure(Constraints.fixed(cs.maxWidth, h))
        layout(cs.maxWidth, full) { p.place(0, ((full - h) / 2f).roundToInt()) }
    }
    Box(m, propagateMinConstraints = true) { content() }
}

/**
 * Las barras de arriba de «Tu año en Órbita»: tantas como páginas; las de antes, llenas; la de ahora se llena aquí sola
 * al ritmo de la web (lo que dura la página), salvo en pausa o en la última.
 */
@Composable
internal fun MRcBars(c: MNode) {
    val o = remember(c.v) { try { JSONObject(c.v) } catch (t: Throwable) { JSONObject() } }
    val at = remember(c.v) { SystemClock.uptimeMillis() }
    val n = max(0, o.optInt("n", 0)); val i = o.optInt("i", 0)
    val f0 = o.optDouble("f", 0.0).toFloat(); val dur = max(1f, o.optDouble("d", 1.0).toFloat()); val paused = o.optInt("p") == 1
    val tc = o.optLong("tc", 0x38FFFFFFL).toInt(); val fc = o.optLong("fc", 0xFFFFFFFFL).toInt()
    val g = o.optDouble("g", 4.0).toFloat(); val r = o.optDouble("r", 3.0).toFloat(); val bh = o.optDouble("bh", 3.0).toFloat()
    val clk = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    if (!paused && f0 < 1f) LaunchedEffect(c.v) {
        while (true) {
            val now = withFrameMillis { SystemClock.uptimeMillis() }
            clk.longValue = now; MAnimStat.bars++
            if (f0 + (now - at) / dur >= 1f) break
        }
    }
    var m: Modifier = Modifier.fillMaxSize()
    if (c.o < 1f) m = m.alpha(c.o)
    Canvas(m) {
        if (n <= 0) return@Canvas
        val now = clk.longValue
        val f = if (paused) f0 else min(1f, f0 + (now - at) / dur)
        val gw = g * density; val bw = (size.width - gw * (n - 1)) / n; val hh = bh * density; val rr = r * density
        val top = (size.height - hh) / 2f
        for (k in 0 until n) {
            val x = k * (bw + gw)
            drawRoundRect(Color(tc), Offset(x, top), Size(bw, hh), CornerRadius(rr, rr))
            val fill = if (k < i) 1f else if (k == i) f else 0f
            if (fill > 0f) {
                val clip = Path().apply { addRoundRect(RoundRect(x, top, x + bw, top + hh, CornerRadius(rr, rr))) }
                clipPath(clip) { drawRoundRect(Color(fc), Offset(x, top), Size(bw * fill, hh), CornerRadius(rr, rr)) }
            }
        }
    }
}
