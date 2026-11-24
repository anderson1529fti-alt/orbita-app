package __PACKAGE__

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fase 4, parte 2 · «Ajustes» de la Órbita nativa, hecho por Android (antes era la página de la web, leída entera):
 * el despertador, el sonido y las mezclas con su ecualizador, el tema y el aspecto, la DJ con su voz, el rendimiento, las
 * copias de seguridad y los permisos. Mismas piezas y mismas medidas que su CSS en el móvil.
 */
private const val P_GEAR_EQ_CUSTOM = "M4 12v2M8 9v8M12 5v14M16 8v9M20 11v3"
private val EQ_ICON = mapOf(
    "custom" to listOf("M4 12v2M8 9v8M12 5v14M16 8v9M20 11v3"),
    "graves" to listOf("M9 3h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M14.5 11a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z", "M4 18c2.5 2.5 13.5 2.5 16 0"),
    "voces" to listOf("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0M12 18v3"),
    "agudos" to listOf("M10 5h4a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M14 14a2 2 0 1 1-4 0 2 2 0 0 1 4 0z", "M4 8a10 10 0 0 0 0 8M20 8a10 10 0 0 1 0 8")
)
private val VSTYLES = listOf(Triple("natural", "Natural", 1.0 to 1.0), Triple("radio", "Locutora de radio", 1.07 to 1.04), Triple("calma", "Tranquila", 0.92 to 0.97),
    Triple("noche", "Voz de noche", 0.88 to 0.9), Triple("energia", "Con energía", 1.15 to 1.08))
private val LOOK_OPTS = mapOf(
    "lkBg" to listOf("aurora" to "Aurora", "quieto" to "Quieto", "portada" to "Portada", "negro" to "Negro puro"),
    "lkRound" to listOf("redondo" to "Redondas", "suave" to "Suaves", "recto" to "Rectas"),
    "lkText" to listOf("s" to "Pequeño", "m" to "Normal", "l" to "Grande"),
    "lkTitles" to listOf("orbita" to "Órbita", "limpia" to "Limpia", "retro" to "Retro"),
    "startView" to listOf("inicio" to "Ahora", "biblioteca" to "Música", "buscar" to "Buscar", "tu" to "Tú"))
private val LOOK_DEF = mapOf("lkBg" to "aurora", "lkRound" to "redondo", "lkText" to "m", "lkTitles" to "orbita", "startView" to "inicio")
private val WARN = Color(0xFFFFC35C)

@Composable
fun AjustesScreen() {
    val sk = rememberSkin()
    @Suppress("UNUSED_VARIABLE") val v = Ver.n + Alarma.ver
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(UiState.pageScroll("ajustes")).padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            PageHead("Ajustes", "sonido, aspecto, DJ y copias" + Ajustes.version().let { if (it.isNotEmpty()) " · versión $it" else "" }, sk)
            AlarmPanel(sk)
            SoundPanel(sk)
            ThemePanel(sk)
            LookPanel(sk)
            CoversPanel(sk)
            DjPanel(sk)
            PerfPanel(sk)
            BackupPanel(sk)
            PermsPanel(sk)
            Column(Modifier.fillMaxWidth().padding(top = 18.dp - 26.dp + 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HFlow(12.dp, 12.dp) {
                    HBtn("Ver otra vez la bienvenida", sk, kind = 1) { Bienvenida.open(true) }
                    HBtn("Copiar diagnóstico", sk, kind = 1) { Ajustes.copyDiag() }
                }
                T(Ajustes.versionLine(), 13.44f * sk.tk, sk.pal.muted, Modifier.padding(bottom = 4.dp), align = TextAlign.Center)
            }
        }
        ToastHost(sk, Modifier.align(Alignment.BottomCenter))
    }
}

/** Título con algo a su derecha (un interruptor): si no caben, debajo. */
@Composable
private fun HeadRow(title: String, sk: Skin, tag: String? = null, right: @Composable () -> Unit) {
    HeadWith({ Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { H2(title, sk); if (tag != null) BetaTag(tag, sk) } }, right)
}

