package __PACKAGE__

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Fase 4, parte 2 · Lo que suena de internet (la radio y las muestras de 30 segundos), fuera del motor de las canciones
 * (como en la web, donde lo tocaba su propio reproductor). Con el reproductor de Android, que entiende las emisoras.
 * El aviso del móvil (y la pantalla de bloqueo) lo enseña [NovaCore] como «de fuera».
 */
object Calle {
    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var gen = 0
    var id = ""; private set
    @Volatile var playing = false; private set
    @Volatile var ready = false; private set
    private var vol = 1f

    /** Suena «url» (de la canción «t»); «done» dice si ha empezado (en el hilo principal). */
    fun play(t: Track, url: String, autoplay: Boolean, at: Double = 0.0, done: (Boolean) -> Unit) {
        stop()
        val a = Nativo.act ?: run { done(false); return }
        val my = ++gen
        id = t.id; ready = false; playing = false
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            try { p.setWakeMode(a.applicationContext, PowerManager.PARTIAL_WAKE_LOCK) } catch (ignored: Throwable) { }
            p.setDataSource(a.applicationContext, android.net.Uri.parse(url), mapOf("User-Agent" to Online.UA))
            p.setOnPreparedListener {
                if (my != gen) return@setOnPreparedListener
                ready = true
                try { if (at > 0 && p.duration > 0) p.seekTo((at * 1000).toInt()) } catch (ignored: Throwable) { }
                applyVol()
                if (autoplay) { try { NovaCore.requestFocus() } catch (ignored: Throwable) { }; p.start(); playing = true }
                main.post { done(true) }
            }
            p.setOnCompletionListener { if (my == gen) { playing = false; main.post { Player.netEnded(id) } } }
            p.setOnErrorListener { _, what, extra ->
                if (my == gen) {
                    Nativo.note("de internet: no suena ($what/$extra)")
                    val wasReady = ready
                    playing = false; ready = false
                    main.post { if (wasReady) Player.netEnded(id, failed = true) else done(false) }
                }
                true
            }
            p.prepareAsync()
        } catch (t: Throwable) {
            Nativo.note("de internet: ${t.message}")
            main.post { done(false) }
        }
    }

    fun pause() { try { mp?.takeIf { ready }?.pause() } catch (ignored: Throwable) { }; playing = false }
    fun resume(): Boolean {
        val p = mp ?: return false
        if (!ready) return false
        try { NovaCore.requestFocus() } catch (ignored: Throwable) { }
        return try { applyVol(); p.start(); playing = true; true } catch (t: Throwable) { false }
    }
    fun stop() {
        gen++
        val p = mp; mp = null
        playing = false; ready = false; id = ""
        if (p != null) try { p.reset(); p.release() } catch (ignored: Throwable) { }
    }
    fun seek(sec: Double) { try { val p = mp ?: return; if (ready && p.duration > 0) p.seekTo((sec * 1000).toInt()) } catch (ignored: Throwable) { } }
    fun pos(): Double = try { if (ready) (mp?.currentPosition ?: 0) / 1000.0 else 0.0 } catch (t: Throwable) { 0.0 }
    /** (la radio en directo no tiene duración: 0) */
    fun dur(): Double = try { val d = if (ready) mp?.duration ?: 0 else 0; if (d > 0) d / 1000.0 else 0.0 } catch (t: Throwable) { 0.0 }
    fun has(): Boolean = mp != null

    /** El volumen (lo que baja mientras habla Nova y lo que sube el despertador). */
    fun setVolume(v: Float) { vol = v.coerceIn(0f, 1f); applyVol() }
    private fun applyVol() { try { mp?.setVolume(vol, vol) } catch (ignored: Throwable) { } }
}
