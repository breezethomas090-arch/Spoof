package com.audify.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A downloaded audio file. */
data class Track(
    val file: File,
    val title: String,
    val artist: String,
    val videoId: String?,
    val cover: File?,
    val quality: String? = null,   // "low" = quick copy waiting for its upgrade
    val source: String = "yt",
    val page: String? = null,
    val art: String? = null,
) {
    val path: String get() = file.absolutePath

    fun toSong(): Song? = videoId?.let { Song(it, title, artist, 0, source, page, art) }

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(path)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifEmpty { null })
                .setArtworkUri(cover?.let { Uri.fromFile(it) } ?: toSong()?.thumbLarge?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) })
                .build()
        )
        .build()
}

/** Downloaded songs on disk. */
object Library {
    private val ID_RE = Regex("""\s*\[([A-Za-z0-9_-]{11})]$""")

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val cache = HashMap<String, Pair<Long, Track>>()

    fun byId(id: String): Track? = _tracks.value.firstOrNull { it.videoId == id }
    fun byPath(path: String): Track? = _tracks.value.firstOrNull { it.path == path }

    @Synchronized
    fun refresh(ctx: Context) {
        val covers = Downloader.coversDir(ctx)
        val files = Downloader.audioDir(ctx)
            .listFiles { f -> f.isFile && f.extension.lowercase() in Downloader.AUDIO_EXTS }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        _tracks.value = files.map { f ->
            cache[f.path]?.takeIf { it.first == f.lastModified() }?.second
                ?: load(f, covers).also { cache[f.path] = f.lastModified() to it }
        }
    }

    fun delete(ctx: Context, t: Track) {
        t.file.delete()
        t.cover?.delete()
        File(Downloader.coversDir(ctx), t.file.nameWithoutExtension + ".json").delete()
        synchronized(this) { cache.remove(t.file.path) }
        refresh(ctx)
    }

    private fun load(f: File, coversDir: File): Track {
        val stem = f.nameWithoutExtension
        var id = ID_RE.find(stem)?.groupValues?.get(1)
        var source = "yt"
        var page: String? = null
        var art: String? = null
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
        // Sidecar written by Audify's own downloader (files it saves aren't tagged)
        var quality: String? = null
        File(coversDir, "$stem.json").takeIf { it.exists() }?.let { meta ->
            runCatching {
                val o = org.json.JSONObject(meta.readText())
                o.optString("t").takeIf { it.isNotBlank() }?.let { title = it }
                o.optString("a").takeIf { it.isNotBlank() }?.let { artist = it }
                quality = o.optString("q").ifBlank { null }
                o.optString("k").takeIf { it.isNotBlank() }?.let { id = it }
                o.optString("s").takeIf { it.isNotBlank() }?.let { source = it }
                page = o.optString("p").ifBlank { null }
                art = o.optString("r").ifBlank { null }
            }
        }
        val cover = File(coversDir, "$stem.jpg").takeIf { it.exists() }
        return Track(f, title, artist, id, cover, quality, source, page, art)
    }
}

/** Download queue: one at a time, progress exposed as state. */
sealed interface DlState {
    data object Queued : DlState
    data class Running(val percent: Float, val line: String) : DlState
    data class Failed(val message: String) : DlState
}

data class DlJob(val key: String, val url: String, val title: String, val state: DlState, val song: Song? = null)

object Downloads {
    private val _jobs = MutableStateFlow<Map<String, DlJob>>(emptyMap())
    val jobs: StateFlow<Map<String, DlJob>> = _jobs.asStateFlow()

    /** Songs whose quick low-quality copy is being replaced with the full-quality version. */
    private val _upgrading = MutableStateFlow<Set<String>>(emptySet())
    val upgrading: StateFlow<Set<String>> = _upgrading.asStateFlow()

    private val executor = Executors.newFixedThreadPool(3)        // three songs at a time
    private val upgrader = Executors.newSingleThreadExecutor()     // background quality upgrades
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(ctx: Context, song: Song) = enqueue(ctx, song.url, song.title, song.id, song.artist, song)

