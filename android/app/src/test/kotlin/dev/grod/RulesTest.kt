package dev.grod

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RulesTest {
    private fun read(property: String) = File(System.getProperty(property)!!).readText()

    @Test
    fun acceptsRepoRules() {
        val set = Rules.parse(read("grod.rulesFile"))
        assertNull(set.revision)

        val yt = set.rules.single { it.id == "youtube-shorts" }
        assertEquals(listOf("web", "android"), yt.platforms)
        val android = yt.android!!
        assertEquals(listOf("com.google.android.youtube", "app.revanced.android.youtube"), android.packages)
        assertEquals(listOf(Matcher(viewId = "reel_player_page_container"), Matcher(viewId = "reel_watch_player")), android.feed)
        assertEquals(listOf(Matcher(desc = "Shorts")), android.cover)
        assertTrue(Matcher(descContains = "play Short") in android.mask)
        assertEquals(Action.BACK, android.action)

        val igAndroid = set.rules.single { it.id == "instagram-reels" }.android!!
        assertTrue(Matcher(text = "Watch more reels") in igAndroid.cover)
        assertTrue(Matcher(viewId = "clips_viewer_action_bar_title", text = "Your reels") in igAndroid.exempt)

        val tiktok = set.rules.single { it.id == "tiktok" }.android!!
        assertTrue(tiktok.feed.isEmpty())
        assertTrue(tiktok.mask.isEmpty())
        assertEquals(Action.HOME, tiktok.action)

        assertNull(set.rules.single { it.id == "facebook-reels" }.android)
        assertEquals(ReviewSettings(7, 20 * 60), Rules.parse("""{"schemaVersion":1,"rules":[],"review":{"weekday":7,"time":"20:00"}}""").review)
        val n = set.notifications!!
        assertTrue(n.deliverEvery in 15..720)
        assertTrue(NotificationAllow(null, "event", null) in n.allow)
        assertTrue("com.instagram.android" in set.targetPackages)
        assertTrue(set.rulesFor("com.android.chrome").isEmpty())
    }

    @Test
    fun acceptsBundledAssetWithRevision() {
        val set = Rules.parse(read("grod.assetRulesFile"))
        assertTrue(set.revision!!.isNotEmpty())
        assertTrue(set.rules.isNotEmpty())
    }

    @Test
    fun disabledRulesDoNotTarget() {
        val set = Rules.parse(
            """{"schemaVersion":1,"rules":[{"id":"a","title":"A","enabled":false,"android":{"packages":["p"]}}]}""",
        )
        assertTrue(set.targetPackages.isEmpty())
        assertTrue(set.rulesFor("p").isEmpty())
    }

    @Test
    fun rejectsBadCopies() {
        val rule = """{"id":"a","title":"A","enabled":true,"android":%s}"""
        fun withAndroid(android: String) = """{"schemaVersion":1,"rules":[${rule.format(android)}]}"""
        val bad = mapOf(
            "not json" to "{nope",
            "array root" to "[]",
            "wrong version" to """{"schemaVersion":2,"rules":[]}""",
            "no version" to """{"rules":[]}""",
            "no rules" to """{"schemaVersion":1}""",
            "rules not array" to """{"schemaVersion":1,"rules":{}}""",
            "revision not string" to """{"schemaVersion":1,"rules":[],"revision":5}""",
            "rule not object" to """{"schemaVersion":1,"rules":[1]}""",
            "bad id" to """{"schemaVersion":1,"rules":[{"id":"Bad Id","title":"A","enabled":true}]}""",
            "empty title" to """{"schemaVersion":1,"rules":[{"id":"a","title":"","enabled":true}]}""",
            "enabled string" to """{"schemaVersion":1,"rules":[{"id":"a","title":"A","enabled":"yes"}]}""",
            "duplicate id" to """{"schemaVersion":1,"rules":[{"id":"a","title":"A","enabled":true},{"id":"a","title":"B","enabled":true}]}""",
            "no packages" to withAndroid("""{"feed":[]}"""),
            "empty packages" to withAndroid("""{"packages":[]}"""),
            "package not string" to withAndroid("""{"packages":[3]}"""),
            "bad action" to withAndroid("""{"packages":["p"],"action":"kill"}"""),
            "unknown android field" to withAndroid("""{"packages":["p"],"viewIds":["x"]}"""),
            "empty matcher" to withAndroid("""{"packages":["p"],"feed":[{}]}"""),
            "unknown matcher field" to withAndroid("""{"packages":["p"],"cover":[{"id":"x"}]}"""),
            "matcher wrong type" to withAndroid("""{"packages":["p"],"exempt":[{"selected":"true"}]}"""),
            "empty descContains" to withAndroid("""{"packages":["p"],"mask":[{"descContains":""}]}"""),
            "descContains not string" to withAndroid("""{"packages":["p"],"mask":[{"descContains":1}]}"""),
            "mask not array" to withAndroid("""{"packages":["p"],"mask":{"descContains":"x"}}"""),
            "notifications every too small" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":10}}""",
            "notifications every fraction" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":60.5}}""",
            "notifications no enabled" to """{"schemaVersion":1,"rules":[],"notifications":{"deliverEvery":60}}""",
            "notifications keepDays" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":60,"keepDays":-1}}""",
            "notifications empty allow" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":60,"allow":[{}]}}""",
            "notifications allow field" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":60,"allow":[{"app":"x"}]}}""",
            "notifications deliverAt" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":60,"deliverAt":["9:00"]}}""",
            "notifications deliverAt empty" to """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":60,"deliverAt":[]}}""",
            "review weekday" to """{"schemaVersion":1,"rules":[],"review":{"weekday":0,"time":"20:00"}}""",
            "review time" to """{"schemaVersion":1,"rules":[],"review":{"weekday":7,"time":"8pm"}}""",
            "review field" to """{"schemaVersion":1,"rules":[],"review":{"weekday":7,"time":"20:00","day":"sun"}}""",
            "matchers not array" to withAndroid("""{"packages":["p"],"feed":{"viewId":"x"}}"""),
        )
        for ((name, json) in bad) {
            try {
                Rules.parse(json)
                fail("accepted bad copy: $name")
            } catch (expected: InvalidRulesException) {
                assertTrue(name, expected.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun parsesNotificationSettings() {
        val set = Rules.parse(
            """{"schemaVersion":1,"rules":[],"notifications":{"enabled":true,"deliverEvery":120,
               "deliverAt":["18:00","09:00","13:30"],"allow":[{"package":"com.whatsapp","titleContains":"Annem"}]}}""",
        )
        assertEquals(ReviewSettings(7, 20 * 60), Rules.parse("""{"schemaVersion":1,"rules":[],"review":{"weekday":7,"time":"20:00"}}""").review)
        val n = set.notifications!!
        assertEquals(listOf(9 * 60, 13 * 60 + 30, 18 * 60), n.slots)
        assertEquals(30, n.keepDays)
        assertEquals(listOf(NotificationAllow("com.whatsapp", null, "Annem")), n.allow)
        assertEquals(30, Rules.parse("""{"schemaVersion":1,"rules":[]}""").keepDays)
        assertEquals((0 until 1440 step 120).toList(), n.copy(deliverAt = null).slots)
    }
}
