package com.audify.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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

/** A song on YouTube, identified by its video id. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int = 0,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumb get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val thumbLarge get() = "https://i.ytimg.com/vi/$id/hq720.jpg"

    /** A media item that streams from YouTube (resolved to a real URL when ExoPlayer loads it). */
    fun toStreamItem(): MediaItem = MediaItem.Builder()
        .setMediaId("yt:$id")
        .setUri("ytid:$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifEmpty { null })
                .setArtworkUri(Uri.parse(thumbLarge))
                .build()
        )
        .build()

    fun toJson(): JSONObject = JSONObject().put("i", id).put("t", title).put("a", artist).put("d", durationSec)

    companion object {
        fun fromJson(o: JSONObject) = Song(o.optString("i"), o.optString("t"), o.optString("a"), o.optInt("d"))
    }
}

data class Playlist(val id: String, val name: String, val songs: List<Song>)

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
                pl.optJSONObject(i)?.let { Playlist(it.optString("id"), it.optString("name"), it.optJSONArray("songs").toSongs()) }
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

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "My playlist" }, songs.distinctBy { it.id })
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

    fun deletePlaylist(playlistId: String) {
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
        data class SharedPlaylist(val name: String, val ids: List<String>) : Parsed
    }

    fun songCode(id: String) = "AU-$id"
    fun songUri(id: String) = "audify://s/$id"
    fun playlistUri(p: Playlist) =
        "audify://p/" + Uri.encode(p.name.take(60)) + "/" + p.songs.take(150).joinToString(",") { it.id }

    fun parse(text: String): Parsed? {
        val t = text.trim()
        SONG_CODE.find(t)?.let { return Parsed.Songs(listOf(it.groupValues[1])) }
        if (t.startsWith("audify://p/")) {
            val rest = t.removePrefix("audify://p/")
            val name = Uri.decode(rest.substringBefore('/'))
            val ids = rest.substringAfter('/', "").split(',').filter { ID.matches(it) }
            return if (ids.isEmpty()) null else Parsed.SharedPlaylist(name.ifBlank { "Shared playlist" }, ids)
        }
        if (t.startsWith("audify://s/")) {
            val ids = t.removePrefix("audify://s/").split(',').filter { ID.matches(it) }
            return if (ids.isEmpty()) null else Parsed.Songs(ids)
        }
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

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var keepVideo: Boolean
        get() = sp.getBoolean("keepVideo", false)
        set(v) = sp.edit().putBoolean("keepVideo", v).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString("quality", null) ?: "") }.getOrDefault(Quality.BEST)
        set(v) = sp.edit().putString("quality", v.name).apply()
}
