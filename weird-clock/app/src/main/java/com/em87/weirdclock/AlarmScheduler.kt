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
    const val EXTRA_SOUND = "extra_sound"

    fun update(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // No time travel: alarms only work while time runs at real speed.
        if (prefs.getInt(Prefs.TIME_SPEED, 100) != 100) {
            cancel(context)
            return
        }
        val enabled = AlarmStore.load(context).filter { it.enabled }
        if (enabled.isEmpty()) {
            cancel(context)
            return
        }
        var next: Alarm? = null
        var nextAt = Long.MAX_VALUE
        for (alarm in enabled) {
            val at = nextOccurrence(alarm.hour, alarm.minute)
            if (at < nextAt) {
                nextAt = at
                next = alarm
            }
        }
        val alarm = next ?: return
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
                firePendingIntent(context, alarm)
            )
        } catch (e: SecurityException) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                nextAt,
                60_000L,
                firePendingIntent(context, alarm)
            )
        }
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(context, null))
    }

    private fun firePendingIntent(context: Context, alarm: Alarm?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        alarm?.let {
            intent.putExtra(EXTRA_ALARM_ID, it.id)
            intent.putExtra(EXTRA_SOUND, it.sound)
        }
        return PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