    fun enqueue(
        ctx: Context,
        url: String,
        title: String,
        key: String = Downloader.videoIdFromUrl(url) ?: url,
        artist: String = "",
        song: Song? = null,
    ) {
        if (Library.byId(key) != null) return
        val existing = _jobs.value[key]
        if (existing != null && existing.state !is DlState.Failed) return
        cancelled.remove(key)
        _jobs.update { it + (key to DlJob(key, url, title, DlState.Queued, song)) }
        val app = ctx.applicationContext
        executor.execute {
            if (cancelled.remove(key)) return@execute
            setState(key, DlState.Running(-1f, "Starting…"))
            val id = if (song != null && !song.isYouTube) null else Downloader.videoIdFromUrl(url)
            val target = Prefs.quality
            try {
                // SoundCloud / Bandcamp / Audius
                if (song != null && !song.isYouTube) {
                    otherDownload(app, song) { pct, line -> setState(key, DlState.Running(pct, line)) }
                    Library.refresh(app)
                    remove(key)
                    return@execute
                }
                // 1) Quick low-quality copy so the song is offline in seconds, upgraded later
                if (id != null && Prefs.quickFirst && target != Quality.SAVER) {
                    val quick = runCatching {
                        DirectDownload.save(app, id, title, artist, NewPipeSource.Pick.LOWEST, "low") { p ->
                            setState(key, DlState.Running(p, "Quick download"))
                        }
                    }
                    if (quick.isSuccess && !cancelled.contains(key)) {
                        Library.refresh(app)
                        remove(key)
                        scheduleUpgrade(app, id, url, title, artist, target)
                        return@execute
                    }
                }
                // 2) Full quality straight away
                fullDownload(app, id, url, title, artist, target, key) { pct, line -> setState(key, DlState.Running(pct, line)) }
                Library.refresh(app)
                remove(key)
            } catch (e: Exception) {
                if (cancelled.remove(key)) remove(key) else setState(key, DlState.Failed(shortError(e)))
            }
        }
    }

    /**
     * Tries every way we have, in order: NewPipe + chunked download (fastest, no conversion),
     * then yt-dlp with fast multi-connection downloads, plain yt-dlp, and yt-dlp pretending
     * to be other YouTube apps (helps when YouTube blocks one of them).
     */
    private fun fullDownload(
        app: Context, id: String?, url: String, title: String, artist: String,
        q: Quality, key: String, progress: (Float, String) -> Unit,
    ): File {
        val errors = ArrayList<Throwable>()
        if (id != null && (q == Quality.BEST || q == Quality.SAVER)) {
            val pick = if (q == Quality.BEST) NewPipeSource.Pick.HIGHEST else NewPipeSource.Pick.LOWEST
            runCatching { return DirectDownload.save(app, id, title, artist, pick, "high") { p -> progress(p, "Downloading ${p.toInt()}%") } }
                .onFailure { errors += it }
        }
        val attempts = listOf(
            Triple(true, false, "Downloading…"),
            Triple(false, false, "Retrying…"),
            Triple(false, true, "Trying another way…"),
        )
        for ((fast, alt, label) in attempts) {
            if (cancelled.contains(key)) throw IOException("Cancelled")
            if (fast && !Prefs.fastDownloads) continue
            progress(-1f, label)
            runCatching {
                val f = Downloader.fetch(app, url, q, Prefs.keepVideo, "dl-$key", fast = fast, altClients = alt, onProgress = progress)
                DirectDownload.writeMeta(app, f, title, artist, "high")
                return f
            }.onFailure { errors += it }
        }
        throw errors.lastOrNull() ?: IOException("Download failed")
    }

    /** Direct download when the platform gives a plain file; yt-dlp (with ffmpeg) for HLS streams. */
    private fun otherDownload(app: Context, s: Song, progress: (Float, String) -> Unit): File {
        val errors = ArrayList<Throwable>()
        runCatching {
            val st = OtherSources.stream(app, s)
            if (st.mimeType == null) {
                return DirectDownload.saveFrom(app, s, AudioSource(st.url, null, st.ext, 0, s.sourceLabel)) { p ->
                    progress(p, "Downloading ${p.toInt()}%")
                }
            }
        }.onFailure { errors += it }
        val page = s.page ?: throw errors.lastOrNull() ?: IOException("No link for this song")
        progress(-1f, "Downloading…")
        runCatching {
            val f = Downloader.fetch(app, page, Prefs.quality, false, "dl-${s.id}", fast = false, onProgress = progress)
            DirectDownload.writeMeta(app, f, s.title, s.artist, "high", s)
            return f
        }.onFailure { errors += it }
        throw errors.lastOrNull() ?: IOException("Download failed")
    }

