package __PACKAGE__

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fase 4, parte 2 · La barra de abajo de la Órbita nativa: la canción que suena, sus botones, la barra del tiempo y las
 * pestañas. Con las medidas exactas de la de siempre (las de su CSS en el móvil, medidas en la web).
 */
internal object Bar {
    var track by mutableStateOf<UiTrack?>(null)
    var playing by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf("off")
    var sleep by mutableStateOf("")
    var live by mutableStateOf(false)
    /** Hacia dónde entró la canción nueva (1: siguiente, −1: anterior), para su movimiento. */
    var dir = 1
    var pos by mutableDoubleStateOf(0.0)
    var dur by mutableDoubleStateOf(0.0)
    /** Lo de abajo del todo del móvil (la raya de gestos o sus botones), en «px» de CSS. */
    var inset by mutableDoubleStateOf(0.0)
    /** El alto que ocupa (en «px» de CSS), sin lo de abajo del todo del móvil. */
    const val PLAYER = 152f
    const val NAV = 60.77f
}

private const val P_PLAY = "M7 4.5v15l13-7.5z"
private const val P_PAUSE = "M6 4h4.5v16H6zM13.5 4H18v16h-4.5z"
private const val P_PREV = "M6 5h2v14H6zM20 5v14L9 12z"
private const val P_NEXT = "M16 5h2v14h-2zM4 5v14l11-7z"
private const val P_SHUFFLE = "M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"
private const val P_REPEAT = "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"
private const val P_SLEEP = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"
private const val P_HEART = "M12 20s-7.5-4.6-9.2-9.3C1.7 7.5 3.8 4.5 7 4.5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.2 0 5.3 3 4.2 6.2C19.5 15.4 12 20 12 20z"

internal val TABS = listOf(
    Triple("inicio", "Ahora", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
    Triple("buscar", "Buscar", "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0M20 20l-4-4"),
    Triple("biblioteca", "Música", "M9 18V5l12-2v13M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
    Triple("listas", "Listas", "M3 6h13M3 12h13M3 18h8M18 14v7l4-2"),
    Triple("tu", "Tú", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M4 21a8 8 0 0 1 16 0")
)

private val EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

private fun fmtT(s: Double): String {
    if (s.isNaN() || s.isInfinite() || s <= 0) return "0:00"
    val r = Math.round(s)
    return (r / 60).toString() + ":" + (r % 60).toString().padStart(2, '0')
}

/** La barra entera, pegada abajo (con el hueco de los botones del móvil debajo de las pestañas). */
@Composable
internal fun NatBar(sk: Skin, bottomInset: Float) {
    Column(Modifier.fillMaxWidth()) {
        PlayerBlock(sk)
        NavBlock(sk, bottomInset)
    }
}

@Composable
private fun PlayerBlock(sk: Skin) {
    val p = sk.pal
    val black = UiState.look.black
    Box(Modifier.fillMaxWidth().height(Bar.PLAYER.dp)
        .glow(p.glow, 0.dp, 50.dp, (-20).dp, (-30).dp)
        .background(if (black) Color.Black.copy(alpha = 0.96f) else Color(0xF00A0714))
        .drawBehind {
            // la raya de color de arriba (2 px), que se apaga hacia los lados
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, p.accent, p.accent2, Color.Transparent)), size = Size(size.width, 2.dp.toPx()))
        }
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TrackRow(sk)
            Column(Modifier.fillMaxWidth().height(74.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Controls(sk)
                SeekRow(sk)
            }
        }
    }
}

