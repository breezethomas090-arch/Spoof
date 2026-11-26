package com.example.ytplayer

import android.app.Application
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        thread(name = "startup") {
            Library.refresh(this)
            // Unpack yt-dlp / Python / ffmpeg now so the first search is quicker
            runCatching { Downloader.init(this) }
        }
    }
}
