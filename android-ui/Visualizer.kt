package __PACKAGE__

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 4.9 · Fase 3, paso 2: los visualizadores, dibujados por Android.
 *
 * Los mismos siete estilos que la web, con los mismos dibujos para la tarjeta gráfica (los «shaders» se copian tal cual
 * de la web: OpenGL ES 2 entiende lo mismo que WebGL) y las mismas cuentas, pero oyendo la música directamente del
 * reproductor de Kotlin (NovaCore), sin pasar por la web. La web solo dice qué estilo, sus ajustes, los colores, la
 * portada y qué lienzo se ve ahora («Ahora», el reproductor o la pantalla completa). Con las animaciones «Ligeras» (o si
 * el móvil no puede con la tarjeta gráfica) se ven los tres clásicos, dibujados como en la web.
 */

/** Números al azar, pero siempre los mismos (como en la web): las estrellas de la galaxia salen igual en las dos. */
internal class VzRng(seed: Int) {
    private var a = seed
    fun next(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}

/** (Pruebas) todo quieto y conocido, para comparar con la web píxel a píxel. */
internal class VizFreeze(
    val t: Float, val spec: FloatArray, val bass: Float, val mid: Float, val high: Float, val level: Float, val beatK: Float,
    val k: Float, val s: Float, val rot: Float, val travel: Float, val wave: IntArray?,
    val yaw: Float, val pitch: Float, val zoom: Float, val dx: Float, val dy: Float, val histT: Float
)

internal object Viz {
    // ------------------------------------------------------------ lo que dice la web
    /** El lienzo que se ve ahora: «vizHero» (Ahora), «vizNP» (el reproductor), «vizFS» (pantalla completa) o «». */
    @Volatile var active = ""
    @Volatile var scene = "orbita"
    @Volatile var detail = "medio"
    @Volatile var speed = 1f
    @Volatile var sens = 1f
    @Volatile var pal: Array<FloatArray> = arrayOf(floatArrayOf(0.8f, 0.3f, 0.9f), floatArrayOf(0.5f, 0.3f, 0.9f), floatArrayOf(0.84f, 0.63f, 0.99f))
    @Volatile var bpm = 0f
    /** La portada de la que suena (la misma clave que el disco de «Ahora»). */
    @Volatile var art = ""
    @Volatile var play = false
    /** Suena por el reproductor de Kotlin (se puede oír); si no (radio, vídeo…), se mueve solo, como en la web. */
    @Volatile var own = false
    @Volatile var gl = true
    @Volatile var lite = false
    @Volatile var slow = false
    @Volatile var hue = 300f
    @Volatile var sat = 1f
    /** «Restablecer» y los cambios de detalle: los puntos se vuelven a hacer. */
    @Volatile var gen = 0
    @Volatile var camGen = 0
    // la cámara (pantalla completa: arrastrar para girar, pellizcar para acercar)
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.5f
    @Volatile var zoom = 1f
    @Volatile var drag = false
    @Volatile var dx = 0f
    @Volatile var dy = 0f
    @Volatile var freeze: VizFreeze? = null
    /** Los clásicos (2D): con «Ligeras», sin tarjeta gráfica o si la de Android falló (la vista se cambia entera). */
    var two by mutableStateOf(false)
    @Volatile var glBroken = false
    // (para las pruebas y el diagnóstico)
    @Volatile var frames = 0L
    @Volatile var drawn = ""
    @Volatile var err = ""
    /** El último fotograma tenía la portada (para el planeta y las partículas). */
    @Volatile var coverOk = false
    /** Lo que va pasando se toca de uno en uno (un momento puede haber dos vistas del mismo lienzo). */
    val lock = Any()

    private val main = Handler(Looper.getMainLooper())

    fun set(o: JSONObject) {
        if (o.has("active")) active = o.optString("active", "")
        if (o.has("scene")) scene = o.optString("scene", "orbita")
        if (o.has("detail")) detail = o.optString("detail", "medio")
        if (o.has("speed")) speed = o.optDouble("speed", 1.0).toFloat()
        if (o.has("sens")) sens = o.optDouble("sens", 1.0).toFloat()
        if (o.has("bpm")) bpm = o.optDouble("bpm", 0.0).toFloat()
        if (o.has("art")) art = o.optString("art", "")
        if (o.has("play")) play = o.optBoolean("play", false)
        if (o.has("own")) own = o.optBoolean("own", false)
        if (o.has("lite")) lite = o.optBoolean("lite", false)
        if (o.has("slow")) slow = o.optBoolean("slow", false)
        if (o.has("hue")) hue = o.optDouble("hue", 300.0).toFloat()
        if (o.has("sat")) sat = o.optDouble("sat", 1.0).toFloat()
        if (o.has("gen")) gen = o.optInt("gen", 0)
        if (o.has("camGen")) { val g = o.optInt("camGen", 0); if (g != camGen) { camGen = g; zoom = 1f; pitch = 0.5f } }
        val p = o.optJSONArray("pal")
        if (p != null && p.length() >= 3) {
            val c = Array(3) { i -> val a = p.optJSONArray(i); FloatArray(3) { j -> (a?.optDouble(j, 0.5) ?: 0.5).toFloat() } }
            pal = c
        }
        if (o.has("gl")) {
            gl = o.optBoolean("gl", true)
            val two2 = !gl || glBroken
            main.post { if (two != two2) two = two2 }
        }
        if (o.has("freeze")) freeze = if (o.isNull("freeze")) null else parseFreeze(o.getJSONObject("freeze"))
    }

    private fun floats(a: JSONArray?, n: Int, def: Float): FloatArray = FloatArray(n) { i -> (a?.optDouble(i, def.toDouble()) ?: def.toDouble()).toFloat() }

    private fun parseFreeze(o: JSONObject): VizFreeze {
        val w = o.optJSONArray("wave")
        return VizFreeze(
            o.optDouble("t", 0.0).toFloat(), floats(o.optJSONArray("spec"), 96, 0f), o.optDouble("bass", 0.0).toFloat(),
            o.optDouble("mid", 0.0).toFloat(), o.optDouble("high", 0.0).toFloat(), o.optDouble("level", 0.0).toFloat(),
            o.optDouble("beatK", 0.0).toFloat(), o.optDouble("k", 0.0).toFloat(), o.optDouble("s", 0.0).toFloat(),
            o.optDouble("rot", 0.0).toFloat(), o.optDouble("travel", 0.0).toFloat(), if (w != null) IntArray(256) { i -> w.optInt(i, 128) } else null,
            o.optDouble("yaw", 0.6).toFloat(), o.optDouble("pitch", 0.5).toFloat(), o.optDouble("zoom", 1.0).toFloat(),
            o.optDouble("dx", 0.0).toFloat(), o.optDouble("dy", 0.0).toFloat(), o.optDouble("histT", 0.0).toFloat()
        )
    }

    /** La vista de Android no puede con la tarjeta gráfica: los clásicos, como la web cuando le pasa lo mismo. */
    fun brokeGl(why: String) {
        glBroken = true; err = "3D: " + why.take(120)
        main.post { two = true }
    }

    // ------------------------------------------------------------ lo que va pasando (solo el hilo que dibuja lo toca)
    class Frame { var dt = 0.016f; var playing = false; var beat = false }
    private val fr = Frame()
    val spec = FloatArray(96)
    var bass = 0f; var mid = 0f; var high = 0f; var level = 0f
    private var avgBass = 0.2f
    private var lastBeat = 0.0
    var beatK = 0f
    var t = 0f
    private var last = 0.0
    /** La forma del sonido (512 valores, 0…255), o null si no se oye. */
    var wave: IntArray? = null
    private val waveBuf = IntArray(512)
    var bpmNow = 0f
    // los instrumentos (el bombo y la caja), como las luces de la web
    var plK = 0f
    var plS = 0f
    private var plT = 0.0
    private var liveAt = 0.0
    private var kickAt = -1.0
    private var plAvg = 0.4f
    private var plSavg = 0.3f
    private var snAt = 0.0
    // los de la tarjeta gráfica
    var rot = 0f
    var travel = 0f
    var histT = 0f
    val hist = ArrayList<FloatArray>()
    class Shoot(var x: Float, var y: Float, val dx: Float, val dy: Float, var life: Float)
    val shoot = ArrayList<Shoot>()
    var lastSnare = 0.0
    /** La onda para «Ondas» (0…255, suavizada como en la web). */
    val wave256 = IntArray(256)
    // los clásicos
    var rot2 = 0f
    class P2(var a: Float, val d: Float, val s: Float, val z: Float)
    class S2(val d: Float, val arm: Int, val j: Float, val b: Float, val k: Int)
    class W2(var r: Float, var a: Float)
    val parts = ArrayList<P2>()
    val stars = ArrayList<S2>()
    val waves = ArrayList<W2>()
    var rngP = VzRng(4)
    var rngS = VzRng(5)
    private var lastScene = ""

    private fun u(b: Byte) = b.toInt() and 0xff

