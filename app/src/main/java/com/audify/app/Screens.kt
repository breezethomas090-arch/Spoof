@file:OptIn(ExperimentalMaterial3Api::class)

package com.audify.app

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

private val GENRES = listOf(
    "Pop" to listOf(Color(0xFFE11D48), Color(0xFFF97316)),
    "Hip-hop" to listOf(Color(0xFF7C3AED), Color(0xFF2563EB)),
    "R&B" to listOf(Color(0xFFDB2777), Color(0xFF7C3AED)),
    "Chill" to listOf(Color(0xFF0D9488), Color(0xFF0EA5E9)),
    "Lo-fi" to listOf(Color(0xFF6D28D9), Color(0xFF1E3A8A)),
    "Rock" to listOf(Color(0xFF991B1B), Color(0xFF1F2937)),
    "Workout" to listOf(Color(0xFFEA580C), Color(0xFFDC2626)),
    "Party" to listOf(Color(0xFFC026D3), Color(0xFFF43F5E)),
    "Throwbacks" to listOf(Color(0xFFB45309), Color(0xFF7C2D12)),
    "Focus" to listOf(Color(0xFF334155), Color(0xFF0F766E)),
    "Jazz" to listOf(Color(0xFF92400E), Color(0xFF1E293B)),
    "Classical" to listOf(Color(0xFFF59E0B), Color(0xFFF97316)),
)

// ============================================================== shared bits

@Composable
private fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) Violet else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

/** Big square card with a title underneath (playlists, public playlists). */
@Composable
private fun CoverCard(title: String, subtitle: String, width: Dp = 150.dp, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Column(Modifier.width(width).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp)) {
        cover()
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private class QuickTileSpec(val title: String, val onClick: () -> Unit, val cover: @Composable () -> Unit)

/** Compact two-column shortcut tile, like Spotify's home grid. */
@Composable
private fun QuickTile(title: String, modifier: Modifier, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover()
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
    }
}

@Composable
fun RemoteCover(r: RemotePlaylist, size: Dp) {
    if (r.thumb != null) {
        Artwork(r.thumb, Modifier.size(size), RoundedCornerShape(8.dp))
    } else {
        PlaylistCover(Playlist(r.id, r.title, emptyList()), size)
    }
}

