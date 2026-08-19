package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot

/**
 * A hand is a rod, and a rod does not know what shape its case is.
 *
 * The hands were drawn out to the edge of the face, which on a round one is
 * the same distance in every direction and on a square one is half again as
 * far at the corners. So a square clock's minute hand grew as it swept
 * towards each corner and shrank back between them — four times an hour,
 * and impossible to unsee once noticed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class HandLengthTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun dial(shape: ClockView.DialShape): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            dialShape = shape
            chronoProvider = { 0L }
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1600)
        }
    }

    /** How far the minute hand's tip is from the middle, right now. */
    private fun minuteHandLength(clock: ClockView): Float {
        val bar = clock.mountedHands().let { bars ->
            // The three hands are all at once here; the middle one by
            // length is the minute hand, and it is the one that sweeps a
            // whole face in an hour.
            bars.sortedBy { hypot(it.x2 - clock.width / 2f, it.y2 - clock.height / 2f) }[1]
        }
        return hypot(bar.x2 - clock.width / 2f, bar.y2 - clock.height / 2f)
    }

    /**
     * On a square face the hand is the same length wherever it points.
     *
     * Walked round a whole revolution rather than sampled at a corner and a
     * flat, because a rule that happens to hold at two angles is not a
     * rule.
     */
    @Test
    fun `a square face does not stretch its hands into the corners`() {
        val clock = dial(ClockView.DialShape.SQUARE)
        var shortest = Float.MAX_VALUE
        var longest = 0f
        for (minute in 0 until 60) {
            clock.chronoProvider = { minute * 60_000L }
            val len = minuteHandLength(clock)
            if (len < shortest) shortest = len
            if (len > longest) longest = len
        }
        assertEquals(
            "the hand is ${longest - shortest}px longer at one angle than another",
            shortest, longest, 0.5f
        )
    }

    /** And every other shape the dial can take. */
    @Test
    fun `no shape stretches its hands`() {
        for (shape in ClockView.DialShape.entries) {
            val clock = dial(shape)
            var shortest = Float.MAX_VALUE
            var longest = 0f
            for (minute in 0 until 60) {
                clock.chronoProvider = { minute * 60_000L }
                val len = minuteHandLength(clock)
                if (len < shortest) shortest = len
                if (len > longest) longest = len
            }
            assertEquals("$shape stretches its hands", shortest, longest, 0.5f)
        }
    }

    /**
     * And a round face is untouched by the fix.
     *
     * Which is why nobody saw this for so long: on a circle the edge and
     * the inscribed circle are the same line, so the old arithmetic and the
     * new one give the same answer to the pixel.
     */
    @Test
    fun `a round face's hands are the length they always were`() {
        val round = dial(ClockView.DialShape.CIRCLE)
        round.chronoProvider = { 0L }
        val len = minuteHandLength(round)
        assertTrue(
            "a minute hand that reaches nowhere near the rim: $len of " +
                "${round.dialRadiusForTest()}",
            len > round.dialRadiusForTest() * 0.7f
        )
        assertTrue("and one that overshoots it", len <= round.dialRadiusForTest())
    }

    /**
     * The hand still reaches the edge where the edge is nearest.
     *
     * The fix could have been "make every hand short enough for the worst
     * case", which would leave a square clock with stubby hands and a lot
     * of empty glass. It is the inscribed circle, so the hand touches the
     * middle of each side exactly as a real square clock's does.
     */
    @Test
    fun `a square face's hands still reach the middle of a side`() {
        val square = dial(ClockView.DialShape.SQUARE)
        // Twelve o'clock: straight up, at the middle of the top side.
        square.chronoProvider = { 0L }
        val len = minuteHandLength(square)
        val toTheSide = square.dialRadiusForTest() * kotlin.math.cos(Math.PI / 4).toFloat()
        assertTrue(
            "the hand stops $len short of a side that is $toTheSide away",
            len > toTheSide * 0.7f
        )
    }
}
