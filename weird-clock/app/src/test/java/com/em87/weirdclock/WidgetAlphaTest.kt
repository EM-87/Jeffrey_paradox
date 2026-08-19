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
     * The gear is the launcher's, in the popup a long press brings up.
     *
     * Three things have to be declared together and any two without the
     * third get you nothing: the widget names a configuration activity,
     * that activity is marked as one the launcher may open *again* after
     * the widget has been placed — which is what actually puts the gear
     * beside the bin — and it is marked optional so it is not shoved in
     * front of somebody the moment they drop the widget.
     *
     * Read out of the declaration, which is as far as this can go. Whether
     * a launcher then draws a gear is the launcher's business and is not
     * observable from inside the app — the same position the status-bar
     * alarm clock is in, and worth saying rather than implying. What is
     * checked here is that our half is stated.
     */
    @Test
    fun `the launcher is told the widget can be reconfigured`() {
        val xml = context.resources.getXml(R.xml.widget_info)
        var configure: String? = null
        var features: String? = null
        var event = xml.next()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                for (i in 0 until xml.attributeCount) {
                    when (xml.getAttributeName(i)) {
                        "configure" -> configure = xml.getAttributeValue(i)
                        "widgetFeatures" -> features = xml.getAttributeValue(i)
                    }
                }
            }
            event = xml.next()
        }
        assertEquals(
            "the widget names no configuration screen, so there is no gear",
            WidgetSettingsActivity::class.java.name, configure
        )
        assertNotNull("the widget declares no features at all", features)
        // As flags rather than as the words that were typed: the build
        // compiles "reconfigurable|configuration_optional" down to a
        // number, and a test looking for the words would be reading the
        // source rather than what was produced from it.
        val flags = features!!.removePrefix("0x").toInt(16)
        assertTrue(
            "the gear would only appear while the widget is being placed: $features",
            flags and android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
        )
        assertTrue(
            "the slider is shoved in front of anybody who drops the widget: $features",
            flags and
                android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        )
    }

    /**
     * And nothing of ours is drawn on the widget to do that job.
     *
     * A gear in the corner of a clock face is a control sitting on the
     * thing it configures, all day, for the sake of something set once.
     */
    @Test
    fun `the widget itself is nothing but the clock`() {
        val applied = ClockWidgetProvider.viewsForTest(context, 1)
            .apply(context, android.widget.FrameLayout(context))
        assertEquals(
            "something other than the dial is being drawn on the widget",
            1, (applied as android.view.ViewGroup).childCount
        )
    }

    /**
     * Leaving the panel does not throw the widget away.
     *
     * A configuration activity that closes without answering tells the
     * launcher the widget was never wanted, and the launcher deletes it.
     * So somebody who opened this to look, changed nothing and pressed
     * Back would lose the widget they had just placed — which is a
     * spectacular way to fail at "adjust the transparency".
     */
    @Test
    fun `backing out of the panel keeps the widget`() {
        val intent = android.content.Intent(context, WidgetSettingsActivity::class.java)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 7)
        org.robolectric.Robolectric.buildActivity(WidgetSettingsActivity::class.java, intent)
            .use { c ->
                c.setup()
                val shadow = org.robolectric.Shadows.shadowOf(c.get())
                assertEquals(
                    "the launcher was told the widget was not wanted",
                    android.app.Activity.RESULT_OK, shadow.resultCode
                )
                assertEquals(
                    "and it was not told which widget",
                    7,
                    shadow.resultIntent?.getIntExtra(
                        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1
                    )
                )
            }
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
