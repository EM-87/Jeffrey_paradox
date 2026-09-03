package com.em87.weirdclock

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planet's weight, in four numbers.
 *
 * A feel is not usually something a test can hold, but this one is: the
 * complaint was that the world snapped back like a clock hand, and "like a
 * clock hand" is a measurable claim — a hand goes home in a fixed time
 * however far it was pushed, and a flywheel does not. Every test here is
 * one sentence of what somebody asked for, turned into a number.
 */
class WorldSpinTest {

    /** Runs it to a stop, and says what happened on the way. */
    private class Run(from: WorldSpin.State) {
        var state = from
        var seconds = 0.0
        var travelled = 0.0
        var furthest = 0.0
        var crossings = 0

        init {
            val dt = 1.0 / 60.0
            var was = state.degrees
            while (seconds < 30.0 && !WorldSpin.resting(state)) {
                state = WorldSpin.step(state, dt)
                travelled += kotlin.math.abs(state.degrees - was)
                furthest = maxOf(furthest, kotlin.math.abs(state.degrees))
                if (was != 0.0 && (was < 0.0) != (state.degrees < 0.0)) crossings++
                was = state.degrees
                seconds += dt
            }
        }
    }

    /**
     * A hard flick keeps turning.
     *
     * Two turns a second is a real shove, and what it must not do is stop
     * dead: a body that heavy carries on. Half a turn is the loosest
     * reading of "it kept going".
     */
    @Test
    fun `a hard flick goes on turning by itself`() {
        val run = Run(WorldSpin.let(degrees = 0.0, rate = 720.0))
        assertTrue(
            "a shove of two turns a second went ${run.furthest} degrees",
            run.furthest > 180.0
        )
        assertTrue("and it took ${run.seconds}s to do it", run.seconds > 0.8)
    }

    /**
     * And a harder one goes further, which is the property a clock hand
     * does not have.
     *
     * This is the whole difference and the reason the spring is left
     * unhooked while it coasts — see [WorldSpin.COASTING]. On a spring
     * that pulls all the way, the return time, and very nearly the
     * distance, is the same whatever you do to it.
     */
    @Test
    fun `harder means further`() {
        val gentle = Run(WorldSpin.let(0.0, 200.0))
        val hard = Run(WorldSpin.let(0.0, 900.0))
        assertTrue(
            "a shove four times as hard went ${hard.furthest} against ${gentle.furthest}",
            hard.furthest > gentle.furthest * 2.5
        )
    }

    /** However hard it is pushed, it ends up back at the time it is. */
    @Test
    fun `it always comes home`() {
        for (rate in listOf(-2000.0, -900.0, -60.0, 0.0, 45.0, 400.0, 1500.0)) {
            for (from in listOf(-720.0, -30.0, 0.0, 12.0, 900.0)) {
                val run = Run(WorldSpin.let(from, rate))
                assertTrue(
                    "from $from at $rate it was still at ${run.state.degrees} after ${run.seconds}s",
                    WorldSpin.resting(run.state)
                )
            }
        }
    }

    /**
     * Let go of gently, it rocks into place rather than arriving dead.
     *
     * The bounce is the part that reads as a spring rather than as an
     * animation, and it is [WorldSpin.SETTLING] being under critical for
     * [WorldSpin.PULL]. One crossing of the mark is enough to see; a
     * dozen would be a bell.
     */
    @Test
    fun `it overshoots once and settles`() {
        val run = Run(WorldSpin.let(degrees = 40.0, rate = 0.0))
        assertTrue("it came back dead, with ${run.crossings} crossings", run.crossings >= 1)
        assertTrue("it rang like a bell: ${run.crossings} crossings", run.crossings <= 4)
        assertTrue("it took ${run.seconds}s to settle", run.seconds < 6.0)
    }

    /** A slow frame does not throw the world across the screen. */
    @Test
    fun `a late frame is clamped`() {
        val fast = WorldSpin.step(WorldSpin.let(0.0, 600.0), 1.0 / 60.0)
        val stalled = WorldSpin.step(WorldSpin.let(0.0, 600.0), 2.0)
        assertTrue(
            "a two-second frame moved ${stalled.degrees} degrees",
            stalled.degrees <= fast.degrees * 3.0
        )
    }

    /** And nothing ever goes faster than the eye can follow. */
    @Test
    fun `it never spins faster than its cap`() {
        var s = WorldSpin.let(0.0, 100_000.0)
        repeat(10) { s = WorldSpin.step(s, 1.0 / 60.0) }
        assertTrue("it is going at ${s.rate}", kotlin.math.abs(s.rate) <= WorldSpin.TOP_RATE)
    }
}