// ============================================================== home

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    var recs by mutableStateOf<List<Song>>(emptyList())
        private set
    var seed by mutableStateOf<Song?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var publicLists by mutableStateOf<List<RemotePlaylist>>(emptyList())
        private set
    var viral by mutableStateOf<List<Song>>(emptyList())
        private set
    var viralLoading by mutableStateOf(false)
        private set
    private var loadedFor: String? = null
    private var publicLoaded = false
    private var viralLoaded = false

    private fun pickSeed(): Song? = Store.history.value.firstOrNull()
        ?: Store.liked.value.firstOrNull()
        ?: Library.tracks.value.firstNotNullOfOrNull { it.toSong() }

    fun load(force: Boolean = false) {
        val s = pickSeed()
        if (force || loadedFor != (s?.id ?: "none") || recs.isEmpty()) {
            seed = s
            loadedFor = s?.id ?: "none"
            loading = true
            error = null
            viewModelScope.launch {
                val r = runCatching {
                    withContext(Dispatchers.IO) {
                        if (s != null) Downloader.related(getApplication(), s.id)
                        else Downloader.genre(getApplication(), "Pop", 20)
                    }
                }
                loading = false
                r.onSuccess { recs = it }.onFailure { error = shortError(it) }
            }
        }
        if (!viralLoaded || force) {
            viralLoaded = true
            viralLoading = true
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { Downloader.viral(getApplication()) } }
                    .onSuccess { viral = it }
                viralLoading = false
            }
        }
        if (!publicLoaded || force) {
            publicLoaded = true
            val q = s?.artist?.takeIf { it.isNotBlank() }?.split(",")?.first()?.trim() ?: "viral hits"
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { Downloader.publicPlaylists(getApplication(), q) } }
                    .onSuccess { publicLists = it }
            }
        }
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val history by Store.history.collectAsStateWithLifecycle()
    val liked by Store.liked.collectAsStateWithLifecycle()
    val playlists by Store.playlists.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }   // 0 All, 1 Music, 2 Playlists
    LaunchedEffect(Unit) { vm.load() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(36.dp, Modifier.clip(CircleShape).clickable { Ui.detail = Detail.Account })
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("All", filter == 0) { filter = 0 }
                    Pill("Music", filter == 1) { filter = 1 }
                    Pill("Playlists", filter == 2) { filter = 2 }
                }
                IconButton(onClick = { Ui.showCodeDialog = true }) { Icon(AppIcons.QrCode, contentDescription = "Enter Audify code") }
            }
        }

        // Quick-access grid
        if (filter != 1) {
            item {
                val tiles = ArrayList<QuickTileSpec>()
                tiles += QuickTileSpec("Liked Songs", onClick = { Ui.detail = Detail.Liked }, cover = { IconTile(Icons.Rounded.Favorite, 56.dp, BrandBrush) })
                playlists.take(5).forEach { p ->
                    tiles += QuickTileSpec(p.name, onClick = { Ui.detail = Detail.PlaylistView(p.id) }, cover = { PlaylistCover(p, 56.dp, RoundedCornerShape(0.dp)) })
                }
                history.take((8 - tiles.size).coerceAtLeast(0)).forEachIndexed { i, s ->
                    tiles += QuickTileSpec(s.title, onClick = { PlayerConn.playSongs(history, i) }, cover = { SongArt(s, Modifier.size(56.dp), RoundedCornerShape(0.dp)) })
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { t ->
                                QuickTile(t.title, Modifier.weight(1f), onClick = t.onClick, cover = t.cover)
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (filter != 2 && history.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            item {
                val recent = history.take(15)
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(recent, key = { _, s -> "r:" + s.id }) { i, s -> SongCard(s) { PlayerConn.playSongs(recent, i) } }
                }
            }
        }

        if (filter != 2) {
            item {
                SectionHeader("Viral right now") {
                    if (vm.viral.isNotEmpty()) {
                        TextButton(onClick = { PlayerConn.playSongs(vm.viral, 0) }) { Text("Play all") }
                    }
                }
            }
            item {
                if (vm.viral.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        if (vm.viralLoading) CircularProgressIndicator(strokeWidth = 2.dp)
                        else Text("Couldn't load what's trending", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(vm.viral, key = { _, s -> "v:" + s.id }) { i, s -> SongCard(s) { PlayerConn.playSongs(vm.viral, i) } }
                    }
                }
            }
        }

        if (filter != 2) {
            item {
                SectionHeader(vm.seed?.let { "More like ${it.artist.split(",").first().ifBlank { it.title }}" } ?: "Popular in Pop") {
                    IconButton(onClick = { vm.load(force = true) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                when {
                    vm.loading && vm.recs.isEmpty() -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                    vm.error != null && vm.recs.isEmpty() -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vm.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        TextButton(onClick = { vm.load(force = true) }) { Text("Try again") }
                    }
                    else -> LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(vm.recs, key = { _, s -> "m:" + s.id }) { i, s -> SongCard(s) { PlayerConn.playSongs(vm.recs, i) } }
                    }
                }
            }
        }

        if (filter != 1 && playlists.isNotEmpty()) {
            item { SectionHeader("Your playlists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        CoverCard(p.name, "By ${p.owner ?: Profile.name}", onClick = { Ui.detail = Detail.PlaylistView(p.id) }) {
                            PlaylistCover(p, 142.dp)
                        }
                    }
                }
            }
        }

        if (filter != 1 && vm.publicLists.isNotEmpty()) {
            item { SectionHeader("Playlists for you") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vm.publicLists, key = { "p:" + it.id }) { r ->
                        CoverCard(r.title, r.channel, onClick = { Ui.detail = Detail.Remote(r) }) { RemoteCover(r, 142.dp) }
                    }
                }
            }
        }

        if (filter == 2 && playlists.isEmpty() && vm.publicLists.isEmpty()) {
            item { EmptyState(AppIcons.Playlist, "No playlists yet", "Tap Create to make one, or open a friend's Audify code.") }
        }
        if (liked.isEmpty() && history.isEmpty() && filter == 0) {
            item {
                Text(
                    "Tip: play a few songs and Audify will start recommending music for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
        }
    }
}

