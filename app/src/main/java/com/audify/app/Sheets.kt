@file:OptIn(ExperimentalMaterial3Api::class)

package com.audify.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

// ============================================================== song menu

@Composable
fun SongMenuSheet(target: SongMenuTarget) {
    val ctx = LocalContext.current
    val song = target.song
    val liked by Store.liked.collectAsStateWithLifecycle()
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val isLiked = liked.any { it.id == song.id }
    val track = tracks.firstOrNull { it.videoId == song.id }
    val dismiss = { Ui.menu = null }

    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SongArt(song, Modifier.size(52.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuItem(AppIcons.PlayNext, "Play next") {
            PlayerConn.playNext(song)
            Ui.toast(ctx, "Playing next")
            dismiss()
        }
        MenuItem(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Remove from Liked songs" else "Like") {
            Store.toggleLike(song)
            dismiss()
        }
        if (song.artist.isNotBlank()) {
            MenuItem(AppIcons.MusicNote, "Go to artist: ${primaryArtist(song.artist)}") {
                dismiss()
                Ui.openArtist(song.artist)
            }
        }
        MenuItem(AppIcons.PlaylistAdd, "Add to playlist") {
            Ui.addToPlaylist = listOf(song)
            dismiss()
        }
        if (track == null) {
            MenuItem(AppIcons.Download, "Download (${Prefs.quality.label})") {
                Downloads.enqueue(ctx, song)
                dismiss()
            }
        } else {
            MenuItem(Icons.Rounded.Delete, "Remove download") {
                PlayerConn.removeFromQueue(track.path)
                thread { Library.delete(ctx, track) }
                dismiss()
            }
        }
        MenuItem(Icons.Rounded.Share, "Share · Audify code / QR") {
            Ui.shareSong(song)
            dismiss()
        }
        if (target.playlistId != null) {
            MenuItem(Icons.Rounded.Delete, "Remove from this playlist") {
                Store.removeFromPlaylist(target.playlistId, song.id)
                dismiss()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// ============================================================== playlists

@Composable
fun AddToPlaylistSheet(songs: List<Song>) {
    val ctx = LocalContext.current
    val playlists by Store.playlists.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = { Ui.addToPlaylist = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text("Add to playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            item {
                PlaylistPickRow(null, "New playlist", "") {
                    Ui.addToPlaylist = null
                    Ui.newPlaylistFor = songs
                }
            }
            items(playlists, key = { it.id }) { p ->
                PlaylistPickRow(p, p.name, "${p.songs.size} songs") {
                    Store.addToPlaylist(p.id, songs)
                    Ui.toast(ctx, "Added to ${p.name}")
                    Ui.addToPlaylist = null
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlaylistPickRow(p: Playlist?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p == null) IconTile(Icons.Rounded.Add, 48.dp) else PlaylistCover(p, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(name) }),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ============================================================== share & codes

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Audify", text))
    Ui.toast(ctx, "Copied")
}

@Composable
fun ShareDialog(t: ShareTarget) {
    val ctx = LocalContext.current
    var themeIdx by rememberSaveable { mutableIntStateOf(0) }
    val theme = QR_THEMES[themeIdx]
    val qr by produceState<Bitmap?>(null, t.qrContent, themeIdx) {
        value = withContext(Dispatchers.Default) { runCatching { styledQr(t.qrContent, theme) }.getOrNull() }
    }
    Dialog(onDismissRequest = { Ui.share = null }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.size(240.dp).clip(RoundedCornerShape(24.dp)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = qr
                    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Audify QR code", modifier = Modifier.fillMaxSize())
                    else CircularProgressIndicator(strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QR_THEMES.forEachIndexed { i, th ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(th.start), Color(th.end))))
                                .then(if (i == themeIdx) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier)
                                .clickable { themeIdx = i },
                        )
                    }
                }
                Text(theme.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (t.code != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("AUDIFY CODE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { copy(ctx, t.code) }) { Icon(AppIcons.Copy, contentDescription = "Copy code", modifier = Modifier.size(20.dp)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Friends open Audify, tap the code icon on Home, then type the code or scan the QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row {
                    OutlinedButton(onClick = { copy(ctx, t.code ?: t.qrContent) }) { Text(if (t.code != null) "Copy code" else "Copy link") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        val bmp = qr
                        if (bmp != null) {
                            shareImage(ctx, bmp, t.shareText)
                        } else {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.shareText)
                            ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share QR")
                    }
                }
            }
        }
    }
}

@Composable
fun CodeDialog(onScan: () -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    fun submit() {
        if (code.isNotBlank() && Ui.openCode(ctx, code)) Ui.showCodeDialog = false
    }
    AlertDialog(
        onDismissRequest = { Ui.showCodeDialog = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(AppIcons.QrCode, contentDescription = null) },
        title = { Text("Open a shared song") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    placeholder = { Text("AU-… code or link") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a QR code")
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { Ui.showCodeDialog = false }) { Text("Cancel") } },
    )
}

// ============================================================== settings

@Composable
fun SettingsDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(Prefs.quality) }
    var streamQ by remember { mutableStateOf(Prefs.streamQuality) }
    var keep by remember { mutableStateOf(Prefs.keepVideo) }
    var fast by remember { mutableStateOf(Prefs.fastDownloads) }
    var status by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    val dismiss = { Ui.showSettings = false }

    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(Modifier.padding(horizontal = 24.dp)) {
            item {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Text("STREAMING QUALITY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Lower quality means less data to fetch, so songs start with less lag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            items(StreamQuality.entries.toList()) { q ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = q == streamQ, onClick = { streamQ = q; Prefs.streamQuality = q; Streams.clear() })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = q == streamQ, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(q.label, style = MaterialTheme.typography.bodyLarge)
                        Text(q.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("DOWNLOAD QUALITY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Used for downloads. YouTube's best source audio is ~160 kbps Opus, so \"lossless\" keeps that exactly rather than adding detail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            items(Quality.entries.toList()) { q ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = q == quality, onClick = { quality = q; Prefs.quality = q })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = q == quality, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(q.label, style = MaterialTheme.typography.bodyLarge)
                        Text(q.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable { fast = !fast; Prefs.fastDownloads = fast }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Fast downloads", style = MaterialTheme.typography.bodyLarge)
                        Text("Uses several connections at once (aria2c) and 2 downloads at a time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = fast, onCheckedChange = { fast = it; Prefs.fastDownloads = it })
                }
                Row(
                    Modifier.fillMaxWidth().clickable { keep = !keep; Prefs.keepVideo = keep }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep video too", style = MaterialTheme.typography.bodyLarge)
                        Text("Also save the MP4 when downloading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = keep, onCheckedChange = { keep = it; Prefs.keepVideo = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("If search, streaming or downloads stop working, YouTube has changed something. Updating fixes it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !updating,
                    onClick = {
                        updating = true
                        status = "Updating…"
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                runCatching { Downloader.updateYtDlp(ctx) { s -> status = s } }
                                    .getOrElse { "Update failed: " + shortError(it) }
                            }
                            Streams.clear()
                            updating = false
                        }
                    },
                ) { Text("Update yt-dlp") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Audify 5.0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
