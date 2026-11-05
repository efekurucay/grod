package dev.grod

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * channels-added.json in the external files dir: channels blocked from the phone's share sheet.
 * `grod sync` pulls it into the rules and pushes back an empty list, so it can change at any moment;
 * every change is a read-modify-write of the current file. Plain java.io, so it runs in JVM unit tests.
 */
class ChannelFile(val file: File) {
    /** Missing file = no channels. Throws InvalidRulesException or IOException for a bad file. */
    fun read(): List<Channel> = if (file.exists()) parse(file.readText()) else emptyList()

    /** Returns the list after adding; unchanged when the handle is already there. */
    fun add(channel: Channel): List<Channel> {
        val list = read()
        if (list.any { sameHandle(it.handle, channel.handle) }) return list
        return (list + channel).also(::write)
    }

    fun remove(handle: String): List<Channel> {
        val list = read()
        val kept = list.filterNot { sameHandle(it.handle, handle) }
        if (kept.size != list.size) write(kept)
        return kept
    }

    private fun write(channels: List<Channel>) {
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeText(serialize(channels))
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        fun parse(text: String): List<Channel> {
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw InvalidRulesException("channels file is not a JSON object")
            }
            return Rules.parseChannels(root.opt("channels") ?: JSONArray(), "channels")
        }

        fun serialize(channels: List<Channel>): String {
            val arr = JSONArray()
            for (c in channels) {
                arr.put(JSONObject().put("name", c.name).put("handle", c.handle).apply { c.id?.let { put("id", it) } })
            }
            return JSONObject().put("channels", arr).toString(2) + "\n"
        }
    }
}
