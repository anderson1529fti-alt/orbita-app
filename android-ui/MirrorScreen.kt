package __PACKAGE__

import android.graphics.BlurMaskFilter
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 4.7 · Fase 2, pasos 4 y 5: «Buscar» y «Ajustes». Son pantallas largas y hechas de muchas piezas distintas, así que
 * en vez de un lector a mano para cada una, la app manda su pantalla de siempre entera (que sigue debajo, invisible,
 * haciendo todo el trabajo): cada caja con su sitio, sus colores y sus letras, panel a panel. Aquí Android lo dibuja
 * tal cual y lo mueve; lo que se toca, se escribe o se arrastra aquí, la app lo hace allí en su control de siempre.
 */

// ---------------------------------------------------------------- lo que manda la app
@Immutable
internal data class MStyle(val fs: Float, val fw: Int, val ff: String, val col: Int, val ls: Float, val fl: Int, val sw: Float)

/** Un degradado de CSS: t = «l» (recto, con su ángulo) o «c» (en abanico, con el ángulo donde empieza). */
@Immutable
internal data class MGrad(val t: String, val a: Float, val cols: List<Int>, val pos: List<Float>)

@Immutable
internal data class MShadow(val c: Int, val x: Float, val y: Float, val blur: Float, val spread: Float)

@Immutable
internal data class MPath(val d: String, val f: Int, val s: Int, val w: Float, val cap: Boolean, val join: Boolean, val o: Float)

/** Dónde va cada parte (cada panel) de la pantalla, medido desde arriba del todo. */
@Immutable
internal data class MPos(val k: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** Una pieza: una caja (con lo que lleva dentro), un texto de un renglón, un dibujo o un control. */
@Immutable
internal data class MNode(
    val x: Float, val y: Float, val w: Float, val h: Float, val e: String,
    val bg: Int, val g: List<MGrad>, val gc: String, val bw: List<Float>, val bi: List<Float>, val bc: Int, val dash: Boolean,
    val r: List<Float>, val sh: List<MShadow>, val o: Float, val clip: Boolean,
    val k: Int, val dis: Boolean, val label: String,
    val s: String, val st: MStyle?, val tg: MGrad?, val tb: List<Float>, val mw: Float, val ell: Boolean,
    val v: String, val ph: String, val pc: Int, val pad: List<Float>, val pw: Boolean, val go: Boolean, val sr: Boolean, val ml: Int,
    val nv: Float, val mn: Float, val mx: Float, val sp: Float, val hue: Boolean,
    val opts: List<Pair<String, String>>, val vb: List<Float>, val paths: List<MPath>,
    val mk: Float, val mc: Int, val mo: Boolean,
    val kids: List<MNode>
)

private val NO_F = emptyList<Float>()
private val ZERO4 = listOf(0f, 0f, 0f, 0f)

private fun JSONObject.f(k: String, d: Float = 0f): Float { val v = optDouble(k, d.toDouble()); return if (v.isNaN()) d else v.toFloat() }
private fun JSONObject.c(k: String): Int = optLong(k, 0L).toInt()

/** Un número, o cuatro (arriba, derecha, abajo, izquierda): siempre vuelven cuatro (o ninguno si no hay). */
private fun four(o: JSONObject, k: String): List<Float> {
    if (!o.has(k)) return NO_F
    val a = o.optJSONArray(k)
    if (a != null) return List(4) { i -> val v = a.optDouble(i, 0.0); if (v.isNaN()) 0f else v.toFloat() }
    val v = o.f(k)
    return listOf(v, v, v, v)
}

private fun mGrad(o: JSONObject?): MGrad? {
    if (o == null) return null
    val t = o.optString("t", "x")
    val sa = o.optJSONArray("s") ?: return null
    if (t == "x" || sa.length() == 0) return null
    val cols = ArrayList<Int>()
    val pos = ArrayList<Float>()
    for (i in 0 until sa.length()) { val s = sa.optJSONArray(i) ?: continue; cols.add(s.optLong(0, 0L).toInt()); val p = s.optDouble(1, -1.0); pos.add(if (p.isNaN()) -1f else p.toFloat()) }
    if (cols.isEmpty()) return null
    if (cols.size == 1) { cols.add(cols[0]); pos.add(-1f) }
    // las paradas sin sitio se reparten entre las que sí lo tienen (como en CSS)
    if (pos[0] < 0f) pos[0] = 0f
    if (pos[pos.size - 1] < 0f) pos[pos.size - 1] = 1f
    var i = 1
    while (i < pos.size) {
        if (pos[i] < 0f) {
            var j = i
            while (j < pos.size && pos[j] < 0f) j++
            val a = pos[i - 1]; val b = pos[j]
            for (q in i until j) pos[q] = a + (b - a) * (q - i + 1) / (j - i + 1).toFloat()
            i = j
        }
        if (pos[i] < pos[i - 1]) pos[i] = pos[i - 1]
        i++
    }
    return MGrad(t, o.f("a", 180f), cols, pos)
}

private fun mKids(a: JSONArray?, st: List<MStyle>, depth: Int): List<MNode> {
    if (a == null || a.length() == 0 || depth > 40) return emptyList()
    val out = ArrayList<MNode>(a.length())
    for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; out.add(mNode(o, st, depth)) }
    return out
}

