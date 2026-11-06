package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ΔT, held against the values NASA's Five Millennium Canon prints beside
 * its eclipses — the same source as the polynomials, so what is checked
 * is the copying — and against itself where one range hands over to the
 * next.
 */
class DeltaTTest {

    /** The Canon's own small correction for its lunar ephemeris, so its values can be compared. */
    private fun canon(y: Double): Double = DeltaT.seconds(y) - 0.000012932 * (y - 1955) * (y - 1955)

    @Test
    fun `the Canon's values at three eclipses`() {
        // 1999 Aug 11: ΔT 64 s. 1715 May 03: 10 s. −584 May 28: 18384 s.
        assertEquals(64.0, canon(DeltaT.decimalYear(1999, 8)), 1.0)
        assertEquals(10.0, canon(DeltaT.decimalYear(1715, 5)), 1.0)
        assertEquals(18384.0, canon(DeltaT.decimalYear(-584, 5)), 20.0)
        // And the shape of it: half an hour in the year 1000, three hours
        // at the year nought.
        assertEquals(1574.0, DeltaT.seconds(1000.0), 5.0)
        assertEquals(10584.0, DeltaT.seconds(0.0), 5.0)
    }

    /** Each range meets the next without a jump worth a second of the Sun. */
    @Test
    fun `the ranges join up`() {
        for (border in listOf(-500.0, 500.0, 1600.0, 1700.0, 1800.0, 1860.0, 1900.0, 1920.0, 1941.0, 1961.0, 1986.0, 2005.0, 2050.0, 2150.0)) {
            val before = DeltaT.seconds(border - 0.001)
            val after = DeltaT.seconds(border + 0.001)
            assertTrue("a jump of ${after - before} s at $border", abs(after - before) < 15.0)
        }
    }

    /** The decimal year is the middle of the month, as the page defines it. */
    @Test
    fun `the decimal year is the middle of the month`() {
        assertEquals(2026.0 + 0.5 / 12.0, DeltaT.decimalYear(2026, 1), 1e-9)
        assertEquals(2026.0 + 11.5 / 12.0, DeltaT.decimalYear(2026, 12), 1e-9)
        // And at an instant, through the calendar in force: the eclipse
        // of −584 is in May of the astronomical year −584.
        val may = AstroCalendar.msOf(-584, 5, 28)
        assertEquals(DeltaT.seconds(DeltaT.decimalYear(-584, 5)), DeltaT.secondsAt(may), 1e-9)
    }
}
