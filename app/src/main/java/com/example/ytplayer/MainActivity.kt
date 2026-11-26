package com.example.ytplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var url: EditText
    private lateinit var downloadBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var keepVideo: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playPause: ImageButton

    private lateinit var adapter: ArrayAdapter<String>
    private val ui = Handler(Looper.getMainLooper())
    private var downloading = false
    private var cancelled = false
    private var dragging = false

    private val ticker = object : Runnable {
        override fun run() {
            if (Player.hasTrack && !dragging) {
                seek.max = Player.duration.coerceAtLeast(1)
                seek.progress = Player.position
                time.text = "${fmt(Player.position)} / ${fmt(Player.duration)}"
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC

        url = findViewById(R.id.url)
        downloadBtn = findViewById(R.id.download)
        updateBtn = findViewById(R.id.update)
        keepVideo = findViewById(R.id.keepVideo)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        nowPlaying = findViewById(R.id.nowPlaying)
        seek = findViewById(R.id.seek)
        time = findViewById(R.id.time)
        playPause = findViewById(R.id.playPause)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(pos); true }

        downloadBtn.setOnClickListener { if (downloading) cancelDownload() else startDownload() }
        url.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { startDownload(); true } else false
        }
        updateBtn.setOnClickListener { updateYtDlp() }

        playPause.setOnClickListener { Player.toggle(this) }
        findViewById<ImageButton>(R.id.stop).setOnClickListener { Player.stop(this) }
        findViewById<ImageButton>(R.id.next).setOnClickListener { Player.next(this) }
        findViewById<ImageButton>(R.id.prev).setOnClickListener { Player.prev(this) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) time.text = "${fmt(value)} / ${fmt(Player.duration)}"
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                Player.seekTo(s.progress)
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Unpack yt-dlp / Python / ffmpeg in the background so the first download starts faster
        status.text = "Preparing yt-dlp..."
        Thread {
            val msg = try {
                Downloader.init(this); "Ready"
            } catch (e: Exception) {
                "Init failed: ${e.message}"
            }
            runOnUiThread { if (!downloading) status.text = msg }
        }.start()

        refresh()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Player.onChange = { updatePlayerUi() }
        updatePlayerUi()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Player.onChange = null
        ui.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------- library

    private fun refresh() {
        val files = Downloader.audioDir(this).listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTS }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        Player.setTracks(files)
        adapter.clear()
        adapter.addAll(files.map { prettyName(it) })
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        updatePlayerUi()
    }

    private fun confirmDelete(pos: Int) {
        val f = Player.tracks.getOrNull(pos) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete track?")
            .setMessage(prettyName(f))
            .setPositiveButton("Delete") { _, _ ->
                if (Player.current == f) Player.stop(this)
                f.delete()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------- playback UI

    private fun play(pos: Int) {
        if (!Player.play(this, pos)) toast("Can't play that file")
    }

    private fun updatePlayerUi() {
        val cur = Player.current
        nowPlaying.text = when {
            cur == null || !Player.hasTrack -> "Nothing playing"
            Player.isPaused -> "⏸  ${prettyName(cur)}"
            else -> "▶  ${prettyName(cur)}"
        }
        playPause.setImageResource(
            if (Player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        if (Player.hasTrack && Player.index >= 0) {
            list.setItemChecked(Player.index, true)
        } else {
            list.clearChoices()
            seek.progress = 0
            time.text = "0:00 / 0:00"
        }
        list.invalidateViews()
    }

    // ---------------------------------------------------------- download

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = Regex("""https?://\S+""").find(text)?.value ?: return
        url.setText(link)
        intent.action = null    // don't re-trigger on rotation / resume
        if (!downloading) startDownload()
    }

    private fun startDownload() {
        val link = url.text.toString().trim()
        if (link.isEmpty() || downloading) return
        downloading = true
        cancelled = false
        downloadBtn.text = "Cancel"
        updateBtn.isEnabled = false
        progress.isIndeterminate = true
        status.text = "Starting..."
        val keep = keepVideo.isChecked

        Thread {
            try {
                Downloader.init(this)
                val mp3 = Downloader.fetch(this, link, keep) { pct, line ->
                    runOnUiThread {
                        if (pct >= 0) {
                            progress.isIndeterminate = false
                            progress.progress = pct.toInt()
                        } else {
                            progress.isIndeterminate = true   // ffmpeg extracting: no % available
                        }
                        if (line.isNotBlank()) status.text = line
                    }
                }
                runOnUiThread {
                    finishDownload("Added: ${prettyName(mp3)}")
                    url.setText("")
                    refresh()
                    if (!Player.hasTrack) play(Player.tracks.indexOf(mp3))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    finishDownload(if (cancelled) "Cancelled" else "Failed: ${e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e}")
                }
            }
        }.start()
    }

    private fun cancelDownload() {
        cancelled = true
        status.text = "Cancelling..."
        Downloader.cancel()
    }

    private fun finishDownload(msg: String) {
        downloading = false
        downloadBtn.text = "Download"
        updateBtn.isEnabled = true
        progress.isIndeterminate = false
        progress.progress = 0
        status.text = msg
    }

    private fun updateYtDlp() {
        updateBtn.isEnabled = false
        status.text = "Updating yt-dlp..."
        Thread {
            val msg = try {
                Downloader.init(this)
                "yt-dlp: ${Downloader.updateYtDlp(this)}"
            } catch (e: Exception) {
                "Update failed: ${e.message}"
            }
            runOnUiThread {
                updateBtn.isEnabled = true
                status.text = msg
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val AUDIO_EXTS = setOf("mp3", "m4a", "ogg", "opus", "wav")
        private fun fmt(ms: Int): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
