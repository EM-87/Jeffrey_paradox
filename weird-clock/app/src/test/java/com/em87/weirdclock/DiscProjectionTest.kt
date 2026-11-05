package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the projection done once agrees with the projection done every
 * time, for every pixel and any turn of the world.
 *
 * The cached projection exists to make the spinning globe affordable, and
 * a cache that is fast and slightly wrong would show as a world that
 * jumps when it is let go of — the exact symptom it was built to cure.
 * So the agreement is exact, not approximate.
 */
class DiscProjectionTest {

    private fun slowU(view: Hemisphere.View, x: Double, y: Double, turn: Double): Double? {
        val place = Hemisphere.unproject(view, x, y, turn) ?: return null
        return ((place[1] + 180.0) / 360.0).coerceIn(0.0, 0.9999)
    }

    private fun slowV(view: Hemisphere.View, x: Double, y: Double): Double? {
        val place = Hemisphere.unproject(view, x, y, 0.0) ?: return null
        return ((90.0 - place[0]) / 180.0).coerceIn(0.0, 0.9999)
    }

    @Test
    fun `the cached projection is the slow projection, for every pixel and every turn`() {
        val size = 96
        val half = size / 2.0
        for (view in Hemisphere.View.entries) {
            val disc = DiscProjection(view, size)
            for (turn in listOf(0.0, 1.5, -6.0, 47.25, 180.0, 359.0, -200.0, 720.5)) {
                var checked = 0
                for (py in 0 until size) {
                    val y = (py + 0.5 - half) / half
                    for (px in 0 until size) {
                        val x = (px + 0.5 - half) / half
                        val at = py * size + px
                        val u = slowU(view, x, y, turn)
                        if (u == null) {
                            assertFalse("$view ($px,$py) is off the world", disc.onWorld(at))
                            continue
                        }
                        assertTrue("$view ($px,$py) is on the world", disc.onWorld(at))
                        checked++
                        // The map is 1024 pixels wide at most, so agreement to
                        // a hundred-thousandth of it is agreement to the pixel
                        // with plenty to spare; the two differ only in float
                        // against double.
                        assertEquals(
                            "$view ($px,$py) turned $turn: longitude",
                            u, disc.u(at, turn), 2e-5
                        )
                        assertEquals(
                            "$view ($px,$py): latitude",
                            slowV(view, x, y)!!, disc.v[at].toDouble(), 2e-5
                        )
                    }
                }
                assertTrue("$view: nothing on the world was checked", checked > size * size / 2)
            }
        }
    }

    /**
     * One answer serves every view looking the same way: the widget's
     * throwaway views and the app's own.
     */
    @Test
    fun `the same view twice is one projection`() {
        val first = DiscProjection.of(Hemisphere.View.NORTH, 48)
        val built = DiscProjection.built
        assertTrue(first === DiscProjection.of(Hemisphere.View.NORTH, 48))
        assertEquals(built, DiscProjection.built)
        val other = DiscProjection.of(Hemisphere.View.SOUTH, 48)
        assertFalse(first === other)
        assertEquals(built + 1, DiscProjection.built)
    }

    /** A pixel's distance from the middle is kept too, for the veil's edge. */
    @Test
    fun `and how far out each pixel is`() {
        val disc = DiscProjection(Hemisphere.View.GLOBE, 64)
        val half = 32.0
        val at = 10 * 64 + 50
        val x = (50 + 0.5 - half) / half
        val y = (10 + 0.5 - half) / half
        assertEquals(kotlin.math.hypot(x, y), disc.rho[at].toDouble(), 1e-6)
    }
}
