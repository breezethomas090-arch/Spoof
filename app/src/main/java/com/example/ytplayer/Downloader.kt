package com.example.ytplayer

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException

/**
 * yt-dlp downloads the video, then yt-dlp's ffmpeg post-processor (the bundled
 * ffmpeg binary) extracts the audio track to MP3.
 */
object Downloader {

    @Volatile
    var ready = false
        private set

    private const val PROCESS_ID = "yt-download"
    private val PART_FILE = Regex("""\.f\d+\.""")   // leftover single-stream parts like video.f137.mp4

    /** Unpacks Python, yt-dlp and ffmpeg on first run. Slow (a few seconds): call off the main thread. */
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

    /** Blocking. Returns the finished MP3. onProgress gets (percent or -1, status line). */
    fun fetch(ctx: Context, url: String, keepVideo: Boolean, onProgress: (Float, String) -> Unit): File {
        val work = File(ctx.cacheDir, "dl_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--restrict-filenames")
                addOption("--no-mtime")
                if (keepVideo) {
                    addOption("-f", "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                    addOption("-k")                          // keep the video after extracting
                } else {
                    addOption("-f", "bestaudio/best")
                }
                // ffmpeg audio extraction
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "2")             // VBR ~190 kbps
                addOption("--embed-metadata")
                addOption("-o", File(work, "%(title).150B [%(id)s].%(ext)s").absolutePath)
            }

            YoutubeDL.getInstance().execute(req, PROCESS_ID) { progress, _, line ->
                onProgress(progress, line)
            }

            val files = work.walkTopDown().filter { it.isFile }.toList()
            val mp3 = files.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
                ?: throw IOException("ffmpeg didn't produce an MP3")

            val dest = uniqueFile(audioDir(ctx), mp3.name)
            mp3.copyTo(dest)

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

    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    /** YouTube changes often; this pulls the newest yt-dlp into the app. */
    fun updateYtDlp(ctx: Context): String =
        YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)?.toString() ?: "No update info"

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
