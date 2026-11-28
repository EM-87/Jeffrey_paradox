package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What is drawn on the turning world for the person looking at it: the
 * dot where they are, the two strings of beads that are its hands, and
 * the word under it that says which day it has been turned to.
 *
 * Three things were reported of them from a phone. Seen from over the
 * other pole the dot was simply gone, and so was the clock's hand. On
 * the ball the two strings "went mad", which they did: they are straight
 * lines from the middle of the picture, and on the ball the middle of
 * the picture is nowhere in particular. And the date a finger turns the
 * world to was printed across the world, over the ocean being looked at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarksOnTheWorldTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun at(hour: Int): Long = DailyImageStore.msOf("2026-11-22")!! + hour * 3_600_000L

    /** Madrid, which is north of the equator and four degrees west of Greenwich. */
    private val lat = 40.42
    private val lon = -3.70

    private fun globe(
        which: Hemisphere.View, hour: Int, here: Boolean, wide: Int = 1080, tall: Int = 1080
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = which
        atMs = at(hour)
        hourRing = true
        showSun = false
        showMoon = false
        moonlight = false
        located = here
        latitude = lat
        longitude = lon
        // One hour east of Greenwich, as Madrid keeps in November.
        zoneOffset = 3_600_000
        measure(
            View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, wide, tall)
    }

    private class Glass(val wide: Int, val tall: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * wide + x]
    }

    private fun glass(v: View, name: String? = null): Glass {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        if (name != null) {
            File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SHOT $name ${bitmap.width}x${bitmap.height}")
        }
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Glass(bitmap.width, bitmap.height, out)
    }

    /** The world's radius on a square face of this size with the ring of hours round it. */
    private fun radius(which: Hemisphere.View, side: Int): Double =
        side * Hemisphere.worldRadius(Hemisphere.ZOOM_MIN, ringed = Hemisphere.hasRimScale(which), moonOutside = false).toDouble()

    /** Where the place is on the glass, and whether it is on the side turned away. */
    private fun place(which: Hemisphere.View, hour: Int, side: Int): Triple<Double, Double, Boolean> {
        val on = Hemisphere.project(which, lat, lon, Hemisphere.subsolar(at(hour))[1], 0.0)
        val r = radius(which, side)
        return Triple(side / 2.0 + on[0] * r, side / 2.0 + on[1] * r, on[2] < 0.0)
    }

    /**
     * How much of a colour has been laid over what was there, from one
     * pixel before and after: nought for none of it, one for all.
     */
    private fun share(under: Int, now: Int, colour: Int): Double {
        var most = 0.0
        var of = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val u = (under shr shift) and 0xFF
            val n = (now shr shift) and 0xFF
            val c = (colour shr shift) and 0xFF
            // Judged by the channel the colour moves furthest.
            if (Math.abs(c - u) > of) {
                of = Math.abs(c - u)
                most = (n - u).toDouble() / (c - u)
            }
        }
        return most
    }

    /**
     * In front of the world the dot is the dot. Behind it — from over the
     * other pole, or round the back of the ball — it is still there, at
     * the place the world would show if it were glass, and a third as
     * strong.
     */
    @Test
    fun `a place behind the world is drawn faintly, not left out`() {
        val side = 1080
        data class Case(val which: Hemisphere.View, val hour: Int, val behind: Boolean)
        val cases = listOf(
            Case(Hemisphere.View.NORTH, 12, false),
            Case(Hemisphere.View.SOUTH, 12, true),
            Case(Hemisphere.View.SOUTH, 3, true),
            Case(Hemisphere.View.GLOBE, 6, false),
            Case(Hemisphere.View.GLOBE, 18, true),
            Case(Hemisphere.View.GLOBE, 21, true)
        )
        for (case in cases) {
            val named = "the ${case.which.key} view at ${case.hour} h"
            val (x, y, behind) = place(case.which, case.hour, side)
            assertEquals("whether Madrid is behind the world on $named", case.behind, behind)
            val without = glass(globe(case.which, case.hour, here = false))
            val with = glass(globe(case.which, case.hour, here = true), "you-${case.which.key}-${case.hour}")
            val dot = share(without.at(x.toInt(), y.toInt()), with.at(x.toInt(), y.toInt()), HemisphereView.YOU)
            println("MEASURE the dot on $named, ${if (behind) "behind" else "in front"}: %.2f of its colour".format(dot))
            val wanted = if (behind) HemisphereView.BEHIND.toDouble() else 1.0
            assertEquals("how much of the dot there is on $named", wanted, dot, 0.04)
            // The ping says "here", and is for the side that is here:
            // between the dot's own ring and as far as a ping reaches
            // there is something in front of the world and nothing behind.
            val r = radius(case.which, side)
            var pinged = 0
            for (py in 0 until side) for (px in 0 until side) {
                val far = Math.hypot(px + 0.5 - x, py + 0.5 - y) / r
                if (far < 0.07 || far > 0.055 + HemisphereView.PING_REACH) continue
                // Not along the strings, which on a view down a pole run through here.
                val alongX = (px + 0.5 - side / 2.0)
                val alongY = (py + 0.5 - side / 2.0)
                val off = Math.abs(alongX * (y - side / 2.0) - alongY * (x - side / 2.0)) / Math.hypot(x - side / 2.0, y - side / 2.0)
                if (Hemisphere.hasRimScale(case.which) && off < r * 0.03) continue
                if (without.at(px, py) != with.at(px, py) && Color.red(with.at(px, py)) > Color.red(without.at(px, py)) + 4) pinged++
            }
            println("MEASURE the ping on $named: $pinged pixels")
            if (behind) assertEquals("a ping behind the world on $named", 0, pinged) else assertTrue("no ping on $named", pinged > 100)
        }
        assertEquals(0.35f, HemisphereView.BEHIND, 0f)
    }

    /**
     * The two strings of beads are hands on a ring of hours. Down a pole
     * they are there, your own meridian in red and your clock's in blue,
     * strong in front of the world and faint behind it like the dot; on
     * the ball, which has no ring, there is the dot and nothing else.
     */
    @Test
    fun `the meridians are strings down a pole and are not drawn on the ball`() {
        val side = 1080
        for ((which, hour) in listOf(
            Hemisphere.View.NORTH to 12, Hemisphere.View.SOUTH to 12, Hemisphere.View.SOUTH to 3,
            Hemisphere.View.GLOBE to 6, Hemisphere.View.GLOBE to 12, Hemisphere.View.GLOBE to 0, Hemisphere.View.GLOBE to 18
        )) {
            val named = "the ${which.key} view at $hour h"
            val (x, y, behind) = place(which, hour, side)
            val r = radius(which, side)
            val without = glass(globe(which, hour, here = false))
            val with = glass(globe(which, hour, here = true))
            // Everything that being somewhere adds, further from the dot
            // than its ring and its ping reach.
            val reach = r * (0.055 + HemisphereView.PING_REACH) + 3.0
            var red = 0
            var blue = 0
            var strongest = 0.0
            for (py in 0 until side) for (px in 0 until side) {
                val a = without.at(px, py)
                val b = with.at(px, py)
                if (a == b) continue
                if (Math.hypot(px + 0.5 - x, py + 0.5 - y) <= reach) continue
                val redder = Color.red(b) - Color.red(a)
                val bluer = Color.blue(b) - Color.blue(a)
                if (redder > bluer) red++ else blue++
                strongest = maxOf(strongest, share(a, b, if (redder > bluer) HemisphereView.YOU else HemisphereView.OFFICIAL))
            }
            println("MEASURE away from the dot on $named: $red pixels redder, $blue bluer, the strongest %.2f of its colour".format(strongest))
            if (which == Hemisphere.View.GLOBE) {
                assertEquals("strings drawn on the ball at $hour h", 0, red + blue)
            } else {
                assertTrue("no string for your own meridian on $named: $red pixels", red > 150)
                assertTrue("no string for your clock's meridian on $named: $blue pixels", blue > 150)
                // A bead is 210 of 255 in front, and a third of that behind.
                val wanted = 210.0 / 255.0 * if (behind) HemisphereView.BEHIND else 1f
                assertEquals("how strong the strings are on $named", wanted, strongest, 0.05)
            }
        }
    }

    /**
     * The day the world has been turned to is written under the world,
     * below its ring of hours — and across it only on a face with no
     * under, which is as wide as it is tall.
     */
    @Test
    fun `the day the world is turned to is written under it`() {
        val wide = 1080
        val tall = 2340
        val day = 86_400_000L
        for (which in Hemisphere.View.entries) {
            val still = globe(which, 12, here = false, wide = wide, tall = tall)
            val before = glass(still)
            assertEquals("a word on a world that has not been turned", null, still.legendForTest())
            val turned = globe(which, 12, here = false, wide = wide, tall = tall)
            // Three whole days: the same face of the world, another date.
            turned.windForTest(3 * day)
            val after = glass(turned, "legend-${which.key}")
            val said = turned.legendForTest()
            assertNotNull("no word on a world turned three days on", said)
            assertTrue("the word is not the day: $said", said!!.contains("25"))
            // Where the line of text is: clear of everything round the world.
            // The ring's numerals end 0.455 of the shorter side from the
            // middle and the sun and the moon 0.47: the top of the letters
            // is below that.
            val at = turned.legendAtForTest()
            val clear = tall / 2f + wide * 0.47f
            val top = at - wide * HemisphereView.LEGEND_SIZE
            assertTrue("the legend on the ${which.key} view starts at $top, not under the world ($clear)", top > clear && at < tall * 0.75f)
            // And on the glass: letters there, in the colour of the face's
            // numerals, and nothing new anywhere on the world itself.
            var letters = 0
            var onTheWorld = 0
            val worldR = wide * Hemisphere.WORLD_EDGE
            for (py in 0 until tall) for (px in 0 until wide) {
                if (before.at(px, py) == after.at(px, py)) continue
                val below = py > clear
                if (below && Color.red(after.at(px, py)) > 150) letters++
                // The world is the same world three days on but for the sun's
                // two thirds of a degree, so only a pale mark counts.
                val far = Math.hypot(px + 0.5 - wide / 2.0, py + 0.5 - tall / 2.0)
                if (far < worldR * 0.8 && Color.red(after.at(px, py)) > 200 && Color.red(before.at(px, py)) < 90) onTheWorld++
            }
            println("MEASURE the legend on the ${which.key} view: at $at of $tall, $letters pixels of letters under the world, $onTheWorld on it")
            assertTrue("no letters under the world on the ${which.key} view: $letters", letters > 300)
            assertEquals("letters across the world on the ${which.key} view", 0, onTheWorld)
        }
        // A face with no under keeps the word on the world, as it was.
        val square = globe(Hemisphere.View.NORTH, 12, here = false)
        square.windForTest(3 * day)
        glass(square)
        val r = radius(Hemisphere.View.NORTH, 1080)
        assertEquals("the legend on a square face", (540f + r * 0.80).toFloat(), square.legendAtForTest(), 1f)
    }
}
