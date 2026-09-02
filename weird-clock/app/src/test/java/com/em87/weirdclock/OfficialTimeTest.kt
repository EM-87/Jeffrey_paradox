package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other line: the meridian your clock is actually keeping.
 *
 * The red one is your longitude, so the hour it points at is the sun over
 * your own head — apparent solar time, which is what this face has always
 * read and which nobody's clock says. A time zone is a promise that
 * everybody inside it will use the sun of one line of longitude instead,
 * and this is that line.
 */
class OfficialTimeTest {

    /** An hour east is fifteen degrees east, and summer time is an hour. */
    @Test
    fun `a zone is fifteen degrees to the hour`() {
        assertEquals(0.0, Hemisphere.zoneMeridian(0), 1e-9)
        assertEquals(15.0, Hemisphere.zoneMeridian(3_600_000), 1e-9)
        assertEquals(-75.0, Hemisphere.zoneMeridian(-5 * 3_600_000), 1e-9)
        // Half-hour zones exist and are not a special case.
        assertEquals(82.5, Hemisphere.zoneMeridian((5.5 * 3_600_000).toInt()), 1e-6)
        // And the far side of the world comes back inside a turn.
        assertTrue("a zone landed outside a circle", Hemisphere.zoneMeridian(13 * 3_600_000) <= 180.0)
    }

    /**
     * Spain is the case that makes the line worth drawing.
     *
     * It sits on Greenwich's meridian and keeps Berlin's hour, and in
     * summer Athens's — so the blue line stands two whole hours round the
     * disc from the red one, which is the same as saying the clock is two
     * hours ahead of the sun. Drawn, it is one glance; explained, it is a
     * paragraph.
     */
    @Test
    fun `the gap between the two lines is the gap between sun and clock`() {
        val view = Hemisphere.View.NORTH
        val summerInSpain = 2 * 3_600_000
        val here = -3.7
        val zone = Hemisphere.zoneMeridian(summerInSpain)
        // Both read against the same ring: a longitude is an hour.
        val solar = Hemisphere.hourAt(view, here)
        val official = Hemisphere.hourAt(view, zone)
        assertEquals(
            "the clock is not running ahead of the sun by what the zone says",
            (zone - here) / 15.0, official - solar, 1e-9
        )
        assertTrue("Madrid's clock is not ahead of Madrid's sun", official > solar)
    }

    /** And a fractional hour lands between the numerals, as it must. */
    @Test
    fun `the ring can be pointed at a time and not only at an hour`() {
        val view = Hemisphere.View.NORTH
        assertEquals(
            Hemisphere.bearingOfHour(view, 15).toDouble(),
            Hemisphere.bearingOfTime(view, 15.0), 1e-9
        )
        val half = Hemisphere.bearingOfTime(view, 15.5)
        assertEquals("half an hour is not half of fifteen degrees",
            7.5, half - Hemisphere.bearingOfTime(view, 15.0), 1e-9)
        // Which way round is still which way round.
        assertEquals(
            -Hemisphere.bearingOfTime(Hemisphere.View.NORTH, 15.5),
            Hemisphere.bearingOfTime(Hemisphere.View.SOUTH, 15.5), 1e-9
        )
    }
}
