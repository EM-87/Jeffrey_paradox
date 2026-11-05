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
        // travel; the orbit is good to a few hundredths near now. In the
        // year 1000 it reads 0.065° short and in 1582 0.035°: the elements
        // are the short table's, with the eccentricity and the perihelion
        // frozen at their J2000 values, and the centuries of their drift
        // are most of that. The long table tightens both to the same
        // three hundredths; ΔT, a quarter of an hour of the Sun in the
        // year 1000, the rest.
        val equinoxes = listOf(
            Triple("1000", at(1000, 3, 14, 23, 11), 0.1),
            Triple("1582", at(1582, 3, 10, 23, 56), 0.05),
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
            Triple("1715-05-03 09:36:20", at(1715, 5, 3, 9, 36, 20), 42.2456452),
            Triple("1999-08-11 11:03:05", at(1999, 8, 11, 11, 3, 5), 138.3497192),
            Triple("J2000", at(2000, 1, 1, 12, 0), 280.3689092),
            Triple("2026-01-18 19:52", at(2026, 1, 18, 19, 52), 298.7321117),
            Triple("2026-07-14 09:43", at(2026, 7, 14, 9, 43), 111.9860466),
            Triple("2026-10-10 15:50", at(2026, 10, 10, 15, 50), 197.3606002)
        )
        for ((name, ms, horizons) in anchors) {
            val lon = Ephemeris.sunLongitudeOfDate(ms)
            assertTrue("$name: the Sun reads $lon°, Horizons $horizons°", apart(lon, horizons) < 0.03)
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
     * Horizons' Moon, at the eclipses and the phases of 2026. Three terms
     * of the Moon's motion leave a third of a degree of its wanderings
     * unmodelled, which is three quarters of an hour of phase.
     */
    @Test
    fun `the Moon stands where Horizons has it at the anchors`() {
        val anchors = listOf(
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
            assertTrue("$name: the Moon reads $lon°, Horizons $horizons°", off < 0.5)
        }
        println("EPHEMERIS moon worst %.3f°".format(worst))
    }

    /**
     * The new moons and full moons of 2026, as NASA's phase table prints
     * them in UT: the one Moon is new within an hour and a half at each.
     * Measured: three terms leave it up to an hour and a quarter out
     * (April); the next three — 0.21° sin 2M′, 0.19° sin M, 0.11° sin 2F
     * — would bring that under a quarter of an hour.
     */
    @Test
    fun `the new and full moons of 2026 fall within the hour`() {
        val phases = listOf(
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
            assertTrue("$name: the phase reads $phase, ${"%.1f".format(hours)} h off", hours < 1.5)
        }
        println("EPHEMERIS phase worst %.2f h".format(worstHours))
    }
}
