package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Marking the cycle by tapping the day.
 *
 * The sheet is where a period's length is set and where the history is
 * edited, and it should stay there. But writing down that one started
 * today is a two-second job, and going through a sheet for it is the sort
 * of ceremony that stops somebody bothering — at which point the engine
 * has nothing to learn from and the whole feature is decoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CycleTapTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        CycleStore.forget()
    }

    private fun day(year: Int, month: Int, day: Int) = Cycle.epochDay(year, month, day)

    /** A tap on an empty day writes one down. */
    @Test
    fun `tapping a day with nothing on it starts a period`() {
        val at = day(2026, 8, 19)
        val after = Cycle.tapped(emptyList(), at)
        assertEquals(1, after.size)
        assertEquals(at, after.first().start)
        assertTrue(Cycle.marked(after, at))
    }

    /**
     * And a tap on a day already inside one takes it off again.
     *
     * Tapping a thing that is already on means switching it off everywhere
     * else, and a calendar is not the place to break that.
     */
    @Test
    fun `tapping a marked day takes the mark off`() {
        val at = day(2026, 8, 19)
        val marked = Cycle.tapped(emptyList(), at)
        val after = Cycle.tapped(marked, at)
        assertEquals(emptyList<Cycle.Period>(), after)
        assertFalse(Cycle.marked(after, at))
    }

    /**
     * Including a day in the middle of one.
     *
     * By the period the day falls in rather than by the day it started, or
     * tapping the third day of a period would quietly start a second one
     * inside the first — and the engine would learn a three-day cycle from
     * it.
     */
    @Test
    fun `tapping the middle of a period removes that period`() {
        val start = day(2026, 8, 19)
        val record = listOf(Cycle.Period(start, days = 5))
        assertTrue("set up wrong", Cycle.marked(record, start + 2))

        val after = Cycle.tapped(record, start + 2)
        assertEquals("the period was not the thing removed", emptyList<Cycle.Period>(), after)
    }

    /** Two taps far apart are two periods, not a correction. */
    @Test
    fun `marking a second month leaves the first alone`() {
        val august = day(2026, 8, 3)
        val september = day(2026, 9, 1)
        val after = Cycle.tapped(Cycle.tapped(emptyList(), august), september)
        assertEquals(2, after.size)
        assertEquals(
            "and they are not in the order they were tapped",
            listOf(august, september), after.map { it.start }
        )
    }

    /**
     * The whole thing, through the calendar.
     *
     * The rule above is arithmetic. This is that a tap on a day actually
     * reaches it and that what it writes survives being asked for again.
     */
    @Test
    fun `a day tapped on the calendar is marked in the record`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.CYCLE, true)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.CALENDAR)
            val at = activity.markCycleForTest(11)
            assertTrue(
                "the day was tapped and nothing was written down",
                Cycle.marked(CycleStore.all(context), at)
            )
            activity.markCycleForTest(11)
            assertFalse(
                "and tapping it again did not take it off",
                Cycle.marked(CycleStore.all(context), at)
            )
        }
    }

    /**
     * And the calendar shows it without being asked twice.
     *
     * A mark that is written down and not drawn until the month is changed
     * and changed back is a mark nobody believes they made.
     */
    @Test
    fun `the day is painted as soon as it is marked`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.CYCLE, true)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.CALENDAR)
            activity.markCycleForTest(11)
            assertEquals(
                "the calendar is not showing the day that was just marked",
                Cycle.Phase.PERIOD, activity.calendarCyclePhasesForTest()[11]
            )
        }
    }
}
