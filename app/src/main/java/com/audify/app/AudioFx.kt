package com.audify.app

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import kotlin.math.ln

/**
 * Equalizer for everything Audify plays, using Android's built-in audio effects
 * attached to the player's audio session.
 *
 * Gains are stored for five standard frequencies and mapped onto whatever bands
 * the phone's equalizer actually has.
 */
object AudioFx {
    /** The five frequencies the sliders control (Hz). */
    val FREQS = intArrayOf(60, 230, 910, 3600, 14000)
    val LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
    const val MAX_DB = 12f

    data class Preset(val name: String, val gains: FloatArray, val bass: Int = 0, val loud: Int = 0)

    /** Gains in dB for 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz. Bass boost and loudness are 0–1000. */
    val PRESETS = listOf(
        Preset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
        Preset("Bass boost", floatArrayOf(7f, 4.5f, 0f, 0f, 0.5f), bass = 450),
        Preset("Extra bass", floatArrayOf(10f, 7f, 0.5f, -1f, 0f), bass = 800),
        Preset("Vocal", floatArrayOf(-2.5f, -1f, 3.5f, 4.5f, 1.5f)),
        Preset("Treble", floatArrayOf(-1f, -0.5f, 0f, 4f, 7f)),
        Preset("Pop", floatArrayOf(-1f, 2f, 4f, 2f, -0.5f)),
        Preset("Hip-hop", floatArrayOf(6f, 4f, -1f, 1.5f, 2.5f), bass = 300),
        Preset("Rock", floatArrayOf(4.5f, 2f, -2f, 2.5f, 5f)),
        Preset("Electronic", floatArrayOf(5f, 2.5f, 0f, 2f, 5f), bass = 250),
        Preset("Acoustic", floatArrayOf(3f, 1.5f, 1f, 2.5f, 3f)),
        Preset("Classical", floatArrayOf(3.5f, 2f, -1f, 2f, 3.5f)),
        Preset("Late night", floatArrayOf(2f, 1f, 1f, 1f, -1f), loud = 400),
        Preset("Spoken word", floatArrayOf(-3f, 0f, 4f, 3f, -1f)),
    )

    // ---------------------------------------------------------------- settings (Compose state)

    var enabled by mutableStateOf(false)
        private set
    var presetName by mutableStateOf("Flat")
        private set
    var gains by mutableStateOf(FloatArray(5))
        private set
    var bassBoost by mutableStateOf(0)          // 0..1000
        private set
    var surround by mutableStateOf(0)           // 0..1000
        private set
    var loudness by mutableStateOf(0)           // 0..1000 (≈ up to +10 dB)
        private set

    /** Set if this phone doesn't allow audio effects. */
    var unsupported by mutableStateOf<String?>(null)
        private set

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE)
        enabled = sp.getBoolean("enabled", false)
        presetName = sp.getString("preset", "Flat") ?: "Flat"
        gains = runCatching {
            val a = JSONArray(sp.getString("gains", "[0,0,0,0,0]"))
            FloatArray(5) { a.optDouble(it, 0.0).toFloat() }
        }.getOrDefault(FloatArray(5))
        bassBoost = sp.getInt("bass", 0)
        surround = sp.getInt("surround", 0)
        loudness = sp.getInt("loud", 0)
    }

    private fun save() {
        sp.edit()
            .putBoolean("enabled", enabled)
            .putString("preset", presetName)
            .putString("gains", JSONArray(gains.map { it.toDouble() }).toString())
            .putInt("bass", bassBoost)
            .putInt("surround", surround)
            .putInt("loud", loudness)
            .apply()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        save()
        apply()
    }

    fun choosePreset(p: Preset) {
        presetName = p.name
        gains = p.gains.copyOf()
        bassBoost = p.bass
        loudness = p.loud
        if (!enabled) enabled = true
        save()
        apply()
    }

    fun setGain(index: Int, db: Float) {
        gains = gains.copyOf().also { it[index] = db.coerceIn(-MAX_DB, MAX_DB) }
        presetName = "Custom"
        if (!enabled) enabled = true
        save()
        apply()
    }

    fun setBass(v: Int) {
        bassBoost = v.coerceIn(0, 1000)
        presetName = if (presetName in PRESETS.map { it.name } && PRESETS.first { it.name == presetName }.bass != bassBoost) "Custom" else presetName
        if (!enabled) enabled = true
        save()
        apply()
    }

    fun setSurround(v: Int) {
        surround = v.coerceIn(0, 1000)
        if (!enabled) enabled = true
        save()
        apply()
    }

    fun setLoudness(v: Int) {
        loudness = v.coerceIn(0, 1000)
        if (!enabled) enabled = true
        save()
        apply()
    }

    fun reset() = choosePreset(PRESETS.first()).also { setSurround(0) }

    // ---------------------------------------------------------------- live effects

    private var sessionId = 0
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    /** Called by the playback service with the player's audio session. */
    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        try {
            eq = Equalizer(0, audioSessionId)
            unsupported = null
        } catch (e: Exception) {
            unsupported = "Your phone doesn't allow apps to use its equalizer."
        }
        bass = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        virt = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        apply()
    }

    @Synchronized
    fun release() {
        listOf(eq, bass, virt, loud).forEach { runCatching { it?.release() } }
        eq = null
        bass = null
        virt = null
        loud = null
        sessionId = 0
    }

    @Synchronized
    fun apply() {
        val on = enabled
        eq?.let { e ->
            runCatching {
                val (minMb, maxMb) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
                for (band in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(band.toShort()) / 1000f
                    val db = if (on) gainAt(hz) else 0f
                    e.setBandLevel(band.toShort(), (db * 100).toInt().coerceIn(minMb, maxMb).toShort())
                }
                e.enabled = on
            }
        }
        bass?.let { b ->
            runCatching {
                if (b.strengthSupported) b.setStrength((if (on) bassBoost else 0).toShort())
                b.enabled = on && bassBoost > 0
            }
        }
        virt?.let { v ->
            runCatching {
                if (v.strengthSupported) v.setStrength((if (on) surround else 0).toShort())
                v.enabled = on && surround > 0
            }
        }
        loud?.let { l ->
            runCatching {
                l.setTargetGain(if (on) loudness else 0)   // millibels: 1000 = +10 dB
                l.enabled = on && loudness > 0
            }
        }
    }

    /** Gain (dB) at any frequency, interpolated between the five slider points on a log scale. */
    fun gainAt(hz: Float): Float {
        val g = gains
        if (hz <= FREQS.first()) return g.first()
        if (hz >= FREQS.last()) return g.last()
        for (i in 0 until FREQS.size - 1) {
            val f0 = FREQS[i].toFloat()
            val f1 = FREQS[i + 1].toFloat()
            if (hz in f0..f1) {
                val t = (ln(hz / f0) / ln(f1 / f0))
                return g[i] + (g[i + 1] - g[i]) * t
            }
        }
        return 0f
    }
}