private fun mNode(o: JSONObject, st: List<MStyle>, depth: Int): MNode {
    val e = o.optString("e", "")
    val g = ArrayList<MGrad>()
    var gc = o.optString("gc", "")
    val ga = o.optJSONArray("g")
    if (ga != null) {
        // (una capa que no se sabe dibujar se salta, y con ella su letra de «hasta dónde llega»)
        val keep = StringBuilder()
        for (i in 0 until ga.length()) { val x = mGrad(ga.optJSONObject(i)); if (x != null) { g.add(x); keep.append(if (i < gc.length) gc[i] else 'b') } }
        gc = keep.toString()
    }
    val sh = ArrayList<MShadow>()
    val sa = o.optJSONArray("sh")
    if (sa != null) for (i in 0 until sa.length()) { val s = sa.optJSONArray(i) ?: continue; sh.add(MShadow(s.optLong(0, 0L).toInt(), s.optDouble(1, 0.0).toFloat(), s.optDouble(2, 0.0).toFloat(), s.optDouble(3, 0.0).toFloat(), s.optDouble(4, 0.0).toFloat())) }
    val opts = ArrayList<Pair<String, String>>()
    val oa = o.optJSONArray("opts")
    if (oa != null) for (i in 0 until oa.length()) { val x = oa.optJSONArray(i) ?: continue; opts.add(x.optString(0) to x.optString(1)) }
    var vb = NO_F
    val paths = ArrayList<MPath>()
    val sv = if (e == "svg") o.optJSONObject("v") else null
    if (sv != null) {
        val va = sv.optJSONArray("vb")
        vb = List(4) { i -> val v = va?.optDouble(i, if (i < 2) 0.0 else 24.0) ?: (if (i < 2) 0.0 else 24.0); if (v.isNaN()) 0f else v.toFloat() }
        val pa = sv.optJSONArray("p")
        if (pa != null) for (i in 0 until pa.length()) { val p = pa.optJSONObject(i) ?: continue; paths.add(MPath(p.optString("d"), p.c("f"), p.c("s"), p.f("w", 1f), p.optInt("c") == 1, p.optInt("j") == 1, p.f("o", 1f) * p.f("fo", 1f))) }
    }
    val fi = o.optInt("f", -1)
    val pad = four(o, "p")
    val tb = o.optJSONArray("tb")
    return MNode(
        o.f("x"), o.f("y"), o.f("w"), o.f("h"), e,
        o.c("bg"), g, gc, four(o, "bw"), four(o, "bi"), o.c("bc"), o.optInt("bs") == 1,
        four(o, "r"), sh, o.f("o", 1f).coerceIn(0f, 1f), o.optInt("cl") == 1,
        o.optInt("k", 0), o.optInt("d") == 1, o.optString("l", ""),
        if (e == "ph") o.optString("i", "") else o.optString("s", ""), if (fi >= 0 && fi < st.size) st[fi] else null, mGrad(o.optJSONObject("tg")),
        if (tb != null && tb.length() == 4) List(4) { tb.optDouble(it, 0.0).toFloat() } else NO_F, o.f("mw"), o.optInt("el") == 1,
        if (e == "img") o.optString("u", "") else if (e == "svg") "" else o.optString("v", ""), o.optString("ph", ""), o.c("pc"), if (pad.size == 4) pad else ZERO4,
        o.optInt("pw") == 1, o.optInt("go") == 1, o.optInt("sr") == 1, o.optInt("ml", 0),
        if (e == "ph") o.f("hu", 280f) else if (e == "rg") o.f("v") else 0f, if (e == "ph") o.f("fs", 16.8f) else o.f("mn"), o.f("mx", 100f), o.f("sp", 1f), o.optInt("hue") == 1,
        opts, vb, paths,
        o.f("mk"), o.c("mc"), o.optInt("mo") == 1,
        mKids(o.optJSONArray("c"), st, depth + 1)
    )
}

