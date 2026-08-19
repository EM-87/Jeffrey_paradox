package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The second row of digits, actually on the glass.
 *
 * A provider that returns the right number proves the wiring and nothing
 * about the picture: the row could be drawn off the bottom of the view, or
 * over the first row, or not at all, and the wiring test would pass all
 * three. So this one rasterises the dial and counts what is lit in the
 * strip under the readout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondReadoutTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun dial(): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            chronoProvider = { 100_000L }
            chronoButtons = true
            showDate = false
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1600)
        }
    }

    /**
     * How many pixels differ from the background in the band just below the
     * main readout, where the second row belongs.
     */
    private fun litUnderTheReadout(view: ClockView, bitmap: Bitmap): Int {
        val canvas = Canvas(bitmap)
        bitmap.eraseColor(0)
        view.draw(canvas)
        val digitH = view.dialRadiusForTest() * 0.13f
        val top = view.readoutTopForTest() + digitH * 1.2f
        val bottom = top + digitH * 0.9f
        val background = bitmap.getPixel(4, 4)
        var lit = 0
        var y = top.toInt().coerceAtLeast(0)
        while (y < bottom.toInt().coerceAtMost(bitmap.height - 1)) {
            for (x in 0 until bitmap.width step 2) {
                if (bitmap.getPixel(x, y) != background) lit++
            }
            y++
        }
        return lit
    }

    /**
     * Asked for, it appears under the first row; not asked for, nothing.
     *
     * The band measured is below the main readout and above where the
     * buttons sit, so a row drawn on top of the first one would show up as
     * no change here — which is the failure worth catching, since that is
     * exactly what the sky's date and the fallen-hands clock were doing to
     * each other.
     */
    @Test
    fun `the second row is drawn under the first, when there is one`() {
        val clock = dial()
        val bitmap = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)

        clock.secondReadout = { null }
        val quiet = litUnderTheReadout(clock, bitmap)

        clock.secondReadout = { 20_000L }
        val loud = litUnderTheReadout(clock, bitmap)

        assertTrue(
            "nothing was drawn under the readout: $quiet then $loud",
            loud > quiet + 100
        )

        bitmap.recycle()
    }

    /** And the first row is still the one being read. */
    @Test
    fun `the second row does not stand where the first one does`() {
        val clock = dial()
        val bitmap = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        clock.secondReadout = { null }
        bitmap.eraseColor(0)
        clock.draw(canvas)
        val digitH = clock.dialRadiusForTest() * 0.13f
        val top = clock.readoutTopForTest()
        val background = bitmap.getPixel(4, 4)
        fun litIn(from: Float, to: Float): Int {
            var lit = 0
            var y = from.toInt().coerceAtLeast(0)
            while (y < to.toInt().coerceAtMost(bitmap.height - 1)) {
                for (x in 0 until bitmap.width step 2) {
                    if (bitmap.getPixel(x, y) != background) lit++
                }
                y++
            }
            return lit
        }
        val firstRowAlone = litIn(top, top + digitH)

        clock.secondReadout = { 20_000L }
        bitmap.eraseColor(0)
        clock.draw(canvas)
        assertEquals(
            "the second row landed on top of the first",
            firstRowAlone.toFloat(), litIn(top, top + digitH).toFloat(), 1f
        )

        bitmap.recycle()
    }
}
