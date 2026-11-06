package dev.grod

/**
 * A TYPE_VIEW_SCROLLED event; [source] is null when the app reported no source node. [index] is the
 * event's toIndex (a pager's current item), -1 when the app reported none.
 */
class Scroll(val source: Box?, val index: Int = -1)

/**
 * What one event asks the service to do: overlays to show and rules whose action fires now.
 * [covers] swallow taps, [masks] let them through. [onFeed] holds the ids of rules whose feed is on
 * screen, including rules in cooldown.
 */
data class Outcome(val covers: List<Box>, val masks: List<Box>, val fire: List<Rule>, val onFeed: Set<String>)

/**
 * Cover/mask/feed/exempt/action semantics of docs/rules.md, free of Android types so it is unit tested.
 * Not thread-safe; the service calls it from the main thread only.
 */
class Engine(
    private val cooldownMs: Long = COOLDOWN_MS,
    private val scanner: Scanner = Scanner(),
) {
    private val cooldownUntil = HashMap<String, Long>()

    /** Rules whose feed got a scroll while exempt; the exemption ends until the feed leaves the screen. */
    private val scrolledInFeed = HashSet<String>()

    /**
     * Per rule, the item index of the first feed scroll while on the feed. A pager reports a scroll when
     * it settles on the item it opened with; only a different index means the user swiped on.
     */
    private val openedIndex = HashMap<String, Int>()

    /** Per [AndroidRule.followTiles] rule: titles of its masked tiles seen lately, oldest first. */
    private val tileTitles = HashMap<String, LinkedHashSet<String>>()

    /** [AndroidRule.followTiles] rules that fired on the page now on screen. */
    private val firedOnPage = HashSet<String>()

    fun evaluate(rules: List<Rule>, pkg: String, tree: WindowTree, scroll: Scroll?, now: Long): Outcome {
        val wanted = rules.flatMap { it.android!!.run { feed + page + cover + mask } }
        val following = rules.filter { it.android!!.followTiles }
        val seenTitles = LinkedHashSet<String>()
        val hits = scanner.scan(tree, pkg, wanted, now, following.flatMap { it.android!!.mask }.toSet()) { desc ->
            tileTitle(desc)?.let(seenTitles::add)
        }
        val clip = tree.clip
        fun boxes(select: (AndroidRule) -> List<Matcher>) = rules
            .flatMap { r -> select(r.android!!).flatMap { hits[it].orEmpty() } }
            .mapNotNull { if (clip == null) it else it.intersect(clip) }
            .distinct()
        val covers = boxes { it.cover }
        // A node under a cover needs no mask; the cover must stay the window that takes the tap.
        val masks = boxes { it.mask }.filter { m -> covers.none { it.contains(m) } }
        val titled = followTitles(following, pkg, tree, hits, seenTitles, now)

        val onFeed = HashSet<String>()
        val due = rules.filter { rule ->
            val android = rule.android!!
            val feedBoxes = android.feed.flatMap { hits[it].orEmpty() }
            if (!feedOn(rule, hits, rule.id in titled)) {
                scrolledInFeed.remove(rule.id)
                openedIndex.remove(rule.id)
                return@filter false
            }
            onFeed.add(rule.id)
            if (scroll != null && scrollIsInFeed(scroll, android, feedBoxes) && movedOn(rule.id, scroll)) {
                scrolledInFeed.add(rule.id)
            }
            now >= (cooldownUntil[rule.id] ?: 0L)
        }

        // Exempt markers are only looked up for rules that would fire, which keeps idle events cheap.
        val needExempt = due.filter { it.android!!.exempt.isNotEmpty() && it.id !in scrolledInFeed }
        val exemptHits = if (needExempt.isEmpty()) emptyMap()
        else scanner.scan(tree, pkg, needExempt.flatMap { it.android!!.exempt }, now)
        val fire = due.filter { rule ->
            rule !in needExempt || rule.android!!.exempt.none { exemptHits[it].orEmpty().isNotEmpty() }
        }
        for (rule in fire) markFired(rule, now)
        return Outcome(covers, masks, fire, onFeed)
    }

    /**
     * Whether [rule]'s action would still apply on this screen, ignoring cooldown. Decides the
     * escalation that follows a Back, so a reel opened from a DM in the meantime keeps its exemption.
     */
    fun stillDue(rule: Rule, pkg: String, tree: WindowTree, now: Long): Boolean {
        val android = rule.android ?: return false
        if (!rule.enabled) return false
        if (!feedOn(rule, scanner.scan(tree, pkg, android.feed + android.page, now), titled = false)) return false
        if (android.exempt.isEmpty() || rule.id in scrolledInFeed) return true
        return scanner.scan(tree, pkg, android.exempt, now).isEmpty()
    }

    /** Forgets feed scrolls, e.g. when another window comes to the front. */
    fun resetSession() {
        scrolledInFeed.clear()
        openedIndex.clear()
    }

    /** Forgets everything tied to the app in front; called when another app comes to the front. */
    fun leaveApp() {
        resetSession()
        firedOnPage.clear()
    }

    fun markFired(rule: Rule, now: Long) {
        cooldownUntil[rule.id] = now + cooldownMs
        if (rule.android?.followTiles == true) firedOnPage.add(rule.id)
    }

    /**
     * Remembers the titles of masked tiles while no page is open, and returns the ids of rules whose
     * open page shows one of them. Off a page, the fired-on-page mark ends.
     */
    private fun followTitles(
        following: List<Rule>,
        pkg: String,
        tree: WindowTree,
        hits: Map<Matcher, List<Box>>,
        seen: Set<String>,
        now: Long,
    ): Set<String> {
        val titled = HashSet<String>()
        for (rule in following) {
            val android = rule.android!!
            val titles = tileTitles.getOrPut(rule.id) { LinkedHashSet() }
            if (!pageOn(android, hits)) {
                firedOnPage.remove(rule.id)
                titles.addAll(seen)
                while (titles.size > MAX_TITLES) titles.remove(titles.first())
                continue
            }
            if (rule.id in firedOnPage || android.feed.any { !hits[it].isNullOrEmpty() } || titles.isEmpty()) continue
            val byTitle = titles.map { Matcher(text = it) }
            if (scanner.scan(tree, pkg, byTitle, now).isNotEmpty()) titled.add(rule.id)
        }
        return titled
    }

    private fun feedOn(rule: Rule, hits: Map<Matcher, List<Box>>, titled: Boolean): Boolean {
        val android = rule.android!!
        fun hit(m: Matcher) = !hits[m].isNullOrEmpty()
        if (!pageOn(android, hits)) return false
        return android.feed.isEmpty() || android.feed.any(::hit) || titled || rule.id in firedOnPage
    }

    private fun pageOn(android: AndroidRule, hits: Map<Matcher, List<Box>>): Boolean =
        android.page.isEmpty() || android.page.any { !hits[it].isNullOrEmpty() }

    /** A scroll without an index counts as moving on; one with an index only when it left the opened item. */
    private fun movedOn(ruleId: String, scroll: Scroll): Boolean {
        if (scroll.index < 0) return true
        return scroll.index != openedIndex.getOrPut(ruleId) { scroll.index }
    }

    /** Only a scroll attributed to the feed ends an exemption; an unknown source keeps it. */
    private fun scrollIsInFeed(scroll: Scroll, android: AndroidRule, feedBoxes: List<Box>): Boolean {
        val source = scroll.source ?: return false
        return android.feed.isEmpty() || feedBoxes.any { it.contains(source) }
    }

    companion object {
        const val COOLDOWN_MS = 1500L

        /** Titles kept per rule; enough for several screens of search results. */
        const val MAX_TITLES = 200

    }
}
