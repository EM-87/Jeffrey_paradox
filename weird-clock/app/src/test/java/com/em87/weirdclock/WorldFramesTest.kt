package com.em87.weirdclock

import android.graphics.Bitmap
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

/**
 * What a frame of the turning world costs, and what it does not do twice.
 *
 * The spinning globe was reported seizing up, and it was: every six
 * degrees of turn it projected a quarter of a million points through two
 * inverse trigonometric functions each, on the thread that draws, and
 * copied three maps' worth of pixels out of their bitmaps to read them.
 * The projection is kept now and the maps are read out once, and these
 * tests hold it to that by counting rather than by timing — a count is
 * the same on every machine, and the timing is printed beside it for
 * whoever is curious.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorldFramesTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun noon(): Long =
        java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun globe(which: Hemisphere.View = Hemisphere.View.GLOBE): HemisphereView =
        HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            view = which
            atMs = noon()
            clouds = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(0xFFFFFFFF.toInt())
            }
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1000)
        }

    private fun frame(v: View) {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
    }

    /**
     * The world turned through a dozen steps is a dozen bakes of one
     * projection, not a dozen projections.
     */
    @Test
    fun `the projection is worked out once however far the world turns`() {
        val world = globe()
        val start = noon()
        val began = System.nanoTime()
        for (step in 0 until 12) {
            // Six minutes is a degree and a half, which is one step of the
            // bake's own quantisation, so every frame here is a new bake.
            world.atMs = start + step * 6 * 60_000L
            frame(world)
        }
        val took = (System.nanoTime() - began) / 1_000_000
        println("twelve turned frames of the globe with clouds: $took ms")
        assertTrue("only ${world.bakesForTest()} bakes for twelve turns", world.bakesForTest() >= 12)
        assertEquals("the projection was worked out again", 1, world.projectionsForTest())
    }

    /** And the weather is looked up once a second, not once a frame. */
    @Test
    fun `the clouds are asked for once a second`() {
        val world = globe()
        var asked = 0
        val sky = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        world.cloudsOn = { asked++; sky }
        repeat(10) { frame(world) }
        assertEquals("asked $asked times inside one second", 1, asked)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000))
        frame(world)
        assertEquals("not asked again after a second", 2, asked)
        // A day wound back is a different day's weather, at once.
        world.atMs = noon() - 86_400_000L
        frame(world)
        assertEquals("not asked for the day the world was wound to", 3, asked)
    }
}
