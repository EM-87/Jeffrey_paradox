package com.em87.weirdclock

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cloud read off the weather satellites, hour by hour.
 *
 * Asked for as "the clouds moving in real time", and what there is to
 * move them with is two pictures an hour: one of temperature, and one
 * by daylight that is black over half the world. So the rules that turn
 * them into cloud are held here against the only thing there is to hold
 * them against: the day's photograph, on the one morning all three were
 * had of the same places within the hour — the frames of one to five
 * o'clock UTC on 24 November 2026, and the photograph of that day as
 * far as it had got by six, from sixty-five degrees east to the date
 * line. All eleven are beside the tests as the services sent them.
 *
 * And the service's own word for which hour a frame is of, in the four
 * forms it was heard to give that morning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCloudsTest {

    private fun pixels(name: String): Triple<Int, Int, IntArray> {
        val raw = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
        val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
        val out = IntArray(picture.width * picture.height)
        picture.getPixels(out, 0, picture.width, 0, 0, picture.width, picture.height)
        return Triple(picture.width, picture.height, out)
    }

    private fun frame(hour: Int) = pixels("nowcoast-ir-2026-11-24T%02d.jpg".format(hour))

    private fun seen(hour: Int) = pixels("nowcoast-vis-2026-11-24T%02d.jpg".format(hour))

    /** A frame as cloud by its infrared alone. */
    private fun cold(sent: IntArray, w: Int, h: Int): ByteArray = LiveClouds.field(sent, null, w, h, 0.0, 0.0)

    /** An hour as cloud by both its pictures, with the sun where it stood. */
    private fun both(hour: Int): ByteArray {
        val (w, h, infrared) = frame(hour)
        val sun = Hemisphere.subsolar(at(hour))
        return LiveClouds.field(infrared, seen(hour).third, w, h, sun[0], sun[1])
    }

    private fun at(hour: Int): Long = DailyImageStore.msOf("2026-11-24")!! + hour * LiveClouds.HOUR_MS

    private fun grey(g: Int) = (0xFF shl 24) or (g shl 16) or (g shl 8) or g

    // ------------------------------------------------------------ the asking

    /** The request is the whole world and carries no place and no person. */
    @Test
    fun `the request is for the whole world, the newest frame or an hour's`() {
        val newest = LiveClouds.url()
        assertTrue(newest, newest.startsWith("https://nowcoast.noaa.gov/geoserver/satellite/wms?"))
        assertTrue(newest, "layers=global_longwave_imagery_mosaic" in newest)
        assertTrue(newest, "bbox=-90,-180,90,180" in newest)
        assertTrue(newest, "width=720&height=360" in newest)
        assertTrue(newest, "format=image/jpeg" in newest)
        assertFalse("the newest frame is asked for by an hour: $newest", "time=" in newest)
        val five = LiveClouds.url(at(5))
        assertTrue(five, five.endsWith("&time=2026-11-24T05:00:00.000Z"))
        assertEquals(newest, five.substringBefore("&time="))
        // And the question that comes first is the same one, the size of a stamp.
        val ask = LiveClouds.ask()
        assertTrue(ask, "width=16&height=8" in ask)
        assertFalse(ask, "time=" in ask)
        assertEquals(newest.replace("width=720&height=360", "width=16&height=8"), ask)
        // The visible picture of an hour is the same request of the other layer.
        assertEquals(
            five.replace("global_longwave_imagery_mosaic", "global_visible_imagery_mosaic"),
            LiveClouds.visible(at(5))
        )
        assertTrue(LiveClouds.visible(at(5)) != five)
    }

    /**
     * The four things the service was heard to say, word for word, and
     * what each makes of the frame beside it.
     */
    @Test
    fun `the hour of a frame is the one the service names`() {
        val layer = "(satellite:global_longwave_imagery_mosaic)"
        // Nothing asked: the newest, and it says which.
        assertEquals(
            at(5),
            LiveClouds.frameTime("99 Default value used: time=2026-11-24T05:00:00.000Z ISO8601 $layer", null)
        )
        // An hour it has: nothing said, and it is that hour's.
        assertEquals(at(4), LiveClouds.frameTime(null, at(4)))
        assertEquals(at(4), LiveClouds.frameTime("", at(4)))
        // An hour it has not, near one it has: the one it names, not the one asked for.
        assertEquals(
            at(1),
            LiveClouds.frameTime("99 Nearest value used: time=2026-11-24T01:00:00.000Z ISO8601 $layer", at(0))
        )
        // An hour nowhere near anything: no frame.
        assertEquals(
            null,
            LiveClouds.frameTime("99 No nearest value found on satellite:global_longwave_imagery_mosaic: time", at(-1))
        )
        // Nothing asked and nothing said: a picture of no hour anybody can name.
        assertEquals(null, LiveClouds.frameTime(null, null))
        // And something said that is not this: not guessed at.
        assertEquals(null, LiveClouds.frameTime("110 Response is stale", at(3)))
        // The hour as the service's list of hours writes it, without the thousandths.
        assertEquals(at(5), LiveClouds.frameTime("99 Default value used: time=2026-11-24T05:00:00Z ISO8601", null))
        // Not only whole hours, and not only this month.
        assertEquals(
            DailyImageStore.msOf("2027-03-01")!! - 30 * 60_000L,
            LiveClouds.frameTime("99 Nearest value used: time=2027-02-28T23:30:00.000Z ISO8601", null)
        )
        assertEquals("2026-11-24T05:00:00.000Z", LiveClouds.stamp(at(5)))
        assertEquals("2027-02-28T23:30:00.000Z", LiveClouds.stamp(DailyImageStore.msOf("2027-03-01")!! - 30 * 60_000L))
    }

    // -------------------------------------------------------------- the rule

    /** No cloud up to the low mark above the clear sky, the thickest from the high one, and no step between. */
    @Test
    fun `cloud is how much colder than the clear sky of its own row`() {
        val clear = 60f
        assertEquals(0, LiveClouds.cover(60, clear))
        assertEquals(0, LiveClouds.cover(60 + LiveClouds.LOW, clear))
        assertTrue(LiveClouds.cover(60 + LiveClouds.LOW + 8, clear) > 0)
        assertEquals(SatelliteClouds.THICKEST, LiveClouds.cover(60 + LiveClouds.HIGH, clear))
        assertEquals(SatelliteClouds.THICKEST, LiveClouds.cover(239, clear))
        // Half way between the marks is half the thickest.
        val half = LiveClouds.cover(60 + (LiveClouds.LOW + LiveClouds.HIGH) / 2, clear)
        assertTrue("half way is $half", Math.abs(half - SatelliteClouds.THICKEST / 2) <= 3)
        var last = 0
        for (g in 0 until LiveClouds.NO_DATA) {
            val c = LiveClouds.cover(g, clear)
            assertTrue("cloud thinned as it got colder at $g", c >= last)
            assertTrue("a step of ${c - last} at $g", c - last <= 6)
            last = c
        }
        // The same grey is cloud over the tropics and clear sky by the pole.
        assertTrue(LiveClouds.cover(150, 60f) > 200)
        assertEquals(0, LiveClouds.cover(150, 126f))
        // A place the mosaic has nothing for is not the coldest cloud there is.
        assertEquals(0, LiveClouds.cover(LiveClouds.NO_DATA, clear))
        assertEquals(0, LiveClouds.cover(255, clear))
        assertEquals(0, LiveClouds.cover(200, Float.NaN))
    }

    /**
     * A row's clear sky is its darkest tenth, counted over what the row
     * has, and taken with its neighbours'.
     */
    @Test
    fun `the clear sky of a row is the darkest tenth of what it has`() {
        val w = 360
        val h = 180
        // Every row the same: ninety greys from 50 up, four of each. A
        // tenth of the way up three hundred and sixty is the thirty-
        // seventh, which is the first of the tenth grey: 59.
        val even = IntArray(w * h) { grey(50 + (it % w) % 90) }
        val all = LiveClouds.clearSky(even, w, h)
        for (y in 0 until h) assertEquals("row $y", 59f, all[y], 0.001f)

        // Half of every row missing: the tenth is a tenth of what is left,
        // and nothing that is missing is counted as the coldest thing there.
        val halved = IntArray(w * h) { if ((it % w) < 180) grey(50 + (it % w) % 90) else grey(255) }
        assertEquals(59f, LiveClouds.clearSky(halved, w, h)[90], 0.001f)

        // A row with a quarter of itself or less is not asked.
        val thin = even.copyOf()
        for (x in 0 until w) {
            if (x >= 90) thin[40 * w + x] = grey(250)
            if (x >= 91) thin[41 * w + x] = grey(250)
        }
        val asked = LiveClouds.clearSky(thin, w, h)
        assertTrue("a row with a quarter of itself was asked", asked[40].isNaN())
        assertFalse("a row with a pixel more than a quarter was not", asked[41].isNaN())

        // Two skies, a warm one below a cold one: each row keeps its own,
        // and between them the answer is taken over eight degrees either way.
        val two = IntArray(w * h) { grey((if (it / w < 90) 120 else 50) + (it % w) % 90) }
        val both = LiveClouds.clearSky(two, w, h)
        assertEquals(129f, both[40], 0.001f)
        assertEquals(59f, both[140], 0.001f)
        assertEquals("eight degrees is eight rows here", 129f, both[81], 0.001f)
        assertTrue("the row at the join is ${both[90]}", both[90] > 59f && both[90] < 129f)
        assertEquals(59f, both[98], 0.001f)
        for (y in 82..98) assertTrue("the join is not a slope at $y", both[y] <= both[y - 1])

        // A picture that is not the size it says is nobody's sky.
        assertTrue(LiveClouds.clearSky(IntArray(10), 4, 4).all { it.isNaN() })
    }

    /** The cloud is thinned towards where the mosaic stops, and only there. */
    @Test
    fun `the cloud thins towards the end of the mosaic`() {
        val h = 180
        // Nothing for the top twenty rows, everything below.
        val clear = FloatArray(h) { if (it < 20) Float.NaN else 80f }
        val kept = LiveClouds.rowsKept(clear)
        for (y in 0 until 20) assertEquals("a row with nothing kept its cloud", 0f, kept[y], 0f)
        // Four degrees is four rows here: the first is a sliver, the fourth whole.
        assertTrue("the row beside nothing kept ${kept[20]}", kept[20] > 0f && kept[20] < 0.2f)
        assertTrue(kept[21] > kept[20] && kept[22] > kept[21])
        assertEquals(1f, kept[23], 0f)
        assertEquals(1f, kept[100], 0f)
        // The bottom of the picture is the pole and not an end: nothing is taken off it.
        assertEquals(1f, kept[h - 1], 0f)
        // A hole of rows in the middle is an end on both its sides.
        val holed = FloatArray(h) { if (it in 60..62) Float.NaN else 80f }
        val round = LiveClouds.rowsKept(holed)
        assertTrue(round[59] < 0.2f && round[63] < 0.2f)
        assertEquals(1f, round[56], 0f)
        assertEquals(1f, round[66], 0f)
    }

    // ------------------------------------------------- against a real frame

    /**
     * The frame of five o'clock as cloud: where it has any, how much,
     * and that it runs out where the mosaic does.
     */
    @Test
    fun `a real frame is cloud between the ends of the mosaic and nothing beyond`() {
        val (w, h, sent) = frame(5)
        assertEquals(LiveClouds.WIDTH, w)
        assertEquals(LiveClouds.HEIGHT, h)
        val clear = LiveClouds.clearSky(sent, w, h)
        val field = cold(sent, w, h)
        fun row(latitude: Double) = ((90.0 - latitude) / 180.0 * h).toInt()
        // The clear sky is dark at the equator and pale towards the poles.
        println(
            "MEASURE the clear sky of 05:00 UTC, 24 Nov 2026, as a grey: " +
                listOf(70, 60, 40, 20, 0, -20, -40, -60, -70).joinToString(" | ") { "$it: %.0f".format(clear[row(it.toDouble())]) }
        )
        assertTrue(clear[row(0.0)] < 80f)
        assertTrue(clear[row(65.0)] > clear[row(0.0)] + 30f)
        assertTrue(clear[row(-65.0)] > clear[row(0.0)] + 30f)
        // The mosaic has nothing past seventy-three degrees, and says so by being white.
        assertTrue(clear[row(76.0)].isNaN() && clear[row(-76.0)].isNaN())
        assertFalse(clear[row(70.0)].isNaN() || clear[row(-70.0)].isNaN())
        var beyond = 0
        var seen = 0
        var cloudy = 0
        var weight = 0.0
        var cloudWeight = 0.0
        for (y in 0 until h) {
            val latitude = 90.0 - (y + 0.5) * 180.0 / h
            val area = Math.cos(Math.toRadians(latitude))
            for (x in 0 until w) {
                val c = field[y * w + x].toInt() and 0xFF
                if (Math.abs(latitude) > 73.0) {
                    if (c > 0) beyond++
                    continue
                }
                if (LiveClouds.grey(sent[y * w + x]) >= LiveClouds.NO_DATA) continue
                seen++
                weight += area
                if (c > 40) {
                    cloudy++
                    cloudWeight += area
                }
            }
        }
        val share = 100.0 * cloudWeight / weight
        println("MEASURE the infrared of 05:00 UTC as cloud: %.1f%% of what the mosaic sees, by area ($cloudy of $seen pixels), $beyond pixels beyond 73 degrees".format(share))
        assertEquals("cloud where the mosaic has nothing", 0, beyond)
        assertTrue("$share% of the world under cloud", share in 15.0..35.0)
        // And thinned towards the end: the same grey a degree inside the end is less cloud than five inside.
        val kept = LiveClouds.rowsKept(clear)
        val last = (0 until h).first { !clear[it].isNaN() }
        assertTrue("the mosaic starts at row $last", last in 30..38)
        assertTrue(kept[last] < 0.2f && kept[last + 3] < 1f)
        assertEquals(1f, kept[last + 8], 0f)
        assertEquals(1f, kept[h / 2], 0f)
    }

    /** Pearson's, for two lists of the same length. */
    private fun correlation(a: List<Int>, b: List<Int>): Double {
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in a.indices) {
            sab += (a[i] - ma) * (b[i] - mb)
            saa += (a[i] - ma) * (a[i] - ma)
            sbb += (b[i] - mb) * (b[i] - mb)
        }
        return sab / Math.sqrt(saa * sbb)
    }

    /**
     * The measurement the two marks were set by, kept.
     *
     * The satellite that takes the photograph crosses the equator at
     * half past ten of the morning below it, so at any hour UTC there is
     * one strip of the world it is over: the one where it is half past
     * ten. Fifteen degrees of longitude for each of the five frames,
     * between forty-five north and south, and in each the cloud this
     * rule reads off the frame against the cloud [SatelliteClouds.veil]
     * reads off the photograph, pixel for pixel.
     */
    @Test
    fun `the infrared finds as much cloud as the photograph does, in the same hour and place`() {
        val (pw, ph, photograph) = pixels("gibs-clouds-2026-11-24-part.jpg")
        val allLive = ArrayList<Int>()
        val allPhoto = ArrayList<Int>()
        val lines = ArrayList<String>()
        for (hour in 1..5) {
            val (w, h, sent) = frame(hour)
            assertEquals(pw, w)
            assertEquals(ph, h)
            val field = cold(sent, w, h)
            val centre = (10.5 - hour) * 15.0
            val live = ArrayList<Int>()
            val photo = ArrayList<Int>()
            var both = 0
            var liveOnly = 0
            var photoOnly = 0
            for (y in h / 4 until 3 * h / 4) for (x in 0 until w) {
                val longitude = -180.0 + (x + 0.5) * 360.0 / w
                if (Math.abs(longitude - centre) > 7.5) continue
                val i = y * w + x
                val p = photograph[i]
                val there = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > SatelliteClouds.NO_DATA
                if (!there || LiveClouds.grey(sent[i]) >= LiveClouds.NO_DATA) continue
                val a = field[i].toInt() and 0xFF
                val b = SatelliteClouds.veil(p)
                live += a
                photo += b
                if (a > 40 && b > 40) both++ else if (a > 40) liveOnly++ else if (b > 40) photoOnly++
            }
            assertTrue("only ${live.size} pixels in the strip of $hour o'clock", live.size > 4_000)
            val r = correlation(live, photo)
            val n = live.size.toDouble()
            lines += "%02d:00 at %.1f E: r %.2f, mean %.0f against %.0f, both %.0f%% infrared only %.0f%% photograph only %.0f%%"
                .format(hour, centre, r, live.average(), photo.average(), 100 * both / n, 100 * liveOnly / n, 100 * photoOnly / n)
            // Two instruments, not two copies: but never strangers.
            assertTrue("the strip of $hour o'clock agrees at $r", r > 0.25)
            allLive += live
            allPhoto += photo
        }
        val r = correlation(allLive, allPhoto)
        val mine = allLive.average()
        val theirs = allPhoto.average()
        println("MEASURE infrared against photograph, 24 Nov 2026: " + lines.joinToString(" | "))
        println("MEASURE all five strips, ${allLive.size} pixels: r %.2f, mean cloud %.1f by infrared against %.1f by photograph".format(r, mine, theirs))
        assertTrue("over the five strips the two agree at $r", r > 0.45)
        // As much cloud as the photograph's rule finds, to within a fifth of it.
        assertTrue("$mine by infrared against $theirs by photograph", Math.abs(mine - theirs) < theirs * 0.2)
    }

    // ------------------------------------------------------ the visible one

    /** Cloud by daylight is how bright, between two marks, with no step. */
    @Test
    fun `by daylight cloud is how bright a place is`() {
        assertEquals(0, LiveClouds.seen(0))
        assertEquals(0, LiveClouds.seen(60))
        assertEquals(0, LiveClouds.seen(LiveClouds.SEEN_LOW))
        assertTrue(LiveClouds.seen(LiveClouds.SEEN_LOW + 8) > 0)
        assertEquals(SatelliteClouds.THICKEST, LiveClouds.seen(LiveClouds.SEEN_HIGH))
        assertEquals(SatelliteClouds.THICKEST, LiveClouds.seen(239))
        val half = LiveClouds.seen((LiveClouds.SEEN_LOW + LiveClouds.SEEN_HIGH) / 2)
        assertTrue("half way is $half", Math.abs(half - SatelliteClouds.THICKEST / 2) <= 3)
        var last = 0
        for (g in 0 until LiveClouds.NO_DATA) {
            val c = LiveClouds.seen(g)
            assertTrue("cloud thinned as it got brighter at $g", c >= last)
            assertTrue("a step of ${c - last} at $g", c - last <= 6)
            last = c
        }
        // The white the service paints a place it has nothing for is not the brightest cloud.
        assertEquals(0, LiveClouds.seen(LiveClouds.NO_DATA))
        assertEquals(0, LiveClouds.seen(255))
    }

    /** The visible picture is believed by how high the sun stood: nine degrees up not at all, twenty wholly. */
    @Test
    fun `daylight is believed by how high the sun stood`() {
        assertEquals(0f, LiveClouds.daylight(-1.0), 0f)
        assertEquals(0f, LiveClouds.daylight(0.0), 0f)
        assertEquals(0f, LiveClouds.daylight(LiveClouds.SUN_LEAST), 0f)
        assertEquals(1f, LiveClouds.daylight(LiveClouds.SUN_ENOUGH), 0f)
        assertEquals(1f, LiveClouds.daylight(1.0), 0f)
        assertEquals(0.5f, LiveClouds.daylight((LiveClouds.SUN_LEAST + LiveClouds.SUN_ENOUGH) / 2), 0.001f)
        var last = 0f
        for (i in 0..100) {
            val now = LiveClouds.daylight(i / 100.0)
            assertTrue(now >= last)
            last = now
        }
        // As heights of the sun: the two marks are nine degrees and twenty.
        assertEquals(8.6, Math.toDegrees(Math.asin(LiveClouds.SUN_LEAST)), 0.1)
        assertEquals(20.5, Math.toDegrees(Math.asin(LiveClouds.SUN_ENOUGH)), 0.1)
    }

    /**
     * One sky of the two pictures: the visible one under a high sun,
     * the infrared under none, and a share of each between.
     */
    @Test
    fun `an hour is its visible picture by day and its infrared by night`() {
        val w = 360
        val h = 180
        // The infrared says thick cloud everywhere: a clear sky of 59 in
        // every row, and all but that darkest ninth of each a hundred
        // and fifty greys colder. The visible picture says none anywhere.
        val infrared = IntArray(w * h) { if ((it % w) % 9 == 0) grey(59) else grey(210) }
        val dark = IntArray(w * h) { grey(60) }
        // The sun over the equator at the Greenwich meridian.
        val field = LiveClouds.field(infrared, dark, w, h, 0.0, 0.0)
        val alone = LiveClouds.field(infrared, null, w, h, 0.0, 0.0)
        fun at(latitude: Double, longitude: Double): Int {
            val y = ((90.0 - latitude) / 180.0 * h).toInt()
            var x = ((longitude + 180.0) / 360.0 * w).toInt()
            if (x % 9 == 0) x++
            return field[y * w + x].toInt() and 0xFF
        }
        assertEquals("the infrared alone is thick cloud", SatelliteClouds.THICKEST, alone[90 * w + 181].toInt() and 0xFF)
        // Under the sun, and anywhere it is twenty degrees up: the visible picture's none.
        assertEquals(0, at(0.5, 0.5))
        assertEquals(0, at(40.5, 30.5))
        assertEquals(0, at(0.5, 65.5))
        // On the far side, and anywhere it is under nine degrees up: the infrared's all.
        assertEquals(SatelliteClouds.THICKEST, at(0.5, 179.5))
        assertEquals(SatelliteClouds.THICKEST, at(0.5, -120.5))
        assertEquals(SatelliteClouds.THICKEST, at(0.5, 83.5))
        // Between, a share: fourteen and a half degrees up is half of each.
        val between = at(0.5, 90.0 - 14.5)
        assertTrue("half and half is $between", Math.abs(between - SatelliteClouds.THICKEST / 2) <= 12)
        // The other way about: the infrared's none, the visible picture's all.
        val clear = IntArray(w * h) { grey(59 + (it % w) % 3) }
        val bright = IntArray(w * h) { grey(200) }
        val day = LiveClouds.field(clear, bright, w, h, 0.0, 0.0)
        assertEquals(SatelliteClouds.THICKEST, day[90 * w + 181].toInt() and 0xFF)
        assertEquals("cloud at night off a picture taken by daylight", 0, day[90 * w + 1].toInt() and 0xFF)
        // Where the visible picture has nothing, the infrared answers alone, sun or no sun.
        val holed = dark.copyOf().also { for (x in 170 until 190) it[90 * w + x] = grey(255) }
        val patched = LiveClouds.field(infrared, holed, w, h, 0.0, 0.0)
        assertEquals(SatelliteClouds.THICKEST, patched[90 * w + 181].toInt() and 0xFF)
        assertEquals(0, patched[91 * w + 181].toInt() and 0xFF)
        // A visible picture of another size is not this hour's.
        assertTrue(LiveClouds.field(infrared, IntArray(w * h / 4), w, h, 0.0, 0.0).contentEquals(alone))
        // And the sun's place is the sun's: moved to the far side, the answers change places.
        val turned = LiveClouds.field(infrared, dark, w, h, 0.0, 180.0)
        assertEquals(SatelliteClouds.THICKEST, turned[90 * w + 181].toInt() and 0xFF)
        assertEquals(0, turned[90 * w + 1].toInt() and 0xFF)
        // At midsummer the pole is in daylight and at midwinter it is not.
        val summer = LiveClouds.field(infrared, dark, w, h, 23.4, 0.0)
        val winter = LiveClouds.field(infrared, dark, w, h, -23.4, 0.0)
        assertEquals(0, summer[5 * w + 181].toInt() and 0xFF)
        assertEquals(SatelliteClouds.THICKEST, winter[5 * w + 181].toInt() and 0xFF)
    }

    /**
     * The two pictures against the photograph, in the same five strips
     * and then further south, where the low cloud is.
     */
    @Test
    fun `by daylight the hour agrees with the photograph better than its infrared does`() {
        val (pw, ph, photograph) = pixels("gibs-clouds-2026-11-24-part.jpg")
        class Band(val name: String, val north: Double, val south: Double) {
            val cold = ArrayList<Int>()
            val seen = ArrayList<Int>()
            val photo = ArrayList<Int>()
        }
        val middle = Band("45 N to 45 S", 45.0, -45.0)
        val storm = Band("45 to 55 S", -45.0, -55.0)
        val far = Band("55 to 65 S", -55.0, -65.0)
        for (hour in 1..5) {
            val (w, h, infrared) = frame(hour)
            assertEquals(pw, w)
            assertEquals(ph, h)
            val byCold = cold(infrared, w, h)
            val byBoth = both(hour)
            val visible = seen(hour).third
            val centre = (10.5 - hour) * 15.0
            for (band in listOf(middle, storm, far)) {
                for (y in ((90.0 - band.north) / 180.0 * h).toInt() until ((90.0 - band.south) / 180.0 * h).toInt()) {
                    for (x in 0 until w) {
                        val longitude = -180.0 + (x + 0.5) * 360.0 / w
                        if (Math.abs(longitude - centre) > 7.5) continue
                        val i = y * w + x
                        val p = photograph[i]
                        val there = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > SatelliteClouds.NO_DATA
                        if (!there || LiveClouds.grey(infrared[i]) >= LiveClouds.NO_DATA) continue
                        if (LiveClouds.grey(visible[i]) >= LiveClouds.NO_DATA) continue
                        band.cold += byCold[i].toInt() and 0xFF
                        band.seen += byBoth[i].toInt() and 0xFF
                        band.photo += SatelliteClouds.veil(p)
                    }
                }
            }
        }
        for (band in listOf(middle, storm, far)) {
            println(
                "MEASURE ${band.name}, ${band.photo.size} pixels: by both pictures r %.2f mean %.0f | by infrared alone r %.2f mean %.0f | photograph mean %.0f"
                    .format(
                        correlation(band.seen, band.photo), band.seen.average(),
                        correlation(band.cold, band.photo), band.cold.average(), band.photo.average()
                    )
            )
            assertTrue("only ${band.photo.size} pixels ${band.name}", band.photo.size > 2_500)
        }
        // Where the marks were set: as much cloud as the photograph, and nearer it than the infrared is.
        val r = correlation(middle.seen, middle.photo)
        assertTrue("the two pictures agree with the photograph at $r", r > 0.6)
        assertTrue(r > correlation(middle.cold, middle.photo) + 0.05)
        val mine = middle.seen.average()
        val theirs = middle.photo.average()
        assertTrue("$mine by daylight against $theirs by photograph", Math.abs(mine - theirs) < theirs * 0.2)
        // And where the infrared has next to nothing to say.
        assertTrue(correlation(storm.seen, storm.photo) > 0.55)
        assertTrue(correlation(storm.seen, storm.photo) > correlation(storm.cold, storm.photo) + 0.1)
        assertTrue(correlation(far.seen, far.photo) > 0.4)
        assertTrue(correlation(far.cold, far.photo) < 0.2)
        assertTrue("the southern ocean is still nearly clear: ${far.seen.average()}", far.seen.average() > far.cold.average() * 3)
    }

    /** On the real frame: the night side is the infrared's to the pixel, and the day side is not. */
    @Test
    fun `a real hour is its infrared wherever it was night`() {
        val (w, h, infrared) = frame(3)
        val byCold = cold(infrared, w, h)
        val byBoth = both(3)
        val sun = Hemisphere.subsolar(at(3))
        var night = 0
        var nightDiffers = 0
        var day = 0
        var dayDiffers = 0
        for (y in 0 until h) for (x in 0 until w) {
            val latitude = Math.toRadians(90.0 - (y + 0.5) * 180.0 / h)
            val longitude = -180.0 + (x + 0.5) * 360.0 / w
            val up = Math.sin(latitude) * Math.sin(Math.toRadians(sun[0])) +
                Math.cos(latitude) * Math.cos(Math.toRadians(sun[0])) * Math.cos(Math.toRadians(longitude - sun[1]))
            val i = y * w + x
            if (up < LiveClouds.SUN_LEAST - 0.01) {
                night++
                if (byCold[i] != byBoth[i]) nightDiffers++
            } else if (up > LiveClouds.SUN_ENOUGH + 0.01) {
                day++
                if (byCold[i] != byBoth[i]) dayDiffers++
            }
        }
        println("MEASURE 03:00 UTC: of $day pixels the sun was well up over, $dayDiffers are other cloud by daylight than by infrared; of $night it was down over, $nightDiffers")
        assertEquals("the visible picture changed the night", 0, nightDiffers)
        assertTrue("the visible picture changed nothing by day: $dayDiffers of $day", dayDiffers > day / 5)
    }

    /** The cloud moves from one hour's frame to the next, and not all at once. */
    @Test
    fun `an hour on the cloud has moved a little`() {
        val (w, h, one) = frame(1)
        val two = frame(2).third
        val five = frame(5).third
        val a = cold(one, w, h)
        val b = cold(two, w, h)
        val e = cold(five, w, h)
        fun apart(p: ByteArray, q: ByteArray): Double {
            var moved = 0
            for (i in p.indices) if (Math.abs((p[i].toInt() and 0xFF) - (q[i].toInt() and 0xFF)) > 40) moved++
            return 100.0 * moved / p.size
        }
        val hour = apart(a, b)
        val four = apart(a, e)
        println("MEASURE cloud that changed by more than 40 in 255: %.1f%% of the world in one hour, %.1f%% in four".format(hour, four))
        assertTrue("nothing moved in an hour: $hour%", hour > 0.3)
        assertTrue("an hour moved $hour% of the world", hour < 8.0)
        assertTrue("four hours moved no more than one: $four against $hour", four > hour * 1.5)
    }
}
