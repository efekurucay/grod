package dev.grod

/** A TYPE_VIEW_SCROLLED event; [source] is null when the app reported no source node. */
class Scroll(val source: Box?)

/**
 * What one event asks the service to do: overlays to show and rules whose action fires now.
 * [onFeed] holds the ids of rules whose feed is on screen, including rules in cooldown.
 */
data class Outcome(val covers: List<Box>, val fire: List<Rule>, val onFeed: Set<String>)

/**
 * Feed/exempt/cover/action semantics of docs/rules.md, free of Android types so it is unit tested.
 * Not thread-safe; the service calls it from the main thread only.
 */
class Engine(private val cooldownMs: Long = COOLDOWN_MS) {
    private val cooldownUntil = HashMap<String, Long>()

    /** Rules whose feed got a scroll while exempt; the exemption ends until the feed leaves the screen. */
    private val scrolledInFeed = HashSet<String>()

    fun evaluate(rules: List<Rule>, pkg: String, tree: WindowTree, scroll: Scroll?, now: Long): Outcome {
        val hits = scan(tree, pkg, rules.flatMap { it.android!!.feed + it.android.cover })
        val covers = rules.flatMap { r -> r.android!!.cover.flatMap { hits[it].orEmpty() } }.distinct()

        val onFeed = HashSet<String>()
        val due = rules.filter { rule ->
            val android = rule.android!!
            val feedBoxes = android.feed.flatMap { hits[it].orEmpty() }
            if (android.feed.isNotEmpty() && feedBoxes.isEmpty()) {
                scrolledInFeed.remove(rule.id)
                return@filter false
            }
            onFeed.add(rule.id)
            if (scroll != null && scrollIsInFeed(scroll, android, feedBoxes)) scrolledInFeed.add(rule.id)
            now >= (cooldownUntil[rule.id] ?: 0L)
        }

        // Exempt markers are only looked up for rules that would fire, which keeps idle events cheap.
        val needExempt = due.filter { it.android!!.exempt.isNotEmpty() && it.id !in scrolledInFeed }
        val exemptHits = if (needExempt.isEmpty()) emptyMap()
        else scan(tree, pkg, needExempt.flatMap { it.android!!.exempt })
        val fire = due.filter { rule ->
            rule !in needExempt || rule.android!!.exempt.none { exemptHits[it].orEmpty().isNotEmpty() }
        }
        for (rule in fire) markFired(rule.id, now)
        return Outcome(covers, fire, onFeed)
    }

    /**
     * Whether [rule]'s action would still apply on this screen, ignoring cooldown. Decides the Home that
     * follows a Back, so a reel opened from a DM in the meantime keeps its exemption.
     */
    fun stillDue(rule: Rule, pkg: String, tree: WindowTree): Boolean {
        val android = rule.android ?: return false
        if (!rule.enabled) return false
        if (android.feed.isNotEmpty() && scan(tree, pkg, android.feed).isEmpty()) return false
        if (android.exempt.isEmpty() || rule.id in scrolledInFeed) return true
        return scan(tree, pkg, android.exempt).isEmpty()
    }

    /** Forgets feed scrolls, e.g. when another app or window comes to the front. */
    fun resetSession() {
        scrolledInFeed.clear()
    }

    fun markFired(ruleId: String, now: Long) {
        cooldownUntil[ruleId] = now + cooldownMs
    }

    /** Only a scroll attributed to the feed ends an exemption; an unknown source keeps it. */
    private fun scrollIsInFeed(scroll: Scroll, android: AndroidRule, feedBoxes: List<Box>): Boolean {
        val source = scroll.source ?: return false
        return android.feed.isEmpty() || feedBoxes.any { it.contains(source) }
    }

    companion object {
        const val COOLDOWN_MS = 1500L
    }
}
