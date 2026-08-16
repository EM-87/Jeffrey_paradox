package com.em87.weirdclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import java.util.Calendar

/**
 * Schedules the next upcoming enabled alarm with AlarmManager; when it fires,
 * [AlarmReceiver] re-arms the following one. As a clock app we declare
 * USE_EXACT_ALARM, so exact scheduling is granted without a runtime prompt;
 * if the platform still refuses, we degrade to a one-minute window.
 */
object AlarmScheduler {

    const val EXTRA_ALARM_ID = "extra_alarm_id"

    /**
     * Which calendar reminder this ringing is, or -1 for a plain alarm.
     *
     * Nothing reads it today. It used to say which reminder to delete, back
     * when a reminder was deleted the moment it rang — which killed a
     * yearly one on its first outing — and that went. It is still put on and
     * still carried because it is the only thing that says *which* reminder
     * is ringing, and an intent that cannot name what it is about is a
     * thing you find out you needed at the worst moment.
     */
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_SOUND = "extra_sound"
    const val EXTRA_SOUND_URI = "extra_sound_uri"

    /** Snooze length in minutes; 0 disables the snooze action. */
    const val EXTRA_SNOOZE = "extra_snooze"

    /** How many times this alarm has already been put off this morning. */
    const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"
    const val EXTRA_LABEL = "extra_label"
    const val EXTRA_VIBRATE = "extra_vibrate"
    const val EXTRA_FLASH = "extra_flash"

    /** Set when the ringing is a finished countdown rather than an alarm. */
    const val EXTRA_FROM_TIMER = "extra_from_timer"

    /** This alarm's own mission, and its own gradual sunrise in seconds. */
    const val EXTRA_MISSION = "extra_mission"
    const val EXTRA_MISSION_LEVEL = "extra_mission_level"
    const val EXTRA_GENTLE = "extra_gentle"
    const val EXTRA_GENTLE_FLASH = "extra_gentle_flash"

    /**
     * Everything one ringing carries with it.
     *
     * There is a chain — the scheduler arms an intent, the receiver hands
     * it to the service, the service hands it to the screen — and each hop
     * was copying the extras out by hand. Miss one and it silently becomes
     * its default at that hop: the snooze count was dropped by the
     * receiver, so the ring screen was told "none so far" every time and
     * the snooze limit, a setting people had turned on, limited nothing at
     * all. Nobody could have seen that from the outside; the button simply
     * never went away.
     *
     * So the list lives here, once, and the hop copies the list.
     */
    val CARRIED = arrayOf(
        EXTRA_ALARM_ID, EXTRA_REMINDER_ID, EXTRA_SOUND, EXTRA_SOUND_URI,
        EXTRA_SNOOZE, EXTRA_SNOOZE_COUNT, EXTRA_LABEL, EXTRA_VIBRATE,
        EXTRA_FLASH, EXTRA_FROM_TIMER, EXTRA_MISSION, EXTRA_MISSION_LEVEL,
        EXTRA_GENTLE, EXTRA_GENTLE_FLASH,
        Nag.EXTRA_ROUND
    )

    /**
     * Copies every carried extra from one intent to the next, whatever
     * its type, and leaves anything absent absent.
     */
    fun carryOver(from: Intent, to: Intent): Intent {
        val extras = from.extras ?: return to
        for (key in CARRIED) {
            if (extras.containsKey(key)) {
                @Suppress("DEPRECATION")
                to.putExtra(key, extras.get(key) as java.io.Serializable?)
            }
        }
        return to
    }