// ============================================================== search

fun isUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") ||
    s.contains("youtu.be/") || s.contains("youtube.com/") || s.startsWith("audify://")

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Song>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var seq = 0

    fun search(q: String = query.trim()) {
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

    /** Genre / mood tiles: songs from YouTube Music's own playlist for that genre. */
    fun genre(name: String) {
        query = name
        val mine = ++seq
        loading = true
        error = null
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Downloader.genre(getApplication(), name) } }
            if (mine != seq) return@launch
            loading = false
            r.onSuccess {
                results = it
                if (it.isEmpty()) error = "Couldn't load $name right now"
            }.onFailure { error = shortError(it) }
        }
    }

    fun clear() {
        query = ""
        results = emptyList()
        error = null
        seq++
        loading = false
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
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(36.dp, Modifier.clip(CircleShape).clickable { Ui.detail = Detail.Account })
                Spacer(Modifier.width(12.dp))
                Text("Search", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { Ui.showCodeDialog = true }) { Icon(AppIcons.QrCode, contentDescription = "Enter Audify code") }
            }
        }
        item {
            TextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("What do you want to listen to?", color = Color(0xFF555555)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF222222)) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.clear() }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear", tint = Color(0xFF222222)) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    cursorColor = Violet,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
        }
        when {
            isUrl(q) -> item {
                Row(
                    Modifier.fillMaxWidth().clickable { submit() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(AppIcons.QrCode, 50.dp, BrandBrush)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Open this link", style = MaterialTheme.typography.bodyLarge)
                        Text(q, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            vm.loading -> item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    if (!Downloader.ready) {
                        Spacer(Modifier.height(12.dp))
                        Text("Getting ready: the first search takes a little longer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            vm.error != null -> item {
                Text(vm.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            }
            vm.results.isEmpty() -> {
                item {
                    Text("Browse all", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp))
                }
                items(GENRES.chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, colors) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(96.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Brush.linearGradient(colors))
                                    .clickable { vm.genre(name) }
                                    .padding(12.dp),
                            ) {
                                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
            else -> {
                // If the results are mostly by one artist that matches the search, offer their page
                val top = vm.results.groupingBy { primaryArtist(it.artist) }.eachCount().maxByOrNull { it.value }?.key
                if (top != null && top.isNotBlank() && (q.contains(top, true) || top.contains(q, true))) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { Ui.openArtist(top) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(56.dp).clip(CircleShape).background(BrandBrush), contentAlignment = Alignment.Center) {
                                Text(top.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(top, style = MaterialTheme.typography.titleMedium)
                                Text("Artist · see songs, albums and playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.rotate(180f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(vm.results, key = { _, s -> s.id }) { i, s ->
                    SongRow(s, onClick = { PlayerConn.playSongs(vm.results, i) })
                }
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
    var filter by rememberSaveable { mutableIntStateOf(0) }   // 0 all, 1 playlists, 2 downloaded

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(36.dp, Modifier.clip(CircleShape).clickable { Ui.detail = Detail.Account })
                Spacer(Modifier.width(12.dp))
                Text("Your Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { Ui.newPlaylistFor = emptyList() }) { Icon(Icons.Rounded.Add, contentDescription = "New playlist") }
                IconButton(onClick = { Ui.showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Playlists", filter == 1) { filter = if (filter == 1) 0 else 1 }
                Pill("Downloaded", filter == 2) { filter = if (filter == 2) 0 else 2 }
            }
        }
        if (jobs.isNotEmpty()) {
            item { Text("Downloading", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) }
            items(jobs.values.toList(), key = { "job:" + it.key }) { JobRow(it) }
        }
        if (filter != 2) {
            item {
                CollectionRow({ IconTile(Icons.Rounded.Favorite, 64.dp, BrandBrush) }, "Liked Songs", "Playlist · ${liked.size} songs") {
                    Ui.detail = Detail.Liked
                }
            }
        }
        if (filter != 1) {
            item {
                CollectionRow({ IconTile(AppIcons.DownloadDone, 64.dp, Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF0F766E)))) }, "Downloads", "${tracks.size} songs · offline") {
                    Ui.detail = Detail.Downloaded
                }
            }
        }
        if (filter != 2) {
            items(playlists, key = { it.id }) { p ->
                CollectionRow({ PlaylistCover(p, 64.dp) }, p.name, "Playlist · ${p.owner ?: Profile.name}") {
                    Ui.detail = Detail.PlaylistView(p.id)
                }
            }
            item {
                CollectionRow({ IconTile(Icons.Rounded.Add, 64.dp) }, "Create playlist", "") { Ui.newPlaylistFor = emptyList() }
            }
            item {
                CollectionRow({ IconTile(AppIcons.PlaylistAdd, 64.dp, SpotifyBrush) }, "Import from Spotify", "Bring over playlists, albums and songs") {
                    Ui.showSpotifyDialog = true
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(tile: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun JobRow(job: DlJob) {
    val ctx = LocalContext.current
    val failed = job.state as? DlState.Failed
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
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
                title = "Liked Songs",
                subtitle = "${liked.size} songs",
                colorModel = null,
                cover = { IconTile(Icons.Rounded.Favorite, 200.dp, BrandBrush) },
                songs = liked,
                emptyText = "Tap ♡ on any song to save it here.",
            )
        }
        Detail.Downloaded -> DownloadsScreen()
        Detail.Account -> AccountScreen()
        is Detail.Remote -> RemotePlaylistScreen(d.playlist)
        is Detail.PlaylistView -> PlaylistScreen(d.id)
        is Detail.Artist -> ArtistScreen(d.name)
    }
}

@Composable
private fun PlaylistScreen(id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by Store.playlists.collectAsStateWithLifecycle()
    val p = playlists.firstOrNull { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { Ui.back() }
        return
    }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { saveImage(ctx, uri, "covers", p.id) }
            if (path != null) Store.setPlaylistCover(p.id, path) else Ui.toast(ctx, "Couldn't use that image")
        }
    }

    SongCollection(
        title = p.name,
        subtitle = "${p.owner ?: Profile.name} · ${p.songs.size} songs",
        colorModel = p.cover?.let { File(it) } ?: p.songs.firstOrNull()?.thumb,
        cover = { PlaylistCover(p, 200.dp) },
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
                        text = { Text("Change image") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            menu = false
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    if (p.cover != null) {
                        DropdownMenuItem(
                            text = { Text("Use automatic cover") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = {
                                menu = false
                                Store.setPlaylistCover(p.id, null)
                            },
                        )
                    }
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
                    Ui.back()
                    Store.deletePlaylist(p.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loadedId: String? = null

    fun load(r: RemotePlaylist) {
        if (loadedId == r.id) return
        loadedId = r.id
        loading = true
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Downloader.remoteSongs(getApplication(), r) } }
                .onSuccess { songs = it }
                .onFailure { error = shortError(it) }
            loading = false
        }
    }
}

@Composable
private fun RemotePlaylistScreen(r: RemotePlaylist) {
    val ctx = LocalContext.current
    val vm: RemoteViewModel = viewModel(key = "remote:" + r.id)
    LaunchedEffect(r.id) { vm.load(r) }
    val playlists by Store.playlists.collectAsStateWithLifecycle()
    val saved = playlists.any { it.name == r.title && it.owner == r.channel.ifBlank { "YouTube" } }

    SongCollection(
        title = r.title,
        subtitle = listOf(r.channel.ifBlank { "YouTube" }, if (vm.songs.isNotEmpty()) "${vm.songs.size} songs" else "").filter { it.isNotEmpty() }.joinToString(" · "),
        colorModel = r.thumb ?: vm.songs.firstOrNull()?.thumb,
        cover = {
            if (r.thumb != null) RemoteCover(r, 200.dp)
            else PlaylistCover(Playlist(r.id, r.title, vm.songs), 200.dp)
        },
        songs = vm.songs,
        emptyText = if (vm.loading) "" else (vm.error ?: "This playlist is empty."),
        loading = vm.loading,
        actions = {
            IconButton(
                enabled = vm.songs.isNotEmpty() && !saved,
                onClick = {
                    Store.createPlaylist(r.title, vm.songs, owner = r.channel.ifBlank { "YouTube" })
                    Ui.toast(ctx, "Saved to Your Library")
                },
            ) {
                Icon(
                    if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Save to library",
                    tint = if (saved) Pink else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

/** Spotify-style header: tinted gradient, big cover, title, actions and a play button. */
@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    colorModel: Any?,
    cover: @Composable () -> Unit,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val tint by rememberArtColor(colorModel)
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, Color.Black)))) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Ui.back() }) {
                Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.shadow(20.dp, RoundedCornerShape(8.dp))) { cover() }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
            Spacer(Modifier.weight(1f))
            if (canPlay) {
                IconButton(onClick = onShuffle) {
                    Icon(AppIcons.Shuffle, contentDescription = "Shuffle", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                GradientPlayButton(56.dp, playing = false, onClick = onPlay)
            }
        }
    }
}

@Composable
private fun SongCollection(
    title: String,
    subtitle: String,
    colorModel: Any?,
    cover: @Composable () -> Unit,
    songs: List<Song>,
    emptyText: String,
    playlistId: String? = null,
    loading: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            DetailHeader(
                title, subtitle, colorModel, cover,
                canPlay = songs.isNotEmpty(),
                onPlay = { PlayerConn.playSongs(songs, 0) },
                onShuffle = { PlayerConn.playSongs(songs, songs.indices.random(), shuffled = true) },
                actions = actions,
            )
        }
        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 2.dp) }
            }
        } else if (songs.isEmpty() && emptyText.isNotEmpty()) {
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
                colorModel = null,
                cover = { IconTile(AppIcons.DownloadDone, 200.dp, Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF0F766E)))) },
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
                Modifier.fillMaxWidth().clickable { PlayerConn.playTracks(tracks, i) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(t.cover ?: t.toSong()?.thumb, Modifier.size(50.dp), RoundedCornerShape(4.dp))
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
                        listOf(t.artist.ifBlank { "Unknown artist" }, t.file.extension.uppercase()).joinToString(" · "),
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

// ============================================================== account

@Composable
private fun AccountScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val liked by Store.liked.collectAsStateWithLifecycle()
    val playlists by Store.playlists.collectAsStateWithLifecycle()
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { saveImage(ctx, uri, "avatars", "avatar", 512) }
            if (path != null) Profile.changeAvatar(path) else Ui.toast(ctx, "Couldn't use that image")
        }
    }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val tint by rememberArtColor(Profile.avatar?.let { File(it) })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, Color.Black))),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = { Ui.back() }) {
                    Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Ui.showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(132.dp, Modifier.shadow(16.dp, CircleShape).clip(CircleShape).clickable { pickPhoto() })
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.White).clickable { pickPhoto() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Edit, contentDescription = "Change photo", tint = Color.Black, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Profile.name, style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = { editName = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit name", modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Stat(liked.size, "Liked")
                Stat(playlists.size, "Playlists")
                Stat(tracks.size, "Downloads")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                OutlinedButton(onClick = { editName = true }) { Text("Edit profile") }
                if (Profile.avatar != null) OutlinedButton(onClick = { Profile.changeAvatar(null) }) { Text("Remove photo") }
            }
        }
        if (playlists.isNotEmpty()) {
            Text("Your playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            playlists.forEach { p ->
                CollectionRow({ PlaylistCover(p, 56.dp) }, p.name, "${p.songs.size} songs") { Ui.detail = Detail.PlaylistView(p.id) }
            }
        }
        Text(
            "Your profile is saved on this phone. Your name appears when you share songs and playlists with friends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (editName) {
        var name by remember { mutableStateOf(Profile.name) }
        AlertDialog(
            onDismissRequest = { editName = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Edit profile") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(88.dp, Modifier.clip(CircleShape).clickable { pickPhoto() })
                    TextButton(onClick = { pickPhoto() }) { Text("Change photo") }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(30) },
                        singleLine = true,
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Profile.changeName(name)
                    editName = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(n: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================== artist

class ArtistViewModel(app: Application) : AndroidViewModel(app) {
    var thumb by mutableStateOf<String?>(null)
        private set
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var albums by mutableStateOf<List<RemotePlaylist>>(emptyList())
        private set
    var playlists by mutableStateOf<List<RemotePlaylist>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loaded: String? = null

    fun load(name: String) {
        if (loaded == name) return
        loaded = name
        loading = true
        val ctx: Application = getApplication()
        // Four lookups in parallel; each part of the page fills in as it arrives
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Downloader.artistSongs(ctx, name) } }
                .onSuccess { songs = it }
                .onFailure { error = shortError(it) }
            loading = false
        }
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { Downloader.artistThumb(ctx, name) } }.onSuccess { thumb = it } }
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { Downloader.artistAlbums(ctx, name) } }.onSuccess { albums = it } }
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { Downloader.artistPlaylists(ctx, name) } }.onSuccess { playlists = it } }
    }
}

