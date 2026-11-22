package __PACKAGE__

import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 5.1 · Fase 3, paso 4: la voz de Nova, de Android.
 *
 * «Háblale» ya no abre la ventana de Google: Órbita escucha ella misma, con el reconocimiento de voz del móvil. Mientras
 * hablas, abajo sale un recuadro con el aro de Nova, que se mueve con tu voz, y lo que va entendiendo; al callarte, lo que
 * has dicho le llega a Nova como siempre. «Listo» termina antes; «Cancelar» (o tocar fuera, o «atrás») no le manda nada.
 * Sin permiso del micrófono, o sin reconocimiento en el móvil, la app usa la ventana de Google de siempre.
 */
internal object Ear {
    // ---- lo que se ve (lo lee Compose)
    var on by mutableStateOf(false)
    /** «perm» (pidiendo permiso), «ready» (esperando a que hables), «speech» (hablando), «think» (pensando), «done», «fail». */
    var step by mutableStateOf("")
    var words by mutableStateOf("")
    var level by mutableFloatStateOf(0f)
    var who by mutableStateOf("Nova")
    var fail by mutableStateOf("")
    var lite by mutableStateOf(false)
    // ---- (para las pruebas)
    @Volatile var sessions = 0
    @Volatile var last = ""
    @Volatile var partials = 0
    @Volatile var levels = 0
    @Volatile var maxLevel = 0f

    /** Lo que se le cuenta a la app: lo que va entendiendo, y cómo acabó (lo dicho, las otras formas de entenderlo y un código). */
    var onPartial: ((String) -> Unit)? = null
    var onEnd: ((String, List<String>, String) -> Unit)? = null
    /** La capa donde se dibuja el recuadro (se esconde del todo cuando no escucha). */
    var host: View? = null

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var gen = 0
    private var ended = true
    private var tries = 0
    private var ctx: Context? = null
    private var lang = "es-ES"
    private var offline = false

    val busy: Boolean get() = !ended || step == "perm"

    /** El recuadro sale ya (mientras Android pide permiso para el micrófono, explica por qué). */
    fun open(who: String, lite: Boolean, perm: Boolean) {
        gen++; ended = true
        this.who = who.ifBlank { "Nova" }; this.lite = lite
        words = ""; fail = ""; level = 0f; step = if (perm) "perm" else "ready"
        show(true)
    }

    /** Empieza a escuchar. */
    fun start(ctx: Context, lang: String, offline: Boolean) {
        gen++; val g = gen
        ended = false; tries = 0; sessions++; partials = 0; levels = 0; maxLevel = 0f
        this.ctx = ctx.applicationContext; this.lang = lang; this.offline = offline
        words = ""; fail = ""; level = 0f; step = "ready"
        show(true)
        launch(g)
        after(g, 30000) // (nunca más de medio minuto)
    }

    /** «Listo»: deja de escuchar y se queda con lo dicho. */
    fun finish() {
        if (ended) return
        val g = gen
        step = "think"; level = 0f
        try { sr?.stopListening() } catch (ignored: Throwable) { }
        after(g, 4000)
    }

    /** «Cancelar», tocar fuera o «atrás»: no le llega nada a Nova. */
    fun cancel() {
        if (!ended) { end("", emptyList(), "cancel"); return }
        if (on) { gen++; step = ""; show(false) }
    }

    /** No hubo permiso (o la app dijo que no): el recuadro se va sin más. */
    fun close() { gen++; ended = true; step = ""; show(false) }

    private fun show(v: Boolean) {
        on = v
        val h = host ?: return
        if (v) { h.visibility = View.VISIBLE; return }
        val g = gen
        main.postDelayed({ if (!on && g == gen) h.visibility = View.GONE }, 320)
    }

    private fun launch(g: Int) {
        val c = ctx ?: return end("", emptyList(), "norec")
        try { sr?.destroy() } catch (ignored: Throwable) { }
        sr = null
        val r = try { SpeechRecognizer.createSpeechRecognizer(c) } catch (t: Throwable) { null } ?: return end("", emptyList(), "norec")
        sr = r
        r.setRecognitionListener(listener(g))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try { r.startListening(i) } catch (t: Throwable) { end("", emptyList(), "norec") }
    }

