package __PACKAGE__

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Fase 4, parte 2 · La bienvenida (lo que en la web era «3.5 · Primera vez fácil»): para quien acaba de instalar Órbita.
 * Cuatro pasos: tu música (el permiso), lo que te gusta, tu DJ y «todo listo», con Nova hablando en cada uno.
 */
object Bienvenida {
    private val main = Handler(Looper.getMainLooper())
    val STEPS = listOf("hola", "musica", "gustos", "dj", "listo")
    var step by mutableIntStateOf(0); private set
    var genres by mutableStateOf(setOf<String>()); private set
    var energy by mutableStateOf(""); private set
    var scanning by mutableStateOf(false); private set
    var again = false; private set
    var name by mutableStateOf(""); var city by mutableStateOf("")
    private var gen = 0

    /** Alguien que acaba de instalarla: nada escuchado, nada guardado y sin haber visto las novedades de una versión. */
    fun fresh(): Boolean = !Prefs.bool("onboarded", false) && Prefs.str("newsSeen", "").isEmpty() && Memoria.events.isEmpty() && Lib.tracks.none { !it.demo }

    fun open(again0: Boolean) {
        step = 0; again = again0; energy = ""
        genres = Cerebro.prefList().filter { Json.s(it["kind"]) == "boost" && Json.s(Json.obj(it["target"])?.get("type")) == "genre" && Json.s(it["said"]).startsWith("Bienvenida") }.map { Json.s(Json.obj(it["target"])?.get("value")) }.toSet()
        name = Nova.name(); city = ""
        Prefs.set("devWelcomeDone", true); Inicio.mark()
        // (las novedades de esta versión son para quien actualiza, no para quien acaba de llegar)
        val mm = Ajustes.version().split(".").take(2).joinToString("."); if (mm.isNotEmpty() && !again0) Prefs.set("newsSeen", mm)
        Ventanas.open("onb")
        show(0)
    }

    fun close() {
        if ("onb" !in Ventanas.stack) return
        Ventanas.gone("onb"); gen++; Prefs.set("onboarded", true); Voz.stop()
    }
    /** «atrás»: el paso de antes (en el primero, fuera). */
    fun back() { if (step > 0) show(step - 1, true) else close() }

