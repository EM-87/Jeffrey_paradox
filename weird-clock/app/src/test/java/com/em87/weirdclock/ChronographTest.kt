package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two chronographs, with time moved by hand.
 *
 * No Robolectric and no Android: the whole point of pulling these out of
 * the activity is that what a running total *is* can be checked without a
 * phone, an emulator or a screen. They take their clock as an argument,
 * so a test can jump an hour without waiting one.
 */
class ChronographTest {

    private var clock = 0L

    private fun stopwatch() = Chronograph { clock }

    private fun countdown(startingAt: Long = 60_000L) = Countdown({ clock }, startingAt)

    // ------------------------------------------------------- the stopwatch

    @Test
    fun `a stopwatch nobody has started reads nothing`() {
        val watch = stopwatch()
        clock = 5_000L
        assertEquals(0L, watch.elapsed())
        assertFalse(watch.running)
    }

    @Test
    fun `a running one counts, and a stopped one holds`() {
        val watch = stopwatch()
        assertTrue(watch.startOrStop())
        clock = 3_000L
        assertEquals(3_000L, watch.elapsed())

        assertFalse(watch.startOrStop())
        clock = 9_000L
        assertEquals("a stopped watch must not keep counting", 3_000L, watch.elapsed())
    }

    /** And starting again adds to what was banked rather than replacing it. */
    @Test
    fun `a second run adds to the first`() {
        val watch = stopwatch()
        watch.startOrStop()
        clock = 2_000L
        watch.startOrStop()
        clock = 10_000L
        watch.startOrStop()
        clock = 12_500L
        assertEquals(4_500L, watch.elapsed())
    }

    /**
     * Stopping banks what the run was worth at the moment the pusher went
     * down, rather than reading the clock again when somebody asks. The
     * whole point of a chronograph is that the number is fixed at the
     * instant you press it.
     */
    @Test
    fun `stopping fixes the number at the moment it was pressed`() {
        val watch = stopwatch()
        watch.startOrStop()
        clock = 7_000L
        watch.startOrStop()
        clock = 1_000_000L
        assertEquals(7_000L, watch.elapsed())
    }

    @Test
    fun `reset empties it and stops it`() {
        val watch = stopwatch()
        watch.startOrStop()
        clock = 8_000L
        watch.reset()
        clock = 20_000L
        assertEquals(0L, watch.elapsed())
        assertFalse(watch.running)
    }

    /** Picked up where it was left, running or not. */
    @Test
    fun `it can be put back the way it was found`() {
        val watch = stopwatch()
        clock = 50_000L
        watch.restore(accum = 4_000L, started = 45_000L, wasRunning = true)
        assertTrue(watch.running)
        assertEquals("banked plus the run so far", 9_000L, watch.elapsed())
    }

    // -------------------------------------------------------- the countdown

    @Test
    fun `a countdown counts down and stops at nothing`() {
        val timer = countdown(10_000L)
        assertTrue(timer.startOrStop())
        clock = 4_000L
        assertEquals(6_000L, timer.remaining())
        clock = 30_000L
        assertEquals("it must never go below nothing", 0L, timer.remaining())
    }

    /**
     * Starting one with nothing left does nothing at all. It would run out
     * in the same instant, which is a timer that goes off the moment you
     * press it.
     */
    @Test
    fun `a countdown with nothing left refuses to start`() {
        val timer = countdown(10_000L)
        timer.reset()
        assertFalse(timer.startOrStop())
        assertFalse(timer.running)
    }

    @Test
    fun `pausing holds what was left, and starting again resumes from there`() {
        val timer = countdown(10_000L)
        timer.startOrStop()
        clock = 4_000L
        timer.startOrStop()
        assertEquals(6_000L, timer.remaining())

        clock = 100_000L
        assertEquals("a paused countdown must not drain", 6_000L, timer.remaining())

        timer.startOrStop()
        clock = 102_000L
        assertEquals(4_000L, timer.remaining())
    }

    /**
     * Winding it to a length sets what it draws against too, and never to
     * nothing — the sand in the hourglass divides by that total.
     */
    @Test
    fun `winding it sets the length it is drawn against`() {
        val timer = countdown()
        timer.setTo(45_000L)
        assertEquals(45_000L, timer.remaining())
        assertEquals(45_000L, timer.totalMs)

        timer.setTo(0L)
        assertTrue("nothing may ever divide by this", timer.totalMs > 0L)
    }

    /** One started elsewhere — the shade, or a spoken request — is adopted. */
    @Test
    fun `a countdown started somewhere else is picked up whole`() {
        val timer = countdown()
        clock = 1_000L
        timer.adopt(endingAt = 31_000L, total = 60_000L)
        assertTrue(timer.running)
        assertEquals(30_000L, timer.remaining())
        assertEquals(60_000L, timer.totalMs)
    }

    /**
     * The two are not one class with a sign flipped. A countdown has a
     * length it was set to and a floor it stops at; a stopwatch has
     * neither, and sharing would mean each carrying a field that means
     * nothing to it.
     */
    @Test
    fun `the stopwatch has no floor and the countdown does`() {
        val watch = stopwatch()
        watch.startOrStop()
        clock = 10_000_000L
        assertTrue("a stopwatch runs as long as it likes", watch.elapsed() > 60_000L)

        val timer = countdown(1_000L)
        timer.startOrStop()
        clock += 5_000L
        assertEquals("a countdown stops at the bottom", 0L, timer.remaining())
    }
}
