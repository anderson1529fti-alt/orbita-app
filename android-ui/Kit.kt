package __PACKAGE__

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fase 4, parte 2 · Las piezas de las pantallas que antes eran de la web y ahora hace Android a mano («Ajustes», «Buscar»,
 * «Tu voz» y las ventanas grandes), con las medidas de su CSS en el móvil: recuadros, títulos, notas, interruptores,
 * botones de opciones, deslizadores, campos de texto…
 */

/** Lo que cambia en estas pantallas (cada vez que se toca algo, se vuelven a leer los ajustes). */
object Ver {
    var n by androidx.compose.runtime.mutableIntStateOf(0); private set
    fun bump() { android.os.Handler(android.os.Looper.getMainLooper()).post { n++ } }
}

/** (que lo que está debajo no se toque) */
internal fun Modifier.pointerBlock(): Modifier = this.clickable(MutableInteractionSource(), null) { }

/** Un recuadro («panel»), con brillo («glow») o normal, con sus cosas una debajo de otra a 14. */
@Composable
internal fun Panel(sk: Skin, glow: Boolean = false, modifier: Modifier = Modifier, gap: Dp = 14.dp, content: @Composable () -> Unit) {
    Column((if (glow) modifier.fillMaxWidth().glowPanel(sk) else modifier.fillMaxWidth().panel(sk)).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(gap)) { content() }
}

@Composable internal fun H2(t: String, sk: Skin, modifier: Modifier = Modifier) = T(t, 20f * sk.tk, sk.pal.fg, modifier, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
@Composable internal fun Note(t: String, sk: Skin, modifier: Modifier = Modifier, color: Color? = null) = T(t, 13.44f * sk.tk, color ?: sk.pal.muted, modifier)
@Composable internal fun Lbl(t: String, sk: Skin, modifier: Modifier = Modifier) = T(t, 13.6f * sk.tk, sk.pal.muted, modifier, weight = FontWeight.SemiBold)

/** Un renglón que pasa al siguiente si no cabe («row-wrap»: a 12). */
@Composable internal fun RowWrap(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable () -> Unit) = HFlow(gap, gap, modifier.fillMaxWidth(), content = content)

/** Un título y, a su derecha, lo que vaya («row-wrap» con «spacer»): si no cabe, debajo. */
@Composable
internal fun HeadWith(title: @Composable () -> Unit, right: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = { title(); right() }) { ms, c ->
        val g = 12.dp.roundToPx()
        val a = ms[0].measure(androidx.compose.ui.unit.Constraints(0, c.maxWidth, 0, androidx.compose.ui.unit.Constraints.Infinity))
        val b = ms[1].measure(androidx.compose.ui.unit.Constraints(0, c.maxWidth, 0, androidx.compose.ui.unit.Constraints.Infinity))
        if (a.width + 2 * g + b.width <= c.maxWidth) {
            val h = max(a.height, b.height)
            layout(c.maxWidth, h) { a.place(0, (h - a.height) / 2); b.place(c.maxWidth - b.width, (h - b.height) / 2) }
        } else layout(c.maxWidth, a.height + g + b.height) { a.place(0, 0); b.place(0, a.height + g) }
    }
}

