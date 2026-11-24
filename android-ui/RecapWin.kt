package __PACKAGE__

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fase 4, parte 2 · «Tu año en Órbita» hecho por Android: las tarjetas del año (tu tiempo, tu canción, tu top, tu
 * artista, cuándo suena tu música, tus récords, tu DJ y qué tipo de oyente has sido), contadas por Nova, con sus barras
 * que avanzan solas (tocar a la izquierda vuelve, a la derecha sigue, mantener pulsado para), y la imagen para compartir
 * (1080 × 1920, el tamaño de una historia) dibujada igual que en la web.
 */
object RcWin {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    class Slide(val k: String, val h: Int, val say: String, val last: Boolean = false)
    var s: Recap.Stats? = null; private set
    var slides: List<Slide> = emptyList(); private set
    var i by mutableIntStateOf(0); private set
    var paused by mutableStateOf(false); private set
    var prog by mutableFloatStateOf(0f); private set
    var ver by mutableIntStateOf(0); private set
    private var gen = 0
    private var t0 = 0L; private var dur = 6500L; private var pausedAt = 0L
    private var spoken = true; private var sayOn = false; private var say0 = 0L
    var img by mutableStateOf<Bitmap?>(null); private set
    var imgErr by mutableStateOf(false); private set
    private var imgBytes: ByteArray? = null

    fun voiceOn(): Boolean = Prefs.bool("recapVoice", true) && Nova.voiceOn()
    fun toggleVoice() { Prefs.set("recapVoice", !Prefs.bool("recapVoice", true)); ver++; if (!voiceOn()) { Voz.stop(); spoken = true } }

