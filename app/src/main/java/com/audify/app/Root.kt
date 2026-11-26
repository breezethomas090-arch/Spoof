package com.audify.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private data class TabSpec(val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { if (Ui.openCode(ctx, it)) Ui.showCodeDialog = false }
    }
    val tabs = listOf(
        TabSpec("Home", Icons.Rounded.Home),
        TabSpec("Search", Icons.Rounded.Search),
        TabSpec("Library", AppIcons.Library),
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (PlayerConn.current != null) MiniPlayer()
                    NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp) {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = Ui.tab == i && Ui.detail == null,
                                onClick = {
                                    Ui.tab = i
                                    Ui.detail = null
                                },
                                icon = {
                                    if (i == 2 && jobs.isNotEmpty()) {
                                        BadgedBox(badge = { Badge { Text("${jobs.size}") } }) { Icon(t.icon, contentDescription = null) }
                                    } else {
                                        Icon(t.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = Color.White,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                val d = Ui.detail
                if (d != null) {
                    DetailScreen(d)
                } else {
                    when (Ui.tab) {
                        0 -> HomeScreen()
                        1 -> SearchScreen()
                        else -> LibraryScreen()
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = Ui.showPlayer && PlayerConn.current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen()
        }

        // "Opening…" overlay while a code is being looked up
        AnimatedVisibility(visible = Ui.busy != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(Ui.busy ?: "")
                }
            }
        }
    }

    BackHandler(enabled = Ui.showPlayer || Ui.detail != null) {
        if (Ui.showPlayer) Ui.showPlayer = false else Ui.detail = null
    }

    Ui.menu?.let { SongMenuSheet(it) }
    Ui.addToPlaylist?.let { AddToPlaylistSheet(it) }
    Ui.newPlaylistFor?.let { songs ->
        NameDialog("New playlist", "", "Create", onDismiss = { Ui.newPlaylistFor = null }) { name ->
            val p = Store.createPlaylist(name, songs)
            Ui.newPlaylistFor = null
            if (songs.isNotEmpty()) Ui.toast(ctx, "Added to ${p.name}")
        }
    }
    Ui.share?.let { ShareDialog(it) }
    if (Ui.showSettings) SettingsDialog()
    if (Ui.showCodeDialog) {
        CodeDialog(onScan = {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan an Audify QR code")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        })
    }
}
