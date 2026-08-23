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
    private fun dial(
        buttons: Boolean = false,
        moon: Boolean = true,
        warm: Boolean = true,
        second: Boolean = true,
        at: Long = fixed
    ): ClockView =
        ClockView(context).apply {
            chronoProvider = { at }
            showMoonPhase = moon
            chronoButtons = buttons
            showSecondHand = second
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
            handAngleForTest(ClockView.Hand.HOUR)
            // Drawn once, like a dial that has been looked at. A card's
            // dial is GONE until you first go to it, and a view that is not
            // visible never draws at all — see the first-visit test below.
            if (warm) draw(Canvas(Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)))
        }

    private fun render(view: ClockView): Bitmap =
        Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    /** How far apart two pictures are, summed over every channel. */
    private fun distance(a: Bitmap, b: Bitmap): Long {
        var total = 0L
        for (x in 0 until 720 step 2) {
            for (y in 0 until 720 step 2) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                for (shift in intArrayOf(0, 8, 16, 24)) {
                    total += kotlin.math.abs(((p shr shift) and 0xFF) - ((q shr shift) and 0xFF))
                }
            }
        }
        return total
    }

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (x in 0 until 720 step 2) {
            for (y in 0 until 720 step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return true
            }
        }
        return false
    }

    /**
     * Where two pictures differ, for a failure message.
     *
     * Every assertion here is "these two pictures are the same" or "these
     * two are not", and when one of them is wrong the report is a boolean:
     * something differed, somewhere, by something. This class went out of
     * step once during a full run and could not be made to do it again in
     * isolation — a hundred and twenty repetitions of the same comparison
     * came out identical every time — which means whatever moved was left
     * behind by another class in the same sandbox. So the message carries
     * the evidence: how many samples moved, the box they moved in, and
     * what colour they went from and to. A red line across the middle is a
     * second hand; a small patch at the top is the crown.
     */
    private fun where(a: Bitmap, b: Bitmap): String {
        var n = 0
        var minX = 720
        var maxX = -1
        var minY = 720
        var maxY = -1
        val samples = ArrayList<String>()
        for (x in 0 until 720 step 2) {
            for (y in 0 until 720 step 2) {
                if (a.getPixel(x, y) == b.getPixel(x, y)) continue
                n++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (samples.size < 3) {
                    samples += "($x,$y ${hex(a.getPixel(x, y))}->${hex(b.getPixel(x, y))})"
                }
            }
        }
        if (n == 0) return "nothing differed"
        return "$n samples in $minX..$maxX by $minY..$maxY: ${samples.joinToString(" ")}"
    }

    private fun hex(colour: Int): String = Integer.toHexString(colour)

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
     * A dial handed over from one that did or did not have a crown, drawn
     * [after] milliseconds into the journey.
     *
     * Built and rendered in one go, deliberately: building a dial idles two
     * seconds of the clock, so holding one while another is built leaves
     * the first one's transition already finished, and the two pictures
     * then differ for a reason that has nothing to do with any crown.
     */
    private fun arrival(
        fromCrown: Boolean = false,
        fromMoon: Boolean = false,
        moon: Boolean = true,
        warm: Boolean = true,
        second: Boolean = true,
        fromSecond: Boolean = true,
        after: Long = 0L
    ): Bitmap {
        val arriving = dial(moon = moon, warm = warm, second = second)
        arriving.handOverFrom(dial(buttons = fromCrown, moon = fromMoon, second = fromSecond))
        if (after > 0L) ShadowLooper.idleMainLooper(after, TimeUnit.MILLISECONDS)
        return render(arriving)
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
     *
     */
    @Test
    fun `a crown handed over dissolves on the dial that receives it`() {
        val once = arrival(fromCrown = false)
        val twice = arrival(fromCrown = false)
        assertFalse(
            "the control must be reproducible: ${where(once, twice)}",
            differs(once, twice)
        )
        assertTrue(
            "a dial handed a crown must draw it",
            differs(arrival(fromCrown = false), arrival(fromCrown = true))
        )
    }

    /** And it is gone by the end, like any other fade. */
    @Test
    fun `and the inherited crown is gone once the fade is over`() {
        val without = arrival(fromCrown = false, after = 2000L)
        val with = arrival(fromCrown = true, after = 2000L)
        assertFalse(
            "the crown must not still be sitting on a clock: ${where(without, with)}",
            differs(without, with)
        )
    }

    /**
     * The other half of the fade: what the outgoing dial was carrying has
     * to go somewhere to dissolve, and the only dial still on screen is
     * this one.
     *
     * Read through a dial arriving *without* a sky at all. Anything sky
     * shaped in its first frame therefore belongs to the dial it replaced —
     * and the control arrives from a skyless dial, so it carries the same
     * transition at the same point in the same fade and the ghost is the
     * one thing that differs.
     */
    @Test
    fun `what the outgoing dial was carrying fades out on this one`() {
        assertFalse(
            "the control must be reproducible",
            differs(
                arrival(moon = false, fromMoon = false),
                arrival(moon = false, fromMoon = false)
            )
        )
        assertTrue(
            "the sky it is replacing must still be on screen",
            differs(
                arrival(moon = false, fromMoon = false),
                arrival(moon = false, fromMoon = true)
            )
        )
    }

    /**
     * And it really fades, rather than being cut when the hands land.
     *
     * Measured against the control at the same instant, not against itself
     * earlier: the arriving dial's own readout is fading *in* over these
     * same milliseconds, so two frames of the ghosted dial differ from each
     * other whether the ghost fades or not — which is what the first
     * version of this test was quietly reporting. Taking both against a
     * control at the same point in the same journey cancels everything the
     * two dials are doing alike and leaves the ghost.
     */
    @Test
    fun `the ghost gets fainter as the hands travel`() {
        val atStart = distance(
            arrival(moon = false, fromMoon = false, after = 0L),
            arrival(moon = false, fromMoon = true, after = 0L)
        )
        val halfWay = distance(
            arrival(moon = false, fromMoon = false, after = 350L),
            arrival(moon = false, fromMoon = true, after = 350L)
        )
        assertTrue("there must be a ghost to fade at all", atStart > 0)
        assertTrue(
            "half way through the journey it must be fainter: $halfWay vs $atStart",
            halfWay < atStart
        )
    }

    /** And it is gone by the time the hands finish their journey. */
    @Test
    fun `and the ghost is gone once the hands arrive`() {
        assertFalse(
            "nothing of the old dial may be left behind",
            differs(
                arrival(moon = false, fromMoon = false, after = 2000L),
                arrival(moon = false, fromMoon = true, after = 2000L)
            )
        )
    }

    /**
     * A dial nobody has looked at yet fades like any other.
     *
     * The fade was gated on "has this dial drawn before", which is a fair
     * question for a dial changing its own mode and the wrong one for a
     * hand-over: a card's dial is GONE until you first go there and a view
     * that is not visible never draws, so the very first trip to the
     * stopwatch — the one time a new arrival is most worth explaining —
     * was the one trip with no fade at all.
     */
    @Test
    fun `the first visit to a dial fades like every other`() {
        assertTrue(
            "a dial's first frame ever is still an arrival",
            differs(
                arrival(moon = false, fromMoon = false, warm = false),
                arrival(moon = false, fromMoon = true, warm = false)
            )
        )
    }

    // ------------------------------------------------- one clock, one curve

    /**
     * What fades in and what fades out do it at the same rate.
     *
     * The complaint that kept coming back was never that a fade was
     * missing; it was that they did not agree, so whichever finished first
     * looked like the one thing that had been left out. This is that claim
     * measured: the curve is symmetric about its middle, so the ghost a
     * quarter of the way through the journey must be exactly as strong as
     * the arriving face is three quarters of the way through.
     *
     * Both sides are read against a control at the same instant, so
     * everything the two frames are doing alike cancels and what is left is
     * the one thing fading.
     */
    @Test
    fun `what leaves fades at the same rate as what arrives`() {
        val quarter = 250L
        val threeQuarters = TRANSITION_MS - quarter

        val leaving = distance(
            arrival(moon = false, fromMoon = false, after = quarter),
            arrival(moon = false, fromMoon = true, after = quarter)
        )
        val arriving = distance(
            arrival(moon = false, fromMoon = false, after = threeQuarters),
            arrival(moon = true, fromMoon = false, after = threeQuarters)
        )

        assertTrue("there must be something to compare", leaving > 0 && arriving > 0)
        val gap = kotlin.math.abs(leaving - arriving).toDouble() / maxOf(leaving, arriving)
        assertTrue(
            "in and out must match: leaving $leaving, arriving $arriving",
            gap < 0.05
        )
    }

    /**
     * And they run for the same length of time. The crown took 500 ms
     * against the face's 700, so it was always finished a fifth of a second
     * early — which is what "the crown has no fade" looks like from the
     * sofa.
     */
    @Test
    fun `the crown is still fading when the face still is`() {
        val late = TRANSITION_MS - 100L
        assertTrue(
            "at $late ms the crown must still be on screen",
            differs(
                arrival(fromCrown = false, after = late),
                arrival(fromCrown = true, after = late)
            )
        )
    }

    /**
     * The tenths scale is furniture, not a hand: it belongs to the
     * chronograph and not to the clock, and it used to be the one thing
     * that snapped — a ten-division ring appearing in the middle of the
     * dial in a single frame while everything around it was still arriving.
     */
    @Test
    fun `the tenths scale crosses over with the rest of the face`() {
        assertTrue(
            "the scale of the dial being left must still be there",
            differs(
                arrival(second = false, fromSecond = false),
                arrival(second = false, fromSecond = true)
            )
        )
    }

    /**
     * The face fades on the curve the hands travel on, not on a straight
     * line.
     *
     * Both were running for the same seven hundred milliseconds, which is
     * why this was so easy to miss: a linear fade and an eased journey
     * agree at the start, at the middle and at the end, and disagree
     * everywhere else. A quarter of the way in the face was at 25% and the
     * hands at 15%, so the furniture kept arriving ahead of the hands and
     * leaving behind them — the same gesture, told at two speeds.
     *
     * Measured as one against the other rather than either against a
     * number: what is fading out and what is travelling are two halves of
     * one journey, so their fractions must come to one.
     */
    @Test
    fun `the face fades on the same curve the hands travel`() {
        val quarter = 175L
        val elsewhere = 3 * 3_600_000L

        fun ghostAt(after: Long): Long {
            val control = dial(moon = false)
            control.handOverFrom(dial(moon = false, at = elsewhere))
            if (after > 0L) ShadowLooper.idleMainLooper(after, TimeUnit.MILLISECONDS)
            val plain = render(control)

            val ghosted = dial(moon = false)
            ghosted.handOverFrom(dial(moon = true, at = elsewhere))
            if (after > 0L) ShadowLooper.idleMainLooper(after, TimeUnit.MILLISECONDS)
            return distance(plain, render(ghosted))
        }

        val full = ghostAt(0L)
        val left = ghostAt(quarter).toDouble() / full

        // And the hands over the same quarter of the same journey.
        val from = dial(at = elsewhere).handAngleForTest(ClockView.Hand.HOUR)
        val to = dial().handAngleForTest(ClockView.Hand.HOUR)
        val travelling = dial()
        travelling.handOverFrom(dial(at = elsewhere))
        ShadowLooper.idleMainLooper(quarter, TimeUnit.MILLISECONDS)
        val gone = (travelling.handAngleForTest(ClockView.Hand.HOUR) - from) / (to - from)

        assertTrue("the hands must have moved but not arrived: $gone", gone in 0.02f..0.98f)
        assertTrue(
            "what is left of the old face and what the hands have covered " +
                "are two halves of one journey: $left + $gone",
            kotlin.math.abs(left + gone - 1.0) < 0.06
        )
    }

    private companion object {
        /** The one duration the whole gesture runs on. */
        const val TRANSITION_MS = 700L
    }
}
