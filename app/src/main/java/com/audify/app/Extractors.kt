package com.audify.app

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader as NpDownloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** A playable audio URL plus how to fetch it. */
data class AudioSource(
    val url: String,
    val userAgent: String?,
    val ext: String,          // "m4a" or "webm"
    val bitrate: Int,         // kbps (approx)
    val via: String,          // "NewPipe" or "yt-dlp"
)

/**
 * NewPipeExtractor: a second, independent YouTube extractor written in Java.
 * It doesn't start Python, so it resolves streams in about a second, and it keeps
 * working when yt-dlp is blocked or broken for a user.
 */
object NewPipeSource {
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    @Volatile private var ready = false

    fun ensureInit() = init()

    @Synchronized
    private fun init() {
        if (ready) return
        NewPipe.init(HttpDownloader, Localization("en", "GB"), ContentCountry("GB"))
        ready = true
    }

    enum class Pick { LOWEST, NORMAL, HIGHEST }

    /** Audio stream for a video. [pick] chooses the bitrate. Throws if nothing usable is found. */
    fun audio(id: String, pick: Pick): AudioSource {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (streams.isEmpty()) throw IOException("NewPipe found no audio streams")
        val sorted = streams.sortedBy { it.averageBitrate }
        val s = when (pick) {
            Pick.LOWEST -> sorted.first()
            Pick.NORMAL -> sorted.lastOrNull { it.averageBitrate in 1..100 } ?: sorted.first()
            Pick.HIGHEST -> sorted.last()
        }
        val url = s.content
        val ext = if (s.format?.suffix == "m4a" || url.contains("mime=audio%2Fmp4")) "m4a" else "webm"
        return AudioSource(url, userAgentFor(url), ext, s.averageBitrate, "NewPipe")
    }

    /** Some YouTube URLs only work with the user agent of the app "client" that requested them. */
    private fun userAgentFor(url: String): String = when {
        url.contains("c=ANDROID_VR") -> "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        url.contains("c=ANDROID") -> "com.google.android.youtube/19.28.35 (Linux; U; Android 15; GB) gzip"
        url.contains("c=IOS") -> "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X; GB)"
        else -> BROWSER_UA
    }

    /** Minimal HTTP client NewPipeExtractor uses to talk to YouTube. */
    private object HttpDownloader : NpDownloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            try {
                c.requestMethod = request.httpMethod()
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", BROWSER_UA)
                request.headers().forEach { (k, values) ->
                    c.setRequestProperty(k, null)
                    values.forEach { v -> c.addRequestProperty(k, v) }
                }
                val body = request.dataToSend()
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code == 429) throw ReCaptchaException("YouTube is rate-limiting requests", request.url())
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val headers: Map<String, List<String>> = c.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key!! to it.value }
                return Response(code, c.responseMessage ?: "", headers, text, c.url.toString())
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Downloads a file in 1 MB chunks, 4 at a time, using HTTP range requests.
 * Several small requests are much faster than one big one on YouTube, which slows
 * down long single connections, and a failed chunk is simply retried.
 */
object ChunkedDownloader {
    private const val CHUNK = 1L * 1024 * 1024
    private const val PARALLEL = 4

    fun download(src: AudioSource, dest: File, onProgress: (Float) -> Unit) {
        if (!supportsRanges(src)) {
            plainDownload(src, dest, onProgress)
            return
        }
        val total = contentLength(src)
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        val done = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            val jobs = ArrayList<Future<*>>()
            var start = 0L
            while (start < total) {
                val from = start
                val to = minOf(start + CHUNK, total) - 1
                jobs += pool.submit {
                    fetchRange(src, dest, from, to)
                    val d = done.addAndGet(to - from + 1)
                    onProgress(d * 100f / total)
                }
                start += CHUNK
            }
            jobs.forEach { it.get() }   // rethrows the first failure
        } catch (e: java.util.concurrent.ExecutionException) {
            dest.delete()
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "Download failed", e.cause)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun supportsRanges(src: AudioSource): Boolean {
        val c = open(src, 0, 0)
        return try {
            c.responseCode == 206
        } catch (_: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    /** One plain request, for servers that don't support ranges. */
    private fun plainDownload(src: AudioSource, dest: File, onProgress: (Float) -> Unit) {
        val c = URL(src.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            src.userAgent?.let { c.setRequestProperty("User-Agent", it) }
            if (c.responseCode != 200) throw IOException("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done * 100f / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** Quick check that the URL actually serves audio (some extracted URLs are blocked). */
    fun works(src: AudioSource): Boolean = runCatching { contentLength(src) > 0 }.getOrDefault(false)

    private fun open(src: AudioSource, from: Long, to: Long): HttpURLConnection {
        val c = URL(src.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        src.userAgent?.let { c.setRequestProperty("User-Agent", it) }
        c.setRequestProperty("Range", "bytes=$from-$to")
        return c
    }

    private fun contentLength(src: AudioSource): Long {
        val c = open(src, 0, 0)
        try {
            val code = c.responseCode
            if (code != 206 && code != 200) throw IOException("Server refused the download (HTTP $code)")
            val range = c.getHeaderField("Content-Range")
            return range?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong.takeIf { it > 1 }
                ?: throw IOException("Unknown file size")
        } finally {
            c.disconnect()
        }
    }

    private fun fetchRange(src: AudioSource, dest: File, from: Long, to: Long) {
        var lastError: IOException? = null
        repeat(4) { attempt ->
            if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
            val c = open(src, from, to)
            try {
                if (c.responseCode != 206) throw IOException("Chunk failed (HTTP ${c.responseCode})")
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(from)
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                        }
                    }
                }
                return
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(400L * (attempt + 1))
            } finally {
                c.disconnect()
            }
        }
        throw lastError ?: IOException("Chunk failed")
    }
}
