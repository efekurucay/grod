package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val ig = "com.instagram.android"
    private val igRule = Rule(
        id = "instagram-reels", title = "Instagram Reels", why = null, enabled = true, platforms = listOf("android"),
        android = AndroidRule(
            packages = listOf(ig),
            feed = listOf(Matcher(viewId = "clips_viewer_view_pager")),
            exempt = listOf(Matcher(viewId = "reply_bar_container")),
            cover = listOf(Matcher(viewId = "clips_tab")),
            mask = listOf(Matcher(descContains = "Reel by ")),
            action = Action.BACK,
        ),
    )
    private val tiktokRule = Rule(
        "tiktok", "TikTok", null, true, listOf("android"),
        AndroidRule(listOf("com.zhiliaoapp.musically"), emptyList(), emptyList(), emptyList(), emptyList(), Action.HOME),
    )

    private val feedBox = Box(0, 200, 1080, 2000)
    private fun id(name: String) = "$ig:id/$name"
    private val tab = FakeNode(viewId = id("clips_tab"), bounds = Box(400, 2100, 600, 2200))
    private val feed = FakeNode(viewId = id("clips_viewer_view_pager"), bounds = feedBox)
    private val replyBar = FakeNode(viewId = id("reply_bar_container"), bounds = Box(0, 1900, 1080, 2000))

    @Test
    fun matcherFields() {
        val node = FakeNode(viewId = "p:id/x", textValue = "Reels", descValue = "Shorts", isSelected = true)
        assertTrue(Matcher(viewId = "x").matches(node, "p"))
        assertFalse(Matcher(viewId = "x").matches(node, "q"))
        assertTrue(Matcher(text = "reels").matches(node, "p"))
        assertTrue(Matcher(desc = "SHORTS", selected = true).matches(node, "p"))
        assertFalse(Matcher(desc = "Shorts", selected = false).matches(node, "p"))
        assertFalse(Matcher(desc = "Short").matches(node, "p"))
        assertFalse(Matcher(text = "Reels").matches(FakeNode(), "p"))
    }

    @Test
    fun coversVisibleTabOnly() {
        val engine = Engine()
        val hidden = FakeNode(viewId = id("clips_tab"), visible = false)
        val empty = FakeNode(viewId = id("clips_tab"), bounds = Box(5, 5, 5, 50))
        val out = engine.evaluate(listOf(igRule), ig, FakeTree(listOf(tab, hidden, empty)), null, 0)
        assertEquals(listOf(tab.bounds), out.covers)
        assertTrue(out.fire.isEmpty())
    }

    @Test
    fun feedFiresOncePerCooldown() {
        val engine = Engine()
        val tree = FakeTree(listOf(tab, feed))
        assertEquals(listOf(igRule), engine.evaluate(listOf(igRule), ig, tree, null, 1_000).fire)
        assertTrue(engine.evaluate(listOf(igRule), ig, tree, null, 2_499).fire.isEmpty())
        assertEquals(listOf(igRule), engine.evaluate(listOf(igRule), ig, tree, null, 2_500).fire)
    }

    @Test
    fun exemptHoldsUntilScrollInsideFeed() {
        val engine = Engine()
        val tree = FakeTree(listOf(feed, replyBar))
        assertTrue(engine.evaluate(listOf(igRule), ig, tree, null, 0).fire.isEmpty())
        // A scroll elsewhere (e.g. the tab bar) does not end the exemption.
        val outside = Scroll(Box(0, 2100, 1080, 2200))
        assertTrue(engine.evaluate(listOf(igRule), ig, tree, outside, 100).fire.isEmpty())
        assertEquals(listOf(igRule), engine.evaluate(listOf(igRule), ig, tree, inFeed, 200).fire)
    }

    private val inFeed = Scroll(Box(0, 200, 1080, 1900))

    @Test
    fun scrollWithoutSourceKeepsExemption() {
        val engine = Engine()
        val tree = FakeTree(listOf(feed, replyBar))
        assertTrue(engine.evaluate(listOf(igRule), ig, tree, Scroll(null), 0).fire.isEmpty())
        assertFalse(engine.stillDue(igRule, ig, tree, 0))
    }

    @Test
    fun exemptionReturnsAfterLeavingFeed() {
        val engine = Engine()
        val withExempt = FakeTree(listOf(feed, replyBar))
        engine.evaluate(listOf(igRule), ig, withExempt, inFeed, 0)
        engine.evaluate(listOf(igRule), ig, FakeTree(listOf(tab)), null, 2_000)
        assertTrue(engine.evaluate(listOf(igRule), ig, withExempt, null, 4_000).fire.isEmpty())
    }

    @Test
    fun resetSessionRestoresExemption() {
        val engine = Engine()
        val withExempt = FakeTree(listOf(feed, replyBar))
        engine.evaluate(listOf(igRule), ig, FakeTree(listOf(feed)), null, 0)
        // Scrolled during cooldown, then the user left the app before the feed was seen gone.
        engine.evaluate(listOf(igRule), ig, withExempt, inFeed, 500)
        engine.resetSession()
        assertTrue(engine.evaluate(listOf(igRule), ig, withExempt, null, 5_000).fire.isEmpty())
    }

    @Test
    fun scrollDuringCooldownIsRemembered() {
        val engine = Engine()
        val plain = FakeTree(listOf(feed))
        engine.evaluate(listOf(igRule), ig, plain, null, 0)
        val withExempt = FakeTree(listOf(feed, replyBar))
        val during = engine.evaluate(listOf(igRule), ig, withExempt, inFeed, 500)
        assertTrue(during.fire.isEmpty())
        assertEquals(setOf(igRule.id), during.onFeed)
        assertEquals(listOf(igRule), engine.evaluate(listOf(igRule), ig, withExempt, null, 1_600).fire)
    }

    @Test
    fun onFeedIsEmptyWhenFeedGone() {
        assertTrue(Engine().evaluate(listOf(igRule), ig, FakeTree(listOf(tab)), null, 0).onFeed.isEmpty())
    }

    @Test
    fun wholeAppFeedFiresOnAnyEvent() {
        val engine = Engine()
        val tree = FakeTree(emptyList())
        val out = engine.evaluate(listOf(tiktokRule), "com.zhiliaoapp.musically", tree, null, 0)
        assertEquals(listOf(tiktokRule), out.fire)
        assertTrue(engine.stillDue(tiktokRule, "com.zhiliaoapp.musically", tree, 0))
    }

    @Test
    fun homeFollowUpReevaluatesDecision() {
        val engine = Engine()
        assertTrue(engine.stillDue(igRule, ig, FakeTree(listOf(feed)), 0))
        assertFalse(engine.stillDue(igRule, ig, FakeTree(listOf(tab)), 0))
        // A reel opened from a DM after Back keeps its exemption.
        val dmReel = FakeTree(listOf(feed, replyBar))
        assertFalse(engine.stillDue(igRule, ig, dmReel, 0))
        assertFalse(engine.stillDue(igRule.copy(enabled = false), ig, FakeTree(listOf(feed)), 0))
        engine.evaluate(listOf(igRule), ig, dmReel, inFeed, 0)
        assertTrue(engine.stillDue(igRule, ig, dmReel, 0))
    }

    @Test
    fun viewIdRulesNeverWalkOrReadText() {
        val idOnly = igRule.copy(android = igRule.android!!.copy(mask = emptyList()))
        val other = FakeNode(viewId = id("feed_tab"), textValue = "Home")
        val tree = FakeTree(listOf(tab, feed, other))
        Engine().evaluate(listOf(idOnly), ig, tree, null, 0)
        assertEquals(0, tree.walks + tree.textSearches)
        assertEquals(0, other.textReads + tab.textReads + feed.textReads)
    }

    @Test
    fun descContainsMatchesSubstringIgnoringCase() {
        val tile = FakeNode(descValue = "Reel by alice at row 1, column 2")
        assertTrue(Matcher(descContains = "reel BY ").matches(tile, ig))
        assertFalse(Matcher(descContains = "Reel by bob").matches(tile, ig))
        assertFalse(Matcher(descContains = "Reel").matches(FakeNode(), ig))
    }

    private fun tile(row: Int) = FakeNode(descValue = "Reel by x at row $row, column 1", bounds = Box(0, row * 300, 360, row * 300 + 300))

    @Test
    fun masksNeverDriveTheAction() {
        val tiles = (1..12).map(::tile)
        val out = Engine().evaluate(listOf(igRule), ig, FakeTree(tiles + replyBar), null, 0)
        assertEquals(tiles.map { it.bounds }, out.masks)
        assertTrue(out.covers.isEmpty())
        assertTrue(out.fire.isEmpty())
        assertTrue(out.onFeed.isEmpty())
    }

    @Test
    fun masksDoNotEndExemption() {
        val tree = FakeTree(listOf(feed, replyBar, tile(1)))
        val out = Engine().evaluate(listOf(igRule), ig, tree, null, 0)
        assertEquals(1, out.masks.size)
        assertTrue(out.fire.isEmpty())
    }

    @Test
    fun coverWinsOverMaskOnSameNode() {
        val both = igRule.copy(android = igRule.android!!.copy(cover = listOf(Matcher(descContains = "Reel by "))))
        val out = Engine().evaluate(listOf(both), ig, FakeTree(listOf(tile(1), tile(2))), null, 0)
        assertEquals(listOf(tile(1).bounds, tile(2).bounds), out.covers)
        assertTrue(out.masks.isEmpty())
    }

    @Test
    fun overlaysAreClippedToWindow() {
        // Window ends at 1000 (tab bar below is drawn by the nav bar region here); tile 3 straddles it.
        val clip = Box(0, 100, 1080, 1000)
        val out = Engine().evaluate(listOf(igRule), ig, FakeTree(listOf(tile(0), tile(3), tile(4)), clip), null, 0)
        assertEquals(listOf(Box(0, 100, 360, 300), Box(0, 900, 360, 1000)), out.masks)
    }

    @Test
    fun textMatchersUseTextSearchNotWalk() {
        val shorts = FakeNode(descValue = "Shorts", bounds = Box(300, 2100, 500, 2200))
        val tree = FakeTree(listOf(FakeNode(descValue = "Home"), FakeNode(descValue = "Shorts shelf"), shorts))
        val hits = Scanner().scan(tree, "p", listOf(Matcher(desc = "Shorts"), Matcher(descContains = "shorts")), 0)
        assertEquals(0, tree.walks)
        assertEquals(1, tree.textSearches)
        assertEquals(listOf(shorts.bounds), hits[Matcher(desc = "Shorts")])
        assertEquals(2, hits[Matcher(descContains = "shorts")]!!.size)
    }

    @Test
    fun fallsBackToWalkWhenTextSearchMisses() {
        val shorts = FakeNode(descValue = "Shorts", bounds = Box(300, 2100, 500, 2200))
        val tree = FakeTree(listOf(FakeNode(descValue = "Home"), shorts), textSearchWorks = false)
        val scanner = Scanner()
        val cover = Matcher(desc = "Shorts")
        assertEquals(listOf(shorts.bounds), scanner.scan(tree, "p", listOf(cover), 0)[cover])
        // Learned: walked from then on, even inside the probe interval.
        assertEquals(listOf(shorts.bounds), scanner.scan(tree, "p", listOf(cover), 100)[cover])
        assertEquals(2, tree.walks)
        assertEquals(WALK_LIMIT, tree.walkLimit)
    }

    @Test
    fun absentTextMatcherProbesAtMostOncePerInterval() {
        val tree = FakeTree(listOf(FakeNode(descValue = "Home")))
        val scanner = Scanner()
        val m = Matcher(text = "Watch more reels")
        scanner.scan(tree, "p", listOf(m), 0)
        scanner.scan(tree, "p", listOf(m), 1_000)
        assertEquals(1, tree.walks)
        scanner.scan(tree, "p", listOf(m), Scanner.PROBE_INTERVAL_MS)
        assertEquals(2, tree.walks)
        assertEquals(3, tree.textSearches)
    }

    @Test
    fun selectedOnlyMatcherWalks() {
        val tree = FakeTree(listOf(FakeNode(isSelected = true)))
        val m = Matcher(selected = true)
        assertEquals(1, Scanner().scan(tree, "p", listOf(m), 0)[m]!!.size)
        assertEquals(1, tree.walks)
        assertEquals(0, tree.textSearches)
    }

    @Test
    fun oneLearnedMatcherSwitchesThePackageToWalk() {
        val shortsTab = FakeNode(descValue = "Shorts", bounds = Box(300, 2100, 500, 2200))
        val tab = Matcher(desc = "Shorts")
        val tileMask = Matcher(descContains = "play Short")
        val scanner = Scanner()
        // First screen: no tiles yet, but the tab teaches that text search does not work here.
        scanner.scan(FakeTree(listOf(shortsTab), textSearchWorks = false), "yt", listOf(tab, tileMask), 0)
        val tile = FakeNode(descValue = "Funny cat - play Short", bounds = Box(0, 300, 540, 900))
        val tree = FakeTree(listOf(shortsTab, tile), textSearchWorks = false)
        // Inside the probe interval, the tiles are still found right away.
        assertEquals(listOf(tile.bounds), scanner.scan(tree, "yt", listOf(tab, tileMask), 100)[tileMask])
        assertEquals(0, tree.textSearches)
        // Learning is per package.
        val other = FakeTree(listOf(tile))
        scanner.scan(other, "ig", listOf(tileMask), 100)
        assertEquals(1, other.textSearches)
    }

    @Test
    fun manyNeedlesUseOneWalk() {
        val tree = FakeTree(emptyList())
        val matchers = (1..(Scanner.MAX_TEXT_SEARCHES + 1)).map { Matcher(descContains = "Go to channel $it -") }
        Scanner().scan(tree, "p", matchers, 0)
        assertEquals(0, tree.textSearches)
        assertEquals(1, tree.walks)
    }

    @Test
    fun learnedPackagesAreReportedAndRestored() {
        var saved = emptySet<String>()
        val scanner = Scanner(onLearned = { saved = it })
        val tile = FakeNode(descValue = "Cat - play Short", bounds = Box(0, 0, 10, 10))
        scanner.scan(FakeTree(listOf(tile), textSearchWorks = false), "yt", listOf(Matcher(descContains = "play Short")), 0)
        assertEquals(setOf("yt"), saved)
        val restored = FakeTree(listOf(tile), textSearchWorks = false)
        Scanner(learned = saved).scan(restored, "yt", listOf(Matcher(descContains = "play Short")), 0)
        assertEquals(0, restored.textSearches)
        assertEquals(1, restored.walks)
    }
}
