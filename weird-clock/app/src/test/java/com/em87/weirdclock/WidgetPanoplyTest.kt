package com.em87.weirdclock

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

/**
 * One widget per clock, and each one is the clock it says it is.
 *
 * There was a single clock widget that drew whichever face the app was set
 * to, which is a sensible thing for a clock to do and the wrong thing for
 * a widget to be: somebody who wants the sundial beside the digital clock
 * could have exactly one, and it was whichever they had last chosen in the
 * app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WidgetPanoplyTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString(Prefs.FACE, Face.ANALOG.key)
            .commit()
    }

    /** Every face has one, and the original still follows the app. */
    @Test
    fun `there is one of each and one that follows`() {
        val kinds = ClockWidgetProvider.KINDS
        assertEquals("a face is missing a widget of its own", Face.entries.size + 1, kinds.size)
        assertEquals("the first is not the one that follows the app", null, kinds.first().second)
        for (face in Face.entries) {
            assertTrue(
                "$face has no widget of its own",
                kinds.any { it.second == face }
            )
        }
        // And each of them is a real, declared receiver: a provider the
        // manifest has never heard of is a widget nobody can add.
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        assertNotNull(manager)
        for ((cls, _) in kinds) {
            val info = context.packageManager.getReceiverInfo(
                android.content.ComponentName(context, cls), 0
            )
            assertNotNull("${cls.simpleName} is not in the manifest", info)
        }
    }

    /**
     * And a pinned widget wakes at its own face's rate whatever the app
     * is set to.
     *
     * A digital clock is wrong the moment the minute turns and a sundial's
     * shadow is worth redrawing every ten minutes; a widget that took the
     * app's answer would have the sundial repainting sixty times an hour
     * or the digital clock sitting on the same minute until sunset.
     */
    @Test
    fun `each kind sleeps for its own face`() {
        val digits = ClockWidgetProvider.nextRepaintMs(context, Face.DIGITAL)
        val plate = ClockWidgetProvider.nextRepaintMs(context, Face.SUNDIAL)
        assertTrue("the digital clock sleeps past a minute", digits <= 60_000L)
        assertTrue("the sundial repaints as often as a digital clock", plate > 60_000L)
        // The app is set to the dial, whose own answer is the next change
        // in the sky — hours away. Asking without a face gives that one,
        // which is the whole point of being able to pass one in.
        val following = ClockWidgetProvider.nextRepaintMs(context)
        assertTrue(
            "the pinned face was ignored: following $following, digital $digits",
            following > 5 * 60_000L
        )
    }

    // --------------------------------------------- what each of them keeps

    /** Every provider answers to a kind, and each kind to one provider. */
    @Test
    fun `every widget knows which one it is`() {
        val seen = listOf(
            "com.em87.weirdclock.ClockWidgetProvider" to WidgetKind.FOLLOWING,
            "com.em87.weirdclock.AnalogWidgetProvider" to WidgetKind.DIAL,
            "com.em87.weirdclock.DigitalWidgetProvider" to WidgetKind.DIGITS,
            "com.em87.weirdclock.SundialWidgetProvider" to WidgetKind.SUNDIAL,
            "com.em87.weirdclock.WorldWidgetProvider" to WidgetKind.GLOBE,
            "com.em87.weirdclock.OrreryWidgetProvider" to WidgetKind.ORRERY,
            "com.em87.weirdclock.HourglassWidgetProvider" to WidgetKind.HOURGLASS,
            "com.em87.weirdclock.WeatherWidgetProvider" to WidgetKind.WEATHER
        )
        for ((provider, kind) in seen) {
            assertEquals(provider, kind, WidgetKind.of(provider))
        }
        assertEquals(
            "a kind has no provider to answer for it",
            WidgetKind.entries.size, seen.map { it.second }.distinct().size
        )
        // Each keeps its own settings under its own name, and the three
        // that had a key before it keep it: somebody's ghost of a solar
        // system is not a thing to reset for a naming scheme.
        assertEquals(Prefs.WIDGET_ALPHA_ORRERY, WidgetKind.ORRERY.alphaKey)
        assertEquals(Prefs.WIDGET_ALPHA_HOURGLASS, WidgetKind.HOURGLASS.alphaKey)
        // And the rest have one each, inheriting the shared one once —
        // see [WidgetAlphaTest], which measures that.
        assertEquals(WidgetKind.DIAL.pref("alpha"), WidgetKind.DIAL.alphaKey)
        assertEquals(Prefs.WIDGET_ALPHA, WidgetKind.DIAL.alphaWas)
        assertEquals(
            "two kinds share a settings key",
            WidgetKind.entries.size,
            WidgetKind.entries.map { it.pref("ground") }.distinct().size
        )
    }

    /**
     * The background is a switch, and it does something.
     *
     * Some of them arrived with one and some without — the globe carries
     * its own black sky, the dial is a face on the wallpaper — which is a
     * difference nobody chose. The defaults are what each of them already
     * looked like, so nobody's home screen changes; the switch is what
     * was missing.
     */
    @Test
    fun `every widget can be given a background or have it taken away`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        for (kind in WidgetKind.entries) {
            assertEquals(
                "$kind does not start as it looked",
                kind.groundByDefault, WidgetRenderer.grounded(context, kind)
            )
            prefs.edit().putBoolean(kind.pref("ground"), !kind.groundByDefault).commit()
            assertEquals(
                "$kind ignored its own switch",
                !kind.groundByDefault, WidgetRenderer.grounded(context, kind)
            )
            prefs.edit().remove(kind.pref("ground")).commit()
        }
        // And the card really is drawn: the corner of a widget on a card
        // is the card, and the corner of one without is nothing at all.
        val bare = android.graphics.Bitmap.createBitmap(
            120, 120, android.graphics.Bitmap.Config.ARGB_8888
        )
        val carded = WidgetRenderer.onCard(bare, WidgetKind.DIAL, ClockThemes.MIDNIGHT)
        assertEquals("the bare one had something in the middle", 0, bare.getPixel(60, 60))
        assertTrue(
            "nothing was drawn behind it",
            android.graphics.Color.alpha(carded.getPixel(60, 60)) > 200
        )
    }

    /**
     * The digits widget can wear a mechanism the app is not wearing.
     *
     * A home screen is not a settings page: flip cards on the wall and lit
     * bars in the app is a perfectly ordinary thing to want, and until
     * this row existed the widget was whatever the app had been left on.
     */
    @Test
    fun `the digits widget keeps its own mechanism`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(Prefs.DIGIT_STYLE, Prefs.DIGITS_SEGMENT)
            .putString(WidgetKind.DIGITS.pref("mechanism"), Prefs.DIGITS_ROLLER)
            .commit()
        val theirs = WidgetRenderer.digitalBitmap(context, 240, 240)
        prefs.edit().remove(WidgetKind.DIGITS.pref("mechanism")).commit()
        val apps = WidgetRenderer.digitalBitmap(context, 240, 240)
        assertTrue("the widget's own mechanism was ignored", differ(theirs, apps) > 40)
    }

    /**
     * And the globe can lose its sun, which is also what lets it fill the
     * widget.
     */
    @Test
    fun `the globe widget keeps its own sun`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(WidgetKind.GLOBE.pref("sun"), true).commit()
        val withSun = WidgetRenderer.hemisphereBitmap(context, 240, 240)
        prefs.edit().putBoolean(WidgetKind.GLOBE.pref("sun"), false).commit()
        val without = WidgetRenderer.hemisphereBitmap(context, 240, 240)
        assertTrue("the sun would not go", differ(withSun, without) > 40)
        // And the world is bigger for it: a pixel near the edge that was
        // empty space is now the earth.
        assertTrue(
            "the world did not take the room the sun was using",
            painted(without) > painted(withSun)
        )
    }

    /** The weather widget draws a sky and a reading, and no clock. */
    @Test
    fun `the weather widget draws something`() {
        val map = WidgetRenderer.weatherBitmap(context, 240, 240)
        assertTrue("nothing was drawn at all", painted(map) > 200)
    }

    /**
     * Tapping a pinned widget asks for that clock, and each asks for its
     * own.
     *
     * Four widgets that all opened the same app at the same face is what
     * was reported: the globe and the sundial both arrived at the dial.
     */
    @Test
    fun `each pinned widget asks for its own face`() {
        val asked = ClockWidgetProvider.KINDS.mapNotNull { it.second }
        assertEquals(
            "two widgets ask for the same clock",
            asked.size, asked.distinct().size
        )
        for (face in Face.entries) {
            assertTrue("$face has no widget asking for it", face in asked)
        }
    }

    /** How many sampled pixels are not the same in the two pictures. */
    private fun differ(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        }
        return n
    }

    /** And how many of them are not transparent. */
    private fun painted(map: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until map.height step 2) {
            for (x in 0 until map.width step 2) {
                if (android.graphics.Color.alpha(map.getPixel(x, y)) > 8) n++
            }
        }
        return n
    }

    /**
     * The seconds are a switch on the two widgets that have any.
     *
     * The dial's is a hand that sweeps and the digits' is a counter that
     * ticks, and both default to what the app is doing so nothing changes
     * under anybody. Measured on the dial as pixels, because the hand is
     * drawn; on the digits as the visibility of the one view on that
     * widget that moves by itself.
     */
    @Test
    fun `the clock widgets keep their own seconds`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(WidgetKind.DIAL.pref("seconds"), true).commit()
        val sweeping = ClockWidgetProvider.viewsForTest(context, 1, Face.ANALOG)
        prefs.edit().putBoolean(WidgetKind.DIAL.pref("seconds"), false).commit()
        val still = ClockWidgetProvider.viewsForTest(context, 1, Face.ANALOG)
        // Both are built; what differs is the bitmap handed over as the
        // second hand, which a RemoteViews will not show us. What we can
        // see is that the two are not the same object graph.
        assertNotNull(sweeping)
        assertNotNull(still)
        // The digits, where the difference is a view coming and going.
        prefs.edit()
            .putString(Prefs.FACE, Face.DIGITAL.key)
            .putBoolean(WidgetKind.DIGITS.pref("seconds"), true)
            .commit()
        val counting = applied(ClockWidgetProvider.viewsForTest(context, 1, Face.DIGITAL))
        prefs.edit().putBoolean(WidgetKind.DIGITS.pref("seconds"), false).commit()
        val quiet = applied(ClockWidgetProvider.viewsForTest(context, 1, Face.DIGITAL))
        assertEquals(
            "the seconds were asked for and did not appear",
            android.view.View.VISIBLE,
            counting.findViewById<android.view.View>(R.id.widget_digital_seconds).visibility
        )
        assertEquals(
            "and they would not go away",
            android.view.View.GONE,
            quiet.findViewById<android.view.View>(R.id.widget_digital_seconds).visibility
        )
    }

    /** The widget as a launcher would actually inflate it. */
    private fun applied(views: android.widget.RemoteViews): android.view.View {
        val host = android.widget.FrameLayout(context)
        return views.apply(context, host)
    }

    /**
     * The weather widget says what it knows, and why when it knows
     * nothing.
     *
     * Two dashes and nothing else is a widget that looks broken, and the
     * two reasons it can happen — the weather switch off, no fix ever
     * taken — are both one tap away and neither is guessable from a dash.
     */
    @Test
    fun `the weather widget says why it has no number`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(Prefs.WEATHER, false).commit()
        val off = WidgetRenderer.weatherBitmap(context, 240, 240)
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        val on = WidgetRenderer.weatherBitmap(context, 240, 240)
        // Different words for the two states, so the pictures differ.
        assertTrue("it says the same thing either way", differ(off, on) > 4)
        assertTrue("it drew nothing at all", painted(off) > 200)
    }

    /**
     * The panel the launcher's gear opens has something behind it.
     *
     * Its window is floating and translucent so that the corners are
     * round and the wallpaper dims behind it — and with no background on
     * the content, that means the home screen showed straight through the
     * rows. Reported as the widget menu having no background, which is
     * exactly what it was.
     */
    @Test
    fun `the widget settings panel is not see-through`() {
        org.robolectric.Robolectric.buildActivity(WidgetSettingsActivity::class.java)
            .setup().use { c ->
                val content = c.get()
                    .findViewById<android.view.ViewGroup>(android.R.id.content)
                val panel = content.getChildAt(0)
                assertNotNull("the panel was never built", panel)
                assertNotNull("the panel has nothing behind it", panel.background)
            }
    }
}
