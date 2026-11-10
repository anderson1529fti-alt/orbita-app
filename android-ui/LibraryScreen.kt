package __PACKAGE__

import android.graphics.BlurMaskFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import com.getcapacitor.JSArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------------------------------------------------------- el aspecto de Órbita (los mismos valores que su CSS)
private val Body = FontFamily(
    Font(R.font.figtree_400, FontWeight.Normal), Font(R.font.figtree_500, FontWeight.Medium), Font(R.font.figtree_600, FontWeight.SemiBold),
    Font(R.font.figtree_700, FontWeight.Bold), Font(R.font.figtree_800, FontWeight.ExtraBold)
)
private val Display = FontFamily(Font(R.font.unbounded_500, FontWeight.Medium), Font(R.font.unbounded_700, FontWeight.Bold), Font(R.font.unbounded_900, FontWeight.Black))
private val Mono = FontFamily(Font(R.font.plexmono_400, FontWeight.Normal), Font(R.font.plexmono_500, FontWeight.Medium), Font(R.font.plexmono_600, FontWeight.SemiBold))

private fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color = Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** Los colores de la app: los fijos y los que salen del color del momento (o de la portada). */
internal class Pal(h: Float, s: Float, glowK: Float, black: Boolean) {
    val fg = Color(0xFFF7F2FF)
    val muted = Color(0xFFACA2CB)
    val faint = Color(0xFF71688F)
    val line = Color.White.copy(alpha = 0.08f)
    val line2 = Color.White.copy(alpha = 0.17f)
    val bg = if (black) Color.Black else Color(0xFF06050B)
    val panelSolid = if (black) Color(0xFF0B0911) else Color(0xFF130E22)
    val onAccent = Color(0xFF14081C)
    val bad = Color(0xFFFF6B81)
    val good = Color(0xFF5EE6A8)
    val accent = hsl(h, s * 0.96f, 0.64f)
    val accent2 = hsl(h - 45f, s * 0.92f, 0.66f)
    val wash = hsl(h, s * 0.96f, 0.60f, 0.18f)
    val glow = hsl(h, s * 0.96f, 0.60f, 0.42f * glowK)
    /** El degradado de los botones principales (100 grados, casi horizontal). */
    val grad: Brush get() = Brush.horizontalGradient(listOf(accent, accent2))
}

internal class Skin(val pal: Pal, val round: Float, val tk: Float, val titles: String, val b: Dp) {
    // b: el grosor de un borde de «1 px» de la web (el navegador lo dibuja con píxeles enteros del móvil)
    val display: FontFamily get() = when (titles) { "limpia" -> Body; "retro" -> Mono; else -> Display }
    /** El peso «muy negro» de los títulos, según la letra elegida (como hace el navegador con lo que hay). */
    val displayWeight: FontWeight get() = when (titles) { "limpia" -> FontWeight.ExtraBold; "retro" -> FontWeight.SemiBold; else -> FontWeight.Black }
    fun r(px: Float): RoundedCornerShape = RoundedCornerShape((px * round).dp)
}

/** Texto con las medidas de la web: tamaño en «px» de CSS (no cambia con el tamaño de letra del móvil), interlineado y espaciado. */
@Composable
private fun T(
    text: String, size: Float, color: Color, modifier: Modifier = Modifier, family: FontFamily = Body, weight: FontWeight = FontWeight.Normal,
    lh: Float = 1.45f, ls: Float = 0f, maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start, brush: Brush? = null
) {
    BasicText(text, modifier.lines(size * lh), style = style(size, color, family, weight, lh, ls, align, brush), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * El alto que ocupa el texto en la web: cada renglón mide justo lo que dice su CSS (con decimales). Android redondea
 * cada renglón hacia arriba a píxeles enteros, y en una pantalla larga eso se va sumando.
 */
private fun Modifier.lines(lineDp: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val exact = lineDp * density
    val each = maxOf(1f, ceil(exact - 0.01f))
    val n = maxOf(1, (p.height / each).roundToInt())
    val hgt = maxOf(1, (n * exact).roundToInt())
    layout(p.width, hgt) { p.place(0, (hgt - p.height) / 2) }
}

@Composable
private fun style(size: Float, color: Color, family: FontFamily = Body, weight: FontWeight = FontWeight.Normal, lh: Float = 1.45f, ls: Float = 0f, align: TextAlign = TextAlign.Start, brush: Brush? = null): TextStyle {
    val d = LocalDensity.current
    val base = TextStyle(
        fontSize = with(d) { size.dp.toSp() }, fontFamily = family, fontWeight = weight,
        lineHeight = with(d) { (size * lh).dp.toSp() }, letterSpacing = if (ls == 0f) TextUnit.Unspecified else ls.em, textAlign = align,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        // las letras se colocan con fracciones de píxel, como hace el navegador (si no, los textos salen algo más estrechos o anchos)
        textMotion = TextMotion.Animated
    )
    return if (brush != null) base.copy(brush = brush) else base.copy(color = color)
}

/** Un icono de la app: el mismo dibujo (trazado SVG de 24 × 24) que usa la web. */
@Composable
private fun Ico(path: String, size: Float, color: Color, modifier: Modifier = Modifier, fill: Boolean = false, sw: Float = 2f, round: Boolean = true) {
    val p = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size.dp)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            if (fill) drawPath(p, color)
            else drawPath(p, color, style = Stroke(width = sw, cap = if (round) StrokeCap.Round else StrokeCap.Butt, join = StrokeJoin.Round))
        }
    }
}

