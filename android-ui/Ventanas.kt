package __PACKAGE__

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fase 4, parte 2 · Las ventanas de la Órbita nativa, de Android de verdad: el reproductor a pantalla completa
 * («Ahora suena»), el temporizador para dormir, editar una canción y el aviso de la importación. Con las medidas de la
 * web (las de su CSS en el móvil).
 */
object Ventanas {
    /** Las que se ven, de la de abajo a la de arriba. */
    var stack by mutableStateOf(listOf<String>())
    /** Lo que cambia y hay que volver a pintar (cada ventana mira su número). */
    var gen by mutableIntStateOf(0)
    /** La canción que se edita (y lo que se ha escrito). */
    var editId by mutableStateOf("")
    var editTitle by mutableStateOf(""); var editArtist by mutableStateOf(""); var editAlbum by mutableStateOf("")
    /** La portada nueva elegida (bytes) o «quitar» (null con [editCoverSet]). */
    var editCover: ByteArray? = null; var editCoverSet by mutableStateOf(false)
    var editCoverGen by mutableIntStateOf(0)
    /** El reproductor: cuánto está abierto (0 cerrado, 1 abierto) mientras se arrastra o se anima. */
    val npP = Animatable(0f)
    var npOpen by mutableStateOf(false)
    /** En el reproductor: la letra o el visualizador en lugar de la portada. */
    var lyOn by mutableStateOf(false)
    var vizOn by mutableStateOf(false)

    fun open(id: String) {
        if (id == "np" && !Player.has()) { Player.toggle(); return }
        if (id !in stack) stack = stack + id
        if (id == "np") npOpen = true
        gen++
        Nativo.ui?.nativoWin(true)
    }

    fun close(id: String) {
        if (id == "np") { npOpen = false; return }   // (se va con su animación; al acabar, [gone])
        gone(id)
    }

    fun gone(id: String) {
        stack = stack - id
        if (stack.isEmpty()) Nativo.ui?.nativoWin(false)
    }

    fun refresh(id: String) {
        gen++
        if (id == "imp") { if (Biblio.job != null) { if ("imp" !in stack) open("imp") } else if ("imp" in stack) gone("imp") }
    }

    fun openEdit(id: String) {
        val t = Lib.byId(id) ?: return
        editId = id; editTitle = t.title; editArtist = t.artist; editAlbum = t.album; editCover = null; editCoverSet = false
        open("edit")
    }

    fun saveEdit() {
        val t = Lib.byId(editId) ?: run { gone("edit"); return }
        t.title = editTitle.trim().ifEmpty { t.title }; t.artist = editArtist.trim().ifEmpty { "Artista desconocido" }; t.album = editAlbum.trim()
        t.edited = true
        val nc = editCover; val set = editCoverSet
        Biblio.io.execute {
            if (set) Biblio.setCover(t, nc)
            Biblio.save(t)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                UiState.covers.remove(t.id); UiState.asked.remove(t.id); UiState.covers.remove(t.id + "|L"); UiState.asked.remove(t.id + "|L")
                Nativo.libraryChanged(); if (Player.current() === t) { Player.barSync(); Nativo.look(); Nativo.bgSync() }
                Nativo.toast("Guardado")
            }
        }
        gone("edit")
    }

    fun pickCover() {
        val a = Nativo.act as? androidx.activity.ComponentActivity ?: return
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*")
        Actos.launch(a, androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), i) { r ->
            val u = r.data?.data ?: return@launch
            Biblio.io.execute {
                val b = try { a.contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (t: Throwable) { null }
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (b != null) { editCover = b; editCoverSet = true; editCoverGen++ } }
            }
        }
    }

    /** Lo que se toca en las ventanas (devuelve si era suyo). */
    fun onUi(type: String, o: JSONObject): Boolean {
        when (type) {
            "np-close" -> close("np")
            "q-jump" -> Player.jumpTo(o.optInt("i"))
            "q-up" -> Player.queueUp(o.optInt("i"))
            "q-del" -> Player.queueDel(o.optInt("i"))
            "sleep" -> { Player.setSleep(o.optString("v")); if (o.optString("v") != "off") gone("sleep") }
            "sleep-close" -> gone("sleep")
            "imp-stop" -> Biblio.stopImport()
            else -> return false
        }
        return true
    }

    /** «Atrás» del móvil: cierra la de arriba (devuelve si había alguna). */
    fun back(): Boolean {
        val top = stack.lastOrNull() ?: return false
        if (top == "imp") return false
        close(top); return true
    }
}

