package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * The globe as a ball the sun lights.
 *
 * Asked after somebody had held another phone's globe beside this one:
 * why is theirs beautiful. Because theirs is a lit ball and this was a
 * map with the night laid over half of it. Three things were chosen to
 * close most of the distance without drawing the earth any other way —
 * the sun's light by how high it stands, the air at the edge, and the
 * cloud given the same light and a shadow — and each is held here twice:
 * the rule by itself, and then on the glass against the globe drawn the
 * way it was, which the view can still be told to do for the purpose.
 *
 * On the glass, because a rule can be right and wired to nothing. The
 * measurements are of pictures: how much of its brightness a patch of
 * ground keeps, where the blue is, which side of a cloud is darker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobeLightTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun pixels(name: String): Triple<Int, Int, IntArray> {
        val raw = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
        val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
        val out = IntArray(picture.width * picture.height)
        picture.getPixels(out, 0, picture.width, 0, 0, picture.width, picture.height)
        return Triple(picture.width, picture.height, out)
    }

    /** The cloud of 22 November 2026, its gaps filled from the day before. */
    private fun cloud(): SatelliteClouds.Field {
        val (w, h, day) = pixels("gibs-clouds-2026-11-22.jpg")
        SatelliteClouds.fill(day, pixels("gibs-clouds-2026-11-21.jpg").third)
        return SatelliteClouds.Field(w, h, SatelliteClouds.field(day, w, h))
    }

    /** Cloud over the whole world, as thick as cloud gets. */
    private fun overcast(): SatelliteClouds.Field =
        SatelliteClouds.Field(720, 360, ByteArray(720 * 360) { SatelliteClouds.THICKEST.toByte() })

    private fun at(hour: Int): Long = DailyImageStore.msOf("2026-11-22")!! + hour * 3_600_000L

    private fun globe(
        atMs: Long,
        which: Hemisphere.View,
        clouds: SatelliteClouds.Field? = null,
        lit: Boolean = true,
        sunAt: Double = 0.0,
        size: Int = 1080,
        ground: SatellitePhoto.Picture? = null
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = which
        this.atMs = atMs
        this.sunAt = sunAt
        hourRing = false
        showSun = false
        showMoon = false
        moonlight = false
        located = false
        this.clouds = clouds
        this.lit = lit
        photo = ground
        measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, size, size)
    }

    private fun draw(v: View): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return bitmap
    }

    private fun shoot(v: View, name: String): Bitmap = draw(v).also { bitmap ->
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name ${bitmap.width}x${bitmap.height}")
    }

    /** A drawn picture as its pixels: asking a bitmap for them one at a time is slow. */
    private class Glass(val size: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * size + x]
    }

    private fun glass(v: View): Glass {
        val bitmap = draw(v)
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Glass(bitmap.width, out)
    }

    private fun brightness(c: Int): Double = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10.0

    /** The world's radius on a picture of this size, as the view draws it with nothing round it. */
    private fun radius(size: Int): Double =
        size * Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed = false, moonOutside = false).toDouble()

    /** The way to the sun in the picture's own frame: across, down, and towards the eye. */
    private fun sun(which: Hemisphere.View, atMs: Long, sunAt: Double = 0.0): DoubleArray =
        Hemisphere.project(which, Hemisphere.subsolar(atMs)[0], 0.0, 0.0, sunAt)

    /** How high the sun stands over the point of the ball drawn at this pixel, or null off the world. */
    private fun sunOver(px: Int, py: Int, size: Int, toSun: DoubleArray): Double? {
        val r = radius(size)
        val x = (px + 0.5 - size / 2.0) / r
        val y = (py + 0.5 - size / 2.0) / r
        val within = 1.0 - x * x - y * y
        if (within < 0.0) return null
        return x * toSun[0] + y * toSun[1] + Math.sqrt(within) * toSun[2]
    }

    private fun rho(px: Int, py: Int, size: Int): Double =
        Math.hypot(px + 0.5 - size / 2.0, py + 0.5 - size / 2.0) / radius(size)

    /**
     * The same for anywhere on the picture: off the world, the sun's
     * height over the edge nearest, which is what lights the air there.
     */
    private fun sunNear(px: Int, py: Int, size: Int, toSun: DoubleArray): Double {
        sunOver(px, py, size, toSun)?.let { return it }
        val far = rho(px, py, size)
        val r = radius(size)
        return (px + 0.5 - size / 2.0) / r / far * toSun[0] + (py + 0.5 - size / 2.0) / r / far * toSun[1]
    }

    /**
     * A world of one grey, by way of the day's photograph, which is laid
     * over the map and can be a photograph of anything: ground that is
     * the same everywhere, so that what a cloud does to it can be
     * measured without the sea being darker than the shore.
     */
    private fun grey(shade: Int = 0x90): SatellitePhoto.Picture = SatellitePhoto.Picture(
        SatellitePhoto.WIDTH, SatellitePhoto.HEIGHT,
        IntArray(SatellitePhoto.WIDTH * SatellitePhoto.HEIGHT) { (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade }
    )

    /** One round cloud, as thick as cloud gets, and clear sky everywhere else. */
    private fun oneCloud(lat: Double, lon: Double, degrees: Double): SatelliteClouds.Field {
        val w = 720
        val h = 360
        val cover = ByteArray(w * h)
        val la0 = Math.toRadians(lat)
        for (y in 0 until h) for (x in 0 until w) {
            val la = Math.toRadians(90.0 - (y + 0.5) * 0.5)
            val lo = Math.toRadians(-180.0 + (x + 0.5) * 0.5 - lon)
            val apart = Math.acos(
                (Math.sin(la) * Math.sin(la0) + Math.cos(la) * Math.cos(la0) * Math.cos(lo)).coerceIn(-1.0, 1.0)
            )
            if (Math.toDegrees(apart) < degrees) cover[y * w + x] = SatelliteClouds.THICKEST.toByte()
        }
        return SatelliteClouds.Field(w, h, cover)
    }

    // ------------------------------------------------------------ the rules

    /** All of its light under the sun, a floor of it on the terminator, and more than a matt ball's between. */
    @Test
    fun `the ground is lit by how high the sun stands, and never less than a floor`() {
        assertEquals(1.0, GlobeLight.sunlight(1.0), 1e-9)
        assertEquals(GlobeLight.FLOOR, GlobeLight.sunlight(0.0), 1e-9)
        assertEquals("in the twilight the floor still", GlobeLight.FLOOR, GlobeLight.sunlight(-0.3), 1e-9)
        assertEquals("no brighter than all of it", 1.0, GlobeLight.sunlight(1.2), 1e-9)
        var last = GlobeLight.sunlight(0.0)
        for (i in 1..100) {
            val now = GlobeLight.sunlight(i / 100.0)
            assertTrue("dimmer with the sun higher at $i", now > last)
            last = now
        }
        // The sun fifteen degrees up, thirty and sixty: seven tenths, more than four fifths, nearly all.
        fun up(degrees: Double) = GlobeLight.sunlight(Math.sin(Math.toRadians(degrees)))
        assertEquals(0.705, up(15.0), 0.005)
        assertEquals(0.824, up(30.0), 0.005)
        assertEquals(0.958, up(60.0), 0.005)
        // Brighter than the cosine a matt ball would have, all the way up.
        for (i in 1..99) assertTrue(GlobeLight.sunlight(i / 100.0) > i / 100.0)
    }

    /**
     * The numbers themselves. Each was arrived at by looking at the globe
     * drawn with it, and one changed in passing changes the look of every
     * face that wears the world: a change to one has to be meant.
     */
    @Test
    fun `the numbers are the ones that were looked at`() {
        assertEquals(0.40, GlobeLight.FLOOR, 0.0)
        assertEquals(0.5, GlobeLight.CURVE, 0.0)
        assertEquals(0x78B6FF, GlobeLight.AIR)
        assertEquals(0.035, GlobeLight.AIR_REACH, 0.0)
        assertEquals(0.55, GlobeLight.AIR_THICKEST, 0.0)
        assertEquals(3.0, GlobeLight.AIR_INWARD, 0.0)
        assertEquals(-0.08, GlobeLight.AIR_DARK, 0.0)
        assertEquals(0.17, GlobeLight.AIR_LIT, 0.0)
        assertEquals(0.012, GlobeLight.SHADOW_REACH, 0.0)
        assertEquals(0.45, GlobeLight.SHADOW_DEPTH, 0.0)
    }

    /** Thickest at the edge, the same on both sides of it, nothing in the middle, nothing far out, nothing at night. */
    @Test
    fun `the air is at the edge of the world where the sun is on it`() {
        fun share(rho: Double, cosZenith: Double) = (GlobeLight.air(rho, cosZenith) ushr 24) / 255.0
        // In full sun: thickest at the edge.
        assertEquals(GlobeLight.AIR_THICKEST, share(1.0, 1.0), 0.003)
        assertEquals("the edge is a seam", share(1.0 - 1e-9, 1.0), share(1.0 + 1e-9, 1.0), 0.005)
        // Thinning inward: next to none over the middle three quarters.
        assertEquals(0.0, share(0.0, 1.0), 0.0)
        assertTrue("air over the middle of the disc: ${share(0.75, 1.0)}", share(0.75, 1.0) < 0.03)
        assertTrue(share(0.9, 1.0) > share(0.75, 1.0) && share(0.98, 1.0) > share(0.9, 1.0))
        assertTrue("the air is not thick close to the edge: ${share(0.98, 1.0)}", share(0.98, 1.0) > 0.2)
        // Thinning outward, to nothing at its reach and beyond.
        var last = share(1.0, 1.0)
        for (i in 1..10) {
            val now = share(1.0 + GlobeLight.AIR_REACH * i / 10.0, 1.0)
            assertTrue("thicker further out at $i", now < last)
            last = now
        }
        assertEquals(0.0, share(1.0 + GlobeLight.AIR_REACH, 1.0), 0.0)
        assertEquals(0.0, share(1.5, 1.0), 0.0)
        // Quickly at first: half way out there is a quarter of it left, not a half.
        assertEquals(GlobeLight.AIR_THICKEST * 0.25, share(1.0 + GlobeLight.AIR_REACH / 2.0, 1.0), 0.004)
        // Lit by the sun: none at night, all of it by ten degrees up, and a little after sunset.
        assertEquals(0.0, share(1.0, -1.0), 0.0)
        assertEquals(0.0, share(1.0, GlobeLight.AIR_DARK), 0.0)
        assertEquals(share(1.0, 1.0), share(1.0, GlobeLight.AIR_LIT), 0.0)
        assertTrue("no air on the terminator, where the ground has just lost the sun", share(1.0, 0.0) > 0.05)
        // Coming up gently from nothing and gently to all of it: on the
        // terminator, a third of the way up, a quarter of the air is lit
        // and not a third.
        val risen = (0.0 - GlobeLight.AIR_DARK) / (GlobeLight.AIR_LIT - GlobeLight.AIR_DARK)
        assertEquals(0.32, risen, 1e-9)
        assertEquals(GlobeLight.AIR_THICKEST * 0.2417, share(1.0, 0.0), 0.004)
        assertTrue(share(1.0, 0.0) < share(1.0, 0.1) && share(1.0, 0.1) < share(1.0, 0.2))
        // And always the one colour, whatever there is of it.
        for (rho in listOf(0.0, 0.5, 0.97, 1.0, 1.02, 2.0)) for (c in listOf(-1.0, 0.0, 0.1, 1.0)) {
            assertEquals(GlobeLight.AIR, GlobeLight.air(rho, c) and 0xFFFFFF)
        }
        assertTrue("the air is not blue", Color.blue(GlobeLight.AIR) > Color.red(GlobeLight.AIR) + 80)
    }

    /** Straight away from the sun, by as much of the way to it as lies across the picture. */
    @Test
    fun `a cloud's shadow is thrown away from the sun`() {
        val right = GlobeLight.shadowOffset(1.0, 0.0)
        assertEquals(-GlobeLight.SHADOW_REACH, right[0], 1e-12)
        assertEquals(0.0, right[1], 1e-12)
        val below = GlobeLight.shadowOffset(0.0, 1.0)
        assertEquals(0.0, below[0], 1e-12)
        assertEquals(-GlobeLight.SHADOW_REACH, below[1], 1e-12)
        // The sun behind the eye: the shadow is under the cloud.
        val behind = GlobeLight.shadowOffset(0.0, 0.0)
        assertEquals(0.0, Math.hypot(behind[0], behind[1]), 0.0)
        // Half across the picture, half the reach.
        val half = GlobeLight.shadowOffset(-0.3, 0.4)
        assertEquals(GlobeLight.SHADOW_REACH * 0.5, Math.hypot(half[0], half[1]), 1e-12)
        assertTrue(half[0] > 0.0 && half[1] < 0.0)
    }

    /**
     * The globe asks how high the sun is by a short cut: the point's own
     * direction on the ball against the sun's. Held against the long way
     * round, by latitude and longitude, on every view.
     */
    @Test
    fun `the sun's height off the picture is the sun's height off the map`() {
        var worst = 0.0
        for (which in Hemisphere.View.entries) for (subLat in listOf(-23.4, -7.0, 0.0, 12.5, 23.4)) {
            for (sunAt in listOf(0.0, 37.5, 90.0, 180.0, 261.0)) {
                val toSun = Hemisphere.project(which, subLat, 0.0, 0.0, sunAt)
                assertEquals("the way to the sun is not a direction", 1.0, Math.sqrt(toSun[0] * toSun[0] + toSun[1] * toSun[1] + toSun[2] * toSun[2]), 1e-9)
                for (i in 0 until 400) {
                    val x = -0.98 + 1.96 * ((i * 37) % 400) / 399.0
                    val y = -0.98 + 1.96 * ((i * 91) % 400) / 399.0
                    val within = 1.0 - x * x - y * y
                    if (within < 0.0) continue
                    val place = Hemisphere.unproject(which, x, y, sunAt)!!
                    val long = Hemisphere.cosZenith(place[0], place[1], subLat)
                    val short = x * toSun[0] + y * toSun[1] + Math.sqrt(within) * toSun[2]
                    worst = maxOf(worst, Math.abs(long - short))
                }
            }
        }
        println("MEASURE the sun's height by the short cut against by latitude and longitude: worst difference %.2e".format(worst))
        assertTrue("the short cut is out by $worst", worst < 1e-9)
    }

    // ------------------------------------------------------------ the glass

    /**
     * The three views flat and lit, to be looked at: the world alone, and
     * the face as the app wears it, with its ring of hours, its sun and
     * moon and somebody's place marked on it.
     */
    @Test
    fun `the three views, flat and lit`() {
        val sky = cloud()
        for (which in Hemisphere.View.entries) {
            val flat = shoot(globe(at(12), which, sky, lit = false), "light-${which.key}-flat")
            val lit = shoot(globe(at(12), which, sky, lit = true), "light-${which.key}-lit")
            assertTrue("the ${which.key} view is drawn the same lit as flat", !flat.sameAs(lit))
            for (on in listOf(false, true)) {
                val worn = globe(at(12), which, sky, lit = on).apply {
                    hourRing = true
                    showSun = true
                    showMoon = true
                    moonlight = true
                    located = true
                    latitude = if (which == Hemisphere.View.SOUTH) -33.87 else 40.42
                    longitude = if (which == Hemisphere.View.SOUTH) 151.21 else -3.70
                }
                shoot(worn, "face-${which.key}-${if (on) "lit" else "flat"}")
            }
        }
        // And the two things nothing above looks at: the air against a
        // pale face, where it is a blue laid on white and not on the dark,
        // and the world opened as far as it opens, where the pictures of
        // the light and the air are stretched twice as far.
        for ((named, pale) in listOf("daylight" to ClockThemes.DAYLIGHT, "ivory" to ClockThemes.IVORY)) {
            shoot(globe(at(12), Hemisphere.View.GLOBE, sky).apply { theme = pale }, "light-globe-lit-on-$named")
        }
        shoot(globe(at(12), Hemisphere.View.GLOBE, sky).apply { zoom = Hemisphere.ZOOM_MAX }, "light-globe-lit-opened")
        assertTrue("the globe is not lit unless it is told to be", HemisphereView(context).lit)
    }

    /**
     * On the glass, a patch of ground keeps as much of its brightness as
     * the rule says for the sun's height over it — and so does cloud,
     * which is given the same light.
     *
     * Cloud over the whole world, and so the whole world in the shadow
     * of cloud: what shows of the ground through the thickest cloud,
     * which is a seventh of it, is the ground darkened by the shadow's
     * depth. The first count left that out and was two parts in a
     * hundred from the rule on average, five over the ice.
     */
    @Test
    fun `ground and cloud keep as much of their brightness as the sun's height gives them`() {
        val size = 1080
        val cover = SatelliteClouds.THICKEST / 255.0
        val shadowed = 1.0 - Math.round(GlobeLight.SHADOW_DEPTH * 255.0) / 255.0 * cover
        for (which in Hemisphere.View.entries) for (sky in listOf(null, overcast())) {
            val bare = glass(globe(at(12), which, null, lit = false, size = size))
            val flat = glass(globe(at(12), which, sky, lit = false, size = size))
            val lit = glass(globe(at(12), which, sky, lit = true, size = size))
            val toSun = sun(which, at(12))
            var n = 0
            var off = 0.0
            var worst = 0.0
            val kept = DoubleArray(10)
            val counted = IntArray(10)
            for (py in 0 until size step 2) for (px in 0 until size step 2) {
                // Well inside the edge, where there is next to no air, and
                // well into the day, where the rule is not at its steepest.
                if (rho(px, py, size) > 0.7) continue
                val up = sunOver(px, py, size, toSun) ?: continue
                if (up < 0.1) continue
                val was = brightness(flat.at(px, py))
                if (was < 60.0) continue
                val share = brightness(lit.at(px, py)) / was
                val wanted = if (sky == null) {
                    GlobeLight.sunlight(up)
                } else {
                    val ground = brightness(bare.at(px, py))
                    GlobeLight.sunlight(up) *
                        (ground * shadowed * (1.0 - cover) + 255.0 * cover) / (ground * (1.0 - cover) + 255.0 * cover)
                }
                n++
                off += Math.abs(share - wanted)
                worst = maxOf(worst, Math.abs(share - wanted))
                val bin = (up * 10).toInt().coerceIn(0, 9)
                kept[bin] += share
                counted[bin]++
            }
            val what = if (sky == null) "ground" else "cloud"
            val bins = (1..9).filter { counted[it] > 50 }
            println(
                "MEASURE $what on the ${which.key} view keeps, by the sun's height in tenths: " +
                    bins.joinToString(" ") { "$it:%.2f".format(kept[it] / counted[it]) } +
                    " | $n pixels, off the rule by %.3f on average, %.3f at worst".format(off / n, worst)
            )
            assertTrue("only $n pixels of $what to judge by on the ${which.key} view", n > 2_000)
            assertTrue("$what on the ${which.key} view is off the rule by ${off / n} on average", off / n < 0.008)
            assertTrue("$what on the ${which.key} view is off the rule by $worst somewhere", worst < 0.03)
            // And it is the sun's height that does it: brighter the higher.
            assertTrue("only ${bins.size} heights of the sun on the ${which.key} view", bins.size >= 3)
            for (i in 1 until bins.size) {
                assertTrue(
                    "$what is no brighter with the sun higher",
                    kept[bins[i]] / counted[bins[i]] > kept[bins[i - 1]] / counted[bins[i - 1]]
                )
            }
        }
    }

    /**
     * Where it is night the globe is, to the pixel, what it was: the
     * light is the day's, the air is lit by the sun or is not there, and
     * a shadow is thrown by sunlight. Every pixel of the picture is
     * asked, the world's and the sky's round it.
     *
     * But for the world's own outline, a ring two or three pixels wide, which
     * is counted apart. The world is cut to a circle with a soft edge,
     * and a pixel on that edge is partly the night and partly what was
     * drawn before the night — which is the day, lit now. A fringe of
     * the same kind and seven pixels wide was found by this test the
     * first time it ran, and that one was a mistake: see
     * HemisphereView.placeAt.
     */
    @Test
    fun `the night is drawn as it was`() {
        val size = 1080
        // Three pixels of the outline, as a share of the world's radius.
        val outline = 3.0 / radius(size)
        for (which in Hemisphere.View.entries) {
            val flat = glass(globe(at(12), which, cloud(), lit = false, size = size))
            val lit = glass(globe(at(12), which, cloud(), lit = true, size = size))
            val toSun = sun(which, at(12))
            var night = 0
            var changed = 0
            var onTheOutline = 0
            val where = StringBuilder()
            for (py in 0 until size) for (px in 0 until size) {
                // Well past the end of the twilight, which is a tenth.
                if (sunNear(px, py, size, toSun) > -0.13) continue
                night++
                if (flat.at(px, py) == lit.at(px, py)) continue
                if (Math.abs(rho(px, py, size) - 1.0) <= outline) {
                    onTheOutline++
                } else {
                    changed++
                    if (changed <= 4) {
                        where.append(
                            " $px,$py (%.4f out, the sun %.3f up, %08x to %08x)"
                                .format(rho(px, py, size), sunNear(px, py, size, toSun), flat.at(px, py), lit.at(px, py))
                        )
                    }
                }
            }
            println(
                "MEASURE the night on the ${which.key} view: $changed of $night pixels are other than they were," +
                    " and $onTheOutline more on the world's outline"
            )
            assertTrue("no night to judge by on the ${which.key} view: $night", night > 100_000)
            assertEquals("the night on the ${which.key} view was changed, at$where", 0, changed)
        }
    }

    /**
     * The air on the glass against the rule, across the lit edge: a
     * little way outside the world, where there was only the sky, and a
     * little way inside it, where the ground is under it. And none at the
     * dark edge. On every view, and with the sun turned to stand on the
     * other side, which the air must follow.
     */
    @Test
    fun `the air is on the glass at the lit edge and not at the dark one`() {
        val size = 1080
        val r = radius(size)
        val air = intArrayOf(Color.red(GlobeLight.AIR), Color.green(GlobeLight.AIR), Color.blue(GlobeLight.AIR))
        fun channels(c: Int) = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        for (which in Hemisphere.View.entries) for (sunAt in listOf(0.0, 180.0)) {
            val flat = glass(globe(at(12), which, null, lit = false, sunAt = sunAt, size = size))
            val lit = glass(globe(at(12), which, null, lit = true, sunAt = sunAt, size = size))
            val toSun = sun(which, at(12), sunAt)
            val along = Math.hypot(toSun[0], toSun[1])
            val ux = toSun[0] / along
            val uy = toSun[1] / along
            val said = StringBuilder()
            var worstOut = 0.0
            var worstIn = 0
            // Outside the lit edge: the sky was dark there, and what has
            // been added to it is the air's colour by the rule's share.
            for (far in listOf(1.010, 1.014, 1.018, 1.024, 1.030)) {
                val px = (size / 2.0 + ux * far * r).toInt()
                val py = (size / 2.0 + uy * far * r).toInt()
                val was = channels(flat.at(px, py))
                val now = channels(lit.at(px, py))
                assertTrue("the sky was not dark at $far: ${was.toList()}", was.max() < 60)
                val wanted = (GlobeLight.air(rho(px, py, size), sunNear(px, py, size, toSun)) ushr 24) / 255.0
                val measured = (now[2] - was[2]) / (air[2] - was[2]).toDouble()
                said.append(" %.3f:%.3f/%.3f".format(far, measured, wanted))
                worstOut = maxOf(worstOut, Math.abs(measured - wanted))
                // And it is the air's colour that was added, not a grey.
                for (c in 0..2) {
                    val mixed = was[c] + (air[c] - was[c]) * measured
                    assertEquals("the colour added at $far on the ${which.key} view", mixed, now[c].toDouble(), 3.0)
                }
            }
            // Past its reach: the sky as it was.
            for (far in listOf(1.0 + GlobeLight.AIR_REACH * 1.15, 1.06, 1.1)) {
                val px = (size / 2.0 + ux * far * r).toInt()
                val py = (size / 2.0 + uy * far * r).toInt()
                assertEquals("air $far out on the ${which.key} view", flat.at(px, py), lit.at(px, py))
            }
            // Inside the lit edge, and right up to it: the ground in the
            // sun's light, and the air over that. Neither lets go of the
            // last pixels of the world, which is where a picture
            // stretched over a disc comes to its own corners.
            for (far in listOf(0.90, 0.94, 0.965, 0.98, 0.985, 0.99)) {
                val px = (size / 2.0 + ux * far * r).toInt()
                val py = (size / 2.0 + uy * far * r).toInt()
                val was = channels(flat.at(px, py))
                val now = channels(lit.at(px, py))
                val up = sunOver(px, py, size, toSun)!!
                val share = (GlobeLight.air(rho(px, py, size), up) ushr 24) / 255.0
                var offHere = 0
                for (c in 0..2) {
                    val wanted = was[c] * GlobeLight.sunlight(up) * (1.0 - share) + air[c] * share
                    offHere = maxOf(offHere, Math.abs(Math.round(wanted).toInt() - now[c]))
                }
                worstIn = maxOf(worstIn, offHere)
                said.append(" %.3f:%s off %d".format(far, now.toList(), offHere))
            }
            // And round from the sun, where it stands lower over the edge
            // and the ground has less of its light: the same account, to
            // the same last pixels, over ground of one grey. Straight
            // towards the sun the ground at the edge has nearly all of
            // its light and looks the same with none taken off — which
            // is how a light that let go of the world's last pixels got
            // past the lines above when it was tried. A pale grey, since
            // light taken off dark ground is not much to find.
            val greyFlat = glass(globe(at(12), which, null, lit = false, sunAt = sunAt, size = size, ground = grey(0xF0)))
            val greyLit = glass(globe(at(12), which, null, lit = true, sunAt = sunAt, size = size, ground = grey(0xF0)))
            var worstRound = 0
            var round = 0
            // At a great many places, because whether the fault shows at
            // one of them is a matter of where it falls between the
            // pixels of the picture that is stretched: counted at six
            // bearings, it showed on the ball and on neither pole.
            val bearings = (0..16).flatMap { listOf(40.0 + it * 2.5, -40.0 - it * 2.5) }
            for (bearing in bearings) {
                val a = Math.toRadians(bearing)
                val vx = ux * Math.cos(a) - uy * Math.sin(a)
                val vy = ux * Math.sin(a) + uy * Math.cos(a)
                for (far in listOf(0.97, 0.985, 0.9875, 0.99)) {
                    val px = (size / 2.0 + vx * far * r).toInt()
                    val py = (size / 2.0 + vy * far * r).toInt()
                    val up = sunOver(px, py, size, toSun)!!
                    // Clear of the twilight, where the night is laid over all of this.
                    if (up < 0.08) continue
                    val was = channels(greyFlat.at(px, py))
                    val now = channels(greyLit.at(px, py))
                    val share = (GlobeLight.air(rho(px, py, size), up) ushr 24) / 255.0
                    for (c in 0..2) {
                        val wanted = was[c] * GlobeLight.sunlight(up) * (1.0 - share) + air[c] * share
                        worstRound = maxOf(worstRound, Math.abs(Math.round(wanted).toInt() - now[c]))
                    }
                    round++
                }
            }
            println(
                "MEASURE the air on the ${which.key} view with the sun at $sunAt, outside as measured/ruled:$said" +
                    " | off by %.3f of a share outside, $worstIn levels inside, and $worstRound at $round places round the edge".format(worstOut)
            )
            assertTrue("only $round places round the edge of the ${which.key} view are in daylight", round >= 100)
            // Two shades, as it is. With the light letting go of the last
            // pixels it is five from over either pole and ten on the ball.
            assertTrue("the light at the edge of the ${which.key} view, round from the sun, is off the rule by $worstRound levels", worstRound <= 3)
            assertTrue("the air outside the ${which.key} view is off the rule by $worstOut", worstOut < 0.025)
            assertTrue("the air over the edge of the ${which.key} view is off the rule by $worstIn levels", worstIn <= 4)
            // The dark edge, inside and out: what it was.
            for (far in listOf(0.90, 0.95, 0.975, 1.010, 1.014, 1.02, 1.03, 1.06)) {
                val px = (size / 2.0 - ux * far * r).toInt()
                val py = (size / 2.0 - uy * far * r).toInt()
                assertEquals(
                    "air at the dark edge of the ${which.key} view, $far out, with the sun at $sunAt",
                    flat.at(px, py), lit.at(px, py)
                )
            }
            // And all the way round, just outside: air where the rule has
            // any to speak of, and none where the rule has none.
            var there = 0
            var missing = 0
            var uncalled = 0
            for (step in 0 until 720) {
                val angle = Math.toRadians(step / 2.0)
                val px = (size / 2.0 + Math.cos(angle) * 1.014 * r).toInt()
                val py = (size / 2.0 + Math.sin(angle) * 1.014 * r).toInt()
                val up = sunNear(px, py, size, toSun)
                val wanted = (GlobeLight.air(rho(px, py, size), up) ushr 24) / 255.0
                val changed = lit.at(px, py) != flat.at(px, py)
                if (changed) there++
                if (wanted > 0.04 && !changed) missing++
                if (up < GlobeLight.AIR_DARK - 0.04 && changed) uncalled++
            }
            println("MEASURE the air round the ${which.key} view with the sun at $sunAt: on $there of 720 half-degrees of the edge")
            assertEquals("places round the ${which.key} view the rule lights and the glass does not", 0, missing)
            assertEquals("places round the ${which.key} view lit that the rule leaves dark", 0, uncalled)
            assertTrue("the air is on $there half-degrees of the edge", there in 200..520)
        }
    }

    /**
     * The light and the air are worked out once for where the sun stands
     * and kept, and worked out again when it stands somewhere else: the
     * sun pinned to another side, another season, another view. One
     * globe taken through those is each time, to the pixel, a globe that
     * was made that way.
     */
    @Test
    fun `the light goes with the sun on a globe that is kept`() {
        val size = 720
        val sky = cloud()
        val kept = globe(at(12), Hemisphere.View.GLOBE, sky, size = size)
        fun same(what: String, made: HemisphereView, within: Int = 0) {
            val a = glass(kept)
            val b = glass(made)
            var differing = 0
            var worst = 0
            for (i in a.pixels.indices) {
                if (a.pixels[i] == b.pixels[i]) continue
                differing++
                worst = maxOf(
                    worst,
                    Math.abs(Color.red(a.pixels[i]) - Color.red(b.pixels[i])),
                    Math.abs(Color.green(a.pixels[i]) - Color.green(b.pixels[i])),
                    Math.abs(Color.blue(a.pixels[i]) - Color.blue(b.pixels[i]))
                )
            }
            println("MEASURE a globe that was kept, $what: $differing pixels are not the ones of a globe made that way, by $worst shades at most")
            assertTrue("$what: the globe that was kept is $worst shades from the globe made that way", worst <= within)
        }
        glass(kept)
        assertEquals("the light and the air, once each", 2, kept.lightingsForTest())
        glass(kept)
        assertEquals("worked out again for a sun that has not moved", 2, kept.lightingsForTest())
        kept.sunAt = 180.0
        same("the sun pinned to the other side", globe(at(12), Hemisphere.View.GLOBE, sky, sunAt = 180.0, size = size))
        assertEquals(4, kept.lightingsForTest())
        // Five months on the sun is over the other half of the world.
        val spring = at(12) + 150L * DailyImageStore.DAY_MS
        assertTrue(Hemisphere.subsolar(at(12))[0] < -15.0 && Hemisphere.subsolar(spring)[0] > 8.0)
        kept.atMs = spring
        same("another season", globe(spring, Hemisphere.View.GLOBE, sky, sunAt = 180.0, size = size))
        assertEquals(6, kept.lightingsForTest())
        kept.view = Hemisphere.View.SOUTH
        same("another view", globe(spring, Hemisphere.View.SOUTH, sky, sunAt = 180.0, size = size))
        assertEquals(8, kept.lightingsForTest())
        // Ten minutes later the sun is over other ground and no higher to
        // speak of: the world has turned under a light that has not
        // moved. The light is kept until the sun has gone a quarter of a
        // degree north or south, so it may be a shade or two from the
        // light of a globe made at that minute, and no more.
        kept.atMs = spring + 600_000L
        same("ten minutes later", globe(spring + 600_000L, Hemisphere.View.SOUTH, sky, sunAt = 180.0, size = size), within = 3)
        assertEquals("worked out again for a sun that has only gone round", 8, kept.lightingsForTest())
    }

    /**
     * Off the screen a face gives back what it was holding, the light and
     * the air with the rest. Put back it works them out again and is the
     * same picture — and does not reach for the ones it gave back.
     */
    @Test
    fun `a globe taken off the screen and put back is lit again`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val frame = android.widget.FrameLayout(activity)
        activity.setContentView(frame)
        val world = globe(at(12), Hemisphere.View.NORTH, cloud(), size = 720)
        frame.addView(world, 720, 720)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("the globe is not on the screen", world.isAttachedToWindow)
        assertEquals(720, world.width)
        val before = glass(world)
        val worked = world.lightingsForTest()
        assertTrue("nothing was worked out for the first picture", worked >= 2)
        frame.removeView(world)
        assertTrue("the globe is still on the screen", !world.isAttachedToWindow)
        val after = glass(world)
        assertEquals("the light and the air were not worked out again", worked + 2, world.lightingsForTest())
        assertTrue("the globe put back is another picture", before.pixels.contentEquals(after.pixels))
    }

    /**
     * One round cloud on the daylit side, and what it does to the ground
     * round it: darker on the side away from the sun, by about as far as
     * the rule throws a shadow, and on no other side. On every view; at
     * hours that have the turning views turned three different ways,
     * because the shadow is thrown in the frame of the glass, where the
     * sun stands still, and the cloud is drawn in the world's, which
     * turns; and with the sun put on the other side. Drawn flat, the same
     * cloud darkens nothing, which is how it was a veil.
     *
     * Over a world of one grey — see [grey]. Over the real one the first
     * of these was a cloud over the sea, most of which is too dark for a
     * shadow on it to be told from no shadow, and the darkened ground
     * that could be counted was the half of it that lay on a shore.
     */
    @Test
    fun `a cloud darkens the ground on the side away from the sun`() {
        val size = 1620
        val r = radius(size)
        data class Case(val which: Hemisphere.View, val hour: Int, val sunAt: Double)
        val cases = listOf(
            Case(Hemisphere.View.GLOBE, 12, 0.0),
            Case(Hemisphere.View.GLOBE, 12, 180.0),
            Case(Hemisphere.View.GLOBE, 5, 0.0),
            Case(Hemisphere.View.NORTH, 3, 0.0),
            Case(Hemisphere.View.NORTH, 12, 0.0),
            Case(Hemisphere.View.NORTH, 19, 90.0),
            Case(Hemisphere.View.SOUTH, 3, 0.0),
            Case(Hemisphere.View.SOUTH, 12, 270.0),
            Case(Hemisphere.View.SOUTH, 19, 0.0)
        )
        for (case in cases) {
            val (which, hour, sunAt) = case
            val named = "the ${which.key} view at $hour h with the sun at ${sunAt.toInt()}"
            val toSun = sun(which, at(hour), sunAt)
            val along = Math.hypot(toSun[0], toSun[1])
            val ux = toSun[0] / along
            val uy = toSun[1] / along
            // A place six tenths of the way from the middle of the picture
            // to its edge, towards the sun: in daylight on all three.
            val place = Hemisphere.unproject(which, ux * 0.6, uy * 0.6, sunAt)!!
            val longitude = Hemisphere.wrap(Hemisphere.subsolar(at(hour))[1] + place[1])
            val one = oneCloud(place[0], longitude, 4.0)
            val bare = glass(globe(at(hour), which, null, lit = true, sunAt = sunAt, size = size, ground = grey()))
            val clouded = glass(globe(at(hour), which, one, lit = true, sunAt = sunAt, size = size, ground = grey()))
            var whiter = 0
            var darker = 0
            var cloudX = 0.0
            var cloudY = 0.0
            var shadowX = 0.0
            var shadowY = 0.0
            var deepest = 1.0
            var whitest = 1.0
            var ground = 0.0
            for (py in 0 until size) for (px in 0 until size) {
                val was = brightness(bare.at(px, py))
                if (was < 12.0) continue
                val kept = brightness(clouded.at(px, py)) / was
                if (kept > 1.08) {
                    whiter++
                    cloudX += px
                    cloudY += py
                    ground += was
                    whitest = maxOf(whitest, kept)
                } else if (kept < 0.97) {
                    darker++
                    shadowX += px
                    shadowY += py
                    deepest = minOf(deepest, kept)
                }
            }
            Bitmap.createBitmap(clouded.pixels, size, size, Bitmap.Config.ARGB_8888).let { picture ->
                val name = "shadow-${which.key}-$hour-${sunAt.toInt()}"
                File(outDir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                println("SHOT $name ${size}x$size")
            }
            assertTrue("no cloud on $named: $whiter pixels", whiter > 2_000)
            // The cloud is where it was put.
            val wantedX = size / 2.0 + ux * 0.6 * r
            val wantedY = size / 2.0 + uy * 0.6 * r
            assertEquals("the cloud on $named is not where it was put, across", wantedX, cloudX / whiter, 0.012 * r)
            assertEquals("the cloud on $named is not where it was put, down", wantedY, cloudY / whiter, 0.012 * r)
            assertTrue("the cloud on $named darkens nothing: $darker pixels", darker > 150)
            val thrownX = shadowX / darker - cloudX / whiter
            val thrownY = shadowY / darker - cloudY / whiter
            val awayFromSun = -(thrownX * ux + thrownY * uy)
            val sideways = Math.abs(-thrownX * uy + thrownY * ux)
            // Along the line through the cloud's middle that points at the
            // sun: how far the darkened ground runs beyond the cloud on
            // the far side, and that there is none on the near one.
            var run = 0
            var nearSide = 0
            for (t in 1..(0.25 * r).toInt()) {
                val farX = (cloudX / whiter - ux * t).toInt()
                val farY = (cloudY / whiter - uy * t).toInt()
                val nearX = (cloudX / whiter + ux * t).toInt()
                val nearY = (cloudY / whiter + uy * t).toInt()
                fun kept(x: Int, y: Int): Double {
                    val was = brightness(bare.at(x, y))
                    return if (was < 12.0) 1.0 else brightness(clouded.at(x, y)) / was
                }
                if (kept(farX, farY) < 0.97) run++
                if (kept(nearX, nearY) < 0.97) nearSide++
            }
            val thrown = GlobeLight.SHADOW_REACH * along * r
            println(
                ("MEASURE one cloud on $named: $whiter pixels whiter over ground of brightness %.0f, $darker darker, the deepest keeping %.2f;" +
                    " the darker lie %.1f pixels from the cloud's middle away from the sun and %.1f to one side;" +
                    " on the line to the sun the shadow runs %d pixels beyond the cloud where the rule throws it %.1f")
                    .format(ground / whiter, deepest, awayFromSun, sideways, run, thrown)
            )
            assertTrue("the darker ground on $named is not away from the sun: $awayFromSun", awayFromSun > 0.02 * r)
            assertTrue("the darker ground on $named is off to one side: $sideways against $awayFromSun", sideways < awayFromSun / 4)
            assertEquals("ground darkened on the sun's side of the cloud on $named", 0, nearSide)
            assertTrue("the shadow on $named runs $run pixels where the rule throws it $thrown", run >= thrown * 0.5 && run <= thrown * 1.6)
            // No darker than the rule's depth under the thickest cloud, and not far short of it.
            val depth = GlobeLight.SHADOW_DEPTH * SatelliteClouds.THICKEST / 255.0
            assertTrue("the shadow on $named keeps $deepest of the ground's light", deepest > 1.0 - depth - 0.03)
            assertTrue("the shadow on $named is faint: it keeps $deepest", deepest < 1.0 - depth * 0.5)
            // And it is under the cloud, not over it: the cloud is as white
            // as cloud that thick is over ground that grey, in the same sun.
            val white = (0x90 * (255 - SatelliteClouds.THICKEST) + 255 * SatelliteClouds.THICKEST) / 255.0 / 0x90
            assertEquals("the cloud on $named is not as white as it was", white, whitest, 0.04)
            // Flat, the same cloud only ever whitens.
            val flatBare = glass(globe(at(hour), which, null, lit = false, sunAt = sunAt, size = size, ground = grey()))
            val flatClouded = glass(globe(at(hour), which, one, lit = false, sunAt = sunAt, size = size, ground = grey()))
            var flatDarker = 0
            for (i in flatBare.pixels.indices) {
                val was = brightness(flatBare.pixels[i])
                if (was >= 12.0 && brightness(flatClouded.pixels[i]) / was < 0.97) flatDarker++
            }
            assertEquals("the veil as it was darkened the ground on $named", 0, flatDarker)
        }
    }
}
