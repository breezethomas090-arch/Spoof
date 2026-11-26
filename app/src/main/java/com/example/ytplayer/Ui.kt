package com.example.ytplayer

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

// ============================================================== root

@Composable
fun AppRoot(tab: MutableIntState) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    var showPlayer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (PlayerConn.current != null) MiniPlayer(onOpen = { showPlayer = true })
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab.intValue == 0,
                            onClick = { tab.intValue = 0 },
                            icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            label = { Text("Search") },
                        )
                        NavigationBarItem(
                            selected = tab.intValue == 1,
                            onClick = { tab.intValue = 1 },
                            icon = {
                                BadgedBox(badge = { if (jobs.isNotEmpty()) Badge { Text("${jobs.size}") } }) {
                                    Icon(AppIcons.Library, contentDescription = null)
                                }
                            },
                            label = { Text("Library") },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                when (tab.intValue) {
                    0 -> SearchScreen(tracks, jobs, onSettings = { showSettings = true })
                    else -> LibraryScreen(tracks, jobs, onSettings = { showSettings = true })
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && PlayerConn.current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(onClose = { showPlayer = false })
        }
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }
    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

// ============================================================== search

fun isUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") ||
    s.contains("youtu.be/") || s.contains("youtube.com/")

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchResult>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var seq = 0

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || isUrl(q)) return
        val mine = ++seq
        loading = true
        error = null
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Downloader.search(getApplication(), q) } }
            if (mine != seq) return@launch
            loading = false
            r.onSuccess {
                results = it
                if (it.isEmpty()) error = "No results for \"$q\""
            }.onFailure { error = shortError(it) }
        }
    }
}

@Composable
fun SearchScreen(
    tracks: List<Track>,
    jobs: Map<String, DlJob>,
    onSettings: () -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val downloadedIds = remember(tracks) { tracks.mapNotNull { it.videoId }.toSet() }
    val q = vm.query.trim()

    fun downloadLink() {
        Downloads.enqueue(ctx, q, q)
        vm.query = ""
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        Header("Search", onSettings)
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Songs, artists or a YouTube link") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                if (isUrl(q)) downloadLink() else vm.search()
            }),
        )
        Spacer(Modifier.height(8.dp))

        when {
            isUrl(q) -> LinkCard(q, onDownload = { downloadLink() })
            vm.loading -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (Downloader.ready) "Searching YouTube…" else "Getting ready — the first search takes a little longer…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            vm.error != null -> Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(vm.error ?: "", style = MaterialTheme.typography.bodyMedium)
            }
            vm.results.isEmpty() -> EmptyState(
                AppIcons.MusicNote,
                "Find any song",
                "Search YouTube, then tap the download button to save it as an MP3.",
            )
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(vm.results, key = { it.id }) { r ->
                ResultRow(
                    r = r,
                    state = jobs[r.id]?.state,
                    downloaded = r.id in downloadedIds,
                    onDownload = { Downloads.enqueue(ctx, r.url, r.title, r.id) },
                    onCancel = { Downloads.cancel(r.id) },
                    onPlay = {
                        val i = tracks.indexOfFirst { it.videoId == r.id }
                        if (i >= 0) PlayerConn.playTracks(tracks, i)
                    },
                )
            }
        }
    }
}

@Composable
fun ResultRow(
    r: SearchResult,
    state: DlState?,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    downloaded -> onPlay()
                    state == null || state is DlState.Failed -> onDownload()
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = r.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            if (r.durationSec > 0) {
                Text(
                    fmtSec(r.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                r.channel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val small = MaterialTheme.typography.labelSmall
            when (state) {
                is DlState.Running -> Text(statusText(state), style = small, color = MaterialTheme.colorScheme.primary)
                is DlState.Failed -> Text(state.message, style = small, color = MaterialTheme.colorScheme.error, maxLines = 2)
                DlState.Queued -> Text("Queued", style = small, color = MaterialTheme.colorScheme.primary)
                null -> if (downloaded) Text("In your library", style = small, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(4.dp))
        when {
            state is DlState.Running || state == DlState.Queued -> {
                val pct = (state as? DlState.Running)?.percent ?: -1f
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (pct in 0f..100f) {
                        CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                    }
                }
            }
            downloaded -> FilledTonalIconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play")
            }
            state is DlState.Failed -> IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Retry", tint = MaterialTheme.colorScheme.error)
            }
            else -> IconButton(onClick = onDownload) {
                Icon(AppIcons.Download, contentDescription = "Download")
            }
        }
    }
}

@Composable
fun LinkCard(url: String, onDownload: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YouTube link", style = MaterialTheme.typography.titleSmall)
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onDownload) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download")
            }
        }
    }
}

fun statusText(s: DlState.Running): String = when {
    listOf("ExtractAudio", "Metadata", "Thumbnails").any { s.line.contains(it) } -> "Converting to MP3…"
    s.percent in 0f..99.9f -> "Downloading ${s.percent.toInt()}%"
    s.percent >= 100f -> "Processing…"
    else -> "Starting…"
}

// ============================================================== library

