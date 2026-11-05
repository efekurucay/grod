package dev.grod

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val cem = Channel("Cem Yılmaz", "@CemYilmaz", "UCabcdefghijklmnopqrstuv")
    private val pipe = Channel("Kafa | Çin Malı / Şov", "@ÇinMalı")

    @Test
    fun addsRemovesAndRoundTrips() {
        val file = ChannelFile(File(tmp.root, "channels-added.json"))
        assertTrue(file.read().isEmpty())
        assertEquals(listOf(cem), file.add(cem))
        assertEquals(listOf(cem, pipe), file.add(pipe))
        // Same channel with an encoded handle is not added twice.
        assertEquals(listOf(cem, pipe), file.add(Channel("x", "@%C3%87inMal%C4%B1")))
        assertEquals(listOf(cem, pipe), ChannelFile(file.file).read())
        assertEquals(listOf(pipe), file.remove("@cemyilmaz"))
        assertEquals(listOf(pipe), file.read())
    }

    @Test
    fun followsReplacementBySync() {
        val file = ChannelFile(File(tmp.root, "channels-added.json"))
        file.add(cem)
        file.file.writeText("{\"channels\":[]}\n")
        assertTrue(file.read().isEmpty())
        assertEquals(listOf(pipe), file.add(pipe))
    }

    @Test
    fun rejectsBadFile() {
        val file = ChannelFile(File(tmp.root, "channels-added.json"))
        for (bad in listOf("nope", """{"channels":[{"name":"A","handle":"no-at"}]}""", """{"channels":{}}""")) {
            file.file.writeText(bad)
            try {
                file.read()
                fail("accepted $bad")
            } catch (expected: InvalidRulesException) {
            }
        }
    }
}
