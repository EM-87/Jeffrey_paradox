package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Catching a hand when the hands are on top of one another.
 *
 * At twelve o'clock all three lie along the same line, and "which hand is
 * nearest the finger" has no answer — every one of them is under it. What
 * people actually do is reach out near the rim for the long hand and down
 * near the middle for the short one, so the dial answers by radius instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandGrabTest {

    /**
     * Lets the mode-change animation finish.
     *
     * Giving a dial a chrono provider makes its hands *travel* to their new
     * positions over seven hundred milliseconds, hand-clocked on uptime —
     * and under Robolectric uptime does not move on its own. Without this
     * every one of these tests measures a dial frozen part-way between the
     * wall clock and the value it was given, which is neither.
     */
    private fun ClockView.settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            2, java.util.concurrent.TimeUnit.SECONDS
        )
    }

    /** All three hands straight up: the pile this is all about. */
    private fun dialAtTwelve(second: Boolean = true): ClockView =
        ClockView(ApplicationProvider.getApplicationContext()).apply {
            chronoProvider = { 0L }
            chronoSettable = true
            showSecondHand = second
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            settle()
        }

    /**
     * Two hands eleven degrees apart, which is the case that tells the two
     * rules apart. In a perfect pile every hand is at distance zero from
     * the finger and "the nearest" falls back to whatever order the loop
     * runs in — which happened to agree with the rings, so the first
     * version of this test passed with the rings taken out.
     */
    private fun dialNearlyPiled(): ClockView =
        ClockView(ApplicationProvider.getApplicationContext()).apply {
            // Two minutes past twelve: the hour hand at 1 degree, the
            // minute hand at 12, the second hand back at 0.
            chronoProvider = { 120_000L }
            chronoSettable = true
            showSecondHand = true
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            settle()
        }

    /** Touches along [angleDeg], [fraction] of the way to the rim. */
    private fun grabAlong(view: ClockView, angleDeg: Float, fraction: Float): ClockView.Hand? {
        val d = 360f * 0.92f * fraction
        val a = Math.toRadians(angleDeg.toDouble())
        view.grabHandNear(
            360f + (Math.sin(a) * d).toFloat(),
            360f - (Math.cos(a) * d).toFloat()
        )
        return view.draggedHandForTest()
    }

    /**
     * Reaching low along the *minute* hand asks for the hour hand, because
     * low is where the hour hand lives. This is the one the old rule got
     * wrong: nearest-hand-wins hands you the minute hand, since you are
     * standing on it.
     */
    @Test
    fun `reaching low along the long hand still catches the short one`() {
        assertEquals(ClockView.Hand.HOUR, grabAlong(dialNearlyPiled(), 12f, 0.30f))
        // And reaching out along the hour hand's line catches the long one,
        // out past where the short hand can reach.
        assertEquals(ClockView.Hand.MINUTE, grabAlong(dialNearlyPiled(), 1f, 0.62f))
    }

    /** Touches straight up from the centre, [fraction] of the way to the rim. */
    private fun grabAt(view: ClockView, fraction: Float): ClockView.Hand? {
        // A circular dial's boundary is half the smaller side times 0.92.
        view.grabHandNear(360f, 360f - 360f * 0.92f * fraction)
        return view.draggedHandForTest()
    }

    @Test
    fun `the rings run short hand inwards, long hand outwards`() {
        val v = dialAtTwelve()
        assertEquals(ClockView.Hand.HOUR, v.handForRing(0.2f))
        assertEquals(ClockView.Hand.HOUR, v.handForRing(0.45f))
        assertEquals(ClockView.Hand.MINUTE, v.handForRing(0.6f))
        assertEquals(ClockView.Hand.MINUTE, v.handForRing(0.7f))
        assertEquals(ClockView.Hand.SECOND, v.handForRing(0.8f))
    }

    /**
     * The whole point: three hands in a pile, three different answers
     * depending on how far out the finger lands.
     */
    @Test
    fun `a pile of hands is untangled by where the finger lands`() {
        assertEquals(ClockView.Hand.HOUR, grabAt(dialAtTwelve(), 0.30f))
        assertEquals(ClockView.Hand.MINUTE, grabAt(dialAtTwelve(), 0.62f))
        assertEquals(ClockView.Hand.SECOND, grabAt(dialAtTwelve(), 0.79f))
    }

    /** With no second hand, its ring goes to the longest one still there. */
    @Test
    fun `the outer ring falls to the minute hand when there is no second`() {
        assertEquals(ClockView.Hand.MINUTE, grabAt(dialAtTwelve(second = false), 0.79f))
        assertEquals(ClockView.Hand.HOUR, grabAt(dialAtTwelve(second = false), 0.30f))
    }

    /** A ring with nothing in it hands the touch outwards, not to nothing. */
    @Test
    fun `an empty ring does not swallow the grab`() {
        val v = dialAtTwelve()
        val onlyHour = listOf(ClockView.Hand.HOUR to 5f)
        assertEquals(ClockView.Hand.HOUR, v.untangle(0.79f, onlyHour))
        val onlySecond = listOf(ClockView.Hand.SECOND to 5f)
        assertEquals(ClockView.Hand.SECOND, v.untangle(0.20f, onlySecond))
    }

    /** Nothing under the finger is still nothing. */
    @Test
    fun `a touch nowhere near a hand grabs nothing`() {
        val v = dialAtTwelve()
        // Straight down, where no hand is pointing.
        v.grabHandNear(360f, 360f + 360f * 0.92f * 0.5f)
        assertNull(v.draggedHandForTest())
    }

    // ------------------------------------------------------ the ratchet

    /**
     * Winding one hand used to whirl the second hand round with it. On a
     * dial being set it now stays put — and the old rule only pinned it
     * when the value happened to land on a whole minute, so a countdown of
     * one and a half minutes still sent it spinning.
     */
    @Test
    fun `winding a length leaves the second hand where it was`() {
        var value = 90_000L
        val v = ClockView(ApplicationProvider.getApplicationContext()).apply {
            chronoProvider = { value }
            chronoSettable = true
            showSecondHand = true
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            settle()
        }
        val before = v.secondAngleForTest()
        // Not on a whole minute, which is what the old rule needed.
        assertNotEquals(0f, before)

        // Take hold of the minute hand and wind a long way.
        v.grabHandNear(360f, 360f - 360f * 0.92f * 0.62f)
        assertEquals(ClockView.Hand.MINUTE, v.draggedHandForTest())
        v.windForTest(17 * 60.0 + 23.0)
        assertEquals("the second hand must not move", before, v.secondAngleForTest(), 0.001f)
        // The minute hand did move, so the wind really happened — without
        // this the test would pass on a dial that ignored the wind entirely.
        assertNotEquals(90_000L, v.settingValueMs())
    }
}
