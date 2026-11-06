package dev.grod

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pause before a distracting app (Grüning et al. 2023): its name, a countdown that only changes
 * text, and two choices. "Open" appears when the countdown ends; "Not now" goes back home.
 */
class PauseActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var app: AppEntry
    private lateinit var countdown: TextView
    private lateinit var buttons: LinearLayout
    private var left = 0
    private var decided = false

    private val tick = object : Runnable {
        override fun run() {
            left--
            showCountdown()
            if (left > 0) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        app = AppEntry(intent.getStringExtra(EXTRA_PKG)!!, intent.getStringExtra(EXTRA_COMPONENT)!!, intent.getStringExtra(EXTRA_LABEL)!!)
        left = RuleStore.get(this).rules.launcher.pauseSeconds

        val page = ui.column().apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
        }
        page.addView(ui.text(app.label, 28f, bold = true).apply { gravity = Gravity.CENTER })
        page.addView(ui.muted("Do you still want it?", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, ui.dp(32))
        })
        countdown = ui.muted("", 20f).apply { gravity = Gravity.CENTER }
        page.addView(countdown)
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(24), 0, 0)
        }
        page.addView(buttons)
        setContentView(page)
        showCountdown()
        if (left > 0) handler.postDelayed(tick, 1000)
    }

    private fun showCountdown() {
        countdown.text = if (left > 0) "$left" else ""
        buttons.removeAllViews()
        // Equal weight: both are quiet buttons, so neither choice is pushed.
        buttons.addView(ui.button("Not now") { decide("backed-out") })
        if (left <= 0) {
            buttons.addView(ui.button("Open ${app.label}") { decide("opened") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })
        }
    }

    private fun decide(action: String) {
        if (decided) return
        decided = true
        logLauncher(app.pkg, action)
        if (action == "opened") {
            launch(app)
        } else {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    @Deprecated("Back is handled here for every API level the app supports.")
    override fun onBackPressed() = decide("backed-out")

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_LABEL = "label"
    }
}
