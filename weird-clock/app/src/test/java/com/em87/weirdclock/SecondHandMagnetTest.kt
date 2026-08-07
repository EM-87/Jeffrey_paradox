package com.em87.weirdclock

import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a hand's magnets are allowed to pull.
 *
 * Nowhere but the precision band — the ring out between the numerals and
 * the rim, where a finger goes for fine adjustment. The hour and minute
 * hands have always known that: take hold of one by the body and whip it
 * round and it spins free, with no detents and no buzzing. The second hand
 * had the rule left out, so holding it anywhere at all snapped it every
 * five seconds of dial and fired the haptic each time, the whole way
 * round.
 *
 * It went unnoticed because the screen this winding was written for —
 * setting a time on the clock face — is the one screen with no second hand
 * on it. The countdown has one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecondHandMagnetTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /** Out among the numerals, and in against the hands' own bodies. */
    private val byTheMarks = 300f
    private val byTheBody = 90f

    private fun countdown(startMs: Long): ClockView = ClockView(context).apply {
        chronoProvider = { startMs }
        chronoSettable = true
        showSecondHand = true
        magnetProfile = ClockView.MagnetProfile.COUNTDOWN
        measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 720, 720)
        // Giving a dial a provider starts a transition, and its hands are
        // still travelling in from wherever they were until it is over —
        // so a grab aimed at where they will be catches nothing at all.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            2, java.util.concurrent.TimeUnit.SECONDS
        )
        handAngleForTest(ClockView.Hand.HOUR)
    }

    /** Twelve o'clock is zero and the degrees run clockwise, as on a dial. */
    private fun at(degrees: Float, radius: Float): Pair<Float, Float> {
        val rad = Math.toRadians(degrees.toDouble())
        return (360f + radius * sin(rad)).toFloat() to (360f - radius * cos(rad)).toFloat()
    }

    /**
     * Takes hold of a hand where it stands, winds it [turn] degrees with
     * the finger held [radius] out, and reports what the dial is set to.
     */
    private fun wind(
        startMs: Long,
        expected: ClockView.Hand,
        grabAt: Float,
        grabRadius: Float,
        turn: Float,
        radius: Float
    ): Long {
        val dial = countdown(startMs)
        val (grabX, grabY) = at(grabAt, grabRadius)
        dial.grabHandNear(grabX, grabY)
        assertEquals("the wrong hand came away", expected, dial.draggedHandForTest())
        // In steps, because winding accumulates the angle turned rather
        // than reading it off the finger's final position.
        var step = 0f
        while (step < turn) {
            step = minOf(step + 6f, turn)
            val (x, y) = at(grabAt + step, radius)
            dial.dragToForTest(x, y)
        }
        return dial.settingValueMs() ?: -1L
    }

    // ------------------------------------------------------- the second hand

    /** Six seconds round: the magnet at five is in reach and takes it. */
    @Test
    fun `out by the marks the second hand's magnets pull`() {
        assertEquals(
            5_000L,
            wind(0L, ClockView.Hand.SECOND, 0f, 260f, turn = 36f, radius = byTheMarks)
        )
    }

    /**
     * The same six seconds, held by the hand's own body: no magnet and no
     * buzz — one flag decides both — and it sits where it was put.
     */
    @Test
    fun `held by the body the second hand spins free`() {
        assertEquals(
            6_000L,
            wind(0L, ClockView.Hand.SECOND, 0f, 260f, turn = 36f, radius = byTheBody)
        )
    }

    // ------------------------------------------------------- and its siblings

    /**
     * The same pair of measurements on the minute hand, which is the
     * comparison the bug report was making: five and a half minutes on from
     * five, which the five-minute grid is close enough to swallow.
     *
     * Deliberately not a round number. Ten minutes exactly is where a free
     * drag lands *and* where the magnet would put it, so a test that wound
     * there would report the magnet working whether or not it ran.
     */
    @Test
    fun `the minute hand does the same, which it always did`() {
        val start = 5 * 60_000L
        assertEquals(
            "out by the marks it is pulled back to ten minutes",
            10 * 60_000L,
            wind(start, ClockView.Hand.MINUTE, 30f, 200f, turn = 33f, radius = byTheMarks)
        )
        assertEquals(
            "and by the body it stays where it was put",
            10 * 60_000L + 30_000L,
            wind(start, ClockView.Hand.MINUTE, 30f, 200f, turn = 33f, radius = byTheBody)
        )
    }
}
