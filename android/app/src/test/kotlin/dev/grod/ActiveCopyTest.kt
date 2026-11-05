package dev.grod

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActiveCopyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v1 = """{"schemaVersion":1,"revision":"aaa","rules":[]}"""
    private val v2 = """{"schemaVersion":1,"revision":"bbb","rules":[]}"""

    @Test
    fun savesAndLoadsBack() {
        val file = File(tmp.root, "rules.json")
        assertNull(ActiveCopy(file).load())
        assertEquals("aaa", ActiveCopy(file).replace(v1).revision)
        assertEquals("aaa", ActiveCopy(file).load()!!.revision)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun invalidCopyLeavesOldOne() {
        val file = File(tmp.root, "rules.json")
        val copy = ActiveCopy(file).apply { replace(v1) }
        try {
            copy.replace("""{"schemaVersion":2,"rules":[]}""")
            fail("accepted invalid copy")
        } catch (expected: InvalidRulesException) {
        }
        assertEquals(v1, file.readText())
    }

    @Test
    fun failedWriteThrowsAndKeepsNothing() {
        val file = File(tmp.root, "missing-dir/rules.json")
        try {
            ActiveCopy(file).replace(v1)
            fail("write into a missing directory succeeded")
        } catch (expected: IOException) {
        }
        assertFalse(file.exists())
    }

    @Test
    fun failedRenameThrowsAndKeepsOldCopy() {
        // A non-empty directory at the target path makes the rename fail.
        val file = File(tmp.root, "rules.json").apply { mkdir() }
        File(file, "blocker").writeText("x")
        try {
            ActiveCopy(file).replace(v2)
            fail("rename onto a directory succeeded")
        } catch (expected: IOException) {
        }
        assertFalse(File(file.path + ".tmp").exists())
        assertEquals("x", File(file, "blocker").readText())
    }
}
