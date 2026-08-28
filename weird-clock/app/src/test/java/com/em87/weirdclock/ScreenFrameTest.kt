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
import kotlin.math.hypot

/**
 * The window the chronograph's digits are printed on.
 *
 * Two things to get right and only one of them is arithmetic. The panel is
 * a rectangle inside a circle, so what limits it is its corners — and a
 * panel sized by its width fits until the day it gets tall enough for the
 * corners to come out through the bezel, which is a bug that waits for a
 * longer number to arrive. The other is simply that it is there at all,
 * which no amount of arithmetic will tell you and a bitmap will.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenFrameTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** A panel that already fits is left exactly as it is. */
    @Test
    fun `a panel inside the bezel is not touched`() {
        val out = ScreenFrame.fit(30f, 10f, 100f)
        assertEquals(30f, out[0], 0.0001f)
        assertEquals(10f, out[1], 0.0001f)
    }

    /**
     * And one whose corners would come out is pulled in until they do not
     * — both sides by the same amount, because a panel that keeps its
     * height and loses its width stops being the shape of a screen.
     */
    @Test
    fun `it is the corners that limit the panel, not the width`() {
        val r = 100f
        // Comfortably inside on width alone — 80 against a hundred — and
        // well outside once it has a height. This is the case that was
        // wrong before there was a rule at all.
        val out = ScreenFrame.fit(80f, 60f, r)
        val corner = hypot(out[0], out[1])
        assertTrue("the corner is outside the bezel: $corner", corner <= r * ScreenFrame.REACH + 0.01f)
        assertEquals(
            "the panel changed shape on the way in",
            80f / 60f, out[0] / out[1], 0.0001f
        )
    }

    /** The glass is darker than the dial, on a pale face as much as a dark one. */
    @Test
    fun `the screen is darker than the face it is cut into`() {
        for (theme in listOf(ClockThemes.MIDNIGHT, ClockThemes.DAYLIGHT, ClockThemes.IVORY)) {
            val glass = ScreenFrame.glass(theme.face)
            for (shift in listOf(16, 8, 0)) {
                val was = (theme.face shr shift) and 0xFF
                val now = (glass shr shift) and 0xFF
                assertTrue("the glass is not darker than the face", now <= was)
            }
            assertEquals("the glass went transparent", 0xFF, glass ushr 24)
        }
    }

    /**
     * And it is actually on the screen.
     *
     * Measured off the pixels, because everything above would go on
     * passing with the panel never drawn: the row of lit bars floating in
     * the middle of an empty bezel is exactly what this was added to fix,
     * and it looks fine to an assertion.
     *
     * The test looks for the glass colour itself and not merely for
     * "something other than the dial", which is what it did first — and
     * which passed with the panel taken out altogether, because the lit
     * bars are also something other than the dial. A test that survives
     * the removal of the thing it tests is worse than no test.
     */
    @Test
    fun `the digits are on a panel and not on the dial`() {
        val view = ClockView(context).apply {
            theme = ClockThemes.MIDNIGHT
            lcdChrono = true
            chronoProvider = { 65_432L }
            measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 900, 900)
        }
        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val glass = ScreenFrame.glass(ClockThemes.MIDNIGHT.face)
        var panel = 0
        var y = 0
        while (y < 900) {
            var x = 0
            while (x < 900) {
                if (bitmap.getPixel(x, y) == glass) panel++
                x += 2
            }
            y += 2
        }
        assertTrue("there is no screen behind the digits", panel > 2000)
    }
}
