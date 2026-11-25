package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every layer of the world is drawn where its places are.
 *
 * Which nothing asked for ten versions. The projection was tested, and
 * the marks drawn by it — the dot, the night, the hours — and the maps
 * were looked at; what was never asked is whether a place of the map
 * comes out on the glass where the projection puts that place. On the
 * view from under the south pole it did not: the disc was turned the
 * north's way, and Sydney's dot stood in the open sea at three in the
 * morning UTC with Australia a quarter of the way round from it.
 *
 * Found by a test about something else — a cloud put where the sun was
 * high, to measure its shadow, which was not where it had been put.
 * So here each layer is given something at a known place and looked for
 * on the glass: the day's ground, the cloud, the night's map, last
 * night's lights. On all three views, at hours that have the sun over
 * six different longitudes, and with the sun pinned to different sides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacesOnTheGlassTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val size = 1080

    private fun at(hour: Int): Long = DailyImageStore.msOf("2026-11-22")!! + hour * 3_600_000L

    /** An hour of the day and where the sun is pinned: between them, the sun over every part of the world. */
    private class Case(val which: Hemisphere.View, val hour: Int, val sunAt: Double) {
        val named: String get() = "the ${which.key} view at $hour h with the sun at ${sunAt.toInt()}"
    }

    /**
     * Down a pole the sun can be pinned anywhere: the picture is turned
     * and is the same picture. On the globe pinning it is turning the
     * ball, and with the sun brought round to the front there is no
     * night on it to put a light in — so there it is pinned to one side
     * or the other, and a little off each.
     */
    private val cases: List<Case> = Hemisphere.View.entries.flatMap { which ->
        val pinned = if (which == Hemisphere.View.GLOBE) {
            listOf(1 to 0.0, 5 to 180.0, 9 to 345.0, 14 to 195.0, 18 to 0.0, 22 to 180.0)
        } else {
            listOf(1 to 0.0, 5 to 135.0, 9 to 0.0, 14 to 250.0, 18 to 0.0, 22 to 90.0)
        }
        pinned.map { Case(which, it.first, it.second) }
    }

    private class Glass(val size: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * size + x]
    }

    private fun glass(v: View): Glass {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Glass(bitmap.width, out)
    }

    private fun globe(
        case: Case,
        atMs: Long = at(case.hour),
        ground: SatellitePhoto.Picture? = null,
        clouds: SatelliteClouds.Field? = null,
        lights: NightLightStore.Sky? = null
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = case.which
        this.atMs = atMs
        sunAt = case.sunAt
        hourRing = false
        showSun = false
        showMoon = false
        moonlight = false
        located = false
        photo = ground
        this.clouds = clouds
        this.lights = lights
        measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, size, size)
    }

    private fun brightness(c: Int): Double = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10.0

    private val radius: Double
        get() = size * Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed = false, moonOutside = false).toDouble()

    /**
     * A place for something to be put: the one drawn so far out from the
     * middle of the picture, and so many degrees clockwise of the way to
     * the sun — or of the way from it, for a place in the night. As a
     * latitude and a longitude of the world.
     */
    private fun place(case: Case, atMs: Long, out: Double, clockwise: Double, night: Boolean = false): DoubleArray {
        val sub = Hemisphere.subsolar(atMs)
        val toSun = Hemisphere.project(case.which, sub[0], 0.0, 0.0, case.sunAt)
        val along = Math.hypot(toSun[0], toSun[1])
        val side = if (night) -1.0 else 1.0
        val ux = side * toSun[0] / along
        val uy = side * toSun[1] / along
        val a = Math.toRadians(clockwise)
        val x = (ux * Math.cos(a) - uy * Math.sin(a)) * out
        val y = (ux * Math.sin(a) + uy * Math.cos(a)) * out
        val there = Hemisphere.unproject(case.which, x, y, case.sunAt)!!
        return doubleArrayOf(there[0], Hemisphere.wrap(sub[1] + there[1]))
    }

    /** Where the projection puts a place on the glass, in pixels. */
    private fun onGlass(case: Case, atMs: Long, place: DoubleArray): DoubleArray {
        val p = Hemisphere.project(case.which, place[0], place[1], Hemisphere.subsolar(atMs)[1], case.sunAt)
        assertTrue("the place is behind the world on ${case.named}", p[2] > 0.3)
        return doubleArrayOf(size / 2.0 + p[0] * radius, size / 2.0 + p[1] * radius)
    }

    /** How far apart two places are, in degrees round the world. */
    private fun apart(lat: Double, lon: Double, lat0: Double, lon0: Double): Double {
        val a = Math.toRadians(lat)
        val b = Math.toRadians(lat0)
        val c = Math.sin(a) * Math.sin(b) + Math.cos(a) * Math.cos(b) * Math.cos(Math.toRadians(lon - lon0))
        return Math.toDegrees(Math.acos(c.coerceIn(-1.0, 1.0)))
    }

    /** A world of one grey with a round patch of magenta, which is no colour of anything else drawn. */
    private fun painted(place: DoubleArray, degrees: Double): SatellitePhoto.Picture {
        val w = SatellitePhoto.WIDTH
        val h = SatellitePhoto.HEIGHT
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val lat = 90.0 - (y + 0.5) * 180.0 / h
            val lon = -180.0 + (x + 0.5) * 360.0 / w
            out[y * w + x] = if (apart(lat, lon, place[0], place[1]) < degrees) 0xFFFF00FF.toInt() else 0xFF909090.toInt()
        }
        return SatellitePhoto.Picture(w, h, out)
    }

    /** A map of the world, half a degree a pixel, with something round at one place of it. */
    private fun dotted(place: DoubleArray, degrees: Double, value: Int): ByteArray {
        val w = 720
        val h = 360
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val lat = 90.0 - (y + 0.5) * 0.5
            val lon = -180.0 + (x + 0.5) * 0.5
            if (apart(lat, lon, place[0], place[1]) < degrees) out[y * w + x] = value.toByte()
        }
        return out
    }

    /** The middle of the pixels a test picks out, and how many there were. */
    private fun middle(size: Int, picked: (Int, Int) -> Boolean): DoubleArray {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        for (py in 0 until size) for (px in 0 until size) {
            if (!picked(px, py)) continue
            n++
            sx += px
            sy += py
        }
        return if (n == 0) doubleArrayOf(Double.NaN, Double.NaN, 0.0) else doubleArrayOf(sx / n, sy / n, n.toDouble())
    }

    private fun magenta(c: Int): Boolean =
        Color.red(c) - Color.green(c) > 50 && Color.blue(c) - Color.green(c) > 50

    /**
     * Near enough: a fiftieth of the world's radius. The globe is
     * repainted only when the world has turned a degree and a half, so it
     * may be three quarters of a degree behind; being turned the wrong
     * way was out by twice the sun's longitude.
     */
    private val near: Double get() = radius * 0.02

    // ------------------------------------------------------------- the rule

    /**
     * The painted disc, turned by what the rule says, has every place
     * where the projection puts it — which is all the rule is for.
     */
    @Test
    fun `a flat view's disc is turned to where the projection puts its places`() {
        var worst = 0.0
        for (which in listOf(Hemisphere.View.NORTH, Hemisphere.View.SOUTH)) {
            for (subLon in listOf(-170.0, -93.0, -20.0, 0.0, 41.5, 131.5, 180.0)) for (sunAt in listOf(0.0, 90.0, 215.0)) {
                val turn = Math.toRadians(Hemisphere.discTurn(which, subLon, sunAt))
                for (lat in listOf(-80.0, -33.9, 0.0, 12.0, 51.5, 89.0)) for (lon in listOf(-150.0, -58.4, 0.0, 28.0, 151.2)) {
                    // As painted: Greenwich where the sun is, the sun on the right.
                    val painted = Hemisphere.project(which, lat, lon, 0.0, 0.0)
                    // Turned anticlockwise on a screen whose y runs down.
                    val x = painted[0] * Math.cos(turn) + painted[1] * Math.sin(turn)
                    val y = -painted[0] * Math.sin(turn) + painted[1] * Math.cos(turn)
                    val wanted = Hemisphere.project(which, lat, lon, subLon, sunAt)
                    worst = maxOf(worst, Math.hypot(x - wanted[0], y - wanted[1]))
                    assertEquals("which side of the world a place is on does not turn", wanted[2], painted[2], 1e-12)
                }
            }
        }
        println("MEASURE a painted disc turned by the rule against the projection: worst %.2e of the radius".format(worst))
        assertTrue("the rule turns a place $worst of the radius from where the projection has it", worst < 1e-9)
        // The two poles' views turn opposite ways for the same sun, and the globe's disc not at all.
        assertEquals(-131.5, Hemisphere.discTurn(Hemisphere.View.NORTH, 131.5, 0.0), 0.0)
        assertEquals(131.5, Hemisphere.discTurn(Hemisphere.View.SOUTH, 131.5, 0.0), 0.0)
        assertEquals(-41.5, Hemisphere.discTurn(Hemisphere.View.NORTH, 131.5, 90.0), 0.0)
        assertEquals(221.5, Hemisphere.discTurn(Hemisphere.View.SOUTH, 131.5, 90.0), 0.0)
        assertEquals(0.0, Hemisphere.discTurn(Hemisphere.View.GLOBE, 131.5, 90.0), 0.0)
    }

    // ------------------------------------------------------------ the glass

    /**
     * A patch painted on the ground and a cloud in the sky, at two places
     * in daylight, are on the glass where the projection puts the two
     * places.
     */
    @Test
    fun `the ground and the cloud are drawn where their places are`() {
        for (case in cases) {
            val ms = at(case.hour)
            val patch = place(case, ms, out = 0.7, clockwise = 30.0)
            val cloud = place(case, ms, out = 0.7, clockwise = -30.0)
            val ground = painted(patch, 5.0)
            val bare = glass(globe(case, ground = ground))
            val clouded = glass(globe(case, ground = ground, clouds = SatelliteClouds.Field(720, 360, dotted(cloud, 4.0, SatelliteClouds.THICKEST))))
            val found = middle(size) { x, y -> magenta(bare.at(x, y)) }
            val wanted = onGlass(case, ms, patch)
            assertTrue("no patch on ${case.named}: ${found[2]} pixels", found[2] > 1_000)
            val off = Math.hypot(found[0] - wanted[0], found[1] - wanted[1])
            val white = middle(size) { x, y ->
                val was = brightness(bare.at(x, y))
                was > 30.0 && brightness(clouded.at(x, y)) > was * 1.08
            }
            val sky = onGlass(case, ms, cloud)
            assertTrue("no cloud on ${case.named}: ${white[2]} pixels", white[2] > 1_000)
            val skyOff = Math.hypot(white[0] - sky[0], white[1] - sky[1])
            println(
                "MEASURE on ${case.named} the patch of ground is %.1f pixels from its place and the cloud %.1f, of %.1f allowed"
                    .format(off, skyOff, near)
            )
            assertTrue("the ground on ${case.named} is $off pixels from its place", off < near)
            assertTrue("the cloud on ${case.named} is $skyOff pixels from its place", skyOff < near)
        }
    }

    /**
     * The night's map is the app's own picture of the earth at night,
     * and nothing can be painted on it. So the glass is held against the
     * picture: over the dark side, how bright the glass is goes with how
     * bright the map is at the place the projection says is there — and
     * does not go with the map at the place the south view used to draw.
     */
    @Test
    fun `the night's map is drawn where its places are`() {
        val map = BitmapFactory.decodeResource(
            context.resources, R.drawable.earth_night, BitmapFactory.Options().apply { inScaled = false }
        )
        val w = map.width
        val h = map.height
        val lit = IntArray(w * h).also { map.getPixels(it, 0, w, 0, 0, w, h) }
        fun mapAt(lat: Double, lon: Double): Double {
            val x = Math.floorMod(Math.floor((Hemisphere.wrap(lon) + 180.0) / 360.0 * w).toInt(), w)
            val y = Math.floor((90.0 - lat) / 180.0 * h).toInt().coerceIn(0, h - 1)
            return brightness(lit[y * w + x])
        }
        fun together(a: List<Double>, b: List<Double>): Double {
            val ma = a.average()
            val mb = b.average()
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in a.indices) {
                ab += (a[i] - ma) * (b[i] - mb)
                aa += (a[i] - ma) * (a[i] - ma)
                bb += (b[i] - mb) * (b[i] - mb)
            }
            return ab / Math.sqrt(aa * bb)
        }
        for (case in cases) {
            val ms = at(case.hour)
            val sub = Hemisphere.subsolar(ms)
            val toSun = Hemisphere.project(case.which, sub[0], 0.0, 0.0, case.sunAt)
            val picture = glass(globe(case))
            val seen = ArrayList<Double>()
            val there = ArrayList<Double>()
            val elsewhere = ArrayList<Double>()
            // By squares of twelve pixels, each the average of sixteen
            // points of it: the globe is repainted every degree and a
            // half and may be most of a degree behind, and a town on the
            // night's map is a pixel or two.
            for (top in 0 until size - 12 step 12) for (left in 0 until size - 12 step 12) {
                var glassHere = 0.0
                var mapHere = 0.0
                var mapElsewhere = 0.0
                var whole = true
                for (py in top until top + 12 step 3) for (px in left until left + 12 step 3) {
                    val x = (px + 0.5 - size / 2.0) / radius
                    val y = (py + 0.5 - size / 2.0) / radius
                    val within = 1.0 - x * x - y * y
                    // Deep in the night, where the glass is the night's map
                    // and nothing else, and not out at the edge.
                    if (within < 0.19 || x * toSun[0] + y * toSun[1] + Math.sqrt(within) * toSun[2] > -0.2) {
                        whole = false
                        continue
                    }
                    val place = Hemisphere.unproject(case.which, x, y, case.sunAt)!!
                    glassHere += brightness(picture.at(px, py))
                    mapHere += mapAt(place[0], sub[1] + place[1])
                    // The same point of the glass with the disc turned the other way for this sun.
                    mapElsewhere += mapAt(place[0], -sub[1] + place[1])
                }
                if (!whole) continue
                seen += glassHere / 16.0
                there += mapHere / 16.0
                elsewhere += mapElsewhere / 16.0
            }
            val right = together(seen, there)
            val wrong = together(seen, elsewhere)
            println(
                "MEASURE the night on ${case.named}: over ${seen.size} squares the glass goes with the map at their places by %.2f, and with the map turned the other way by %.2f"
                    .format(right, wrong)
            )
            assertTrue("only ${seen.size} squares of night on ${case.named}", seen.size > 400)
            assertTrue("the night's map on ${case.named} goes with its places by only $right", right > 0.8)
            // Turned the other way is another part of the world unless the
            // sun is near Greenwich or the date line, where it is the same.
            if (Math.abs(Math.sin(Math.toRadians(sub[1]))) > 0.5) {
                assertTrue("the night's map on ${case.named} goes as well with the wrong places: $wrong against $right", wrong < right - 0.4)
            }
        }
    }

    /** A town's worth of last night's lights, at a place in the night, is on the glass where the place is. */
    @Test
    fun `last night's lights are drawn where their places are`() {
        for (case in cases) {
            val ms = at(case.hour)
            val town = place(case, ms, out = 0.6, clockwise = 25.0, night = true)
            val layer = NightLightStore.Layer(720, 360, dotted(town, 3.0, 255), NightLights.dayFor(ms - DailyImageStore.DAY_MS), 1)
            val sky = NightLightStore.Sky(NightLights.Mode.CLEAN, layer, null, NightLights.RESIDUE_FLOOR_LEAST)
            val dark = glass(globe(case))
            val world = globe(case, lights = sky)
            val lamps = glass(world)
            assertNull("the globe says something about its lights, which would be counted as light", world.legendForTest())
            val found = middle(size) { x, y -> brightness(lamps.at(x, y)) > brightness(dark.at(x, y)) + 25.0 }
            val wanted = onGlass(case, ms, town)
            assertTrue("no lights on ${case.named}: ${found[2]} pixels", found[2] > 300)
            val off = Math.hypot(found[0] - wanted[0], found[1] - wanted[1])
            println("MEASURE on ${case.named} the lights (${found[2].toInt()} pixels) are %.1f pixels from their place, of %.1f allowed".format(off, near))
            assertTrue("the lights on ${case.named} are $off pixels from their place", off < near)
        }
    }

    /**
     * A finger that goes thirty degrees round a pole takes the world
     * thirty degrees round with it, the same way. [Hemisphere.windBy] has
     * always said which way that is for each pole; from under the south
     * one the world went the other way from the finger, because its map
     * did.
     */
    @Test
    fun `the world goes round with the finger from over either pole`() {
        for (which in listOf(Hemisphere.View.NORTH, Hemisphere.View.SOUTH)) for (hour in listOf(2, 10, 17)) {
            val case = Case(which, hour, 0.0)
            val ms = at(hour)
            // Well clear of the night both before and after: the patch is looked for by its colour.
            val patch = place(case, ms, out = 0.7, clockwise = -45.0)
            val ground = painted(patch, 5.0)
            val before = glass(globe(case, atMs = ms, ground = ground))
            val after = glass(globe(case, atMs = ms + Hemisphere.windBy(which, 30.0), ground = ground))
            val from = middle(size) { x, y -> magenta(before.at(x, y)) }
            val to = middle(size) { x, y -> magenta(after.at(x, y)) }
            assertTrue("no patch on ${case.named}", from[2] > 1_000 && to[2] > 1_000)
            // Clockwise on the glass, which is how a finger's turn is counted.
            val a0 = Math.toDegrees(Math.atan2(from[1] - size / 2.0, from[0] - size / 2.0))
            val a1 = Math.toDegrees(Math.atan2(to[1] - size / 2.0, to[0] - size / 2.0))
            val turned = Hemisphere.wrap(a1 - a0)
            println("MEASURE a finger thirty degrees round ${case.named} takes the ground %.1f degrees round".format(turned))
            assertEquals("the ground on ${case.named} went $turned degrees for the finger's thirty", 30.0, turned, 1.0)
        }
    }
}
