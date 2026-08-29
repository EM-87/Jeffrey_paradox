package com.em87.weirdclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import java.util.Calendar

/**
 * Schedules the next upcoming enabled alarm with AlarmManager; when it fires,
 * [AlarmReceiver] re-arms the following one. As a clock app we declare
 * USE_EXACT_ALARM, so exact scheduling is granted without a runtime prompt;
 * if the platform still refuses, we degrade to a one-minute window.
 */
object AlarmScheduler {

    const val EXTRA_ALARM_ID = "extra_alarm_id"

    /**
     * Which calendar reminder this ringing is, or -1 for a plain alarm.
     *
     * Nothing reads it today. It used to say which reminder to delete, back
     * when a reminder was deleted the moment it rang — which killed a
     * yearly one on its first outing — and that went. It is still put on and
     * still carried because it is the only thing that says *which* reminder
     * is ringing, and an intent that cannot name what it is about is a
     * thing you find out you needed at the worst moment.
     */
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_SOUND = "extra_sound"
    const val EXTRA_SOUND_URI = "extra_sound_uri"

    /** Snooze length in minutes; 0 disables the snooze action. */
    const val EXTRA_SNOOZE = "extra_snooze"

    /** How many times this alarm has already been put off this morning. */
    const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"

    /**
     * And how many times it may be, or 0 for as often as you like.
     *
     * On the intent rather than read from the settings wherever it is
     * wanted, because it belongs to *this* alarm now. Read from a
     * preference at each of the two places that ask — the ring screen, to
     * decide whether to offer the button, and the scheduler, to decide
     * whether to honour it — the two would answer for whichever alarm was
     * edited last rather than for the one that is ringing.
     */
    const val EXTRA_SNOOZE_LIMIT = "extra_snooze_limit"
    const val EXTRA_LABEL = "extra_label"
    const val EXTRA_VIBRATE = "extra_vibrate"
    const val EXTRA_FLASH = "extra_flash"

    /** Set when the ringing is a finished countdown rather than an alarm. */
    const val EXTRA_FROM_TIMER = "extra_from_timer"

    /** This alarm's own mission, and its own gradual sunrise in seconds. */
    const val EXTRA_MISSION = "extra_mission"
    const val EXTRA_MISSION_LEVEL = "extra_mission_level"
    const val EXTRA_GENTLE = "extra_gentle"
    const val EXTRA_GENTLE_FLASH = "extra_gentle_flash"

    /**
     * Everything one ringing carries with it.
     *
     * There is a chain — the scheduler arms an intent, the receiver hands
     * it to the service, the service hands it to the screen — and each hop
     * was copying the extras out by hand. Miss one and it silently becomes
     * its default at that hop: the snooze count was dropped by the
     * receiver, so the ring screen was told "none so far" every time and
     * the snooze limit, a setting people had turned on, limited nothing at
     * all. Nobody could have seen that from the outside; the button simply
     * never went away.
     *
     * So the list lives here, once, and the hop copies the list.
     */
    val CARRIED = arrayOf(
        EXTRA_ALARM_ID, EXTRA_REMINDER_ID, EXTRA_SOUND, EXTRA_SOUND_URI,
        EXTRA_SNOOZE, EXTRA_SNOOZE_COUNT, EXTRA_SNOOZE_LIMIT, EXTRA_LABEL, EXTRA_VIBRATE,
        EXTRA_FLASH, EXTRA_FROM_TIMER, EXTRA_MISSION, EXTRA_MISSION_LEVEL,
        EXTRA_GENTLE, EXTRA_GENTLE_FLASH,
        Nag.EXTRA_ROUND
    )

    /**
     * Copies every carried extra from one intent to the next, whatever
     * its type, and leaves anything absent absent.
     */
    fun carryOver(from: Intent, to: Intent): Intent {
        val extras = from.extras ?: return to
        for (key in CARRIED) {
            if (extras.containsKey(key)) {
                @Suppress("DEPRECATION")
                to.putExtra(key, extras.get(key) as java.io.Serializable?)
            }
        }
        return to
    }

