package dev.grod

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT as WRAP
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Status screen: service state, active rules revision and source, rules, and the rules URL. */
class MainActivity : Activity() {
    private lateinit var store: RuleStore
    private lateinit var serviceStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var ruleList: LinearLayout
    private lateinit var channelList: LinearLayout
    private lateinit var urlInput: EditText
    private val onStoreChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text("grod", 24f))
        column.addView(text("Removes short-video feeds and keeps the rest of each app.", 14f))

        column.addView(heading("Service"))
        serviceStatus = text("", 16f).also(column::addView)
        column.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        column.addView(heading("Rules"))
        syncStatus = text("", 14f).also(column::addView)
        ruleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)

        column.addView(heading("Blocked YouTube channels"))
        column.addView(text("Add one from YouTube: Share → grod.", 14f))
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)

        column.addView(heading("Rules URL (optional, HTTPS)"))
        urlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/grod.rules.json"
            setText(store.rulesUrl.orEmpty())
        }.also(column::addView)
        column.addView(button("Save & Refresh") {
            store.rulesUrl = urlInput.text.toString()
            store.checkLocal()
            store.fetch()
            render()
        })

        val scroll = ScrollView(this).apply { addView(column) }
        applySystemBarPadding(scroll, dp(16))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        store.addListener(onStoreChanged)
        store.checkLocal()
        render()
    }

    override fun onPause() {
        store.removeListener(onStoreChanged)
        super.onPause()
    }

    private fun render() {
        serviceStatus.text = if (serviceEnabled()) "Accessibility service: ON" else "Accessibility service: OFF"
        val fetched = store.lastFetchAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
        syncStatus.text = buildString {
            append("Revision: ${store.revision} (source: ${store.source})\n")
            append("Last sync error: ${store.lastError ?: "none"}")
            if (store.rulesUrl != null) append("\nLast URL fetch: ${fetched ?: "never"}")
        }
        ruleList.removeAllViews()
        for (rule in store.rules.rules) {
            val state = if (rule.enabled) "enabled" else "disabled"
            val overlays = rule.android?.let { " · covers ${it.cover.size} · masks ${it.mask.size}" }.orEmpty()
            val line = "${rule.title}\n$state · ${rule.platforms.size} platforms$overlays · fired ${store.fireCount(rule.id)}×"
            ruleList.addView(text(line, 15f).apply { setPadding(0, dp(8), 0, 0) })
        }
        channelList.removeAllViews()
        for ((channel, ruleId) in store.rules.channels) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val from = ruleId?.let { "rule $it" } ?: "added on this phone"
            row.addView(text("${channel.name}\n${channel.handle} · $from", 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
            // Rule channels are removed with `grod channel remove`; the phone only owns what it added.
            if (ruleId == null) row.addView(button("Remove") { store.removeLocalChannel(channel.handle) })
            channelList.addView(row)
        }
        if (store.rules.channels.isEmpty()) channelList.addView(text("None", 15f))
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, GrodService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
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

    private fun heading(label: String) = text(label, 18f).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun text(value: String, sizeSp: Float) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
