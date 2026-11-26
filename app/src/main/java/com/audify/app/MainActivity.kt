package com.audify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Color as AColor

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AudifyTheme {
                AppRoot()
            }
        }
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PlayerConn.connect(this)
    }

    override fun onDestroy() {
        // Keep the connection while the DJ is running so it can keep talking in the background
        if (!isChangingConfigurations && !Dj.active) PlayerConn.disconnect()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Shared YouTube links ("Share → Audify") and audify:// links / QR codes. */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        intent?.action = null
        val link = Regex("""(audify://\S+|https?://\S+)""").find(text)?.value ?: text
        if (SpotifyImport.isSpotifyLink(link)) {
            SpotifyImport.start(this, link)
            return
        }
        if (YtMusicImport.isPlaylistLink(link)) {
            YtMusicImport.start(this, link)
            return
        }
        // Player connection may still be starting: give it a moment
        window.decorView.postDelayed({ Ui.openCode(this, link) }, 600)
    }
}