// ---------------------------------------------------------------- iconos
private const val I_DOWN = "M6 9l6 6 6-6"
private const val I_PREV = "M6 5h2v14H6zM20 5v14L9 12z"
private const val I_NEXT = "M16 5h2v14h-2zM4 5v14l11-7z"
private const val I_PLAY = "M7 4.5v15l13-7.5z"
private const val I_PAUSE = "M6 4h4.5v16H6zM13.5 4H18v16h-4.5z"
private const val I_HEART = "M12 20s-7.5-4.6-9.2-9.3C1.7 7.5 3.8 4.5 7 4.5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.2 0 5.3 3 4.2 6.2C19.5 15.4 12 20 12 20z"
private const val I_MOON = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"
private const val I_UP = "M12 19V5M5 12l7-7 7 7"
private const val I_X = "M6 6l12 12M18 6L6 18"

private val EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

private fun fmtT(s: Double): String {
    if (s.isNaN() || s.isInfinite() || s <= 0) return "0:00"
    val r = Math.round(s); return (r / 60).toString() + ":" + (r % 60).toString().padStart(2, '0')
}

/** La capa de las ventanas (encima de todo). */
@Composable
fun NatWindows() {
    val sk = rememberSkin()
    val list = Ventanas.stack
    Box(Modifier.fillMaxSize()) {
        for (id in list) key(id) {
            when (id) {
                "np" -> NpWindow(sk)
                "sleep" -> SleepWindow(sk)
                "edit" -> EditWindow(sk)
                "imp" -> ImportPill(sk, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (list.any { it != "imp" }) ToastHost(sk, Modifier.align(Alignment.BottomCenter).padding(bottom = (190f + Bar.inset.toFloat() - 10f).dp))
    }
}

// ---------------------------------------------------------------- «Ahora suena»
@Composable
private fun NpWindow(sk: Skin) {
    val p = sk.pal
    val dens = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val open = Ventanas.npOpen
    val prog = Ventanas.npP
    // abrir (desde abajo) y cerrar (hacia abajo), como la web: 0,34 s con su curva
    LaunchedEffect(open) {
        if (open) prog.animateTo(1f, tween(340, easing = EASE))
        else { prog.animateTo(0f, tween(300, easing = EASE)); Ventanas.gone("np") }
    }
    val t = Bar.track
    val cur = Player.current()
    @Suppress("UNUSED_VARIABLE") val g = Ventanas.gen
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { translationY = (1f - prog.value) * size.height }) {
        val wDp = maxWidth.value; val hDp = maxHeight.value
        // el fondo: la portada muy desenfocada, con más color y menos luz (o la luz del color de la app, sin portada)
        Box(Modifier.fillMaxSize().background(p.bg))
        val big = if (t != null && t.cover) UiState.covers[t.id + "|L"] else null
        if (t != null && t.cover && big == null) LaunchedEffect(t.id) { needArt(t.id, 400, dens) }
        if (big != null) {
            val soft = remember(big, wDp, hDp) { blurredCover(t!!.id, big, (wDp + 120) * dens, (hDp + 120) * dens, 50f * dens) }
            val cm = remember { val m = ColorMatrix(); m.setToSaturation(1.4f); val b = .45f; m.timesAssign(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))); m }
            if (soft != null) Image(soft, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.1f * (wDp + 120) / wDp; scaleY = 1.1f * (hDp + 120) / hDp },
                contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.colorMatrix(cm))
        } else Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(Brush.radialGradient(0f to p.glow, 0.7f to Color.Transparent, center = Offset(w * .3f, h * .2f), radius = maxOf(w, h) * .6f))
            drawRect(Brush.radialGradient(0f to hsl(UiState.look.h - 45f, .9f * UiState.look.s, .5f, .3f), 0.7f to Color.Transparent, center = Offset(w * .8f, h * .9f), radius = maxOf(w, h) * .6f))
        }
        // lo de dentro: una columna de 460 como mucho, con 20 de margen, que se desliza
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            // (hacia abajo, con la lista arriba del todo: se cierra siguiendo al dedo)
            var dragging = false; var v = 0f; var lastT = 0L; var lastY = 0f
            detectVerticalDragGestures(
                onDragStart = { dragging = scroll.value <= 2; v = 0f; lastT = System.currentTimeMillis() },
                onDragEnd = {
                    if (!dragging) return@detectVerticalDragGestures
                    dragging = false
                    val close = (prog.value < 0.7f && v > -0.3f) || v > 0.5f
                    if (close) Ventanas.npOpen = false else scope.launch { prog.animateTo(1f, tween(240, easing = EASE)) }
                },
                onDragCancel = { if (dragging) { dragging = false; scope.launch { prog.animateTo(1f, tween(240, easing = EASE)) } } }
            ) { ch, dy ->
                if (!dragging) return@detectVerticalDragGestures
                val now = System.currentTimeMillis(); val dt = max(1L, now - lastT)
                val vv = dy / dens / dt; v = if (v != 0f) v * .35f + vv * .65f else vv; lastT = now; lastY = dy
                ch.consume()
                scope.launch { prog.snapTo((prog.value - dy / size.height).coerceIn(0f, 1f)) }
            }
        }, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(scroll)
                .padding(start = 20.dp, end = 20.dp, top = maxOf(20f, Nativo.topInset()).dp, bottom = maxOf(20f, Bar.inset.toFloat()).dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // «Cerrar»
                Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .4f)).border(sk.b, p.line2, CircleShape)
                    .press { Ventanas.close("np") }.spot("np-close").semantics { contentDescription = "Cerrar" }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ico(I_DOWN, 18f, p.fg, sw = 2.4f)
                    T("Cerrar", 15f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
                }
                NpArt(sk, t)
                // título, artista y me gusta
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        T(npSource(cur).uppercase(), 11.2f, p.muted, family = Mono, weight = FontWeight.SemiBold, ls = .16f, maxLines = 1)
                        val ts = (min(max(24f, wDp * .06f), 35.2f))
                        T(t?.title ?: "Nada sonando", ts * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1.05f, ls = -.035f)
                        Spacer(Modifier.height(4.dp))
                        T(t?.artist ?: "Gira la ruleta o pídele algo a la DJ", 16.8f * sk.tk, p.muted, weight = FontWeight.SemiBold)
                    }
                    if (t != null && cur?.net != true) Box(Modifier.size(34.dp).clip(sk.r(10f)).press(.85f) { Biblio.like(t.id) }
                        .semantics { contentDescription = if (t.liked) "Quitar de favoritas" else "Favorita" }, contentAlignment = Alignment.Center) {
                        Ico(I_HEART, 18f, if (t.liked) p.accent else p.muted, fill = t.liked, round = false)
                    }
                }
                NpSeek(sk)
                // los botones grandes
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).press { Player.prev() }.semantics { contentDescription = "Anterior" }, contentAlignment = Alignment.Center) { Ico(I_PREV, 26f, p.muted, fill = true) }
                    val pl = Bar.playing
                    Box(Modifier.size(68.dp).spot("np-toggle").glow(p.glow, 34.dp, 26.dp, 0.dp, (-4).dp).clip(CircleShape).background(p.grad).press(.9f) { Player.toggle() }
                        .semantics { contentDescription = if (pl) "Pausar" else "Reproducir" }, contentAlignment = Alignment.Center) {
                        Ico(if (pl) I_PAUSE else I_PLAY, 22f, p.onAccent, fill = true)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).press { Player.next(false) }.semantics { contentDescription = "Siguiente" }, contentAlignment = Alignment.Center) { Ico(I_NEXT, 26f, p.muted, fill = true) }
                }
                NpChips(sk, cur)
                NpBrain(sk, cur)
                NpQueue(sk)
            }
        }
    }
}