private const val I_HEART = "M12 20s-7.5-4.6-9.2-9.3C1.7 7.5 3.8 4.5 7 4.5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.2 0 5.3 3 4.2 6.2C19.5 15.4 12 20 12 20z"
private const val I_EDIT = "M4 20h4L19 9l-4-4L4 16z"
private const val I_TRASH = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"
private const val I_PLUS = "M12 5v14M5 12h14"
private const val I_SEARCH = "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0M20 20l-4-4"
private const val I_DOWN = "M6 9l6 6 6-6"

/** El brillo de color de debajo de los botones principales (la sombra de CSS: desplazada, difuminada y encogida). */
private fun Modifier.glow(color: Color, radius: Dp, blur: Dp, dy: Dp, spread: Dp): Modifier = drawBehind {
    if (color.alpha <= 0.01f) return@drawBehind
    // como una sombra de CSS: solo se ve por fuera de la caja (aunque la caja sea medio transparente)
    val own = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radius.toPx()))) }
    clipPath(own, ClipOp.Difference) {
        drawIntoCanvas { c ->
            val p = Paint()
            val fp = p.asFrameworkPaint()
            fp.isAntiAlias = true
            fp.color = color.toArgb()
            fp.maskFilter = BlurMaskFilter(maxOf(1f, blur.toPx() * 0.5f), BlurMaskFilter.Blur.NORMAL)
            val s = spread.toPx()
            val r = maxOf(0f, radius.toPx() + s)
            c.drawRoundRect(-s, dy.toPx() - s, size.width + s, size.height + dy.toPx() + s, r, r, p)
        }
    }
}

/** Al tocar, los botones se encogen un poco (como en la web). */
@Composable
private fun Modifier.press(scale: Float = 0.94f, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (down && enabled) scale else 1f, tween(120), label = "p")
    return this.graphicsLayer { scaleX = k; scaleY = k }.clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

