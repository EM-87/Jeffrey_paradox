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
     * A drag round the disc winds it, a flat shove does not, and letting
     * go brings it back.
     *
     * The flat shove is the page swipe: this face lives on a card in a
     * pager and a sideways drag across it has to be allowed to mean "next
     * card" — the same rule the dial's hands follow.
     */
    @Test
    fun `an arc winds the world and a flat swipe does not`() {
        val flat = world()
        touch(flat, MotionEvent.ACTION_DOWN, 700f, 500f)
        for (step in 1..8) touch(flat, MotionEvent.ACTION_MOVE, 700f - step * 30f, 500f)
        assertEquals("a page swipe turned the world", 0L, flat.woundForTest())

        val turned = world()
        touch(turned, MotionEvent.ACTION_DOWN, 700f, 500f)
        for (step in 1..8) touch(turned, MotionEvent.ACTION_MOVE, 700f, 500f - step * 30f)
        assertFalse("the world did not turn under an arc", turned.woundForTest() == 0L)
        assertTrue(
            "a quarter of the disc turned it by more than a day",
            kotlin.math.abs(turned.woundForTest()) < 24 * 3_600_000L
        )
    }
}
