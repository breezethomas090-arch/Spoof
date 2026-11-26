package com.audify.app

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.Close
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun rememberPosition(): State<Long> {
    val s = remember { mutableLongStateOf(PlayerConn.position) }
    LaunchedEffect(Unit) {
        while (true) {
            s.longValue = PlayerConn.position
            delay(500)
        }
    }
    return s
}

/**
 * Swipe right = next song, swipe left = previous. The content follows your finger
 * and springs back.
 */
@Composable
fun Modifier.swipeToSkip(): Modifier {
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    return this
        .graphicsLayer {
            translationX = offset.value
            alpha = 1f - (kotlin.math.abs(offset.value) / (threshold * 4)).coerceIn(0f, 0.5f)
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val dx = offset.value
                    when {
                        dx > threshold -> PlayerConn.next()
                        dx < -threshold -> PlayerConn.previous()
                    }
                    scope.launch { offset.animateTo(0f, spring()) }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
            ) { change, dragAmount ->
                change.consume()
                scope.launch { offset.snapTo(offset.value + dragAmount) }
            }
        }
}

@Composable
private fun PlayPauseIcon(modifier: Modifier = Modifier) {
    Icon(
        if (PlayerConn.isPlaying) AppIcons.Pause else Icons.Rounded.PlayArrow,
        contentDescription = if (PlayerConn.isPlaying) "Pause" else "Play",
        modifier = modifier,
    )
}

/** Floating bar above the tabs. */
@Composable
fun MiniPlayer() {
    val item = PlayerConn.current ?: return
    val md = item.mediaMetadata
    val pos by rememberPosition()
    val dur = PlayerConn.duration
    val song = PlayerConn.currentSong
    val liked by Store.liked.collectAsStateWithLifecycle()
    val isLiked = song != null && liked.any { it.id == song.id }

    // Tinted with a colour from the cover art, like Spotify
    val artTint by rememberArtColor(md.artworkUri ?: song?.thumb, MaterialTheme.colorScheme.surfaceContainerHigh)
    val tint by animateColorAsState(artTint, label = "miniTint")

    Surface(
        color = tint,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().swipeToSkip().clickable { Ui.showPlayer = true }.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(md.artworkUri, Modifier.size(42.dp), RoundedCornerShape(6.dp), fallback = song?.thumb)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(md.title?.toString() ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        PlayerConn.error ?: (md.artist?.toString() ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (PlayerConn.error != null) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (song != null) {
                    IconButton(onClick = { Store.toggleLike(song) }) {
                        Icon(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isLiked) Pink else Color.White,
                        )
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (PlayerConn.isBuffering) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { PlayerConn.toggle() }) { PlayPauseIcon() }
                }
                IconButton(onClick = { PlayerConn.next() }) {
                    Icon(AppIcons.SkipNext, contentDescription = "Skip to next song")
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(
                    Modifier
                        .fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f)
                        .fillMaxHeight()
                        .background(Color.White),
                )
            }
        }
    }
}

/** Shuffle button with three states: off, shuffle, and Smart Shuffle (with a sparkle). */
@Composable
fun ShuffleButton(on: Color, off: Color, onClick: () -> Unit) {
    val mode = PlayerConn.shuffleMode
    IconButton(onClick = onClick) {
        Box {
            Icon(
                AppIcons.Shuffle,
                contentDescription = when (mode) {
                    PlayerConn.ShuffleMode.OFF -> "Shuffle off"
                    PlayerConn.ShuffleMode.NORMAL -> "Shuffle on"
                    PlayerConn.ShuffleMode.SMART -> "Smart Shuffle on"
                },
                tint = if (mode == PlayerConn.ShuffleMode.OFF) off else on,
            )
            if (mode == PlayerConn.ShuffleMode.SMART) {
                Text(
                    "✦",
                    color = Pink,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
                )
            }
        }
    }
}

