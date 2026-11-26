package com.audify.app

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Wraps the bundled yt-dlp + ffmpeg:
 *  search / related  -> song lists
 *  streamInfo        -> a direct audio URL for streaming
 *  fetch             -> download + ffmpeg conversion
 */
object Downloader {

    @Volatile
    var ready = false
        private set

    val AUDIO_EXTS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav", "aac")
    private val PART_FILE = Regex("""\.f\d+\.""")
    private val ID_IN_URL = Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    fun audioDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "Audify").apply { mkdirs() }

    fun videoDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "Audify").apply { mkdirs() }

    fun coversDir(ctx: Context): File = File(audioDir(ctx), ".covers").apply { mkdirs() }

    fun videoIdFromUrl(url: String): String? = ID_IN_URL.find(url)?.groupValues?.get(1)

    private fun run(req: YoutubeDLRequest, tag: String): String =
        YoutubeDL.getInstance().execute(req, "$tag-${System.nanoTime()}") { _, _, _ -> }.out

    // ---------------------------------------------------------------- lists

    fun search(ctx: Context, query: String, limit: Int = 20): List<Song> =
        flatList(ctx, "ytsearch$limit:$query", null)

    /** Songs related to [id], from YouTube's auto-generated "Mix" for that video. */
    fun related(ctx: Context, id: String, limit: Int = 25): List<Song> =
        flatList(ctx, "https://www.youtube.com/watch?v=$id&list=RD$id", limit).filter { it.id != id }

    private fun flatList(ctx: Context, target: String, limit: Int?): List<Song> {
        init(ctx)
        val req = YoutubeDLRequest(target).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
            if (limit != null) addOption("--playlist-end", limit.toString())
        }
        val entries = JSONObject(run(req, "list")).optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<Song>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.str("id")
            val title = e.str("title")
            if (id.length != 11 || title.isEmpty()) continue
            val dur = if (e.isNull("duration")) 0 else e.optDouble("duration", 0.0).toInt()
            out += Song(id, title, e.str("channel").ifEmpty { e.str("uploader") }, dur)
        }
        return out.distinctBy { it.id }
    }

    /** Public playlists on YouTube matching [query] (made by other people). */
    fun publicPlaylists(ctx: Context, query: String, limit: Int = 12): List<RemotePlaylist> {
        init(ctx)
        val url = "https://www.youtube.com/results?search_query=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&sp=EgIQAw%3D%3D"
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
            addOption("--playlist-end", limit.toString())
        }
        val entries = JSONObject(run(req, "pl")).optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<RemotePlaylist>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.str("id")
            val title = e.str("title")
            if (id.length < 13 || title.isEmpty() || id.startsWith("RD")) continue
            val thumbs = e.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.startsWith("http") }
            val count = if (e.isNull("playlist_count")) 0 else e.optInt("playlist_count")
            out += RemotePlaylist(id, title, e.str("channel").ifEmpty { e.str("uploader") }, thumb, count)
        }
        return out
    }

    fun playlistSongs(ctx: Context, playlistId: String, limit: Int = 100): List<Song> =
        flatList(ctx, "https://www.youtube.com/playlist?list=$playlistId", limit)

    /** Look up titles for bare video ids (used when opening Audify codes). */
    fun resolveSongs(ctx: Context, ids: List<String>): List<Song> {
        init(ctx)
        if (ids.isEmpty()) return emptyList()
        return try {
            lookup(ids)
        } catch (e: Exception) {
            // One bad id fails the whole batch, so fall back to one at a time
            ids.mapNotNull { id -> runCatching { lookup(listOf(id)) }.getOrNull()?.firstOrNull() }
        }
    }

    private fun lookup(ids: List<String>): List<Song> {
        val urls = ids.map { "https://www.youtube.com/watch?v=$it" }
        val req = YoutubeDLRequest(urls.first()).apply {
            urls.drop(1).forEach { addOption(it) }
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--print", "%(id)s\t%(title)s\t%(uploader)s\t%(duration)s")
        }
        return run(req, "lookup").lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4 || p[0].length != 11) null
            else Song(p[0], p[1], p[2].takeIf { it != "NA" } ?: "", p[3].toDoubleOrNull()?.toInt() ?: 0)
        }
    }

    /** Direct audio URL (+ the User-Agent it must be fetched with) for streaming. */
    fun streamInfo(ctx: Context, id: String): Pair<String, String?> {
        init(ctx)
        // Streaming uses a lighter format than downloads so songs start quicker
        val format = when (Prefs.streamQuality) {
            StreamQuality.LOW -> "worstaudio[abr>=40]/worstaudio/bestaudio"
            StreamQuality.NORMAL -> "bestaudio[abr<=100]/bestaudio[ext=m4a]/bestaudio"
            StreamQuality.HIGH -> "bestaudio[ext=m4a]/bestaudio"
        }
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id").apply {
            addOption("-f", format)
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--print", "%(url)s\t%(http_headers.User-Agent)s")
        }
        val line = run(req, "stream").lines().lastOrNull { it.startsWith("http") }
            ?: throw IOException("Couldn't get a stream for this song")
        val parts = line.split('\t')
        return parts[0] to parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "NA" }
    }

    // ---------------------------------------------------------------- download

    fun fetch(
        ctx: Context,
        url: String,
        quality: Quality,
        keepVideo: Boolean,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): File {
        init(ctx)
        val work = File(ctx.cacheDir, "dl_${System.nanoTime()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--windows-filenames")
                addOption("--no-mtime")
                if (keepVideo) {
                    addOption("-f", "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                    addOption("-k")
                } else {
                    addOption("-f", "bestaudio/best")
                }
                addOption("-x")   // ffmpeg: extract the audio track
                when (quality) {
                    Quality.BEST -> Unit   // keep the original codec, no re-encode
                    Quality.FLAC -> addOption("--audio-format", "flac")
                    Quality.MP3_320 -> {
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "320K")
                    }
                    Quality.MP3_192 -> {
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "192K")
                    }
                    Quality.SAVER -> {
                        addOption("--audio-format", "opus")
                        addOption("--audio-quality", "64K")
                    }
                }
                addOption("--embed-metadata")
                addOption("--write-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("-o", File(work, "%(title).120B [%(id)s].%(ext)s").absolutePath)
            }

            YoutubeDL.getInstance().execute(req, processId) { progress, _, line -> onProgress(progress, line) }

            val files = work.walkTopDown().filter { it.isFile }.toList()
            val audio = files.firstOrNull { it.extension.lowercase() in AUDIO_EXTS && !PART_FILE.containsMatchIn(it.name) }
                ?: files.firstOrNull { it.extension.lowercase() in AUDIO_EXTS }
                ?: throw IOException("ffmpeg didn't produce an audio file")

            val dest = uniqueFile(audioDir(ctx), audio.name)
            audio.copyTo(dest)

            val thumb = files.firstOrNull {
                it.extension.equals("jpg", true) && it.nameWithoutExtension == audio.nameWithoutExtension
            } ?: files.firstOrNull { it.extension.equals("jpg", true) }
            thumb?.copyTo(File(coversDir(ctx), dest.nameWithoutExtension + ".jpg"), overwrite = true)

            if (keepVideo) {
                files.filter {
                    it.extension.lowercase() in setOf("mp4", "webm", "mkv") && !PART_FILE.containsMatchIn(it.name)
                }.forEach { it.copyTo(uniqueFile(videoDir(ctx), it.name)) }
            }
            return dest
        } finally {
            work.deleteRecursively()
        }
    }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    fun updateYtDlp(ctx: Context): String {
        init(ctx)
        return YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)?.toString() ?: "No update info"
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
            n++
        }
        return f
    }
}

fun shortError(e: Throwable): String {
    val line = e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
    return line.removePrefix("ERROR: ").replace(Regex("""^\[[^]]+] [\w-]+: """), "").take(200)
}
