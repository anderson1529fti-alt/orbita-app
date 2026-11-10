package __PACKAGE__

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Size
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 4.2 · El reproductor de Órbita, en Kotlin. Vive mientras viva la app en Android (no la pantalla): el motor, la cola,
 * el foco del sonido, los auriculares, el temporizador para dormir, las escuchas y el widget. La parte web de la app
 * (NovaAudioPlugin) y Android (OrbitaService, con Media3) le hablan a él. Así la música sigue aunque cierres Órbita
 * desde las apps recientes, y al volver a abrirla la app recoge lo que suena.
 * Es lo que hacían NovaAudioPlugin.java y NovaSession.java hasta la 4.1, con las mismas reglas.
 */
object NovaCore : NovaAudioEngine.Listener {

    /** La parte web de la app, si está abierta: recibe los avisos del motor. */
    interface Sink {
        fun audio(o: JSONObject)
        fun spectrum(b64: String)
    }

    /** Lo que Media3 necesita saber para el aviso, de una vez. */
    class Snap(
        val kind: Int, val items: List<PItem>, val index: Int, val slot: Int, val repeat: Int, val playing: Boolean,
        val title: String, val artist: String, val album: String, val dur: Double, val posMs: Long, val art: ByteArray?,
        val extUid: Long, val jumpMs: Long
    ) {
        companion object { const val EMPTY = 0; const val ENGINE = 1; const val EXTERNAL = 2 }
    }

    private const val STORE = "orbita-cola.json"
    private const val PREFS = "orbita_cola"

    @Volatile private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private val later: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()

    @Volatile var engine: NovaAudioEngine? = null; private set
    private var ticker: ScheduledExecutorService? = null
    val queue = NovaQueue()
    @Volatile var sink: Sink? = null
    @Volatile var player: OrbitaPlayer? = null
    /** 4.2 b2 · por qué no arrancó el servicio de música (para el diagnóstico). */
    @Volatile var serviceError: String? = null
    private const val CRASH = "orbita-cierre.txt"

