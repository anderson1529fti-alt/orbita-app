package __PACKAGE__

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 5.0 · Fase 3, paso 3: la letra que va con la música, en el reproductor, la dibuja Android.
 *
 * La caja de la letra llega leída entera una vez (cada verso: dónde está y por dónde lo partió la web en renglones). Qué
 * verso se canta, y cuánto dura su relleno, llega aparte, y Android hace lo de siempre por su cuenta: lo lleva al 42 % de
 * la caja (si el dedo no la ha movido en tres segundos y medio), lo enciende (blanco y un 4 % más grande), deja los ya
 * cantados a medias y lo va llenando de izquierda a derecha, renglón tras renglón, como un karaoke. Tocar un verso lleva
 * la canción allí, como en la web.
 */
internal object Ly {
    /** De qué letra es (cambia cada vez que la web la vuelve a escribir), qué verso (-1: ninguno aún) y cuánto dura su relleno. */
    @Volatile var g = -1
    @Volatile var i = -2
    @Volatile var d = 0f
    @Volatile var rm = false
    @Volatile var t0 = 0L
    val ver = mutableIntStateOf(0)
    // (para las pruebas) lo que se dibuja
    @Volatile var drawn = ""
    @Volatile var shown = -2
    @Volatile var sc: ScrollState? = null
    @Volatile var dens = 1f
    val y: Float get() = (sc?.value ?: 0) / dens
    @Volatile var fill = 0f
    @Volatile var taps = 0

    fun set(o: JSONObject) {
        g = o.optInt("g", -1); i = o.optInt("i", -2); d = o.optDouble("d", 0.0).toFloat().coerceIn(0f, 30f)
        rm = o.optInt("rm") == 1; t0 = o.optLong("t", System.currentTimeMillis())
        ver.intValue++
    }
}

// los colores de la web (.ly-line, .sung, .on y el relleno)
private val LY_BASE = Color(1f, 1f, 1f, 0.34f)
private val LY_SUNG = Color(1f, 1f, 1f, 0.55f)
private val LY_DIM = Color(1f, 1f, 1f, 0.45f)

/** La caja de la letra: se desliza sola, y con el dedo (sin arrastrar al reproductor, como en la web). */
@Composable
internal fun LyBox(c: MNode, modifier: Modifier) {
    val d = LocalDensity.current.density
    val synced = c.v != "p"
    val lines = c.kids
    @Suppress("UNUSED_VARIABLE") val tick = Ly.ver.intValue
    val idx = if (synced && Ly.g == c.gn) Ly.i else -2
    val rm = Ly.rm
    val contentH = max(c.mx, c.h)
    val maxPx = max(0, ((contentH - c.h) * d).roundToInt())
    val scroll = remember(c.gn) { ScrollState(0) }
    val lastUser = remember(c.gn) { LongArray(1) }
    val first = remember(c.gn) { BooleanArray(1) { true } }
    // al verso que se canta: el 42 % de la caja por encima de él, como hace la web (si el dedo no la movió hace poco)
    LaunchedEffect(c.gn, idx, maxPx) {
        if (!synced || idx < 0) { first[0] = false; return@LaunchedEffect }
        val ln = lines.firstOrNull { it.v.toIntOrNull() == idx } ?: return@LaunchedEffect
        if (SystemClock.uptimeMillis() - lastUser[0] < 3500) return@LaunchedEffect
        val target = ((ln.ot - c.h * 0.42f) * d).roundToInt().coerceIn(0, maxPx)
        if (first[0] || rm) { first[0] = false; scroll.scrollTo(target); return@LaunchedEffect }
        val dist = abs(target - scroll.value) / d
        scroll.animateScrollTo(target, tween<Float>((140f + sqrt(dist) * 16f).roundToInt().coerceIn(180, 650), easing = FastOutSlowInEasing))
    }
    // el dedo la mueve: tres segundos y medio sin ir sola; y lo que sobra no arrastra al reproductor (overscroll-behavior: contain)
    val conn = remember(c.gn) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) lastUser[0] = SystemClock.uptimeMillis()
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
        }
    }
    SideEffect {
        Ly.drawn = (if (synced) "s" else "p") + ":" + c.gn + ":" + lines.size
        Ly.shown = idx
        Ly.sc = scroll; Ly.dens = d
    }
    var box = modifier.clipToBounds()
    if (synced) box = box.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        // (mask-image: de transparente a negro en el 18 % de arriba y al revés en el 18 % de abajo)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
    // (el dedo que empieza aquí no baja el reproductor)
    val hold = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            UiState.npNoDrag = true
            try { do { val ev = awaitPointerEvent(PointerEventPass.Initial) } while (ev.changes.any { it.pressed }) } finally { UiState.npNoDrag = false }
        }
    }
    Box(box.then(hold).nestedScroll(conn).verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().height(contentH.dp)) {
            Layout(content = {
                for (ln in lines) key(ln.v, ln.y) { LyLine(ln, synced, idx, rm, Ly.d, Ly.t0) }
            }) { ms, cs ->
                val n = min(ms.size, lines.size)
                val ps = ArrayList<Placeable>(n)
                for (i in 0 until n) {
                    val ln = lines[i]
                    val l = (ln.x * d).roundToInt(); val t = (ln.y * d).roundToInt()
                    ps.add(ms[i].measure(Constraints.fixed(max(0, ((ln.x + ln.w) * d).roundToInt() - l), max(0, ((ln.y + ln.h) * d).roundToInt() - t))))
                }
                layout(cs.maxWidth, (contentH * d).roundToInt()) { for (i in 0 until n) ps[i].place((lines[i].x * d).roundToInt(), (lines[i].y * d).roundToInt()) }
            }
        }
    }
}

