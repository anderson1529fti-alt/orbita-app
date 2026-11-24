package __PACKAGE__

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fase 4, parte 2 · Lo que la web le decía al visualizador de Android (qué lienzo se ve, el estilo y sus ajustes, los
 * colores, la portada, si suena y su ritmo) lo dice ahora Android, cada cuarto de segundo. Y el visualizador de «Ahora
 * suena» (con su botón para cambiar de estilo) y el de pantalla completa (girar, acercar y sus ajustes), como en la web.
 */
object VizCtl {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val SCENES = listOf("orbita" to "Órbita", "galaxia" to "Galaxia", "ondas" to "Ondas", "montanas" to "Montañas", "aurora" to "Aurora", "tunel" to "Túnel", "particulas" to "Partículas")
    private val DEF = mapOf<String, Any?>("detail" to "medio", "speed" to 1.0, "sens" to 1.0, "color" to "portada", "hue" to 300.0)

    var ver by mutableIntStateOf(0); private set
    fun scene(): String = Prefs.str("vizScene", "orbita").let { s -> if (SCENES.any { it.first == s }) s else "orbita" }
    fun sceneName(): String = SCENES.firstOrNull { it.first == scene() }?.second ?: "Órbita"
    fun opts(sc: String = scene()): Map<String, Any?> = LinkedHashMap(DEF).apply { putAll(Json.obj(Json.obj(Prefs.get("vizOpts"))?.get(sc)) ?: emptyMap()) }
    private var gen = 0; private var camGen = 0
    fun setOpt(k: String, v: Any?) {
        val all = LinkedHashMap(Json.obj(Prefs.get("vizOpts")) ?: emptyMap()); val sc = scene()
        all[sc] = LinkedHashMap(Json.obj(all[sc]) ?: emptyMap()).apply { put(k, v) }
        Prefs.set("vizOpts", all); gen++; ver++; sync(true)
    }
    fun reset() {
        val all = LinkedHashMap(Json.obj(Prefs.get("vizOpts")) ?: emptyMap()); all.remove(scene()); Prefs.set("vizOpts", all)
        gen++; camGen++; ver++; sync(true)
    }
    fun setScene(sc: String) { Prefs.set("vizScene", sc); ver++; sync(true) }
    fun nextScene() {
        val i = SCENES.indexOfFirst { it.first == scene() }; val nx = SCENES[(i + 1) % SCENES.size]
        setScene(nx.first); Nativo.toast("Visualizador: ${nx.second}")
    }

    /** «setVizNP»: el visualizador en el sitio de la portada de «Ahora suena» (con él, la letra se cierra). */
    fun setNP(on: Boolean) {
        Ventanas.vizOn = on; Prefs.set("vizNP", on)
        if (on && Ventanas.lyOn) { Ventanas.lyOn = false; Prefs.set("lyOn", false) }
        sync(true)
    }
    /** La pantalla completa y lo que se ve en ella. */
    var panel by mutableStateOf(false)
    var chrome by mutableStateOf(false)
    fun openFull() { panel = false; chrome = false; Ventanas.open("viz"); sync(true) }

    /** El lienzo que se ve ahora: «vizFS», «vizNP», «vizHero» o «». */
    fun which(): String = when {
        "viz" in Ventanas.stack -> "vizFS"
        "np" in Ventanas.stack && Ventanas.npOpen && Ventanas.vizOn && Player.current()?.kind != "yt" -> "vizNP"
        UiState.screen == "inicio" && Prefs.str("heroMode", "vinyl") == "viz" && !("np" in Ventanas.stack && Ventanas.npOpen) -> "vizHero"
        else -> ""
    }