/** Una opción redonda («chip»): marcada, con el degradado de la app. */
@Composable
internal fun Chip(t: String, on: Boolean, sk: Skin, modifier: Modifier = Modifier, center: Boolean = false, padV: Dp = 9.dp, padH: Dp = 15.dp, onClick: () -> Unit) {
    val p = sk.pal
    val shape = RoundedCornerShape(999.dp)
    var m = modifier
    if (on) m = m.glow(p.glow, 999.dp, 22.dp, 6.dp, (-8).dp)
    m = m.press(onClick = onClick).clip(shape)
    m = if (on) m.background(p.grad).padding(sk.b) else m.background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, shape).padding(sk.b)
    Box(m.padding(horizontal = padH, vertical = padV), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        T(t, 14.08f * sk.tk, if (on) p.onAccent else p.muted, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}
@Composable internal fun Chips(modifier: Modifier = Modifier, content: @Composable () -> Unit) = HFlow(9.dp, 9.dp, modifier.fillMaxWidth(), content = content)

/** Varias opciones pegadas, una marcada («seg»). Con «stretch», a todo lo ancho y repartidas. */
@Composable
internal fun Seg(opts: List<Pair<String, String>>, cur: String, sk: Skin, modifier: Modifier = Modifier, stretch: Boolean = false, padH: Dp = 14.dp, onPick: (String) -> Unit) {
    val p = sk.pal
    val shape = sk.r(12f)
    val box = Modifier.clip(shape).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, shape).padding(sk.b)
    @Composable fun item(i: Int, id: String, name: String, m: Modifier) {
        val on = id == cur
        Box(m.drawBehind { if (i > 0) drawRect(p.line2, Offset.Zero, Size(sk.b.toPx(), size.height)) }.padding(start = if (i > 0) sk.b else 0.dp)
            .background(if (on) p.fg else Color.Transparent).clickable(remember { MutableInteractionSource() }, null) { onPick(id) }
            .padding(horizontal = padH, vertical = 10.dp), contentAlignment = Alignment.Center) {
            T(name, 13.76f * sk.tk, if (on) p.bg else p.muted, weight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
    if (stretch) Row(modifier.fillMaxWidth().then(box)) { opts.forEachIndexed { i, (id, name) -> item(i, id, name, Modifier.weight(1f)) } }
    else HFlowTight(modifier.then(box)) { opts.forEachIndexed { i, (id, name) -> item(i, id, name, Modifier) } }
}
/** (en fila, y si no caben, al renglón siguiente; sin huecos) */
@Composable internal fun HFlowTight(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { ms, c ->
        val loose = androidx.compose.ui.unit.Constraints(0, c.maxWidth, 0, androidx.compose.ui.unit.Constraints.Infinity)
        val ps = ms.map { it.measure(loose) }
        var x = 0; var y = 0; var rowH = 0; var w = 0
        val pos = ArrayList<Pair<Int, Int>>()
        for (pl in ps) { if (x > 0 && x + pl.width > c.maxWidth) { x = 0; y += rowH; rowH = 0 }; pos.add(x to y); x += pl.width; w = max(w, x); rowH = max(rowH, pl.height) }
        layout(w, y + rowH) { ps.forEachIndexed { i, pl -> pl.place(pos[i].first, pos[i].second) } }
    }
}

/** El deslizador de la app: la barra de 6 con su parte llena del degradado y la bola blanca con su halo. */
@Composable
internal fun Range(value: Float, min0: Float, max0: Float, step: Float, sk: Skin, modifier: Modifier = Modifier, hue: Boolean = false, onChange: (Float) -> Unit, onDone: (Float) -> Unit = {}) {
    val p = sk.pal
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange); val done by rememberUpdatedState(onDone)
    fun at(x: Float, w: Float, r: Float): Float {
        val k = ((x - r) / max(1f, w - 2 * r)).coerceIn(0f, 1f)
        var v = min0 + k * (max0 - min0)
        if (step > 0) v = Math.round((v - min0) / step) * step + min0
        return v.coerceIn(min0, max0)
    }
    Canvas(modifier.fillMaxWidth().height(20.dp).pointerInput(min0, max0, step) {
        detectTapGestures { o -> val v = at(o.x, size.width.toFloat(), 8.dp.toPx()); change(v); done(v) }
    }.pointerInput(min0, max0, step) {
        var last = cur
        detectHorizontalDragGestures(onDragStart = { o -> last = at(o.x, size.width.toFloat(), 8.dp.toPx()); change(last) }, onDragEnd = { done(last) }, onDragCancel = { done(last) }) { ch, _ ->
            ch.consume(); last = at(ch.position.x, size.width.toFloat(), 8.dp.toPx()); change(last)
        }
    }) {
        val th = 6.dp.toPx(); val r = 8.dp.toPx()
        val k = ((cur - min0) / max(0.0001f, max0 - min0)).coerceIn(0f, 1f)
        val cx = r + k * (size.width - 2 * r); val cy = size.height / 2
        val top = cy - th / 2
        if (hue) drawRoundRect(Brush.horizontalGradient(listOf(hsl(0f, .9f, .6f), hsl(60f, .9f, .55f), hsl(120f, .8f, .5f), hsl(180f, .85f, .5f), hsl(240f, .85f, .62f), hsl(300f, .85f, .6f), hsl(359f, .9f, .6f))), Offset(0f, top), Size(size.width, th), CornerRadius(th / 2))
        else {
            drawRoundRect(Color.White.copy(alpha = 0.13f), Offset(0f, top), Size(size.width, th), CornerRadius(th / 2))
            if (cx > 0) drawRoundRect(Brush.horizontalGradient(listOf(p.accent, p.accent2), 0f, max(1f, cx)), Offset(0f, top), Size(cx, th), CornerRadius(th / 2))
        }
        drawIntoCanvas { cv ->
            val pt = Paint(); pt.color = p.accent
            pt.asFrameworkPaint().maskFilter = android.graphics.BlurMaskFilter(7.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            cv.drawCircle(Offset(cx, cy), r, pt)
        }
        drawCircle(p.wash, r + 4.dp.toPx(), Offset(cx, cy))
        drawCircle(Color.White, r, Offset(cx, cy))
    }
}

/** Un deslizador con su nombre y su valor encima («slider»). */
@Composable
internal fun Slider(label: String, valText: String, value: Float, min0: Float, max0: Float, step: Float, sk: Skin, modifier: Modifier = Modifier, hue: Boolean = false, onChange: (Float) -> Unit, onDone: (Float) -> Unit = {}) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (valText.isEmpty()) T(label, 13.44f * sk.tk, sk.pal.muted, weight = FontWeight.Bold)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(label, 13.44f * sk.tk, sk.pal.muted, Modifier.weight(1f), weight = FontWeight.Bold)
            T(valText, 13.44f * sk.tk, sk.pal.muted, family = Mono, weight = FontWeight.Bold, maxLines = 1)
        }
        Range(value, min0, max0, step, sk, hue = hue, onChange = onChange, onDone = onDone)
    }
}

