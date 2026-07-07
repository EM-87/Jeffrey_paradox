package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
        // Re-arm for tomorrow (the stored trigger time has just passed).
        AlarmScheduler.update(context)
    }
}
