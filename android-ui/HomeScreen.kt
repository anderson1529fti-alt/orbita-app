package __PACKAGE__

import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import androidx.core.content.res.ResourcesCompat
import com.getcapacitor.JSArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * 4.5 · Fase 2, paso 2: «Ahora» (la pantalla principal), dibujada por Android (Kotlin y Compose) con las medidas de la
 * pantalla de siempre. Lo que enseña se lo manda la app, parte a parte, leído de su propia pantalla (que sigue debajo,
 * invisible, haciendo todo el trabajo); lo que se toca aquí, la app lo «pulsa» allí.
 */

// ---------------------------------------------------------------- lo que manda la app
internal fun JSONObject?.str(k: String): String = this?.optString(k, "") ?: ""
internal fun JSONObject?.on(k: String): Boolean = (this?.optInt(k, 0) ?: 0) == 1
internal fun JSONObject?.num(k: String, d: Float): Float = this?.optDouble(k, d.toDouble())?.toFloat() ?: d
internal fun JSONObject?.obj(k: String): JSONObject? = this?.optJSONObject(k)
internal fun JSONObject?.arr(k: String): List<JSONObject> {
    val a = this?.optJSONArray(k) ?: return emptyList()
    val out = ArrayList<JSONObject>(a.length())
    for (i in 0 until a.length()) { val o = a.optJSONObject(i); if (o != null) out.add(o) }
    return out
}
/** Una lista de listas de textos (las filas de una tabla). */
internal fun JSONObject?.arr2(k: String): List<List<String>> {
    val a = this?.optJSONArray(k) ?: return emptyList()
    val out = ArrayList<List<String>>(a.length())
    for (i in 0 until a.length()) { val r = a.optJSONArray(i) ?: continue; val row = ArrayList<String>(r.length()); for (j in 0 until r.length()) row.add(r.optString(j)); out.add(row) }
    return out
}
internal fun JSONObject?.strs(k: String): List<String> {
    val a = this?.optJSONArray(k) ?: return emptyList()
    val out = ArrayList<String>(a.length())
    for (i in 0 until a.length()) out.add(a.optString(i))
    return out
}

/** Tocar algo: la app pulsa su botón de siempre (por su nombre o por el número que le dio al leerlo). */
internal fun tap(sel: String) { UiState.send("hTap") { it.put("k", sel) } }
internal fun tap(o: JSONObject?) { val k = o?.optInt("k", 0) ?: 0; if (k > 0) UiState.send("hTap") { it.put("k", k) } }

// ---------------------------------------------------------------- iconos (los mismos trazados que la web)
private const val H_GEAR = "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M14 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M8 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M16 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"
private const val H_ALARM = "M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0M12 10v3.5l2 1.5M5 3 2.5 5.5M19 3l2.5 2.5"
private const val H_CHAT = "M4 5h16v11H9l-5 4z"
private const val H_MIC = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1 -6 0V6a3 3 0 0 1 3 -3zM5 11a7 7 0 0 0 14 0M12 18v3"
private const val H_SPARK = "M12 2l2.2 6.3L20.5 10l-6.3 2.2L12 18.5l-2.2-6.3L3.5 10l6.3-1.7z"
private const val H_ROUL = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6L5.6 18.4"
private const val H_RADIO = "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19.1 4.9a10 10 0 0 1 0 14.2M4.9 19.1a10 10 0 0 1 0-14.2"
private const val H_ALL = "M4 6h11M4 12h11M4 18h7M17 15v6l4-3z"
private const val H_DICE = "M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1 -4 4H7a4 4 0 0 1 -4 -4V7a4 4 0 0 1 4 -4z"
private const val H_DOTS = "M7.2 8.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0M14.2 15.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0M14.2 8.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0M7.2 15.5a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0M10.7 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0"
internal const val H_PLAY = "M7 4.5v15l13-7.5z"
private const val H_YT_BOX = "M6.5 5.5h11a4 4 0 0 1 4 4v5a4 4 0 0 1 -4 4h-11a4 4 0 0 1 -4 -4v-5a4 4 0 0 1 4 -4z"
private const val H_YT_TRI = "M10 9.2l5 2.8-5 2.8z"
private const val H_DOWN = "M6 9l6 6 6-6"

// ---------------------------------------------------------------- portadas
private val artPool = Executors.newFixedThreadPool(2)
private val artMain = Handler(Looper.getMainLooper())
private val artQueue = HashMap<Int, ArrayList<String>>()
private val artFlush = Runnable {
    for ((side, urls) in artQueue) {
        if (urls.isEmpty()) continue
        val a = JSArray()
        for (u in urls) a.put(u)
        UiState.send("needArt") { it.put("urls", a); it.put("side", side); if (side > 100) it.put("tag", "|L") }
    }
    artQueue.clear()
}

/** Una portada, reducida al tamaño al que se va a ver. */
internal fun decodeArt(b: ByteArray, side: Int): android.graphics.Bitmap? {
    val size = BitmapFactory.Options()
    size.inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(b, 0, b.size, size)
    if (size.outWidth <= 0 || size.outHeight <= 0) return null
    var sample = 1
    while (minOf(size.outWidth, size.outHeight) / (sample * 2) >= side) sample *= 2
    val opt = BitmapFactory.Options()
    opt.inSampleSize = sample
    val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, opt) ?: return null
    val m = minOf(bmp.width, bmp.height)
    if (m <= side * 1.15f) return bmp
    val k = side.toFloat() / m
    val out = android.graphics.Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * k).roundToInt()), maxOf(1, (bmp.height * k).roundToInt()), true)
    if (out !== bmp) bmp.recycle()
    return out
}

private fun putArt(key: String, bmp: ImageBitmap) {
    if (!UiState.covers.containsKey(key)) UiState.coverOrder.addLast(key)
    UiState.covers[key] = bmp
    while (UiState.coverOrder.size > UiState.COVERS_MAX) {
        val old = UiState.coverOrder.removeFirst()
        UiState.covers.remove(old); UiState.asked.remove(old)
    }
}

/** Pide una portada: las de internet las baja Android; las que guarda la app, se las pide a ella (varias de una vez). */
internal fun needArt(url: String, sideDp: Int, density: Float) {
    val key = if (sideDp > 100) "$url|L" else url
    if (url.isEmpty() || !UiState.asked.add(key)) return
    if (url.startsWith("http://") || url.startsWith("https://")) {
        val px = maxOf(32, (sideDp * density).roundToInt())
        artPool.execute {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                val bytes = c.inputStream.use { it.readBytes() }
                val bmp = decodeArt(bytes, px)
                if (bmp != null) artMain.post { putArt(key, bmp.asImageBitmap()) }
                else artMain.postDelayed({ UiState.asked.remove(key) }, 60000)
            } catch (t: Throwable) {
                artMain.postDelayed({ UiState.asked.remove(key) }, 60000)   // (sin conexión: se vuelve a intentar más tarde)
            }
        }
        return
    }
    artQueue.getOrPut(sideDp) { ArrayList() }.add(url)
    artMain.removeCallbacks(artFlush)
    artMain.postDelayed(artFlush, 40)
}

