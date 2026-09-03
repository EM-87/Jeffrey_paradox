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
 * The bedside clock's two switches, on the glass rather than in the model.
 *
 * [BedsideTest] proves the activity hands the switches to the face. That is
 * a different claim from the seconds being on the screen: the face has its
 * own opinion about what fits, and a row one cell too wide for a landscape
 * window could quietly drop something and still report it in its cells.
 * Reported twice as not working, so it is measured twice — once in the
 * wiring, once in the ink.
 *
 * Counted as shapes rather than as quantity of light, which is the trap
 * this test fell into on its first draft: adding the date makes everything
 * on the face *smaller*, so "there is more ink now" is false when the
 * feature works and true when it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BedsideInkTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** A landscape window, the shape a phone on its side gives. */
    private val w = 1600
    private val h = 720

    private fun bedside(seconds: Boolean, date: Boolean): DigitalClockView =
        DigitalClockView(context).apply {
            theme = ClockThemes.MIDNIGHT
            style = DigitStyle.SEGMENT
            script = DigitScript.ARABIC
            hour24 = true
            showWeekday = false
            fullScreen = true
            bedsideSeconds = seconds
            bedsideDate = date
            atMs = java.util.Calendar.getInstance().apply {
                set(2026, java.util.Calendar.AUGUST, 27, 18, 19, 44)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, w, h)
        }

    private fun shoot(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }

    /**
     * Lit, meaning a segment that is on.
     *
     * Well above the unlit ghosts behind them, which are drawn at about a
     * quarter of this and are on the screen whatever the switches say.
     */
    private fun lit(map: Bitmap, x: Int, y: Int): Boolean {
        val p = map.getPixel(x, y)
        return (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) > 300
    }

    /** How many separated bands of lit pixels there are down the face. */
    private fun rows(map: Bitmap): Int {
        var bands = 0
        var inBand = false
        for (y in 0 until map.height) {
            val any = (0 until map.width step 2).any { lit(map, it, y) }
            if (any && !inBand) bands++
            inBand = any
        }
        return bands
    }

    /** And how many separated columns of them, across the widest band. */
    private fun columns(map: Bitmap): Int {
        var bands = 0
        var inBand = false
        for (x in 0 until map.width) {
            val any = (0 until map.height step 2).any { lit(map, x, it) }
            if (any && !inBand) bands++
            inBand = any
        }
        return bands
    }

    /**
     * Seconds asked for are seconds drawn.
     *
     * Counted as columns of light: 18:19 lights four digits and one pair of
     * dots, and 18:19:44 lights six and two. The exact numbers depend on
     * which segments the digits happen to use — a 1 is one column, an 8 is
     * three — so what is asserted is that there are meaningfully more of
     * them, and that the same picture without the switch has fewer.
     */
    @Test
    fun `the bedside seconds reach the glass`() {
        val bare = columns(shoot(bedside(seconds = false, date = false)))
        val ticking = columns(shoot(bedside(seconds = true, date = false)))
        assertTrue(
            "the seconds put nothing on the screen: $bare columns then $ticking",
            ticking >= bare + 3
        )
    }

    /**
     * And the date is a second row under the time.
     *
     * One band of light without it and two with, which is the whole of
     * what "there is a date under the clock" means and is not fooled by
     * the digits getting smaller to make room.
     */
    @Test
    fun `the bedside date reaches the glass`() {
        // A row of seven-bar digits is several bands of its own — the top
        // bars, the middles, the bottoms — so the count is not one and two.
        // What the date adds is exactly one more, under all of them.
        val bare = rows(shoot(bedside(seconds = false, date = false)))
        val dated = rows(shoot(bedside(seconds = false, date = true)))
        assertEquals("the date was not drawn under the time", bare + 1, dated)
    }

    /** And both at once, which is the most the face is ever asked for. */
    @Test
    fun `both at once still fit inside the glass`() {
        val both = bedside(seconds = true, date = true)
        assertTrue("the seconds went missing", both.cellsForTest().count { it is Cell.Colon } > 1)
        assertTrue("the date went missing", both.datedForTest())
        val map = shoot(both)
        assertEquals(
            "the date is not a row of its own under the time",
            rows(shoot(bedside(seconds = true, date = false))) + 1, rows(map)
        )
        // Nothing touching either edge: a block centred on a height it
        // does not have hangs its date through the bottom of the screen,
        // which is a date that is on and cannot be seen.
        assertTrue(
            "the block is drawn off the top of the screen",
            (0 until map.width step 2).none { lit(map, it, 0) }
        )
        assertTrue(
            "the block is drawn off the bottom of the screen",
            (0 until map.width step 2).none { lit(map, it, map.height - 1) }
        )
    }
}
