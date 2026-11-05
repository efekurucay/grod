package dev.grod

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import org.json.JSONException
import org.json.JSONObject

/** What a shared YouTube link points to; [url] is canonical on www.youtube.com. */
sealed class LinkTarget(val url: String) {
    class Video(url: String) : LinkTarget(url)
    class Channel(url: String) : LinkTarget(url)
}

data class OEmbed(val authorName: String, val authorUrl: String)

class ChannelLookupException(message: String) : Exception(message)

/** Pure parsing for the share flow, so it is unit tested without network or Android. */
object YouTubeLinks {
    private val URL = Regex("""https?://(?:[a-z0-9-]+\.)*(?:youtube\.com|youtu\.be)/\S*""", RegexOption.IGNORE_CASE)
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,}$")

    /** First youtube.com / youtu.be link in shared text ("Check this out https://youtu.be/…"). */
    fun extractUrl(text: String): String? = URL.find(text)?.value

    fun classify(url: String): LinkTarget? {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        fun video(id: String?) = id?.takeIf(VIDEO_ID::matches)?.let { LinkTarget.Video("https://www.youtube.com/watch?v=$it") }
        if (host == "youtu.be") return video(segments.firstOrNull())
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return null
        val first = segments.firstOrNull() ?: return null
        return when {
            first == "watch" -> video(queryParam(uri.rawQuery, "v"))
            first == "shorts" || first == "live" -> video(segments.getOrNull(1))
            first.startsWith("@") -> LinkTarget.Channel("https://www.youtube.com/$first")
            first in setOf("channel", "c", "user") && segments.size > 1 ->
                LinkTarget.Channel("https://www.youtube.com/$first/${segments[1]}")
            else -> null
        }
    }

    fun oEmbedUrl(videoUrl: String): String =
        "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(videoUrl, "UTF-8")

    fun parseOEmbed(json: String): OEmbed {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ChannelLookupException("oEmbed answer is not JSON")
        }
        val name = o.optString("author_name").takeIf { it.isNotEmpty() }
        val url = o.optString("author_url").takeIf { it.isNotEmpty() }
        if (name == null || url == null) throw ChannelLookupException("oEmbed answer has no author")
        return OEmbed(name, url)
    }

    /** "@Çin…" from ".../@%C3%87in…"; null when the URL is not a handle URL (/channel/UC…, /user/…). */
    fun handleFromUrl(url: String): String? {
        val path = try {
            URI(url).rawPath
        } catch (e: URISyntaxException) {
            return null
        } ?: return null
        val segment = path.split('/').firstOrNull { it.isNotEmpty() } ?: return null
        return if (segment.startsWith("@") && segment.length > 1) normalizeHandle(segment) else null
    }

    fun parseChannelPage(html: String): Channel {
        val name = find(html, """<meta property="og:title" content="([^"]*)"""")
        val handle = find(html, """"vanityChannelUrl":"https?://www\.youtube\.com/(@[^"]+)"""")
            ?: find(html, """<link itemprop="url" href="https?://www\.youtube\.com/(@[^"]+)"""")
            ?: find(html, """"canonicalBaseUrl":"/(@[^"]+)"""")
        val id = find(html, """<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[A-Za-z0-9_-]{22})"""")
            ?: find(html, """"externalId":"(UC[A-Za-z0-9_-]{22})"""")
        if (name.isNullOrEmpty() || handle == null) throw ChannelLookupException("channel page shows no name and handle")
        return Channel(decodeEntities(name), normalizeHandle(handle), id)
    }

    private fun find(text: String, pattern: String): String? = Regex(pattern).find(text)?.groupValues?.get(1)

    private fun queryParam(rawQuery: String?, key: String): String? = rawQuery.orEmpty().split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it[0] == key }
        ?.getOrNull(1)

    private fun decodeEntities(s: String): String = s
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")
}