// ---------------------------------------------------------------- el despertador
@Composable
private fun AlarmPanel(sk: Skin) {
    val p = sk.pal
    val c = Alarma.cfg()
    val st = Alarma.st()
    val next = if (c.on) (if (st != null && st.on && st.next > 0) st.next else Alarma.next(c)) else -1
    fun save(quiet: Boolean, f: (Alarma.Cfg) -> Unit) { val x = Alarma.cfg(); f(x); Alarma.save(x, quiet) }
    Panel(sk, glow = true) {
        HeadRow("Despertador", sk, "nuevo") {
            Toggle("Activado", c.on, sk) { save(false) { it.on = !it.on }; val n = Alarma.nextNow(); if (Alarma.cfg().on) Nativo.toast("Despertador: ${Alarma.whenText(n)}", 3200) }
        }
        Note(if (c.on && next > 0) "Sonará ${Alarma.whenText(next)}." else "${Nova.name()} te despierta con tu música, subiendo poco a poco, y te dice la hora y el tiempo.", sk)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fs = min(max(44.8f * sk.tk, maxWidth.value * 0.14f), 60.8f * sk.tk)
                HFlow(12.dp, 12.dp, Modifier.fillMaxWidth()) {
                    Box(Modifier.press { pickTime() }) { T(Alarma.hm(c.h, c.m), fs, p.fg, family = sk.display, weight = sk.displayWeight, lh = 1f, ls = -0.04f, maxLines = 1) }
                    HBtn("Cambiar la hora", sk, kind = 1) { pickTime() }
                }
            }
            Row(Modifier.widthIn(max = 340.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((d, l, _) in Alarma.DAYS) {
                    val on = d in c.days
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).then(if (on) Modifier.background(p.grad) else Modifier.background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, CircleShape))
                        .press { save(true) { x -> if (d in x.days) x.days.remove(d) else x.days.add(d) } }, contentAlignment = Alignment.Center) {
                        T(l, 15f * sk.tk, if (on) p.onAccent else p.muted, weight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
            }
            Note(Alarma.daysText(c.days) + if (c.days.isNotEmpty()) "" else ": luego se apaga solo", sk)
        }
        Eyebrow("Con qué música", sk)
        Chips {
            for ((k, l) in Alarma.MUSIC) Chip(l, c.music == k, sk) { val x = Alarma.cfg(); x.music = k; Prefs.set("alarm", x.toMap()); Alarma.changed() }
            val lists = Lib.playlists.filter { (Json.list(it["trackIds"]) ?: emptyList()).isNotEmpty() }
            if (lists.isNotEmpty()) {
                val curId = if (c.music.startsWith("list:")) c.music.substring(5) else ""
                Dropdown(listOf("" to "Una de tus listas…") + lists.map { Json.s(it["id"]) to Json.s(it["name"]) }, curId, sk, on = curId.isNotEmpty()) { id ->
                    val x = Alarma.cfg(); x.music = if (id.isNotEmpty()) "list:$id" else "suave"; Prefs.set("alarm", x.toMap()); Alarma.changed()
                }
            }
        }
        var vol by remember { mutableStateOf(-1f) }
        val vv = if (vol >= 0) vol else c.vol.toFloat()
        Slider("Volumen al despertar (como mínimo)", "${vv.roundToInt()} %", vv, 20f, 100f, 5f, sk, onChange = { vol = it }, onDone = { val x = Alarma.cfg(); x.vol = it.roundToInt(); Alarma.save(x, true); vol = -1f })
        RowWrap { Toggle("Que me salude con la hora y el tiempo", c.talk, sk) { val x = Alarma.cfg(); x.talk = !x.talk; Prefs.set("alarm", x.toMap()); Alarma.changed() } }
        RowWrap {
            Lbl("Posponer", sk)
            Seg(listOf("5" to "5 min", "10" to "10 min", "15" to "15 min"), c.snooze.toString(), sk) { s -> save(true) { it.snooze = s.toInt() } }
        }
        val w = ArrayList<Triple<String, String, String>>()
        if (st != null && c.on) {
            if (!st.notif) w.add(Triple("notif", "Las notificaciones de Órbita están desactivadas: sin ellas el despertador no se ve con el móvil bloqueado.", "Activarlas"))
            if (!st.exact) w.add(Triple("exact", "Android no deja a Órbita poner alarmas exactas: podría sonar unos minutos tarde.", "Permitir"))
            if (!st.fullScreen) w.add(Triple("fullscreen", "Falta el permiso de pantalla completa: con el móvil bloqueado solo verás un aviso.", "Permitir"))
        }
        if (w.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, t, b) in w) HFlow(10.dp, 10.dp, Modifier.fillMaxWidth().clip(sk.r(20f)).background(Color(0x14FFC35C)).border(sk.b, WARN, sk.r(20f)).padding(horizontal = 12.dp + sk.b, vertical = 10.dp + sk.b), grow = 0, basis = 200.dp) {
                T(t, 14.4f * sk.tk, p.fg, lh = 1.4f)
                HBtn(b, sk, kind = 1) { if (k == "notif") { Alarma.checkPerms(true); if (android.os.Build.VERSION.SDK_INT < 33) Alarma.openSettings("notif") } else Alarma.openSettings(k) }
            }
        }
        RowWrap {
            HBtn("Probar ahora", sk, kind = 1) { Alarma.wake(System.currentTimeMillis(), "alarm", true) }
            Note("Si no puede poner tu música, suena la alarma del móvil.", sk)
        }
    }
}

private fun pickTime() {
    val a = Nativo.act ?: return
    val c = Alarma.cfg()
    try {
        android.app.TimePickerDialog(a, { _, h, m -> val x = Alarma.cfg(); x.h = h; x.m = m; x.on = true; Alarma.save(x, false) }, c.h, c.m, true).show()
    } catch (ignored: Throwable) { }
}

