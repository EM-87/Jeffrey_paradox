package com.em87.weirdclock

import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening the world out, and pushing it round.
 *
 * The face is a picture of the earth drawn at a third of the screen so a
 * ring of numerals could have the rest, and the ring is furniture. And the
 * earth goes round once a day, which means turning it is not a camera move
 * — it is winding a clock with one hand, and the hand is the planet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class WorldInHandTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun world(): HemisphereView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return HemisphereView(themed).apply {
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1000)
        }
    }

    private fun touch(v: HemisphereView, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    /** The world opens and the ring gets out of its way first. */
    @Test
    fun `the ring gives way before the world reaches it`() {
        val shut = Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed = true)
        val open = Hemisphere.worldRadius(Hemisphere.ZOOM_MAX, ringed = true)
        assertEquals("the world does not start where it always did",
            Hemisphere.WORLD_RINGED, shut, 1e-6f)
        assertTrue("the world does not open at all", open > shut * 1.2f)
        assertTrue("the world opens past the edge of the screen", open < 0.5f)

        assertEquals("the ring is not whole to begin with", 1f, Hemisphere.ringFade(1f), 1e-6f)
        assertEquals("the ring is still there at full zoom", 0f, Hemisphere.ringFade(Hemisphere.ZOOM_MAX), 1e-6f)
        // Gone before the world arrives: at the zoom where the ring runs
        // out, the world is still inside where the ring's ticks are.
        var goneAt = Hemisphere.ZOOM_MAX
        var z = Hemisphere.ZOOM_MIN
        while (z <= Hemisphere.ZOOM_MAX) {
            if (Hemisphere.ringFade(z) <= 0.01f) { goneAt = z; break }
            z += 0.01f
        }
        assertTrue(
            "the world ran the ring over",
            Hemisphere.worldRadius(goneAt, ringed = true) < Hemisphere.WORLD_RINGED * 1.06f
        )
    }

    /** A degree of turn is four minutes, because a day is a turn. */
    @Test
    fun `turning the world is winding the clock`() {
        val quarter = Hemisphere.windBy(Hemisphere.View.NORTH, 90.0)
        assertEquals("a quarter turn is not six hours", 6 * 3_600_000L, kotlin.math.abs(quarter))
        assertEquals(
            "the world turns the same way from either pole",
            -quarter, Hemisphere.windBy(Hemisphere.View.SOUTH, 90.0)
        )
        assertEquals(0L, Hemisphere.windBy(Hemisphere.View.NORTH, 0.0))
    }

    /**
     * A finger on the world turns the world, whichever way it goes.
     *
     * It used to have to prove itself first: a drag was given to the pager
     * unless it curved, which is the rule the dial's *hands* follow and
     * the wrong one here. A hand is a thin thing on a wide face and most
     * of the face is not it; the world is the face. So a straight
     * sideways push on the equator — the most obvious way there is to
     * spin a globe — turned the page instead, and the planet could only be
     * turned up and down. Now the rule is the one the dial really uses:
     * inside the instrument, the instrument has it.
     */
    @Test
    fun `a finger anywhere on the world turns it`() {
        for ((name, dx, dy) in listOf(
            Triple("sideways", -30f, 0f),
            Triple("up", 0f, -30f),
            Triple("across", -20f, -20f)
        )) {
            val globe = world()
            touch(globe, MotionEvent.ACTION_DOWN, 700f, 500f)
            for (step in 1..8) {
                touch(globe, MotionEvent.ACTION_MOVE, 700f + step * dx, 500f + step * dy)
            }
            assertFalse("a $name drag on the world did not turn it", globe.woundForTest() == 0L)
            assertTrue(
                "a quarter of the disc turned it by more than a day",
                kotlin.math.abs(globe.woundForTest()) < 24 * 3_600_000L
            )
        }
    }

    /**
     * And a finger off the world leaves it alone.
     *
     * The margin outside the disc is where the page swipe lives. Without
     * one there is no way to leave this card by dragging at all, which is
     * a face you can get to and not leave.
     */
    @Test
    fun `a finger outside the world is a page swipe`() {
        val globe = world()
        // Half past four on the rim: outside a disc of 0.355 of a
        // thousand, and well inside the view.
        touch(globe, MotionEvent.ACTION_DOWN, 900f, 500f)
        for (step in 1..8) touch(globe, MotionEvent.ACTION_MOVE, 900f - step * 30f, 500f)
        assertEquals("a swipe in the margin turned the world", 0L, globe.woundForTest())
    }

    /**
     * Thrown, it keeps going; left alone, it comes back.
     *
     * The arithmetic is [WorldSpin] and is measured there. What is
     * measured here is that the view is wired to it: that letting go with
     * some speed leaves the world turning for a while rather than
     * arriving, and that it does arrive in the end.
     */
    @Test
    fun `a thrown world keeps turning and then comes home`() {
        val globe = world()
        touch(globe, MotionEvent.ACTION_DOWN, 700f, 500f)
        for (step in 1..6) touch(globe, MotionEvent.ACTION_MOVE, 700f, 500f - step * 40f)
        val held = globe.woundForTest()
        assertFalse("nothing was wound to throw", held == 0L)

        globe.letGoForTest(400.0)
        assertTrue("letting go of a moving world stopped it dead", globe.spinningForTest())
        // A fifth of a second in, it has gone further rather than back.
        var went = 0L
        repeat(12) {
            globe.stepSpinForTest(1.0 / 60.0)
            went = globe.woundForTest()
        }
        assertTrue(
            "the world came straight back instead of carrying on: $held then $went",
            kotlin.math.abs(went) > kotlin.math.abs(held)
        )

        var frames = 0
        while (globe.spinningForTest() && frames < 60 * 30) {
            globe.stepSpinForTest(1.0 / 60.0)
            frames++
        }
        assertFalse("the world never stopped", globe.spinningForTest())
        assertEquals("and it did not come home", 0L, globe.woundForTest())
    }

    /** A finger that stops before lifting has not thrown anything. */
    @Test
    fun `letting go of a still world simply puts it back`() {
        val globe = world()
        touch(globe, MotionEvent.ACTION_DOWN, 700f, 500f)
        touch(globe, MotionEvent.ACTION_MOVE, 700f, 460f)
        globe.letGoForTest(0.0)
        var frames = 0
        while (globe.spinningForTest() && frames < 60 * 30) {
            globe.stepSpinForTest(1.0 / 60.0)
            frames++
        }
        assertEquals("it did not settle back on the time it is", 0L, globe.woundForTest())
        assertTrue("it took $frames frames to put back a nudge", frames < 60 * 6)
    }
}
