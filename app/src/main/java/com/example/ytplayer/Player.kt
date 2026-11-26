package com.example.ytplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import java.io.File

/** App-wide playback state. Lives outside the Activity so music keeps going in the background. */
object Player {

    var tracks: List<File> = emptyList()
        private set
    var index = -1
        private set
    var isPaused = false
        private set

    /** Called on the main thread whenever track / play state changes. */
    var onChange: (() -> Unit)? = null

    private var mp: MediaPlayer? = null

    val hasTrack get() = mp != null
    val isPlaying get() = mp?.isPlaying == true
    val current: File? get() = tracks.getOrNull(index)
    val position: Int get() = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0)
    val duration: Int get() = runCatching { mp?.duration ?: 0 }.getOrDefault(0)

    fun setTracks(list: List<File>) {
        val playing = current
        tracks = list
        index = if (playing != null) list.indexOf(playing) else -1
    }

    /** Returns false if the file couldn't be played. */
    fun play(ctx: Context, i: Int): Boolean {
        if (tracks.isEmpty()) return false
        val app = ctx.applicationContext
        val idx = ((i % tracks.size) + tracks.size) % tracks.size
        release()
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(tracks[idx].absolutePath)
                setOnCompletionListener { next(app) }
                setOnErrorListener { _, _, _ -> stop(app); true }
                prepare()
                start()
            }
            index = idx
            isPaused = false
            PlaybackService.update(app)
            return true
        } catch (e: Exception) {
            release()
            PlaybackService.stop(app)
            return false
        } finally {
            onChange?.invoke()
        }
    }

    fun toggle(ctx: Context) {
        val p = mp
        when {
            p == null -> play(ctx, maxOf(index, 0))
            p.isPlaying -> { p.pause(); isPaused = true }
            else -> { p.start(); isPaused = false }
        }
        PlaybackService.update(ctx.applicationContext)
        onChange?.invoke()
    }

    fun next(ctx: Context) {
        play(ctx, index + 1)
    }

    fun prev(ctx: Context) {
        if (position > 3000) seekTo(0) else play(ctx, index - 1)
    }

    fun seekTo(ms: Int) {
        mp?.seekTo(ms)
    }

    fun stop(ctx: Context) {
        release()
        isPaused = false
        PlaybackService.stop(ctx.applicationContext)
        onChange?.invoke()
    }

    private fun release() {
        mp?.run {
            setOnCompletionListener(null)
            runCatching { stop() }
            release()
        }
        mp = null
    }
}
