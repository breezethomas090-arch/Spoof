package com.audify.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A song from any platform.
 *  - YouTube: [id] is the 11-character video id.
 *  - SoundCloud / Bandcamp / Audius: [id] is "sc:…", "bc:…" or "au:…", [page] is its web page
 *    and [art] its cover image.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int = 0,
    val source: String = "yt",
    val page: String? = null,
    val art: String? = null,
) {
    val isYouTube get() = source == "yt"
    val url get() = if (isYouTube) "https://www.youtube.com/watch?v=$id" else page ?: ""
    val thumb get() = art ?: if (isYouTube) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else ""
    val thumbLarge get() = art ?: if (isYouTube) "https://i.ytimg.com/vi/$id/hq720.jpg" else ""
    val sourceLabel get() = Sources.label(source)

    /** A media item that streams (resolved to a real URL when it's about to play). */
    fun toStreamItem(): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifEmpty { null })
            .setArtworkUri(thumbLarge.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) })
            .build()
        return if (isYouTube) {
            MediaItem.Builder().setMediaId("yt:$id").setUri("ytid:$id").setMediaMetadata(md).build()
        } else {
            ExtSongs.remember(this)
            MediaItem.Builder().setMediaId("ext:$id").setUri("ext:$id").setMediaMetadata(md).build()
        }
    }

    fun toJson(): JSONObject = JSONObject().put("i", id).put("t", title).put("a", artist).put("d", durationSec).apply {
        if (!isYouTube) {
            put("s", source)
            put("p", page ?: "")
            put("r", art ?: "")
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = Song(
            o.optString("i"), o.optString("t"), o.optString("a"), o.optInt("d"),
            o.optString("s").ifEmpty { "yt" }, o.optString("p").ifEmpty { null }, o.optString("r").ifEmpty { null },
        )
    }
}

/** Non-YouTube songs currently in the queue, so the player can look up their pages. */
object ExtSongs {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Song>()
    fun remember(s: Song) {
        map[s.id] = s
    }
    fun get(id: String): Song? = map[id]
}

object Sources {
    fun label(source: String) = when (source) {
        "sc" -> "SoundCloud"
        "bc" -> "Bandcamp"
        "au" -> "Audius"
        else -> "YouTube"
    }
}

data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song>,
    val cover: String? = null,   // custom image file, or null for an auto-generated cover
    val owner: String? = null,   // who shared it (null = you)
)

/** A public playlist on YouTube (other people's playlists). */
data class RemotePlaylist(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String?,
    val count: Int,
    val url: String? = null,    // for albums opened by URL
)

data class ArtistPage(
    val name: String,
    val thumb: String?,
    val songs: List<Song>,
    val albums: List<RemotePlaylist>,
    val playlists: List<RemotePlaylist>,
)

/** Liked songs, playlists and listening history, saved to a small JSON file. */
object Store {
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()

