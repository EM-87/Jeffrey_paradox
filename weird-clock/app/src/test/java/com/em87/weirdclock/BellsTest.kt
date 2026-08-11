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
            assertEquals(style, 12, Bells.peal(style, 12, 0, Bells.MARKS_HOUR)!!.count)
            assertEquals(style, 12, Bells.peal(style, 0, 0, Bells.MARKS_HOUR)!!.count)
            assertEquals(style, 1, Bells.peal(style, 13, 0, Bells.MARKS_HOUR)!!.count)
            assertEquals(style, 11, Bells.peal(style, 23, 0, Bells.MARKS_HOUR)!!.count)
        }
    }

    /** And no hour of the day is silent, whichever style is chosen. */
    @Test
    fun `every hour is struck`() {
        for (style in ALL) {
            for (hour in 0..23) {
                val peal = Bells.peal(style, hour, 0, Bells.MARKS_HOUR)
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
            val peal = Bells.peal(style, 9, 30, Bells.MARKS_HOUR)
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
        assertEquals(8, Bells.peal(Prefs.BELL_STYLE_SHIPS, 12, 0, Bells.MARKS_HOUR)!!.count)
        assertEquals(2, Bells.peal(Prefs.BELL_STYLE_SHIPS, 13, 0, Bells.MARKS_HOUR)!!.count)
        assertEquals(3, Bells.peal(Prefs.BELL_STYLE_SHIPS, 13, 30, Bells.MARKS_HOUR)!!.count)
        assertTrue(Bells.peal(Prefs.BELL_STYLE_SHIPS, 13, 0, Bells.MARKS_HOUR)!!.pairGrouping)
    }

    // ------------------------------------------------------- the bip bip

    /**
     * The Casio option still counts the hour. That is the whole worth of
     * the bells — not having to look — and a signal that says only "an
     * hour has happened" gives that up.
     */
    @Test
    fun `the digital beeps count, they do not just signal`() {
        val nine = Bells.peal(Prefs.BELL_STYLE_BEEP, 21, 0, Bells.MARKS_HOUR)!!
        assertTrue("it must be beeps and not a bell", nine.voice == Bells.Voice.BEEP)
        assertEquals(9, nine.count)
    }

    /** Half past is the two-beep signal every cheap watch makes. */
    @Test
    fun `and half past is bip bip`() {
        val half = Bells.peal(Prefs.BELL_STYLE_BEEP, 21, 30, Bells.MARKS_HALF)!!
        assertTrue(half.voice == Bells.Voice.BEEP)
        assertEquals(2, half.count)
    }

    /**
     * Twelve beeps must not take as long as twelve strikes of a
     * grandfather clock. A beep is a short thing by nature, and twelve of
     * them at a bell's spacing would be a fire alarm.
     */
    @Test
    fun `twelve beeps are over long before twelve strikes are`() {
        val beeps = Bells.peal(Prefs.BELL_STYLE_BEEP, 12, 0, Bells.MARKS_HOUR)!!
        val bells = Bells.peal(Prefs.BELL_STYLE_COUNT, 12, 0, Bells.MARKS_HOUR)!!
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
            val peal = Bells.peal(style, 15, 0, Bells.MARKS_HALF)!!
            assertEquals(style == Prefs.BELL_STYLE_BEEP, peal.voice == Bells.Voice.BEEP)
        }
    }

    // ------------------------------------------------------ the quarters

    /**
     * One round at a quarter past, two at half past, three at a quarter
     * to. A tower clock does this so the quarter tells you *where in the
     * hour you are*, which is the whole point: a single ding at every
     * quarter says only that one has gone by, and you are back to looking.
     */
    @Test
    fun `each quarter says which quarter it is`() {
        assertEquals(1, Bells.peal(null, 9, 15, Bells.MARKS_QUARTERS)!!.count)
        assertEquals(2, Bells.peal(null, 9, 30, Bells.MARKS_QUARTERS)!!.count)
        assertEquals(3, Bells.peal(null, 9, 45, Bells.MARKS_QUARTERS)!!.count)
    }

    /** And a quarter is never mistakable for the hour it belongs to. */
    @Test
    fun `a quarter does not sound like an hour`() {
        val quarter = Bells.peal(null, 9, 45, Bells.MARKS_QUARTERS)!!
        val hour = Bells.peal(null, 9, 0, Bells.MARKS_QUARTERS)!!
        assertEquals(Bells.Voice.QUARTER_CHIME, quarter.voice)
        assertEquals(Bells.Voice.BELL, hour.voice)
        assertTrue("and it is over far sooner", length(quarter) < length(hour))
    }

    /** They stay silent unless quarters were asked for. */
    @Test
    fun `the quarters are quiet on the other two settings`() {
        for (marks in arrayOf(Bells.MARKS_HOUR, Bells.MARKS_HALF)) {
            assertNull(marks, Bells.peal(null, 9, 15, marks))
            assertNull(marks, Bells.peal(null, 9, 45, marks))
        }
    }

    /**
     * Quarters mean nothing at sea: a ship's bell counts half hours of a
     * watch and nothing else, so asking for quarters must not start it
     * ringing at a quarter past.
     */
    @Test
    fun `a ship's bell has no quarters`() {
        assertNull(Bells.peal(Prefs.BELL_STYLE_SHIPS, 9, 15, Bells.MARKS_QUARTERS))
        assertNull(Bells.peal(Prefs.BELL_STYLE_SHIPS, 9, 45, Bells.MARKS_QUARTERS))
        assertNotNull(
            "but half past is still its own",
            Bells.peal(Prefs.BELL_STYLE_SHIPS, 9, 30, Bells.MARKS_QUARTERS)
        )
    }

    /** Nothing sounds at a minute that is not a mark, on any setting. */
    @Test
    fun `no other minute of the hour makes a sound`() {
        for (style in ALL) {
            for (marks in arrayOf(Bells.MARKS_HOUR, Bells.MARKS_HALF, Bells.MARKS_QUARTERS)) {
                for (minute in 0..59) {
                    if (minute % 15 == 0) continue
                    assertNull("$style $marks at $minute", Bells.peal(style, 9, minute, marks))
                }
            }
        }
    }

    /**
     * Somebody updating from the build with the half-hour switch keeps
     * what they had. The bells are the part of this clock people arrange
     * their day around; quietly resetting them would be taking a working
     * alarm away.
     */
    @Test
    fun `the old half-hour switch is honoured until it is replaced`() {
        assertEquals(Bells.MARKS_HALF, Bells.marksFrom(null, legacyHalfHour = true))
        assertEquals(Bells.MARKS_HOUR, Bells.marksFrom(null, legacyHalfHour = false))
        // And once there is a real setting, the old switch stops mattering.
        assertEquals(Bells.MARKS_HOUR, Bells.marksFrom(Bells.MARKS_HOUR, legacyHalfHour = true))
        assertEquals(
            Bells.MARKS_QUARTERS,
            Bells.marksFrom(Bells.MARKS_QUARTERS, legacyHalfHour = false)
        )
        assertEquals("and nonsense falls back", Bells.MARKS_HOUR, Bells.marksFrom("wat", false))
    }

    // -------------------------------------------- and when to wake up for

    /**
     * The alarm for the next strike is always strictly ahead. Asked at
     * exactly quarter past, the answer must be half past — the quarter
     * ringing as the question is asked is not one to wake up for again,
     * and an alarm for the instant that is now would fire immediately and
     * arm another for the same instant.
     */
    @Test
    fun `the next slot is always ahead, never the one going off`() {
        val quarters = Bells.marked(Bells.MARKS_QUARTERS)
        assertEquals(15, BellScheduler.nextSlot(0, quarters))
        assertEquals(30, BellScheduler.nextSlot(15, quarters))
        assertEquals(45, BellScheduler.nextSlot(31, quarters))
        assertEquals("the next hour", 60, BellScheduler.nextSlot(45, quarters))
        assertEquals("and from the very end", 60, BellScheduler.nextSlot(59, quarters))
    }

    @Test
    fun `and it only wakes for the marks that were asked for`() {
        assertEquals(60, BellScheduler.nextSlot(5, Bells.marked(Bells.MARKS_HOUR)))
        assertEquals(30, BellScheduler.nextSlot(5, Bells.marked(Bells.MARKS_HALF)))
        assertEquals(60, BellScheduler.nextSlot(35, Bells.marked(Bells.MARKS_HALF)))
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
                "$style", style == Prefs.BELL_STYLE_BEEP, (Bells.sample(style).voice == Bells.Voice.BEEP)
            )
            assertTrue("$style", Bells.sample(style).count > 0)
        }
        assertEquals(
            "a gong is one strike, not three",
            1, Bells.sample(Prefs.BELL_STYLE_SINGLE).count
        )
        assertEquals(
            "the Casio signal is bip bip, and hearing three is hearing the wrong thing",
            2, Bells.sample(Prefs.BELL_STYLE_BEEP).count
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
            .putString(Prefs.BELL_MARKS, Bells.MARKS_QUARTERS)
            .putString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_BEEP)
            .commit()

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val app = controller.get()

            app.chimeAt(15, 0)
            assertEquals(Bells.peal(Prefs.BELL_STYLE_BEEP, 15, 0, Bells.MARKS_QUARTERS), app.lastPeal)
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
                Bells.peal(Prefs.BELL_STYLE_BEEP, 15, 30, Bells.MARKS_QUARTERS), app.lastPeal
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