    // ---------------------------------------------------------------- las frases
    private fun equiv(sec: Double): String = when {
        sec >= 86400 * 1.5 -> "Eso son ${(Math.round(sec / 8640) / 10.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString().replace('.', ',') }} días enteros sin parar."
        sec >= 86400 -> "Eso es más de un día entero sin parar."
        sec >= 7200 -> "Como ver ${Recap.nES(sec / 6600)} películas seguidas."
        else -> "Y esto no ha hecho más que empezar."
    }
    fun artistOk(a: String?) = !a.isNullOrEmpty() && a != "Artista desconocido"
    fun song(r: Memoria.TrackStat, mid: Boolean = false) = if (artistOk(r.artist)) "«${r.title}», de ${r.artist}${if (mid) "," else ""}" else "«${r.title}»"
    fun veces(n: Int) = if (n == 1) "una vez" else "${Recap.nES(n.toDouble())} veces"
    private fun fmtLong(s0: Double): String { val s = Math.round(s0); if (s < 60) return "$s s"; val h = s / 3600; val m = Math.round((s % 3600) / 60.0); return if (h > 0) "$h h${if (m > 0) " $m min" else ""}" else "$m min" }
    private fun spokenDur(s: Double): String { val h = Math.floor(s / 3600).toInt(); val m = Math.round((s % 3600) / 60).toInt(); return if (h > 0) "$h ${if (h == 1) "hora" else "horas"}${if (m > 0) " y $m minutos" else ""}" else "${max(1, m)} minutos" }
    private fun argmax(a: DoubleArray): Int { var b = 0; for (k in a.indices) if (a[k] > a[b]) b = k; return b }
    fun franja(h: Int): Momento.Franja = Momento.FRANJAS.firstOrNull { h >= it.from && h < it.to } ?: Momento.FRANJAS[0]
    /** «recapType»: qué tipo de oyente has sido. */
    fun type(s: Recap.Stats): Pair<String, String> {
        val tot = if (s.sec > 0) s.sec else 1.0
        fun sum(f: Int, t: Int) = (f until t).sumOf { s.hours[it] }
        val night = (sum(0, 5) + s.hours[23]) / tot; val morning = sum(6, 10) / tot; val top = if (s.artists.isNotEmpty()) s.artists[0].sec / tot else 0.0
        return when {
            night > 0.3 -> "Alma nocturna" to "Tu música suena mejor cuando el resto duerme."
            morning > 0.3 -> "Sol de mañana" to "Empiezas el día con banda sonora."
            top > 0.35 -> "Corazón fiel" to "Cuando algo te gusta, no lo sueltas. Y ${s.artists[0].name} lo sabe."
            s.nArtists >= 15 && s.nArtists.toDouble() / max(1, s.plays) > 0.4 -> "Espíritu explorador" to "Siempre a la caza de algo nuevo."
            s.radioSec / tot > 0.35 -> "Alma de radio" to "Lo tuyo es dejarte sorprender por las emisoras."
            s.novaSec / tot > 0.5 -> "En buenas manos" to "Dejaste que ${Nova.name()} eligiera más de la mitad de lo que sonó."
            else -> "Todoterreno" to "Un poco de todo, a cualquier hora. Así da gusto pinchar."
        }
    }
    private fun build(s: Recap.Stats): List<Slide> {
        val y = s.y; val out = ArrayList<Slide>(); val (hn, hu) = Recap.hours(s.sec)
        out.add(Slide("hola", 0, "Hola. He estado tomando nota de todo lo que has escuchado en $y. ¿Vemos tu año?"))
        out.add(Slide("tiempo", 40, "Has escuchado $hn $hu de música. ${equiv(s.sec)}"))
        s.tracks.getOrNull(0)?.let { t1 -> out.add(Slide("cancion", 90, "Tu canción del año es ${song(t1)}. La pusiste ${veces(t1.plays)}.")) }
        if (s.tracks.size >= 3) out.add(Slide("top5", 140, "Detrás vienen ${song(s.tracks[1], true)} y ${song(s.tracks[2])}."))
        s.artists.firstOrNull { artistOk(it.name) }?.let { a1 -> out.add(Slide("artista", 200, "Tu artista del año es ${a1.name}. Le dedicaste ${Recap.hoursTxt(a1.sec)}.")) }
        val pm = argmax(s.months); val ph = argmax(s.hours); val pd = argmax(s.dows); val fr = franja(ph).phrase
        out.add(Slide("cuando", 260, "Tu mes fue ${Recap.MESES[pm]}. Escuchas sobre todo $fr, y tu día es el ${Momento.DAYS[pd]}."))
        val recSay = ArrayList<String>()
        if (s.streak.n >= 2) recSay.add("Tu mejor racha fue de ${s.streak.n} días seguidos con música.")
        val bd = s.bigDay; if (bd != null && bd.sec >= 1800) recSay.add("Y el ${Recap.fmtDia(bd.ts)} fue tu gran día: ${spokenDur(bd.sec)} de música.")
        val ob = s.obs; if (ob != null) s.tracks.firstOrNull { it.id == ob.id }?.let { r -> recSay.add("El ${Recap.fmtDia(ob.ts)} pusiste ${song(r, true)} ${ob.n} veces. Te entiendo.") }
        if (recSay.isNotEmpty()) out.add(Slide("records", 310, recSay.joinToString(" ")))
        if (s.novaSec / (if (s.sec > 0) s.sec else 1.0) >= 0.1) out.add(Slide("nova", 330, "Una cosa más: el ${Math.round(s.novaSec / s.sec * 100)} por ciento de lo que sonó lo elegí yo. Me lo pasé genial pinchando para ti."))
        val (tl, td) = type(s)
        out.add(Slide("tipo", 20, "Este año has sido… $tl. $td"))
        val now = java.util.Calendar.getInstance()
        val later = y == now.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.MONTH) < 11
        out.add(Slide("fin", 0, if (later) "Gracias por dejarme ser tu DJ. Y lo que queda de año, a seguir sumando." else "Gracias por dejarme ser tu DJ. ¡A por un ${y + 1} con más música!", last = true))
        return out
    }

    // ---------------------------------------------------------------- abrir, pasar, cerrar
    fun start(st: Recap.Stats) {
        s = st; slides = build(st); i = 0; paused = false; img = null; imgErr = false; imgBytes = null; gen++
        Ventanas.open("recap")
        // (la música de debajo: si no suena nada, tus canciones del año)
        val playing = Player.current() != null && Player.playing
        var started = false
        if (!playing) { val ids = st.tracks.map { it.id }.filter { Lib.byId(it) != null }.take(30); if (ids.isNotEmpty()) { started = true; Player.playQueue(ids, 0, "library") } }
        val my = gen
        main.postDelayed({ if (my == gen) show(0) }, if (started) 700 else 150)
        loop(my)
    }
    fun show(k: Int) {
        if ("recap" !in Ventanas.stack) return
        val ni = k.coerceIn(0, max(0, slides.size - 1)); i = ni; ver++
        val sl = slides.getOrNull(ni) ?: return
        t0 = SystemClock.uptimeMillis(); pausedAt = if (paused) t0 else 0L
        dur = min(16000L, max(6500L, 1500L + sl.say.length * 62L))
        Voz.stop(); spoken = true; sayOn = false
        if (voiceOn() && sl.say.isNotEmpty()) { spoken = false; say0 = SystemClock.uptimeMillis(); Voz.speak(sl.say) } else Nova.setBubble(sl.say)
        if (sl.k == "fin") prepareCard()
    }
    private fun loop(my: Int) {
        main.postDelayed({
            if (my != gen || "recap" !in Ventanas.stack) return@postDelayed
            val sl = slides.getOrNull(i)
            if (sl != null && !paused) {
                val now = SystemClock.uptimeMillis()
                val f = min(1f, (now - t0).toFloat() / dur)
                prog = if (sl.last) 1f else f
                // (la voz: cuando empieza y cuando acaba; si no habla en 3 s, o 12 s tras su tiempo, se sigue)
                if (!spoken) { if (Voz.speaking) sayOn = true else if (sayOn || now - say0 > 3000) spoken = true }
                if (!spoken && now - t0 > dur + 12000) spoken = true
                if (f >= 1 && spoken && !sl.last) show(i + 1)
            }
            loop(my)
        }, 33)
    }
    fun pause(on: Boolean) {
        if (paused == on) return
        paused = on; val now = SystemClock.uptimeMillis()
        if (on) pausedAt = now else { t0 += now - (if (pausedAt > 0) pausedAt else now); pausedAt = 0 }
    }
    fun close() { if ("recap" !in Ventanas.stack) return; gen++; Voz.stop(); Ventanas.gone("recap"); Paginas.mark("tu") }
    fun tap(x: Float) { pause(false); val ni = i + (if (x < 0.3f) -1 else 1); if (ni < slides.size) show(ni) }

    // lo que se toca dentro
    fun playTop() {
        val st = s ?: return; val id = st.tracks.getOrNull(0)?.id ?: return
        if (Lib.byId(id) == null) return
        val t = Player.current()
        if (t == null || t.id != id) Player.playQueue(listOf(id) + st.tracks.drop(1).take(29).map { it.id }.filter { Lib.byId(it) != null }, 0, "library")
        else if (!Player.playing) Player.toggle()
    }
    fun listen() {
        val st = s ?: return
        val ids = st.tracks.map { it.id }.filter { Lib.byId(it) != null }.take(50)
        if (ids.isEmpty()) { Nativo.toast("Esas canciones ya no están en tu biblioteca"); return }
        val name = "Tu ${st.y}"
        val old = Lib.playlists.firstOrNull { Json.s(it["name"]) == name && Json.s(it["source"]) == "recap" }
        val p = LinkedHashMap(old ?: mapOf("id" to Listas.uid("p"), "name" to name, "createdAt" to System.currentTimeMillis(), "source" to "recap"))
        p["description"] = "Las ${ids.size} canciones que más escuchaste en ${st.y}."; p["trackIds"] = ids
        Listas.put(p)
        close(); Player.playQueue(ids, 0, "library"); Nativo.toast("Suena «$name» · guardada en Mis listas", 3600)
    }
    fun share(save: Boolean) {
        val st = s ?: return
        val go = {
            val b = imgBytes
            if (b != null) {
                pause(true)
                val t1 = st.tracks.getOrNull(0)
                val text = "Mi ${st.y} en Órbita: ${Recap.hoursTxt(st.sec)} de música${if (t1 != null) ". Mi canción del año: «${t1.title}»${if (artistOk(t1.artist)) ", de ${t1.artist}" else ""}" else ""}."
                if (save) Compartir.saveImage(b, "orbita-${st.y}.jpg", "image/jpeg")
                else Compartir.share(b, "orbita-${st.y}.jpg", "image/jpeg", text, "Tu ${st.y} en Órbita")
            }
        }
        if (imgBytes == null) prepareCard { go() } else go()
    }

    // ---------------------------------------------------------------- la imagen para compartir
    private fun prepareCard(then: (() -> Unit)? = null) {
        if (imgBytes != null) { then?.invoke(); return }
        val st = s ?: return; val my = gen
        io.execute {
            val r = try {
                val bmp = image(st)
                val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 92, bo)
                bmp to bo.toByteArray()
            } catch (e: Throwable) { Nativo.note("tu año: ${e.message}"); null }
            main.post {
                if (my != gen) return@post
                if (r == null) { imgErr = true; return@post }
                img = r.first; imgBytes = r.second; then?.invoke()
            }
        }
    }
    private fun font(id: Int): Typeface = Nativo.act?.let { androidx.core.content.res.ResourcesCompat.getFont(it, id) } ?: Typeface.DEFAULT
    private fun hslI(h: Double, s: Double, l: Double): Int { val c = hsl(h.toFloat(), s.toFloat(), l.toFloat()); return android.graphics.Color.argb(255, (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt()) }
    /** «recapImage»: la tarjeta, como la dibuja la web (mismas medidas, letras y colores). */
    fun image(s: Recap.Stats): Bitmap {
        val W = 1080; val H = 1920
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(bmp)
        val hue = UiState.look.h.toDouble(); val sat = max(0.2, UiState.look.s.toDouble())
        val a1 = hslI(hue, 0.96 * sat, 0.64); val a2 = hslI(hue - 45, 0.92 * sat, 0.66); val a3 = hslI(hue + 150, 0.85 * sat, 0.58)
        val unb9 = font(R.font.unbounded_900); val unb7 = font(R.font.unbounded_700); val fig8 = font(R.font.figtree_800); val fig7 = font(R.font.figtree_700); val fig6 = font(R.font.figtree_600); val mono6 = font(R.font.plexmono_600)
        c.drawColor(android.graphics.Color.rgb(6, 5, 11))
        fun glow(x: Float, y: Float, r: Float, col: Int, alpha: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG); p.shader = RadialGradient(x, y, r, intArrayOf(col, android.graphics.Color.argb(0, 6, 5, 11)), null, Shader.TileMode.CLAMP); p.alpha = (alpha * 255).roundToInt()
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        }
        glow(980f, 180f, 820f, a1, 0.55f); glow(60f, 1500f, 900f, a2, 0.4f); glow(900f, 1700f, 600f, a3, 0.22f)
        val M = 84f
        fun paint(tf: Typeface, size: Float, color: Int, ls: Float = 0f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color; if (ls != 0f) letterSpacing = ls / size }
        fun grad(p: Paint, x0: Float, x1: Float) { p.shader = LinearGradient(x0, 0f, x1, 0f, a1, a2, Shader.TileMode.CLAMP) }
        fun fit(p: Paint, text: String, maxW: Float): String {
            if (p.measureText(text) <= maxW) return text
            var lo = 0; var hi = text.length
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (p.measureText(text.substring(0, m) + "…") <= maxW) lo = m else hi = m - 1 }
            return text.substring(0, lo).trimEnd() + "…"
        }
        fun wrap(p: Paint, text: String, maxW: Float, lines: Int): List<String> {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }; val out = ArrayList<String>(); var cur = ""
            var k = 0
            while (k < words.size) {
                val tryL = if (cur.isNotEmpty()) "$cur ${words[k]}" else words[k]
                if (p.measureText(tryL) <= maxW || cur.isEmpty()) cur = tryL
                else { out.add(cur); cur = words[k]; if (out.size == lines - 1) { cur = words.drop(k).joinToString(" "); break } }
                k++
            }
            if (cur.isNotEmpty()) out.add(cur)
            return out.take(lines).mapIndexed { ix, l -> if (ix == min(lines, out.size) - 1) fit(p, l, maxW) else l }
        }
        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, stroke: Int? = null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = fill; c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p)
            if (stroke != null) { val ps = Paint(Paint.ANTI_ALIAS_FLAG); ps.style = Paint.Style.STROKE; ps.strokeWidth = 2f; ps.color = stroke; c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, ps) }
        }
        val fg = android.graphics.Color.rgb(247, 242, 255)
        fun fga(a: Float) = android.graphics.Color.argb((a * 255).roundToInt(), 247, 242, 255)
        c.drawText("ÓRBITA · TU MÚSICA, TU DJ", M, 150f, paint(mono6, 28f, fga(0.72f), 6f))
        paint(unb9, 168f, fg).also { grad(it, M, M + 800); c.drawText("Mi ${s.y}", M - 6, 330f, it) }
        c.drawText("en Órbita", M, 408f, paint(fig7, 56f, fg))
        // el tiempo
        val (hn, hu) = Recap.hours(s.sec)
        val pBig = paint(unb9, 200f, fg); c.drawText(hn, M - 8, 650f, pBig)
        val hw = pBig.measureText(hn); val pU = paint(fig8, 50f, a1)
        val unitX = M + hw + 26
        if (unitX + pU.measureText(hu) < W - M) { c.drawText(hu, unitX, 586f, pU); pU.color = fga(0.8f); c.drawText("de música", unitX, 646f, pU) }
        else c.drawText("$hu de música", M, 720f, pU)
        c.drawText("${Recap.nES(s.plays.toDouble())} canciones · ${Recap.nES(s.nArtists.toDouble())} ${if (s.nArtists == 1) "artista" else "artistas"} · ${Recap.nES(s.nDays.toDouble())} días con música", M, 770f, paint(fig6, 38f, fga(0.66f)))
        // la canción del año
        s.tracks.getOrNull(0)?.let { t1 ->
            val y0 = 830f; val h0 = 300f
            rrect(M, y0, W - 2 * M, h0, 36f, android.graphics.Color.argb(18, 255, 255, 255), android.graphics.Color.argb(31, 255, 255, 255))
            val cs = 236f; val cx = M + 32; val cy = y0 + 32; val t = Lib.byId(t1.id)
            c.save(); val clip = android.graphics.Path(); clip.addRoundRect(RectF(cx, cy, cx + cs, cy + cs), 26f, 26f, android.graphics.Path.Direction.CW); c.clipPath(clip)
            var drew = false
            val f = t?.let { Lib.bigCoverFile(it) ?: Lib.coverFile(it) }
            if (f != null) try {
                val im = BitmapFactory.decodeFile(f.absolutePath)
                if (im != null) { val sc = max(cs / im.width, cs / im.height); val w = im.width * sc; val h = im.height * sc; c.drawBitmap(im, null, RectF(cx + (cs - w) / 2, cy + (cs - h) / 2, cx + (cs + w) / 2, cy + (cs + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG)); drew = true }
            } catch (ignored: Throwable) { }
            if (!drew) {
                val ph = Momento.phHue(t)
                val p = Paint(Paint.ANTI_ALIAS_FLAG); p.shader = LinearGradient(cx, cy, cx + cs, cy + cs, hslI(ph, 0.7, 0.55), hslI(ph + 50, 0.7, 0.3), Shader.TileMode.CLAMP)
                c.drawRect(cx, cy, cx + cs, cy + cs, p)
                val pt = paint(unb9, 120f, android.graphics.Color.argb(230, 255, 255, 255)); pt.textAlign = Paint.Align.CENTER
                c.drawText(((t1.title.trim().take(1)).ifEmpty { "♪" }).uppercase(), cx + cs / 2, cy + cs / 2 + 44, pt)
            }
            c.restore()
            val tx = cx + cs + 36; val tw = W - M - 32 - tx
            c.drawText("CANCIÓN DEL AÑO", tx, cy + 34, paint(mono6, 26f, a1, 4f))
            val pT = paint(unb7, 50f, fg); val tl = wrap(pT, t1.title, tw, 2); tl.forEachIndexed { ix, l -> c.drawText(l, tx, cy + 104 + ix * 62, pT) }
            var yy = cy + 104 + tl.size * 62
            if (artistOk(t1.artist)) { val pa = paint(fig6, 36f, fga(0.72f)); c.drawText(fit(pa, t1.artist, tw), tx, yy, pa); yy += 52 }
            c.drawText(veces(t1.plays), tx, min(yy, cy + cs - 4), paint(fig8, 34f, a2))
        }
        // el top 5 y los artistas
        val colW = (W - 2 * M - 40) / 2; val y1 = 1210f
        fun col(x: Float, head: String, rows: List<String>) {
            c.drawText(head, x, y1, paint(mono6, 26f, fga(0.6f), 4f))
            rows.forEachIndexed { ix, r ->
                val yy = y1 + 74 + ix * 66
                c.drawText("${ix + 1}", x, yy, paint(unb9, 34f, if (ix > 0) fga(0.5f) else a1))
                val pr = paint(fig7, 36f, fg); c.drawText(fit(pr, r, colW - 56), x + 52, yy, pr)
            }
        }
        col(M, "MIS CANCIONES", s.tracks.take(5).map { it.title })
        col(M + colW + 40, "MIS ARTISTAS", s.artists.filter { artistOk(it.name) }.take(5).map { it.name })
        // qué tipo de oyente
        val (tl) = type(s); val y2 = 1600f
        rrect(M, y2, W - 2 * M, 170f, 36f, android.graphics.Color.argb(18, 255, 255, 255))
        c.drawText("ESTE AÑO HE SIDO", M + 36, y2 + 56, paint(mono6, 26f, fga(0.6f), 4f))
        paint(unb9, 62f, fg).also { grad(it, M + 36, M + 700); c.drawText(fit(it, tl, W - 2 * M - 72), M + 32, y2 + 130, it) }
        val pd = argmax(s.dows); val ph = argmax(s.hours); val day = Momento.DAYS[pd]
        val pf = paint(fig6, 32f, fga(0.7f))
        c.drawText(fit(pf, listOf(Cerebro.cap(franja(ph).phrase), "los " + day + (if (day.endsWith("s")) "" else "s"), if (s.streak.n >= 2) "racha de ${s.streak.n} días" else "").filter { it.isNotEmpty() }.joinToString(" · "), W - 2 * M), M, 1830f, pf)
        c.drawText("DJ: ${Nova.name().uppercase()}", M, 1880f, paint(mono6, 24f, fga(0.45f), 3f))
        return bmp
    }
}

