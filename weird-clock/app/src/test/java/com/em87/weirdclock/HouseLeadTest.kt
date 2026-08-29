package com.em87.weirdclock

import android.app.AlarmManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * The alarm before the alarm, checked through AlarmManager.
 *
 * The event that earns this whole feature is the one nothing else in the
 * app is awake for: a sunrise scene has to start ramping *before* the bell,
 * so something has to run at half past six with no clock on screen and no
 * service running. That means a second AlarmManager entry, and a second
 * entry is a second thing that can be left behind.
 *
 * So what is tested is not "does it send" — [IftttStoreTest] holds that
 * end — it is that the lead is armed and cancelled *with* the alarm it
 * belongs to. An alarm turned off that still tells the house it is coming
 * is a bedroom lighting itself up, half an hour before a bell that will
 * never ring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HouseLeadTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun shadow() =
        Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))

    /** Every entry AlarmManager is holding, without consuming them. */
    private fun armed(): List<Long> =
        shadow().scheduledAlarms.map { it.triggerAtTime }.sorted()

    private fun settings(on: Boolean, lead: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.IFTTT, on)
            .putString(Prefs.IFTTT_KEY, "bX9kQ27fLmNp4rS8tV1w")
            .putInt(Prefs.IFTTT_LEAD, lead)
            .commit()
    }

    /** One alarm, some hours from now, so the lead is comfortably ahead. */
    private fun anAlarmIn(hours: Int) {
        val at = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, hours)
        }
        AlarmStore.forget()
        AlarmStore.all(context).apply {
            clear()
            add(
                Alarm(
                    1,
                    at.get(Calendar.HOUR_OF_DAY),
                    at.get(Calendar.MINUTE),
                    true,
                    Prefs.ALARM_SOUND_BELLS,
                    label = "wake up"
                )
            )
        }
        AlarmStore.save(context)
    }

    @Before
    fun clean() {
        while (shadow().nextScheduledAlarm != null) {
            // getNextScheduledAlarm removes as it reads.
        }
    }

    /**
     * Switched on, arming an alarm arms two things, and the second is the
     * lead time earlier.
     */
    @Test
    fun `the house is warned the chosen number of minutes early`() {
        settings(on = true, lead = 30)
        anAlarmIn(4)
        AlarmScheduler.update(context)
        val entries = armed()
        assertEquals("the lead was not armed with the alarm", 2, entries.size)
        assertEquals(
            "the warning is not half an hour before the bell",
            30L * 60_000L, entries[1] - entries[0]
        )
    }

    /** Switched off, there is one entry and it is the alarm. */
    @Test
    fun `switched off, nothing extra is armed`() {
        settings(on = false, lead = 30)
        anAlarmIn(4)
        AlarmScheduler.update(context)
        assertEquals("something was armed for a house nobody asked about", 1, armed().size)
    }

    /**
     * A lead of nought is a house that wants telling when it rings and not
     * before, which is a real answer and not an off switch.
     */
    @Test
    fun `no lead time arms nothing early`() {
        settings(on = true, lead = 0)
        anAlarmIn(4)
        AlarmScheduler.update(context)
        assertEquals(1, armed().size)
    }

    /**
     * An alarm closer than the lead arms nothing early either.
     *
     * Ordinary at bedtime: set something for twenty minutes' time with a
     * thirty-minute lead and the warning belongs in the past. Arming it
     * anyway is an alarm that fires the instant it is set.
     */
    @Test
    fun `an alarm sooner than the lead does not warn anybody`() {
        settings(on = true, lead = 120)
        anAlarmIn(1)
        AlarmScheduler.update(context)
        assertEquals("it armed a warning that was already overdue", 1, armed().size)
    }

    /**
     * And the lead goes when the alarm goes.
     *
     * The failure this is here for: an alarm switched off overnight whose
     * warning is still armed, so the bedroom lights itself up half an hour
     * before a bell that is never going to ring.
     */
    @Test
    fun `turning the alarm off takes the warning with it`() {
        settings(on = true, lead = 30)
        anAlarmIn(4)
        AlarmScheduler.update(context)
        assertEquals(2, armed().size)

        AlarmStore.all(context).clear()
        AlarmStore.save(context)
        AlarmScheduler.update(context)
        assertTrue("the warning outlived the alarm: ${armed()}", armed().isEmpty())
    }

    /**
     * Switching the house off takes the warning down while the alarm
     * stays up.
     *
     * The case the previous test only *looked* like it covered. Emptying
     * the alarm list cancels everything through a different door — the
     * scheduler's own "nothing to arm" path — so removing the cancel
     * inside the arming left that test green while a real warning went on
     * standing. This is the one that fails: the alarm is still there, the
     * house has been switched off, and a lamp is otherwise still going to
     * come up at half past six for the rest of the week.
     */
    @Test
    fun `switching the house off takes the warning down and leaves the alarm`() {
        settings(on = true, lead = 30)
        anAlarmIn(4)
        AlarmScheduler.update(context)
        assertEquals(2, armed().size)

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(Prefs.IFTTT, false).commit()
        AlarmScheduler.update(context)
        assertEquals("the warning went on standing without the house", 1, armed().size)

        // The same for the lead being turned down to nothing, which is the
        // other way somebody stops wanting it.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.IFTTT, true).commit()
        AlarmScheduler.update(context)
        assertEquals(2, armed().size)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(Prefs.IFTTT_LEAD, 0).commit()
        AlarmScheduler.update(context)
        assertEquals("a lead turned down to nothing left one behind", 1, armed().size)
    }

    /**
     * And the four events that throw an alarm away put the warning back
     * with it, because both go through the same scheduler.
     */
    @Test
    fun `a new build of the app re-arms the warning too`() {
        settings(on = true, lead = 30)
        anAlarmIn(4)
        AlarmScheduler.update(context)
        while (shadow().nextScheduledAlarm != null) {
            // Everything gone, the way installing a new build does it.
        }
        assertTrue(armed().isEmpty())
        context.sendBroadcast(
            android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED)
                .setPackage(context.packageName)
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("the warning did not come back with the alarm", 2, armed().size)
    }
}
