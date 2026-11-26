package com.audify.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Audify DJ: builds a personal set in sections (your favourites, more like your top artist,
 * what's new, a throwback) and talks you through it, Spotify-DJ style.
 *
 * The voice is the phone's text-to-speech. The words come from built-in lines that use your
 * listening history, or from Claude if you add an Anthropic API key in Settings.
 */
object Dj {
    class Segment(val kind: String, val title: String, val songs: List<Song>, var line: String = "")

    var active by mutableStateOf(false)
        private set
    var building by mutableStateOf(false)
        private set
    /** What the DJ last said (shown in the player). */
    var line by mutableStateOf<String?>(null)
        private set
    var speaking by mutableStateOf(false)
        private set
    var sectionTitle by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val starts = HashMap<String, Segment>()     // first song's media id -> its section
    private var segments = ArrayList<Segment>()
    private var spoken = HashSet<Segment>()

    // ---------------------------------------------------------------- start / stop

    fun start(ctx: Context) {
        if (building) return
        app = ctx.applicationContext
        building = true
        line = "Putting your set together…"
        DjVoice.init(ctx.applicationContext)
        thread(name = "dj-build") {
            val segs = runCatching { buildSegments(ctx.applicationContext) }.getOrDefault(emptyList())
            // Write the first intro before playing so the DJ can open the set
            segs.firstOrNull()?.let { it.line = Writer.write(ctx.applicationContext, it, first = true) }
            main.post {
                building = false
                if (segs.isEmpty()) {
                    line = null
                    Ui.toast(ctx, "The DJ couldn't build a set right now. Check your connection.")
                    return@post
                }
                play(segs, replace = true)
            }
        }
    }

    fun stop() {
        active = false
        line = null
        sectionTitle = null
        starts.clear()
        segments.clear()
        spoken.clear()
        DjVoice.stop()
        PlayerConn.duck(false)
    }

    /** "Switch it up": skip to the next section, building a fresh one if needed. */
    fun switchItUp(ctx: Context) {
        if (!active) return start(ctx)
        val currentId = PlayerConn.current?.mediaId
        val currentIdx = segments.indexOfFirst { seg -> seg.songs.any { PlayerConn.itemFor(it).mediaId == currentId } }
        val next = segments.getOrNull(currentIdx + 1)
        if (next != null) {
            val idx = PlayerConn.queueIndexOf(PlayerConn.itemFor(next.songs.first()).mediaId)
            if (idx >= 0) {
                PlayerConn.seekToIndex(idx)
                return
            }
        }
        // Out of sections: make a fresh batch and jump to it
        building = true
        line = "Switching it up…"
        thread(name = "dj-more") {
            val segs = runCatching { buildSegments(ctx.applicationContext) }.getOrDefault(emptyList())
            main.post {
                building = false
                if (segs.isNotEmpty()) play(segs, replace = false)
            }
        }
    }

    private fun play(segs: List<Segment>, replace: Boolean) {
        segs.forEach { seg -> starts[PlayerConn.itemFor(seg.songs.first()).mediaId] = seg }
        segments.addAll(segs)
        val songs = segs.flatMap { it.songs }
        if (replace) {
            spoken.clear()
            PlayerConn.playSongs(songs, 0, inOrder = true)   // DJ sets play in order
            active = true
            // Transition for the first song may already have fired
            onTransition(PlayerConn.current)
        } else {
            PlayerConn.appendSongs(songs)
            val idx = PlayerConn.queueIndexOf(PlayerConn.itemFor(songs.first()).mediaId)
            if (idx >= 0) PlayerConn.seekToIndex(idx)
        }
        // Write the rest of the intros in the background
        val ctx = app ?: return
        thread(name = "dj-lines") {
            segs.drop(if (replace) 1 else 0).forEach { if (it.line.isEmpty()) it.line = Writer.write(ctx, it, first = false) }
        }
    }

    /** Called on every song change: when a new section starts, the DJ talks over the intro. */
    fun onTransition(item: MediaItem?) {
        if (!active || item == null) return
        val seg = starts[item.mediaId] ?: return
        if (!spoken.add(seg)) return
        sectionTitle = seg.title
        val ctx = app ?: return
        val say = { text: String ->
            line = text
            if (Prefs.djVoice) {
                speaking = true
                PlayerConn.duck(true)
                DjVoice.speak(text) {
                    main.post {
                        speaking = false
                        PlayerConn.duck(false)
                    }
                }
            }
        }
        if (seg.line.isNotEmpty()) say(seg.line)
        else thread { val t = Writer.write(ctx, seg, first = false).also { seg.line = it }; main.post { say(t) } }
    }

    // ---------------------------------------------------------------- building the set

    private fun buildSegments(ctx: Context): List<Segment> {
        fun playable(s: Song) = s.isYouTube || Library.byId(s.id) != null
        val history = Store.history.value.filter(::playable)
        val liked = Store.liked.value.filter(::playable)
        val used = HashSet<String>()
        fun fresh(list: List<Song>, n: Int) = list.filter { used.add(it.id) }.take(n)
        val out = ArrayList<Segment>()

        // 1) Songs you love
        val favs = fresh((liked.shuffled() + history.take(25).shuffled()).distinctBy { it.id }, 4)
        if (favs.size >= 2) out += Segment("favourites", "Your favourites", favs)

        // 2) More like your most-played artist
        val topArtist = (history + liked).filter { it.artist.isNotBlank() }
            .groupingBy { primaryArtist(it.artist) }.eachCount().maxByOrNull { it.value }?.key
        val seed = history.firstOrNull { primaryArtist(it.artist) == topArtist && it.isYouTube }
            ?: history.firstOrNull { it.isYouTube } ?: liked.firstOrNull { it.isYouTube }
        if (seed != null) {
            val rel = fresh(runCatching { Downloader.related(ctx, seed.id, 25) }.getOrDefault(emptyList()).shuffled(), 4)
            if (rel.isNotEmpty()) out += Segment("related", "More like ${topArtist ?: primaryArtist(seed.artist)}", rel)
        }

        // 3) Something new that's blowing up
        val viral = fresh(runCatching { Downloader.viral(ctx, 25) }.getOrDefault(emptyList()).shuffled(), 4)
        if (viral.isNotEmpty()) out += Segment("new", "Fresh finds", viral)

        // 4) A throwback from further back in your history
        val throwback = fresh(history.drop(15).shuffled(), 3)
        if (throwback.size >= 2) out += Segment("throwback", "Throwback", throwback)

        if (out.isEmpty()) {
            val pop = fresh(runCatching { Downloader.genre(ctx, "Pop", 20) }.getOrDefault(emptyList()).shuffled(), 6)
            if (pop.isNotEmpty()) out += Segment("new", "Today's hits", pop)
        }
        return out
    }

    // ---------------------------------------------------------------- what the DJ says

    private object Writer {
        fun write(ctx: Context, seg: Segment, first: Boolean): String {
            val key = Prefs.claudeKey
            if (key.isNotBlank()) runCatching { claude(key, seg, first) }.getOrNull()?.let { if (it.isNotBlank()) return it }
            return template(seg, first)
        }

        private fun timeOfDay(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            in 18..22 -> "evening"
            else -> "late night"
        }

        private fun template(seg: Segment, first: Boolean): String {
            val name = Profile.name.takeIf { it != "Listener" } ?: "friend"
            val s = seg.songs.first()
            val who = primaryArtist(s.artist).ifBlank { "this one" }
            val hello = if (first) listOf(
                "Hey $name, it's your Audify DJ. Good ${timeOfDay()}.",
                "What's up $name, DJ here, let's get this ${timeOfDay()} going.",
                "Hi $name, welcome back. I've got a set lined up just for you.",
            ).random() + " " else ""
            val body = when (seg.kind) {
                "favourites" -> listOf(
                    "Let's start with some songs you just can't get enough of. Here's ${s.title} by $who.",
                    "Kicking off with a few of your favourites, starting with $who.",
                    "You know these ones. Up first, ${s.title}.",
                ).random()
                "related" -> listOf(
                    "You've had ${seg.title.removePrefix("More like ")} on repeat lately, so here's more in that lane. This is $who.",
                    "Staying close to what you love. Try this one from $who.",
                    "Based on what you've been playing, I think you'll like ${s.title}.",
                ).random()
                "new" -> listOf(
                    "Time for something new. These are blowing up right now, starting with $who.",
                    "Let's switch it up with some fresh finds. Here's ${s.title}.",
                    "Everyone's playing this one at the moment. Check out $who.",
                ).random()
                "throwback" -> listOf(
                    "Let's take it back a little. Remember this? ${s.title}.",
                    "Throwback time. You haven't played this one in a while.",
                    "A blast from your past, here's $who.",
                ).random()
                else -> "Here's ${s.title} by $who."
            }
            return hello + body
        }

        /** Optional: Claude writes the intro (uses the user's own Anthropic API key). */
        private fun claude(key: String, seg: Segment, first: Boolean): String {
            val songs = seg.songs.joinToString("; ") { "${it.title} by ${primaryArtist(it.artist)}" }
            val prompt = buildString {
                append("You are the friendly radio DJ inside a music app called Audify, speaking out loud to ")
                append(Profile.name).append(". It is ").append(timeOfDay()).append(". ")
                if (first) append("This is the start of their DJ session, so greet them briefly. ")
                append("Introduce this section of their personal mix: \"").append(seg.title).append("\". ")
                append("The songs are: ").append(songs).append(". Mention the first song. ")
                append("Reply with only what you'd say: one or two short sentences, under 40 words, ")
                append("warm and natural for speech, no emojis, no stage directions, no quotation marks.")
            }
            val body = JSONObject()
                .put("model", "claude-haiku-5-5")
                .put("max_tokens", 120)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 8_000
                c.readTimeout = 12_000
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-api-key", key)
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode != 200) return ""
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val content = o.optJSONArray("content") ?: return ""
                return (0 until content.length())
                    .mapNotNull { content.optJSONObject(it)?.takeIf { b -> b.optString("type") == "text" }?.optString("text") }
                    .joinToString(" ").trim().trim('"')
            } finally {
                c.disconnect()
            }
        }
    }

    // ---------------------------------------------------------------- the voice

    private object DjVoice {
        private var tts: TextToSpeech? = null
        private var ready = false
        private var pending: Pair<String, () -> Unit>? = null

        fun init(ctx: Context) {
            if (tts != null) return
            tts = TextToSpeech(ctx) init@{ status ->
                if (status != TextToSpeech.SUCCESS) return@init
                val t = tts ?: return@init
                t.language = Locale.UK
                // Prefer a high-quality voice that works offline
                runCatching {
                    t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && it.quality >= android.speech.tts.Voice.QUALITY_HIGH }
                        ?.maxByOrNull { it.quality }?.let { t.voice = it }
                }
                t.setSpeechRate(1.04f)
                ready = true
                pending?.let { (text, done) -> speak(text, done) }
                pending = null
            }
        }

        fun speak(text: String, done: () -> Unit) {
            val t = tts
            if (t == null || !ready) {
                pending = text to done
                return
            }
            val id = "dj-${System.nanoTime()}"
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = done()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = done()
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }

        fun stop() {
            pending = null
            runCatching { tts?.stop() }
        }
    }
}
