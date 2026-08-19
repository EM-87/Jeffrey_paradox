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
     * A tick most of a second late belongs to the second after it.
     *
     * Playing it anyway would put two ticks nearly on top of one another,
     * because the next one is about to arrive on time.
     */
    @Test
    fun `a tick that has lost its second is not played`() {
        assertTrue("dead on", Ticker.onTime(4_000L))
        assertTrue("a shade early", Ticker.onTime(3_900L))
        assertTrue("a shade late", Ticker.onTime(4_100L))
        assertFalse("half a second adrift", Ticker.onTime(4_500L))
        assertFalse("most of a second adrift", Ticker.onTime(4_600L))
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
}
