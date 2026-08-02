package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the marks on the dial say, measured off the dial.
 *
 * Three of these behaviours are invisible to a compiler and easy to claim
 * without checking: that a calendar mark is ringed, that two marks on the
 * same spot mix instead of one hiding the other, and that a wedge goes out
 * as the hand crosses it. Each is a rule about pixels, so each is checked
 * in pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialMarksTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        DayNight.configure(context)
    }

    private fun dial(): ClockView = ClockView(context).apply {
        theme = ClockThemes.MIDNIGHT
        showSecondHand = false
        measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 720, 720)
    }

    private fun render(view: ClockView): Bitmap =
        Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun countOf(bitmap: Bitmap, color: Int): Int {
        var n = 0
        for (x in 0 until 720) for (y in 0 until 720) if (bitmap.getPixel(x, y) == color) n++
        return n
    }

    /**
     * The brightest pixel in the small patch of dial where a mark is drawn.
     *
     * Sampled at a known place rather than compared against a bare dial:
     * the hands move between two renders a millisecond apart, so a
     * difference-based metric ends up measuring the minute hand and
     * reporting on the marks. The geometry is the view's own — a circular
     * dial's boundary is half the smaller side times 0.92.
     */
    private fun lumaAt(bitmap: Bitmap, angleDeg: Float, radiusFraction: Float): Int {
        val boundary = 360f * 0.92f
        val a = Math.toRadians(angleDeg.toDouble())
        val cx = 360f + Math.sin(a).toFloat() * boundary * radiusFraction
        val cy = 360f - Math.cos(a).toFloat() * boundary * radiusFraction
        var best = 0
        for (dx in -6..6) for (dy in -6..6) {
            val x = (cx + dx).toInt().coerceIn(0, 719)
            val y = (cy + dy).toInt().coerceIn(0, 719)
            val p = bitmap.getPixel(x, y)
            val luma = (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
            if (luma > best) best = luma
        }
        return best
    }

    /** The brightest point anywhere along a wedge running from [from] to [to]. */
    private fun wedgeLuma(bitmap: Bitmap, from: Float, to: Float): Int {
        var best = 0
        var angle = from + 2f
        while (angle < to - 2f) {
            best = maxOf(best, lumaAt(bitmap, angle, 0.925f))
            angle += 2f
        }
        return best
    }

    /**
     * A dot off the calendar wears a ring; one off an alarm does not. Both
     * keep their fill, because the fill is the other half of the answer.
     */
    @Test
    fun `a calendar dot is ringed and an alarm dot is not`() {
        val plain = dial().apply { alarmMarkers = listOf(DialMark(90f, false, false, "gym")) }
        val dated = dial().apply { alarmMarkers = listOf(DialMark(90f, false, true, "dentist")) }
        val ink = ClockThemes.contrastInk(ClockThemes.MIDNIGHT.face)

        assertEquals(0, countOf(render(plain), ink))
        assertTrue("the ring must actually be drawn", countOf(render(dated), ink) > 20)
    }

    /**
     * Two marks at the same hour used to be one mark: the second covered the
     * first and the dial simply lost an appointment. Now they add.
     */
    @Test
    fun `two marks on the same spot mix instead of hiding each other`() {
        val green = dial().apply { alarmMarkers = listOf(DialMark(90f, false)) }
        val blue = dial().apply { alarmMarkers = listOf(DialMark(90f, true)) }
        val both = dial().apply {
            alarmMarkers = listOf(DialMark(90f, false), DialMark(90f, true))
        }
        val g = lumaAt(render(green), 90f, 1.055f)
        val b = lumaAt(render(blue), 90f, 1.055f)
        val mixed = lumaAt(render(both), 90f, 1.055f)
        // Brighter than *either* alone is the discriminating claim. Merely
        // "brighter than the green one" is satisfied by plain overdraw,
        // since blue is the lighter of the two — the first version of this
        // test asserted exactly that and passed with the blending removed.
        assertTrue("green=$g blue=$b mixed=$mixed", mixed > maxOf(g, b))
        assertNotEquals(ClockThemes.MIDNIGHT.amMark, ClockThemes.MIDNIGHT.pmMark)
    }

    /**
     * A wedge fades as the minute hand crosses it, and is gone once past.
     * Driven off the dial's own clock, so it needs no refresh to be right.
     */
    @Test
    fun `a wedge that has already finished is not drawn`() {
        val now = java.util.Calendar.getInstance()
        val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        // Two hours behind, and two hours ahead, of whatever time it is.
        val past = ((minuteNow - 180) % 1440 + 1440) % 1440
        val ahead = (minuteNow + 180) % 1440

        val spent = dial().apply {
            eventArcs = listOf(DialArc(0f, 30f, false, true, "over", past, past + 60))
        }
        val coming = dial().apply {
            eventArcs = listOf(DialArc(0f, 30f, false, true, "soon", ahead, ahead + 60))
        }
        val ink = ClockThemes.contrastInk(ClockThemes.MIDNIGHT.face)
        assertEquals("a finished event leaves the dial", 0, countOf(render(spent), ink))
        assertTrue("one still to come is on it", countOf(render(coming), ink) > 20)
        assertTrue(wedgeLuma(render(coming), 0f, 30f) > wedgeLuma(render(spent), 0f, 30f))
    }

    /** An event running right now is drawn, but dimmer than one not started. */
    @Test
    fun `a wedge dims as it is crossed`() {
        val now = java.util.Calendar.getInstance()
        val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        // Started three hours ago, ends in one: three quarters spent.
        val running = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 30f, false, false, "now", minuteNow - 180, minuteNow + 60)
            )
        }
        val fresh = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 30f, false, false, "later", minuteNow + 60, minuteNow + 300)
            )
        }
        val spent = wedgeLuma(render(running), 0f, 30f)
        val untouched = wedgeLuma(render(fresh), 0f, 30f)
        assertTrue(
            "a mostly-spent event must be fainter: spent=$spent fresh=$untouched",
            spent < untouched
        )
    }
}
