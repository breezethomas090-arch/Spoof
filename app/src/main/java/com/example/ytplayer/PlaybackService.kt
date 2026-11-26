package com.example.ytplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/** Foreground service that keeps playback alive with the screen off and shows media controls. */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_NEXT = "next"
        private const val ACTION_STOP = "stop"

        @Volatile
        private var running = false

        /** Start the service, or just refresh its notification if it's already running. */
        fun update(ctx: Context) {
            if (running) {
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(ctx))
            } else {
                val i = Intent(ctx, PlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }

        private fun buildNotification(ctx: Context): Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun action(icon: Int, title: String, act: String) = Notification.Action.Builder(
                Icon.createWithResource(ctx, icon), title,
                PendingIntent.getService(
                    ctx, act.hashCode(),
                    Intent(ctx, PlaybackService::class.java).setAction(act), flags
                )
            ).build()

            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)

            return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(Player.current?.let { prettyName(it) } ?: "YT Player")
                .setContentText(if (Player.isPaused) "Paused" else "Playing")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    if (Player.isPaused) action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE)
                    else action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                )
                .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
                .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after startForegroundService
        val n = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }

        when (intent?.action) {
            ACTION_TOGGLE -> Player.toggle(this)
            ACTION_NEXT -> Player.next(this)
            ACTION_STOP -> Player.stop(this)
        }
        if (!Player.hasTrack) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

fun prettyName(f: java.io.File) = f.nameWithoutExtension.replace('_', ' ')
