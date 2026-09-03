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
            val axes = Sundial.levelPlate(Sundial.alignBearing(lat))
            assertEquals(
                "at %02d:00 an aligned dial has moved its own shadow".format(hour),
                engraved,
                Sundial.shadowOnPlate(
                    sun.altitudeDeg, sun.azimuthDeg, axes[0], axes[1], axes[2], lat
                )!!,
                0.6
            )
            checked++
        }
        assertTrue("no hour of the day was checked", checked >= 8)
    }

    /**
     * And turning the phone moves the shadow off its hour line, the other
     * way from the turn, which is what makes the alignment worth finding.
     *
     * Not degree for degree, which is what this used to say. The
     * engraving is fixed to the plate and so is the style — turn the
     * plate and the style stops pointing at the pole, so the shadow moves
     * too, by less. See [SundialTiltTest], which has the whole of it.
     */
    @Test
    fun `turning the phone moves the shadow the other way`() {
        val lat = 40.4
        val at = noonish()
        val sun = SolarTime.position(lat, 0.0, at)
        fun shadowAt(bearing: Double): Double {
            val axes = Sundial.levelPlate(bearing)
            return Sundial.shadowOnPlate(
                sun.altitudeDeg, sun.azimuthDeg, axes[0], axes[1], axes[2], lat
            )!!
        }
        val straight = shadowAt(0.0)
        val turned = shadowAt(40.0)
        assertTrue("the shadow went round with the plate", turned < straight)
        assertTrue(
            "the shadow moved further than the plate did",
            kotlin.math.abs(turned - straight) < 40.0
        )
    }

    /**
     * Only one heading reads the right hour all day.
     *
     * That is the sharp form of what aligning a dial means, and it is
     * worth stating that way because the loose form is not true: a plate
     * turned right round reads the right hour at some moment of some day
     * the way a stopped clock does — this very latitude, turned a quarter
     * turn, is within a degree of right at ten in the morning. What no
     * other heading can do is be right at *every* hour, and that is the
     * whole reason a dialist spends the afternoon on one line.
     */
    @Test
    fun `only the meridian reads the right hour all day`() {
        val lat = 40.4
        fun worstErrorAt(bearing: Double): Double {
            var worst = 0.0
            val axes = Sundial.levelPlate(bearing)
            for (hour in 8..16) {
                val at = java.util.Calendar.getInstance().apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                    set(2026, java.util.Calendar.JUNE, 21, hour, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val sun = SolarTime.position(lat, 0.0, at)
                if (sun.altitudeDeg <= 5.0) continue
                val engraved = Sundial.lineAngle(
                    Sundial.Kind.HORIZONTAL, lat, SolarTime.hourAngleDeg(0.0, at) / 15.0
                )
                val shadow = Sundial.shadowOnPlate(
                    sun.altitudeDeg, sun.azimuthDeg, axes[0], axes[1], axes[2], lat
                ) ?: continue
                var off = shadow - engraved
                while (off > 180.0) off -= 360.0
                while (off <= -180.0) off += 360.0
                worst = maxOf(worst, kotlin.math.abs(off))
            }
            return worst
        }
        assertTrue(
            "an aligned dial is wrong somewhere in the day: ${worstErrorAt(0.0)}°",
            worstErrorAt(0.0) < 1.0
        )
        for (bearing in listOf(20.0, 45.0, 90.0, 150.0, 250.0, 320.0)) {
            assertTrue(
                "a plate at $bearing° read the right hour all day",
                worstErrorAt(bearing) > Sundial.ALIGNED_DEGREES
            )
        }
    }

    /** Ten in the morning at midsummer, so there is plenty of sun. */
    private fun noonish(): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 10, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Only the plate you can lay flat is aligned by turning a phone. */
    @Test
    fun `a wall dial cannot be pointed`() {
        assertTrue(Sundial.pointable(Sundial.Kind.HORIZONTAL))
        assertFalse(Sundial.pointable(Sundial.Kind.VERTICAL))
        assertFalse(Sundial.pointable(Sundial.Kind.EQUATORIAL))
    }
}
