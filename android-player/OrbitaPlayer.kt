package __PACKAGE__

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 4.2 · El reproductor de Órbita tal como lo ve Android (Media3): el aviso, la pantalla de bloqueo, los auriculares,
 * el reloj, el coche… No reproduce nada él: cuenta lo que hace el motor de Órbita (NovaCore) y le pasa los botones.
 * Todo en el hilo principal (Media3 lo exige).
 */
@OptIn(markerClass = [UnstableApi::class])
class OrbitaPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    companion object {
        /** Lo que se enseña de la cola: unas pocas antes (para «anterior») y unas cuantas después. */
        const val BEFORE = 3
        const val AFTER = 20
        /** El elemento que pide Android para volver a poner la música con Órbita cerrada. */
        const val RESUME_ID = "orbita:reanudar"

        private val BASE: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_MEDIA_ITEM
        ).build()
        private val SEEKS: Player.Commands = Player.Commands.Builder().addAll(BASE).addAll(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM
        ).build()
    }

    /** El motor ha cambiado algo: Media3 vuelve a preguntar (getState). En el hilo principal. */
    fun refresh() = invalidateState()

    /** Dónde empieza la parte de la cola que se enseña (para traducir «la n.º 2 de la lista» a la cola de Órbita). */
    private var offset = 0

    override fun getState(): State {
        val s = NovaCore.snapshot()
        val b = State.Builder()
            .setPlaybackState(if (s.kind == NovaCore.Snap.EMPTY) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setMaxSeekToPreviousPositionMs(3000)
        when (s.kind) {
            NovaCore.Snap.ENGINE -> {
                val from = maxOf(0, s.index - BEFORE)
                val to = minOf(s.items.size, s.index + AFTER + 1)
                offset = from
                val list = ArrayList<MediaItemData>(to - from)
                for (k in from until to) {
                    val it = s.items[k]
                    list.add(itemData(it.uid, it.title, it.artist, it.album, it.dur, if (k == s.index) s.art else null, "q:" + it.uid))
                }
                b.setAvailableCommands(SEEKS)
                    .setPlaylist(list)
                    .setCurrentMediaItemIndex(s.index - from)
                    .setRepeatMode(if (s.repeat == NovaQueue.REP_ONE) Player.REPEAT_MODE_ONE else if (s.repeat == NovaQueue.REP_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF)
                val slot = s.slot
                b.setContentPositionMs(PositionSupplier { NovaCore.positionMs(slot) })
            }
            NovaCore.Snap.EXTERNAL -> {
                offset = 0
                b.setAvailableCommands(SEEKS)
                    .setPlaylist(listOf(itemData(s.extUid, s.title, s.artist, s.album, s.dur, s.art, "ext:" + s.extUid)))
                    .setCurrentMediaItemIndex(0)
                b.setContentPositionMs(if (s.playing) PositionSupplier.getExtrapolating(s.posMs, 1f) else PositionSupplier.getConstant(s.posMs))
            }
            else -> {
                offset = 0
                b.setAvailableCommands(BASE)
            }
        }
        if (s.jumpMs >= 0) b.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, s.jumpMs)
        return b.build()
    }

    private fun itemData(uid: Long, title: String, artist: String, album: String, dur: Double, art: ByteArray?, mediaId: String): MediaItemData {
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.ifEmpty { null })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (art != null) md.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        val mi = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(md.build()).build()
        return MediaItemData.Builder(uid)
            .setMediaItem(mi)
            .setDurationUs(if (dur > 0) (dur * 1_000_000).toLong() else C.TIME_UNSET)
            .setIsSeekable(dur > 0)
            .build()
    }

    // ---------------------------------------------------------------- los botones (aviso, auriculares, reloj, coche…)
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        NovaCore.button(if (playWhenReady) "play" else "pause", 0.0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> NovaCore.button("next", 0.0)
            // «anterior»: dentro de los 3 primeros segundos, la anterior; si no, al principio de esta (lo decide el motor)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> NovaCore.button("prev", 0.0)
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> NovaCore.button("seek", maxOf(0L, positionMs) / 1000.0)
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                if (mediaItemIndex != C.INDEX_UNSET) NovaCore.seekToItem(offset + mediaItemIndex, if (positionMs == C.TIME_UNSET) 0.0 else positionMs / 1000.0)
            }
            else -> { }
        }
        return Futures.immediateVoidFuture()
    }

    /** Solo se usa para volver a poner la música guardada (con Órbita cerrada); lo demás no se acepta desde fuera. */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val first = mediaItems.firstOrNull()
        if (first != null && first.mediaId == RESUME_ID) NovaCore.resumeSaved()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        NovaCore.button("pause", 0.0)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
