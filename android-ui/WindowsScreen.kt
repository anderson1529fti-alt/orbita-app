package __PACKAGE__

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 4.8 · Fase 2, último paso: las ventanas (el temporizador, las novedades, editar canción, la letra) y el reproductor a
 * pantalla completa. Cada ventana llega leída entera de la pantalla de siempre, como «Buscar» y «Ajustes», y se dibuja
 * en una capa propia por encima de todo (la pantalla de debajo sigue como está). Del reproductor, lo que se desliza
 * (de arriba abajo) va en una lista; y si se baja con el dedo estando arriba del todo, el de siempre se mueve con él.
 */

/** Una parte que no se mueve: dónde va en la pantalla. */
@Immutable
internal data class WFix(val k: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** La parte que se desliza: dónde está, lo que mide todo lo que lleva, sus partes y por dónde iba en la web. */
@Immutable
internal data class WScroll(val k: Int, val x: Float, val y: Float, val w: Float, val h: Float, val total: Float, val order: List<MPos>, val y0: Float)

@Immutable
internal data class WDef(val id: String, val fixed: List<WFix>, val sc: WScroll?, val pass: Boolean = false)

internal fun wDef(id: String, o: JSONObject): WDef {
    val fixed = ArrayList<WFix>()
    val fa = o.optJSONArray("fixed")
    if (fa != null) for (i in 0 until fa.length()) {
        val a = fa.optJSONArray(i) ?: continue
        fixed.add(WFix(a.optInt(0), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat(), a.optDouble(4, 0.0).toFloat()))
    }
    var sc: WScroll? = null
    val so = o.optJSONObject("sc")
    if (so != null) {
        val order = ArrayList<MPos>()
        val oa = so.optJSONArray("order")
        if (oa != null) for (i in 0 until oa.length()) {
            val a = oa.optJSONArray(i) ?: continue
            order.add(MPos(a.optInt(0), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat(), a.optDouble(4, 0.0).toFloat()))
        }
        sc = WScroll(so.optInt("k"), so.optDouble("x", 0.0).toFloat(), so.optDouble("y", 0.0).toFloat(), so.optDouble("w", 0.0).toFloat(), so.optDouble("h", 0.0).toFloat(),
            so.optDouble("total", 0.0).toFloat(), order, so.optDouble("y0", 0.0).toFloat())
    }
    return WDef(id, fixed, sc, o.optInt("pass") == 1)
}

/** (Para las pruebas) los textos que Android tiene de cada ventana, en el orden en que los dibuja. */
internal fun winTexts(max: Int): List<String> {
    val out = ArrayList<String>()
    fun walk(ns: List<MNode>) { for (n in ns) { if (out.size >= max) return; if (n.e == "t" && n.s.isNotBlank()) out.add(n.s); walk(n.kids) } }
    for (w in UiState.wins) {
        if (out.size >= max) break
        out.add("[" + w.id + "]")
        for (f in w.fixed) walk(UiState.winParts["${w.id}.${f.k}"] ?: emptyList())
        w.sc?.order?.forEach { walk(UiState.winParts["${w.id}.${it.k}"] ?: emptyList()) }
    }
    return out
}

/** Las ventanas que hay, una sobre otra (la última, arriba), y los avisos de la app encima de todo. */
@Composable
fun OrbitaWindows() {
    val sk = rememberMirrorSkin()
    val list = UiState.wins
    CompositionLocalProvider(LocalMSkin provides sk) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = UiState.winAlpha
            // 5.0 · con el videoclip, la capa se pinta aparte: así se le puede abrir el hueco por el que se ve el vídeo
            compositingStrategy = if (UiState.ytHole() != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }.drawWithContent {
            drawContent()
            val h = UiState.ytHole() ?: return@drawWithContent
            val r = UiState.ytSlotR
            drawRoundRect(Color.Black, topLeft = h.topLeft, size = h.size, cornerRadius = CornerRadius(r, r), blendMode = BlendMode.Clear)
        }) {
            for (w in list) key(w.id) { WinView(w) }
            ToastHost(sk, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun WinView(w: WDef) {
    val sc = w.sc
    // 5.3 · las ventanas «de paso» (el resumen del año, la bienvenida, el despertador): lo que se toca le llega a la web tal
    // cual (WinFrame se lo pasa); aquí solo se escribe y se arrastran barras
    CompositionLocalProvider(LocalMPass provides w.pass) {
    // (en las «de paso», lo que lee TalkBack es lo de la web, que es lo que recibe el dedo)
    Layout(modifier = if (w.pass) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize(), content = {
        for (f in w.fixed) key(f.k) {
            // (un toque fuera de los botones le llega a la web en su sitio: lo oscuro de alrededor cierra la ventana)
            val tap = if (w.pass) Modifier else Modifier.pointerInput(f.k) {
                detectTapGestures(onTap = { o -> UiState.send("mAt") { it.put("k", f.k); it.put("x", (o.x / density).toDouble()); it.put("y", (o.y / density).toDouble()) } })
            }
            MGroup(UiState.winParts["${w.id}.${f.k}"] ?: emptyList(), 0f, 0f, f.w, f.h, tap)
        }
        if (sc != null) key("sc") { WinScroller(w.id, sc) }
    }) { ms, cs ->
        val d = density
        val n = ms.size
        val ps = ArrayList<androidx.compose.ui.layout.Placeable>(n)
        val at = ArrayList<IntArray>(n)
        for (i in 0 until n) {
            val r = if (i < w.fixed.size) w.fixed[i].let { floatArrayOf(it.x, it.y, it.w, it.h) } else if (sc != null) floatArrayOf(sc.x, sc.y, sc.w, sc.h) else floatArrayOf(0f, 0f, 0f, 0f)
            val l = (r[0] * d).roundToInt(); val t = (r[1] * d).roundToInt()
            val pw = max(0, ((r[0] + r[2]) * d).roundToInt() - l); val ph = max(0, ((r[1] + r[3]) * d).roundToInt() - t)
            ps.add(ms[i].measure(Constraints.fixed(pw, ph))); at.add(intArrayOf(l, t))
        }
        layout(cs.maxWidth, cs.maxHeight) { for (i in 0 until n) ps[i].place(at[i][0], at[i][1]) }
    }
    }
}

/** Lo que se desliza dentro de una ventana (en el reproductor: la portada, los botones, lo que viene después…). */
@Composable
private fun WinScroller(id: String, sc: WScroll) {
    val ls = UiState.winList(id)
    val dens = LocalDensity.current.density
    // cuando el dedo la suelta, la de siempre se pone a la misma altura (5.0 · con un vídeo en el reproductor, mientras
    // se desliza también: el vídeo de la web va con su sitio)
    LaunchedEffect(id, ls) {
        snapshotFlow { if (ls.isScrollInProgress && UiState.ytDock == null) -1f else UiState.winY(id, dens) }.collect { y ->
            if (y >= 0f) UiState.send("wY") { it.put("id", id); it.put("y", (Math.round(y * 100.0) / 100.0)) }
        }
    }
    var m = Modifier.fillMaxSize()
    if (id == "np") m = m.nestedScroll(rememberNpDrag(ls, dens))
    LazyColumn(state = ls, modifier = m) {
        items(sc.order.size, key = { sc.order[it].k }) { i ->
            val p = sc.order[i]
            val top = if (i == 0) 0f else p.y
            val bottom = if (i + 1 < sc.order.size) sc.order[i + 1].y else max(sc.total, p.y + p.h)
            MPart(p, UiState.winParts["$id.${p.k}"] ?: emptyList(), top, bottom)
        }
    }
}

/**
 * Bajar el reproductor con el dedo (estando arriba del todo): como en la de siempre. Aquí no se dibuja: la capa nativa
 * se hace transparente (pero sigue recibiendo el dedo) y la web mueve su reproductor, que ya sabe hacerlo, con el
 * dedo; al soltarlo, ella decide si se cierra o vuelve arriba (y, quieto otra vez, vuelve el nativo).
 */
@Composable
private fun rememberNpDrag(ls: LazyListState, dens: Float): NestedScrollConnection = remember(ls) {
    object : NestedScrollConnection {
        var on = false
        var dy = 0f
        // (como en la de siempre: solo si el dedo empezó con todo arriba del todo; si se llega arriba a mitad de gesto, no)
        var started = false
        var topAtStart = false
        fun move() { UiState.send("npDrag") { it.put("ph", "m"); it.put("dy", (Math.round(dy * 10.0) / 10.0)) } }
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (on) { dy = max(0f, dy + available.y / dens); move(); return available }
            if (source != NestedScrollSource.UserInput || UiState.npNoDrag) return Offset.Zero
            if (!started) { started = true; topAtStart = !ls.canScrollBackward }
            if (available.y > 0f && topAtStart && !ls.canScrollBackward) {
                on = true; dy = available.y / dens
                UiState.winAlpha = 0f
                UiState.send("npDrag") { it.put("ph", "s") }
                move()
                return available
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            started = false
            if (!on) return Velocity.Zero
            on = false
            UiState.send("npDrag") { it.put("ph", "e"); it.put("v", (available.y / dens / 1000f).toDouble()) }
            UiState.winGone()
            return available
        }
    }
}