    private val _liked = MutableStateFlow<List<Song>>(emptyList())
    val liked: StateFlow<List<Song>> = _liked.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _history = MutableStateFlow<List<Song>>(emptyList())
    val history: StateFlow<List<Song>> = _history.asStateFlow()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "audify.json")
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            _liked.value = o.optJSONArray("liked").toSongs()
            _history.value = o.optJSONArray("history").toSongs()
            val pl = o.optJSONArray("playlists") ?: JSONArray()
            _playlists.value = (0 until pl.length()).mapNotNull { i ->
                pl.optJSONObject(i)?.let {
                    Playlist(
                        it.optString("id"), it.optString("name"), it.optJSONArray("songs").toSongs(),
                        it.optString("cover").ifEmpty { null }, it.optString("owner").ifEmpty { null },
                    )
                }
            }
        }
    }

    private fun save() {
        val o = JSONObject()
            .put("liked", JSONArray(_liked.value.map { it.toJson() }))
            .put("history", JSONArray(_history.value.map { it.toJson() }))
            .put(
                "playlists",
                JSONArray(_playlists.value.map {
                    JSONObject().put("id", it.id).put("name", it.name).put("songs", JSONArray(it.songs.map { s -> s.toJson() }))
                        .put("cover", it.cover ?: "").put("owner", it.owner ?: "")
                })
            )
        val text = o.toString()
        io.execute {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    fun isLiked(id: String) = _liked.value.any { it.id == id }

    fun toggleLike(s: Song) {
        _liked.update { l -> if (l.any { it.id == s.id }) l.filterNot { it.id == s.id } else listOf(s) + l }
        save()
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList(), owner: String? = null): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "My playlist" }, songs.distinctBy { it.id }, owner = owner)
        _playlists.update { it + p }
        save()
        return p
    }

    fun addToPlaylist(playlistId: String, songs: List<Song>) {
        _playlists.update { list ->
            list.map { p ->
                if (p.id != playlistId) p
                else p.copy(songs = p.songs + songs.filter { s -> p.songs.none { it.id == s.id } })
            }
        }
        save()
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        _playlists.update { list -> list.map { if (it.id == playlistId) it.copy(songs = it.songs.filterNot { s -> s.id == songId }) else it } }
        save()
    }

    fun renamePlaylist(playlistId: String, name: String) {
        _playlists.update { list -> list.map { if (it.id == playlistId) it.copy(name = name.trim().ifEmpty { it.name }) else it } }
        save()
    }

    fun setPlaylistCover(playlistId: String, path: String?) {
        _playlists.update { list ->
            list.map {
                if (it.id != playlistId) it
                else {
                    if (it.cover != null && it.cover != path) runCatching { File(it.cover).delete() }
                    it.copy(cover = path)
                }
            }
        }
        save()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value.firstOrNull { it.id == playlistId }?.cover?.let { runCatching { File(it).delete() } }
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
        save()
    }

    fun addHistory(s: Song) {
        if (_history.value.firstOrNull()?.id == s.id) return
        _history.update { (listOf(s) + it.filterNot { x -> x.id == s.id }).take(60) }
        save()
    }

    /** Fill in titles for songs imported from a code (which only carry ids). */
    fun updateSongs(resolved: Map<String, Song>) {
        if (resolved.isEmpty()) return
        fun fix(s: Song) = resolved[s.id] ?: s
        _playlists.update { list -> list.map { p -> p.copy(songs = p.songs.map(::fix)) } }
        _liked.update { l -> l.map(::fix) }
        save()
    }

    private fun JSONArray?.toSongs(): List<Song> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(Song::fromJson) }.filter { it.id.isNotEmpty() }
    }
}

/**
 * Audify codes:
 *   song      AU-dQw4w9WgXcQ           (also as QR: audify://s/dQw4w9WgXcQ)
 *   playlist  audify://p/<name>/<id,id,...>   (QR)
 * YouTube links and bare video ids are accepted too.
 */
object AudifyCodes {
    private val ID = Regex("[A-Za-z0-9_-]{11}")
    private val SONG_CODE = Regex("""^[Aa][Uu]-?([A-Za-z0-9_-]{11})$""")

    sealed interface Parsed {
        data class Songs(val ids: List<String>) : Parsed
        data class SharedPlaylist(val name: String, val ids: List<String>, val by: String?) : Parsed
        data class Page(val url: String) : Parsed
    }

    fun songCode(id: String) = "AU-$id"
    fun songUri(id: String) = "audify://s/$id"
    fun playlistUri(p: Playlist, by: String) =
        "audify://p/" + Uri.encode(p.name.take(40)) + "/" + p.songs.filter { it.isYouTube }.take(80).joinToString(",") { it.id } +
            "?by=" + Uri.encode(by.take(30))