/** Un campo de texto de la app (oscuro, con su borde; letra de máquina si es una clave). */
@Composable
internal fun Field(value: String, onValue: (String) -> Unit, sk: Skin, modifier: Modifier = Modifier, placeholder: String = "", password: Boolean = false, mono: Boolean = true,
                   size: Float = 13.6f, padH: Dp = 12.dp, padV: Dp = 10.dp, radius: Float = 11f, bg: Color = Color.Black.copy(alpha = 0.4f), single: Boolean = true, minLines: Int = 1,
                   kb: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null) {
    val p = sk.pal
    val shape = sk.r(radius)
    val st = style(size * sk.tk, p.fg, if (mono) Mono else Body)
    BasicTextField(value, onValue, modifier.clip(shape).background(bg).border(sk.b, p.line2, shape), textStyle = st, singleLine = single, minLines = minLines,
        cursorBrush = SolidColor(p.accent), visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else kb, imeAction = if (single) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = padH + sk.b, vertical = padV + sk.b)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, size * sk.tk, p.faint, family = if (mono) Mono else Body, maxLines = 1)
                inner()
            }
        })
}

/** «keyrow»: el campo (que se estira) y sus botones; si no caben, debajo. */
@Composable
internal fun KeyRow(modifier: Modifier = Modifier, field: @Composable (Modifier) -> Unit, buttons: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = { field(Modifier); buttons() }, modifier = modifier.fillMaxWidth()) { ms, c ->
        val g = 8.dp.roundToPx()
        val loose = androidx.compose.ui.unit.Constraints(0, c.maxWidth, 0, androidx.compose.ui.unit.Constraints.Infinity)
        val bs = ms.drop(1).map { it.measure(loose) }
        val bw = bs.sumOf { it.width } + g * bs.size
        val basis = 220.dp.roundToPx()
        val oneRow = c.maxWidth - bw >= basis
        val fw = if (oneRow) c.maxWidth - bw else c.maxWidth
        val f = ms[0].measure(androidx.compose.ui.unit.Constraints(fw, fw, 0, androidx.compose.ui.unit.Constraints.Infinity))
        if (oneRow) {
            val h = max(f.height, bs.maxOfOrNull { it.height } ?: 0)
            layout(c.maxWidth, h) { f.place(0, (h - f.height) / 2); var x = fw + g; for (b in bs) { b.place(x, (h - b.height) / 2); x += b.width + g } }
        } else {
            // (los botones, en su propio renglón, uno tras otro)
            val bh = bs.maxOfOrNull { it.height } ?: 0
            layout(c.maxWidth, f.height + (if (bs.isNotEmpty()) g + bh else 0)) { f.place(0, 0); var x = 0; for (b in bs) { b.place(x, f.height + g); x += b.width + g } }
        }
    }
}

