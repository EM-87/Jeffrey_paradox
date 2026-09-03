package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the earth, because a mirrored world looks exactly as
 * plausible as a correct one.
 *
 * [HemisphereTest] can prove that the pole is in the middle and that the
 * thing turns the right way, and it cannot tell you whether what comes out
 * looks like the earth. That needs somebody who knows where Africa is to
 * look at it, which is what these are for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HemisphereShotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Noon over Greenwich at midsummer, so the terminator is at its most bent. */
    private fun midsummerNoon(): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun globe(
        which: Hemisphere.View = Hemisphere.View.NORTH,
        at: Long = midsummerNoon(),
        sunAt: Double = 0.0,
        ring: Boolean = true,
        notches: Boolean = true,
        clouds: Bitmap? = null
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = which
        this.sunAt = sunAt
        hourRing = ring
        showMoon = notches
        this.clouds = clouds
        latitude = 40.4
        longitude = -3.7
        located = true
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1000, 1000)
    }

    private fun shoot(v: View, name: String): Int {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val seen = HashSet<Int>()
        var y = 0
        while (y < v.height) {
            var x = 0
            while (x < v.width) {
                seen.add(bitmap.getPixel(x, y)); x += 4
            }
            y += 4
        }
        return seen.size
    }

    /** The three ways of looking at it. */
    @Test
    fun `north, south and the ball`() {
        for (which in Hemisphere.View.entries) {
            assertTrue(shoot(globe(which), "hemi-${which.key}") > 200)
        }
    }

    /**
     * The same world six hours apart, which is the clock working.
     *
     * If these two come out the same picture the earth is not turning,
     * and if the dot has not moved a quarter of the way round then it is
     * not the hand.
     */
    @Test
    fun `six hours later the world has turned a quarter`() {
        val noon = midsummerNoon()
        assertTrue(shoot(globe(at = noon), "hemi-noon") > 200)
        assertTrue(shoot(globe(at = noon + 6 * 3_600_000L), "hemi-six-later") > 200)
        assertTrue(shoot(globe(at = noon + 12 * 3_600_000L), "hemi-midnight") > 200)
    }

    /** And the sun can be pinned somewhere else. */
    @Test
    fun `the sun moved to the top`() {
        assertTrue(shoot(globe(sunAt = 90.0), "hemi-sun-top") > 200)
    }

    /** The plainest it gets: no ring, no notches, just the world. */
    @Test
    fun `the world on its own`() {
        assertTrue(shoot(globe(ring = false, notches = false), "hemi-bare") > 200)
    }

    /**
     * The whole card, and the sky where the calendar would be.
     *
     * The two pictures somebody actually sees on this face: the planet
     * with its buttons under it, and the page one swipe left, which is
     * the solar system rather than a grid of days.
     */
    @Test
    fun `the card, and the sky next door`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.HEMISPHERE.key)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().hemisphereForTest()?.atMs = midsummerNoon()
            val screen = c.get().findViewById<View>(android.R.id.content)
            assertTrue(shoot(screen, "hemi-card") > 50)
            c.get().goToForTest(Card.CALENDAR)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(1200)
            )
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(shoot(screen, "hemi-sky-card") > 50)
        }
    }

    /** Midwinter, when the terminator bends the other way. */
    @Test
    fun `midwinter, with the shadow over the pole`() {
        val winter = java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.DECEMBER, 21, 12, 0, 0)
        }.timeInMillis
        assertTrue(shoot(globe(at = winter), "hemi-midwinter") > 200)
        assertTrue(
            shoot(globe(which = Hemisphere.View.GLOBE, at = winter), "hemi-globe-midwinter") > 200
        )
    }

    /**
     * A real day's satellite mosaic, kept beside the tests.
     *
     * NASA's, fetched once and committed rather than downloaded while the
     * tests run: a test that needs the internet is a test that fails on a
     * train. It is the same request [SatelliteClouds.url] makes.
     */
    private fun satellite(): Bitmap {
        val bytes = javaClass.classLoader!!.getResourceAsStream("gibs-sample.jpg")!!
            .use { it.readBytes() }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    }

    /**
     * The world wearing yesterday's weather, on all three views.
     *
     * The only thing worth checking here is the thing only an eye can
     * check: whether the cloud sits *on* the earth — cyclones over the
     * ocean, the fronts running the right way, the continents still
     * legible under it — or whether it has been laid on top like a sheet
     * of tracing paper, which is what a projection out of step with the
     * map underneath looks like.
     */
    @Test
    fun `the globe wearing the satellite clouds`() {
        val sky = satellite()
        for (which in Hemisphere.View.entries) {
            assertTrue(
                shoot(globe(which = which, clouds = sky), "hemisphere-clouds-${which.key}") > 3
            )
        }
        // And the same three without them, for the comparison.
        for (which in Hemisphere.View.entries) {
            assertTrue(shoot(globe(which = which), "hemisphere-bare-${which.key}") > 3)
        }
    }

    /**
     * The clouds are drawn from the picture, and go when it goes.
     *
     * A pixel count rather than a look: a layer wired to nothing would
     * still photograph beautifully, since the earth underneath is already
     * a photograph.
     */
    @Test
    fun `the cloud layer is wired to the picture`() {
        val bare = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        globe(which = Hemisphere.View.GLOBE).draw(Canvas(bare))
        val clouded = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        globe(which = Hemisphere.View.GLOBE, clouds = satellite()).draw(Canvas(clouded))
        var moved = 0
        for (y in 0 until 1000 step 2) {
            for (x in 0 until 1000 step 2) {
                if (bare.getPixel(x, y) != clouded.getPixel(x, y)) moved++
            }
        }
        assertTrue("the globe ignored the satellite picture: $moved pixels", moved > 5000)
    }

    /**
     * The world opened out, which is what the pinch is for.
     *
     * Three pictures because the middle one is the interesting one: the
     * ring on its way out, half faded, with the world already growing
     * past where its ticks are. Only a look says whether that reads as
     * furniture getting out of the way or as a mess.
     */
    @Test
    fun `the world opened out`() {
        for ((name, zoom) in listOf(
            "hemi-zoom-none" to Hemisphere.ZOOM_MIN,
            "hemi-zoom-half" to (Hemisphere.ZOOM_MIN + Hemisphere.ZOOM_MAX) / 2f,
            "hemi-zoom-full" to Hemisphere.ZOOM_MAX
        )) {
            val view = globe()
            view.zoom = zoom
            assertTrue(name, shoot(view, name) > 200)
        }
    }
}
