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
 * Last night's lights on the dark side of the globe, looked at.
 *
 * Driven with the service's real answers for one night — a clean night
 * and a raw one, kept beside the tests — and counted rather than
 * eyeballed: amber where the clean night has cities, cyan where the raw
 * night has something the clean one has not, nothing on the lit side,
 * and nothing at all when the lights are off. The pictures are written
 * out as well, for the eyeballing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HemisphereLightsShotTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun resource(name: String): Bitmap =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })!!
        }

    private fun layer(name: String, day: String, lag: Int): NightLightStore.Layer {
        val picture = resource(name)
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = ByteArray(w * h) { NightLights.luminance(pixels[it]).toByte() }
        return NightLightStore.Layer(w, h, lum, day, lag)
    }

    private fun sky(mode: NightLights.Mode, cleanLag: Int = 2, rawLag: Int = 0) =
        NightLightStore.Sky(
            mode,
            if (mode.clean) layer("gibs-lights-clean.png", "2026-11-03", cleanLag) else null,
            if (mode.raw) layer("gibs-lights-raw.png", "2026-11-05", rawLag) else null
        )

    /**
     * Two in the morning UTC at midsummer: the sun is over the Pacific,
     * so the ball is turned to face Europe and Africa and its dark half
     * is Europe, West Africa and the Atlantic — which is where the
     * clean night's picture has the most to show.
     */
    private fun noon(): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 2, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun globe(which: Hemisphere.View, lights: NightLightStore.Sky?): HemisphereView =
        HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            view = which
            atMs = noon()
            hourRing = false
            showSun = false
            showMoon = false
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
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    /**
     * Whether a pixel of [on] is one of [off]'s with amber light added:
     * warmer and brighter in red than it was, by more than the map's own
     * deserts could make it. Judged against the picture without the
     * lights rather than by colour alone, because the day map has tan in
     * it already and the night map has the old lights.
     */
    private fun warmed(on: Int, off: Int): Boolean {
        val r = (on shr 16) and 0xFF
        val g = (on shr 8) and 0xFF
        val b = on and 0xFF
        val r0 = (off shr 16) and 0xFF
        return r - r0 > 40 && r > g && g > b + 20
    }

    /** And with cyan added: green and blue up, red hardly. */
    private fun cooled(on: Int, off: Int): Boolean {
        val r = (on shr 16) and 0xFF
        val g = (on shr 8) and 0xFF
        val b = on and 0xFF
        val g0 = (off shr 8) and 0xFF
        return g - g0 > 40 && g > r + 25 && b > r + 10
    }

    private fun count(on: Bitmap, off: Bitmap, left: Boolean, test: (Int, Int) -> Boolean): Int {
        var n = 0
        val x0 = if (left) 0 else on.width / 2
        val x1 = if (left) on.width / 2 else on.width
        for (y in 0 until on.height step 2) {
            for (x in x0 until x1 step 2) {
                if (test(on.getPixel(x, y), off.getPixel(x, y))) n++
            }
        }
        return n
    }

    /** How many pixels differ at all between two pictures of a half. */
    private fun differs(a: Bitmap, b: Bitmap, left: Boolean): Int =
        count(a, b, left) { p, q -> p != q }

    /** On, the dark side has cities in it; the lit side is untouched. */
    @Test
    fun `the clean night puts amber cities on the dark side and nowhere else`() {
        val off = shoot(globe(Hemisphere.View.GLOBE, null), "lights-off-globe")
        val on = shoot(globe(Hemisphere.View.GLOBE, sky(NightLights.Mode.CLEAN)), "lights-clean-globe")
        val dark = count(on, off, left = true, ::warmed)
        assertTrue("no cities on the dark side: $dark", dark > 100)
        // The sunlit half is the day map, and the lights are cut to where
        // the sun has set: nothing there may change.
        val lit = differs(on, off, left = false)
        assertTrue("the lit side changed in $lit places", lit < 40)
        // And the sea stays sea: most of the dark half is ocean, and it
        // must not have become a field of speckle.
        val cooledSea = count(on, off, left = true, ::cooled)
        assertEquals("cyan with only the clean night on", 0, cooledSea)
        assertTrue("the dark side is speckled: $dark warmed pixels", dark < 6_000)
    }

    /** Both: the raw night's surplus is cyan, and the cities are still amber. */
    @Test
    fun `both picks the difference out in cyan over the amber`() {
        val off = shoot(globe(Hemisphere.View.GLOBE, null), "lights-off-again")
        val both = shoot(globe(Hemisphere.View.GLOBE, sky(NightLights.Mode.BOTH)), "lights-both-globe")
        val amber = count(both, off, left = true, ::warmed)
        val cyan = count(both, off, left = true, ::cooled)
        assertTrue("the cities went: $amber", amber > 100)
        // A handful is right: the floor is set above everything the raw
        // night adds to open sea, so what is left is the few per cent of
        // the picture that is genuinely brighter than the clean night —
        // cloud under the moon, the twilight edge, a town lit up.
        assertTrue("nothing picked out of the raw night: $cyan", cyan > 8)
        assertTrue("the dark side is speckled cyan: $cyan", cyan < 6_000)
    }

    /** The flat views wear them too, round the pole. */
    @Test
    fun `the polar views wear the lights as well`() {
        for (which in listOf(Hemisphere.View.NORTH, Hemisphere.View.SOUTH)) {
            val off = shoot(globe(which, null), "lights-off-${which.key}")
            val shot = shoot(globe(which, sky(NightLights.Mode.CLEAN)), "lights-clean-${which.key}")
            val amber = count(shot, off, left = true, ::warmed) + count(shot, off, left = false, ::warmed)
            assertTrue("${which.key}: no cities at all: $amber", amber > 50)
        }
    }

    /**
     * The globe says which night it is only when that night is further
     * behind than is ordinary.
     */
    @Test
    fun `the date is on the face only when the lights are overdue`() {
        val ordinary = shoot(globe(Hemisphere.View.GLOBE, sky(NightLights.Mode.BOTH, cleanLag = 3, rawLag = 1)), "lights-ordinary")
        val overdue = shoot(globe(Hemisphere.View.GLOBE, sky(NightLights.Mode.BOTH, cleanLag = 5, rawLag = 2)), "lights-overdue")
        // The legend sits low on the disc, in a dark pill with light
        // type: compare that band of the two pictures.
        fun band(b: Bitmap): Int {
            var differentFromBlack = 0
            for (y in 760..880 step 2) for (x in 250..750 step 2) {
                val c = b.getPixel(x, y)
                val lum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (lum > 500) differentFromBlack++
            }
            return differentFromBlack
        }
        assertTrue(
            "no legend on an overdue globe, or one on an ordinary one: ${band(ordinary)} vs ${band(overdue)}",
            band(overdue) > band(ordinary) + 40
        )
    }
}
