package com.audify.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Imports YouTube Music (and YouTube) playlists and albums from their links,
 * e.g. music.youtube.com/playlist?list=… or youtube.com/playlist?list=…
 */
object YtMusicImport {
    data class Result(val name: String, val owner: String, val songs: List<Song>)

    private val LIST = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""")
    private val BROWSE = Regex("""/browse/(VL)?([A-Za-z0-9_-]{10,})""")
    private const val MAX_SONGS = 1000
    private val main = Handler(Looper.getMainLooper())

    private fun isYouTube(text: String) = text.contains("youtube.com/") || text.contains("youtu.be/")

    /** A link to a playlist or album page (not a single song that happens to be in a list). */
    fun isPlaylistLink(text: String): Boolean =
        isYouTube(text) && (text.contains("/playlist") || text.contains("/browse/")) && (LIST.containsMatchIn(text) || BROWSE.containsMatchIn(text))

    /** Any YouTube link carrying a playlist id, including watch?v=…&list=… (used in the import dialog). */
    fun hasPlaylist(text: String): Boolean = isYouTube(text) && (LIST.containsMatchIn(text) || BROWSE.containsMatchIn(text))

    private fun playlistId(link: String): String? =
        LIST.find(link)?.groupValues?.get(1)
            ?: BROWSE.find(link)?.let { m -> if (m.groupValues[1] == "VL") m.groupValues[2] else null }

    fun fetch(ctx: Context, link: String): Result {
        val id = playlistId(link)
        if (id == "LM" || id == "LL") {
            throw IOException("Liked Music is private. In YouTube Music, add those songs to a public or unlisted playlist and import that.")
        }
        // 1) NewPipeExtractor: fast, pages through big playlists
        if (id != null && !id.startsWith("RD")) {
            runCatching { viaNewPipe(id) }.getOrNull()?.takeIf { it.songs.isNotEmpty() }?.let { return it }
        }
        // 2) yt-dlp fallback (also handles album "browse" links)
        val url = if (id != null) "https://music.youtube.com/playlist?list=$id" else link
        return viaYtDlp(ctx, url)
    }

    private fun viaNewPipe(id: String): Result {
        NewPipeSource.ensureInit()
        val url = "https://www.youtube.com/playlist?list=$id"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = ArrayList<StreamInfoItem>(info.relatedItems)
        var page: Page? = info.nextPage
        while (Page.isValid(page) && items.size < MAX_SONGS) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            items += more.items
            page = more.nextPage
        }
        val songs = items.mapNotNull { item ->
            val vid = Downloader.videoIdFromUrl(item.url) ?: return@mapNotNull null
            Song(vid, item.name, (item.uploaderName ?: "").removeSuffix(" - Topic"), item.duration.toInt().coerceAtLeast(0))
        }.distinctBy { it.id }
        return Result(info.name ?: "YouTube playlist", (info.uploaderName ?: "").removeSuffix(" - Topic"), songs)
    }

    private fun viaYtDlp(ctx: Context, url: String): Result {
        Downloader.init(ctx)
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
            addOption("--playlist-end", MAX_SONGS.toString())
        }
        val o = JSONObject(YoutubeDL.getInstance().execute(req, "ytimport-${System.nanoTime()}") { _, _, _ -> }.out)
        val entries = o.optJSONArray("entries") ?: throw IOException("That playlist is empty or private")
        val songs = (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val vid = e.optString("id")
            val title = e.optString("title")
            if (vid.length != 11 || title.isBlank() || title == "null") return@mapNotNull null
            val artist = listOf("artist", "channel", "uploader").map { e.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: ""
            val dur = if (e.isNull("duration")) 0 else e.optDouble("duration", 0.0).toInt()
            Song(vid, title, artist.removeSuffix(" - Topic"), dur)
        }.distinctBy { it.id }
        if (songs.isEmpty()) throw IOException("That playlist is empty or private")
        val name = o.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "YouTube playlist"
        val owner = listOf("channel", "uploader").map { o.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: ""
        return Result(name, owner.removeSuffix(" - Topic"), songs)
    }

    /** Imports in the background, shows progress, then opens the new playlist. */
    fun start(ctx: Context, link: String) {
        if (Ui.importing != null) return
        val app = ctx.applicationContext
        Ui.importing = ImportProgress("Reading YouTube Music playlist…", 0, 0)
        thread(name = "yt-import") {
            try {
                val r = fetch(app, link.trim())
                main.post {
                    Ui.importing = null
                    val p = Store.createPlaylist(r.name, r.songs, owner = "YouTube Music" + if (r.owner.isNotBlank()) " · ${r.owner}" else "")
                    Ui.showYtDialog = false
                    Ui.tab = 2
                    Ui.detail = Detail.PlaylistView(p.id)
                    Ui.toast(app, "Imported ${r.songs.size} songs")
                }
            } catch (e: Exception) {
                main.post {
                    Ui.importing = null
                    Ui.toast(app, shortError(e))
                }
            }
        }
    }
}