/** Un desplegable redondo (como el «select» de la app): al tocarlo, sus opciones. */
@Composable
internal fun Dropdown(opts: List<Pair<String, String>>, cur: String, sk: Skin, on: Boolean = false, onPick: (String) -> Unit) {
    val p = sk.pal
    var open by remember { mutableStateOf(false) }
    val label = opts.firstOrNull { it.first == cur }?.second ?: opts.firstOrNull()?.second ?: ""
    val shape = RoundedCornerShape(999.dp)
    Box {
        Box(Modifier.press { open = true }.clip(shape).background(Color.Black.copy(alpha = 0.3f)).border(sk.b, if (on) p.accent else p.line2, shape).padding(horizontal = 14.dp + sk.b, vertical = 8.dp + sk.b)) {
            T("$label  ▾", 14.08f * sk.tk, if (on) p.fg else p.muted, weight = FontWeight.ExtraBold, maxLines = 1)
        }
        if (open) Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }), onDismissRequest = { open = false }) {
            val ps = sk.r(12f)
            Column(Modifier.widthIn(min = 200.dp, max = 300.dp).glow(Color.Black.copy(alpha = 0.6f), 12.dp, 30.dp, 10.dp, 0.dp).clip(ps).background(p.panelSolid).border(sk.b, p.line2, ps).padding(vertical = 6.dp)) {
                for ((id, name) in opts) Box(Modifier.fillMaxWidth().press(0.98f) { open = false; onPick(id) }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    T(name, 15f * sk.tk, if (id == cur) p.accent else p.fg, weight = if (id == cur) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- el sonido
@Composable
private fun SoundPanel(sk: Skin) {
    val p = sk.pal
    val direct = Mezcla.Aud.direct
    Panel(sk, glow = true) {
        HeadRow("Sonido y mezclas", sk) { Toggle("Sonido directo", direct, sk) { Ajustes.set("aDirect", !direct); Player.applyEq(); Player.resync() } }
        Note(if (direct) "Sonido directo: la música sale tal cual del archivo, sin ecualizador ni mezclas (entre canciones habrá un fundido)."
            else "Cada canción se analiza la primera vez que suena (ritmo, silencios y volumen) y Órbita lo recuerda para mezclar.", sk)
        Column(Modifier.graphicsLayer { alpha = if (direct) 0.38f else 1f }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val mix = Mezcla.Aud.mix
            Eyebrow("Entre canciones", sk)
            Chips { for (id in Mezcla.MIX_ORDER) Chip(Mezcla.MIX_STYLES[id]!!.first, mix == id, sk) { if (!direct) { Ajustes.set("mixStyle", id); Player.resync() } } }
            Note(Mezcla.MIX_STYLES[mix]?.second ?: "", sk)
            if (mix !in listOf("corte", "eco")) {
                var len by remember { mutableStateOf(-1f) }
                val lv = if (len >= 0) len else Mezcla.Aud.mixLen.toFloat()
                Slider("Duración del fundido", "${lv.roundToInt()} s", lv, 2f, 16f, 1f, sk, onChange = { if (!direct) { len = it; Prefs.set("mixLen", it.roundToInt()) } }, onDone = { len = -1f; Player.resync(); Ver.bump() })
            }
            EqPanel(sk, direct)
            RowWrap {
                Toggle("Volumen igualado", Mezcla.Aud.level, sk) { if (!direct) { Ajustes.set("aLevel", !Mezcla.Aud.level); Player.current()?.let { Player.applyNorm(Player.slot, it) } } }
                Note("Sube las canciones que suenan bajito y baja las muy fuertes.", sk)
            }
        }
    }
}

@Composable
private fun EqPanel(sk: Skin, direct: Boolean) {
    val p = sk.pal
    val eqOn = Prefs.bool("eqOn", true)
    val sel = Prefs.str("eq", "plano")
    @Suppress("UNUSED_VARIABLE") val vv = Ver.n
    val gains = Mezcla.eqGains()
    fun apply() { Player.applyEq(); Ver.bump() }
    fun setBand(i: Int, v0: Int) {
        val v = v0.coerceIn(-12, 12)
        if (sel != "custom" || !Prefs.bool("eqOn", true)) {
            Prefs.set("eqCustom", Mezcla.eqGains().toList()); Prefs.set("eq", "custom")
            if (!Prefs.bool("eqOn", true)) Prefs.set("eqOn", true)
        }
        val cur = Mezcla.eqMigrate(Prefs.get("eqCustom"))
        if (cur[i] == v) return
        cur[i] = v; Prefs.set("eqCustom", cur.toList())
        apply()
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HeadWith({ Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Ecualizador", sk)
            T(if (eqOn) Mezcla.eqName() else "Apagado", 12.48f * sk.tk, p.accent, family = Mono, weight = FontWeight.SemiBold, maxLines = 1)
        } }) { Toggle("Activado", eqOn, sk) { if (!direct) { Prefs.set("eqOn", !eqOn); apply(); Nativo.toast(if (!eqOn) "Ecualizador: ${Mezcla.eqName()}" else "Ecualizador apagado") } } }
        // las 10 bandas, de arriba (+12) a abajo (−12)
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(vertical = 4.dp).graphicsLayer { alpha = if (eqOn) 1f else 0.45f }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 0 until 10) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val g = gains[i]
                T((if (g > 0) "+" else "") + g, 11.2f * sk.tk, p.muted, family = Mono, lh = 1.2f, maxLines = 1)
                Canvas(Modifier.widthIn(max = 44.dp).fillMaxWidth().height(190.dp).pointerInput(i, direct) {
                    if (direct) return@pointerInput
                    detectTapGestures(onDoubleTap = { setBand(i, 0) }, onPress = { o -> setBand(i, Math.round(12 - o.y / size.height * 24)) })
                }.pointerInput(i, direct) {
                    if (direct) return@pointerInput
                    detectVerticalDragGestures { ch, _ -> ch.consume(); setBand(i, Math.round(12 - ch.position.y / size.height * 24)) }
                }) {
                    val cx = size.width / 2
                    drawRect(p.line2, Offset(cx - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                    drawRect(p.line2, Offset(0f, size.height / 2), Size(size.width, 1.dp.toPx()))
                    val pp = (12 - g) / 24f
                    val top = min(0.5f, pp) * size.height; val h = abs(pp - 0.5f) * size.height
                    val fw = 9.dp.toPx()
                    if (h > 0) drawRoundRect(p.accent, Offset(cx - fw / 2, top), Size(fw, h), CornerRadius(fw / 2))
                    val ky = pp * size.height
                    drawCircle(Color.Black.copy(alpha = 0.28f), 7.5.dp.toPx() + 3.dp.toPx(), Offset(cx, ky))
                    drawCircle(p.accent, 7.5.dp.toPx(), Offset(cx, ky))
                }
                T(Mezcla.EQ_LABELS[i], 11.2f * sk.tk, p.muted, family = Mono, lh = 1.2f, maxLines = 1)
            }
        }
        Note("Arrastra cada banda hacia arriba o hacia abajo. Toca dos veces una banda para dejarla a 0.", sk)
        // las cuatro grandes y los estilos
        Column(Modifier.widthIn(max = 560.dp).graphicsLayer { alpha = if (eqOn) 1f else 0.45f }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in listOf("custom", "graves", "voces", "agudos").chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (id in row) {
                    val on = sel == id
                    val shape = sk.r(18f)
                    Column(Modifier.weight(1f).press { if (!direct) Ajustes.setEq(id) }.clip(shape).then(if (on) Modifier.background(p.grad) else Modifier.background(Color.White.copy(alpha = 0.03f)).border(sk.b, p.line2, shape))
                        .padding(horizontal = 16.dp + sk.b, vertical = 14.dp + sk.b), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(26.dp)) { for (path in EQ_ICON[id]!!) Ico(path, 26f, if (on) Color(0xFF140B1E) else p.muted, sw = 1.8f) }
                        T(Mezcla.eqPreset(id)!!.name, 15f * sk.tk, if (on) Color(0xFF140B1E) else p.fg, weight = FontWeight.Bold)
                    }
                }
            }
        }
        Chips(Modifier.graphicsLayer { alpha = if (eqOn) 1f else 0.45f }) {
            for (e in Mezcla.EQ_PRESETS.filter { it.id !in listOf("custom", "graves", "voces", "agudos") }) Chip(e.name, sel == e.id, sk) { if (!direct) Ajustes.setEq(e.id) }
            for (m in Mezcla.eqMine()) {
                val id = "mine-" + Json.s(m["id"]); val on = sel == id
                val shape = RoundedCornerShape(999.dp)
                Row(Modifier.clip(shape).then(if (on) Modifier.background(p.grad) else Modifier.background(Color.Black.copy(alpha = 0.3f)).border(sk.b, p.line2, shape)).padding(start = 15.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.press { if (!direct) Ajustes.setEq(id) }) { T(Json.s(m["name"]), 14.08f * sk.tk, if (on) p.onAccent else p.muted, weight = FontWeight.ExtraBold, maxLines = 1) }
                    Box(Modifier.size(22.dp).clip(CircleShape).press { Ajustes.delEq(Json.s(m["id"])) }.graphicsLayer { alpha = 0.75f }, contentAlignment = Alignment.Center) {
                        T("×", 16.8f * sk.tk, if (on) p.onAccent else p.muted, lh = 1f, maxLines = 1)
                    }
                }
            }
        }
        if (eqOn && sel == "custom" && Mezcla.eqMigrate(Prefs.get("eqCustom")).any { it != 0 }) {
            var name by remember { mutableStateOf("") }
            RowWrap {
                KeyRow(Modifier.widthIn(max = 420.dp), field = { m -> Field(name, { name = it.take(24) }, sk, m, placeholder = "Nombre para tu ajuste", mono = true, onDone = { if (Ajustes.saveEq(name)) name = "" }) }) {
                    HBtn("Guardar", sk, primary = true, kind = 1) { if (Ajustes.saveEq(name)) name = "" }
                }
                HBtn("Todo a 0", sk, kind = 1) { Prefs.set("eqCustom", List(10) { 0 }); Prefs.set("eq", "custom"); apply() }
            }
        }
    }
}

