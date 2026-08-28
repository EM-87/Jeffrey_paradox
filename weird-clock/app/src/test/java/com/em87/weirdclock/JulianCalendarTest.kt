package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The calendar the dial was cut for, checked against dates anybody can
 * look up.
 *
 * A calendar conversion is the kind of arithmetic that is either exactly
 * right or off by a day in one direction for four hundred years, and no
 * amount of looking at it will tell you which. So the cases here are ones
 * with an answer in a book: the day the calendar changed, the leap year
 * that only one of the two has, and the drift on either side of the
 * centuries that cause it.
 */
class JulianCalendarTest {

    private fun julian(y: Int, m: Int, d: Int) = JulianCalendar.of(y, m, d)

    /**
     * The change itself. Thursday 4 October 1582 in the Julian calendar
     * was followed by Friday 15 October in the Gregorian one — ten days
     * that never happened — so the Gregorian 15th *is* the Julian 5th.
     */
    @Test
    fun `the ten days that never happened`() {
        val at = julian(1582, 10, 15)
        assertEquals(1582, at.year)
        assertEquals(10, at.month)
        assertEquals(5, at.day)
        assertEquals(10, JulianCalendar.driftDays(1582, 10, 15))
    }

    /**
     * And Britain's, which was eleven days by 1752 — the "give us our
     * eleven days" of the pamphlets. Wednesday 2 September was followed
     * by Thursday 14 September.
     */
    @Test
    fun `and the eleven Britain lost`() {
        assertEquals(11, JulianCalendar.driftDays(1752, 9, 14))
        val at = julian(1752, 9, 14)
        assertEquals(3, at.day)
        assertEquals(9, at.month)
    }

    /**
     * Thirteen days, now, which is the number everybody knows — and the
     * two centuries either side of it, which are the part worth computing
     * rather than writing down. 1900 was a leap year in the Julian
     * calendar and not in ours; so was 2100 and so will 2200 be.
     */
    @Test
    fun `thirteen days today, twelve before nineteen hundred, fourteen after twenty-one`() {
        assertEquals(12, JulianCalendar.driftDays(1900, 2, 28))
        assertEquals(13, JulianCalendar.driftDays(1900, 3, 14))
        assertEquals(13, JulianCalendar.driftDays(2026, 8, 28))
        // 2100 is a leap year for the Julian calendar and not for ours,
        // so its 29th of February is a real day — the Gregorian 14th of
        // March — and the gap does not widen until the day after it.
        assertEquals(13, JulianCalendar.driftDays(2100, 3, 14))
        assertEquals(14, JulianCalendar.driftDays(2100, 3, 15))
        assertEquals(14, JulianCalendar.driftDays(2200, 3, 15))
    }

    /** Today's date, spelled out, since that is what goes on the plate. */
    @Test
    fun `the twenty-eighth of August is the fifteenth`() {
        val at = julian(2026, 8, 28)
        assertEquals(2026, at.year)
        assertEquals(8, at.month)
        assertEquals(15, at.day)
    }

    /**
     * The Julian calendar's own leap day exists and ours does not.
     *
     * 1900 was not a leap year for us and was for it, so the Gregorian
     * 1st of March 1900 is the Julian 17th of February — and the Julian
     * 29th of February 1900 is a real day, which is the Gregorian 13th of
     * March.
     */
    @Test
    fun `a leap day only one of the two calendars has`() {
        val first = julian(1900, 3, 1)
        assertEquals(2, first.month)
        assertEquals(17, first.day)
        val leap = julian(1900, 3, 13)
        assertEquals(2, leap.month)
        assertEquals(29, leap.day)
    }

    /** And the day number itself runs forward one at a time, with no gaps. */
    @Test
    fun `every day is one more than the day before it`() {
        var previous = JulianCalendar.jdnOfGregorian(2023, 12, 31)
        for (day in 1..31) {
            val now = JulianCalendar.jdnOfGregorian(2024, 1, day)
            assertEquals("the year turned and the count did not", previous + 1, now)
            previous = now
        }
        // Across a leap day our calendar has, in a year the other's rule
        // and ours agree on.
        assertEquals(
            JulianCalendar.jdnOfGregorian(2024, 2, 28) + 1,
            JulianCalendar.jdnOfGregorian(2024, 2, 29)
        )
    }
}