@Composable
private fun TrackRow(sk: Skin) {
    val p = sk.pal
    val t = Bar.track
    // al cambiar de canción: la portada entra deslizándose y los textos detrás (hacia donde se ha ido)
    val slide = remember { Animatable(0f) }; val fade = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(t?.id) }
    LaunchedEffect(t?.id) {
        if (seen != null && t != null && seen != t.id) {
            slide.snapTo(Bar.dir.toFloat()); fade.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch { slide.animateTo(0f, tween(300, easing = EASE)) }
                launch { delay(40); fade.animateTo(1f, tween(280, easing = EASE)) }
            }
        }
        seen = t?.id
    }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        val open = Modifier.pointerInput(Unit) { detectTapGestures { Nativo.onUi("np-open", org.json.JSONObject()) } }
        Box(open.graphicsLayer { translationX = slide.value * 26.dp.toPx(); alpha = 1f - kotlin.math.abs(slide.value) }) {
            if (t != null) Cover(t, sk, 56f, 12f)
            else Cover(UiTrack("", "", "", "", "", 0.0, 0.0, 0, 0.0, false, false, false, false, false, 280f), sk, 56f, 12f)
        }
        Spacer(Modifier.width(12.dp))
        Column(open.weight(1f, fill = false).graphicsLayer { translationX = (1f - fade.value) * Bar.dir * 10.dp.toPx(); alpha = fade.value }) {
            T(t?.title ?: "Nada sonando", 15f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
            T(t?.artist ?: "Gira la ruleta o pídele algo a la DJ", 13.44f * sk.tk, p.muted, maxLines = 1)
        }
        if (t != null && !Bar.live) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(34.dp).semantics { contentDescription = if (t.liked) "Quitar de favoritas" else "Favorita" }.press(0.85f) {
                Nativo.onUi("like", org.json.JSONObject().put("id", t.id))
            }, contentAlignment = Alignment.Center) {
                Ico(P_HEART, 18f, if (t.liked) p.accent else p.muted, fill = t.liked)
            }
        }
    }
}

@Composable
private fun Controls(sk: Skin) {
    val p = sk.pal
    Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Ctrl(sk, "Aleatorio", on = Bar.shuffle) { Ico(P_SHUFFLE, 18f, if (Bar.shuffle) p.accent else p.muted) }
        Ctrl(sk, "Anterior", act = "prev") { Ico(P_PREV, 20f, p.muted, fill = true) }
        PlayBtn(sk)
        Ctrl(sk, "Siguiente", act = "next") { Ico(P_NEXT, 20f, p.muted, fill = true) }
        Ctrl(sk, when (Bar.repeat) { "all" -> "Repetir: toda la lista"; "one" -> "Repetir: esta canción"; else -> "Repetir: no" }, act = "repeat", badge = if (Bar.repeat == "one") "1" else "") {
            Ico(P_REPEAT, 18f, if (Bar.repeat != "off") p.accent else p.muted)
        }
        Ctrl(sk, "Temporizador para dormir", act = "sleep-open", badge = Bar.sleep) { Ico(P_SLEEP, 18f, if (Bar.sleep.isNotEmpty()) p.accent else p.muted) }
    }
}

@Composable
private fun Ctrl(sk: Skin, label: String, act: String = "shuffle", on: Boolean = false, badge: String = "", icon: @Composable () -> Unit) {
    val p = sk.pal
    Box(Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = label }.press { Nativo.onUi(act, org.json.JSONObject()) }, contentAlignment = Alignment.Center) {
        icon()
        if (badge.isNotEmpty()) T(badge, 9.6f, if (on || act == "repeat" || act == "sleep-open") p.accent else p.muted, Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp), family = Mono, weight = FontWeight.ExtraBold, lh = 1f)
    }
}

@Composable
private fun PlayBtn(sk: Skin) {
    val p = sk.pal
    val playing = Bar.playing
    // el icono gira al cambiar de reproducir a pausa (y al revés)
    val turn = remember { Animatable(0f) }
    var was by remember { mutableStateOf(playing) }
    LaunchedEffect(playing) { if (was != playing) { turn.snapTo(1f); turn.animateTo(0f, tween(240)) }; was = playing }
    Box(Modifier.size(46.dp).glow(p.glow, 23.dp, 26.dp, 0.dp, (-4).dp).clip(CircleShape).background(p.grad)
        .semantics { contentDescription = if (playing) "Pausar" else "Reproducir" }.press(0.9f) { Nativo.onUi("toggle", org.json.JSONObject()) },
        contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { rotationZ = turn.value * (if (playing) -90f else 90f); val s = 1f - 0.6f * turn.value; scaleX = s; scaleY = s; alpha = 1f - turn.value }) {
            Ico(if (playing) P_PAUSE else P_PLAY, 22f, p.onAccent, fill = true)
        }
    }
}

