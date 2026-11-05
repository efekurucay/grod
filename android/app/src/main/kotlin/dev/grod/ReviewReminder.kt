package dev.grod

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZoneId

/**
 * The weekly review reminder: an inexact alarm at the `review` time posts one grod notification.
 * The schedule is saved so boot and app updates can re-arm it before the rules have loaded.
 */
object ReviewSchedule {
    const val ACTION_REMIND = "dev.grod.REVIEW_REMIND"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val K_WEEKDAY = "weekday"
    private const val K_MINUTE = "minute"

    /** Arms the next reminder for [review], or cancels it when the rules have none. */
    fun apply(context: Context, review: ReviewSettings?) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("grod.review", Context.MODE_PRIVATE)
        val alarms = app.getSystemService(AlarmManager::class.java)
        val remind = PendingIntent.getBroadcast(
            app, 0, Intent(app, ReviewReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (review == null) {
            alarms.cancel(remind)
            prefs.edit().clear().apply()
            return
        }
        prefs.edit().putInt(K_WEEKDAY, review.weekday).putInt(K_MINUTE, review.minute).apply()
        // A window rather than an exact alarm: a few minutes late is fine and needs no special permission.
        val at = Review.next(System.currentTimeMillis(), review, ZoneId.systemDefault())
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, remind)
    }

    fun saved(context: Context): ReviewSettings? {
        val prefs = context.applicationContext.getSharedPreferences("grod.review", Context.MODE_PRIVATE)
        if (!prefs.contains(K_WEEKDAY)) return null
        return ReviewSettings(prefs.getInt(K_WEEKDAY, 7), prefs.getInt(K_MINUTE, 0))
    }
}

/** Posts the reminder when the alarm fires and re-arms the alarm after it, after boot and after updates. */
class ReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReviewSchedule.ACTION_REMIND -> post(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        ReviewSchedule.apply(context, ReviewSchedule.saved(context))
    }

    private fun post(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weekly review", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, MainActivity.SECTION_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.grod_mark)
            .setContentTitle("Weekly review: connect me to your computer")
            .setContentText(MainActivity.REVIEW_HOW)
            .setStyle(Notification.BigTextStyle().bigText(MainActivity.REVIEW_HOW))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL = "review"
        const val NOTIFICATION_ID = 1
    }
}
