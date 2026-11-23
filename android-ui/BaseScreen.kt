package __PACKAGE__

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 5.3 · Fase 4, parte 1: todo lo que se ve, de Android. Esta es la capa de abajo del todo:
 * · el fondo de la app (la aurora que se mueve despacio, o quieta, o la portada de lo que suena, o negro puro), dibujado
 *   aquí con los mismos degradados, el mismo movimiento (al mismo punto que el de la web) y el mismo grano;
 * · la barra de abajo (la canción que suena y las pestañas), leída de la de siempre como las pantallas.
 * Lo que se toca en la barra le llega a la web tal cual (ella ya sabe qué hacer: también subir el reproductor con el
 * dedo); solo la barra de la canción se arrastra aquí, para que vaya pegada al dedo.
 */
internal object Base {
    var on by mutableStateOf(false)
    var fixed by mutableStateOf<List<WFix>>(emptyList())
    val parts = mutableStateMapOf<Int, List<MNode>>()
    var bg by mutableStateOf(Bg())
    // (para las pruebas y el diagnóstico)
    @Volatile var syncs = 0
    @Volatile var frames = 0
    @Volatile var drawn = ""
    @Volatile var noiseMs = -1L
    @Volatile var live = false
}

/** El fondo: su tono, cómo es, la portada (si va con portada) y por dónde va su movimiento. */
@Immutable
internal data class Bg(
    val h: Float = 6f, val s: Float = 1f, val mode: String = "aurora", val base: Int = 0xFF06050B.toInt(),
    val cover: String = "", val moving: Boolean = false, val t: Double = 0.0, val at: Long = 0L, val fade: Boolean = true
)

@Composable
fun OrbitaBase() {
    val sk = rememberMirrorSkin()
    val dens = LocalDensity.current.density
    val bg = Base.bg
    val noise = rememberNoise(dens)
    // la portada de fondo (si el fondo va con portada)
    val wantCover = bg.mode == "portada" && bg.cover.isNotEmpty()
    val coverBmp = if (wantCover) UiState.covers["${bg.cover}|L"] else null
    if (wantCover && coverBmp == null) LaunchedEffect(bg.cover) { needArt(bg.cover, 400, dens) }
    var gaveUp by remember { mutableStateOf("") }
    if (wantCover && coverBmp == null) LaunchedEffect(bg.cover) { kotlinx.coroutines.delay(1500); gaveUp = bg.cover }
    // (la primera vez, no se ve nada hasta tenerlo todo, el grano y la portada: mientras, debajo se ve el de la web, que
    //  es igual; después ya no se deshace: al irse y volver, está al momento; si cambia la canción, se queda la portada
    //  de antes hasta tener la nueva)
    var live by remember { mutableStateOf(false) }
    val on = Base.on
    if (on && !live && noise != null && (!wantCover || coverBmp != null || gaveUp == bg.cover)) live = true
    Base.live = live && on
    // (ya se ve: la web guarda el suyo, que ya no hace falta que pinte; se le dice cada vez que vuelve a verse)
    val shown = live && on
    LaunchedEffect(shown) { if (shown) UiState.send("baseLive") { } }
    if (!live) return
    CompositionLocalProvider(LocalMSkin provides sk, LocalMPass provides true) {
        // (lo que se toca aquí le llega a la web: lo que lee TalkBack, también lo de la web)
        Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            Backdrop(bg, noise, if (bg.mode == "portada") coverBmp else null, wantCover)
            BaseBar()
        }
    }
}

/** (Para las pruebas) los textos que Android tiene de la barra de abajo, en el orden en que los dibuja. */
internal fun baseTexts(max: Int): List<String> {
    val out = ArrayList<String>()
    fun walk(ns: List<MNode>) { for (n in ns) { if (out.size >= max) return; if ((n.e == "t" || n.e == "ph") && n.s.isNotBlank()) out.add(n.s); walk(n.kids) } }
    for (f in Base.fixed) walk(Base.parts[f.k] ?: emptyList())
    return out
}