    /** Una vez por fotograma: lo que suena (como plRead y vizRead de la web) y, con la tarjeta gráfica, lo que gira. */
    fun step(ts: Double, withGl: Boolean): Frame {
        if (scene != lastScene) {   // (como vizSetScene: cada estilo empieza de cero)
            lastScene = scene; parts.clear(); stars.clear(); hist.clear(); rngP = VzRng(4); rngS = VzRng(5)
        }
        val fz = freeze
        if (fz != null) {
            for (i in 0 until 96) spec[i] = fz.spec[i]
            bass = fz.bass; mid = fz.mid; high = fz.high; level = fz.level; beatK = fz.beatK; plK = fz.k; plS = fz.s
            t = fz.t; rot = fz.rot; travel = fz.travel; yaw = fz.yaw; pitch = fz.pitch; zoom = fz.zoom; dx = fz.dx; dy = fz.dy
            if (fz.wave != null) for (i in 0 until 256) wave256[i] = fz.wave[i]
            wave = null; bpmNow = 0f
            if (scene == "montanas") {
                hist.clear(); histT = fz.histT
                for (r in 0 until 41) hist.add(FloatArray(64) { i -> min(1f, spec[floor(i * 1.45).toInt()] * sens) })
            }
            fr.dt = 0f; fr.playing = play; fr.beat = false
            return fr
        }
        plRead(ts)
        vizRead(ts)
        if (withGl) tick()
        return fr
    }

    private fun plRead(ts: Double) {
        if (ts - plT < 8) return
        val dt = min(0.06, if (plT > 0) (ts - plT) / 1000 else 0.016).toFloat()
        plT = ts
        val playing = play && own
        var k: Float; var sn: Float; var hit = 0
        if (playing) {
            liveAt = ts
            val sp = NovaCore.lastSpec
            if (sp != null && sp.size >= 776 && SystemClock.uptimeMillis() - NovaCore.lastSpecAt < 500) {
                k = u(sp[768]) / 255f; sn = u(sp[770]) / 255f
                hit = NovaCore.specHits; NovaCore.specHits = 0
            } else if (sp != null && sp.size >= 256) {
                k = max(u(sp[1]), u(sp[2])) / 255f
                var a = 0; for (i in 16 until 54) a += u(sp[i]); sn = a / 38f / 255f
                plAvg += (k - plAvg) * min(1f, dt * 3)
                if (k > plAvg * 1.18f && k > 0.45f && ts - kickAt > 230) hit = hit or 1
                plSavg += (sn - plSavg) * min(1f, dt * 4)
                if (sn > plSavg * 1.3f && sn > 0.32f && ts - snAt > 140) { hit = hit or 2; snAt = ts }
            }
        }
        if (hit and 1 != 0) { plK = 1f; kickAt = ts } else plK *= exp(-dt * 6.5f)
        if (hit and 2 != 0) plS = 1f else plS *= exp(-dt * 9f)
    }

    private fun vizRead(ts: Double) {
        val playing = play
        val raw = (ts - last) / 1000
        val dt = min(0.05, if (raw == 0.0 || raw.isNaN()) 0.016 else raw).toFloat()
        last = ts; t += dt
        val sp = NovaCore.lastSpec
        val real = playing && own && sp != null && sp.size >= 768
        var b = 0f; var m = 0f; var h = 0f; var lv: Float
        if (real) {
            sp!!
            for (i in 0 until 96) {
                val kk = min(255, floor((i / 96.0).pow(1.7) * 256 * 0.72).toInt() + 1)
                val v = u(sp[kk]) / 255f
                spec[i] += (v - spec[i]) * (if (v > spec[i]) 0.6f else 0.18f)
            }
            for (i in 1..3) b += u(sp[i]); b /= 3 * 255f
            for (i in 6..40) m += u(sp[i]); m /= 35 * 255f
            for (i in 60 until 160) h += u(sp[i]); h /= 100 * 255f
            for (i in 0 until 512) waveBuf[i] = u(sp[256 + i])
            wave = waveBuf
            var s = 0.0; var i = 0
            while (i < 512) { val v = (waveBuf[i] - 128) / 128.0; s += v * v; i += 2 }
            lv = min(1.0, sqrt(s / 256) * 2.4).toFloat()
        } else {
            val k = if (playing) 1f else 0.25f
            val tt = t
            for (i in 0 until 96) {
                val v = k * (0.28f + 0.22f * sin(tt * 2.1f + i * 0.31f) * sin(tt * 1.3f + i * 0.07f) + 0.12f * sin(tt * 7f + i))
                spec[i] += (max(0f, v) - spec[i]) * 0.2f
            }
            b = k * (0.35f + 0.25f * max(0f, sin(tt * PI.toFloat() * 2f * 1.8f))); m = k * 0.3f; h = k * 0.22f; lv = k * 0.4f
            wave = null
        }
        fun sm(a: Float, x: Float, up: Float, dn: Float) = a + (x - a) * (if (x > a) up else dn)
        bass = sm(bass, b, 0.55f, 0.12f); mid = sm(mid, m, 0.4f, 0.1f); high = sm(high, h, 0.5f, 0.15f); level = sm(level, lv, 0.4f, 0.08f)
        // el golpe: el bombo que suena ahora (el del reproductor); el ritmo de la canción solo marca el paso
        var beat = false
        val live = playing && own && ts - liveAt < 300
        if (playing && bpm > 0f) bpmNow = bpm
        else {
            avgBass += (bass - avgBass) * 0.04f
            if (!live && playing && bass > avgBass * 1.32f && bass > 0.22f && ts - lastBeat > 260) beat = true
            bpmNow = 0f
        }
        if (live && kickAt == ts) beat = true
        if (beat) { lastBeat = ts; beatK = 1f; waves.add(W2(0f, 0.8f)); if (waves.size > 6) waves.removeAt(0) }
        beatK *= 0.02f.pow(dt)
        fr.dt = dt; fr.playing = playing; fr.beat = beat
    }

