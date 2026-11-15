package __PACKAGE__

import androidx.compose.foundation.background
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
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

/** La cabecera de una pantalla: el título grande y, a su lado (abajo), el dato pequeño. */
@Composable
internal fun PageHead(title: String, meta: String, sk: Skin, extra: @Composable () -> Unit = {}) {
    val p = sk.pal
    HFlow(14.dp, 14.dp, Modifier.fillMaxWidth(), bottom = true) {
        // (la caja mide lo que en la web: el interlineado de 0,95 es menor que la letra)
        Box(Modifier.height((33.6f * 0.95f * sk.tk).dp), contentAlignment = Alignment.CenterStart) {
            T(title.uppercase(), 33.6f * sk.tk, p.fg, Modifier.wrapContentHeight(unbounded = true), family = sk.display, weight = sk.displayWeight, lh = 1.2f, ls = -0.045f, maxLines = 1,
                brush = Brush.horizontalGradient(0.2f to Color.White, 0.8f to p.accent))
        }
        if (meta.isNotEmpty()) T(meta, 14.4f * sk.tk, p.muted, family = Mono, maxLines = 1)
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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
