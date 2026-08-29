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
import kotlin.math.atan2

/**
 * Whether the ring of hours is telling the truth, view by view.
 *
 * It is not decoration. A ring of numerals round a picture of the earth
 * says: *the place at this angle from the middle is at this hour*. On the
 * two flat views that is exactly true and true by construction — they are
 * azimuthal projections about a pole, so the angle round the disc **is**
 * the longitude from the sun, which **is** the hour.
 *
 * On the ball it is not true at all, and this measures how untrue. That
 * projection is orthographic and tipped twenty-four degrees, so the angle
 * round the disc is not a longitude — and half the longitudes the ring
 * labels are on the far side of the world, which is a scale printed for
 * places that are not in the picture.
 *
 * The owner spotted it by looking at it. This is the number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RimScaleTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** Where a place on the equator really lands, as an angle round the disc. */
    private fun reallyAt(view: Hemisphere.View, hour: Int): DoubleArray {
        // Longitude measured from the sun, so the subsolar meridian is
        // noon by construction — the same frame the ring is laid out in.
        val fromSun = (hour - 12) * 15.0
        val on = Hemisphere.project(view, 0.0, fromSun, 0.0, 0.0)
        return doubleArrayOf(
            Math.toDegrees(atan2(-on[1], on[0])),
            on[2]
        )
    }

    private fun apart(a: Double, b: Double): Double {
        val d = Math.abs(Hemisphere.wrap(a - b))
        return minOf(d, 360.0 - d)
    }

    /**
     * On the flat views every numeral is over the place it names, to
     * within a rounding error.
     *
     * Which is the reason the ring exists there and why it must not be
     * taken away from them by a fix aimed at the ball.
     */
    @Test
    fun `on the flat views the ring is exact`() {
        for (view in listOf(Hemisphere.View.NORTH, Hemisphere.View.SOUTH)) {
            for (hour in 0..23) {
                val (bearing, _) = reallyAt(view, hour).let { it[0] to it[1] }
                assertEquals(
                    "$view, $hour o'clock",
                    0.0, apart(Hemisphere.bearingOfHour(view, hour), bearing), 0.001
                )
            }
            assertTrue("$view lost its ring", Hemisphere.hasRimScale(view))
        }
    }

    /**
     * On the ball it is out by an hour and a half, and half of it is
     * pointing at the far side of the world.
     *
     * Both halves are why it goes. Twenty-three degrees is an hour and a
     * half on a twenty-four hour ring — not a rounding error, a wrong
     * time — and a numeral for a longitude that is not in the picture is
     * a scale for something nobody can see.
     */
    @Test
    fun `on the ball the ring is neither exact nor about the visible world`() {
        var worst = 0.0
        var hidden = 0
        for (hour in 0..23) {
            val answer = reallyAt(Hemisphere.View.GLOBE, hour)
            worst = maxOf(worst, apart(Hemisphere.bearingOfHour(Hemisphere.View.GLOBE, hour), answer[0]))
            if (answer[1] <= 0.0) hidden++
        }
        assertTrue("the ball's ring is nearly right after all: $worst°", worst > 20.0)
        assertTrue("every hour is on the near side: $hidden", hidden >= 10)
        assertTrue("the ball is still drawing it", !Hemisphere.hasRimScale(Hemisphere.View.GLOBE))
    }

    private fun globe(which: Hemisphere.View, ring: Boolean, notches: Boolean): Bitmap {
        val view = HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            this.view = which
            hourRing = ring
            hourNumbers = ring
            meridians = notches
            latitude = 40.4
            longitude = -3.7
            located = true
            atMs = java.util.Calendar.getInstance().apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 900, 900)
        }
        return Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
            .also { view.draw(Canvas(it)) }
    }

    private fun differ(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        }
        return n
    }

    /**
     * So on the ball the three rim switches change nothing at all, and on
     * a flat view every one of them still does.
     *
     * The strongest way to say "it is not drawn": the same picture comes
     * out whichever way the switches are set.
     */
    @Test
    fun `the rim switches do nothing on the ball and everything on a flat view`() {
        assertEquals(
            "something round the rim is still drawn on the ball",
            0,
            differ(
                globe(Hemisphere.View.GLOBE, ring = true, notches = true),
                globe(Hemisphere.View.GLOBE, ring = false, notches = false)
            )
        )
        assertTrue(
            "the flat view lost its ring along with the ball's",
            differ(
                globe(Hemisphere.View.NORTH, ring = true, notches = true),
                globe(Hemisphere.View.NORTH, ring = false, notches = false)
            ) > 200
        )
    }

    /**
     * And the sun is still on the screen with the ring switched off.
     *
     * It was not, and had not been since the switch existed: with no ring
     * the disc grows, the sun mark is placed at a fraction of the grown
     * radius, and the product of the two was past the edge of the view.
     * Nobody saw it because nobody had turned the ring off and looked.
     */
    @Test
    fun `the sun mark is inside the picture with no ring`() {
        for (view in Hemisphere.View.entries) {
            val bare = globe(view, ring = false, notches = false)
            // The sun is the only warm thing on a face of blue and white.
            var found = 0
            for (y in 0 until 900) {
                for (x in 0 until 900) {
                    val p = bare.getPixel(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (r > 200 && g > 140 && b < 120) found++
                }
            }
            assertTrue("$view drew its sun off the side of the view: $found", found > 200)
        }
    }
}
