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

    /**
     * Pixels of exactly [color] in a small box around a point on the dial.
     *
     * Around a point, not over the whole bitmap: the ring is drawn in the
     * numerals' own ink now — which is the fix for it glaring — so counting
     * that colour across the face counts the numerals and the ticks too. An
     * earlier version of this test did exactly that and started failing the
     * moment the ink stopped being a colour nothing else used.
     */
    private fun inkNear(bitmap: Bitmap, angleDeg: Float, radiusFraction: Float, color: Int): Int {
        val boundary = 360f * 0.92f
        val a = Math.toRadians(angleDeg.toDouble())
        val cx = 360f + Math.sin(a).toFloat() * boundary * radiusFraction
        val cy = 360f - Math.cos(a).toFloat() * boundary * radiusFraction
        var n = 0
        for (dx in -22..22) for (dy in -22..22) {
            val x = (cx + dx).toInt().coerceIn(0, 719)
            val y = (cy + dy).toInt().coerceIn(0, 719)
            if (bitmap.getPixel(x, y) == color) n++
        }
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
        val ink = ClockThemes.contrastInk(ClockThemes.MIDNIGHT)

        assertEquals(0, inkNear(render(plain), 90f, 1.055f, ink))
        assertTrue(
            "the ring must actually be drawn",
            inkNear(render(dated), 90f, 1.055f, ink) > 10
        )
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
        // Three hours behind, and three ahead, of whatever time it is —
        // not folded into a day. An event that began yesterday evening has
        // a negative start minute and one that ends tomorrow morning a
        // start past 1440, and both are ordinary; folding them was what
        // made this test depend on the hour it happened to run at, and it
        // duly failed the first time it ran at half past eleven at night.
        val past = minuteNow - 180
        val ahead = minuteNow + 180

        val spent = dial().apply {
            eventArcs = listOf(DialArc(0f, 30f, false, true, "over", startMinute = past, endMinute = past + 60))
        }
        val coming = dial().apply {
            eventArcs = listOf(DialArc(0f, 30f, false, true, "soon", startMinute = ahead, endMinute = ahead + 60))
        }
        val bare = render(dial())
        assertEquals(
            "a finished event leaves the dial with nothing on it",
            0, wedgeExtent(bare, render(spent), 1f, 29f)
        )
        assertTrue("one still to come is on it", wedgeExtent(bare, render(coming), 1f, 29f) > 8)
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
                DialArc(0f, 30f, false, false, "now", startMinute = minuteNow - 180, endMinute = minuteNow + 60)
            )
        }
        val fresh = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 30f, false, false, "later", startMinute = minuteNow + 60, endMinute = minuteNow + 300)
            )
        }
        val spent = wedgeLuma(render(running), 0f, 30f)
        val untouched = wedgeLuma(render(fresh), 0f, 30f)
        assertTrue(
            "a mostly-spent event must be fainter: spent=$spent fresh=$untouched",
            spent < untouched
        )
    }

    /**
     * How many degrees of the wedge band the marks actually paint.
     *
     * Measured against a bare dial degree by degree, not against an
     * absolute brightness: the hour ticks live in this same band and are
     * near-white, so the first version of this counted ticks and reported
     * them as wedge.
     */
    private fun wedgeExtent(bare: Bitmap, marked: Bitmap, from: Float, to: Float): Int {
        var lit = 0
        var angle = from
        while (angle <= to) {
            if (lumaAt(marked, angle, 0.925f) > lumaAt(bare, angle, 0.925f) + 30) lit++
            angle += 1f
        }
        return lit
    }

    /**
     * The fade the user actually asked for: the wedge is eaten from its
     * start as the hand crosses it, so what is left on the face is the time
     * left. The first version dimmed the whole thing uniformly, which said
     * "going away" without saying how much had gone.
     */
    @Test
    fun `a wedge is eaten from its start, not dimmed as a whole`() {
        val now = java.util.Calendar.getInstance()
        val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        val fresh = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 40f, false, false, "later", startMinute = minuteNow + 120, endMinute = minuteNow + 360)
            )
        }
        // Three quarters gone: only the last quarter should still be there.
        val running = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 40f, false, false, "now", startMinute = minuteNow - 180, endMinute = minuteNow + 60)
            )
        }
        val bare = render(dial())
        val whole = wedgeExtent(bare, render(fresh), 1f, 39f)
        val left = wedgeExtent(bare, render(running), 1f, 39f)
        // Not every degree of the band registers — the hour ticks under it
        // are already near-white and adding to them changes nothing — so
        // the counts are compared with each other, not against the sweep.
        assertTrue("nothing was drawn at all: whole=$whole", whole > 12)
        assertTrue(
            "three quarters spent must leave well under half: $whole then $left",
            left < whole / 2
        )
        assertTrue("but not gone: $left", left > 1)
    }

    /** And what is left sits at the far end, where the time still to come is. */
    @Test
    fun `what is left of a wedge is its tail, not its head`() {
        val now = java.util.Calendar.getInstance()
        val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        val running = dial().apply {
            eventArcs = listOf(
                DialArc(0f, 40f, false, false, "now", startMinute = minuteNow - 180, endMinute = minuteNow + 60)
            )
        }
        val bare = render(dial())
        val image = render(running)
        assertTrue(
            "the head is eaten: ${wedgeExtent(bare, image, 2f, 20f)} degrees still lit",
            wedgeExtent(bare, image, 2f, 20f) <= 2
        )
        assertTrue("the tail is still there", wedgeExtent(bare, image, 32f, 38f) > 2)
    }

    /**
     * The ring's ink was a hardcoded white chosen by measuring the face's
     * brightness, and night mode never touched it: after ten at night the
     * whole dial dropped to thirty per cent and the rings stayed full
     * white, sitting on it like stars.
     */
    @Test
    fun `the ring dims with the dial and never outshines it`() {
        for (theme in listOf(ClockThemes.MIDNIGHT, ClockThemes.DAYLIGHT, ClockThemes.IVORY)) {
            val ink = ClockThemes.contrastInk(theme)
            val dimmed = ClockThemes.contrastInk(ClockThemes.dim(theme))
            fun luma(c: Int) =
                (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
            assertTrue("night mode must reach it too", luma(dimmed) < luma(ink))
            // And it never stands out more than the numerals do — measured
            // against the face, because on a pale dial "stands out" means
            // darker, not brighter. Pure white failed this on every dark
            // theme, which is exactly how it came to look like a star.
            val faceLuma = luma(theme.face)
            assertTrue(
                "the ring must not shout louder than the numerals",
                kotlin.math.abs(luma(ink) - faceLuma) <=
                    kotlin.math.abs(luma(theme.numeral) - faceLuma)
            )
        }
    }

    /** On a pale face it goes dark, as the numerals do. */
    @Test
    fun `the ring follows the face into the light`() {
        fun luma(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
        assertTrue(luma(ClockThemes.contrastInk(ClockThemes.DAYLIGHT)) < 300)
        assertTrue(luma(ClockThemes.contrastInk(ClockThemes.MIDNIGHT)) > 400)
    }
}
