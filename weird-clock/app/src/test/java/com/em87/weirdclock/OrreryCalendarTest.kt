package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The word under the sky's date when that date is Julian.
 *
 * Before the 15th of October 1582 the date the orrery shows is in the
 * calendar people kept then, which is not the one a phone would print
 * for the same instant; so the orrery says which — "4 Feb 3000 BC,
 * Julian" — and says nothing when the calendar is the one on the wall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class OrreryCalendarTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PhoneZone.move("UTC")
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    /** A dial with its sky wound to noon UTC of a day in the astronomers' calendar. */
    private fun skyOn(year: Int, month: Int, day: Int): ClockView {
        val clock = ClockView(context)
        val want = AstroCalendar.msOf(year, month, day) + 12 * 3_600_000L
        clock.nudgeOrreryForTest(want - clock.orreryMsForTest())
        // The sky runs on the wall clock, which moves between the two
        // readings — a couple of milliseconds, measured — so the landing
        // is checked to the minute, which cannot move noon off its day.
        val landed = clock.orreryMsForTest()
        assertTrue("the sky did not land on the day: $landed for $want", Math.abs(landed - want) < 60_000L)
        return clock
    }

    @Test
    fun `a date before the reform is named Julian, with its year before Christ`() {
        assertEquals("4 Feb 3000 BC, Julian", skyOn(-2999, 2, 4).orreryCalendarNote())
        assertEquals("28 May 585 BC, Julian", skyOn(-584, 5, 28).orreryCalendarNote())
        assertEquals("14 Mar 1000, Julian", skyOn(1000, 3, 14).orreryCalendarNote())
        assertEquals("4 Oct 1582, Julian", skyOn(1582, 10, 4).orreryCalendarNote())
    }

    @Test
    fun `from the reform on there is nothing to say`() {
        assertNull(skyOn(1582, 10, 15).orreryCalendarNote())
        assertNull(skyOn(1715, 5, 3).orreryCalendarNote())
        assertNull(skyOn(2026, 11, 10).orreryCalendarNote())
    }

    /** And the year the date row writes is the same year, whatever the phone's calendar. */
    @Test
    fun `the date row and the note agree on the year`() {
        val clock = skyOn(-584, 5, 28)
        assertEquals(OrreryYear.Script.EGYPTIAN, clock.orreryScript())
        // Egyptian years are a count back from the year one: 585.
        assertTrue(clock.orreryDateDigits(), clock.orreryDateDigits().endsWith("585"))
        assertEquals(1000, SkyAge.yearOf(AstroCalendar.msOf(1000, 3, 14) + 12 * 3_600_000L))
        assertEquals(-584, SkyAge.yearOf(AstroCalendar.msOf(-584, 5, 28)))
    }
}
