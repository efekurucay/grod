package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PacksTest {
    private val week = PackSettings(count = 5, minutes = 5, refilled = "2026-11-21T13:00:00.000Z")

    @Test
    fun packsLeftCountDownFromTheRefill() {
        var use = PackUse(null, 0)
        assertEquals(5, use.left(week))
        repeat(5) { use = use.take(week) }
        assertEquals(0, use.left(week))
        assertEquals(0, use.take(week).left(week))
    }

    @Test
    fun aNewRefillGivesEveryPackAgain() {
        val used = PackUse(week.refilled, 4)
        assertEquals(1, used.left(week))
        val next = week.copy(refilled = "2026-11-28T13:00:00.000Z")
        assertEquals(5, used.left(next))
        assertEquals(PackUse(next.refilled, 1), used.take(next))
        // A new length alone is no refill.
        assertEquals(1, used.left(week.copy(minutes = 3)))
    }

    @Test
    fun noPacksSectionMeansNoPacks() {
        assertEquals(0, PackUse(null, 0).left(Rules.parse("""{"schemaVersion":1,"rules":[]}""").packs))
    }

    @Test
    fun parsesPacks() {
        assertEquals(week, Rules.parse("""{"schemaVersion":1,"rules":[],"packs":{"count":5,"refilled":"2026-11-21T13:00:00.000Z"}}""").packs)
        assertEquals(3, Rules.parse("""{"schemaVersion":1,"rules":[],"packs":{"count":5,"minutes":3,"refilled":"x"}}""").packs.minutes)
        for (bad in listOf("""{"count":5}""", """{"count":21,"refilled":"x"}""", """{"count":5,"minutes":0,"refilled":"x"}""", """{"count":5,"refilled":"x","extra":1}""")) {
            try {
                Rules.parse("""{"schemaVersion":1,"rules":[],"packs":$bad}""")
                fail("accepted $bad")
            } catch (e: InvalidRulesException) {
                // expected
            }
        }
    }
}
