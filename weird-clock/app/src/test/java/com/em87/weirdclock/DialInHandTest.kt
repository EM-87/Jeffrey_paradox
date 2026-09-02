package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial held in a hand, which is a different instrument from the dial
 * on a table.
 *
 * It used to point at the sun and soften its shadow while you were not
 * pointing at it — a qibla compass with a dial drawn on it, telling you
 * where the sun was, which you can see. What a real dial in a hand does is
 * stay still: the shadow keeps its place in the world, the engraved lines
 * turn with the plate, and the time is right at the one heading where the
 * two agree.
 */
class DialInHandTest {

    /** The meridian, and not the sun. */
    @Test
    fun `the plate is aligned on the meridian`() {
        assertEquals("north is not where a northern dial faces", 0.0, Sundial.alignBearing(40.4), 1e-9)
        assertEquals("a southern dial faces the wrong way", 180.0, Sundial.alignBearing(-33.9), 1e-9)
        // Signed, and the sign is which way to turn.
        assertEquals(30.0, Sundial.offBy(330.0, 0.0), 1e-9)
        assertEquals(-30.0, Sundial.offBy(30.0, 0.0), 1e-9)
    }

    /**
     * Set on the meridian, the dial is the dial it always was.
     *
     * Nothing about the engraving changes when this mode is switched on:
     * at the heading the mark asks for, the shadow is exactly where it
     * would be on a plate bedded in a garden. Checked across a day
     * because "unchanged" is the kind of claim that is easy to get right
     * once and wrong at six in the morning.
     */
    @Test
    fun `pointed north, the dial reads as it always did`() {
        val lat = 40.4
        val lon = -3.7
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2026, java.util.Calendar.JUNE, 21, 0, 0)
        var checked = 0
        for (hour in 7..19) {
            val at = cal.timeInMillis + hour * 3_600_000L
            val sun = SolarTime.position(lat, lon, at)
            if (sun.altitudeDeg <= 5.0) continue
            val hours = SolarTime.hourAngleDeg(lon, at) / 15.0
            val engraved = Sundial.shadowAngle(
                Sundial.Kind.HORIZONTAL, lat, hours, sun.altitudeDeg,
                SolarTime.declinationDeg(at)
            ) ?: continue
            assertEquals(
                "at %02d:00 an aligned dial has moved its own shadow".format(hour),
                engraved,
                Sundial.shadowInHand(engraved, Sundial.alignBearing(lat), lat),
                1e-9
            )
            checked++
        }
        assertTrue("no hour of the day was checked", checked >= 8)
    }

    /**
     * And turning the phone turns the shadow under the plate, degree for
     * degree, which is what makes the alignment worth finding.
     *
     * The other way about from the phone: the engraving is fixed to the
     * plate and the shadow is fixed to the world, so on a screen where
     * the plate is what stands still, the shadow is what moves.
     */
    @Test
    fun `turning the phone turns the shadow the other way`() {
        val engraved = 25.0
        val straight = Sundial.shadowInHand(engraved, 0.0, 40.4)
        val turned = Sundial.shadowInHand(engraved, 40.0, 40.4)
        assertEquals("an aligned dial is not showing its own hour", engraved, straight, 1e-9)
        assertEquals("the shadow went round with the plate", -40.0, turned - straight, 1e-9)
    }

    /**
     * A dial pointed the wrong way tells the wrong time, and says so by
     * doing it rather than by fading.
     */
    @Test
    fun `a plate off the meridian points at the wrong hour`() {
        val engraved = 25.0
        val off = Sundial.shadowInHand(engraved, 90.0, 40.4)
        assertTrue(
            "a plate a quarter turn out is still reading the right hour",
            kotlin.math.abs(off - engraved) > Sundial.ALIGNED_DEGREES
        )
    }

    /** Only the plate you can lay flat is aligned by turning a phone. */
    @Test
    fun `a wall dial cannot be pointed`() {
        assertTrue(Sundial.pointable(Sundial.Kind.HORIZONTAL))
        assertFalse(Sundial.pointable(Sundial.Kind.VERTICAL))
        assertFalse(Sundial.pointable(Sundial.Kind.EQUATORIAL))
    }
}