/** La barra de abajo: cada parte (la canción, los botones, la barra del tiempo, las pestañas) en su sitio exacto. */
@Composable
private fun BaseBar() {
    val fixed = Base.fixed
    Layout(content = {
        for (f in fixed) key(f.k) { MGroup(Base.parts[f.k] ?: emptyList(), 0f, 0f, f.w, f.h, Modifier) }
    }, modifier = Modifier.fillMaxSize()) { ms, cs ->
        val d = density
        val n = min(ms.size, fixed.size)
        val ps = ArrayList<androidx.compose.ui.layout.Placeable>(n)
        val at = ArrayList<IntArray>(n)
        for (i in 0 until n) {
            val r = fixed[i]
            val l = (r.x * d).roundToInt(); val t = (r.y * d).roundToInt()
            val pw = max(0, ((r.x + r.w) * d).roundToInt() - l); val ph = max(0, ((r.y + r.h) * d).roundToInt() - t)
            ps.add(ms[i].measure(Constraints.fixed(pw, ph))); at.add(intArrayOf(l, t))
        }
        layout(cs.maxWidth, cs.maxHeight) { for (i in 0 until n) ps[i].place(at[i][0], at[i][1]) }
    }
}

// ---------------------------------------------------------------- el fondo
/** hsla de CSS (tono en grados, saturación y luz de 0 a 1) como color. */
private fun hslaC(h: Float, s: Float, l: Float, a: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f / 360f
    val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
    fun f(n: Float): Float { val k = (n + hh * 12f) % 12f; val q = ss * min(ll, 1f - ll); return ll - q * max(-1f, min(min(k - 3f, 9f - k), 1f)) }
    val r = (f(0f) * 255f).roundToInt(); val g = (f(8f) * 255f).roundToInt(); val b = (f(4f) * 255f).roundToInt()
    return ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
}

/** Las cuatro luces de la aurora: [cx, cy, rx, ry] en partes de su caja, y el color (tono relativo, saturación, luz, alfa). */
private val AURORA = arrayOf(
    floatArrayOf(.18f, .22f, .38f, .34f, 0f, .96f, .55f, .36f),
    floatArrayOf(.86f, .18f, .34f, .40f, -45f, .92f, .56f, .30f),
    floatArrayOf(.62f, .98f, .46f, .38f, 150f, .85f, .48f, .20f),
    floatArrayOf(.40f, .60f, .30f, .30f, -90f, .90f, .40f, .16f)
)

/** «ease-in-out» de CSS (cubic-bezier(.42, 0, .58, 1)). */
private fun easeInOut(x: Float): Float {
    if (x <= 0f) return 0f; if (x >= 1f) return 1f
    var lo = 0f; var hi = 1f; var t = x
    repeat(24) {
        val bx = 3f * (1f - t) * (1f - t) * t * .42f + 3f * (1f - t) * t * t * .58f + t * t * t
        if (bx < x) lo = t else hi = t
        t = (lo + hi) / 2f
    }
    return 3f * (1f - t) * t * t + t * t * t
}

/** Por dónde va el movimiento de la aurora (46 s de ida y otros tantos de vuelta), de 0 a 1. */
internal fun driftAt(bg: Bg, now: Long): Float {
    val ms = bg.t + if (bg.moving) (now - bg.at).toDouble() else 0.0
    val it = floor(ms / 46000.0)
    var f = ((ms - it * 46000.0) / 46000.0).toFloat()
    if (it.toLong() % 2L != 0L) f = 1f - f
    return easeInOut(f)
}

