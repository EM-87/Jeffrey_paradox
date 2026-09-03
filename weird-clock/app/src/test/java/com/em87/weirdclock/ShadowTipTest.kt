package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The end of the gnomon's shadow, which was cut off square.
 *
 * The sides of it were soft from the first version — three passes, each
 * wider and fainter than the last, which is a penumbra. The far end was
 * not: the shape was a quadrilateral and its last edge was a straight
 * line across, so a low sun drew a long black plank on the stone with a
 * sawn end. It is the one place on that dial where the drawing said
 * something false about light, and the tip is where a reader's eye goes,
 * because it is the part that moves furthest.
 *
 * Measured by walking down the shadow from its foot and reading how dark
 * the plate is under it. A shadow that ends must be at its faintest where
 * it ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShadowTipTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val side = 1000

    /**
     * Half past two on a June afternoon.
     *
     * Chosen because the shadow ends *on the plate* then. A low evening
     * sun throws one that runs off the edge, and a shadow running off the
     * edge has no tip to look at — it is cut by the rim of the stone,
     * which is a real edge and belongs there. The fault is only ever
     * visible when the sun is high enough for the whole shadow to fit,
     * which is most of the day.
     */
    private fun highSun(): Long =
        java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JUNE, 21, 14, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun shot(): Pair<Bitmap, FloatArray> {
        val view = SundialView(context).apply {
            theme = ClockThemes.IVORY
            kind = Sundial.Kind.HORIZONTAL
            plate = Sundial.Plate.ROUND
            latitude = 40.4
            longitude = -3.7
            // Off, so nothing is cut into the stone anywhere near the
            // line this walks down.
            motto = false
            halfHours = false
            atMs = highSun()
            measure(
                View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, side, side)
        }
        val map = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(map))
        val ray = view.shadowRay
        assertNotNull("the sun was up: there should be a shadow", ray)
        return map to ray!!
    }

    /**
     * How dark the picture is on the shadow's own axis, [along] of the way
     * to its end.
     *
     * On the axis and barely off it — three pixels either side — because
     * every other dark thing on this plate radiates from the same point
     * the shadow does. A slice taken *across* the shadow at any distance
     * from the foot crosses the hour lines either side of it, and the
     * first version of this measured the mason's chisel and reported that
     * the shadow was at its darkest two thirds of the way along.
     */
    private fun darkestOn(map: Bitmap, ray: FloatArray, along: Float): Int {
        val (cx, cy, dx) = Triple(ray[0], ray[1], ray[2])
        val dy = ray[3]
        val reach = ray[4]
        var darkest = 255
        var off = -3f
        while (off <= 3f) {
            val x = (cx + dx * reach * along - dy * off).toInt()
            val y = (cy + dy * reach * along + dx * off).toInt()
            if (x in 0 until side && y in 0 until side) {
                val p = map.getPixel(x, y)
                val v = minOf(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
                darkest = minOf(darkest, v)
            }
            off += 1f
        }
        return darkest
    }

    @Test
    fun `the shadow gives out at its tip instead of stopping`() {
        val (map, ray) = shot()
        // The stone itself: the commonest colour inside the plate. Taken
        // as a count rather than sampled at a point, because the first
        // version of this picked one spot and the spot it picked was on
        // an hour line, so the test decided the stone was black.
        val tally = HashMap<Int, Int>()
        var y = 0
        while (y < side) {
            var x = 0
            while (x < side) {
                if (kotlin.math.hypot(x - side / 2.0, y - side / 2.0) < side * 0.30) {
                    val p = map.getPixel(x, y)
                    tally[p] = (tally[p] ?: 0) + 1
                }
                x += 2
            }
            y += 2
        }
        val stone = tally.maxByOrNull { it.value }!!.key
        val plate = minOf(stone shr 16 and 0xFF, stone shr 8 and 0xFF, stone and 0xFF)

        val root = darkestOn(map, ray, 0.35f)
        val middle = darkestOn(map, ray, 0.70f)
        val tip = darkestOn(map, ray, 1.00f)

        // There is a shadow at all: it is properly darker than the stone
        // where it starts. Without this the rest would pass on a picture
        // with nothing drawn on it.
        assertTrue(
            "no shadow at the root: stone $plate, shadow $root",
            root < plate - 60
        )
        // And it gives out along its length rather than stopping on an
        // edge. Both steps, because a shadow that only lightened over the
        // last few pixels would be a square end with a bevel on it.
        assertTrue(
            "the shadow does not fade: root $root, middle $middle, tip $tip",
            middle > root + 30 && tip > middle + 30
        )
        // And at the end it is most of the way back to the stone. This is
        // the number the square end failed: it was as dark there as it
        // was in the middle.
        assertTrue(
            "the shadow still has an end on it: tip $tip, root $root, stone $plate",
            tip > root + 120
        )
    }

    /**
     * And the tilt reaches the drawing.
     *
     * The arithmetic is measured in [SundialTiltTest]; what is measured
     * here is that the view is wired to it, which is the half that a pure
     * test cannot see. Two shadows on the same dial at the same instant,
     * one from a phone lying flat and one from a phone leaned back twenty
     * degrees, and they have to be in different places — for a version
     * the second one was the first, because only a bearing was ever
     * handed over.
     */
    @Test
    fun `leaning the phone moves the shadow on the plate`() {
        fun dial(orientation: FloatArray?): FloatArray? {
            val view = SundialView(context).apply {
                theme = ClockThemes.IVORY
                kind = Sundial.Kind.HORIZONTAL
                plate = Sundial.Plate.ROUND
                latitude = 40.4
                longitude = -3.7
                motto = false
                halfHours = false
                compass = true
                phoneBearing = 0.0
                phoneOrientation = orientation
                atMs = highSun()
                measure(
                    View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, side, side)
            }
            val map = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(map))
            map.recycle()
            return view.shadowRay
        }
        // A rotation matrix, by columns: the device's right, top and face
        // in east, north and up. Flat first, then leaned back twenty
        // degrees about its own right edge.
        val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val p = Math.toRadians(20.0)
        val leaned = floatArrayOf(
            1f, 0f, 0f,
            0f, Math.cos(p).toFloat(), -Math.sin(p).toFloat(),
            0f, Math.sin(p).toFloat(), Math.cos(p).toFloat()
        )
        val level = dial(flat)
        val tilted = dial(leaned)
        assertNotNull("the flat dial drew no shadow", level)
        assertNotNull("the leaned dial drew no shadow", tilted)
        val turned = kotlin.math.abs(
            Math.toDegrees(
                Math.atan2(tilted!![3].toDouble(), tilted[2].toDouble()) -
                    Math.atan2(level!![3].toDouble(), level[2].toDouble())
            )
        )
        assertTrue("leaning the phone did nothing to the shadow: $turned°", turned > 1.0)
    }
}