/** «infobox»: el recuadro de aviso, con su parte en negrita. */
@Composable
internal fun InfoBox(bold: String, rest: String, sk: Skin) {
    val p = sk.pal
    val txt = androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(color = p.fg, fontWeight = FontWeight.Bold)); append(bold); pop()
        append(rest)
    }
    Box(Modifier.fillMaxWidth().clip(sk.r(14f)).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, sk.r(14f)).padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b)) {
        androidx.compose.foundation.text.BasicText(txt, Modifier.lines(14.4f * sk.tk * 1.45f), style = style(14.4f * sk.tk, p.muted))
    }
}

/** La barra de lo ocupado («meter»). */
@Composable
internal fun Meter(frac: Float, sk: Skin) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(8.dp).background(sk.pal.grad))
    }
}

/** La etiqueta pequeña con el degradado («nuevo», «beta»). */
@Composable
internal fun BetaTag(t: String, sk: Skin) {
    Box(Modifier.clip(RoundedCornerShape(99.dp)).background(sk.pal.grad).padding(horizontal = 7.dp, vertical = 0.dp)) {
        T(t.uppercase(), 9.92f, Color.White, weight = FontWeight.Bold, lh = 1.5f, ls = 0.06f, maxLines = 1)
    }
}

/** Una pastilla de estado («Permitido», «Bloqueado»…), con su color. */
@Composable
internal fun StateTag(t: String, kind: String, sk: Skin) {
    val p = sk.pal
    val c = when (kind) { "ok" -> p.good; "no" -> p.bad; else -> p.muted }
    val bc = when (kind) { "ok" -> p.good; "no" -> p.bad; else -> p.line2 }
    Box(Modifier.clip(RoundedCornerShape(999.dp)).border(sk.b, bc, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp + sk.b, vertical = 3.dp + sk.b)) {
        T(t.uppercase(), 11.2f * sk.tk, c, family = Mono, weight = FontWeight.SemiBold, ls = 0.08f, maxLines = 1)
    }
}

/** Una pastilla pequeña («Natural», «En uso»). */
@Composable
internal fun Pill(t: String, sk: Skin, accent: Boolean = false) {
    val p = sk.pal
    Box(Modifier.clip(RoundedCornerShape(999.dp)).border(sk.b, if (accent) p.accent else p.line2, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp + sk.b, vertical = 2.dp + sk.b)) {
        T(t.uppercase(), 9.92f * sk.tk, if (accent) p.accent else p.muted, family = Mono, weight = FontWeight.SemiBold, ls = 0.1f, maxLines = 1)
    }
}

/** «d-text»: un título en negrita y su explicación debajo. */
@Composable
internal fun DText(b: String, s: String, sk: Skin, modifier: Modifier = Modifier, bSize: Float = 15f, display: Boolean = false, extra: @Composable () -> Unit = {}) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T(b, bSize * sk.tk, sk.pal.fg, family = if (display) sk.display else Body, weight = if (display) FontWeight.ExtraBold else FontWeight.ExtraBold, ls = if (display) -0.01f else 0f)
            extra()
        }
        if (s.isNotEmpty()) T(s, 13.12f * sk.tk, sk.pal.muted)
    }
}

