package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The one Sun and the one Moon, held against JPL Horizons.
 *
 * Every number here was asked of Horizons (ssd.jpl.nasa.gov/api/horizons
 * .api, observer table, geocentric, quantity 31: apparent ecliptic
 * longitude of date, times in UT) and copied, not worked out. The
 * instants are the ones that matter to a clock: the March equinoxes of
 * 1000, 1582 and 2026, where the Sun's longitude is nought by definition;
 * the eclipses of −584, 1715 and 1999, where the Moon stands on the Sun;
 * and the new and full moons of 2026, which the dial draws.
 *
 * Dates before the 15th of October 1582 are in the Julian calendar, as
 * Horizons writes them and as [AstroCalendar] reads them.
 */
class EphemerisTest {

    /** An instant from a date in the calendar in force, UT. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        AstroCalendar.msOf(year, month, day) + ((hour * 60L + minute) * 60L + second) * 1000L

    private fun apart(a: Double, b: Double): Double = abs(Orrery.wrapSigned(a - b))

    // ------------------------------------------------------------- the Sun

    /**
     * Horizons: the Sun's longitude crosses nought between these hours.
     * Interpolated to the minute from the two readings either side.
     */
    @Test
    fun `the March equinox falls where Horizons puts it, in three centuries`() {
        // 1000-Mar-14 23:00 359.9925657, 00:00 0.0335530 → 23:11.
        // 1582-Mar-10 23:00 359.9618018, 00:00 0.0030129 → 23:56.
        // 2026-Mar-20 14:00 359.9682634, 15:00 0.0096802 → 14:46.
        // A hundredth of a degree is a quarter of an hour of the Sun's
        // travel; the orbit is good to a hundredth. With the short table
        // of elements the year 1000 read 0.065° short and 1582 0.035°,
        // from a frozen eccentricity and perihelion; the long table and ΔT
        // brought both under a hundredth.
        val equinoxes = listOf(
            Triple("1000", at(1000, 3, 14, 23, 11), 0.03),
            Triple("1582", at(1582, 3, 10, 23, 56), 0.03),
            Triple("2026", at(2026, 3, 20, 14, 46), 0.03)
        )
        for ((name, ms, within) in equinoxes) {
            val lon = Ephemeris.sunLongitudeOfDate(ms)
            println("EPHEMERIS equinox $name: the Sun reads %.4f°".format(Orrery.wrapSigned(lon)))
            assertTrue("the Sun at the equinox of $name reads $lon°", apart(lon, 0.0) < within)
            assertEquals("the year read off the instant", name.toInt(), AstroCalendar.dateAt(ms).year)
        }
    }

    /** Horizons' Sun, at the instants the Moon is also checked at below. */
    @Test
    fun `the Sun stands where Horizons has it at the anchors`() {
        val anchors = listOf(
            // The eclipse of Thales: 19:28:50 TD less the Canon's 18384 s
            // of ΔT is 14:22:26 UT, and Horizons was asked for that UT.
            // The short table's Earth read 0.29° off here, twenty-six
            // centuries from home; the long table's reads 0.009°.
            Triple("-584-05-28 14:22:26", at(-584, 5, 28, 14, 22, 26), 59.7254464),
            Triple("1715-05-03 09:36:20", at(1715, 5, 3, 9, 36, 20), 42.2456452),
            Triple("1999-08-11 11:03:05", at(1999, 8, 11, 11, 3, 5), 138.3497192),
            Triple("J2000", at(2000, 1, 1, 12, 0), 280.3689092),
            Triple("2026-01-18 19:52", at(2026, 1, 18, 19, 52), 298.7321117),
            Triple("2026-07-14 09:43", at(2026, 7, 14, 9, 43), 111.9860466),
            Triple("2026-10-10 15:50", at(2026, 10, 10, 15, 50), 197.3606002)
        )
        for ((name, ms, horizons) in anchors) {
            val lon = Ephemeris.sunLongitudeOfDate(ms)
            val within = if (name.startsWith("-584")) 0.05 else 0.03
            println("EPHEMERIS sun $name: reads %.4f°, Horizons %.4f°, off %.3f°".format(lon, horizons, apart(lon, horizons)))
            assertTrue("$name: the Sun reads $lon°, Horizons $horizons°", apart(lon, horizons) < within)
        }
    }

