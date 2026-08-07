package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stopwatch run outliving the app.
 *
 * Nothing about it was kept — not the laps, not the accumulated time, not
 * even the fact that it was running. Time something for an hour, let
 * Android reclaim the app while you are in another one, and the run was
 * gone, which is the one thing a stopwatch must never do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StopwatchStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun wipe() {
        prefs.edit().clear().commit()
    }

    private fun laps(vararg ms: Long) = ms.mapIndexed { i, at ->
        ClockView.LapRecord(at, fake = i == 1, hour = 1f * i, minute = 2f * i, second = 3f * i)
    }

    @Test
    fun `nothing stored is nothing to restore`() {
        assertNull(StopwatchStore.load(prefs))
    }

    @Test
    fun `a run comes back with its laps`() {
        StopwatchStore.save(prefs, 90_000L, 1_000L, running = true, laps = laps(30_000L, 60_000L))
        val back = StopwatchStore.load(prefs)
        assertNotNull(back)
        assertEquals(90_000L, back!!.accumMs)
        assertEquals(1_000L, back.startedAt)
        assertTrue(back.running)
        assertEquals(2, back.laps.size)
        assertEquals(60_000L, back.laps[1].ms)
    }

    /**
     * The angles travel with each lap rather than being worked out again
     * from its time. A faked lap is precisely one whose hands disagreed
     * with its number, so deriving the hands from the number would quietly
     * make every restored lap honest.
     */
    @Test
    fun `a faked lap comes back faked, hands and all`() {
        StopwatchStore.save(prefs, 0L, 0L, running = false, laps = laps(30_000L, 60_000L))
        val back = StopwatchStore.load(prefs)!!
        assertFalse(back.laps[0].fake)
        assertTrue(back.laps[1].fake)
        assertEquals(1f, back.laps[1].hour, 0.001f)
        assertEquals(2f, back.laps[1].minute, 0.001f)
        assertEquals(3f, back.laps[1].second, 0.001f)
    }

    /**
     * The stopwatch counts on the clock that runs from boot, so after one
     * a stored start time reads as an enormous elapsed stretch. What was
     * already banked is kept and the watch comes back stopped: the missing
     * stretch could only be guessed from the wall clock, which is the one
     * clock on the device a user can move by hand.
     */
    @Test
    fun `a reboot stops the watch but keeps what it had banked`() {
        StopwatchStore.save(prefs, 90_000L, 1_000L, running = true, laps = laps(30_000L))
        // The elapsed clock started somewhere else entirely: a reboot.
        prefs.edit().putLong("stopwatch_boot_at", 1L).commit()

        val back = StopwatchStore.load(prefs)!!
        assertFalse("it cannot know how long it ran", back.running)
        assertEquals("but it knows what it had", 90_000L, back.accumMs)
        assertEquals("and the laps are facts", 1, back.laps.size)
    }

    /** A run that cannot be read must not be an app that will not open. */
    @Test
    fun `nonsense in the store is survived`() {
        StopwatchStore.save(prefs, 5_000L, 0L, running = false, laps = laps(1_000L))
        prefs.edit().putString("stopwatch_laps", "{{{not json").commit()

        val back = StopwatchStore.load(prefs)!!
        assertEquals(5_000L, back.accumMs)
        assertTrue(back.laps.isEmpty())
    }

    /**
     * And the app actually writes, on its way to the background — the last
     * moment it is certain to be alive, since the system may reclaim it
     * afterwards without another word.
     */
    @Test
    fun `the app writes the run down when it goes away`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val app = controller.get()
            app.findViewById<android.widget.ImageButton>(R.id.to_stopwatch_button).performClick()
            app.findViewById<ClockView>(R.id.stopwatch_clock_view).recordLap()

            controller.pause()

            val stored = StopwatchStore.load(prefs)
            assertNotNull("nothing was written down", stored)
            assertEquals("the lap must have survived", 1, stored!!.laps.size)
        }
    }

    /**
     * And the app actually asks. The store working says nothing about
     * anything calling it — which is how the last three regressions got
     * out.
     */
    @Test
    fun `the app picks the run up when it opens`() {
        StopwatchStore.save(prefs, 42_000L, 0L, running = false, laps = laps(10_000L, 20_000L))

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val dial = controller.get().findViewById<ClockView>(R.id.stopwatch_clock_view)
            assertEquals("the laps must be back on the dial", 2, dial.exportLaps().size)
            assertEquals(20_000L, dial.exportLaps()[1].ms)
        }
    }
}
