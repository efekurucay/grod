package dev.grod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherTest {
    private val rules = Rules.parse(
        """{"schemaVersion":1,"rules":[
            {"id":"ig","title":"IG","enabled":true,"android":{"packages":["com.instagram.android"]}},
            {"id":"tt","title":"TT","enabled":false,"android":{"packages":["com.zhiliaoapp.musically"]}}],
           "launcher":{"distracting":["com.twitter.android"],"hidden":["com.android.stk"]}}""",
    )

    private fun app(pkg: String, label: String) = AppEntry(pkg, "$pkg.Main", label)
    private val ig = app("com.instagram.android", "Instagram")
    private val x = app("com.twitter.android", "X")
    private val tiktok = app("com.zhiliaoapp.musically", "TikTok")
    private val maps = app("com.google.android.apps.maps", "Maps")
    private val stk = app("com.android.stk", "SIM Toolkit")
    private val ogrenci = app("tr.edu.ogrenci", "Öğrenci Belgesi")
    private val isbank = app("com.ykb.isbank", "İşCep")
    private val all = listOf(ig, x, tiktok, maps, stk, ogrenci, isbank)

    @Test
    fun parsesLauncherSettings() {
        assertEquals(LauncherSettings(listOf("com.twitter.android"), listOf("com.android.stk")), rules.launcher)
        assertEquals(LauncherSettings(), Rules.parse("""{"schemaVersion":1,"rules":[]}""").launcher)
    }

    @Test
    fun distractingComesFromEnabledRulesSettingsAndMarks() {
        assertEquals(setOf("com.instagram.android", "com.twitter.android"), Launcher.distracting(rules, LocalMarks()))
        val marks = LocalMarks(distracting = setOf("com.google.android.apps.maps"), notDistracting = setOf("com.instagram.android"))
        assertEquals(setOf("com.twitter.android", "com.google.android.apps.maps"), Launcher.distracting(rules, marks))
    }

    @Test
    fun togglesOverruleOrUndo() {
        val byRules = Launcher.toggleDistracting(LocalMarks(), ig.pkg, byRules = true, now = true)
        assertFalse(ig.pkg in Launcher.distracting(rules, byRules))
        val back = Launcher.toggleDistracting(byRules, ig.pkg, byRules = true, now = false)
        assertEquals(LocalMarks(), back)
        val marked = Launcher.toggleDistracting(LocalMarks(), maps.pkg, byRules = false, now = false)
        assertTrue(maps.pkg in Launcher.distracting(rules, marked))
        assertEquals(LocalMarks(), Launcher.toggleDistracting(marked, maps.pkg, byRules = false, now = true))
        val unhidden = Launcher.toggleHidden(LocalMarks(), stk.pkg, byRules = true, now = true)
        assertFalse(stk.pkg in Launcher.hidden(rules, unhidden))
    }

    @Test
    fun listsUsefulFirstAlphabeticallyAndHidesHidden() {
        val listing = Launcher.list(all, Launcher.distracting(rules, LocalMarks()), Launcher.hidden(rules, LocalMarks()))
        assertEquals(listOf("İşCep", "Maps", "Öğrenci Belgesi", "TikTok"), listing.useful.map { it.label })
        assertEquals(listOf("Instagram", "X"), listing.distracting.map { it.label })
    }

    @Test
    fun foldsTurkishCaseAndAccents() {
        assertEquals("iscep", Launcher.fold("İşCep"))
        assertEquals("instagram", Launcher.fold("INSTAGRAM"))
        assertEquals("instagram", Launcher.fold("ınstagram"))
        assertEquals("ogrenci belgesi", Launcher.fold("Öğrenci Belgesi"))
    }

    @Test
    fun searchFindsHiddenRanksAndPutsDistractingLast() {
        val distracting = Launcher.distracting(rules, LocalMarks())
        assertEquals(listOf(isbank), Launcher.search(all, "isc", distracting))
        assertEquals(listOf(ogrenci), Launcher.search(all, "ogrenci", distracting))
        assertEquals(listOf(stk), Launcher.search(all, "toolkit", distracting))
        assertEquals(listOf(ig), Launcher.search(all, "INST", distracting))
        // Useful before distracting; then a label starting with the query; then alphabetical.
        assertEquals(listOf("SIM Toolkit", "İşCep", "Maps", "Öğrenci Belgesi", "Instagram"), Launcher.search(all, "s", distracting).map { it.label })
        assertTrue(Launcher.search(all, "   ", distracting).isEmpty())
    }

    private val hour = 60 * 60_000L

    @Test
    fun sizesFollowUseOnALogScale() {
        val use = mapOf("a" to 40 * hour, "b" to 10 * hour, "c" to hour, "d" to 5 * 60_000L)
        val steps = Launcher.sizeSteps(listOf("a", "b", "c", "d", "e"), use, emptyMap(), emptySet())
        assertEquals(4, steps["a"])
        assertEquals(0, steps["e"])
        assertTrue(steps["a"]!! >= steps["b"]!! && steps["b"]!! >= steps["c"]!! && steps["c"]!! >= steps["d"]!! && steps["d"]!! >= steps["e"]!!)
        assertEquals(5, Launcher.SIZES_SP.size)
        // Launches from the home screen count too.
        assertTrue(Launcher.sizeSteps(listOf("x", "y"), mapOf("x" to hour), mapOf("y" to 100), emptySet())["y"]!! > 0)
    }

    @Test
    fun atMostThreeAtTheLargestSize() {
        val pkgs = (1..6).map { "p$it" }
        val steps = Launcher.sizeSteps(pkgs, pkgs.associateWith { 10 * hour }, emptyMap(), emptySet())
        assertEquals(3, steps.values.count { it == 4 })
        assertEquals(3, steps.values.count { it == 3 })
    }

    @Test
    fun distractingNeverGrows() {
        val use = mapOf("com.instagram.android" to 300 * hour, "maps" to 2 * hour)
        val steps = Launcher.sizeSteps(listOf("com.instagram.android", "maps"), use, mapOf("com.instagram.android" to 999), setOf("com.instagram.android"))
        assertEquals(0, steps["com.instagram.android"])
        // Its heavy use is not the yardstick either: the most used useful app still gets the top size.
        assertEquals(4, steps["maps"])
        assertTrue(Launcher.sizeSteps(listOf("a"), emptyMap(), emptyMap(), emptySet()).values.all { it == 0 })
    }

    @Test
    fun weatherFlagDefaultsOff() {
        assertFalse(rules.launcher.weather)
        assertTrue(Rules.parse("""{"schemaVersion":1,"rules":[],"launcher":{"weather":true}}""").launcher.weather)
    }

    @Test
    fun fitScaleFindsTheLargestFittingFactor() {
        assertEquals(1.0, Launcher.fitScale { true }!!, 0.0)
        assertEquals(null, Launcher.fitScale { false })
        // Fits up to 0.8: the search lands just below it, never above.
        val found = Launcher.fitScale { it <= 0.8 }!!
        assertTrue(found <= 0.8 && found > 0.79)
        assertEquals(0.55, Launcher.fitScale { it <= 0.55 }!!, 0.0)
    }

    @Test
    fun homeScreensAreLeftOut() {
        val ap15 = app("com.aurhe.ap15", "ap15")
        val grod = app("dev.grod", "grod")
        val apps = listOf(ap15, grod, maps)
        assertEquals(listOf(grod, maps), Launcher.withoutHomes(apps, setOf("com.aurhe.ap15", "com.huawei.android.launcher", "dev.grod"), "dev.grod"))
    }

    @Test
    fun cloudKeepsAppsOpenedInTheLastWeek() {
        val now = 100 * Launcher.UNUSED_MS
        val day = 24 * 60 * 60 * 1000L
        val recent = Launcher.recent(
            mapOf(maps.pkg to now - 6 * day, ig.pkg to now - day, tiktok.pkg to now - 8 * day),
            mapOf(
                // Found by search and opened from grod a minute ago: in at once.
                ogrenci.pkg to listOf(now - 60_000L),
                stk.pkg to listOf(now - 8 * day),
            ),
            now,
        )
        assertEquals(setOf(maps.pkg, ig.pkg, ogrenci.pkg), recent)
        val listing = Launcher.list(all, Launcher.distracting(rules, LocalMarks()), emptySet(), recent)
        assertEquals(listOf("Maps", "Öğrenci Belgesi"), listing.useful.map { it.label })
        // Distracting apps follow the same rule: Instagram was opened, TikTok (8 days ago) was not.
        assertEquals(listOf("Instagram"), listing.distracting.map { it.label })
        // Unknown use (no usage access): everything shows.
        assertEquals(5, Launcher.list(all, emptySet(), setOf(stk.pkg, x.pkg)).useful.size)
        // Search still finds the rest.
        assertEquals(listOf(isbank), Launcher.search(all, "iscep", emptySet()))
    }

    @Test
    fun mostUsedComeFirst() {
        val steps = mapOf(maps.pkg to 2, isbank.pkg to 4, ogrenci.pkg to 2)
        val ordered = Launcher.byUse(listOf(ogrenci, maps, tiktok, isbank)) { steps[it] ?: 0 }
        assertEquals(listOf("İşCep", "Maps", "Öğrenci Belgesi", "TikTok"), ordered.map { it.label })
    }

    @Test
    fun suggestsUninstallingUserAppsUnopenedForThirtyDays() {
        val now = 100 * Launcher.UNUSED_MS
        val day = 24 * 60 * 60 * 1000L
        val unused = Launcher.unused(
            installedAt = mapOf(
                maps.pkg to now - 90 * day,
                isbank.pkg to now - 90 * day,
                ogrenci.pkg to now - 90 * day,
                // Installed last week: too new to judge.
                tiktok.pkg to now - 7 * day,
            ),
            lastOpened = mapOf(maps.pkg to now - 31 * day, isbank.pkg to now - 29 * day),
            launchTimes = mapOf(ogrenci.pkg to listOf(now - day)),
            now = now,
        )
        assertEquals(setOf(maps.pkg), unused)
    }

    @Test
    fun voiceSearchFindsWhatWasMeantAsSaid() {
        val whatsapp = app("com.whatsapp", "WhatsApp")
        val chrome = app("com.android.chrome", "Chrome")
        val apps = all + whatsapp + chrome
        // Found as heard, before the Turkish suffix, or without spaces.
        assertEquals("instagram", Launcher.voiceQuery(apps, listOf("instagram")))
        assertEquals("WhatsApp", Launcher.voiceQuery(apps, listOf("WhatsApp'ı aç")))
        assertEquals("whatsapp", Launcher.voiceQuery(apps, listOf("whats app")))
        // Not found by search: the closest sounding name.
        assertEquals("WhatsApp", Launcher.voiceQuery(apps, listOf("vatsap")))
        assertEquals("WhatsApp", Launcher.voiceQuery(apps, listOf("vatsap aç")))
        assertEquals("Chrome", Launcher.voiceQuery(apps, listOf("krome")))
        // Nothing close enough.
        assertEquals(null, Launcher.voiceQuery(apps, listOf("hava durumu")))
    }

    @Test
    fun dockKeepsItsOrderTakesPhoneChangesAndLeavesTheCloud() {
        val docked = Rules.parse("""{"schemaVersion":1,"rules":[],"launcher":{"dock":["com.whatsapp","com.google.android.apps.maps"]}}""")
        assertEquals(listOf("com.whatsapp", "com.google.android.apps.maps"), Launcher.dock(docked, LocalMarks()))
        // Removed on the phone, another added there: rules order first, phone additions after.
        var local = Launcher.toggleDocked(LocalMarks(), "com.whatsapp", byRules = true, now = true)
        local = Launcher.toggleDocked(local, isbank.pkg, byRules = false, now = false)
        assertEquals(listOf(maps.pkg, isbank.pkg), Launcher.dock(docked, local))
        // Docked apps are not words in the cloud too.
        val listing = Launcher.list(all, emptySet(), emptySet(), docked = setOf(maps.pkg, isbank.pkg))
        assertFalse(listing.useful.any { it.pkg == maps.pkg || it.pkg == isbank.pkg })
        assertEquals(listOf(maps), Launcher.search(all, "maps", emptySet()))
    }
}
