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
 * The moon's light on the dark side: that it is where the moon is, as
 * strong as the moon is full, under the lights and inside the night —
 * and that the fullness it is drawn at is the fullness the satellite
 * saw.
 *
 * That last one is the point of the whole thing. The raw night pictures
 * kept beside the tests are three nights of one autumn: the new moon of
 * the 12th of October, the waning moon of the 2nd of November and the
 * full moon of the 26th of October, and the floor measured off each —
 * the grey of unlit sea and land — is a reading of the moonlight on
 * them. The phase the clock computes for those nights has to run with
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoonlightTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun utc(y: Int, m: Int, d: Int, h: Int): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d, h, 0, 0)
        }.timeInMillis

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun greys(name: String): Triple<Int, Int, ByteArray> {
        val bytes = resource(name)
        val picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        return Triple(w, h, ByteArray(w * h) { NightLights.luminance(pixels[it]).toByte() })
    }

    /**
     * Nothing at the new moon, a fifth at the full, and an eighth of that
     * at the half: the cube of the lit fraction, which is what the
     * photometry says and what the pictures below say.
     */
    @Test
    fun `the veil is as strong as the moon is full, cubed`() {
        assertEquals(0.0, Moonlight.alpha(0.0), 1e-9)
        assertEquals(Moonlight.MAX_ALPHA, Moonlight.alpha(1.0), 1e-9)
        assertEquals(Moonlight.MAX_ALPHA / 8, Moonlight.alpha(0.5), 1e-9)
        assertEquals(0, Moonlight.pixel(0.0, 1.0) ushr 24)
        // Fifteen hundredths of 255, rounded: the full moon's veil.
        assertEquals(38, Moonlight.pixel(1.0, 1.0) ushr 24)
        // Soft at the moon's horizon, like the sun's.
        assertEquals(0.0, Moonlight.up(-0.2), 1e-9)
        assertEquals(1.0, Moonlight.up(0.2), 1e-9)
        assertTrue(Moonlight.up(-0.03) > 0.0 && Moonlight.up(-0.03) < 1.0)
    }

    /**
     * The fullness the clock computes for the three sample nights runs
     * with the moonlight the satellite measured on them.
     *
     * The floor is the ninety-fifth percentile of the raw night over
     * places the clean night says are unlit, which on open sea is the
     * moon and the air and nothing else. Three nights are not a curve,
     * but they are an order, and the order is the thing a wrong
     * ephemeris would get wrong: a phase that put the full moon a
     * fortnight out would put the 26th under the 12th.
     */
    @Test
    fun `the computed phase runs with the moonlight the satellite saw`() {
        val (cw, ch, clean) = greys("gibs-lights-clean.png")
        fun cleanAt(u: Double, v: Double): Int =
            clean[(v * ch).toInt().coerceIn(0, ch - 1) * cw + (u * cw).toInt().coerceIn(0, cw - 1)].toInt() and 0xFF
        // Each night's picture is the orbits of its UTC day, flown at
        // half past one in the morning local time all round the world,
        // so the moon of the night is the moon at noon UTC of that day,
        // give or take twelve hours — which on a lunar month is nothing.
        val nights = listOf(
            Triple("new 2026-10-12", "gibs-lights-raw-newmoon.png", utc(2026, 10, 12, 12)),
            Triple("waning 2026-11-02", "gibs-lights-raw.png", utc(2026, 11, 2, 12)),
            Triple("full 2026-10-26", "gibs-lights-raw-fullmoon.png", utc(2026, 10, 26, 12))
        )
        val readings = nights.map { (name, file, at) ->
            val (w, h, raw) = greys(file)
            val floor = NightLights.residueFloor(raw, w, h, ::cleanAt)
            val lit = SolarTime.moonIllumination(at)
            println("$name: computed illumination %.2f, measured floor $floor".format(lit))
            Triple(name, lit, floor)
        }
        // The order first: new under waning under full, in both.
        for (i in 0 until readings.size - 1) {
            val (a, litA, floorA) = readings[i]
            val (b, litB, floorB) = readings[i + 1]
            assertTrue("$a is not less lit than $b in the ephemeris", litA < litB)
            assertTrue("$a is not darker than $b in the pictures", floorA < floorB)
        }
        // And the ends where they should be: a new moon is dark and a
        // full one is full, in the ephemeris.
        assertTrue("the new moon reads ${readings[0].second}", readings[0].second < 0.1)
        assertTrue("the full moon reads ${readings[2].second}", readings[2].second > 0.9)
        // The curve. Between the new moon's floor and the full moon's, how
        // far up is the half moon's? The pictures say fifteen per cent;
        // the veil's curve, the lit fraction cubed, says twelve and a
        // half. The square would say twenty-five and a straight line
        // fifty, and either is held to be wrong here.
        val (_, litHalf, floorHalf) = readings[1]
        val (_, _, floorNew) = readings[0]
        val (_, _, floorFull) = readings[2]
        val measured = (floorHalf - floorNew).toDouble() / (floorFull - floorNew)
        val drawn = Moonlight.alpha(litHalf) / Moonlight.alpha(1.0)
        println("half moon: measured %.3f of the way from new to full, drawn %.3f".format(measured, drawn))
        assertEquals(
            "the veil's curve does not follow the pictures: drawn $drawn, measured $measured",
            measured, drawn, 0.06
        )
    }

    // ------------------------------------------------------------ pictures

    private fun globe(at: Long, veil: Boolean): HemisphereView =
        HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            view = Hemisphere.View.GLOBE
            atMs = at
            hourRing = false
            showSun = false
            showMoon = false
            moonlight = veil
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1000)
        }

    private fun shoot(v: View, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    /** How many pixels of a picture differ from another's, by half. */
    private fun differs(a: Bitmap, b: Bitmap, left: Boolean): Int {
        var n = 0
        val x0 = if (left) 0 else a.width / 2
        val x1 = if (left) a.width / 2 else a.width
        for (y in 0 until a.height step 2) for (x in x0 until x1 step 2) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        }
        return n
    }

    /**
     * How many pixels that were in daylight — bright in the picture
     * without the veil — the veil touched. The daylit half is not a half
     * of the screen: in October the Arctic is in its night at the top of
     * the sunlit side, and the veil is right to be there.
     */
    private fun daylightTouched(on: Bitmap, off: Bitmap, at: Long): String {
        var n = 0
        var highest = -1.0
        val cx = on.width / 2.0
        val cy = on.height / 2.0
        val r = minOf(on.width, on.height) * Hemisphere.worldRadius(1f, false, false)
        val subLat = Hemisphere.subsolar(at)[0]
        for (y in 0 until on.height step 2) for (x in 0 until on.width step 2) {
            if (on.getPixel(x, y) == off.getPixel(x, y)) continue
            // The same question the view asks of every pixel, asked
            // here of the ones the veil touched: is the sun up there?
            // Brightness would not do — the night map's own city lights
            // are white, and they are in the night.
            val place = Hemisphere.unproject(
                Hemisphere.View.GLOBE, (x + 0.5 - cx) / r, (y + 0.5 - cy) / r, 0.0
            ) ?: continue
            val cosZ = Hemisphere.cosZenith(place[0], place[1], subLat)
            if (cosZ > highest) highest = cosZ
            // The sun's own dawn line, which the moon's rise line copies,
            // plus one cell of the mask: the mask is a hundred and
            // twenty-eight across and is stretched over the disc, so its
            // edge is a cell wide, and a cell at the terminator is a
            // couple of degrees of sun.
            if (cosZ > Moonlight.RISE + 0.04) n++
        }
        return "$n in daylight, highest sun over a touched pixel cos=%.3f".format(highest)
    }

    /** Whether a pixel went cooler and lighter: the veil, and not the lights. */
    private fun veiled(on: Int, off: Int): Boolean {
        val b = on and 0xFF
        val b0 = off and 0xFF
        val r = (on shr 16) and 0xFF
        return b - b0 > 8 && b >= r
    }

    private fun countVeiled(on: Bitmap, off: Bitmap, left: Boolean): Int {
        var n = 0
        val x0 = if (left) 0 else on.width / 2
        val x1 = if (left) on.width / 2 else on.width
        for (y in 0 until on.height step 2) for (x in x0 until x1 step 2) {
            if (veiled(on.getPixel(x, y), off.getPixel(x, y))) n++
        }
        return n
    }

    /**
     * Full moon: the dark side the moon is above is veiled; the sunlit
     * side is untouched. New moon: nothing at all. First quarter: half
     * of the night, with the moon's own terminator across it.
     */
    @Test
    fun `the veil lies on the moonlit night and nowhere else`() {
        // Two in the morning UTC, so the dark half faces us: Europe,
        // Africa and the Atlantic.
        val full = utc(2026, 10, 26, 2)
        val new = utc(2026, 10, 12, 2)
        val quarter = utc(2026, 10, 19, 2)
        val fullOff = shoot(globe(full, veil = false), "moonlight-2026-10-26-full-0200utc-off")
        val fullOn = shoot(globe(full, veil = true), "moonlight-2026-10-26-full-0200utc")
        val touched = daylightTouched(fullOn, fullOff, full)
        assertTrue("the veil lay on daylight: $touched", touched.startsWith("0 "))
        val veiledFull = countVeiled(fullOn, fullOff, left = true)
        assertTrue("no veil on a full-moon night: $veiledFull", veiledFull > 5_000)

        val newOff = shoot(globe(new, veil = false), "moonlight-2026-10-12-new-0200utc-off")
        val newOn = shoot(globe(new, veil = true), "moonlight-2026-10-12-new-0200utc")
        assertEquals("a veil on a new-moon night", 0, differs(newOn, newOff, left = true))

        val quarterOff = shoot(globe(quarter, veil = false), "moonlight-2026-10-19-first-quarter-0200utc-off")
        val quarterOn = shoot(globe(quarter, veil = true), "moonlight-2026-10-19-first-quarter-0200utc")
        // A sliver, and a faint one: at first quarter the moon stands
        // ninety degrees east of the sun, over the Pacific at this hour,
        // so only the Atlantic edge of the visible night has it above the
        // horizon — and at an eighth of the full moon's strength, which
        // is what the cube makes of a half-lit moon. That it is there at
        // all is the moon's own terminator, drawn; that it is faint is
        // the photometry.
        val quarterTouched = differs(quarterOn, quarterOff, left = true)
        assertTrue("no veil at first quarter: $quarterTouched", quarterTouched > 100)
        assertTrue(
            "the quarter moon veils as strongly as the full one",
            countVeiled(quarterOn, quarterOff, left = true) < veiledFull / 4
        )
    }
}