// ---------------------------------------------------------------- el tema
@Composable
private fun ThemePanel(sk: Skin) {
    val p = sk.pal
    val cur = Prefs.str("theme", "auto")
    val m = Momento.moment()
    Panel(sk) {
        H2("Tema", sk)
        Note("Elige el color de la app. «Según la portada» toma el color de la canción que suena y cambia con cada una.", sk)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = max(1, ((maxWidth.value + 10f) / 160f).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in Momento.THEMES.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (th in row) {
                        val on = cur == th.id
                        val shape = sk.r(16f)
                        Column(Modifier.weight(1f).then(if (on) Modifier.glow(p.glow, (16f * sk.round).dp, 26.dp, 0.dp, (-8).dp) else Modifier).press { Ajustes.setTheme(th.id) }.clip(shape).background(Color.Black.copy(alpha = 0.3f))
                            .border(if (on) sk.b * 2 else sk.b, if (on) p.accent else p.line2, shape).padding(12.dp + sk.b), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Swatch(th, sk, Modifier.fillMaxWidth().height(46.dp).clip(sk.r(10f)))
                            T(th.name, 14.4f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1)
                            T(if (th.id == "auto") "${th.desc} (ahora: ${m.franja.phrase})" else th.desc, 11.84f * sk.tk, p.muted, lh = 1.3f)
                        }
                    }
                    for (k in row.size until cols) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Swatch(th: Momento.Theme, sk: Skin, modifier: Modifier) {
    when (th.id) {
        "auto" -> Box(modifier.background(Brush.horizontalGradient(Momento.FRANJAS.map { hsl(it.hue.toFloat(), 0.9f, 0.6f) })))
        "cover" -> {
            val t = Player.current()
            if (t != null && Lib.coverFile(t) != null) HCover(Inicio.cover(t), sk, modifier, 16.8f)
            else Box(modifier.background(Brush.sweepGradient(listOf(hsl(0f, .85f, .6f), hsl(120f, .85f, .5f), hsl(240f, .85f, .6f), hsl(360f, .85f, .6f)))))
        }
        else -> {
            val hh = if (th.id == "custom") Prefs.num("customHue", 200.0).toFloat() else th.hue.toFloat()
            val ss = if (th.id == "custom") Prefs.num("customSat", 0.9).toFloat() else th.sat.toFloat()
            Box(modifier.background(Brush.linearGradient(0f to hsl(hh, ss * 0.96f, 0.64f), 0.6f to hsl(hh - 45f, ss * 0.92f, 0.6f), 1f to Color(0xFF0D0A18))))
        }
    }
}

// ---------------------------------------------------------------- el aspecto
@Composable
private fun LookPanel(sk: Skin) {
    Panel(sk) {
        H2("Personaliza Órbita", sk)
        if (Prefs.str("theme", "auto") == "custom") Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Tu color", sk)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Slider("Tono", "", Prefs.num("customHue", 200.0).toFloat(), 0f, 359f, 1f, sk, hue = true, onChange = { Prefs.set("customHue", it.roundToInt()); Nativo.look(); Ver.bump() })
                Slider("Intensidad", "", Prefs.num("customSat", 0.9).toFloat(), 0.15f, 1f, 0.01f, sk, onChange = { Prefs.set("customSat", Math.round(it * 100) / 100.0); Nativo.look(); Ver.bump() })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((key, label) in listOf("lkBg" to "Fondo", "lkRound" to "Esquinas", "lkText" to "Tamaño del texto", "lkTitles" to "Letra de los títulos", "startView" to "Órbita se abre en")) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Lbl(label, sk)
                    Seg(LOOK_OPTS[key]!!, Prefs.str(key, LOOK_DEF[key]!!), sk) { v -> Ajustes.setLook(key, v) }
                }
            }
        }
        var glow by remember { mutableStateOf(-1f) }
        val gv = if (glow >= 0) glow else Prefs.num("lkGlow", 1.0).toFloat()
        Slider("Brillo de los efectos", "${Math.round(gv * 100)} %", gv, 0f, 1.6f, 0.05f, sk, Modifier.widthIn(max = 420.dp), onChange = { glow = it; Prefs.set("lkGlow", Math.round(it * 100) / 100.0); Nativo.look() }, onDone = { glow = -1f; Ver.bump() })
        Eyebrow("En la pantalla Ahora", sk)
        RowWrap {
            Toggle("Descubre: canciones nuevas para ti", Prefs.bool("recOn", true), sk) { val on = !Prefs.bool("recOn", true); Ajustes.set("recOn", on); Inicio.mark(); Nativo.toast(if (on) "Descubre encendido: te buscaré canciones nuevas" else "Descubre apagado"); if (on) Recos.refresh(false) }
            Toggle("Que la DJ me proponga canciones", Prefs.bool("recNova", true), sk) { Ajustes.set("recNova", !Prefs.bool("recNova", true)) }
        }
        Note("«Descubre» busca en Deezer artistas parecidos a los que escuchas y te pone muestras de 30 segundos. Solo salen del móvil los nombres de los artistas.", sk)
        RowWrap {
            Toggle("Para ti hoy", Prefs.bool("lkForYou", true), sk) { Ajustes.set("lkForYou", !Prefs.bool("lkForYou", true)); Inicio.mark() }
            Toggle("Mezcla a medida", Prefs.bool("lkMixer", true), sk) { Ajustes.set("lkMixer", !Prefs.bool("lkMixer", true)); Inicio.mark() }
            Toggle("Cinta de titulares", Prefs.bool("lkTicker", true), sk) { Ajustes.set("lkTicker", !Prefs.bool("lkTicker", true)); Inicio.mark() }
        }
        Eyebrow("Detalles", sk)
        RowWrap {
            Toggle("Animación al abrir", !Prefs.bool("introOff", false), sk) { val off = !Prefs.bool("introOff", false); Ajustes.set("introOff", off); Nativo.toast(if (!off) "La animación de entrada vuelve la próxima vez que abras Órbita" else "Órbita se abrirá sin animación de entrada") }
            Toggle("Vibración al tocar", Prefs.bool("haptics", true), sk) { Ajustes.set("haptics", !Prefs.bool("haptics", true)); Nativo.haptic(longArrayOf(0, 12)) }
        }
    }
}

