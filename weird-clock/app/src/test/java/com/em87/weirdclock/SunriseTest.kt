package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Sunrise and sunset from one location fix.
 *
 * Checked against the physics rather than against a table: the day is
 * longest at midsummer and shortest at midwinter, the two are mirrored
 * across the equator, the equator barely moves all year, and the poles stop
 * having a sunrise at all. An almanac would pin it tighter, but these are
 * the properties a wrong formula breaks, and they hold without a network.
 */
class SunriseTest {

    private fun utcDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    /** Daylight in minutes, wrapping past midnight if it has to. */
    private fun dayLength(lat: Double, lon: Double, ms: Long): Int {
        val (rise, set) = SolarTime.sunriseSunset(lat, lon, ms)!!
        return if (set >= rise) set - rise else set + 1440 - rise
    }

    // Madrid, roughly.
    private val lat = 40.4
    private val lon = -3.7

    @Test
    fun `the sun rises in the morning and sets in the evening`() {
        val (rise, set) = SolarTime.sunriseSunset(lat, lon, utcDay(2026, 3, 21))!!
        assertTrue("sunrise at ${rise / 60}:${rise % 60}", rise in 240..600)
        assertTrue("sunset at ${set / 60}:${set % 60}", set in 900..1320)
        assertTrue(set > rise)
    }

    @Test
    fun `the equinox splits the day nearly in half`() {
        // Twelve hours, give or take the few minutes refraction adds.
        val minutes = dayLength(lat, lon, utcDay(2026, 3, 21))
        assertTrue("equinox day was $minutes min", abs(minutes - 720) < 25)
    }

    @Test
    fun `midsummer is the longest day and midwinter the shortest`() {
        val june = dayLength(lat, lon, utcDay(2026, 6, 21))
        val december = dayLength(lat, lon, utcDay(2026, 12, 21))
        val march = dayLength(lat, lon, utcDay(2026, 3, 21))
        assertTrue("june $june vs march $march", june > march)
        assertTrue("december $december vs march $march", december < march)
        // At this latitude the swing is roughly nine and a half hours.
        assertTrue("swing was ${june - december} min", june - december > 300)
    }

    @Test
    fun `the southern hemisphere has its seasons the other way round`() {
        val juneNorth = dayLength(lat, lon, utcDay(2026, 6, 21))
        val juneSouth = dayLength(-lat, lon, utcDay(2026, 6, 21))
        assertTrue(juneNorth > 720)
        assertTrue(juneSouth < 720)
        // Mirrored latitudes split the day between them.
        assertTrue(abs((juneNorth + juneSouth) - 1440) < 40)
    }

    @Test
    fun `the equator keeps the same day all year`() {
        val lengths = (1..12).map { dayLength(0.5, lon, utcDay(2026, it, 15)) }
        assertTrue("spread ${lengths.max() - lengths.min()}", lengths.max() - lengths.min() < 30)
    }

    @Test
    fun `the pole loses its sunrise in season`() {
        // Above the Arctic circle in June the sun does not set, and in
        // December it does not rise; either way there is nothing to report.
        assertNull(SolarTime.sunriseSunset(78.0, 15.0, utcDay(2026, 6, 21)))
        assertNull(SolarTime.sunriseSunset(78.0, 15.0, utcDay(2026, 12, 21)))
        // And in between it behaves like anywhere else.
        assertNotNull(SolarTime.sunriseSunset(78.0, 15.0, utcDay(2026, 9, 21)))
    }

    @Test
    fun `a polar day is called day and a polar night is called night`() {
        assertTrue(SolarTime.isDaylight(78.0, 15.0, utcDay(2026, 6, 21), 3 * 60))
        assertFalse(SolarTime.isDaylight(78.0, 15.0, utcDay(2026, 12, 21), 12 * 60))
    }

    @Test
    fun `noon is daylight and the small hours are not, every month`() {
        for (month in 1..12) {
            val ms = utcDay(2026, month, 15)
            assertTrue("noon in month $month", SolarTime.isDaylight(lat, lon, ms, 13 * 60))
            assertFalse("3am in month $month", SolarTime.isDaylight(lat, lon, ms, 3 * 60))
        }
    }

    @Test
    fun `the minute of sunrise is light and the minute before it is not`() {
        val ms = utcDay(2026, 4, 10)
        val (rise, set) = SolarTime.sunriseSunset(lat, lon, ms)!!
        assertTrue(SolarTime.isDaylight(lat, lon, ms, rise))
        assertFalse(SolarTime.isDaylight(lat, lon, ms, rise - 1))
        assertFalse(SolarTime.isDaylight(lat, lon, ms, set))
        assertTrue(SolarTime.isDaylight(lat, lon, ms, set - 1))
    }

    @Test
    fun `going east moves the whole day earlier`() {
        // Fifteen degrees is an hour of sun, and the zone has not changed.
        val ms = utcDay(2026, 5, 10)
        val (riseHere, _) = SolarTime.sunriseSunset(lat, 0.0, ms)!!
        val (riseEast, _) = SolarTime.sunriseSunset(lat, 15.0, ms)!!
        assertTrue("moved ${riseHere - riseEast} min", abs((riseHere - riseEast) - 60) <= 3)
    }
}
