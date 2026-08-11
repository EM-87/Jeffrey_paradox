package com.em87.weirdclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * An unpassed mission does not go away.
 *
 * Without this the mission is theatre: the alarm rings, you ignore it, it
 * gives up after its three minutes and leaves a note, and going back to
 * sleep costs nothing at all — which is the exact thing the mission was
 * added to stop. So when the ringing times out with a mission still
 * unsolved, the alarm books itself another go.
 *
 * The whole design here is the way out, not the way in. An alarm that
 * keeps coming back is a frightening thing to put on somebody's phone, so
 * there are three ends to it and every one of them is deliberate:
 *
 *  - pass the mission, which is the point;
 *  - call it off in the app, which takes finding the app, reading a
 *    question and answering it — awake work, and the escape the whole
 *    thing was designed around;
 *  - or [GIVES_UP_AFTER] rounds, which is an hour of being pestered. Not
 *    a get-out — an hour of an alarm every five minutes is not something
 *    anybody sleeps through — but a stop on a runaway, so that a bug in
 *    the two ends above can never mean a phone that rings for ever.
 */
object Nag {

    /** How long it waits before trying again. */
    const val MINUTES = 5

    /**
     * And how many times, at most. An hour of it. See the note above on
     * why there is a limit at all.
     */
    const val GIVES_UP_AFTER = 12

    /**
     * Which round this ringing is, carried on the intent.
     *
     * In the intent and not in a preference, for the same reason the
     * snooze count is: a number kept in a preference is a number that
     * outlives the morning it belongs to. Stored, the tally reached its
     * limit one bad morning and stayed there — and an alarm that had
     * already nagged its twelve would never nag again, on any morning
     * after, because nothing ever put it back.
     */
    const val EXTRA_ROUND = "extra_nag_round"

    private const val FIRE_REQUEST = 104
    private const val SHOW_REQUEST = 105

    /**
     * Whether the alarm should book another go.
     *
     * [guarded] is the service's own answer to "is there a mission on this
     * ringing", which is also what takes the Stop button off the
     * notification — one fact, asked once, so the two can never disagree
     * about whether a mission is in force.
     */
    fun wantsAnother(guarded: Boolean, roundsSoFar: Int): Boolean =
        guarded && roundsSoFar < GIVES_UP_AFTER

    /** When the next go is booked for, or 0 if none is. */
    fun bookedAt(prefs: SharedPreferences): Long = prefs.getLong(Prefs.NAG_AT, 0L)

    /** How many have already been sat through. */
    fun rounds(prefs: SharedPreferences): Int = prefs.getInt(Prefs.NAG_ROUNDS, 0)

    /**
     * Whether there is one waiting, [now] being the time to judge it by.
     *
     * A booking in the past is not pending: the alarm has already gone
     * off, or the phone was off when it should have, and either way the
     * app must not go on offering to call off something that is over.
     */
    fun pending(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Boolean =
        bookedAt(prefs) > now

    /**
     * Books another go in [MINUTES], carrying the alarm's own sound and
     * label so the next one is the same alarm and not a generic bleep.
     */
    fun arm(
        context: Context,
        sound: String,
        soundUri: String,
        label: String,
        snoozeMinutes: Int,
        vibrate: Boolean,
        flash: Boolean,
        roundsSoFar: Int
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + MINUTES * 60_000L
        val fire = firePendingIntent(
            context, sound, soundUri, label, snoozeMinutes, vibrate, flash, roundsSoFar + 1
        )
        val show = PendingIntent.getActivity(
            context,
            SHOW_REQUEST,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } catch (e: SecurityException) {
            manager.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, fire)
        }
        // Written down before the alarm can possibly fire, so the app can
        // always say what is coming and always has something to cancel.
        prefs(context).edit()
            .putLong(Prefs.NAG_AT, at)
            .putInt(Prefs.NAG_ROUNDS, roundsSoFar + 1)
            .apply()
    }

    /**
     * Calls the whole thing off: the booking, the count, and the alarm
     * sitting in the system's queue.
     *
     * Called from the app when somebody says so, and also whenever the
     * ringing is ended properly — passing the mission or pressing snooze
     * both mean the alarm has been dealt with, and a nag left armed behind
     * a snooze would go off in the middle of it.
     */
    fun callOff(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(firePendingIntent(context, "", "", "", 0, true, false, 0))
        prefs(context).edit()
            .remove(Prefs.NAG_AT)
            .remove(Prefs.NAG_ROUNDS)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * The one intent the booking lives on.
     *
     * Cancelling matches on request code and component, never on extras,
     * so [callOff] can build this with nothing in it and still cancel the
     * real one.
     */
    private fun firePendingIntent(
        context: Context,
        sound: String,
        soundUri: String,
        label: String,
        snoozeMinutes: Int,
        vibrate: Boolean,
        flash: Boolean,
        round: Int
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        FIRE_REQUEST,
        Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_SOUND, sound)
            .putExtra(AlarmScheduler.EXTRA_SOUND_URI, soundUri)
            .putExtra(AlarmScheduler.EXTRA_LABEL, label)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, snoozeMinutes)
            // The next go is the same alarm, so it shakes and flashes the
            // way that alarm does. These were left off, and an alarm set to
            // light the room went dark from the second round on.
            .putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
            .putExtra(AlarmScheduler.EXTRA_FLASH, flash)
            .putExtra(EXTRA_ROUND, round),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
