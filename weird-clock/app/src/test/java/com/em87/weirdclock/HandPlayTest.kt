package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Playing with the hands: how far they go before the mechanism gives up,
 * and what they name on the way round.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandPlayTest {

    private fun dial(): ClockView =
        ClockView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
        }

    /**
     * Ten turns for everything was ten turns of the wrong thing. Measured in
     * time travelled, which is what the user is actually doing: two days on
     * the hour hand, half a day on the minute hand, ten minutes on the
     * second hand.
     */
    @Test
    fun `each hand gets a budget of time, not a count of turns`() {
        val v = dial().apply { hoursOnDial = 12 }
        // Two days on a twelve-hour face is four turns.
        assertEquals(1440.0, v.explosionDegrees(ClockView.Hand.HOUR), 0.001)
        // Twelve hours of minute hand is twelve turns.
        assertEquals(4320.0, v.explosionDegrees(ClockView.Hand.MINUTE), 0.001)
        // And the egg stays quick to reach: ten turns of the second hand.
        assertEquals(3600.0, v.explosionDegrees(ClockView.Hand.SECOND), 0.001)
    }

    /**
     * The hour hand's budget is two days of *clock*, so a twenty-four hour
     * face reaches it in half the turns a twelve-hour one does. The rule is
     * about the calendar, not about the geometry.
     */
    @Test
    fun `the hour hand's two days survive a different dial`() {
        assertEquals(720.0, dial().apply { hoursOnDial = 24 }
            .explosionDegrees(ClockView.Hand.HOUR), 0.001)
        assertEquals(2880.0, dial().apply { hoursOnDial = 6 }
            .explosionDegrees(ClockView.Hand.HOUR), 0.001)
    }

    /** Every hand now travels further than the flat ten turns it used to. */
    @Test
    fun `the hour hand goes further than it did, the second hand no less`() {
        val v = dial().apply { hoursOnDial = 12 }
        assertTrue(v.explosionDegrees(ClockView.Hand.MINUTE) > 3600.0)
        assertTrue(v.explosionDegrees(ClockView.Hand.SECOND) >= 3600.0)
    }

    // ------------------------------------------- the hour hand as a reader

    @Test
    fun `an angle on a dot finds it, and one between dots finds nothing`() {
        val v = dial().apply {
            alarmMarkers = listOf(
                DialMark(90f, false, false, "gym"),
                DialMark(210f, true, true, "dentist")
            )
        }
        assertEquals("gym", v.markAtAngle(90f)?.first)
        assertEquals("gym", v.markAtAngle(92f)?.first)
        assertEquals("dentist", v.markAtAngle(210f)?.first)
        assertNull(v.markAtAngle(150f))
        // Just off it is still nothing: a hand two dots away must not name one.
        assertNull(v.markAtAngle(100f))
    }

    /** Anywhere along a wedge names it, not only its leading edge. */
    @Test
    fun `an angle anywhere inside a wedge finds it`() {
        val minuteNow = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        val v = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 40f, false, true, "lunch", minuteNow + 120, minuteNow + 240)
            )
        }
        assertEquals("lunch", v.markAtAngle(1f)?.first)
        assertEquals("lunch", v.markAtAngle(20f)?.first)
        assertEquals("lunch", v.markAtAngle(39f)?.first)
        assertNull(v.markAtAngle(60f))
    }

    /**
     * The part of a wedge the hand has already eaten names nothing: it is
     * not on the face any more, and pointing at it would be pointing at a
     * gap.
     */
    @Test
    fun `the eaten head of a wedge names nothing`() {
        val minuteNow = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        val v = dial().apply {
            // Three quarters spent: only the last ten degrees remain.
            eventArcs = listOf(
                DialArc(0f, 40f, false, true, "meeting", minuteNow - 180, minuteNow + 60)
            )
        }
        assertNull("the head is gone", v.markAtAngle(5f))
        assertNull(v.markAtAngle(25f))
        assertEquals("meeting", v.markAtAngle(35f)?.first)
    }

    /**
     * And none of it happens on a clock that is simply telling the time. A
     * dial that named the day's appointments unprompted would be shouting.
     */
    @Test
    fun `a resting clock names nothing`() {
        val v = dial().apply {
            alarmMarkers = listOf(DialMark(90f, false, false, "gym"))
        }
        v.followHourHand(90f)
        assertNull(v.bubbleLabel())
    }
}
