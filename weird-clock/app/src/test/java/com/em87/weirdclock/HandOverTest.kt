package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving between the clock row and the chronograph row.
 *
 * The two rows are different views, and the app used to cross-fade them: the
 * old face dissolved while the new one appeared, which hid the one thing
 * worth watching. A watch does not dissolve — its hands go round. So the
 * arriving dial is handed the angles the leaving one was showing and covers
 * the distance itself.
 *
 * That is a claim about where a dial's hands are on its first frame after
 * the swap, which is exactly the sort of thing that is easy to believe and
 * hard to see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandOverTest {

    private fun dial(chrono: Long? = null): ClockView =
        ClockView(ApplicationProvider.getApplicationContext()).apply {
            if (chrono != null) chronoProvider = { chrono }
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            // Giving a dial a provider starts a transition of its own. Let
            // the time for it pass, then *read* the angles — a finished
            // transition is retired when it is next asked about, not when
            // the clock says it is over, and nothing here draws frames.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                2, java.util.concurrent.TimeUnit.SECONDS
            )
            handAngleForTest(ClockView.Hand.HOUR)
        }

    /**
     * A stopwatch whose hour hand starts exactly opposite the clock's.
     *
     * Chosen from the clock rather than fixed: a stopwatch at zero points
     * at twelve, and so does a clock at one minute past — which is when
     * this test first ran, and it duly reported that a hand-over moves
     * nothing. Half a turn apart, there is always something to watch.
     */
    private fun stopwatchOpposite(clock: ClockView): ClockView {
        val target = (clock.handAngleForTest(ClockView.Hand.HOUR) + 180f) % 360f
        // On a twelve-hour face one degree of hour hand is 120 seconds.
        return dial(chrono = (target * 120_000f).toLong())
    }

    private fun angles(v: ClockView) = Triple(
        v.handAngleForTest(ClockView.Hand.HOUR),
        v.handAngleForTest(ClockView.Hand.MINUTE),
        v.handAngleForTest(ClockView.Hand.SECOND)
    )

    /**
     * A stopwatch at zero has every hand at twelve; a clock almost never
     * does. Handed the clock's angles, the stopwatch starts there.
     */
    @Test
    fun `the arriving dial starts where the leaving one was`() {
        val clock = dial()
        val stopwatch = stopwatchOpposite(clock)
        val ownAngle = angles(stopwatch).first

        stopwatch.handOverFrom(clock)
        val handed = angles(stopwatch).first
        assertEquals("the hour hand starts on the clock's",
            angles(clock).first, handed, 1f)
        assertTrue("and so is not at its own rest yet",
            kotlin.math.abs(handed - ownAngle) > 90f)
    }

    /** And it arrives, rather than staying where it was handed. */
    @Test
    fun `and travels to its own positions`() {
        val clock = dial()
        val stopwatch = stopwatchOpposite(clock)
        val ownAngle = angles(stopwatch).first
        stopwatch.handOverFrom(clock)

        // Long enough for the seven hundred milliseconds of travel.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            2, java.util.concurrent.TimeUnit.SECONDS
        )
        assertEquals("it arrives where it belongs", ownAngle, angles(stopwatch).first, 0.5f)
    }

    /**
     * The travel takes time. A dial that snapped would report its
     * destination on the very next frame, which is what a cross-fade was
     * hiding and what this whole change is about.
     */
    @Test
    fun `the hands take time about it`() {
        val clock = dial()
        val stopwatch = dial(chrono = 0L)
        stopwatch.handOverFrom(clock)
        // A third of a second in, it should be on its way and not there.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            300, java.util.concurrent.TimeUnit.MILLISECONDS
        )
        val midway = angles(stopwatch).first
        assertNotEquals("still travelling", 0f, midway)
        assertTrue("midway=$midway start=${angles(clock).first}",
            kotlin.math.abs(midway - angles(clock).first) > 0.5f)
    }

    /**
     * A dial handed its own angles has nowhere to go, and must not pretend
     * to travel.
     *
     * This is the shape of the bug that made the stopwatch appear out of
     * nowhere: changing the page fires onPageSelected, which moved the
     * host's note of "the dial on screen" on to the *arriving* face before
     * the cards were swapped — so the hand-over asked a dial to travel from
     * where it already was, and it stood still.
     */
    @Test
    fun `a dial handed its own angles does not move`() {
        val v = dial(chrono = 90 * 120_000L)
        val before = angles(v)
        v.handOverFrom(v)
        assertEquals(before.first, angles(v).first, 0.001f)
        assertEquals(before.second, angles(v).second, 0.001f)
    }

    /** And one handed another's is travelling, which is the observable fact. */
    @Test
    fun `a dial handed another's angles is travelling`() {
        val clock = dial()
        val stopwatch = stopwatchOpposite(clock)
        assertTrue("a settled dial is not travelling", !stopwatch.isTravelling())
        stopwatch.handOverFrom(clock)
        assertTrue(stopwatch.isTravelling())
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            2, java.util.concurrent.TimeUnit.SECONDS
        )
        // Reading the angles is what retires a finished transition.
        angles(stopwatch)
        assertTrue("and stops when it arrives", !stopwatch.isTravelling())
    }
}
