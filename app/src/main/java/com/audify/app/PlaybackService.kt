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

    /**
     * Finds a working stream: NewPipeExtractor first (fast, no Python), checked with a tiny
     * test request, then yt-dlp as the fallback.
     */
    fun source(ctx: Context, id: String): AudioSource {
        val pick = when (Prefs.streamQuality) {
            StreamQuality.LOW -> NewPipeSource.Pick.LOWEST
            StreamQuality.NORMAL -> NewPipeSource.Pick.NORMAL
            StreamQuality.HIGH -> NewPipeSource.Pick.HIGHEST
        }
        runCatching { NewPipeSource.audio(id, pick) }.getOrNull()
            ?.takeIf { ChunkedDownloader.works(it) }
            ?.let { return it }
        val (url, ua) = Downloader.streamInfo(ctx, id)
        return AudioSource(url, ua, "m4a", 0, "yt-dlp")
    }

    // ---- on-disk audio cache: replays and seeks don't re-download, next song is pre-buffered
    private var diskCache: androidx.media3.datasource.cache.SimpleCache? = null
    private var cacheFactory: androidx.media3.datasource.cache.CacheDataSource.Factory? = null

    @OptIn(UnstableApi::class)
    @Synchronized
    fun dataSourceFactory(ctx: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        cacheFactory?.let { return it }
        val app = ctx.applicationContext
        val cache = androidx.media3.datasource.cache.SimpleCache(
            java.io.File(app.cacheDir, "audio-cache"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(400L * 1024 * 1024),
            androidx.media3.database.StandaloneDatabaseProvider(app),
        )
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val chunked = androidx.media3.datasource.DataSource.Factory { ChunkedHttpDataSource(http.createDataSource()) }
        val resolving = ResolvingDataSource.Factory(chunked) { spec -> resolve(app, spec) }
        val factory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolving)
            // Key by song id + quality, not by the (expiring) YouTube URL
            .setCacheKeyFactory { spec -> spec.uri.toString() + "|" + Prefs.streamQuality.name }
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        diskCache = cache
        cacheFactory = factory
        return factory
    }

    /** Download the first ~1.5 MB of a song into the cache so it starts instantly. */
    @OptIn(UnstableApi::class)
    private fun prebuffer(ctx: Context, id: String) {
        val factory = cacheFactory ?: return
        runCatching {
            val spec = DataSpec.Builder().setUri(Uri.parse("ytid:$id")).setLength(1_500_000).build()
            androidx.media3.datasource.cache.CacheWriter(factory.createDataSourceForDownloading(), spec, null, null).cache()
        }
    }

    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Resolve upcoming songs in the background so they start instantly. */
    fun prefetch(ctx: Context, id: String) {
        val now = System.currentTimeMillis()
        if (!pending.add(id)) return
        prefetcher.execute {
            try {
                if (cache[id]?.let { now - it.at < TTL_MS } != true) {
                    val src = source(ctx, id)
                    cache[id] = Entry(src.url, src.userAgent, System.currentTimeMillis())
                }
                prebuffer(ctx, id)
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
                source(ctx, id).let { Entry(it.url, it.userAgent, now) }.also { cache[id] = it }
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
 * Streams in 2 MB pieces instead of one long request. YouTube slows down single long
 * connections, so short range requests keep the buffer full and playback smooth.
 */
@OptIn(UnstableApi::class)
private class ChunkedHttpDataSource(
    private val upstream: androidx.media3.datasource.DataSource,
) : androidx.media3.datasource.DataSource {
    private val chunk = 2L * 1024 * 1024
    private var spec: DataSpec? = null
    private var pos = 0L
    private var end = -1L          // exclusive; -1 = unknown
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else -1L
        openChunk()
        if (end < 0) {
            // Learn the total size from the first response ("Content-Range: bytes a-b/total")
            val total = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringAfter('/')?.toLongOrNull()
            if (total != null) end = total
        }
        return if (end >= 0) end - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val s = spec ?: throw java.io.IOException("Not opened")
        val len = if (end >= 0) minOf(chunk, end - pos) else chunk
        upstream.open(s.buildUpon().setPosition(pos).setLength(len).build())
        upstreamOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (end in 0..pos) return C.RESULT_END_OF_INPUT
            val n = upstream.read(buffer, offset, length)
            if (n != C.RESULT_END_OF_INPUT) {
                pos += n
                return n
            }
            // This piece is finished: open the next one
            upstream.close()
            upstreamOpen = false
            if (end < 0 || pos >= end) return C.RESULT_END_OF_INPUT
            openChunk()
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }
}

/** Sends "ytid:" streams through the cache and everything else (local files) straight to disk. */
@OptIn(UnstableApi::class)
private class StreamOrFileDataSource(
    private val stream: androidx.media3.datasource.DataSource,
    private val plain: androidx.media3.datasource.DataSource,
) : androidx.media3.datasource.DataSource {
    private var active: androidx.media3.datasource.DataSource? = null

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        stream.addTransferListener(transferListener)
        plain.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == "ytid") stream else plain
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw java.io.IOException("Not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
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
    private val extResolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val extResolverPool = java.util.concurrent.Executors.newFixedThreadPool(4)

    override fun onCreate() {
        super.onCreate()
        val streamFactory = Streams.dataSourceFactory(this)
        val plainFactory = DefaultDataSource.Factory(this)
        // Streams go through the cache; downloaded files are read directly
        val dataSource = androidx.media3.datasource.DataSource.Factory {
            StreamOrFileDataSource(streamFactory.createDataSource(), plainFactory.createDataSource())
        }

        // Start after 1s of audio, then keep up to 2 minutes buffered so network hiccups don't stall playback.
        // After a stall, wait for 3s of audio so it doesn't stutter on and off.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_000, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
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

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }
        })

        // Equalizer / bass boost on the player's audio output
        AudioFx.attach(player.audioSessionId)

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
                ): ListenableFuture<MutableList<MediaItem>> {
                    if (mediaItems.none { it.mediaId.startsWith("ext:") }) {
                        return Futures.immediateFuture(mediaItems.map { it.withUri() }.toMutableList())
                    }
                    // SoundCloud / Bandcamp / Audius: look up their streams (in parallel) first
                    val future = com.google.common.util.concurrent.SettableFuture.create<MutableList<MediaItem>>()
                    extResolver.execute {
                        val app = applicationContext
                        val resolved = mediaItems.map { item ->
                            extResolverPool.submit<MediaItem> {
                                if (!item.mediaId.startsWith("ext:")) return@submit item.withUri()
                                val song = ExtSongs.get(item.mediaId.removePrefix("ext:"))
                                val st = song?.let { runCatching { OtherSources.stream(app, it) }.getOrNull() }
                                if (st == null) item.buildUpon().setUri("ext:unavailable").build()
                                else item.buildUpon().setUri(st.url).setMimeType(st.mimeType).build()
                            }
                        }.map { it.get() }
                        future.set(resolved.toMutableList())
                    }
                    return future
                }
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
        AudioFx.release()
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

    /** Spotify-style shuffle: off, normal shuffle, or Smart Shuffle (mixes in recommendations). */
    enum class ShuffleMode { OFF, NORMAL, SMART }

    var shuffleMode by mutableStateOf(ShuffleMode.OFF)
        private set

    /** Media ids that Smart Shuffle added, so they can be marked and removed again. */
    var smartAdded by mutableStateOf<Set<String>>(emptySet())
        private set
    var smartLoading by mutableStateOf(false)
        private set
    private var appCtx: Context? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

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
            Dj.onTransition(mediaItem)
        }

        override fun onPlayerError(e: PlaybackException) {
            error = if (e.errorCode in 2000..2999) "Network problem: check your connection" else "Couldn't play this song"
        }
    }

    fun connect(ctx: Context) {
        appCtx = ctx.applicationContext
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
        // Shuffle toggled from the notification / headset: keep our mode in step
        if (!p.shuffleModeEnabled && shuffleMode != ShuffleMode.OFF) {
            shuffleMode = ShuffleMode.OFF
            smartAdded = emptySet()
        } else if (p.shuffleModeEnabled && shuffleMode == ShuffleMode.OFF) {
            shuffleMode = ShuffleMode.NORMAL
        }
        repeatMode = p.repeatMode
    }

    val position: Long get() = controller?.currentPosition ?: 0L

    /** The Song behind a media item (streams and downloads that came from YouTube). */
    fun songOf(item: MediaItem?): Song? {
        item ?: return null
        val md = item.mediaMetadata
        if (item.mediaId.startsWith("ext:")) return ExtSongs.get(item.mediaId.removePrefix("ext:"))
        if (item.mediaId.startsWith("yt:")) return Song(item.mediaId.removePrefix("yt:"), md.title?.toString() ?: "", md.artist?.toString() ?: "")
        return Library.byPath(item.mediaId)?.toSong()
    }

    val currentSong: Song? get() = songOf(current)

    fun itemFor(s: Song): MediaItem = Library.byId(s.id)?.toMediaItem() ?: s.toStreamItem()

    /** Plays songs, using the downloaded copy when there is one and streaming otherwise. */
    fun playSongs(all: List<Song>, startAt: Int, shuffled: Boolean = false, inOrder: Boolean = false) {
        val c = controller ?: return
        if (all.isEmpty()) return
        // Songs from other platforms are looked up before playing, so keep their queue short
        var songs = all
        var start = startAt
        if (all.any { !it.isYouTube && Library.byId(it.id) == null }) {
            val from = startAt.coerceIn(0, all.size - 1)
            songs = all.subList(from, minOf(from + 8, all.size))
            start = 0
        }
        error = null
        smartAdded = emptySet()
        // Like Spotify, your shuffle setting carries over to whatever you play next
        when {
            inOrder -> shuffleMode = ShuffleMode.OFF
            shuffled && shuffleMode == ShuffleMode.OFF -> shuffleMode = ShuffleMode.NORMAL
        }
        if (!inOrder && Dj.active) Dj.stop()
        c.shuffleModeEnabled = shuffleMode != ShuffleMode.OFF
        c.setMediaItems(songs.map(::itemFor), start.coerceIn(0, songs.size - 1), 0L)
        c.prepare()
        c.play()
        if (shuffleMode == ShuffleMode.SMART) addSmartPicks()
    }

    fun playTracks(tracks: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        error = null
        smartAdded = emptySet()
        if (Dj.active) Dj.stop()
        if (shuffled && shuffleMode == ShuffleMode.OFF) shuffleMode = ShuffleMode.NORMAL
        c.shuffleModeEnabled = shuffleMode != ShuffleMode.OFF
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

    /** Tap the shuffle button: Off → Shuffle → Smart Shuffle → Off. */
    fun cycleShuffle(): ShuffleMode {
        val next = when (shuffleMode) {
            ShuffleMode.OFF -> ShuffleMode.NORMAL
            ShuffleMode.NORMAL -> ShuffleMode.SMART
            ShuffleMode.SMART -> ShuffleMode.OFF
        }
        setShuffle(next)
        return next
    }

    fun setShuffle(mode: ShuffleMode) {
        val c = controller ?: return
        shuffleMode = mode
        when (mode) {
            ShuffleMode.OFF -> {
                removeSmartPicks()
                c.shuffleModeEnabled = false
            }
            ShuffleMode.NORMAL -> {
                removeSmartPicks()
                c.shuffleModeEnabled = true
            }
            ShuffleMode.SMART -> {
                c.shuffleModeEnabled = true
                addSmartPicks()
            }
        }
    }

    /**
     * Smart Shuffle: finds songs related to what's in your queue and shuffles them in,
     * roughly one recommendation for every three of your songs.
     */
    private fun addSmartPicks() {
        val c = controller ?: return
        val ctx = appCtx ?: return
        val queue = (0 until c.mediaItemCount).mapNotNull { songOf(c.getMediaItemAt(it)) }
        val inQueue = queue.map { it.id }.toSet()
        val seeds = queue.filter { it.isYouTube }.shuffled().take(3)
            .ifEmpty { listOfNotNull(Store.history.value.firstOrNull { it.isYouTube }) }
        if (seeds.isEmpty()) return
        val wanted = (queue.size / 3).coerceIn(3, 15)
        smartLoading = true
        kotlin.concurrent.thread(name = "smart-shuffle") {
            val picks = seeds.flatMap { seed -> runCatching { Downloader.related(ctx, seed.id, 15) }.getOrDefault(emptyList()) }
                .distinctBy { it.id }
                .filter { it.id !in inQueue }
                .shuffled()
                .take(wanted)
            main.post {
                smartLoading = false
                val ctl = controller ?: return@post
                if (shuffleMode != ShuffleMode.SMART || picks.isEmpty()) return@post
                val items = picks.map(::itemFor)
                ctl.addMediaItems(items)   // shuffle mode places them at random spots
                smartAdded = smartAdded + items.map { it.mediaId }
            }
        }
    }

    private fun removeSmartPicks() {
        val c = controller ?: return
        if (smartAdded.isEmpty()) return
        val current = c.currentMediaItemIndex
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (i != current && c.getMediaItemAt(i).mediaId in smartAdded) c.removeMediaItem(i)
        }
        smartAdded = emptySet()
    }

    /** Jump to a queue position (used by the DJ's "Switch it up"). */
    fun seekToIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L)
    }

    fun queueIndexOf(mediaId: String): Int {
        val c = controller ?: return -1
        return (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == mediaId } ?: -1
    }

    fun appendSongs(songs: List<Song>) {
        controller?.addMediaItems(songs.map(::itemFor))
    }

    /** Lower the music while the DJ talks. */
    fun duck(on: Boolean) {
        controller?.volume = if (on) 0.22f else 1f
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