/** Compartir y guardar imágenes (lo que la web pedía a «MusicLibrary»: «shareFile» y «saveImage»). */
object Compartir {
    fun share(bytes: ByteArray, name: String, mime: String, text: String, title: String) {
        val a = Nativo.act ?: return
        try {
            val dir = File(a.cacheDir, "compartir"); dir.mkdirs()
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
            val f = File(dir, name); f.writeBytes(bytes)
            val uri: Uri = try { androidx.core.content.FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f) } catch (t: Throwable) { insertImage(bytes, name, mime) ?: throw t }
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
            send.clipData = android.content.ClipData.newRawUri(name, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val ch = Intent.createChooser(send, title); ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(ch)
        } catch (e: Throwable) { Nativo.toast("No he podido compartir la imagen", 3800) }
    }
    private fun insertImage(bytes: ByteArray, name: String, mime: String): Uri? {
        val a = Nativo.act ?: return null
        if (Build.VERSION.SDK_INT < 29) return null
        val v = ContentValues(); v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, mime); v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Orbita")
        val uri = a.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        a.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        return uri
    }
    fun saveImage(bytes: ByteArray, name: String, mime: String) {
        val a = Nativo.act ?: return
        try {
            val where = if (Build.VERSION.SDK_INT >= 29) { insertImage(bytes, name, mime) ?: throw IllegalStateException("insert"); "Galería › Orbita" }
            else { val dir = a.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: a.filesDir; dir.mkdirs(); val f = File(dir, name); f.writeBytes(bytes); f.absolutePath }
            Nativo.toast("Imagen guardada en $where", 4200)
        } catch (e: Throwable) { Nativo.toast("No he podido guardar la imagen", 3800) }
    }
}

