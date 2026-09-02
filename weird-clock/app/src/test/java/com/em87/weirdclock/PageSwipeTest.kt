package com.em87.weirdclock

import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
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
}
