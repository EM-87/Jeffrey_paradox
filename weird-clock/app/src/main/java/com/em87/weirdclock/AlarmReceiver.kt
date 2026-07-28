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
        // A reminder used to be deleted the moment it rang. That killed a
        // yearly one on its first outing, and a reminder set to warn a week
        // early vanished from the calendar a week before the thing it was
        // for. Nothing is deleted here: a spent reminder simply stops being
        // scheduled, because its ring time is now in the past, and the
        // three-month sweep collects it in the end.
        AlarmScheduler.update(context)
    }
}
