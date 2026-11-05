package com.em87.weirdclock

import android.app.AlarmManager
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * That the bells ring when the clock says, and not when the phone gets
 * round to it.
 *
 * A strike two minutes late was heard, from an app whose whole job is the
 * time. The platform's rules for a background alarm are written in its
 * source and not in its documentation, and they come to this: an alarm
 * that is both exact and allowed while idle is the only ordinary kind the
 * standby buckets and the battery saver may not ration, and an alarm
 * registered as an alarm clock is the only kind nothing may touch. These
 * tests pin which of the two is booked, where it is delivered, that the
 * system's lateness is written down where somebody can read it, and that
 * the hour is not struck twice when the app is open to strike it itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BellPunctualityTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private fun shadow() = Shadows.shadowOf(alarmManager)

    @Before
    fun bellsOnWithTheAppShut() {
        prefs.edit().clear()
            .putBoolean(Prefs.BELLS, true)
            .putBoolean(Prefs.BELLS_BACKGROUND, true)
            .commit()
        AlarmStore.forget()
        AlarmStore.all(context).clear()
        AlarmStore.save(context)
        Bells.soundingUntil = 0L
        MainActivity.inFront = false
        while (shadow().nextScheduledAlarm != null) {
            // getNextScheduledAlarm removes as it reads.
        }
    }

    private fun armed() = shadow().peekNextScheduledAlarm()
        ?: throw AssertionError("nothing was armed")

    /** The next whole minute the bells could be booked for, or later. */
    private fun aheadToTheMinute(ms: Long): Boolean =
        ms > System.currentTimeMillis() && ms % 60_000L == 0L

    /**
     * Exact, allowed while idle, and a wake-up: the one ordinary kind of
     * alarm the standby buckets may not ration. An inexact one from an
     * app that has not been opened for a day is allowed ten deliveries an
     * hour and then held to the end of the hour, and an exact one that is
     * not allowed while idle is treated the same.
     */
    @Test
    fun `the strike is booked as an alarm the phone may not ration`() {
        BellScheduler.update(context)
        val alarm = armed()
        assertEquals("not a wake-up", AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals("not exact: window ${alarm.windowLengthMs}", 0L, alarm.windowLengthMs)
        assertTrue("not allowed while idle", alarm.isAllowWhileIdle)
        assertTrue("booked for ${alarm.triggerAtTime}", aheadToTheMinute(alarm.triggerAtTime))
    }

    /**
     * Delivered straight to the service that strikes, as a foreground
     * service, rather than to a receiver that then asks to start one. The
     * hop was a second place for the platform to say no — a background
     * app may start a foreground service only inside windows it does not
     * publish — and it was a hop the strike could lose a second on.
     */
    @Test
    fun `and handed straight to the service that strikes`() {
        BellScheduler.update(context)
        val operation = Shadows.shadowOf(armed().operation)
        assertTrue("not a foreground service", operation.isForegroundService)
        assertEquals(
            BellService::class.java.name,
            operation.savedIntent.component?.className
        )
    }

    /**
     * The strike carries the instant it was armed for, so the service can
     * measure how late the system was rather than argue about it.
     */
    @Test
    fun `the strike knows when it was due`() {
        BellScheduler.update(context)
        val alarm = armed()
        val due = Shadows.shadowOf(alarm.operation).savedIntent
            .getLongExtra(BellScheduler.EXTRA_ARMED_FOR, 0L)
        assertEquals(alarm.triggerAtTime, due)
    }

    /**
     * Registered as an alarm clock only when asked. An alarm clock is the
     * one kind of alarm no policy on the phone may defer, and also the one
     * the lock screen announces as "next alarm" — which, on a phone whose
     * alarm clock this app is, would show every passing hour in place of
     * tomorrow morning. So it is a switch, and off by default.
     */
    @Test
    fun `it is the phone's next alarm only when asked to be`() {
        BellScheduler.update(context)
        assertNull(
            "the bells are standing in for the alarm on the lock screen",
            alarmManager.nextAlarmClock
        )

        prefs.edit().putBoolean(Prefs.BELLS_AS_ALARM, true).commit()
        BellScheduler.update(context)
        val clock = alarmManager.nextAlarmClock
        assertNotNull("asked to be the alarm, and not registered as one", clock)
        assertTrue(aheadToTheMinute(clock.triggerTime))
    }

    /** Switched off, the chain is gone — whichever kind it was booked as. */
    @Test
    fun `off means no alarm of either kind`() {
        prefs.edit().putBoolean(Prefs.BELLS_AS_ALARM, true).commit()
        BellScheduler.update(context)
        assertNotNull(armed())
        prefs.edit().putBoolean(Prefs.BELLS_BACKGROUND, false).commit()
        BellScheduler.update(context)
        assertNull("still armed with the switch off", shadow().peekNextScheduledAlarm())

        prefs.edit().putBoolean(Prefs.BELLS_BACKGROUND, true)
            .putBoolean(Prefs.BELLS_AS_ALARM, false).commit()
        BellScheduler.update(context)
        assertNotNull(armed())
        prefs.edit().putBoolean(Prefs.BELLS, false).commit()
        BellScheduler.update(context)
        assertNull("still armed with the bells off", shadow().peekNextScheduledAlarm())
    }

    private fun strike(armedFor: Long, hour: Int = 10, minute: Int = 0) {
        val intent = Intent(context, BellService::class.java)
            .putExtra(BellScheduler.EXTRA_HOUR, hour)
            .putExtra(BellScheduler.EXTRA_MINUTE, minute)
            .putExtra(BellScheduler.EXTRA_ARMED_FOR, armedFor)
        Robolectric.buildService(BellService::class.java, intent).create().startCommand(0, 0)
    }

    /**
     * How late the system was is written down, so a strike heard two
     * minutes late becomes a number in the settings and not a memory.
     */
    @Test
    fun `the service writes down how late the phone was`() {
        val due = System.currentTimeMillis() - 1_800L
        strike(due)
        assertEquals(due, prefs.getLong(Prefs.BELL_LAST_DUE, 0L))
        val late = prefs.getLong(Prefs.BELL_LAST_LATE_MS, -1L)
        assertTrue("lateness recorded as $late ms", late in 1_800L..4_000L)
    }

    /** And the worst it has ever been, which is the number that matters. */
    @Test
    fun `and the worst it has been`() {
        strike(System.currentTimeMillis() - 5_000L)
        strike(System.currentTimeMillis() - 500L)
        val worst = prefs.getLong(Prefs.BELL_WORST_LATE_MS, -1L)
        assertTrue("worst lateness forgotten: $worst", worst >= 5_000L)
        assertTrue(prefs.getLong(Prefs.BELL_LAST_LATE_MS, -1L) < 3_000L)
    }

    /**
     * Each strike re-arms the next. The receiver used to do this; with the
     * strike delivered straight to the service, the service must, or the
     * chain ends with the first bell.
     */
    @Test
    fun `each strike arms the next`() {
        strike(System.currentTimeMillis())
        val next = armed()
        assertTrue(
            "re-armed for ${next.triggerAtTime}", aheadToTheMinute(next.triggerAtTime)
        )
    }

    /**
     * The app open on the hour strikes it itself, from its own loop. The
     * chain is still armed underneath — it is re-armed every time the
     * app is left — so the service arrives on the same second, and the
     * two used to ring over each other.
     */
    @Test
    fun `the hour is not struck twice while the app is open`() {
        prefs.edit()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertTrue("the app does not know it is in front", MainActivity.inFront)
            strike(System.currentTimeMillis())
            assertEquals(
                "the service struck over the app", 0L, Bells.soundingUntil
            )
            // And still re-armed the next, because the app may be gone by then.
            assertTrue(aheadToTheMinute(armed().triggerAtTime))
            c.pause()
            assertTrue("still in front after pausing", !MainActivity.inFront)
        }
        strike(System.currentTimeMillis())
        assertTrue("the service kept quiet with the app shut", Bells.soundingUntil > 0L)
    }

    /**
     * The lateness is told in words a person can read off a settings row,
     * and the words say what the number says.
     */
    @Test
    fun `lateness is said in seconds or in minutes as it deserves`() {
        assertEquals("0.8 s", Bells.lateText(800L))
        assertEquals("12 s", Bells.lateText(12_400L))
        assertEquals("1 min 52 s", Bells.lateText(112_000L))
        assertEquals("0.0 s", Bells.lateText(-300L))
    }
}