/** De dónde viene lo que suena («npSrc»). */
private fun npSource(t: Track?): String = when {
    t == null -> "Ahora suena"
    t.kind == "radio" -> "Radio por internet · en directo"
    t.kind == "yt" -> "Vídeo de YouTube"
    t.net -> "Avance de 30 s por internet"
    Player.source == "radio" -> "Órbita FM · modo radio"
    Player.source == "daily" -> "Tu mezcla del día"
    Player.source == "mix" -> "Mezcla · " + (Nova.mixName ?: "")
    else -> "Desde tu biblioteca"
}

@Composable
private fun NpArt(sk: Skin, t: UiTrack?) {
    val p = sk.pal
    val dens = LocalDensity.current.density
    val shape = sk.r(22f)
    var dx by remember { mutableStateOf(0f) }
    // al cambiar de canción: la portada entra deslizándose desde donde se fue
    val slide = remember { Animatable(0f) }
    var seen by remember { mutableStateOf(t?.id) }
    LaunchedEffect(t?.id) { if (seen != null && t != null && seen != t.id) { slide.snapTo(Bar.dir.toFloat()); slide.animateTo(0f, tween(450, easing = EASE)) }; seen = t?.id }
    Box(Modifier.fillMaxWidth().aspectRatio(1f)
        .graphicsLayer { translationX = dx * .35f * dens + slide.value * 48f * dens; rotationZ = dx * .02f; alpha = 1f - abs(slide.value) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragEnd = {
                val d = dx; dx = 0f
                if (abs(d) > 70) { if (d < 0) Player.next(false) else Player.prev() }
            }, onDragCancel = { dx = 0f }) { ch, a -> dx += a / dens; ch.consume() }
        }
        .glow(Color.Black.copy(alpha = .9f), (22f * sk.round).dp, 80.dp, 40.dp, (-30).dp)
        .clip(shape).border(sk.b, p.line2, shape)) {
        val big = if (t != null && t.cover) UiState.covers[t.id + "|L"] else null
        if (t != null && t.cover && big == null) LaunchedEffect(t.id) { needArt(t.id, 400, dens) }
        if (big != null) Image(big, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val h = t?.hue ?: 280f
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(hsl(h, .75f, .5f), hsl(h + 60f, .7f, .22f)))).drawBehind {
                drawRect(Brush.radialGradient(0f to hsl(h + 40f, .9f, .7f, .9f), .55f to Color.Transparent, center = Offset(size.width * .3f, size.height * .25f), radius = size.width * 1.03f))
            }, contentAlignment = Alignment.Center) {
                T(t?.title?.trim()?.take(1)?.uppercase()?.ifEmpty { "♪" } ?: "♪", 112f * sk.tk, Color.White, family = sk.display, weight = sk.displayWeight, lh = 1f, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NpSeek(sk: Skin) {
    val p = sk.pal
    var drag by remember { mutableStateOf<Double?>(null) }
    val dur = Bar.dur
    val pos = drag ?: Bar.pos
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        T(fmtT(pos), 12f, p.muted, family = Mono, maxLines = 1)
        Box(Modifier.weight(1f).height(20.dp).semantics { contentDescription = "Posición" }
            .pointerInput(dur) {
                fun at(x: Float): Double { val th = 8.dp.toPx(); return ((x - th) / maxOf(1f, size.width - 2 * th)).coerceIn(0f, 1f) * dur }
                detectTapGestures { o -> if (dur > 0) Player.seek(at(o.x)) }
            }
            .pointerInput(dur) {
                fun at(x: Float): Double { val th = 8.dp.toPx(); return ((x - th) / maxOf(1f, size.width - 2 * th)).coerceIn(0f, 1f) * dur }
                detectHorizontalDragGestures(onDragStart = { o -> if (dur > 0) drag = at(o.x) }, onDragEnd = { drag?.let { Player.seek(it) }; drag = null }, onDragCancel = { drag = null }) { ch, _ -> if (dur > 0) { drag = at(ch.position.x); ch.consume() } }
            }) { Range(sk, if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f) }
        T(if (Bar.live) "EN VIVO" else fmtT(dur), 12f, p.muted, family = Mono, maxLines = 1)
    }
}

/** Una etiqueta «hifi» (letra pequeña de máquina, con borde redondo). */
@Composable
private fun Hifi(sk: Skin, text: String, on: Boolean = false, icon: String? = null, onClick: (() -> Unit)? = null) {
    val p = sk.pal
    var m = Modifier.clip(CircleShape).border(sk.b, if (on) p.accent else p.line2, CircleShape)
    if (onClick != null) m = m.press(onClick = onClick)
    Row(m.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Ico(icon, 12f, if (on) p.accent else p.muted)
        T(text, 10.56f, if (on) p.accent else p.muted, family = Mono, weight = FontWeight.SemiBold, ls = .08f, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NpChips(sk: Skin, t: Track?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (t?.kind != "yt") {
            Hifi(sk, "Letra", Ventanas.lyOn) { Nativo.toast("La letra llega en el siguiente paso de la Órbita nativa.") }
            Hifi(sk, "Visual", Ventanas.vizOn) { Nativo.toast("El visualizador llega en el siguiente paso de la Órbita nativa.") }
        }
        val q = t?.let { Biblio.quality(it) }
        Hifi(sk, if (t != null && t.net) (if (t.kind == "radio") "RADIO EN DIRECTO" else if (t.kind == "yt") "YOUTUBE" else "AVANCE 30 S") else (q?.short ?: "DIRECTO"))
        if (t != null && t.album.isNotEmpty()) Hifi(sk, t.album)
        val left = Player.sleepLeft()
        val mins = if (left != null && left > 0) ((left + 59999) / 60000).toInt() else 0
        Hifi(sk, if (mins > 0) "Para en $mins min" else "Dormir", mins > 0, I_MOON) { Ventanas.open("sleep") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NpBrain(sk: Skin, t: Track?) {
    if (t == null || t.kind == "yt") return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallBtn(sk, "Más como esta", 12.8f) { Nova.moreLike(t) }
        SmallBtn(sk, "No me la pongas más", 12.8f) { Nova.ban(t) }
    }
}

/** Un botón pequeño («btn small»), con el tamaño de letra que se le diga. */
@Composable
internal fun SmallBtn(sk: Skin, text: String, size: Float = 13.6f, primary: Boolean = false, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = sk.pal
    val shape = sk.r(11f)
    var m = Modifier.press(enabled = enabled, onClick = onClick).clip(shape)
    m = if (primary) m.background(p.grad) else m.background(Color.White.copy(alpha = .04f)).border(sk.b, if (on) p.accent else p.line2, shape)
    Box(m.graphicsLayer { alpha = if (enabled) 1f else .5f }.padding(horizontal = 13.dp + sk.b, vertical = 8.dp + sk.b), contentAlignment = Alignment.Center) {
        T(text, size * sk.tk, if (primary) p.onAccent else if (on) p.accent else p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun NpQueue(sk: Skin) {
    val p = sk.pal
    val q = Player.queue; val qi = Player.qi
    @Suppress("UNUSED_VARIABLE") val g = Ventanas.gen
    val up = (qi + 1 until min(q.size, qi + 9)).mapNotNull { i -> Lib.byId(q[i])?.let { it to i } }
    if (up.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("A CONTINUACIÓN", 11.2f, p.muted, family = Mono, weight = FontWeight.SemiBold, ls = .16f, maxLines = 1)
        up.forEachIndexed { k, (t, i) ->
            key(t.id + "|" + i) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shape = sk.r(12f)
                    Row(Modifier.weight(1f).clip(shape).background(Color.Black.copy(alpha = .28f)).border(sk.b, p.line, shape).press { Player.jumpTo(i) }
                        .padding(horizontal = 8.dp + sk.b, vertical = 6.dp + sk.b), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(Player.uiTrack(t), sk, 38f, 8f)
                        Column(Modifier.weight(1f)) {
                            T(t.title, 15f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1)
                            T(t.artist, 12.8f * sk.tk, p.muted, maxLines = 1)
                        }
                    }
                    if (k > 0) Box(Modifier.size(34.dp).press { Player.queueUp(i) }.semantics { contentDescription = "Subir ${t.title}" }, contentAlignment = Alignment.Center) { Ico(I_UP, 16f, p.muted, sw = 2.2f) }
                    Box(Modifier.size(34.dp).press { Player.queueDel(i) }.semantics { contentDescription = "Quitar ${t.title} de la cola" }, contentAlignment = Alignment.Center) { Ico(I_X, 16f, p.muted, sw = 2.2f) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- las ventanas pequeñas (con fondo oscuro)
@Composable
private fun Modal(sk: Skin, label: String, onOutside: () -> Unit, content: @Composable () -> Unit) {
    val p = sk.pal
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(200)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(Color(0xB3040308)).pointerInput(Unit) { detectTapGestures { onOutside() } }.padding(16.dp),
        contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape((20f * sk.round).dp)
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().semantics { contentDescription = label }
            .glow(p.glow, (20f * sk.round).dp, 50.dp, 0.dp, (-18).dp)
            .clip(shape).background(p.panelSolid)
            .border(sk.b, Brush.linearGradient(0f to p.accent, .4f to Color.Transparent, .6f to Color.Transparent, 1f to p.accent2), shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun H2(sk: Skin, text: String) = T(text, 20f * sk.tk, sk.pal.fg, family = sk.display, weight = if (sk.titles == "retro") FontWeight.SemiBold else FontWeight.ExtraBold, ls = -.02f)

@Composable
private fun SleepWindow(sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val g = Ventanas.gen
    val left = Player.sleepLeft(); val on = left != null && left > 0
    val mins = if (on) ((left!! + 59999) / 60000).toInt() else 0
    Modal(sk, "Temporizador para dormir", { Ventanas.gone("sleep") }) {
        H2(sk, "Temporizador para dormir")
        T(if (on) "La música se parará en $mins ${if (mins == 1) "minuto" else "minutos"}. Puedes cambiarlo o desactivarlo." else "La música bajará poco a poco y se parará sola. Nova te dará las buenas noches.", 13.44f * sk.tk, p.muted)
        val opts = listOf("15" to "15 min", "30" to "30 min", "45" to "45 min", "60" to "1 hora", "90" to "1 h 30", "track" to "Esta canción")
        for (row in opts.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, label) in row) Box(Modifier.weight(1f).spot("sleep-$v").clip(CircleShape).background(Color.Black.copy(alpha = .3f)).border(sk.b, p.line2, CircleShape)
                .press { Ventanas.onUi("sleep", JSONObject().put("v", v)) }.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                T(label, 14.08f * sk.tk, p.muted, weight = FontWeight.ExtraBold, maxLines = 1, align = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (on) SmallBtn(sk, "Desactivar") { Ventanas.onUi("sleep", JSONObject().put("v", "off")) }
            SmallBtn(sk, "Cerrar") { Ventanas.gone("sleep") }
        }
    }
}

@Composable
private fun Field(sk: Skin, label: String, value: String, onChange: (String) -> Unit) {
    val p = sk.pal
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        T(label, 13.12f * sk.tk, p.muted, weight = FontWeight.Bold)
        val shape = sk.r(11f)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = .4f)).border(sk.b, p.line2, shape).padding(horizontal = 13.dp, vertical = 11.dp),
            textStyle = style(15f * sk.tk, p.fg), singleLine = true, cursorBrush = SolidColor(p.accent))
    }
}

@Composable
private fun EditWindow(sk: Skin) {
    val p = sk.pal
    val t = Lib.byId(Ventanas.editId) ?: return
    Modal(sk, "Editar canción", { Ventanas.gone("edit") }) {
        H2(sk, "Editar canción")
        Field(sk, "Título", Ventanas.editTitle) { Ventanas.editTitle = it }
        Field(sk, "Artista", Ventanas.editArtist) { Ventanas.editArtist = it }
        Field(sk, "Álbum", Ventanas.editAlbum) { Ventanas.editAlbum = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            @Suppress("UNUSED_VARIABLE") val cg = Ventanas.editCoverGen
            val nb = Ventanas.editCover
            Box(Modifier.size(72.dp).clip(sk.r(12f))) {
                if (Ventanas.editCoverSet && nb != null) {
                    val img = remember(cg) { android.graphics.BitmapFactory.decodeByteArray(nb, 0, nb.size)?.asImageBitmap() }
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else if (Ventanas.editCoverSet) Cover(Player.uiTrack(t).let { UiTrack("none", it.title, it.artist, it.album, it.folder, it.dur, it.added, it.plays, it.last, it.liked, it.demo, it.mine, it.dev, false, it.hue) }, sk, 72f, 12f)
                else Cover(Player.uiTrack(t), sk, 72f, 12f)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallBtn(sk, "Cambiar portada") { Ventanas.pickCover() }
                SmallBtn(sk, "Quitar portada") { Ventanas.editCover = null; Ventanas.editCoverSet = true; Ventanas.editCoverGen++ }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SmallBtn(sk, "Cancelar") { Ventanas.gone("edit") }
            SmallBtn(sk, "Guardar", primary = true) { Ventanas.saveEdit() }
        }
    }
}

// ---------------------------------------------------------------- el aviso de la importación (encima de la barra)
@Composable
private fun ImportPill(sk: Skin, modifier: Modifier) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val g = Ventanas.gen
    val j = Biblio.job ?: return
    val shape = sk.r(14f)
    Row(modifier.padding(bottom = (186f + Bar.inset.toFloat()).dp).widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 16.dp)
        .glow(p.glow, (14f * sk.round).dp, 30.dp, 0.dp, (-12).dp).clip(shape).background(p.panelSolid).border(sk.b, p.line2, shape)
        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(if (j.cancel) "Parando…" else "Importando ${j.done} de ${j.total}" + if (j.skipped > 0) " · ${j.skipped} ya estaban" else "", 13.44f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f))) {
                Box(Modifier.fillMaxWidth(if (j.total > 0) j.done.toFloat() / j.total else 0f).height(6.dp).background(p.grad))
            }
        }
        SmallBtn(sk, "Parar") { Biblio.stopImport() }
    }
}
