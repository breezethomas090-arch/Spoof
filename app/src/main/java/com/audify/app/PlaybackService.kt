package com.audify.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Turns "ytid:<id>" URIs into real YouTube audio URLs, just before ExoPlayer loads them. */
object Streams {
    private const val TTL_MS = 4 * 60 * 60 * 1000L
    private data class Entry(val url: String, val userAgent: String?, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun clear() = cache.clear()

    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Resolve upcoming songs in the background so they start instantly. */
    fun prefetch(ctx: Context, id: String) {
        val now = System.currentTimeMillis()
        if (cache[id]?.let { now - it.at < TTL_MS } == true || !pending.add(id)) return
        prefetcher.execute {
            try {
                val (url, ua) = Downloader.streamInfo(ctx, id)
                cache[id] = Entry(url, ua, System.currentTimeMillis())
            } catch (_: Exception) {
            } finally {
                pending.remove(id)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun resolve(ctx: Context, spec: DataSpec): DataSpec {
        if (spec.uri.scheme != "ytid") return spec
        val id = spec.uri.schemeSpecificPart
        val now = System.currentTimeMillis()
        val entry = cache[id]?.takeIf { now - it.at < TTL_MS }
            ?: try {
                Downloader.streamInfo(ctx, id).let { (url, ua) -> Entry(url, ua, now) }.also { cache[id] = it }
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Exception) {
                throw java.io.IOException(shortError(e), e)
            }
        val headers = HashMap(spec.httpRequestHeaders)
        entry.userAgent?.let { headers["User-Agent"] = it }
        return spec.buildUpon().setUri(Uri.parse(entry.url)).setHttpRequestHeaders(headers).build()
    }
}

/**
 * Media3 playback service: plays downloaded files and streams, keeps going in the
 * background, and provides notification / lock screen / headset / Bluetooth controls.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var retried = false

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(this, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec -> Streams.resolve(applicationContext, spec) }

        // Start playing after 1s of audio is buffered (default is 2.5s) so streams start sooner
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_000, 2_000)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // A stream URL may have expired: refresh it once and retry
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!retried && player.currentMediaItem?.mediaId?.startsWith("yt:") == true) {
                    retried = true
                    Streams.clear()
                    player.prepare()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retried = false
                prefetchUpcoming(player)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                prefetchUpcoming(player)
            }

            override fun onEvents(p: Player, events: Player.Events) {
                AudifyWidget.update(applicationContext, p)
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Items arrive from the UI without URIs; rebuild them from the media id
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { it.withUri() }.toMutableList())
            })
            .build()
    }

    private fun prefetchUpcoming(player: Player) {
        if (player.mediaItemCount == 0) return
        var idx = player.currentMediaItemIndex
        repeat(2) {
            idx = player.currentTimeline.getNextWindowIndex(idx, player.repeatMode, player.shuffleModeEnabled)
            if (idx == C.INDEX_UNSET) return
            val mediaId = player.getMediaItemAt(idx).mediaId
            if (mediaId.startsWith("yt:")) Streams.prefetch(applicationContext, mediaId.removePrefix("yt:"))
        }
    }

    private fun MediaItem.withUri(): MediaItem {
        val uri = if (mediaId.startsWith("yt:")) Uri.parse("ytid:" + mediaId.removePrefix("yt:"))
        else Uri.fromFile(File(mediaId))
        return buildUpon().setUri(uri).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        AudifyWidget.clear(applicationContext)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** The UI's connection to the playback service, as Compose state. */
object PlayerConn {
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var current by mutableStateOf<MediaItem?>(null)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            error = null
            songOf(mediaItem)?.let { Store.addHistory(it) }
        }

        override fun onPlayerError(e: PlaybackException) {
            error = if (e.errorCode in 2000..2999) "Network problem: check your connection" else "Couldn't play this song"
        }
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
        isBuffering = p.playbackState == Player.STATE_BUFFERING
        current = p.currentMediaItem
        duration = p.duration.takeIf { it > 0 } ?: 0L
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
    }

    val position: Long get() = controller?.currentPosition ?: 0L

    /** The Song behind a media item (streams and downloads that came from YouTube). */
    fun songOf(item: MediaItem?): Song? {
        item ?: return null
        val md = item.mediaMetadata
        val id = if (item.mediaId.startsWith("yt:")) item.mediaId.removePrefix("yt:")
        else Library.byPath(item.mediaId)?.videoId ?: return null
        return Song(id, md.title?.toString() ?: "", md.artist?.toString() ?: "")
    }

    val currentSong: Song? get() = songOf(current)

    private fun itemFor(s: Song): MediaItem = Library.byId(s.id)?.toMediaItem() ?: s.toStreamItem()

    /** Plays songs, using the downloaded copy when there is one and streaming otherwise. */
    fun playSongs(songs: List<Song>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        error = null
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(songs.map(::itemFor), start.coerceIn(0, songs.size - 1), 0L)
        c.prepare()
        c.play()
    }

    fun playTracks(tracks: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        error = null
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(tracks.map { it.toMediaItem() }, start.coerceIn(0, tracks.size - 1), 0L)
        c.prepare()
        c.play()
    }

    fun playNext(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playSongs(listOf(s), 0)
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, itemFor(s))
        }
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

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

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

    fun removeFromQueue(path: String) {
        val c = controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId == path) c.removeMediaItem(i)
        }
    }
}
