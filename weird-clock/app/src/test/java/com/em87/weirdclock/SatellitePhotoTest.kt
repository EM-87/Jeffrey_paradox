package com.em87.weirdclock

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The day's photograph laid over the month's map.
 *
 * Asked for as the monthly picture joined to the slices of the new daily
 * scans. What can go wrong with that is all at the joins: a photograph
 * laid where nobody photographed is a black patch on the world, one laid
 * right up to the edge of a hole is a dark rim round it, one that stops
 * at a line is a circle drawn on the sea, and one a pixel out of step
 * with the map has two coasts. Each is held here, the rule on pictures
 * made for the purpose and then the whole of it on a real one: the
 * mosaic of 22 November 2026 from VIIRS on NOAA-20, as the service
 * sent it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SatellitePhotoTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun sent(): Triple<Int, Int, IntArray> {
        val raw = javaClass.classLoader!!.getResourceAsStream("gibs-photo-2026-11-22.jpg")!!.use { it.readBytes() }
        val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
        val out = IntArray(picture.width * picture.height)
        picture.getPixels(out, 0, picture.width, 0, 0, picture.width, picture.height)
        return Triple(picture.width, picture.height, out)
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** The request is the whole world of a day, from the afternoon satellite and then its successor. */
    @Test
    fun `the photograph is asked of two satellites in turn, for the whole world`() {
        val urls = SatellitePhoto.urls("2026-11-22")
        assertEquals(2, urls.size)
        assertTrue(urls[0], "LAYERS=VIIRS_NOAA20_CorrectedReflectance_TrueColor&" in urls[0])
        assertTrue(urls[1], "LAYERS=VIIRS_NOAA21_CorrectedReflectance_TrueColor&" in urls[1])
        for (url in urls) {
            assertTrue(url, url.startsWith("https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?"))
            assertTrue(url, "BBOX=-90,-180,90,180" in url)
            assertTrue(url, "WIDTH=1024&HEIGHT=512" in url)
            assertTrue(url, url.endsWith("&TIME=2026-11-22"))
        }
        // The same request the veil's photograph is, but for the layer and the size.
        assertEquals(
            SatelliteClouds.url("2026-11-22"),
            urls[0].replace(SatellitePhoto.LAYER, SatelliteClouds.LAYER).replace("WIDTH=1024&HEIGHT=512", "WIDTH=720&HEIGHT=360")
        )
    }

    /**
     * Asked for at the size of the maps it is laid over, every one of
     * them: it is laid pixel on pixel, and not at all at another size.
     */
    @Test
    fun `the photograph is the size of the maps`() {
        val world = HemisphereView(context)
        for (month in 1..12) {
            world.atMs = DailyImageStore.msOf("2026-%02d-15".format(month))
            val map = world.dayMapForTest()
            assertEquals("the map of month $month", month, world.dayMapMonthForTest())
            assertEquals("the map of month $month", SatellitePhoto.WIDTH, map.width)
            assertEquals("the map of month $month", SatellitePhoto.HEIGHT, map.height)
        }
        val (w, h, _) = sent()
        assertEquals("the service sent another size than it was asked for", SatellitePhoto.WIDTH, w)
        assertEquals(SatellitePhoto.HEIGHT, h)
    }

    /** No row is more than its distance from a row that is running out allows. */
    @Test
    fun `the photograph slopes down to where it stops`() {
        val h = 180
        // Nothing for the top twenty rows, everything below: four degrees is four rows here.
        val slope = SatellitePhoto.slope(FloatArray(h) { if (it < 20) 0f else 1f })
        for (y in 0 until 20) assertEquals("row $y", 0f, slope[y], 0f)
        assertTrue("the first row is ${slope[20]}", slope[20] > 0.05f && slope[20] < 0.25f)
        assertEquals(0.5f, slope[21], 0.001f)
        assertTrue(slope[22] > 0.75f && slope[22] < 1f)
        assertEquals(1f, slope[23], 0f)
        assertEquals(1f, slope[100], 0f)
        assertEquals(1f, slope[h - 1], 0f)
        for (y in 20..23) assertTrue("not a slope at $y", slope[y] > slope[y - 1])
        // A row that is half there is half, and holds its neighbours down with it.
        val dip = SatellitePhoto.slope(FloatArray(h) { if (it == 60) 0.5f else 1f })
        assertEquals(0.5f, dip[60], 0.001f)
        assertTrue(dip[59] < 1f && dip[61] < 1f && dip[59] > dip[60] && dip[61] > dip[60])
        assertEquals(1f, dip[56], 0f)
        assertEquals(1f, dip[64], 0f)
        // Whole all the way: nothing taken off anywhere.
        assertTrue(SatellitePhoto.slope(FloatArray(h) { 1f }).all { it == 1f })
        assertEquals(4.0, SatellitePhoto.FADE_DEG, 0.0)
    }

    /** All of it where it was photographed, none where it was not, nor beside where it was not. */
    @Test
    fun `none of the photograph is laid where nobody photographed, nor beside it`() {
        val w = 360
        val h = 180
        val sea = rgb(20, 40, 90)
        val whole = SatellitePhoto.picture(IntArray(w * h) { sea }, w, h)
        assertEquals(w, whole.width)
        assertEquals(h, whole.height)
        assertTrue("a photograph with nothing missing is not laid whole", whole.pixels.all { it == sea })

        // One pixel nobody photographed, in the tropics: it and the four
        // beside it, and no others. The row is not thinned for it.
        val holed = IntArray(w * h) { sea }
        holed[90 * w + 100] = rgb(3, 3, 3)
        val laid = SatellitePhoto.picture(holed, w, h)
        for ((x, y) in listOf(100 to 90, 99 to 90, 101 to 90, 100 to 89, 100 to 91)) {
            assertEquals("the photograph was laid at $x,$y", 0, laid.pixels[y * w + x] ushr 24)
        }
        for ((x, y) in listOf(98 to 90, 102 to 90, 99 to 89, 101 to 91, 100 to 88, 100 to 92, 300 to 90)) {
            assertEquals("the photograph was not laid at $x,$y", sea, laid.pixels[y * w + x])
        }
        // The world goes round: a hole at the date line has neighbours on both sides of it.
        val edge = IntArray(w * h) { sea }
        edge[90 * w] = rgb(0, 0, 0)
        val round = SatellitePhoto.picture(edge, w, h)
        assertEquals(0, round.pixels[90 * w + w - 1] ushr 24)
        assertEquals(0, round.pixels[90 * w + 1] ushr 24)
        assertEquals(sea, round.pixels[90 * w + w - 2])

        // The photograph running out towards the pole: none of the rows
        // it has not got, and a slope of four degrees up to them.
        val ended = IntArray(w * h) { if (it / w < 30) rgb(0, 0, 0) else sea }
        val faded = SatellitePhoto.picture(ended, w, h)
        for (y in 0..30) assertEquals("row $y", 0, faded.pixels[y * w + 50] ushr 24)
        var last = 0
        for (y in 31..32) {
            val share = faded.pixels[y * w + 50] ushr 24
            assertTrue("row $y is laid $share after $last", share > last && share < 255)
            assertEquals("the colour was changed with the share", sea and 0xFFFFFF, faded.pixels[y * w + 50] and 0xFFFFFF)
            last = share
        }
        assertEquals(128, faded.pixels[31 * w + 50] ushr 24)
        assertEquals(255, faded.pixels[33 * w + 50] ushr 24)
        assertEquals(255, faded.pixels[90 * w + 50] ushr 24)
        // A picture that is not the size it says is nothing.
        assertTrue(SatellitePhoto.picture(IntArray(10), 4, 4).pixels.all { it == 0 })
    }

    /** The map, the photograph, or between them by the share, and never the wrong size. */
    @Test
    fun `the photograph is laid over the map by its share`() {
        val map = intArrayOf(rgb(10, 20, 30), rgb(10, 20, 30), rgb(10, 20, 30), rgb(200, 100, 0))
        val photo = intArrayOf(
            (0 shl 24) or 0xFFFFFF,
            (255 shl 24) or 0x405060,
            (128 shl 24) or 0x6478C8,
            (51 shl 24) or 0x000000
        )
        val before = map.copyOf()
        val laid = SatellitePhoto.over(map, 2, 2, SatellitePhoto.Picture(2, 2, photo))!!
        assertEquals("none of it: the map", rgb(10, 20, 30), laid[0])
        assertEquals("all of it: the photograph", rgb(0x40, 0x50, 0x60), laid[1])
        // 128 parts in 255 of the way from 10/20/30 to 100/120/200.
        assertEquals(rgb(55, 70, 115), laid[2])
        // A fifth of the way to black.
        assertEquals(rgb(160, 80, 0), laid[3])
        assertTrue("the map was written on", map.contentEquals(before))
        assertTrue(laid.all { it ushr 24 == 255 })
        // Another size is not laid at all.
        assertEquals(null, SatellitePhoto.over(map, 2, 2, SatellitePhoto.Picture(4, 1, photo)))
        assertEquals(null, SatellitePhoto.over(map, 4, 1, SatellitePhoto.Picture(2, 2, photo)))
        assertEquals(null, SatellitePhoto.over(IntArray(6), 2, 3, SatellitePhoto.Picture(2, 2, photo)))
        // Nor is a map or a photograph that is not as long as it is wide and high.
        assertEquals(null, SatellitePhoto.over(IntArray(6), 2, 2, SatellitePhoto.Picture(2, 2, photo)))
        assertEquals(null, SatellitePhoto.over(IntArray(3), 2, 2, SatellitePhoto.Picture(2, 2, photo)))
        assertEquals(null, SatellitePhoto.over(map, 2, 2, SatellitePhoto.Picture(2, 2, IntArray(3))))
        assertEquals(null, SatellitePhoto.over(map, 2, 2, SatellitePhoto.Picture(2, 2, IntArray(6))))
    }

    /**
     * The real one: whole from the Antarctic to the edge of the northern
     * night, nothing missing through the tropics, and the map left to
     * itself beyond where the photograph stops.
     */
    @Test
    fun `a real day is laid whole up to the edge of the polar night`() {
        val (w, h, pixels) = sent()
        val picture = SatellitePhoto.picture(pixels, w, h)
        fun shareOfRow(latitude: Double): Double {
            val y = ((90.0 - latitude) / 180.0 * h).toInt()
            var sum = 0L
            for (x in 0 until w) sum += picture.pixels[y * w + x] ushr 24
            return sum / (255.0 * w)
        }
        var whole = 0
        var none = 0
        var missingInTheBelt = 0
        var belt = 0
        for (y in 0 until h) {
            val latitude = 90.0 - (y + 0.5) * 180.0 / h
            for (x in 0 until w) {
                val share = picture.pixels[y * w + x] ushr 24
                if (share == 255) whole++
                if (share == 0) none++
                if (Math.abs(latitude) < 15.0) {
                    belt++
                    if (!SatelliteClouds.photographed(pixels[y * w + x])) missingInTheBelt++
                }
            }
        }
        val n = (w * h).toDouble()
        println(
            "MEASURE the photograph of 22 Nov 2026: laid whole over %.1f%% of the world, not at all over %.1f%% | unphotographed within 15 deg of the equator %.2f%% | laid by row: 65 N %.2f, 58 N %.2f, 55 N %.2f, 50 N %.2f, 0 %.2f, 70 S %.2f"
                .format(100 * whole / n, 100 * none / n, 100.0 * missingInTheBelt / belt, shareOfRow(65.0), shareOfRow(58.0), shareOfRow(55.0), shareOfRow(50.0), shareOfRow(0.0), shareOfRow(-70.0))
        )
        // No wedges between the passes: that is why it is this satellite.
        assertTrue("${100.0 * missingInTheBelt / belt}% of the belt unphotographed", missingInTheBelt < belt / 200)
        assertTrue("laid whole over only ${100 * whole / n}% of the world", whole > 0.70 * n)
        // It is late November: nothing north of the edge of the night, all of it south of it.
        assertEquals(0.0, shareOfRow(65.0), 0.0)
        assertEquals(0.0, shareOfRow(80.0), 0.0)
        assertTrue(shareOfRow(50.0) > 0.98)
        assertTrue(shareOfRow(0.0) > 0.97)
        assertTrue(shareOfRow(-70.0) > 0.97)
        // And between, a slope and not a step: no row jumps by a third of the whole.
        var steepest = 0.0
        for (y in 1 until h) {
            val jump = Math.abs(shareOfRow(90.0 - (y + 0.5) * 180.0 / h) - shareOfRow(90.0 - (y - 0.5) * 180.0 / h))
            if (jump > steepest) steepest = jump
        }
        assertTrue("the photograph ends at a step of $steepest", steepest < 0.33)
    }

    /** Laid over the November map: the map where there is no photograph, the day where there is. */
    @Test
    fun `over the month's map the day is where it was photographed and the map where it was not`() {
        val (w, h, pixels) = sent()
        val picture = SatellitePhoto.picture(pixels, w, h)
        val world = HemisphereView(context)
        world.atMs = DailyImageStore.msOf("2026-11-22")
        val bitmap = world.dayMapForTest()
        val map = IntArray(w * h)
        bitmap.getPixels(map, 0, w, 0, 0, w, h)
        val laid = SatellitePhoto.over(map, w, h, picture)!!
        fun luma(c: Int) = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        var northSame = 0
        var north = 0
        var photographExactly = 0
        var south = 0
        var mapLuma = 0L
        var laidLuma = 0L
        for (y in 0 until h) {
            val latitude = 90.0 - (y + 0.5) * 180.0 / h
            for (x in 0 until w) {
                val i = y * w + x
                if (latitude > 62.0) {
                    north++
                    if (laid[i] == (map[i] or (0xFF shl 24))) northSame++
                } else if (latitude < 45.0 && picture.pixels[i] ushr 24 == 255) {
                    south++
                    if (laid[i] == (pixels[i] or (0xFF shl 24))) photographExactly++
                    mapLuma += luma(map[i])
                    laidLuma += luma(laid[i])
                }
            }
        }
        println(
            "MEASURE laid over the November map: north of 62 N the map itself at $northSame of $north pixels | south of 45 N the photograph itself at $photographExactly of $south, mean brightness %.0f where the map's is %.0f"
                .format(laidLuma.toDouble() / south, mapLuma.toDouble() / south)
        )
        assertEquals("the map was changed where there is no photograph", north, northSame)
        assertEquals("the photograph was changed where it is laid whole", south, photographExactly)
        // A day has cloud in it and a month's composite has none: it is a brighter world.
        assertTrue(laidLuma > mapLuma * 1.3)
        assertFalse(laid.contentEquals(map))
    }
}
