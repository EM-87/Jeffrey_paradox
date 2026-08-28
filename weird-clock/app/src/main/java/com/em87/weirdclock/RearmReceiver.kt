package com.em87.weirdclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the alarms back whenever something has thrown them away.
 *
 * An alarm in this app is one entry in AlarmManager holding the *next* one
 * to ring, re-armed by [AlarmReceiver] each time it goes off. That entry is
 * not durable, and there are four ordinary events that either delete it or
 * make it wrong. Only the first was handled, and the other three are the
 * kind of failure nobody notices until a morning goes badly:
 *
 *  - **A reboot.** AlarmManager keeps nothing across one.
 *  - **The app being updated.** The system cancels every alarm belonging to
 *    a package it replaces. This clock gets a new build handed to it every
 *    few days, and each one silently disarmed every alarm on the phone
 *    until the app happened to be opened again. It was masked entirely by
 *    the fact that somebody testing a new build opens it.
 *  - **The clock being set.** The entry is an absolute instant worked out
 *    from what the time was when it was written. Move the clock and it is
 *    still that instant, which is no longer seven in the morning.
 *  - **Changing time zone.** The same, and the one people meet: land
 *    somewhere three hours away and the alarm is three hours out, in the
 *    direction that makes you miss things.
 *
 * The hourly bells are their own chain of alarms with the same four
 * problems, so they are re-armed here too.
 *
 * Cheap enough to do on all four: it reads the alarm list, works out which
 * one is soonest, and writes one entry.
 */
class RearmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                AlarmScheduler.update(context)
                BellScheduler.update(context)
            }
        }
    }
}