    // los colores («glColors»): los de la portada (si se pueden leer) o los del tono de la app
    private val palCache = HashMap<String, List<FloatArray>?>()
    private val palAsked = HashSet<String>()
    private fun hsl(h0: Double, s: Double, l: Double): FloatArray {
        val h = ((h0 % 360) + 360) % 360 / 360; val q = if (l < 0.5) l * (1 + s) else l + s - l * s; val p = 2 * l - q
        fun f(x0: Double): Double { val x = (x0 + 1) % 1; return if (x < 1.0 / 6) p + (q - p) * 6 * x else if (x < 0.5) q else if (x < 2.0 / 3) p + (q - p) * (2.0 / 3 - x) * 6 else p }
        return floatArrayOf(f(h + 1.0 / 3).toFloat(), f(h).toFloat(), f(h - 1.0 / 3).toFloat())
    }
    /** «coverPalette»: los dos colores con más fuerza de la portada (en pequeño, 12 × 12), o nada si es gris. */
    private fun coverPalette(t: Track): List<FloatArray>? {
        val f = (if (t.net) Online.cachedImage(t.art.ifEmpty { t.artBig }) else Lib.coverFile(t)) ?: return null
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.absolutePath, o)
        var sm = 1; while (o.outWidth / (sm * 2) >= 48 && o.outHeight / (sm * 2) >= 48) sm *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = sm
        val b0 = BitmapFactory.decodeFile(f.absolutePath, o2) ?: return null
        val b = android.graphics.Bitmap.createScaledBitmap(b0, 12, 12, true)
        class C(val r: Int, val g: Int, val b: Int, val h: Double, val w: Double)
        val cs = ArrayList<C>()
        for (y in 0 until 12) for (x in 0 until 12) {
            val px = b.getPixel(x, y); val r = (px shr 16) and 255; val g = (px shr 8) and 255; val bb = px and 255
            val mx = max(r, max(g, bb)); val mn = min(r, min(g, bb)); val sat = if (mx > 0) (mx - mn).toDouble() / mx else 0.0
            var h = 0.0; if (mx != mn) { h = if (mx == r) (g - bb).toDouble() / (mx - mn) else if (mx == g) 2 + (bb - r).toDouble() / (mx - mn) else 4 + (r - g).toDouble() / (mx - mn); h = (h * 60 + 360) % 360 }
            cs.add(C(r, g, bb, h, sat * (mx / 255.0) * (if (mx > 40) 1.0 else 0.2)))
        }
        cs.sortByDescending { it.w }
        val c1 = cs[0]; val c2 = cs.firstOrNull { min(abs(it.h - c1.h), 360 - abs(it.h - c1.h)) > 35 && it.w > c1.w * 0.35 } ?: c1
        if (c1.w <= 0.08) return null
        fun rgb(c: C) = floatArrayOf(min(255, Math.round(c.r * 1.15).toInt()) / 255f, min(255, Math.round(c.g * 1.15).toInt()) / 255f, min(255, Math.round(c.b * 1.15).toInt()) / 255f)
        return listOf(rgb(c1), rgb(c2))
    }
    private fun colors(o: Map<String, Any?>, t: Track?): List<FloatArray> {
        var c: List<FloatArray>? = null
        if (Json.s(o["color"]) == "portada" && t != null) {
            if (palCache.containsKey(t.id)) c = palCache[t.id]
            else if (palAsked.add(t.id)) io.execute { val pal = try { coverPalette(t) } catch (e: Throwable) { null }; main.post { palCache[t.id] = pal; if (palCache.size > 60) palCache.keys.firstOrNull()?.let { palCache.remove(it) }; sync(true) } }
        }
        if (c == null) { val h = if (Json.s(o["color"]) == "libre") Json.d(o["hue"], 300.0) else UiState.look.h.toDouble(); c = listOf(hsl(h, 0.92, 0.6), hsl(h - 55, 0.9, 0.58)) }
        val c3 = FloatArray(3) { i -> min(1f, (c[0][i] + c[1][i]) * 0.5f * 0.6f + 0.45f) }
        return listOf(c[0], c[1], c3)
    }

    private var lastKey = ""
    /** «vizNatTick»: lo de ahora, al visualizador de Android (solo si ha cambiado algo). */
    fun sync(force: Boolean = false) {
        try {
            val which = which()
            val t = Player.current(); val playing = t != null && Player.playing
            val o = opts(); val lite = Nativo.lite()
            val an = if (t != null && !t.net) Analizador.get(t) else null
            val pal = colors(o, t)
            val v = JSONObject().put("active", which).put("scene", scene()).put("detail", Json.s(o["detail"])).put("speed", Json.d(o["speed"], 1.0)).put("sens", Json.d(o["sens"], 1.0))
                .put("pal", JSONArray(pal.map { a -> JSONArray(a.map { x -> Math.round(x * 10000) / 10000.0 }) }))
                .put("bpm", if (an != null && an.bpm > 0 && an.conf >= 2) an.bpm else 0.0)
                .put("art", if (t != null && (Lib.coverFile(t) != null || (t.net && t.art.isNotEmpty()))) t.id else "")
                .put("play", playing).put("own", playing && Player.mode == 0).put("gl", !lite).put("lite", lite)
                .put("slow", lite || t == null || !playing).put("hue", UiState.look.h.toDouble()).put("sat", UiState.look.s.toDouble()).put("gen", gen).put("camGen", camGen)
            val k = v.toString()
            if (k == lastKey && !force) return
            lastKey = k
            Viz.set(v)
        } catch (e: Throwable) { Viz.err = "ajustes: " + e.toString().take(120) }
    }
}