@Composable
private fun CoversPanel(sk: Skin) {
    Panel(sk) {
        H2("Portadas", sk)
        RowWrap {
            Lbl("En la pantalla Ahora, mostrar", sk)
            Seg(listOf("vinyl" to "Vinilo", "art" to "Portada grande", "viz" to "Visualizador"), Prefs.str("heroMode", "vinyl"), sk) { v -> Ajustes.set("heroMode", v); Inicio.mark() }
        }
        Note("La portada sale de la imagen que trae la propia canción. Si no la trae y eliges una carpeta, uso la imagen del álbum que haya dentro (cover.jpg, folder.jpg…). También puedes ponerla a mano con el lápiz de cada canción en Biblioteca. Toca la portada del reproductor para verla a pantalla completa.", sk)
    }
}

// ---------------------------------------------------------------- la DJ
@Composable
private fun DjPanel(sk: Skin) {
    val p = sk.pal
    val name = Nova.name()
    Panel(sk, glow = true) {
        H2("Tu DJ: nombre, carácter y voz", sk)
        Eyebrow("Tu ciudad", sk)
        val city = Json.obj(Prefs.get("city"))
        var cityIn by remember { mutableStateOf("") }
        KeyRow(Modifier.widthIn(max = 420.dp), field = { m -> Field(cityIn, { cityIn = it.take(60) }, sk, m, placeholder = if (city != null) Json.s(city["name"]) + Json.s(city["country"]).let { if (it.isNotEmpty()) ", $it" else "" } else "Tu ciudad (opcional)", mono = true, onDone = { if (cityIn.isNotBlank()) { Ajustes.setCity(cityIn.trim()); cityIn = "" } }) }) {
            HBtn("Guardar", sk, kind = 1) { if (cityIn.isNotBlank()) { Ajustes.setCity(cityIn.trim()); cityIn = "" } }
            if (city != null) HBtn("Borrar", sk, kind = 1) { Prefs.set("city", null); Prefs.set("wx", null); Ver.bump(); Nativo.toast("Ciudad borrada") }
        }
        Note(if (city != null) "$name tiene en cuenta el tiempo que hace en ${Json.s(city["name"])} para saludarte y elegir la música." else "Si me dices tu ciudad, $name tendrá en cuenta el tiempo que hace. No uso tu ubicación.", sk)
        Eyebrow("Nombre", sk)
        var nm by remember { mutableStateOf(name) }
        KeyRow(Modifier.widthIn(max = 420.dp), field = { m -> Field(nm, { nm = it.take(20) }, sk, m, placeholder = "Nova", mono = true, onDone = { Ajustes.setDjName(nm) }) }) {
            HBtn("Cambiar nombre", sk, primary = true, kind = 1) { Ajustes.setDjName(nm) }
        }
        Eyebrow("Personalidad", sk)
        val per = Nova.persona()
        Chips { for ((id, pp) in Nova.PERSONAS) Chip(pp.name, per == id, sk) { Ajustes.setPersona(id) } }
        Note((Nova.PERSONAS[per]?.desc ?: "") + ".", sk)
        RowWrap {
            Lbl("Presenta", sk)
            Seg(listOf("siempre" to "Todas las canciones", "aveces" to "Una de cada tres", "inicio" to "Solo al empezar"), Nova.freq(), sk) { v -> Ajustes.set("djFreq", v) }
        }
        Eyebrow("Voz propia", sk)
        VozPropiaCard(sk)
        Eyebrow("Cerebro", sk)
        ComprensionCard(sk)
        Eyebrow("Cerebro grande", sk)
        CerebroGrandeCard(sk)
        RowWrap {
            Toggle("Entenderte sin internet", Prefs.bool("sttOffline", false), sk) { val v = !Prefs.bool("sttOffline", false); Ajustes.set("sttOffline", v); Nativo.toast(if (v) "Te escucharé sin internet (con el reconocedor del móvil)" else "Te escucharé como siempre (y sin internet, sin conexión)") }
            Note("El micrófono usa el reconocimiento de voz del propio móvil, sin conexión: más privado y funciona sin cobertura. Sin internet lo hace siempre. Si no te entiende, descarga el español en la app de Google › Ajustes › Voz › Reconocimiento de voz sin conexión.", sk)
        }
        val all = Prefs.bool("allVoices", false)
        HeadWith({ Eyebrow("Voces del sistema", sk) }) { Toggle("Más idiomas", all, sk) { Ajustes.set("allVoices", !all) } }
        Note("Las que trae tu móvil, de la más natural a la más robótica. Se usan si no tienes la voz propia o si la desactivas.", sk)
        val list = Voz.voices(all)
        if (list.isEmpty()) Note(if (Voz.voices(true).isNotEmpty()) "No hay voces en español en este dispositivo. Activa «Más idiomas» o instala voces de español (mira la nota de abajo)." else "Cargando las voces del dispositivo…", sk)
        else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val cur = Voz.current()
            for (vc in list.take(60)) {
                val on = cur?.name == vc.name
                val (lb, where) = Voz.labelOf(vc)
                val shape = sk.r(14f)
                Row(Modifier.fillMaxWidth().then(if (on) Modifier.glow(p.glow, (14f * sk.round).dp, 26.dp, 0.dp, (-10).dp) else Modifier).clip(shape).background(Color.Black.copy(alpha = 0.3f))
                    .border(if (on) sk.b * 2 else sk.b, if (on) p.accent else p.line2, shape).padding(start = 14.dp + sk.b, end = 12.dp + sk.b, top = 12.dp + sk.b, bottom = 12.dp + sk.b),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        T(lb, 15f * sk.tk, p.fg, weight = FontWeight.Bold, maxLines = 1)
                        HFlow(6.dp, 4.dp) {
                            Pill(if (Voz.isNatural(vc)) "Natural" else "Estándar", sk, accent = Voz.isNatural(vc))
                            T(where + if (vc.local) "" else " · con internet", 12.16f * sk.tk, p.muted, maxLines = 1)
                        }
                    }
                    HBtn("Probar", sk, kind = 1) { Voz.test("Hola, soy $name. Así sonaría presentando tus canciones en Órbita FM.", vc.name) }
                    if (on) Pill("En uso", sk, accent = true)
                    else HBtn("Usar", sk, primary = true, kind = 1) { Voz.use(vc.name); Ver.bump(); Inicio.mark(); Voz.speak("Perfecto. A partir de ahora sonaré así.") }
                }
            }
        }
        Eyebrow("Estilo", sk)
        val style = Prefs.str("vStyle", "natural")
        Chips { for ((id, nm2, rp) in VSTYLES) Chip(nm2, style == id, sk) { Ajustes.set("vStyle", id); Prefs.set("vRate", rp.first); Prefs.set("vPitch", rp.second); Ver.bump(); Voz.speak("Así sueno en modo ${nm2.lowercase()}. Te presento la siguiente canción.") } }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rate = Prefs.num("vRate", 1.0).toFloat(); val pitch = Prefs.num("vPitch", 1.0).toFloat()
            Slider("Velocidad", "×" + String.format(java.util.Locale.US, "%.2f", rate), rate, 0.75f, 1.3f, 0.01f, sk,
                onChange = { Prefs.set("vRate", Math.round(it * 100) / 100.0); Prefs.set("vStyle", "custom"); Ver.bump() }, onDone = { Voz.speak("Así suena con este ajuste.") })
            Slider("Tono", "×" + String.format(java.util.Locale.US, "%.2f", pitch), pitch, 0.7f, 1.3f, 0.01f, sk,
                onChange = { Prefs.set("vPitch", Math.round(it * 100) / 100.0); Prefs.set("vStyle", "custom"); Ver.bump() }, onDone = { Voz.speak("Así suena con este ajuste.") })
        }
        InfoBox("¿Quieres voces más naturales?", " La app usa las voces de tu dispositivo, así que depende de lo que tengas instalado. En el ordenador, Microsoft Edge trae voces «Natural» muy realistas. En Android, ve a Ajustes, busca «Texto a voz», elige el motor de Google y descarga las voces de español: aparecerán aquí solas.", sk)
    }
}

