package dev.grod

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One node condition; every non-null field must match (docs/rules.md, Android). */
data class Matcher(
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val descContains: String? = null,
    val selected: Boolean? = null,
    /** Not in the rules format; generated channel rules use it for playlist tiles. */
    val descStartsWith: String? = null,
)

enum class Action { BACK, HOME }

data class AndroidRule(
    val packages: List<String>,
    val feed: List<Matcher>,
    val exempt: List<Matcher>,
    val cover: List<Matcher>,
    val mask: List<Matcher>,
    val action: Action,
    // The fields below are not in the rules format; only generated channel rules set them.
    /** When set, the feed counts only on one of these pages (e.g. a full watch page, not the miniplayer). */
    val page: List<Matcher> = emptyList(),
    /** After the action: media is paused and these are clicked once they appear (YouTube's miniplayer close). */
    val dismiss: List<Matcher> = emptyList(),
    /** What follows a Back when the feed is still on screen 1.5 s later. */
    val escalate: Action = Action.HOME,
    /**
     * A page showing the title of one of this rule's masked tiles counts as feed, and once the action
     * fired on a page, that page keeps counting until it is gone (a Back may only collapse a panel).
     */
    val followTiles: Boolean = false,
)

data class Rule(
    val id: String,
    val title: String,
    val why: String?,
    val enabled: Boolean,
    val platforms: List<String>,
    val android: AndroidRule?,
    val youtubeChannels: List<Channel> = emptyList(),
    /** Fire counter key; a generated channel rule counts under the rule that lists the channel. */
    val countAs: String = id,
)

/** Parsed rules plus the channels added on this phone ([localChannels]), which apply on top. */
data class RuleSet(val revision: String?, val rules: List<Rule>, val localChannels: List<Channel> = emptyList()) {
    val channels: List<ChannelEntry> = mergeChannels(rules, localChannels)

    private val active = rules.filter { it.enabled && it.android != null } + channelRules(rules, channels)

    /** Packages the service listens to. */
    val targetPackages: Set<String> = active.flatMap { it.android!!.packages }.toSet()

    fun rulesFor(pkg: String): List<Rule> = active.filter { pkg in it.android!!.packages }

    companion object {
        val EMPTY = RuleSet(null, emptyList())
    }
}

class InvalidRulesException(message: String) : Exception(message)

/**
 * Parses a rules copy. Beyond the minimum in docs/rules.md (parses, schemaVersion 1, rules array),
 * the Android section is checked strictly because a malformed matcher could match every node.
 * Web and macOS sections are other platforms' business and are only counted.
 */
object Rules {
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val PLATFORMS = listOf("web", "android", "macos")
    private val ANDROID_KEYS = setOf("packages", "feed", "exempt", "cover", "mask", "action")
    private val MATCHER_KEYS = setOf("viewId", "text", "desc", "descContains", "selected")
    private val CHANNEL_KEYS = setOf("name", "handle", "id")
    private val HANDLE = Regex("^@[^\\s/?#]+$")
    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

    fun parse(json: String): RuleSet {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw InvalidRulesException("not a JSON object: ${e.message}")
        }
        val version = root.opt("schemaVersion")
        if (version !is Number || version.toDouble() != 1.0) fail("schemaVersion", "must be 1")
        val list = root.opt("rules") as? JSONArray ?: fail("rules", "must be an array")
        val revision = root.opt("revision")?.let { it as? String ?: fail("revision", "must be a string") }

