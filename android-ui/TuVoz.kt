package __PACKAGE__

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fase 4, parte 2 · «Tu voz» hecho por Android: tus frases de DJ por huecos (saludo, entre canciones, antes de una
 * favorita, la ruleta y las libres), grabadas con el micrófono (con su nivel en el botón y parada sola) o subidas de un
 * archivo, recortadas como en la web (sin el silencio del principio ni del final) y con su forma de onda; quién habla
 * entre canciones; y tus canciones cantadas, que entran en la biblioteca con «Mi voz». Lo grabado se guarda como antes
 * («voice»), así que tus grabaciones de siempre siguen ahí y suenan igual.
 */
object TuVoz {
    private val main = Handler(Looper.getMainLooper())

    class Slot(val id: String, val name: String, val ex: String)
    val SLOTS = listOf(
        Slot("saludo", "Saludo", "Suena al empezar una mezcla o la radio. Por ejemplo: «¡Bienvenidos a mi radio!» o «Esto es Órbita FM y aquí mando yo»."),
        Slot("siguiente", "Entre canciones", "Frases cortas para pasar de una canción a otra: «Seguimos», «Escucha esta», «Vamos con la siguiente»."),
        Slot("favorita", "Antes de una favorita", "Suena antes de las canciones con corazón: «Esta es de mis favoritas»."),
        Slot("ruleta", "Ruleta", "Suena cuando gira la ruleta: «¡La ruleta ha hablado!»."),
        Slot("libre", "Cuñas libres", "Lo que quieras: un grito, una risa, tu frase de siempre. Se mezclan con las de «Entre canciones».")
    )

    class Take(val id: String, val slot: String, val label: String, val start: Double, val end: Double, val duration: Double, val peaks: List<Double>, val addedAt: Double)
    fun takes(): List<Take> = Store.recs("voice").mapNotNull { (_, v) ->
        val m = Json.obj(Json.parseOr(v, null)) ?: return@mapNotNull null
        Take(Json.s(m["id"]), Json.s(m["slot"]).ifEmpty { "libre" }, Json.s(m["label"]), Json.d(m["start"]), Json.d(m["end"]), Json.d(m["duration"]),
            (Json.list(m["peaks"]) ?: emptyList()).map { Json.d(it) }, Json.d(m["addedAt"]))
    }

    /** «setWho»: quién habla entre canciones (Nova, tú o las dos por turnos). */
    fun setWho(w: String) {
        Prefs.set("who", w); Ver.bump(); Inicio.mark()
        if (w != "nova" && takes().isEmpty()) Nova.setBubble("Aún no has grabado ninguna frase. Ve a «Tu voz» y graba tu saludo; mientras tanto hablo yo.")
    }
    fun play(c: Take) = Voz.clip(Grabaciones.Clip(c.id, c.slot, c.label, c.start, c.end, c.duration), "Probando: ${c.label}")
    fun del(id: String) { Store.dropBlobs("voice", id); Store.delRec("voice", id); Ver.bump() }

    // ---------------------------------------------------------------- grabar
    class Rec(val target: String, val file: File, val t0: Long, val max: Int, val r: MediaRecorder) { var lvl = 0f; var secs = 0.0 }
    var rec by mutableStateOf<Rec?>(null); private set
    /** (cada 120 ms mientras graba: el tiempo y el nivel del botón) */
    var tick by mutableIntStateOf(0); private set

