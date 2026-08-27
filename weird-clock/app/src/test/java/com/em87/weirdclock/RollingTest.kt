package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Setting a time by rolling the digits.
 *
 * The digital face has no hands to wind, and this is what it has instead:
 * each digit is a drum worth so many minutes a click, and a finger turns
 * it. What is checked here is the arithmetic and the reach — that the
 * carry falls out, that a fling cannot throw the alarm most of a day, and
 * that a clock which is only telling the time has nothing to grab at all,
 * because that last one is what keeps the gesture off the swipes between
 * cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class RollingTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun face(at: Long? = null): DigitalClockView = DigitalClockView(context).apply {
        settingMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, 1400)
        draw(android.graphics.Canvas(
            android.graphics.Bitmap.createBitmap(1080, 1400, android.graphics.Bitmap.Config.ARGB_8888)
        ))
    }

    private fun at(hour: Int, minute: Int): Long = (hour * 60L + minute) * 60_000L

    private fun readsAs(view: DigitalClockView): String {
        val ms = view.settingMs!!
        return "%02d:%02d".format(ms / 3_600_000L, ms / 60_000L % 60L)
    }

    /** One click of the minutes' units drum is one minute. */
    @Test
    fun `each drum is worth what it says`() {
        val view = face(at(7, 30))
        view.rollForTest(1, 1)
        assertEquals("07:31", readsAs(view))
        view.rollForTest(10, 1)
        assertEquals("07:41", readsAs(view))
        view.rollForTest(60, 1)
        assertEquals("08:41", readsAs(view))
        view.rollForTest(600, 1)
        assertEquals("18:41", readsAs(view))
    }

    /**
     * The carry falls out of the weights rather than being written down.
     *
     * Roll the minutes past fifty-nine and the hour goes up, the way a
     * mechanical counter does. Nothing in the code says so: each drum is
     * worth so many minutes and the total wraps into a day.
     */
    @Test
    fun `rolling the minutes past the hour takes the hour with it`() {
        val view = face(at(7, 59))
        view.rollForTest(1, 1)
        assertEquals("08:00", readsAs(view))
        view.rollForTest(1, -1)
        assertEquals("07:59", readsAs(view))
    }

    /** And the day wraps at both ends rather than going negative. */
    @Test
    fun `the day is round`() {
        val view = face(at(23, 59))
        view.rollForTest(1, 1)
        assertEquals("00:00", readsAs(view))
        view.rollForTest(1, -1)
        assertEquals("23:59", readsAs(view))
        view.rollForTest(600, 3)
        assertTrue("an hour outside a day", view.settingMs!! in 0 until 86_400_000L)
    }

    /**
     * A drag that goes down and comes back up ends where it started.
     *
     * The drum pays out detents against where the finger went down, not
     * against where it was a frame ago — so a wobble mid-drag does not
     * ratchet the number forwards.
     */
    @Test
    fun `a drag that comes back ends where it started`() {
        val view = face(at(7, 30))
        val d = context.resources.displayMetrics.density * 26f
        val grab = view.grabForTest(1)
        press(view, grab.first, grab.second)
        moveTo(view, grab.first, grab.second - d * 3.4f)
        assertEquals("07:33", readsAs(view))
        moveTo(view, grab.first, grab.second)
        assertEquals("07:30", readsAs(view))
        release(view, grab.first, grab.second)
        assertEquals("07:30", readsAs(view))
    }

    /**
     * A clock that is only telling the time has nothing to take hold of.
     *
     * This is the whole reason the gesture is safe: the cards are swiped
     * between with the same finger in the same direction, and a readout
     * that grabbed a drag whenever it felt one would eat every one of
     * them.
     */
    @Test
    fun `a clock telling the time has nothing to grab`() {
        val view = face(at = null)
        for (x in 0..1080 step 60) {
            for (y in 0..1400 step 60) {
                assertEquals(
                    "something was grabbable at $x,$y on a clock with nothing to set",
                    0, view.weightUnderForTest(x.toFloat(), y.toFloat())
                )
            }
        }
        val event = android.view.MotionEvent.obtain(
            0, 0, android.view.MotionEvent.ACTION_DOWN, 540f, 700f, 0
        )
        assertFalse("the readout swallowed a swipe", view.onTouchEvent(event))
    }

    /** And while one is being set, each digit is grabbable and no more. */
    @Test
    fun `each digit is its own drum`() {
        val view = face(at(7, 30))
        val weights = HashSet<Int>()
        for (x in 0..1080 step 4) {
            val w = view.weightUnderForTest(x.toFloat(), 700f)
            if (w != 0) weights += w
        }
        assertEquals(
            "the four drums a time has are not all there",
            setOf(600, 60, 10, 1), weights
        )
    }

    /** The seconds are not shown while a time is being set. */
    @Test
    fun `the seconds go away while a time is being set`() {
        val view = DigitalClockView(context).apply { showSeconds = true }
        view.settingMs = at(7, 30)
        assertEquals(
            "a time being set is still counting seconds",
            0, view.cellsForTest().count { it is Cell.Number && it.weight == 0 }
        )
    }

    private fun press(view: DigitalClockView, x: Float, y: Float) {
        view.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        )
    }

    private fun moveTo(view: DigitalClockView, x: Float, y: Float) {
        view.onTouchEvent(
            android.view.MotionEvent.obtain(0, 8, android.view.MotionEvent.ACTION_MOVE, x, y, 0)
        )
    }

    private fun release(view: DigitalClockView, x: Float, y: Float) {
        view.onTouchEvent(
            android.view.MotionEvent.obtain(0, 16, android.view.MotionEvent.ACTION_UP, x, y, 0)
        )
    }
}