@Composable
fun ArtistScreen(name: String) {
    val vm: ArtistViewModel = viewModel(key = "artist:$name")
    LaunchedEffect(name) { vm.load(name) }
    val tint by rememberArtColor(vm.thumb ?: vm.songs.firstOrNull()?.thumb)
    var showAll by rememberSaveable(name) { mutableStateOf(false) }
    val shown = if (showAll) vm.songs else vm.songs.take(10)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, Color.Black))),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onClick = { Ui.back() }) {
                        Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(30.dp))
                    }
                }
                val photo = vm.thumb
                if (photo != null) {
                    Artwork(photo, Modifier.size(180.dp).shadow(20.dp, CircleShape), CircleShape)
                } else {
                    Box(
                        Modifier.size(180.dp).clip(CircleShape).background(BrandBrush),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(name.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                Text("Artist", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    if (vm.songs.isNotEmpty()) {
                        IconButton(onClick = { PlayerConn.playSongs(vm.songs, vm.songs.indices.random(), shuffled = true) }) {
                            Icon(AppIcons.Shuffle, contentDescription = "Shuffle", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        GradientPlayButton(56.dp, playing = false) { PlayerConn.playSongs(vm.songs, 0) }
                    }
                }
            }
        }

        item { Text("Popular", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) }
        when {
            vm.loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 2.dp) }
            }
            vm.songs.isEmpty() -> item {
                Text(
                    vm.error ?: "No songs found for $name",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> {
                itemsIndexed(shown, key = { _, s -> "a:" + s.id }) { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(36.dp),
                        )
                        Box(Modifier.weight(1f)) { SongRow(s, onClick = { PlayerConn.playSongs(vm.songs, i) }) }
                    }
                }
                if (vm.songs.size > 10) {
                    item {
                        TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(start = 8.dp)) {
                            Text(if (showAll) "Show less" else "See all ${vm.songs.size} songs")
                        }
                    }
                }
            }
        }

        if (vm.albums.isNotEmpty()) {
            item { SectionHeader("Albums & singles") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vm.albums, key = { "al:" + it.id }) { r ->
                        CoverCard(r.title, "Album", onClick = { Ui.detail = Detail.Remote(r) }) { RemoteCover(r, 142.dp) }
                    }
                }
            }
        }
        if (vm.playlists.isNotEmpty()) {
            item { SectionHeader("Featuring $name") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vm.playlists, key = { "pl:" + it.id }) { r ->
                        CoverCard(r.title, r.channel, onClick = { Ui.detail = Detail.Remote(r) }) { RemoteCover(r, 142.dp) }
                    }
                }
            }
        }
    }
}

// ============================================================== Spotify import

@Composable
fun SpotifyImportDialog() {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val valid = SpotifyImport.isSpotifyLink(link)

    AlertDialog(
        onDismissRequest = { Ui.showSpotifyDialog = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { IconTile(AppIcons.PlaylistAdd, 48.dp, SpotifyBrush) },
        title = { Text("Import from Spotify") },
        text = {
            Column {
                Text(
                    "In Spotify, open a playlist, album or song, tap ⋯ → Share → Copy link, then paste it here. Audify finds every song and makes it a playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    placeholder = { Text("https://open.spotify.com/playlist/…") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = link.isNotBlank() && !valid,
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) { Text("Paste from clipboard") }
                Text(
                    "Works with public playlists (up to 100 songs), albums and songs. Liked Songs is private on Spotify, so add those songs to a public playlist first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                SpotifyImport.start(ctx, link.trim())
                Ui.showSpotifyDialog = false
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { Ui.showSpotifyDialog = false }) { Text("Cancel") } },
    )
}
