package com.em87.weirdclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AlarmService::class.java)
            .putExtra(AlarmScheduler.EXTRA_SOUND, intent.getStringExtra(AlarmScheduler.EXTRA_SOUND))
            .putExtra(AlarmScheduler.EXTRA_SOUND_URI, intent.getStringExtra(AlarmScheduler.EXTRA_SOUND_URI))
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE, 0))
            .putExtra(AlarmScheduler.EXTRA_LABEL, intent.getStringExtra(AlarmScheduler.EXTRA_LABEL))
            .putExtra(
                AlarmScheduler.EXTRA_VIBRATE,
                intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true)
            )
            .putExtra(
                AlarmScheduler.EXTRA_FLASH,
                intent.getBooleanExtra(AlarmScheduler.EXTRA_FLASH, false)
            )
        // Android only allows a background app to start a foreground service
        // in specific windows. An exact alarm is one of them, but the inexact
        // fallback the scheduler uses when exact alarms are refused is not —
        // and an alarm clock that crashes instead of ringing is the worst
        // failure it could have. If the service is refused, the notification
        // rings the doorbell on its own.
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: Exception) {
            notifyWithoutService(context, intent)
        }
        retireIfOnce(context, intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1))
        // A reminder used to be deleted the moment it rang. That killed a
        // yearly one on its first outing, and a reminder set to warn a week
        // early vanished from the calendar a week before the thing it was
        // for. Nothing is deleted here: a spent reminder simply stops being
        // scheduled, because its ring time is now in the past, and the
        // three-month sweep collects it in the end.
        AlarmScheduler.update(context)
    }

    /**
     * An alarm with no days set rings once and is done. Switched off here,
     * before the scheduler re-arms, so the next thing it arms is something
     * else. Left enabled it would come back tomorrow, and the day after —
     * which is what "set an alarm for seven o'clock" used to do, for ever.
     */
    private fun retireIfOnce(context: Context, alarmId: Int) {
        if (alarmId <= 0) return
        val alarms = AlarmStore.all(context)
        val alarm = alarms.firstOrNull { it.id == alarmId } ?: return
        if (!alarm.once || !alarm.enabled) return
        alarm.enabled = false
        AlarmStore.save(context)
    }

    /** Last resort: a full-screen notification, without the looping bells. */
    private fun notifyWithoutService(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AlarmService.CHANNEL_ID,
                    context.getString(R.string.alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val show = PendingIntent.getActivity(
            context,
            6,
            Intent(context, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(AlarmScheduler.EXTRA_LABEL, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            AlarmService.NOTIFICATION_ID,
            NotificationCompat.Builder(context, AlarmService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(label.ifBlank { context.getString(R.string.alarm_ringing) })
                // Counting up, like the service's own — this is the same
                // alarm by another road, and it should read the same.
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setUsesChronometer(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(show, true)
                .setContentIntent(show)
                .build()
        )
    }
}
