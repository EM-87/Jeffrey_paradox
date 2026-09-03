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
     * At the top of the slider the widget is actually solid.
     *
     * It was not, and this is the fault the user found: the face was baked
     * at two-thirds alpha so the wallpaper showed through, which was fine
     * until there was a slider called opacity — turned all the way up, the
     * widget was still a third transparent, because two fades were being
     * multiplied and only one of them was the user's.
     *
     * Measured through the middle of the dial rather than at a corner: the
     * corners of a widget bitmap are outside the round face and are
     * transparent whatever the setting says.
     */
    @Test
    fun `at full opacity the widget face is solid`() {
        val bitmap = WidgetRenderer.dialBitmap(context, 200)
        val middle = bitmap.getPixel(100, 100)
        assertEquals(
            "the face is see-through at the top of the slider",
            255, Color.alpha(middle)
        )
        bitmap.recycle()
    }

    /** And the slider is what makes it see-through, all the way down. */
    @Test
    fun `the slider is the only thing that fades the widget`() {
        val bitmap = WidgetRenderer.dialBitmap(context, 200)
        val ghost = WidgetRenderer.faded(bitmap, WidgetRenderer.opacity(50))
        assertEquals(
            "half opacity is not half of solid",
            WidgetRenderer.opacity(50), Color.alpha(ghost.getPixel(100, 100))
        )
        bitmap.recycle()
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
     *
     * The layout holds two clocks now — a dial and a readout — and exactly
     * one of them is ever showing, so what this asks is that nothing
     * *else* is there and that the widget is not showing two clocks at
     * once.
     */
    @Test
    fun `the widget itself is nothing but the clock`() {
        for (face in Face.entries) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(Prefs.FACE, face.key).commit()
            val applied = ClockWidgetProvider.viewsForTest(context, 1)
                .apply(context, android.widget.FrameLayout(context))
                    as android.view.ViewGroup
            val showing = (0 until applied.childCount)
                .map { applied.getChildAt(it) }
                .filter { it.visibility == android.view.View.VISIBLE }
            assertEquals(
                "the widget is showing ${showing.size} things and should show one",
                1, showing.size
            )
            val id = showing.single().id
            if (face.hands) {
                assertEquals("a dial went missing", R.id.widget_analog_clock, id)
            } else {
                // Both of the faces with no hands draw their own bitmap
                // into the same child. What they draw differs; that it is
                // drawn at all is what this asks.
                assertEquals(
                    "$face is still a dial on the home screen",
                    R.id.widget_digital_clock, id
                )
            }
        }
    }

    /**
     * The two clocks are woken on completely different schedules, and the
     * widget has to change its mind when the face does.
     *
     * A bitmap of the time goes stale in a minute. A dial whose hands the
     * system draws goes stale when the sun moves, which is hours. A widget
     * that changed face and kept the dial's schedule sat on the same
     * minute until sunset.
     */
    @Test
    fun `the wake-up follows the face`() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        prefs.edit().putString(Prefs.FACE, Face.DIGITAL.key).commit()
        val digits = ClockWidgetProvider.nextRepaintMs(context)
        assertTrue(
            "a clock made of digits is sleeping $digits ms through a minute",
            digits in 1..60_000L
        )
        prefs.edit().putString(Prefs.FACE, Face.ANALOG.key).commit()
        assertTrue(
            "the dial is being woken as often as a digital clock",
            ClockWidgetProvider.nextRepaintMs(context) > 60_000L
        )
        // And a shadow moves a quarter of a degree a minute, so it is
        // neither of the two: often enough that the shadow never jumps,
        // rarely enough that a picture of a stone is not repainted every
        // minute of the day.
        prefs.edit().putString(Prefs.FACE, Face.SUNDIAL.key).commit()
        val shadow = ClockWidgetProvider.nextRepaintMs(context)
        assertTrue(
            "the sundial widget is being woken like a digital clock: $shadow",
            shadow >= 5 * 60_000L
        )
    }

    /**
     * The sundial widget is a picture of a thing standing where it was
     * put, and its own switch says where that is.
     *
     * Its own key and not the app's, because the plate in the garden and
     * the plate on the home screen are two different objects and there is
     * no reason they should agree.
     */
    @Test
    fun `the home-screen sundial has its own projection`() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(Prefs.FACE, Face.SUNDIAL.key)
            .putBoolean(Prefs.SUNDIAL_LATITUDE_FIXED, true)
            .putInt(Prefs.SUNDIAL_LATITUDE, 45)
            .putBoolean(Prefs.WIDGET_SUNDIAL_WALL, false)
            .commit()
        val flat = WidgetRenderer.sundialBitmap(context, 400, 400)
        prefs.edit().putBoolean(Prefs.WIDGET_SUNDIAL_WALL, true).commit()
        val wall = WidgetRenderer.sundialBitmap(context, 400, 400)
        var same = 0
        for (y in 0 until 400 step 3) {
            for (x in 0 until 400 step 3) {
                if (flat.getPixel(x, y) == wall.getPixel(x, y)) same++
            }
        }
        val of = (400 / 3 + 1) * (400 / 3 + 1)
        assertTrue(
            "the wall dial and the ground dial came out the same picture",
            same < of * 0.92
        )
        flat.recycle()
        wall.recycle()
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

    // ------------------------------- one slider, two opacities, per widget

    /**
     * Each widget's slider is its own.
     *
     * Five of them read one key, so making the globe a ghost made a ghost
     * of the dial beside it and of the digits beside that — reported as
     * exactly that, with the solar system noted as the one that behaved.
     * The two that already had keys of their own keep them; the rest get
     * their own and inherit the shared value once, so nothing on anybody's
     * home screen moves until they move a slider.
     */
    @Test
    fun `every widget has a slider of its own`() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        assertEquals(
            "two kinds share a transparency",
            WidgetKind.entries.size,
            WidgetKind.entries.map { it.alphaKey }.distinct().size
        )
        // The shared key is what they all start from.
        prefs.edit().putInt(Prefs.WIDGET_ALPHA, 40).commit()
        for (kind in listOf(
            WidgetKind.DIAL, WidgetKind.DIGITS, WidgetKind.GLOBE,
            WidgetKind.SUNDIAL, WidgetKind.FOLLOWING, WidgetKind.WEATHER
        )) {
            assertEquals(
                "$kind did not inherit the transparency it had",
                40, WidgetRenderer.percentOf(context, kind)
            )
        }
        // And from then on they are separate: moving one leaves the rest.
        prefs.edit().putInt(WidgetKind.GLOBE.alphaKey, 90).commit()
        assertEquals(90, WidgetRenderer.percentOf(context, WidgetKind.GLOBE))
        assertEquals(
            "the dial went with the globe",
            40, WidgetRenderer.percentOf(context, WidgetKind.DIAL)
        )
        // The solar system was never in the pool and is not now.
        assertEquals(100, WidgetRenderer.percentOf(context, WidgetKind.ORRERY))
        prefs.edit().clear().commit()
    }

    /**
     * A transparency slider settles a widget into a wallpaper. It does not
     * take the time off it.
     *
     * One number in the settings and two on the glass: the flat ground
     * goes as far as the slider asks and the marks — the numerals, the
     * hands, the planets — keep most of their solidity, because a widget
     * you can no longer read the time on is not a transparent clock.
     */
    @Test
    fun `the marks stay solid while the ground fades`() {
        for (percent in WidgetRenderer.MIN_OPACITY_PERCENT..100 step 5) {
            val ground = WidgetRenderer.opacity(percent)
            val marks = WidgetRenderer.marked(ground)
            assertTrue("$percent%: the marks faded past the ground", marks >= ground)
            assertTrue(
                "$percent%: the marks went below their floor",
                marks >= (255 * WidgetRenderer.MARK_FLOOR).toInt() - 1
            )
            assertTrue("$percent%: the marks went over solid", marks <= 255)
        }
        // And at the top nothing is faded at all.
        assertEquals(255, WidgetRenderer.marked(255))
        // At the bottom the ground is nearly gone and the time is not.
        val bottom = WidgetRenderer.opacity(WidgetRenderer.MIN_OPACITY_PERCENT)
        assertTrue("the ground is still solid at the bottom", bottom < 128)
        assertTrue("the marks went with it", WidgetRenderer.marked(bottom) > 128)
    }

    /**
     * And on the glass: the dial's face fades further than its numerals.
     *
     * Measured on two renderings of the same dial, one at each of the two
     * opacities the slider produces, because "passed an alpha to the fill
     * paint" is the sort of line that can quietly do nothing.
     */
    @Test
    fun `the dial's face fades further than what is printed on it`() {
        // The face is baked pre-divided and the whole picture is faded
        // again on the way out, so the two together land on the number the
        // slider asked for — checked here, because the compensation is
        // exactly the sort of arithmetic that can be applied twice or not
        // at all and look plausible either way.
        assertEquals(255, WidgetRenderer.beforeMarking(255, 255))
        // Within a step of it: both halves are integer division, so the
        // round trip loses at most one of two hundred and fifty-six.
        assertEquals(
            "the pre-division does not undo the second fade",
            64.0,
            (WidgetRenderer.beforeMarking(64, 200) * 200 / 255).toDouble(),
            1.5
        )
        val faint = WidgetRenderer.dialBitmap(context, 240, 60)
        val solid = WidgetRenderer.dialBitmap(context, 240, 255)
        // The middle of the dial is face and nothing else.
        val middleFaint = Color.alpha(faint.getPixel(120, 120))
        val middleSolid = Color.alpha(solid.getPixel(120, 120))
        assertTrue(
            "the face ignored its own opacity: $middleFaint against $middleSolid",
            middleFaint < middleSolid - 40
        )
        faint.recycle()
        solid.recycle()
    }
}
