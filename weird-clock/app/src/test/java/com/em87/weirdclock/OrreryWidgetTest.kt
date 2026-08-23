package com.em87.weirdclock

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
import java.io.File

/**
 * The solar system on the home screen.
 *
 * Almost nothing about a widget can be tested from inside the app — the
 * launcher draws it, in another process, and what arrives there is a
 * parcel. What can be checked is what goes into the parcel: that the
 * picture is a picture of something, that the planets in it are where they
 * are today rather than at some fixed epoch, that tapping it lands on the
 * sky, and that the bitmap crossing the boundary is not the size of a
 * poster.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrreryWidgetTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun at(year: Int, month: Int, day: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day)
        return cal.timeInMillis
    }

    /** How many pixels are not the background. */
    private fun ink(bitmap: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 8) n++
        }
        return n
    }

    /** There is a solar system in it, and a picture of it goes on disk. */
    @Test
    fun `the widget draws a sky`() {
        val bitmap = OrreryWidgetProvider.bitmap(context, 480, at(2026, 6, 15))
        File(outDir, "widget-orrery.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("the widget is blank", ink(bitmap) > 500)
    }

    /**
     * And the planets in it are where they are on the day it was drawn.
     *
     * The way this goes wrong is not a crash: it is a widget that draws a
     * beautiful and completely fixed solar system, because the time never
     * got as far as the arithmetic. Two dates half a Mercury year apart
     * must not give the same picture.
     */
    @Test
    fun `the sky in it is the sky of the day it was drawn`() {
        val winter = OrreryWidgetProvider.bitmap(context, 480, at(2026, 1, 15))
        val spring = OrreryWidgetProvider.bitmap(context, 480, at(2026, 3, 15))
        var same = 0
        var counted = 0
        for (y in 0 until 480 step 3) for (x in 0 until 480 step 3) {
            counted++
            if (winter.getPixel(x, y) == spring.getPixel(x, y)) same++
        }
        assertTrue(
            "two months apart the widget drew the identical sky",
            same < counted
        )
    }

    /**
     * And the widget the launcher is handed is drawn for now.
     *
     * The test above proves the drawing follows the date it is given; it
     * says nothing about what date the widget gives it, and those are two
     * different lines. A widget that renders a perfect solar system for
     * the first of January 2000 every single time would pass everything
     * else here.
     */
    @Test
    fun `the picture the launcher gets is of today`() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val size = WidgetRenderer.dialPixels(context, manager, 1)
        val views = OrreryWidgetProvider.viewsForTest(context, 1)
        val host = android.widget.FrameLayout(context)
        val image = views.apply(context, host)
            .findViewById<android.widget.ImageView>(R.id.widget_orrery_image)
        val sent = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap

        val now = OrreryWidgetProvider.bitmap(context, size, System.currentTimeMillis())
        val epoch = OrreryWidgetProvider.bitmap(context, size, Orrery.J2000_MS)
        fun apart(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Int {
            var n = 0
            for (y in 0 until size step 3) for (x in 0 until size step 3) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) n++
            }
            return n
        }
        assertTrue(
            "the widget handed over a sky that is not today's",
            apart(sent, now) < apart(sent, epoch)
        )
    }

    /**
     * And the Moon in it moves too.
     *
     * The Moon is the one body on this dial whose position is passed in
     * rather than worked out from the instant — it has to be, because in
     * the app it lets go of the mechanism while a planet is being carried
     * — so it is the one that can be left behind while everything else
     * follows the date. A whole-picture comparison never notices, because
     * the planets moving is enough on its own to make two frames differ.
     *
     * The two frames are therefore four Earth years apart, which is the
     * useful coincidence here: the Earth is back on the same pixel, and
     * the Moon has gone round forty-nine and a half times and is very
     * nearly on the other side of it. So the crop around the Earth can be
     * the same crop in both frames, and any difference in it is the Moon.
     *
     * Four years and not one, because the crops have to line up to the
     * pixel. Taking them half a lunar month apart and cropping around
     * wherever the Earth had got to compared two grids sampled at
     * different sub-pixel offsets, so every anti-aliased edge in both
     * frames disagreed and the answer was "different" no matter what the
     * Moon did.
     */
    @Test
    fun `the moon in it moves with the date as well`() {
        val size = 480
        val t0 = at(2026, 6, 15)
        val year = Orrery.periodDays(Orrery.Body.EARTH) * 86_400_000.0
        val t1 = t0 + (4 * year).toLong()
        assertEquals(
            "four years on the Earth is not back where it was, so this " +
                "comparison is measuring the Earth and not the Moon",
            Orrery.longitude(Orrery.Body.EARTH, t0),
            Orrery.longitude(Orrery.Body.EARTH, t1), 0.05
        )

        val a = OrreryWidgetProvider.bitmap(context, size, t0)
        val b = OrreryWidgetProvider.bitmap(context, size, t1)
        val half = size / 2f
        val r = half * 0.94f
        val earth = OrreryDial.positionOf(Orrery.Body.EARTH, half, half, r, t0, 0.0)
        val reach = (Orrery.MOON_RING * r * 1.8f).toInt()
        var moved = 0
        for (y in -reach..reach) for (x in -reach..reach) {
            val px = (earth.x + x).toInt()
            val py = (earth.y + y).toInt()
            if (px !in 0 until size || py !in 0 until size) continue
            if (a.getPixel(px, py) != b.getPixel(px, py)) moved++
        }
        assertTrue(
            "the Moon is in the same place beside the Earth four years " +
                "apart, so it is not being drawn for the date at all: " +
                "$moved pixels differ",
            moved > 30
        )
    }

    /** Tapping it opens the clock with the sky already up. */
    @Test
    fun `tapping the widget asks for the sky`() {
        val views = OrreryWidgetProvider.viewsForTest(context, 1)
        val host = android.widget.FrameLayout(context)
        val rendered = views.apply(context, host)
        val image = rendered.findViewById<android.view.View>(R.id.widget_orrery_image)
        assertNotNull("the widget has no picture in it", image)
        assertTrue("the widget does nothing when tapped", image.hasOnClickListeners())
    }

    /**
     * The bitmap does not grow without limit.
     *
     * Every update crosses a process boundary whole. A widget stretched
     * across a tablet would otherwise send a bitmap the size of the
     * screen through IPC, several times a day, to move Neptune by nothing.
     */
    @Test
    fun `the picture sent across is capped at both ends`() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val density = context.resources.displayMetrics.density
        val size = WidgetRenderer.dialPixels(context, manager, 1)
        assertTrue("the widget is drawn at $size pixels", size <= (320 * density).toInt())
        assertTrue("the widget is drawn at $size pixels", size >= (64 * density).toInt())
    }

    /**
     * The comets follow the app's own switch.
     *
     * Not a second setting on the home screen: the widget is a window onto
     * the same sky the card shows, and two switches for one picture is one
     * switch too many.
     */
    @Test
    fun `the widget shows comets only when the app does`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(Prefs.COMETS, false).commit()
        val without = OrreryWidgetProvider.bitmap(context, 480, at(2026, 6, 15))
        prefs.edit().putBoolean(Prefs.COMETS, true).commit()
        val with = OrreryWidgetProvider.bitmap(context, 480, at(2026, 6, 15))
        // Counted as changed pixels rather than as more ink: the face is
        // an opaque disc either way, so four hairline ellipses drawn on it
        // add no opacity at all — they only move colour about.
        var moved = 0
        for (y in 0 until 480 step 2) for (x in 0 until 480 step 2) {
            if (with.getPixel(x, y) != without.getPixel(x, y)) moved++
        }
        assertTrue(
            "the widget draws the same picture whether comets are on or off",
            moved > 100
        )
    }

    /**
     * And the whole thing fades with a transparency slider of its own.
     *
     * Its own, not the clock's. They shared a stored percentage while only
     * the clock had a slider, and the moment the other two got one that
     * sharing became a lie — three controls moving one number, so setting
     * the sky to a ghost took the clock beside it down with it.
     */
    @Test
    fun `the widget fades with a slider of its own`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt(Prefs.WIDGET_ALPHA_ORRERY, 100).commit()
        val solid = OrreryWidgetProvider.bitmap(context, 240, at(2026, 6, 15))
        prefs.edit()
            .putInt(Prefs.WIDGET_ALPHA_ORRERY, WidgetRenderer.MIN_OPACITY_PERCENT)
            .commit()
        val faint = OrreryWidgetProvider.bitmap(context, 240, at(2026, 6, 15))
        assertTrue(
            "the transparency slider does nothing to the sky",
            Color.alpha(faint.getPixel(120, 120)) < Color.alpha(solid.getPixel(120, 120))
        )

        // And the clock's own slider leaves the sky alone.
        prefs.edit()
            .putInt(Prefs.WIDGET_ALPHA_ORRERY, 100)
            .putInt(Prefs.WIDGET_ALPHA, WidgetRenderer.MIN_OPACITY_PERCENT)
            .commit()
        val untouched = OrreryWidgetProvider.bitmap(context, 240, at(2026, 6, 15))
        assertEquals(
            "the clock's slider faded the solar system with it",
            Color.alpha(solid.getPixel(120, 120)),
            Color.alpha(untouched.getPixel(120, 120))
        )
    }

    /**
     * And it is the same circle the clock widget draws.
     *
     * The two were 0.94 and 0.90 of their bitmap, which nobody would spot
     * apart and which made it impossible to stand them side by side on a
     * home screen at the same size: the same widget cell gave two
     * different dials.
     */
    @Test
    fun `the sky and the clock are the same size at the same widget size`() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        assertEquals(
            "the two widgets ask for different bitmaps at the same size",
            WidgetRenderer.dialPixels(context, manager, 1),
            WidgetRenderer.dialPixels(context, manager, 1)
        )
        val size = 240
        val sky = OrreryWidgetProvider.bitmap(context, size, at(2026, 6, 15))
        val clock = WidgetRenderer.dialBitmap(context, size)
        // Walked out from the middle along one row: the last opaque pixel
        // is the rim, and the two rims must be in the same place.
        fun edgeOf(b: android.graphics.Bitmap): Int {
            var last = 0
            for (x in size / 2 until size) {
                if (Color.alpha(b.getPixel(x, size / 2)) > 8) last = x
            }
            return last - size / 2
        }
        val skyEdge = edgeOf(sky)
        val clockEdge = edgeOf(clock)
        assertTrue("nothing was drawn at all: $skyEdge, $clockEdge", skyEdge > 10)
        assertTrue(
            "the sky's dial is $skyEdge across and the clock's $clockEdge, so " +
                "they cannot be stood side by side",
            kotlin.math.abs(skyEdge - clockEdge) <= 2
        )
    }

    /** The redraw is booked far apart, because nothing on it hurries. */
    @Test
    fun `the widget does not wake the phone up to move nothing`() {
        val shadow = org.robolectric.Shadows.shadowOf(
            context.getSystemService(android.app.AlarmManager::class.java)
        )
        OrreryWidgetProvider.scheduleTick(context)
        // Peeked, not taken. The other one hands the alarm over and drops
        // it from the queue, which is how the test below first passed with
        // the cancelling removed: it had emptied the queue itself.
        val next = shadow.peekNextScheduledAlarm()
        assertNotNull("the widget never books a redraw, so it never gets one", next)
        val ahead = next.triggerAtTime - System.currentTimeMillis()
        assertTrue(
            "the widget wakes up every ${ahead / 60_000} minutes to move a planet " +
                "four degrees a day",
            ahead > 60 * 60_000L
        )
    }

    /** Nothing is left ticking once the last one is gone. */
    @Test
    fun `the last widget removed takes its wake-up with it`() {
        val shadow = org.robolectric.Shadows.shadowOf(
            context.getSystemService(android.app.AlarmManager::class.java)
        )
        OrreryWidgetProvider.scheduleTick(context)
        assertNotNull(shadow.peekNextScheduledAlarm())
        OrreryWidgetProvider().onDisabled(context)
        assertEquals(
            "the phone goes on waking up for a widget that is not there",
            null, shadow.peekNextScheduledAlarm()
        )
    }
}
