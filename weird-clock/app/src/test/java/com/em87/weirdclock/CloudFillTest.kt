package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The gaps between the satellite's passes, which were slits of clear sky.
 *
 * Measured and left undone in the version before, and asked for with one
 * word. The daily photograph has a wedge of nothing between each pass and
 * the next all the way round the tropics; the day before's passes fall
 * somewhere else, and close them.
 *
 * Held against two real days running, as the service sent them: the 21st
 * and the 22nd of November 2026. What is counted is counted off those two
 * pictures here, and the store is held to the rule place by place — it
 * hands the globe exactly what the two pictures say, and exactly the
 * first of them when the second is not on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudFillTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val real = CloudStore.fetch

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun pixels(name: String): Triple<Int, Int, IntArray> {
        val raw = bytes(name)
        val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inScaled = false })!!
        val out = IntArray(picture.width * picture.height)
        picture.getPixels(out, 0, picture.width, 0, 0, picture.width, picture.height)
        return Triple(picture.width, picture.height, out)
    }

    private val today by lazy { pixels("gibs-clouds-2026-11-22.jpg") }
    private val before by lazy { pixels("gibs-clouds-2026-11-21.jpg") }

    /**
     * Noon UTC on the 23rd: the day the globe asks for then is the 22nd,
     * a day being asked for thirty hours behind — see
     * [SatelliteClouds.BEHIND_MS].
     */
    private val now = DailyImageStore.msOf("2026-11-23")!! + 12 * 3_600_000L

    private fun there(argb: Int) =
        maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF) > SatelliteClouds.NO_DATA

    @Before
    fun twoDaysOnTheWire() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, true)
            .commit()
        CloudStore.forget(context)
        serve("2026-11-22", "2026-11-21")
    }

    @After
    fun putTheSocketBack() {
        CloudStore.fetch = real
        CloudStore.forget(context)
    }

    /** The service has these days and no others. */
    private fun serve(vararg days: String) {
        CloudStore.fetch = CloudStore.Fetch { url ->
            val day = url.substringAfter("TIME=")
            if (day in days) bytes("gibs-clouds-$day.jpg") else null
        }
    }

    /** How much of the belt between two latitudes of a picture nobody photographed, in parts in a hundred. */
    private fun gaps(pixels: IntArray, w: Int, h: Int, north: Double, south: Double): Double {
        var n = 0
        var missing = 0
        for (y in ((90 - north) / 180 * h).toInt() until ((90 - south) / 180 * h).toInt()) for (x in 0 until w) {
            n++
            if (!there(pixels[y * w + x])) missing++
        }
        return 100.0 * missing / n
    }

    /**
     * The measurement, kept: a tenth of the tropics is missing from a
     * day, and none of it from the day and the day before together.
     */
    @Test
    fun `the day before has what the day lacks`() {
        val (w, h, sent) = today
        val filled = sent.copyOf()
        val closed = SatelliteClouds.fill(filled, before.third)
        val beltWas = gaps(sent, w, h, 15.0, -15.0)
        val beltNow = gaps(filled, w, h, 15.0, -15.0)
        val outerWas = gaps(sent, w, h, 30.0, 15.0)
        val outerNow = gaps(filled, w, h, 30.0, 15.0)
        println(
            "MEASURE the gaps of 22 Nov 2026: within 15 deg of the equator %.1f%% -> %.1f%% | 15 to 30 north %.1f%% -> %.1f%% | $closed pixels filled"
                .format(beltWas, beltNow, outerWas, outerNow)
        )
        assertTrue("the belt was $beltWas% missing: not the day that was measured", beltWas in 9.0..15.0)
        assertTrue("the outer tropics were $outerWas% missing", outerWas in 3.0..8.0)
        assertTrue("the belt is still $beltNow% missing", beltNow < 0.1)
        assertTrue("the outer tropics are still $outerNow% missing", outerNow < 0.1)
        // The night round the winter pole is dark both days, and is left.
        assertEquals(100.0, gaps(filled, w, h, 90.0, 75.0), 0.0)
    }

    /** The store hands the globe the two pictures' cloud, place by place. */
    @Test
    fun `the globe is handed the day with its gaps filled`() {
        assertTrue(CloudStore.refresh(context, now))
        val field = CloudStore.cached(context, now)!!
        val (w, h, sent) = today
        assertEquals(w, field.width)
        assertEquals(h, field.height)
        val filled = sent.copyOf()
        SatelliteClouds.fill(filled, before.third)
        val wanted = SatelliteClouds.field(filled, w, h)
        val bare = SatelliteClouds.field(sent, w, h)
        var differ = 0
        var gained = 0
        for (i in wanted.indices) {
            if (field.cover[i] != wanted[i]) differ++
            // Short of forty-five degrees, that is. Beyond it a row is
            // thinned by how much of it is missing, and a row with its
            // notches filled keeps more of the cloud it had.
            val latitude = 90.0 - (i / w + 0.5) * 180.0 / h
            if (Math.abs(latitude) >= SatelliteClouds.EDGE_FROM_DEG) continue
            if ((field.cover[i].toInt() and 0xFF) > (bare[i].toInt() and 0xFF) + 40) {
                gained++
                assertTrue("cloud was added at pixel $i, which was photographed that day", !there(sent[i]))
            }
        }
        println("MEASURE the field of 22 Nov 2026 filled from the 21st: $gained pixels of cloud where there was a gap")
        // A JPEG decodes the same way twice, so this is to the pixel.
        assertEquals("pixels where the store's field is not the two pictures'", 0, differ)
        assertTrue("only $gained pixels of cloud came out of the gaps", gained > 500)
        assertEquals("2026-11-22", CloudStore.worn(context, now)!!.day)
    }

    /** A day's picture put on the phone by hand, the socket left out of it. */
    private fun put(day: String, picture: ByteArray): File =
        File(context.cacheDir, "clouds-$day.jpg").also { it.writeBytes(picture) }

    /** With the day before not on the phone the day is as it was sent, and nothing else fills it. */
    @Test
    fun `without the day before the gaps are left`() {
        val (w, h, sent) = today
        val bare = SatelliteClouds.field(sent, w, h)
        // Nothing but the day itself.
        serve("2026-11-22")
        CloudStore.refresh(context, now)
        assertTrue(CloudStore.cached(context, now)!!.cover.contentEquals(bare))
        // A picture two days older is not the day before: the same file
        // under another day's name will do for one.
        CloudStore.forget(context)
        put("2026-11-22", bytes("gibs-clouds-2026-11-22.jpg"))
        put("2026-11-20", bytes("gibs-clouds-2026-11-21.jpg"))
        assertTrue("the gaps were filled from two days before", CloudStore.cached(context, now)!!.cover.contentEquals(bare))
        // A day before of another size is not the same world.
        CloudStore.forget(context)
        put("2026-11-22", bytes("gibs-clouds-2026-11-22.jpg"))
        val small = Bitmap.createBitmap(72, 36, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFFFAFAFC.toInt()) }
        File(context.cacheDir, "clouds-2026-11-21.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        assertTrue("the gaps were filled from a picture of another size", CloudStore.cached(context, now)!!.cover.contentEquals(bare))
        // And one a fetch was killed halfway through writing is no picture.
        CloudStore.forget(context)
        put("2026-11-22", bytes("gibs-clouds-2026-11-22.jpg"))
        put("2026-11-21", bytes("gibs-clouds-2026-11-21.jpg").copyOf(900))
        assertTrue("the gaps were filled from half a file", CloudStore.cached(context, now)!!.cover.contentEquals(bare))
    }

    /**
     * Switched off, the globe is handed nothing, though the pictures are
     * still on the phone: turning a switch off does not throw a week of
     * them away, and turning it on again should not cost a week of them.
     */
    @Test
    fun `switched off there are no clouds whatever is on the phone`() {
        assertTrue(CloudStore.refresh(context, now))
        assertTrue(CloudStore.cached(context, now) != null)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(Prefs.HEMISPHERE_CLOUDS, false).commit()
        assertEquals("clouds with their own switch off", null, CloudStore.cached(context, now))
        prefs.edit().putBoolean(Prefs.HEMISPHERE_CLOUDS, true).putBoolean(Prefs.WEATHER, false).commit()
        assertEquals("clouds with the weather off", null, CloudStore.cached(context, now))
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        assertTrue("the pictures went with the switch", CloudStore.cached(context, now) != null)
    }

    /**
     * Forgetting forgets the field, and not only the files it was made
     * of: a picture that lands afterwards with the same date on its file
     * is another picture.
     */
    @Test
    fun `a field forgotten is not handed back for a file with the same date on it`() {
        val stamp = put("2026-11-22", bytes("gibs-clouds-2026-11-22.jpg")).lastModified()
        val first = CloudStore.cached(context, now)!!
        CloudStore.forget(context)
        put("2026-11-22", bytes("gibs-clouds-2026-11-21.jpg")).setLastModified(stamp)
        val second = CloudStore.cached(context, now)!!
        assertNotSame("the field of a picture that was thrown away", first, second)
        assertTrue(!second.cover.contentEquals(first.cover))
    }

    /**
     * One field for one day, however often it is asked for — the globe
     * projects again for a new one — and a new one when the day before
     * arrives after the day did.
     */
    @Test
    fun `the field is made once and made again when the day before arrives`() {
        serve("2026-11-22")
        CloudStore.refresh(context, now)
        val alone = CloudStore.cached(context, now)!!
        assertSame("a second field for the same day and the same files", alone, CloudStore.cached(context, now))
        // The day before lands.
        File(context.cacheDir, "clouds-2026-11-21.jpg").writeBytes(bytes("gibs-clouds-2026-11-21.jpg"))
        val filled = CloudStore.cached(context, now)!!
        assertNotSame("the day before arrived and the field was not made again", alone, filled)
        assertTrue(!filled.cover.contentEquals(alone.cover))
        assertSame(filled, CloudStore.cached(context, now))
        // A day fetched again is a new picture, and a new field.
        val file = File(context.cacheDir, "clouds-2026-11-22.jpg")
        file.setLastModified(file.lastModified() + 5_000L)
        assertNotSame(filled, CloudStore.cached(context, now))
    }

    private fun globe(clouds: SatelliteClouds.Field?): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = Hemisphere.View.GLOBE
        // Six in the evening in Greenwich on the day of the photograph:
        // the face of the ball that is turned this way is in daylight.
        atMs = DailyImageStore.msOf("2026-11-22")!! + 18 * 3_600_000L
        hourRing = false
        showSun = false
        showMoon = false
        moonlight = false
        located = false
        zoom = Hemisphere.ZOOM_MAX
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

    /** And on the glass: cloud where the slits were, and none taken away. */
    @Test
    fun `the slits through the tropics are closed on the globe`() {
        assertTrue(CloudStore.refresh(context, now))
        val (w, h, sent) = today
        val slit = shoot(globe(SatelliteClouds.Field(w, h, SatelliteClouds.field(sent, w, h))), "clouds-gaps-open")
        val closed = shoot(globe(CloudStore.cached(context, now)), "clouds-gaps-closed")
        var more = 0
        var less = 0
        for (y in 0 until closed.height) for (x in 0 until closed.width) {
            val was = (slit.getPixel(x, y) shr 8) and 0xFF
            val now0 = (closed.getPixel(x, y) shr 8) and 0xFF
            if (now0 - was > 30) more++
            if (was - now0 > 30) less++
        }
        println("MEASURE the globe with the gaps filled: $more pixels gained cloud, $less lost it")
        assertTrue("only $more pixels of the glass gained cloud", more > 3_000)
        assertEquals("cloud was taken off the glass", 0, less)
    }
}
