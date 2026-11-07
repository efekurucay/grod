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

        val yt = set.rules.single { it.id == "youtube" }
        assertEquals(listOf("web", "android"), yt.platforms)
        assertEquals(listOf("com.google.android.youtube", "app.revanced.android.youtube"), yt.android!!.packages)
        assertEquals(listOf(yt), set.rulesFor("com.google.android.youtube"))
        assertEquals(ScrollSettings(), set.scroll)

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
            "no packages" to withAndroid("""{}"""),
            "empty packages" to withAndroid("""{"packages":[]}"""),
            "package not string" to withAndroid("""{"packages":[3]}"""),
            "unknown android field" to withAndroid("""{"packages":["p"],"feed":[{"viewId":"x"}]}"""),
            "scroll not object" to """{"schemaVersion":1,"rules":[],"scroll":60}""",
            "scroll field" to """{"schemaVersion":1,"rules":[],"scroll":{"freeMinutes":1}}""",
            "scroll free too small" to """{"schemaVersion":1,"rules":[],"scroll":{"freeSeconds":5}}""",
            "scroll pause fraction" to """{"schemaVersion":1,"rules":[],"scroll":{"firstPauseSeconds":2.5}}""",
            "scroll growth below 1" to """{"schemaVersion":1,"rules":[],"scroll":{"growth":0.5}}""",
            "scroll growth string" to """{"schemaVersion":1,"rules":[],"scroll":{"growth":"2"}}""",
            "scroll reset zero" to """{"schemaVersion":1,"rules":[],"scroll":{"resetMinutes":0}}""",
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
    fun parsesScrollSettingsWithDefaults() {
        assertEquals(
            ScrollSettings(freeSeconds = 30, growth = 1.5, resetMinutes = 5),
            Rules.parse("""{"schemaVersion":1,"rules":[],"scroll":{"freeSeconds":30,"growth":1.5,"resetMinutes":5}}""").scroll,
        )
        assertEquals(ScrollSettings(60, 10, 2.0, 300, 15), Rules.parse("""{"schemaVersion":1,"rules":[]}""").scroll)
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