/** El recuadro de los permisos («perm»): qué es, en qué estado está y su botón. */
@Composable
internal fun PermRow(t: String, d: String, sk: Skin, state: Pair<String, String>? = null, btn: String? = null, onBtn: () -> Unit = {}) {
    val p = sk.pal
    HFlow(12.dp, 12.dp, Modifier.fillMaxWidth().clip(sk.r(14f)).background(Color.Black.copy(alpha = 0.28f)).border(sk.b, p.line, sk.r(14f)).padding(horizontal = 14.dp + sk.b, vertical = 12.dp + sk.b), grow = 0, basis = 240.dp) {
        DText(t, d, sk)
        if (state != null) StateTag(state.second, state.first, sk)
        if (btn != null) HBtn(btn, sk, primary = true, kind = 1, onClick = onBtn)
    }
}

/** Una tarjeta de «nv-card» (la voz propia, el cerebro): borde del color de la app y un poco de su luz. */
@Composable
internal fun NvCard(sk: Skin, content: @Composable () -> Unit) {
    val p = sk.pal
    val shape = sk.r(16f)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brush.horizontalGradient(listOf(p.wash, Color.Black.copy(alpha = 0.3f)))).border(sk.b, p.accent, shape)
        .padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** El aviso de arriba («update»): borde del color de la app, su luz y sus botones. */
@Composable
internal fun UpdateBox(b: String, t: String, sk: Skin, buttons: @Composable () -> Unit) {
    val p = sk.pal
    val shape = sk.r(16f)
    HFlow(12.dp, 12.dp, Modifier.fillMaxWidth().glow(p.glow, (16f * sk.round).dp, 30.dp, 0.dp, (-12).dp).clip(shape).background(Brush.horizontalGradient(listOf(p.wash, Color.Black.copy(alpha = 0.35f))))
        .border(sk.b, p.accent, shape).padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b), grow = 0, basis = 240.dp) {
        DText(b, t, sk, display = true)
        buttons()
    }
}

/** El círculo de Nova en grande (la bienvenida, el despertador): su aro de colores que gira y las barras que suben y bajan. */
@Composable
internal fun BigAvatar(sk: Skin, side: Float) {
    val p = sk.pal
    val lite = Nativo.lite()
    val tr = rememberInfiniteTransition(label = "av")
    val spin by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "s")
    val bars = (0 until 5).map { i -> tr.animateFloat(6f, 26f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(150 * i)), label = "b$i") }
    val lime = Color(0xFFA3E635); val teal = Color(0xFF21D4A8)
    Canvas(Modifier.size(side.dp)) {
        val ctr = Offset(size.width / 2, size.height / 2)
        val ring = Brush.sweepGradient(listOf(lime, p.accent, p.accent2, teal, lime), ctr)
        rotate((if (lite) 0f else spin) - 50f, ctr) {
            drawIntoCanvas { cv ->
                val pt = Paint()
                ring.applyTo(size, pt, 0.7f)
                pt.asFrameworkPaint().maskFilter = android.graphics.BlurMaskFilter(10.dp.toPx() * 1.7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                cv.drawCircle(ctr, size.width / 2 + 8.dp.toPx(), pt)
            }
            drawCircle(ring, size.width / 2, ctr)
        }
        val inner = size.width / 2 - 4.dp.toPx()
        drawCircle(Brush.radialGradient(0f to Color(0xFF2A1F45), 0.7f to Color(0xFF0B0814), 1f to Color(0xFF0B0814), center = Offset(ctr.x - inner + 2 * inner * 0.35f, ctr.y - inner + 2 * inner * 0.30f), radius = 2 * inner * 0.72f), inner, ctr)
        val w = 6.dp.toPx(); val g = 3.dp.toPx()
        var x = ctr.x - (5 * w + 4 * g) / 2
        for (i in 0 until 5) {
            val hp = (if (lite || Nativo.reduceMotion()) 14f else bars[i].value).dp.toPx()
            drawRoundRect(p.fg, Offset(x, ctr.y - hp / 2), Size(w, hp), CornerRadius(2.dp.toPx() * sk.round))
            x += w + g
        }
    }
}
