package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A clock with no minute hand.
 *
 * Which is a clock read to the nearest hour, and was how a great many of
 * them were read for a great many centuries. The switch is one line; what
 * is worth testing is everything that has to agree with it — a hand that
 * is not drawn must also not be grabbable, not fall off the dial when the
 * phone is knocked, not collide with the debris, and not be offered to a
 * screen reader. Each of those used to carry its own copy of "unless the
 * second hand is switched off", and a dozen copies of a rule is a dozen
 * places for the next hand to be forgotten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinuteHandTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun dial(): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1600)
        }
    }

    /** The predicate every other question about a hand goes through. */
    @Test
    fun `the hour hand cannot be turned off and the other two can`() {
        val clock = dial()
        assertTrue(clock.handIsOn(ClockView.Hand.HOUR))
        assertTrue(clock.handIsOn(ClockView.Hand.MINUTE))
        assertTrue(clock.handIsOn(ClockView.Hand.SECOND))

        clock.showMinuteHand = false
        assertFalse("the minute hand stayed on", clock.handIsOn(ClockView.Hand.MINUTE))
        assertTrue(
            "turning the minute hand off took the hour hand with it",
            clock.handIsOn(ClockView.Hand.HOUR)
        )
        assertTrue(
            "and the second hand",
            clock.handIsOn(ClockView.Hand.SECOND)
        )

        clock.showMinuteHand = true
        clock.showSecondHand = false
        assertFalse(clock.handIsOn(ClockView.Hand.SECOND))
        assertTrue(clock.handIsOn(ClockView.Hand.MINUTE))
    }

    /**
     * A hand that is not there is not drawn.
     *
     * Counted as the difference between two frames rather than as lit
     * pixels against the background, which is what the first version of
     * this did — and the view paints its whole face opaque, so "differs
     * from the background" came to the same number every time and the test
     * passed a dial with the hand still on it.
     */
    @Test
    fun `switching the minute hand off takes it off the glass`() {
        val clock = dial().apply { showSecondHand = false }
        val with = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)
        val without = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)

        clock.draw(Canvas(with))
        val unchanged = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)
        clock.draw(Canvas(unchanged))
        assertEquals(
            "two frames of a stopped dial are not the same picture, so this " +
                "measures nothing",
            0, differences(with, unchanged)
        )

        clock.showMinuteHand = false
        clock.draw(Canvas(without))
        val moved = differences(with, without)
        assertTrue("the minute hand was still drawn ($moved pixels changed)", moved > 500)

        with.recycle(); without.recycle(); unchanged.recycle()
    }

    /** How many pixels the two pictures disagree about. */
    private fun differences(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) n++
            }
        }
        return n
    }

    /** And it is not there to be taken hold of either. */
    @Test
    fun `a hand that is not drawn cannot be grabbed`() {
        val clock = dial().apply { showSecondHand = false }
        val box = clock.handBounds(ClockView.Hand.MINUTE)!!
        clock.showMinuteHand = false
        assertNull(
            "a hand that is not on the dial still has somewhere to be touched",
            clock.handBounds(ClockView.Hand.MINUTE)
        )
        clock.grabHandNear(box.exactCenterX(), box.exactCenterY())
        assertFalse(
            "the invisible minute hand took the touch",
            clock.isDisarranged()
        )
    }

    /** Nor does it fall off a dial it is not on. */
    @Test
    fun `a hand that is not drawn does not fall`() {
        val clock = dial().apply {
            showMinuteHand = false
            showSecondHand = false
        }
        clock.knockHandsOff()
        assertEquals(
            "something other than the hour hand ended up on the floor",
            listOf(ClockView.Hand.HOUR),
            ClockView.Hand.entries.filter { clock.isFallenForTest(it) }
        )
    }

    /** And a screen reader is not told about it. */
    @Test
    fun `a hand that is not drawn is not read out`() {
        val clock = dial().apply { showMinuteHand = false }
        assertFalse(
            "a hand nobody can see was offered to somebody who cannot see any of them",
            ClockView.Hand.MINUTE in clock.spokenHands()
        )
    }
}