    /** Una vez por fotograma (no por lienzo): girar, viajar, las filas del paisaje, la cámara. */
    private fun tick() {
        val sp = speed; val dt = fr.dt
        rot += dt * sp * (0.12f + level * 0.6f + (if (bpmNow > 0f) bpmNow / 700f else 0f))
        travel += dt * sp * (0.35f + level * 1.6f + plK * 0.8f + (if (bpmNow > 0f) bpmNow / 160f else 0f))
        if (!drag) yaw += dt * sp * 0.05f
        if (scene == "montanas") {
            histT += dt * sp * 8
            while (histT >= 1) {
                histT -= 1
                val row = FloatArray(64) { i -> min(1f, spec[floor(i * 1.45).toInt()] * sens) }
                hist.add(0, row); if (hist.size > 41) hist.removeAt(hist.size - 1)
            }
        }
    }
}

// ======================================================================= la tarjeta gráfica (lo mismo que glDraw)
// (generado de la web: los mismos dibujos para la tarjeta gráfica que los visualizadores de la web)
private const val VS_QUAD = """attribute vec2 aXY; varying vec2 vUv; void main(){ vUv = aXY * 0.5 + 0.5; gl_Position = vec4(aXY, 0.0, 1.0); }"""
private const val FS_ONDAS = """precision mediump float;

uniform vec2 uRes; uniform float uT; uniform vec4 uA; uniform vec4 uB; uniform vec3 uC1, uC2, uC3; uniform vec3 uCam;
uniform vec4 uSp[8]; varying vec2 vUv;
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y); }
float sb(int k){ float v = 0.0; for (int j = 0; j < 8; j++) { if (j == k / 4) { int c = k - (k / 4) * 4; vec4 q = uSp[j]; v = c == 0 ? q.x : c == 1 ? q.y : c == 2 ? q.z : q.w; } } return v; }
float spec(float x){ float i = clamp(x, 0.0, 0.999) * 31.0; int k = int(i); return mix(sb(k), sb(k + 1 > 31 ? 31 : k + 1), fract(i)); }
vec3 tone(vec3 c){ return vec3(1.0) - exp(-c * 1.35); }
uniform sampler2D uWave;
void main(){
  vec2 uv = vUv; vec3 col = vec3(0.012, 0.01, 0.025);
  col += mix(uC1, uC2, uv.x) * 0.10 * (0.35 + uA.x) * (1.0 - abs(uv.y - 0.5) * 2.0);
  for (int k = 0; k < 6; k++) {
    float fk = float(k);
    float w = texture2D(uWave, vec2(fract(uv.x * 0.92 + fk * 0.061), 0.5)).r * 2.0 - 1.0;
    float env = sin(uv.x * 3.14159);
    float amp = (0.08 + uA.w * 0.32) * (1.0 - fk * 0.1);
    float y = 0.5 + w * amp * env + sin(uT * (0.5 + fk * 0.13) + fk * 1.7 + uv.x * 2.6) * (0.025 + 0.02 * uA.y) * fk;
    float d = abs(uv.y - y) * uRes.y / 600.0;
    float th = 0.0022 + 0.004 * uB.x + 0.003 * uB.y;
    vec3 c = mix(mix(uC1, uC2, fk / 5.0), uC3, 0.5 + 0.5 * sin(uT * 0.3 + fk));
    col += c * (th / (d + th * 0.6)) * (0.42 - fk * 0.05) * (0.55 + uA.w);
  }
  gl_FragColor = vec4(tone(col), 1.0);
}"""
private const val FS_AURORA = """precision mediump float;

uniform vec2 uRes; uniform float uT; uniform vec4 uA; uniform vec4 uB; uniform vec3 uC1, uC2, uC3; uniform vec3 uCam;
uniform vec4 uSp[8]; varying vec2 vUv;
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y); }
float sb(int k){ float v = 0.0; for (int j = 0; j < 8; j++) { if (j == k / 4) { int c = k - (k / 4) * 4; vec4 q = uSp[j]; v = c == 0 ? q.x : c == 1 ? q.y : c == 2 ? q.z : q.w; } } return v; }
float spec(float x){ float i = clamp(x, 0.0, 0.999) * 31.0; int k = int(i); return mix(sb(k), sb(k + 1 > 31 ? 31 : k + 1), fract(i)); }
vec3 tone(vec3 c){ return vec3(1.0) - exp(-c * 1.35); }

void main(){
  vec2 uv = vUv; float asp = uRes.x / uRes.y;
  vec3 col = mix(vec3(0.01, 0.012, 0.03), vec3(0.03, 0.02, 0.07), uv.y);
  vec2 sg = uv * vec2(asp, 1.0) * 90.0, g = floor(sg); float st = hash(g);
  col += vec3(1.0) * step(0.985, st) * smoothstep(0.12, 0.0, length(fract(sg) - 0.5)) * (0.5 + 0.5 * sin(uT * 2.0 + st * 40.0)) * (0.6 + uA.z);
  for (int k = 0; k < 3; k++) {
    float fk = float(k);
    float x = uv.x * asp * (1.1 + fk * 0.35) + uT * 0.035 * (fk + 1.0) * uCam.z;
    float n = noise(vec2(x * 1.6, uT * 0.12 + fk * 7.0)) * 0.7 + noise(vec2(x * 4.0, uT * 0.2 + fk)) * 0.3;
    float yc = 0.42 + 0.22 * n + 0.08 * fk + 0.06 * spec(fract(x * 0.15));
    float h = uv.y - yc;
    float curtain = h < 0.0 ? exp(-h * h * 900.0) : exp(-h * (4.5 + fk));
    float rays = pow(noise(vec2(x * 16.0, uT * 0.3 + fk * 3.0)), 1.5) * 1.1 + 0.35;
    float k2 = curtain * rays * (0.16 + uA.y * 0.7 + uB.y * 0.4 + uA.x * 0.25);
    vec3 c = mix(uC1, uC3, clamp((uv.y - yc) * 3.0 + 0.4, 0.0, 1.0)); c = mix(c, uC2, fk * 0.4);
    col += c * k2 * (0.75 - fk * 0.15);
  }
  gl_FragColor = vec4(tone(col), 1.0);
}"""
private const val FS_TUNEL = """precision mediump float;

uniform vec2 uRes; uniform float uT; uniform vec4 uA; uniform vec4 uB; uniform vec3 uC1, uC2, uC3; uniform vec3 uCam;
uniform vec4 uSp[8]; varying vec2 vUv;
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y); }
float sb(int k){ float v = 0.0; for (int j = 0; j < 8; j++) { if (j == k / 4) { int c = k - (k / 4) * 4; vec4 q = uSp[j]; v = c == 0 ? q.x : c == 1 ? q.y : c == 2 ? q.z : q.w; } } return v; }
float spec(float x){ float i = clamp(x, 0.0, 0.999) * 31.0; int k = int(i); return mix(sb(k), sb(k + 1 > 31 ? 31 : k + 1), fract(i)); }
vec3 tone(vec3 c){ return vec3(1.0) - exp(-c * 1.35); }

void main(){
  vec2 p = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0) / uCam.z;
  p += vec2(sin(uT * 0.4), cos(uT * 0.3)) * 0.04;
  float r = length(p) + 1e-4, a = atan(p.y, p.x);
  float z = 0.28 / r + uB.w;
  float ex = 8.0 + 260.0 * r * r;   /* the further out, the thinner (seen on screen, all rings look as fine) */
  float rings = pow(0.5 + 0.5 * sin(z * 12.566), ex) + 0.3 * pow(0.5 + 0.5 * sin(z * 12.566 + 3.1416), ex * 1.6);
  float seg = pow(0.5 + 0.5 * sin(a * 8.0 + z * 1.2), 24.0 + 60.0 * r);
  float s = spec(fract(a / 6.2831 + 0.5));
  vec3 c = mix(uC1, uC2, 0.5 + 0.5 * sin(z * 0.6)) * rings * (0.35 + s * 1.4);
  c += uC3 * seg * (0.06 + uA.z * 0.4);
  c *= smoothstep(0.02, 0.4, r);
  c += mix(uC1, vec3(1.0), 0.3) * (0.12 + uB.y * 0.9) * exp(-r * 6.0);
  gl_FragColor = vec4(tone(c * (0.7 + uA.w)), 1.0);
}"""
private const val FS_BACK = """precision mediump float;

uniform vec2 uRes; uniform float uT; uniform vec4 uA; uniform vec4 uB; uniform vec3 uC1, uC2, uC3; uniform vec3 uCam;
uniform vec4 uSp[8]; varying vec2 vUv;
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y); }
float sb(int k){ float v = 0.0; for (int j = 0; j < 8; j++) { if (j == k / 4) { int c = k - (k / 4) * 4; vec4 q = uSp[j]; v = c == 0 ? q.x : c == 1 ? q.y : c == 2 ? q.z : q.w; } } return v; }
float spec(float x){ float i = clamp(x, 0.0, 0.999) * 31.0; int k = int(i); return mix(sb(k), sb(k + 1 > 31 ? 31 : k + 1), fract(i)); }
vec3 tone(vec3 c){ return vec3(1.0) - exp(-c * 1.35); }
uniform sampler2D uCover; uniform float uHasCover, uPlanet, uRot;
void main(){
  vec2 p = (vUv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(p);
  vec3 col = vec3(0.012, 0.01, 0.026) + mix(uC1, uC2, 0.5) * 0.05;
  col += mix(uC1, uC2, 0.4) * (0.08 + uA.x * 0.25 + uB.y * 0.35) * exp(-r * r * 9.0);
  if (uPlanet > 0.5) {
    float R = 0.17 * uCam.z * (1.0 + uA.x * 0.06 + uB.y * 0.05);
    float q = r / R;
    if (q < 1.0) {
      vec3 n = vec3(p / R, sqrt(1.0 - q * q));
      float u = fract(atan(n.x, n.z) / 6.2831 + uRot * 0.05), v = 0.5 - asin(n.y) / 3.14159;
      vec3 tex = uHasCover > 0.5 ? texture2D(uCover, vec2(u, v)).rgb : mix(uC1, uC2, v + 0.2 * sin(u * 12.0));
      float lit = 0.35 + 0.75 * max(0.0, dot(n, normalize(vec3(-0.5, 0.5, 0.7))));
      col = tex * lit + mix(uC1, uC3, 0.5) * pow(1.0 - n.z, 3.0) * 0.8;
    } else col += mix(uC1, uC3, 0.5) * exp(-(q - 1.0) * 9.0) * (0.35 + uB.y * 0.4);
  }
  gl_FragColor = vec4(col, 1.0);
}"""
private const val VS_PTS = """attribute vec4 aP; attribute vec4 aQ;
uniform vec2 uRes; uniform float uT, uRot, uSize, uDpr, uKind; uniform vec4 uA, uB; uniform vec3 uCam, uC1, uC2, uC3; uniform vec4 uSp[8];
varying vec3 vCol; varying float vA;
float band(float x){ int k = int(clamp(x, 0.0, 0.999) * 31.0); float v = 0.0; for (int j = 0; j < 8; j++) { if (j == k / 4) { int c = k - (k / 4) * 4; vec4 q = uSp[j]; v = c == 0 ? q.x : c == 1 ? q.y : c == 2 ? q.z : q.w; } } return v; }
void main(){
  vec3 pos; float s = band(aQ.y); vec3 c; float al;
  if (uKind < 0.5) {                       /* galaxy: arms that turn faster inside than outside */
    float r = aP.x, ang = aP.y + r * 4.6 + uRot * (1.3 - r);
    float rr = r * (1.0 + uB.x * 0.03 + s * 0.04);
    float th = r < 0.25 ? 0.11 * (1.0 - r / 0.25) * sqrt(1.0 - min(1.0, aP.z * aP.z)) : 0.025;
    pos = vec3(cos(ang) * rr, aP.z * th, sin(ang) * rr);
    c = mix(mix(uC1, uC2, r), uC3, aQ.x * 0.5); c = mix(c, vec3(1.0), 0.2 + s * 0.45);
    al = (0.3 + s * 0.9) * (1.0 - r * 0.45) * (1.0 + (uA.x * 1.6 + uB.y) * (1.0 - smoothstep(0.0, 0.35, r)) + uA.z * 1.2 * smoothstep(0.55, 1.0, r));
  } else if (uKind < 1.5) {               /* galaxy clouds (nebulae) */
    float r = aP.x, ang = aP.y + r * 4.6 + uRot * (1.3 - r);
    pos = vec3(cos(ang) * r, aP.z * 0.05, sin(ang) * r);
    c = mix(uC1, uC2, aQ.x); al = 0.028 * (0.5 + uA.w + uB.y * 0.6);
  } else if (uKind < 2.5) {               /* Órbita: rings made of the spectrum, and particles orbiting */
    float rr = aP.x + s * 0.18 * aQ.z, ang = aP.y + uRot * (0.5 / aP.x) * (aQ.w > 0.5 ? -0.7 : 1.0);
    pos = vec3(cos(ang) * rr, aP.z, sin(ang) * rr);
    c = mix(mix(uC1, uC2, aQ.y), uC3, 0.3 + s * 0.7); al = (0.55 + s * 1.2) * (aQ.w > 0.5 ? 0.5 + uA.z : 1.0);
  } else {                                /* the cover in particles: they jump with the music and burst on the kick */
    vec2 g = aP.xy; float d = length(g);
    float z = s * 0.45 * aQ.z + uB.y * 0.35 * aP.w * aQ.z;
    pos = vec3(g.x * (1.0 + uB.y * 0.25 * aP.w), -g.y * (1.0 + uB.y * 0.25 * aP.w), z - d * 0.1);
    c = vec3(aP.z, aQ.x, aQ.w); c = c * (0.75 + s * 0.6); al = 0.85;
  }
  float cy = cos(uCam.x), sy = sin(uCam.x), cp = cos(uCam.y), sp = sin(uCam.y);
  pos = vec3(cy * pos.x + sy * pos.z, pos.y, -sy * pos.x + cy * pos.z);
  pos = vec3(pos.x, cp * pos.y - sp * pos.z, sp * pos.y + cp * pos.z);
  pos.z += 2.4 / uCam.z;
  vec2 sc = pos.xy * 1.7 / pos.z; sc.x *= uRes.y / uRes.x;
  if (uKind > 1.5 && uKind < 2.5) {       /* the planet hides what's behind it */
    vec2 pp = sc; pp.x *= uRes.x / uRes.y; float R = 0.17 * uCam.z * 2.0;
    if (pos.z > 2.4 / uCam.z && length(pp) < R) al = 0.0;
  }
  gl_Position = vec4(sc, 0.0, 1.0);
  float big = uKind > 0.5 && uKind < 1.5 ? 16.0 : 1.0;
  float grow = uKind > 2.5 ? 1.0 + s * 0.6 : 0.6 + aQ.x * 1.4 + s * 1.8;
  gl_PointSize = clamp(uSize * big * grow * uDpr / pos.z, 1.0, 200.0);
  vCol = c; vA = al;
}"""
private const val FS_PTS = """precision mediump float;
uniform float uSolid; varying vec3 vCol; varying float vA; void main(){ vec2 d = gl_PointCoord - 0.5; float a = max(0.0, 1.0 - length(d) * 2.0); if (uSolid > 0.5) gl_FragColor = vec4(vCol, smoothstep(0.0, 0.35, a)); else gl_FragColor = vec4(vCol * a * a * vA, 1.0); }"""
private const val VS_LN = """attribute vec2 aXY; attribute vec4 aC; varying vec4 vC; void main(){ vC = aC; gl_Position = vec4(aXY, 0.0, 1.0); }"""
private const val FS_LN = """precision mediump float;
varying vec4 vC; void main(){ gl_FragColor = vec4(vC.rgb * vC.a, 1.0); }"""

private class VizGl {
    private class Prog(val id: Int) {
        private val us = HashMap<String, Int>()
        private val ats = HashMap<String, Int>()
        fun u(n: String) = us.getOrPut(n) { GLES20.glGetUniformLocation(id, n) }
        fun a(n: String) = ats.getOrPut(n) { GLES20.glGetAttribLocation(id, n) }
    }
    private val P = HashMap<String, Prog>()
    private var quadBuf = 0
    private var lnBuf = 0
    private val bufs = HashMap<String, Int>()
    private val counts = HashMap<String, Int>()
    private var texWave = 0
    private var texCover = 0
    private var built = ""
    private var coverOf: Any? = null
    private var hasCover = false
    private var side = 0
    private var partBase: FloatArray? = null
    private var partKey: Any? = null
    private val s32 = FloatArray(32)
    private val waveBytes: ByteBuffer = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder())
    private var mt: FloatArray? = null
    private var mtEnv: FloatArray? = null
    private var lnFb: FloatBuffer? = null

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw IllegalStateException("shader: " + log) }
        return s
    }

    private fun prog(vs: String, fs: String): Prog {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p))
        return Prog(p)
    }

    private fun fbuf(a: FloatArray, n: Int = a.size): FloatBuffer {
        val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(a, 0, n); b.position(0); return b
    }

    private fun mkTex(): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun init() {
        P["ondas"] = prog(VS_QUAD, FS_ONDAS); P["aurora"] = prog(VS_QUAD, FS_AURORA); P["tunel"] = prog(VS_QUAD, FS_TUNEL)
        P["back"] = prog(VS_QUAD, FS_BACK); P["pts"] = prog(VS_PTS, FS_PTS); P["ln"] = prog(VS_LN, FS_LN)
        val b = IntArray(2); GLES20.glGenBuffers(2, b, 0); quadBuf = b[0]; lnBuf = b[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf)
        val q = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, q.size * 4, fbuf(q), GLES20.GL_STATIC_DRAW)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        texWave = mkTex()
        for (i in 0 until 256) waveBytes.put(i, Viz.wave256[i].toByte())
        waveBytes.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 256, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, waveBytes)
        texCover = mkTex()
        val one = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()); one.put(byteArrayOf(120, 60, 200.toByte(), 255.toByte())); one.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, one)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("init: " + err)
    }

    private fun mk(name: String, a: FloatArray) {
        val id = bufs.getOrPut(name) { val b = IntArray(1); GLES20.glGenBuffers(1, b, 0); b[0] }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.size * 4, fbuf(a), GLES20.GL_STATIC_DRAW)
        counts[name] = a.size / 8
    }

    /** Los puntos de cada estilo (una vez por estilo y detalle), con los mismos números al azar que la web. */
    private fun build(scene: String, detail: String) {
        val mult = if (detail == "bajo") 0.4 else if (detail == "alto") 1.8 else 1.0
        when (scene) {
            "galaxia" -> {
                val r = VzRng(1)
                val n = (16000 * mult).roundToInt(); val a = FloatArray(n * 8)
                for (i in 0 until n) {
                    val arm = floor(r.next() * 3); val core = r.next() < 0.16
                    val rr = if (core) r.next().pow(1.5) * 0.25 else 0.12 + r.next().pow(0.9) * 0.88
                    val spread = if (core) 6.28 else (if (r.next() < 0.75) 0.22 else 0.7) * (0.6 + 0.6 * rr)
                    val j = i * 8
                    a[j] = rr.toFloat(); a[j + 1] = (arm * 2.094 + (r.next() - 0.5) * spread).toFloat()
                    a[j + 2] = ((r.next() + r.next() + r.next() - 1.5) / 1.5).toFloat(); a[j + 3] = r.next().toFloat()
                    a[j + 4] = r.next().pow(3.0).toFloat(); a[j + 5] = min(0.999, rr * 0.85 + r.next() * 0.15).toFloat()
                }
                mk("stars", a)
                val m = (260 * sqrt(mult)).roundToInt(); val b = FloatArray(m * 8)
                for (i in 0 until m) {
                    val j = i * 8; val arm = floor(r.next() * 3); val rr = 0.15 + r.next() * 0.75
                    b[j] = rr.toFloat(); b[j + 1] = (arm * 2.094 + (r.next() - 0.5) * 0.7).toFloat(); b[j + 2] = (r.next() - 0.5).toFloat()
                    b[j + 3] = r.next().toFloat(); b[j + 4] = r.next().toFloat(); b[j + 5] = rr.toFloat()
                }
                mk("neb", b)
            }
            "orbita" -> {
                val r = VzRng(2)
                val n = (14000 * mult).roundToInt(); val a = FloatArray(n * 8)
                val bands = arrayOf(doubleArrayOf(0.62, 0.72), doubleArrayOf(0.76, 0.93), doubleArrayOf(0.97, 1.03))
                for (i in 0 until n) {
                    val j = i * 8; val ring = i < n * 0.85
                    val bd = bands[if (r.next() < 0.3) 0 else if (r.next() < 0.75) 1 else 2]
                    val rr = if (ring) bd[0] + r.next() * (bd[1] - bd[0]) else 1.2 + r.next() * 0.45
                    a[j] = rr.toFloat(); a[j + 1] = (r.next() * 6.2832).toFloat()
                    a[j + 2] = (if (ring) (r.next() - 0.5) * 0.004 else (r.next() - 0.5) * 0.3).toFloat(); a[j + 3] = r.next().toFloat()
                    a[j + 4] = r.next().pow(2.0).toFloat(); a[j + 5] = min(0.999, (rr - 0.64) / 1.0).toFloat()
                    a[j + 6] = if (ring) 1f else 0.3f; a[j + 7] = if (ring) 0f else 1f
                }
                mk("ring", a)
            }
            "particulas" -> {
                val r = VzRng(3)
                val sd = if (detail == "bajo") 70 else if (detail == "alto") 160 else 110
                val a = FloatArray(sd * sd * 8)
                var i = 0
                for (y in 0 until sd) for (x in 0 until sd) {
                    val j = i * 8; val gx = (x.toDouble() / (sd - 1) - 0.5) * 1.3; val gy = (y.toDouble() / (sd - 1) - 0.5) * 1.3
                    a[j] = gx.toFloat(); a[j + 1] = gy.toFloat(); a[j + 2] = 0.5f; a[j + 3] = r.next().toFloat(); a[j + 4] = 0.5f
                    a[j + 5] = min(0.999, hypot(gx, gy) / 0.92).toFloat(); a[j + 6] = (0.3 + r.next() * 0.7).toFloat(); a[j + 7] = 0.5f
                    i++
                }
                side = sd; partBase = a; mk("parts", a.copyOf()); partKey = null
            }
        }
        built = scene + ":" + detail + ":" + Viz.gen
    }

    /** La portada en partículas: cada una con el color de su sitio de la portada. */
    private fun partColors(cover: Bitmap?, coverId: Any?) {
        val key: Any = coverId ?: "none"
        if (partKey == key) return
        val base = partBase ?: return
        partKey = key
        val sd = side; val a = base.copyOf()
        var px: IntArray? = null
        if (cover != null) {
            try { val s = Bitmap.createScaledBitmap(cover, sd, sd, true); px = IntArray(sd * sd); s.getPixels(px, 0, sd, 0, 0, sd, sd); if (s !== cover) s.recycle() } catch (t: Throwable) { px = null }
        }
        for (i in 0 until sd * sd) {
            val j = i * 8
            if (px != null) { val c = px[i]; a[j + 2] = ((c shr 16) and 0xff) / 255f; a[j + 4] = ((c shr 8) and 0xff) / 255f; a[j + 7] = (c and 0xff) / 255f }
            else { val t = i.toFloat() / (sd * sd); val c = Viz.pal[if (t < 0.5f) 0 else 1]; a[j + 2] = c[0]; a[j + 4] = c[1]; a[j + 7] = c[2] }
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufs["parts"] ?: return)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.size * 4, fbuf(a), GLES20.GL_STATIC_DRAW)
    }

    private var W = 1
    private var H = 1
    private var a0 = 0f; private var a1 = 0f; private var a2 = 0f; private var a3 = 0f
    private var kick = 0f; private var snare = 0f

    private fun set(p: Prog) {
        GLES20.glUseProgram(p.id)
        val sc = Viz.scene; val pal = Viz.pal
        GLES20.glUniform2f(p.u("uRes"), W.toFloat(), H.toFloat())
        GLES20.glUniform1f(p.u("uT"), Viz.t * Viz.speed)
        GLES20.glUniform4f(p.u("uA"), a0, a1, a2, a3)
        GLES20.glUniform4f(p.u("uB"), Viz.beatK, kick, snare, Viz.travel)
        GLES20.glUniform3fv(p.u("uC1"), 1, pal[0], 0); GLES20.glUniform3fv(p.u("uC2"), 1, pal[1], 0); GLES20.glUniform3fv(p.u("uC3"), 1, pal[2], 0)
        if (sc == "particulas") GLES20.glUniform3f(p.u("uCam"), Viz.dx + 0.32f * sin(Viz.t * 0.25f * Viz.speed), Viz.dy + 0.12f * sin(Viz.t * 0.2f * Viz.speed), Viz.zoom)
        else GLES20.glUniform3f(p.u("uCam"), Viz.yaw, if (sc == "galaxia") min(1.4f, Viz.pitch + 0.5f) else Viz.pitch, Viz.zoom)
        GLES20.glUniform4fv(p.u("uSp"), 8, s32, 0)
        GLES20.glUniform1f(p.u("uRot"), Viz.rot)
        GLES20.glUniform1f(p.u("uDpr"), min(W, H) / 360f)
    }

    private fun quad(p: Prog) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf)
        val a = p.a("aXY"); GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); GLES20.glDisableVertexAttribArray(a)
    }

    private fun pts(name: String, kind: Float, size: Float) {
        val p = P["pts"]!!; set(p)
        GLES20.glUniform1f(p.u("uKind"), kind); GLES20.glUniform1f(p.u("uSize"), size); GLES20.glUniform1f(p.u("uSolid"), if (kind > 2.5f) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufs[name] ?: return)
        val ap = p.a("aP"); val aq = p.a("aQ")
        GLES20.glEnableVertexAttribArray(ap); GLES20.glVertexAttribPointer(ap, 4, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glEnableVertexAttribArray(aq); GLES20.glVertexAttribPointer(aq, 4, GLES20.GL_FLOAT, false, 32, 16)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, counts[name] ?: 0)
        GLES20.glDisableVertexAttribArray(ap); GLES20.glDisableVertexAttribArray(aq)
    }

    private fun lnUpload(a: FloatArray, n: Int): Prog {
        val p = P["ln"]!!; GLES20.glUseProgram(p.id)
        var fb = lnFb
        if (fb == null || fb.capacity() < n) { fb = ByteBuffer.allocateDirect(max(n, 1024) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); lnFb = fb }
        fb!!.clear(); fb.put(a, 0, n); fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lnBuf)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 4, fb, GLES20.GL_DYNAMIC_DRAW)
        val ax = p.a("aXY"); val ac = p.a("aC")
        GLES20.glEnableVertexAttribArray(ax); GLES20.glVertexAttribPointer(ax, 2, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(ac); GLES20.glVertexAttribPointer(ac, 4, GLES20.GL_FLOAT, false, 24, 8)
        return p
    }

    private fun lnDone(p: Prog) { GLES20.glDisableVertexAttribArray(p.a("aXY")); GLES20.glDisableVertexAttribArray(p.a("aC")) }

    /** Un fotograma, en un lienzo de w×h píxeles (lo mismo que glDraw de la web). */
    fun draw(w: Int, h: Int, ts: Double, f: Viz.Frame, cover: Bitmap?, coverId: Any?) {
        W = w; H = h
        val sc = Viz.scene; val pal = Viz.pal
        GLES20.glViewport(0, 0, W, H)
        if (built != sc + ":" + Viz.detail + ":" + Viz.gen) build(sc, Viz.detail)
        // lo que dice la música, según la sensibilidad
        val k = Viz.sens
        fun cl(x: Float) = min(1f, x * k)
        val sp = Viz.spec
        for (i in 0 until 32) s32[i] = cl(max(sp[i * 3], max(sp[i * 3 + 1], sp[i * 3 + 2])))
        kick = max(cl(Viz.plK), Viz.beatK * 0.8f); snare = cl(Viz.plS)
        a0 = cl(Viz.bass); a1 = cl(Viz.mid); a2 = cl(Viz.high); a3 = cl(Viz.level)
        // la onda (para «Ondas»)
        if (sc == "ondas") {
            if (Viz.freeze == null) {
                val wv = Viz.wave
                for (i in 0 until 256) {
                    val v = if (wv != null) wv[floor(i * wv.size / 256.0).toInt()].toFloat() else 128f + 70f * sin(i * 0.12f + Viz.t * 3f) * Viz.level
                    val tg = max(0f, min(255f, 128f + (v - 128f) * k))
                    val cur = Viz.wave256[i]
                    Viz.wave256[i] = (cur + (tg - cur) * 0.5f).toInt().coerceIn(0, 255)
                }
            }
            for (i in 0 until 256) waveBytes.put(i, Viz.wave256[i].toByte())
            waveBytes.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texWave)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 256, 1, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, waveBytes)
        }
        // la portada (el planeta)
        if (sc == "orbita" && cover != null && coverOf !== coverId) {
            try {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texCover)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, cover, 0); coverOf = coverId; hasCover = true
            } catch (t: Throwable) { coverOf = coverId; hasCover = false }
        }
        if (cover == null) hasCover = false
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_BLEND)
        if (sc == "ondas" || sc == "aurora" || sc == "tunel") {
            val p = P[sc]!!; set(p)
            if (sc == "ondas") { GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texWave); GLES20.glUniform1i(p.u("uWave"), 1) }
            quad(p)
        } else {
            val b = P["back"]!!; set(b)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texCover); GLES20.glUniform1i(b.u("uCover"), 0)
            GLES20.glUniform1f(b.u("uHasCover"), if (hasCover) 1f else 0f); GLES20.glUniform1f(b.u("uPlanet"), if (sc == "orbita") 1f else 0f)
            quad(b)
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            when (sc) {
                "galaxia" -> { pts("neb", 1f, 3.0f); pts("stars", 0f, 1.45f) }
                "orbita" -> pts("ring", 2f, 1.05f)
                "particulas" -> { partColors(cover, coverId); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); pts("parts", 3f, 560f / side) }
                "montanas" -> mountains(pal)
            }
            // estrellas fugaces con la caja (galaxia)
            if (sc == "galaxia") {
                if (snare > 0.95f && ts - Viz.lastSnare > 180) {
                    Viz.lastSnare = ts
                    val a = Math.random() * 6.28
                    Viz.shoot.add(Viz.Shoot(((Math.random() - 0.5) * 1.6).toFloat(), ((Math.random() - 0.2) * 1.2).toFloat(), (cos(a) * 1.6).toFloat(), (-abs(sin(a)) * 1.2).toFloat(), 1f))
                    if (Viz.shoot.size > 6) Viz.shoot.removeAt(0)
                }
                if (Viz.shoot.isNotEmpty()) {
                    val ln = FloatArray(Viz.shoot.size * 12); var n = 0
                    for (s in Viz.shoot) {
                        s.life -= f.dt * 1.6f; s.x += s.dx * f.dt * 0.6f; s.y += s.dy * f.dt * 0.6f
                        val L = 0.18f
                        ln[n++] = s.x; ln[n++] = s.y; ln[n++] = 1f; ln[n++] = 1f; ln[n++] = 1f; ln[n++] = max(0f, s.life)
                        ln[n++] = s.x - s.dx * L * 0.3f; ln[n++] = s.y - s.dy * L * 0.3f; ln[n++] = pal[0][0]; ln[n++] = pal[0][1]; ln[n++] = pal[0][2]; ln[n++] = 0f
                    }
                    Viz.shoot.removeAll { it.life <= 0f }
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                    val p = lnUpload(ln, n); GLES20.glDrawArrays(GLES20.GL_LINES, 0, n / 6); lnDone(p)
                }
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    /** El sonido como un paisaje: cada momento es una fila que nace arriba (lejos) y baja hacia ti. */
    private fun mountains(pal: Array<FloatArray>) {
        val rows = Viz.hist; val nc = 64; val nr = 40
        if (mt == null) {
            mt = FloatArray(42 * nc * 6)
            mtEnv = FloatArray(nc) { i -> max(0.0, cos((i.toDouble() / (nc - 1) * 2 - 1) * PI / 2)).pow(1.6).toFloat() }
        }
        val m = mt!!; val env = mtEnv!!
        val runs = ArrayList<Int>(); var w = 0
        var ri = 0
        while (ri < rows.size && ri < 42) {
            val row = rows[ri]; val q = min(1f, (ri + Viz.histT) / nr)
            ri++
            if (q >= 1f) continue
            val y0 = 0.62f - q * 1.45f; val sx = 0.55f + 0.5f * q; val hs = 0.12f + 0.42f * q
            val fade = min(1f, q * 4) * (1 - max(0f, q - 0.85f) / 0.15f)
            runs.add(w / 6)
            for (i in 0 until nc) {
                val uu = i.toFloat() / (nc - 1) * 2 - 1; val mm = abs(uu); val v = row[min(63, floor(mm * 63).toInt())]
                m[w++] = uu * sx; m[w++] = y0 + v * hs * env[i]
                m[w++] = pal[0][0] * (1 - mm) + pal[1][0] * mm; m[w++] = pal[0][1] * (1 - mm) + pal[1][1] * mm; m[w++] = pal[0][2] * (1 - mm) + pal[1][2] * mm
                m[w++] = fade * (0.45f + v * 0.8f)
            }
        }
        if (runs.isNotEmpty()) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            val p = lnUpload(m, w)
            for (f0 in runs) GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, f0, nc)
            lnDone(p)
        }
    }
}

