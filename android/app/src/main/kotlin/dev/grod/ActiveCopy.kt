package dev.grod

import java.io.File
import java.io.IOException

/** The last valid rules copy on disk. Plain java.io, so it runs in JVM unit tests. */
class ActiveCopy(private val file: File) {
    val exists: Boolean get() = file.exists()

    fun load(): RuleSet? = try {
        if (file.exists()) Rules.parse(file.readText()) else null
    } catch (e: InvalidRulesException) {
        null
    } catch (e: IOException) {
        null
    }

    /**
     * Validates [text] and atomically replaces the file with it. On any failure the previous copy is
     * left untouched and the exception propagates, so callers publish rules only after a real save.
     */
    fun replace(text: String): RuleSet {
        val parsed = Rules.parse(text)
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        return parsed
    }
}
