package com.audify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/** Cover art with an optional fallback image and a music-note placeholder. */
@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    fallback: Any? = null,
) {
    var src by remember(model) { mutableStateOf(model) }
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxSize(0.4f),
        )
        if (src != null) {
            AsyncImage(
                model = src,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { if (fallback != null && src != fallback) src = fallback },
            )
        }
    }
}

@Composable
fun SongArt(song: Song, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp), large: Boolean = false) {
    val local = Library.byId(song.id)?.cover
    Artwork(
        model = local ?: if (large) song.thumbLarge else song.thumb,
        modifier = modifier,
        shape = shape,
        fallback = song.thumb,
    )
}

/** Download button / progress / done, for one song. */
@Composable
fun DownloadAction(song: Song) {
    val ctx = LocalContext.current
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val state = jobs[song.id]?.state
    val downloaded = tracks.any { it.videoId == song.id }
    when {
        downloaded -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(AppIcons.DownloadDone, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        state is DlState.Running || state == DlState.Queued -> {
            val pct = (state as? DlState.Running)?.percent ?: -1f
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (pct in 0f..100f) {
                    CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp)
                }
                IconButton(onClick = { Downloads.cancel(song.id) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
                }
            }
        }
        else -> IconButton(onClick = { Downloads.enqueue(ctx, song) }) {
            Icon(
                AppIcons.Download,
                contentDescription = "Download",
                tint = if (state is DlState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    showDownload: Boolean = true,
    playlistId: String? = null,
) {
    val isCurrent = PlayerConn.currentSong?.id == song.id
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArt(song, Modifier.size(50.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    song.artist.ifBlank { null },
                    song.durationSec.takeIf { it > 0 }?.let { fmtSec(it) },
                    song.sourceLabel.takeIf { !song.isYouTube },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showDownload) DownloadAction(song)
        IconButton(onClick = { Ui.menu = SongMenuTarget(song, playlistId) }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Square card for horizontal rows. */
@Composable
fun SongCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp)) {
        SongArt(song, Modifier.size(124.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.height(8.dp))
        Text(song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Big round gradient play button. */
@Composable
fun GradientPlayButton(size: Dp, playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(BrandBrush).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) AppIcons.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Coloured square tile with an icon (Liked songs, Downloads, playlists without art). */
@Composable
fun IconTile(icon: ImageVector, size: Dp, brush: Brush? = null) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (brush != null) Modifier.background(brush)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

fun statusText(s: DlState.Running): String = when {
    listOf("ExtractAudio", "Metadata", "Thumbnails").any { s.line.contains(it) } -> "Converting…"
    s.percent in 0f..99.9f -> "Downloading ${s.percent.toInt()}%"
    s.percent >= 100f -> "Processing…"
    else -> "Starting…"
}

fun fmtMs(ms: Long): String = fmtSec((ms / 1000).toInt())

fun fmtSec(s: Int): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)

// ============================================================== playlist covers & avatars

private val COVER_GRADIENTS = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
    listOf(Color(0xFF0EA5E9), Color(0xFF4F46E5)),
    listOf(Color(0xFFF97316), Color(0xFFE11D48)),
    listOf(Color(0xFF10B981), Color(0xFF0F766E)),
    listOf(Color(0xFFF59E0B), Color(0xFFB45309)),
    listOf(Color(0xFF64748B), Color(0xFF1E293B)),
)

/**
 * Playlist cover: your custom image if you set one, otherwise auto-generated from its songs
 * (a 2×2 mosaic with 4+ songs, the first song's art with 1–3, or a gradient with an icon when empty).
 */
@Composable
fun PlaylistCover(p: Playlist, size: Dp, shape: Shape = RoundedCornerShape(8.dp)) {
    if (p.cover != null) {
        Artwork(java.io.File(p.cover), Modifier.size(size), shape)
        return
    }
    val songs = p.songs.filter { it.title != "Loading…" }.distinctBy { it.id }
    when {
        songs.size >= 4 -> Column(Modifier.size(size).clip(shape)) {
            for (row in 0..1) Row(Modifier.weight(1f)) {
                for (col in 0..1) SongArt(songs[row * 2 + col], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp))
            }
        }
        songs.isNotEmpty() -> SongArt(songs.first(), Modifier.size(size), shape)
        else -> {
            val colors = COVER_GRADIENTS[(p.name.hashCode() and 0x7fffffff) % COVER_GRADIENTS.size]
            Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Playlist, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.4f))
            }
        }
    }
}

/** Your profile picture, or your initial on a gradient. */
@Composable
fun Avatar(size: Dp, modifier: Modifier = Modifier) {
    val path = Profile.avatar
    if (path != null) {
        Artwork(java.io.File(path), modifier.size(size), CircleShape)
    } else {
        Box(modifier.size(size).clip(CircleShape).background(BrandBrush), contentAlignment = Alignment.Center) {
            Text(
                Profile.name.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = if (size >= 80.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
            )
        }
    }
}