    private fun scheduleUpgrade(app: Context, id: String, url: String, title: String, artist: String, q: Quality) {
        _upgrading.update { it + id }
        upgrader.execute {
            try {
                val old = Library.byId(id)
                val f = fullDownload(app, id, url, title, artist, q, "up-$id") { _, _ -> }
                if (old != null && old.file.absolutePath != f.absolutePath) {
                    // Keep the original name so the cover and id stay linked, then drop the quick copy
                    val finalFile = File(old.file.parentFile, old.file.nameWithoutExtension + "." + f.extension)
                    old.file.delete()
                    if (f.absolutePath != finalFile.absolutePath) {
                        f.renameTo(finalFile)
                        File(Downloader.coversDir(app), f.nameWithoutExtension + ".jpg").let { if (it.exists() && old.cover != null) it.delete() }
                        File(Downloader.coversDir(app), f.nameWithoutExtension + ".json").delete()
                    }
                    DirectDownload.writeMeta(app, finalFile, title, artist, "high")
                }
                Library.refresh(app)
            } catch (_: Exception) {
                // The quick copy stays; it'll be upgraded next time the song is downloaded
            } finally {
                _upgrading.update { it - id }
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

/** Audify's own downloader: NewPipe finds the audio, ChunkedDownloader fetches it in parallel pieces. */
object DirectDownload {
    private val BAD_CHARS = Regex("""[\\/:*?"<>|\n\r\t]""")

    fun save(
        ctx: Context, id: String, title: String, artist: String,
        pick: NewPipeSource.Pick, qualityTag: String, onProgress: (Float) -> Unit,
    ): File {
        val src = runCatching { NewPipeSource.audio(id, pick) }.getOrNull()?.takeIf { ChunkedDownloader.works(it) }
            ?: Downloader.audioSource(ctx, id, low = pick == NewPipeSource.Pick.LOWEST)
        val dir = Downloader.audioDir(ctx)
        val partial = File(dir, ".partial").apply { mkdirs() }
        val stem = title.replace(BAD_CHARS, "_").trim().take(110).ifEmpty { id } + " [$id]"
        val tmp = File(partial, "$id.${src.ext}")
        ChunkedDownloader.download(src, tmp, onProgress)
        val dest = File(dir, "$stem.${src.ext}")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        saveCover(ctx, id, stem)
        writeMeta(ctx, dest, title, artist, qualityTag)
        return dest
    }

    /** Saves a non-YouTube song from a direct file URL. */
    fun saveFrom(ctx: Context, s: Song, src: AudioSource, onProgress: (Float) -> Unit): File {
        val dir = Downloader.audioDir(ctx)
        val partial = File(dir, ".partial").apply { mkdirs() }
        val key = s.id.replace(':', '-')
        val stem = s.title.replace(BAD_CHARS, "_").trim().take(110).ifEmpty { key } + " [$key]"
        val tmp = File(partial, "$key.${src.ext}")
        ChunkedDownloader.download(src, tmp, onProgress)
        val dest = File(dir, "$stem.${src.ext}")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        s.art?.let { url -> saveImage(url, File(Downloader.coversDir(ctx), "$stem.jpg")) }
        writeMeta(ctx, dest, s.title, s.artist, "high", s)
        return dest
    }

    private fun saveImage(url: String, out: File) {
        runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode == 200) c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            } finally {
                c.disconnect()
            }
        }
    }

    fun writeMeta(ctx: Context, audio: File, title: String, artist: String, quality: String, song: Song? = null) {
        runCatching {
            val o = org.json.JSONObject().put("t", title).put("a", artist).put("q", quality)
            if (song != null && !song.isYouTube) {
                o.put("k", song.id).put("s", song.source).put("p", song.page ?: "").put("r", song.art ?: "")
            }
            File(Downloader.coversDir(ctx), audio.nameWithoutExtension + ".json").writeText(o.toString())
        }
    }

    private fun saveCover(ctx: Context, id: String, stem: String) {
        val out = File(Downloader.coversDir(ctx), "$stem.jpg")
        if (out.exists()) return
        for (name in listOf("hq720", "mqdefault")) {
            val ok = runCatching {
                val c = java.net.URL("https://i.ytimg.com/vi/$id/$name.jpg").openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    if (c.responseCode != 200) return@runCatching false
                    c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                    true
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(false)
            if (ok) return
        }
    }
}
