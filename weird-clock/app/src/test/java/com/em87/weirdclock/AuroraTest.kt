package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The aurora, which was in the pictures and not on the globe.
 *
 * Reported as the auroras not showing, with two guesses at why: that
 * they are too faint to come through, or that the map is not being
 * fetched every day. It was the first. The raw night the app fetches has
 * the oval in it on every night that was looked at — a band right round
 * the pole — and the rule that turns that picture into light painted a
 * twentieth of it.
 *
 * Held against one of those nights: the raw night of the 8th of November
 * 2026 and the clean night of the 7th, as the service sent them, two
 * days before a new moon. Everything measured here is measured on those
 * two pictures by this test, and what the rule paints is counted in the
 * oval, over open sea under it, and over the oceans it must leave dark.
 *
 * And two things the first pictures of it showed that were not aurora
 * and were painted as if they were: a disc on the north pole, where the
 * clean night has no reading to be compared with, and a ring round
 * Antarctica, where the raw night was not taken at night.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuroraTest {

    private companion object {
        const val RAW_DAY = "2026-11-08"
        const val CLEAN_DAY = "2026-11-07"
    }

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val realFetch = NightLightStore.fetch

    @After
    fun tidy() {
        NightLightStore.fetch = realFetch
        NightLightStore.forget(context)
    }

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun greys(name: String): Triple<Int, Int, ByteArray> {
        val picture = bytes(name).let {
            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inScaled = false })!!
        }
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        return Triple(w, h, ByteArray(w * h) { NightLights.luminance(pixels[it]).toByte() })
    }

    /** The raw night as the service sent it, twilight and all. */
    private val sent by lazy { greys("gibs-lights-raw-aurora.png") }

    /** And as the store hands it to the rules: without the rows that were not night. */
    private val raw by lazy {
        val (w, h, lum) = sent
        val night = lum.copyOf()
        NightLights.withoutTwilight(night, w, h, RAW_DAY)
        Triple(w, h, night)
    }
    private val clean by lazy { greys("gibs-lights-clean-aurora.png") }

    // ------------------------------------------------------------ the rule

    /** A night of [w] by [h] where every place is flown over and reads [grey] raw, 7 clean. */
    private fun flat(w: Int, h: Int, grey: Int): Pair<ByteArray, ByteArray> =
        ByteArray(w * h) { grey.toByte() } to ByteArray(w * h) { 7 }

    /**
     * The haze is the average surplus of the unlit places round each
     * pixel: a glow that is everywhere comes through whole and a point
     * comes through as a ninth of itself.
     */
    @Test
    fun `the haze keeps what is spread out and loses what is a point`() {
        // Three hundred and sixty across: a degree a pixel, three by three.
        val w = 360
        val h = 180
        val (rawLum, cleanLum) = flat(w, h, 47)
        val even = NightLights.haze(rawLum, cleanLum, w, h)!!
        assertTrue("an even glow of forty over the clean night is not forty everywhere",
            even.all { (it.toInt() and 0xFF) == 40 })

        // One pixel of 250 on a sea of 7: a fire, a ship.
        val (pointRaw, pointClean) = flat(w, h, 7)
        pointRaw[90 * w + 180] = 250.toByte()
        val point = NightLights.haze(pointRaw, pointClean, w, h)!!
        assertEquals("a point is not spread over its nine neighbours", 243 / 9, point[90 * w + 180].toInt() and 0xFF)
        assertEquals(243 / 9, point[91 * w + 181].toInt() and 0xFF)
        assertEquals("a point reaches two pixels away", 0, point[90 * w + 182].toInt() and 0xFF)

        // A band ten rows deep, right round: its middle is itself.
        val (bandRaw, bandClean) = flat(w, h, 7)
        for (y in 20 until 30) for (x in 0 until w) bandRaw[y * w + x] = 67
        val band = NightLights.haze(bandRaw, bandClean, w, h)!!
        assertEquals(60, band[25 * w + 100].toInt() and 0xFF)
        assertEquals("the edge of a band is not two thirds of it", 40, band[20 * w + 100].toInt() and 0xFF)
        assertEquals(0, band[40 * w + 100].toInt() and 0xFF)
    }

    /** Only the unlit, flown-over places count, and a place with too few of them has no haze. */
    @Test
    fun `the haze is of the dark places and the flown ones`() {
        val w = 360
        val h = 180
        // A city: lit in the clean night, and brighter still in the raw.
        val (rawLum, cleanLum) = flat(w, h, 7)
        for (y in 88..92) for (x in 178..182) {
            cleanLum[y * w + x] = 200.toByte()
            rawLum[y * w + x] = 250.toByte()
        }
        val city = NightLights.haze(rawLum, cleanLum, w, h)!!
        assertEquals("a city's surplus made a halo round it", 0, city[90 * w + 176].toInt() and 0xFF)
        assertEquals(0, city[90 * w + 184].toInt() and 0xFF)
        // In the middle of it there is nobody unlit to ask.
        assertEquals(0, city[90 * w + 180].toInt() and 0xFF)

        // Not flown over: nothing is known, and nought is not averaged in.
        val (halfRaw, halfClean) = flat(w, h, 47)
        for (y in 0 until h) for (x in 0 until w / 2) halfRaw[y * w + x] = 0
        val half = NightLights.haze(halfRaw, halfClean, w, h)!!
        // On the flown side of the edge the glow is the whole glow.
        assertEquals(40, half[90 * w + w / 2].toInt() and 0xFF)
        assertEquals(40, half[90 * w + w / 2 + 20].toInt() and 0xFF)
        // A pixel into the unflown side, it has three of nine to go on,
        // which is enough; two pixels in, it has none.
        assertEquals(40, half[90 * w + w / 2 - 1].toInt() and 0xFF)
        assertEquals(0, half[90 * w + w / 2 - 2].toInt() and 0xFF)

        // One dark place in the middle of a lit quarter cannot speak for
        // the sky over it — one of nine is less than a quarter — and
        // two can.
        val (blockRaw, blockClean) = flat(w, h, 47)
        for (y in 20..30) for (x in 20..30) blockClean[y * w + x] = 200.toByte()
        blockClean[25 * w + 25] = 7
        assertEquals(0, NightLights.haze(blockRaw, blockClean, w, h)!![25 * w + 25].toInt() and 0xFF)
        blockClean[25 * w + 26] = 7
        assertEquals(40, NightLights.haze(blockRaw, blockClean, w, h)!![25 * w + 25].toInt() and 0xFF)

        // Two pictures of different shapes are not two readings of a night.
        assertNull(NightLights.haze(ByteArray(100), ByteArray(50), 10, 10))
        assertNull(NightLights.haze(ByteArray(0), ByteArray(0), 0, 0))
    }

    /**
     * Where the clean night has no reading there is nothing to be over.
     *
     * The clean product stops at eighty north. Read as darkness, its
     * blank made everything the raw night saw beyond it a surplus, and
     * the globe had a cyan disc on the pole.
     */
    @Test
    fun `a place the clean night does not cover has no haze`() {
        val w = 360
        val h = 180
        val (rawLum, cleanLum) = flat(w, h, 47)
        // The top ten rows: the raw night reads what it reads everywhere,
        // the clean one nothing at all.
        for (y in 0 until 10) for (x in 0 until w) cleanLum[y * w + x] = 0
        val haze = NightLights.haze(rawLum, cleanLum, w, h)!!
        for (y in 0 until 8) {
            assertEquals("row $y, beyond the clean night's edge, has a haze", 0, haze[y * w + 100].toInt() and 0xFF)
        }
        // The last row before the edge can still ask the three beneath it.
        assertEquals(40, haze[9 * w + 100].toInt() and 0xFF)
        assertEquals(40, haze[10 * w + 100].toInt() and 0xFF)
        // And the floor is not read off the blank either: a band of it
        // across the equator, where the raw night blazes, moves nothing.
        val (blazeRaw, blazeClean) = flat(w, h, 27)
        for (y in 80 until 100) for (x in 0 until w) {
            blazeRaw[y * w + x] = 200.toByte()
            blazeClean[y * w + x] = 0
        }
        val blaze = NightLights.haze(blazeRaw, blazeClean, w, h)!!
        assertEquals(20 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(blaze, blazeRaw, blazeClean, w, h))
    }

    /** The picture is the whole world: the window goes round the back of it and stops at the poles. */
    @Test
    fun `the haze goes round the date line and stops at the poles`() {
        val w = 360
        val h = 180
        val (rawLum, cleanLum) = flat(w, h, 7)
        // One column of glow on the very edge of the picture.
        for (y in 0 until h) rawLum[y * w + 0] = 97
        val seam = NightLights.haze(rawLum, cleanLum, w, h)!!
        assertEquals("the glow did not reach the pixel before the edge, round the back",
            30, seam[90 * w + (w - 1)].toInt() and 0xFF)
        assertEquals(30, seam[90 * w + 1].toInt() and 0xFF)
        assertEquals(0, seam[90 * w + 2].toInt() and 0xFF)
        // The top row has six neighbours and not nine, and is still the
        // average of the ones it has.
        assertEquals(30, seam[0 * w + 1].toInt() and 0xFF)
        assertEquals(30, seam[(h - 1) * w + (w - 1)].toInt() and 0xFF)
    }

    /**
     * The haze's floor is read near the equator and nowhere else, so an
     * aurora cannot raise the floor that is there to find it.
     */
    @Test
    fun `the haze's floor is read where there is no aurora`() {
        val w = 360
        val h = 180
        val (rawLum, cleanLum) = flat(w, h, 47)
        val field = ByteArray(w * h)
        // Within fifty degrees of the equator: a hundred rows, greys
        // nought to eighty-nine by column, each as often as the next;
        // outside it, a blaze.
        for (y in 0 until h) for (x in 0 until w) {
            field[y * w + x] = if (y in 40 until 140) (x % 90).toByte() else 240.toByte()
        }
        // The ninety-ninth percentile of nought to eighty-nine, and the margin.
        assertEquals(89 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(field, rawLum, cleanLum, w, h))
        // With the blaze half a row further in it is a two-hundredth of
        // what is read and has not moved it; two rows, and it has.
        for (x in 0 until w / 2) field[40 * w + x] = 240.toByte()
        assertEquals(89 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(field, rawLum, cleanLum, w, h))
        for (x in 0 until w) {
            field[40 * w + x] = 240.toByte()
            field[41 * w + x] = 240.toByte()
        }
        assertEquals(240 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(field, rawLum, cleanLum, w, h))
        // A night with nothing in it has a floor all the same.
        assertEquals(NightLights.HAZE_FLOOR_LEAST, NightLights.hazeFloor(ByteArray(w * h), rawLum, cleanLum, w, h))
        assertEquals(255, NightLights.hazeFloor(ByteArray(10), rawLum, cleanLum, w, h))
        assertEquals(50.0, NightLights.QUIET_LATITUDE, 0.0)
    }

    /**
     * A day the satellite is still flying over is a few strips of sky
     * and a great deal of blank, and the blank is not the darkest sky
     * there is: the floor is read off the strips.
     */
    @Test
    fun `the floor of a night half flown is the floor of the half`() {
        val w = 360
        val h = 180
        val (rawLum, cleanLum) = flat(w, h, 47)
        val field = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) field[y * w + x] = (x % 90).toByte()
        val whole = NightLights.hazeFloor(field, rawLum, cleanLum, w, h)
        assertEquals(89 + NightLights.HAZE_MARGIN, whole)
        // Three columns in four not flown over yet, and no haze there.
        for (y in 0 until h) for (x in 90 until w) {
            rawLum[y * w + x] = 0
            field[y * w + x] = 0
        }
        assertEquals("a quarter of the sky has another floor than the whole of it",
            whole, NightLights.hazeFloor(field, rawLum, cleanLum, w, h))
        // The lit places are not the sky either.
        val (litRaw, litClean) = flat(w, h, 47)
        val lit = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            lit[y * w + x] = (x % 90).toByte()
            if (x >= 90) litClean[y * w + x] = 200.toByte()
        }
        assertEquals(whole, NightLights.hazeFloor(lit, litRaw, litClean, w, h))
        // And with nothing flown at all there is nothing to paint by.
        assertEquals(255, NightLights.hazeFloor(field, ByteArray(w * h), cleanLum, w, h))
    }

    /** And the raw night's own floor, the same way: its sea, not somebody else's. */
    @Test
    fun `a raw night shown alone has its own floor`() {
        val w = 360
        val h = 180
        val night = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            night[y * w + x] = if (y in 40 until 140) (10 + x % 90).toByte() else 250.toByte()
        }
        // The ninety-fifth percentile of ten to ninety-nine, each as often as the next.
        assertEquals(95 + NightLights.RESIDUE_MARGIN, NightLights.rawFloor(night, w, h))
        // Places not flown over are not dark sea.
        for (y in 40 until 140) for (x in 0 until w / 2) night[y * w + x] = 0
        assertEquals(95 + NightLights.RESIDUE_MARGIN, NightLights.rawFloor(night, w, h))
        // Nothing flown at all: the floor it used to be.
        assertEquals(NightLights.RAW_FLOOR, NightLights.rawFloor(ByteArray(w * h), w, h))
    }

    /** What a pixel is painted: the haze above its floor is cyan, with the residue or without it. */
    @Test
    fun `the haze is painted in the colour of the things only the raw night has`() {
        fun alpha(argb: Int) = argb ushr 24
        fun rgb(argb: Int) = argb and 0x00FFFFFF
        val both = NightLights.Mode.BOTH
        // Dark in both nights pixel by pixel, and nothing in the haze.
        assertEquals(0, alpha(NightLights.pixel(both, 7, 40, 0.0, residueFloor = 77, haze = 20, hazeFloor = 27)))
        // The same pixel, in a haze of sixty: cyan.
        val glowing = NightLights.pixel(both, 7, 40, 0.0, residueFloor = 77, haze = 60, hazeFloor = 27)
        assertTrue("a haze of sixty over a floor of twenty-seven is painted at ${alpha(glowing)}", alpha(glowing) in 60..110)
        assertEquals(NightLights.CYAN and 0x00FFFFFF, rgb(glowing))
        // A fire in a haze is the fire, in the same colour.
        val fire = NightLights.pixel(both, 7, 250, 0.0, residueFloor = 77, haze = 60, hazeFloor = 27)
        assertEquals(NightLights.CYAN and 0x00FFFFFF, rgb(fire))
        assertTrue(alpha(fire) >= alpha(NightLights.pixel(both, 7, 250, 0.0, residueFloor = 77)))
        // A town in a haze is still a town: the haze is under its lights.
        val bare = NightLights.pixel(both, 150, 150, 0.0, residueFloor = 77)
        val town = NightLights.pixel(both, 150, 150, 0.0, residueFloor = 77, haze = 60, hazeFloor = 27)
        fun red(argb: Int) = (argb shr 16) and 0xFF
        fun blue(argb: Int) = argb and 0xFF
        assertEquals(NightLights.AMBER and 0x00FFFFFF, rgb(bare))
        assertTrue("a town under the oval went from red ${red(bare)} to ${red(town)}", red(town) > red(bare) - 30)
        assertTrue("and its blue from ${blue(bare)} to ${blue(town)}", blue(town) < blue(bare) + 25)
        assertTrue(alpha(town) > alpha(bare))
        // With no haze worked out, nothing is added.
        assertEquals(0, alpha(NightLights.pixel(both, 7, 40, 0.0, residueFloor = 77)))
        // It is the raw night's and nobody else's.
        assertEquals(0, alpha(NightLights.pixel(NightLights.Mode.CLEAN, 7, 40, 0.0, 77, haze = 200, hazeFloor = 27)))
        assertEquals(0, alpha(NightLights.pixel(NightLights.Mode.NONE, 7, 40, 0.0, 77, haze = 200, hazeFloor = 27)))
        // Shown alone, the raw night is amber above its own floor.
        val alone = NightLights.Mode.RAW
        assertEquals(0, alpha(NightLights.pixel(alone, 0, 60, 0.0, 77)))
        val dim = NightLights.pixel(alone, 0, 60, 0.0, 77, rawFloor = 49)
        assertTrue(alpha(dim) > 20)
        assertEquals(NightLights.AMBER and 0x00FFFFFF, rgb(dim))
        assertEquals(0, alpha(NightLights.pixel(alone, 0, 49, 0.0, 77, rawFloor = 49)))
    }

    // ------------------------------------------------------- the real night

    /** How much of a box of the world, by latitude and longitude, [painted] says is painted: of its unlit, flown places. */
    private fun share(
        north: Double, south: Double, west: Double = -180.0, east: Double = 180.0,
        of: Triple<Int, Int, ByteArray> = raw,
        painted: (Int) -> Boolean
    ): Double {
        val (w, h, rawLum) = of
        val cleanLum = clean.third
        var n = 0
        var hit = 0
        for (y in ((90 - north) / 180 * h).toInt() until ((90 - south) / 180 * h).toInt()) {
            for (x in ((west + 180) / 360 * w).toInt() until ((east + 180) / 360 * w).toInt()) {
                val at = y * w + x
                if ((rawLum[at].toInt() and 0xFF) == 0) continue
                if ((cleanLum[at].toInt() and 0xFF) > NightLights.CLEAN_FLOOR) continue
                n++
                if (painted(at)) hit++
            }
        }
        return if (n == 0) 0.0 else 100.0 * hit / n
    }

    /**
     * The oval is painted, and the oceans are not.
     *
     * The same night by the rule as it was and as it is. What it was
     * painted a twentieth of the oval; what it is paints most of it, and
     * most of the open sea under it, and leaves the rest of the world's
     * dark as dark as it was.
     */
    @Test
    fun `the real oval is painted and the oceans under the tropics are not`() {
        val w = raw.first
        val h = raw.second
        val cleanLum = clean.third
        assertEquals(w, clean.first)
        fun floorOf(night: ByteArray) = NightLights.residueFloor(night, w, h) { u, v ->
            cleanLum[(v * h).toInt().coerceIn(0, h - 1) * w + (u * w).toInt().coerceIn(0, w - 1)].toInt() and 0xFF
        }
        // As it was: the picture as it was sent, by the residue alone.
        val sentLum = sent.third
        val floorWas = floorOf(sentLum)
        fun was(at: Int) =
            NightLights.residue(sentLum[at].toInt() and 0xFF, cleanLum[at].toInt() and 0xFF, floorWas) > 0
        // As it is: without its twilight, by the residue and the haze.
        val rawLum = raw.third
        val residueFloor = floorOf(rawLum)
        val moonless = NightLights.moonlessRows(h, RAW_DAY)
        val haze = NightLights.haze(rawLum, cleanLum, w, h, moonless)!!
        val hazeFloor = NightLights.hazeFloor(haze, rawLum, cleanLum, w, h, moonless)
        fun wash(at: Int) = NightLights.residue(haze[at].toInt() and 0xFF, 0, hazeFloor)
        fun isNow(at: Int) =
            NightLights.residue(rawLum[at].toInt() and 0xFF, cleanLum[at].toInt() and 0xFF, residueFloor) > 0 ||
                wash(at) > 0

        val ovalWas = share(72.0, 62.0, of = sent) { was(it) }
        val ovalNow = share(72.0, 62.0) { isNow(it) }
        val seaWas = share(70.0, 66.0, 0.0, 10.0, of = sent) { was(it) }
        val seaNow = share(70.0, 66.0, 0.0, 10.0) { isNow(it) }
        val tropics = share(50.0, -50.0) { isNow(it) }
        val tropicsWas = share(50.0, -50.0, of = sent) { was(it) }
        val tropicsStrong = share(50.0, -50.0) { wash(it) > 80 }
        val pacific = share(10.0, 0.0, -150.0, -140.0) { isNow(it) }
        // North of where the clean night stops, which is eighty degrees:
        // nothing there is the haze's.
        val cap = share(90.0, 82.0) { wash(it) > 0 }
        println(
            "MEASURE aurora, 8 Nov 2026: floors residue $floorWas -> $residueFloor, haze $hazeFloor | oval painted " +
                "%.1f%% -> %.1f%% | Norwegian Sea %.1f%% -> %.1f%% | within 50 deg %.2f%% -> %.2f%% (strong %.2f%%) | mid-Pacific %.2f%% | haze north of 82: %.2f%%"
                    .format(ovalWas, ovalNow, seaWas, seaNow, tropicsWas, tropics, tropicsStrong, pacific, cap)
        )
        assertEquals("the haze painted the pole, where the clean night has nothing to compare with", 0.0, cap, 0.0)
        assertTrue("the old rule painted $ovalWas% of the oval: this night is not the one that was measured", ovalWas < 15.0)
        assertTrue("the oval is $ovalNow% painted", ovalNow > 40.0)
        assertTrue("the open sea under the oval is $seaNow% painted", seaNow > 80.0)
        assertTrue("$tropics% of the dark within fifty degrees of the equator is painted", tropics < 1.5)
        assertTrue("$tropicsStrong% of it strongly", tropicsStrong < 0.1)
        assertTrue("the middle of the Pacific is $pacific% painted", pacific < 1.0)
        // The floors themselves: the old one above the oval's middle,
        // the haze's well under it.
        assertTrue("the residue floor was $floorWas", floorWas in 60..95)
        assertTrue("the haze's floor is $hazeFloor", hazeFloor in 15..40)
        // And the raw night alone: where a floor of 96 stood.
        val rawFloor = NightLights.rawFloor(rawLum, w, h)
        assertTrue("the raw night's own floor is $rawFloor", rawFloor in 40..65)
        val aloneWas = share(72.0, 62.0, of = sent) { NightLights.glow(sentLum[it].toInt() and 0xFF, NightLights.RAW_FLOOR) > 0 }
        val aloneNow = share(72.0, 62.0) { NightLights.glow(rawLum[it].toInt() and 0xFF, rawFloor) > 0 }
        println("MEASURE the raw night alone: its floor $rawFloor against 96 | oval painted %.1f%% -> %.1f%%".format(aloneWas, aloneNow))
        assertTrue("alone, the raw night shows $aloneNow% of the oval where it showed $aloneWas%", aloneNow > aloneWas * 3 && aloneNow > 25.0)
    }

    // --------------------------------------------------------- the twilight

    /** The hour the satellite keeps over each latitude: out of the morning in the north, into the evening before in the south. */
    @Test
    fun `the satellite comes down out of the morning`() {
        assertEquals(1.5, NightLights.overpassHour(0.0), 1e-9)
        // A little after three over seventy north, and ten to midnight over seventy south.
        assertEquals(3.16, NightLights.overpassHour(70.0), 0.02)
        assertEquals(-0.16, NightLights.overpassHour(-70.0), 0.02)
        assertEquals(5.51, NightLights.overpassHour(80.0), 0.03)
        // The top of the orbit, six hours round from the equator, and
        // the same hour for everything beyond it.
        assertEquals(7.5, NightLights.overpassHour(81.31), 0.05)
        assertEquals(7.5, NightLights.overpassHour(89.0), 1e-9)
        assertEquals(-4.5, NightLights.overpassHour(-89.0), 1e-9)
        // Later all the way up, earlier all the way down.
        var last = NightLights.overpassHour(-90.0)
        for (latitude in -89..90) {
            val hour = NightLights.overpassHour(latitude.toDouble())
            assertTrue("the hour ran backwards at $latitude", hour >= last)
            last = hour
        }
        assertEquals(98.7, NightLights.ORBIT_INCLINATION_DEG, 0.0)
        assertEquals(1.5, NightLights.OVERPASS_HOUR, 0.0)
    }

    /** How high the sun stood when the satellite came over, on the picture's day. */
    @Test
    fun `the sun at the overpass is the sun at the satellite's hour`() {
        fun sun(lat: Double, day: String) = NightLights.sunAtOverpass(lat, day)!!
        // On the equator at an equinox the sun goes straight round under
        // the feet: an hour and a half past the bottom is 22.5 degrees
        // short of straight down.
        assertEquals(-67.5, sun(0.0, "2026-03-20"), 0.3)
        // On a pole the sun is as high as its declination, whatever the hour.
        assertEquals(23.4, sun(90.0, "2026-06-21"), 0.2)
        assertEquals(-23.4, sun(-90.0, "2026-06-21"), 0.2)
        assertEquals(-16.6, sun(90.0, RAW_DAY), 0.3)
        // Sixty north at midsummer, at half past two: the white nights.
        assertEquals(-1.0, sun(60.0, "2026-06-21"), 0.7)
        // The same latitude at the other end of the world and of the
        // year is darker, because the satellite is there nearer midnight.
        assertEquals(-6.4, sun(-60.0, "2026-12-21"), 0.7)
        // On the night that was looked at the line falls at fifty-five
        // south, and round the north pole at eighty.
        assertTrue(sun(-54.0, RAW_DAY) < NightLights.TWILIGHT_LIMIT_DEG)
        assertTrue(sun(-56.0, RAW_DAY) > NightLights.TWILIGHT_LIMIT_DEG)
        assertTrue(sun(79.0, RAW_DAY) < NightLights.TWILIGHT_LIMIT_DEG)
        assertTrue(sun(80.5, RAW_DAY) > NightLights.TWILIGHT_LIMIT_DEG)
        assertNull(NightLights.sunAtOverpass(0.0, "yesterday"))
        assertEquals(-18.0, NightLights.TWILIGHT_LIMIT_DEG, 0.0)
    }

    /** The rows that were not night are blanked, and only those. */
    @Test
    fun `the twilight is taken out of a raw night by the row`() {
        val w = 8
        val h = 180
        fun night() = ByteArray(w * h) { 40 }
        fun blank(lum: ByteArray, y: Int) = (0 until w).all { lum[y * w + it].toInt() == 0 }
        fun whole(lum: ByteArray, y: Int) = (0 until w).all { lum[y * w + it].toInt() == 40 }
        // Row y is the degree of latitude from 90 - y down to 89 - y.
        fun north(latitude: Int) = 89 - latitude
        fun south(latitude: Int) = 90 + latitude

        // November: the south from fifty-five degrees down, and the cap
        // of the north from eighty up.
        val november = night()
        val gone = NightLights.withoutTwilight(november, w, h, RAW_DAY)
        for (y in 0 until h) {
            val twilit = y <= north(80) || y >= south(55)
            if (twilit) assertTrue("row $y was kept", blank(november, y))
            else assertTrue("row $y was blanked", whole(november, y))
        }
        assertEquals("rows blanked in November", 10 + 35, gone)

        // June: the north from forty-three up, and the south whole.
        val june = night()
        NightLights.withoutTwilight(june, w, h, "2026-06-14")
        assertTrue(whole(june, north(41)))
        assertTrue(blank(june, north(44)))
        assertTrue(blank(june, 0))
        assertTrue(whole(june, h - 1))

        // An equinox: the north from sixty-six and the south from
        // seventy-one — not the same, because the hour is not.
        val march = night()
        NightLights.withoutTwilight(march, w, h, "2026-03-19")
        assertTrue(whole(march, north(65)) && blank(march, north(67)) && blank(march, 0))
        assertTrue(whole(march, south(70)) && blank(march, south(72)) && blank(march, h - 1))

        // Midwinter: the whole north is night, pole and all.
        val december = night()
        NightLights.withoutTwilight(december, w, h, "2026-12-21")
        assertTrue(whole(december, 0))

        // A picture that is not the shape it says, or a day that is not one: left alone.
        val odd = ByteArray(10) { 40 }
        assertEquals(0, NightLights.withoutTwilight(odd, w, h, RAW_DAY))
        assertTrue(odd.all { it.toInt() == 40 })
        val undated = night()
        assertEquals(0, NightLights.withoutTwilight(undated, w, h, "soon"))
        assertTrue(undated.all { it.toInt() == 40 })
    }

    /** The darkest tenth of the rows of a real night the sun stood between [from] and [to] over, and how much of them the service sent. */
    private fun darkestTenth(
        night: Triple<Int, Int, ByteArray>, day: String, northern: Boolean, from: Double, to: Double
    ): Pair<Int, Double> {
        val (w, h, lum) = night
        val seen = ArrayList<Int>()
        var asked = 0
        for (y in (if (northern) 0 until h / 2 else h / 2 until h)) {
            val sun = NightLights.sunAtOverpass(90.0 - (y + 0.5) * 180.0 / h, day)!!
            if (sun < from || sun >= to) continue
            for (x in 0 until w) {
                asked++
                val grey = lum[y * w + x].toInt() and 0xFF
                if (grey != 0) seen += grey
            }
        }
        seen.sort()
        return (if (seen.isEmpty()) -1 else seen[seen.size / 10]) to (if (asked == 0) 0.0 else 100.0 * seen.size / asked)
    }

    /**
     * The measurement the limit stands on, kept: in the south of the
     * real night, where every part of a strip sees much the same sun,
     * the darkest tenth of a row is at the scale's floor wherever the
     * sun was eighteen degrees down or more when the satellite came,
     * has left it by thirteen, and by nine the service has no picture.
     */
    @Test
    fun `the real night is dark where the sun was eighteen degrees down and is not at twelve`() {
        val (deep, _) = darkestTenth(sent, RAW_DAY, northern = false, from = -90.0, to = -18.0)
        val (dusk, _) = darkestTenth(sent, RAW_DAY, northern = false, from = -18.0, to = -14.5)
        val (fringe, sentThere) = darkestTenth(sent, RAW_DAY, northern = false, from = -13.0, to = -11.0)
        val (_, beyond) = darkestTenth(sent, RAW_DAY, northern = false, from = -9.0, to = 90.0)
        println(
            "MEASURE the raw night's darkest tenth by the sun at the overpass, south: below -18 deg $deep | -18..-14.5 $dusk | -13..-11 $fringe (%.0f%% sent) | above -9: %.1f%% sent"
                .format(sentThere, beyond)
        )
        assertEquals("the scale's floor", 7, deep)
        assertTrue("between eighteen and fourteen and a half down it reads $dusk", dusk <= 13)
        assertTrue("between thirteen and eleven down it reads $fringe", fringe >= 24)
        assertEquals("the service sent a picture where the sun was less than nine degrees down", 0.0, beyond, 0.0)
    }

    /**
     * The ring round Antarctica: painted nine parts in ten as it was,
     * and not at all with the twilight out; and the southern lights
     * north of it, which are an aurora, still there.
     */
    @Test
    fun `the twilight round the summer pole is not an aurora`() {
        val w = sent.first
        val h = sent.second
        val cleanLum = clean.third
        fun rule(night: ByteArray): (Int) -> Boolean {
            val floor = NightLights.residueFloor(night, w, h) { u, v ->
                cleanLum[(v * h).toInt().coerceIn(0, h - 1) * w + (u * w).toInt().coerceIn(0, w - 1)].toInt() and 0xFF
            }
            val haze = NightLights.haze(night, cleanLum, w, h)!!
            val hazeFloor = NightLights.hazeFloor(haze, night, cleanLum, w, h)
            return { at ->
                NightLights.residue(night[at].toInt() and 0xFF, cleanLum[at].toInt() and 0xFF, floor) > 0 ||
                    NightLights.residue(haze[at].toInt() and 0xFF, 0, hazeFloor) > 0
            }
        }
        val withTwilight = rule(sent.third)
        val without = rule(raw.third)
        // The fringe: fifty-nine to sixty-two and a half south, right round.
        val fringeWas = share(-59.0, -62.5, of = sent) { withTwilight(it) }
        val fringeNow = share(-59.0, -62.5, of = sent) { without(it) }
        // The southern lights that night: south of Tasmania and New
        // Zealand, north of the line.
        val australisWas = share(-50.0, -54.5, 120.0, 180.0, of = sent) { withTwilight(it) }
        val australisNow = share(-50.0, -54.5, 120.0, 180.0, of = sent) { without(it) }
        // And the same stretch of latitude round the other side of the world, where there were none.
        val elsewhere = share(-50.0, -54.5, -120.0, -20.0, of = sent) { without(it) }
        println(
            "MEASURE the south, 8 Nov 2026: twilight fringe painted %.1f%% -> %.1f%% | southern lights off Tasmania %.1f%% -> %.1f%% | same latitudes in the Atlantic and Pacific %.1f%%"
                .format(fringeWas, fringeNow, australisWas, australisNow, elsewhere)
        )
        assertTrue("the fringe was $fringeWas% painted: this is not the night that was measured", fringeWas > 80.0)
        assertEquals("the twilight is still painted", 0.0, fringeNow, 0.0)
        assertTrue("the southern lights are $australisNow% painted", australisNow > 30.0)
        assertTrue("and were $australisWas%", australisNow >= australisWas - 5.0)
        assertTrue("the empty sea at the same latitude is $elsewhere% painted", elsewhere < 5.0)
        // Nothing of the raw night is left south of the line, for any rule to find.
        val (_, _, night) = raw
        for (y in ((90 + 56.0) / 180 * h).toInt() until h) {
            for (x in 0 until w) assertEquals(0, night[y * w + x].toInt())
        }
    }

    /**
     * The other shape the twilight takes, in the north, in spring and
     * autumn: fins. Each pass is later by the sun at the edge of its
     * strip nearer the dawn, so each leaves a bright wedge, and a row
     * of them ran across the Arctic at the latitude of the oval.
     *
     * Held against the raw night of the 7th of October 2026, as it was
     * sent. The fins came down to seventy-one north. By a satellite
     * that kept half past one at every latitude they should have
     * stopped at seventy-seven, which is how the first rule was found
     * to be wrong: the hour is the orbit's — see
     * [NightLights.overpassHour].
     */
    @Test
    fun `the fins of an October dawn are not lamps`() {
        val day = "2026-10-07"
        val fins = greys("gibs-lights-raw-fins.png")
        val (w, h, sentLum) = fins
        val night = sentLum.copyOf()
        val rows = NightLights.withoutTwilight(night, w, h, day)
        /** How much of the sky between two latitudes reads over [floor], lamp or not. */
        fun lit(lum: ByteArray, north: Double, south: Double, floor: Int): Double {
            var n = 0
            var over = 0
            for (y in ((90 - north) / 180 * h).toInt() until ((90 - south) / 180 * h).toInt()) for (x in 0 until w) {
                n++
                if ((lum[y * w + x].toInt() and 0xFF) > floor) over++
            }
            return 100.0 * over / n
        }
        // The raw night alone, by the floor it now reads off itself.
        val floor = NightLights.rawFloor(night, w, h)
        val finsWas = lit(sentLum, 77.0, 72.0, floor)
        val finsNow = lit(night, 77.0, 72.0, floor)
        val belowWas = lit(sentLum, 70.0, 60.0, floor)
        val belowNow = lit(night, 70.0, 60.0, floor)
        // A fin is a part of a row, and the darkest tenth of a row with
        // fins in it is as dark as any: that measure finds the ring in
        // the south and is blind to these.
        val (deep, _) = darkestTenth(fins, day, northern = true, from = -90.0, to = -18.0)
        println(
            "MEASURE the north, 7 Oct 2026: $rows rows of twilight out, floor $floor | lit over the floor at 72..77 N %.1f%% -> %.1f%% | at 60..70 N %.1f%% -> %.1f%% | darkest tenth below -18 deg $deep"
                .format(finsWas, finsNow, belowWas, belowNow)
        )
        assertTrue("the fins were $finsWas% of the sky at 72..77 north: not the night that was looked at", finsWas > 12.0)
        assertEquals("the fins are still in the raw night", 0.0, finsNow, 0.0)
        // South of them the night is as it was sent: the oval is there.
        assertEquals("the night south of the fins was changed", belowWas, belowNow, 0.0)
        assertTrue(belowNow > 1.0)
        assertEquals(7, deep)
        // The line is at seventy-one and a quarter north that night.
        assertTrue(NightLights.sunAtOverpass(70.5, day)!! < NightLights.TWILIGHT_LIMIT_DEG)
        assertTrue(NightLights.sunAtOverpass(72.0, day)!! > NightLights.TWILIGHT_LIMIT_DEG)
    }

    // ------------------------------------------------------------- the moon

    /** Where the moon stood when the satellite came over, by the clock's own moon. */
    @Test
    fun `the moon at the overpass is the clock's moon`() {
        fun moon(lat: Double, day: String) = NightLights.moonAtOverpass(lat, day)!!
        // The night before a new moon: it is with the sun, under everybody's feet.
        for (latitude in listOf(75.0, 60.0, 30.0, 0.0, -30.0, -50.0)) {
            assertTrue("the moon was ${moon(latitude, RAW_DAY)} up at $latitude", moon(latitude, RAW_DAY) < -15.0)
        }
        // A full moon: high over the tropics in the small hours, and up
        // over the Arctic too — it is as far north of the equator as
        // the sun is south.
        assertTrue(moon(0.0, "2026-10-26") > 50.0)
        assertTrue(moon(70.0, "2026-10-26") > 20.0)
        // The quarter moon of the 2nd of November: up over Greenland,
        // down in the far south.
        assertTrue(moon(72.0, "2026-11-02") > 10.0)
        assertTrue(moon(-60.0, "2026-11-02") < NightLights.MOON_LIMIT_DEG)
        // At the satellite's hour and not at half past one: it is over
        // seventy-five north at ten to four, by when a waning crescent
        // has climbed.
        val noon = DailyImageStore.msOf("2026-11-05")!! + DailyImageStore.DAY_MS / 2
        val atHalfPastOne = SolarTime.moonPosition(75.0, (1.5 - 12.0) * 15.0, noon).altitudeDeg
        println("MEASURE the crescent of 5 Nov 2026 over 75 N: %.1f deg at half past one, %.1f at the satellite's hour".format(atHalfPastOne, moon(75.0, "2026-11-05")))
        assertTrue(moon(75.0, "2026-11-05") > atHalfPastOne + 3.0)
        assertNull(NightLights.moonAtOverpass(0.0, "never"))
        assertEquals(-6.0, NightLights.MOON_LIMIT_DEG, 0.0)
    }

    /** Which rows the moon was down for, a day at a time and for two together. */
    @Test
    fun `a row is moonless when the moon was well under it`() {
        val h = 180
        fun row(latitude: Double) = (90.0 - latitude).toInt().coerceIn(0, h - 1)
        val dark = NightLights.moonlessRows(h, RAW_DAY)
        assertEquals(h, dark.size)
        for (latitude in listOf(78.0, 65.0, 40.0, 0.0, -40.0, -54.0)) {
            assertTrue("$latitude was not moonless the night before the new moon", dark[row(latitude)])
        }
        val quarter = NightLights.moonlessRows(h, "2026-11-02")
        assertFalse(quarter[row(72.0)])
        assertFalse(quarter[row(40.0)])
        assertFalse(quarter[row(0.0)])
        assertTrue(quarter[row(-60.0)])
        // Row by row it is the rule and nothing else.
        for (y in 0 until h) {
            val moon = NightLights.moonAtOverpass(90.0 - (y + 0.5), "2026-11-02")!!
            assertEquals("row $y, the moon at $moon", moon < NightLights.MOON_LIMIT_DEG, quarter[y])
        }
        val full = NightLights.moonlessRows(h, "2026-10-26")
        assertTrue("the full moon left ${full.count { it }} rows of $h dark", full.count { it } < h / 8)
        // Two nights in one picture: moonless on both or not at all.
        val both = NightLights.moonlessRows(h, RAW_DAY, "2026-11-02")
        for (y in 0 until h) assertEquals(dark[y] && quarter[y], both[y])
        assertTrue(both[row(-60.0)])
        assertFalse(both[row(40.0)])
        // A day that is not one has no moonless rows to offer.
        assertTrue(NightLights.moonlessRows(h, "soon").none { it })
        assertTrue(NightLights.moonlessRows(h, RAW_DAY, "soon").none { it })
        assertEquals(0, NightLights.moonlessRows(0, RAW_DAY).size)
    }

    /** The haze is read only off the rows it is given, and its floor too. */
    @Test
    fun `the haze is of the rows the moon was down for`() {
        val w = 360
        val h = 180
        val (rawLum, cleanLum) = flat(w, h, 47)
        // The top half moonlit: twice as bright, and no sky to read.
        for (y in 0 until h / 2) for (x in 0 until w) rawLum[y * w + x] = 107
        val rows = BooleanArray(h) { it >= h / 2 }
        val haze = NightLights.haze(rawLum, cleanLum, w, h, rows)!!
        for (y in 0 until h / 2) {
            assertEquals("row $y, under the moon, has a haze", 0, haze[y * w + 10].toInt() and 0xFF)
        }
        // The first moonless row is not lit by the moonlit one above it.
        assertEquals(40, haze[(h / 2) * w + 10].toInt() and 0xFF)
        assertEquals(40, haze[(h - 1) * w + 10].toInt() and 0xFF)
        // Without the rows the moonlight is a haze of a hundred, and
        // the row under it is half made of it.
        val blind = NightLights.haze(rawLum, cleanLum, w, h)!!
        assertEquals(100, blind[10 * w + 10].toInt() and 0xFF)
        assertEquals((100 + 40 + 40) / 3, blind[(h / 2) * w + 10].toInt() and 0xFF)
        // The floor is read off the moonless rows: forty and the margin,
        // not the hundred of the moonlit half of the band.
        assertEquals(40 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(haze, rawLum, cleanLum, w, h, rows))
        assertEquals(100 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(blind, rawLum, cleanLum, w, h))
        // And whatever is in the moonlit rows of a field, the floor does
        // not look at them: of the blind one it reads the row the
        // moonlight was smeared into, which is sixty, and not the
        // hundred above it.
        assertEquals(60 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(blind, rawLum, cleanLum, w, h, rows))
        // Rows that are not the picture's are nobody's.
        assertNull(NightLights.haze(rawLum, cleanLum, w, h, BooleanArray(h - 1)))
        assertEquals(255, NightLights.hazeFloor(haze, rawLum, cleanLum, w, h, BooleanArray(h - 1)))
        // With the moon up over all of the quiet band there is no floor,
        // and with all but a sliver of it: a fiftieth of the picture is
        // the least sky a floor is read off.
        assertEquals(255, NightLights.hazeFloor(haze, rawLum, cleanLum, w, h, BooleanArray(h)))
        val sliver = BooleanArray(h) { it in 90..92 }
        assertEquals(255, NightLights.hazeFloor(haze, rawLum, cleanLum, w, h, sliver))
        val enough = BooleanArray(h) { it in 90..93 }
        assertEquals(40 + NightLights.HAZE_MARGIN, NightLights.hazeFloor(haze, rawLum, cleanLum, w, h, enough))
        assertEquals(50, NightLights.HAZE_SAMPLE_LEAST)
    }

    /**
     * Moonlit snow is not an aurora.
     *
     * The raw night of the 2nd of November 2026, a quarter moon
     * standing twenty degrees up over the Arctic when the satellite
     * came: read without asking where the moon was, the haze paints the
     * south of the Greenland ice cap from coast to coast. It is a
     * surplus over the clean night, and spread out, and it is the
     * moon's.
     */
    @Test
    fun `under the moon Greenland is not an aurora`() {
        val day = "2026-11-02"
        val (w, h, lum) = greys("gibs-lights-raw.png")
        val cleanLum = greys("gibs-lights-clean.png").third
        NightLights.withoutTwilight(lum, w, h, day)
        val moonlit = Triple(w, h, lum)
        val blind = NightLights.haze(lum, cleanLum, w, h)!!
        val blindFloor = NightLights.hazeFloor(blind, lum, cleanLum, w, h)
        val rows = NightLights.moonlessRows(h, day)
        val haze = NightLights.haze(lum, cleanLum, w, h, rows)!!
        val floor = NightLights.hazeFloor(haze, lum, cleanLum, w, h, rows)
        // The south of the ice cap: sixty-four to seventy north, forty-six to thirty west.
        val iceWas = share(70.0, 64.0, -46.0, -30.0, of = moonlit) {
            NightLights.residue(blind[it].toInt() and 0xFF, 0, blindFloor) > 0
        }
        val iceNow = share(70.0, 64.0, -46.0, -30.0, of = moonlit) {
            NightLights.residue(haze[it].toInt() and 0xFF, 0, floor) > 0
        }
        val northNow = share(85.0, 0.0, of = moonlit) { NightLights.residue(haze[it].toInt() and 0xFF, 0, floor) > 0 }

        println(
            "MEASURE the quarter moon of 2 Nov 2026: haze floor without the moon rule $blindFloor, with it $floor, ${rows.count { it }} of $h rows moonless | Greenland's ice painted %.1f%% -> %.1f%% | the north %.2f%%"
                .format(iceWas, iceNow, northNow)
        )
        assertTrue("the ice was $iceWas% painted: this is not the night that was looked at", iceWas > 80.0)
        assertEquals("the moonlit ice is still painted", 0.0, iceNow, 0.0)
        assertEquals("something north of the equator is painted by the haze under the moon", 0.0, northNow, 0.0)
    }

    // ------------------------------------------------------------ the globe

    private fun layer(which: Triple<Int, Int, ByteArray>, day: String, lag: Int) =
        NightLightStore.Layer(which.first, which.second, which.third, day, lag)

    /** That night on the globe, with the haze or as it used to be painted. */
    private fun sky(mode: NightLights.Mode, withHaze: Boolean, asItWas: Boolean = !withHaze): NightLightStore.Sky {
        // As it was, the twilight was in the raw night too.
        val night = if (asItWas) sent else raw
        val (w, h, rawLum) = night
        val cleanLum = clean.third
        val cleanLayer = if (mode.clean) layer(clean, CLEAN_DAY, 1) else null
        val rawLayer = if (mode.raw) layer(night, RAW_DAY, 0) else null
        val residueFloor = NightLights.residueFloor(rawLum, w, h) { u, v ->
            cleanLum[(v * h).toInt().coerceIn(0, h - 1) * w + (u * w).toInt().coerceIn(0, w - 1)].toInt() and 0xFF
        }
        if (asItWas) return NightLightStore.Sky(mode, cleanLayer, rawLayer, residueFloor)
        val moonless = NightLights.moonlessRows(h, RAW_DAY)
        val haze =
            if (withHaze && mode == NightLights.Mode.BOTH) NightLights.haze(rawLum, cleanLum, w, h, moonless) else null
        return NightLightStore.Sky(
            mode, cleanLayer, rawLayer, residueFloor,
            rawFloor = NightLights.rawFloor(rawLum, w, h),
            haze = haze,
            hazeFloor = haze?.let { NightLights.hazeFloor(it, rawLum, cleanLum, w, h, moonless) } ?: 255
        )
    }

    /** Half past one in the morning in Greenwich on the 8th of November: Europe's side of the pole is dark. */
    private fun night(): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.NOVEMBER, 8, 1, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun globe(
        lights: NightLightStore.Sky?,
        which: Hemisphere.View = Hemisphere.View.NORTH,
        at: Long = night()
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = which
        atMs = at
        hourRing = false
        showSun = false
        showMoon = false
        moonlight = false
        this.lights = lights
        measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1000, 1000)
    }

    private fun shoot(v: View, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    /** How many pixels of [on] have had cyan added to what they are in [off]. */
    private fun cyan(on: Bitmap, off: Bitmap): Int {
        var n = 0
        for (y in 0 until on.height step 2) {
            for (x in 0 until on.width step 2) {
                val a = on.getPixel(x, y)
                val b = off.getPixel(x, y)
                val g = (a shr 8) and 0xFF
                if (g - ((b shr 8) and 0xFF) > 25 && g > ((a shr 16) and 0xFF) + 20) n++
            }
        }
        return n
    }

    /** How many have had amber added. */
    private fun amber(on: Bitmap, off: Bitmap): Int {
        var n = 0
        for (y in 0 until on.height step 2) {
            for (x in 0 until on.width step 2) {
                val a = on.getPixel(x, y)
                val b = off.getPixel(x, y)
                val r = (a shr 16) and 0xFF
                if (r - ((b shr 16) and 0xFF) > 25 && r > (a and 0xFF) + 20) n++
            }
        }
        return n
    }

    /**
     * And on the glass: the oval is a ring round the pole on the dark
     * side, where there were a few specks.
     */
    @Test
    fun `the oval is on the globe`() {
        val dark = shoot(globe(null), "world-aurora-no-lights")
        val before = shoot(globe(sky(NightLights.Mode.BOTH, withHaze = false)), "world-aurora-before")
        val after = shoot(globe(sky(NightLights.Mode.BOTH, withHaze = true)), "world-aurora-after")
        val was = cyan(before, dark)
        val now = cyan(after, dark)
        println("MEASURE cyan on the globe: $was pixels as it was, $now with the haze")
        assertTrue("the haze added no cyan to the globe: $was then $now", now > was * 4 && now > 3000)
        // The towns are where they were: the haze is under their lights,
        // and no light that was amber has lost any of its red to it.
        // Against the same night without the haze, so that the haze is
        // the only thing that could have done it.
        val bare = shoot(
            globe(sky(NightLights.Mode.BOTH, withHaze = false, asItWas = false)), "world-aurora-no-haze"
        )
        var towns = 0
        var dimmed = 0
        var inTheHaze = 0
        for (y in 0 until bare.height step 2) {
            for (x in 0 until bare.width step 2) {
                val was0 = bare.getPixel(x, y)
                val base = dark.getPixel(x, y)
                val r = (was0 shr 16) and 0xFF
                if (r - ((base shr 16) and 0xFF) < 60 || r < (was0 and 0xFF) + 40) continue
                towns++
                val now0 = after.getPixel(x, y)
                if (now0 != was0) inTheHaze++
                if (((now0 shr 16) and 0xFF) < r - 12) dimmed++
            }
        }
        println("MEASURE towns on the globe: $towns amber pixels, $inTheHaze of them in the haze, $dimmed dimmer for it")
        assertTrue("there are $towns amber pixels to judge by", towns > 800)
        assertTrue("there are $inTheHaze towns under the oval to judge by", inTheHaze > 20)
        assertEquals("towns lost their light to the haze", 0, dimmed)
        // And nothing on the pole, where the clean night has no reading:
        // the world is 767 across of a thousand at rest, so eighty-two
        // north is fifty-three pixels from the middle.
        var onThePole = 0
        var onTheOval = 0
        for (y in 0 until after.height) for (x in 0 until after.width) {
            if (after.getPixel(x, y) == bare.getPixel(x, y)) continue
            val dx = x - 500
            val dy = y - 500
            if (dx * dx + dy * dy <= 50 * 50) onThePole++ else onTheOval++
        }
        assertEquals("the haze is painted on the pole", 0, onThePole)
        assertTrue("the haze changed $onTheOval pixels of the globe", onTheOval > 20000)

        // The raw night alone, by the floor it had and by its own.
        val aloneBefore = shoot(globe(sky(NightLights.Mode.RAW, withHaze = false)), "world-aurora-raw-before")
        val aloneAfter = shoot(globe(sky(NightLights.Mode.RAW, withHaze = true)), "world-aurora-raw-after")
        val a = amber(aloneBefore, dark)
        val b = amber(aloneAfter, dark)
        println("MEASURE amber on the globe, raw night alone: $a pixels at a floor of 96, $b at its own")
        assertTrue("the raw night alone shows no more at its own floor: $a then $b", b > a * 2)
    }

    /** How many pixels between two distances of the middle have had cyan added. */
    private fun cyanBetween(on: Bitmap, off: Bitmap, near: Double, far: Double): Int {
        var n = 0
        for (y in 0 until on.height) {
            for (x in 0 until on.width) {
                val r = Math.hypot(x - on.width / 2.0, y - on.height / 2.0)
                if (r < near || r > far) continue
                val a = on.getPixel(x, y)
                val g = (a shr 8) and 0xFF
                if (g - ((off.getPixel(x, y) shr 8) and 0xFF) > 25 && g > ((a shr 16) and 0xFF) + 20) n++
            }
        }
        return n
    }

    /**
     * And on the glass, from over the other pole: the ring of twilight
     * that sat at sixty south is gone, and the southern lights off
     * Tasmania are there.
     */
    @Test
    fun `there is no ring round Antarctica`() {
        // Two in the afternoon in Greenwich: midnight south of Tasmania.
        val at = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.NOVEMBER, 8, 14, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val south = Hemisphere.View.SOUTH
        val dark = shoot(globe(null, south, at), "world-south-no-lights")
        val before = shoot(globe(sky(NightLights.Mode.BOTH, withHaze = false), south, at), "world-south-before")
        val after = shoot(globe(sky(NightLights.Mode.BOTH, withHaze = true), south, at), "world-south-after")
        // The world is 767 across of a thousand, and a parallel is a
        // circle the cosine of its latitude out from the middle.
        fun out(latitude: Double) = 383.5 * Math.cos(Math.toRadians(latitude))
        val ringWas = cyanBetween(before, dark, out(62.5), out(58.5))
        val ringNow = cyanBetween(after, dark, out(62.5), out(55.5))
        val lightsWas = cyanBetween(before, dark, out(54.5), out(48.0))
        val lightsNow = cyanBetween(after, dark, out(54.5), out(48.0))
        println("MEASURE the south on the globe: cyan in the twilight ring $ringWas -> $ringNow | southern lights north of it $lightsWas -> $lightsNow")
        assertTrue("the ring was $ringWas pixels: this is not the picture that was looked at", ringWas > 600)
        assertEquals("the twilight ring is still on the globe", 0, ringNow)
        assertTrue("the southern lights are $lightsNow pixels where they were $lightsWas", lightsNow > lightsWas * 3 / 2 && lightsNow > 400)

        // And the raw night alone, which drew the same ring in amber.
        val aloneBefore = shoot(globe(sky(NightLights.Mode.RAW, withHaze = false), south, at), "world-south-raw-before")
        val aloneAfter = shoot(globe(sky(NightLights.Mode.RAW, withHaze = true), south, at), "world-south-raw-after")
        fun amberRing(shot: Bitmap): Int {
            var n = 0
            for (y in 0 until shot.height) for (x in 0 until shot.width) {
                val r = Math.hypot(x - 500.0, y - 500.0)
                if (r < out(62.5) || r > out(55.5)) continue
                val a = shot.getPixel(x, y)
                val red = (a shr 16) and 0xFF
                if (red - ((dark.getPixel(x, y) shr 16) and 0xFF) > 25 && red > (a and 0xFF) + 20) n++
            }
            return n
        }
        val amberWas = amberRing(aloneBefore)
        val amberNow = amberRing(aloneAfter)
        println("MEASURE the south on the globe, raw night alone: amber in the twilight ring $amberWas -> $amberNow")
        assertTrue("the amber ring was $amberWas pixels", amberWas > 600)
        assertEquals("the twilight ring is still on the globe in amber", 0, amberNow)
    }

    // ------------------------------------------------------------ the store

    /**
     * The store works the haze out once for each pair of nights and hands
     * it to the globe; with one layer there is nothing to be the surplus
     * of.
     */
    @Test
    fun `the store hands the globe the haze of the two nights it has`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        fun mode(key: String) = prefs.edit().putBoolean(Prefs.WEATHER, true)
            .putString(Prefs.HEMISPHERE_LIGHTS, key).commit()
        val rawBytes = bytes("gibs-lights-raw-aurora.png")
        val cleanBytes = bytes("gibs-lights-clean-aurora.png")
        NightLightStore.fetch = DailyImageStore.Fetch { url ->
            if (url.substringAfter("LAYERS=").substringBefore("&").contains("GapFilled")) cleanBytes else rawBytes
        }
        mode("both")
        NightLightStore.forget(context)
        val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.NOVEMBER, 8, 20, 0, 0)
        }.timeInMillis
        assertTrue(NightLightStore.refresh(context, now))
        val sky = NightLightStore.skyFor(context, now)!!
        assertNotNull("both nights are there and no haze was worked out", sky.haze)
        assertEquals(sky.raw!!.lum.size, sky.haze!!.size)
        assertTrue("the haze's floor is ${sky.hazeFloor}", sky.hazeFloor in 15..40)
        assertTrue("the raw night's floor is ${sky.rawFloor}", sky.rawFloor in 40..65)
        // The Norwegian Sea, under the oval: in the haze. The equator: not.
        assertTrue(sky.hazeAt((5.0 + 180) / 360, (90 - 68.0) / 180) > sky.hazeFloor)
        assertTrue(sky.hazeAt((-145.0 + 180) / 360, (90 - 5.0) / 180) < sky.hazeFloor)
        // Once: asked again, it is the same field and not another megabyte.
        assertSame(sky.haze, NightLightStore.skyFor(context, now)!!.haze)

        // The store asks the moon about the night the picture is of.
        // The same two pictures filed under the 2nd of November, when a
        // quarter moon stood over the north: no haze under it.
        val sea = Pair((5.0 + 180) / 360, (90 - 68.0) / 180)
        NightLightStore.forget(context)
        val moonlit = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.NOVEMBER, 2, 20, 0, 0)
        }.timeInMillis
        assertTrue(NightLightStore.refresh(context, moonlit))
        val under = NightLightStore.skyFor(context, moonlit)!!
        assertEquals("2026-11-02", under.raw!!.day)
        assertTrue("the raw night has a reading under the oval", under.raw!!.at(sea.first, sea.second) > 0)
        assertEquals("the haze was read off a moonlit sky", 0, under.hazeAt(sea.first, sea.second))
        // And its floor is the floor of the rows the moon was down for,
        // which that night were a strip of the far south: read off the
        // whole band, the blank rows under the moon would count as the
        // darkest sky there is.
        val moonless = NightLights.moonlessRows(under.raw!!.height, "2026-11-02", under.raw!!.filledFrom)
        val byTheRows = NightLights.hazeFloor(
            under.haze!!, under.raw!!.lum, under.clean!!.lum, under.raw!!.width, under.raw!!.height, moonless
        )
        val blindly = NightLights.hazeFloor(
            under.haze!!, under.raw!!.lum, under.clean!!.lum, under.raw!!.width, under.raw!!.height
        )
        assertTrue("the rows make no difference to this night's floor: $byTheRows and $blindly", byTheRows != blindly)
        assertEquals("the store's floor is not the floor of the moonless rows", byTheRows, under.hazeFloor)

        // And about the night before, when the picture is filled in
        // from it. On the 7th the moon was down for seventy-seven north
        // and on the 6th it was not.
        val h = sky.raw!!.height
        val row = ((90 - 77.0) / 180 * h).toInt()
        println(
            "MEASURE the moon over 77 N at the overpass: %.1f deg on the 6th, %.1f on the 7th of Nov 2026"
                .format(NightLights.moonAtOverpass(77.0, "2026-11-06"), NightLights.moonAtOverpass(77.0, "2026-11-07"))
        )
        assertTrue(NightLights.moonlessRows(h, "2026-11-07")[row])
        assertFalse(NightLights.moonlessRows(h, "2026-11-06")[row])
        var hazeThere = 0
        for (x in 0 until sky.raw!!.width) if ((sky.haze!![row * sky.raw!!.width + x].toInt() and 0xFF) > 0) hazeThere++
        assertTrue("the night of the 8th has a haze along 77 north to lose: $hazeThere", hazeThere > 50)
        NightLightStore.forget(context)
        val seventh = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.NOVEMBER, 7, 20, 0, 0)
        }.timeInMillis
        assertTrue(NightLightStore.refresh(context, seventh))
        val filled = NightLightStore.skyFor(context, seventh)!!
        assertEquals("2026-11-07", filled.raw!!.day)
        assertEquals("2026-11-06", filled.raw!!.filledFrom)
        for (x in 0 until filled.raw!!.width) {
            assertEquals("a haze at 77 north, column $x, on a night filled in from a moonlit one", 0, filled.haze!![row * filled.raw!!.width + x].toInt() and 0xFF)
        }
        // Further south both nights were moonless, and the oval is there.
        assertTrue(filled.hazeAt(sea.first, sea.second) > filled.hazeFloor)
        NightLightStore.forget(context)
        assertTrue(NightLightStore.refresh(context, now))

        // One layer: no haze, and the floors that go with it — though
        // the store has just worked a haze out and still has it.
        assertNotNull(NightLightStore.skyFor(context, now)!!.haze)
        mode("clean")
        val cleanOnly = NightLightStore.skyFor(context, now)!!
        assertNull(cleanOnly.haze)
        assertEquals(255, cleanOnly.hazeFloor)
        assertEquals(0, cleanOnly.hazeAt(0.5, 0.1))
        mode("raw")
        val rawOnly = NightLightStore.skyFor(context, now)!!
        assertNull(rawOnly.haze)
        assertTrue(rawOnly.rawFloor in 40..65)
    }
}
