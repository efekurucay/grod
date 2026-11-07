package dev.grod

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The scroll pause screen (docs/rules.md, "Scroll pause"), started by [GrodService] in its own task
 * on top of the app. "Leave" goes home at any time; "Continue" appears when the countdown ends and
 * finishing uncovers the app again. Leaving it any other way (Back, Recents) counts as "Leave".
 */
class ScrollPauseActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var countdown: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var focus: AudioFocusRequest

    /** elapsedRealtime when the wait is over; kept across a rotation so it does not start over. */
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
        GrodService.session.paused = true
        ui = Ui(this)
        endsAt = savedInstanceState?.getLong(K_ENDS_AT)
            ?: (SystemClock.elapsedRealtime() + intent.getIntExtra(EXTRA_SECONDS, 0) * 1000L)

        // Transient focus makes the app behind pause its video or audio while this screen is up.
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build()
        getSystemService(AudioManager::class.java).requestAudioFocus(focus)

        val page = ui.column().apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
        }
        page.addView(ui.text(intent.getStringExtra(EXTRA_TITLE).orEmpty(), 28f, bold = true).apply { gravity = Gravity.CENTER })
        intent.getStringExtra(EXTRA_WHY)?.let { why ->
            page.addView(ui.muted(why, 16f).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, 0)
            })
        }
        val scrolled = ScrollSession.label(intent.getLongExtra(EXTRA_SCROLLED_MS, 0))
        page.addView(ui.muted("Scrolled $scrolled in this session.", 16f).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(16), 0, ui.dp(32))
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
        tick.run()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(K_ENDS_AT, endsAt)
    }

    private fun secondsLeft(): Long = ((endsAt - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)

    private fun showCountdown() {
        val left = secondsLeft()
        countdown.text = if (left > 0) "$left" else ""
        buttons.removeAllViews()
        // Equal weight: both are quiet buttons, so neither choice is pushed.
        buttons.addView(ui.button("Leave") { leave() })
        if (left <= 0) {
            buttons.addView(ui.button("Continue") { decide("continued") },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })
        }
    }

    private fun leave() {
        if (decided) return
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        decide("left")
    }

    private fun decide(action: String) {
        if (decided) return
        decided = true
        log(action)
        finish()
    }

    private fun log(action: String) {
        val rule = intent.getStringExtra(EXTRA_RULE) ?: return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        LogWriter.get(this).append("events", LogLines.event(System.currentTimeMillis(), rule, pkg, action), RuleStore.get(this).rules.keepDays)
    }

    /** Back would otherwise uncover the app before the wait is over. */
    @Deprecated("Back is handled here for every API level the app supports.")
    override fun onBackPressed() = leave()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) {
            // noHistory finishes this screen when the user goes elsewhere (Recents, a notification).
            if (!decided) log("left")
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus)
            GrodService.session.paused = false
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RULE = "rule"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHY = "why"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_SCROLLED_MS = "scrolledMs"
        private const val K_ENDS_AT = "endsAt"
    }
}
