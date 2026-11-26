package com.audify.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

sealed interface Detail {
    data object Liked : Detail
    data object Downloaded : Detail
    data class PlaylistView(val id: String) : Detail
    data class Remote(val playlist: RemotePlaylist) : Detail
    data object Account : Detail
    data class Artist(val name: String) : Detail
}

data class ImportProgress(val name: String, val done: Int, val total: Int)

data class SongMenuTarget(val song: Song, val playlistId: String? = null)

data class ShareTarget(
    val title: String,
    val subtitle: String,
    val qrContent: String,
    val code: String?,
    val shareText: String,
)

/** App-wide UI state, so any screen can open the player, menus, sheets and dialogs. */
object Ui {
    var setupDone by mutableStateOf(false)      // first-launch yt-dlp update finished (or skipped)
    var tab by mutableIntStateOf(0)            // 0 Home, 1 Search, 2 Library
    /** Pages opened on top of a tab, newest last, so Back goes to the previous page. */
    val stack = mutableStateListOf<Detail>()

    /** Setting a page opens it on top; setting null closes them all (e.g. switching tabs). */
    var detail: Detail?
        get() = stack.lastOrNull()
        set(v) {
            if (v == null) stack.clear() else if (stack.lastOrNull() != v) stack.add(v)
        }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
    }

    fun openArtist(name: String) {
        val primary = primaryArtist(name)
        if (primary.isBlank()) return
        showPlayer = false
        detail = Detail.Artist(primary)
    }

    var importing by mutableStateOf<ImportProgress?>(null)
    var showSpotifyDialog by mutableStateOf(false)
    var showYtDialog by mutableStateOf(false)
    var showPlayer by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showEq by mutableStateOf(false)
    var showCodeDialog by mutableStateOf(false)
    var menu by mutableStateOf<SongMenuTarget?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var newPlaylistFor by mutableStateOf<List<Song>?>(null)   // non-null = "create playlist" dialog open
    var share by mutableStateOf<ShareTarget?>(null)
    var busy by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())

    fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    fun shareSong(s: Song) {
        if (!s.isYouTube) {
            // Songs from other platforms are shared by their page link
            share = ShareTarget(
                title = s.title,
                subtitle = "${s.artist} · ${s.sourceLabel}",
                qrContent = s.url,
                code = null,
                shareText = "🎵 ${Profile.name} shared ${s.title} — ${s.artist} (${s.sourceLabel})\n${s.url}",
            )
            return
        }
        share = ShareTarget(
            title = s.title,
            subtitle = s.artist,
            qrContent = AudifyCodes.songUri(s.id),
            code = AudifyCodes.songCode(s.id),
            shareText = "🎵 ${Profile.name} shared ${s.title}${if (s.artist.isNotBlank()) " — ${s.artist}" else ""}\n" +
                "Open it in Audify with code ${AudifyCodes.songCode(s.id)}\n${s.url}",
        )
    }

    fun sharePlaylist(p: Playlist) {
        val uri = AudifyCodes.playlistUri(p, Profile.name)
        share = ShareTarget(
            title = p.name,
            subtitle = "${p.songs.size} songs",
            qrContent = uri,
            code = null,
            shareText = "🎧 \"${p.name}\" by ${Profile.name} on Audify (${p.songs.size} songs)\n" +
                "Paste this into Audify → Enter code:\n$uri",
        )
    }

    /** Opens an Audify code, QR content, or YouTube link. Returns false if it wasn't recognised. */
    fun openCode(ctx: Context, text: String): Boolean {
        if (SpotifyImport.isSpotifyLink(text)) {
            SpotifyImport.start(ctx, text.trim())
            return true
        }
        if (YtMusicImport.isPlaylistLink(text)) {
            YtMusicImport.start(ctx, text.trim())
            return true
        }
        val parsed = AudifyCodes.parse(text)
        if (parsed == null) {
            toast(ctx, "That isn't an Audify code or YouTube link")
            return false
        }
        val app = ctx.applicationContext
        when (parsed) {
            is AudifyCodes.Parsed.Songs -> {
                busy = "Opening…"
                thread {
                    val songs = runCatching { Downloader.resolveSongs(app, parsed.ids) }.getOrDefault(emptyList())
                    main.post {
                        busy = null
                        if (songs.isEmpty()) {
                            toast(app, "Couldn't find that song")
                        } else {
                            PlayerConn.playSongs(songs, 0)
                            showPlayer = true
                        }
                    }
                }
            }
            is AudifyCodes.Parsed.Page -> {
                busy = "Opening…"
                thread {
                    val song = runCatching { OtherSources.songFromPage(parsed.url) }.getOrNull()
                    main.post {
                        busy = null
                        if (song == null) {
                            toast(app, "Couldn't open that link")
                        } else {
                            PlayerConn.playSongs(listOf(song), 0)
                            showPlayer = true
                        }
                    }
                }
            }
            is AudifyCodes.Parsed.SharedPlaylist -> {
                val p = Store.createPlaylist(parsed.name, parsed.ids.map { Song(it, "Loading…", "") }, owner = parsed.by ?: "a friend")
                tab = 2
                detail = Detail.PlaylistView(p.id)
                toast(app, "Added \"${p.name}\" to your library")
                thread {
                    val songs = runCatching { Downloader.resolveSongs(app, parsed.ids) }.getOrDefault(emptyList())
                    Store.updateSongs(songs.associateBy { it.id })
                }
            }
        }
        return true
    }
}

/** "Artist A, Artist B feat. C" -> "Artist A". */
fun primaryArtist(name: String): String =
    name.split(",", " & ", " feat.", " feat ", " ft.", " ft ", " x ", " X ", " with ").first()
        .removeSuffix(" - Topic").trim()