@Composable
private fun Backdrop(bg: Bg, noise: Bitmap?, coverBmp: androidx.compose.ui.graphics.ImageBitmap?, wantCover: Boolean) {
    val dens = LocalDensity.current.density
    var tick by remember { mutableStateOf(0L) }
    // (se mueve en cada fotograma solo mientras se ve: guardada, no gasta)
    val on = Base.on
    if (bg.moving && bg.mode != "negro" && on) LaunchedEffect(bg) { while (true) withFrameNanos { tick = it } }
    // la portada: al cambiar de canción, la nueva aparece encima de la de antes (0,9 s, como el «background-image» de
    // la web); al poner o quitar el fondo con portada, aparece o se va (su «opacity», 0,9 s); si la nueva aún no ha
    // llegado, se queda la de antes
    var cur by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var prev by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    val first = remember { BooleanArray(1) { true } }
    val target = if (bg.mode == "portada") (coverBmp ?: if (wantCover) cur else null) else null
    LaunchedEffect(target) {
        if (target === cur) return@LaunchedEffect
        if (first[0] || !bg.fade) { prev = null; cur = target; fade.snapTo(1f); return@LaunchedEffect }
        prev = cur; cur = target; fade.snapTo(0f)
        fade.animateTo(1f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.CubicBezierEasing(.25f, .1f, .25f, 1f)))
        prev = null
    }
    LaunchedEffect(Unit) { first[0] = false }
    Box(Modifier.fillMaxSize()) {
        // el color de debajo
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(bg.base))
            if (bg.mode == "negro") Base.drawn = "negro"
        }
        if (bg.mode != "negro") {
            // la aurora: su caja es un 25 % más grande por cada lado; se pinta UNA vez en su propia capa y lo que se mueve
            // (se desplaza, gira y crece desde su centro) es la capa entera, como hace la web: moverla no cuesta casi nada.
            // (la capa, a la mitad de tamaño y estirada al doble: la luz es tan suave que se ve igual, y ocupa un cuarto)
            val auroraAlpha = if (bg.mode == "portada") .35f else 1f
            Canvas(
                Modifier
                    .layout { ms, cs ->
                        val w = cs.maxWidth; val h = cs.maxHeight
                        val lw = (w * .75f).roundToInt(); val lh = (h * .75f).roundToInt()
                        val pl = ms.measure(Constraints.fixed(lw, lh))
                        layout(w, h) { pl.place(((w - lw) / 2f).roundToInt(), ((h - lh) / 2f).roundToInt()) }
                    }
                    .graphicsLayer {
                        tick
                        Base.frames++
                        val p = driftAt(bg, SystemClock.uptimeMillis())
                        val bw = size.width * 2f; val bh = size.height * 2f   // (la caja de verdad)
                        translationX = bw * .05f * p; translationY = -bh * .04f * p
                        rotationZ = 10f * p
                        val k = 2f * (1f + .1f * p); scaleX = k; scaleY = k
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
            ) {
                val bw = size.width; val bh = size.height
                drawIntoCanvas { cv ->
                    val nc = cv.nativeCanvas
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG)
                    // (la primera de la lista es la de arriba: se pintan de la última a la primera)
                    for (i in AURORA.indices.reversed()) {
                        val a = AURORA[i]
                        val c = hslaC(bg.h + a[4], bg.s * a[5], a[6], a[7] * auroraAlpha)
                        val c0 = c and 0x00FFFFFF   // (el mismo color, transparente: así se desvanece como en la web)
                        val cx = bw * a[0]; val cy = bh * a[1]; val rx = bw * a[2]; val ry = bh * a[3]
                        val sh = android.graphics.RadialGradient(cx, cy, rx, intArrayOf(c, c0, c0), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
                        val m = android.graphics.Matrix(); m.setScale(1f, ry / rx, cx, cy); sh.setLocalMatrix(m)
                        paint.shader = sh
                        nc.drawRect(0f, 0f, bw, bh, paint)
                    }
                }
                Base.drawn = "${bg.mode}:${bg.h.roundToInt()}:${(bg.s * 100).roundToInt()}:${if (bg.moving) "mueve" else "quieta"}" + if (cur != null) ":portada" else ""
            }
        }
        if (bg.mode != "negro") {
            val pv = prev; val cu = cur
            if (pv != null) CoverBack(pv, if (cu != null) 1f else 1f - fade.value)
            if (cu != null) CoverBack(cu, if (pv != null || fade.value < 1f) fade.value else 1f)
            // el grano, encima de todo (en «Negro puro» la web no tiene fondo: ni grano)
            if (noise != null) Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { cv ->
                    val paint = android.graphics.Paint()
                    paint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                        val k = 180f * dens / noise.width; val m = android.graphics.Matrix(); m.setScale(k, k); it.setLocalMatrix(m)
                    }
                    paint.alpha = (0.05f * 255f).roundToInt()
                    cv.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                }
            }
        }
    }
}

/**
 * La portada de fondo: su caja es un 12 % más grande por cada lado que la pantalla, la imagen la cubre (centrada), y va
 * desenfocada 70 px, con más color y menos luz. Como en la web, el desenfoque se aclara hacia los bordes de esa caja
 * (fuera de ella no hay nada): por eso la caja entera es la capa que se desenfoca.
 */