/** Una parte, tal como llega («st»: cómo son sus letras; «c»: sus piezas), lista para dibujar. */
internal fun mParsePart(o: JSONObject): List<MNode> {
    val st = ArrayList<MStyle>()
    val sa = o.optJSONArray("st")
    if (sa != null) for (i in 0 until sa.length()) {
        val a = sa.optJSONArray(i)
        st.add(if (a == null) MStyle(15f, 400, "b", -1, 0f, 0, 0f) else MStyle(a.optDouble(0, 15.0).toFloat().coerceIn(1f, 400f), a.optInt(1, 400).coerceIn(100, 900), a.optString(2, "b"), a.optLong(3, 0L).toInt(), a.optDouble(4, 0.0).toFloat(), a.optInt(5, 0), a.optDouble(6, 0.0).toFloat()))
    }
    return mKids(o.optJSONArray("c"), st, 0)
}

// ---------------------------------------------------------------- la pantalla
@Composable
fun MirrorScreen(name: String) {
    val sk = rememberSkin()
    val order = UiState.mirOrder[name] ?: emptyList()
    val total = UiState.mirTotal[name] ?: 0f
    val ls = UiState.mirList(name)
    val dens = LocalDensity.current.density
    // cuando el dedo la suelta, la web se pone a la misma altura (lo que salga encima de ella, la encuentra igual)
    LaunchedEffect(name, ls) {
        snapshotFlow { if (ls.isScrollInProgress) -1f else UiState.mirY(name, dens) }.collect { y ->
            if (y >= 0f) UiState.send("mY") { it.put("name", name); it.put("y", (Math.round(y * 100.0) / 100.0)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = ls, modifier = Modifier.fillMaxSize()) {
            items(order.size, key = { order[it].k }) { i ->
                val p = order[i]
                // cada parte ocupa desde donde empieza hasta donde empieza la siguiente (la primera, desde arriba del todo; la última, hasta el final)
                val top = if (i == 0) 0f else p.y
                val bottom = if (i + 1 < order.size) order[i + 1].y else max(total, p.y + p.h)
                MPart(p, UiState.mirParts[name + "." + p.k] ?: emptyList(), top, bottom, sk)
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MPart(p: MPos, nodes: List<MNode>, top: Float, bottom: Float, sk: Skin) {
    // (un toque fuera de los botones también le llega a la web, en su sitio: allí puede haber algo que lo espere)
    val tapAt = Modifier.pointerInput(p.k, p.x, p.y, top) {
        detectTapGestures(onTap = { o -> UiState.send("mAt") { it.put("k", p.k); it.put("x", (o.x / density - p.x).toDouble()); it.put("y", (o.y / density + top - p.y).toDouble()) } })
    }
    Layout(content = { MGroup(nodes, 0f, 0f, p.w, p.h, Modifier, sk) }, modifier = Modifier.fillMaxWidth().then(tapAt)) { ms, cs ->
        val d = density
        val t = (top * d).roundToInt()
        val pl = ms[0].measure(Constraints())
        layout(cs.maxWidth, max(0, (bottom * d).roundToInt() - t)) { pl.place((p.x * d).roundToInt(), (p.y * d).roundToInt() - t) }
    }
}

/** Varias piezas, cada una en su sitio exacto (las medidas son las de la web; los textos se centran en su renglón). */
@Composable
private fun MGroup(kids: List<MNode>, ox: Float, oy: Float, w: Float, h: Float, modifier: Modifier, sk: Skin) {
    Layout(content = { for (i in kids.indices) { val c = kids[i]; key(if (c.k > 0) c.k else -1 - i) { MAny(c, sk) } } }, modifier = modifier) { ms, _ ->
        val d = density
        val x0 = (ox * d).roundToInt()
        val y0 = (oy * d).roundToInt()
        val n = min(ms.size, kids.size)
        val ps = ArrayList<Placeable>(n)
        for (i in 0 until n) {
            val c = kids[i]
            if (c.e == "t") ps.add(ms[i].measure(Constraints(maxWidth = if (c.mw > 0f) max(1, (c.mw * d).roundToInt()) else Constraints.Infinity)))
            else {
                val l = (c.x * d).roundToInt(); val t = (c.y * d).roundToInt()
                ps.add(ms[i].measure(Constraints.fixed(max(0, ((c.x + c.w) * d).roundToInt() - l), max(0, ((c.y + c.h) * d).roundToInt() - t))))
            }
        }
        layout(max(0, ((ox + w) * d).roundToInt() - x0), max(0, ((oy + h) * d).roundToInt() - y0)) {
            for (i in 0 until n) {
                val c = kids[i]; val p = ps[i]
                if (c.e == "t") p.place((c.x * d).roundToInt() - x0, ((c.y + c.h / 2f) * d - p.height / 2f).roundToInt() - y0)
                else p.place((c.x * d).roundToInt() - x0, (c.y * d).roundToInt() - y0)
            }
        }
    }
}

/** Una pieza (siempre un solo elemento, para que cada una caiga en su sitio). */
@Composable
private fun MAny(c: MNode, sk: Skin) {
    when (c.e) {
        "t" -> MText(c)
        "in" -> MInput(c, sk)
        "rg" -> MRange(c, sk)
        "sel" -> MSelect(c, sk)
        "svg" -> MSvg(c)
        "ph" -> MPh(c, sk)
        "img" -> MImg(c)
        "eqa" -> MEqAnim(sk)
        "hole" -> Spacer(Modifier)
        else -> MBoxNode(c, sk)
    }
}

// ---------------------------------------------------------------- cajas
/** Las esquinas, en píxeles, sin pasarse de media caja. */
private fun radii(c: MNode, w: Float, h: Float, d: Float): FloatArray {
    val lim = min(w, h) / 2f
    return FloatArray(4) { i -> if (c.r.size == 4) min(c.r[i] * d, lim).coerceAtLeast(0f) else 0f }
}

private fun rr(l: Float, t: Float, r: Float, b: Float, q: FloatArray, less: Float = 0f): Path = Path().apply {
    addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(max(0f, q[0] - less)), CornerRadius(max(0f, q[1] - less)), CornerRadius(max(0f, q[2] - less)), CornerRadius(max(0f, q[3] - less))))
}

/** El grosor de un borde como lo pinta el navegador: en píxeles enteros del móvil, hacia abajo (y nunca menos de uno). */
private fun bpx(w: Float, d: Float): Float = if (w <= 0f) 0f else max(1f, floor(w * d + 0.02f))

private fun mBrush(g: MGrad, box: Rect): Brush? {
    if (g.cols.size < 2 || g.cols.size != g.pos.size) return null
    val stops = Array(g.cols.size) { g.pos[it] to Color(g.cols[it]) }
    if (g.t == "l") {
        val a = g.a * PI.toFloat() / 180f
        val dx = sin(a); val dy = -cos(a)
        val len = abs(box.width * dx) + abs(box.height * dy)
        val ctr = box.center
        return Brush.linearGradient(*stops, start = Offset(ctr.x - dx * len / 2, ctr.y - dy * len / 2), end = Offset(ctr.x + dx * len / 2, ctr.y + dy * len / 2))
    }
    if (g.t == "c") {
        val cols = IntArray(g.cols.size) { g.cols[it] }
        val pos = FloatArray(g.pos.size) { g.pos[it] }
        val ctr = box.center
        val from = g.a
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val s = android.graphics.SweepGradient(ctr.x, ctr.y, cols, pos)
                val m = android.graphics.Matrix()
                m.setRotate(from - 90f, ctr.x, ctr.y)   // (en CSS el abanico empieza arriba; en Android, a la derecha)
                s.setLocalMatrix(m)
                return s
            }
        }
    }
    return null
}

/** El aspecto de una caja de la web: sombras, fondo, degradados (cada uno hasta donde le toca), borde y, si es un desplegable, su triángulo. */
private fun Modifier.mDecor(c: MNode): Modifier {
    if (c.bg == 0 && c.g.isEmpty() && (c.bc == 0 || c.bw.size != 4) && c.sh.isEmpty() && c.mk <= 0f) return this
    return this.drawWithCache {
        val d = density
        val w = size.width; val h = size.height
        val q = radii(c, w, h, d)
        val outer = rr(0f, 0f, w, h, q)
        val bi = if (c.bi.size == 4) FloatArray(4) { bpx(c.bi[it], d) } else FloatArray(4)
        val inner = if (bi.any { it > 0f }) rr(bi[3], bi[0], w - bi[1], h - bi[2], q, max(bi[0], bi[3])) else outer
        val innerBox = Rect(bi[3], bi[0], w - bi[1], h - bi[2])
        val full = Rect(0f, 0f, w, h)
        val layers = ArrayList<Pair<Brush, Path>>()
        for (i in c.g.indices.reversed()) {
            val pad = i < c.gc.length && c.gc[i] != 'b'
            val b = mBrush(c.g[i], if (pad) innerBox else full) ?: continue
            layers.add(b to (if (pad) inner else outer))
        }
        val shadows = c.sh.map { s ->
            val p = Paint()
            val fp = p.asFrameworkPaint()
            fp.isAntiAlias = true
            fp.color = s.c
            if (s.blur > 0.2f) fp.maskFilter = BlurMaskFilter(max(0.5f, (s.blur * d * 0.5f - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
            p
        }
        val uniform = c.bw.size == 4 && c.bw[0] == c.bw[1] && c.bw[1] == c.bw[2] && c.bw[2] == c.bw[3]
        val bw = if (c.bw.size == 4) FloatArray(4) { bpx(c.bw[it], d) } else FloatArray(4)
        val bcol = Color(c.bc)
        val dash = if (c.dash && bw[0] > 0f) PathEffect.dashPathEffect(floatArrayOf(bw[0] * 3, bw[0] * 3)) else null
        val mark = if (c.mk > 0f) Path().apply {
            val mw = c.mk * 0.68f * d; val mh = c.mk * 0.79f * d; val cy = h / 2f
            if (c.mo) { moveTo(0f, cy - mw / 2f); lineTo(mh, cy - mw / 2f); lineTo(mh / 2f, cy + mw / 2f); close() }
            else { moveTo(0f, cy - mh / 2f); lineTo(mw, cy); lineTo(0f, cy + mh / 2f); close() }
        } else null
        onDrawBehind {
            if (shadows.isNotEmpty()) clipPath(outer, ClipOp.Difference) {
                drawIntoCanvas { cv ->
                    for (i in c.sh.indices.reversed()) {
                        val s = c.sh[i]
                        val sp = s.spread * d; val ox = s.x * d; val oy = s.y * d
                        val r0 = max(0f, q[0] + sp)
                        if (w + 2 * sp > 0f && h + 2 * sp > 0f) cv.drawRoundRect(ox - sp, oy - sp, w + ox + sp, h + oy + sp, r0, r0, shadows[i])
                    }
                }
            }
            if (c.bg != 0) drawPath(outer, Color(c.bg))
            for ((b, p) in layers) drawPath(p, b)
            if (c.bc != 0 && c.bw.size == 4) {
                if (uniform && bw[0] > 0f) {
                    val t = bw[0]
                    drawPath(rr(t / 2f, t / 2f, w - t / 2f, h - t / 2f, q, t / 2f), bcol, style = Stroke(t, pathEffect = dash))
                } else if (!uniform) {
                    if (bw[0] > 0f) drawRect(bcol, Offset(0f, 0f), Size(w, bw[0]))
                    if (bw[1] > 0f) drawRect(bcol, Offset(w - bw[1], 0f), Size(bw[1], h))
                    if (bw[2] > 0f) drawRect(bcol, Offset(0f, h - bw[2]), Size(w, bw[2]))
                    if (bw[3] > 0f) drawRect(bcol, Offset(0f, 0f), Size(bw[3], h))
                }
            }
            if (mark != null) drawPath(mark, Color(c.mc))
        }
    }
}

private fun mShape(c: MNode): RoundedCornerShape = if (c.r.size == 4) RoundedCornerShape(c.r[0].coerceIn(0f, 4000f).dp, c.r[1].coerceIn(0f, 4000f).dp, c.r[2].coerceIn(0f, 4000f).dp, c.r[3].coerceIn(0f, 4000f).dp) else RoundedCornerShape(0.dp)

private fun mSend(type: String, k: Int, fill: (com.getcapacitor.JSObject) -> Unit = {}) { if (k > 0) UiState.send(type) { it.put("k", k); fill(it) } }

@Composable
private fun MBoxNode(c: MNode, sk: Skin) {
    var m: Modifier = Modifier
    if (c.label.isNotEmpty()) m = m.semantics { contentDescription = c.label }
    if (c.e == "dr" && c.k > 0) {
        // una banda del ecualizador: se arrastra arriba y abajo; dos toques seguidos la dejan a 0
        val last = remember { LongArray(2) }
        m = m.pointerInput(c.k) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val hh = max(1f, size.height.toFloat())
                val now = SystemClock.uptimeMillis()
                if (now - last[0] < 320) { last[0] = 0L; mSend("mDr", c.k) { it.put("dbl", 1) } }
                else {
                    last[0] = now
                    mSend("mDr", c.k) { it.put("f", (down.position.y / hh).toDouble()) }
                    var y = down.position.y
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (ch.position.y != y) {
                            y = ch.position.y
                            val t = SystemClock.uptimeMillis()
                            if (!ch.pressed || t - last[1] > 40) { last[1] = t; mSend("mDr", c.k) { it.put("f", (y / hh).toDouble()) } }
                        }
                        if (!ch.pressed) break
                    }
                }
            }
        }
    } else if (c.k > 0) {
        // (lo pequeño se encoge un poco al tocarlo, como los botones de la web; una fila entera, no)
        m = m.press(if (c.w < 230f && c.h < 90f) 0.96f else 1f) { mSend("hTap", c.k) }
    }
    if (c.o < 1f) m = m.alpha(c.o)
    m = m.mDecor(c)
    if (c.clip) m = m.clip(mShape(c))
    MGroup(c.kids, c.x, c.y, c.w, c.h, m, sk)
}

// ---------------------------------------------------------------- letras
@Composable
private fun mStyleOf(st: MStyle, brush: Brush? = null, color: Color = Color(st.col)): androidx.compose.ui.text.TextStyle {
    val fam = when (st.ff) { "d" -> Display; "m" -> Mono; else -> Body }
    var ts = style(st.fs, color, fam, FontWeight(st.fw.coerceIn(100, 900)), 1.25f, if (st.fs > 0f) st.ls / st.fs else 0f, TextAlign.Start, brush)
    if ((st.fl and 1) != 0) ts = ts.copy(fontStyle = FontStyle.Italic)
    if ((st.fl and 6) != 0) ts = ts.copy(textDecoration = if ((st.fl and 6) == 6) TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)) else if ((st.fl and 2) != 0) TextDecoration.Underline else TextDecoration.LineThrough)
    if (st.sw > 0f) ts = ts.copy(drawStyle = Stroke(width = st.sw * LocalDensity.current.density))
    return ts
}

