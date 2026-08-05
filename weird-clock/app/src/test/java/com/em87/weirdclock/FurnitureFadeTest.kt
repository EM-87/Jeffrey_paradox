package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * What the rest of the dial does while the hands are travelling.
 *
 * The hand-over moves the hands and only the hands: everything else the
 * face carries — the date, the marks, the sky, the readout — snapped into
 * place on the first frame, so an arrival read as two events, a face
 * appearing and then some hands catching up with it. And the crown, which
 * does know how to fade out, faded out on a card that had already been cut
 * away with its page, so going out it grew in and coming back it simply
 * stopped existing.
 *
 * Both are claims about single frames, which is exactly the sort of thing
 * that is easy to assert and impossible to see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
// Robolectric's default canvas records nothing, so a pixel comparison would
// happily report that two blank images match.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FurnitureFadeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        DayNight.configure(context)
    }

    /** A quarter past seven, fixed. */
    private val fixed = 7 * 3_600_000L + 15 * 60_000L

    /**
     * A dial showing a fixed time, with its own birth transition already
     * over — a provider starts one, and it is retired when the angles are
     * next *read*, not when the clock says its time is up.
     *
     * Fixed rather than live on purpose: two renders of a running clock a
     * moment apart differ by a second hand, and this test is about pixels.
     */
    private fun dial(buttons: Boolean = false): ClockView =
        ClockView(context).apply {
            chronoProvider = { fixed }
            showMoonPhase = true
            chronoButtons = buttons
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
            handAngleForTest(ClockView.Hand.HOUR)
            // Drawn once, because a face fades its furniture in only when
            // there was a face on screen to replace — a dial being born is
            // not changing into anything.
            draw(Canvas(Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)))
        }

    private fun render(view: ClockView): Bitmap =
        Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (x in 0 until 720 step 2) {
            for (y in 0 until 720 step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return true
            }
        }
        return false
    }

    /**
     * Two dials built and rendered the same way must come out the same, or
     * nothing else in this file means anything.
     */
    @Test
    fun `the same dial twice is the same picture`() {
        assertFalse(differs(render(dial()), render(dial())))
    }

    /** On the first frame of a hand-over the furniture is not there yet. */
    @Test
    fun `the furniture arrives with the hands rather than before them`() {
        val settled = render(dial())

        val arriving = dial()
        arriving.handOverFrom(dial())
        // No time has passed since the hand-over, so it is frame zero.
        assertTrue(
            "at the start of a hand-over the face must not be fully dressed",
            differs(render(arriving), settled)
        )
    }

    /** And by the time they get there it is. */
    @Test
    fun `and it is all the way there when they arrive`() {
        val settled = render(dial())

        val arriving = dial()
        arriving.handOverFrom(dial())
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)

        assertFalse(
            "once the hands have arrived the face must look like any other",
            differs(render(arriving), settled)
        )
    }

    /**
     * The crown is inherited by the dial being handed to, because the dial
     * handing it over is not on screen to fade anything.
     *
     * The control is handed over from a dial with no crown: it carries the
     * identical transition, so its furniture is at the identical fraction
     * of the identical fade. The crown is then the only thing that differs
     * between the two pictures — otherwise this test would pass on the
     * strength of the fade it is not testing.
     */
    /**
     * A dial handed over from one that did or did not have a crown, drawn
     * [after] milliseconds into the journey.
     *
     * Built and rendered in one go, deliberately: building a dial idles two
     * seconds of the clock, so holding one while another is built leaves
     * the first one's transition already finished, and the two pictures
     * then differ for a reason that has nothing to do with any crown.
     */
    private fun arrival(fromCrown: Boolean, after: Long = 0L): Bitmap {
        val arriving = dial()
        arriving.handOverFrom(dial(buttons = fromCrown))
        if (after > 0L) ShadowLooper.idleMainLooper(after, TimeUnit.MILLISECONDS)
        return render(arriving)
    }

    @Test
    fun `a crown handed over dissolves on the dial that receives it`() {
        assertFalse(
            "the control must be reproducible",
            differs(arrival(fromCrown = false), arrival(fromCrown = false))
        )
        assertTrue(
            "a dial handed a crown must draw it",
            differs(arrival(fromCrown = false), arrival(fromCrown = true))
        )
    }

    /** And it is gone by the end, like any other fade. */
    @Test
    fun `and the inherited crown is gone once the fade is over`() {
        assertFalse(
            "the crown must not still be sitting on a clock",
            differs(arrival(fromCrown = false, after = 2000L), arrival(fromCrown = true, after = 2000L))
        )
    }
}
