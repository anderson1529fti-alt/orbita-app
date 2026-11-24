package __PACKAGE__

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fase 4, parte 2 · El despertador con Nova (lo que en la web era «3.5 · Despertador»): tu música subiendo poco a poco,
 * la hora y el tiempo, a pantalla completa. La alarma de verdad la programa Android ([NovaAlarm]); aquí está lo demás.
 */
object Alarma {
    private val main = Handler(Looper.getMainLooper())
    val DAYS = listOf(Triple(1, "L", "lunes"), Triple(2, "M", "martes"), Triple(3, "X", "miércoles"), Triple(4, "J", "jueves"), Triple(5, "V", "viernes"), Triple(6, "S", "sábado"), Triple(0, "D", "domingo"))
    val MUSIC = listOf("suave" to "Suave para despertar", "daily" to "Tu mezcla del día", "favs" to "Tus favoritas", "radio" to "Modo radio")

    class Cfg(var on: Boolean, var h: Int, var m: Int, var days: MutableList<Int>, var vol: Int, var snooze: Int, var music: String, var talk: Boolean) {
        fun toMap(): Map<String, Any?> = linkedMapOf("on" to on, "h" to h, "m" to m, "days" to days.toList(), "vol" to vol, "snooze" to snooze, "music" to music, "talk" to talk)
    }

    fun cfg(): Cfg {
        val o = Json.obj(Prefs.get("alarm")) ?: emptyMap()
        return Cfg(Json.b(o["on"], false), Json.l(o["h"], 7).toInt(), Json.l(o["m"], 30).toInt(),
            (Json.list(o["days"])?.map { Json.l(it).toInt() } ?: listOf(1, 2, 3, 4, 5)).toMutableList(),
            Json.l(o["vol"], 60).toInt(), Json.l(o["snooze"], 10).toInt(), Json.s(o["music"], "suave"), Json.b(o["talk"], true))
    }

    // ---------------------------------------------------------------- lo que sabe Android (como «get» del plugin)
    class St(val on: Boolean, val next: Long, val ringAt: Long, val ringState: Int, val ringKind: String, val exact: Boolean, val notif: Boolean, val fullScreen: Boolean)

    fun st(): St? {
        val c = Nativo.act ?: return null
        return try {
            val p = NovaAlarm.prefs(c)
            St(p.getBoolean("on", false), p.getLong("next", -1), p.getLong("ringAt", 0), p.getInt("ringState", 0), p.getString("ringKind", "alarm") ?: "alarm",
                NovaAlarm.canExact(c), NovaAlarm.notificationsOk(c), NovaAlarm.fullScreenOk(c))
        } catch (t: Throwable) { null }
    }

    /** «alarmSave»: guarda los ajustes y programa la alarma del móvil. */
    fun save(c: Cfg, quiet: Boolean) {
        Prefs.set("alarm", c.toMap())
        val a = Nativo.act
        if (a != null) try {
            val e = NovaAlarm.prefs(a).edit()
            e.putBoolean("on", c.on); e.putInt("hour", c.h.coerceIn(0, 23)); e.putInt("minute", c.m.coerceIn(0, 59))
            e.putInt("snoozeMin", c.snooze.coerceIn(1, 60)); e.putInt("volume", c.vol.coerceIn(5, 100))
            var bits = 0; for (d in c.days) if (d in 0..6) bits = bits or (1 shl d); e.putInt("days", bits)
            if (!c.on) e.putLong("snoozeAt", 0)
            e.apply()
            NovaAlarm.schedule(a)
        } catch (t: Throwable) { if (!quiet) Nativo.toast("No he podido programar el despertador") }
        changed()
        if (!quiet && c.on) checkPerms(true)
    }

