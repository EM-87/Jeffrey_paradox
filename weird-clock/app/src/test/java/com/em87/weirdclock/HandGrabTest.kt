package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** A plain clock, hands wherever the wall clock has them. */
    private fun realClock(smooth: Boolean = false): ClockView =
        ClockView(ApplicationProvider.getApplicationContext()).apply {
            showSecondHand = true
            smoothSeconds = smooth
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            settle()
        }

    /**
     * Takes hold of a hand where that hand actually is.
     *
     * On a clock showing the real time the hands are wherever the hour
     * happens to put them, and reaching straight up catches whatever is
     * pointing up — which is how three of these tests came to grab the
     * wrong hand, or nothing at all.
     */
    private fun grabHand(view: ClockView, hand: ClockView.Hand, fraction: Float) {
        grabAlong(view, view.handAngleForTest(hand), fraction)
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

    /**
     * And it stays loose after the finger lifts.
     *
     * The point of winding the hands forward is to read the day's events off
     * the face, which takes a moment — and letting go used to re-engage the
     * gearing and set the second hand spinning at whatever offset you had
     * stopped at. It stays loose for as long as the dial is showing a time
     * that is not now.
     */
    @Test
    fun `a clock left wound forward does not spin its second hand`() {
        val v = realClock()
        val real = v.secondAngleForTest()
        // Wind the minute hand three hours and seventeen seconds forward,
        // then let go: no hand is held, but the dial is not showing now.
        grabHand(v, ClockView.Hand.MINUTE, 0.62f)
        assertEquals(ClockView.Hand.MINUTE, v.draggedHandForTest())
        v.windForTest(3 * 3600.0 + 17.0)
        v.releaseForTest()
        val wound = v.secondAngleForTest()
        assertEquals(
            "the second hand must still be reading the real clock",
            real, wound, 2f
        )
        // And not where the gearing would have put it: seventeen seconds of
        // wind is a hundred and two degrees of second hand, which is the
        // whole difference between the two behaviours. Without this the
        // test would pass on a dial whose second hand never moved at all.
        val geared = (real + 102f) % 360f
        assertNotEquals("$wound must not be the geared angle $geared", geared, wound)
    }

    /** Back at now, it is an ordinary second hand again. */
    @Test
    fun `back at now the second hand reads the dial again`() {
        val v = realClock()
        val real = v.secondAngleForTest()
        grabHand(v, ClockView.Hand.MINUTE, 0.62f)
        v.windForTest(17.0)
        v.releaseForTest()
        v.windForTest(0.0)
        assertEquals(real, v.secondAngleForTest(), 2f)
    }

    // ------------------------------------------- what the setting dial hides

    /**
     * The tenths hand is the second hand's decoration, and it was drawn for
     * anything with a chrono provider — which the dial that sets an alarm
     * time is. Taking the second hand off that face left the tenths hand
     * spinning there on its own.
     */
    @Test
    fun `the tenths hand goes where the second hand goes`() {
        // A clock: only if the setting asks for it.
        val clock = ClockView(ApplicationProvider.getApplicationContext()).apply {
            showSecondHand = true
        }
        assertFalse(clock.showsFastHand())
        clock.fastHand = ClockView.FastHandMode.TENTHS
        assertTrue(clock.showsFastHand())

        // A chronograph: always, because sub-second motion is the point.
        val chrono = ClockView(ApplicationProvider.getApplicationContext()).apply {
            chronoProvider = { 0L }
            showSecondHand = true
        }
        assertTrue(chrono.showsFastHand())

        // The dial that sets a time: no second hand, so no tenths either.
        val setting = ClockView(ApplicationProvider.getApplicationContext()).apply {
            chronoProvider = { 0L }
            chronoSettable = true
            showSecondHand = false
        }
        assertFalse("nothing sub-second belongs on a face setting an alarm",
            setting.showsFastHand())

        // And the little faces on the alarm cards, which had one all along.
        val mini = ClockView(ApplicationProvider.getApplicationContext()).apply {
            chronoProvider = { 7 * 3_600_000L }
            showSecondHand = false
        }
        assertFalse(mini.showsFastHand())
    }

    /**
     * Winding the second hand itself must spring home with the rest. Asking
     * "is the offset non-zero" could not tell that from winding the minute
     * hand, so the second hand jumped to real time the instant the finger
     * lifted instead of coming back on the spring.
     */
    @Test
    fun `the second hand springs back when it is the one that was wound`() {
        // Smooth seconds, so the reading does not quantise to whole ticks
        // between one call and the next: this test is about a jump of
        // seventeen seconds, and a tick of slack either way is only noise
        // that has to be allowed for.
        val v = realClock(smooth = true)
        val real = v.secondAngleForTest()
        // Take hold of the second hand, out where it lives, and wind it.
        grabHand(v, ClockView.Hand.SECOND, 0.79f)
        assertEquals(ClockView.Hand.SECOND, v.draggedHandForTest())
        v.windForTest(17.0)
        val held = v.secondAngleForTest()
        assertNotEquals("it must follow the finger", real, held)
        // Let go with the offset still there, as the spring does.
        v.releaseForTest()
        val after = v.secondAngleForTest()
        // Within a tick of where it was — the millisecond part is dropped
        // when nothing is animating — and nowhere near where the real clock
        // has got to, which is where the jump used to land it.
        assertTrue("$held then $after", kotlin.math.abs(held - after) < 3f)
        assertTrue("it jumped home to $real", kotlin.math.abs(after - real) > 20f)
    }

    /**
     * A loose second hand still ticks. `useMs` is true whenever anything on
     * the face is animating, and a hand being dragged is animating, so the
     * hand that had come loose to keep telling the time started sweeping
     * smoothly while it did — a different clock, not a quieter one.
     */
    @Test
    fun `a loose second hand ticks rather than sweeping`() {
        val v = realClock()
        grabHand(v, ClockView.Hand.MINUTE, 0.62f)
        assertEquals(ClockView.Hand.MINUTE, v.draggedHandForTest())
        v.windForTest(600.0)
        // Six degrees a second, so a ticking hand always lands on a
        // multiple of six and a sweeping one almost never does.
        val angle = v.secondAngleForTest()
        assertEquals("$angle is not on a whole second", 0f, angle % 6f, 0.001f)
    }

    /**
     * The band past the minute hand's tip exists so the thin second hand is
     * easy to catch. It is radial only — it says "past the tip" and nothing
     * about direction — so on its own it handed over the second hand for a
     * touch on the far side of the dial from it.
     */
    @Test
    fun `the outer band does not hand over a second hand that is elsewhere`() {
        val v = realClock()
        val opposite = (v.handAngleForTest(ClockView.Hand.SECOND) + 180f) % 360f
        assertNotEquals(ClockView.Hand.SECOND, grabAlong(v, opposite, 0.85f))
        // And it still catches it where it really is.
        val w = realClock()
        assertEquals(
            ClockView.Hand.SECOND,
            grabAlong(w, w.handAngleForTest(ClockView.Hand.SECOND), 0.85f)
        )
    }

    /**
     * The ticking used to fall silent for any hand at all, from when the
     * second hand was dragged round by whatever else was being wound. It
     * keeps real time now, so it keeps its voice — except while it is the
     * hand being wound, where the winding fires its own ticks.
     */
    @Test
    fun `only the second hand silences the ticking`() {
        val v = realClock()
        assertFalse(v.isSecondHandGrabbed())
        grabHand(v, ClockView.Hand.MINUTE, 0.62f)
        assertEquals(ClockView.Hand.MINUTE, v.draggedHandForTest())
        assertFalse("winding the minute hand must not silence it", v.isSecondHandGrabbed())

        val w = realClock()
        grabHand(w, ClockView.Hand.SECOND, 0.79f)
        assertEquals(ClockView.Hand.SECOND, w.draggedHandForTest())
        assertTrue(w.isSecondHandGrabbed())
    }
}