    fun update(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // No time travel: alarms only work while time runs at real speed.
        if (prefs.getInt(Prefs.TIME_SPEED, 100) != 100) {
            cancel(context)
            return
        }
        val enabled = AlarmStore.all(context).filter { it.enabled }
        // Calendar reminders compete for the same single armed slot.
        val reminders = ReminderStore.all(context)
            .filter { it.rings && it.ringAtMillis() > System.currentTimeMillis() + 1000 }
        if (enabled.isEmpty() && reminders.isEmpty()) {
            cancel(context)
            return
        }
        var next: Alarm? = null
        var nextReminder: Reminder? = null
        var nextAt = Long.MAX_VALUE
        for (alarm in enabled) {
            val at = nextOccurrence(alarm)
            if (at < nextAt) {
                nextAt = at
                next = alarm
            }
        }
        for (reminder in reminders) {
            val at = reminder.ringAtMillis()
            if (at < nextAt) {
                nextAt = at
                next = null
                nextReminder = reminder
            }
        }
        val reminderId = if (next == null) nextReminder?.id ?: -1 else -1
        val alarm = next ?: nextReminder?.let { r ->
            // A reminder rings like an alarm in its own chosen sound, and
            // deliberately without a snooze: the sheet offers a warning
            // beforehand instead, which is the useful end for something
            // dated. The comment here used to promise five minutes of
            // snooze that the line below has never given.
            Alarm(
                0, r.hour, r.minute, true, r.sound,
                label = r.label, snoozeMinutes = 0
            )
        } ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(nextAt, show),
                firePendingIntent(context, alarm, reminderId)
            )
            prefs.edit().putBoolean(Prefs.EXACT_DENIED, false).apply()
        } catch (e: SecurityException) {
            // The phone will not let this app set an exact alarm. It still
            // rings — inside a minute of the right time — but two things are
            // gone, and both used to go without a word: the alarm is no
            // longer registered as an alarm clock, which is what draws the
            // little clock in the status bar, and it can be pushed about by
            // battery saving. Written down so somebody can be told.
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                nextAt,
                60_000L,
                firePendingIntent(context, alarm, reminderId)
            )
            prefs.edit().putBoolean(Prefs.EXACT_DENIED, true).apply()
        }
        armTheHouse(context, alarmManager, nextAt, alarm)
    }

    /**
     * And a second, earlier alarm whose only job is to tell the house.
     *
     * The one event that earns the whole webhook feature: a lamp asked to
     * simulate a sunrise at the same instant the bell rings has simulated
     * nothing. So the house is told a chosen number of minutes ahead —
     * see [Prefs.IFTTT_LEAD] — and that means a second entry, because
     * there is nothing else running at half past six in the morning to
     * notice anything.
     *
     * Armed and cancelled with the alarm it belongs to, out of the same
     * function, so the two cannot drift apart: an alarm turned off leaves
     * no lead behind it, and the four events that throw an alarm away —
     * see [RearmReceiver] — put this back with it.
     */
    private fun armTheHouse(
        context: Context,
        alarmManager: AlarmManager,
        nextAt: Long,
        alarm: Alarm
    ) {
        val pending = housePendingIntent(context, alarm)
        alarmManager.cancel(pending)
        if (!IftttStore.wanted(context)) return
        val lead = IftttStore.lead(context)
        if (lead <= 0) return
        val at = nextAt - lead * 60_000L
        // A lead that is already in the past is an alarm less than the
        // lead away, which is an ordinary thing at bedtime. Nothing to
        // arm: the ring itself will tell the house soon enough.
        if (at <= System.currentTimeMillis()) return
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            // Not worth an exact slot's argument. A sunrise that starts a
            // few minutes late is still a sunrise.
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pending)
        }
    }

    /** Where the lead alarm lands, and what it carries. */
    private fun housePendingIntent(context: Context, alarm: Alarm?): PendingIntent {
        val intent = Intent(context, HouseReceiver::class.java)
        alarm?.let {
            intent.putExtra(EXTRA_LABEL, it.label)
            intent.putExtra(HouseReceiver.EXTRA_AT, nextOccurrence(it))
        }
        return PendingIntent.getBroadcast(
            context,
            HouseReceiver.REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The soonest this alarm next goes off, across every time of day it is
     * set for — a three-times-a-day concept is still one armed alarm.
     */
    fun nextOccurrence(alarm: Alarm, now: Long = System.currentTimeMillis()): Long {
        val soonest = alarm.allTimes().minOf { (h, m) -> nextOccurrenceOf(alarm, h, m, now) }
        if (soonest != alarm.skippedOccurrence) return soonest
        // This one has been let off. Ask again from a moment after it, so
        // the answer is the one that comes next — which is the whole of
        // "off just for today": the alarm is still armed, and tomorrow it
        // rings.
        return alarm.allTimes().minOf { (h, m) -> nextOccurrenceOf(alarm, h, m, soonest + 1000L) }
    }

    /**
     * Whether [alarm] is standing down for its next turn.
     *
     * A skip that is in the past is not a skip: the morning it applied to
     * has been and gone, and an alarm that goes on remembering it would be
     * an alarm that skips the same weekday for ever.
     */
    fun isSkippingNext(alarm: Alarm, now: Long = System.currentTimeMillis()): Boolean =
        alarm.skippedOccurrence > now

    private fun nextOccurrenceOf(alarm: Alarm, hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now + 1000) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        // Walk forward to the next day this alarm actually rings on.
        var guard = 0
        while (!alarm.ringsOn(cal.get(Calendar.DAY_OF_WEEK)) && guard < 8) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return cal.timeInMillis
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(firePendingIntent(context, null, -1))
        // The lead goes with it. An alarm turned off that still tells the
        // house it is coming is a bedroom that lights itself up for
        // nothing, half an hour before an alarm that was cancelled.
        manager.cancel(housePendingIntent(context, null))
    }

    private fun firePendingIntent(context: Context, alarm: Alarm?, reminderId: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        alarm?.let {
            intent.putExtra(EXTRA_ALARM_ID, it.id)
            intent.putExtra(EXTRA_REMINDER_ID, reminderId)
            intent.putExtra(EXTRA_SOUND, it.sound)
            intent.putExtra(EXTRA_SOUND_URI, it.soundUri)
            intent.putExtra(EXTRA_SNOOZE, it.snoozeMinutes)
            intent.putExtra(EXTRA_SNOOZE_LIMIT, it.snoozeLimit)
            intent.putExtra(EXTRA_LABEL, it.label)
            intent.putExtra(EXTRA_VIBRATE, it.vibrate)
            intent.putExtra(EXTRA_FLASH, it.flash)
            intent.putExtra(EXTRA_MISSION, it.mission)
            intent.putExtra(EXTRA_MISSION_LEVEL, it.missionLevel)
            intent.putExtra(EXTRA_GENTLE, it.gentleWakeSeconds)
            // The sunrise's own torch is the app's answer, not this
            // alarm's: whether a sleeper the light cannot reach wants the
            // light turned up is the same answer every morning.
            intent.putExtra(EXTRA_GENTLE_FLASH, wantsGentleFlash(context))
        }
        return PendingIntent.getBroadcast(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Puts the ringing described by [from] off for [minutes], unless it has
     * been put off enough.
     *
     * It takes the whole intent and not a handful of fields, because the
     * alarm that comes back has to be the *same alarm*. Built by hand from
     * four values, it was not: an alarm snoozed once came back with no
     * mission, no gradual sunrise, no torch, no label, and vibrating even
     * if it had been told not to. Which is the worst possible place for
     * that bug to live — somebody who presses snooze is exactly the person
     * the mission was put there for, and pressing it turned the mission
     * off.
     *
     * [alreadySnoozed] rides in the intent rather than living in a
     * preference, so it can never be a count left over from an alarm three
     * days ago — the thing being counted is one morning's worth of
     * pressing snooze, and one morning's worth of pressing snooze is
     * exactly what one chain of intents is.
     *
     * Returns false when the limit is spent, which is the ring screen's cue
     * to stop offering the button: an alarm that must be got up for is a
     * feature, and a Snooze button that silently does nothing is not.
     */
    fun snooze(
        context: Context,
        from: Intent,
        minutes: Int,
        alreadySnoozed: Int = 0
    ): Boolean {
        val limit = snoozeLimit(from)
        if (limit in 1..alreadySnoozed) return false
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        val at = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        val fire = PendingIntent.getBroadcast(
            context,
            102,
            // Everything this ringing was carrying, and then the two things
            // the snooze itself decides: how long, and that this is one
            // more time of asking.
            carryOver(from, Intent(context, AlarmReceiver::class.java))
                .putExtra(EXTRA_SNOOZE, minutes)
                .putExtra(EXTRA_SNOOZE_COUNT, alreadySnoozed + 1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            context,
            103,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } catch (e: SecurityException) {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, fire)
        }
        return true
    }

    /**
     * Whether the torch takes over after a gradual sunrise has failed.
     *
     * One answer for the app, read here at arming time, so that changing it
     * in the settings takes effect on the next alarm rather than on the
     * next alarm anybody happens to edit.
     */
    fun wantsGentleFlash(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Prefs.GENTLE_FLASH, false)

    /**
     * How many times the ringing described by [intent] may be put off, or 0
     * for as often as you like.
     */
    fun snoozeLimit(intent: Intent?): Int =
        (intent?.getIntExtra(EXTRA_SNOOZE_LIMIT, 0) ?: 0).coerceIn(0, 20)
}
