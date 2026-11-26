package com.example.ytplayer

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Media3 playback service: keeps music going in the background and gives you
 * notification, lock screen, headphone and Bluetooth controls for free.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)   // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Items arrive from the UI without their file URI, so rebuild it from the id (the file path)
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(
                        mediaItems.map { it.buildUpon().setUri(Uri.fromFile(File(it.mediaId))).build() }
                            .toMutableList()
                    )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** The UI's connection to the playback service, exposed as Compose state. */
object PlayerConn {
    var isPlaying by mutableStateOf(false)
        private set
    var current by mutableStateOf<MediaItem?>(null)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    fun connect(ctx: Context) {
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            runCatching { f.get() }.getOrNull()?.let { c ->
                controller = c
                c.addListener(listener)
                sync(c)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
    }

    private fun sync(p: Player) {
        isPlaying = p.isPlaying
        current = p.currentMediaItem
        duration = p.duration.takeIf { it > 0 } ?: 0L
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
    }

    val position: Long get() = controller?.currentPosition ?: 0L

    fun playTracks(tracks: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(tracks.map { it.toMediaItem() }, start.coerceIn(0, tracks.size - 1), 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    /** Remove a file from the play queue (before deleting it). */
    fun removeFromQueue(path: String) {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId == path) c.removeMediaItem(i)
        }
    }
}
