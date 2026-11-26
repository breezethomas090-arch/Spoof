package com.audify.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import coil.executeBlocking
import coil.imageLoader
import coil.request.ImageRequest
import java.util.concurrent.Executors

/** Home-screen widget: cover art, title, and previous / play-pause / next. */
class AudifyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, last ?: IDLE)
    }

    companion object {
        private data class State(
            val title: String,
            val artist: String,
            val playing: Boolean,
            val hasMedia: Boolean,
            val artKey: String?,
        )

        private val IDLE = State("Audify", "Tap to start listening", playing = false, hasMedia = false, artKey = null)
        private val io = Executors.newSingleThreadExecutor()

        @Volatile private var last: State? = null
        @Volatile private var artKey: String? = null
        @Volatile private var art: Bitmap? = null

        /** Called by the playback service whenever the player changes. */
        fun update(ctx: Context, p: Player) {
            val item = p.currentMediaItem
            val md = item?.mediaMetadata
            val st = if (item == null) IDLE else State(
                title = md?.title?.toString() ?: "Audify",
                artist = md?.artist?.toString() ?: "",
                playing = p.playWhenReady,
                hasMedia = true,
                artKey = md?.artworkUri?.toString(),
            )
            if (st == last) return
            last = st
            render(ctx, st)

            if (st.artKey != artKey) {
                artKey = st.artKey
                art = null
                val key = st.artKey ?: return
                io.execute {
                    val bmp = runCatching { loadArt(ctx, key) }.getOrNull()
                    if (artKey == key && bmp != null) {
                        art = bmp
                        last?.let { render(ctx, it) }
                    }
                }
            }
        }

        fun clear(ctx: Context) {
            last = null
            artKey = null
            art = null
            render(ctx, IDLE)
        }

        private fun loadArt(ctx: Context, key: String): Bitmap? {
            val req = ImageRequest.Builder(ctx).data(key).size(220).allowHardware(false).build()
            val src = ctx.imageLoader.executeBlocking(req).drawable?.toBitmap() ?: return null
            // Centre-crop to a rounded square
            val side = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply {
                    setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f)
                })
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            Canvas(out).drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), side * 0.12f, side * 0.12f, paint)
            return out
        }

        private fun render(ctx: Context, st: State) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AudifyWidget::class.java))
            if (ids.isEmpty()) return

            val v = RemoteViews(ctx.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.widget_title, st.title)
            v.setTextViewText(R.id.widget_artist, st.artist)
            v.setImageViewResource(R.id.widget_play, if (st.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            val bmp = art
            if (bmp != null && st.hasMedia) v.setImageViewBitmap(R.id.widget_art, bmp)
            else v.setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)

            val open = PendingIntent.getActivity(
                ctx, 100, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            v.setOnClickPendingIntent(R.id.widget_art, open)

            if (st.hasMedia) {
                v.setOnClickPendingIntent(R.id.widget_play, mediaButton(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, foreground = !st.playing))
                v.setOnClickPendingIntent(R.id.widget_next, mediaButton(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, foreground = false))
                v.setOnClickPendingIntent(R.id.widget_prev, mediaButton(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, foreground = false))
            } else {
                v.setOnClickPendingIntent(R.id.widget_play, open)
                v.setOnClickPendingIntent(R.id.widget_next, open)
                v.setOnClickPendingIntent(R.id.widget_prev, open)
            }
            mgr.updateAppWidget(ids, v)
        }

        /** Media-button intent handled by Media3's MediaSessionService. */
        private fun mediaButton(ctx: Context, keyCode: Int, foreground: Boolean): PendingIntent {
            val i = Intent(ctx, PlaybackService::class.java)
                .setAction(Intent.ACTION_MEDIA_BUTTON)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (foreground && Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(ctx, keyCode, i, flags)
            else PendingIntent.getService(ctx, keyCode, i, flags)
        }
    }
}
