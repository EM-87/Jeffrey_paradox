package com.em87.weirdclock

import android.app.AlarmManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The little clock in the status bar.
 *
 * It is not ours to draw. Android shows it whenever some app has an
 * [AlarmManager.AlarmClockInfo] pending, and that is the only way an app
 * can put anything in that strip. So the test is not "does the icon
 * appear" — nothing on this side of the glass can answer that — but "is
 * there an AlarmClockInfo registered", which is the whole of our half of
 * the bargain.
 *
 * Worth pinning because the fall-back hides it. If [AlarmScheduler.update]
 * catches a SecurityException it schedules with `setWindow` instead, which
 * still rings but gives no icon and a minute of slop; the alarm goes on
 * working and the only outward sign that anything changed is an icon that
 * is not there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmIconTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
        ReminderStore.forget()
    }

    private fun tomorrowAt(hour: Int, minute: Int) = Alarm(1, hour, minute, true, "bell")

    /** Puts exactly these alarms in the store, the way the app would. */
    private fun store(vararg alarms: Alarm) {
        val list = AlarmStore.all(context)
        list.clear()
        list.addAll(alarms)
        AlarmStore.save(context)
    }

    /** An armed alarm registers as an alarm clock, which is what draws the icon. */
    @Test
    fun `an enabled alarm puts a real alarm clock on the system`() {
        store(tomorrowAt(7, 30))
        AlarmScheduler.update(context)

        val next = alarmManager.nextAlarmClock
        assertNotNull(
            "nothing was registered as an alarm clock, so the status bar has nothing to draw",
            next
        )
        assertEquals(
            "and it is registered for the time the alarm is set to",
            AlarmScheduler.nextOccurrence(tomorrowAt(7, 30)), next!!.triggerTime
        )
    }

    /** Switch them all off and it goes away again. */
    @Test
    fun `with nothing armed there is no alarm clock`() {
        store(tomorrowAt(7, 30).copy(enabled = false))
        AlarmScheduler.update(context)
        assertNull("something stayed armed with every alarm off", alarmManager.nextAlarmClock)
    }

    /**
     * And the soonest one wins, since the system only shows one.
     *
     * There is a single armed slot in this app on purpose — the receiver
     * re-arms the next one after each ring — so the slot must hold the
     * earliest of everything, alarms and dated reminders alike.
     */
    @Test
    fun `the soonest of several is the one registered`() {
        val early = Alarm(1, 6, 0, true, "bell")
        val late = Alarm(2, 9, 0, true, "bell")
        store(late, early)
        AlarmScheduler.update(context)
        assertEquals(
            AlarmScheduler.nextOccurrence(
                listOf(early, late).minByOrNull { AlarmScheduler.nextOccurrence(it) }!!
            ),
            alarmManager.nextAlarmClock!!.triggerTime
        )
    }

    /**
     * Winding time makes the icon disappear, and that is deliberate.
     *
     * Alarms are cancelled outright while the clock is running fast or
     * slow, because an alarm set for seven and a clock that thinks it is
     * seven twice as fast is not something worth guessing about. Recorded
     * here because "my alarm icon vanished" and "I left the speed at 200%"
     * are the same sentence.
     */
    @Test
    fun `time travel disarms everything, icon included`() {
        store(tomorrowAt(7, 30))
        AlarmScheduler.update(context)
        assertNotNull(alarmManager.nextAlarmClock)

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(Prefs.TIME_SPEED, 200).commit()
        AlarmScheduler.update(context)
        assertNull("an alarm survived time travel", alarmManager.nextAlarmClock)
    }
}
