package com.em87.weirdclock

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What one ringing carries with it, from one hop to the next.
 *
 * There is a chain — the scheduler arms an intent, the receiver hands it
 * to the service, the service hands it to the screen — and every hop used
 * to copy the extras out by hand. Miss one and it silently becomes its
 * default at that hop, with nothing to see from the outside.
 *
 * That is not a hypothetical. The snooze count was dropped by the
 * receiver, so the ring screen was told "none so far" every single time,
 * and the snooze limit — a setting people had turned on, with a test of
 * its own that passed — limited nothing at all. The test passed because it
 * handed the function a number the real chain never produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmChainTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** An intent with every carried extra set to something recognisable. */
    private fun loaded(): Intent = Intent()
        .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 7)
        .putExtra(AlarmScheduler.EXTRA_REMINDER_ID, 8)
        .putExtra(AlarmScheduler.EXTRA_SOUND, Prefs.ALARM_SOUND_BABY)
        .putExtra(AlarmScheduler.EXTRA_SOUND_URI, "content://somewhere")
        .putExtra(AlarmScheduler.EXTRA_SNOOZE, 9)
        .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 3)
        .putExtra(AlarmScheduler.EXTRA_LABEL, "Work")
        .putExtra(AlarmScheduler.EXTRA_VIBRATE, false)
        .putExtra(AlarmScheduler.EXTRA_FLASH, true)
        .putExtra(AlarmScheduler.EXTRA_FROM_TIMER, true)
        .putExtra(AlarmScheduler.EXTRA_MISSION, Mission.SHAKE)
        .putExtra(AlarmScheduler.EXTRA_MISSION_LEVEL, 4)
        .putExtra(AlarmScheduler.EXTRA_GENTLE, 60)
        .putExtra(AlarmScheduler.EXTRA_GENTLE_FLASH, true)
        .putExtra(Nag.EXTRA_ROUND, 4)

    /** Nothing carried is lost on the way across. */
    @Test
    fun `every extra survives the hop`() {
        val from = loaded()
        val to = AlarmScheduler.carryOver(from, Intent())

        for (key in AlarmScheduler.CARRIED) {
            assertTrue("$key was dropped", to.extras?.containsKey(key) == true)
            assertEquals("$key changed", from.extras?.get(key), to.extras?.get(key))
        }
    }

    /**
     * Every extra an alarm is actually armed with is on the list.
     *
     * The test above walks the list and checks each one survives, which is
     * trivially true if somebody shortens the list — exactly how a dropped
     * extra would look. This one goes the other way: it arms a real alarm,
     * reads the intent the system was handed, and insists that nothing on
     * it is missing from the list that gets copied. A field added to an
     * alarm and forgotten in CARRIED fails here.
     */
    @Test
    fun `nothing an alarm is armed with is left off the list`() {
        AlarmStore.forget()
        AlarmStore.all(context).add(
            Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS).apply {
                mission = Mission.MATHS
                missionLevel = 5
                gentleWakeSeconds = 60
                gentleFlash = true
                flash = true
                label = "Work"
            }
        )
        AlarmStore.save(context)
        AlarmScheduler.update(context)

        val armed = org.robolectric.Shadows
            .shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
            .scheduledAlarms
            .mapNotNull { org.robolectric.Shadows.shadowOf(it.operation).savedIntent }
            .firstOrNull { it.component?.className?.endsWith("AlarmReceiver") == true }
        assertTrue("no alarm was armed at all", armed != null)

        val carried = AlarmScheduler.CARRIED.toSet()
        for (key in armed!!.extras?.keySet().orEmpty()) {
            assertTrue(
                "$key is put on the alarm and is not in CARRIED, so every hop drops it",
                key in carried
            )
        }
        AlarmStore.forget()
    }

    /** And what was not there does not appear out of nowhere. */
    @Test
    fun `an empty intent carries nothing`() {
        val to = AlarmScheduler.carryOver(Intent(), Intent())
        for (key in AlarmScheduler.CARRIED) {
            assertTrue("$key invented", to.extras?.containsKey(key) != true)
        }
    }

    /**
     * The two counts are the reason the list exists. Both ride in the
     * intent rather than in a preference — a number kept in a preference
     * outlives the morning it belongs to — and both were being left
     * behind, which reset them to zero on every hop and made each of them
     * a limit that never arrived.
     */
    @Test
    fun `the counts are among the things carried`() {
        assertTrue(AlarmScheduler.EXTRA_SNOOZE_COUNT in AlarmScheduler.CARRIED)
        assertTrue(Nag.EXTRA_ROUND in AlarmScheduler.CARRIED)

        val to = AlarmScheduler.carryOver(loaded(), Intent())
        assertEquals(3, to.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0))
        assertEquals(4, to.getIntExtra(Nag.EXTRA_ROUND, 0))
    }

    /** The receiver really uses it, rather than copying by hand again. */
    @Test
    fun `the receiver hands the whole lot to the service`() {
        AlarmReceiver().onReceive(context, loaded())
        val started = org.robolectric.Shadows
            .shadowOf(context as android.app.Application)
            .nextStartedService
        assertTrue("no service was started at all", started != null)
        assertEquals(
            "the snooze count was dropped on the way to the service",
            3, started!!.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, -1)
        )
        assertEquals(
            "and so was the nagging round",
            4, started.getIntExtra(Nag.EXTRA_ROUND, -1)
        )
    }

    /**
     * And the second hop, which is the other place one can go missing:
     * everything the ring screen reads off its intent has to be on it.
     */
    @Test
    fun `the screen is handed everything it reads`() {
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        try {
            val intent = controller.get().ringIntent()
            for (key in arrayOf(
                AlarmScheduler.EXTRA_SOUND,
                AlarmScheduler.EXTRA_SOUND_URI,
                AlarmScheduler.EXTRA_SNOOZE,
                AlarmScheduler.EXTRA_SNOOZE_COUNT,
                AlarmScheduler.EXTRA_LABEL,
                AlarmScheduler.EXTRA_FROM_TIMER,
                AlarmScheduler.EXTRA_MISSION,
                AlarmScheduler.EXTRA_GENTLE,
                AlarmScheduler.EXTRA_MISSION_LEVEL,
                AlarmScheduler.EXTRA_GENTLE_FLASH
            )) {
                assertTrue("$key never reaches the screen", intent.extras?.containsKey(key) == true)
            }
        } finally {
            controller.destroy()
        }
    }
}