// ---------------------------------------------------------------- rendimiento y almacenamiento
@Composable
private fun PerfPanel(sk: Skin) {
    Panel(sk) {
        H2("Rendimiento y almacenamiento", sk)
        RowWrap {
            Lbl("Animaciones", sk)
            Seg(listOf("full" to "Completas", "lite" to "Ligeras (ahorran batería)"), Prefs.str("anim", "full"), sk) { v -> Ajustes.set("anim", v); Nativo.bgSync() }
        }
        Note("«Ligeras»: menos movimiento y sin luces (los botones no rebotan, las canciones cambian sin deslizarse). Va más suave en móviles lentos y gasta menos batería.", sk)
        RowWrap {
            Toggle("Luces con la música", Prefs.bool("lights", true), sk) { Ajustes.set("lights", !Prefs.bool("lights", true)) }
            Note("Un halo con los colores de la portada que late con el bombo, el vinilo que brilla con el ritmo y las letras que se van llenando.", sk)
        }
        val (used, free) = Ajustes.storage()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Lbl("Tu música ocupa ${Ajustes.gb(used.toDouble())} de ${Ajustes.gb((used + free).toDouble())} disponibles en este móvil", sk)
            Meter(if (used + free > 0) used.toFloat() / (used + free) else 0f, sk)
        }
        RowWrap { Note("Tus canciones se guardan dentro de la app: Android no las borra. Solo se pierden si desinstalas Órbita o borras sus datos.", sk) }
    }
}

