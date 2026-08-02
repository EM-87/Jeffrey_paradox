package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Winding a time onto the dial: where the magnets are, and where the day ends.
 *
 * Both of these are felt rather than seen — a detent under the finger, a
 * number that stops climbing — so neither shows up in a screenshot and both
 * are easy to believe without checking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DialSettingTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    private fun dial(): ClockView =
        ClockView(ApplicationProvider.getApplicationContext()).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
        }

    /**
     * The bug this fixes: a Lasts wound from six in the evening had its
     * detents measured from midnight, so 18:00 fell in the "past two hours,
     * whole hours only" band. There was no magnet anywhere near "and it
     * lasts twenty minutes" — the very durations the progression exists for.
     */
    @Test
    fun `a length's magnets count from the hour it starts at`() {
        val v = dial().apply {
            magnetProfile = ClockView.MagnetProfile.COUNTDOWN
            magnetOrigin = 18 * hour
        }
        // Twenty minutes in, the grid is five minutes: 18:20 is a detent.
        assertEquals(18 * hour + 20 * minute, v.magnetFor(18 * hour + 20 * minute, null))
        // Fifteen minutes in, likewise, and a minute either side is pulled in.
        assertEquals(18 * hour + 15 * minute, v.magnetFor(18 * hour + 15 * minute + 30_000L, null))
        // An hour in, the grid has widened to quarters: 19:00 is a detent.
        assertEquals(19 * hour, v.magnetFor(19 * hour, null))
        // And the fine end is still fine: three minutes in, minute detents.
        assertEquals(18 * hour + 3 * minute, v.magnetFor(18 * hour + 3 * minute, null))
    }

    /** Without an origin it behaves exactly as the countdown always did. */
    @Test
    fun `a countdown's magnets still count from zero`() {
        val v = dial().apply {
            magnetProfile = ClockView.MagnetProfile.COUNTDOWN
            magnetOrigin = 0L
        }
        assertEquals(3 * minute, v.magnetFor(3 * minute, null))
        assertEquals(20 * minute, v.magnetFor(20 * minute, null))
        assertEquals(3 * hour, v.magnetFor(3 * hour, null))
        // Between detents, nothing: 3h07 is not near any hour mark.
        assertNull(v.magnetFor(3 * hour + 7 * minute, null))
    }

    /**
     * The same instant is a detent from one origin and not from the other,
     * which is the whole difference and the thing worth pinning down.
     */
    @Test
    fun `the origin is what moves the detents`() {
        val fromMidnight = dial().apply {
            magnetProfile = ClockView.MagnetProfile.COUNTDOWN
        }
        val fromSix = dial().apply {
            magnetProfile = ClockView.MagnetProfile.COUNTDOWN
            magnetOrigin = 18 * hour
        }
        // 18:20 — nothing from midnight (hours-only that far out), a five
        // minute detent from six.
        assertNull(fromMidnight.magnetFor(18 * hour + 20 * minute, null))
        assertNotNull(fromSix.magnetFor(18 * hour + 20 * minute, null))
    }

    /**
     * A time of day lives in a day. Wound past twenty-four hours the dial
     * reads 00:00 rather than counting on into a twenty-fifth hour nobody
     * can see on a twelve-hour face.
     */
    @Test
    fun `a time being set wraps at the end of the day`() {
        var value = 0L
        val v = dial().apply {
            chronoProvider = { value }
            chronoSettable = true
            chronoWrapsDay = true
        }
        value = 23 * hour
        assertEquals(23 * hour, v.settingValueMs())
        value = 24 * hour
        assertEquals("twenty-four hours is midnight", 0L, v.settingValueMs())
        value = 25 * hour + 30 * minute
        assertEquals(hour + 30 * minute, v.settingValueMs())
        // And backwards off the bottom comes round the other way, which is
        // what a clock does.
        value = -30 * minute
        assertEquals(23 * hour + 30 * minute, v.settingValueMs())
    }

    /** A countdown is a length, not a time, and is allowed to exceed a day. */
    @Test
    fun `a countdown is not wrapped`() {
        var value = 26 * hour
        val v = dial().apply {
            chronoProvider = { value }
            chronoSettable = true
            chronoWrapsDay = false
        }
        assertEquals(26 * hour, v.settingValueMs())
        value = -5 * minute
        assertTrue("a countdown may go negative while playing", v.settingValueMs()!! < 0)
    }
}
