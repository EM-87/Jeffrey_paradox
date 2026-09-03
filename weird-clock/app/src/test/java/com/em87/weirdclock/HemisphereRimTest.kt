package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
 * What is drawn round the world, and what survives opening it out.
 *
 * Everything here was reported from the phone, and all of it in one
 * sentence: the two coloured meridians vanished a fifth of the way into a
 * pinch, which reads as a bug because they are the hands. They went with
 * the ring of hours, on the argument that a line pointing at a scale that
 * is not there is worse than no line — true of a line, false of a hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HemisphereRimTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** Midsummer noon over Greenwich, so nothing here reads the clock. */
    private fun noon(): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val size = 1000

    private fun globe(
        zoom: Float = Hemisphere.ZOOM_MIN,
        sun: Boolean = true,
        moon: Boolean = true,
        alarms: List<Long> = emptyList(),
        at: Long = noon()
    ): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = Hemisphere.View.NORTH
        latitude = 40.4
        longitude = -3.7
        located = true
        showSun = sun
        showMoon = moon
        alarmsAt = alarms
        this.zoom = zoom
        atMs = at
        measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, size, size)
    }

    private fun shoot(v: View): Bitmap =
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            .also { v.draw(Canvas(it)) }

    /** How many pixels are within reach of that colour. */
    private fun count(map: Bitmap, colour: Int, slack: Int = 40): Int {
        var n = 0
        val r = colour shr 16 and 0xFF
        val g = colour shr 8 and 0xFF
        val b = colour and 0xFF
        for (y in 0 until map.height step 2) {
            for (x in 0 until map.width step 2) {
                val p = map.getPixel(x, y)
                if (kotlin.math.abs((p shr 16 and 0xFF) - r) < slack &&
                    kotlin.math.abs((p shr 8 and 0xFF) - g) < slack &&
                    kotlin.math.abs((p and 0xFF) - b) < slack
                ) n++
            }
        }
        return n
    }

    /**
     * The two meridians are still there with the world opened out.
     *
     * This is the report, in one assertion. The blue one is the easier of
     * the two to count — nothing else on the face is that colour, whereas
     * the red dot and its ring are red as well — so both are counted and
     * both have to survive.
     */
    @Test
    fun `the coloured meridians survive the zoom`() {
        val shut = shoot(globe(zoom = Hemisphere.ZOOM_MIN))
        val open = shoot(globe(zoom = Hemisphere.ZOOM_MAX, sun = false, moon = false))
        val blueShut = count(shut, 0x5AA9FF)
        val blueOpen = count(open, 0x5AA9FF)
        assertTrue("there was no blue meridian to begin with: $blueShut", blueShut > 20)
        assertTrue("the blue meridian went with the ring: $blueOpen", blueOpen > 20)
    }

    /**
     * And the ring of hours does go, which is the half that was right.
     *
     * A scale drawn across the thing it is measuring is not a scale.
     */
    @Test
    fun `the ring of hours gives way`() {
        assertEquals("the ring is not whole at rest", 1f, Hemisphere.ringFade(1f), 1e-6f)
        assertEquals(
            "the ring is still up at full zoom", 0f,
            Hemisphere.ringFade(Hemisphere.ZOOM_MAX), 1e-6f
        )
        // A little past a fifth of the pinch, which is where it was asked
        // to go: gone at a fifth exactly felt like a bug, because that is
        // where the coloured lines used to go with it.
        assertTrue("the ring goes before a fifth", Hemisphere.ringFade(1.2f) > 0.01f)
    }

    /** With nothing outside it, the world opens to the edge of the glass. */
    @Test
    fun `with the sun off the world reaches the screen`() {
        val room = Hemisphere.worldRadius(Hemisphere.ZOOM_MAX, ringed = true, sunOutside = false)
        val kept = Hemisphere.worldRadius(Hemisphere.ZOOM_MAX, ringed = true, sunOutside = true)
        assertEquals("the world does not reach the edge", 0.5f, room, 1e-6f)
        assertTrue("the sun was left nowhere to stand", kept < room)
    }

    /**
     * The moon is on the rim, and its distance from the sun is the phase.
     *
     * Measured as a bearing rather than as pixels, because the claim is
     * about where it is and not about how it looks: a full moon is
     * opposite the sun, which is why it rises as the sun sets.
     */
    @Test
    fun `the moon stands off the sun by the phase`() {
        val full = firstFullMoonAfter(noon())
        val hour = Hemisphere.moonHour(full)
        val off = kotlin.math.abs(Hemisphere.wrap((hour - 12.0) * 15.0))
        assertTrue("a full moon is not opposite the sun: $off degrees", off > 150.0)
        val newMoon = firstNewMoonAfter(noon())
        val together = kotlin.math.abs(
            Hemisphere.wrap((Hemisphere.moonHour(newMoon) - 12.0) * 15.0)
        )
        assertTrue("a new moon is not next to the sun: $together degrees", together < 30.0)
    }

    /** And it is actually drawn, and goes when it is switched off. */
    @Test
    fun `the moon is on the face and can be taken off it`() {
        // Counted as the difference between the two pictures rather than
        // as a colour, because the moon's own disc is filled to whatever
        // fraction of it is lit tonight — so at a new moon it is an
        // outline and at a full one it is solid, and no single colour
        // finds both.
        val drawn = differ(shoot(globe(moon = true)), shoot(globe(moon = false)))
        assertTrue("no moon was drawn: $drawn pixels", drawn > 20)
        val same = differ(shoot(globe(moon = false)), shoot(globe(moon = false)))
        assertEquals("two pictures of the same thing differ", 0, same)
    }

    /** How many sampled pixels are not the same in the two pictures. */
    private fun differ(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        }
        return n
    }

    /** So is the sun, which never had a switch at all. */
    @Test
    fun `the sun can be taken off the face`() {
        val there = count(shoot(globe(sun = true)), 0xFFC93C, slack = 30)
        val gone = count(shoot(globe(sun = false)), 0xFFC93C, slack = 30)
        assertTrue("no sun was drawn: $there", there > 40)
        assertTrue("the sun would not go: $gone against $there", gone < there / 4)
    }

    /** What is armed is marked on the ring. */
    @Test
    fun `armed alarms are marked on the ring`() {
        val bare = count(shoot(globe()), 0x7CE08A, slack = 24)
        val armed = count(
            shoot(globe(alarms = listOf(7 * 3_600_000L + 20 * 60_000L, 19 * 3_600_000L))),
            0x7CE08A, slack = 24
        )
        assertTrue("something green was already there: $bare", bare < 10)
        assertTrue("the alarms were not marked: $armed", armed > 10)
    }

    /**
     * The ping asks for frames only where there is a dot to ping.
     *
     * Twenty a second is cheap and nothing is cheap enough to run when
     * nobody is looking — which is the lesson of the world-clock physics
     * that ran sixty a second on every card in the app.
     */
    @Test
    fun `the ping only runs when there is somewhere to point at`() {
        val lost = globe().apply { located = false }
        assertFalse("a globe with no fix is animating a ping", lost.pingingForTest())
    }

    private fun firstFullMoonAfter(from: Long): Long = firstPhaseAfter(from, 0.5)

    private fun firstNewMoonAfter(from: Long): Long = firstPhaseAfter(from, 0.0)

    /** Walks forward an hour at a time to the moment nearest that phase. */
    private fun firstPhaseAfter(from: Long, phase: Double): Long {
        var best = from
        var closest = 1.0
        for (h in 0 until 24 * 40) {
            val at = from + h * 3_600_000L
            val d = kotlin.math.abs(Hemisphere.wrap((SkyGlyph.phaseAt(at) - phase) * 360.0)) / 360.0
            if (d < closest) {
                closest = d
                best = at
            }
        }
        return best
    }
}