/** Un renglón de texto, tal como lo partió la web. */
@Composable
private fun MText(c: MNode) {
    val st = c.st
    if (st == null) { Spacer(Modifier); return }
    val d = LocalDensity.current.density
    val tg = c.tg
    val brush = if (tg != null && c.tb.size == 4) remember(tg, c.tb, d) { mBrush(tg, Rect(c.tb[0] * d, c.tb[1] * d, (c.tb[0] + c.tb[2]) * d, (c.tb[1] + c.tb[3]) * d)) } else null
    BasicText(c.s, style = mStyleOf(st, brush), maxLines = 1, softWrap = false, overflow = if (c.ell) TextOverflow.Ellipsis else TextOverflow.Clip)
}

// ---------------------------------------------------------------- controles
/** Una caja para escribir: lo que se teclea va a la de la web (que es la que manda), y lo que ella cambie, vuelve. */
@Composable
private fun MInput(c: MNode, sk: Skin) {
    val st = c.st ?: MStyle(15f, 400, "b", -1, 0f, 0, 0f)
    var text by remember { mutableStateOf(c.v) }
    val pend = remember { ArrayDeque<String>() }
    val last = remember { LongArray(1) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(c.v) {
        val i = pend.indexOf(c.v)
        if (i >= 0) { for (q in 0..i) pend.removeFirst() }
        else if (c.v != text && (pend.isEmpty() || SystemClock.uptimeMillis() - last[0] > 1500)) { pend.clear(); text = c.v }
    }
    val focus = LocalFocusManager.current
    val ts = mStyleOf(st)
    var box = Modifier.mDecor(c)
    if (c.clip) box = box.clip(mShape(c))
    Box(box, contentAlignment = Alignment.CenterStart) {
        var fm = Modifier.fillMaxWidth().padding(start = c.pad[3].coerceIn(0f, 200f).dp, end = c.pad[1].coerceIn(0f, 200f).dp)
        if (c.label.isNotEmpty()) fm = fm.semantics { contentDescription = c.label }
        BasicTextField(
            value = text,
            onValueChange = { v ->
                val t = if (c.ml > 0) v.take(c.ml) else v
                if (t != text) {
                    text = t
                    pend.addLast(t)
                    if (pend.size > 40) pend.removeFirst()
                    last[0] = SystemClock.uptimeMillis()
                    mSend("mIn", c.k) { it.put("v", t) }
                }
            },
            modifier = fm.onFocusChanged { f ->
                if (focused && !f.isFocused) mSend("mIn", c.k) { it.put("v", text); it.put("fin", 1) }
                focused = f.isFocused
            },
            enabled = !c.dis, singleLine = true, textStyle = ts, cursorBrush = SolidColor(sk.pal.accent),
            visualTransformation = if (c.pw) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = if (c.sr) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onSearch = { focus.clearFocus(); mSend("mGo", c.k) },
                onDone = { focus.clearFocus(); if (c.go) mSend("mGo", c.k) }
            ),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty() && c.ph.isNotEmpty()) BasicText(c.ph, style = mStyleOf(st, null, Color(c.pc)), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    inner()
                }
            }
        )
    }
}

