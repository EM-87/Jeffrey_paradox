package com.em87.weirdclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.widget.Toast

/**
 * The door the rest of Android knocks on.
 *
 * "Set an alarm for seven", "set a timer for ten minutes", "show my alarms":
 * the assistant, the launcher's search and any other app all say these things
 * through the same handful of standard intents. An app that answers them can
 * be chosen as the phone's clock; one that does not is a place you go to on
 * purpose, and nothing else in the system will ever hand it any work.
 *
 * No window of its own: it does what it was asked, opens the right card if it
 * was not told to keep quiet, and gets out of the way.
 */
class ClockIntentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handled = when (intent?.action) {
            AlarmClock.ACTION_SET_ALARM -> setAlarm()
            AlarmClock.ACTION_SET_TIMER -> setTimer()
            AlarmClock.ACTION_SHOW_ALARMS -> open(MainActivity.EXTRA_OPEN_ALARMS)
            ACTION_SHOW_TIMERS, ACTION_SHOW_STOPWATCH -> open(MainActivity.EXTRA_OPEN_TIMER)
            ACTION_SHOW_CALENDAR -> open(MainActivity.EXTRA_OPEN_CALENDAR)
            else -> open(null)
        }
        if (!handled) open(null)
        finish()
    }

    /**
     * Builds the alarm the caller described. Everything is optional: with no
     * time at all the request is really "take me to the alarms", which is
     * what the platform expects.
     */
    private fun setAlarm(): Boolean {
        val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        if (hour !in 0..23) return open(MainActivity.EXTRA_OPEN_ALARMS)
        val minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
        val label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
        val vibrate = intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true)
        // EXTRA_DAYS speaks Calendar's weekdays; our mask is a bit per day.
        val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)
        var mask = 0
        days?.forEach { day -> if (day in 1..7) mask = mask or (1 shl (day - 1)) }

        val alarms = AlarmStore.load(this)
        alarms.add(
            Alarm(
                id = AlarmStore.nextId(alarms),
                hour = hour,
                minute = minute,
                enabled = true,
                sound = Prefs.ALARM_SOUND_BELLS,
                daysMask = mask,
                label = label,
                vibrate = vibrate
            )
        )
        AlarmStore.save(this, alarms)
        AlarmScheduler.update(this)
        if (intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) {
            Toast.makeText(
                this,
                getString(R.string.alarm_set_toast, String.format("%02d:%02d", hour, minute)),
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
        return open(MainActivity.EXTRA_OPEN_ALARMS)
    }

    /** Starts a countdown of the requested length, running or not. */
    private fun setTimer(): Boolean {
        val seconds = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        if (seconds <= 0) return open(MainActivity.EXTRA_OPEN_TIMER)
        val total = seconds * 1000L
        CountdownService.start(this, SystemClock.elapsedRealtime() + total, total)
        if (intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) {
            Toast.makeText(
                this,
                getString(R.string.timer_set_toast, seconds / 60, seconds % 60),
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
        return open(MainActivity.EXTRA_OPEN_TIMER)
    }

    private fun open(extra: String?): Boolean {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { extra?.let { putExtra(it, true) } }
        )
        return true
    }

    companion object {
        /** Shortcuts and other callers, for the cards the platform has no word for. */
        const val ACTION_SHOW_TIMERS = "android.intent.action.SHOW_TIMERS"
        const val ACTION_SHOW_STOPWATCH = "com.em87.weirdclock.action.SHOW_STOPWATCH"
        const val ACTION_SHOW_CALENDAR = "com.em87.weirdclock.action.SHOW_CALENDAR"
    }
}