    fun parse(text: String): Parsed? {
        val t = text.trim()
        SONG_CODE.find(t)?.let { return Parsed.Songs(listOf(it.groupValues[1])) }
        if (t.startsWith("audify://p/")) {
            val rest = t.removePrefix("audify://p/")
            val name = Uri.decode(rest.substringBefore('/'))
            val tail = rest.substringAfter('/', "")
            val ids = tail.substringBefore('?').split(',').filter { ID.matches(it) }
            val by = tail.substringAfter("?by=", "").let { Uri.decode(it) }.ifBlank { null }
            return if (ids.isEmpty()) null else Parsed.SharedPlaylist(name.ifBlank { "Shared playlist" }, ids, by)
        }
        if (t.startsWith("audify://s/")) {
            val ids = t.removePrefix("audify://s/").split(',').filter { ID.matches(it) }
            return if (ids.isEmpty()) null else Parsed.Songs(ids)
        }
        if (OtherSources.isSupportedPage(t)) return Parsed.Page(t)
        Downloader.videoIdFromUrl(t)?.let { return Parsed.Songs(listOf(it)) }
        if (ID.matches(t)) return Parsed.Songs(listOf(t))
        return null
    }
}

enum class Quality(val label: String, val detail: String) {
    BEST("Best · original", "No re-encoding: Opus/M4A exactly as YouTube serves it (~130–160 kbps)."),
    FLAC("Lossless · FLAC", "A bit-perfect copy of YouTube's audio in FLAC. Larger files, same sound."),
    MP3_320("MP3 · 320 kbps", "Plays on any device or car stereo. Large files."),
    MP3_192("MP3 · 192 kbps", "Plays anywhere. Good balance of size and quality."),
    SAVER("Data saver", "Opus ~64 kbps. Smallest files, and lighter streaming."),
}

enum class StreamQuality(val label: String, val detail: String) {
    LOW("Data saver", "~50 kbps. Starts fastest, uses least data."),
    NORMAL("Normal", "~70 kbps Opus. Quick to start, sounds great on phone speakers and earbuds."),
    HIGH("High", "~130 kbps. Best sound, slightly slower to start."),
}

/** Your local Audify profile: name and picture, stored on this phone. */
object Profile {
    private lateinit var sp: SharedPreferences
    var name by mutableStateOf("Listener")
        private set
    var avatar by mutableStateOf<String?>(null)
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)
        name = sp.getString("name", null) ?: "Listener"
        avatar = sp.getString("avatar", null)?.takeIf { File(it).exists() }
    }

    fun changeName(n: String) {
        name = n.trim().take(30).ifEmpty { name }
        sp.edit().putString("name", name).apply()
    }

    fun changeAvatar(path: String?) {
        avatar?.takeIf { it != path }?.let { runCatching { File(it).delete() } }
        avatar = path
        sp.edit().putString("avatar", path).apply()
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var djVoice: Boolean
        get() = sp.getBoolean("djVoice", true)
        set(v) = sp.edit().putBoolean("djVoice", v).apply()

    /** Optional Anthropic API key so Claude writes what the DJ says. Stored only on this phone. */
    var claudeKey: String
        get() = sp.getString("claudeKey", "") ?: ""
        set(v) = sp.edit().putString("claudeKey", v.trim()).apply()

    var quickFirst: Boolean
        get() = sp.getBoolean("quickFirst", true)
        set(v) = sp.edit().putBoolean("quickFirst", v).apply()

    var fastDownloads: Boolean
        get() = sp.getBoolean("fastDownloads", true)
        set(v) = sp.edit().putBoolean("fastDownloads", v).apply()

    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit().putBoolean("setupDone", v).apply()

    var keepVideo: Boolean
        get() = sp.getBoolean("keepVideo", false)
        set(v) = sp.edit().putBoolean("keepVideo", v).apply()

    var streamQuality: StreamQuality
        get() = runCatching { StreamQuality.valueOf(sp.getString("streamQuality", null) ?: "") }.getOrDefault(StreamQuality.NORMAL)
        set(v) = sp.edit().putString("streamQuality", v.name).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString("quality", null) ?: "") }.getOrDefault(Quality.BEST)
        set(v) = sp.edit().putString("quality", v.name).apply()
}

/** App version shown in Settings (read from the installed package). */
object BuildConfigVersion {
    var name: String = ""
        private set

    fun init(ctx: Context) {
        name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
}