@Composable
fun LibraryScreen(tracks: List<Track>, jobs: Map<String, DlJob>, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Track?>(null) }
    val shown = remember(tracks, filter) {
        if (filter.isBlank()) tracks
        else tracks.filter { it.title.contains(filter, true) || it.artist.contains(filter, true) }
    }
    val currentId = PlayerConn.current?.mediaId

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Header("Library", onSettings) }

        if (jobs.isNotEmpty()) {
            item { SectionLabel("Downloading") }
            items(jobs.values.toList(), key = { "job:" + it.key }) { job -> JobRow(job) }
        }

        if (tracks.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${tracks.size} song${if (tracks.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { PlayerConn.playTracks(tracks, tracks.indices.random(), shuffled = true) }) {
                        Icon(AppIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { PlayerConn.playTracks(tracks, 0) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                }
            }
            if (tracks.size > 5) {
                item {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Filter your songs") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (filter.isNotEmpty()) {
                                IconButton(onClick = { filter = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear") }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                    )
                }
            }
            items(shown, key = { it.path }) { t ->
                TrackRow(
                    t = t,
                    isCurrent = t.path == currentId,
                    onClick = { PlayerConn.playTracks(shown, shown.indexOf(t)) },
                    onDelete = { toDelete = t },
                )
            }
        } else if (jobs.isEmpty()) {
            item {
                EmptyState(
                    AppIcons.Library,
                    "No songs yet",
                    "Search for a song, or Share a video to YT Player from the YouTube app.",
                )
            }
        }
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete song?") },
            text = { Text(t.title) },
            confirmButton = {
                TextButton(onClick = {
                    PlayerConn.removeFromQueue(t.path)
                    thread { Library.delete(ctx, t) }
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun TrackRow(t: Track, isCurrent: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.cover, Modifier.size(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                t.artist.ifEmpty { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
fun JobRow(job: DlJob) {
    val ctx = LocalContext.current
    val failed = job.state as? DlState.Failed
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (val s = job.state) {
                            is DlState.Running -> statusText(s)
                            is DlState.Failed -> s.message
                            DlState.Queued -> "Queued"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (failed != null) {
                    TextButton(onClick = { Downloads.enqueue(ctx, job.url, job.title, job.key) }) { Text("Retry") }
                }
                IconButton(onClick = { if (failed != null) Downloads.dismiss(job.key) else Downloads.cancel(job.key) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                }
            }
            if (failed == null) {
                val pct = (job.state as? DlState.Running)?.percent ?: -1f
                Box(Modifier.padding(end = 12.dp)) {
                    if (pct in 0f..100f) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ============================================================== player

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

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val item = PlayerConn.current ?: return
    val md = item.mediaMetadata
    val pos by rememberPosition()
    val dur = PlayerConn.duration

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            LinearProgressIndicator(
                progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(md.artworkUri, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        md.title?.toString() ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        md.artist?.toString() ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { PlayerConn.toggle() }) {
                    Icon(
                        if (PlayerConn.isPlaying) AppIcons.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (PlayerConn.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { PlayerConn.next() }) {
                    Icon(AppIcons.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val md = PlayerConn.current?.mediaMetadata
    val pos by rememberPosition()
    val dur = PlayerConn.duration
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val max = dur.toFloat().coerceAtLeast(1f)
    val shown = if (dragging) dragValue else pos.toFloat().coerceIn(0f, max)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(32.dp))
                }
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(48.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Artwork(
                    md?.artworkUri,
                    Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true),
                    RoundedCornerShape(24.dp),
                )
            }

            Text(
                md?.title?.toString() ?: "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                md?.artist?.toString() ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
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
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtMs(shown.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(fmtMs(dur), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val on = MaterialTheme.colorScheme.primary
                val off = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = { PlayerConn.toggleShuffle() }) {
                    Icon(AppIcons.Shuffle, contentDescription = "Shuffle", tint = if (PlayerConn.shuffle) on else off)
                }
                IconButton(onClick = { PlayerConn.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(onClick = { PlayerConn.toggle() }, modifier = Modifier.size(76.dp)) {
                    Icon(
                        if (PlayerConn.isPlaying) AppIcons.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (PlayerConn.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { PlayerConn.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(AppIcons.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { PlayerConn.cycleRepeat() }) {
                    Icon(
                        if (PlayerConn.repeatMode == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                        contentDescription = "Repeat",
                        tint = if (PlayerConn.repeatMode == Player.REPEAT_MODE_OFF) off else on,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ============================================================== settings

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keep by remember { mutableStateOf(Prefs.keepVideo) }
    var status by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        keep = !keep
                        Prefs.keepVideo = keep
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep video too", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Also save the MP4 (uses more space)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = keep, onCheckedChange = {
                        keep = it
                        Prefs.keepVideo = it
                    })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "If search or downloads stop working, YouTube has probably changed something. Updating yt-dlp usually fixes it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !updating,
                    onClick = {
                        updating = true
                        status = "Updating…"
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                runCatching { "yt-dlp: " + Downloader.updateYtDlp(ctx) }
                                    .getOrElse { "Update failed: " + shortError(it) }
                            }
                            updating = false
                        }
                    },
                ) { Text("Update yt-dlp") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Songs are saved in Android/data/com.example.ytplayer/files/Music/YTPlayer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ============================================================== shared bits

@Composable
fun Header(title: String, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Artwork(model: Any?, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.45f),
            )
        }
    }
}

fun fmtMs(ms: Long): String = fmtSec((ms / 1000).toInt())

fun fmtSec(s: Int): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
