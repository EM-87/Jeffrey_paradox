package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three widgets, and the things they were each missing.
 *
 * They grew one at a time and diverged as they went: the clock got a fade
 * slider and the other two did not, the clock and the sky measured
 * themselves against different fractions of the same box so they could not
 * be put side by side at the same size, and the countdown had its panel
 * colour written into it in hex and so stayed night-black on a home screen
 * that had gone light. All three are the same bug wearing different
 * clothes — a decision made once and then copied badly — so they are
 * tested together, from the outside, where the differences show.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetSkinTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    // ------------------------------------------------------ one slider each

    /**
     * Each widget's panel writes to its own key.
     *
     * They looked the same on screen and shared a key underneath, so
     * fading the sky faded the clock too and the second panel you opened
     * showed you the first one's number.
     */
    @Test
    fun `each widget has a fade slider of its own`() {
        val clock = WidgetRenderer.alphaKeyOf("com.em87.weirdclock.ClockWidgetProvider")
        val sky = WidgetRenderer.alphaKeyOf("com.em87.weirdclock.OrreryWidgetProvider")
        val glass = WidgetRenderer.alphaKeyOf("com.em87.weirdclock.HourglassWidgetProvider")
        assertEquals(Prefs.WIDGET_ALPHA, clock)
        assertEquals(Prefs.WIDGET_ALPHA_ORRERY, sky)
        assertEquals(Prefs.WIDGET_ALPHA_HOURGLASS, glass)
        assertEquals("three widgets, three keys", 3, setOf(clock, sky, glass).size)
    }

    /** Anything else — a widget added later — falls back to the clock's. */
    @Test
    fun `an unknown widget uses the clock's slider`() {
        assertEquals(Prefs.WIDGET_ALPHA, WidgetRenderer.alphaKeyOf("com.example.SomethingElse"))
    }

    /** And each key is read on its own, full strength when untouched. */
    @Test
    fun `an untouched widget is solid`() {
        for (key in listOf(Prefs.WIDGET_ALPHA, Prefs.WIDGET_ALPHA_ORRERY, Prefs.WIDGET_ALPHA_HOURGLASS)) {
            assertEquals("$key does not start solid", 255, WidgetRenderer.alphaOf(context, key))
        }
    }

    /** Moving one leaves the others where they were. */
    @Test
    fun `fading one widget does not fade the others`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(Prefs.WIDGET_ALPHA_HOURGLASS, 30)
            .commit()
        val faded = WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA_HOURGLASS)
        assertTrue("the countdown did not fade: $faded", faded < 128)
        assertEquals(255, WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA))
        assertEquals(255, WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA_ORRERY))
    }

    // ------------------------------------------------------- the same size

    /**
     * The clock and the sky measure themselves the same way.
     *
     * They were 0.90 and 0.94 of the same box, which is a four per cent
     * difference — small enough to look like a rendering artefact and
     * large enough that two widgets set to the same size on the same home
     * screen were visibly not the same size.
     */
    @Test
    fun `the clock and the sky agree on how big a widget is`() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val pixels = WidgetRenderer.dialPixels(context, manager, 1)
        assertTrue("the widget measured to nothing", pixels > 0)
        val density = context.resources.displayMetrics.density
        assertEquals(
            "an unmeasured widget is not the default size",
            (WidgetRenderer.DEFAULT_DIAL_DP * density).toInt(), pixels
        )
        // One number, asked for by both — so this cannot drift again
        // without the fraction itself changing.
        assertTrue(
            "the dial does not fill most of the box",
            WidgetRenderer.DIAL_FRACTION > 0.8f && WidgetRenderer.DIAL_FRACTION < 1f
        )
    }

    // ---------------------------------------------------- the countdown skin

    /**
     * The countdown panel follows the theme rather than a hex constant.
     *
     * Measured off the corner of the drawn panel, not read back off the
     * paint: the colour was hardcoded at the point of drawing, so a test
     * that asked the view what colour it thought it was would have been
     * told the truth by a view that then drew something else.
     */
    @Test
    fun `the countdown panel follows the theme`() {
        val dark = panelColour(ClockThemes.MIDNIGHT)
        val light = panelColour(ClockThemes.IVORY)
        assertNotEquals("the countdown panel is the same colour in both themes", dark, light)
        assertTrue("the countdown panel is not light in a light theme", luma(light) > 150f)
        assertTrue("the countdown panel is not dark in a dark theme", luma(dark) < 80f)
    }

    // ------------------------------------------------ sand, or a strip

    /**
     * On a face with no glass in it the countdown is a progress bar.
     *
     * Sand in a glass is a picture of a fraction; a strip that empties is
     * the digital drawing of the same thing. The digits alone are not:
     * they say how long is left and never how much has gone.
     *
     * Measured by how much of the strip is lit at two different times,
     * because that is the whole claim. An assertion that "a bar is drawn"
     * would pass with a bar that never moves.
     */
    @Test
    fun `the sand becomes a strip that empties`() {
        val full = litAcross(600_000L, 600_000L)
        val part = litAcross(600_000L, 150_000L)
        val gone = litAcross(600_000L, 0L)
        // The strip is much the widest thing drawn in the lit colour, so
        // the longest run of it across any row is the strip — until there
        // is no strip, and then it is one bar of a digit.
        assertTrue("the strip is not full at the start: $full", full > 400)
        assertTrue("a quarter left is not a quarter of the strip: $part", part in 100..200)
        assertTrue("the strip did not empty: $gone", gone < 100)
    }

    /**
     * And the home-screen countdown follows the face, which is the half
     * that was missed.
     *
     * The card went and the button to it went, and this went on pouring
     * sand on the home screen — in the one place the owner of the phone
     * sees it without opening anything.
     */
    @Test
    fun `the countdown widget pours sand only on the face that has glass`() {
        val drawn = HashMap<Face, Int>()
        for (face in Face.entries) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(Prefs.FACE, face.key).commit()
            val bitmap = HourglassWidgetProvider.renderForTest(
                context, 600_000L, 600_000L, 680, 920
            )
            drawn[face] = wideRows(bitmap)
            bitmap.recycle()
        }
        // A strip is a rectangle: dozens of rows, all of them nearly the
        // full width of the widget. A bulb of sand is curved, so it has
        // one widest row and that one is narrower than this — which is why
        // the test counts rows and not the widest of them. Counting the
        // widest put an hourglass at 388 pixels against a strip's 517,
        // which is a difference a slightly rounder bulb would erase.
        assertTrue(
            "there is no strip on the face with no glass: ${drawn[Face.DIGITAL]}",
            drawn.getValue(Face.DIGITAL) > 10
        )
        assertEquals(
            "the hourglass grew a progress bar",
            0, drawn.getValue(Face.ANALOG)
        )
    }

    /** How many rows are lit right across the widget. */
    private fun wideRows(bitmap: android.graphics.Bitmap): Int {
        val lit = ClockThemes.resolve(context, null).decimal and 0xFFFFFF
        val wide = bitmap.width * 0.66f
        var rows = 0
        for (y in 0 until bitmap.height) {
            var run = 0
            var longest = 0
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) and 0xFFFFFF == lit) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
            }
            if (longest > wide) rows++
        }
        return rows
    }

    /** The longest unbroken run of lit pixels across any one row. */
    private fun litAcross(totalMs: Long, remainingMs: Long): Int {
        val view = HourglassView(context).apply {
            theme = ClockThemes.MIDNIGHT
            lcd = true
            this.totalMs = totalMs
            this.remainingMs = remainingMs
        }
        val w = 680
        val h = 920
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        val bitmap = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        view.draw(android.graphics.Canvas(bitmap))
        var longest = 0
        for (y in 0 until h) {
            var run = 0
            for (x in 0 until w) {
                if (bitmap.getPixel(x, y) == ClockThemes.MIDNIGHT.decimal) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
            }
        }
        bitmap.recycle()
        return longest
    }

    private fun luma(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** The colour the glass's panel actually came out, in a given theme. */
    private fun panelColour(theme: ClockTheme): Int {
        val view = HourglassView(context)
        view.theme = theme
        val size = 240
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, size, size)
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        // On black, so the panel's own transparency does not read as white.
        canvas.drawColor(android.graphics.Color.BLACK)
        view.draw(canvas)
        // Well inside the rounded corner and clear of the glass, which
        // starts a tenth of the way down.
        val at = bitmap.getPixel(size / 12, size / 12 + size / 40)
        bitmap.recycle()
        return at
    }
}