    /** Los permisos que hacen falta (los avisos, para verse con el móvil bloqueado). */
    fun checkPerms(ask: Boolean) {
        val a = Nativo.act ?: return
        val s = st()
        if (ask && s != null && !s.notif && android.os.Build.VERSION.SDK_INT >= 33) {
            (a as? androidx.activity.ComponentActivity)?.let { ca -> Actos.launch(ca, androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), "android.permission.POST_NOTIFICATIONS") { changed() } }
        }
        changed()
    }

    fun openSettings(what: String) {
        val a = Nativo.act ?: return
        val pkg = android.net.Uri.parse("package:" + a.packageName)
        val i = when {
            what == "exact" && android.os.Build.VERSION.SDK_INT >= 31 -> android.content.Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", pkg)
            what == "fullscreen" && android.os.Build.VERSION.SDK_INT >= 34 -> android.content.Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", pkg)
            what == "notif" && android.os.Build.VERSION.SDK_INT >= 26 -> android.content.Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", a.packageName)
            else -> android.content.Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkg)
        }
        try { a.startActivity(i) } catch (t: Throwable) { try { a.startActivity(android.content.Intent("android.settings.APPLICATION_DETAILS_SETTINGS", pkg)) } catch (ignored: Throwable) { } }
    }

    /** Lo que cambió: «Ahora» (la pastilla) y «Ajustes». */
    var ver by mutableIntStateOf(0); private set
    fun changed() { main.post { ver++; Inicio.mark() } }

    // ---------------------------------------------------------------- las horas, dichas
    fun pad2(n: Int) = n.toString().padStart(2, '0')
    fun hm(h: Int, m: Int) = "$h:${pad2(m)}"

    /** «las siete y media», «la una en punto», «las ocho menos cuarto». */
    fun horaHablada(c: Calendar = Calendar.getInstance()): Pair<String, String> {
        var h = c.get(Calendar.HOUR_OF_DAY); var m = c.get(Calendar.MINUTE); var menos = false
        if (m > 35) { h = (h + 1) % 24; m = 60 - m; menos = true }
        val h12 = if (h % 12 == 0) 12 else h % 12
        val art = if (h12 == 1) "la una" else "las $h12"
        val tail = if (m == 0) " en punto" else if (m == 15) (if (menos) " menos cuarto" else " y cuarto") else if (m == 30) " y media" else if (menos) " menos $m" else " y $m"
        return (if (h12 == 1) "Es" else "Son") to (art + tail)
    }

    /** Cuándo sonará (la misma cuenta que la del móvil). */
    fun next(c: Cfg = cfg(), now: Long = System.currentTimeMillis()): Long {
        if (!c.on) return -1
        val d = Calendar.getInstance(); d.timeInMillis = now
        for (i in 0 until 9) {
            val x = Calendar.getInstance(); x.clear(); x.set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH) + i, c.h, c.m, 0)
            if (x.timeInMillis > now + 1000 && (c.days.isEmpty() || (x.get(Calendar.DAY_OF_WEEK) - 1) in c.days)) return x.timeInMillis
        }
        return -1
    }

    fun whenText(ts: Long): String {
        if (ts <= 0) return ""
        val d = Calendar.getInstance(); d.timeInMillis = ts
        val day = Math.round((Recap.dayIdx(ts) - Recap.dayIdx(System.currentTimeMillis())).toDouble()).toInt()
        val dia = if (day == 0) "hoy" else if (day == 1) "mañana" else if (day < 7) "el ${Momento.DAYS[d.get(Calendar.DAY_OF_WEEK) - 1]}" else "el ${d.get(Calendar.DAY_OF_MONTH)} de ${Recap.MESES[d.get(Calendar.MONTH)]}"
        val left = ts - System.currentTimeMillis(); val h = (left / 3600000L).toInt(); val mi = Math.round((left % 3600000L) / 60000.0).toInt()
        return "$dia a las ${hm(d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))}" + (if (left < 24 * 3600000L) " (dentro de ${if (h > 0) "$h h " else ""}$mi min)" else "")
    }

    fun daysText(days: List<Int>): String {
        val s = days.sortedBy { (it + 6) % 7 }
        if (s.isEmpty()) return "Solo una vez"
        if (s.size == 7) return "Todos los días"
        if (s == listOf(1, 2, 3, 4, 5)) return "De lunes a viernes"
        if (s == listOf(6, 0)) return "Los fines de semana"
        return "Los " + Cerebro.listES(s.map { d -> DAYS.first { it.first == d }.third })
    }

    /** La pastilla de «Ahora» («7:30»), o nada. */
    fun pill(): String {
        val c = cfg(); if (!c.on) return ""
        val s = st(); val n = if (s != null && s.on && s.next > 0) s.next else next(c)
        if (n <= 0) return ""
        val d = Calendar.getInstance(); d.timeInMillis = n
        return hm(d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))
    }
    fun nextNow(): Long { val c = cfg(); if (!c.on) return -1; val s = st(); return if (s != null && s.on && s.next > 0) s.next else next(c) }

    fun musicText(c: Cfg = cfg()): String {
        val m = c.music
        if (m.startsWith("list:")) { val pl = Lib.playlists.firstOrNull { Json.s(it["id"]) == m.substring(5) }; return if (pl != null) "tu lista «${Json.s(pl["name"])}»" else "música suave" }
        return mapOf("suave" to "música suave", "daily" to "tu mezcla del día", "favs" to "tus favoritas", "radio" to "el modo radio")[m] ?: "tu música"
    }

    /** La música para despertar. */
    private class Pick(val radio: Boolean, val ids: List<String>, val name: String)
    private fun pick(c: Cfg): Pick? {
        val pool = Cerebro.pool().filter { !it.net }; val own = pool.filter { !it.demo }; val base = if (own.size >= 6) own else pool
        if (c.music == "radio" && base.size >= 2) return Pick(true, emptyList(), "")
        if (c.music == "daily") { val d = Cerebro.buildDaily(false); if (d != null && d.ids.isNotEmpty()) return Pick(false, d.ids.filter { Lib.byId(it) != null }, d.name) }
        if (c.music == "favs") { val f = base.filter { Biblio.liked(it) }; if (f.size >= 2) return Pick(false, f.map { it.id }.shuffled(), "Tus favoritas") }
        if (c.music.startsWith("list:")) {
            val pl = Lib.playlists.firstOrNull { Json.s(it["id"]) == c.music.substring(5) }
            val ids = pl?.let { (Json.list(it["trackIds"]) ?: emptyList()).map { x -> Json.s(x) }.filter { id -> Lib.byId(id) != null } }
            if (ids != null && ids.isNotEmpty()) return Pick(false, ids, Json.s(pl["name"]))
        }
        // «Suave para despertar»: las que conoces bien, de la más tranquila a una algo más alegre
        val st = Memoria.stats(60); val rec = st.all.associateBy { it.id }
        val sc = base.filter { !Cerebro.prefBlocked(it) }.map { t ->
            val r = rec[t.id]; val a = Analizador.get(t)
            Triple(t, Math.log1p((r?.plays ?: 0).toDouble()) + (if (Biblio.liked(t)) 1 else 0) - (if (Memoria.skipProne(t)) 1.2 else 0.0) + Math.random() * 0.7, a?.energy ?: 0.5)
        }.sortedByDescending { it.second }.take(24).sortedBy { it.third }
        return if (sc.isNotEmpty()) Pick(false, sc.map { it.first.id }, "Para despertar") else null
    }

    // ---------------------------------------------------------------- cuando suena
    var active by mutableStateOf(false); private set
    var ring = 0L; private set
    var kind = ""; private set
    var test = false; private set
    var acked by mutableStateOf(false); private set
    var noMusic by mutableStateOf(false); private set
    /** El volumen del despertador (de muy bajo a tu volumen): multiplica el de la música. */
    @Volatile var f = 1f; private set
    private var gen = 0

    private fun later(ms: Long, fn: () -> Unit) { val g = gen; main.postDelayed({ if (g == gen) fn() }, ms) }
    private fun setF(v: Float) { f = v; Voz.applyMaster() }

    fun busy(): Boolean = active

    fun greeting(kind: String): String {
        val c = Calendar.getInstance(); val h = c.get(Calendar.HOUR_OF_DAY); val (verb, text) = horaHablada(c); val t = Player.current(); val dow = c.get(Calendar.DAY_OF_WEEK) - 1
        val hola = if (h in 5..12) "Buenos días" else if (h in 13..20) "Buenas tardes" else "Buenas noches"
        val day = "Hoy es ${Momento.DAYS[dow]}, ${c.get(Calendar.DAY_OF_MONTH)} de ${Recap.MESES[c.get(Calendar.MONTH)]}."
        val close = if (dow == 5) "¡Por fin es viernes!" else if (dow == 6 || dow == 0) "Es fin de semana: sin prisas." else Cerebro.pick(listOf("Despacito, que la música ya está aquí.", "¡Arriba, que hoy va a ser un buen día!", "Tómate tu tiempo: yo me encargo de la música.", "Venga, que hoy es tu día."))
        if (kind == "snooze") return "Venga, ahora sí. $verb $text. $close"
        return "$hola. $verb $text. $day${Online.weatherLine()} ${if (t != null) "Te despierto con ${Cerebro.nameOf(t)}. " else ""}$close".replace(Regex("\\s+"), " ").trim()
    }

    /** «alarmWake»: la pantalla, la música (muy baja y subiendo) y los buenos días de Nova. */
    fun wake(ring0: Long, kind0: String = "alarm", test0: Boolean = false) {
        if (active && ring == ring0) { show(); return }
        gen++; active = true; ring = ring0; kind = kind0; test = test0; acked = false; noMusic = false
        val c = cfg()
        // un despertador de una sola vez se apaga solo al sonar (el móvil hace lo mismo)
        if (!test0 && kind0 == "alarm" && c.days.isEmpty() && c.on) { c.on = false; Prefs.set("alarm", c.toMap()); changed() }
        show()
        val a = Nativo.act
        if (a != null && !test0) try { NovaAlarm.raiseVolume(a, c.vol) } catch (ignored: Throwable) { }
        if (Online.on() && Prefs.get("city") != null) Online.loadWeather(false) { ver++ }
        val p = pick(c)
        if (p == null) { noMusicNow(); return }
        setF(0.1f)
        if (p.radio) { if (!Radio.on) Nova.radioButton() else if (!Player.playing) Player.toggle() }
        else Player.playQueue(p.ids, 0, "library")
        // de muy bajo a tu volumen en unos 50 segundos (igual en sonoridad: ×10 de nivel)
        val t0 = SystemClock.uptimeMillis()
        val ramp = object : Runnable { override fun run() { if (!active) return; val k = min(1f, (SystemClock.uptimeMillis() - t0) / 50000f); setF(if (k >= 1f) 1f else (0.1 * 10.0.pow(k.toDouble())).toFloat()); if (k < 1f) later(400) { run() } } }
        later(400, ramp::run)
        // suena de verdad: se le dice al móvil (no hace falta la alarma de reserva)
        val ack = object : Runnable { override fun run() {
            if (!active) return
            val t = Player.current()
            if (Player.playing && Bar.pos > 0.6 && t != null) {
                acked = true; ver++
                if (a != null && !test) try { NovaAlarm.ack(a, ring, t.title) } catch (ignored: Throwable) { }
            } else later(700) { run() }
        } }
        later(700, ack::run)
        later(20000) { if (active && !acked) noMusicNow() }
        if (c.talk) later(if (kind0 == "snooze") 5000 else if (test0) 6000 else 13000) { if (active) Nova.djTalk(greeting(kind0), "test", forceTTS = true) }
        // media hora sin tocarlo: se apaga
        later(30 * 60000L) { if (active) end("off", 0L, true) }
    }

    private fun noMusicNow() {
        val a = Nativo.act
        if (a != null && !test) try { NovaAlarm.ringLoud(a, ring) } catch (ignored: Throwable) { }
        noMusic = true; ver++
    }

    private fun show() { Ventanas.open("alarm") }
    private fun hide() { Ventanas.gone("alarm"); try { NovaAlarmPlugin.releaseLock() } catch (ignored: Throwable) { } }

    /** Posponer, «Ya me levanto» (la música sigue) o apagar. fromPhone: se tocó en el aviso del móvil (o su hora). */
    fun end(how: String, fromPhone: Long = 0L, auto: Boolean = false) {
        if (!active) { hide(); return }
        active = false; gen++; Voz.stop(); hide()
        val wasTest = test; val a = Nativo.act; val c = cfg()
        if (how == "up") {
            setF(1f)
            if (a != null && !wasTest && fromPhone == 0L) try { NovaAlarm.stop(a, true) } catch (ignored: Throwable) { }
            if (c.talk) Nova.djTalk(Cerebro.pick(listOf("¡Genial! Que tengas un gran día.", "Perfecto. La música sigue, tú a lo tuyo.", "¡Así me gusta! Sigo pinchando para ti.")), "test", forceTTS = true)
            changed(); return
        }
        // baja la música y la para
        val f0 = f
        for (k in 1..8) main.postDelayed({ setF(f0 * (1 - k / 8f)) }, 170L * k)
        main.postDelayed({
            if (Player.current() != null && Player.playing) Player.fadePause()
            main.postDelayed({ setF(1f) }, 900)
            if (how == "snooze") {
                var at = System.currentTimeMillis() + c.snooze * 60000L
                if (a != null && !wasTest && fromPhone == 0L) try { val r = NovaAlarm.snooze(a); if (r > 0) at = r } catch (ignored: Throwable) { }
                else if (fromPhone > 1e12.toLong()) at = fromPhone
                val d = Calendar.getInstance(); d.timeInMillis = at
                Nativo.toast("${if (wasTest) "Prueba: te despertaría" else "Te despierto"} a las ${hm(d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))}", 3600)
            } else {
                if (a != null && !wasTest && fromPhone == 0L) try { NovaAlarm.stop(a, false) } catch (ignored: Throwable) { }
                if (auto) Nativo.toast("Despertador apagado: media hora sin tocarlo")
            }
            changed()
        }, 170L * 8 + 30)
    }

    /** Lo que avisa el móvil (la alarma suena, o se tocó «Posponer» o «Apagar» en su aviso). Con la Órbita nativa, aquí. */
    @JvmStatic fun javaEvent(type: String, ring0: Long, kind0: String?): Boolean {
        if (!Nativo.on) return false
        main.post {
            when (type) {
                "fire", "open" -> wake(if (ring0 > 0) ring0 else System.currentTimeMillis(), kind0 ?: "alarm")
                "snooze" -> end("snooze", if (ring0 > 0) ring0 else 2L)
                "stop" -> end("off", 2L)
            }
        }
        return true
    }

    /** Al arrancar: lo que dice el móvil (uno de una sola vez se apaga solo) y una alarma que suena ahora mismo. */
    fun boot() {
        val s = st() ?: return
        val c = cfg()
        if (!s.on && c.on && s.ringAt > 0) { c.on = false; Prefs.set("alarm", c.toMap()) }
        else if (c.on && !s.on && s.ringAt == 0L) save(c, true)
        changed()
        if (s.ringState == 1 && System.currentTimeMillis() - s.ringAt < 15 * 60000L) wake(s.ringAt, s.ringKind)
    }

    // ---------------------------------------------------------------- «despiértame a las siete y media»
    fun command(q: String): Boolean {
        val say = { s: String -> Ordenes.say(s) }
        val c = cfg()
        if (Regex("(quita|apaga|desactiva|cancela|borra)( el| la)? (despertador|alarma)|no me despiertes").containsMatchIn(q)) {
            if (!c.on) { say("No tienes ningún despertador puesto."); return true }
            c.on = false; save(c, true); say("Hecho: despertador apagado."); return true
        }
        if (Regex("(a que hora|cuando) (me despiertas|suena (el despertador|la alarma))|tengo (alarma|despertador)|(esta|tengo) puesto el despertador").containsMatchIn(q)) {
            val n = next(c); say(if (c.on && n > 0) "Te despierto ${whenText(n)}. ${daysText(c.days)}." else "No tienes ningún despertador puesto. Dime, por ejemplo, «despiértame a las siete y media»."); return true
        }
        if (!Regex("(despiertame|despiertanos|despertador|alarma|levantame)").containsMatchIn(q)) return false
        val m = Regex("(?:a las|a la|para las|para la|sobre las|las) (\\d{1,2})(?:(?: y |:| )(\\d{1,2}|media|cuarto)| menos (cuarto|\\d{1,2}))?(?: (?:de la|por la) (manana|tarde|noche|madrugada))?").find(q)
        if (m == null) { if (Regex("(despiertame|pon(me)? (el|un|una) (despertador|alarma))").containsMatchIn(q)) { say("¿A qué hora? Dime, por ejemplo, «despiértame a las siete y media»."); return true }; return false }
        val g = m.groupValues
        var h = g[1].toInt(); var mi = if (g[2] == "media") 30 else if (g[2] == "cuarto") 15 else if (g[2].isNotEmpty()) g[2].toInt() else 0
        if (g[3].isNotEmpty()) { h -= 1; mi = if (g[3] == "cuarto") 45 else 60 - g[3].toInt() }
        if ((g[4] == "tarde" || g[4] == "noche") && h < 12) h += 12
        if (h < 0) h += 24
        if (h > 23 || mi > 59) { say("Esa hora no la entiendo. Prueba con «a las siete y media»."); return true }
        val days = if (Regex("todos los dias|cada dia|siempre").containsMatchIn(q)) listOf(0, 1, 2, 3, 4, 5, 6) else if (Regex("entre semana|de lunes a viernes|los dias de diario|los laborables").containsMatchIn(q)) listOf(1, 2, 3, 4, 5)
            else if (Regex("fin(es)? de semana|el finde|los findes").containsMatchIn(q)) listOf(6, 0) else emptyList()
        c.on = true; c.h = h; c.m = mi; c.days = days.toMutableList()
        save(c, false)
        val n = next(c)
        say("Hecho: te despierto ${whenText(n).replace(Regex(" \\(dentro de.*\\)$"), "")}${if (days.isNotEmpty()) " (${daysText(days).lowercase()})" else ""} con ${musicText(c)}.")
        return true
    }
}