    /**
     * The frame of date is the frame of J2000 turned by the precession:
     * fourteen degrees by the year 1000, the other way from now.
     */
    @Test
    fun `the equinox of the date slides back a degree and a half a century`() {
        val t = Ephemeris.centuries(at(1000, 3, 14, 23, 11))
        assertEquals(-10.0, t, 0.01)
        assertEquals(-13.97, Ephemeris.precessionDeg(t), 0.05)
        assertEquals(23.4393, Ephemeris.obliquityDeg(0.0), 0.0001)
    }

    /**
     * The equation of time, at its two ends: the sundial is a quarter of
     * an hour slow in February and a quarter fast in November, as every
     * almanac prints.
     */
    @Test
    fun `the equation of time has the almanac's extremes`() {
        val february = Ephemeris.equationOfTimeMinutes(at(2026, 2, 11, 12, 0))
        val november = Ephemeris.equationOfTimeMinutes(at(2026, 11, 3, 12, 0))
        assertEquals(-14.2, february, 0.4)
        assertEquals(16.4, november, 0.4)
    }

    // ------------------------------------------------------------ the Moon

    /**
     * Horizons' Moon, at the eclipses and the phases of 2026. Six terms
     * of the Moon's motion leave a tenth of a degree of its wanderings
     * unmodelled — measured, the worst of these anchors is 0.14°, the
     * eclipse of 1715; with three terms it was 0.51°. One of the three
     * large terms missing is a degree or more; the three small ones are
     * told apart in the year of days below.
     */
    @Test
    fun `the Moon stands where Horizons has it at the anchors`() {
        val anchors = listOf(
            // Five hours of ΔT: without it the Moon reads three degrees
            // short of this, which is the whole of what ΔT is for.
            Triple("-584-05-28 14:22:26 eclipse", at(-584, 5, 28, 14, 22, 26), 59.6656969),
            Triple("1715-05-03 09:36:20 eclipse", at(1715, 5, 3, 9, 36, 20), 42.3164677),
            Triple("1999-08-11 11:03:05 eclipse", at(1999, 8, 11, 11, 3, 5), 138.3008763),
            Triple("J2000", at(2000, 1, 1, 12, 0), 223.3237860),
            Triple("2026-01-03 10:03 full", at(2026, 1, 3, 10, 3), 103.0338214),
            Triple("2026-01-18 19:52 new", at(2026, 1, 18, 19, 52), 298.7322811),
            Triple("2026-02-17 12:01 new", at(2026, 2, 17, 12, 1), 328.8274344),
            Triple("2026-03-19 01:23 new", at(2026, 3, 19, 1, 23), 358.4467449),
            Triple("2026-07-14 09:43 new", at(2026, 7, 14, 9, 43), 111.9800222),
            Triple("2026-07-29 14:36 full", at(2026, 7, 29, 14, 36), 306.5046935),
            Triple("2026-10-10 15:50 new", at(2026, 10, 10, 15, 50), 197.3598690),
            Triple("2026-12-09 00:52 new", at(2026, 12, 9, 0, 52), 256.9477770)
        )
        var worst = 0.0
        for ((name, ms, horizons) in anchors) {
            val lon = Ephemeris.moonLongitudeOfDate(ms)
            val off = apart(lon, horizons)
            println("EPHEMERIS moon $name: reads %.4f°, Horizons %.4f°, off %.3f°".format(lon, horizons, off))
            if (off > worst) worst = off
            assertTrue("$name: the Moon reads $lon°, Horizons $horizons°", off < 0.2)
        }
        println("EPHEMERIS moon worst %.3f°".format(worst))
    }