        val seen = HashSet<String>()
        val rules = (0 until list.length()).map { i ->
            val path = "rules[$i]"
            val rule = parseRule(list.opt(i) as? JSONObject ?: fail(path, "must be an object"), path)
            if (!seen.add(rule.id)) fail(path, "duplicate id \"${rule.id}\"")
            rule
        }
        return RuleSet(revision, rules)
    }

    private fun parseRule(o: JSONObject, path: String): Rule {
        val id = o.opt("id") as? String ?: fail("$path.id", "must be a string")
        if (!ID.matches(id)) fail("$path.id", "must be kebab-case")
        val title = o.opt("title") as? String ?: fail("$path.title", "must be a string")
        if (title.isEmpty()) fail("$path.title", "must not be empty")
        val enabled = o.opt("enabled") as? Boolean ?: fail("$path.enabled", "must be a boolean")
        val why = o.opt("why")?.let { it as? String ?: fail("$path.why", "must be a string") }
        val android = o.opt("android")?.let {
            parseAndroid(it as? JSONObject ?: fail("$path.android", "must be an object"), "$path.android")
        }
        val channels = o.opt("youtubeChannels")?.let { parseChannels(it, "$path.youtubeChannels") }.orEmpty()
        return Rule(id, title, why, enabled, PLATFORMS.filter { o.has(it) }, android, channels)
    }

    /** A `youtubeChannel` array; also the format of channels-added.json's "channels". */
    fun parseChannels(raw: Any, path: String): List<Channel> {
        val arr = raw as? JSONArray ?: fail(path, "must be an array")
        return (0 until arr.length()).map { i ->
            val p = "$path[$i]"
            val o = arr.opt(i) as? JSONObject ?: fail(p, "must be an object")
            unknownKey(o, CHANNEL_KEYS)?.let { fail("$p.$it", "unknown field") }
            val name = o.opt("name") as? String
            if (name.isNullOrEmpty()) fail("$p.name", "must be a non-empty string")
            val handle = o.opt("handle") as? String
            if (handle == null || !HANDLE.matches(handle)) fail("$p.handle", "must look like @handle")
            val id = o.opt("id")?.let { it as? String }
            if (o.has("id") && (id == null || !CHANNEL_ID.matches(id))) fail("$p.id", "must look like UC + 22 characters")
            Channel(name, handle, id)
        }
    }

    private fun parseAndroid(o: JSONObject, path: String): AndroidRule {
        unknownKey(o, ANDROID_KEYS)?.let { fail("$path.$it", "unknown field") }
        val packages = o.opt("packages") as? JSONArray ?: fail("$path.packages", "must be an array")
        if (packages.length() == 0) fail("$path.packages", "must not be empty")
        val pkgs = (0 until packages.length()).map { i ->
            val p = packages.opt(i) as? String
            if (p.isNullOrEmpty()) fail("$path.packages[$i]", "must be a non-empty string")
            p
        }
        val action = when (val a = o.opt("action")) {
            null, "back" -> Action.BACK
            "home" -> Action.HOME
            else -> fail("$path.action", "must be \"back\" or \"home\", got $a")
        }
        return AndroidRule(
            packages = pkgs,
            feed = parseMatchers(o, "feed", path),
            exempt = parseMatchers(o, "exempt", path),
            cover = parseMatchers(o, "cover", path),
            mask = parseMatchers(o, "mask", path),
            action = action,
        )
    }

    private fun parseMatchers(parent: JSONObject, key: String, parentPath: String): List<Matcher> {
        val raw = parent.opt(key) ?: return emptyList()
        val path = "$parentPath.$key"
        val arr = raw as? JSONArray ?: fail(path, "must be an array")
        return (0 until arr.length()).map { i -> parseMatcher(arr.opt(i), "$path[$i]") }
    }

    private fun parseMatcher(raw: Any?, path: String): Matcher {
        val o = raw as? JSONObject ?: fail(path, "must be an object")
        unknownKey(o, MATCHER_KEYS)?.let { fail("$path.$it", "unknown field") }
        if (o.length() == 0) fail(path, "needs at least one field")
        fun str(k: String) = o.opt(k)?.let { it as? String ?: fail("$path.$k", "must be a string") }
        val selected = o.opt("selected")?.let { it as? Boolean ?: fail("$path.selected", "must be a boolean") }
        val descContains = str("descContains")
        // An empty substring would match every node that has a description.
        if (descContains == "") fail("$path.descContains", "must not be empty")
        return Matcher(str("viewId"), str("text"), str("desc"), descContains, selected)
    }

    private fun unknownKey(o: JSONObject, allowed: Set<String>): String? =
        o.keys().asSequence().firstOrNull { it !in allowed }

    private fun fail(path: String, message: String): Nothing = throw InvalidRulesException("$path: $message")
}