@Composable
private fun Btn(text: String, sk: Skin, modifier: Modifier = Modifier, primary: Boolean = false, small: Boolean = false, enabled: Boolean = true, icon: String? = null, onClick: () -> Unit) {
    val p = sk.pal
    val shape = sk.r(if (small) 11f else 14f)
    var m = modifier
    if (primary) m = m.glow(p.glow, ((if (small) 11f else 14f) * sk.round).dp, 30.dp, 10.dp, (-10).dp)
    m = m.press(enabled = enabled, onClick = onClick).clip(shape)
    m = if (primary) m.background(p.grad) else m.background(Color.White.copy(alpha = 0.04f)).border(sk.b, p.line2, shape)
    Row(m.graphicsLayer { alpha = if (enabled) 1f else 0.5f }.padding(horizontal = (if (small) 13.dp else 18.dp) + sk.b, vertical = (if (small) 8.dp else 12.dp) + sk.b),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Ico(icon, 18f, if (primary) p.onAccent else p.fg, sw = 2.4f)
        T(text, (if (small) 13.6f else 15f) * sk.tk, if (primary) p.onAccent else p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- portadas
private val needQueue = ArrayList<String>()
private val needHandler = Handler(Looper.getMainLooper())
private val needFlush = Runnable {
    if (needQueue.isEmpty()) return@Runnable
    val a = JSArray()
    for (id in needQueue) a.put(id)
    needQueue.clear()
    UiState.send("needCovers") { it.put("ids", a) }
}

/** La portada de una canción la guarda la app: se pide una vez, junto con las de las filas vecinas. */
private fun needCover(id: String) {
    if (!UiState.asked.add(id)) return
    needQueue.add(id)
    needHandler.removeCallbacks(needFlush)
    needHandler.postDelayed(needFlush, 40)
}

@Composable
private fun Cover(t: UiTrack, sk: Skin) {
    val shape = sk.r(10f)
    val bmp = if (t.cover) UiState.covers[t.id] else null
    if (t.cover && bmp == null) LaunchedEffect(t.id) { needCover(t.id) }
    Box(Modifier.size(46.dp).clip(shape), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            // sin portada: la inicial sobre un degradado con el color de la canción
            val h = t.hue
            Box(Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(hsl(h, 0.75f, 0.50f), hsl(h + 60f, 0.70f, 0.26f))))
                .drawBehind {
                    val c = Offset(size.width * 0.30f, size.height * 0.25f)
                    drawRect(Brush.radialGradient(0f to hsl(h + 40f, 0.90f, 0.70f, 0.9f), 0.55f to Color.Transparent, center = c, radius = size.width * 1.03f))
                }, contentAlignment = Alignment.Center) {
                T((t.title.trim().take(1).ifEmpty { "♪" }).uppercase(), 16.8f * sk.tk, Color.White, family = sk.display, weight = sk.displayWeight, lh = 1f)
            }
        }
    }
}

