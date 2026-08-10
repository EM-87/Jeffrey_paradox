package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bells: the feature people actually mention, and the one thing in
 * here nobody wants quietly changed.
 *
 * The striking rule was written out three times — in the app, in the
 * service that rings with the app shut, and in the settings preview — and
 * the only way to catch two of them disagreeing was to sit through an
 * hour. Now there is one, and this is it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BellsTest {

    private val ALL = arrayOf(
        Prefs.BELL_STYLE_COUNT,
        Prefs.BELL_STYLE_SHIPS,
        Prefs.BELL_STYLE_SINGLE,
        Prefs.BELL_STYLE_BEEP
    )

    /** A clock that says nothing at twelve has stopped, as far as anyone
     *  listening can tell. */
    @Test
    fun `noon and midnight are twelve, not none`() {
        for (style in arrayOf(Prefs.BELL_STYLE_COUNT, Prefs.BELL_STYLE_BEEP)) {
            assertEquals(style, 12, Bells.peal(style, 12, true, false)!!.count)
            assertEquals(style, 12, Bells.peal(style, 0, true, false)!!.count)
            assertEquals(style, 1, Bells.peal(style, 13, true, false)!!.count)
            assertEquals(style, 11, Bells.peal(style, 23, true, false)!!.count)
        }
    }

    /** And no hour of the day is silent, whichever style is chosen. */
    @Test
    fun `every hour is struck`() {
        for (style in ALL) {
            for (hour in 0..23) {
                val peal = Bells.peal(style, hour, onTheHour = true, halfHours = false)
                assertNotNull("$style at $hour", peal)
                assertTrue("$style at $hour", peal!!.count > 0)
            }
        }
    }

    /**
     * Half past is silent unless it has been asked for — except on a ship,
     * where half past is half the point.
     */
    @Test
    fun `half past keeps quiet unless it is wanted`() {
        for (style in ALL) {
            val peal = Bells.peal(style, 9, onTheHour = false, halfHours = false)
            if (style == Prefs.BELL_STYLE_SHIPS) {
                assertNotNull("a ship's bell always marks it", peal)
            } else {
                assertNull("$style", peal)
            }
        }
    }

    /** Nautical watches: one bell per half hour, eight at the change. */
    @Test
    fun `the ship's bell counts its watch`() {
        assertEquals(8, Bells.peal(Prefs.BELL_STYLE_SHIPS, 12, true, false)!!.count)
        assertEquals(2, Bells.peal(Prefs.BELL_STYLE_SHIPS, 13, true, false)!!.count)
        assertEquals(3, Bells.peal(Prefs.BELL_STYLE_SHIPS, 13, false, false)!!.count)
        assertTrue(Bells.peal(Prefs.BELL_STYLE_SHIPS, 13, true, false)!!.pairGrouping)
    }

    // ------------------------------------------------------- the bip bip

    /**
     * The Casio option still counts the hour. That is the whole worth of
     * the bells — not having to look — and a signal that says only "an
     * hour has happened" gives that up.
     */
    @Test
    fun `the digital beeps count, they do not just signal`() {
        val nine = Bells.peal(Prefs.BELL_STYLE_BEEP, 21, true, false)!!
        assertTrue("it must be beeps and not a bell", nine.beeps)
        assertEquals(9, nine.count)
    }

    /** Half past is the two-beep signal every cheap watch makes. */
    @Test
    fun `and half past is bip bip`() {
        val half = Bells.peal(Prefs.BELL_STYLE_BEEP, 21, onTheHour = false, halfHours = true)!!
        assertTrue(half.beeps)
        assertEquals(2, half.count)
    }

    /**
     * Twelve beeps must not take as long as twelve strikes of a
     * grandfather clock. A beep is a short thing by nature, and twelve of
     * them at a bell's spacing would be a fire alarm.
     */
    @Test
    fun `twelve beeps are over long before twelve strikes are`() {
        val beeps = Bells.peal(Prefs.BELL_STYLE_BEEP, 12, true, false)!!
        val bells = Bells.peal(Prefs.BELL_STYLE_COUNT, 12, true, false)!!
        assertTrue(beeps.ringSeconds < bells.ringSeconds)
        assertTrue(
            "${length(beeps)} vs ${length(bells)}",
            length(beeps) < length(bells) / 3
        )
    }

    private fun length(peal: Bells.Peal) =
        (peal.count - 1) * peal.interval + peal.ringSeconds

    /** A beep has no resonance to speak of; a bell is mostly resonance. */
    @Test
    fun `no bell is a beep and no beep is a bell`() {
        for (style in ALL) {
            val peal = Bells.peal(style, 15, true, true)!!
            assertEquals(style == Prefs.BELL_STYLE_BEEP, peal.beeps)
        }
    }

    // ----------------------------------------------------- and the wiring

    /**
     * The preview button exists to let you hear the style you just picked,
     * which is exactly the thing a forgotten third copy of the rule gets
     * wrong: it would play the one before it, and sound like a bug in the
     * setting rather than in the button.
     */
    @Test
    fun `the preview plays the style that is selected`() {
        for (style in ALL) {
            assertEquals(
                "$style", style == Prefs.BELL_STYLE_BEEP, Bells.sample(style).beeps
            )
            assertTrue("$style", Bells.sample(style).count > 0)
        }
        assertEquals(
            "a gong is one strike, not three",
            1, Bells.sample(Prefs.BELL_STYLE_SINGLE).count
        )
    }

    /**
     * And the app itself asks rather than remembering — driven through the
     * minute boundary, because the copy that lived here was the one people
     * actually heard.
     */
    @Test
    fun `the app strikes what the style says`() {
        val prefs = PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
        prefs.edit().clear()
            .putBoolean(Prefs.BELLS, true)
            // Half past is wanted here on purpose. Without it the minute
            // below is silent for the wrong reason — there would be nothing
            // to strike at half past either, so a clock that struck every
            // minute of the hour would still pass this.
            .putBoolean(Prefs.HALF_HOUR, true)
            .putString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_BEEP)
            .commit()

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val app = controller.get()

            app.chimeAt(15, 0)
            assertEquals(Bells.peal(Prefs.BELL_STYLE_BEEP, 15, true, true), app.lastPeal)
            assertEquals(1, app.pealsStruck)

            // Twenty past is not a bell of any kind — counted rather than
            // compared, because striking three o'clock a second time leaves
            // the last peal looking exactly as it did.
            app.chimeAt(15, 20)
            assertEquals("nothing is struck at twenty past", 1, app.pealsStruck)

            // And half past is, so the silence above was the minute and not
            // the setting.
            app.chimeAt(15, 30)
            assertEquals(2, app.pealsStruck)
            assertEquals(
                Bells.peal(Prefs.BELL_STYLE_BEEP, 15, false, true), app.lastPeal
            )
        }
    }

    /** Half past stays silent in the app too, until it is asked for. */
    @Test
    fun `and it keeps quiet at half past unless told otherwise`() {
        val prefs = PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
        prefs.edit().clear()
            .putBoolean(Prefs.BELLS, true)
            .putString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT)
            .commit()

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val app = controller.get()
            app.chimeAt(15, 30)
            assertNull(app.lastPeal)
        }
    }

    /**
     * The background bells kept their own hard-coded ten-till-seven, so
     * setting the night hours moved the quiet hours in the app and left
     * them where they were with it closed.
     */
    @Test
    fun `the quiet hours are the ones that were set`() {
        // Somebody who sleeps in the afternoon: three o'clock is their
        // night, and ten at night is not.
        assertTrue("three in the afternoon", Bells.quiet(true, 15, 14, 18))
        assertFalse("ten at night", Bells.quiet(true, 22, 14, 18))
        // And with the dimming off there are no quiet hours at all.
        assertFalse(Bells.quiet(false, 15, 14, 18))
        assertFalse(Bells.quiet(false, 3, 22, 7))
    }
}
