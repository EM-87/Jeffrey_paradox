package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Color
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How much of the wallpaper shows through the widget.
 *
 * A slider on the home screen, reached from a gear on the widget itself,
 * because it is a decision you can only make while looking at what is
 * behind it — and because a widget configuration screen that opens once
 * when the widget is dropped is a screen nobody ever sees twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetAlphaTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /** Full is full, and the ends of the slider mean what they say. */
    @Test
    fun `the slider's top is a solid widget`() {
        assertEquals(255, WidgetRenderer.opacity(100))
    }

    /**
     * And its bottom is faint, not gone.
     *
     * A widget that can be set to invisible is a widget you then cannot
     * find to set back. It is still there, still taking its square of the
     * home screen, and the only way out is to remember where it was and
     * guess. So the bottom of the slider is faint.
     */
    @Test
    fun `the slider cannot make the widget disappear`() {
        assertTrue(
            "the widget can be turned invisible",
            WidgetRenderer.opacity(0) > 20
        )
        assertEquals(
            "and anything below the floor is the floor",
            WidgetRenderer.opacity(WidgetRenderer.MIN_OPACITY_PERCENT),
            WidgetRenderer.opacity(-40)
        )
        assertTrue(
            "the floor is not so high that the slider does nothing",
            WidgetRenderer.opacity(WidgetRenderer.MIN_OPACITY_PERCENT) < 128
        )
    }

    /** Between the ends it climbs. */
    @Test
    fun `more percent is more solid`() {
        var last = -1
        for (percent in WidgetRenderer.MIN_OPACITY_PERCENT..100 step 5) {
            val a = WidgetRenderer.opacity(percent)
            assertTrue("$percent% is no more solid than the step below it", a > last)
            last = a
        }
    }

    /**
     * A faded picture really is fainter, and a full one is not copied.
     *
     * Measured, because "drew it through a paint with an alpha on it" is
     * the sort of line that can quietly do nothing — the paint ignored, or
     * the copy returned instead of the drawing.
     */
    @Test
    fun `fading a picture makes it fainter`() {
        val solid = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val ghost = WidgetRenderer.faded(solid, WidgetRenderer.opacity(30))
        assertTrue(
            "the faded copy is as opaque as the original",
            Color.alpha(ghost.getPixel(4, 4)) < Color.alpha(solid.getPixel(4, 4))
        )
        assertTrue("and it faded to nothing", Color.alpha(ghost.getPixel(4, 4)) > 10)

        assertTrue(
            "a widget at full strength was copied for no reason",
            WidgetRenderer.faded(solid, 255) === solid
        )
        solid.recycle()
    }

    /**
     * The gear is on the widget, and it leads somewhere.
     *
     * Two things that are easy to have one of without the other: a button
     * drawn on the layout that nothing listens to, or a screen registered
     * in the manifest that nothing opens.
     */
    @Test
    fun `the gear is on the widget and opens the slider`() {
        val inflated = android.view.LayoutInflater.from(context)
            .inflate(R.layout.widget_clock, null)
        assertNotNull(
            "there is no gear on the widget",
            inflated.findViewById<android.view.View>(R.id.widget_gear)
        )

        val activities = context.packageManager.queryIntentActivities(
            android.content.Intent(context, WidgetSettingsActivity::class.java), 0
        )
        assertTrue("the slider has no screen to live on", activities.isNotEmpty())
    }

    /** And the setting is not something the app's own settings can bury. */
    @Test
    fun `a widget at full strength is what an untouched install draws`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals(
            "a fresh install draws a see-through widget",
            255, WidgetRenderer.opacity(prefs.getInt(Prefs.WIDGET_ALPHA, 100))
        )
    }
}