@Composable
private fun CoverBack(bmp: androidx.compose.ui.graphics.ImageBitmap, alpha: Float) {
    val dens = LocalDensity.current.density
    val cm = remember {
        val m = ColorMatrix(); m.setToSaturation(1.35f)
        val b = .42f
        val br = ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        m.timesAssign(br); m
    }
    var mod = Modifier.layout { ms, cs ->
        val w = cs.maxWidth; val h = cs.maxHeight
        val bw = (w * 1.24f).roundToInt(); val bh = (h * 1.24f).roundToInt()
        val pl = ms.measure(Constraints.fixed(bw, bh))
        layout(w, h) { pl.place(-((bw - w) / 2f).roundToInt(), -((bh - h) / 2f).roundToInt()) }
    }
    if (Build.VERSION.SDK_INT >= 31) mod = mod.graphicsLayer {
        // (el desenfoque de CSS es una gaussiana de desviación 70 px; Android pide el «radio»: desviación = 0,57735 · radio + 0,5)
        val r = max(1f, (70f * dens - 0.5f) / 0.57735f)
        renderEffect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL).asComposeRenderEffect()
        this.alpha = alpha
    } else mod = mod.graphicsLayer { this.alpha = alpha }
    Canvas(mod) {
        val bw = size.width; val bh = size.height
        // «cover»: la imagen llena la caja, centrada
        val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
        val k = max(bw / iw, bh / ih)
        val dw = iw * k; val dh = ih * k
        drawIntoCanvas { cv ->
            val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)
            p.colorFilter = ColorFilter.colorMatrix(cm).asAndroidColorFilter()
            val src = if (Build.VERSION.SDK_INT >= 31) bmp else blurSoft(bmp)
            cv.nativeCanvas.drawBitmap(src.asAndroidBitmap(), null,
                android.graphics.RectF((bw - dw) / 2f, (bh - dh) / 2f, (bw + dw) / 2f, (bh + dh) / 2f), p)
        }
    }
}

/** (Android 11 o antes, sin desenfoque de capa: la imagen en pequeñito y estirada se parece mucho a una muy borrosa) */
private val softCache = HashMap<androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.graphics.ImageBitmap>()
private fun blurSoft(b: androidx.compose.ui.graphics.ImageBitmap): androidx.compose.ui.graphics.ImageBitmap {
    softCache[b]?.let { return it }
    val src = b.asAndroidBitmap()
    val small = Bitmap.createScaledBitmap(src, 6, max(1, (6f * src.height / max(1, src.width)).roundToInt()), true)
    val out = Bitmap.createScaledBitmap(small, 96, max(1, (96f * src.height / max(1, src.width)).roundToInt()), true).asImageBitmap()
    if (softCache.size > 4) softCache.clear()
    softCache[b] = out
    return out
}

// ---------------------------------------------------------------- el grano
/**
 * El grano de la web: un «feTurbulence» de SVG (ruido fractal, frecuencia .85, dos octavas, en baldosas de 180 px que
 * casan), calculado aquí con el algoritmo de la especificación de SVG, en el espacio de color «linearRGB» que usan los
 * filtros y pasado a sRGB. Se hace una vez por pantalla (unos milisegundos, fuera del hilo de la pantalla).
 */
@Composable
private fun rememberNoise(dens: Float): Bitmap? {
    var bmp by remember { mutableStateOf<Bitmap?>(Noise.cached(dens)) }
    if (bmp == null) LaunchedEffect(dens) {
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Noise.make(dens) }
    }
    return bmp
}

internal object Noise {
    private var lastD = 0f
    private var last: Bitmap? = null
    @Synchronized fun cached(d: Float): Bitmap? = if (d == lastD) last else null

    private const val BSIZE = 0x100
    private const val BM = 0xff
    private const val PERLIN_N = 0x1000
    private val lattice = IntArray(BSIZE + BSIZE + 2)
    private val grad = Array(4) { Array(BSIZE + BSIZE + 2) { DoubleArray(2) } }
    private var ready = false