    fun count(): Pair<Int, Int> { val dev = Lib.tracks.filter { it.dev }; return dev.size to dev.map { Biblio.artistKey(it.artist) }.filter { it.isNotEmpty() }.toSet().size }
    fun styles(): List<String> {
        val n = HashMap<String, Int>(); for (t in Lib.tracks) if (t.dev) for (g in Cerebro.trackGenres(t)) n[g] = (n[g] ?: 0) + 1
        return Cerebro.GENRES.mapIndexed { i, (g, _) -> Triple(g, n[g] ?: 0, i) }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }
    }

    fun show(i: Int, quiet: Boolean = false) {
        step = max(0, min(STEPS.size - 1, i)); gen++
        if (quiet) return
        val k = STEPS[step]
        val n = Nova.name()
        val say = when (k) {
            "hola" -> "¡Hola! Soy $n, tu DJ. En un minuto lo dejamos todo listo."
            "musica" -> "Primero, tu música. Dame permiso y la busco en tu móvil, sin copiar nada."
            "gustos" -> "¿Qué estilos te gustan? Elige los que quieras."
            "dj" -> "¿Cómo quieres que me llame? ¿Y cuánto hablo entre canciones?"
            else -> "Todo listo. ¿Te pongo tu primera mezcla?"
        }
        Voz.stop(); if (Nova.voiceOn()) Nova.djTalk(say, "test", forceTTS = true) else Nova.setBubble(say)
    }

    fun toggleGenre(g: String) { genres = if (g in genres) genres - g else genres + g }
    fun pickEnergy(e: String) { energy = e }
    fun setFreq(v: String) {
        if (v == "nunca") Prefs.set("intros", false) else { Prefs.set("intros", true); Prefs.set("djFreq", v) }
        Ver.bump(); Inicio.mark()
    }

    fun perm() {
        if (scanning) return
        scanning = true
        Biblio.connect()
        val my = gen
        val poll = object : Runnable { override fun run() {
            if (my != gen && !scanning) return
            val st = Biblio.devState()
            if ((st == "granted" && !Biblio.scanning && Lib.tracks.isNotEmpty()) || st == "denied" || (st == "granted" && !Biblio.scanning)) {
                scanning = false
                if ("onb" !in Ventanas.stack || STEPS[step] != "musica") return
                val (songs, _) = count()
                val line = if (st == "granted") (if (songs > 0) "¡Genial! He encontrado $songs canciones." else "No he encontrado canciones en el móvil, pero no pasa nada.") else "Vale, sin tu música por ahora. Puedes darme permiso más tarde."
                Voz.stop(); if (Nova.voiceOn()) Nova.djTalk(line, "test", forceTTS = true)
                return
            }
            main.postDelayed(this, 400)
        } }
        main.postDelayed(poll, 600)
    }

    private fun saveTastes() {
        val keep = Cerebro.prefList().filter { !Json.s(it["said"]).startsWith("Bienvenida") }
        val add = genres.toList().mapIndexed { i, g -> linkedMapOf<String, Any?>("id" to "p${System.currentTimeMillis()}$i", "kind" to "boost", "target" to linkedMapOf("type" to "genre", "value" to g, "name" to g), "when" to null, "said" to "Bienvenida: me gusta $g") }
        Cerebro.savePrefs(keep + add)
    }
    private fun saveDj() {
        val n = name.replace(Regex("[<>]"), "").trim().take(20)
        if (n.isNotEmpty() && n != Nova.name()) { Prefs.set("djName", n); Ver.bump(); Inicio.mark() }
        val c = city.trim(); if (c.isNotEmpty() && Online.on()) obCity(c)
    }
    private fun obCity(n: String) {
        Thread {
            try {
                val j = Json.obj(Online.getJson("https://geocoding-api.open-meteo.com/v1/search?name=${Online.enc(n)}&count=1&language=es&format=json"))
                val r = Json.list(j?.get("results"))?.firstOrNull()?.let { Json.obj(it) }
                if (r == null) { Nativo.toast("No encuentro «$n». Puedes ponerla luego en Ajustes.", 3600); return@Thread }
                Prefs.set("city", linkedMapOf("name" to Json.s(r["name"]), "lat" to Json.d(r["latitude"]), "lon" to Json.d(r["longitude"]), "country" to Json.s(r["country"]))); Prefs.set("wx", null)
                main.post { Ver.bump(); Online.loadWeather(true) }
            } catch (ignored: Throwable) { }
        }.start()
    }

    fun next() {
        when (STEPS[step]) { "gustos" -> saveTastes(); "dj" -> saveDj() }
        show(step + 1)
    }
    fun skip() { when (STEPS[step]) { "gustos" -> saveTastes(); "dj" -> saveDj() }; close() }
    fun firstMix() {
        val gs = genres.take(3)
        val req = (if (gs.isNotEmpty()) Cerebro.listES(gs) else "una mezcla variada para empezar") + (if (energy == "calma") ", tranquila" else if (energy == "energia") ", con energía" else "")
        close(); Nativo.go("inicio")
        main.postDelayed({ Ordenes.mixFromChat(req) }, 250)
    }
}

