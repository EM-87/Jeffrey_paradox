package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The winding crown, and the hands running home.
 *
 * The crown is the one control on the case that had no job of its own: it
 * put fallen hands back and tore up the cheater's stamp, and on a tidy dial
 * it did nothing at all except set a cuckoo off. It has work now, and the
 * work is different on each dial — which is why it reports what it found
 * rather than deciding by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class CrownTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
    }

    private fun dial(): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1600)
        }
    }

    // ------------------------------------------------------- what it says

    /**
     * The crown says whether it found anything to put right.
     *
     * That flag is the whole of the difference between the bird and the
     * click, and between "tidy up" and "do the thing this card wants".
     */
    @Test
    fun `the crown reports whether it tidied anything`() {
        val clock = dial()
        val heard = mutableListOf<Boolean>()
        clock.onCrownTap = { heard.add(it) }

        clock.crownTapForTest()
        assertEquals("a tidy dial", listOf(false), heard)

        clock.knockHandsOff()
        assertTrue("nothing fell", clock.isDisarranged())
        clock.crownTapForTest()
        assertEquals("and one with hands on the floor", listOf(false, true), heard)
        assertFalse("the hands are still down", clock.isDisarranged())
    }

    // ----------------------------------------------- the journey to zero

    /**
     * The hands travel back to zero instead of arriving there.
     *
     * Partway through they must be somewhere between the two and at neither
     * end — a cut with a pause in front of it sits at one of them.
     */
    @Test
    fun `the chronograph hands run back to zero rather than jumping`() {
        val clock = dial()
        var value = 90_000L
        clock.chronoProvider = { value }
        assertEquals(90_000L, clock.chronoShownForTest())

        value = 0L
        clock.glideChronoTo(90_000L, 0L)
        assertTrue("it is not travelling", clock.chronoGliding())
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        val midway = clock.chronoShownForTest()
        assertTrue("it jumped to zero", midway > 1_000L)
        assertTrue("it has not set off", midway < 89_000L)

        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        assertEquals("it never arrived", 0L, clock.chronoShownForTest())
        assertFalse("it is still travelling", clock.chronoGliding())
    }

    /** And it asks for the frames it needs to be a journey. */
    @Test
    fun `the journey to zero asks for frames`() {
        val clock = dial()
        clock.chronoProvider = { 0L }
        clock.chronoRunning = false
        // Past anything the dial started on its own when it was handed a
        // chronograph: a hand-over still running would answer yes to this
        // and hide whatever the journey does or does not ask for.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200))
        assertFalse("something else is asking for frames", clock.isAnimatingForTest())
        clock.glideChronoTo(60_000L, 0L)
        assertTrue("nothing was asking for frames", clock.isAnimatingForTest())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200))
        clock.chronoShownForTest()
        assertFalse("it went on asking after arriving", clock.isAnimatingForTest())
    }

    // ------------------------------------------------------ the countdown

    /**
     * A countdown cannot be wound past a day.
     *
     * Beyond that the hands have been round the whole dial twice and the
     * face says nothing anybody can read; a thing you want the day after
     * tomorrow is an alarm, which this app already has and which survives
     * the phone being switched off.
     */
    @Test
    fun `a countdown stops at a day`() {
        val down = Countdown({ 0L }, 0L)
        down.setTo(25L * 60 * 60 * 1000)
        assertEquals(24L * 60 * 60 * 1000, down.remaining())

        down.setTo(90L * 60 * 1000)
        assertEquals("and anything under it is untouched", 90L * 60 * 1000, down.remaining())
    }

    /** The total it draws against is capped with it, not left long. */
    @Test
    fun `the sand is measured against the capped length`() {
        val down = Countdown({ 0L }, 0L)
        down.setTo(80L * 60 * 60 * 1000)
        assertEquals(24L * 60 * 60 * 1000, down.totalMs)
    }

    /**
     * The little marks beside the digits follow the digits.
     *
     * They were chosen from the chronograph's own value while the digits
     * showed the value being wound, so carrying a hand past an hour gave a
     * reading in hours with minute-and-second marks under it until the
     * finger came off.
     */
    @Test
    fun `the marks follow the digits while a hand is being carried`() {
        val clock = dial()
        clock.chronoProvider = { 30_000L }
        clock.chronoSettable = true
        val short = clock.readoutUnits()
        // Wound past the hour: the digits now read hours, so the marks must
        // read hours too.
        clock.glideChronoTo(30_000L, 2 * 3_600_000L)
        // Partway along, where the digits have crossed the hour: at the
        // very first frame they still read half a minute, and the marks
        // would be right for the wrong reason.
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        val long = clock.readoutUnits()
        assertFalse(
            "the marks stayed on minutes and seconds while the digits showed hours",
            short.contentEquals(long)
        )
    }

    /**
     * Reset clears the countdown, the way the stopwatch's reset beside it
     * clears the stopwatch.
     *
     * It used to mean "again" — back to the length last set — which is what
     * a kitchen timer's reset means and is a perfectly good button. It is
     * also the opposite of what the pusher one card away does, and the two
     * dials are the same dial with different hands: a reset that clears on
     * one page and refills on the other is a button whose meaning depends
     * on which way you swiped to get there.
     */
    @Test
    fun `reset clears the countdown`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.REVERSE)
            activity.startCountdownForTest(3 * 60_000L)
            activity.resetCountdownForTest()
            assertEquals(
                "the countdown was refilled instead of cleared",
                0L, activity.countdownRemainingForTest()
            )
        }
    }

    /**
     * And the crown puts the length back, which is where it went.
     *
     * The stopwatch's crown does exactly this with the last race. Nothing
     * is lost by a reset that clears: the length moves to the crown, which
     * is where this watch already keeps its second thoughts, on both cards.
     */
    @Test
    fun `the crown puts the last countdown back`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.REVERSE)
            activity.startCountdownForTest(3 * 60_000L)
            activity.resetCountdownForTest()
            assertEquals(0L, activity.countdownRemainingForTest())
            activity.countdownForTest()!!.crownTapForTest()
            assertEquals(
                "three minutes had to be wound back on by hand",
                3 * 60_000L, activity.countdownRemainingForTest()
            )
        }
    }

    /**
     * The crown does not overwrite a countdown that has something on it.
     *
     * It is an undo for a dial that reads zero, not a "put my last timer
     * back" button that fires whenever it is pressed — the crown's other
     * job is tidying the scene, and that has to stay pressable on a dial
     * with three minutes on it.
     */
    @Test
    fun `the crown leaves a wound countdown alone`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.REVERSE)
            activity.windCountdownForTest(3 * 60_000L)
            activity.windCountdownForTest(5 * 60_000L)
            activity.countdownForTest()!!.crownTapForTest()
            assertEquals(
                "the crown overwrote a countdown that was already set",
                5 * 60_000L, activity.countdownRemainingForTest()
            )
        }
    }

    /**
     * And through the card the pusher clears whatever is on the dial.
     *
     * The dial remembered *two* lengths for a while — three minutes for
     * the tea, five for the eggs — and the reset pusher swapped between
     * them. That only made sense while reset meant "again"; with reset
     * meaning "clear", there is one length worth remembering and it is the
     * one that was just cleared.
     */
    @Test
    fun `the reset pusher clears a wound countdown and the crown restores it`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.REVERSE)
            activity.windCountdownForTest(3 * 60_000L)
            activity.windCountdownForTest(5 * 60_000L)
            assertEquals(
                "winding the hands did not set the countdown",
                5 * 60_000L, activity.countdownRemainingForTest()
            )

            activity.resetCountdownForTest()
            assertEquals(
                "the pusher refilled the dial instead of clearing it",
                0L, activity.countdownRemainingForTest()
            )
            activity.countdownForTest()!!.crownTapForTest()
            assertEquals(
                "the crown gave back the wrong length",
                5 * 60_000L, activity.countdownRemainingForTest()
            )
        }
    }

    // ------------------------------- the crown on a countdown that is running

    /**
     * On a running countdown the crown says how long it has been going.
     *
     * The hands can only show one of the two numbers, and the one they show
     * is what is left — which is the right choice and leaves the other one
     * unaskable. So the crown answers it, in a smaller row under the
     * digits, the way a lap sits under a stopwatch's reading. Press again
     * and it goes; again and it is back.
     *
     * And it must not send the hands home while the thing is running, which
     * is what the crown does on a stopped one and is the last thing anybody
     * wants three minutes into a boiling egg.
     */
    @Test
    fun `the crown counts up under a running countdown`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.REVERSE)
            activity.startCountdownForTest(3 * 60_000L)
            val dial = activity.countdownForTest()!!

            assertEquals(
                "the second row was there before anybody asked for it",
                null, dial.secondReadout?.invoke()
            )

            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            dial.crownTapForTest()
            assertEquals(
                "the crown did not say how long it had been running",
                20_000L, dial.secondReadout?.invoke()
            )
            assertEquals(
                "the crown sent the hands home on a countdown that was running",
                2 * 60_000L + 40_000L, activity.countdownRemainingForTest()
            )

            dial.crownTapForTest()
            assertEquals(
                "it would not go away again",
                null, dial.secondReadout?.invoke()
            )
            dial.crownTapForTest()
            assertEquals(
                "and it would not come back",
                20_000L, dial.secondReadout?.invoke()
            )
        }
    }

    /**
     * Pressing reset twice does not lose the memory of the first.
     *
     * The guard that stops it is one line — the length is remembered only
     * when there is one to remember — and without it the second press
     * writes zero over three minutes and the crown has nothing to give
     * back. The stopwatch has the same line for the same reason.
     */
    @Test
    fun `pressing reset twice keeps the length`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.REVERSE)
            activity.windCountdownForTest(3 * 60_000L)
            activity.resetCountdownForTest()
            activity.resetCountdownForTest()
            assertEquals(
                "the second press wrote nothing over the length",
                3 * 60_000L, activity.lastCountdownForTest()
            )
            activity.countdownForTest()!!.crownTapForTest()
            assertEquals(
                "the crown had nothing left to give back",
                3 * 60_000L, activity.countdownRemainingForTest()
            )
        }
    }
}
