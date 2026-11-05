package com.em87.weirdclock

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a bake of the globe costs in arithmetic, the old way and the new.
 *
 * No bitmaps and no Android: the two loops the view runs, over the same
 * quarter of a million points, timed against each other. Printed rather
 * than asserted to a ratio, because a ratio measured on a build machine
 * under load is an argument and not a fact; what is asserted is only that
 * both loops read every pixel of the world, so the comparison is of like
 * with like.
 */
class DiscProjectionCostTest {

    private val size = 512

    private fun slow(turn: Double): Long {
        val half = size / 2.0
        var touched = 0L
        for (py in 0 until size) {
            val y = (py + 0.5 - half) / half
            for (px in 0 until size) {
                val x = (px + 0.5 - half) / half
                val place = Hemisphere.unproject(Hemisphere.View.GLOBE, x, y, turn) ?: continue
                val u = ((place[1] + 180.0) / 360.0).coerceIn(0.0, 0.9999)
                val v = ((90.0 - place[0]) / 180.0).coerceIn(0.0, 0.9999)
                touched += (u * 1024).toInt() + (v * 512).toInt() * 1024
            }
        }
        return touched
    }

    private fun fast(disc: DiscProjection, turn: Double): Long {
        var touched = 0L
        for (at in 0 until size * size) {
            if (!disc.onWorld(at)) continue
            val u = disc.u(at, turn)
            val v = disc.v[at].toDouble()
            touched += (u * 1024).toInt() + (v * 512).toInt() * 1024
        }
        return touched
    }

    @Test
    fun `a turned frame is a subtraction per pixel, not two arcs`() {
        // Warm both up: the first run of anything on a virtual machine is
        // the compiler's time, not the loop's.
        slow(3.0)
        val disc = DiscProjection(Hemisphere.View.GLOBE, size)
        fast(disc, 3.0)

        val frames = 20
        var began = System.nanoTime()
        var slowTouched = 0L
        for (i in 0 until frames) slowTouched += slow(i * 1.5)
        val slowMs = (System.nanoTime() - began) / 1_000_000.0 / frames
        began = System.nanoTime()
        var fastTouched = 0L
        for (i in 0 until frames) fastTouched += fast(disc, i * 1.5)
        val fastMs = (System.nanoTime() - began) / 1_000_000.0 / frames
        println(
            "one bake of the globe, 512 squared: trigonometry %.1f ms, cached %.1f ms".format(
                slowMs, fastMs
            )
        )
        // The sums are of map offsets and agree to the pixel almost
        // everywhere; the few that do not are the float-against-double
        // edge of a pixel, and are allowed a hair.
        assertTrue(
            "the two loops did not read the same world: $slowTouched vs $fastTouched",
            kotlin.math.abs(slowTouched - fastTouched) < slowTouched / 10_000
        )
    }
}