@Composable
private fun SeekRow(sk: Skin) {
    val p = sk.pal
    var drag by remember { mutableStateOf<Double?>(null) }
    val dur = Bar.dur
    val pos = drag ?: Bar.pos
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        T(fmtT(pos), 12f, p.muted, Modifier.widthIn(min = 28.81.dp), family = Mono, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(20.dp)
            .semantics { contentDescription = "Posición" }
            .pointerInput(dur) {
                fun at(x: Float): Double { val th = 8.dp.toPx(); return ((x - th) / maxOf(1f, size.width - 2 * th)).coerceIn(0f, 1f) * dur }
                detectTapGestures { o -> if (dur > 0) Nativo.onUi("seek", org.json.JSONObject().put("pos", at(o.x))) }
            }
            .pointerInput(dur) {
                fun at(x: Float): Double { val th = 8.dp.toPx(); return ((x - th) / maxOf(1f, size.width - 2 * th)).coerceIn(0f, 1f) * dur }
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (dur > 0) drag = at(o.x) },
                    onDragEnd = { drag?.let { Nativo.onUi("seek", org.json.JSONObject().put("pos", it)) }; drag = null },
                    onDragCancel = { drag = null }
                ) { ch, _ -> if (dur > 0) { drag = at(ch.position.x); ch.consume() } }
            }) {
            Range(sk, if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f)
        }
        Spacer(Modifier.width(12.dp))
        T(if (Bar.live) "EN VIVO" else fmtT(dur), 12f, p.muted, Modifier.widthIn(min = 28.81.dp), family = Mono, maxLines = 1)
    }
}

/** Una barra como la «range» de la web: carril de 6, relleno de color hasta donde va y la bolita blanca con su halo. */
@Composable
internal fun Range(sk: Skin, frac: Float) {
    val p = sk.pal
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val h = 6.dp.toPx(); val y = (size.height - h) / 2; val th = 8.dp.toPx()
        val cx = th + frac * (size.width - 2 * th)
        drawRoundRect(Color.White.copy(alpha = 0.13f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
        if (cx > 0f) drawRoundRect(Brush.horizontalGradient(listOf(p.accent, p.accent2), 0f, size.width), Offset(0f, y), Size(cx, h), CornerRadius(h / 2))
        val c = Offset(cx, size.height / 2)
        drawCircle(p.accent.copy(alpha = 0.55f), 8.dp.toPx() + 6.dp.toPx(), c, alpha = 0.35f)
        drawCircle(p.wash, 8.dp.toPx() + 4.dp.toPx(), c)
        drawCircle(Color.White, 8.dp.toPx(), c)
    }
}

@Composable
private fun NavBlock(sk: Skin, bottomInset: Float) {
    val p = sk.pal
    val black = UiState.look.black
    Column(Modifier.fillMaxWidth().background(if (black) Color.Black else Color(0xF7080610))
        .drawBehind { drawRect(p.line, size = Size(size.width, 1.dp.toPx())) }
        .padding(top = 5.dp, bottom = (4f + bottomInset).dp, start = 16.dp, end = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(51.77.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label, icon) in TABS) {
                val cur = UiState.screen == id
                Column(Modifier.weight(1f).height(51.77.dp).semantics { contentDescription = label }.press(0.96f) { Nativo.onUi("tab", org.json.JSONObject().put("view", id)) }
                    .padding(top = 7.dp, bottom = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Ico(icon, 20f, if (cur) p.accent else p.muted)
                    T(label, 10.88f, if (cur) p.fg else p.muted, weight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
