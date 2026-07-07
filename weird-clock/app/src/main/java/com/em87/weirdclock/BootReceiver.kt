package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager alarms do not survive a reboot; reschedule ours. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.update(context)
        }
    }
}
