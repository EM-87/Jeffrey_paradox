package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File

/**
 * The clouds at the size a phone shows them, which is not the size they
 * had been looked at.
 *
 * Asked as part of "look the graphics over": the resolution, and cuts
 * where the image ends. Both were there to see as soon as the globe was
 * drawn at a phone's width with a real day on it and a piece of it
 * magnified. The cloud was a mosaic of white squares — each pixel of the
 * photograph is half a degree, five or six pixels of glass, and each
 * point of the globe wore the one pixel it fell in. And where the
 * photograph runs out of daylight, towards the winter pole, it ends in
 * scallops, one a pass, and so did the cloud.
 *
 * Held against the real mosaic kept beside the tests, a day of northern
 * summer whose south end runs out between fifty-nine and sixty-nine
 * south.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudFieldTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun mosaic(): Bitmap {
        val bytes = javaClass.classLoader!!.getResourceAsStream("gibs-sample.jpg")!!.use { it.readBytes() }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false })!!
    }

    private fun pixelsOf(picture: Bitmap): IntArray =
        IntArray(picture.width * picture.height).also {
            picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height)
        }

    /**
     * The real picture's south end: whole down to fifty-eight south,
     * thinning through the scallops, and nothing from sixty-five on —
     * where the photograph still has white in it, in bites.
     */
    @Test
    fun `the real mosaic thins out where it runs out`() {
        val picture = mosaic()
        val w = picture.width
        val h = picture.height
        val pixels = pixelsOf(picture)
        val kept = SatelliteClouds.rowsKept(pixels, w, h)
        val field = SatelliteClouds.field(pixels, w, h)
        fun row(latitude: Double) = ((90.0 - latitude) / 180.0 * h).toInt().coerceIn(0, h - 1)
        // The whole north, the tropics with their gaps, and the south to fifty-eight.
        for (y in 0..row(-58.0)) assertTrue("row $y keeps ${kept[y]}", kept[y] > 0.98f)
        val scallops = kept[row(-62.25)]
        assertTrue("at sixty-two south, in the scallops, $scallops is kept", scallops in 0.2f..0.9f)
        for (y in row(-65.5) until h) assertEquals("row $y is kept", 0f, kept[y], 0f)
        // Thinner all the way down, and never thicker again.
        for (y in row(-58.0) until row(-65.5)) {
            assertTrue("row ${y + 1} keeps ${kept[y + 1]} and the row above it ${kept[y]}", kept[y + 1] <= kept[y] + 0.12f)
        }
        // What that takes off: the cloud the photograph has in its last bites.
        var bites = 0
        var left = 0
        for (y in row(-65.5) until h) for (x in 0 until w) {
            if (SatelliteClouds.veil(pixels[y * w + x]) > 40) bites++
            if ((field[y * w + x].toInt() and 0xFF) > 0) left++
        }
        println(
            "MEASURE the mosaic's south end: kept %.2f at 60 S, %.2f at 62 S, %.2f at 64 S | cloud south of 65.5 S: $bites pixels in the photograph, $left in the field"
                .format(kept[row(-60.25)], kept[row(-62.25)], kept[row(-64.25)])
        )
        assertTrue("the photograph has only $bites pixels of cloud in its last bites", bites > 300)
        assertEquals(0, left)
        // And north of all that the field is the veil, pixel for pixel.
        for (y in 0 until row(-58.0)) for (x in 0 until w step 7) {
            assertEquals(SatelliteClouds.veil(pixels[y * w + x]).toDouble(), (field[y * w + x].toInt() and 0xFF).toDouble(), 2.0)
        }
    }

    private fun globe(which: Hemisphere.View, at: Long, zoomTo: Float, clouds: Bitmap?): HemisphereView =
        HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            view = which
            atMs = at
            hourRing = false
            showSun = false
            showMoon = false
            moonlight = false
            located = false
            zoom = zoomTo
            this.clouds = clouds
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1080, 1080)
        }

    private fun shoot(v: View, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    private fun noon(): Long = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
    }.timeInMillis

    /** How much brighter a pixel is with the clouds on than without. */
    private fun veiled(on: Int, off: Int): Int = ((on shr 8) and 0xFF) - ((off shr 8) and 0xFF)

    /**
     * On the glass, at a phone's width and wound in as far as it goes:
     * the cloud has no steps in it. A step is two neighbouring pixels
     * whose cloud differs by a fifth of the scale, which a mosaic of
     * squares has along every side of every square and a sky has
     * almost nowhere.
     */
    @Test
    fun `the cloud on the glass is not a mosaic of squares`() {
        val sky = mosaic()
        val bare = shoot(globe(Hemisphere.View.NORTH, noon(), Hemisphere.ZOOM_MAX, null), "clouds-close-bare")
        val worn = shoot(globe(Hemisphere.View.NORTH, noon(), Hemisphere.ZOOM_MAX, sky), "clouds-close")
        var cloudy = 0
        var steps = 0
        for (y in 1 until worn.height - 1) {
            for (x in 1 until worn.width - 1) {
                val here = veiled(worn.getPixel(x, y), bare.getPixel(x, y))
                if (here > 30) cloudy++
                val across = veiled(worn.getPixel(x + 1, y), bare.getPixel(x + 1, y))
                val down = veiled(worn.getPixel(x, y + 1), bare.getPixel(x, y + 1))
                if (Math.abs(across - here) > 50 || Math.abs(down - here) > 50) steps++
            }
        }
        val share = 100.0 * steps / cloudy
        println("MEASURE cloud on the glass: $cloudy pixels of it, $steps at a step of a fifth of the scale (%.2f%%)".format(share))
        assertTrue("there is cloud on the glass to judge by: $cloudy pixels", cloudy > 100_000)
        // As a mosaic it was 6.4 parts in a hundred; read between the
        // pixels it is under half a part.
        assertTrue("$share% of the cloud is at a step", share < 1.0)
    }

    /**
     * And where the photograph ends, the cloud ends without bites. From
     * over the south pole at midwinter, wound in: between sixty-five
     * and sixty-nine south the photograph still has cloud in it, in
     * scallops, and the glass has none.
     */
    @Test
    fun `the cloud does not end in bites`() {
        val sky = mosaic()
        val bare = shoot(globe(Hemisphere.View.SOUTH, noon(), Hemisphere.ZOOM_MAX, null), "clouds-end-bare")
        val worn = shoot(globe(Hemisphere.View.SOUTH, noon(), Hemisphere.ZOOM_MAX, sky), "clouds-end")
        // A parallel is a circle the cosine of its latitude out from the middle.
        val world = Hemisphere.worldRadius(Hemisphere.ZOOM_MAX, ringed = false, moonOutside = false) * worn.width
        fun out(latitude: Double) = world * Math.cos(Math.toRadians(latitude))
        var bites = 0
        var before = 0
        for (y in 0 until worn.height) {
            for (x in 0 until worn.width) {
                val r = Math.hypot(x - worn.width / 2.0, y - worn.height / 2.0)
                if (r > out(58.0)) continue
                val cloud = veiled(worn.getPixel(x, y), bare.getPixel(x, y)) > 30
                if (!cloud) continue
                if (r < out(65.5)) bites++ else before++
            }
        }
        println("MEASURE the cloud's end from over the south pole: $before pixels of cloud between 58 and 65.5 S, $bites beyond")
        assertTrue("there is cloud up to the end to judge by: $before pixels", before > 2_000)
        assertEquals("cloud is drawn in the photograph's last bites", 0, bites)
    }

    /**
     * And the map under it the same way, on the views from over a pole:
     * a coast is an edge, but not a staircase. Counted as the pixels of
     * the bare world where the colour changes by a sixth of the scale
     * to the pixel beside it or below — which a stair has at every
     * tread and a coast read between its pixels spreads over three.
     */
    @Test
    fun `the coasts on the glass are not staircases`() {
        val bare = shoot(globe(Hemisphere.View.NORTH, noon(), Hemisphere.ZOOM_MAX, null), "coasts-close")
        var world = 0
        var treads = 0
        for (y in 1 until bare.height - 1) {
            for (x in 1 until bare.width - 1) {
                val here = bare.getPixel(x, y)
                if (here == SPACE) continue
                world++
                val g = (here shr 8) and 0xFF
                val across = (bare.getPixel(x + 1, y) shr 8) and 0xFF
                val down = (bare.getPixel(x, y + 1) shr 8) and 0xFF
                if (Math.abs(across - g) > 42 || Math.abs(down - g) > 42) treads++
            }
        }
        val share = 100.0 * treads / world
        println("MEASURE the bare world on the glass: $world pixels, $treads at a step of a sixth of the scale (%.3f%%)".format(share))
        assertTrue("there is a world on the glass: $world pixels", world > 500_000)
        // Read off the nearest pixel it was 1.69 parts in a hundred, and
        // between them it is 1.03: what is left is the night's lamps,
        // the shadow's edge and the snow.
        assertTrue("$share% of the world is at a step", share < 1.3)
    }

    private val SPACE = 0xFF05070D.toInt()
}
