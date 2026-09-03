package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial held in a hand rather than set in a garden.
 *
 * The compass mode used to answer with a plate that was level and merely
 * turned, which is most of the way there and is not what a hand does: a
 * phone sits at twenty or thirty degrees, and a tilted plate carries its
 * style with it, so the style stops being parallel to the earth's axis and
 * the shadow stops falling on the hour lines. Reported as the whole thing
 * feeling artificial, which it was — the only thing moving was a rotation.
 *
 * The first test here is the theorem the entire instrument rests on: laid
 * level and pointed at the pole, the shadow of the style falls exactly on
 * the engraved line for the hour. If that one holds, the machinery is
 * right and everything else is the same machinery being tilted.
 */
class SundialTiltTest {

    private val east = doubleArrayOf(1.0, 0.0, 0.0)
    private val north = doubleArrayOf(0.0, 1.0, 0.0)
    private val up = doubleArrayOf(0.0, 0.0, 1.0)

    /** The sun at a place and an instant, as this app works it out. */
    private fun sunAt(lat: Double, lon: Double, atMs: Long): SolarTime.Position =
        SolarTime.position(lat, lon, atMs)

    /** Midsummer, so the sun is high and every hour of the day is lit. */
    private fun utc(hour: Int, minute: Int = 0): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, hour, minute, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Brings an angle into −180…180 so two of them can be compared. */
    private fun wrap(deg: Double): Double {
        var d = deg
        while (d > 180.0) d -= 360.0
        while (d <= -180.0) d += 360.0
        return d
    }

    /**
     * Level and pointed north, the style's shadow is the hour line.
     *
     * This is the whole theory of a sundial and it is worth stating as an
     * assertion: the engraving is not a drawing of where a shadow happens
     * to fall on the day it was made, it is where the shadow falls on
     * every day of the year, which is only true because the style is
     * parallel to the axis the sun goes round.
     *
     * Checked against the sun this app computes rather than against an
     * idealised one, so the two halves of the app have to agree as well.
     */
    @Test
    fun `laid level, the shadow falls on the engraved hour line`() {
        val lat = 40.4
        val lon = 0.0
        for (hour in 7..17) {
            val at = utc(hour)
            val sun = sunAt(lat, lon, at)
            if (sun.altitudeDeg < 5.0) continue
            val shadow = Sundial.shadowOnPlate(
                sun.altitudeDeg, sun.azimuthDeg, east, north, up, lat
            )
            assertNotNull("no shadow at $hour with the sun ${sun.altitudeDeg}° up", shadow)
            // The hour the dial is actually reading, from the sun's own
            // hour angle: solar noon is nought.
            val fromNoon = SolarTime.hourAngleDeg(lon, at) / 15.0
            val engraved = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, fromNoon)
            assertEquals(
                "at $hour the shadow is off its own hour line",
                0.0, wrap(shadow!! - engraved), 0.6
            )
        }
    }

    /**
     * Turned off the meridian, the shadow leaves its hour line — and by
     * less than the turn.
     *
     * This corrects something. The first version of the compass mode
     * swung the shadow a degree for every degree of turn, on the argument
     * that the shadow belongs to the world and the engraving belongs to
     * the plate. Half of that is true: the engraving does belong to the
     * plate. But so does the *style*, which is screwed to it — turn the
     * plate and the style turns too, stops pointing at the pole, and its
     * shadow moves with it. The honest answer moves the shadow by about a
     * third of the turn at this hour, not by all of it, and it is the
     * reason a dial in the hand feels like an instrument being held
     * rather than a picture being spun.
     */
    @Test
    fun `turning the plate takes the style with it`() {
        val lat = 40.4
        val at = utc(10)
        val sun = sunAt(lat, 0.0, at)
        val fromNoon = SolarTime.hourAngleDeg(0.0, at) / 15.0
        val engraved = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, fromNoon)
        for (turn in listOf(-30.0, 25.0, 60.0)) {
            val a = Math.toRadians(turn)
            // The same level plate, turned clockwise by that much: its
            // own axes swing round the vertical.
            val right = doubleArrayOf(Math.cos(a), -Math.sin(a), 0.0)
            val top = doubleArrayOf(Math.sin(a), Math.cos(a), 0.0)
            val shadow = Sundial.shadowOnPlate(
                sun.altitudeDeg, sun.azimuthDeg, right, top, up, lat
            )!!
            val moved = wrap(shadow - engraved)
            assertTrue(
                "turned $turn°, the shadow stayed on its hour line",
                kotlin.math.abs(moved) > 2.0
            )
            assertTrue(
                "turned $turn°, the shadow moved $moved° — as if the style had stayed put",
                kotlin.math.abs(moved) < kotlin.math.abs(turn)
            )
            // And the other way from the turn, which is what makes the
            // dial readable: turn the plate clockwise and the hour lines
            // come round to meet the shadow.
            assertTrue(
                "turned $turn°, the shadow went with the plate",
                moved * turn < 0.0
            )
        }
    }

    /**
     * Tilted, it moves — which is the whole of what was asked for.
     *
     * A plate tipped towards the sun has a shorter, differently placed
     * shadow than a level one, and a phone in a hand is tipped by twenty
     * or thirty degrees the whole time.
     */
    @Test
    fun `tilting the plate moves the shadow as well`() {
        val lat = 40.4
        val at = utc(10)
        val sun = sunAt(lat, 0.0, at)
        val level = Sundial.shadowOnPlate(
            sun.altitudeDeg, sun.azimuthDeg, east, north, up, lat
        )!!
        // The top edge lifted by twenty degrees: the plate leans back,
        // so its normal leans away from north.
        val p = Math.toRadians(20.0)
        val top = doubleArrayOf(0.0, Math.cos(p), Math.sin(p))
        val normal = doubleArrayOf(0.0, -Math.sin(p), Math.cos(p))
        val tilted = Sundial.shadowOnPlate(
            sun.altitudeDeg, sun.azimuthDeg, east, top, normal, lat
        )!!
        assertTrue(
            "twenty degrees of tilt changed nothing: $level then $tilted",
            kotlin.math.abs(wrap(tilted - level)) > 2.0
        )
    }

    /** And a plate turned away from the sun has nothing on it at all. */
    @Test
    fun `a plate facing away from the sun has no shadow`() {
        val lat = 40.4
        val sun = sunAt(lat, 0.0, utc(10))
        // Face down.
        assertNull(
            Sundial.shadowOnPlate(
                sun.altitudeDeg, sun.azimuthDeg,
                east, north, doubleArrayOf(0.0, 0.0, -1.0), lat
            )
        )
        // And edge on to the light, where the divisor runs out.
        assertNull(
            Sundial.shadowOnPlate(
                20.0, 180.0, east, doubleArrayOf(0.0, 0.0, 1.0),
                doubleArrayOf(0.0, 1.0, 0.0), lat
            )
        )
    }
}
