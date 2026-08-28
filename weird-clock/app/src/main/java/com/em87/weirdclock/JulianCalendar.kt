package com.em87.weirdclock

/**
 * The calendar the dial was cut for.
 *
 * Every sundial older than 1582 was made under this one, and a good many
 * made after it went on being read under it — Britain did not change until
 * 1752, Russia until 1918, and the Orthodox churches never did. It is the
 * same twelve months, with one difference that matters: a leap year every
 * fourth year without exception, where ours skips three of them every four
 * hundred years.
 *
 * That one difference is why the two are thirteen days apart today and why
 * the gap grows: the Julian year is eleven minutes too long, which is a day
 * every hundred and twenty-eight years. Ours has the same fault a hundred
 * times smaller.
 *
 * Offered on the sundial and nowhere else. It is not a better calendar and
 * it is not being proposed as one; it is what was cut into the plate, and
 * this face is the one whose owner came for that.
 */
object JulianCalendar {

    /** One date, in whichever calendar it was read out of. */
    class Date(val year: Int, val month: Int, val day: Int)

    /**
     * The Julian Day Number for a Gregorian date.
     *
     * The standard integer form, which works for any date either calendar
     * can express and needs no floating point — the whole point of a day
     * number is that it is a count of days and nothing else.
     */
    fun jdnOfGregorian(year: Int, month: Int, day: Int): Long {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return day.toLong() + (153L * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045
    }

    /** And the Julian-calendar date that day number falls on. */
    fun julianOf(jdn: Long): Date {
        val c = jdn + 32082
        val d = (4 * c + 3) / 1461
        val e = c - 1461 * d / 4
        val m = (5 * e + 2) / 153
        return Date(
            year = (d - 4800 + m / 10).toInt(),
            month = (m + 3 - 12 * (m / 10)).toInt(),
            day = (e - (153 * m + 2) / 5 + 1).toInt()
        )
    }

    /** The whole trip, for a date off a [java.util.Calendar]. */
    fun of(year: Int, month: Int, day: Int): Date =
        julianOf(jdnOfGregorian(year, month, day))

    /**
     * How many days behind the Gregorian calendar the Julian one is on a
     * given date.
     *
     * Thirteen for the whole of the twentieth and twenty-first centuries,
     * which is the number everybody knows; twelve before 1900 and fourteen
     * after 2100, which is the part that makes it worth computing rather
     * than writing down.
     */
    fun driftDays(year: Int, month: Int, day: Int): Int {
        val jdn = jdnOfGregorian(year, month, day)
        val julian = julianOf(jdn)
        return (jdn - jdnOfGregorian(julian.year, julian.month, julian.day)).toInt()
    }
}
