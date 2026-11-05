package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The world wears the month it is.
 *
 * The day map was one month of the Blue Marble — August — all year, so
 * the snow line sat where August's does in January. There are twelve
 * now, and the globe picks the one for the instant it shows, which is
 * not always now: wound back across a month it changes with the world.
 *
 * Measured over Hudson Bay and the land around it, which is white in
 * January and dark in July, and on the count of bakes, which is the disc
 * being projected again from the new map rather than redrawn from the
 * old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HemisphereMonthTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun noonUtc(month: Int, day: Int = 15): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, month - 1, day, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** The northern hemisphere from above, the pole at the centre. */
    private fun globe(): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = Hemisphere.View.NORTH
        hourRing = false
        showSun = false
        showMoon = false
        moonlight = false
        located = false
        measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1000, 1000)
    }

    /** Draws the globe, and keeps the picture under [name] if asked. */
    private fun shot(v: HemisphereView, name: String? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        name?.let { n ->
            File(outDir, "$n.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun brightness(c: IntArray): Int = (c[0] + c[1] + c[2]) / 3

    /** Six in the evening UTC: early afternoon over Hudson Bay, in daylight either month. */
    private val afternoon = 6 * 3_600_000L

    @Test
    fun `the day map is the month of the instant shown, and changes with it`() {
        val globe = globe()
        globe.atMs = noonUtc(1) + afternoon
        val january = shot(globe, "month-january-north")
        assertEquals("not January's map", 1, globe.dayMapMonthForTest())
        val bakesAfterJanuary = globe.bakesForTest()
        assertTrue("nothing was baked", bakesAfterJanuary > 0)

        globe.atMs = noonUtc(7) + afternoon
        val july = shot(globe, "month-july-north")
        assertEquals("not July's map", 7, globe.dayMapMonthForTest())
        assertTrue("the discs were not baked again for the new month", globe.bakesForTest() > bakesAfterJanuary)
        // Not the same picture: the two renders differ by far more than
        // the terminator's drift between the dates. Counted over the
        // whole disc rather than at one place, so the test does not hang
        // on where exactly Hudson Bay lands in the projection.
        val differs = differingPixels(january, july)
        println("MONTH pixels differing between January and July on the pole view: $differs")
        assertTrue("January and July look the same: $differs pixels differ", differs > 40_000)
    }

    /** How many pixels differ between two pictures of the disc. */
    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = kotlin.math.abs(Color.red(p) - Color.red(q)) +
                kotlin.math.abs(Color.green(p) - Color.green(q)) +
                kotlin.math.abs(Color.blue(p) - Color.blue(q))
            if (d > 60) n++
        }
        return n * 4
    }

    /** And the snow: central Siberia is white in January and is not in July. */
    @Test
    fun `January is white where July is dark`() {
        // Read straight off the maps the globe draws from, through the
        // globe's own choice of month, so what is checked is the picture
        // and not the projection. The taiga round 62°N 100°E: on a map
        // of 1024 by 512 that is column 796, row 80.
        val globe = globe()
        globe.atMs = noonUtc(1)
        val january = sampleMap(globe, 780..810, 70..90)
        globe.atMs = noonUtc(7)
        val july = sampleMap(globe, 780..810, 70..90)
        println("MONTH Siberia: January ${january.joinToString("/")}, July ${july.joinToString("/")}")
        assertTrue("January is not white there: ${january.joinToString("/")}", brightness(january) > 150)
        assertTrue("July is not darker there: ${july.joinToString("/")}", brightness(january) - brightness(july) > 60)
    }

    private fun sampleMap(globe: HemisphereView, xs: IntRange, ys: IntRange): IntArray {
        val map = globe.dayMapForTest()
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in ys) for (x in xs) {
            val c = map.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
