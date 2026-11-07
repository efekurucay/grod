package dev.grod

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The opening question (docs/rules.md, "Opening check (Android)"), started by [GrodService] in its
 * own task over a distracting app that just came to the front. A quick answer finishes it, which
 * uncovers the app; Browse, or a quick purpose that lost its quick open, first shows a wait. "Leave"
 * goes home at any time; leaving any other way (Back, Home, Recents) counts as Leave too.
 */
class OpeningCheckActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var store: OpeningStore
    private lateinit var pkg: String
    private lateinit var label: String
    private lateinit var page: LinearLayout
    private lateinit var countdown: TextView
    private lateinit var buttons: LinearLayout

    /** The answer whose wait is running; kept across a rotation with [endsAt] (elapsedRealtime). */
    private var waiting: Opening.Answer? = null
    private var endsAt = 0L
    private var decided = false

    private val tick = object : Runnable {
        override fun run() {
            showCountdown()
            if (secondsLeft() > 0) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        ui = Ui(this)
        store = OpeningStore.get(this)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        label = try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        page = ui.column().apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
        }
        setContentView(page)
        val purpose = Purpose.of(savedInstanceState?.getString(K_PURPOSE))
        if (purpose != null) {
            val window = savedInstanceState!!.getLong(K_WINDOW, -1L).takeIf { it >= 0 }
            waiting = Opening.Answer(purpose, 0, window)
            endsAt = savedInstanceState.getLong(K_ENDS_AT)
            showWait()
        } else {
            showQuestion()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        waiting?.let {
            outState.putString(K_PURPOSE, it.purpose.id)
            outState.putLong(K_WINDOW, it.windowMs ?: -1L)
            outState.putLong(K_ENDS_AT, endsAt)
        }
    }

    private fun rules() = RuleStore.get(this).rules

    private fun header(subtitle: String) {
        page.removeAllViews()
        page.addView(ui.text(label, 28f, bold = true).apply { gravity = Gravity.CENTER })
        page.addView(ui.muted(subtitle, 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, ui.dp(32))
        })
    }

    /** Quick purposes that lost their quick open, as "Message: 6 of your last 10 ran over". */
    private fun overrunLines(only: Purpose? = null): List<String> {
        val now = System.currentTimeMillis()
        return Purpose.entries.filter { it.quick && (only == null || it == only) }.mapNotNull { p ->
            Opening.overrun(store.memory.past(p), now).takeIf { it.lost }?.let { "${p.short}: ${it.label}" }
        }
    }

    private fun showQuestion() {
        header("What is it for?")
        // The answer last given for this app is the primary button; the others are equal and quiet.
        val last = store.memory.last[pkg]
        for (purpose in Purpose.entries) {
            page.addView(
                ui.button(purpose.label, if (purpose == last) Ui.Kind.PRIMARY else Ui.Kind.QUIET) { answer(purpose) }
                    .apply { minHeight = ui.dp(48) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(12) },
            )
        }
        for (line in overrunLines()) {
            page.addView(ui.muted(line, 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(16), 0, 0)
            })
        }
        page.addView(ui.button("Leave", Ui.Kind.GHOST) { leave() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(24) })
    }

    private fun answer(purpose: Purpose) {
        if (decided || waiting != null) return
        val now = System.currentTimeMillis()
        val answer = store.answer(pkg, purpose, rules(), now)
        val wait = answer.waitSeconds
        if (wait == null) {
            store.start(pkg, answer, now)
            decided = true
            finish()
            return
        }
        waiting = answer
        endsAt = SystemClock.elapsedRealtime() + wait * 1000L
        showWait()
    }

    private fun showWait() {
        val purpose = waiting!!.purpose
        header(overrunLines(purpose).firstOrNull() ?: purpose.label)
        page.addView(ui.text("What's next after this?", 20f).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ui.dp(24))
        })
        countdown = ui.muted("", 20f).apply { gravity = Gravity.CENTER }
        page.addView(countdown)
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(24), 0, 0)
        }
        page.addView(buttons)
        handler.removeCallbacks(tick)
        tick.run()
    }

    private fun secondsLeft(): Long = ((endsAt - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)

    private fun showCountdown() {
        val left = secondsLeft()
        countdown.text = if (left > 0) "$left" else ""
        buttons.removeAllViews()
        // Equal weight, as on the scroll pause: both are quiet buttons.
        buttons.addView(ui.button("Leave") { leave() })
        if (left <= 0) {
            buttons.addView(ui.button("Continue") { open() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(12) })
        }
    }

    /** The wait is over: the session starts now, so the wait never counts toward a quick window. */
    private fun open() {
        val answer = waiting ?: return
        if (decided) return
        decided = true
        store.start(pkg, answer, System.currentTimeMillis())
        finish()
    }

    private fun leave() {
        if (decided) return
        decided = true
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        store.left(pkg, rules())
        finish()
    }

    @Deprecated("Back is handled here for every API level the app supports.")
    override fun onBackPressed() = leave()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) {
            // noHistory finishes this screen when the user goes elsewhere (Home, Recents, screen off).
            if (!decided && ::pkg.isInitialized) store.left(pkg, rules())
            showing = false
            launchedAt = 0L
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val K_PURPOSE = "purpose"
        private const val K_WINDOW = "windowMs"
        private const val K_ENDS_AT = "endsAt"

        /** True while the question or its wait is up; events from the app behind it are not openings. */
        var showing = false

        /** When the service last started this screen (wall clock); 0 once it is gone. */
        var launchedAt = 0L
    }
}
