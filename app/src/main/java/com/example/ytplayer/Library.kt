package com.example.ytplayer

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class Track(
    val file: File,
    val title: String,
    val artist: String,
    val videoId: String?,
    val cover: File?,
) {
    val path: String get() = file.absolutePath

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(path)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifEmpty { null })
                .setArtworkUri(cover?.let { Uri.fromFile(it) })
                .build()
        )
        .build()
}

/** The downloaded songs on disk. */
object Library {
    private val AUDIO_EXTS = setOf("mp3", "m4a", "ogg", "opus", "wav", "flac")
    private val ID_RE = Regex("""\s*\[([A-Za-z0-9_-]{11})]$""")

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val cache = HashMap<String, Pair<Long, Track>>()

    @Synchronized
    fun refresh(ctx: Context) {
        val covers = Downloader.coversDir(ctx)
        val files = Downloader.audioDir(ctx)
            .listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTS }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        _tracks.value = files.map { f ->
            cache[f.path]?.takeIf { it.first == f.lastModified() }?.second
                ?: load(f, covers).also { cache[f.path] = f.lastModified() to it }
        }
    }

    fun delete(ctx: Context, t: Track) {
        t.file.delete()
        t.cover?.delete()
        synchronized(this) { cache.remove(t.file.path) }
        refresh(ctx)
    }

    private fun load(f: File, coversDir: File): Track {
        val stem = f.nameWithoutExtension
        val id = ID_RE.find(stem)?.groupValues?.get(1)
        var title = stem.replace(ID_RE, "").replace('_', ' ').trim()
        var artist = ""
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        val cover = File(coversDir, "$stem.jpg").takeIf { it.exists() }
        return Track(f, title, artist, id, cover)
    }
}

/** Download queue: one download at a time, progress exposed as state. */
sealed interface DlState {
    data object Queued : DlState
    data class Running(val percent: Float, val line: String) : DlState
    data class Failed(val message: String) : DlState
}

data class DlJob(val key: String, val url: String, val title: String, val state: DlState)

object Downloads {
    private val _jobs = MutableStateFlow<Map<String, DlJob>>(emptyMap())
    val jobs: StateFlow<Map<String, DlJob>> = _jobs.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun keyFor(url: String) = Downloader.videoIdFromUrl(url) ?: url

    fun enqueue(ctx: Context, url: String, title: String, key: String = keyFor(url)) {
        val existing = _jobs.value[key]
        if (existing != null && existing.state !is DlState.Failed) return
        cancelled.remove(key)
        _jobs.update { it + (key to DlJob(key, url, title, DlState.Queued)) }
        val app = ctx.applicationContext
        executor.execute {
            if (cancelled.remove(key)) return@execute
            setState(key, DlState.Running(-1f, "Starting…"))
            try {
                Downloader.fetch(app, url, Prefs.keepVideo, "dl-$key") { pct, line ->
                    setState(key, DlState.Running(pct, line))
                }
                Library.refresh(app)
                remove(key)
            } catch (e: Exception) {
                if (cancelled.remove(key)) remove(key) else setState(key, DlState.Failed(shortError(e)))
            }
        }
    }

    fun cancel(key: String) {
        val job = _jobs.value[key] ?: return
        cancelled.add(key)
        if (job.state is DlState.Running) runCatching { Downloader.cancel("dl-$key") }
        remove(key)
    }

    fun dismiss(key: String) = remove(key)

    private fun setState(key: String, s: DlState) =
        _jobs.update { m -> m[key]?.let { m + (key to it.copy(state = s)) } ?: m }

    private fun remove(key: String) = _jobs.update { it - key }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var keepVideo: Boolean
        get() = sp.getBoolean("keepVideo", false)
        set(v) = sp.edit().putBoolean("keepVideo", v).apply()
}