// ---------------------------------------------------------------- una fila
@Composable
private fun TrackRow(t: UiTrack, playing: Boolean, sk: Skin, onPlay: () -> Unit) {
    val p = sk.pal
    val shape = sk.r(12f)
    var armed by remember(t.id) { mutableStateOf(false) }
    if (armed) LaunchedEffect(t.id) { delay(3000); armed = false }
    var m = Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(shape)
    if (playing) m = m.background(Brush.horizontalGradient(0f to p.wash, 0.7f to Color.Transparent)).border(sk.b, p.accent.copy(alpha = 0.25f), shape)
    Row(m.padding(horizontal = 6.dp + sk.b, vertical = 5.dp + sk.b), verticalAlignment = Alignment.CenterVertically) {
        // la canción (tocar = ponerla)
        Row(Modifier.weight(1f).press(0.98f, onClick = onPlay).padding(horizontal = 2.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t, sk)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t.title, 15f * sk.tk, if (playing) p.accent else p.fg, Modifier.weight(1f, fill = false), weight = FontWeight.ExtraBold, maxLines = 1)
                    if (t.demo) Pill("Demo", p.muted, p.line2, sk)
                    if (t.mine) Pill("Mi voz", p.good, p.good, sk)
                }
                T(t.sub, 13.44f * sk.tk, p.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(10.dp))
        T(fmt(t.dur), 12.8f * sk.tk, p.muted, Modifier.width(46.dp), family = Mono, align = TextAlign.End, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(34.dp).semantics { contentDescription = if (t.liked) "Quitar de favoritas" else "Favorita" }.press(0.85f) { UiState.send("like") { it.put("id", t.id) } }, contentAlignment = Alignment.Center) {
            Ico(I_HEART, 18f, if (t.liked) p.accent else p.muted, fill = t.liked, round = false)
        }
        Spacer(Modifier.width(10.dp))
        if (t.demo) Spacer(Modifier.width(40.dp))
        else if (armed) {
            // segundo toque para confirmar (como en la app)
            Box(Modifier.height(34.dp).press { armed = false; UiState.send("del") { it.put("id", t.id) } }.clip(sk.r(10f)).border(sk.b, p.bad, sk.r(10f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                T("¿Borrar?", 12.8f * sk.tk, p.bad, weight = FontWeight.ExtraBold, maxLines = 1)
            }
        } else Row(Modifier.width(40.dp)) {
            Box(Modifier.width(20.dp).height(34.dp).semantics { contentDescription = "Editar canción y portada" }.press(0.85f) { UiState.send("edit") { it.put("id", t.id) } }, contentAlignment = Alignment.Center) { Ico(I_EDIT, 16f, p.muted) }
            Box(Modifier.width(20.dp).height(34.dp).semantics { contentDescription = if (t.dev) "Quitar de Órbita (no borra el archivo)" else "Borrar" }.press(0.85f) { armed = true }, contentAlignment = Alignment.Center) { Ico(I_TRASH, 16f, p.muted) }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color, border: Color, sk: Skin) {
    Box(Modifier.padding(start = 7.dp).border(sk.b, border, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp + sk.b, vertical = 2.dp + sk.b)) {
        T(text.uppercase(), 9.92f * sk.tk, color, family = Mono, weight = FontWeight.SemiBold, ls = 0.1f, lh = 1.3f, maxLines = 1)
    }
}

private fun fmt(s: Double): String {
    if (s.isNaN() || s.isInfinite() || s <= 0) return "0:00"
    val r = Math.round(s)
    return (r / 60).toString() + ":" + (r % 60).toString().padStart(2, '0')
}

// ---------------------------------------------------------------- la pantalla
private val SORTS = listOf("recent" to "Recientes", "title" to "Título", "artist" to "Artista", "plays" to "Más escuchadas")

@Composable
fun LibraryScreen() {
    val look = UiState.look
    val h by animateFloatAsState(look.h, tween(1100), label = "h")
    val s by animateFloatAsState(look.s, tween(1100), label = "s")
    val pal = remember(h, s, look.glowK, look.black) { Pal(h, s, look.glowK, look.black) }
    val dens = LocalDensity.current.density
    val sk = remember(pal, look.round, look.tk, look.titles, dens) { Skin(pal, look.round, look.tk, look.titles, (maxOf(1f, kotlin.math.floor(dens)) / dens).dp) }
    val all = UiState.tracks
    val filter = UiState.filter
    val sort = UiState.sort
    val search = UiState.search
    val rows = remember(all, filter, sort, search) { UiState.filtered(all, filter, sort, search) }
    val now = UiState.nowId
    val focus = LocalFocusManager.current
    val listState = UiState.list
    val toolsIndex = 1 + (if (UiState.warn.isNotEmpty()) 1 else 0) + (if (UiState.dev != null) 1 else 0) + (if (UiState.drop.isNotEmpty()) 1 else 0)

    // al buscar, la caja sube arriba del todo para que se vean los resultados con el teclado abierto (se repite cuando
    // el teclado termina de abrirse y la pantalla encoge)
    var searching by remember { mutableStateOf(false) }
    val toolsUp = with(LocalDensity.current) { 18.dp.roundToPx() }
    LaunchedEffect(searching, toolsIndex) {
        if (!searching) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collectLatest {
            delay(140)
            listState.animateScrollToItem(toolsIndex, toolsUp)
        }
    }

    fun play(index: Int) {
        focus.clearFocus()
        val ids = JSArray()
        for (t in rows) ids.put(t.id)
        UiState.send("play") { it.put("ids", ids); it.put("index", index) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 48.dp)) {
            // ---- título, cuántas hay y «Añadir canciones»
            item(key = "#head") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // (la caja mide lo que en la web: el interlineado de 0,95 es menor que la letra)
                    Box(Modifier.height((33.6f * 0.95f * sk.tk).dp), contentAlignment = Alignment.CenterStart) {
                        T("BIBLIOTECA", 33.6f * sk.tk, pal.fg, Modifier.wrapContentHeight(unbounded = true), family = sk.display, weight = sk.displayWeight, lh = 1.2f, ls = -0.045f, maxLines = 1,
                            brush = Brush.horizontalGradient(0.2f to Color.White, 0.8f to pal.accent))
                    }
                    T(UiState.meta, 14.4f * sk.tk, pal.muted, family = Mono, maxLines = 1)
                    Btn("Añadir canciones", sk, primary = true, icon = I_PLUS) { UiState.send("add") }
                }
                Spacer(Modifier.height(26.dp))
            }
            // ---- aviso de canciones con poca calidad
            if (UiState.warn.isNotEmpty()) item(key = "#warn") {
                val shape = sk.r(14f)
                Box(Modifier.fillMaxWidth().clip(shape).background(hsl(350f, 1f, 0.60f, 0.08f)).border(sk.b, hsl(350f, 1f, 0.70f, 0.35f), shape).padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b)) {
                    BasicText(buildAnnotatedString {
                        withStyle(SpanStyle(color = pal.bad, fontWeight = FontWeight.Bold)) { append(UiState.warn) }
                        append(" " + UiState.warnText)
                    }, Modifier.lines(14.08f * sk.tk * 1.45f), style = style(14.08f * sk.tk, pal.fg))
                }
                Spacer(Modifier.height(26.dp))
            }
            // ---- la música del móvil
            UiState.dev?.let { b ->
                item(key = "#dev") {
                    val shape = sk.r(16f)
                    Row(Modifier.fillMaxWidth().glow(pal.glow, (16f * sk.round).dp, 30.dp, 0.dp, (-12).dp).clip(shape)
                        .background(Brush.horizontalGradient(listOf(pal.wash, Color.Black.copy(alpha = 0.35f)))).border(sk.b, pal.accent, shape).padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(b.title, 15f * sk.tk, pal.fg, family = sk.display, weight = sk.displayWeight, ls = -0.01f)
                            T(b.text, 13.12f * sk.tk, pal.muted)
                        }
                        Btn(b.button, sk, small = true, primary = b.primary, enabled = b.enabled) { UiState.send("dev") { it.put("act", b.act) } }
                    }
                    Spacer(Modifier.height(26.dp))
                }
            }
            // ---- cómo añadir música (bibliotecas pequeñas)
            if (UiState.drop.isNotEmpty()) item(key = "#drop") {
                val r = (20f * sk.round)
                Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(r.dp)).dashed(pal.line2, r.dp, 1.5.dp).padding(22.dp)) {
                    BasicText(dropText(UiState.drop, pal.fg), Modifier.lines(15f * sk.tk * 1.45f), style = style(15f * sk.tk, pal.muted))
                }
                Spacer(Modifier.height(26.dp))
            }
            // ---- buscar, filtrar, ordenar
            item(key = "#tools") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchBox(sk) { searching = it }
                    Seg(sk)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SortSelect(sk)
                        Btn("Reproducir todo", sk, small = true) { if (rows.isNotEmpty()) play(0) }
                    }
                }
                Spacer(Modifier.height(26.dp))
                // la cabecera de la tabla
                Row(Modifier.fillMaxWidth().drawBehind { drawLine(pal.line, Offset(0f, size.height - sk.b.toPx() / 2), Offset(size.width, size.height - sk.b.toPx() / 2), sk.b.toPx()) }.padding(start = 6.dp, end = 6.dp, bottom = 8.dp + sk.b)) {
                    T("CANCIÓN", 10.88f * sk.tk, pal.faint, Modifier.weight(1f), family = Mono, ls = 0.12f, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    T("DUR.", 10.88f * sk.tk, pal.faint, Modifier.width(46.dp), family = Mono, ls = 0.12f, align = TextAlign.End, maxLines = 1)
                    Spacer(Modifier.width(94.dp))
                }
            }
            // ---- las canciones
            if (rows.isEmpty()) item(key = "#empty") {
                val r = 20f * sk.round
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(r.dp)).dashed(pal.line2, r.dp, 1.dp).padding(26.dp)) {
                    T(if (search.isNotBlank()) "Nada coincide con tu búsqueda." else when (filter) {
                        "liked" -> "Aún no tienes favoritas. Pulsa el corazón en cualquier canción."
                        "mine" -> "Aún no has subido canciones cantadas por ti. Hazlo en «Tu voz»."
                        else -> "Tu biblioteca está vacía."
                    }, 15f * sk.tk, pal.muted)
                }
            }
            items(rows.size, key = { "t:" + rows[it].id }) { i ->
                val t = rows[i]
                TrackRow(t, t.id == now, sk) { play(i) }
            }
            // ---- las pistas de demostración
            UiState.demos?.let { b ->
                item(key = "#demos") {
                    Spacer(Modifier.height(26.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(b.text, 13.44f * sk.tk, pal.muted)
                        Btn(b.button, sk, small = true) { UiState.send("demos") }
                    }
                }
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

private fun dropText(s: String, strong: Color): AnnotatedString = buildAnnotatedString {
    // «**negrita** resto»: la app manda el texto con la parte fuerte entre dos asteriscos
    val a = s.indexOf("**")
    val b = if (a >= 0) s.indexOf("**", a + 2) else -1
    if (a < 0 || b < 0) { append(s); return@buildAnnotatedString }
    append(s.substring(0, a))
    withStyle(SpanStyle(color = strong, fontWeight = FontWeight.Bold)) { append(s.substring(a + 2, b)) }
    append(s.substring(b + 2))
}

/** Borde de rayas (los recuadros «vacíos» de la app). */
private fun Modifier.dashed(color: Color, radius: Dp, width: Dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 3))))
}