    /** 4.2 b2 · Si la app se cierra por un error, queda apuntado (qué y dónde) y sale en «Copiar diagnóstico». */
    private fun watchCrashes(c: Context) {
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, e ->
                try {
                    val sb = StringBuilder()
                    sb.append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append(" · hilo ").append(th.name).append('\n')
                    var x: Throwable? = e
                    var depth = 0
                    while (x != null && depth < 3) {
                        sb.append(if (depth == 0) "" else "causa: ").append(x.toString()).append('\n')
                        for (el in x.stackTrace.take(if (depth == 0) 14 else 8)) sb.append("  ").append(el.toString()).append('\n')
                        x = x.cause; depth++
                    }
                    File(c.filesDir, CRASH).writeText(sb.toString().take(4000))
                } catch (ignored: Throwable) { }
                prev?.uncaughtException(th, e)
            }
        } catch (ignored: Throwable) { }
    }

    private fun lastCrash(): String? = try {
        val f = File(ctx().filesDir, CRASH)
        if (f.exists()) f.readText() else null
    } catch (t: Throwable) { null }

    // lo que suena fuera del motor (radio, YouTube…): lo cuenta la app
    @Volatile private var external = false
    private var exTitle = ""
    private var exArtist = ""
    private var exAlbum = ""
    private var exArt: ByteArray? = null
    private var exPlaying = false
    private var exPos = 0.0
    private var exDur = 0.0
    private var exAt = 0L
    private var exUid = 0L

    // la portada de la que suena
    private var artFor: String? = null
    private var art: ByteArray? = null
    private var artBmp: Bitmap? = null
    private val artTried: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // un salto (mover la barra, otra canción) que Media3 aún no sabe
    private var jumpMs = -1L

    private var listens: NovaListenLog? = null
    @Volatile private var sleepAt = 0L
    @Volatile private var sleepFade = 30_000L

    private var am: AudioManager? = null
    private var focusReq: AudioFocusRequest? = null
    @Volatile private var hasFocus = false
    /* 3.9: otra app que suena. Se avisa a la app y, si no contesta en 900 ms (dormida o cerrada), el motor pausa solo
       con un fundido (1,5 s; 0,8 s si es algo corto, y entonces vuelve solo al acabar). */
    @Volatile private var focusPending = 0
    @Volatile private var focusSeq = 0
    @Volatile private var resumeOnGain = false
    private var wake: PowerManager.WakeLock? = null
    private var wakeAt = 0L
    private var noisy: BroadcastReceiver? = null
    private var btTick = 0
    private var saveTick = 0
    private var controller: ListenableFuture<MediaController>? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_GAIN) hasFocus = true
        else if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false
        val o = JSONObject()
        o.put("type", "focus"); o.put("value", change)
        o.put("t", System.currentTimeMillis())   // 3.9: la app, al despertar, no hace caso a los avisos atrasados
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            val id = ++focusSeq
            focusPending = id; o.put("id", id)
            val shortOne = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            // sin la app abierta no hay nadie que conteste: se pausa ya
            val wait = if (sink == null) 0L else 900L
            try {
                later.schedule({ if (focusPending == id) { focusPending = 0; fadePauseAll(if (shortOne) 800 else 1500, shortOne, "otra app") } }, wait, TimeUnit.MILLISECONDS)
            } catch (ignored: Throwable) { }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusPending = 0
            if (resumeOnGain) { resumeOnGain = false; o.put("resumed", resumeFadeIn(700)) }
        }
        emit(o)
    }

    // ---------------------------------------------------------------- arranque
    fun init(ctx: Context) {
        if (app != null) return
        synchronized(lock) {
            if (app != null) return
            val c = ctx.applicationContext
            app = c
            watchCrashes(c)
            try { listens = NovaListenLog(c.getSharedPreferences(NovaListenLog.PREFS, Context.MODE_PRIVATE)) } catch (ignored: Throwable) { }
            // el aviso de antes de la 4.2 (el nuevo es el de Media3)
            try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(7401) } catch (ignored: Throwable) { }
        }
    }

    private fun ctx(): Context = app ?: throw IllegalStateException("NovaCore sin iniciar")

    fun eng(): NovaAudioEngine {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val c = ctx()
            val e = NovaAudioEngine({ src -> NovaAudioIO.AndroidDecoder(c, src) }, NovaAudioIO.AndroidOutput(outputRate()), this)
            e.start()
            engine = e
            val tk = Executors.newSingleThreadScheduledExecutor()
            tk.scheduleAtFixedRate({ tick() }, 250, 250, TimeUnit.MILLISECONDS)
            ticker = tk
            registerNoisy()
            return e
        }
    }

    private fun audio(): AudioManager {
        am?.let { return it }
        val a = ctx().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am = a
        return a
    }

    fun outputRate(): Int = try {
        val v = audio().getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
        if (v in 8000..192000) v else 48000
    } catch (t: Throwable) { 48000 }

    /* 4.0b3 · ¿suena por Bluetooth? (se mira cada 2 s) */
    fun btOut(): Boolean {
        try {
            for (d in audio().getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val t = d.type
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 || t == 27) return true   // 26, 27: auriculares y altavoces BLE
            }
        } catch (ignored: Throwable) { }
        return false
    }

    // ---------------------------------------------------------------- avisos a la app (si está abierta)
    private fun emit(o: JSONObject) { try { sink?.audio(o) } catch (ignored: Throwable) { } }

    private fun ev(type: String): JSONObject { val o = JSONObject(); o.put("type", type); return o }

    // ---------------------------------------------------------------- cuatro veces por segundo
    private fun tick() {
        try {
            val e = engine ?: return
            var any = false
            for (i in 0 until NovaAudioEngine.SLOTS) {
                if (!e.playing(i)) continue
                any = true
                if (sink != null) {
                    val o = ev("pos")
                    o.put("slot", i)
                    o.put("pos", e.heardPosition(i))   // 4.0b3: lo que se oye (la barra, la letra y las luces van con el sonido)
                    o.put("dur", e.duration(i))
                    emit(o)
                }
            }
            keepAwake(any)
            countListen(e)
            sleepTick(e)
            if (++btTick % 8 == 0) e.setBluetooth(btOut())
            // 4.2 · por dónde va, cada 10 s (para volver a ponerla si Android cierra Órbita)
            if (any && ++saveTick % 40 == 0) savePos()
        } catch (ignored: Throwable) {
        }
    }

    /** 3.6 · Apunta lo que suena en el plato del aviso (la canción de ahora, cuánto lleva). */
    private fun countListen(e: NovaAudioEngine) {
        val l = listens ?: return
        if (external) { l.tick(-1, null, false, 0.0, 0.0, null, null); return }
        val sl = queue.slot
        val src = e.source(sl)
        val it = queue.now()
        val mine = it != null && src != null && src == it.src
        l.tick(sl, src, e.playing(sl), e.position(sl), e.duration(sl), if (mine) it!!.title else null, if (mine) it!!.artist else null)
    }

    /** 3.6 · El temporizador para dormir: baja la música poco a poco y la para, aunque la app esté dormida. */
    private fun sleepTick(e: NovaAudioEngine) {
        val at = sleepAt
        if (at <= 0) return
        val left = at - System.currentTimeMillis()
        if (left > 0) {
            val fade = maxOf(1000L, sleepFade)
            e.setSleepGain(if (left < fade) maxOf(0.02f, left / fade.toFloat()) else 1f)
            return
        }
        sleepAt = 0
        var was = false
        for (i in 0 until NovaAudioEngine.SLOTS) if (e.playing(i)) { e.pause(i); was = true }
        e.setSleepGain(1f)
        if (!external && was) state(queue.slot, false)
        val o = ev("sleep"); o.put("t", System.currentTimeMillis()); o.put("paused", was)
        emit(o)
    }

    @Synchronized private fun keepAwake(on: Boolean) {
        try {
            var w = wake
            if (w == null) {
                val pm = ctx().getSystemService(Context.POWER_SERVICE) as PowerManager
                w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orbita:musica")
                w.setReferenceCounted(false)
                wake = w
            }
            val now = System.currentTimeMillis()
            if (on && (!w!!.isHeld || now - wakeAt > 60_000L)) { w!!.acquire(30 * 60 * 1000L); wakeAt = now }
            else if (!on && w!!.isHeld) w.release()
        } catch (ignored: Throwable) {
        }
    }

    /** Si se desconectan los auriculares, la música se para (y no sale de golpe por el altavoz). */
    private fun registerNoisy() {
        if (noisy != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY != i.action) return
                // 3.9: el motor pausa él solo (fundido corto: al cuadrado, en 150 ms ya ha bajado a la mitad)
                val e = engine
                val mine = e != null && e.anyPlaying()
                if (mine) { resumeOnGain = false; focusPending = 0; fadePauseAll(500, false, "auriculares") }
                val o = ev("noisy"); o.put("handled", mine); o.put("t", System.currentTimeMillis())
                emit(o)
            }
        }
        try {
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) ctx().registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx().registerReceiver(r, f)
            noisy = r
        } catch (ignored: Throwable) {
        }
    }

    fun requestFocus() {
        if (hasFocus) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                var fr = focusReq
                if (fr == null) {
                    val aa = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(aa).setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(false).build()
                    focusReq = fr
                }
                hasFocus = audio().requestAudioFocus(fr!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                hasFocus = audio().requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (ignored: Throwable) {
        }
    }

    /** La app pide el sonido otra vez (después de hablar Nova). */
    fun refocus(): Boolean { hasFocus = false; requestFocus(); return hasFocus }

    fun focusAck(id: Int) { if (id == 0 || id == focusPending) focusPending = 0 }

    /** 3.9 · Pausa todo lo que suene con un fundido (lo hace el motor: vale con la app dormida o cerrada). */
    private fun fadePauseAll(ms: Int, resumeLater: Boolean, why: String) {
        val e = engine ?: return
        if (!e.anyPlaying()) return
        resumeOnGain = resumeLater
        e.fadeTo(0f, ms) {
            for (i in 0 until NovaAudioEngine.SLOTS) if (e.playing(i)) { e.pause(i); state(i, false) }
            e.fadeSet(1f)
            val o = ev("autopause"); o.put("why", why)
            emit(o)
        }
    }

    /** 3.9 · Vuelve a sonar donde estaba, subiendo poco a poco (tras una interrupción corta). */
    private fun resumeFadeIn(ms: Int): Boolean {
        val e = engine ?: return false
        if (external || queue.isEmpty()) return false
        val sl = queue.slot
        if (e.source(sl) == null) return false
        if (e.playing(sl)) {
            // 4.0b2 · la otra app duró menos que el fundido: aún estaba bajando. Se cancela la pausa y vuelve a subir.
            if (!e.fadingOut()) return false
            e.fadeTo(1f, ms, null); state(sl, true)
            return true
        }
        e.fadeSet(0f); requestFocus(); e.play(sl); keepAwake(true); e.fadeTo(1f, ms, null); state(sl, true)
        return true
    }

    // ---------------------------------------------------------------- lo que pide la app
    fun load(slot: Int, src: String, at: Double, play: Boolean) {
        if (play) requestFocus()
        if (!external && queue.slot == slot) listens?.end("app")
        eng().load(slot, src, at, play)
        if (play) keepAwake(true)
    }

    fun play(slot: Int) {
        val e = eng()
        if (e.fadeLevel() < 1f) e.fadeSet(1f)   // 3.9: un fundido a medias no se lleva lo que pides
        resumeOnGain = false
        requestFocus()
        e.play(slot)
        keepAwake(true)
        if (slot == queue.slot) changed(false)
    }

    fun pause(slot: Int) {
        eng().pause(slot)
        if (slot == queue.slot) { changed(false); savePos() }
    }

    fun stop(slot: Int) {
        engine?.stop(slot)
        if (slot == queue.slot) changed(false)
    }

    /** 3.5b3: la cola entera, desde la que suena, con lo del aviso y los fundidos. El motor la sigue solo. */
    fun setPlaylist(list: List<PItem>, index: Int, slot: Int, repeat: Int, cycle: Int) {
        synchronized(lock) {
            queue.set(list, index, slot, repeat, cycle)
            external = false
        }
        val e = eng()
        val cur = queue.now()
        e.setNorm(slot, 1f)
        if (cur != null) e.setSongGain(slot, cur.g)
        e.setItems(slot, queue.after(queue.index))
        changed(true)
        ensureService()
    }

    /** 3.5b3: lo que suena fuera del motor (radio, YouTube…), para el aviso. Sus botones los atiende la app. */
    fun setExternal(title: String, artist: String, album: String, artData: String?, playing: Boolean, pos: Double, dur: Double) {
        synchronized(lock) {
            val same = external && title == exTitle && artist == exArtist
            external = true
            if (!same) { exUid = System.nanoTime(); exArt = null }
            exTitle = title; exArtist = artist; exAlbum = album
            val a = dataUrlBytes(artData)
            if (a != null) exArt = a
            exPlaying = playing; exPos = pos; exDur = dur; exAt = System.currentTimeMillis()
        }
        changed(false)
        ensureService()
    }

    /** La app ha terminado (ya no suena nada): fuera el aviso. */
    fun sessionEnd() {
        synchronized(lock) { queue.clear(); external = false }
        changed(false)
    }

    fun setSleep(at: Long, fade: Long?) {
        if (fade != null) sleepFade = maxOf(1000L, fade)
        val e = eng()
        sleepAt = if (at > 0) at else 0
        if (sleepAt == 0L) e.setSleepGain(1f)
    }

    fun takeListens(after: Long): JSONObject {
        val r = JSONObject()
        val l = listens
        if (l == null) { r.put("items", JSONArray()); r.put("seq", 0); return r }
        r.put("items", l.take(after))
        r.put("seq", l.seq())
        return r
    }

    fun status(): JSONObject {
        val r = JSONObject()
        r.put("available", true)
        r.put("rate", outputRate())
        r.put("v", 3)   // 4.2: el reproductor de Kotlin
        val e = engine   // 4.0b3 · para el diagnóstico: salida y lo que tarda en oírse
        r.put("bt", btOut())
        r.put("lat", if (e != null) Math.round(e.latency() * 1000) else -1)
        r.put("player", "kotlin")
        r.put("service", player != null)
        serviceError?.let { r.put("serviceError", it) }
        lastCrash()?.let { r.put("crash", it) }
        return r
    }

    fun position(slot: Int): JSONObject {
        val r = JSONObject()
        val e = engine
        r.put("pos", e?.heardPosition(slot) ?: 0.0)
        r.put("dur", e?.duration(slot) ?: 0.0)
        r.put("lat", e?.latency() ?: 0.0)
        r.put("playing", e != null && e.playing(slot))
        return r
    }

    /**
     * 4.2 · La app se acaba de abrir: ¿sonaba algo? (la música puede seguir con Órbita cerrada). Lo que suena, en qué
     * plato, por dónde va y la cola, para que la app lo recoja tal cual en vez de empezar de cero.
     */
    fun attach(): JSONObject {
        val r = JSONObject()
        val e = engine
        synchronized(lock) {
            val it = queue.now()
            val sl = queue.slot
            val src = e?.source(sl)
            val active = !external && it != null && e != null && src != null && src == it.src
            r.put("active", active)
            r.put("external", external)
            if (!active) return r
            r.put("playing", e!!.playing(sl))
            r.put("slot", sl)
            r.put("index", queue.index)
            r.put("src", src)
            r.put("pos", e.heardPosition(sl))
            r.put("dur", e.duration(sl))
            r.put("repeat", NovaQueue.repeatName(queue.repeat))
            val a = JSONArray()
            for (p in queue.items) { val o = JSONObject(); o.put("src", p.src); o.put("id", p.id); a.put(o) }
            r.put("items", a)
        }
        return r
    }

    /**
     * Un botón del aviso, de los auriculares, del reloj o del widget. Si lo que suena es del motor, se hace aquí mismo
     * (al momento); si no (radio, YouTube…), se le pasa a la app.
     */
    fun button(a: String, arg: Double) {
        val e = engine
        val o = ev("button"); o.put("action", a); o.put("t", System.currentTimeMillis())
        if (e == null || external || queue.now() == null || e.source(queue.slot) == null) {
            o.put("forward", true)
            if (a == "seek") o.put("pos", arg)
            // radio, YouTube…: lo que se ve en el aviso cambia ya (la app lo confirma enseguida)
            if (external && (a == "play" || a == "pause")) synchronized(lock) { exPos = exNow(); exAt = System.currentTimeMillis(); exPlaying = a == "play" }
            if (external) changed(false)
            emit(o)
            return
        }
        val sl = queue.slot
        try {
            when (a) {
                "play" -> { resumeOnGain = false; e.fadeSet(1f); requestFocus(); e.play(sl); keepAwake(true); state(sl, true) }
                "pause" -> { e.pause(sl); state(sl, false); savePos() }
                "playpause" -> if (e.playing(sl)) { e.pause(sl); state(sl, false); savePos() } else { resumeOnGain = false; e.fadeSet(1f); requestFocus(); e.play(sl); keepAwake(true); state(sl, true) }
                "seek" -> { e.seek(sl, arg); markJump(arg) }
                "next" -> if (queue.item(queue.index + 1) == null) o.put("forward", true) else jump(sl, queue.index + 1)
                "prev" -> if (e.position(sl) > 3 || queue.item(queue.index - 1) == null) { e.seek(sl, 0.0); markJump(0.0) } else jump(sl, queue.index - 1)
                else -> o.put("forward", true)
            }
        } catch (t: Throwable) {
            o.put("forward", true)
        }
        emit(o)
    }

    /** Media3: otra canción de la lista (desde la cola del coche o del reloj). */
    fun seekToItem(idx: Int, pos: Double) {
        val e = engine ?: return
        if (external || queue.item(idx) == null) return
        if (idx == queue.index) { e.seek(queue.slot, pos); markJump(pos); return }
        jump(queue.slot, idx)
        if (pos > 0) e.seek(queue.slot, pos)
    }

    /** 3.6 · Los botones del widget, con la música del motor: los hace él al momento (la app puede ir dormida). */
    fun widgetButton(action: String): Boolean {
        val e = engine ?: return false
        if (external || queue.now() == null || e.source(queue.slot) == null) return false
        button(if (action == "toggle") "playpause" else action, 0.0)
        return true
    }

    private fun state(slot: Int, playing: Boolean) {
        if (slot == queue.slot && !external) changed(false)
        val e = engine ?: return
        val o = ev("state"); o.put("slot", slot); o.put("playing", playing)
        o.put("pos", e.position(slot)); o.put("src", e.source(slot))
        emit(o)
    }

    /** Siguiente o anterior desde un botón: otra canción de la cola en este mismo plato, ya. */
    private fun jump(slot: Int, idx: Int) {
        val e = engine ?: return
        val it = queue.item(idx) ?: return
        listens?.end("boton")
        val other = 1 - slot
        if (e.playing(other)) e.pause(other)
        requestFocus()
        e.setNorm(slot, 1f)
        e.setSongGain(slot, it.g)
        e.load(slot, it.src, it.st, true)
        synchronized(lock) {
            queue.index = idx
            queue.extend()
        }
        e.setItems(slot, queue.after(queue.index))
        keepAwake(true)
        markJump(it.st)
        changed(true)
        val o = ev("jump"); o.put("slot", slot); o.put("src", it.src); o.put("pos", it.st); o.put("dur", it.dur)
        emit(o)
    }

    private fun markJump(pos: Double) { synchronized(lock) { jumpMs = (maxOf(0.0, pos) * 1000).toLong() } }

    // ---------------------------------------------------------------- lo que cuenta el motor (hilo del sonido)
    override fun onEvent(type: String, slot: Int, src: String?, pos: Double, dur: Double, msg: String?) {
        if (slot == queue.slot && !external) {
            try {
                when (type) {
                    "gapless" -> {
                        val was = queue.now()?.src
                        if (queue.onSource(slot, src)) {
                            synchronized(lock) { jumpMs = (maxOf(0.0, pos) * 1000).toLong() }
                            queue.now()?.let { if (dur > 0 && it.dur <= 0) it.dur = dur }
                        }
                        ticker?.execute { afterGapless(slot, src, was) }
                        changed(true)
                    }
                    "seeked" -> { markJump(pos); changed(false) }
                    "loaded", "ended", "error" -> changed(false)
                }
            } catch (ignored: Throwable) {
            }
        }
        if (sink == null) return
        val o = ev(type)
        o.put("slot", slot); o.put("src", src); o.put("pos", pos); o.put("dur", dur)
        if (msg != null) o.put("msg", msg)
        emit(o)
    }

    /** Fuera del hilo del sonido: otra vuelta de la lista (repetir) y, si es la misma canción otra vez, una escucha nueva. */
    private fun afterGapless(slot: Int, src: String?, was: String?) {
        try {
            val e = engine ?: return
            if (slot != queue.slot) return
            if (src != null && src == was) listens?.restart("auto")
            val more = synchronized(lock) { queue.extend() }
            if (queue.repeat == NovaQueue.REP_ONE || more) e.setItems(slot, queue.after(queue.index))
        } catch (ignored: Throwable) {
        }
    }

    override fun onSpectrum(bins: ByteArray) {
        val s = sink ?: return
        try { s.spectrum(Base64.encodeToString(bins, Base64.NO_WRAP)) } catch (ignored: Throwable) { }
    }

    // ---------------------------------------------------------------- lo que ve Android
    fun positionMs(slot: Int): Long {
        val e = engine ?: return 0
        return (maxOf(0.0, e.heardPosition(slot)) * 1000).toLong()
    }

    private fun exNow(): Double = if (exPlaying) exPos + (System.currentTimeMillis() - exAt) / 1000.0 else exPos

    fun snapshot(): Snap {
        val e = engine
        synchronized(lock) {
            val jm = jumpMs
            jumpMs = -1
            if (external) {
                return Snap(Snap.EXTERNAL, emptyList(), 0, 0, NovaQueue.REP_OFF, exPlaying, exTitle, exArtist, exAlbum, exDur,
                    (maxOf(0.0, exNow()) * 1000).toLong(), exArt, exUid, -1)
            }
            val it = queue.now()
            if (it == null || e == null) return Snap(Snap.EMPTY, emptyList(), -1, 0, NovaQueue.REP_OFF, false, "", "", "", 0.0, 0, null, 0, -1)
            val sl = queue.slot
            val d = if (it.dur > 0) it.dur else e.duration(sl)
            return Snap(Snap.ENGINE, ArrayList(queue.items), queue.index, sl, queue.repeat, e.playing(sl), it.title, it.artist, it.album, d,
                positionMs(sl), if (it.src == artFor) art else null, 0, jm)
        }
    }

    @Volatile private var invalidatePending = false

    /**
     * Algo ha cambiado: Media3 (el aviso) se pone al día, la portada se carga si es otra canción, el widget y lo
     * guardado para volver a ponerla. meta: ha cambiado la canción o la cola.
     */
    fun changed(meta: Boolean) {
        if (!invalidatePending) {
            invalidatePending = true
            main.post {
                invalidatePending = false
                try { player?.refresh() } catch (ignored: Throwable) { }
            }
        }
        if (meta) { loadArtSoon(); saveSoon() }
        widgetSoon()
    }

    // ---------------------------------------------------------------- portadas
    private fun loadArtSoon() {
        val it = queue.now() ?: return
        val src = it.src
        synchronized(lock) {
            if (src == artFor) return
            artFor = src; art = null; artBmp = null
        }
        bg.execute {
            val b = loadArt(ctx(), src)
            val bytes = b?.let { jpeg(it) }
            artTried.add(src)
            if (artTried.size > 200) artTried.clear()
            synchronized(lock) { if (src == artFor) { art = bytes; artBmp = b } }
            changed(false)
        }
    }

    private fun jpeg(b: Bitmap): ByteArray? = try {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 88, o)
        o.toByteArray()
    } catch (t: Throwable) { null }

    fun loadArt(ctx: Context, src: String): Bitmap? {
        try {
            val u = Uri.parse(src)
            if (Build.VERSION.SDK_INT >= 29 && "content" == u.scheme) {
                try { return ctx.contentResolver.loadThumbnail(u, Size(512, 512), null) } catch (ignored: Throwable) { }
            }
            val r = MediaMetadataRetriever()
            try {
                if (src.startsWith("content:")) r.setDataSource(ctx, u)
                else r.setDataSource(if (src.startsWith("file://")) u.path else src)
                val pic = r.embeddedPicture ?: return null
                var o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2
                o = BitmapFactory.Options()
                o.inSampleSize = s
                return BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
            } finally {
                try { r.release() } catch (ignored: Throwable) { }
            }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun dataUrlBytes(u: String?): ByteArray? {
        try {
            if (u == null) return null
            val i = u.indexOf(";base64,")
            if (i < 0) return null
            return Base64.decode(u.substring(i + 8), Base64.DEFAULT)
        } catch (t: Throwable) {
            return null
        }
    }

    // ---------------------------------------------------------------- el widget (3.6)
    private var wSig: String? = null
    private var wArtFor: String? = null

    /**
     * El widget de la pantalla de inicio cambia con la canción aunque la parte web de la app esté dormida o cerrada.
     * Lo de la radio o YouTube lo sigue poniendo la app (está despierta mientras suena).
     */
    private fun widgetSoon() {
        val it: PItem
        val a: Bitmap?
        val pl: Boolean
        synchronized(lock) {
            if (external) return
            it = queue.now() ?: return
            a = if (it.src == artFor) artBmp else null
            pl = engine?.playing(queue.slot) ?: false
        }
        val t = it.title
        val ar = it.artist
        if (t.isEmpty()) return
        val src = it.src
        // la portada se escribe cuando ya está cargada (o se sabe que no tiene)
        val artReady = src == artFor && (a != null || artTried.contains(src))
        val sig = t + "\n" + ar + "\n" + pl + "\n" + (if (artReady) src else "")
        if (sig == wSig) return
        wSig = sig
        bg.execute {
            try {
                val c = ctx()
                val ed = c.getSharedPreferences("orbita_widget", Context.MODE_PRIVATE).edit()
                ed.putString("title", t); ed.putString("artist", ar); ed.putBoolean("playing", pl)
                ed.apply()
                if (artReady && src != wArtFor) {
                    wArtFor = src
                    val f = File(c.filesDir, "widget_art.jpg")
                    val r = if (a != null) rounded(a, 144, 22f) else null
                    if (r != null) FileOutputStream(f).use { r.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    else if (f.exists()) f.delete()
                }
                Class.forName(c.packageName + ".OrbitaWidget").getMethod("refresh", Context::class.java).invoke(null, c)
            } catch (ignored: Throwable) {
                // sin widget (la versión segura de la app no lo tiene)
            }
        }
    }

    /** La portada como la pone la app en el widget: cuadrada, recortada al centro, con las esquinas redondeadas. */
    private fun rounded(src: Bitmap, size: Int, radius: Float): Bitmap? = try {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val sh = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sc = maxOf(size / src.width.toFloat(), size / src.height.toFloat())
        val m = Matrix()
        m.setScale(sc, sc)
        m.postTranslate((size - src.width * sc) / 2f, (size - src.height * sc) / 2f)
        sh.setLocalMatrix(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = sh
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, p)
        out
    } catch (t: Throwable) { null }

    // ---------------------------------------------------------------- guardar la cola (4.2)
    @Volatile private var savePending = false

    private fun saveSoon() {
        if (savePending) return
        savePending = true
        try {
            later.schedule({ savePending = false; saveNow() }, 1500, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) { savePending = false }
    }

    private fun saveNow() {
        try {
            val c = app ?: return
            val o = synchronized(lock) { if (queue.isEmpty() || external) null else queue.toJson(curPos()) } ?: return
            val f = File(c.filesDir, STORE)
            val tmp = File(c.filesDir, "$STORE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("index", queue.index).putFloat("pos", curPos().toFloat()).apply()
        } catch (ignored: Throwable) {
        }
    }

    private fun curPos(): Double {
        val e = engine ?: return 0.0
        return maxOf(0.0, e.position(queue.slot))
    }

    /** Por dónde va (cada 10 s sonando y al pausar): solo el número, sin escribir la cola entera. */
    private fun savePos() {
        val c = app ?: return
        if (external || queue.isEmpty()) return
        try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("index", queue.index).putFloat("pos", curPos().toFloat()).apply() } catch (ignored: Throwable) { }
    }

    /** Lo guardado (la última cola que sonó con el motor), o null. */
    fun saved(ctx: Context): NovaQueue.Saved? {
        try {
            val f = File(ctx.filesDir, STORE)
            if (!f.exists()) return null
            val s = NovaQueue.parse(JSONObject(f.readText())) ?: return null
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val idx = sp.getInt("index", s.index)
            val pos = sp.getFloat("pos", s.pos.toFloat()).toDouble()
            if (idx < 0 || idx >= s.items.size) return s
            return NovaQueue.Saved(s.items, idx, s.repeat, s.cycle, if (pos.isNaN() || pos < 0) 0.0 else pos)
        } catch (t: Throwable) {
            return null
        }
    }

    fun hasSaved(ctx: Context): Boolean = try { File(ctx.applicationContext.filesDir, STORE).length() > 20 } catch (t: Throwable) { false }

    /**
     * 4.2 · Con Órbita cerrada, el «play» de los auriculares o de los controles de Android vuelve a poner lo último que
     * sonó, donde se quedó (sin abrir la app). Queda en pausa: Media3 le da al «play» justo después.
     */
    fun resumeSaved(): Boolean {
        val s = saved(ctx()) ?: return false
        if (!queue.isEmpty() && engine?.source(queue.slot) != null) return true   // ya hay algo puesto
        val sl = 0
        synchronized(lock) {
            queue.set(s.items, s.index, sl, s.repeat, s.cycle)
            external = false
        }
        val e = eng()
        val cur = queue.now() ?: return false
        e.setNorm(sl, 1f)
        e.setSongGain(sl, cur.g)
        e.load(sl, cur.src, s.pos, false)
        e.setItems(sl, queue.after(queue.index))
        markJump(s.pos)
        changed(true)
        emit(ev("resumed"))   // la app, si está abierta, lo recoge (como al abrirla)
        return true
    }

    // ---------------------------------------------------------------- el servicio de Android (Media3)
    /**
     * Que exista el servicio con el reproductor de Android: se conecta a él (como hace cualquier app de música con su
     * propio servicio). Media3 lo pone en primer plano cuando suena y lo deja cuando para.
     */
    fun ensureService() {
        main.post {
            if (controller != null) return@post
            try {
                val c = ctx()
                val token = SessionToken(c, ComponentName(c, OrbitaService::class.java))
                val f = MediaController.Builder(c, token).buildAsync()
                controller = f
                // si no se pudo conectar, se vuelve a intentar la próxima vez
                f.addListener({ try { f.get() } catch (t: Throwable) { if (controller === f) controller = null } }, { r -> main.post(r) })
            } catch (ignored: Throwable) {
                controller = null
            }
        }
    }

    /** La app se cierra (la parte web): se suelta el servicio. Si está sonando, sigue (Media3 lo tiene en primer plano). */
    fun appGone() {
        sink = null
        synchronized(lock) {
            // la radio o un vídeo suenan dentro de la parte web: sin ella ya no suenan
            if (external) { external = false; queue.clear() }
        }
        changed(false)
        savePos()
        main.post {
            val f = controller ?: return@post
            controller = null
            try { MediaController.releaseFuture(f) } catch (ignored: Throwable) { }
        }
    }
}