// ---------------------------------------------------------------- copias de seguridad
@Composable
private fun BackupPanel(sk: Skin) {
    Panel(sk) {
        H2("Copia de seguridad", sk)
        Note("Guarda en un archivo tus favoritas, listas, letras, estadísticas, grabaciones de voz y ajustes. Si cambias de móvil o reinstalas Órbita, restáurala y lo recuperas todo: las canciones se emparejan por título y artista. Los archivos de música no van dentro de la copia; siguen en tu móvil.", sk)
        RowWrap {
            HBtn("Hacer copia", sk, primary = true, kind = 1, enabled = !Copia.busy) { Copia.export() }
            HBtn("Restaurar una copia", sk, kind = 1) { Copia.pickAndRestore() }
            val last = Prefs.num("lastBackup", 0.0).toLong()
            Note(if (last > 0) { val c = Calendar.getInstance(); c.timeInMillis = last; "Última copia: ${c.get(Calendar.DAY_OF_MONTH)} de ${Recap.MESES[c.get(Calendar.MONTH)]} de ${c.get(Calendar.YEAR)}." } else "Aún no has hecho ninguna copia.", sk)
        }
    }
}

// ---------------------------------------------------------------- la app y los permisos
@Composable
private fun PermsPanel(sk: Skin) {
    val up = Ajustes.update
    Panel(sk) {
        H2("App y permisos", sk)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermRow("Órbita ${Ajustes.version()}", if (up != null) "Hay una versión nueva (${up.version}). Descárgala e instálala encima: no pierdes nada." else "App nativa: todo funciona dentro del móvil, sin navegador. Las actualizaciones llegan desde tu GitHub.", sk,
                if (up != null) "no" to "Nueva versión" else "ok" to "Instalada", if (up != null) "Ver" else "Buscar actualización") { if (up != null) { Prefs.set("skipUpdate", ""); Nativo.go("inicio"); Inicio.mark() } else Ajustes.checkUpdate(true) }
            val mic = Ajustes.micState()
            PermRow("Micrófono", if (mic == "denied") "Bloqueado. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Micrófono." else "Para grabar tus frases de DJ y tus canciones.", sk,
                if (mic == "granted") "ok" to "Permitido" else if (mic == "denied") "no" to "Bloqueado" else null, if (mic != "granted" && mic != "denied") "Permitir" else null) { Ajustes.askMic() }
            PermRow("Widget en la pantalla de inicio", "Mantén pulsado un hueco libre de la pantalla de inicio › Widgets › Órbita. Muestra la canción con su portada y los botones para pausar y saltar.", sk, "ok" to "Disponible")
            PermRow("Compartir con Órbita", "En YouTube, WhatsApp o el gestor de archivos pulsa Compartir › Órbita: los vídeos se abren aquí dentro y las canciones entran en tu biblioteca.", sk, "ok" to "Activo")
            PermRow("Voz de ${Nova.name()}", if (Voz.loaded()) "Usa el motor de voz de Android. Para más voces: Ajustes del móvil › «Texto a voz» › motor de Google › instalar datos de voz en español." else "No se ha encontrado el motor de voz del móvil.", sk,
                if (Voz.loaded()) "ok" to "Activa" else "no" to "No disponible")
            PermRow("Controles en la pantalla de bloqueo", "Sigue sonando con la pantalla apagada; pausa, salta canciones y ve la portada desde la notificación, la pantalla de bloqueo o los auriculares. Si se corta al bloquear el móvil, quita el ahorro de batería a Órbita.", sk, "ok" to "Activo")
            val dev = Biblio.devState()
            PermRow("Música del móvil", if (dev == "granted") "Órbita lee tus canciones directamente del almacenamiento, sin copiarlas. Solo lectura: nunca modifica ni borra tus archivos." else if (dev == "denied") "Bloqueado. Actívalo en Ajustes del móvil › Apps › Órbita › Permisos › Música y audio." else "Para reproducir todas las canciones que ya tienes en el móvil.", sk,
                if (dev == "granted") "ok" to "Permitido" else if (dev == "denied") "no" to "Bloqueado" else null, if (dev == "granted") null else "Permitir") { Biblio.connect() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("IA de Nova en la app instalada", sk)
            Note("Dentro de Claude, la IA usa tu cuenta de Claude. En la app instalada necesita una clave de la API de Claude (la creas en la consola de desarrolladores: console.anthropic.com). Sin clave, todo funciona igual y Nova elige al azar. La clave se guarda solo en este dispositivo, y cada mezcla o búsqueda gasta un poco del saldo de tu cuenta de la API.", sk)
            var key by remember { mutableStateOf("") }
            val has = Ia.key().isNotEmpty()
            KeyRow(field = { m -> Field(key, { key = it.trim() }, sk, m, placeholder = if (has) "Clave guardada (escribe otra para cambiarla)" else "sk-ant-…", password = true, onDone = { if (Ajustes.setKey(key)) key = "" }) }) {
                HBtn("Guardar clave", sk, primary = true, kind = 1) { if (Ajustes.setKey(key)) key = "" }
                if (has) HBtn("Quitar", sk, kind = 1) { Prefs.set("apiKey", ""); Ver.bump(); Inicio.mark(); Nativo.toast("Clave quitada") }
            }
        }
    }
}
