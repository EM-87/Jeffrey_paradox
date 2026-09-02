package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The colon that swells instead of blinking, measured on the glass.
 *
 * It shipped without a test and it did not work. The arithmetic was right
 * — [DigitalReadout.breath] has always given back a cosine — and the
 * drawing was right, and between the two of them was a face that asked to
 * be redrawn once a second. So it was drawn at the top of every second,
 * which is exactly where the swell is at its fullest, and a setting whose
 * whole purpose is that something moves produced a colon sitting at full
 * brightness doing nothing at all. Both halves are checked here: that the
 * face asks for the frames, and that the dots really are dimmer halfway
 * through the second when it gets them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColonBreathTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** Twenty past six, on the second. */
    private fun sixTwenty(): Long =
        java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 18, 20, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val w = 1080
    private val h = 700

    private fun face(breathes: Boolean, into: Long): DigitalClockView =
        DigitalClockView(context).apply {
            theme = ClockThemes.MIDNIGHT
            style = DigitStyle.SEGMENT
            script = DigitScript.ARABIC
            hour24 = true
            showSeconds = false
            showDate = false
            showWeekday = false
            blinkColon = true
            breathingColon = breathes
            atMs = sixTwenty() + into
            measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, w, h)
        }

    private fun shoot(view: View): Bitmap {
        val map = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(map))
        return map
    }

    /** How much light is on the glass altogether. */
    private fun light(map: Bitmap): Long {
        var sum = 0L
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val p = map.getPixel(x, y)
                sum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)).toLong()
            }
        }
        return sum
    }

    /**
     * The frames to breathe with.
     *
     * This is the half that was missing. Measured as the rule rather than
     * by counting frames, because a view that is not in a window never
     * draws under Robolectric and the count would be a happy zero whatever
     * the rule said.
     */
    @Test
    fun `a breathing colon asks for more than one frame a second`() {
        val delay = face(breathes = true, into = 0L).frameDelayForTest()
        assertTrue("one frame every ${delay}ms is not a breath", delay in 1L..50L)
    }

    /** And a blinking one still does not: it changes on the second. */
    @Test
    fun `a blinking colon still asks for one a second`() {
        val delay = face(breathes = false, into = 0L).frameDelayForTest()
        assertTrue("a blink does not need $delay ms frames", delay > 50L)
    }

    /** Halfway through the second the dots are down to a quarter. */
    @Test
    fun `the dots really do fade across the second`() {
        val top = light(shoot(face(breathes = true, into = 0L)))
        val half = light(shoot(face(breathes = true, into = 500L)))
        assertTrue(
            "the face was no dimmer at the half second: $top then $half",
            half < top
        )
    }

    /** And a colon that only blinks is unchanged inside the same second. */
    @Test
    fun `a blinking colon holds still inside its second`() {
        val top = light(shoot(face(breathes = false, into = 0L)))
        val half = light(shoot(face(breathes = false, into = 500L)))
        assertEquals("a blink changed halfway through its own second", top, half)
    }
}