// ---------------------------------------------------------------- la pantalla del despertador
/** El fondo de las pantallas grandes (el despertador, la bienvenida, tu año): dos luces del color de la app que flotan. */
@Composable
internal fun FloatBg(sk: Skin, hueShift: Float) {
    val look = UiState.look
    val tr = rememberInfiniteTransition(label = "bg")
    val k by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "k")
    val lite = Nativo.lite()
    Canvas(Modifier.fillMaxSize().graphicsLayer { if (!lite) { val kk = (1 - kotlin.math.cos(k * Math.PI.toFloat())) / 2; translationX = -0.04f * size.width * 1.4f * kk; translationY = 0.03f * size.height * 1.4f * kk; scaleX = 1f + 0.06f * kk; scaleY = scaleX } }) {
        val w = size.width * 1.4f; val h = size.height * 1.4f; val ox = -size.width * 0.2f; val oy = -size.height * 0.2f
        val a = hsl(look.h + hueShift, look.s * 0.9f, 0.6f, 0.55f); val b = hsl(look.h + hueShift - 50f, look.s * 0.9f, 0.6f, 0.42f)
        drawRect(Brush.radialGradient(listOf(a, Color.Transparent), Offset(ox + w * 0.78f, oy + h * 0.18f), max(w * 0.6f, h * 0.45f) * 0.7f))
        drawRect(Brush.radialGradient(listOf(b, Color.Transparent), Offset(ox + w * 0.14f, oy + h * 0.84f), max(w * 0.55f, h * 0.42f) * 0.7f))
    }
}