    /** Horizons' Moon at nought hours UT, every day of three years: JD and degrees. */
    private fun dailyMoon(): List<Pair<Double, Double>> =
        javaClass.classLoader!!.getResourceAsStream("horizons/moon-daily.txt")!!
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.trim().split(Regex("\\s+")).let { it[0].toDouble() to it[1].toDouble() } }

    /** How far the one Moon is from Horizons' over a run of days: the worst, the root mean square, the mean. */
    private fun spread(days: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
        var worst = 0.0
        var squares = 0.0
        var sum = 0.0
        for ((jd, horizons) in days) {
            val ms = Math.round((jd - 2_440_587.5) * 86_400_000.0)
            val off = Orrery.wrapSigned(Ephemeris.moonLongitudeOfDate(ms) - horizons)
            if (abs(off) > worst) worst = abs(off)
            squares += off * off
            sum += off
        }
        return Triple(worst, Math.sqrt(squares / days.size), sum / days.size)
    }

    /**
     * The Moon day by day through three whole years — the year of
     * Thales's eclipse, the year 1000 and 2026 — against Horizons at
     * nought hours each day, eleven hundred readings.
     *
     * A handful of anchors cannot tell a term of a tenth of a degree from
     * the sum of the ones left out, which is as large; a year of days can,
     * because over a year every term goes through all its phases and what
     * is left is the root mean square of the ones not modelled. Measured,
     * with six terms: 0.092° in 2026 and in the year 1000, 0.117° in −584
     * (where the Moon also runs 0.06° ahead on average), the worst single
     * day 0.36°. With the reduction to the ecliptic left out, the
     * smallest of the six, the three read 0.123°, 0.122° and 0.141°; with
     * the annual equation out, 0.158°; with the second term of the ellipse
     * out, 0.176°. The allowance sits between.
     */
    @Test
    fun `the Moon keeps to Horizons day by day through three years`() {
        val days = dailyMoon()
        assertEquals("eleven hundred readings", 1100, days.size)
        val years = listOf(
            Triple("-584", days.filter { it.first < 1_600_000.0 }, 0.129),
            Triple("1000", days.filter { it.first in 2_000_000.0..2_100_000.0 }, 0.107),
            Triple("2026", days.filter { it.first > 2_400_000.0 }, 0.107)
        )
        val wrong = ArrayList<String>()
        for ((name, run, within) in years) {
            assertTrue("a whole year of $name", run.size >= 366)
            val (worst, rms, mean) = spread(run)
            println(
                "EPHEMERIS moon daily $name: rms %.4f°, worst %.4f°, mean %+.4f° over %d days"
                    .format(rms, worst, mean, run.size)
            )
            if (rms >= within) wrong += "$name: ${"%.4f".format(rms)}° rms from Horizons, allowed $within"
            if (worst >= 0.4) wrong += "$name: the worst day is ${"%.4f".format(worst)}° from Horizons"
        }
        assertTrue(wrong.joinToString("; "), wrong.isEmpty())
    }

    /**
     * The new moons and full moons of 2026, as NASA's phase table prints
     * them in UT: the one Moon is new within half an hour at each.
     * Measured: with three terms it was up to an hour and a quarter out
     * (April); with six, seventeen minutes at the worst of 2026 and
     * twenty-five at the eclipse of 1715 — whose printed instant is the
     * middle of the eclipse, not the conjunction.
     */
    @Test
    fun `the new and full moons of 2026 fall within the half hour`() {
        val phases = listOf(
            // And the three eclipses, which are new moons by definition.
            Triple("-584 May 28 eclipse", at(-584, 5, 28, 14, 22, 26), 0.0),
            Triple("1715 May 3 eclipse", at(1715, 5, 3, 9, 36, 20), 0.0),
            Triple("1999 Aug 11 eclipse", at(1999, 8, 11, 11, 3, 5), 0.0),
            Triple("Jan 3 full", at(2026, 1, 3, 10, 3), 0.5),
            Triple("Jan 18 new", at(2026, 1, 18, 19, 52), 0.0),
            Triple("Feb 17 new", at(2026, 2, 17, 12, 1), 0.0),
            Triple("Mar 19 new", at(2026, 3, 19, 1, 23), 0.0),
            Triple("Apr 17 new", at(2026, 4, 17, 11, 52), 0.0),
            Triple("May 16 new", at(2026, 5, 16, 20, 1), 0.0),
            Triple("Jun 15 new", at(2026, 6, 15, 2, 54), 0.0),
            Triple("Jul 14 new", at(2026, 7, 14, 9, 43), 0.0),
            Triple("Jul 29 full", at(2026, 7, 29, 14, 36), 0.5),
            Triple("Aug 12 new", at(2026, 8, 12, 17, 37), 0.0),
            Triple("Sep 11 new", at(2026, 9, 11, 3, 27), 0.0),
            Triple("Oct 10 new", at(2026, 10, 10, 15, 50), 0.0),
            Triple("Nov 9 new", at(2026, 11, 9, 7, 2), 0.0),
            Triple("Dec 9 new", at(2026, 12, 9, 0, 52), 0.0)
        )
        val hour = 1.0 / (29.530588853 * 24.0)
        var worstHours = 0.0
        for ((name, ms, expected) in phases) {
            val phase = Ephemeris.moonPhase(ms)
            val off = abs(Orrery.wrapSigned((phase - expected) * 360.0)) / 360.0
            val hours = off / hour
            println("EPHEMERIS phase $name: off %.2f h".format(hours))
            if (hours > worstHours) worstHours = hours
            assertTrue("$name: the phase reads $phase, ${"%.2f".format(hours)} h off", hours < 0.5)
        }
        println("EPHEMERIS phase worst %.2f h".format(worstHours))
    }
}