// ================================================================ la ventana
private val RC_EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
internal fun RecapWindow(sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val v = RcWin.ver
    val st = RcWin.s ?: return
    val sl = RcWin.slides.getOrNull(RcWin.i)
    Box(Modifier.fillMaxSize().background(Color(0xFF06050B))) {
        key(sl?.h ?: 0) { FloatBg(sk, (sl?.h ?: 0).toFloat()) }
        // (tocar: a la izquierda vuelve, a la derecha sigue; mantener pulsado, se para)
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown(requireUnconsumed = false)
                val t0 = SystemClock.uptimeMillis(); var held = false
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                while (true) {
                    val ev = awaitPointerEvent()
                    if (!held && SystemClock.uptimeMillis() - t0 > 280) { held = true; RcWin.pause(true) }
                    val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                    if (!ch.pressed) { up = ch; break }
                }
                if (held) RcWin.pause(false) else if (up != null) RcWin.tap(up.position.x / size.width)
            }
        })
        // la tarjeta de ahora
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 100.dp, bottom = max(30f, Bar.inset.toFloat()).dp), contentAlignment = Alignment.Center) {
            val vw = maxWidth.value + 44f; val vh = maxHeight.value + 130f + max(30f, Bar.inset.toFloat())
            if (sl != null) key(RcWin.i, sl.k) { RcSlide(sl, st, sk, vw, vh) }
        }
        // arriba: las barras de cada tarjeta, la voz y cerrar
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = max(12f, Nativo.topInset()).dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().graphicsLayer { alpha = if (RcWin.paused) 0.5f else 1f }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RcWin.slides.forEachIndexed { k, _ ->
                    val f = if (k < RcWin.i) 1f else if (k == RcWin.i) RcWin.prog else 0f
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f))) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color.White)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                val vOn = RcWin.voiceOn(); val en = Nova.voiceOn()
                RcIc(sk, if (vOn) "M11 5L6 9H2v6h4l5 4V5zM15.5 8.5a5 5 0 010 7M19 5a10 10 0 010 14" else "M11 5L6 9H2v6h4l5 4V5zM22 9l-6 6M16 9l6 6", en) { RcWin.toggleVoice() }
                RcIc(sk, "M6 6l12 12M18 6L6 18", true, 18f, 2.4f) { RcWin.close() }
            }
        }
    }
}