    /** Pasado un rato, si aún no ha acabado: con lo que haya entendido (o con nada). */
    private fun after(g: Int, ms: Long) {
        main.postDelayed({ if (g == gen && !ended) { if (words.isNotBlank()) end(words, listOf(words), "ok") else end("", emptyList(), "timeout") } }, ms)
    }

    private fun end(text: String, alts: List<String>, code: String) {
        if (ended) return
        ended = true; gen++
        val g = gen
        try { sr?.cancel() } catch (ignored: Throwable) { }
        try { sr?.destroy() } catch (ignored: Throwable) { }
        sr = null
        last = code; level = 0f
        when (code) {
            "ok" -> { words = text; step = "done" }
            "cancel", "denied", "norec" -> { }
            else -> {
                step = "fail"
                fail = when (code) {
                    "net" -> "No te he entendido: parece que no hay internet."
                    "lang" -> "Sin internet, el móvil necesita el español descargado para entenderte."
                    "audio" -> "El micrófono lo está usando otra cosa."
                    "busy" -> "El reconocimiento de voz está ocupado. Prueba otra vez."
                    else -> "No te he oído bien. Prueba otra vez."
                }
            }
        }
        try { onEnd?.invoke(text, alts, code) } catch (ignored: Throwable) { }
        // (lo dicho se ve un momento; un aviso, algo más; lo cancelado se va ya)
        val ms = when (code) { "ok" -> 550L; "cancel", "denied", "norec" -> 0L; else -> 1600L }
        if (ms == 0L) show(false) else main.postDelayed({ if (g == gen) show(false) }, ms)
    }

    private fun listener(g: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (g == gen && step != "speech") step = "ready" }
        override fun onBeginningOfSpeech() { if (g == gen) step = "speech" }
        override fun onRmsChanged(rmsdB: Float) {
            if (g != gen || ended) return
            levels++
            val v = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            if (v > maxLevel) maxLevel = v
            level = level * 0.45f + v * 0.55f
        }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { if (g == gen && !ended) { step = "think"; level = 0f; after(g, 8000) } }
        override fun onError(error: Int) { if (g == gen && !ended) failed(g, error) }
        override fun onResults(results: Bundle?) {
            if (g != gen || ended) return
            val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            when {
                l.isNotEmpty() -> end(l[0], l, "ok")
                words.isNotBlank() -> end(words, listOf(words), "ok")   // (sin final: lo que iba entendiendo)
                else -> end("", emptyList(), "nomatch")
            }
        }
        override fun onPartialResults(partial: Bundle?) {
            if (g != gen || ended) return
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isEmpty() || t == words) return
            words = t; partials++
            if (step == "ready") step = "speech"
            try { onPartial?.invoke(t) } catch (ignored: Throwable) { }
        }
        override fun onEvent(eventType: Int, params: Bundle?) { }
    }

    private fun failed(g: Int, error: Int) {
        when (error) {
            // no ha oído nada (o nada que entienda): con lo que fuera entendiendo, si algo
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT ->
                if (words.isNotBlank()) end(words, listOf(words), "ok") else end("", emptyList(), if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "timeout" else "nomatch")
            // ocupado: una vez más, con uno nuevo
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (tries < 1) { tries++; try { sr?.cancel() } catch (ignored: Throwable) { }; main.postDelayed({ if (g == gen && !ended) launch(g) }, 350) } else end("", emptyList(), "busy")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end("", emptyList(), "denied")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 11 -> end("", emptyList(), "net")
            12, 13 -> end("", emptyList(), "lang")
            SpeechRecognizer.ERROR_AUDIO -> end("", emptyList(), "audio")
            10 -> end("", emptyList(), "busy")
            else -> end("", emptyList(), "err$error")
        }
    }
}

/** La capa del recuadro que escucha (por encima de todo lo demás). */
@Composable
internal fun EarLayer() {
    val sk = rememberSkin()
    AnimatedVisibility(Ear.on, enter = fadeIn(tween(180)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).pointerInput(Unit) { detectTapGestures { Ear.cancel() } }) {
            AnimatedVisibility(Ear.on, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(260)) { it / 3 }, exit = fadeOut(tween(160))) {
                EarCard(sk)
            }
        }
    }
}