@Composable
private fun SearchBox(sk: Skin, onFocus: (Boolean) -> Unit) {
    val p = sk.pal
    val shape = sk.r(13f)
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().height(20.dp + sk.b * 2 + (23.75f * sk.tk).dp).clip(shape).background(Color.Black.copy(alpha = 0.35f)).border(sk.b, p.line2, shape).padding(horizontal = 14.dp + sk.b),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(I_SEARCH, 16f, p.muted, sw = 2.2f)
        BasicTextField(
            value = UiState.search, onValueChange = { UiState.search = it.take(80) }, modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) }, singleLine = true,
            textStyle = style(15f * sk.tk, p.fg), cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (UiState.search.isEmpty()) T("Filtrar por título, artista o álbum", 15f * sk.tk, Color(0xFF757575), maxLines = 1)
                    inner()
                }
            }
        )
        if (UiState.search.isNotEmpty()) Box(Modifier.size(22.dp).semantics { contentDescription = "Borrar la búsqueda" }.press { UiState.search = "" }, contentAlignment = Alignment.Center) { Ico("M6 6l12 12M18 6L6 18", 14f, p.muted, sw = 2.4f) }
    }
}

@Composable
private fun Seg(sk: Skin) {
    val p = sk.pal
    val shape = sk.r(12f)
    val opts = ArrayList<Pair<String, String>>()
    opts.add("all" to "Todas"); opts.add("liked" to "Favoritas"); opts.add("mine" to "Mi voz")
    if (UiState.showDev) opts.add("dev" to "Del móvil")
    Row(Modifier.clip(shape).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, shape).padding(sk.b).height(IntrinsicSize.Min)) {
        opts.forEachIndexed { i, (id, name) ->
            val on = UiState.filter == id
            if (i > 0) Box(Modifier.width(sk.b).fillMaxHeight().background(p.line2))
            Box(Modifier.background(if (on) p.fg else Color.Transparent).clickable(remember { MutableInteractionSource() }, null) {
                UiState.filter = id
                UiState.send("pref") { it.put("filter", id) }
            }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                T(name, 13.76f * sk.tk, if (on) p.bg else p.muted, weight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SortSelect(sk: Skin) {
    val p = sk.pal
    val shape = sk.r(10f)
    var open by remember { mutableStateOf(false) }
    val label = SORTS.firstOrNull { it.first == UiState.sort }?.second ?: SORTS[0].second
    Box {
        Row(Modifier.width(138.1.dp).clip(shape).background(p.panelSolid).border(sk.b, p.line2, shape).clickable(remember { MutableInteractionSource() }, null) { open = true }.padding(start = 13.dp, end = 6.dp, top = 7.dp + sk.b, bottom = 7.dp + sk.b),
            verticalAlignment = Alignment.CenterVertically) {
            T(label, 13.12f * sk.tk, p.fg, Modifier.weight(1f), lh = 1.335f, maxLines = 1)
            Ico(I_DOWN, 14f, p.fg, sw = 2f)
        }
        if (open) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }), onDismissRequest = { open = false }) {
            val ps = sk.r(12f)
            Column(Modifier.width(200.dp).glow(Color.Black.copy(alpha = 0.6f), 12.dp, 30.dp, 10.dp, 0.dp).clip(ps).background(p.panelSolid).border(sk.b, p.line2, ps).padding(vertical = 6.dp)) {
                for ((id, name) in SORTS) {
                    val on = id == UiState.sort
                    Box(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {
                        UiState.sort = id; open = false
                        UiState.send("pref") { it.put("sort", id) }
                    }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        T(name, 15f * sk.tk, if (on) p.accent else p.fg, weight = if (on) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Los avisos cortos de la app, encima de la lista (los de la web quedarían tapados). */
@Composable
private fun ToastHost(sk: Skin, modifier: Modifier) {
    val n = UiState.toastN
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(n) { if (n > 0 && UiState.toast.isNotEmpty()) { show = true; delay(UiState.toastMs); show = false } }
    if (!show) return
    val shape = sk.r(12f)
    Box(modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).glow(sk.pal.glow, (12f * sk.round).dp, 40.dp, 14.dp, (-10).dp).clip(shape).background(sk.pal.grad).padding(horizontal = 18.dp, vertical = 11.dp)) {
        T(UiState.toast, 14.08f * sk.tk, sk.pal.onAccent, weight = FontWeight.ExtraBold, align = TextAlign.Center)
    }
}