/** What the DJ is saying, with "Switch it up" and stop. */
@Composable
fun DjPanel() {
    val ctx = LocalContext.current
    Surface(
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(BrandBrush),
                contentAlignment = Alignment.Center,
            ) { Text("DJ", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    Dj.sectionTitle ?: "Audify DJ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    (if (Dj.speaking) "🔊 " else "") + (Dj.line ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (Dj.building) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { Dj.switchItUp(ctx) }) { Text("Switch it up") }
            }
            IconButton(onClick = { Dj.stop() }) {
                Icon(Icons.Rounded.Close, contentDescription = "Stop DJ", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun NowPlayingScreen() {
    val md = PlayerConn.current?.mediaMetadata
    val song = PlayerConn.currentSong
    val liked by Store.liked.collectAsStateWithLifecycle()
    val isLiked = song != null && liked.any { it.id == song.id }
    val pos by rememberPosition()
    val dur = PlayerConn.duration
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val max = dur.toFloat().coerceAtLeast(1f)
    val shown = if (dragging) dragValue else pos.toFloat().coerceIn(0f, max)
    val art = md?.artworkUri
    val artTint by rememberArtColor(art ?: song?.thumb)
    val tint by animateColorAsState(artTint, label = "npTint")

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Color.Black)))) {
        // Soft blurred artwork glow behind everything (Android 12+)
        if (Build.VERSION.SDK_INT >= 31 && art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(90.dp).alpha(0.3f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black))
            )
        )

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Ui.showPlayer = false }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                LogoMark(18.dp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Ui.showEq = true }) {
                    Icon(
                        AppIcons.Equalizer,
                        contentDescription = "Equalizer",
                        tint = if (AudioFx.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { song?.let { Ui.menu = SongMenuTarget(it) } }, enabled = song != null) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                Artwork(
                    art,
                    Modifier
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .swipeToSkip()
                        .shadow(24.dp, RoundedCornerShape(16.dp)),
                    RoundedCornerShape(16.dp),
                    fallback = song?.thumb,
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(md?.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val artist = md?.artist?.toString() ?: ""
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Tap the artist to open their page
                        modifier = Modifier.clickable(enabled = artist.isNotBlank()) { Ui.openArtist(artist) },
                    )
                }
                if (song != null) {
                    IconButton(onClick = { Store.toggleLike(song) }) {
                        Icon(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isLiked) Pink else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            if (PlayerConn.current?.mediaId in PlayerConn.smartAdded) {
                Text(
                    "✦ Smart Shuffle pick",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (Dj.active || Dj.building) DjPanel()
            PlayerConn.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    PlayerConn.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..max,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtMs(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(fmtMs(dur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val on = MaterialTheme.colorScheme.primary
                val off = MaterialTheme.colorScheme.onSurfaceVariant
                val ctx = LocalContext.current
                ShuffleButton(on, off) {
                    val mode = PlayerConn.cycleShuffle()
                    Ui.toast(
                        ctx,
                        when (mode) {
                            PlayerConn.ShuffleMode.OFF -> "Shuffle off"
                            PlayerConn.ShuffleMode.NORMAL -> "Shuffle on"
                            PlayerConn.ShuffleMode.SMART -> "Smart Shuffle on: adding songs you might like"
                        },
                    )
                }
                IconButton(onClick = { PlayerConn.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(34.dp))
                }
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    GradientPlayButton(72.dp, PlayerConn.isPlaying) { PlayerConn.toggle() }
                    if (PlayerConn.isBuffering) CircularProgressIndicator(Modifier.size(72.dp), color = Color.White, strokeWidth = 2.dp)
                }
                IconButton(onClick = { PlayerConn.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipNext, contentDescription = "Next", modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { PlayerConn.cycleRepeat() }) {
                    Icon(
                        if (PlayerConn.repeatMode == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                        contentDescription = "Repeat",
                        tint = if (PlayerConn.repeatMode == Player.REPEAT_MODE_OFF) off else on,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (song != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    DownloadAction(song)
                    IconButton(onClick = { Ui.addToPlaylist = listOf(song) }) {
                        Icon(AppIcons.PlaylistAdd, contentDescription = "Add to playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Ui.shareSong(song) }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