@Composable
private fun EarCard(sk: Skin) {
    val p = sk.pal
    val step = Ear.step
    val words = Ear.words
    val who = Ear.who.uppercase()
    val label = when (step) {
        "perm" -> "PERMISO DEL MICRÓFONO"
        "fail" -> "NO HA PODIDO SER"
        else -> "$who TE ESCUCHA"
    }
    val text = when {
        step == "perm" -> "Para escucharte sin salir de Órbita, Android te va a pedir permiso para usar el micrófono."
        step == "fail" -> Ear.fail
        words.isNotBlank() -> "«$words»"
        step == "think" -> "…"
        else -> "Habla cuando quieras…"
    }
    val dim = words.isBlank() && step != "fail" && step != "perm"
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 14.dp)
            .glowPanel(sk, 22f).pointerInput(Unit) { detectTapGestures { } }
            .semantics { contentDescription = "$label. $text" }
            .padding(18.dp + sk.b),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EarOrb(sk, step)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(label, 11f * sk.tk, if (step == "fail") p.bad else p.accent, family = Mono, weight = FontWeight.Medium, ls = 0.16f, maxLines = 1)
                T(text, 18f * sk.tk, if (dim) p.muted else p.fg, weight = FontWeight.Bold, lh = 1.3f, maxLines = 4)
            }
        }
        if (step != "done" && step != "fail") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Btn("Cancelar", sk, small = true) { Ear.cancel() }
            if (step != "perm") Btn("Listo", sk, primary = true, small = true, enabled = step != "think") { Ear.finish() }
        }
    }
}

/** El aro de Nova, que se mueve con tu voz: gira, su halo crece con lo fuerte que hablas y las barras suben y bajan. */
@Composable
private fun EarOrb(sk: Skin, step: String) {
    val p = sk.pal
    val lite = Ear.lite
    val lv by animateFloatAsState(if (step == "speech" || step == "ready") Ear.level else 0f, tween(if (lite) 0 else 110), label = "lv")
    val tr = if (lite) null else rememberInfiniteTransition(label = "ear")
    val spinS = tr?.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "s")
    val waveS = tr?.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "w")
    val lime = Color(0xFFA3E635); val teal = Color(0xFF21D4A8)
    Canvas(Modifier.size(66.dp)) {
        val spin = spinS?.value ?: 0f
        val wave = waveS?.value ?: 0f
        val ctr = Offset(size.width / 2, size.height / 2)
        val ring = Brush.sweepGradient(listOf(lime, p.accent, p.accent2, teal, lime), ctr)
        rotate(spin - 50f, ctr) {
            drawIntoCanvas { cv ->
                val pt = Paint()
                ring.applyTo(size, pt, if (step == "fail") 0.35f else 0.45f + 0.55f * lv)
                pt.asFrameworkPaint().maskFilter = BlurMaskFilter(17.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                cv.drawCircle(ctr, (size.width / 2 + 8.dp.toPx()) * (1f + 0.3f * lv), pt)
            }
            drawCircle(ring, size.width / 2, ctr)
        }
        clipPath(Path().apply { addOval(Rect(ctr, 29.dp.toPx())) }) {
            drawRect(Brush.radialGradient(0f to Color(0xFF2A1F45), 0.7f to Color(0xFF0B0814), 1f to Color(0xFF0B0814),
                center = Offset(4.dp.toPx() + 58.dp.toPx() * 0.35f, 4.dp.toPx() + 58.dp.toPx() * 0.30f), radius = 58.dp.toPx() * 0.72f))
        }
        // las barras: con tu voz (al pensar, una ola tranquila)
        val hs = floatArrayOf(8f, 16f, 22f, 14f, 8f)
        val w = 4.dp.toPx(); val g = 3.dp.toPx()
        var x = ctr.x - (5 * w + 4 * g) / 2
        for (i in 0 until 5) {
            val hh = when (step) {
                "think" -> 6f + 10f * (0.5f + 0.5f * sin((wave + i * 0.18f) * 2f * PI.toFloat()))
                "speech", "ready" -> 5f + (hs[i] - 3f) * (0.25f + 0.75f * lv) * (0.85f + 0.15f * cos((wave + i * 0.23f) * 2f * PI.toFloat()))
                else -> hs[i] * 0.6f
            }
            val hp = hh.dp.toPx()
            drawRoundRect(if (step == "speech" && lv > 0.08f) p.accent else p.fg, Offset(x, ctr.y - hp / 2), Size(w, hp), CornerRadius(2.dp.toPx() * sk.round))
            x += w + g
        }
    }
}
