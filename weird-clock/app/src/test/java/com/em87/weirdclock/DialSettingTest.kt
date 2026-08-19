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

    /**
     * Winding a Lasts, the hands show the hour the thing ends at and the
     * readout showed the same hour in digits — the same fact twice, and not
     * the one being chosen. What is being chosen is the length.
     */
    @Test
    fun `the readout of a length is the length, not the hour it ends at`() {
        var value = 18 * hour
        val v = dial().apply {
            chronoProvider = { value }
            chronoSettable = true
            chronoWrapsDay = true
            magnetOrigin = 18 * hour
        }
        assertEquals("nothing wound yet, so no length yet", 0L, v.readoutForTest())
        value = 18 * hour + 20 * minute
        assertEquals(20 * minute, v.readoutForTest())
        value = 20 * hour + 30 * minute
        assertEquals(2 * hour + 30 * minute, v.readoutForTest())
        // Over midnight the length keeps counting forward rather than
        // going negative: an event cannot last minus twenty minutes.
        value = hour
        assertEquals(7 * hour, v.readoutForTest())
    }

    /** A time of day still reads as the time of day, hands and digits agreeing. */
    @Test
    fun `the readout of a time is the time`() {
        val v = dial().apply {
            chronoProvider = { 9 * hour + 45 * minute }
            chronoSettable = true
            chronoWrapsDay = true
            magnetOrigin = 0L
        }
        assertEquals(9 * hour + 45 * minute, v.readoutForTest())
    }

    // -------------------------------------------------- the digital readout

    private fun settingDial(value: Long, origin: Long = 0L): ClockView =
        dial().apply {
            chronoProvider = { value }
            chronoSettable = true
            chronoWrapsDay = true
            magnetOrigin = origin
        }

    /**
     * Setting a time, the readout is the time — hours and minutes, and
     * nothing that moves the digits sideways.
     *
     * It was the chronograph's, which swaps units as the value grows:
     * hundredths under the hour, seconds over it. So the number shifted
     * along the moment you wound past one o'clock, under the eye of someone
     * reading it precisely because their finger was covering the hand — and
     * offered hundredths of a second to a person setting an alarm.
     */
    @Test
    fun `setting a time reads in hours and minutes`() {
        assertEquals("07:20", settingDial(7 * hour + 20 * minute).readoutText())
        assertEquals("00:05", settingDial(5 * minute).readoutText())
        assertEquals("23:59", settingDial(23 * hour + 59 * minute).readoutText())
        // Seconds are not shown, so they cannot flicker either.
        assertEquals("07:20", settingDial(7 * hour + 20 * minute + 43_000L).readoutText())
    }

    /** And a length reads as a length, in the same steady two groups. */
    @Test
    fun `setting a length reads in hours and minutes too`() {
        val fromSix = { ms: Long -> settingDial(ms, origin = 18 * hour).readoutText() }
        assertEquals("00:30", fromSix(18 * hour + 30 * minute))
        assertEquals("01:00", fromSix(19 * hour))
        assertEquals("02:15", fromSix(20 * hour + 15 * minute))
    }

    /**
     * Two groups, so two unit marks. Three would leave one hanging off the
     * end of a number that has no third group.
     */
    @Test
    fun `the units follow the format`() {
        // Was "hours and minutes only" here, back when a dial being set
        // wore a degree and a prime. It wears nothing now: the marks say
        // which unit a pair of digits is standing in, and on a time being
        // wound that never changes, so they only added a ° over a quarter
        // past seven. Changed because the behaviour was, not to quieten a
        // failure — see ReadoutUnitsTest, which is where the rule lives.
        assertEquals("", settingDial(7 * hour).readoutUnits().joinToString(""))
        // The countdown keeps the chronograph's three, and its centiseconds
        // with them: timing something is what they are for.
        val countdown = dial().apply {
            chronoProvider = { 90_000L }
            chronoSettable = true
            chronoWrapsDay = false
        }
        assertEquals(3, countdown.readoutUnits().size)
        assertEquals("01:30:00", countdown.readoutText())
    }

    /**
     * The digits do not shift sideways as the value crosses an hour, which
     * is the visible symptom the whole change is about: the same number of
     * groups either side of it.
     */
    @Test
    fun `the readout does not change shape as it crosses an hour`() {
        val before = settingDial(59 * minute).readoutText()!!
        val after = settingDial(hour + minute).readoutText()!!
        assertEquals(before.length, after.length)
        assertEquals(before.count { it == ':' }, after.count { it == ':' })
    }

    // ------------------------------------------------- the stop at a full day

    private fun touch(v: ClockView, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = android.view.MotionEvent.obtain(at, at, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    /**
     * One gesture: take hold of a hand and turn it through each leg in turn.
     *
     * Legs rather than separate calls because taking hold again is what
     * hides the bug — a fresh grab resets what has been turned so far, so a
     * push into the stop and a pull back off it have to be the same
     * uninterrupted gesture to prove anything.
     *
     * Real touch events, because what is on trial is the thing that
     * accumulates degrees as they arrive; a helper that added them up
     * itself would only be checking my arithmetic against my arithmetic.
     */
    private fun windBy(
        v: ClockView,
        hand: ClockView.Hand,
        vararg legs: Double
    ) {
        // Any hand-over between one set of angles and another has to be
        // over before the hand can be found: while one is running the hands
        // are somewhere between where they were and where they belong, and
        // a test that reaches for one lands on empty dial about half the
        // time. Which is how this test first "failed".
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        val cx = v.width / 2f
        val cy = v.height / 2f
        // Where the hand actually is, asked of the view rather than worked
        // out here: the middle of its box is the middle of the hand, and a
        // second copy of the angle arithmetic in the test would only ever
        // agree with itself.
        val box = v.handBounds(hand)!!
        val startX = box.exactCenterX()
        val startY = box.exactCenterY()
        val grip = kotlin.math.hypot(startX - cx, startY - cy)
        val startDeg = Math.toDegrees(
            kotlin.math.atan2((startY - cy).toDouble(), (startX - cx).toDouble())
        )
        fun at(deg: Double): Pair<Float, Float> {
            val a = Math.toRadians(deg)
            return (cx + grip * kotlin.math.cos(a)).toFloat() to
                (cy + grip * kotlin.math.sin(a)).toFloat()
        }
        var here = startDeg
        touch(v, android.view.MotionEvent.ACTION_DOWN, startX, startY)
        for (leg in legs) {
            val end = here + leg
            val step = if (leg >= 0) 20.0 else -20.0
            // Twenty degrees at a time: small enough that the half-turn
            // rule which decides which way a finger went is never in doubt.
            while (kotlin.math.abs(end - here) > 0.0001) {
                here = if (kotlin.math.abs(end - here) < kotlin.math.abs(step)) end
                else here + step
                val p = at(here)
                touch(v, android.view.MotionEvent.ACTION_MOVE, p.first, p.second)
            }
        }
    }

    /** A countdown dial with its hands at [ms], ready to be wound. */
    private fun countdownAt(ms: Long): ClockView = dial().apply {
        chronoProvider = { ms }
        chronoSettable = true
        chronoWrapsDay = false
        showSecondHand = false
    }

    /**
     * The hands hit something at twenty-four hours.
     *
     * The value was capped when it was committed and not before, so the
     * hands went on turning past the end into hours that were quietly
     * thrown away on release. Now they stand: a countdown cannot be wound
     * past a day, and the dial says so while it is being wound rather than
     * after the finger comes off.
     */
    @Test
    fun `a countdown will not be wound past a day`() {
        val v = countdownAt(3 * hour)
        // The hour hand starts at three o'clock. Two and a half turns of it
        // is thirty hours, which is well past the end.
        windBy(v, ClockView.Hand.HOUR, 900.0)
        assertEquals(
            "the hands went on past a full day",
            24 * hour, v.chronoShownForTest()
        )
    }

    /**
     * And it is a stop, not a jam.
     *
     * The turn beyond the end has to be given back rather than banked. Left
     * banked, a hand pushed six hours into the wall would need six hours of
     * unwinding before it moved at all — which feels like a dial that has
     * broken rather than one that has reached its limit.
     */
    @Test
    fun `winding back off the stop moves at once`() {
        val v = countdownAt(3 * hour)
        // One gesture: hard into the stop, then thirty degrees back, which
        // is one hour of a twelve-hour hand.
        windBy(v, ClockView.Hand.HOUR, 900.0, -30.0)
        assertEquals(
            "the hours wound past the end were banked against the stop",
            23 * hour, v.chronoShownForTest()
        )
    }

    /**
     * A time of day has no such stop: it comes round again.
     *
     * The alarm dial is wound the same way, and midnight is a place on it
     * rather than an end. A stop there would put one in the morning out of
     * reach of a hand being turned forwards.
     */
    @Test
    fun `a time of day is not stopped at midnight`() {
        val v = dial().apply {
            chronoProvider = { 22 * hour }
            chronoSettable = true
            chronoWrapsDay = true
            showSecondHand = false
        }
        assertNull("a time of day was given an end", v.windingStopSeconds())
        windBy(v, ClockView.Hand.HOUR, 180.0)
        assertEquals(
            "the dial stuck at midnight instead of going round",
            4 * hour, v.chronoShownForTest()
        )
    }
}
