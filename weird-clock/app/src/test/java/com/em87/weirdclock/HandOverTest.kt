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
            // Let any transition of its own finish first.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                2, java.util.concurrent.TimeUnit.SECONDS
            )
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
        val stopwatch = dial(chrono = 0L)

        val restingAtZero = angles(stopwatch)
        assertEquals(0f, restingAtZero.first, 0.001f)

        stopwatch.handOverFrom(clock)
        val handed = angles(stopwatch)
        assertEquals("the hour hand starts on the clock's", angles(clock).first, handed.first, 1f)
        assertNotEquals("and so is not at its own rest yet", 0f, handed.first)
    }

    /** And it arrives, rather than staying where it was handed. */
    @Test
    fun `and travels to its own positions`() {
        val clock = dial()
        val stopwatch = dial(chrono = 0L)
        stopwatch.handOverFrom(clock)
        assertNotEquals(0f, angles(stopwatch).first)

        // Long enough for the seven hundred milliseconds of travel.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            2, java.util.concurrent.TimeUnit.SECONDS
        )
        val arrived = angles(stopwatch)
        assertEquals("a stopwatch at zero points at twelve", 0f, arrived.first, 0.001f)
        assertEquals(0f, arrived.second, 0.001f)
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
        assertTrue("and no longer where it started", kotlin.math.abs(midway - angles(clock).first) > 0.5f)
    }
}
