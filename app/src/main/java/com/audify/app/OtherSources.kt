package com.audify.app

import android.content.Context
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free music platforms besides YouTube:
 *  - SoundCloud and Bandcamp, through NewPipeExtractor
 *  - Audius, through its free public API (https://api.audius.co/v1)
 */
object OtherSources {
    private const val AUDIUS = "https://api.audius.co/v1"
    private const val APP_NAME = "Audify"

    /** Where a playable stream lives, and whether it's an HLS playlist. */
    data class Stream(val url: String, val mimeType: String?, val ext: String)

    // ---------------------------------------------------------------- search

    fun search(source: String, query: String, limit: Int = 20): List<Song> = when (source) {
        "sc" -> newPipeSearch(ServiceList.SoundCloud, "sc", query, listOf("tracks"), limit)
        "bc" -> newPipeSearch(ServiceList.Bandcamp, "bc", query, emptyList(), limit)
        "au" -> audiusSearch(query, limit)
        else -> emptyList()
    }

    private fun newPipeSearch(service: StreamingService, src: String, query: String, filters: List<String>, limit: Int): List<Song> {
        NewPipeSource.ensureInit()
        val handler = runCatching { service.searchQHFactory.fromQuery(query, filters, "") }
            .getOrElse { service.searchQHFactory.fromQuery(query, emptyList(), "") }
        val info = SearchInfo.getInfo(service, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().take(limit).map { it.toSong(src) }
    }

    private fun StreamInfoItem.toSong(src: String): Song {
        val art = thumbnails.maxByOrNull { it.height }?.url
        return Song(
            id = src + ":" + Integer.toHexString(url.hashCode()) + url.length.toString(36),
            title = name,
            artist = uploaderName ?: "",
            durationSec = duration.toInt().coerceAtLeast(0),
            source = src,
            page = url,
            art = art,
        )
    }

    private fun audiusSearch(query: String, limit: Int): List<Song> {
        val body = get("$AUDIUS/tracks/search?query=${URLEncoder.encode(query, "UTF-8")}&app_name=$APP_NAME")
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Song>()
        for (i in 0 until data.length()) {
            val t = data.optJSONObject(i) ?: continue
            if (t.has("is_streamable") && !t.optBoolean("is_streamable", true)) continue
            val id = t.optString("id")
            if (id.isEmpty()) continue
            val art = t.optJSONObject("artwork")?.let { a -> a.optString("480x480").ifEmpty { a.optString("150x150") } }?.ifEmpty { null }
            val permalink = t.optString("permalink")
            out += Song(
                id = "au:$id",
                title = t.optString("title"),
                artist = t.optJSONObject("user")?.optString("name") ?: "",
                durationSec = t.optInt("duration"),
                source = "au",
                page = if (permalink.startsWith("/")) "https://audius.co$permalink" else "https://audius.co",
                art = art,
            )
            if (out.size >= limit) break
        }
        return out
    }

    // ---------------------------------------------------------------- playback / download

    /** A direct stream for a non-YouTube song. */
    fun stream(ctx: Context, s: Song): Stream {
        if (s.source == "au") {
            val id = s.id.removePrefix("au:")
            return Stream("$AUDIUS/tracks/$id/stream?app_name=$APP_NAME", null, "mp3")
        }
        val page = s.page ?: throw IOException("No link for this song")
        // NewPipe first
        runCatching {
            NewPipeSource.ensureInit()
            val info = StreamInfo.getInfo(page)
            val audio = info.audioStreams.filter { it.isUrl }
            val progressive = audio.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.averageBitrate }
            if (progressive != null) return Stream(progressive.content, null, progressive.format?.suffix ?: "mp3")
            val hls = audio.firstOrNull { it.deliveryMethod == DeliveryMethod.HLS }
            if (hls != null) return Stream(hls.content, MimeTypes.APPLICATION_M3U8, "m4a")
        }
        // yt-dlp fallback (it supports SoundCloud and Bandcamp too)
        val url = Downloader.directUrl(ctx, page)
        return Stream(url, if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null, "mp3")
    }

    // ---------------------------------------------------------------- shared links

    fun isSupportedPage(text: String): Boolean =
        text.contains("soundcloud.com/") || text.contains("bandcamp.com/") || text.contains("audius.co/")

    /** Builds a Song from a SoundCloud / Bandcamp / Audius link. */
    fun songFromPage(page: String): Song {
        if (page.contains("audius.co/")) {
            val body = get("$AUDIUS/resolve?url=${URLEncoder.encode(page, "UTF-8")}&app_name=$APP_NAME")
            val t = JSONObject(body).optJSONObject("data") ?: throw IOException("Couldn't open that Audius link")
            val art = t.optJSONObject("artwork")?.optString("480x480")?.ifEmpty { null }
            return Song("au:" + t.optString("id"), t.optString("title"), t.optJSONObject("user")?.optString("name") ?: "",
                t.optInt("duration"), "au", page, art)
        }
        NewPipeSource.ensureInit()
        val info = StreamInfo.getInfo(page)
        val src = if (page.contains("soundcloud.com")) "sc" else "bc"
        return Song(
            id = src + ":" + Integer.toHexString(page.hashCode()) + page.length.toString(36),
            title = info.name,
            artist = info.uploaderName ?: "",
            durationSec = info.duration.toInt().coerceAtLeast(0),
            source = src,
            page = page,
            art = info.thumbnails.maxByOrNull { it.height }?.url,
        )
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw IOException("Audius returned an error (${c.responseCode})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
