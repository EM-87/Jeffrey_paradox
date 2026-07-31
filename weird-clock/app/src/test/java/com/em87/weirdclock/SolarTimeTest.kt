package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Sundial mode. There is no closed form to compare against here, so what is
 * checked is the physics: four minutes of clock per degree of longitude, an
 * equation of time that stays inside its ±16 minute envelope, and a solar
 * noon that lands near midday.
 */
class SolarTimeTest {

    private fun utc(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private val midsummer = utc(2026, 6, 21)

    /** The offset with the local zone's own shift taken back out. */
    private fun solarVsUtc(longitude: Double, now: Long): Long =
        SolarTime.offsetMs(longitude, now) + TimeZone.getDefault().getOffset(now)

    @Test
    fun `a degree of longitude is four minutes of sun`() {
        val atZero = solarVsUtc(0.0, midsummer)
        val atFifteen = solarVsUtc(15.0, midsummer)
        // Fifteen degrees is one hour, to the second — the equation of time
        // is the same on both and cancels out of the difference.
        assertTrue(abs((atFifteen - atZero) - 3_600_000L) < 1000L)
    }

    @Test
    fun `east is ahead and west is behind`() {
        assertTrue(solarVsUtc(30.0, midsummer) > solarVsUtc(0.0, midsummer))
        assertTrue(solarVsUtc(-30.0, midsummer) < solarVsUtc(0.0, midsummer))
    }

    @Test
    fun `longitude moves the sun the same way all year`() {
        for (month in 1..12) {
            val now = utc(2026, month, 15)
            assertTrue(
                "month $month",
                solarVsUtc(10.0, now) > solarVsUtc(-10.0, now)
            )
        }
    }

    @Test
    fun `the equation of time stays inside sixteen minutes, all year`() {
        // On the Greenwich meridian the whole offset is the equation of
        // time. Nothing in the orbit takes it past about a quarter of an
        // hour; a wilder number means the approximation has gone wrong.
        for (dayOfYear in 1..365) {
            val now = utc(2026, 1, 1) + (dayOfYear - 1) * 86_400_000L
            val eot = solarVsUtc(0.0, now)
            assertTrue(
                "day $dayOfYear gave ${eot / 60000.0} min",
                abs(eot) <= 17 * 60_000L
            )
        }
    }

    @Test
    fun `the equation of time really does swing both ways`() {
        // Around mid-February the sun is a quarter of an hour behind the
        // clock, around early November a quarter of an hour ahead. A
        // constant would pass the envelope test above; this one it fails.
        val year = (1..365).map { solarVsUtc(0.0, utc(2026, 1, 1) + (it - 1) * 86_400_000L) }
        assertTrue("never runs slow", year.min() < -10 * 60_000L)
        assertTrue("never runs fast", year.max() > 10 * 60_000L)
    }

    @Test
    fun `solar noon on the prime meridian lands within a quarter hour of midday`() {
        for (month in 1..12) {
            val now = utc(2026, month, 15)
            val solar = now + SolarTime.offsetMs(0.0, now) +
                TimeZone.getDefault().getOffset(now)
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                .apply { timeInMillis = solar }
            val minutesFromNoon =
                (cal.get(Calendar.HOUR_OF_DAY) - 12) * 60 + cal.get(Calendar.MINUTE)
            assertTrue("month $month was $minutesFromNoon min off", abs(minutesFromNoon) <= 17)
        }
    }
}