/** Una barra para deslizar (el volumen, el tono…), con el aspecto de las de la web. */
@Composable
private fun MRange(c: MNode, sk: Skin) {
    val p = sk.pal
    var drag by remember { mutableStateOf<Float?>(null) }
    var hold by remember { mutableStateOf<Float?>(null) }
    val last = remember { LongArray(1) }
    val span = if (c.mx - c.mn != 0f) c.mx - c.mn else 1f
    val step = if (c.sp > 0f) c.sp else 1f
    // (al soltar se queda donde se dejó hasta que la web dice el valor nuevo: así no da un salto atrás)
    LaunchedEffect(hold) { if (hold != null) { delay(900); hold = null } }
    LaunchedEffect(c.nv) { val hv = hold; if (hv != null && abs(c.nv - hv) <= step * 0.51f) hold = null }
    val v = drag ?: hold ?: c.nv
    val frac = ((v - c.mn) / span).coerceIn(0f, 1f)
    fun at(x: Float, wPx: Float, d: Float): Float {
        val th = 8f * d
        val f = ((x - th) / max(1f, wPx - 2 * th)).coerceIn(0f, 1f)
        val raw = c.mn + f * span
        return (c.mn + ((raw - c.mn) / step).roundToInt() * step).coerceIn(min(c.mn, c.mx), max(c.mn, c.mx))
    }
    fun send(x: Float, fin: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!fin && now - last[0] < 45) return
        last[0] = now
        mSend("mRg", c.k) { it.put("v", (Math.round(x * 1000.0) / 1000.0)); if (fin) it.put("fin", 1) }
    }
    var m: Modifier = Modifier
    if (c.label.isNotEmpty()) m = m.semantics { contentDescription = c.label }
    if (!c.dis && c.k > 0) {
        m = m.pointerInput(c.k, c.mn, c.mx, c.sp) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val slop = viewConfiguration.touchSlop
                var dragging = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        // se suelta: donde esté el dedo (arrastrando o con un toque)
                        if (dragging || !ch.isConsumed) { ch.consume(); val x = at(ch.position.x, w, density); drag = null; hold = x; send(x, true) }
                        break
                    }
                    if (!dragging && ch.isConsumed) break          // (la lista se ha puesto a moverse)
                    val dx = ch.position.x - down.position.x
                    val dy = ch.position.y - down.position.y
                    if (!dragging) {
                        if (abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                        else if (abs(dy) > slop) break              // (hacia arriba o abajo: la lista)
                    }
                    if (dragging) { ch.consume(); val x = at(ch.position.x, w, density); drag = x; send(x, false) }
                }
                if (drag != null) { val x = drag; drag = null; if (x != null) { hold = x; send(x, true) } }
            }
        }
    }
    Canvas(m) {
        val d = density
        val w = size.width; val cy = size.height / 2f
        val th = 6f * d; val r = CornerRadius(th / 2f)
        if (c.hue) drawRoundRect(Brush.horizontalGradient(listOf(hsl(0f, .9f, .6f), hsl(60f, .9f, .55f), hsl(120f, .8f, .5f), hsl(180f, .85f, .5f), hsl(240f, .85f, .62f), hsl(300f, .85f, .6f), hsl(359f, .9f, .6f))), Offset(0f, cy - th / 2f), Size(w, th), r)
        else {
            drawRoundRect(Color.White.copy(alpha = 0.13f), Offset(0f, cy - th / 2f), Size(w, th), r)
            val fw = w * frac
            if (fw > 0.5f) clipPath(Path().apply { addRoundRect(RoundRect(Rect(0f, cy - th / 2f, w, cy + th / 2f), r)) }) {
                drawRect(Brush.horizontalGradient(listOf(p.accent, p.accent2), startX = 0f, endX = fw), Offset(0f, cy - th / 2f), Size(fw, th))
            }
        }
        val cx = 8f * d + frac * (w - 16f * d)
        drawIntoCanvas { cv ->
            val gp = Paint()
            val fp = gp.asFrameworkPaint()
            fp.isAntiAlias = true
            fp.color = p.accent.toArgb()
            fp.maskFilter = BlurMaskFilter(7f * d, BlurMaskFilter.Blur.NORMAL)
            cv.drawCircle(Offset(cx, cy), 8f * d, gp)
        }
        drawCircle(p.wash, 12f * d, Offset(cx, cy))
        drawCircle(Color.White, 8f * d, Offset(cx, cy))
    }
}

