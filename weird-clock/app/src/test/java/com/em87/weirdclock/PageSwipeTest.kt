package com.em87.weirdclock

import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether the dial lets go of a page swipe.
 *
 * The clock card fills the screen and a hand can be grabbed anywhere along
 * its length with nearly half an inch of slack either side, so on that card
 * almost every swipe starts on one. The dial claimed the gesture the
 * instant a hand came under the finger — which is right for winding and is
 * why the swipe from the clock to the alarms stopped working.
 *
 * Measured through a parent that records the one call that matters: a
 * pager can only be stopped by [android.view.ViewParent.requestDisallowInterceptTouchEvent],
 * so a dial that never makes it can never block one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class PageSwipeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** A parent that remembers being told to keep its hands off. */
    private class Watcher(context: android.content.Context) : android.widget.FrameLayout(context) {
        var held = false
        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            if (disallow) held = true
            super.requestDisallowInterceptTouchEvent(disallow)
        }
    }

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun dial(): Pair<ClockView, Watcher> {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        val parent = Watcher(themed)
        val clock = ClockView(themed)
        parent.addView(clock)
        parent.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 1000, 1600)
        return clock to parent
    }

    private fun touch(view: ClockView, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    /** Where the hour hand can be taken hold of. */
    private fun onTheHourHand(clock: ClockView): Pair<Float, Float> {
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val tip = clock.handTipForTest(ClockView.Hand.HOUR)
        return (cx + (tip.x - cx) * 0.6f) to (cy + (tip.y - cy) * 0.6f)
    }

    /**
     * A flat sideways drag across a hand leaves the gesture alone.
     *
     * Two hundred pixels across and none down, which is a page swipe and
     * nothing else. The hand is still grabbed — the dial does not refuse
     * the touch — it simply does not tell the pager to keep out, so
     * whichever of the two the drag really is gets to have it.
     */
    @Test
    fun `a flat swipe over a hand is not claimed`() {
        val (clock, parent) = dial()
        val (x, y) = onTheHourHand(clock)
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        assertTrue("the swipe did not start on a hand", clock.grabbedHandForTest() != null)
        for (step in 1..8) touch(clock, MotionEvent.ACTION_MOVE, x - step * 25f, y)
        touch(clock, MotionEvent.ACTION_UP, x - 200f, y)
        assertFalse("the dial swallowed a page swipe", parent.held)
    }

    /** And a wind still owns the gesture, so the pager cannot steal one. */
    @Test
    fun `a wind is claimed`() {
        val (clock, parent) = dial()
        val (x, y) = onTheHourHand(clock)
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        assertTrue("the wind did not start on a hand", clock.grabbedHandForTest() != null)
        for (step in 1..8) touch(clock, MotionEvent.ACTION_MOVE, x, y - step * 25f)
        touch(clock, MotionEvent.ACTION_UP, x, y - 200f)
        assertTrue("a wind can be taken away by a pager", parent.held)
    }

    /**
     * And the hand does not twitch while the swipe is making its mind up.
     *
     * The claim was deferred and the *movement* was not, so for the first
     * few pixels of every page swipe the hand followed the finger, then
     * the pager took the gesture and the hand sprang back. That flick is
     * the small step somebody kept feeling on the way from the clock to
     * the alarms — one frame of a hand going somewhere it was not going to
     * stay.
     */
    @Test
    fun `a flat swipe does not move the hand at all`() {
        val (clock, _) = dial()
        clock.onAttachedToWindowForTest()
        val (x, y) = onTheHourHand(clock)
        val before = clock.handAngleForTest(ClockView.Hand.HOUR)
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..8) touch(clock, MotionEvent.ACTION_MOVE, x - step * 25f, y)
        assertEquals(
            "the hand moved during a page swipe",
            before, clock.handAngleForTest(ClockView.Hand.HOUR), 0.01f
        )
        touch(clock, MotionEvent.ACTION_CANCEL, x - 200f, y)
    }

    /**
     * And a wind loses nothing to the wait.
     *
     * The hand stands still until the drag has proved itself a wind, and
     * then it has to catch up: the finger is already a few pixels on from
     * where it landed. It did not. The wind was taken from the angle of
     * the last undecided move, so every drag came up short by the slop —
     * a few degrees of hour hand, which is ten minutes of clock, lost at
     * the start of every wind and never made up. Measured here as the
     * angle the hand turned against the angle the finger went round.
     */
    @Test
    fun `a wind loses nothing to the claim`() {
        val (clock, _) = dial()
        clock.onAttachedToWindowForTest()
        // Three o'clock, so the hour hand points right and a finger going
        // round it moves down the screen — a wind, not a flat swipe.
        clock.freezeAtForTest(
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 3)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
            }.timeInMillis
        )
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val (x, y) = onTheHourHand(clock)
        val grip = kotlin.math.hypot(x - cx, y - cy)
        val a0 = kotlin.math.atan2(y - cy, x - cx)
        val before = clock.handAngleForTest(ClockView.Hand.HOUR)
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        // Two degrees a move: the first lands inside the slop and is the
        // one that used to be lost.
        val steps = 15
        for (step in 1..steps) {
            val a = a0 + Math.toRadians(step * 2.0)
            touch(
                clock, MotionEvent.ACTION_MOVE,
                cx + grip * kotlin.math.cos(a).toFloat(), cy + grip * kotlin.math.sin(a).toFloat()
            )
        }
        val turned = clock.handAngleForTest(ClockView.Hand.HOUR) - before
        val short = Math.floorMod(turned.toDouble().let { Math.round(it * 100.0) }, 36_000L) / 100.0
        assertEquals(
            "the finger went round ${steps * 2}° and the hand turned $short°",
            steps * 2.0, short, 0.5
        )
        touch(clock, MotionEvent.ACTION_UP, x, y)
    }

    /** And a real wind still winds, picking the finger up where it is. */
    @Test
    fun `a drag that curves winds the hand`() {
        val (clock, _) = dial()
        clock.onAttachedToWindowForTest()
        val (x, y) = onTheHourHand(clock)
        val before = clock.handAngleForTest(ClockView.Hand.HOUR)
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..10) touch(clock, MotionEvent.ACTION_MOVE, x, y - step * 25f)
        assertNotEquals(
            "the hand did not move under a wind",
            before, clock.handAngleForTest(ClockView.Hand.HOUR), 0.01f
        )
        touch(clock, MotionEvent.ACTION_UP, x, y - 250f)
    }
}