@Composable
internal fun OnbWindow(sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val vv = Ver.n
    val st = Bienvenida.step
    val k = Bienvenida.STEPS[st]
    val hue by animateFloatAsState(listOf(0f, 60f, 150f, 230f, 310f)[st], tween(900), label = "h")
    Box(Modifier.fillMaxSize().background(Color(0xFF06050B)).pointerBlock()) {
        FloatBg(sk, hue)
        // los pasos (puntos) y «Saltar»
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in Bienvenida.STEPS.indices) {
                    val on = n == st
                    val w by animateFloatAsState(if (on) 26f else 8f, tween(300), label = "w$n")
                    Box(Modifier.width(w.dp).height(8.dp).clip(RoundedCornerShape(8.dp)).then(if (on) Modifier.background(p.grad) else Modifier.background(Color.White.copy(alpha = if (n < st) 0.6f else 0.22f))))
                }
            }
            Box(Modifier.press { Bienvenida.skip() }.padding(horizontal = 6.dp, vertical = 10.dp)) { T("Saltar", 15f * sk.tk, p.muted, weight = FontWeight.Bold, maxLines = 1) }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 72.dp)) {
            val vw = maxWidth.value
            key(st) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 26.dp + Bar.inset.dp), verticalArrangement = Arrangement.Center) {
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OnbStep(k, sk, vw)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnbStep(k: String, sk: Skin, vw: Float) {
    val p = sk.pal
    val hFs = min(max(32f * sk.tk, vw * 0.09f), 52.8f * sk.tk)
    @Composable fun h(t: String) = T(t, hFs, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1.04f, ls = -0.035f)
    @Composable fun para(t: String) = T(t, 17.6f * sk.tk, Color(0xDBF7F2FF), lh = 1.45f)
    @Composable fun of(i: Int) = Eyebrow("Paso $i de 4", sk)
    @Composable fun btns(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    @Composable fun res(t: String, ok: Boolean = false, spin: Boolean = false) {
        Row(Modifier.fillMaxWidth().clip(sk.r(20f)).background(Color.White.copy(alpha = 0.07f)).border(sk.b, if (ok) p.good else p.line, sk.r(20f)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (spin) Spinner(sk)
            T(t, 15f * sk.tk, p.fg, lh = 1.45f)
        }
    }
    when (k) {
        "hola" -> {
            Box(Modifier.padding(bottom = 6.dp)) { BigAvatar(sk, 96f) }
            Eyebrow("Bienvenida", sk)
            h("Hola, soy ${Nova.name()}")
            para("Soy tu DJ. Pongo tu música, la mezclo sin cortes y te hablo entre canciones, como en la radio. En un minuto lo dejamos listo.")
            btns { HBtn("Empezar", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Bienvenida.next() } }
        }
        "musica" -> {
            of(1); h("Tu música")
            para("Pongo las canciones que ya tienes en el móvil, directamente: sin copiarlas ni ocupar espacio. Para verlas, Android te pedirá permiso.")
            val st = Biblio.devState(); val (songs, arts) = Bienvenida.count()
            if (Bienvenida.scanning) res("Buscando tu música…", spin = true)
            else if (st == "granted") { if (songs > 0) res("He encontrado ${Recap.nES(songs.toDouble())} ${if (songs == 1) "canción" else "canciones"} de ${Recap.nES(arts.toDouble())} ${if (arts == 1) "artista" else "artistas"}.", ok = true) else res("No he encontrado canciones en el móvil. Puedes añadirlas desde Biblioteca, y mientras tanto escuchar la radio o vídeos de YouTube.") }
            else if (st == "denied") res("Sin permiso no puedo ver tu música. Puedes dármelo cuando quieras en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio.")
            btns {
                if (st == "granted" || st == "denied") HBtn("Siguiente", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Bienvenida.next() }
                else { HBtn("Dar acceso a mi música", sk, Modifier.fillMaxWidth(), primary = true, kind = 2, enabled = !Bienvenida.scanning) { Bienvenida.perm() }; HBtn("Ahora no", sk, Modifier.fillMaxWidth()) { Bienvenida.next() } }
            }
        }
        "gustos" -> {
            of(2); h("¿Qué te gusta?")
            para("Elige los estilos que más escuchas. Los tendré en cuenta en tus mezclas y en la radio.")
            HFlow(8.dp, 8.dp, Modifier.fillMaxWidth()) { for (g in Bienvenida.styles()) Chip(Cerebro.cap(g), g in Bienvenida.genres, sk) { Bienvenida.toggleGenre(g) } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Lbl("¿Y cómo te gusta?", sk)
                Seg(listOf("calma" to "Tranquila", "" to "De todo", "energia" to "Con energía"), Bienvenida.energy, sk, stretch = true, padH = 6.dp) { Bienvenida.pickEnergy(it) }
            }
            btns { HBtn("Siguiente", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Bienvenida.next() } }
        }
        "dj" -> {
            of(3); h("Tu DJ")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("¿Cómo quieres llamarme?", 14.4f * sk.tk, p.muted, weight = FontWeight.SemiBold)
                Field(Bienvenida.name, { Bienvenida.name = it.take(20) }, sk, Modifier.fillMaxWidth(), mono = false, size = 16f, padH = 14.dp, padV = 12.dp, radius = 12f, bg = Color.Black.copy(alpha = 0.35f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Lbl("¿Cuánto hablo entre canciones?", sk)
                val f = if (!Nova.intros()) "nunca" else Nova.freq()
                Seg(listOf("siempre" to "En cada una", "aveces" to "A veces", "inicio" to "Al empezar", "nunca" to "Nada"), f, sk, stretch = true, padH = 6.dp) { Bienvenida.setFreq(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T("Tu ciudad ", 14.4f * sk.tk, p.muted, weight = FontWeight.SemiBold)
                    T("(opcional, para decirte qué tiempo hace)", 14.4f * sk.tk, p.faint, weight = FontWeight.Medium)
                }
                val c = Json.obj(Prefs.get("city"))
                Field(Bienvenida.city, { Bienvenida.city = it.take(60) }, sk, Modifier.fillMaxWidth(), placeholder = if (c != null) Json.s(c["name"]) else "Por ejemplo, Sevilla", mono = false, size = 16f, padH = 14.dp, padV = 12.dp, radius = 12f,
                    bg = Color.Black.copy(alpha = 0.35f), onDone = { Bienvenida.next() })
            }
            btns { HBtn("Siguiente", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Bienvenida.next() } }
        }
        else -> {
            val (songs, _) = Bienvenida.count(); val gs = Bienvenida.genres.take(3)
            val what = (if (songs > 0) "Tengo tus ${Recap.nES(songs.toDouble())} canciones" else "Todo preparado") + (if (gs.isNotEmpty()) " y sé que te ${if (gs.size == 1) "gusta" else "gustan"} ${Cerebro.listES(gs)}." else ".")
            Box(Modifier.padding(bottom = 6.dp)) { BigAvatar(sk, 96f) }
            of(4); h("Todo listo"); para(what)
            Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tip in listOf("Pídeme lo que quieras: «ponme algo para correr», «música para cenar».", "Gira la ruleta si no sabes qué escuchar.", "En «Tú» verás lo que escuchas y, a final de año, tu resumen.")) Row {
                    Box(Modifier.width((16.5f * sk.tk).dp).padding(top = 9.dp), contentAlignment = Alignment.TopStart) { Box(Modifier.size(5.dp).background(Color(0xD1F7F2FF), CircleShape)) }
                    T(tip, 15f * sk.tk, Color(0xD1F7F2FF), lh = 1.4f)
                }
            }
            btns {
                if (Cerebro.pool().size >= 2) HBtn("Ponme mi primera mezcla", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Bienvenida.firstMix() }
                else HBtn("Añadir mi música", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Bienvenida.close(); Nativo.go("biblioteca") }
                HBtn("Explorar la app", sk, Modifier.fillMaxWidth()) { Bienvenida.close(); Nativo.go("inicio") }
            }
        }
    }
}

/** El círculo que gira mientras se busca algo. */
@Composable
internal fun Spinner(sk: Skin, size: Float = 16f) {
    val p = sk.pal
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "sp")
    val a by tr.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "a")
    androidx.compose.foundation.Canvas(Modifier.size(size.dp).graphicsLayer { rotationZ = a }) {
        val w = 2.dp.toPx()
        drawCircle(p.line2, this.size.minDimension / 2 - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(p.accent, -90f, 90f, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    }
}
