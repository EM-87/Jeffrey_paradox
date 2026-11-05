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
        val held = throwIt(globe)

        assertTrue("letting go of a moving world stopped it dead", globe.spinningForTest())
        // A fifth of a second in, it has gone further the same way rather
        // than back. The speed used to be handed to the physics in the
        // finger's frame while the offset was in the other one, so a world
        // thrown clockwise set off anticlockwise at the speed it had been
        // thrown — which was reported, accurately, as the world bouncing
        // off something as it was let go. This test used to feed the
        // speed in by hand, in the frame that happened to agree with the
        // mistake, and passed.
        var went = 0L
        repeat(12) {
            globe.stepSpinForTest(1.0 / 60.0)
            went = globe.woundForTest()
        }
        assertTrue(
            "the world came straight back instead of carrying on: $held then $went",
            kotlin.math.abs(went) > kotlin.math.abs(held) &&
                kotlin.math.sign(went.toDouble()) == kotlin.math.sign(held.toDouble())
        )

        var frames = 0
        while (globe.spinningForTest() && frames < 60 * 30) {
            globe.stepSpinForTest(1.0 / 60.0)
            frames++
        }
        assertFalse("the world never stopped", globe.spinningForTest())
        assertEquals("and it did not come home", 0L, globe.woundForTest())
    }

    /**
     * Thrown the other way, it keeps going the other way. The sign error
     * this guards against is symmetric, but the test above could pass by
     * luck on one side of it and this one cannot pass on the same luck.
     */
    @Test
    fun `and thrown the other way it carries on the other way`() {
        val globe = world()
        val held = throwIt(globe, dy = 40f)
        var went = 0L
        repeat(12) {
            globe.stepSpinForTest(1.0 / 60.0)
            went = globe.woundForTest()
        }
        assertTrue(
            "the world came back instead of carrying on: $held then $went",
            kotlin.math.abs(went) > kotlin.math.abs(held) &&
                kotlin.math.sign(went.toDouble()) == kotlin.math.sign(held.toDouble())
        )
    }

    /**
     * A pinch in the middle of a turn, and the world still comes home
     * when the last finger comes off.
     *
     * The pinch takes the world out of the hand that was turning it, and
     * the lift that ends it used to be ignored for that reason — so the
     * world stayed wound to whenever the pinch began, until the next
     * touch. Driven through the real touch events, two fingers and all.
     */
    @Test
    fun `a pinch mid-turn still lets the world go`() {
        val globe = world()
        val down = android.os.SystemClock.uptimeMillis()
        touch(globe, MotionEvent.ACTION_DOWN, 700f, 500f)
        for (step in 1..4) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16))
            touch(globe, MotionEvent.ACTION_MOVE, 700f, 500f - step * 40f)
        }
        val held = globe.woundForTest()
        assertFalse("nothing was wound before the pinch", held == 0L)

        // The second finger lands, the two move apart, and both come off.
        fun two(action: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16))
            val at = android.os.SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
                MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f }
            )
            val e = MotionEvent.obtain(down, at, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            globe.onTouchEvent(e)
            e.recycle()
        }
        two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 700f, 340f, 400f, 600f)
        for (step in 1..4) two(MotionEvent.ACTION_MOVE, 700f + step * 20f, 340f - step * 20f, 400f - step * 20f, 600f + step * 20f)
        two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 780f, 260f, 320f, 680f)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16))
        touch(globe, MotionEvent.ACTION_UP, 780f, 260f)

        var frames = 0
        while (globe.spinningForTest() && frames < 60 * 30) {
            globe.stepSpinForTest(1.0 / 60.0)
            frames++
        }
        assertEquals(
            "the world stayed wound to where the pinch began", 0L, globe.woundForTest()
        )
    }

    /**
     * A real throw, through the touch events, with the clock running so
     * the lift is seen to come straight after the last move. Returns how
     * far the world was wound when the finger came off.
     */
    private fun throwIt(globe: HemisphereView, dy: Float = -40f): Long {
        touch(globe, MotionEvent.ACTION_DOWN, 700f, 500f)
        for (step in 1..6) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16))
            touch(globe, MotionEvent.ACTION_MOVE, 700f, 500f + step * dy)
        }
        val held = globe.woundForTest()
        assertFalse("nothing was wound to throw", held == 0L)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(8))
        touch(globe, MotionEvent.ACTION_UP, 700f, 500f + 6 * dy)
        return held
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