/** Una portada (o, si no tiene, su inicial sobre el degradado de la canción). */
@Composable
internal fun HCover(c: JSONObject?, sk: Skin, modifier: Modifier, letter: Float, side: Int = 76) {
    val u = c.str("u")
    val key = if (side > 100 && u.isNotEmpty()) "$u|L" else u
    val bmp = if (u.isNotEmpty()) UiState.covers[key] else null
    val dens = LocalDensity.current.density
    if (u.isNotEmpty() && bmp == null) LaunchedEffect(key) { needArt(u, side, dens) }
    Box(modifier.background(Color(0x802E2250)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (u.isEmpty()) {
            val h = c.num("h", 280f)
            Box(Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(hsl(h, 0.75f, 0.50f), hsl(h + 60f, 0.70f, 0.26f))))
                .drawBehind {
                    val ctr = Offset(size.width * 0.30f, size.height * 0.25f)
                    drawRect(Brush.radialGradient(0f to hsl(h + 40f, 0.90f, 0.70f, 0.9f), 0.55f to Color.Transparent, center = ctr, radius = size.width * 1.03f))
                }, contentAlignment = Alignment.Center) {
                T(c.str("i").ifEmpty { "♪" }, letter * sk.tk, Color.White, family = sk.display, weight = sk.displayWeight, lh = 1f, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- piezas
/** El peso «negrita» de los títulos con la letra elegida. */
internal val Skin.displayBold: FontWeight get() = if (titles == "retro") FontWeight.SemiBold else FontWeight.Bold

/** Un degradado de CSS (ángulo en grados, 0 = hacia arriba) para una caja de este tamaño. */
internal fun cssLinear(deg: Float, size: Size, vararg stops: Pair<Float, Color>): Brush {
    val a = deg * PI.toFloat() / 180f
    val dx = sin(a); val dy = -cos(a)
    val len = abs(size.width * dx) + abs(size.height * dy)
    val c = Offset(size.width / 2, size.height / 2)
    return Brush.linearGradient(*stops, start = Offset(c.x - dx * len / 2, c.y - dy * len / 2), end = Offset(c.x + dx * len / 2, c.y + dy * len / 2))
}

/** El recuadro «con luz» de la app: fondo liso, borde que va del color del momento a nada y vuelve, y brillo alrededor. */
internal fun Modifier.glowPanel(sk: Skin, radius: Float = 20f): Modifier {
    val p = sk.pal
    val r = (radius * sk.round).dp
    return this.glow(Color.Black.copy(alpha = 0.8f), r, 60.dp, 24.dp, (-30).dp).glow(p.glow, r, 50.dp, 0.dp, (-18).dp)
        .clip(RoundedCornerShape(r)).background(p.panelSolid)
        .drawBehind {
            val w = sk.b.toPx()
            drawRoundRect(cssLinear(130f, size, 0f to p.accent, 0.4f to Color.Transparent, 0.6f to Color.Transparent, 1f to p.accent2),
                Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.toPx() - w / 2), style = Stroke(w))
        }
}

/** El recuadro normal. */
internal fun Modifier.panel(sk: Skin): Modifier {
    val r = (20f * sk.round).dp
    val black = sk.pal.bg == Color.Black
    return this.glow(Color.Black.copy(alpha = 0.8f), r, 60.dp, 24.dp, (-30).dp).clip(RoundedCornerShape(r))
        .background(if (black) Color(0xEB0C0A12) else Color(0xCC140F24)).border(sk.b, sk.pal.line, RoundedCornerShape(r))
}

/**
 * Cosas en fila que pasan al renglón siguiente cuando no caben (como «flex-wrap» en la web), centradas en su renglón.
 * «grow» es la que se estira para llenar su renglón (con «basis» como ancho mínimo para decidir si cabe).
 */
@Composable
internal fun HFlow(hGap: Dp, vGap: Dp, modifier: Modifier = Modifier, grow: Int = -1, basis: Dp = 0.dp, bottom: Boolean = false, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gx = hGap.roundToPx(); val gy = vGap.roundToPx()
        val maxW = constraints.maxWidth
        val base = basis.roundToPx()
        val loose = Constraints(0, maxW, 0, Constraints.Infinity)
        val ps = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        val ws = IntArray(measurables.size)
        for (i in measurables.indices) {
            if (i == grow) ws[i] = minOf(base, maxW)
            else { val pl = measurables[i].measure(loose); ps[i] = pl; ws[i] = pl.width }
        }
        // en qué renglón va cada una
        val row = IntArray(measurables.size)
        var x = 0; var r = 0
        for (i in measurables.indices) {
            if (x > 0 && x + ws[i] > maxW) { x = 0; r++ }
            row[i] = r
            x += ws[i] + gx
        }
        if (grow >= 0 && grow < measurables.size) {
            var used = 0; var n = 0
            for (i in measurables.indices) if (row[i] == row[grow] && i != grow) { used += ws[i]; n++ }
            val w = maxOf(0, maxW - used - n * gx)
            ps[grow] = measurables[grow].measure(Constraints(w, w, 0, Constraints.Infinity)); ws[grow] = w
        }
        val rows = r + 1
        val hs = IntArray(rows)
        for (i in measurables.indices) hs[row[i]] = maxOf(hs[row[i]], ps[i]!!.height)
        val ys = IntArray(rows)
        var y = 0
        for (k in 0 until rows) { ys[k] = y; y += hs[k] + (if (k < rows - 1) gy else 0) }
        layout(maxW, y) {
            var cx = 0; var cr = 0
            for (i in measurables.indices) {
                if (row[i] != cr) { cr = row[i]; cx = 0 }
                val pl = ps[i]!!
                pl.place(cx, ys[cr] + (if (bottom) hs[cr] - pl.height else (hs[cr] - pl.height) / 2))
                cx += ws[i] + gx
            }
        }
    }
}

/**
 * Una columna de filas con la altura exacta de la web. En la web una fila puede medir «134,5 puntos de pantalla»; Android
 * solo sabe de puntos enteros, y en una lista larga el medio punto de cada fila se va sumando. Aquí cada fila se coloca
 * donde le toca según la cuenta exacta.
 */
@Composable
internal fun ExactColumn(gap: Float, heights: List<Float>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { ms, c ->
        val n = minOf(ms.size, heights.size)
        val ys = IntArray(n + 1)
        var y = 0f
        for (i in 0 until n) { ys[i] = (y * density).roundToInt(); y += heights[i] + (if (i < n - 1) gap else 0f) }
        ys[n] = (y * density).roundToInt()
        val ps = ArrayList<androidx.compose.ui.layout.Placeable>(n)
        for (i in 0 until n) {
            val h = maxOf(1, (if (i < n - 1) ys[i + 1] - (gap * density).roundToInt() else ys[n]) - ys[i])
            ps.add(ms[i].measure(Constraints(c.maxWidth, c.maxWidth, h, h)))
        }
        layout(c.maxWidth, ys[n]) { for (i in 0 until n) ps[i].place(0, ys[i]) }
    }
}

/** Un botón de la app con las medidas de la web (normal, pequeño o grande), con su icono si lo lleva. */
@Composable
internal fun HBtn(text: String, sk: Skin, modifier: Modifier = Modifier, primary: Boolean = false, kind: Int = 0, enabled: Boolean = true, on: Boolean = false,
                 icon: String? = null, iconFill: Boolean = false, iconSize: Float = 18f, iconSw: Float = 2f, dots: String? = null, danger: Boolean = false, ghost: Boolean = false, onClick: () -> Unit) {
    // kind: 0 normal, 1 pequeño, 2 grande, 3 grande con más aire (el «Posponer» del despertador)
    val p = sk.pal
    val rad = when (kind) { 1 -> 11f; 2, 3 -> 16f; else -> 14f }
    val shape = sk.r(rad)
    val fs = when (kind) { 1 -> 13.6f; 2 -> 16.32f; 3 -> 17.92f; else -> 15f } * sk.tk
    val ph = when (kind) { 1 -> 13.dp; 2 -> 22.dp; 3 -> 20.dp; else -> 18.dp }
    val pv = when (kind) { 1 -> 8.dp; 2 -> 15.dp; 3 -> 20.dp; else -> 12.dp }
    val fg = if (primary) p.onAccent else if (danger) p.bad else if (on) p.accent else if (ghost) p.muted else p.fg
    var m = modifier
    if (primary) m = m.glow(p.glow, (rad * sk.round).dp, 30.dp, 10.dp, (-10).dp)
    else if (on) m = m.glow(p.glow, (rad * sk.round).dp, 22.dp, 0.dp, (-8).dp)
    m = m.press(enabled = enabled, onClick = onClick).clip(shape)
    m = if (primary) m.background(p.grad) else if (ghost) m else m.background(Color.White.copy(alpha = 0.04f)).border(sk.b, if (danger) p.bad else if (on) p.accent else p.line2, shape)
    if (ghost) m = m.padding(sk.b)
    Row(m.graphicsLayer { alpha = if (enabled) 1f else 0.5f }.padding(horizontal = ph + sk.b, vertical = pv + sk.b),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (icon != null) Box {
            Ico(icon, iconSize, fg, fill = iconFill, sw = iconSw)
            // (los puntos del dado: rellenos y con su contorno, como en la web)
            if (dots != null) { Ico(dots, iconSize, fg, fill = true); Ico(dots, iconSize, fg, sw = iconSw) }
        }
        if (text.isNotEmpty()) T(text, fs, fg, weight = FontWeight.ExtraBold, maxLines = 1)
        else Box(Modifier.height((fs * 1.45f).dp))
    }
}

/** Un botón tal como lo manda la app (texto, si es el principal, si lleva el triángulo de «reproducir»…). */
@Composable
internal fun JBtn(b: JSONObject, sk: Skin, kind: Int, iconSize: Float = 16f) {
    HBtn(b.str("t"), sk, primary = b.on("p"), kind = kind, enabled = !b.on("d"), on = b.on("on"), icon = if (b.on("i")) H_PLAY else null, iconFill = true, iconSize = iconSize) { tap(b) }
}

/** Una sugerencia (borde de rayas): al tocarla, la app pulsa la suya. */
@Composable
internal fun Idea(o: JSONObject, sk: Skin) {
    Box(Modifier.press { tap(o) }.dashed(sk.pal.line2, 999.dp, sk.b).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) {
        T(o.str("t"), 12.8f * sk.tk, sk.pal.muted, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** Las letras pequeñas de encima de los títulos. */
@Composable
internal fun Eyebrow(text: String, sk: Skin, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    T(text.uppercase(), 11.2f * sk.tk, sk.pal.muted, modifier, family = Mono, weight = FontWeight.SemiBold, ls = 0.16f, maxLines = maxLines)
}

/** Título de recuadro y, a su derecha (o debajo si no cabe), su nota. */
@Composable
private fun HeadRow(title: String, right: String, sk: Skin) {
    Layout(content = {
        T(title, 20f * sk.tk, sk.pal.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
        Eyebrow(right, sk)
    }) { ms, c ->
        val g = 12.dp.roundToPx()
        val a = ms[0].measure(Constraints(0, c.maxWidth, 0, Constraints.Infinity))
        val b = ms[1].measure(Constraints(0, c.maxWidth, 0, Constraints.Infinity))
        if (a.width + 2 * g + b.width <= c.maxWidth) {
            val h = maxOf(a.height, b.height)
            layout(c.maxWidth, h) { a.place(0, (h - a.height) / 2); b.place(c.maxWidth - b.width, (h - b.height) / 2) }
        } else layout(c.maxWidth, a.height + g + b.height) { a.place(0, 0); b.place(0, a.height + g) }
    }
}

/** Un interruptor de los de arriba («Voz», «Presenta cada canción») o un botón con icono del mismo aspecto. */
@Composable
internal fun Toggle(text: String, on: Boolean, sk: Skin, icon: String? = null, onClick: () -> Unit) {
    val p = sk.pal
    val shape = RoundedCornerShape(999.dp)
    val kx by animateFloatAsState(if (on) 12f else 0f, tween(280), label = "sw")
    Row(Modifier.press(onClick = onClick).clip(shape).background(Color.Black.copy(alpha = 0.25f)).border(sk.b, if (on) p.accent.copy(alpha = 0.5f) else p.line2, shape)
        .padding(start = 8.dp + sk.b, end = 12.dp + sk.b, top = 7.dp + sk.b, bottom = 7.dp + sk.b), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Ico(icon, 14f, if (on) p.fg else p.muted, sw = 2f)
        else Box(Modifier.size(28.dp, 16.dp).background(if (on) p.accent else p.line2, shape)) {
            Box(Modifier.padding(start = (2f + kx).dp, top = 2.dp).size(12.dp).background(if (on) p.onAccent else p.fg, CircleShape))
        }
        T(text, 13.12f * sk.tk, if (on) p.fg else p.muted, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** Un desplegable de la app: al tocarlo, la lista de opciones; la app cambia el suyo. */
@Composable
private fun Select(sel: String, o: JSONObject?, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(10f)
    var open by remember { mutableStateOf(false) }
    // la lista desplegada se cierra si la pantalla se aparta o se mueve
    val hidden = UiState.hideN
    LaunchedEffect(hidden) { open = false }
    if (open) LaunchedEffect(Unit) {
        val st = UiState.homeList
        val y0 = st.firstVisibleItemIndex * 100000 + st.firstVisibleItemScrollOffset
        while (true) {
            delay(100)
            if (st.firstVisibleItemIndex * 100000 + st.firstVisibleItemScrollOffset != y0) { open = false; break }
        }
    }
    val opts = remember(o) { val a = o?.optJSONArray("o"); val l = ArrayList<Pair<String, String>>(); if (a != null) for (i in 0 until a.length()) { val x = a.optJSONArray(i); if (x != null) l.add(x.optString(0) to x.optString(1)) }; l }
    val cur = o.str("v")
    val label = opts.firstOrNull { it.first == cur }?.second ?: (opts.firstOrNull()?.second ?: "")
    Box {
        // tan ancho como su opción más larga (como en la web), con el tope de 220
        Layout(content = {
            Row(Modifier.clip(shape).background(p.panelSolid).border(sk.b, p.line2, shape).clickable(remember { MutableInteractionSource() }, null) { open = true }
                .padding(start = 14.7.dp, end = 1.3.dp, top = 7.dp + sk.b, bottom = 7.dp + sk.b), verticalAlignment = Alignment.CenterVertically) {
                T(label, 13.12f * sk.tk, p.fg, Modifier.weight(1f), lh = 1.335f, maxLines = 1)
                Ico(H_DOWN, 14f, p.fg, sw = 2f)
            }
            for ((_, name) in opts) T(name, 13.12f * sk.tk, Color.Transparent, lh = 1.335f, maxLines = 1)
        }) { ms, c ->
            var w = 0
            for (i in 1 until ms.size) w = maxOf(w, ms[i].maxIntrinsicWidth(Constraints.Infinity))
            val full = minOf(minOf(w + 41.75.dp.roundToPx(), 220.dp.roundToPx()), c.maxWidth)
            val pl = ms[0].measure(Constraints(full, full, 0, Constraints.Infinity))
            layout(pl.width, pl.height) { pl.place(0, 0) }
        }
        if (open) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }), onDismissRequest = { open = false }) {
            val ps = sk.r(12f)
            Column(Modifier.width(240.dp).glow(Color.Black.copy(alpha = 0.6f), 12.dp, 30.dp, 10.dp, 0.dp).clip(ps).background(p.panelSolid).border(sk.b, p.line2, ps).padding(vertical = 6.dp)) {
                for ((id, name) in opts) {
                    val on = id == cur
                    Box(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {
                        open = false
                        UiState.send("hSel") { it.put("sel", sel); it.put("v", id) }
                    }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        T(name, 15f * sk.tk, if (on) p.accent else p.fg, weight = if (on) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Varias opciones pegadas, una marcada (las duraciones de la mezcla). */
@Composable
internal fun SegRow(items: List<JSONObject>, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(12f)
    val dens = LocalDensity.current.density
    val padStart = floor(14f * dens)
    val padEnd = (28f * dens).roundToInt() - padStart
    Row(Modifier.clip(shape).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, shape).padding(sk.b)) {
        items.forEachIndexed { i, o ->
            val on = o.on("on")
            Box(Modifier.drawBehind { if (i > 0) drawRect(p.line2, Offset.Zero, Size(sk.b.toPx(), size.height)) }.padding(start = if (i > 0) sk.b else 0.dp)
                .background(if (on) p.fg else Color.Transparent).clickable(remember { MutableInteractionSource() }, null) { tap(o) }
                .padding(start = (padStart / dens).dp, end = (padEnd / dens).dp, top = 10.dp, bottom = 10.dp)) {
                T(o.str("t"), 13.76f * sk.tk, if (on) p.bg else p.muted, weight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- la pantalla
@Composable
fun HomeScreen() {
    val sk = rememberSkin()
    val top = UiState.hTop
    val tick = UiState.hTick
    val fy = UiState.hFy
    val mixer = UiState.hMixer
    val lite = top.on("lite")
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vw = maxWidth.value
            if (top != null) LazyColumn(Modifier.fillMaxSize(), state = UiState.homeList, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 26.dp, bottom = Tubo.viewBottom()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                val bans = top.arr("ban")
                items(bans.size, key = { "#ban$it" }) { i -> Banner(bans[i], sk) }
                item(key = "#hero") { Hero(top, sk, vw, lite) }
                if (tick != null && !tick.on("off")) item(key = "#tick") { Ticker(tick, sk, lite) }
                item(key = "#dj") { DjPanel(sk, lite) }
                if (fy != null && !fy.on("off")) item(key = "#fy") { ForYou(fy, sk) }
                if (mixer != null && !mixer.on("off")) item(key = "#mixer") { Mixer(mixer, sk) }
                item(key = "#out") { MixOut(UiState.hOut, sk) }
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

/** Un aviso de arriba (versión nueva, dar acceso a la música, el resumen del año). */
@Composable
private fun Banner(o: JSONObject, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(16f)
    val btns = o.arr("btns")
    HFlow(12.dp, 12.dp, Modifier.fillMaxWidth().glow(p.glow, (16f * sk.round).dp, 30.dp, 0.dp, (-12).dp).clip(shape)
        .background(Brush.horizontalGradient(listOf(p.wash, Color.Black.copy(alpha = 0.35f)))).border(sk.b, p.accent, shape).padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b), grow = 0, basis = 240.dp) {
        Column {
            if (o.str("b").isNotEmpty()) T(o.str("b"), 15f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.01f)
            if (o.str("t").isNotEmpty()) T(o.str("t"), 13.12f * sk.tk, p.muted)
        }
        for (b in btns) JBtn(b, sk, 1)
    }
}

// ---------------------------------------------------------------- la cabecera
@Composable
private fun Hero(top: JSONObject, sk: Skin, vw: Float, lite: Boolean) {
    val p = sk.pal
    val side = min(140f, vw * 0.34f)
    val pill = RoundedCornerShape(999.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            // «ÓRBITA FM» y la hora
            val blink = if (lite) null else rememberInfiniteTransition(label = "dot").animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "b")
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).clip(pill).background(Color.Black.copy(alpha = 0.35f)).border(sk.b, p.line2, pill).padding(horizontal = 12.dp + sk.b, vertical = 5.dp + sk.b),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).graphicsLayer { alpha = blink?.value ?: 1f }.glow(p.accent, 4.dp, 10.dp, 0.dp, 0.dp).background(p.accent, CircleShape))
                T("ÓRBITA FM", 12.48f * sk.tk, p.fg, family = Mono, weight = FontWeight.SemiBold, ls = 0.08f, maxLines = 1)
                T(top.str("clock"), 12.48f * sk.tk, p.fg, family = Mono, weight = FontWeight.SemiBold, ls = 0.08f, maxLines = 1)
            }
            if (top.str("alarm").isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Row(Modifier.press { tap("#alarmPill") }.clip(pill).background(Color.Black.copy(alpha = 0.35f)).border(sk.b, p.line2, pill).padding(horizontal = 12.dp + sk.b, vertical = 5.dp + sk.b),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ico(H_ALARM, 14f, p.accent, sw = 2.2f)
                    T(top.str("alarm"), 12.48f * sk.tk, p.fg, family = Mono, weight = FontWeight.SemiBold, ls = 0.08f, maxLines = 1)
                }
            }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(34.dp).semantics { contentDescription = "Ajustes: tema, voces y animaciones" }.press(0.9f) { tap(".hero-top .gear") }.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).border(sk.b, p.line2, CircleShape),
                contentAlignment = Alignment.Center) { Ico(H_GEAR, 17f, p.muted, sw = 2f) }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val wpx = constraints.maxWidth.toFloat()
                // (la Órbita nativa no lo manda: lo mide aquí, como «fitTitle» de la web: si no cabe, encoge)
                val dfs = if (top.has("dfs")) top.num("dfs", 41.6f * sk.tk) else {
                    val tm = androidx.compose.ui.text.rememberTextMeasurer()
                    val base = 41.6f * sk.tk
                    val need = tm.measure(top.str("day").uppercase(), style(base, p.fg, sk.display, sk.displayWeight, 1.2f, -0.045f), maxLines = 1, softWrap = false).size.width.toFloat()
                    if (need > wpx && need > 0f) base * kotlin.math.max(0.3f, (wpx / need) * 0.97f) else base
                }
                Column {
                    // el día (su tamaño lo decide la app: los nombres largos encogen para no meterse bajo el disco)
                    Box(Modifier.height((dfs * 0.9f).dp), contentAlignment = Alignment.CenterStart) {
                        BasicText(top.str("day").uppercase(), Modifier.wrapContentHeight(unbounded = true).wrapContentWidth(Alignment.Start, unbounded = true),
                            style = style(dfs, p.fg, sk.display, sk.displayWeight, 1.2f, -0.045f, brush = Brush.horizontalGradient(0.10f to Color.White, 0.55f to p.accent, 0.95f to p.accent2, startX = 0f, endX = wpx)),
                            maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.height((3.744f * sk.tk).dp))
                    // la franja («por la tarde»): solo el contorno de las letras
                    Box(Modifier.height((18.72f * sk.tk).dp), contentAlignment = Alignment.CenterStart) {
                        val sw = with(LocalDensity.current) { 1.15.dp.toPx() }
                        BasicText(top.str("frag"), Modifier.wrapContentHeight(unbounded = true).wrapContentWidth(Alignment.Start, unbounded = true),
                            style = style(20.8f * sk.tk, p.accent2, sk.display, FontWeight.Medium, 1.2f, 0.03f).copy(drawStyle = Stroke(width = sw)), maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.height(14.dp))
                    T(top.str("hint"), 14.72f * sk.tk, p.muted)
                }
            }
            Spacer(Modifier.width(18.dp))
            if (top.str("hero") == "art") {
                val shape = sk.r(22f)
                Box(Modifier.size(side.dp).graphicsLayer { rotationZ = 2f }.semantics { contentDescription = "Abrir Ahora suena" }.glow(p.glow, (22f * sk.round).dp, 70.dp, 30.dp, (-25).dp)
                    .clickable(remember { MutableInteractionSource() }, null) { tap("#artWrap") }.clip(shape).border(sk.b, p.line2, shape)) {
                    HCover(top.obj("art"), sk, Modifier.fillMaxSize(), 80f * side / 140f, side = 150)
                }
            } else if (top.str("hero") == "viz") {
                // 4.9 · el visualizador en lugar del disco (un círculo; lo dibuja Android, oyendo al reproductor)
                VizBox("vizHero", Modifier.size(side.dp).semantics { contentDescription = "Visualizador. Abrir Ahora suena" }
                    .clickable(remember { MutableInteractionSource() }, null) { tap("#vizWrap") }.clip(CircleShape))
            } else {
                // (en la web el disco es un dibujo «en línea»: debajo le queda el hueco de un renglón)
                Box(Modifier.width(side.dp).height((side + 6.75f * sk.tk).dp), contentAlignment = Alignment.TopStart) {
                    Vinyl(Modifier.size(side.dp).semantics { contentDescription = "Abrir Ahora suena" }.clickable(remember { MutableInteractionSource() }, null) { tap("#vinylWrap") }, sk, side)
                }
            }
        }
    }
}

/** Lo que el disco recuerda de un fotograma a otro. */
private class VinylMem {
    var angle = 0f
    var last = 0L
    var kick = 0f
    var level = 0f
    val rings = ArrayList<FloatArray>()   // [radio 0..1, opacidad]
    val vals = FloatArray(64)
    var label: Typeface? = null
    var body: Typeface? = null
}

/** El disco que gira, con sus barras alrededor (se mueven con lo que suena) y el arco de por dónde va la canción. */
@Composable
private fun Vinyl(modifier: Modifier, sk: Skin, side: Float) {
    val v = UiState.hVin
    val playing = v.on("play")
    val mem = remember { VinylMem() }
    val ctx = LocalContext.current
    if (mem.label == null) {
        mem.label = try { ResourcesCompat.getFont(ctx, R.font.unbounded_900) } catch (t: Throwable) { null } ?: Typeface.DEFAULT_BOLD
        mem.body = try { ResourcesCompat.getFont(ctx, R.font.figtree_700) } catch (t: Throwable) { null } ?: Typeface.DEFAULT_BOLD
    }
    var frame by remember { mutableStateOf(0L) }
    val slow = v.on("slow")
    LaunchedEffect(playing, slow) {
        while (true) {
            withFrameNanos { frame = it }
            delay(if (!playing) 90L else if (slow) 50L else 20L)
        }
    }
    val art = v.str("art")
    val bmp = if (art.isNotEmpty()) UiState.covers[art] else null
    val dens = LocalDensity.current.density
    if (art.isNotEmpty() && bmp == null) LaunchedEffect(art) { needArt(art, 60, dens) }
    val look = UiState.look
    Canvas(modifier) {
        val ts = frame / 1.0e6
        val dt = if (mem.last == 0L) 0f else min(0.05f, (frame - mem.last) / 1.0e9f)
        mem.last = frame
        val rm = v.on("rm")
        if (playing && !rm) mem.angle += dt * 2f
        val hue = look.h; val sat = look.s
        fun c(h: Float, s: Float, l: Float, a: Float) = hsl(h, s * sat / 100f, l / 100f, a)
        // (la web lo dibuja en un lienzo de 1,5 puntos por «px»: las medidas fijas van en esa escala)
        val f = size.width / (side * 1.5f)
        val wc = side * 1.5f
        val cx = size.width / 2; val cy = size.height / 2
        val R = (wc / 2 - 2f) * f
        val dR = R * 0.74f
        val ctr = Offset(cx, cy)
        // lo que suena, por frecuencias
        val spec = NovaCore.lastSpec
        val own = playing && v.on("own") && spec != null && spec.size >= 256 && SystemClock.uptimeMillis() - NovaCore.lastSpecAt < 500
        var level = 0f
        val n = 64
        for (i in 0 until n) {
            val x = if (own) { val k = floor((i.toFloat() / n).toDouble().pow(1.6) * (256 * 0.7)).toInt() + 1; (spec!![min(255, k)].toInt() and 0xff) / 255f }
            else if (playing) (0.22 + 0.2 * sin(ts / 260 + i * 0.55) * sin(ts / 410 + i * 0.21) + 0.08 * sin(ts / 90 + i)).toFloat()
            else (0.04 + 0.03 * sin(ts / 900 + i * 0.4)).toFloat()
            mem.vals[i] = x.coerceIn(0f, 1f); level += mem.vals[i]
        }
        level /= n
        val lit = v.on("lit") && playing
        if (lit) {
            var hit = 0; var lv = 0f
            if (own && spec!!.size > 774) { lv = (spec[773].toInt() and 0xff) / 255f; hit = NovaCore.specHits; NovaCore.specHits = 0 }
            if (hit and 1 != 0) { mem.kick = 1f; mem.rings.add(floatArrayOf(0f, 0.75f)); if (mem.rings.size > 4) mem.rings.removeAt(0) }
            else mem.kick *= exp(-dt * 6.5f)
            val L = ((lv - 0.45f) / 0.45f).coerceIn(0f, 1f)
            mem.level += (L - mem.level) * min(1f, dt * (if (L > mem.level) 12f else 4f))
            level = max(level, mem.level * 0.8f)
        } else { mem.kick = 0f; mem.level = 0f; mem.rings.clear() }
        // el resplandor
        val h0 = c(hue, 96f, 60f, 0f)
        drawCircle(Brush.radialGradient(0f to h0, 0.55f to h0, 0.82f to c(hue, 96f, 60f, (0.12f + level * 0.35f + (if (lit) mem.kick * 0.3f else 0f)).coerceIn(0f, 1f)), 1f to c(hue - 45f, 92f, 60f, 0f), center = ctr, radius = R), R, ctr)
        if (lit) {
            val it = mem.rings.iterator()
            while (it.hasNext()) {
                val w = it.next()
                w[0] += 0.033f * 1.6f; w[1] *= 0.86f
                drawCircle(c(hue, 96f, 72f, w[1] * 0.7f), R * (0.8f + 0.2f * min(1f, w[0])), ctr, style = Stroke(max(1f, wc / 220f) * f))
                if (w[1] <= 0.04f || w[0] >= 1f) it.remove()
            }
        }
        // las barras de alrededor
        val lw = max(2f, wc / 120f) * f
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat() - PI.toFloat() / 2f + (if (rm) 0f else mem.angle * 0.05f)
            val r1 = R * 0.79f; val r2 = r1 + R * 0.025f + mem.vals[i] * R * 0.18f
            drawLine(c(hue - 45f * (i.toFloat() / n), 95f, 64f, 0.35f + mem.vals[i] * 0.65f), Offset(cx + cos(a) * r1, cy + sin(a) * r1), Offset(cx + cos(a) * r2, cy + sin(a) * r2), lw, StrokeCap.Round)
        }
        // el disco y sus surcos
        drawCircle(Brush.radialGradient(0f to Color(0xFF1D1828), 0.2f to Color(0xFF1D1828), 1f to Color(0xFF050409), center = ctr, radius = dR), dR, ctr)
        val gw = max(1f, wc / 500f) * f
        for (k in 0 until 22) drawCircle(Color.White.copy(alpha = if (k % 4 == 0) 0.07f else 0.03f), dR * (0.47f + k * 0.024f), ctr, style = Stroke(gw))
        val rim = max(2f, wc / 150f) * f
        drawCircle(Color.White.copy(alpha = 0.08f), dR + R * 0.025f, ctr, style = Stroke(rim))
        val lR = dR * 0.42f
        rotate(mem.angle * 180f / PI.toFloat(), ctr) {
            // los dos brillos
            for (off in 0..1) {
                val dir = if (off == 0) 1f else -1f
                drawArc(Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), c(hue, 90f, 75f, 0.14f)), start = ctr, end = Offset(cx + dir * dR, cy)),
                    (if (off == 0) 0f else 180f) - 20.05f, 40.1f, true, Offset(cx - dR, cy - dR), Size(dR * 2, dR * 2))
            }
            // la etiqueta: la portada o el nombre de la app
            val circle = Path().apply { addOval(Rect(cx - lR, cy - lR, cx + lR, cy + lR)) }
            if (bmp != null) clipPath(circle) {
                drawIntoCanvas { cv -> cv.nativeCanvas.drawBitmap(bmp.asAndroidBitmap(), null, android.graphics.RectF(cx - lR, cy - lR, cx + lR, cy + lR), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)) }
            } else {
                drawCircle(Brush.linearGradient(listOf(c(hue, 96f, 62f, 1f), c(hue - 45f, 92f, 55f, 1f)), start = Offset(cx - lR, cy - lR), end = Offset(cx + lR, cy + lR)), lR, ctr)
                drawIntoCanvas { cv ->
                    val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    pt.color = android.graphics.Color.argb(217, 20, 8, 28)
                    pt.textAlign = android.graphics.Paint.Align.CENTER
                    val lc = lR / f
                    pt.typeface = mem.label; pt.textSize = Math.round(lc * 0.3f) * f
                    cv.nativeCanvas.drawText("ÓRBITA", cx, cy - lR * 0.38f - (pt.ascent() + pt.descent()) / 2f, pt)
                    val title = v.str("title")
                    pt.typeface = mem.body; pt.textSize = Math.round(lc * 0.16f) * f
                    cv.nativeCanvas.drawText(if (v.str("id").isEmpty()) "FM" else if (title.length > 18) title.take(17) + "…" else title, cx, cy + lR * 0.42f - (pt.ascent() + pt.descent()) / 2f, pt)
                }
            }
        }
        drawCircle(Color(0xFF06050B), R * 0.03f, ctr)
        // por dónde va la canción
        val dur = v.num("dur", 0f)
        if (v.str("id").isNotEmpty() && dur > 0f) {
            val pos = (v.num("pos", 0f) + (if (playing) (SystemClock.uptimeMillis() - UiState.hVinAt) / 1000f else 0f)).coerceIn(0f, dur)
            val rr = dR + R * 0.025f
            drawArc(c(hue, 96f, 66f, 1f), -90f, pos / dur * 360f, false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(rim, cap = StrokeCap.Round))
        }
    }
}

// ---------------------------------------------------------------- la cinta
@Composable
private fun Ticker(o: JSONObject, sk: Skin, lite: Boolean) {
    val p = sk.pal
    val items = remember(o) { o.strs("items") }
    var half by remember { mutableStateOf(0) }
    val ph = if (lite) null else rememberInfiniteTransition(label = "cinta").animateFloat(0f, 1f, infiniteRepeatable(tween(38000, easing = LinearEasing)), label = "x")
    val look = UiState.look
    Box(Modifier.layout { m, c ->
        // (sobresale 6 por cada lado, como en la web)
        val extra = 12.dp.roundToPx()
        val pl = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
        layout(c.maxWidth, pl.height) { pl.place(-extra / 2, 0) }
    }.graphicsLayer { rotationZ = -1.2f }
        .background(Brush.horizontalGradient(listOf(p.wash, hsl(look.h - 45f, look.s * 0.92f, 0.55f, 0.16f))))
        .drawBehind {
            val w = sk.b.toPx()
            drawRect(p.line2, Offset.Zero, Size(size.width, w)); drawRect(p.line2, Offset(0f, size.height - w), Size(size.width, w))
        }.clipToBounds().padding(vertical = 10.dp + sk.b)) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).onSizeChanged { half = it.width / 2 }.graphicsLayer { translationX = -(ph?.value ?: 0f) * half }) {
            for (k in 0..1) for (t in items) {
                T(t.uppercase(), 14.72f * sk.tk, p.fg, Modifier.padding(end = 28.dp), family = sk.display, weight = sk.displayBold, ls = 0.02f, maxLines = 1)
                T("✦", 14.72f * sk.tk, p.accent, Modifier.padding(end = 28.dp), family = sk.display, weight = sk.displayBold, ls = 0.02f, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- DJ Nova
/** El círculo de Nova: el aro de colores que gira, su halo y las barras (que se mueven cuando habla). */
@Composable
private fun Avatar(sk: Skin, mine: Boolean, talk: Boolean, lite: Boolean) {
    val p = sk.pal
    val tr = if (lite) null else rememberInfiniteTransition(label = "nova")
    val spinS = tr?.animateFloat(0f, 360f, infiniteRepeatable(tween(if (talk) 3500 else 7000, easing = LinearEasing)), label = "s")
    val beatS = if (tr != null && talk) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "b") else null
    val barsS = if (tr != null && talk) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(500, easing = LinearEasing)), label = "t") else null
    val lime = Color(0xFFA3E635); val teal = Color(0xFF21D4A8)
    Canvas(Modifier.size(66.dp)) {
        val spin = spinS?.value ?: 0f
        val beat = beatS?.value ?: 0f
        val bars = barsS?.value ?: -1f
        val ctr = Offset(size.width / 2, size.height / 2)
        val ring = if (mine) Brush.sweepGradient(listOf(p.good, p.accent, p.good), ctr) else Brush.sweepGradient(listOf(lime, p.accent, p.accent2, teal, lime), ctr)
        val from = if (mine) -90f else -50f
        // el halo (difuminado, por fuera)
        rotate(spin + from, ctr) {
            drawIntoCanvas { cv ->
                val pt = Paint()
                ring.applyTo(size, pt, if (talk) 0.55f + 0.45f * beat else 0.7f)
                pt.asFrameworkPaint().maskFilter = BlurMaskFilter(17.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                cv.drawCircle(ctr, (size.width / 2 + 8.dp.toPx()) * (1f + (if (talk) 0.18f * beat else 0f)), pt)
            }
            drawCircle(ring, size.width / 2, ctr)
        }
        // el centro oscuro
        val cc = Offset(4.dp.toPx() + 58.dp.toPx() * 0.35f, 4.dp.toPx() + 58.dp.toPx() * 0.30f)
        clipPath(Path().apply { addOval(Rect(ctr, 29.dp.toPx())) }) {
            drawRect(Brush.radialGradient(0f to Color(0xFF2A1F45), 0.7f to Color(0xFF0B0814), 1f to Color(0xFF0B0814), center = cc, radius = 58.dp.toPx() * 0.72f))
        }
        // las barras
        val hs = floatArrayOf(8f, 16f, 22f, 14f, 8f)
        val dl = floatArrayOf(0f, 0.2f, 0.35f, 0.1f, 0.27f)
        val w = 4.dp.toPx(); val g = 3.dp.toPx()
        var x = ctr.x - (5 * w + 4 * g) / 2
        for (i in 0 until 5) {
            val hh = if (bars < 0f) hs[i] else { val t = ((bars + dl[i] / 0.5f) % 1f); 5f + 19f * (0.5f - 0.5f * cos(t * 2f * PI.toFloat())) }
            val hp = hh.dp.toPx()
            drawRoundRect(if (bars < 0f) p.fg else p.accent, Offset(x, ctr.y - hp / 2), Size(w, hp), CornerRadius(2.dp.toPx() * sk.round))
            x += w + g
        }
    }
}

@Composable
private fun DjPanel(sk: Skin, lite: Boolean) {
    val p = sk.pal
    val dj = UiState.hDj
    val bub = UiState.hBub
    val slot = UiState.hSlot
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().glowPanel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // quién es
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(sk, dj.on("mine"), dj.on("talk"), lite)
                Column {
                    Eyebrow(dj.str("role"), sk, maxLines = 1)
                    T(dj.str("name"), 24f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1f, ls = -0.03f, maxLines = 1)
                }
            }
            HFlow(8.dp, 8.dp, Modifier.fillMaxWidth()) {
                Toggle("Voz", dj.on("voice"), sk) { tap("#tVoice") }
                Toggle("Presenta cada canción", dj.on("intros"), sk) { tap("#tIntros") }
                Toggle("Conversación", false, sk, icon = H_CHAT) { focus.clearFocus(); tap("#openChat") }
            }
        }
        // lo que dice (tocarlo abre la conversación)
        val r4 = (4f * sk.round).dp; val r18 = (18f * sk.round).dp
        val bs = RoundedCornerShape(r4, r18, r18, r18)
        val bfs = 17.28f * sk.tk
        Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { focus.clearFocus(); tap("#djBubble") }.clip(bs).background(Color.Black.copy(alpha = 0.38f)).border(sk.b, p.line2, bs)
            .padding(horizontal = 18.dp + sk.b, vertical = 16.dp + sk.b)) {
            if (bub.str("who").isNotEmpty()) {
                T(bub.str("who").uppercase(), 10.88f * sk.tk, p.accent, family = Mono, ls = 0.14f, lh = 1.4f, maxLines = 1)
                Spacer(Modifier.height(4.dp))
            }
            val caret = bub.on("c")
            val blink = if (caret && !lite) rememberInfiniteTransition(label = "cur").animateFloat(1f, 0.35f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "c") else null
            val txt = buildAnnotatedString { append(bub.str("t")); if (caret) appendInlineContent("c", "▌") }
            val inline = mapOf("c" to InlineTextContent(Placeholder(0.58.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize().padding(start = 2.dp).graphicsLayer { alpha = if ((blink?.value ?: 1f) > 0.67f) 1f else 0.35f }.background(p.accent))
            })
            // (como mínimo ocupa lo de dos renglones largos, como en la web)
            Box(Modifier.layout { m, c ->
                val pl = m.measure(c)
                val minH = maxOf(0, (bfs * 3.2f * density).roundToInt() - ((32.dp + sk.b * 2).roundToPx()) - (if (bub.str("who").isNotEmpty()) ((10.88f * sk.tk * 1.4f + 4f) * density).roundToInt() else 0))
                val h = maxOf(pl.height, minH)
                layout(pl.width, h) { pl.place(0, 0) }
            }) {
                BasicText(txt, Modifier.lines(bfs * 1.4f), style = style(bfs, p.fg, weight = FontWeight.SemiBold, lh = 1.4f), inlineContent = inline)
            }
        }
        // la ruleta
        if (slot != null && !slot.on("off")) {
            val landed = slot.on("l")
            val ss = sk.r(16f)
            var m = Modifier.fillMaxWidth()
            m = if (landed) m.glow(p.glow, (16f * sk.round).dp, 30.dp, 0.dp, (-10).dp).border(sk.b, p.accent, ss) else m.dashed(p.line2, (16f * sk.round).dp, sk.b)
            Column(m.padding(18.dp + sk.b), horizontalAlignment = Alignment.CenterHorizontally) {
                T(slot.str("t"), 20.8f * sk.tk, if (landed) p.accent else p.muted, family = sk.display, weight = sk.displayWeight, lh = 1.05f, ls = -0.03f, maxLines = 1, align = TextAlign.Center)
                if (slot.str("s").isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    T(slot.str("s"), 15.2f * sk.tk, p.muted, weight = FontWeight.Bold, lh = 1.05f, maxLines = 1, align = TextAlign.Center)
                }
            }
        }
        // pedirle algo
        AskBox(dj, sk)
        val chips = dj.arr("chips")
        if (chips.isNotEmpty()) HFlow(8.dp, 8.dp, Modifier.fillMaxWidth()) { for (ch in chips) Idea(ch, sk) }
        val ideas = dj.arr("ideas")
        if (ideas.isNotEmpty()) HFlow(8.dp, 8.dp, Modifier.fillMaxWidth()) { for (ch in ideas) Idea(ch, sk) }
        // ruleta, radio, todas
        HFlow(10.dp, 10.dp, Modifier.fillMaxWidth()) {
            val rs = sk.r(16f)
            val rdis = dj.on("roul")
            Row(Modifier.glow(p.glow, (16f * sk.round).dp, 36.dp, 12.dp, (-10).dp).press(enabled = !rdis) { focus.clearFocus(); tap("#btnRoulette") }.clip(rs)
                .drawBehind {
                    val ctr = Offset(size.width / 2, size.height / 2)
                    rotate(120f, ctr) { drawRect(Brush.sweepGradient(listOf(p.accent, p.accent2, hsl(UiState.look.h + 150f, UiState.look.s * 0.85f, 0.58f), p.accent), ctr), Offset(ctr.x - size.width, ctr.y - size.width), Size(size.width * 2, size.width * 2)) }
                }.graphicsLayer { alpha = if (rdis) 0.5f else 1f }.padding(horizontal = 22.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(H_ROUL, 20f, p.onAccent, sw = 2.2f, round = false)
                T("Girar la ruleta", 16.32f * sk.tk, p.onAccent, family = sk.display, weight = sk.displayWeight, ls = -0.01f, maxLines = 1)
            }
            HBtn("Modo radio", sk, kind = 2, on = dj.on("radio"), icon = H_RADIO, iconSize = 20f) { tap("#btnRadio") }
            if (dj.on("allOn")) HBtn("Todas mis canciones", sk, kind = 2, on = dj.on("all"), icon = H_ALL, iconSize = 20f) { tap("#btnAll") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val good = dj.on("aiOn")
                Box(Modifier.size(8.dp).glow(if (good) p.good else Color.Transparent, 4.dp, 8.dp, 0.dp, 0.dp).background(if (good) p.good else p.faint, CircleShape))
                T(dj.str("ai"), 13.12f * sk.tk, p.muted)
            }
        }
        // quién habla, cómo se pasa de una canción a otra, qué voz
        HFlow(16.dp, 10.dp, Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Habla", 12.8f * sk.tk, p.muted, weight = FontWeight.Bold, maxLines = 1)
                Select("#whoSel", dj.obj("who"), sk)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Entre canciones", 12.8f * sk.tk, p.muted, weight = FontWeight.Bold, maxLines = 1)
                Select("#mixSel", dj.obj("mix"), sk)
            }
            if (dj.on("vbtnOn")) HBtn(dj.str("vbtn"), sk, kind = 1) { tap("#voicePickBtn") }
        }
    }
}

/** Escribirle a Nova: la caja, el micrófono (si el móvil tiene) y «Pedir». */
@Composable
private fun AskBox(dj: JSONObject?, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(14f)
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submit() {
        val v = UiState.homeDraft.trim()
        if (v.isEmpty()) return
        focus.clearFocus()
        UiState.send("hAsk") { it.put("text", v) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var box = Modifier.fillMaxWidth()
        if (focused) box = box.drawBehind {
            val w = 3.dp.toPx()
            drawRoundRect(p.wash, Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w), CornerRadius((14f * sk.round).dp.toPx() + w / 2), style = Stroke(w))
        }
        Box(box.clip(shape).background(Color.Black.copy(alpha = 0.4f)).border(sk.b, if (focused) p.accent else p.line2, shape).padding(horizontal = 16.dp + sk.b, vertical = 13.dp + sk.b)) {
            BasicTextField(
                value = UiState.homeDraft, onValueChange = { UiState.homeDraft = it.take(200) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pídele algo a la DJ" }.onFocusChanged {
                    if (it.isFocused && !focused) UiState.send("hFocus")
                    focused = it.isFocused
                },
                singleLine = true, textStyle = style(15.68f * sk.tk, p.fg), cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (UiState.homeDraft.isEmpty()) T(dj.str("ph"), 15.68f * sk.tk, p.faint, maxLines = 1)
                        inner()
                    }
                }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (dj.on("mic")) {
                Box(Modifier.semantics { contentDescription = "Háblale a la DJ" }.press { focus.clearFocus(); tap("#djMic") }.clip(shape).background(Color.White.copy(alpha = 0.04f)).border(sk.b, p.line2, shape)
                    .padding(horizontal = 18.dp + sk.b, vertical = 12.dp + sk.b)) {
                    Box(Modifier.height((21.75f * sk.tk).dp), contentAlignment = Alignment.Center) { Ico(H_MIC, 18f, p.fg) }
                }
            }
            HBtn("Pedir", sk, Modifier.weight(1f), primary = true, icon = H_SPARK, iconFill = true) { submit() }
        }
    }
}

// ---------------------------------------------------------------- «Para ti hoy»
@Composable
private fun ForYou(fy: JSONObject, sk: Skin) {
    Column(Modifier.fillMaxWidth().panel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeadRow("Para ti hoy", fy.str("date"), sk)
        for (card in fy.arr("cards")) FyCard(card, sk)
    }
}

@Composable
private fun FyCard(card: JSONObject, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(16f)
    val daily = card.on("daily")
    var m = Modifier.fillMaxWidth().clip(shape)
    m = if (daily) m.drawBehind { drawRect(cssLinear(110f, size, 0f to p.wash, 0.6f to Color.Black.copy(alpha = 0.3f), 1f to Color.Black.copy(alpha = 0.3f))) }.border(sk.b, p.accent.copy(alpha = 0.35f), shape)
    else m.background(Color.Black.copy(alpha = 0.28f)).border(sk.b, p.line, shape)
    val grid = card.arr("grid")
    val xl = card.obj("xl")
    if (daily && grid.isNotEmpty()) {
        Column(m.padding(14.dp + sk.b), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val gs = sk.r(14f)
            Layout(content = { for (c in grid.take(4)) HCover(c, sk, Modifier, 16.8f) }, modifier = Modifier.glow(p.glow, (14f * sk.round).dp, 40.dp, 18.dp, (-18).dp).clip(gs)) { ms, c ->
                // cuatro portadas en fila, hasta 220 de ancho, con 4 de separación
                val w = minOf(c.maxWidth, 220.dp.roundToPx()); val g = 4.dp.roundToPx()
                val each = (w - 3 * g) / 4
                val ps = ms.map { it.measure(Constraints.fixed(each, each)) }
                layout(each * 4 + g * 3, each) { ps.forEachIndexed { i, pl -> pl.place(i * (each + g), 0) } }
            }
            FyBody(card, sk, Modifier.fillMaxWidth())
        }
    } else {
        Row(m.padding(14.dp + sk.b), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            if (xl != null) HCover(xl, sk, Modifier.size(72.dp).clip(sk.r(14f)), 25.6f)
            FyBody(card, sk, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FyBody(card: JSONObject, sk: Skin, modifier: Modifier) {
    val p = sk.pal
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (el in card.arr("body")) {
            when (el.str("e")) {
                "eye" -> Eyebrow(el.str("t"), sk)
                "h3" -> T(el.str("t"), 16.8f * sk.tk, p.fg, Modifier.padding(top = 2.dp), family = sk.display, weight = sk.displayWeight, lh = 1.15f, ls = -0.02f)
                "sub" -> T(el.str("t"), 13.44f * sk.tk, p.muted, Modifier.padding(top = 2.dp), maxLines = 1)
                "btns" -> HFlow(8.dp, 8.dp, Modifier.fillMaxWidth().padding(top = 10.dp)) { for (b in el.arr("b")) JBtn(b, sk, 1) }
                "mini" -> {
                    val rows = el.arr("rows")
                    // (cada fila: 4 + la portada o los dos renglones con sus 2 de separación + 4)
                    val rowH = 8f + maxOf(38f, 2f + (15f + 13.44f) * 1.45f * sk.tk)
                    ExactColumn(4f, rows.map { rowH }, Modifier.fillMaxWidth().padding(top = 6.dp)) { for (r in rows) MiniRow(r, sk) }
                }
                else -> T(el.str("t"), 13.44f * sk.tk, p.muted, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Una canción de una lista corta (y, en las recomendaciones, sus tres botones: me gusta, no es para mí, YouTube). */
@Composable
private fun MiniRow(r: JSONObject, sk: Skin) {
    val p = sk.pal
    val acts = r.arr("acts")
    val hot = r.on("play") || r.on("liked")
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f).press(0.98f) { tap(r) }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HCover(r.obj("c"), sk, Modifier.size(38.dp).clip(sk.r(8f)), 16.8f)
            Column(Modifier.weight(1f, fill = false)) {
                T(r.str("t"), 15f * sk.tk, if (hot) p.accent else p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
                T(r.str("s"), 13.44f * sk.tk, p.muted, Modifier.padding(top = 2.dp), maxLines = 1)
            }
        }
        if (acts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (a in acts) {
                val on = a.on("on")
                val col = if (on) p.accent else p.muted
                Box(Modifier.size(36.dp).semantics { contentDescription = a.str("l") }.press(0.85f) { tap(a) }.background(if (on) Color(0x802E2250) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    if (a.str("t") == "yt") { Ico(H_YT_BOX, 18f, col, sw = 1.8f); Ico(H_YT_TRI, 18f, col, fill = true) }
                    else T(a.str("t"), 16f * sk.tk, col, lh = 1f, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- «Mezcla a medida» y la mezcla
@Composable
private fun Mixer(mx: JSONObject, sk: Skin) {
    val p = sk.pal
    Column(Modifier.fillMaxWidth().panel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeadRow("Mezcla a medida", mx.str("moment"), sk)
        HFlow(9.dp, 9.dp, Modifier.fillMaxWidth()) {
            for (ch in mx.arr("moods")) {
                val on = ch.on("on")
                val shape = RoundedCornerShape(999.dp)
                var m: Modifier = Modifier
                if (on) m = m.glow(p.glow, 20.dp, 22.dp, 6.dp, (-8).dp)
                m = m.press { tap(ch) }.clip(shape)
                m = if (on) m.background(p.grad) else m.background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, shape)
                Box(m.padding(horizontal = 15.dp + sk.b, vertical = 9.dp + sk.b)) { T(ch.str("t"), 14.08f * sk.tk, if (on) p.onAccent else p.muted, weight = FontWeight.ExtraBold, maxLines = 1) }
            }
        }
        HFlow(12.dp, 12.dp, Modifier.fillMaxWidth(), grow = 2) {
            T("Duración", 13.6f * sk.tk, p.muted, weight = FontWeight.SemiBold, maxLines = 1)
            SegRow(mx.arr("lens"), sk)
            Spacer(Modifier)
            HBtn("Sorpréndeme", sk, icon = H_DICE, iconSw = 2f, dots = H_DOTS) { tap("#btnSurprise") }
            HBtn("Crear mezcla", sk, primary = true) { tap("#btnMix") }
        }
    }
}

@Composable
private fun MixOut(out: JSONObject?, sk: Skin) {
    val p = sk.pal
    when (out.str("e")) {
        "mix" -> MixPanel(out!!, sk)
        "think" -> {
            Row(Modifier.fillMaxWidth().panel(sk).padding(18.dp + sk.b), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val ph = rememberInfiniteTransition(label = "eq").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "e").value
                Canvas(Modifier.size(41.dp, 28.dp)) {
                    val dl = floatArrayOf(0f, 0.3f, 0.6f, 0.15f, 0.45f)
                    for (i in 0 until 5) {
                        val t = (ph + dl[i]) % 1f
                        val h = (6f + 22f * (0.5f - 0.5f * cos(t * 2f * PI.toFloat()))).dp.toPx()
                        drawRoundRect(p.grad, Offset(i * 9.dp.toPx(), size.height - h), Size(5.dp.toPx(), h), CornerRadius(3.dp.toPx() * sk.round))
                    }
                }
                Column(Modifier.weight(1f)) {
                    T(out.str("b"), 15f * sk.tk, p.fg, weight = FontWeight.Bold)
                    T(out.str("t"), 13.44f * sk.tk, p.muted)
                }
                val stop = out.obj("stop")
                if (stop != null) JBtn(stop, sk, 1)
            }
        }
        "empty" -> {
            val r = (20f * sk.round).dp
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(r)).dashed(p.line2, r, sk.b).padding(26.dp + sk.b), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T(out.str("h"), 18.4f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
                T(out.str("t"), 15f * sk.tk, p.muted)
            }
        }
        else -> if (out.str("t").isNotEmpty()) T(out.str("t"), 15f * sk.tk, p.muted)
    }
}

/** La mezcla hecha: su nombre, sus botones y sus canciones (tocar una empieza por ella). */
@Composable
private fun MixPanel(mx: JSONObject, sk: Skin) {
    val p = sk.pal
    Column(Modifier.fillMaxWidth().glowPanel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wpx = constraints.maxWidth.toFloat()
                Column {
                    Eyebrow(mx.str("eye"), sk)
                    Spacer(Modifier.height(8.dp))
                    T(mx.str("name"), 25.6f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1f, ls = -0.04f,
                        brush = Brush.horizontalGradient(0f to Color.White, 0.7f to p.accent, 1f to p.accent2, startX = 0f, endX = wpx))
                    for (ps in mx.arr("ps")) {
                        if (ps.on("n")) T(ps.str("t"), 13.44f * sk.tk, p.muted) else { Spacer(Modifier.height(6.dp)); T(ps.str("t"), 15f * sk.tk, p.muted) }
                    }
                }
            }
            HFlow(8.dp, 8.dp, Modifier.fillMaxWidth()) { for (b in mx.arr("acts")) JBtn(b, sk, 0, iconSize = 22f) }
        }
        val songs = mx.arr("items")
        // (cada fila: 8 + la portada o sus renglones + 8, más el borde)
        ExactColumn(6f, songs.map { 16f + 2f * sk.b.value + maxOf(46f, (15f + 13.44f) * 1.45f * sk.tk + (if (it.str("w").isNotEmpty()) 2f + 12.8f * 1.45f * sk.tk else 0f)) }, Modifier.fillMaxWidth()) { for (song in songs) MixItem(song, sk) }
        val disc = mx.obj("disc")
        if (disc != null) {
            val r = (16f * sk.round).dp
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(r)).dashed(p.line2, r, sk.b).padding(horizontal = 18.dp + sk.b, vertical = 16.dp + sk.b), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow(disc.str("eye"), sk)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in disc.arr("rows")) HFlow(8.dp, 8.dp, Modifier.fillMaxWidth(), grow = 0, basis = 240.dp) {
                        Column {
                            BasicText(buildAnnotatedString { withStyle(SpanStyle(fontFamily = sk.display, fontWeight = sk.displayWeight)) { append(row.str("b")) }; append(" " + row.str("r")) }, Modifier.lines(15f * sk.tk * 1.45f), style = style(15f * sk.tk, p.fg))
                            if (row.str("w").isNotEmpty()) T(row.str("w"), 13.12f * sk.tk, p.muted)
                        }
                        for (l in row.arr("links")) {
                            val ls = sk.r(9f)
                            Box(Modifier.press { tap(l) }.border(sk.b, p.line2, ls).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) { T(l.str("t"), 12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1) }
                        }
                    }
                }
            }
        }
        if (mx.str("note").isNotEmpty()) T(mx.str("note"), 13.44f * sk.tk, p.muted)
    }
}

@Composable
private fun MixItem(o: JSONObject, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(14f)
    val play = o.on("play")
    var m = Modifier.fillMaxSize().clip(shape)
    if (play) m = m.background(Brush.horizontalGradient(listOf(p.wash, Color.Transparent))).border(sk.b, p.accent.copy(alpha = 0.3f), shape)
    Row(m.press(0.98f) { tap(o) }.padding(start = 6.dp + sk.b, end = 12.dp + sk.b), verticalAlignment = Alignment.CenterVertically) {
        // el número: solo el contorno (o relleno con el color del momento si es la que suena)
        val sw = with(LocalDensity.current) { 1.dp.toPx() }
        val ns = style(19.2f * sk.tk, if (play) p.accent else p.faint, sk.display, sk.displayWeight, 1f, 0f, TextAlign.End)
        BasicText(o.str("n"), Modifier.width(30.dp), style = if (play) ns else ns.copy(drawStyle = Stroke(width = sw)), maxLines = 1, softWrap = false)
        Spacer(Modifier.width(10.dp))
        HCover(o.obj("c"), sk, Modifier.size(46.dp).clip(sk.r(10f)), 16.8f)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            T(o.str("t"), 15f * sk.tk, if (play) p.accent else p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
            T(o.str("s"), 13.44f * sk.tk, p.muted, maxLines = 1)
            if (o.str("w").isNotEmpty()) T(o.str("w"), 12.8f * sk.tk, p.accent, Modifier.padding(top = 2.dp), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        T(o.str("d"), 13.44f * sk.tk, p.muted, family = Mono, maxLines = 1)
    }
}