/** Un verso: sus renglones donde los puso la web; encendido, cantado o por cantar; y, el que suena, llenándose. */
@Composable
private fun LyLine(ln: MNode, synced: Boolean, idx: Int, rm: Boolean, fd: Float, t0: Long) {
    val i = ln.v.toIntOrNull() ?: -1
    val on = synced && i >= 0 && i == idx
    val target = if (on) Color.White else if (synced && i >= 0 && i < idx) LY_SUNG else LY_BASE
    val col by animateColorAsState(target, if (rm) snap<Color>() else tween<Color>(250), label = "ly")
    val sc by animateFloatAsState(if (on) 1.04f else 1f, if (rm) snap<Float>() else tween<Float>(250), label = "lys")
    // el relleno: de 0 a 1 en lo que dura el verso (desde cuando empezó, aunque se llegue tarde), en línea recta
    val fillOn = on && !ln.gap && fd > 0f && !rm
    val p = remember(ln.v) { Animatable(0f) }
    LaunchedEffect(on, fillOn, t0, fd) {
        if (!fillOn) { p.snapTo(if (on) 1f else 0f); return@LaunchedEffect }
        val total = fd * 1000f
        val p0 = ((System.currentTimeMillis() - t0) / total).coerceIn(0f, 1f)
        p.snapTo(p0)
        if (p0 < 1f) p.animateTo(1f, tween<Float>(((1f - p0) * total).roundToInt().coerceAtLeast(1), easing = LinearEasing))
    }
    if (on) SideEffect { Ly.fill = if (fillOn) p.value else 1f }
    var m: Modifier = Modifier.graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, 0.5f) }
    if (synced && i >= 0) m = m.pointerInput(i) { detectTapGestures(onTap = { Ly.taps++; UiState.send("lyTap") { it.put("i", i) } }) }
    val rows = ln.kids
    // (el relleno va sobre todos los renglones seguidos, uno detrás de otro: el ancho de todos y dónde empieza cada uno)
    var all = 0f
    val from = FloatArray(rows.size)
    for (k in rows.indices) { from[k] = all; all += rows[k].w }
    val pv = if (fillOn) p.value else 1f
    Layout(content = {
        for (k in rows.indices) {
            val r = rows[k]
            val st = r.st
            if (st == null) { Box(Modifier); continue }
            if (!synced) { BasicText(r.s, style = mStyleOf(st), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip); continue }
            val brush = if (fillOn && all > 0f) {
                val dd = LocalDensity.current.density
                val at = pv * all - from[k]
                Brush.horizontalGradient(listOf(Color.White, LY_DIM), startX = at * dd, endX = (at + all * 0.06f) * dd)
            } else null
            BasicText(r.s, style = mStyleOf(st, brush, col), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }, modifier = m) { ms, cs ->
        val d = density
        val n = min(ms.size, rows.size)
        val ps = ArrayList<Placeable>(n)
        for (k in 0 until n) ps.add(ms[k].measure(Constraints()))
        layout(cs.maxWidth, cs.maxHeight) {
            // (cada renglón, como en el resto de la copia: en su sitio, centrado en su alto)
            for (k in 0 until n) { val r = rows[k]; ps[k].place(((r.x - ln.x) * d).roundToInt(), ((r.y - ln.y + r.h / 2f) * d - ps[k].height / 2f).roundToInt()) }
        }
    }
}
