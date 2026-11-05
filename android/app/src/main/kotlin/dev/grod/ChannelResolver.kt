package dev.grod

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Turns a shared YouTube link into a [Channel]. Blocking network; call off the main thread. */
object ChannelResolver {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 4 shl 20

    fun resolve(url: String): Channel = when (val target = YouTubeLinks.classify(url)) {
        null -> throw ChannelLookupException("not a video or channel link")
        is LinkTarget.Channel -> YouTubeLinks.parseChannelPage(get(target.url))
        is LinkTarget.Video -> {
            // oEmbed names the uploader without an API key; a handle URL saves loading the channel page.
            val embed = YouTubeLinks.parseOEmbed(get(YouTubeLinks.oEmbedUrl(target.url)))
            YouTubeLinks.handleFromUrl(embed.authorUrl)?.let { Channel(embed.authorName, it) }
                ?: YouTubeLinks.parseChannelPage(get(embed.authorUrl))
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; grod)")
            conn.setRequestProperty("Accept-Language", "en")
            // Without it, EU-region requests get the cookie consent page instead of the channel page.
            conn.setRequestProperty("Cookie", "SOCS=CAI")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readNBytesCompat(MAX_BYTES) }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
