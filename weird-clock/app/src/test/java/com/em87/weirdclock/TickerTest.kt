package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tick, and keeping it on the second.
 *
 * Ticks were arriving late and occasionally two seconds apart. They were
 * posted on the thread that draws the dial, and a dial with planets on it
 * and pieces rolling about the case is not cheap to draw — so a long frame
 * carried the tick along with it. The timing now runs on a thread of its
 * own; the arithmetic it runs on is [Ticker], and this is where the
 * arithmetic is held to the second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class TickerTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------ the arithmetic

    @Test
    fun `the next tick is at the next whole second`() {
        assertEquals(1000L, Ticker.delayToNext(0L))
        assertEquals(700L, Ticker.delayToNext(1_300L))
        assertEquals(1L, Ticker.delayToNext(9_999L))
        assertEquals(1000L, Ticker.delayToNext(5_000L))
    }

    /**
     * Landing exactly on the second asks for a whole second, not for none.
     *
     * A delay of zero is a runnable that reposts itself immediately and
     * goes round for ever, which on the audio thread is a tick that becomes
     * a tone.
     */
    @Test
    fun `a tick that lands dead on the second does not spin`() {
        for (ms in listOf(0L, 1000L, 60_000L, 1_755_000_000_000L)) {
            assertTrue("delay was ${Ticker.delayToNext(ms)} at $ms", Ticker.delayToNext(ms) > 0L)
        }
    }

    /** And it never asks for longer than a second. */
    @Test
    fun `no delay is longer than the period`() {
        var ms = 0L
        while (ms < 3000L) {
            val d = Ticker.delayToNext(ms)
            assertTrue("$d ms at $ms", d in 1L..1000L)
            ms += 37L
        }
    }

    /**
     * A late tick is played once, not made up for.
     *
     * Two ticks in quick succession sound worse than one that was late: a
     * clock that stutters once is a clock, a clock that machine-guns is
     * broken. So the count of missed ticks is for knowing, not for
     * catching up.
     */
    @Test
    fun `missed ticks are counted, not repaid`() {
        assertEquals("nothing missed", 0, Ticker.missed(1_000L, 2_000L))
        assertEquals("one whole second went by unheard", 1, Ticker.missed(1_000L, 3_000L))
        assertEquals(3, Ticker.missed(1_000L, 5_000L))
        assertEquals("no history, nothing missed", 0, Ticker.missed(0L, 9_000L))
    }

    /**
     * A late tick is played; only one that has lost its second entirely is
     * dropped.
     *
     * This started out refusing anything more than a quarter second late,
     * which turned every late tick into a missing one — and a clock that
     * misses a tick is a worse clock than one that ticks a little late.
     * What is still refused is a tick so late that the next one is about to
     * land, because playing it would put two of them almost together.
     */
    @Test
    fun `only a tick that has lost its second entirely is dropped`() {
        assertTrue("dead on", Ticker.onTime(4_000L))
        assertTrue("a shade late", Ticker.onTime(4_100L))
        assertTrue("half a second late is still this second's tick", Ticker.onTime(4_500L))
        assertTrue("three quarters late", Ticker.onTime(4_750L))
        assertFalse("the next one is already due", Ticker.onTime(4_900L))
        assertFalse("and this is simply the next one, early", Ticker.onTime(3_950L))
    }

    // ---------------------------------------------------------- the wiring

    /**
     * The dial decides whether to tick; the sound thread only obeys.
     *
     * Every reason not to tick — the hand is off, the hand is held, another
     * card is showing, the planets have the dial — is a question about
     * views, and views may only be asked on the thread that owns them. So
     * the answer is worked out once a second on the main thread and left
     * where the sound thread can find it.
     */
    @Test
    fun `the planets silence the second hand`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.TICKING, true)
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.CLOCK)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1200))
            assertTrue("a clock with a second hand is not ticking", activity.ticksWantedForTest())

            activity.clockForTest().toggleOrrery()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1200))
            assertFalse(
                "the second hand went on ticking behind the planets",
                activity.ticksWantedForTest()
            )
        }
    }

    /**
     * A beat laid out in advance does not drift.
     *
     * The whole of the second fix: every tick's time is a multiple of a
     * second from one fixed point, so a callback that arrives late does not
     * move the one after it. Asking "how long until the next second?" each
     * time round instead let every late callback push the next one later
     * still.
     */
    @Test
    fun `a beat laid out in advance does not drift`() {
        val anchor = 5_000L
        for (beat in 0..3600L) {
            assertEquals(
                "beat $beat has wandered off the second",
                anchor + beat * 1000L, Ticker.beatAt(anchor, beat)
            )
        }
    }

    /**
     * And it is re-laid when the two clocks part company.
     *
     * The anchor is in uptime and the seconds it lands on are wall-clock
     * seconds, and those are not the same clock: uptime stops in deep sleep
     * and the wall clock is corrected from the network. Left alone, the
     * tick would end up sounding halfway between two seconds while the hand
     * stepped on them.
     */
    @Test
    fun `a tick that has drifted off the second is re-laid`() {
        assertFalse("dead on the second", Ticker.needsResync(9_000L))
        assertFalse("a hair early", Ticker.needsResync(8_950L))
        assertFalse("a hair late", Ticker.needsResync(9_050L))
        assertTrue("a third of a second adrift", Ticker.needsResync(9_330L))
        assertTrue("and adrift the other way", Ticker.needsResync(8_700L))
    }

    /** The drift is signed: early is negative, late is positive. */
    @Test
    fun `drift says which side of the second it is on`() {
        assertEquals(0L, Ticker.driftMs(4_000L))
        assertEquals(40L, Ticker.driftMs(4_040L))
        assertEquals(-60L, Ticker.driftMs(3_940L))
    }

    // ------------------------------------------------- how loud it is

    /**
     * The tick turns down for the night with everything else on the dial.
     *
     * The level that reads as "present" across a room in the afternoon
     * reads as "loud" in the dark with your ear a foot from the phone —
     * and the dial, the marks, the planets and the surround all dim for
     * the night already. The one sound the clock makes did not.
     */
    @Test
    fun `the tick is quieter at night`() {
        assertTrue(
            "the night tick is no quieter than the day one",
            Ticker.tickVolume(night = true) < Ticker.tickVolume(night = false)
        )
    }

    /**
     * And it is quieter, not silent.
     *
     * A clock that ticks in a bedroom is the whole point of a ticking
     * clock. Turning it off after dark would be turning the feature off at
     * the hour somebody switched it on for.
     */
    @Test
    fun `the tick does not go silent at night`() {
        assertTrue(
            "the tick is inaudible at night, which is not turning it down",
            Ticker.tickVolume(night = true) > 0.1f
        )
    }

    /**
     * And the dial asks for the night level when the dial is dim.
     *
     * The rule above is arithmetic; this is the wiring. It reaches as far
     * as the function the beat calls and no further — a beat that ignored
     * that function and passed a number of its own would still slip past,
     * which is worth saying out loud rather than implying.
     */
    @Test
    fun `a dim dial ticks at the night level`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val thisHour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        for (night in listOf(false, true)) {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.NIGHT_DIM, night)
                // A window that starts on this very hour and runs for two,
                // so it is night whenever this test happens to run. A
                // window from midnight to midnight is not the whole day —
                // it is nothing, which is the sensible reading of a pair of
                // pins that have been dragged on top of one another.
                .putInt(Prefs.NIGHT_FROM, thisHour)
                .putInt(Prefs.NIGHT_TO, (thisHour + 2) % 24)
                .commit()
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                assertEquals(
                    "with night mode ${if (night) "on" else "off"}",
                    Ticker.tickVolume(night), c.get().tickLevelForTest(), 0.001f
                )
            }
        }
    }
}
