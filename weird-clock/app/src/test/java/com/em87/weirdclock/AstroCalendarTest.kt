package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The astronomers' calendar: Julian to the 4th of October 1582, Gregorian
 * from the 15th, the year nought before the year one, and nothing read
 * off the phone.
 */
class AstroCalendarTest {

    @Test
    fun `the ten days that never happened are one day apart`() {
        val lastJulian = AstroCalendar.jdnOf(1582, 10, 4)
        val firstGregorian = AstroCalendar.jdnOf(1582, 10, 15)
        assertEquals(1L, firstGregorian - lastJulian)
        assertEquals(AstroCalendar.GREGORIAN_FROM_JDN, firstGregorian)
        assertTrue(AstroCalendar.dateOf(lastJulian).julian)
        assertFalse(AstroCalendar.dateOf(firstGregorian).julian)
        // Going the other way lands on the same two days.
        val before = AstroCalendar.dateOf(firstGregorian - 1)
        assertEquals(listOf(1582, 10, 4), listOf(before.year, before.month, before.day))
    }

    @Test
    fun `the year nought is the year before one, and 585 BC is minus 584`() {
        // The eclipse of Thales, as the canons write it: -0584 May 28.
        val eclipse = AstroCalendar.jdnOf(-584, 5, 28)
        // Horizons gave JD 1507900.09891 for 14:22 UT that day, so the
        // day's number — noon to noon — is 1507900.
        assertEquals(1_507_900L, eclipse)
        val d = AstroCalendar.dateOf(eclipse)
        assertEquals(-584, d.year)
        assertTrue(d.julian)
        // A year nought exists and is a leap year in the Julian reckoning.
        assertEquals(366, AstroCalendar.daysInYear(0))
        assertEquals(1L, AstroCalendar.jdnOf(1, 1, 1) - AstroCalendar.jdnOf(0, 12, 31))
    }

    @Test
    fun `Julian years have a leap day every fourth year and Gregorian ones skip the centuries`() {
        assertEquals(366, AstroCalendar.daysInYear(1000))
        assertEquals(366, AstroCalendar.daysInYear(1500))
        assertEquals(365, AstroCalendar.daysInYear(1900))
        assertEquals(366, AstroCalendar.daysInYear(2000))
        // The year of the reform itself is ten days short.
        assertEquals(355, AstroCalendar.daysInYear(1582))
    }

    @Test
    fun `every day is one more than the day before it, across the reform and the year nought`() {
        for (jdn in listOf(1_507_900L, 1_721_424L, 2_299_161L, 2_451_545L)) {
            for (k in -400L..400L) {
                val d = AstroCalendar.dateOf(jdn + k)
                assertEquals("round trip at ${jdn + k}", jdn + k, AstroCalendar.jdnOf(d.year, d.month, d.day))
            }
        }
    }

    @Test
    fun `an instant is read in UTC, or on the phone's clock when asked`() {
        // 2026-03-20 14:46 UTC is the 20th in UTC and the 21st ten hours east.
        val at = AstroCalendar.msOf(2026, 3, 20) + (14 * 60 + 46) * 60_000L
        assertEquals(20, AstroCalendar.dateAt(at).day)
        assertEquals(21, AstroCalendar.dateAt(at, 10 * 3_600_000).day)
        assertFalse(AstroCalendar.dateAt(at).julian)
        // And the epoch-day form agrees with the day count the app keeps
        // for modern dates.
        assertEquals(CivilDays.epochDay(2026, 3, 20), AstroCalendar.epochDayOf(2026, 3, 20))
        // Before the reform it does not: in the year 1000 the Julian
        // calendar ran six days behind the Gregorian one, so the day the
        // people of 1000 called the first of March is the one a proleptic
        // Gregorian count calls the seventh — six days after its own first.
        assertEquals(-6, CivilDays.epochDay(1000, 3, 1) - AstroCalendar.epochDayOf(1000, 3, 1))
    }
}