    /** «toggleRec»: grabar en ese hueco (o «mine», una canción cantada); si ya graba, parar. */
    fun toggle(target: String) {
        if (rec != null) { stopRec(); return }
        val a = Nativo.act as? ComponentActivity ?: return
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Actos.launch(a, ActivityResultContracts.RequestPermission(), Manifest.permission.RECORD_AUDIO) { ok ->
                if (ok) start(target) else { Nativo.toast("Sin permiso de micrófono. Actívalo en Ajustes › App y permisos.", 4200); Ver.bump() }
            }
            return
        }
        start(target)
    }

    private fun start(target: String) {
        val a = Nativo.act ?: return
        val mine = target == "mine"
        if (Player.playing) Player.fadePause()
        Voz.stop()
        val f = File(a.cacheDir, "grabacion-${System.currentTimeMillis()}.m4a")
        val r = try {
            @Suppress("DEPRECATION") val m = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(a) else MediaRecorder()
            m.setAudioSource(MediaRecorder.AudioSource.MIC)
            m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            m.setAudioEncodingBitRate(if (mine) 192000 else 96000)
            m.setAudioSamplingRate(44100); m.setAudioChannels(1)
            m.setOutputFile(f.absolutePath)
            m.prepare(); m.start(); m
        } catch (t: Throwable) { f.delete(); Nativo.toast("No he podido usar el micrófono.", 4200); return }
        val R = Rec(target, f, SystemClock.uptimeMillis(), if (mine) 600 else 15, r)
        rec = R
        if (mine) Nativo.go("voz")
        Ver.bump()
        poll(R)
    }
    private fun poll(R: Rec) {
        main.postDelayed({
            if (rec !== R) return@postDelayed
            R.secs = (SystemClock.uptimeMillis() - R.t0) / 1000.0
            val m = try { R.r.maxAmplitude } catch (t: Throwable) { 0 }
            R.lvl = R.lvl * 0.6f + (m / 32767f) * 0.4f
            tick++
            if (R.secs >= R.max) stopRec() else poll(R)
        }, 120)
    }

    /** «onRecStop»: lo grabado, a su hueco (recortado) o a tu biblioteca si es una canción cantada. */
    fun stopRec() {
        val R = rec ?: return; rec = null
        val ok = try { R.r.stop(); true } catch (t: Throwable) { false }
        try { R.r.release() } catch (ignored: Throwable) { }
        Ver.bump()
        val secs = (SystemClock.uptimeMillis() - R.t0) / 1000.0
        if (!ok || R.file.length() < 1500 || secs < 0.6) { R.file.delete(); Nativo.toast("La grabación ha salido vacía. Mantén el botón un poco más."); return }
        if (R.target == "mine") {
            val c = Calendar.getInstance()
            fun p2(n: Int) = n.toString().padStart(2, '0')
            val name = "Mi versión ${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.YEAR)} ${p2(c.get(Calendar.HOUR_OF_DAY))}.${p2(c.get(Calendar.MINUTE))}.m4a"
            Biblio.importFile(R.file, name, "audio/mp4", mine = true)
        } else importClips(listOf(Src({ R.file.readBytes().also { R.file.delete() } }, "toma.m4a", "audio/mp4")), R.target)
    }

    // ---------------------------------------------------------------- subir y recortar
    class Src(val read: () -> ByteArray?, val name: String, val type: String)
    private val AUDIO_RE = Regex("\\.(mp3|m4a|aac|wav|ogg|oga|opus|flac|webm|caf|3gp|amr)$", RegexOption.IGNORE_CASE)

    /** «Subir archivo» en un hueco: el selector del móvil (solo audio). */
    fun pickFor(slot: String) {
        val a = Nativo.act as? ComponentActivity ?: return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        Actos.launch(a, ActivityResultContracts.StartActivityForResult(), i) { res ->
            val d = res.data ?: return@launch
            if (res.resultCode != android.app.Activity.RESULT_OK) return@launch
            val uris = ArrayList<Uri>()
            val cd = d.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) } else d.data?.let { uris.add(it) }
            val cr = a.contentResolver
            importClips(uris.map { u ->
                var name = u.lastPathSegment ?: "archivo"
                try { cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } } } catch (ignored: Throwable) { }
                Src({ try { cr.openInputStream(u)?.use { it.readBytes() } } catch (t: Throwable) { null } }, name, cr.getType(u) ?: "")
            }, slot)
        }
    }

    /** «importClips»: cada archivo, guardado en su hueco como «Toma N», recortado y con su forma de onda. */
    fun importClips(srcs: List<Src>, slot: String) {
        val list = srcs.filter { it.type.startsWith("audio/") || it.type == "video/webm" || AUDIO_RE.containsMatchIn(it.name) }
        if (list.isEmpty()) { Nativo.toast("Elige un archivo de audio con tu voz."); return }
        Biblio.io.execute {
            var n = 0
            for (s in list) {
                val bytes = s.read() ?: continue
                if (bytes.size > 40_000_000) continue
                val id = Listas.uid("v")
                val blob = Store.putBlob("voice", id, "blob", bytes, s.type.ifEmpty { "audio/mp4" })
                val a = analyze(Store.blobFile(blob))
                val cnt = takes().count { it.slot == slot } + 1
                Store.putRec("voice", id, Json.str(linkedMapOf("id" to id, "slot" to slot, "label" to "Toma $cnt", "blob" to blob, "start" to a.start, "end" to a.end,
                    "duration" to a.duration, "peaks" to a.peaks, "addedAt" to System.currentTimeMillis())))
                n++
            }
            main.post {
                Ver.bump()
                if (n == 0) { Nativo.toast("No he podido leer ese archivo."); return@post }
                Nativo.toast(if (n == 1) "Grabación guardada y recortada" else "$n grabaciones guardadas")
                if (Nova.who() == "nova") { setWho("mix"); Nativo.toast("Ahora hablaréis Nova y tú por turnos. Cámbialo arriba si quieres.", 3600) }
            }
        }
    }

    class Ana(val start: Double, val end: Double, val duration: Double, val peaks: List<Double>)
    /** «analyzeClip»: dónde empieza y acaba de verdad la voz (sin el silencio de los lados, como mucho 15 s) y su forma. */
    fun analyze(f: File?): Ana {
        val d = try { if (f == null) null else Analizador.decode(f.absolutePath, 0.05) } catch (t: Throwable) { null }
        if (d == null) {
            // (si no se puede leer el sonido: su duración, sin recortar)
            var dur = 0.0
            if (f != null) try { val mmr = android.media.MediaMetadataRetriever(); mmr.setDataSource(f.absolutePath); dur = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0; mmr.release() } catch (ignored: Throwable) { }
            return Ana(0.0, 0.0, dur, emptyList())
        }
        val (L, R) = d
        val sr = Analizador.SR.toDouble(); val n = L.size
        fun amp(i: Int): Float = if (R != null) max(Math.abs(L[i]), Math.abs(R[i])) else Math.abs(L[i])
        // (los pasos de la web, a 44 100 Hz, pasados a esta frecuencia)
        val k = sr / 44100.0
        val s64 = max(1, (64 * k).roundToInt()); val s32 = max(1, (32 * k).roundToInt()); val s16 = max(1, (16 * k).roundToInt())
        var peak = 0f; var i = 0; while (i < n) { peak = max(peak, amp(i)); i += s64 }
        val th = max(0.015f, peak * 0.08f)
        var s = 0; while (s < n && amp(s) < th) s += s32
        var e = n - 1; while (e > s && amp(e) < th) e -= s32
        val dur = n / sr
        val start = max(0.0, s / sr - 0.06)
        var end = min(dur, e / sr + 0.18)
        if (end - start > 15) end = start + 15
        val bars = 18; val step = ((e - s) / bars).takeIf { it > 0 } ?: 1
        val peaks = ArrayList<Double>()
        for (b in 0 until bars) {
            var m = 0f; var j = s + b * step
            while (j < s + (b + 1) * step && j < n) { m = max(m, amp(j)); j += s16 }
            peaks.add(Math.round((m / (if (peak > 0) peak else 1f)) * 100) / 100.0)
        }
        return Ana(start, end, dur, peaks)
    }
}

