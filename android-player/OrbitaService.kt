package __PACKAGE__

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 4.2 · El servicio de música de Órbita (Media3): el aviso con la canción y sus botones, la pantalla de bloqueo, los
 * auriculares, el reloj… Media3 lo pone en primer plano mientras suena (Android no lo para con la pantalla apagada ni
 * al cerrar Órbita desde las recientes) y lo quita cuando la música para.
 */
@OptIn(markerClass = [UnstableApi::class])
class OrbitaService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        NovaCore.init(this)
        // 4.2 b2 · si Media3 no puede crear la sesión, la app no se cierra: la música sigue (sin aviso) y el
        // diagnóstico dice por qué
        try {
            val player = OrbitaPlayer()
            val b = MediaSession.Builder(this, player).setId("orbita").setCallback(Callback())
            open(this)?.let { b.setSessionActivity(it) }
            session = b.build()
            NovaCore.player = player
            NovaCore.serviceError = null
        } catch (t: Throwable) {
            session = null
            NovaCore.serviceError = t.javaClass.simpleName + ": " + t.message
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        NovaCore.player = null
        session?.let {
            try { it.player.release() } catch (ignored: Throwable) { }
            it.release()
        }
        session = null
        super.onDestroy()
    }

    /** Abrir Órbita al tocar el aviso. */
    private fun open(c: Context): PendingIntent? = try {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (i == null) null else PendingIntent.getActivity(c, 7402, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    } catch (t: Throwable) { null }

    private inner class Callback : MediaSession.Callback {
        /**
         * «Play» sin nada puesto (los auriculares, los controles de Android, con Órbita cerrada): se pone lo último que
         * sonó con el motor, donde se quedó. Si la app está abierta, se entera y lo recoge.
         */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val s = NovaCore.saved(this@OrbitaService) ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nada guardado"))
            val it = s.items[s.index]
            val md = MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).setAlbumTitle(it.album.ifEmpty { null })
                .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
            val mi = MediaItem.Builder().setMediaId(OrbitaPlayer.RESUME_ID).setMediaMetadata(md).build()
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(mi), 0, (s.pos * 1000).toLong()))
        }
    }
}

/**
 * Los botones de los auriculares con Órbita cerrada. Solo arranca el servicio si hay algo que poner (si no, Android lo
 * cerraría con un error por no sonar nada).
 */
@OptIn(markerClass = [UnstableApi::class])
class OrbitaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean = NovaCore.hasSaved(context)
}
