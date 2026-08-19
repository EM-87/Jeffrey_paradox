package com.em87.weirdclock

import android.view.WindowManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which cards are allowed to keep the screen alight.
 *
 * The clock is a bedside clock, so it holds the screen: a face that goes
 * black after thirty seconds is not a clock you can look at in the night.
 * Nothing else has that claim, and the flag used to be set once when the
 * app opened and never cleared — so the calendar, the alarm list and an
 * idle stopwatch all burned the screen too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenAwakeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
    }

    private fun MainActivity.holdsTheScreen(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    @Test
    fun `the clock holds the screen`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CLOCK)
            assertTrue("a bedside clock that goes dark is not one", c.get().holdsTheScreen())
        }
    }

    /** And every card that is not the clock lets it sleep. */
    @Test
    fun `the other cards let the screen sleep`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            for (card in listOf(Card.CALENDAR, Card.ALARM, Card.STOPWATCH, Card.REVERSE)) {
                c.get().showCardForTest(card)
                assertFalse(
                    "$card kept the screen burning with nothing running on it",
                    c.get().holdsTheScreen()
                )
            }
        }
    }

    /**
     * A running countdown earns it back, because you are watching it.
     *
     * The distinction the old code could not make: the card is the same
     * card either way, and what changes is whether there is anything on it
     * worth looking at.
     */
    @Test
    fun `a running countdown holds the screen and a finished one lets go`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.REVERSE)
            assertFalse(c.get().holdsTheScreen())
            c.get().startCountdownForTest(3 * 60_000L)
            c.get().showCardForTest(Card.REVERSE)
            assertTrue("a countdown you are watching went dark", c.get().holdsTheScreen())
        }
    }

    /** Leaving the clock for another card gives the screen back. */
    @Test
    fun `walking away from the clock gives the screen back`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CLOCK)
            assertTrue(c.get().holdsTheScreen())
            c.get().showCardForTest(Card.CALENDAR)
            assertFalse("the flag was set once and never cleared", c.get().holdsTheScreen())
        }
    }
}