@Composable
private fun RcIc(sk: Skin, path: String, enabled: Boolean, size: Float = 20f, sw: Float = 2f, onClick: () -> Unit) {
    val p = sk.pal
    Box(Modifier.size(42.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }.clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).border(sk.b, p.line2, CircleShape).press(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Ico(path, size, p.fg, sw = sw)
    }
}

/** Una tarjeta: entra subiendo y apareciendo («rcIn»). */
@Composable
private fun RcSlide(sl: RcWin.Slide, s: Recap.Stats, sk: Skin, vw: Float, vh: Float) {
    val p = sk.pal
    val a = remember { Animatable(if (Nativo.reduceMotion()) 1f else 0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(550, easing = RC_EASE)) }
    val hSize = min(max(32f, vw * 0.09f), 52.8f) * sk.tk
    val bigSize = min(max(64f, vw * 0.23f), 118.4f) * sk.tk
    @Composable fun H(t: String, title: Boolean = false, grad: Boolean = false) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = constraints.maxWidth.toFloat()
            T(t, hSize, p.fg, family = sk.display, weight = FontWeight.Black, lh = 1.04f, ls = -0.035f, maxLines = if (title) 3 else Int.MAX_VALUE,
                brush = if (grad) Brush.horizontalGradient(listOf(p.accent, p.accent2), 0f, w) else null)
        }
    }
    @Composable fun P(t: String) = T(t, 17.6f * sk.tk, Color(0xDBF7F2FF))
    @Composable fun Big(n: String, small: String) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(n, bigSize, p.fg, family = sk.display, weight = FontWeight.Black, lh = 0.92f, ls = -0.05f)
            T(small, 20f * sk.tk, p.accent, weight = FontWeight.ExtraBold, lh = 1.2f)
        }
    }
    @Composable fun Facts(items: List<Triple<String, String, String>>) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((k, b, sm) in items) Column(Modifier.fillMaxWidth().clip(sk.r(20f)).background(Color.White.copy(alpha = 0.07f)).border(sk.b, p.line, sk.r(20f)).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T(k.uppercase(), 11.2f * sk.tk, p.muted, family = Mono, ls = 0.14f)
                T(b, 23.2f * sk.tk, p.fg, family = sk.display, weight = FontWeight.Black, ls = -0.02f)
                if (sm.isNotEmpty()) T(sm, 15.2f * sk.tk, p.muted)
            }
        }
    }
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24.dp.toPx() }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (sl.k) {
            "hola" -> { Eyebrow("Tu ${s.y}", sk); H("Tu año en Órbita"); P("${Nova.name()} ha estado tomando nota. ¿Vemos lo que ha sonado?") }
            "tiempo" -> {
                Eyebrow("Has escuchado", sk); val (hn, hu) = Recap.hours(s.sec); Big(hn, "$hu de música")
                P(RcWin.slides.firstOrNull { it.k == "tiempo" }?.say?.substringAfter("de música. ") ?: "")
                HFlow(8.dp, 8.dp) {
                    for ((n, w) in listOf(s.plays to "canciones", s.nSongs to "distintas", s.nArtists to (if (s.nArtists == 1) "artista" else "artistas"))) {
                        val txt = buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(Recap.nES(n.toDouble())) }; append(" $w") }
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.08f)).border(sk.b, p.line, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            androidx.compose.foundation.text.BasicText(txt, style = style(15.2f * sk.tk, p.fg))
                        }
                    }
                }
            }
            "cancion" -> {
                val t1 = s.tracks[0]
                val side = min(min(vw * 0.6f, 270f), vh * 0.34f)
                RcCover(t1, sk, side, 22f, big = true)
                Eyebrow("Tu canción del año", sk); H(t1.title, title = true)
                if (RcWin.artistOk(t1.artist)) T(t1.artist, 19.2f * sk.tk, p.muted, weight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(RcWin.veces(t1.plays), 17.6f * sk.tk, Color(0xDBF7F2FF), weight = FontWeight.Bold)
                    if (Lib.byId(t1.id) != null) { T(" · ", 17.6f * sk.tk, Color(0xDBF7F2FF)); Box(Modifier.padding(start = 4.dp)) { HBtn("Ponla", sk, kind = 1) { RcWin.playTop() } } }
                }
            }
            "top5" -> {
                val top = s.tracks.take(5)
                Eyebrow("Tu top ${top.size}", sk)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    top.forEachIndexed { k, r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RcCover(r, sk, 50f, 12f)
                            T("${k + 1}", 20f * sk.tk, p.accent, Modifier.width((20f * 1.3f * sk.tk).dp), family = sk.display, weight = FontWeight.Black, align = androidx.compose.ui.text.style.TextAlign.Center)
                            Column(Modifier.weight(1f)) { T(r.title, 15f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1); T(if (RcWin.artistOk(r.artist)) r.artist else "", 14.4f * sk.tk, p.muted, maxLines = 1) }
                            T(Recap.nES(r.plays.toDouble()), 13.6f * sk.tk, p.muted, family = Mono)
                        }
                    }
                }
            }
            "artista" -> {
                val a1 = s.artists.first { RcWin.artistOk(it.name) }
                val rest = s.artists.filter { it !== a1 && RcWin.artistOk(it.name) }.take(4); val mx = if (a1.sec > 0) a1.sec else 1.0
                Eyebrow("Tu artista del año", sk); H(a1.name, title = true)
                P("${Recap.hoursTxt(a1.sec)} con su música · ${Recap.nES(a1.songs.size.toDouble())} ${if (a1.songs.size == 1) "canción" else "canciones"}")
                if (rest.isNotEmpty()) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rest.forEachIndexed { k, ar ->
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            T("${k + 2}.", 15f * sk.tk, p.accent, Modifier.width(22.5.dp), weight = FontWeight.ExtraBold)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                T(ar.name, 15f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1)
                                Box(Modifier.fillMaxWidth(max(0.06f, (ar.sec / mx).toFloat())).height(6.dp).clip(RoundedCornerShape(6.dp)).background(p.grad))
                            }
                        }
                    }
                }
            }
            "cuando" -> {
                val pm = s.months.indices.maxByOrNull { s.months[it] }!!.let { b -> s.months.indices.firstOrNull { s.months[it] == s.months[b] } ?: b }
                val ph = s.hours.indices.firstOrNull { s.hours[it] == (s.hours.maxOrNull() ?: 0.0) } ?: 0
                val pd = s.dows.indices.firstOrNull { s.dows[it] == (s.dows.maxOrNull() ?: 0.0) } ?: 0
                Eyebrow("Cuándo suena tu música", sk)
                Facts(listOf(Triple("Tu mes", Cerebro.cap(Recap.MESES[pm]), ""), Triple("Tu momento", Cerebro.cap(RcWin.franja(ph).phrase), "sobre las $ph:00"), Triple("Tu día", Cerebro.cap(Momento.DAYS[pd]), "")))
                val mmax = (s.months.maxOrNull() ?: 0.0).let { if (it > 0) it else 1.0 }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 22.dp).height(84.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                    for (k in 0 until 12) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        val f = max(0.04f, (s.months[k] / mmax).toFloat())
                        Box(Modifier.fillMaxWidth().fillMaxHeight(f).heightIn(min = 4.dp).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp)).background(if (k == pm) p.grad else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.18f)))))
                        Box(Modifier.offset(y = 20.dp)) { T(Recap.MESES[k].take(1).uppercase(), 10.56f * sk.tk, p.faint, family = Mono, maxLines = 1) }
                    }
                }
            }
            "records" -> {
                val items = ArrayList<Triple<String, String, String>>()
                if (s.streak.n >= 2) items.add(Triple("Tu mejor racha", "${Recap.nES(s.streak.n.toDouble())} días seguidos", "del ${Recap.fmtDia(s.streak.from)} al ${Recap.fmtDia(s.streak.to)}"))
                val bd = s.bigDay; if (bd != null && bd.sec >= 1800) items.add(Triple("Tu gran día", Cerebro.cap(Recap.fmtDia(bd.ts)), "${fmtLongPub(bd.sec)} de música"))
                val ob = s.obs; if (ob != null) s.tracks.firstOrNull { it.id == ob.id }?.let { r -> items.add(Triple("En bucle", "«${r.title}»", "${Recap.nES(ob.n.toDouble())} veces el ${Recap.fmtDia(ob.ts)}")) }
                Eyebrow("Tus récords", sk); Facts(items)
            }
            "nova" -> {
                val pct = Math.round(s.novaSec / s.sec * 100)
                Eyebrow("Tu DJ", sk); Big("$pct", "% lo eligió ${Nova.name()}")
                P("${Recap.hoursTxt(s.novaSec)} de mezclas, radio y tu mezcla del día.${if (s.radioSec >= 600) " Y ${Recap.hoursTxt(s.radioSec)} de emisoras." else ""}")
            }
            "tipo" -> { val (tl, td) = RcWin.type(s); Eyebrow("Este año has sido", sk); H(tl, grad = true); P(td) }
            "fin" -> {
                val cw = min(min(vw * 0.64f, 290f), (vh - 330f) * 0.5625f).coerceAtLeast(120f)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(cw.dp).aspectRatio(9f / 16f).clip(sk.r(18f)).border(sk.b, p.line2, sk.r(18f)).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                        val im = RcWin.img
                        if (im != null) Image(remember(im) { im.asImageBitmap() }, "Tu ${s.y} en Órbita: tu tiempo, tu canción, tu top 5 y tus artistas", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Note(if (RcWin.imgErr) "No he podido dibujar la imagen." else "Preparando tu imagen…", sk, Modifier.padding(12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    HBtn("Compartir", sk, Modifier.weight(1f, fill = false).widthIn(max = 220.dp), primary = true) { RcWin.share(false) }
                    HBtn("Guardar imagen", sk, Modifier.weight(1f, fill = false).widthIn(max = 220.dp)) { RcWin.share(true) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    HBtn("Escuchar tu ${s.y}", sk, kind = 1) { RcWin.listen() }
                    HBtn("Cerrar", sk, kind = 1) { RcWin.close() }
                }
            }
        }
    }
}
private fun fmtLongPub(s0: Double): String { val s = Math.round(s0); if (s < 60) return "$s s"; val h = s / 3600; val m = Math.round((s % 3600) / 60.0); return if (h > 0) "$h h${if (m > 0) " $m min" else ""}" else "$m min" }

/** Una portada del resumen («rc-cover»): la de la canción, o su inicial sobre su color. */
@Composable
private fun RcCover(r: Memoria.TrackStat, sk: Skin, side: Float, rad: Float, big: Boolean = false) {
    val t = Lib.byId(r.id)
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    val bigBmp = if (big && t != null) UiState.covers["${t.id}|L"] else null
    if (big && t != null && bigBmp == null && Player.uiTrack(t).cover) LaunchedEffect(t.id) { needArt(t.id, 400, dens) }
    if (bigBmp != null) Image(bigBmp, null, Modifier.size(side.dp).clip(sk.r(rad)), contentScale = ContentScale.Crop)
    else if (t != null) Cover(Player.uiTrack(t), sk, side, rad)
    else Box(Modifier.size(side.dp).clip(sk.r(rad)).background(Color(0xFF1A1426)), contentAlignment = Alignment.Center) {
        T(((r.title.trim().take(1)).ifEmpty { "♪" }).uppercase(), (if (big) 64f else 16.8f) * sk.tk, Color.White, family = sk.display, weight = FontWeight.Black, lh = 1f)
    }
}