    fun update(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // No time travel: alarms only work while time runs at real speed.
        if (prefs.getInt(Prefs.TIME_SPEED, 100) != 100) {
            cancel(context)
            return
        }
        val enabled = AlarmStore.all(context).filter { it.enabled }
        // Calendar reminders compete for the same single armed slot.
        val reminders = ReminderStore.all(context)
            .filter { it.rings && it.ringAtMillis() > System.currentTimeMillis() + 1000 }
        if (enabled.isEmpty() && reminders.isEmpty()) {
            cancel(context)
            return
        }
        var next: Alarm? = null
        var nextReminder: Reminder? = null
        var nextAt = Long.MAX_VALUE
        for (alarm in enabled) {
            val at = nextOccurrence(alarm)
            if (at < nextAt) {
                nextAt = at
                next = alarm
            }
        }
        for (reminder in reminders) {
            val at = reminder.ringAtMillis()
            if (at < nextAt) {
                nextAt = at
                next = null
                nextReminder = reminder
            }
        }
        val reminderId = if (next == null) nextReminder?.id ?: -1 else -1
        val alarm = next ?: nextReminder?.let { r ->
            // A reminder rings like an alarm in its own chosen sound, and
            // deliberately without a snooze: the sheet offers a warning
            // beforehand instead, which is the useful end for something
            // dated. The comment here used to promise five minutes of
            // snooze that the line below has never given.
            Alarm(
                0, r.hour, r.minute, true, r.sound,
                label = r.label, snoozeMinutes = 0
            )
        } ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(nextAt, show),
                firePendingIntent(context, alarm, reminderId)
            )
        } catch (e: SecurityException) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                nextAt,
                60_000L,
                firePendingIntent(context, alarm, reminderId)
            )
        }
    }

    /**
     * The soonest this alarm next goes off, across every time of day it is
     * set for — a three-times-a-day concept is still one armed alarm.
     */
    fun nextOccurrence(alarm: Alarm, now: Long = System.currentTimeMillis()): Long =
        alarm.allTimes().minOf { (h, m) -> nextOccurrenceOf(alarm, h, m, now) }

    private fun nextOccurrenceOf(alarm: Alarm, hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now + 1000) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        // Walk forward to the next day this alarm actually rings on.
        var guard = 0
        while (!alarm.ringsOn(cal.get(Calendar.DAY_OF_WEEK)) && guard < 8) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return cal.timeInMillis
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(context, null, -1))
    }

    private fun firePendingIntent(context: Context, alarm: Alarm?, reminderId: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        alarm?.let {
            intent.putExtra(EXTRA_ALARM_ID, it.id)
            intent.putExtra(EXTRA_REMINDER_ID, reminderId)
            intent.putExtra(EXTRA_SOUND, it.sound)
            intent.putExtra(EXTRA_SOUND_URI, it.soundUri)
            intent.putExtra(EXTRA_SNOOZE, it.snoozeMinutes)
            intent.putExtra(EXTRA_LABEL, it.label)
            intent.putExtra(EXTRA_VIBRATE, it.vibrate)
            intent.putExtra(EXTRA_FLASH, it.flash)
            intent.putExtra(EXTRA_MISSION, it.mission)
            intent.putExtra(EXTRA_MISSION_LEVEL, it.missionLevel)
            intent.putExtra(EXTRA_GENTLE, it.gentleWakeSeconds)
            intent.putExtra(EXTRA_GENTLE_FLASH, it.gentleFlash)
        }
        return PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Puts the ringing described by [from] off for [minutes], unless it has
     * been put off enough.
     *
     * It takes the whole intent and not a handful of fields, because the
     * alarm that comes back has to be the *same alarm*. Built by hand from
     * four values, it was not: an alarm snoozed once came back with no
     * mission, no gradual sunrise, no torch, no label, and vibrating even
     * if it had been told not to. Which is the worst possible place for
     * that bug to live — somebody who presses snooze is exactly the person
     * the mission was put there for, and pressing it turned the mission
     * off.
     *
     * [alreadySnoozed] rides in the intent rather than living in a
     * preference, so it can never be a count left over from an alarm three
     * days ago — the thing being counted is one morning's worth of
     * pressing snooze, and one morning's worth of pressing snooze is
     * exactly what one chain of intents is.
     *
     * Returns false when the limit is spent, which is the ring screen's cue
     * to stop offering the button: an alarm that must be got up for is a
     * feature, and a Snooze button that silently does nothing is not.
     */
    fun snooze(
        context: Context,
        from: Intent,
        minutes: Int,
        alreadySnoozed: Int = 0
    ): Boolean {
        val limit = snoozeLimit(context)
        if (limit in 1..alreadySnoozed) return false
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        val at = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val fire = PendingIntent.getBroadcast(
            context,
            102,
            // Everything this ringing was carrying, and then the two things
            // the snooze itself decides: how long, and that this is one
            // more time of asking.
            carryOver(from, Intent(context, AlarmReceiver::class.java))
                .putExtra(EXTRA_SNOOZE, minutes)
                .putExtra(EXTRA_SNOOZE_COUNT, alreadySnoozed + 1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            context,
            103,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } catch (e: SecurityException) {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, fire)
        }
        return true
    }

    /** How many times one alarm may be put off, or 0 for as often as you like. */
    fun snoozeLimit(context: Context): Int =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Prefs.SNOOZE_LIMIT, null)
            ?.toIntOrNull()
            ?.coerceIn(0, 20)
            ?: 0
}
