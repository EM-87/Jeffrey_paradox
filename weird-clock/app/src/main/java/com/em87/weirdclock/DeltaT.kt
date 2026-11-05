package com.em87.weirdclock

/**
 * ΔT: how far the clocks have drifted from the turning of the Earth.
 *
 * The orbits run on a uniform time, and civil time runs on the Earth's
 * rotation, which is slowing; the difference is ΔT, about a minute now,
 * half an hour in the year 1000, three hours at the year nought and most
 * of a day five thousand years back. A sky worked out for a civil instant
 * without it puts the Moon a quarter of a turn of its own phase out at
 * the far end of the dial.
 *
 * The polynomials are Espenak and Meeus's, from the NASA eclipse site's
 * "Polynomial Expressions for Delta T" (deltatpoly2004), copied as
 * printed there, range by range, with the decimal year they define: the
 * year plus (month − 0.5)/12. The page's small correction for the Canon's
 * own lunar ephemeris is not applied; it is a second or two and is about
 * that ephemeris, not this one.
 */
object DeltaT {

    /** The decimal year the polynomials take: the year, plus the middle of the month. */
    fun decimalYear(year: Int, month: Int): Double = year + (month - 0.5) / 12.0

    /** ΔT in seconds, TT − UT, at a decimal year. */
    fun seconds(y: Double): Double = when {
        y < -500 -> {
            val u = (y - 1820) / 100
            -20 + 32 * u * u
        }
        y < 500 -> {
            val u = y / 100
            10583.6 - 1014.41 * u + 33.78311 * u * u - 5.952053 * u * u * u -
                0.1798452 * u * u * u * u + 0.022174192 * u * u * u * u * u +
                0.0090316521 * u * u * u * u * u * u
        }
        y < 1600 -> {
            val u = (y - 1000) / 100
            1574.2 - 556.01 * u + 71.23472 * u * u + 0.319781 * u * u * u -
                0.8503463 * u * u * u * u - 0.005050998 * u * u * u * u * u +
                0.0083572073 * u * u * u * u * u * u
        }
        y < 1700 -> {
            val t = y - 1600
            120 - 0.9808 * t - 0.01532 * t * t + t * t * t / 7129
        }
        y < 1800 -> {
            val t = y - 1700
            8.83 + 0.1603 * t - 0.0059285 * t * t + 0.00013336 * t * t * t - t * t * t * t / 1174000
        }
        y < 1860 -> {
            val t = y - 1800
            13.72 - 0.332447 * t + 0.0068612 * t * t + 0.0041116 * t * t * t -
                0.00037436 * t * t * t * t + 0.0000121272 * t * t * t * t * t -
                0.0000001699 * t * t * t * t * t * t + 0.000000000875 * t * t * t * t * t * t * t
        }
        y < 1900 -> {
            val t = y - 1860
            7.62 + 0.5737 * t - 0.251754 * t * t + 0.01680668 * t * t * t -
                0.0004473624 * t * t * t * t + t * t * t * t * t / 233174
        }
        y < 1920 -> {
            val t = y - 1900
            -2.79 + 1.494119 * t - 0.0598939 * t * t + 0.0061966 * t * t * t - 0.000197 * t * t * t * t
        }
        y < 1941 -> {
            val t = y - 1920
            21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t
        }
        y < 1961 -> {
            val t = y - 1950
            29.07 + 0.407 * t - t * t / 233 + t * t * t / 2547
        }
        y < 1986 -> {
            val t = y - 1975
            45.45 + 1.067 * t - t * t / 260 - t * t * t / 718
        }
        y < 2005 -> {
            val t = y - 2000
            63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t +
                0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t
        }
        y < 2050 -> {
            val t = y - 2000
            62.92 + 0.32217 * t + 0.005589 * t * t
        }
        y < 2150 -> {
            val u = (y - 1820) / 100
            -20 + 32 * u * u - 0.5628 * (2150 - y)
        }
        else -> {
            val u = (y - 1820) / 100
            -20 + 32 * u * u
        }
    }

    /** ΔT in seconds at a civil instant, by the calendar in force. */
    fun secondsAt(atMs: Long): Double {
        val date = AstroCalendar.dateAt(atMs)
        return seconds(decimalYear(date.year, date.month))
    }
}