// ================================================================ la pantalla
private const val V_EDIT = "M4 20h4L19 9l-4-4L4 16z"
private const val V_TRASH = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"

@Composable
fun VozScreen() {
    val sk = rememberSkin()
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val v = Ver.n
    val takes = TuVoz.takes().sortedWith(compareBy({ it.addedAt }, { it.id }))
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(UiState.pageScroll("voz")).padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = Tubo.viewBottom()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            PageHead("Tu voz", if (takes.isNotEmpty()) "${takes.size} ${if (takes.size == 1) "grabación" else "grabaciones"}" else "", sk)
            T("Graba frases cortas y sé tu propio DJ: sonarán entre canciones en lugar de la voz de Nova, con la música bajando justo cuando empiezas a hablar. Recorto el silencio del principio y del final de cada grabación para que entre a tiempo.", 16f * sk.tk, p.muted)
            InfoBox("Lo que esta app no puede hacer:", " clonar tu voz para que diga frases nuevas o cante canciones. Para eso hace falta un modelo de clonación de voz, y la app no puede conectarse a ninguno. Usa tus grabaciones tal cual las haces.", sk)
            RowWrap {
                Lbl("¿Quién habla entre canciones?", sk)
                Seg(listOf("nova" to "Nova", "mine" to "Mi voz", "mix" to "Las dos"), Nova.who(), sk) { TuVoz.setWho(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { for (s in TuVoz.SLOTS) SlotCard(s, takes.filter { it.slot == s.id }, sk) }
            Panel(sk, glow = true) {
                H2("Tus canciones cantadas", sk)
                Note("¿Te has grabado cantando? Sube la grabación y se añade a tu biblioteca con la etiqueta «Mi voz». La DJ la pinchará como cualquier otra canción.", sk)
                val mine = Lib.tracks.count { it.mine }
                RowWrap {
                    RecBtn("mine", "Grabarme cantando", primary = true, small = false, sk = sk)
                    HBtn("Subir grabación", sk) { Biblio.pick(true) }
                    if (mine > 0) Note("$mine ${if (mine == 1) "canción tuya" else "canciones tuyas"} en la biblioteca", sk)
                }
                Note("Consejo: usa auriculares si cantas encima de una canción, para que el micrófono no recoja la música. La grabación se para sola a los 10 minutos.", sk)
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SlotCard(s: TuVoz.Slot, takes: List<TuVoz.Take>, sk: Skin) {
    val p = sk.pal
    Panel(sk, gap = 12.dp) {
        HeadWith({ T(s.name, 16.8f * sk.tk, p.fg, family = sk.display, weight = sk.displayWeight, ls = -0.02f) }) {
            // «pill» (verde si ya hay tomas)
            val c = if (takes.isNotEmpty()) p.good else p.muted
            Box(Modifier.padding(start = 7.dp).clip(RoundedCornerShape(999.dp)).border(sk.b, if (takes.isNotEmpty()) p.good else p.line2, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp + sk.b, vertical = 2.dp + sk.b)) {
                T("${takes.size} ${if (takes.size == 1) "toma" else "tomas"}".uppercase(), 9.92f * sk.tk, c, family = Mono, weight = FontWeight.SemiBold, ls = 0.1f, maxLines = 1)
            }
        }
        T(s.ex, 14.08f * sk.tk, p.muted)
        if (takes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { for (c in takes) TakeRow(c, sk) }
        HFlow(8.dp, 8.dp) {
            RecBtn(s.id, if (takes.isEmpty()) "Grabar" else "Grabar otra toma", primary = takes.isEmpty(), small = true, sk = sk)
            HBtn("Subir archivo", sk, kind = 1) { TuVoz.pickFor(s.id) }
        }
    }
}

@Composable
private fun TakeRow(c: TuVoz.Take, sk: Skin) {
    val p = sk.pal
    val shape = sk.r(11f)
    Row(Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line, shape).padding(horizontal = 8.dp + sk.b, vertical = 6.dp + sk.b),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VIcon(H_PLAY, sk, fill = true) { TuVoz.play(c) }
        // la forma de la grabación («wave»)
        val pk = c.peaks.ifEmpty { listOf(.4, .7, .5, .9, .6, .3) }
        Canvas(Modifier.size((pk.size * 5 - 2).dp, 22.dp)) {
            val w = 3.dp.toPx()
            pk.forEachIndexed { i, v ->
                val h = max(3, (v * 22).roundToInt()).dp.toPx()
                drawRoundRect(p.good.copy(alpha = 0.85f), Offset(i * 5.dp.toPx(), (size.height - h) / 2), Size(w, h), CornerRadius(2.dp.toPx() * sk.round))
            }
        }
        T(c.label, 14.08f * sk.tk, p.fg, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 1)
        T(String.format(java.util.Locale.US, "%.1f s", max(0.0, (if (c.end > 0) c.end else c.duration) - c.start)), 12f * sk.tk, p.muted, family = Mono, maxLines = 1)
        VIcon(V_EDIT, sk) { Ventanas.openClipEdit(c.id) }
        ArmDel(sk) { TuVoz.del(c.id) }
    }
}

/** «icon-btn» (34 × 34, el icono de 16). */
@Composable
private fun VIcon(path: String, sk: Skin, fill: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(sk.r(10f)).press(onClick = onClick), contentAlignment = Alignment.Center) { Ico(path, 16f, sk.pal.muted, fill = fill, sw = 2f) }
}

/** La papelera que pregunta antes («¿Borrar?» durante 3 segundos; el segundo toque borra). */
@Composable
private fun ArmDel(sk: Skin, run: () -> Unit) {
    val p = sk.pal
    var armed by remember { mutableStateOf(false) }
    if (armed) LaunchedEffect(Unit) { delay(3000); armed = false }
    if (!armed) VIcon(V_TRASH, sk) { armed = true }
    else Box(Modifier.height(34.dp).clip(sk.r(10f)).border(sk.b, p.bad, sk.r(10f)).press { armed = false; run() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        T("¿Borrar?", 12.8f * sk.tk, p.bad, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** El botón de grabar («btn rec»): su punto; grabando, rojo, con el tiempo y un aro que crece con tu voz. */
@Composable
private fun RecBtn(target: String, idle: String, primary: Boolean, small: Boolean, sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val tk = TuVoz.tick
    val R = TuVoz.rec
    val on = R != null && R.target == target
    val rad = if (small) 11f else 14f
    val shape = sk.r(rad)
    val fs = (if (small) 13.6f else 15f) * sk.tk
    val bad = p.bad
    val lvl = if (on) min(1f, R!!.lvl * 2.5f) else 0f
    val fg = if (on) Color.White else if (primary) p.onAccent else p.fg
    var m: Modifier = Modifier
    if (on) m = m.drawBehind {
        val s = lvl * 12.dp.toPx()
        if (s > 0.5f) drawRoundRect(hsl(350f, 1f, 0.7f, 0.3f), Offset(-s, -s), Size(size.width + 2 * s, size.height + 2 * s), CornerRadius((rad * sk.round).dp.toPx() + s))
    } else if (primary) m = m.glow(p.glow, (rad * sk.round).dp, 30.dp, 10.dp, (-10).dp)
    m = m.press { TuVoz.toggle(target) }.clip(shape)
    m = if (on) m.background(bad).border(sk.b, bad, shape) else if (primary) m.background(p.grad).padding(sk.b) else m.background(Color.White.copy(alpha = 0.04f)).border(sk.b, p.line2, shape)
    Row(m.padding(horizontal = (if (small) 13.dp else 18.dp) + (if (on || !primary) sk.b else 0.dp), vertical = (if (small) 8.dp else 12.dp) + (if (on || !primary) sk.b else 0.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // el punto (grabando, parpadea)
        val blink = if (on && !Nativo.reduceMotion()) rememberInfiniteTransition(label = "rec").animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "b").value else 0f
        Box(Modifier.size(10.dp).graphicsLayer { alpha = if (on && blink >= 0.5f) 0f else 1f }.clip(CircleShape).background(fg))
        T(if (on) "Detener · ${Cerebro.fmt(R!!.secs)}" else idle, fs, fg, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

