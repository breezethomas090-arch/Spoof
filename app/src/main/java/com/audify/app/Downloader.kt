package com.audify.app

import android.content.Context
import android.os.Environment
import com.yausername.aria2c.Aria2c
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

    val AUDIO_EXTS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav", "aac", "webm")
    private val PART_FILE = Regex("""\.f\d+\.""")
    private val ID_IN_URL = Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""")

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        runCatching { Aria2c.getInstance().init(ctx.applicationContext) }
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

    fun search(ctx: Context, query: String, limit: Int = 20): List<Song> {
        val music = runCatching { musicSongs(ctx, query, limit) }.getOrDefault(emptyList())
        if (music.size >= 5) return music
        val yt = flatList(ctx, "ytsearch${limit + 10}:$query", null).filter(::isSingleSong)
        return (music + yt).distinctBy { it.id }.take(limit)
    }

    /** Songs related to [id], from YouTube's auto-generated "Mix" for that video. */
    fun related(ctx: Context, id: String, limit: Int = 25): List<Song> =
        flatList(ctx, "https://www.youtube.com/watch?v=$id&list=RD$id", limit).filter { it.id != id && isSingleSong(it) }

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
            out += Song(id, title, artistOf(e), dur)
        }
        return out.distinctBy { it.id }
    }

    private fun artistOf(e: JSONObject): String {
        e.optJSONArray("artists")?.let { a ->
            val names = (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() && it != "null" } }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return e.str("artist").ifEmpty { e.str("channel") }.ifEmpty { e.str("uploader") }.removeSuffix(" - Topic")
    }

    // ---------------------------------------------------------------- real songs, not compilations

    /** Catches "Top 100 songs 2026", "1 hour mix", full albums, nonstop playlists and similar. */
    private val COMPILATION = Regex(
        """(?i)(\btop\s*\d{2,3}\b|\b\d{2,3}\s*(songs|hits|tracks)\b|full\s*album|\bplaylist\b|compilation|non-?stop|megamix|""" +
            """\b\d+\s*(hours?|hrs?)\b|greatest\s*hits|\bbest\s*of\b|\bmix\s*20\d\d|\bhits\s*20\d\d|20\d\d\s*hits|\btop\s*hits\b|\bbillboard\b)"""
    )

    /** Chart-style "Top 100" playlists, which people don't want in place of real playlists. */
    private val TOP_N = Regex("""(?i)(\btop\s*\d{2,3}\b|\b\d{2,3}\s*(songs|hits)\b|\bbillboard\b|non-?stop)""")

    fun isSingleSong(s: Song): Boolean =
        (s.durationSec == 0 || s.durationSec in 45..600) && !COMPILATION.containsMatchIn(s.title)

    private fun enc(q: String) = java.net.URLEncoder.encode(q, "UTF-8")

    /** YouTube Music "Songs" search: real tracks only. */
    private fun musicSongs(ctx: Context, query: String, limit: Int): List<Song> =
        flatList(ctx, "https://music.youtube.com/search?q=${enc(query)}#songs", limit).filter(::isSingleSong)

    /** YouTube Music's own editorial playlists ("Featured playlists"), e.g. Viral Hits, Pop Hotlist. */
    fun featuredPlaylists(ctx: Context, query: String, limit: Int = 10): List<RemotePlaylist> =
        playlistResults(ctx, "https://music.youtube.com/search?q=${enc(query)}#featured+playlists", limit)

    /** Songs from the first featured playlist matching [query], with compilations removed. */
    private fun songsFromFeatured(ctx: Context, query: String, limit: Int): List<Song> {
        for (p in runCatching { featuredPlaylists(ctx, query, 3) }.getOrDefault(emptyList())) {
            val songs = runCatching { playlistSongs(ctx, p.id, limit + 10) }.getOrDefault(emptyList()).filter(::isSingleSong)
            if (songs.size >= 8) return songs.take(limit)
        }
        return emptyList()
    }

    /** What's actually going viral right now, from YouTube Music's viral/trending playlists. */
    fun viral(ctx: Context, limit: Int = 30): List<Song> {
        for (q in listOf("viral hits", "trending now", "tiktok hits")) {
            val songs = songsFromFeatured(ctx, q, limit)
            if (songs.isNotEmpty()) return songs
        }
        return flatList(ctx, "ytsearch40:new viral song official audio", 40).filter(::isSingleSong).take(limit)
    }

    /** Current songs for a genre or mood tile. */
    fun genre(ctx: Context, name: String, limit: Int = 30): List<Song> {
        songsFromFeatured(ctx, "$name hits", limit).takeIf { it.isNotEmpty() }?.let { return it }
        songsFromFeatured(ctx, name, limit).takeIf { it.isNotEmpty() }?.let { return it }
        return flatList(ctx, "ytsearch40:$name song official audio", 40).filter(::isSingleSong).take(limit)
    }

    /** Public playlists matching [query]: YouTube Music's editorial ones first, then community ones. */
    fun publicPlaylists(ctx: Context, query: String, limit: Int = 12): List<RemotePlaylist> {
        val featured = runCatching { featuredPlaylists(ctx, query, limit) }.getOrDefault(emptyList())
        if (featured.size >= 6) return featured
        val community = runCatching {
            playlistResults(ctx, "https://music.youtube.com/search?q=${enc(query)}#community+playlists", limit)
        }.getOrDefault(emptyList())
        return (featured + community).distinctBy { it.id }.take(limit)
    }

    private fun playlistResults(ctx: Context, url: String, limit: Int): List<RemotePlaylist> {
        init(ctx)
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
            var id = e.str("id").removePrefix("VL")
            if (id.isEmpty()) id = e.str("url").substringAfter("list=", "").substringBefore('&')
            val title = e.str("title")
            // Skip videos, auto "Mix" radios and chart compilations; keep editorial RDCLAK playlists
            if (id.length < 13 || title.isEmpty()) continue
            if (id.startsWith("RD") && !id.startsWith("RDCLAK")) continue
            if (TOP_N.containsMatchIn(title)) continue
            val thumbs = e.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.startsWith("http") }
            val count = if (e.isNull("playlist_count")) 0 else e.optInt("playlist_count")
            val url = e.str("url").takeIf { it.startsWith("http") && it.contains("/browse/") }
            out += RemotePlaylist(id, title, artistOf(e).ifEmpty { "YouTube Music" }, thumb, count, url)
        }
        return out.distinctBy { it.id }
    }

    /** Songs in a public playlist or album. */
    fun remoteSongs(ctx: Context, r: RemotePlaylist, limit: Int = 100): List<Song> =
        if (r.url != null) flatList(ctx, r.url, limit) else playlistSongs(ctx, r.id, limit)

    // ---------------------------------------------------------------- artists

    fun artistSongs(ctx: Context, name: String): List<Song> {
        val all = musicSongs(ctx, name, 40)
        return all.filter { it.artist.contains(name, ignoreCase = true) }.ifEmpty { all }.take(25)
    }

    fun artistAlbums(ctx: Context, name: String): List<RemotePlaylist> =
        playlistResults(ctx, "https://music.youtube.com/search?q=${enc(name)}#albums", 15)
            .filter { it.channel.contains(name, ignoreCase = true) || it.channel == "YouTube Music" }

    fun artistPlaylists(ctx: Context, name: String): List<RemotePlaylist> = publicPlaylists(ctx, "$name", 12)

    /** The artist's profile picture from YouTube Music's "Artists" search. */
    fun artistThumb(ctx: Context, name: String): String? {
        init(ctx)
        val req = YoutubeDLRequest("https://music.youtube.com/search?q=${enc(name)}#artists").apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
            addOption("--playlist-end", "3")
        }
        val entries = JSONObject(run(req, "artist")).optJSONArray("entries") ?: return null
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val thumbs = e.optJSONArray("thumbnails") ?: continue
            val url = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.startsWith("http") } ?: continue
            return if (url.startsWith("//")) "https:$url" else url
        }
        return null
    }

    /** Best YouTube match for a song known only by title and artist (used for Spotify imports). */
    fun matchSong(ctx: Context, title: String, artist: String): Song? {
        val q = "$title $artist".trim()
        runCatching { musicSongs(ctx, q, 5).firstOrNull() }.getOrNull()?.let { return it }
        return runCatching { flatList(ctx, "ytsearch5:$q audio", 5).firstOrNull(::isSingleSong) }.getOrNull()
    }

    fun playlistSongs(ctx: Context, playlistId: String, limit: Int = 100): List<Song> =
        flatList(ctx, "https://music.youtube.com/playlist?list=$playlistId", limit)

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

    /** Direct audio URL for any page yt-dlp supports (SoundCloud, Bandcamp…). */
    fun directUrl(ctx: Context, page: String): String {
        init(ctx)
        val req = YoutubeDLRequest(page).apply {
            addOption("-f", "bestaudio/best")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--print", "%(url)s")
        }
        return run(req, "direct").lines().lastOrNull { it.startsWith("http") } ?: throw IOException("No audio found")
    }

    /** yt-dlp fallback for Audify's own downloader. */
    fun audioSource(ctx: Context, id: String, low: Boolean): AudioSource {
        init(ctx)
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id").apply {
            addOption("-f", if (low) "worstaudio[abr>=40]/worstaudio" else "bestaudio[ext=m4a]/bestaudio")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--print", "%(url)s\t%(http_headers.User-Agent)s\t%(ext)s")
        }
        val line = run(req, "src").lines().lastOrNull { it.startsWith("http") } ?: throw IOException("No audio found")
        val p = line.split('\t')
        val ext = if (p.getOrNull(2) == "m4a") "m4a" else "webm"
        return AudioSource(p[0], p.getOrNull(1)?.takeIf { it.isNotBlank() && it != "NA" }, ext, 0, "yt-dlp")
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
        fast: Boolean = true,
        altClients: Boolean = false,
        onProgress: (Float, String) -> Unit,
    ): File {
        init(ctx)
        val work = File(ctx.cacheDir, "dl_${System.nanoTime()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--windows-filenames")
                addOption("--no-mtime")
                addOption("--concurrent-fragments", "4")
                if (altClients) {
                    // Ask YouTube as different apps; helps when one is blocked for this user
                    addOption("--extractor-args", "youtube:player_client=tv,mweb,android_vr,web_safari")
                }
                if (fast) {
                    // aria2c opens several connections at once, which gets past YouTube's per-connection speed limit
                    addOption("--downloader", "libaria2c.so")
                    addOption("--external-downloader-args", "aria2c:-x 8 -s 8 -k 1M --summary-interval=1 --console-log-level=warn")
                } else {
                    addOption("--http-chunk-size", "10M")
                }
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

    // ---------------------------------------------------------------- yt-dlp updates

    private const val LATEST_PAGE = "https://github.com/yt-dlp/yt-dlp/releases/latest"
    private const val LATEST_BIN = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

    /** The yt-dlp file the library actually runs (inside the app's no-backup folder). */
    private fun ytdlpFile(ctx: Context): File? =
        ctx.noBackupFilesDir.walkTopDown().firstOrNull { it.isFile && it.name == "yt-dlp" }

    /** Latest release tag, read from GitHub's redirect (no API call, so no rate limit). */
    private fun latestTag(): String? {
        val c = java.net.URL(LATEST_PAGE).openConnection() as java.net.HttpURLConnection
        return try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Audify")
            c.getHeaderField("Location")?.substringAfterLast("/tag/", "")?.takeIf { it.isNotBlank() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File) {
        var current = url
        repeat(5) {   // follow redirects (github.com -> release asset host)
            val c = java.net.URL(current).openConnection() as java.net.HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "Audify")
                val code = c.responseCode
                if (code in 300..399) {
                    current = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                    return@repeat
                }
                if (code != 200) throw IOException("Download failed (HTTP $code)")
                c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                return
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun runningVersion(): String =
        YoutubeDL.getInstance().execute(
            YoutubeDLRequest("https://www.youtube.com").apply { addOption("--version") },
            "version-${System.nanoTime()}",
        ) { _, _, _ -> }.out.trim().lines().last().trim()

    fun lastUpdateCheck(ctx: Context): Long =
        ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE).getLong("checked", 0L)

    /**
     * Downloads the newest yt-dlp straight from its GitHub release and swaps it in,
     * keeping a backup and rolling back if the new one won't run.
     */
    @Synchronized
    fun updateYtDlp(ctx: Context, onStatus: (String) -> Unit = {}): String {
        onStatus("Preparing…")
        init(ctx)
        val sp = ctx.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val target = ytdlpFile(ctx)
            ?: return YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)?.toString() ?: "Updated"

        onStatus("Checking for updates…")
        val tag = runCatching { latestTag() }.getOrNull()
        if (tag != null && tag == sp.getString("tag", null) && target.length() > 0) {
            sp.edit().putLong("checked", System.currentTimeMillis()).apply()
            return "Already up to date ($tag)"
        }

        onStatus("Downloading the latest yt-dlp…")
        val tmp = File(ctx.cacheDir, "yt-dlp.new")
        try {
            download(LATEST_BIN, tmp)
            val head = tmp.inputStream().use { val b = ByteArray(2); it.read(b); String(b) }
            if (tmp.length() < 500_000 || (head != "#!" && head != "PK")) throw IOException("Downloaded file looks wrong")

            onStatus("Installing…")
            val backup = File(target.parentFile, "yt-dlp.bak")
            target.copyTo(backup, overwrite = true)
            tmp.copyTo(target, overwrite = true)

            val version = try {
                runningVersion()
            } catch (e: Exception) {
                backup.copyTo(target, overwrite = true)   // roll back
                throw IOException("New version didn't start, kept the old one", e)
            }
            backup.delete()
            sp.edit()
                .putString("tag", tag ?: version)
                .putLong("checked", System.currentTimeMillis())
                .apply()
            return "Updated to $version"
        } finally {
            tmp.delete()
        }
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
