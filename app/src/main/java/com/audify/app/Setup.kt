package com.audify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shown on first launch: Audify must update yt-dlp before you can use it,
 * because the copy bundled in the app goes out of date as YouTube changes.
 */
@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Getting things ready…") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { Downloader.updateYtDlp(ctx) { s -> status = s } }
        }
        result.onSuccess {
            Prefs.setupDone = true
            Ui.setupDone = true
        }.onFailure { error = shortError(it) }
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241542), Color.Black))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp).systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoMark(72.dp)
            Spacer(Modifier.height(20.dp))
            Text("Welcome to Audify", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Audify needs to download the latest version of its YouTube engine (yt-dlp) before you start. This only takes a moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            val err = error
            if (err == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(0.7f))
                Spacer(Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Update failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Check you're connected to Wi-Fi or mobile data, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { attempt++ }) { Text("Try again") }
                // Escape hatch so the app is still usable offline; setup will be asked for again next launch
                TextButton(onClick = { Ui.setupDone = true }) { Text("Skip for now") }
            }
        }
    }
}