private const val M_DOWN = "M6 9l6 6 6-6"

/** Una lista desplegable (el país de las radios): la caja de la web y, al tocarla, sus opciones. */
@Composable
private fun MSelect(c: MNode, sk: Skin) {
    val p = sk.pal
    val st = c.st ?: MStyle(13.12f, 400, "b", -1, 0f, 0, 0f)
    var open by remember { mutableStateOf(false) }
    val hidden = UiState.hideN
    LaunchedEffect(hidden) { open = false }
    val label = c.opts.firstOrNull { it.first == c.v }?.second ?: (c.opts.firstOrNull()?.second ?: "")
    val dens = LocalDensity.current.density
    var m = Modifier.mDecor(c)
    if (c.clip) m = m.clip(mShape(c))
    if (c.label.isNotEmpty()) m = m.semantics { contentDescription = c.label }
    if (!c.dis && c.k > 0) m = m.clickable(remember { MutableInteractionSource() }, null) { open = true }
    Box(m) {
        Row(Modifier.fillMaxSize().padding(start = 14.7.dp, end = 1.3.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f), style = mStyleOf(st), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Ico(M_DOWN, 14f, Color(st.col), sw = 2f)
        }
        if (open) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, (c.h * dens).roundToInt() + (4 * dens).roundToInt()), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            val ps = sk.r(12f)
            Column(Modifier.width(240.dp).heightIn(max = 360.dp).glow(Color.Black.copy(alpha = 0.6f), 12.dp, 30.dp, 10.dp, 0.dp).clip(ps).background(p.panelSolid)
                .verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                for ((id, name) in c.opts) {
                    val on = id == c.v
                    Box(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {
                        open = false
                        mSend("mSel", c.k) { it.put("v", id) }
                    }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        T(name, 15f * sk.tk, if (on) p.accent else p.fg, weight = if (on) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- dibujos
/** Un icono de la web (sus trazos, con sus colores y grosores), a la medida de su caja. */
@Composable
private fun MSvg(c: MNode) {
    val paths = remember(c.paths) { c.paths.map { try { PathParser().parsePathString(it.d).toPath() } catch (t: Throwable) { Path() } } }
    Canvas(Modifier) {
        if (c.vb.size != 4 || c.vb[2] <= 0f || c.vb[3] <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val k = min(size.width / c.vb[2], size.height / c.vb[3])
        val tx = (size.width - c.vb[2] * k) / 2f - c.vb[0] * k
        val ty = (size.height - c.vb[3] * k) / 2f - c.vb[1] * k
        withTransform({ translate(tx, ty); scale(k, k, pivot = Offset.Zero) }) {
            for (i in paths.indices) {
                val q = c.paths[i]
                if (q.f != 0) drawPath(paths[i], Color(q.f), alpha = q.o.coerceIn(0f, 1f))
                if (q.s != 0 && q.w > 0f) drawPath(paths[i], Color(q.s), alpha = q.o.coerceIn(0f, 1f),
                    style = Stroke(width = q.w, cap = if (q.cap) StrokeCap.Round else StrokeCap.Butt, join = if (q.join) StrokeJoin.Round else StrokeJoin.Miter))
            }
        }
    }
}

/** Una portada sin imagen: la inicial sobre el degradado de la canción (como la dibuja la web). */
@Composable
private fun MPh(c: MNode, sk: Skin) {
    val h = c.nv
    Box(Modifier.background(Brush.linearGradient(listOf(hsl(h, 0.75f, 0.50f), hsl(h + 60f, 0.70f, 0.26f))))
        .drawBehind {
            val ctr = Offset(size.width * 0.30f, size.height * 0.25f)
            drawRect(Brush.radialGradient(0f to hsl(h + 40f, 0.90f, 0.70f, 0.9f), 0.55f to Color.Transparent, center = ctr, radius = size.width * 1.03f))
        }, contentAlignment = Alignment.Center) {
        T(c.s.ifEmpty { "♪" }, c.mn.coerceIn(4f, 200f), Color.White, family = sk.display, weight = sk.displayWeight, lh = 1f, maxLines = 1)
    }
}

/** Una imagen de la web (una portada, la miniatura de un vídeo, el logo de una emisora). */
@Composable
private fun MImg(c: MNode) {
    val u = c.v
    val side = max(c.w, c.h).roundToInt().coerceIn(32, 400)
    val key = if (side > 100) "$u|L" else u
    val bmp = if (u.isNotEmpty()) UiState.covers[key] else null
    val dens = LocalDensity.current.density
    if (u.isNotEmpty() && bmp == null) LaunchedEffect(key) { needArt(u, side, dens) }
    var m = Modifier.mDecor(c)
    if (c.r.size == 4) m = m.clip(mShape(c))
    Box(m) { if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** Las cinco barritas que suben y bajan mientras se busca. */
@Composable
private fun MEqAnim(sk: Skin) {
    val ph = rememberInfiniteTransition(label = "meq").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "me").value
    Canvas(Modifier) {
        val dl = floatArrayOf(0f, 0.3f, 0.6f, 0.15f, 0.45f)
        for (i in 0 until 5) {
            val t = (ph + dl[i]) % 1f
            val hh = (6f + 22f * (0.5f - 0.5f * cos(t * 2f * PI.toFloat()))).dp.toPx()
            drawRoundRect(sk.pal.grad, Offset(i * 9.dp.toPx(), size.height - hh), Size(5.dp.toPx(), hh), CornerRadius(3.dp.toPx() * sk.round))
        }
    }
}
