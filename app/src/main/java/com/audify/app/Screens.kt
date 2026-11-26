@file:OptIn(ExperimentalMaterial3Api::class)

package com.audify.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.concurrent.thread

private val MOODS = listOf("Top hits", "Pop", "Hip-hop", "R&B", "Chill", "Lo-fi", "Rock", "Workout", "Party", "Throwbacks", "Focus")

@Composable
private fun TopBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CodeAndSettings() {
    IconButton(onClick = { Ui.showCodeDialog = true }) { Icon(AppIcons.QrCode, contentDescription = "Enter Audify code") }
    IconButton(onClick = { Ui.showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
}

// ============================================================== home

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    var recs by mutableStateOf<List<Song>>(emptyList())
        private set
    var seed by mutableStateOf<Song?>(null)
        private set
    var mood by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var seq = 0
    private var loadedFor: String? = null

    /** Recommendations seeded from what you last played or liked. */
    fun loadForYou(force: Boolean = false) {
        val s = Store.history.value.firstOrNull()
            ?: Store.liked.value.firstOrNull()
            ?: Library.tracks.value.firstNotNullOfOrNull { it.toSong() }
        if (s == null) {
            if (recs.isEmpty() || force) loadMood(MOODS.first())
            return
        }
        if (!force && mood == null && loadedFor == s.id && recs.isNotEmpty()) return
        seed = s
        mood = null
        loadedFor = s.id
        fetch { Downloader.related(getApplication(), s.id) }
    }

    fun loadMood(m: String) {
        mood = m
        fetch { Downloader.search(getApplication(), "$m songs", 20) }
    }

    fun refresh() = if (mood != null) loadMood(mood!!) else loadForYou(force = true)

    private fun fetch(block: () -> List<Song>) {
        val mine = ++seq
        loading = true
        error = null
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            if (mine != seq) return@launch
            loading = false
            r.onSuccess { recs = it }.onFailure { error = shortError(it) }
        }
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val history by Store.history.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadForYou() }
    val hasSeed = history.isNotEmpty() || Store.liked.value.isNotEmpty() || Library.tracks.value.any { it.videoId != null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopBar {
                Wordmark(Modifier.weight(1f))
                CodeAndSettings()
            }
        }
        item {
            Text(
                greeting(),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasSeed) {
                    item {
                        FilterChip(
                            selected = vm.mood == null,
                            onClick = { vm.loadForYou(force = vm.mood != null) },
                            label = { Text("For you") },
                        )
                    }
                }
                items(MOODS) { m ->
                    FilterChip(selected = vm.mood == m, onClick = { vm.loadMood(m) }, label = { Text(m) })
                }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionTitle("Jump back in") }
            item {
                val recent = history.take(12)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(recent, key = { _, s -> s.id }) { i, s ->
                        SongCard(s) { PlayerConn.playSongs(recent, i) }
                    }
                }
            }
        }
        item {
            SectionTitle(
                when {
                    vm.mood != null -> vm.mood!!
                    vm.seed != null -> "Because you played ${vm.seed!!.title}"
                    else -> "Recommended"
                },
                action = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        when {
            vm.loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
            vm.error != null -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.refresh() }) { Text("Try again") }
                }
            }
            else -> itemsIndexed(vm.recs, key = { _, s -> "rec:" + s.id }) { i, s ->
                SongRow(s, onClick = { PlayerConn.playSongs(vm.recs, i) })
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

// ============================================================== search

fun isUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") ||
    s.contains("youtu.be/") || s.contains("youtube.com/")

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Song>>(emptyList())
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
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val q = vm.query.trim()

    fun submit() {
        focus.clearFocus()
        if (isUrl(q)) {
            if (Ui.openCode(ctx, q)) vm.query = ""
        } else {
            vm.search()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopBar {
                Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                CodeAndSettings()
            }
        }
        item {
            TextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("What do you want to listen to?") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
        }
        when {
            isUrl(q) -> item {
                Row(
                    Modifier.fillMaxWidth().clickable { submit() }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Rounded.Search, 50.dp, BrandBrush)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Play this link", style = MaterialTheme.typography.bodyLarge)
                        Text(q, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            vm.loading -> item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    if (!Downloader.ready) {
                        Spacer(Modifier.height(12.dp))
                        Text("Getting ready — the first search takes a little longer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            vm.error != null -> item {
                Text(
                    vm.error ?: "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            vm.results.isEmpty() -> item {
                EmptyState(Icons.Rounded.Search, "Search millions of songs", "Tap a result to stream it instantly, or download it to listen offline.")
            }
            else -> itemsIndexed(vm.results, key = { _, s -> s.id }) { i, s ->
                SongRow(s, onClick = { PlayerConn.playSongs(vm.results, i) })
            }
        }
    }
}

// ============================================================== library

@Composable
fun LibraryScreen() {
    val liked by Store.liked.collectAsStateWithLifecycle()
    val playlists by Store.playlists.collectAsStateWithLifecycle()
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopBar {
                Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { Ui.newPlaylistFor = emptyList() }) { Icon(Icons.Rounded.Add, contentDescription = "New playlist") }
                IconButton(onClick = { Ui.showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
        }
        if (jobs.isNotEmpty()) {
            item { SectionTitle("Downloading") }
            items(jobs.values.toList(), key = { "job:" + it.key }) { JobRow(it) }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            CollectionRow(
                tile = { IconTile(Icons.Rounded.Favorite, 56.dp, BrandBrush) },
                title = "Liked songs",
                subtitle = "${liked.size} songs",
            ) { Ui.detail = Detail.Liked }
        }
        item {
            CollectionRow(
                tile = { IconTile(AppIcons.DownloadDone, 56.dp) },
                title = "Downloads",
                subtitle = "${tracks.size} songs · offline",
            ) { Ui.detail = Detail.Downloaded }
        }
        item { SectionTitle("Playlists") }
        items(playlists, key = { it.id }) { p ->
            CollectionRow(
                tile = { PlaylistCover(p, 56.dp) },
                title = p.name,
                subtitle = "Playlist · ${p.songs.size} songs",
            ) { Ui.detail = Detail.PlaylistView(p.id) }
        }
        item {
            CollectionRow(
                tile = { IconTile(Icons.Rounded.Add, 56.dp) },
                title = "New playlist",
                subtitle = "",
            ) { Ui.newPlaylistFor = emptyList() }
        }
    }
}

@Composable
private fun CollectionRow(tile: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun JobRow(job: DlJob) {
    val ctx = LocalContext.current
    val failed = job.state as? DlState.Failed
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (val s = job.state) {
                        is DlState.Running -> statusText(s)
                        is DlState.Failed -> s.message
                        DlState.Queued -> "Queued"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (failed != null) TextButton(onClick = { Downloads.enqueue(ctx, job.url, job.title, job.key) }) { Text("Retry") }
            IconButton(onClick = { if (failed != null) Downloads.dismiss(job.key) else Downloads.cancel(job.key) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (failed == null) {
            val pct = (job.state as? DlState.Running)?.percent ?: -1f
            if (pct in 0f..100f) {
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
    }
}

// ============================================================== detail pages

@Composable
fun DetailScreen(d: Detail) {
    when (d) {
        Detail.Liked -> {
            val liked by Store.liked.collectAsStateWithLifecycle()
            SongCollection(
                title = "Liked songs",
                subtitle = "${liked.size} songs",
                cover = { IconTile(Icons.Rounded.Favorite, 180.dp, BrandBrush) },
                songs = liked,
                emptyText = "Tap ♡ on any song to save it here.",
            )
        }
        Detail.Downloaded -> DownloadsScreen()
        is Detail.PlaylistView -> {
            val playlists by Store.playlists.collectAsStateWithLifecycle()
            val p = playlists.firstOrNull { it.id == d.id }
            if (p == null) {
                LaunchedEffect(Unit) { Ui.detail = null }
                return
            }
            var menu by remember { mutableStateOf(false) }
            var rename by remember { mutableStateOf(false) }
            var confirmDelete by remember { mutableStateOf(false) }
            SongCollection(
                title = p.name,
                subtitle = "Playlist · ${p.songs.size} songs",
                cover = { PlaylistCover(p, 180.dp) },
                songs = p.songs,
                playlistId = p.id,
                emptyText = "Add songs from the ⋮ menu on any song.",
                actions = {
                    IconButton(onClick = { Ui.sharePlaylist(p) }, enabled = p.songs.isNotEmpty()) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share playlist")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; rename = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete playlist") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
            if (rename) {
                NameDialog("Rename playlist", p.name, "Save", onDismiss = { rename = false }) {
                    Store.renamePlaylist(p.id, it)
                    rename = false
                }
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    title = { Text("Delete \"${p.name}\"?") },
                    text = { Text("The songs stay in your library.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            Ui.detail = null
                            Store.deletePlaylist(p.id)
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    cover: @Composable () -> Unit,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Ui.detail = null }) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Back", modifier = Modifier.rotate(90f))
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), contentAlignment = Alignment.Center) { cover() }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canPlay) {
                IconButton(onClick = onShuffle) {
                    Icon(AppIcons.Shuffle, contentDescription = "Shuffle", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                GradientPlayButton(52.dp, playing = false, onClick = onPlay)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SongCollection(
    title: String,
    subtitle: String,
    cover: @Composable () -> Unit,
    songs: List<Song>,
    emptyText: String,
    playlistId: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            DetailHeader(
                title, subtitle, cover,
                canPlay = songs.isNotEmpty(),
                onPlay = { PlayerConn.playSongs(songs, 0) },
                onShuffle = { PlayerConn.playSongs(songs, songs.indices.random(), shuffled = true) },
                actions = actions,
            )
        }
        if (songs.isEmpty()) {
            item { EmptyState(AppIcons.MusicNote, "Nothing here yet", emptyText) }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            SongRow(s, onClick = { PlayerConn.playSongs(songs, i) }, playlistId = playlistId)
        }
    }
}

@Composable
private fun DownloadsScreen() {
    val ctx = LocalContext.current
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Track?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            DetailHeader(
                "Downloads", "${tracks.size} songs · available offline",
                cover = { IconTile(AppIcons.DownloadDone, 180.dp) },
                canPlay = tracks.isNotEmpty(),
                onPlay = { PlayerConn.playTracks(tracks, 0) },
                onShuffle = { PlayerConn.playTracks(tracks, tracks.indices.random(), shuffled = true) },
                actions = {},
            )
        }
        if (tracks.isEmpty()) {
            item { EmptyState(AppIcons.Download, "No downloads yet", "Tap the download icon on any song to keep it offline.") }
        }
        itemsIndexed(tracks, key = { _, t -> t.path }) { i, t ->
            val isCurrent = PlayerConn.current?.mediaId == t.path
            Row(
                Modifier.fillMaxWidth().clickable { PlayerConn.playTracks(tracks, i) }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(t.cover ?: t.toSong()?.thumb, Modifier.size(50.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(t.artist.ifBlank { "Unknown artist" }, t.file.extension.uppercase()).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                val song = t.toSong()
                IconButton(onClick = { if (song != null) Ui.menu = SongMenuTarget(song) else toDelete = t }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Delete download?") },
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
