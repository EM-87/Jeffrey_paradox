package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        ContextCompat.startForegroundService(context, service)
        // A calendar reminder is one-shot: it expires as it fires.
        val reminderId = intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1)
        if (reminderId > 0) ReminderStore.remove(context, reminderId)
        // Re-arm the next upcoming alarm (this one's next slot is tomorrow).
        AlarmScheduler.update(context)
    }
}
