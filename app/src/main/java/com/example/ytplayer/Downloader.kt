package com.example.ytplayer

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class SearchResult(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Int,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/**
 * Wraps the bundled yt-dlp + ffmpeg.
 *  - search(): yt-dlp "ytsearch" returns matching videos
 *  - fetch():  yt-dlp downloads, ffmpeg extracts the audio to MP3
 */
object Downloader {

    @Volatile
    var ready = false
        private set

    private val PART_FILE = Regex("""\.f\d+\.""")   // leftover single-stream parts like video.f137.mp4
    private val ID_IN_URL = Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")

    /** Unpacks Python, yt-dlp and ffmpeg on first run (a few seconds). Call off the main thread. */
    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    fun audioDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "YTPlayer").apply { mkdirs() }

    fun videoDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "YTPlayer").apply { mkdirs() }

    fun coversDir(ctx: Context): File = File(audioDir(ctx), ".covers").apply { mkdirs() }

    fun videoIdFromUrl(url: String): String? = ID_IN_URL.find(url)?.groupValues?.get(1)

    /** Blocking YouTube search. */
    fun search(ctx: Context, query: String, limit: Int = 20): List<SearchResult> {
        init(ctx)
        val req = YoutubeDLRequest("ytsearch$limit:$query").apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
        }
        val out = YoutubeDL.getInstance().execute(req, "search-${System.nanoTime()}") { _, _, _ -> }.out
        val entries = JSONObject(out).optJSONArray("entries") ?: return emptyList()
        val results = ArrayList<SearchResult>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.str("id")
            val title = e.str("title")
            if (id.length != 11 || title.isEmpty()) continue
            val duration = if (e.isNull("duration")) 0 else e.optDouble("duration", 0.0).toInt()
            results += SearchResult(id, title, e.str("channel").ifEmpty { e.str("uploader") }, duration)
        }
        return results
    }

    /** Blocking download + ffmpeg audio extraction. Returns the finished MP3. */
    fun fetch(
        ctx: Context,
        url: String,
        keepVideo: Boolean,
        processId: String,
        onProgress: (Float, String) -> Unit,
    ): File {
        init(ctx)
        val work = File(ctx.cacheDir, "dl_${System.nanoTime()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--windows-filenames")   // strip characters Android storage rejects
                addOption("--no-mtime")
                if (keepVideo) {
                    addOption("-f", "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                    addOption("-k")
                } else {
                    addOption("-f", "bestaudio/best")
                }
                // ffmpeg: extract audio -> MP3 with title/artist tags
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "2")
                addOption("--embed-metadata")
                // Cover art for the library and lock screen
                addOption("--write-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("-o", File(work, "%(title).120B [%(id)s].%(ext)s").absolutePath)
            }

            YoutubeDL.getInstance().execute(req, processId) { progress, _, line ->
                onProgress(progress, line)
            }

            val files = work.walkTopDown().filter { it.isFile }.toList()
            val mp3 = files.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
                ?: throw IOException("ffmpeg didn't produce an MP3")

            val dest = uniqueFile(audioDir(ctx), mp3.name)
            mp3.copyTo(dest)

            val thumb = files.firstOrNull {
                it.extension.equals("jpg", true) && it.nameWithoutExtension == mp3.nameWithoutExtension
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

    /** YouTube changes often; this pulls the newest yt-dlp into the app. */
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
    return line.removePrefix("ERROR: ").take(200)
}