// ======================================================================= los clásicos (2D), como en la web
private class Viz2D {
    private var bmp: Bitmap? = null
    private var cv: android.graphics.Canvas? = null
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val path = Path()
    private val clip = Path()
    private val rf = RectF()

    /** hsla() de la web, con la saturación de Órbita («sat»). */
    private fun vh(h: Float, s: Float, l: Float, a: Float): Int {
        val ss = (s * Viz.sat).roundToInt().coerceIn(0, 100) / 100f
        val ll = l.coerceIn(0f, 100f) / 100f; val aa = a.coerceIn(0f, 1f)
        val hh = (((h % 360f) + 360f) % 360f) / 360f
        val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss; val p = 2 * ll - q
        fun f(x0: Float): Float { var x = x0; if (x < 0) x += 1f; if (x > 1) x -= 1f
            return if (x < 1f / 6) p + (q - p) * 6 * x else if (x < 0.5f) q else if (x < 2f / 3) p + (q - p) * (2f / 3 - x) * 6 else p }
        val r = (f(hh + 1f / 3) * 255).roundToInt(); val g = (f(hh) * 255).roundToInt(); val b = (f(hh - 1f / 3) * 255).roundToInt()
        return ((aa * 255).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun draw(w: Int, h: Int, f: Viz.Frame, cover: Bitmap?): Bitmap? {
        var b = bmp
        if (b == null || b.width != w || b.height != h) { b?.recycle(); b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp = b; cv = android.graphics.Canvas(b) }
        val c = cv ?: return null
        when (when (Viz.scene) { "galaxia" -> "galaxia"; "ondas", "montanas", "aurora" -> "ondas"; else -> "orbita" }) {
            "ondas" -> ondas(c, w.toFloat(), h.toFloat())
            "galaxia" -> galaxia(c, w.toFloat(), h.toFloat(), f)
            else -> orbita(c, w.toFloat(), h.toFloat(), f, cover)
        }
        return b
    }

    private fun fill(c: android.graphics.Canvas, color: Int, w: Float, h: Float) { pf.shader = null; pf.xfermode = null; pf.color = color; c.drawRect(0f, 0f, w, h, pf) }

    private fun radial(cx: Float, cy: Float, r0: Float, r1: Float, c0: Int, c1: Int): Shader =
        if (r0 <= 0f) RadialGradient(cx, cy, max(1f, r1), c0, c1, Shader.TileMode.CLAMP)
        else RadialGradient(cx, cy, max(1f, r1), intArrayOf(c0, c0, c1), floatArrayOf(0f, (r0 / r1).coerceIn(0f, 1f), 1f), Shader.TileMode.CLAMP)

    private fun orbita(c: android.graphics.Canvas, w: Float, h: Float, f: Viz.Frame, cover: Bitmap?) {
        val hue = Viz.hue
        val cx = w / 2; val cy = h / 2; val R = min(w, h) / 2; val n = 96
        fill(c, 0x47 shl 24 or (6 shl 16) or (5 shl 8) or 11, w, h)   // rgba(6,5,11,0.28)
        pf.shader = radial(cx, cy, R * 0.1f, R * 1.1f, vh(hue, 96f, 55f, 0.18f + Viz.bass * 0.35f), vh(hue - 45, 90f, 40f, 0f)); c.drawRect(0f, 0f, w, h, pf); pf.shader = null
        Viz.rot2 += f.dt * (0.15f + Viz.level * 0.9f + (if (Viz.bpmNow > 0) Viz.bpmNow / 400 else 0f))
        // ondas de choque con cada golpe
        ps.strokeCap = Paint.Cap.BUTT; ps.xfermode = null
        for (wv in Viz.waves) {
            wv.r += f.dt * R * 1.1f; wv.a *= 0.12f.pow(f.dt)
            ps.color = vh(hue, 96f, 70f, wv.a * 0.6f); ps.strokeWidth = max(1f, R * 0.012f)
            c.drawCircle(cx, cy, R * 0.34f + wv.r, ps)
        }
        Viz.waves.removeAll { it.a <= 0.02f }
        // el anillo del espectro
        val r1 = R * 0.42f * (1 + Viz.beatK * 0.05f)
        ps.strokeCap = Paint.Cap.ROUND; ps.strokeWidth = max(2f, (PI.toFloat() * 2 * r1 / n) * 0.55f)
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * PI.toFloat() * 2 + Viz.rot2 * 0.25f; val v = Viz.spec[i]; val r2 = r1 + R * 0.04f + v * R * 0.36f
            ps.color = vh(hue - 50 * (i.toFloat() / n) + 25 * sin(Viz.t * 0.5f), 96f, 58 + v * 18, 0.35f + v * 0.65f)
            c.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * r2, cy + sin(a) * r2, ps)
        }
        // partículas en órbita
        val want = if (Viz.lite) 40 else 90
        while (Viz.parts.size < want) { val r = Viz.rngP; Viz.parts.add(Viz.P2((r.next() * PI * 2).toFloat(), (0.55 + r.next() * 0.45).toFloat(), (0.4 + r.next() * 0.8).toFloat(), r.next().toFloat())) }
        pf.xfermode = add
        for (p in Viz.parts) {
            p.a += f.dt * p.s * (0.25f + Viz.level * 1.4f) * (if (p.z > 0.5f) 1f else -0.6f)
            val dd = R * p.d * (1 + Viz.beatK * 0.08f * p.z); val x = cx + cos(p.a) * dd; val y = cy + sin(p.a) * dd * 0.62f
            val sz = max(1f, R * 0.008f * (1 + Viz.high * 3 * p.z))
            pf.color = vh(hue + 30 * p.z, 90f, 70f, 0.25f + Viz.high * 0.7f); c.drawCircle(x, y, sz, pf)
        }
        pf.xfermode = null
        // el planeta: la portada, respirando con el bajo
        val pr = R * 0.3f * (1 + Viz.bass * 0.1f + Viz.beatK * 0.06f)
        c.save(); clip.reset(); clip.addCircle(cx, cy, pr, Path.Direction.CW); c.clipPath(clip)
        if (cover != null) { rf.set(cx - pr, cy - pr, cx + pr, cy + pr); c.drawBitmap(cover, null, rf, pb) }
        else {
            pf.shader = LinearGradient(cx - pr, cy - pr, cx + pr, cy + pr, intArrayOf(vh(hue + 30, 96f, 62f, 1f), vh(hue, 96f, 56f, 1f), vh(hue - 60, 90f, 45f, 1f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, pr, pf); pf.shader = null
        }
        c.restore()
        ps.strokeCap = Paint.Cap.BUTT; ps.color = vh(hue, 96f, 75f, 0.35f + Viz.beatK * 0.5f); ps.strokeWidth = max(1.5f, R * 0.01f)
        c.drawCircle(cx, cy, pr + R * 0.015f, ps)
    }

    private fun ondas(c: android.graphics.Canvas, w: Float, h: Float) {
        val hue = Viz.hue
        fill(c, (0.35f * 255).roundToInt() shl 24 or (6 shl 16) or (5 shl 8) or 11, w, h)
        pf.shader = radial(w / 2, h / 2, 0f, max(w, h) * 0.7f, vh(hue, 96f, 50f, 0.1f + Viz.bass * 0.3f), 0); c.drawRect(0f, 0f, w, h, pf); pf.shader = null
        val lines = 5; val mid = h / 2; val amp = h * (0.12f + Viz.level * 0.32f)
        ps.xfermode = add; ps.strokeJoin = Paint.Join.ROUND; ps.strokeCap = Paint.Cap.BUTT
        val wv = Viz.wave
        for (k in 0 until lines) {
            ps.color = vh(hue - 45 * (k.toFloat() / lines), 96f, 62f, 0.75f - k * 0.12f); ps.strokeWidth = max(1.5f, h * 0.006f * (1 + Viz.beatK * 1.5f - k * 0.15f))
            path.reset()
            val N = 90; val off = k * 9
            for (i in 0..N) {
                val x = (i.toFloat() / N) * w
                val v = if (wv != null) (wv[min(wv.size - 1, (i * 4 + off * 3) % wv.size)] - 128) / 128f
                else sin(i * 0.22f + Viz.t * (3 + k)) * 0.5f * Viz.level + sin(i * 0.05f - Viz.t) * 0.3f * Viz.bass
                val env = sin((i.toFloat() / N) * PI.toFloat())
                val y = mid + v * amp * env * (1 - k * 0.13f) + sin(Viz.t * 0.7f + k) * h * 0.02f * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, ps)
        }
        ps.xfermode = null
        // el bajo, en barras abajo
        val n = 48; val bw = w / n
        for (i in 0 until n) {
            val v = Viz.spec[floor(i * 1.9).toInt()]
            pf.color = vh(hue - 40 * (i.toFloat() / n), 96f, 60f, 0.25f + v * 0.5f); val hh = v * h * 0.18f
            c.drawRect(i * bw + bw * 0.2f, h - hh, i * bw + bw * 0.8f, h, pf)
        }
    }

    private fun galaxia(c: android.graphics.Canvas, w: Float, h: Float, f: Viz.Frame) {
        val hue = Viz.hue
        val cx = w / 2; val cy = h / 2; val R = max(w, h) * 0.62f
        fill(c, (0.3f * 255).roundToInt() shl 24 or (6 shl 16) or (5 shl 8) or 11, w, h)
        val want = if (Viz.lite) 220 else 520
        while (Viz.stars.size < want) { val r = Viz.rngS; val arm = floor(r.next() * 3).toInt(); Viz.stars.add(Viz.S2(r.next().pow(0.7).toFloat(), arm, ((r.next() - 0.5) * 0.5).toFloat(), r.next().toFloat(), floor(r.next() * 96).toInt())) }
        Viz.rot2 += f.dt * (0.08f + Viz.level * 0.5f + (if (Viz.bpmNow > 0) Viz.bpmNow / 900 else 0f))
        pf.xfermode = add
        pf.shader = radial(cx, cy, 0f, min(w, h) * 0.16f * (1 + Viz.bass * 0.5f), vh(hue + 20, 90f, 70f, 0.16f + Viz.beatK * 0.22f), 0); c.drawRect(0f, 0f, w, h, pf); pf.shader = null
        for (s in Viz.stars) {
            val a = s.arm * (PI.toFloat() * 2 / 3) + s.d * 5.2f + Viz.rot2 * (1.4f - s.d) + s.j; val dd = s.d * R * (1 + Viz.beatK * 0.05f)
            val x = cx + cos(a) * dd; val y = cy + sin(a) * dd * 0.72f; val v = Viz.spec[s.k]
            val sz = max(0.8f, (w / 500) * (0.6f + s.b * 1.4f + v * 2.2f))
            pf.color = vh(hue - 60 * s.d + 40 * v, 90f, 60 + v * 25, 0.25f + v * 0.6f + Viz.high * 0.3f)
            c.drawRect(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2, pf)
        }
        pf.xfermode = null
    }
}

// ======================================================================= la vista que dibuja (con su propio hilo)
/**
 * Un lienzo del visualizador: una TextureView (se recorta y se mueve con lo de alrededor) con un hilo que dibuja
 * a cada fotograma de la pantalla, solo cuando su lienzo es el que se ve («Viz.active»).
 */
internal class VizView(ctx: Context, private val cid: String, private val two: Boolean) : TextureView(ctx), TextureView.SurfaceTextureListener {
    @Volatile private var th: VizThread? = null

    init { surfaceTextureListener = this; isOpaque = true }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        th?.halt()
        th = VizThread(st, cid, two, w, h, resources.displayMetrics.density).also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { th?.resize(w, h) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { th?.halt(); th = null; return false }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Con la app en segundo plano o la pantalla apagada, no se dibuja (como la web, que se para al no verse). */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        th?.seen = visibility == android.view.View.VISIBLE
    }
}

private class VizThread(private val st: SurfaceTexture, private val cid: String, private val two: Boolean, w: Int, h: Int, private val dens: Float) : HandlerThread("viz-$cid") {
    @Volatile private var vw = w
    @Volatile private var vh = h
    @Volatile private var stopping = false
    /** La ventana de la app se ve (no está en segundo plano ni con la pantalla apagada). */
    @Volatile var seen = true
    private var hnd: Handler? = null
    private var dpy: EGLDisplay? = null
    private var ectx: EGLContext? = null
    private var esurf: EGLSurface? = null
    private var gl: VizGl? = null
    private var d2: Viz2D? = null
    private var surface: Surface? = null
    private var bufW = 0
    private var bufH = 0
    private var lastDraw = 0.0
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    // poco a poco más fino si se pierden fotogramas (como glAdapt de la web)
    private var scale = 1f
    private var lastTs = 0.0
    private var acc = 0.0
    private var win = 0
    private var good = 0

    fun resize(w: Int, h: Int) { vw = w; vh = h }

    fun halt() {
        stopping = true
        val l = looper ?: return
        Handler(l).post {
            try { Choreographer.getInstance().removeFrameCallback(cb) } catch (_: Throwable) {}
            hnd?.removeCallbacks(wake)
            release(); quitSafely()
        }
    }

    override fun onLooperPrepared() {
        hnd = Handler(looper)
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** Sin nada que dibujar (no es su turno, o la app no se ve), se mira cada cuarto de segundo, no a cada fotograma. */
    private val wake: Runnable = Runnable { if (!stopping) Choreographer.getInstance().postFrameCallback(cb) }

    private val cb: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (stopping) return
            var busy = false
            try { busy = frame(nanos / 1.0e6) } catch (t: Throwable) {
                if (stopping) return // (la vista ya se iba: no es que el móvil no pueda)
                Viz.err = (if (two) "2D: " else "3D: ") + t.toString().take(140)
                if (!two) { release(); Viz.brokeGl(t.toString()); stopping = true; return }
            }
            if (stopping) return
            if (busy) Choreographer.getInstance().postFrameCallback(this) else hnd?.postDelayed(wake, 250)
        }
    }

    private fun frame(ts: Double): Boolean {
        if (Viz.active != cid || !seen || vw < 2 || vh < 2) return false
        synchronized(Viz.lock) { draw(ts) }
        return true
    }

    private fun draw(ts: Double) {
        if (Viz.slow && ts - lastDraw < 31) return
        lastDraw = ts
        val useGl = !two
        val f = Viz.step(ts, useGl)
        val art = Viz.art
        val cover = if (art.isNotEmpty()) UiState.covers["$art|L"]?.asAndroidBitmap() else null
        Viz.coverOk = cover != null
        // el tamaño del lienzo, en «píxeles de la web»: los dibujos dependen de él (puntos, líneas)
        val cssW = vw / dens; val cssH = vh / dens
        if (useGl) {
            adapt(ts)
            val sc = Viz.scene; val heavy = sc == "ondas" || sc == "aurora" || sc == "tunel"
            val dpr = min(dens, if (Viz.detail == "bajo") 0.75f else if (Viz.detail == "alto") 2f else if (heavy) 1f else 1.5f) * scale
            val W = max(2, (cssW * dpr).roundToInt()); val H = max(2, (cssH * dpr).roundToInt())
            if (W != bufW || H != bufH) { st.setDefaultBufferSize(W, H); bufW = W; bufH = H }
            if (gl == null) startGl()
            val e = esurf ?: return
            // (lo que de verdad mide el lienzo ahora: un cambio de tamaño llega un fotograma después)
            val q = IntArray(1)
            EGL14.eglQuerySurface(dpy, e, EGL14.EGL_WIDTH, q, 0); val rw = if (q[0] > 1) q[0] else W
            EGL14.eglQuerySurface(dpy, e, EGL14.EGL_HEIGHT, q, 0); val rh = if (q[0] > 1) q[0] else H
            gl!!.draw(rw, rh, ts, f, cover, cover)
            if (!EGL14.eglSwapBuffers(dpy, e)) throw IllegalStateException("swap " + EGL14.eglGetError())
            Viz.drawn = "$cid:gl:${Viz.scene}:${rw}x$rh"
        } else {
            val dpr = min(if (Viz.lite) 1.25f else 2f, dens)
            val W = max(2, (cssW * dpr).roundToInt()); val H = max(2, (cssH * dpr).roundToInt())
            if (W != bufW || H != bufH) { st.setDefaultBufferSize(W, H); bufW = W; bufH = H }
            val d = d2 ?: Viz2D().also { d2 = it }
            val b = d.draw(W, H, f, cover) ?: return
            val s = surface ?: Surface(st).also { surface = it }
            val c = s.lockCanvas(null)
            try { dst.set(0, 0, c.width, c.height); c.drawColor(0xff06050b.toInt()); c.drawBitmap(b, null, dst, blit) } finally { s.unlockCanvasAndPost(c) }
            Viz.drawn = "$cid:2d:${Viz.scene}:${W}x$H"
        }
        Viz.frames++
    }

    private fun adapt(ts: Double) {
        if (Viz.freeze != null) { scale = 1f; return }   // (pruebas: a toda resolución, para comparar)
        val dt = ts - (if (lastTs > 0) lastTs else ts); lastTs = ts
        if (Viz.slow || dt <= 0 || dt > 250) { win = 0; acc = 0.0; return }
        acc += dt; win++
        if (acc < 1200) return
        val avg = acc / win; val vs = 16.7; acc = 0.0; win = 0
        if (avg > vs * 1.3 && scale > 0.5f) { scale = max(0.5f, scale * 0.85f); good = 0 }
        else if (avg < vs * 1.08) { good++; if (good >= 4 && scale < 1f) { scale = min(1f, scale * 1.1f); good = 0 } }
        else good = 0
    }

    private fun startGl() {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == null || d == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("sin pantalla EGL")
        val v = IntArray(2)
        if (!EGL14.eglInitialize(d, v, 0, v, 1)) throw IllegalStateException("eglInitialize")
        dpy = d
        val attr = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(d, attr, 0, cfgs, 0, 1, n, 0) || n[0] < 1) throw IllegalStateException("eglChooseConfig")
        val c = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (c == null || c == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")
        ectx = c
        val s = EGL14.eglCreateWindowSurface(d, cfgs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface " + EGL14.eglGetError())
        esurf = s
        if (!EGL14.eglMakeCurrent(d, s, s, c)) throw IllegalStateException("eglMakeCurrent")
        val g = VizGl(); g.init(); gl = g
    }

    private fun release() {
        try {
            val d = dpy
            if (d != null) {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                esurf?.let { EGL14.eglDestroySurface(d, it) }
                ectx?.let { EGL14.eglDestroyContext(d, it) }
                EGL14.eglReleaseThread()
            }
        } catch (ignored: Throwable) { }
        dpy = null; ectx = null; esurf = null; gl = null
        try { surface?.release() } catch (ignored: Throwable) { }
        surface = null
        try { st.release() } catch (ignored: Throwable) { }
    }
}

// ======================================================================= en Compose
/**
 * El visualizador en su sitio: «vizHero» (el círculo de «Ahora»), «vizNP» (el reproductor) o «vizFS» (pantalla
 * completa, donde se puede girar arrastrando y acercar pellizcando; un toque sin mover sigue yendo a la web).
 */
@Composable
internal fun VizBox(cid: String, modifier: Modifier, gestures: Boolean = false) {
    val two = Viz.two
    val dens = LocalDensity.current.density
    // la portada, para el planeta y las partículas (la misma que el disco de «Ahora», más grande)
    LaunchedEffect(Unit) {
        while (true) {
            val a = Viz.art
            if (a.isNotEmpty() && UiState.covers["$a|L"] == null) needArt(a, 220, dens)
            delay(600)
        }
    }
    Box(modifier) {
        key(two) { AndroidView(factory = { VizView(it, cid, two) }, modifier = Modifier.fillMaxSize()) }
        if (gestures) Box(Modifier.fillMaxSize().vizGestures())
    }
}

/** Pantalla completa: arrastrar para girar los estilos en 3D, pellizcar para acercar (lo mismo que la web). */
private fun Modifier.vizGestures(): Modifier = pointerInput(Unit) {
    val dens = density
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val pts = LinkedHashMap<PointerId, Offset>()
        pts[first.id] = first.position
        var moved = false
        var d0 = 0f
        var z0 = Viz.zoom
        while (true) {
            val ev = awaitPointerEvent()
            for (c in ev.changes) {
                if (c.changedToDown()) {
                    pts[c.id] = c.position
                    if (pts.size == 2) { val l = pts.values.toList(); d0 = (l[0] - l[1]).getDistance() / dens; z0 = Viz.zoom }
                    continue
                }
                val p = pts[c.id] ?: continue
                if (c.changedToUp()) {
                    val dx = (c.position.x - p.x) / dens; val dy = (c.position.y - p.y) / dens
                    if (pts.size == 1 && (moved || hypot(dx, dy) >= 8f)) {
                        Viz.yaw += dx * 0.006f; Viz.pitch = (Viz.pitch + dy * 0.004f).coerceIn(0.05f, 1.35f)
                        Viz.dx = (Viz.dx + dx * 0.004f).coerceIn(-1f, 1f); Viz.dy = (Viz.dy + dy * 0.003f).coerceIn(-0.8f, 0.8f)
                        c.consume()
                    }
                    pts.remove(c.id); continue
                }
                if (!c.pressed) continue
                if (pts.size >= 2) {
                    pts[c.id] = c.position
                    val l = pts.values.toList(); val d = (l[0] - l[1]).getDistance() / dens
                    if (d0 > 20f) Viz.zoom = (z0 * d / d0).coerceIn(0.6f, 2.2f)
                    moved = true; c.consume(); continue
                }
                val dx = (c.position.x - p.x) / dens; val dy = (c.position.y - p.y) / dens
                if (!moved && hypot(dx, dy) < 8f) continue
                moved = true; Viz.drag = true; pts[c.id] = c.position
                Viz.yaw += dx * 0.006f
                Viz.pitch = (Viz.pitch + dy * 0.004f).coerceIn(0.05f, 1.35f)
                Viz.dx = (Viz.dx + dx * 0.004f).coerceIn(-1f, 1f)
                Viz.dy = (Viz.dy + dy * 0.003f).coerceIn(-0.8f, 0.8f)
                c.consume()
            }
            if (ev.changes.none { it.pressed }) break
        }
        Viz.drag = false
    }
}
