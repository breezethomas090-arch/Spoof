package com.audify.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Imports Spotify playlists, albums and tracks from their share links.
 *
 * Linking a Spotify account needs a registered Spotify developer app, so instead this reads
 * the public "embed" page Spotify serves for any public link (no login), then finds each
 * song on YouTube Music.
 */
object SpotifyImport {
    data class Track(val title: String, val artist: String)
    data class Collection(val type: String, val name: String, val owner: String, val tracks: List<Track>)

    private val LINK = Regex("""open\.spotify\.com/(?:intl-[a-z]{2}(?:-[A-Za-z]{2})?/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]+)""")
    private val main = Handler(Looper.getMainLooper())

    fun isSpotifyLink(text: String) = LINK.containsMatchIn(text)

    /** Reads the playlist/album/track behind a Spotify link. */
    fun fetch(link: String): Collection {
        val m = LINK.find(link) ?: throw IOException("That isn't a Spotify playlist, album or song link")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val html = get("https://open.spotify.com/embed/$type/$id")
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IOException("Spotify didn't return the playlist. Is it public?")
        val root = JSONObject(json)
        val entity = findEntity(root) ?: throw IOException("Couldn't read that Spotify link")

        val name = entity.optString("name").ifEmpty { entity.optString("title") }.ifEmpty { "Spotify $type" }
        val owner = entity.optString("subtitle").clean()
        val list = entity.optJSONArray("trackList")
        val tracks = if (list != null) {
            (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title").ifEmpty { t.optString("name") }
                if (title.isEmpty()) null else Track(title, t.optString("subtitle").clean())
            }
        } else {
            // A single track
            val artists = entity.optJSONArray("artists")
            val artist = if (artists != null) {
                (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(", ")
            } else owner
            listOf(Track(name, artist))
        }
        if (tracks.isEmpty()) throw IOException("No songs found in that link")
        return Collection(type, name, owner, tracks)
    }

    /** Finds the object holding the playlist/album/track data anywhere in Spotify's page JSON. */
    private fun findEntity(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has("trackList")) return node
                node.optJSONObject("entity")?.let { e -> if (e.has("name") || e.has("title")) return e }
                val keys = node.keys()
                while (keys.hasNext()) findEntity(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findEntity(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun String.clean() = replace(' ', ' ').trim()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode != 200) throw IOException("Spotify returned an error (${c.responseCode})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Imports a link in the background: shows progress in [Ui.importing], then opens the
     * new playlist (or plays the song, for a single track).
     */
    fun start(ctx: Context, link: String) {
        if (Ui.importing != null) return
        val app = ctx.applicationContext
        Ui.importing = ImportProgress("Reading Spotify link…", 0, 0)
        thread(name = "spotify-import") {
            try {
                val col = fetch(link)
                post { Ui.importing = ImportProgress(col.name, 0, col.tracks.size) }

                // Match songs three at a time, keeping the original order
                val results = arrayOfNulls<Song>(col.tracks.size)
                val done = AtomicInteger(0)
                val pool = Executors.newFixedThreadPool(3)
                col.tracks.forEachIndexed { i, t ->
                    pool.execute {
                        results[i] = runCatching { Downloader.matchSong(app, t.title, t.artist) }.getOrNull()
                            ?.let { s -> s.copy(title = t.title, artist = t.artist.ifEmpty { s.artist }) }
                        val n = done.incrementAndGet()
                        post { Ui.importing = ImportProgress(col.name, n, col.tracks.size) }
                    }
                }
                pool.shutdown()
                pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)

                val songs = results.filterNotNull().distinctBy { it.id }
                val missed = col.tracks.size - songs.size
                post {
                    Ui.importing = null
                    when {
                        songs.isEmpty() -> Ui.toast(app, "Couldn't find any of those songs")
                        col.type == "track" -> {
                            PlayerConn.playSongs(songs, 0)
                            Ui.showPlayer = true
                        }
                        else -> {
                            val owner = if (col.type == "album") col.owner else "Spotify${if (col.owner.isNotBlank()) " · ${col.owner}" else ""}"
                            val p = Store.createPlaylist(col.name, songs, owner = owner)
                            Ui.showSpotifyDialog = false
                            Ui.tab = 2
                            Ui.detail = Detail.PlaylistView(p.id)
                            Ui.toast(app, "Imported ${songs.size} songs" + if (missed > 0) " ($missed not found)" else "")
                        }
                    }
                }
            } catch (e: Exception) {
                post {
                    Ui.importing = null
                    Ui.toast(app, shortError(e))
                }
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