@Composable
internal fun AlarmWindow(sk: Skin) {
    val p = sk.pal
    @Suppress("UNUSED_VARIABLE") val v = Alarma.ver
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5000); now = System.currentTimeMillis() } }
    val c = Calendar.getInstance().apply { timeInMillis = now }
    Box(Modifier.fillMaxSize().background(Color(0xFF06050B)).pointerBlock()) {
        FloatBg(sk, 30f)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vw = maxWidth.value
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp + Bar.inset.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                Box(Modifier.padding(bottom = 8.dp)) { BigAvatar(sk, 96f) }
                Eyebrow("${Cerebro.cap(Momento.DAYS[c.get(Calendar.DAY_OF_WEEK) - 1])}, ${c.get(Calendar.DAY_OF_MONTH)} de ${Recap.MESES[c.get(Calendar.MONTH)]}", sk)
                val fs = min(max(80f * sk.tk, vw * 0.28f), 144f * sk.tk)
                T(Alarma.hm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), fs, p.fg, family = sk.display, weight = sk.displayWeight, lh = 0.9f, ls = -0.05f, maxLines = 1, align = TextAlign.Center)
                val w = Online.weatherNow()
                Box(Modifier.heightIn(min = (15f * sk.tk * 1.4f).dp)) { T(if (w != null) "${Json.s(w["city"])} · ${Json.s(w["temp"])} °C · ${Online.wxDesc(Json.l(w["code"]).toInt()).first}" else "", 15f * sk.tk, p.muted, weight = FontWeight.Bold, align = TextAlign.Center) }
                val t = Player.current()
                Row(Modifier.heightIn(min = 58.dp).widthIn(max = vw.dp).clip(sk.r(20f)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (t != null && Alarma.acked) {
                        HCover(Inicio.cover(t), sk, Modifier.size(46.dp).clip(sk.r(10f)), 16.8f)
                        Column { T(t.title, 15f * sk.tk, p.fg, weight = FontWeight.ExtraBold, maxLines = 1); T(t.artist, 14.4f * sk.tk, p.muted, maxLines = 1) }
                    } else T(if (Alarma.noMusic) "No he podido poner tu música: suena la alarma del móvil." else if (Alarma.acked) "" else "Poniendo tu música…", 13.44f * sk.tk, p.muted)
                }
                Column(Modifier.padding(top = 16.dp).widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HBtn("Posponer ${Alarma.cfg().snooze} min", sk, Modifier.fillMaxWidth(), kind = 3) { Alarma.end("snooze") }
                    HBtn("Ya me levanto", sk, Modifier.fillMaxWidth(), primary = true, kind = 2) { Alarma.end("up") }
                    HBtn("Apagar la música", sk, Modifier.fillMaxWidth(), kind = 1, ghost = true) { Alarma.end("off") }
                }
            }
        }
    }
}
