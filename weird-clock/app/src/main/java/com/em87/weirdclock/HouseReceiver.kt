package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alarm before the alarm, whose only job is to tell the house.
 *
 * Nothing else in this app runs at half past six in the morning, so there
 * has to be something that does. It rings nothing, wakes nobody and shows
 * no notification: it fires one webhook and stops — see [Ifttt.Event.SOON]
 * and [AlarmScheduler], which arms and cancels it alongside the alarm it
 * belongs to.
 *
 * The work happens on the receiver's own thread rather than on a thread of
 * its own, held open by a `goAsync` so the process is not torn down
 * mid-request. A daemon thread started from a receiver that then returns
 * is a thread the system is entitled to kill immediately, which would make
 * this the one event in the set that silently fails half the time.
 */
class HouseReceiver : BroadcastReceiver() {

    companion object {

        /** Its own slot in AlarmManager, apart from the alarm's. */
        const val REQUEST = 202

        /** When the alarm this is the run-up to actually goes off. */
        const val EXTRA_AT = "extra_house_at"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!IftttStore.wanted(context)) return
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        val ringsAt = if (at > 0L) {
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = at }
            Ifttt.clockOf(
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE)
            )
        } else {
            null
        }
        val result = goAsync()
        Thread {
            try {
                IftttStore.fireNow(
                    context,
                    Ifttt.Event.SOON,
                    label,
                    ringsAt,
                    IftttStore.lead(context).toString()
                )
            } finally {
                result.finish()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
}
