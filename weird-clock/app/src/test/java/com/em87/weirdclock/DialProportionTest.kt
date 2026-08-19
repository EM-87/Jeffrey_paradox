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
 * A square clock is a clock in a square case.
 *
 * The hands were made to fit the inscribed circle, which was right and only
 * half the job: everything else on the face went on being sized against the
 * whole dial. So a triangular clock had half a hand carrying the same weight
 * of ink, numerals nearly touching each other, and marks strung along each
 * slope — crowding at every corner, thinning out along every side — with the
 * numerals sitting on a circle above them and agreeing with none of it.
 *
 * The case is a polygon. Everything you read the time off is a circle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DialProportionTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun dial(shape: ClockView.DialShape): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            dialShape = shape
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 900, 900)
        }
    }

    /** How wide the minute hand is drawn, in pixels. */
    private fun handWidth(clock: ClockView): Float =
        clock.mountedHands().minOf { it.halfWidth }

    /**
     * A hand half as long is not drawn at full thickness.
     *
     * The triangle is the case that shows it: its inscribed circle is half
     * the width of the dial, so a hand of unchanged thickness carries twice
     * the ink per unit of length that the same hand does on a circle.
     */
    @Test
    fun `a narrower face gets narrower hands`() {
        val round = handWidth(dial(ClockView.DialShape.CIRCLE))
        val square = handWidth(dial(ClockView.DialShape.SQUARE))
        val triangle = handWidth(dial(ClockView.DialShape.TRIANGLE))
        assertTrue("a square's hands are as thick as a circle's", square < round)
        assertTrue("and a triangle's are no thinner than a square's", triangle < square)
    }

    /**
     * The numerals are the same size relative to the circle they sit on,
     * whatever shape the case is.
     *
     * Against the inscribed circle and not against the dial's own radius:
     * a polygon is drawn with a *larger* radius than a circle in the same
     * square, to make up for its corners taking the space, so comparing
     * the two radii answers a question about the case rather than about
     * what is written on the face.
     */
    @Test
    fun `the numerals are the same size against the circle they sit on`() {
        val sizes = ClockView.DialShape.entries.map { shape ->
            val clock = dial(shape)
            val cx = clock.width / 2f
            val cy = clock.height / 2f
            val ring = clock.numeralPositionForTest(12).let {
                hypot(it.x - cx, it.y - cy)
            }
            shape to clock.numeralSizeForTest() / ring
        }
        val round = sizes.first { it.first == ClockView.DialShape.CIRCLE }.second
        for ((shape, ratio) in sizes) {
            assertEquals(
                "$shape writes its numerals at a different size for its face",
                round, ratio, round * 0.02f
            )
        }
    }

    /**
     * And what you read the time off is round, whatever the case is.
     *
     * Every numeral the same distance from the middle. Laid on the outline
     * they are not: on a triangle three of them string along each slope,
     * the ones near a corner half again as far out as the one in the
     * middle of a side, and the hour hand points at whichever is nearest
     * rather than at the hour.
     */
    @Test
    fun `the numerals sit on a circle whatever shape the case is`() {
        for (shape in ClockView.DialShape.entries) {
            val clock = dial(shape)
            val cx = clock.width / 2f
            val cy = clock.height / 2f
            val out = (1..12).map { hour ->
                val at = clock.numeralPositionForTest(hour)
                hypot(at.x - cx, at.y - cy)
            }
            assertEquals(
                "$shape strings its numerals along the outline",
                out.min(), out.max(), 0.5f
            )
        }
    }
}
