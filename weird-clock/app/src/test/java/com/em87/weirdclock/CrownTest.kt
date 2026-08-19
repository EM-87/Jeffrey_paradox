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
     * Reset puts the length back rather than clearing it.
     *
     * Reset on a kitchen timer means "again", and the length you want again
     * is the one you just used. Winding three minutes back on by hand every
     * time is the thing a reset button exists to save you.
     */
    @Test
    fun `reset puts the length back rather than clearing it`() {
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
                "three minutes had to be wound back on by hand",
                3 * 60_000L, activity.countdownRemainingForTest()
            )
        }
    }
}
