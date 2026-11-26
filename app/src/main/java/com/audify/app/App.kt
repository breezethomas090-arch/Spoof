package com.audify.app

import android.app.Application
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        Profile.init(this)
        BuildConfigVersion.init(this)
        AudioFx.init(this)
        Ui.setupDone = Prefs.setupDone
        thread(name = "startup") {
            Library.refresh(this)
            // Unpack yt-dlp / Python / ffmpeg now so the first search is quicker
            runCatching { Downloader.init(this) }
            // Keep yt-dlp fresh: quietly check for a new version every 3 days
            val threeDays = 3L * 24 * 60 * 60 * 1000
            if (Prefs.setupDone && System.currentTimeMillis() - Downloader.lastUpdateCheck(this) > threeDays) {
                runCatching { Downloader.updateYtDlp(this) }
            }
        }
    }
}
