package dev.grod

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/** Setup checklist, status, notifications, weekly review, rules, blocked channels and the rules URL. */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_REVIEW = "review"
        const val REVIEW_HOW = "Plug in or use Wi-Fi adb, then click the grod notification on your Mac."
    }

    private lateinit var store: RuleStore
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var scroll: ScrollView
    private var reviewSection: View? = null

    /** Set when the weekly review notification opened the app; consumed by the next render. */
    private var scrollToReview = false
    private val onStoreChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)
        ui = Ui(this)

        val page = ui.column()
        page.addView(header())
        content = ui.column().also(page::addView)
        // The field lives outside the re-rendered part so typing survives a refresh.
        val urlBody = ui.section(page, "Rules URL")
        urlInput = ui.input("https://example.com/grod.rules.json").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(store.rulesUrl.orEmpty())
        }
        urlBody.addView(ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            addView(urlInput)
            addView(ui.muted("Optional. An HTTPS copy of grod.rules.json, checked every 6 hours.").apply {
                setPadding(0, ui.dp(4), 0, ui.dp(12))
            })
            addView(ui.button("Save & refresh", Ui.Kind.PRIMARY) {
                store.rulesUrl = urlInput.text.toString()
                store.checkLocal()
                store.fetch()
                render()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        })

        scroll = ScrollView(this).apply { addView(page) }
        scrollToReview = intent.getStringExtra(EXTRA_SECTION) == SECTION_REVIEW
        applySystemBarPadding(scroll, ui.dp(16))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        store.addListener(onStoreChanged)
        store.checkLocal()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        scrollToReview = intent.getStringExtra(EXTRA_SECTION) == SECTION_REVIEW
    }

    override fun onPause() {
        store.removeListener(onStoreChanged)
        super.onPause()
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(13), ui.dp(16), ui.dp(13), 0)
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The mark is a "g" with a descender, so it sits a little lower than the wordmark's center.
        brand.addView(
            ImageView(context).apply { setImageResource(R.drawable.grod_mark); translationY = ui.dp(3).toFloat() },
            LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginStart = -ui.dp(4) },
        )
        brand.addView(ui.text("grod", 24f, bold = true).apply { setPadding(ui.dp(8), 0, 0, 0) })
        addView(brand)
        addView(ui.muted("Get rid of distracting. Keep what matters.", 15f).apply { setPadding(0, ui.dp(4), 0, 0) })
    }

    private fun render() {
        content.removeAllViews()
        setup()
        status()
        notifications()
        review()
        rules()
        channels()
        if (scrollToReview) {
            scrollToReview = false
            reviewSection?.let { target -> scroll.post { scroll.smoothScrollTo(0, target.top) } }
        }
    }

    private fun review() {
        val body = ui.section(content, "Weekly review")
        reviewSection = body
        val review = store.rules.review
        ui.kv(body, "When", review?.label ?: "Not set in the rules")
        if (review != null) {
            body.addView(ui.muted(REVIEW_HOW, 15f).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0) })
        }
        ui.kvEnd(body)
    }

    private class Step(val title: String, val why: String, val done: Boolean, val open: () -> Unit)

    private fun settings(action: String): () -> Unit = { startActivity(Intent(action)) }

    /** Shown until every access is granted, so a sideloaded install can be finished without adb. */
    private fun setup() {
        val steps = mutableListOf(
            Step("Accessibility service", "Removes the feeds. Find grod under Downloaded apps.",
                Permissions.accessibilityService(this), settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Step("Notification access", "Holds notifications until the next delivery.",
                Permissions.notificationAccess(this), settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            Step("Do Not Disturb access", "Keeps the phone quiet while notifications wait. Calls still ring.",
                Permissions.dndAccess(this), settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            Step("Usage access", "Screen time per app, for your own review.",
                Permissions.usageAccess(this), settings(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
        )
        // Android 13+ asks before an app may post; grod only posts the weekly review reminder.
        if (Build.VERSION.SDK_INT >= 33) {
            steps.add(Step("Notifications", "For the weekly review reminder.",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0) })
        }
        if (steps.all { it.done }) return
        val body = ui.section(content, "Set up ${steps.count { it.done }} of ${steps.size}")
        for ((i, step) in steps.withIndex()) {
            val end = if (step.done) ui.muted("Done", 15f) else ui.button("Open", onClick = step.open)
            // Android 13+ greys out these switches for sideloaded apps until restricted settings are allowed.
            val restricted = Build.VERSION.SDK_INT >= 33 && !step.done && i < 2
            val extra = if (!restricted) null else ui.column().apply {
                addView(ui.muted("Greyed out? App info → ⋮ → Allow restricted settings."))
                addView(ui.button("App info", Ui.Kind.GHOST) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                })
            }
            ui.row(body, step.title, step.why, end, extra = extra)
        }
    }

    private fun status() {
        val body = ui.section(content, "Status")
        val on = Permissions.accessibilityService(this)
        // The one accent on the screen: grod is on.
        ui.kv(body, "Blocking", if (on) "On" else "Off", if (on) R.color.grod_accent else R.color.grod_muted_foreground)
        ui.kv(body, "Rules", store.revision)
        ui.kv(body, "Source", store.source)
        store.lastError?.let { ui.kv(body, "Last error", it, R.color.grod_caution) }
        if (store.rulesUrl != null) {
            val fetched = store.lastFetchAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
            ui.kv(body, "Last fetch", fetched ?: "never")
        }
        ui.kvEnd(body)
    }

    private fun notifications() {
        val body = ui.section(content, "Notifications")
        val s = store.rules.notifications
        if (s == null || !s.enabled) {
            ui.kv(body, "Batching", if (s == null) "Not set in the rules" else "Off")
            ui.kvEnd(body)
            return
        }
        val now = System.currentTimeMillis()
        val schedule = s.deliverAt?.joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) } ?: "Every ${s.deliverEvery} min"
        ui.kv(body, "Batching", "On")
        ui.kv(body, "Delivers", schedule)
        ui.kv(body, "Next", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Batching.nextDelivery(now, s.slots, ZoneId.systemDefault()))))
        ui.kv(body, "Waiting", "${Held(this).waiting(now).size}")
        ui.kvEnd(body)
    }

    private fun rules() {
        val body = ui.section(content, "Rules")
        for (rule in store.rules.rules) {
            val parts = mutableListOf<String>()
            rule.android?.let { a ->
                if (a.cover.isNotEmpty()) parts.add("covers ${a.cover.size}")
                if (a.mask.isNotEmpty()) parts.add("masks ${a.mask.size}")
            }
            if (rule.youtubeChannels.isNotEmpty()) parts.add("channels ${rule.youtubeChannels.size}")
            parts.add("acted ${store.fireCount(rule.id)}×")
            val end = ui.muted(if (rule.enabled) "On" else "Off", 15f)
            ui.row(body, rule.title, parts.joinToString(" · "), end, off = !rule.enabled)
        }
        if (store.rules.rules.isEmpty()) ui.row(body, "No rules yet", off = true)
    }

    private fun channels() {
        val body = ui.section(content, "Blocked YouTube channels")
        for ((channel, ruleId) in store.rules.channels) {
            // Rule channels are removed with `grod channel remove`; the phone only owns what it added.
            val end = if (ruleId == null) ui.button("Remove") { store.removeLocalChannel(channel.handle) } else null
            ui.row(body, channel.name, "${channel.handle} · ${ruleId?.let { "rule $it" } ?: "added on this phone"}", end)
        }
        if (store.rules.channels.isEmpty()) ui.row(body, "None", "Add one from YouTube: Share → grod.", off = true)
    }

    /** targetSdk 35 draws edge to edge, so the content has to keep clear of the system bars itself. */
    private fun applySystemBarPadding(view: View, base: Int) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    base + insets.systemWindowInsetLeft,
                    base + insets.systemWindowInsetTop,
                    base + insets.systemWindowInsetRight,
                    base + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }
}
