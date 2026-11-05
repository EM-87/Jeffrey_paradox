package com.em87.weirdclock

/**
 * Dates the way astronomers write them, independent of the phone.
 *
 * One convention for every instant the sky can be wound to: the Gregorian
 * calendar from the 15th of October 1582, the day it began, and the Julian
 * calendar for everything before, with the years counted straight through
 * nought — the year before 1 is 0, the one before that −1, so that 585 BC
 * is −584 and the arithmetic of leap years holds without a gap. It is the
 * convention of the ephemerides and the eclipse canons, and so of the
 * anchors the sky is tested against.
 *
 * Not the phone's. A `Calendar` does switch at 1582 by default, but not on
 * every locale — a phone set to a Buddhist or a Japanese calendar reports
 * other years entirely — and it counts BC years from one, backwards. The
 * sky's date is a fact about the sky, so it is worked out here from the
 * Julian day number and nothing else.
 *
 * The day numbers are [JulianCalendar]'s, which this is the other half of:
 * that one answers "which Julian date is this Gregorian one", for a stone
 * sundial; this one answers "which calendar is in force at this instant".
 */
object AstroCalendar {

    /** A date in the calendar in force on its day. */
    class Date(val year: Int, val month: Int, val day: Int, val julian: Boolean)

    /** The first Gregorian day, as a Julian day number: 1582-10-15. */
    const val GREGORIAN_FROM_JDN = 2_299_161L

    /** Milliseconds from the Unix epoch to the start of Julian day 0, which is noon. */
    private const val EPOCH_JDN = 2_440_588L

    const val DAY_MS = 86_400_000L

    /** The Julian day number of the civil day an instant falls on, UTC. */
    fun jdnOf(atMs: Long): Long = Math.floorDiv(atMs, DAY_MS) + EPOCH_JDN

    /** And the Julian Day, with its fraction, of an instant: noon is a whole number. */
    fun julianDay(atMs: Long): Double = atMs / DAY_MS.toDouble() + 2_440_587.5

    /** Whether the Gregorian calendar is in force on a day. */
    fun isGregorian(jdn: Long): Boolean = jdn >= GREGORIAN_FROM_JDN

    /** The date of a Julian day number, in the calendar in force that day. */
    fun dateOf(jdn: Long): Date {
        if (isGregorian(jdn)) {
            val g = gregorianOf(jdn)
            return Date(g.first, g.second, g.third, julian = false)
        }
        val j = JulianCalendar.julianOf(jdn)
        return Date(j.year, j.month, j.day, julian = true)
    }

    /** The date an instant falls on, UTC, in the calendar in force. */
    fun dateAt(atMs: Long): Date = dateOf(jdnOf(atMs))

    /**
     * The Julian day number of a date written in the calendar in force —
     * Julian before the switch, Gregorian from it. A date that does not
     * exist, such as the 10th of October 1582, is read as Gregorian.
     */
    fun jdnOf(year: Int, month: Int, day: Int): Long {
        val gregorian = JulianCalendar.jdnOfGregorian(year, month, day)
        if (gregorian >= GREGORIAN_FROM_JDN) return gregorian
        return jdnOfJulian(year, month, day)
    }

    /** The Julian day number of a date in the Julian calendar, year nought and all. */
    fun jdnOfJulian(year: Int, month: Int, day: Int): Long {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return day.toLong() + (153L * m + 2) / 5 + 365L * y + Math.floorDiv(y, 4) - 32083
    }

    /** The instant, UTC, at which a day in the calendar in force begins. */
    fun msOf(year: Int, month: Int, day: Int): Long = (jdnOf(year, month, day) - EPOCH_JDN) * DAY_MS

    /** The civil date of a Julian day number in the proleptic Gregorian calendar. */
    private fun gregorianOf(jdn: Long): Triple<Int, Int, Int> {
        val (y, m, d) = CivilDays.dateOf((jdn - EPOCH_JDN).toInt())
        return Triple(y, m, d)
    }
}