// ================================================================ el visualizador de «Ahora suena»
@Composable
internal fun NpViz(sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val v = VizCtl.ver
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Color(0xFF06050B)).border(sk.b, p.line2, shape)
        .pointerInput(Unit) { detectTapGestures { VizCtl.openFull() } }) {
        VizBox("vizNP", Modifier.fillMaxSize())
        // (el estilo: tocarlo cambia al siguiente)
        Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.5f)).border(sk.b, p.line2, RoundedCornerShape(999.dp))
            .pointerInput(Unit) { detectTapGestures { VizCtl.nextScene() } }.padding(horizontal = 10.dp, vertical = 5.dp)) {
            T(VizCtl.sceneName().uppercase(), 10.88f * sk.tk, p.fg, family = Mono, ls = 0.08f, maxLines = 1)
        }
    }
}

// ================================================================ a pantalla completa
@Composable
internal fun VizFullWindow(sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val v = VizCtl.ver
    val t = Player.current()
    Box(Modifier.fillMaxSize().background(Color(0xFF06050B)).pointerInput(Unit) {
        detectTapGestures { if (VizCtl.panel) VizCtl.panel = false else VizCtl.chrome = !VizCtl.chrome }
    }) {
        VizBox("vizFS", Modifier.fillMaxSize(), gestures = true)
        // el título y los botones (un toque los esconde o los enseña)
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().graphicsLayer { alpha = if (VizCtl.chrome) 0f else 1f }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = max(22f, Bar.inset.toFloat()).dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                T(t?.title ?: "Órbita", 22.4f * sk.tk, p.fg, family = sk.display, weight = FontWeight.Black, ls = -0.03f, maxLines = 1)
                if (t != null) T(t.artist, 15f * sk.tk, p.muted, maxLines = 1)
            }
            HBtn(VizCtl.sceneName(), sk, kind = 1) { VizCtl.nextScene() }
            HBtn("Ajustes", sk, kind = 1) { VizCtl.panel = !VizCtl.panel }
            HBtn("Cerrar", sk, kind = 1) { Ventanas.close("viz") }
        }
        if (VizCtl.panel) VizPanel(sk, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun VizPanel(sk: Skin, modifier: Modifier) {
    val p = sk.pal
    val o = VizCtl.opts()
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(modifier.padding(end = 12.dp, bottom = (90f + Bar.inset.toFloat()).dp)) {
        Column(Modifier.width(min(360f, maxWidth.value - 24f).dp).heightIn(max = maxHeight * 0.7f).clip(shape).background(Color(0xEB0E0B18)).border(sk.b, p.line2, shape)
            .pointerInput(Unit) { detectTapGestures { } }.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun H(t: String) = T(t.uppercase(), 10.88f * sk.tk, p.muted, Modifier.padding(top = 6.dp), family = Mono, ls = 0.12f)
            H("Visualizador")
            HFlow(6.dp, 6.dp) {
                for ((id, name) in VizCtl.SCENES) {
                    val on = VizCtl.scene() == id
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) p.fg else Color.Transparent).border(sk.b, if (on) p.fg else p.line2, RoundedCornerShape(999.dp))
                        .press { VizCtl.setScene(id) }.padding(horizontal = 12.dp, vertical = 8.dp)) { T(name, 13.6f * sk.tk, if (on) p.bg else p.fg, maxLines = 1) }
                }
            }
            H("Detalle")
            Seg(listOf("bajo" to "Bajo", "medio" to "Medio", "alto" to "Alto"), Json.s(o["detail"]), sk) { VizCtl.setOpt("detail", it) }
            H("Velocidad")
            PlainRange(Json.d(o["speed"], 1.0).toFloat(), 0.3f, 2f, 0.1f, sk) { VizCtl.setOpt("speed", Math.round(it * 10) / 10.0) }
            H("Sensibilidad")
            PlainRange(Json.d(o["sens"], 1.0).toFloat(), 0.5f, 2f, 0.1f, sk) { VizCtl.setOpt("sens", Math.round(it * 10) / 10.0) }
            H("Color")
            Seg(listOf("portada" to "De la portada", "orbita" to "De Órbita", "libre" to "Elegir"), Json.s(o["color"]), sk) { VizCtl.setOpt("color", it) }
            if (Json.s(o["color"]) == "libre") { H("Tono"); PlainRange(Json.d(o["hue"], 300.0).toFloat(), 0f, 359f, 1f, sk) { VizCtl.setOpt("hue", it.roundToInt()) } }
            val gl = !Nativo.lite()
            val note = if (!gl) "Con las animaciones «Ligeras» (o si el móvil no puede) se ven los visualizadores clásicos." else if (VizCtl.scene() in listOf("galaxia", "orbita", "particulas")) "Arrastra para girarlo · pellizca para acercarlo." else ""
            if (note.isNotEmpty()) Note(note, sk)
            Box(Modifier.press(scale = 1f) { VizCtl.reset() }.clip(sk.r(9f)).border(sk.b, p.line2, sk.r(9f)).padding(horizontal = 11.dp + sk.b, vertical = 6.dp + sk.b)) {
                T("Restablecer este visualizador", 12.8f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

/** Un deslizador como los del móvil (sin el estilo de la app: así son los del panel del visualizador en la web). */
@Composable
private fun PlainRange(value: Float, min0: Float, max0: Float, step: Float, sk: Skin, onChange: (Float) -> Unit) {
    val p = sk.pal
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    fun at(x: Float, w: Float): Float { var v = min0 + ((x / max(1f, w)).coerceIn(0f, 1f)) * (max0 - min0); if (step > 0) v = Math.round((v - min0) / step) * step + min0; return v.coerceIn(min0, max0) }
    Canvas(Modifier.fillMaxWidth().height(24.dp).pointerInput(min0, max0) { detectTapGestures { o -> change(at(o.x, size.width.toFloat())) } }
        .pointerInput(min0, max0) { detectHorizontalDragGestures { ch, _ -> ch.consume(); change(at(ch.position.x, size.width.toFloat())) } }) {
        val k = ((cur - min0) / max(0.0001f, max0 - min0)).coerceIn(0f, 1f)
        val cy = size.height / 2; val r = 8.dp.toPx(); val x = r + k * (size.width - 2 * r); val th = 4.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, cy - th / 2), androidx.compose.ui.geometry.Size(size.width, th), androidx.compose.ui.geometry.CornerRadius(th / 2))
        drawRoundRect(p.accent, Offset(0f, cy - th / 2), androidx.compose.ui.geometry.Size(x, th), androidx.compose.ui.geometry.CornerRadius(th / 2))
        drawCircle(p.accent, r, Offset(x, cy))
    }
}
