package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The sun's mark, which goes when the world is opened out, and the
 * moon's, which can be taken hold of.
 *
 * Two things asked for of the turning world. Leaned in on, the earth
 * reaches for the edge of the screen, and the sun — nailed just outside
 * it — was left standing half off the glass: it fades with the ring of
 * hours now, which is the thing it was the noon of. And the moon's mark
 * is a hand. The world goes round once a day and the moon's mark once a
 * month, so carrying the mark round the rim winds the clock by days, the
 * world turning once under it for each: the way back through a week that
 * is not seven turns of the planet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoonInHandTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val side = 1000
    private val dayMs = 86_400_000L

    /** The black behind the world, as this face paints it whatever the theme. */
    private val space = 0xFF05070D.toInt()
    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Noon over Greenwich on the 11th of November 2026: a thin moon, two days old. */
    private fun noon(): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.NOVEMBER, 11, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun globe(
        zoom: Float = Hemisphere.ZOOM_MIN,
        view: Hemisphere.View = Hemisphere.View.NORTH,
        sun: Boolean = true,
        moon: Boolean = true,
        ring: Boolean = true,
        at: Long = noon(),
        sunAt: Double = 0.0
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        this.view = view
        this.sunAt = sunAt
        latitude = 40.4
        longitude = -3.7
        located = false
        showSun = sun
        showMoon = moon
        hourRing = ring
        this.zoom = zoom
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, side, side)
    }

    private fun shoot(v: View): Bitmap =
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun save(bitmap: Bitmap, name: String) {
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("SHOT $name ${bitmap.width}x${bitmap.height}")
    }

    private fun touch(v: HemisphereView, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    /** The point [degrees] further round the middle than ([x], [y]), anticlockwise. */
    private fun round(x: Float, y: Float, degrees: Double): Pair<Float, Float> {
        val c = side / 2f
        val radius = hypot(x - c, y - c)
        val angle = atan2((c - y).toDouble(), (x - c).toDouble()) + Math.toRadians(degrees)
        return (c + radius * cos(angle).toFloat()) to (c - radius * sin(angle).toFloat())
    }

    /** Carries a finger from ([x], [y]) round the middle by [degrees], a degree at a time. */
    private fun carry(v: HemisphereView, x: Float, y: Float, degrees: Double): Pair<Float, Float> {
        val steps = abs(degrees).toInt().coerceAtLeast(1)
        var last = x to y
        for (i in 1..steps) {
            last = round(x, y, degrees * i / steps)
            touch(v, MotionEvent.ACTION_MOVE, last.first, last.second)
        }
        return last
    }

    // ---------------------------------------------------------- the sun

    /** The sun's mark goes with the ring, and is gone long before the world is fully open. */
    @Test
    fun `the sun's mark fades as the world is opened out`() {
        assertEquals(1f, Hemisphere.sunFade(Hemisphere.ZOOM_MIN), 1e-6f)
        assertEquals(0f, Hemisphere.sunFade(Hemisphere.ZOOM_MAX), 1e-6f)
        var last = 1f
        var z = Hemisphere.ZOOM_MIN
        while (z <= Hemisphere.ZOOM_MAX) {
            val now = Hemisphere.sunFade(z)
            assertTrue("the sun comes back at zoom $z", now <= last + 1e-6f)
            assertEquals("the sun and the ring part company at zoom $z", Hemisphere.ringFade(z), now, 1e-6f)
            last = now
            z += 0.01f
        }
        // Gone by a third of the way in, on any view and with the ring
        // on or off.
        assertEquals(0f, Hemisphere.sunFade(1.33f), 1e-6f)
    }

    /** How yellow the picture is where the sun's mark stands: nought for none of it. */
    private fun sunAt(v: HemisphereView): Int {
        val map = shoot(v)
        val world = side * Hemisphere.worldRadius(
            v.zoom, v.hourRing && Hemisphere.hasRimScale(v.view),
            moonOutside = v.showMoon && Hemisphere.hasRimScale(v.view)
        )
        val at = side * Hemisphere.markRadius(world / side, v.hourRing && Hemisphere.hasRimScale(v.view))
        // The sun is nailed to the right of the world: three o'clock.
        val x = (side / 2f + at).toInt().coerceAtMost(side - 1)
        val p = map.getPixel(x, side / 2)
        return (p shr 16 and 0xFF) - (p and 0xFF)
    }

    /** And in the picture: there at rest, fainter a little way in, and not there after that. */
    @Test
    fun `the sun is on the glass at rest and gone when leaned in`() {
        for (view in Hemisphere.View.entries) {
            for (ring in listOf(true, false)) {
                val name = "$view, ring $ring"
                val whole = sunAt(globe(view = view, ring = ring))
                assertTrue("$name: no sun at rest ($whole)", whole > 150)
                val going = sunAt(globe(zoom = 1.15f, view = view, ring = ring))
                assertTrue("$name: the sun has not begun to go at 1.15 ($going of $whole)", going in 30 until whole - 30)
                for (zoom in listOf(1.3f, 1.5f, Hemisphere.ZOOM_MAX)) {
                    val gone = sunAt(globe(zoom = zoom, view = view, ring = ring))
                    assertTrue("$name: the sun is still there at $zoom ($gone)", gone < 12)
                }
            }
        }
        // Switched off, it was never there.
        assertTrue(sunAt(globe(sun = false)) < 12)
        save(shoot(globe()), "world-marks-at-rest")
        save(shoot(globe(zoom = Hemisphere.ZOOM_MAX)), "world-marks-leaned-in")
    }

    /**
     * With no moon out there either, the opened world has the whole of
     * the glass.
     *
     * The last twentieth was kept for the sun. The sun is gone by the
     * time the world gets there, so it is kept only for the moon's mark,
     * on the views that have a rim to put one on.
     */
    @Test
    fun `an opened world keeps room only for the moon`() {
        fun edge(v: HemisphereView): Boolean {
            // Just inside the right-hand edge of the glass, on the
            // equator's level: world, or the black behind it.
            val p = shoot(v).getPixel(side - 12, side / 2)
            return p != space
        }
        assertFalse("the moon was left no room", edge(globe(zoom = Hemisphere.ZOOM_MAX)))
        assertTrue(
            "no moon, and the world still stops short",
            edge(globe(zoom = Hemisphere.ZOOM_MAX, moon = false))
        )
        assertTrue(
            "a view with no rim, and the world still stops short",
            edge(globe(zoom = Hemisphere.ZOOM_MAX, view = Hemisphere.View.GLOBE))
        )
        // And at rest nothing has moved: the world is the size it was.
        for (moon in listOf(true, false)) {
            assertEquals(
                Hemisphere.WORLD_RINGED,
                Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed = true, moonOutside = moon), 1e-6f
            )
            assertEquals(
                Hemisphere.WORLD_BARE,
                Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed = false, moonOutside = moon), 1e-6f
            )
        }
    }

    /**
     * The two marks stand outside the ring, or just off the world,
     * whichever is further out — and never jump from one to the other.
     *
     * The moon used to: outside the ring until the ring had faded, and
     * then in one frame a twentieth of the screen further in.
     */
    @Test
    fun `the marks never jump as the world is opened`() {
        assertEquals(
            Hemisphere.WORLD_RINGED * 1.22f,
            Hemisphere.markRadius(Hemisphere.WORLD_RINGED, ringed = true), 1e-6f
        )
        assertEquals(
            Hemisphere.WORLD_BARE * 1.06f,
            Hemisphere.markRadius(Hemisphere.WORLD_BARE, ringed = false), 1e-6f
        )
        for (ringed in listOf(true, false)) {
            var before = Hemisphere.markRadius(
                Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed), ringed
            )
            var z = Hemisphere.ZOOM_MIN
            while (z <= Hemisphere.ZOOM_MAX) {
                val world = Hemisphere.worldRadius(z, ringed)
                val at = Hemisphere.markRadius(world, ringed)
                assertTrue("ring $ringed: the marks are on the world at zoom $z", at >= world * 1.06f - 1e-6f)
                assertTrue("ring $ringed: the marks jump at zoom $z: $before to $at", abs(at - before) < 0.004f)
                assertTrue("ring $ringed: the marks come in as the world opens at $z", at >= before - 1e-6f)
                before = at
                z += 0.005f
            }
            // And still on the glass at the end of it.
            assertTrue(before + Hemisphere.WORLD_FULL * 0.042f < 0.5f)
        }
        // In the picture too: the moon's mark at every zoom is where the
        // rule says, and the view's own account of it agrees.
        var z = Hemisphere.ZOOM_MIN
        var last = -1f
        while (z <= Hemisphere.ZOOM_MAX) {
            val v = globe(zoom = z)
            val mark = v.moonMarkForTest()
            val out = hypot(mark.x - side / 2f, mark.y - side / 2f)
            if (last >= 0f) assertTrue("the moon's mark jumps at zoom $z", abs(out - last) < side * 0.012f)
            last = out
            z += 0.02f
        }
    }

    // ------------------------------------------------- winding by the moon

    /**
     * Winding the clock until the moon stands somewhere puts it there,
     * the nearer way round, and a day is a thumb's width.
     */
    @Test
    fun `the clock can be wound to put the moon anywhere on the rim`() {
        val starts = listOf(noon(), noon() + 9 * dayMs, noon() - 200 * dayMs, noon() + 3_333 * dayMs)
        for (view in listOf(Hemisphere.View.NORTH, Hemisphere.View.SOUTH)) {
            for (at in starts) {
                val from = Hemisphere.moonBearing(view, at)
                for (by in listOf(-170.0, -90.0, -12.0, -1.0, 0.0, 0.5, 12.0, 45.0, 170.0)) {
                    val wound = Hemisphere.windMoonTo(view, at, from + by)
                    val landed = Hemisphere.moonBearing(view, at + wound)
                    assertEquals(
                        "$view: carried $by° and landed off the mark",
                        0.0, Hemisphere.wrap(landed - (from + by)), 0.01
                    )
                    // The nearer way: never more than half a month.
                    assertTrue("$view: $by° took ${wound / dayMs} days", abs(wound) < 16.5 * dayMs)
                    if (by == 0.0) assertEquals(0L, wound)
                    // Anticlockwise is later from over the north pole and
                    // earlier from over the south.
                    val later = if (view == Hemisphere.View.NORTH) by > 0 else by < 0
                    if (by != 0.0) assertEquals("$view: $by° went the wrong way in time", later, wound > 0)
                }
            }
        }
        // A day is about twelve degrees: a thirtieth of the way round.
        val day = Hemisphere.windMoonTo(Hemisphere.View.NORTH, noon(), Hemisphere.moonBearing(Hemisphere.View.NORTH, noon()) + Hemisphere.MOON_DEG_PER_DAY)
        assertEquals(1.0, day.toDouble() / dayMs, 0.15)
        assertEquals(12.19, Hemisphere.MOON_DEG_PER_DAY, 0.01)
    }

    /**
     * A finger on the moon's mark carries it, and the clock is wound to
     * wherever that puts the moon: days, where a finger on the world
     * winds hours.
     */
    @Test
    fun `carrying the moon winds the clock by days`() {
        for (view in listOf(Hemisphere.View.NORTH, Hemisphere.View.SOUTH)) {
            for (zoom in listOf(Hemisphere.ZOOM_MIN, Hemisphere.ZOOM_MAX)) {
                val v = globe(zoom = zoom, view = view)
                val mark = v.moonMarkForTest()
                touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
                assertEquals("taking hold of the moon moved the clock", 0L, v.woundForTest())
                val finger = carry(v, mark.x, mark.y, 61.0)
                // Five days, near enough: sixty-one degrees at twelve and
                // a bit a day. Forwards from the north, backwards from
                // the south, where the rim runs the other way.
                val days = v.woundForTest().toDouble() / dayMs
                val expected = if (view == Hemisphere.View.NORTH) 5.0 else -5.0
                assertEquals("$view at zoom $zoom: sixty-one degrees of moon", expected, days, 0.75)
                // And the mark is under the finger.
                val now = v.moonMarkForTest()
                assertTrue(
                    "$view at zoom $zoom: the moon is not under the finger that carries it",
                    hypot(now.x - finger.first, now.y - finger.second) < 2f
                )
                // Carried back, the clock is back.
                carry(v, finger.first, finger.second, -61.0)
                assertTrue(
                    "$view at zoom $zoom: carried back, the clock is ${v.woundForTest()} ms out",
                    abs(v.woundForTest()) < 60_000L
                )
            }
        }
        // The same sixty-one degrees with a finger on the world is four
        // hours and four minutes.
        val world = globe()
        touch(world, MotionEvent.ACTION_DOWN, 700f, 500f)
        carry(world, 700f, 500f, 61.0)
        assertEquals(61.0 * 4 / 60, abs(world.woundForTest()).toDouble() / 3_600_000L, 0.2)
    }

    /**
     * And the same wherever the sun has been nailed: the rim is measured
     * from the sun, and the sun can be put at the top of the glass.
     */
    @Test
    fun `the moon is carried the same wherever the sun is nailed`() {
        for (nailed in listOf(90.0, 180.0, -45.0)) {
            val v = globe(sunAt = nailed)
            val mark = v.moonMarkForTest()
            // A quarter of a turn round from where it is with the sun on
            // the right, or a half, or an eighth the other way.
            val plain = globe().moonMarkForTest()
            val moved = round(plain.x, plain.y, nailed)
            assertTrue(
                "with the sun at $nailed° the moon's mark did not go round with it",
                hypot(mark.x - moved.first, mark.y - moved.second) < 2f
            )
            touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
            val finger = carry(v, mark.x, mark.y, 61.0)
            assertEquals("sun at $nailed°", 5.0, v.woundForTest().toDouble() / dayMs, 0.75)
            val now = v.moonMarkForTest()
            assertTrue(
                "sun at $nailed°: the moon is not under the finger",
                hypot(now.x - finger.first, now.y - finger.second) < 2f
            )
        }
    }

    /** Taken hold of off its middle, the moon does not jump to the finger. */
    @Test
    fun `the moon is carried from where it was taken hold of`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        // Thirty pixels round from its middle: well inside its reach.
        val off = round(mark.x, mark.y, 4.0)
        assertTrue(hypot(off.first - mark.x, off.second - mark.y) > 20f)
        touch(v, MotionEvent.ACTION_DOWN, off.first, off.second)
        touch(v, MotionEvent.ACTION_MOVE, off.first, off.second)
        assertTrue(
            "the moon jumped to the finger: ${v.woundForTest()} ms",
            abs(v.woundForTest()) < 60_000L
        )
        // And from there twelve degrees is still a day.
        carry(v, off.first, off.second, Hemisphere.MOON_DEG_PER_DAY)
        assertEquals(1.0, v.woundForTest().toDouble() / dayMs, 0.15)
    }

    /**
     * The moon's reach: a centimetre round its mark, which laps over the
     * edge of the world and stops short of the rest of the margin.
     */
    @Test
    fun `the moon is held from a finger's width away and no further`() {
        fun held(x: Float, y: Float): Boolean {
            val v = globe()
            touch(v, MotionEvent.ACTION_DOWN, x, y)
            carry(v, x, y, 12.0)
            // Days if it was the moon; the best part of an hour if it
            // was the world; nothing if it was neither.
            return abs(v.woundForTest()) > dayMs / 2
        }
        val v = globe()
        val mark = v.moonMarkForTest()
        val c = side / 2f
        val out = hypot(mark.x - c, mark.y - c)
        fun along(share: Float) = (c + (mark.x - c) * share) to (c + (mark.y - c) * share)
        assertTrue("the middle of the mark", held(mark.x, mark.y))
        // Sixty pixels towards the world and sixty away: inside the reach.
        for (by in listOf(-60f, 60f)) {
            val (x, y) = along((out + by) / out)
            assertTrue("$by px along the radius", held(x, y))
        }
        // Eighty: outside it. Towards the world that is the world's.
        val (ix, iy) = along((out - 80f) / out)
        val inner = globe()
        touch(inner, MotionEvent.ACTION_DOWN, ix, iy)
        carry(inner, ix, iy, 12.0)
        assertEquals(
            "eighty pixels in from the moon is the world, and winds hours",
            48.0, abs(inner.woundForTest()).toDouble() / 60_000L, 6.0
        )
        // And round the rim from it, the margin is still the page's.
        val (rx, ry) = round(mark.x, mark.y, 25.0)
        val margin = globe()
        touch(margin, MotionEvent.ACTION_DOWN, rx, ry)
        carry(margin, rx, ry, 12.0)
        assertEquals("the margin beside the moon wound the clock", 0L, margin.woundForTest())
        // Opened out, the world comes up under the moon and the reach
        // laps over its edge. There the moon has it: forty pixels in
        // from the edge is still the moon, and sixty is the world.
        val open = globe(zoom = Hemisphere.ZOOM_MAX)
        val openMark = open.moonMarkForTest()
        val openOut = hypot(openMark.x - c, openMark.y - c)
        val edge = side * Hemisphere.worldRadius(Hemisphere.ZOOM_MAX, ringed = true)
        assertTrue("the reach does not lap over the opened world", openOut - side * 0.07f < edge - 30f)
        fun opened(inFromEdge: Float): Long {
            val v2 = globe(zoom = Hemisphere.ZOOM_MAX)
            val share = (edge - inFromEdge) / openOut
            val x = c + (openMark.x - c) * share
            val y = c + (openMark.y - c) * share
            touch(v2, MotionEvent.ACTION_DOWN, x, y)
            carry(v2, x, y, 12.0)
            return abs(v2.woundForTest())
        }
        assertTrue("forty pixels in over the edge is not the moon", opened(40f) > dayMs / 2)
        assertEquals(
            "sixty pixels in over the edge is not the world",
            48.0, opened(60f).toDouble() / 60_000L, 6.0
        )
        // No moon on the rim, nothing to hold.
        for (bare in listOf(globe(moon = false), globe(view = Hemisphere.View.GLOBE))) {
            val at = globe().moonMarkForTest()
            touch(bare, MotionEvent.ACTION_DOWN, at.x, at.y)
            carry(bare, at.x, at.y, 12.0)
            assertEquals(0L, bare.woundForTest())
        }
    }

    /** The page behind must not take the finger that has the moon. */
    @Test
    fun `the moon in the hand is not a swipe of the page`() {
        var kept = 0
        val parent = object : android.widget.FrameLayout(context) {
            override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                if (disallow) kept++
                super.requestDisallowInterceptTouchEvent(disallow)
            }
        }
        val v = globe()
        parent.addView(v)
        v.layout(0, 0, side, side)
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        assertEquals("the page was not told to keep off", 1, kept)
        // And in the margin beside it, it is not told anything.
        val (rx, ry) = round(mark.x, mark.y, 25.0)
        touch(v, MotionEvent.ACTION_UP, mark.x, mark.y)
        touch(v, MotionEvent.ACTION_DOWN, rx, ry)
        assertEquals(1, kept)
    }

    /** In the hand the mark wears a ring, and loses it when it is let go. */
    @Test
    fun `the moon shows that it has been taken hold of`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        fun ringed(): Int {
            val map = shoot(v)
            // Round the mark at twice its own radius, where at rest
            // there is nothing but the black behind the world.
            val world = side * Hemisphere.worldRadius(v.zoom, ringed = true)
            var n = 0
            for (k in 0 until 72) {
                val a = Math.toRadians(k * 5.0)
                val x = (mark.x + world * 0.085f * cos(a).toFloat()).toInt()
                val y = (mark.y + world * 0.085f * sin(a).toFloat()).toInt()
                if (x in 0 until side && y in 0 until side && map.getPixel(x, y) != space) n++
            }
            return n
        }
        val before = ringed()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        val inHand = ringed()
        assertTrue("no ring round a moon in the hand: $before then $inHand", inHand > before + 40)
        save(shoot(v), "world-moon-in-hand")
        touch(v, MotionEvent.ACTION_UP, mark.x, mark.y)
        assertEquals("the ring stayed after the moon was let go", before, ringed())
    }

    // ------------------------------------------------------ letting it go

    /**
     * Let go days off, the days go first, a whole day at a step, and
     * only then does the world turn home.
     *
     * A world five days off would otherwise come home by turning five
     * times as fast as it is allowed, which is a strobe. A whole number
     * of days from now it is turned exactly as it is now, so it does not
     * move at all while they fall away.
     */
    @Test
    fun `let go days off, the days fall away before the world turns`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        val finger = carry(v, mark.x, mark.y, 70.0)
        val wound = v.woundForTest()
        val days = wound / dayMs
        assertTrue("seventy degrees of moon is $days days", days in 5L..6L)
        val rest = wound - days * dayMs
        touch(v, MotionEvent.ACTION_UP, finger.first, finger.second)
        assertTrue("the days are not falling away", v.glidingForTest())
        assertFalse("the world set off turning with the days still on it", v.spinningForTest())

        var frames = 0
        var last = wound
        val seen = HashSet<Long>()
        while (v.glidingForTest() && frames < 400) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            v.stepGlideForTest()
            frames++
            val now = v.woundForTest()
            if (v.glidingForTest()) {
                assertEquals("a part of a day went at frame $frames", rest, now - (now / dayMs) * dayMs)
                assertTrue("the days came back at frame $frames", now <= last)
                seen += now / dayMs
            }
            last = now
        }
        // Three hundred and fifty milliseconds and twenty-five for each
        // of the days.
        val took = frames * 16L
        assertEquals("the days took $took ms to go", 350.0 + 25.0 * days, took.toDouble(), 40.0)
        assertTrue("the days went in one jump: $seen", seen.size >= days.toInt())
        // What is left is less than a day, and the spring has it.
        assertTrue(abs(v.woundForTest()) < dayMs)
        assertTrue("nothing took the rest home", v.spinningForTest())
        var steps = 0
        while (v.spinningForTest() && steps < 2000) {
            v.stepSpinForTest(1.0 / 60.0)
            steps++
        }
        assertEquals("the world did not come home", 0L, v.woundForTest())
    }

    /** Less than a day off, there are no days to go and the world turns home as it always has. */
    @Test
    fun `let go less than a day off, the world simply turns home`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        val finger = carry(v, mark.x, mark.y, 6.0)
        assertTrue(abs(v.woundForTest()) in (dayMs / 4)..(dayMs - 1))
        touch(v, MotionEvent.ACTION_UP, finger.first, finger.second)
        assertFalse(v.glidingForTest())
        assertTrue(v.spinningForTest())
        // And a moon picked up and put down where it was is nothing at all.
        val still = globe()
        val at = still.moonMarkForTest()
        touch(still, MotionEvent.ACTION_DOWN, at.x, at.y)
        touch(still, MotionEvent.ACTION_UP, at.x, at.y)
        assertFalse(still.glidingForTest())
        assertFalse(still.spinningForTest())
        assertEquals(0L, still.woundForTest())
    }

    /** A month of days does not take a month: the going is capped at a second and a half. */
    @Test
    fun `however many days, they are gone in a second and a half`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        // Three times round and a bit: three months.
        var finger = mark.x to mark.y
        repeat(8) { finger = carry(v, finger.first, finger.second, 150.0) }
        val days = abs(v.woundForTest()) / dayMs
        assertTrue("twelve hundred degrees of moon is $days days", days in 90L..106L)
        touch(v, MotionEvent.ACTION_UP, finger.first, finger.second)
        var frames = 0
        while (v.glidingForTest() && frames < 400) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            v.stepGlideForTest()
            frames++
        }
        assertEquals(1500.0, frames * 16.0, 40.0)
    }

    /** A finger put down while the days are going stops them; lifted, they go on. */
    @Test
    fun `a finger stops the days going, and they go on when it lifts`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        val finger = carry(v, mark.x, mark.y, 100.0)
        touch(v, MotionEvent.ACTION_UP, finger.first, finger.second)
        repeat(6) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            v.stepGlideForTest()
        }
        assertTrue(v.glidingForTest())
        val partWay = v.woundForTest()
        assertTrue(abs(partWay) > dayMs)
        // In the margin, where it holds nothing.
        val (rx, ry) = round(mark.x, mark.y, -40.0)
        touch(v, MotionEvent.ACTION_DOWN, rx, ry)
        assertFalse("the days went on under a finger", v.glidingForTest())
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        assertFalse(v.stepGlideForTest())
        assertEquals(partWay, v.woundForTest())
        touch(v, MotionEvent.ACTION_UP, rx, ry)
        assertTrue("the days did not go on when the finger lifted", v.glidingForTest())
    }

    /** Out of sight the moon is let go of and the world is at now, as it is for a finger on the world. */
    @Test
    fun `out of sight, the moon is let go of`() {
        val v = globe()
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        val finger = carry(v, mark.x, mark.y, 60.0)
        assertTrue(abs(v.woundForTest()) > dayMs)
        v.dispatchWindowVisibilityChanged(View.GONE)
        assertEquals(0L, v.woundForTest())
        assertFalse(v.glidingForTest())
        // And a move that arrives late, from where the finger had got
        // to, does not wind it again.
        touch(v, MotionEvent.ACTION_MOVE, finger.first, finger.second)
        assertEquals(0L, v.woundForTest())
        // The same with the days on their way home: they are simply gone.
        val going = globe()
        val at = going.moonMarkForTest()
        touch(going, MotionEvent.ACTION_DOWN, at.x, at.y)
        val lifted = carry(going, at.x, at.y, 60.0)
        touch(going, MotionEvent.ACTION_UP, lifted.first, lifted.second)
        assertTrue(going.glidingForTest())
        going.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse("the days went on falling away out of sight", going.glidingForTest())
        ShadowSystemClock.advanceBy(Duration.ofMillis(50))
        assertFalse(going.stepGlideForTest())
        assertEquals(0L, going.woundForTest())
    }

    // --------------------------------------------------- saying which day

    /** The day the world is wound to is said on its face, whenever it is not today. */
    @Test
    fun `a world wound to another day says which`() {
        val v = globe()
        assertNull(v.legendForTest())
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        carry(v, mark.x, mark.y, -61.0)
        val shown = noon() + v.woundForTest()
        val expected = android.text.format.DateFormat.format(
            "EEE d MMM", java.util.Calendar.getInstance().apply { timeInMillis = shown }
        ).toString()
        assertEquals(expected, v.legendForTest())
        // Five days before the 11th, wherever the machine is.
        assertTrue("$expected is not a day early in November", Regex("""\w+ [5-7] Nov""").matches(expected))
        save(shoot(v), "world-wound-by-the-moon")
        // Twelve times round and a bit more is last year, and the year
        // is said.
        var finger = mark.x to mark.y
        repeat(30) { finger = carry(v, finger.first, finger.second, -150.0) }
        assertTrue(
            "${v.legendForTest()} does not say which year",
            Regex("""\w+ \d+ \w+ 2025""").matches(v.legendForTest()!!)
        )
        // A year to the day is the same day of the year and is not today.
        val anniversary = globe()
        anniversary.windForTest(-365 * dayMs)
        assertTrue(
            "${anniversary.legendForTest()} for a year ago today",
            Regex("""\w+ 1[0-2] Nov 2025""").matches(anniversary.legendForTest() ?: "")
        )
        // And a day that is all of today says nothing at all.
        val today = globe()
        today.windForTest(60_000L)
        assertNull(today.legendForTest())
        // Wound a few hours with a finger on the world, it is still today
        // and nothing is said.
        val hours = globe()
        touch(hours, MotionEvent.ACTION_DOWN, 700f, 500f)
        carry(hours, 700f, 500f, 20.0)
        assertTrue(hours.woundForTest() != 0L)
        val same = java.util.Calendar.getInstance().apply { timeInMillis = noon() }
            .get(java.util.Calendar.DAY_OF_YEAR) ==
            java.util.Calendar.getInstance().apply { timeInMillis = noon() + hours.woundForTest() }
                .get(java.util.Calendar.DAY_OF_YEAR)
        assertEquals(same, hours.legendForTest() == null)
    }

    // --------------------------------------------------------- the weather

    /**
     * The weather of another day is not sent for at every day the moon
     * goes through.
     *
     * A day's clouds are a photograph decoded off disk. A finger on the
     * world goes through a day in a whole turn; the moon goes through
     * one in a thumb's width.
     */
    @Test
    fun `the moon does not send for a week of weather in one frame`() {
        var asked = 0
        val v = globe().apply { cloudsOn = { asked++; null } }
        shoot(v)
        assertEquals(1, asked)
        val mark = v.moonMarkForTest()
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        // Ten days, a frame drawn at each, all in the same instant.
        var finger = mark.x to mark.y
        repeat(10) {
            finger = carry(v, finger.first, finger.second, Hemisphere.MOON_DEG_PER_DAY)
            shoot(v)
        }
        assertEquals("the weather was sent for at every day the moon went through", 1, asked)
        assertEquals("no frame was asked for to catch the weather up", 10, v.catchUpsForTest())
        // A quarter of a second later, the day it has got to is asked about.
        ShadowSystemClock.advanceBy(Duration.ofMillis(260))
        shoot(v)
        assertEquals(2, asked)
        // Let go, and on its way home it is no different.
        touch(v, MotionEvent.ACTION_UP, finger.first, finger.second)
        assertTrue(v.glidingForTest())
        val leaving = v.woundForTest() / dayMs
        repeat(14) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            v.stepGlideForTest()
            shoot(v)
        }
        // A fifth of a second in, and some of the days have gone.
        assertTrue(v.glidingForTest())
        assertTrue("no day had gone yet", v.woundForTest() / dayMs < leaving - 1)
        assertEquals("sent for at every day of the way home", 2, asked)
        // A finger on the world is as it was: another day is asked about
        // at once.
        var again = 0
        val world = globe().apply { cloudsOn = { again++; null } }
        shoot(world)
        world.atMs = noon() - dayMs
        shoot(world)
        world.atMs = noon() - 2 * dayMs
        shoot(world)
        assertEquals(3, again)
    }
}
