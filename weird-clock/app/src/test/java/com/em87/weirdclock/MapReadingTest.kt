package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A map read between its pixels.
 *
 * The colour of a point of the world used to be the colour of the one
 * pixel of the map it fell in, and on a globe that fills a phone every
 * coast was a staircase. On the two views from over a pole it is now the
 * four pixels round the point, each by how near it is. What is held here
 * is that this is arithmetic and not a blur: exact where it should be,
 * the mean where it should be, one colour never leaking into another,
 * and the map still the whole world, round the back at the date line and
 * ending at the poles.
 */
class MapReadingTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun `a point wears the four pixels round it by how near each is`() {
        val w = 4
        val h = 2
        val map = intArrayOf(
            rgb(0, 0, 0), rgb(100, 40, 0), rgb(200, 80, 20), rgb(40, 0, 250),
            rgb(10, 10, 10), rgb(20, 200, 30), rgb(30, 100, 50), rgb(50, 60, 70)
        )
        fun at(u: Double, v: Double) = Hemisphere.between(map, w, h, u, v)
        // In the middle of a pixel it is that pixel, to the last bit.
        assertEquals(rgb(100, 40, 0), at(1.5 / w, 0.5 / h))
        assertEquals(rgb(30, 100, 50), at(2.5 / w, 1.5 / h))
        // Halfway between two it is their mean, colour by colour.
        val between = at(2.0 / w, 0.5 / h)
        assertEquals(150, red(between))
        assertEquals(60, green(between))
        assertEquals(10, blue(between))
        // Halfway between four, the mean of the four.
        val middle = at(2.0 / w, 1.0 / h)
        assertEquals((100 + 200 + 20 + 30) / 4.0, red(middle).toDouble(), 1.0)
        assertEquals((40 + 80 + 200 + 100) / 4.0, green(middle).toDouble(), 1.0)
        assertEquals((0 + 20 + 30 + 50) / 4.0, blue(middle).toDouble(), 1.0)
        // Round the back at the date line: the edge of the map is
        // halfway between its last column and its first.
        val seam = at(0.0, 0.5 / h)
        assertEquals(20, red(seam))
        assertEquals(125, blue(seam))
        assertEquals(seam, at(1.0, 0.5 / h))
        // The poles are an end and not a seam.
        assertEquals(rgb(100, 40, 0), at(1.5 / w, 0.0))
        assertEquals(rgb(20, 200, 30), at(1.5 / w, 1.0))
        // And it is opaque, whatever the map's own top byte said.
        val clear = IntArray(8) { 0x00123456 }
        assertEquals(0xFF123456.toInt(), Hemisphere.between(clear, w, h, 0.3, 0.6))
    }

    /** No colour leaks into another, and nothing is brighter or darker than the pixels it came from. */
    @Test
    fun `the weighing is colour by colour and invents nothing`() {
        val w = 16
        val h = 8
        val random = java.util.Random(11)
        val map = IntArray(w * h) { rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        // Pure red beside pure blue is purple in between, with no green in it.
        val two = intArrayOf(rgb(255, 0, 0), rgb(0, 0, 255))
        val purple = Hemisphere.between(two, 2, 1, 0.5, 0.5)
        assertEquals(0, green(purple))
        assertEquals(127.5, red(purple).toDouble(), 1.0)
        assertEquals(127.5, blue(purple).toDouble(), 1.0)
        // Against the same thing done the long way, in floating point.
        repeat(2000) {
            val u = random.nextDouble()
            val v = random.nextDouble()
            val x = u * w - 0.5
            val y = (v * h - 0.5).coerceIn(0.0, h - 1.0)
            val x0 = Math.floor(x).toInt()
            val y0 = y.toInt()
            val fx = x - x0
            val fy = y - y0
            fun px(xx: Int, yy: Int) = map[minOf(yy, h - 1) * w + Math.floorMod(xx, w)]
            fun long(channel: (Int) -> Int): Double {
                val top = channel(px(x0, y0)) * (1 - fx) + channel(px(x0 + 1, y0)) * fx
                val bottom = channel(px(x0, y0 + 1)) * (1 - fx) + channel(px(x0 + 1, y0 + 1)) * fx
                return top * (1 - fy) + bottom * fy
            }
            val got = Hemisphere.between(map, w, h, u, v)
            assertEquals("red at $u, $v", long(::red), red(got).toDouble(), 2.5)
            assertEquals("green at $u, $v", long(::green), green(got).toDouble(), 2.5)
            assertEquals("blue at $u, $v", long(::blue), blue(got).toDouble(), 2.5)
        }
    }

    /**
     * Which views get it: the two that are painted once and then only
     * turned. The ball is painted again at every step of a spin, and
     * seven times the arithmetic is a frame missed.
     */
    @Test
    fun `the views from over a pole read between the pixels and the ball does not`() {
        assertTrue(Hemisphere.smoothMaps(Hemisphere.View.NORTH))
        assertTrue(Hemisphere.smoothMaps(Hemisphere.View.SOUTH))
        assertFalse(Hemisphere.smoothMaps(Hemisphere.View.GLOBE))
    }
}
