package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager alarms do not survive a reboot; reschedule ours. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.update(context)
            // The hourly bells are their own chain of alarms, and it was not
            // being restarted: after a reboot they went quiet for good, or
            // until someone happened to open the settings again.
            BellScheduler.update(context)
        }
    }
}
