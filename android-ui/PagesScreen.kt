package __PACKAGE__

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * 4.6 · Fase 2, paso 3: las pantallas que siguen a «Ahora», con las mismas piezas y el mismo mecanismo: la app manda
 * cada parte de su pantalla de siempre (que sigue debajo, invisible) y aquí se dibuja con sus medidas. La primera,
 * «Mis listas».
 */

private const val P_SHUFFLE = "M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"
private const val P_TRASH = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"

@Composable
fun PageScreen(name: String) {
    val sk = rememberSkin()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(UiState.pageScroll(name)).padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            when (name) {
                "listas" -> ListasBody(sk)
                "tu" -> TuBody(sk)
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

/** La cabecera de una pantalla: el título grande y, a su lado (abajo), el dato pequeño. */
@Composable
internal fun PageHead(title: String, meta: String, sk: Skin, grow: Int = -1, extra: @Composable () -> Unit = {}) {
    val p = sk.pal
    HFlow(14.dp, 14.dp, Modifier.fillMaxWidth(), grow = grow, bottom = true) {
        // (la caja mide lo que en la web: el interlineado de 0,95 es menor que la letra)
        Box(Modifier.height((33.6f * 0.95f * sk.tk).dp), contentAlignment = Alignment.CenterStart) {
            T(title.uppercase(), 33.6f * sk.tk, p.fg, Modifier.wrapContentHeight(unbounded = true), family = sk.display, weight = sk.displayWeight, lh = 1.2f, ls = -0.045f, maxLines = 1,
                brush = Brush.horizontalGradient(0.2f to Color.White, 0.8f to p.accent))
        }
        // (en la web el dato está siempre, aunque vacío: cuenta para repartir el sitio)
        if (meta.isNotEmpty()) T(meta, 14.4f * sk.tk, p.muted, family = Mono, maxLines = 1) else Spacer(Modifier)
        extra()
    }
}

/** El recuadro de «todavía no hay nada», con su botón. */
@Composable
internal fun EmptyBox(o: JSONObject?, sk: Skin) {
    val p = sk.pal
    val r = (20f * sk.round).dp
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(r)).dashed(p.line2, r, sk.b).padding(26.dp + sk.b), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(o.str("h"), 18.4f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
        T(o.str("t"), 15f * sk.tk, p.muted)
        val b = o.obj("btn")
        if (b != null) JBtn(b, sk, 0)
    }
}

// ---------------------------------------------------------------- «Mis listas»
@Composable
private fun ListasBody(sk: Skin) {
    val head = UiState.pages["listas.head"]
    val nova = UiState.pages["listas.nova"]
    val lists = UiState.pages["listas.lists"]
    PageHead(head.str("title").ifEmpty { "Mis listas" }, head.str("meta"), sk)
    if (nova != null && !nova.on("off")) {
        // (en la web este bloque deja 8 más por debajo)
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow(nova.str("eye"), sk)
            CardGrid(nova.arr("cards"), sk)
        }
    }
    if (lists != null) {
        val em = lists.obj("empty")
        if (em != null) EmptyBox(em, sk) else CardGrid(lists.arr("cards"), sk)
    }
}

