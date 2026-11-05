package dev.grod

import java.net.URLDecoder

/** A blocked YouTube channel (schema `$defs/youtubeChannel`). */
data class Channel(val name: String, val handle: String, val id: String? = null)

/** A channel and the rule listing it; [ruleId] is null for a channel added on this phone. */
data class ChannelEntry(val channel: Channel, val ruleId: String?)

/** Rule id `grod channel add` writes to; channels added on the phone count under it until synced there. */
const val CHANNELS_RULE_ID = "youtube-channels"

val YOUTUBE_PACKAGES = listOf("com.google.android.youtube", "app.revanced.android.youtube")

/** "@Çin…" from "@%C3%87in…", so encoded and plain handles compare equal. */
fun normalizeHandle(handle: String): String {
    val trimmed = handle.trim()
    val decoded = try {
        // URLDecoder would turn "+" into a space; a handle keeps it.
        URLDecoder.decode(trimmed.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        trimmed
    }
    return if (decoded.startsWith("@")) decoded else "@$decoded"
}

fun sameHandle(a: String, b: String): Boolean = normalizeHandle(a).equals(normalizeHandle(b), ignoreCase = true)

/** Channels of enabled rules, then phone-added channels no rule lists yet. One entry per handle. */
fun mergeChannels(rules: List<Rule>, local: List<Channel>): List<ChannelEntry> {
    val merged = ArrayList<ChannelEntry>()
    fun add(channel: Channel, ruleId: String?) {
        if (merged.none { sameHandle(it.channel.handle, channel.handle) }) merged.add(ChannelEntry(channel, ruleId))
    }
    for (rule in rules) if (rule.enabled) rule.youtubeChannels.forEach { add(it, rule.id) }
    local.forEach { add(it, null) }
    return merged
}

/**
 * The Android half of docs/rules.md "YouTube channels", as ordinary rules so the engine applies it:
 * tiles, channel cards and playlists get masks; a watch or channel page of a blocked channel, or one
 * titled like a masked tile, gets Back, and the miniplayer Back leaves behind is closed.
 * One generated rule per listing rule; its id cannot collide with a rules-file id (no ":" allowed there).
 */
fun channelRules(rules: List<Rule>, channels: List<ChannelEntry>): List<Rule> =
    channels.groupBy { it.ruleId ?: CHANNELS_RULE_ID }.map { (ruleId, entries) ->
        val source = rules.firstOrNull { it.id == ruleId }
        val names = entries.map { it.channel.name }
        Rule(
            id = "$ruleId:channels",
            title = source?.title ?: "Blocked YouTube channels",
            why = source?.why ?: "You blocked this channel. Everything else on YouTube stays.",
            enabled = true,
            platforms = emptyList(),
            android = AndroidRule(
                packages = YOUTUBE_PACKAGES,
                feed = entries.flatMap { (channel) ->
                    listOf(
                        Matcher(desc = "Subscribe to ${channel.name}."),
                        Matcher(desc = "Unsubscribe from ${channel.name}."),
                        // A playlist panel covers the owner row; its header names the playlist's channel.
                        Matcher(viewId = "subtitle", text = channel.name),
                        // A channel page shows its handle as a line of its own.
                        Matcher(text = channel.handle),
                    )
                },
                exempt = emptyList(),
                cover = emptyList(),
                mask = names.flatMap {
                    listOf(
                        Matcher(descContains = "Go to channel $it -"),
                        Matcher(text = it),
                        Matcher(descStartsWith = "Playlist - ", descContains = " - $it - "),
                    )
                },
                action = Action.BACK,
                // watch_player alone is no watch page: it is also the miniplayer over search results.
                page = listOf(Matcher(viewId = "watch_panel"), Matcher(viewId = "collapsing_header_container")),
                // Back leaves the watch page as a playing miniplayer; closing it stops the video.
                dismiss = listOf(Matcher(viewId = "modern_miniplayer_close")),
                // Home would leave the video playing for Premium background play; Back walks it down.
                escalate = Action.BACK,
                followTiles = true,
            ),
            countAs = ruleId,
        )
    }

/**
 * The video or playlist title in a YouTube tile description, e.g. "Spring - Blender Open Movie" from
 * "Spring - Blender Open Movie - 7 minutes, 45 seconds - Go to channel Blender Studio - …", or
 * "Official Blender Open Movies" from "Playlist - Official Blender Open Movies - Blender - 17 videos".
 * The watch page shows that title as a text of its own, which names the video when its owner row is
 * hidden (playlist panel) or shows a collaboration ("@BlenderStudio +1").
 */
fun tileTitle(desc: String): String? {
    if (desc.startsWith("Playlist - ")) {
        val parts = desc.removePrefix("Playlist - ").split(" - ")
        return parts.dropLast(2).joinToString(" - ").takeIf { parts.size >= 3 && it.isNotEmpty() }
    }
    val beforeChannel = desc.substringBefore(" - Go to channel ", "").ifEmpty { return null }
    // The last part before the channel is the duration.
    return beforeChannel.substringBeforeLast(" - ", "").ifEmpty { null }
}
