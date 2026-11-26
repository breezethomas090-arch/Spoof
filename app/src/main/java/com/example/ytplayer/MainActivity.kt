package com.example.ytplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import android.graphics.Color as AColor

class MainActivity : ComponentActivity() {

    /** 0 = Search, 1 = Library. Lives here so a share can switch tabs. */
    private val tab = mutableIntStateOf(0)

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
            YTTheme {
                AppRoot(tab)
            }
        }
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        PlayerConn.connect(this)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) PlayerConn.disconnect()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = Regex("""https?://\S+""").find(text)?.value ?: return
        intent.action = null
        Downloads.enqueue(this, link, title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: link)
        tab.intValue = 1
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
    }
}
