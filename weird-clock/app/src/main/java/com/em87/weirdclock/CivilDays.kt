package com.em87.weirdclock

/**
 * Calendar days as plain integers: 1 January 1970 is day zero.
 *
 * Written out rather than taken from `LocalDate`, which wants API 26 and
 * this app supports 24. It lived inside [Cycle] until the solar system
 * needed the same arithmetic to say which day an eclipse falls on, and a
 * second copy of a date conversion is how two parts of an app come to
 * disagree about what day it is.
 */
object CivilDays {

    /** Milliseconds in a day, which is exact for civil days by definition. */
    const val DAY_MS = 86_400_000L

    /**
     * A calendar date as days since 1 January 1970.
     *
     * The standard civil-to-days arithmetic: shift the year to start in
     * March so that the leap day falls at the end of it and every other
     * month follows the same pattern, then count.
     */
    fun epochDay(year: Int, month: Int, day: Int): Int {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** And back again, as year, month, day. */
    fun dateOf(epochDay: Int): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }

    /** Which day a wall-clock instant falls on, in the zone it is read in. */
    fun dayOf(nowMs: Long, zoneOffsetMs: Int): Int =
        Math.floorDiv(nowMs + zoneOffsetMs, DAY_MS).toInt()
}
