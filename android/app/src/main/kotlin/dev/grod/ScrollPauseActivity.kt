package dev.grod

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The scroll pause screen (docs/rules.md, "Scroll pause"), started by [GrodService] in its own task
 * on top of the app. "Leave" goes home at any time. A pack, a planned break, or "Continue" after
 * the countdown uncovers the app again. Leaving it any other way (Back, Recents) counts as "Leave".
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
        GrodService.session.screenShown()
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
        // A quick opening whose window is over says what the app was opened for instead of the scroll time.
        val scrolled = ScrollSession.label(intent.getLongExtra(EXTRA_SCROLLED_MS, 0))
        val line = intent.getStringExtra(EXTRA_REASON)?.let { "$it." }
            ?: "Scrolled $scrolled in this session."
        page.addView(ui.muted(line, 16f).apply {
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
        page.addView(ui.button("Plan a break") { planBreak() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ui.dp(12)
            },
        )
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
        // Equal weight: all are quiet buttons, so no choice is pushed.
        buttons.addView(ui.button("Leave") { leave() })
        // A pack is the way in for something that cannot wait. There are only a few until the next refill,
        // so it needs no wait of its own; each one is logged for the weekly review.
        val packs = RuleStore.get(this).rules.packs
        val packsLeft = PackStore(this).use.left(packs)
        if (packsLeft > 0) addButton(ui.button("${packs.minutes} min · $packsLeft left") { pack(packs) })
        if (left == 0L) addButton(ui.button("Continue") { decide("continued") })
    }

    private fun addButton(button: View) = buttons.addView(button,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })

    private fun leave() {
        if (decided) return
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        decide("left")
    }

    private fun pack(settings: PackSettings) {
        if (decided) return
        val store = PackStore(this)
        store.use = store.use.take(settings)
        GrodService.session.startBreak(SystemClock.elapsedRealtime(), settings.minutes)
        decide("pack")
    }

    private fun planBreak() {
        val duration = ui.input("Minutes").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val intention = ui.input("What will you do?").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val fields = ui.column().apply {
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0)
            addView(duration)
            addView(intention, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(8)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Plan a break")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minutes = duration.text.toString().toIntOrNull()
            val plan = intention.text.toString().trim()
            when {
                minutes == null || minutes <= 0 -> duration.error = "Enter a positive number of minutes"
                plan.isEmpty() -> intention.error = "Enter what you will do"
                !decided -> {
                    GrodService.session.startBreak(SystemClock.elapsedRealtime(), minutes)
                    dialog.dismiss()
                    decide("planned-break", minutes * 60_000L, plan)
                }
            }
        }
    }

    private fun decide(action: String, ms: Long? = null, intention: String? = null) {
        if (decided) return
        decided = true
        log(action, ms, intention)
        finish()
    }

    private fun log(action: String, ms: Long? = null, intention: String? = null) {
        val rule = intent.getStringExtra(EXTRA_RULE) ?: return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val event = LogLines.event(System.currentTimeMillis(), rule, pkg, action, ms = ms, intention = intention)
        LogWriter.get(this).append("events", event, RuleStore.get(this).rules.keepDays)
    }

    /** Back would otherwise uncover the app before the wait is over. */
    @Deprecated("Back is handled here for every API level the app supports.")
    override fun onBackPressed() = leave()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) {
            // noHistory finishes this screen when the user goes elsewhere (Recents, a notification).
            // The pause keeps running in the session, so coming back before it is over shows it again.
            if (!decided) log("left")
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus)
            GrodService.session.screenGone()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RULE = "rule"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHY = "why"
        const val EXTRA_REASON = "reason"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_SCROLLED_MS = "scrolledMs"
        private const val K_ENDS_AT = "endsAt"
    }
}