    private fun random(s: Long): Long {
        var r = 16807L * (s % 127773L) - 2836L * (s / 127773L)
        if (r <= 0) r += 2147483647L
        return r
    }
    private fun init(seed0: Long) {
        var seed = seed0
        if (seed <= 0) seed = -(seed % (2147483647L - 1)) + 1
        if (seed > 2147483647L - 1) seed = 2147483647L - 1
        var i = 0
        for (k in 0 until 4) {
            i = 0
            while (i < BSIZE) {
                lattice[i] = i
                for (j in 0 until 2) { seed = random(seed); grad[k][i][j] = ((seed % (BSIZE + BSIZE)) - BSIZE).toDouble() / BSIZE }
                val s = sqrt(grad[k][i][0] * grad[k][i][0] + grad[k][i][1] * grad[k][i][1])
                grad[k][i][0] /= s; grad[k][i][1] /= s
                i++
            }
        }
        while (--i > 0) {
            val k = lattice[i]
            seed = random(seed)
            val j = (seed % BSIZE).toInt()
            lattice[i] = lattice[j]; lattice[j] = k
        }
        for (ii in 0 until BSIZE + 2) {
            lattice[BSIZE + ii] = lattice[ii]
            for (k in 0 until 4) for (j in 0 until 2) grad[k][BSIZE + ii][j] = grad[k][ii][j]
        }
        ready = true
    }
    private class Stitch(var w: Int, var h: Int, var wx: Int, var wy: Int)
    private fun sCurve(t: Double) = t * t * (3.0 - 2.0 * t)
    private fun noise2(ch: Int, x: Double, y: Double, st: Stitch?): Double {
        var t = x + PERLIN_N
        var bx0 = t.toInt(); var bx1 = bx0 + 1
        val rx0 = t - t.toInt(); val rx1 = rx0 - 1.0
        t = y + PERLIN_N
        var by0 = t.toInt(); var by1 = by0 + 1
        val ry0 = t - t.toInt(); val ry1 = ry0 - 1.0
        if (st != null) {
            if (bx0 >= st.wx) bx0 -= st.w
            if (bx1 >= st.wx) bx1 -= st.w
            if (by0 >= st.wy) by0 -= st.h
            if (by1 >= st.wy) by1 -= st.h
        }
        bx0 = bx0 and BM; bx1 = bx1 and BM; by0 = by0 and BM; by1 = by1 and BM   // (después de casar las baldosas, como en la especificación)
        val i = lattice[bx0]; val j = lattice[bx1]
        val b00 = lattice[i + by0]; val b10 = lattice[j + by0]; val b01 = lattice[i + by1]; val b11 = lattice[j + by1]
        val sx = sCurve(rx0); val sy = sCurve(ry0)
        var q = grad[ch][b00]; var u = rx0 * q[0] + ry0 * q[1]
        q = grad[ch][b10]; var v = rx1 * q[0] + ry0 * q[1]
        val a = u + sx * (v - u)
        q = grad[ch][b01]; u = rx0 * q[0] + ry1 * q[1]
        q = grad[ch][b11]; v = rx1 * q[0] + ry1 * q[1]
        val b = u + sx * (v - u)
        return a + sy * (b - a)
    }
    private fun turb(ch: Int, px: Double, py: Double, f0: Double, tile: Double, tx: Double): Double {
        // (baldosas que casan: la frecuencia se ajusta al tamaño de la baldosa)
        var f = f0
        val lo = floor(tile * f) / tile; val hi = ceil(tile * f) / tile
        f = if (f / lo < hi / f) lo else hi
        val st = Stitch((tile * f + 0.5).toInt(), (tile * f + 0.5).toInt(), 0, 0)
        st.wx = (tx * f + PERLIN_N + st.w).toInt(); st.wy = (tx * f + PERLIN_N + st.h).toInt()
        var sum = 0.0; var vx = px * f; var vy = py * f; var ratio = 1.0
        for (o in 0 until 2) {
            sum += noise2(ch, vx, vy, st) / ratio
            vx *= 2; vy *= 2; ratio *= 2
            st.w *= 2; st.wx = 2 * st.wx - PERLIN_N; st.h *= 2; st.wy = 2 * st.wy - PERLIN_N
        }
        return sum
    }
    private fun toSrgb(c: Double): Int {
        val v = c.coerceIn(0.0, 1.0)
        val s = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    @Synchronized fun make(d: Float): Bitmap {
        cached(d)?.let { return it }
        val t0 = SystemClock.uptimeMillis()
        if (!ready) init(0L)
        val n = max(8, (180f * d).roundToInt())
        val px = IntArray(n * n)
        val k = 180.0 / n
        // (la región del filtro es la del dibujo con un 10 % más por cada lado: 216 px desde −18; y el navegador toma cada
        //  punto medio px más adentro; así, medido contra Chrome, sale el mismo grano, punto por punto)
        for (y in 0 until n) for (x in 0 until n) {
            val ux = (x + 0.5) * k + 0.5; val uy = (y + 0.5) * k + 0.5
            // ruido fractal: (suma · 255 + 255) / 2, en cada canal (rojo, verde, azul y transparencia)
            val r = ((turb(0, ux, uy, .85, 216.0, -18.0) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            val g = ((turb(1, ux, uy, .85, 216.0, -18.0) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            val b = ((turb(2, ux, uy, .85, 216.0, -18.0) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            val a = ((turb(3, ux, uy, .85, 216.0, -18.0) + 1.0) / 2.0).coerceIn(0.0, 1.0)
            px[y * n + x] = ((a * 255.0).roundToInt() shl 24) or (toSrgb(r) shl 16) or (toSrgb(g) shl 8) or toSrgb(b)
        }
        val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        last = bmp; lastD = d
        Base.noiseMs = SystemClock.uptimeMillis() - t0
        return bmp
    }
}