/** Las tarjetas de las listas: una columna en el móvil (dos o más si la pantalla es ancha, como en la web). */
@Composable
private fun CardGrid(cards: List<JSONObject>, sk: Skin) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = maxOf(1, ((maxWidth.value + 16f) / 266f).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (row in cards.chunked(cols)) {
                if (cols == 1) ListCard(row[0], sk, Modifier.fillMaxWidth())
                else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (c in row) ListCard(c, sk, Modifier.weight(1f))
                    for (k in row.size until cols) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Una lista: sus cuatro portadas, el nombre, lo que es, cuántas canciones y sus botones. */
@Composable
private fun ListCard(c: JSONObject, sk: Skin, modifier: Modifier) {
    val p = sk.pal
    Column(modifier.panel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val covers = c.arr("covers")
        // cuatro portadas en dos filas, cuadradas, con 5 de separación
        Layout(content = { for (k in 0 until 4) HCover(covers.getOrNull(k), sk, Modifier, 16.8f, side = 170) }, modifier = Modifier.fillMaxWidth().clip(sk.r(14f))) { ms, cs ->
            val g = 5.dp.roundToPx()
            val w = cs.maxWidth
            val a = (w - g) / 2
            val b = w - g - a
            val ps = ms.mapIndexed { i, m -> val cw = if (i % 2 == 0) a else b; val ch = if (i < 2) a else b; m.measure(Constraints.fixed(cw, ch)) }
            layout(w, w) { ps.forEachIndexed { i, pl -> pl.place(if (i % 2 == 0) 0 else a + g, if (i < 2) 0 else a + g) } }
        }
        val name = c.str("name")
        val desc = c.str("desc")
        if (c.on("wrap")) Column {
            T(name, 16.8f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1.15f, ls = -0.02f)
            if (desc.isNotEmpty()) T(desc, 13.76f * sk.tk, p.muted)
        } else {
            T(name, 16.8f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1.15f, ls = -0.02f)
            if (desc.isNotEmpty()) T(desc, 13.76f * sk.tk, p.muted)
        }
        T(c.str("meta"), 11.52f * sk.tk, p.faint, family = Mono)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val btns = c.arr("btns")
            for (b in btns) {
                val ic = b.str("ic")
                if (ic.isEmpty()) JBtn(b, sk, 1, iconSize = b.num("s", 16f))
                else {
                    // (la papelera va al final de la fila)
                    if (ic == "pl-del") Spacer(Modifier.weight(1f))
                    if (b.on("armed")) {
                        val shape = sk.r(10f)
                        Box(Modifier.height(34.dp).press { tap(b) }.border(sk.b, p.bad, shape).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            T(b.str("t"), 12.8f * sk.tk, p.bad, weight = FontWeight.ExtraBold, maxLines = 1)
                        }
                    } else Box(Modifier.size(34.dp).semantics { contentDescription = b.str("l") }.press(0.85f) { tap(b) }, contentAlignment = Alignment.Center) {
                        Ico(if (ic == "pl-del") P_TRASH else P_SHUFFLE, 16f, p.muted, sw = 2f)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- «Tú»
private const val P_X = "M6 6l12 12M18 6L6 18"
private const val P_PLAY_S = "M7 4.5v15l13-7.5z"
private const val P_TRI = "M8 5l9 7-9 7z"

@Composable
private fun TuBody(sk: Skin) {
    val head = UiState.pages["tu.head"]
    val out = UiState.pages["tu.out"]
    val mem = UiState.pages["tu.mem"]
    val links = UiState.pages["tu.links"]
    PageHead(head.str("title").ifEmpty { "Tú" }, head.str("meta"), sk, grow = 2) {
        Spacer(Modifier)
        SegRow(head.arr("range"), sk)
    }
    if (out != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        for (b in out.arr("blocks")) {
            if (b.str("e") == "grid") for (g in b.arr("items")) TuBlock(g, sk) else TuBlock(b, sk)
        }
    }
    if (mem != null) TuPanel(mem, sk)
    if (links != null) HFlow(12.dp, 12.dp, Modifier.fillMaxWidth()) { for (b in links.arr("b")) JBtn(b, sk, 1) }
}

@Composable
private fun TuBlock(b: JSONObject, sk: Skin) {
    val p = sk.pal
    when (b.str("e")) {
        "empty" -> EmptyBox(b, sk)
        "panel" -> TuPanel(b, sk)
        "recap" -> {
            // (en la web es un panel con resplandor como el de debajo: su borde de dos colores y el mismo fondo)
            Column(Modifier.fillMaxWidth().glowPanel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow(b.str("eye"), sk)
                    T(b.str("h"), 20f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
                    T(b.str("t"), 13.44f * sk.tk, p.muted)
                }
                HFlow(8.dp, 8.dp, Modifier.fillMaxWidth()) { for (x in b.arr("btns")) JBtn(x, sk, if (x.on("sm")) 1 else 0) }
            }
        }
        "hero" -> Column(Modifier.fillMaxWidth().glowPanel(sk).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow(b.str("eye"), sk)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wpx = constraints.maxWidth.toFloat()
                T(b.str("big"), 48f * sk.tk, p.fg, weight = FontWeight.ExtraBold, lh = 1f, ls = -0.03f, maxLines = 1,
                    brush = Brush.horizontalGradient(0.2f to Color.White, 0.8f to p.accent, startX = 0f, endX = wpx))
            }
            HFlow(28.dp, 10.dp, Modifier.fillMaxWidth()) {
                for (st in b.arr("stats")) Column {
                    T(st.str("b"), 24f * sk.tk, p.fg, weight = FontWeight.ExtraBold, lh = 1.1f, maxLines = 1)
                    T(st.str("s"), 12.8f * sk.tk, p.muted, maxLines = 1)
                }
            }
        }
        else -> if (b.str("t").isNotEmpty()) T(b.str("t"), 15f * sk.tk, p.muted)
    }
}

/** Un recuadro de «Tú»: su título y, debajo, listas, notas, gráficas… en el orden en que están en la app. */
@Composable
private fun TuPanel(o: JSONObject, sk: Skin) {
    Column((if (o.on("glow")) Modifier.fillMaxWidth().glowPanel(sk) else Modifier.fillMaxWidth().panel(sk)).padding(18.dp + sk.b), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TuItems(o.arr("items"), sk)
    }
}

@Composable
private fun TuItems(items: List<JSONObject>, sk: Skin) {
    val p = sk.pal
    for (it0 in items) {
        when (it0.str("e")) {
            "h2" -> T(it0.str("t"), 20f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
            "head" -> {
                val tog = it0.obj("tog")
                if (tog != null) HFlow(12.dp, 12.dp, Modifier.fillMaxWidth()) {
                    T(it0.str("t"), 20f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
                    Toggle(tog.str("t"), tog.on("on"), sk) { tap(tog) }
                } else PanelHead(it0.str("t"), it0.str("r"), sk)
            }
            "eye" -> Eyebrow(it0.str("t"), sk)
            "list" -> {
                // (cada fila, del alto exacto que tiene en la web: si se redondea fila a fila, la lista se va alargando)
                val rows = it0.arr("rows")
                if (rows.isNotEmpty() && rows.all { it.num("h", 0f) > 8f }) ExactColumn(6f, rows.map { it.num("h", 0f) }, Modifier.fillMaxWidth()) { for (r in rows) TuRow(r, sk) }
                else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (r in rows) TuRow(r, sk) }
            }
            "facts" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in it0.arr("items")) {
                    if (f.on("note")) T(f.str("t"), 13.44f * sk.tk, p.muted)
                    else Row {
                        // (el punto de la lista, del color del momento)
                        Box(Modifier.width(20.dp).height((21.75f * sk.tk).dp), contentAlignment = Alignment.Center) { Box(Modifier.padding(start = 3.dp).size(5.6.dp).background(p.accent, CircleShape)) }
                        T(f.str("t"), 15f * sk.tk, p.fg, Modifier.weight(1f))
                    }
                }
            }
            "stack" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { TuItems(it0.arr("items"), sk) }
            "btns" -> HFlow(12.dp, 12.dp, Modifier.fillMaxWidth()) { for (b in it0.arr("b")) JBtn(b, sk, if (b.on("sm")) 1 else 0) }
            "chart" -> Chart(it0, sk)
            else -> { val lk = it0.obj("link"); if (lk != null) NoteLink(it0.str("t"), lk, sk) else T(it0.str("t"), 13.44f * sk.tk, p.muted) }
        }
    }
}

/** Título de un recuadro y, a su derecha (o debajo si no cabe), una nota. */
@Composable
private fun PanelHead(title: String, right: String, sk: Skin) {
    if (right.isEmpty()) { T(title, 20f * sk.tk, sk.pal.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f); return }
    Layout(content = {
        T(title, 20f * sk.tk, sk.pal.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f)
        T(right, 13.44f * sk.tk, sk.pal.muted)
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

/** Una fila: una canción (con su número, portada, dato y botón, lo que tenga) o un artista con su barra. */
@Composable
private fun TuRow(r: JSONObject, sk: Skin) {
    val p = sk.pal
    val nw = (20.48f * sk.tk).dp
    if (r.str("e") == "art") {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // (la columna del número mide 1,6 veces la letra de la fila; el número, 1,6 veces la suya, pegado a la izquierda)
            Box(Modifier.width((24f * sk.tk).dp)) { T(r.str("n"), 12.8f * sk.tk, p.faint, Modifier.width(nw), family = Mono, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.End) }
            T(r.str("t"), 15f * sk.tk, p.fg, Modifier.width((r.num("aw", 0f).takeIf { it > 8f } ?: (105f * sk.tk)).dp), weight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.07f))) {
                Box(Modifier.fillMaxWidth(r.num("w", 0f).coerceIn(0f, 100f) / 100f).height(8.dp).background(p.accent, RoundedCornerShape(0.dp, 4.dp, 4.dp, 0.dp)))
            }
            T(r.str("v"), 12.16f * sk.tk, p.muted, family = Mono, maxLines = 1)
        }
        return
    }
    val pad = r.on("pad")
    Row(Modifier.fillMaxWidth().padding(vertical = if (pad) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.on("hasN")) T(r.str("n"), 12.8f * sk.tk, p.faint, Modifier.width(nw), family = Mono, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.End)
        if (r.on("hasC")) HCover(r.obj("c"), sk, Modifier.size(if (pad) 42.dp else 46.dp).clip(sk.r(10f)), 16.8f)
        Column(Modifier.weight(1f)) {
            T(r.str("t"), 15f * sk.tk, if (r.on("play")) p.accent else p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
            if (r.str("s").isNotEmpty()) T(r.str("s"), 13.44f * sk.tk, p.muted, maxLines = 1)
        }
        if (r.str("v").isNotEmpty()) T(r.str("v"), 12.16f * sk.tk, p.muted, family = Mono, maxLines = 1)
        val b = r.obj("btn")
        if (b != null) {
            if (b.on("small")) HBtn(b.str("t"), sk, kind = 1) { tap(b) }
            else Box(Modifier.size(34.dp).semantics { contentDescription = b.str("l") }.press(0.85f) { tap(b) }, contentAlignment = Alignment.Center) {
                if (b.on("x")) Ico(P_X, 14f, p.muted, sw = 2.4f) else Ico(P_PLAY_S, 16f, p.muted, fill = true)
            }
        }
    }
}

/** Un texto con un botón pequeño al final de su último renglón (como en la web, donde va «en línea»). */
@Composable
private fun NoteLink(text: String, link: JSONObject, sk: Skin) {
    val p = sk.pal
    var cut by remember(text) { mutableStateOf(-1) }
    val st = style(13.44f * sk.tk, p.muted)
    val btn: @Composable () -> Unit = {
        val ls = sk.r(9f)
        Box(Modifier.press { tap(link) }.border(sk.b, p.line2, ls).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) { T(link.str("t"), 12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1) }
    }
    if (cut < 0) Column {
        // (primera pasada: dónde empieza el último renglón)
        androidx.compose.foundation.text.BasicText(text, Modifier.lines(13.44f * sk.tk * 1.45f), style = st, onTextLayout = { r -> cut = if (r.lineCount > 1) r.getLineStart(r.lineCount - 1) else 0 })
        btn()
    } else Column {
        if (cut > 0) T(text.substring(0, cut).trimEnd(), 13.44f * sk.tk, p.muted)
        HFlow(3.5.dp, 0.dp, Modifier.fillMaxWidth()) {
            T(text.substring(cut.coerceIn(0, text.length)), 13.44f * sk.tk, p.muted, maxLines = 1)
            btn()
        }
    }
}

/** Una cosa centrada en un punto del ancho (un tanto por ciento), como las etiquetas de las gráficas de la web. */
@Composable
private fun CenterAt(pct: Float, modifier: Modifier = Modifier, above: Boolean = false, clampPx: Float = 0f, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { ms, c ->
        val pl = ms[0].measure(Constraints(0, Constraints.Infinity, 0, Constraints.Infinity))
        var cx = c.maxWidth * pct / 100f
        if (clampPx > 0f) cx = cx.coerceIn(clampPx, maxOf(clampPx, c.maxWidth - clampPx))
        layout(c.maxWidth, if (above) 0 else pl.height) { pl.place((cx - pl.width / 2f).roundToInt(), if (above) -pl.height - 6.dp.roundToPx() else 0) }
    }
}

/** Una gráfica de barras: la más alta resaltada con su valor encima; al tocar una barra, su dato; y debajo, la tabla. */
@Composable
private fun Chart(o: JSONObject, sk: Skin) {
    val p = sk.pal
    val w0 = maxOf(1f, o.num("W", 1f)); val h0 = o.num("H", 96f); val bw = o.num("bw", 16f)
    val bars = o.arr("bars"); val hits = o.arr("hits"); val peak = o.obj("peak"); val sum = o.obj("sum")
    var sel by remember { mutableStateOf(-1) }
    val look = UiState.look
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    val soft = hsl(look.h, look.s * 0.96f, 0.64f, 0.55f)
    Column(Modifier.widthIn(max = o.num("maxW", 560f).dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val hdp = maxWidth.value * h0 / w0
            Box(Modifier.fillMaxWidth().height((22f + hdp).dp)) {
                Canvas(Modifier.padding(top = 22.dp).fillMaxWidth().height(hdp.dp).pointerInput(o) {
                    detectTapGestures { pos ->
                        val x = pos.x / size.width * w0
                        val i = hits.indexOfFirst { x >= it.num("x", 0f) && x < it.num("x", 0f) + it.num("w", 0f) }
                        sel = if (i == sel) -1 else i
                    }
                }) {
                    val k = size.width / w0
                    if (sel >= 0 && sel < hits.size) drawRect(Color.White.copy(alpha = 0.06f), Offset(hits[sel].num("x", 0f) * k, 0f), Size(hits[sel].num("w", 0f) * k, size.height))
                    drawRect(p.line2, Offset(0f, (h0 - 0.5f) * k - sk.b.toPx() / 2), Size(size.width, sk.b.toPx()))
                    for (b in bars) {
                        val x = b.num("x", 0f); val y = b.num("y", 0f); val h = h0 - y
                        val r = min(4f, min(h / 2f, bw / 2f)) * k
                        val path = Path().apply { addRoundRect(RoundRect(x * k, y * k, (x + bw) * k, h0 * k, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r))) }
                        drawPath(path, if (b.on("hi")) p.accent else soft)
                    }
                }
                if (peak != null) CenterAt(peak.num("x", 0f)) { T(peak.str("t"), 11.52f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1) }
                if (sel >= 0 && sel < hits.size) {
                    val hit = hits[sel]
                    val ts = RoundedCornerShape(10.dp)
                    CenterAt((hit.num("x", 0f) + hit.num("w", 0f) / 2f) / w0 * 100f, above = true, clampPx = 40f * dens) {
                        Column(Modifier.glow(Color.Black.copy(alpha = 0.9f), 10.dp, 30.dp, 10.dp, (-10).dp).clip(ts).background(p.panelSolid).border(sk.b, p.line2, ts).padding(horizontal = 10.dp + sk.b, vertical = 6.dp + sk.b)) {
                            T(hit.str("v"), 12.48f * sk.tk, p.fg, weight = FontWeight.ExtraBold, lh = 1.25f, maxLines = 1)
                            T(hit.str("l"), 11.52f * sk.tk, p.muted, lh = 1.25f, maxLines = 1)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            for (x in o.arr("xs")) CenterAt(x.num("x", 0f)) { T(x.str("t"), 10.88f * sk.tk, p.faint, family = Mono, maxLines = 1) }
        }
        if (sum != null) {
            val open = sum.on("open")
            Row(Modifier.press(0.98f) { tap(sum) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width((sum.num("m", 0f).takeIf { it > 2f } ?: 13.2f).dp), contentAlignment = Alignment.CenterStart) { Ico(P_TRI, 10f, p.muted, Modifier.graphicsLayer { rotationZ = if (open) 90f else 0f }, fill = true) }
                T(sum.str("t"), 12.48f * sk.tk, p.muted, maxLines = 1)
            }
            val rows = o.arr2("rows")
            if (open && rows.isNotEmpty()) {
                val th = o.strs("th")
                Row(Modifier.padding(top = 2.dp)) {
                    for (col in 0 until maxOf(th.size, rows[0].size)) Column(Modifier.width(IntrinsicSize.Max)) {
                        val cell: @Composable (String, Boolean) -> Unit = { t, headCell ->
                            Box(Modifier.fillMaxWidth().drawBehind { drawRect(p.line, Offset(0f, size.height - sk.b.toPx()), Size(size.width, sk.b.toPx())) }.padding(end = 14.dp, top = 3.dp, bottom = 3.dp + sk.b)) {
                                T(t, 12.8f * sk.tk, if (headCell) p.muted else p.fg, weight = if (headCell) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                            }
                        }
                        cell(th.getOrNull(col) ?: "", true)
                        for (r in rows) cell(r.getOrNull(col) ?: "", false)
                    }
                }
            }
        }
    }
}
