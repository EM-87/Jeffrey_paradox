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
