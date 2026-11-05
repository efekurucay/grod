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
            action = Action.BACK,
        ),
    )
    private val tiktokRule = Rule(
        "tiktok", "TikTok", null, true, listOf("android"),
        AndroidRule(listOf("com.zhiliaoapp.musically"), emptyList(), emptyList(), emptyList(), Action.HOME),
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
        assertFalse(engine.stillDue(igRule, ig, tree))
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
        assertTrue(engine.stillDue(tiktokRule, "com.zhiliaoapp.musically", tree))
    }

    @Test
    fun homeFollowUpReevaluatesDecision() {
        val engine = Engine()
        assertTrue(engine.stillDue(igRule, ig, FakeTree(listOf(feed))))
        assertFalse(engine.stillDue(igRule, ig, FakeTree(listOf(tab))))
        // A reel opened from a DM after Back keeps its exemption.
        val dmReel = FakeTree(listOf(feed, replyBar))
        assertFalse(engine.stillDue(igRule, ig, dmReel))
        assertFalse(engine.stillDue(igRule.copy(enabled = false), ig, FakeTree(listOf(feed))))
        engine.evaluate(listOf(igRule), ig, dmReel, inFeed, 0)
        assertTrue(engine.stillDue(igRule, ig, dmReel))
    }

    @Test
    fun viewIdRulesNeverWalkOrReadText() {
        val other = FakeNode(viewId = id("feed_tab"), textValue = "Home")
        val tree = FakeTree(listOf(tab, feed, other))
        Engine().evaluate(listOf(igRule), ig, tree, null, 0)
        assertEquals(0, tree.walks)
        assertEquals(0, other.textReads + tab.textReads + feed.textReads)
    }

    @Test
    fun descMatchersShareOneCappedWalk() {
        val shorts = FakeNode(descValue = "Shorts", bounds = Box(300, 2100, 500, 2200))
        val tree = FakeTree(listOf(FakeNode(descValue = "Home"), shorts))
        val hits = scan(tree, "p", listOf(Matcher(desc = "Shorts"), Matcher(desc = "shorts", selected = false)))
        assertEquals(1, tree.walks)
        assertEquals(WALK_LIMIT, tree.walkLimit)
        assertEquals(listOf(shorts.bounds), hits[Matcher(desc = "Shorts")])
        assertEquals(listOf(shorts.bounds), hits[Matcher(desc = "shorts", selected = false)])
    }
}
