package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the little world clocks wear.
 *
 * They copy the main dial, which is the right default and the reason this
 * needs testing at all: anything they are supposed to differ on has to be
 * pushed to each of them by hand after the copying, and a setting that is
 * applied before [WorldBubbles.applyStyle] runs is a setting that appears
 * to work until the theme next changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class WorldFaceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.WORLD_CLOCK, true)
            .putStringSet(Prefs.WORLD_TZS, setOf("Europe/Madrid", "Asia/Tokyo"))
            .commit()
    }

    private fun clocks(seconds: Boolean?): List<ClockView> {
        if (seconds != null) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(Prefs.WORLD_SECONDS, seconds).commit()
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        return activity.worldClocksForTest()
    }

    /** There are bubbles at all, or the rest of this proves nothing. */
    @Test
    fun `the chosen cities each get a clock`() {
        assertEquals("two cities did not make two clocks", 2, clocks(null).size)
    }

    /** Left alone they sweep, like the dial they are copied from. */
    @Test
    fun `the little clocks sweep by default`() {
        val little = clocks(null)
        assertTrue("a bubble started without a second hand", little.all { it.showSecondHand })
    }

    /**
     * Switched off, every one of them stops sweeping.
     *
     * Six second hands on six bubbles is six things moving on a screen
     * that already has three, and at two centimetres across a second hand
     * is a hair going round — it says nothing readable and costs a frame
     * every sixteen milliseconds to say it.
     */
    @Test
    fun `the switch takes the second hands off all of them`() {
        val little = clocks(false)
        assertTrue("the bubbles kept their second hands", little.none { it.showSecondHand })
    }

    /**
     * And the switch reaches bubbles that already exist.
     *
     * Two paths lead to the same place and only one of them is the setting
     * being read at startup: a bubble built later has the flag applied to
     * it along with the rest of the style, and a bubble already floating
     * when the switch is flipped has to be told directly. The startup test
     * above goes through the first path whatever the second one does, so
     * it would pass with the second removed — which is the case a person
     * actually meets, standing in the settings screen watching the clocks
     * behind it.
     */
    @Test
    fun `flipping the switch reaches the bubbles already floating`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bubbles = activity.worldBubblesForTest()
        assertTrue("the bubbles started without second hands", activity.worldClocksForTest().all { it.showSecondHand })
        bubbles.secondHands = false
        assertTrue(
            "a bubble already on screen kept its second hand",
            activity.worldClocksForTest().none { it.showSecondHand }
        )
        bubbles.secondHands = true
        assertTrue(
            "and it did not get it back",
            activity.worldClocksForTest().all { it.showSecondHand }
        )
    }

    /**
     * And the big dial keeps its own.
     *
     * The bubbles are ClockViews like the main one, and the tempting
     * implementation — set the flag on the shared style and let the copying
     * carry it — would take the second hand off the clock the whole app is
     * about.
     */
    @Test
    fun `the main dial keeps its second hand`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WORLD_SECONDS, false).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(
            "switching the bubbles' second hands off stopped the main dial too",
            activity.clockForTest().showSecondHand
        )
        assertTrue(activity.worldClocksForTest().none { it.showSecondHand })
    }
}
