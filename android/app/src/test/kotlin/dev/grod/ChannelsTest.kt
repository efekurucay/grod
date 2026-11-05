package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChannelsTest {
    private val yt = "com.google.android.youtube"
    private val cem = Channel("Cem Yılmaz", "@CemYilmaz", "UCabcdefghijklmnopqrstuv")
    private val pipe = Channel("Kafa | Çin Malı Şov", "@%C3%87inMal%C4%B1")

    private fun rulesJson(channels: String, enabled: Boolean = true) = """{"schemaVersion":1,"rules":[
        {"id":"youtube-channels","title":"Blocked YouTube channels","why":"You blocked it.","enabled":$enabled,
         "youtubeChannels":$channels}]}"""

    private fun tile(name: String, row: Int) = FakeNode(
        descValue = "$name | Genel Müdür - 2 minutes, 42 seconds - Go to channel $name - $name, Verified - 12 million views - play video",
        bounds = Box(0, row * 400, 1080, row * 400 + 400),
    )
    private val watchPanel = FakeNode(viewId = "$yt:id/watch_panel", bounds = Box(0, 683, 1080, 2265))
    private val miniplayer = FakeNode(viewId = "$yt:id/watch_player", bounds = Box(480, 1848, 1056, 2172))
    private val miniClose = FakeNode(viewId = "$yt:id/modern_miniplayer_close", bounds = Box(912, 1848, 1056, 1992))
    private val subscribeCem = FakeNode(descValue = "Subscribe to Cem Yılmaz.", bounds = Box(156, 920, 391, 1016))

    @Test
    fun parsesChannelsIntoAYouTubeRule() {
        val set = Rules.parse(rulesJson("""[{"name":"Cem Yılmaz","handle":"@CemYilmaz","id":"UCabcdefghijklmnopqrstuv"}]"""))
        assertEquals(listOf(ChannelEntry(cem, "youtube-channels")), set.channels)
        assertTrue(yt in set.targetPackages)
        val generated = set.rulesFor(yt).single()
        assertEquals("youtube-channels:channels", generated.id)
        assertEquals("youtube-channels", generated.countAs)
        assertEquals("You blocked it.", generated.why)
    }

    @Test
    fun rejectsBadChannels() {
        val bad = listOf(
            """[{"name":"A","handle":"NoAt"}]""",
            """[{"name":"","handle":"@a"}]""",
            """[{"name":"A","handle":"@a b"}]""",
            """[{"name":"A","handle":"@a","id":"UCshort"}]""",
            """[{"name":"A","handle":"@a","url":"x"}]""",
            """{"name":"A","handle":"@a"}""",
        )
        for (channels in bad) {
            try {
                Rules.parse(rulesJson(channels))
                fail("accepted $channels")
            } catch (expected: InvalidRulesException) {
            }
        }
    }

    @Test
    fun mergesRulesAndPhoneChannelsByHandle() {
        val set = Rules.parse(rulesJson("""[{"name":"Kafa | Çin Malı Şov","handle":"@ÇinMalı"}]"""))
            .copy(localChannels = listOf(pipe, cem))
        // The phone copy of @ÇinMalı is percent-encoded but is the same channel.
        assertEquals(listOf("youtube-channels", null), set.channels.map { it.ruleId })
        assertEquals(listOf("@ÇinMalı", "@CemYilmaz"), set.channels.map { it.channel.handle })
        // Phone channels count under the rule `grod sync` will move them into.
        assertEquals(listOf("youtube-channels:channels"), set.rulesFor(yt).map { it.id })
    }

    @Test
    fun disabledRuleChannelsDoNotApplyButPhoneChannelsDo() {
        val set = Rules.parse(rulesJson("""[{"name":"A","handle":"@a"}]""", enabled = false))
        assertTrue(set.rulesFor(yt).isEmpty())
        val withLocal = set.copy(localChannels = listOf(cem))
        assertEquals(listOf(cem), withLocal.channels.map { it.channel })
        assertEquals(1, withLocal.rulesFor(yt).size)
    }

    private fun engineRules(vararg local: Channel) = RuleSet(null, emptyList(), local.toList()).rulesFor(yt)

    @Test
    fun masksTilesAndCardsOfBlockedChannelsOnly() {
        val rules = engineRules(cem, pipe)
        val card = FakeNode(textValue = "Cem Yılmaz", descValue = "Cem Yılmaz", bounds = Box(0, 2000, 1080, 2200))
        val similar = tile("Cem", 3)
        val nodes = listOf(tile("Cem Yılmaz", 0), tile("Kafa | Çin Malı Şov", 1), tile("Someone Else", 2), similar, card)
        val out = Engine().evaluate(rules, yt, FakeTree(nodes), null, 0)
        assertEquals(listOf(nodes[0].bounds, nodes[1].bounds, card.bounds), out.masks.sortedBy { it.top })
        assertTrue(out.fire.isEmpty())
    }

    @Test
    fun watchPageOfBlockedChannelGoesBack() {
        val rules = engineRules(cem)
        assertEquals(rules, Engine().evaluate(rules, yt, FakeTree(listOf(watchPanel, subscribeCem)), null, 0).fire)
        val unsubscribe = FakeNode(descValue = "Unsubscribe from Cem Yılmaz.", bounds = Box(800, 700, 1080, 780))
        assertEquals(rules, Engine().evaluate(rules, yt, FakeTree(listOf(watchPanel, unsubscribe)), null, 0).fire)
    }

    @Test
    fun subscribeButtonOutsideWatchPageDoesNotFire() {
        val rules = engineRules(cem)
        assertTrue(Engine().evaluate(rules, yt, FakeTree(listOf(subscribeCem)), null, 0).fire.isEmpty())
        // The miniplayer also has watch_player; a search channel card under it is no watch page.
        assertTrue(Engine().evaluate(rules, yt, FakeTree(listOf(miniplayer, subscribeCem)), null, 0).fire.isEmpty())
        val other = FakeNode(descValue = "Subscribe to Someone.", bounds = Box(800, 700, 1080, 780))
        assertTrue(Engine().evaluate(rules, yt, FakeTree(listOf(watchPanel, other)), null, 0).fire.isEmpty())
    }

    @Test
    fun handlesCompareDecodedAndCaseInsensitive() {
        assertEquals("@ÇinMalı", normalizeHandle("%C3%87inMal%C4%B1"))
        assertEquals("@a+b", normalizeHandle("@a+b"))
        assertTrue(sameHandle("@cemyilmaz", "@CemYilmaz"))
        assertFalse(sameHandle("@cem", "@CemYilmaz"))
    }

    @Test
    fun readsTitlesFromTiles() {
        assertEquals("Cem Yılmaz | Genel Müdür", tileTitle(tile("Cem Yılmaz", 0).desc.toString()))
        assertEquals(
            "Spring - Blender Open Movie",
            tileTitle("Spring - Blender Open Movie - 7 minutes, 45 seconds - Go to channel Blender Studio - Blender Studio, Verified - 10 million views"),
        )
        assertEquals("Official Blender Open Movies", tileTitle("Playlist - Official Blender Open Movies - Blender - 17 videos"))
        assertNull(tileTitle("Cem Yılmaz"))
        assertNull(tileTitle("Go to channel Cem Yılmaz"))
    }

    @Test
    fun pageTitledLikeAMaskedTileGoesBack() {
        val rules = engineRules(cem)
        // A collaboration shows "@Other +1" instead of a Subscribe button naming the channel.
        val watchTitle = FakeNode(textValue = "Cem Yılmaz | Genel Müdür", bounds = Box(0, 731, 1080, 804))
        val watch = FakeTree(listOf(watchPanel, watchTitle))
        assertTrue(Engine().evaluate(rules, yt, watch, null, 0).fire.isEmpty())
        val engine = Engine()
        engine.evaluate(rules, yt, FakeTree(listOf(tile("Cem Yılmaz", 0), tile("Other", 1))), null, 0)
        assertEquals(rules, engine.evaluate(rules, yt, watch, null, 1_000).fire)
        val otherTitle = FakeNode(textValue = "Other | Genel Müdür", bounds = Box(0, 731, 1080, 804))
        assertTrue(Engine().apply { evaluate(rules, yt, FakeTree(listOf(tile("Cem Yılmaz", 0))), null, 0) }
            .evaluate(rules, yt, FakeTree(listOf(watchPanel, otherTitle)), null, 1_000).fire.isEmpty())
    }

    @Test
    fun firedPageKeepsCountingUntilItIsGone() {
        val rules = engineRules(Channel("Blender", "@BlenderOfficial"))
        val engine = Engine()
        val panelOwner = FakeNode(viewId = "$yt:id/subtitle", textValue = "Blender", bounds = Box(48, 809, 176, 851))
        assertEquals(rules, engine.evaluate(rules, yt, FakeTree(listOf(watchPanel, panelOwner)), null, 0).fire)
        // Back only collapsed the playlist panel: no owner shows, but it is the same page.
        val collapsed = FakeTree(listOf(watchPanel))
        assertEquals(setOf(rules[0].id), engine.evaluate(rules, yt, collapsed, null, 500).onFeed)
        assertTrue(engine.stillDue(rules[0], yt, collapsed, 1_500))
        engine.evaluate(rules, yt, FakeTree(listOf(miniplayer, miniClose)), null, 2_000)
        assertFalse(engine.stillDue(rules[0], yt, collapsed, 3_000))
    }

    @Test
    fun playlistPanelAndChannelPageNameTheOwner() {
        val rules = engineRules(Channel("Blender", "@BlenderOfficial"))
        val panelOwner = FakeNode(viewId = "$yt:id/subtitle", textValue = "Blender", bounds = Box(48, 809, 176, 851))
        assertEquals(rules, Engine().evaluate(rules, yt, FakeTree(listOf(watchPanel, panelOwner)), null, 0).fire)
        val header = FakeNode(viewId = "$yt:id/collapsing_header_container", bounds = Box(0, 75, 1080, 1138))
        val handle = FakeNode(textValue = "@BlenderOfficial", bounds = Box(312, 626, 594, 674))
        assertEquals(rules, Engine().evaluate(rules, yt, FakeTree(listOf(header, handle)), null, 0).fire)
    }

    @Test
    fun masksPlaylistTilesOfBlockedChannel() {
        val rules = engineRules(Channel("Blender", "@BlenderOfficial"))
        val own = FakeNode(descValue = "Playlist - Official Blender Open Movies - Blender - 17 videos", bounds = Box(0, 219, 1080, 987))
        val other = FakeNode(descValue = "Playlist - Blender Open Movies - Blender Studio - 18 videos", bounds = Box(0, 2038, 1080, 2265))
        val out = Engine().evaluate(rules, yt, FakeTree(listOf(own, other)), null, 0)
        assertEquals(listOf(own.bounds), out.masks)
    }

    @Test
    fun channelRulesWalkBackAndCloseTheMiniplayer() {
        val android = engineRules(cem).single().android!!
        assertEquals(Action.BACK, android.escalate)
        assertEquals(listOf(Matcher(viewId = "modern_miniplayer_close")), android.dismiss)
    }
}
