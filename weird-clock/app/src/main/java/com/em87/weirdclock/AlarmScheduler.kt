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
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_SOUND = "extra_sound"
    const val EXTRA_SOUND_URI = "extra_sound_uri"

    /** Snooze length in minutes; 0 disables the snooze action. */
    const val EXTRA_SNOOZE = "extra_snooze"
    const val EXTRA_LABEL = "extra_label"

    fun update(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // No time travel: alarms only work while time runs at real speed.
        if (prefs.getInt(Prefs.TIME_SPEED, 100) != 100) {
            cancel(context)
            return
        }
        val enabled = AlarmStore.load(context).filter { it.enabled }
        // Calendar reminders compete for the same single armed slot.
        val reminders = ReminderStore.load(context)
            .filter { it.timeInMillis() > System.currentTimeMillis() + 1000 }
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
            val at = reminder.timeInMillis()
            if (at < nextAt) {
                nextAt = at
                next = null
                nextReminder = reminder
            }
        }
        val reminderId = if (next == null) nextReminder?.id ?: -1 else -1
        val alarm = next ?: nextReminder?.let { r ->
            // A reminder rings like a bells alarm with a 5-minute snooze,
            // carrying its label and its own id so it expires after firing.
            Alarm(0, r.hour, r.minute, true, Prefs.ALARM_SOUND_BELLS, label = r.label)
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

    private fun nextOccurrence(alarm: Alarm): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        fun isWeekend(): Boolean {
            val day = cal.get(Calendar.DAY_OF_WEEK)
            return day == Calendar.SATURDAY || day == Calendar.SUNDAY
        }
        when (alarm.repeat) {
            Prefs.ALARM_REPEAT_WEEKDAYS -> while (isWeekend()) cal.add(Calendar.DAY_OF_YEAR, 1)
            Prefs.ALARM_REPEAT_WEEKENDS -> while (!isWeekend()) cal.add(Calendar.DAY_OF_YEAR, 1)
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
        }
        return PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** One-shot re-fire in [minutes], independent of the regular chain. */
    fun snooze(context: Context, sound: String, minutes: Int, soundUri: String = "") {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val fire = PendingIntent.getBroadcast(
            context,
            102,
            Intent(context, AlarmReceiver::class.java)
                .putExtra(EXTRA_SOUND, sound)
                .putExtra(EXTRA_SOUND_URI, soundUri)
                .putExtra(EXTRA_SNOOZE, minutes),
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
    }
}
