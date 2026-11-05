package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YouTubeLinksTest {
    private fun target(url: String) = YouTubeLinks.classify(url)

    @Test
    fun extractsFirstYouTubeLink() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            YouTubeLinks.extractUrl("Watch \"Song\" on YouTube https://youtu.be/dQw4w9WgXcQ?si=abc and more"),
        )
        assertEquals("https://m.youtube.com/@CemYilmaz", YouTubeLinks.extractUrl("https://m.youtube.com/@CemYilmaz"))
        assertNull(YouTubeLinks.extractUrl("https://example.com/watch?v=x"))
    }

    @Test
    fun classifiesVideoLinks() {
        val expected = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        for (url in listOf(
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42",
            "https://youtube.com/shorts/dQw4w9WgXcQ?si=abc",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
        )) {
            val t = target(url)
            assertTrue(url, t is LinkTarget.Video)
            assertEquals(url, expected, t!!.url)
        }
    }

    @Test
    fun classifiesChannelLinks() {
        assertEquals("https://www.youtube.com/@CemYilmaz", (target("https://youtube.com/@CemYilmaz?si=x") as LinkTarget.Channel).url)
        assertEquals("https://www.youtube.com/@%C3%87inMal%C4%B1", target("https://www.youtube.com/@%C3%87inMal%C4%B1/videos")!!.url)
        assertEquals(
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
            (target("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv/featured") as LinkTarget.Channel).url,
        )
        assertNull(target("https://www.youtube.com/feed/trending"))
        assertNull(target("https://www.youtube.com/watch"))
        assertNull(target("https://notyoutube.com/@x"))
    }

    @Test
    fun parsesOEmbedAndHandleUrl() {
        val embed = YouTubeLinks.parseOEmbed(
            """{"title":"Genel Müdür","author_name":"Cem Yılmaz","author_url":"https://www.youtube.com/@%C3%87emY%C4%B1lmaz","type":"video"}""",
        )
        assertEquals("Cem Yılmaz", embed.authorName)
        assertEquals("@ÇemYılmaz", YouTubeLinks.handleFromUrl(embed.authorUrl))
        assertNull(YouTubeLinks.handleFromUrl("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        try {
            YouTubeLinks.parseOEmbed("""{"title":"x"}""")
            fail("accepted oEmbed without author")
        } catch (expected: ChannelLookupException) {
        }
    }

    @Test
    fun parsesChannelPage() {
        val html = """<html><head><meta property="og:title" content="Kafa | Ayar &amp; Ses &#39;Şov&#39;">
            <link rel="canonical" href="https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv">
            </head><script>var x = {"vanityChannelUrl":"http://www.youtube.com/@%C3%87inMal%C4%B1"};</script>"""
        assertEquals(
            Channel("Kafa | Ayar & Ses 'Şov'", "@ÇinMalı", "UCabcdefghijklmnopqrstuv"),
            YouTubeLinks.parseChannelPage(html),
        )
        val viaCanonicalBase = """<meta property="og:title" content="A">"canonicalBaseUrl":"/@a","externalId":"UCabcdefghijklmnopqrstuv""""
        assertEquals(Channel("A", "@a", "UCabcdefghijklmnopqrstuv"), YouTubeLinks.parseChannelPage(viaCanonicalBase))
        try {
            YouTubeLinks.parseChannelPage("<html>consent</html>")
            fail("accepted a page without a channel")
        } catch (expected: ChannelLookupException) {
        }
    }
}
