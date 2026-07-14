package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AlarmService::class.java)
            .putExtra(AlarmScheduler.EXTRA_SOUND, intent.getStringExtra(AlarmScheduler.EXTRA_SOUND))
        ContextCompat.startForegroundService(context, service)
        // Re-arm the next upcoming alarm (this one's next slot is tomorrow).
        AlarmScheduler.update(context)
    }
}
