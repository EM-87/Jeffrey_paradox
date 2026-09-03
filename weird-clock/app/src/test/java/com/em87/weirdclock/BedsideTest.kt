package com.em87.weirdclock

import android.view.View
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
 * The clock on its side, with the screen to itself.
 *
 * Standing a digital clock sideways on a bedside table is most of what one
 * is for, and it is the part of this face that had nothing at all. The
 * rule is three questions and the answers are measured through the built
 * activity, not read off [Bedside] — a table agreeing with itself is not
 * evidence that the buttons went away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class BedsideTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun settled(face: Face) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .commit()
    }

    /**
     * All three questions, and each one on its own.
     *
     * Spelled out here rather than looped, because what makes this rule
     * worth having is that each of the three is a separate way for it to
     * be wrong: a dial that loses its buttons, an alarm list that turns
     * into a clock, and a portrait clock that goes full screen for no
     * reason are three different bugs.
     */
    @Test
    fun `it takes a face that fills a screen, the clock card, and a phone on its side`() {
        assertTrue(Bedside.wanted(Face.DIGITAL, Card.CLOCK, landscape = true))
        assertFalse("a dial went full screen", Bedside.wanted(Face.ANALOG, Card.CLOCK, true))
        assertFalse("the alarm list did", Bedside.wanted(Face.DIGITAL, Card.ALARM, true))
        assertFalse("upright did", Bedside.wanted(Face.DIGITAL, Card.CLOCK, false))
        assertFalse("with no card at all", Bedside.wanted(Face.DIGITAL, null, true))
        assertTrue("wider than tall is on its side", Bedside.landscape(891, 411))
        assertFalse(Bedside.landscape(411, 891))
    }

    /** And the capability it asks the face for. */
    @Test
    fun `a dial is not worth turning the phone over for`() {
        assertTrue(Face.DIGITAL.fills)
        assertFalse(Face.ANALOG.fills)
    }

    /**
     * The card's furniture goes, and the digits get the room it was using.
     *
     * Both halves matter and only one of them is obvious. Hiding the
     * buttons and leaving the numbers a third of the height of the screen
     * is a bedside clock with a bigger margin.
     */
    @Test
    fun `the buttons go and the digits grow`() {
        settled(Face.DIGITAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            val digits = app.digitalForTest()!!
            assertFalse("upright is not a bedside clock", app.bedsideForTest())
            assertFalse(digits.fullScreen)
            assertEquals(
                View.VISIBLE,
                app.findViewById<View>(R.id.home_button_row).visibility
            )

            turnSideways(app)
            assertTrue("the phone went over and nothing happened", app.bedsideForTest())
            assertTrue("the digits are still card-sized", digits.fullScreen)
            assertEquals(
                "the row of buttons is still on the bedside clock",
                View.GONE,
                app.findViewById<View>(R.id.home_button_row).visibility
            )
            assertEquals(
                "so is the gear",
                View.GONE,
                app.findViewById<View>(R.id.settings_button).visibility
            )
        }
    }

    /** And a dial keeps everything, which is the half that is easy to lose. */
    @Test
    fun `a dial on its side is still a dial with buttons`() {
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            turnSideways(app)
            assertFalse(app.bedsideForTest())
            assertEquals(
                View.VISIBLE,
                app.findViewById<View>(R.id.home_button_row).visibility
            )
        }
    }

    /**
     * A tap brings the buttons back, and a second one puts them away.
     *
     * The only way off a screen with nothing on it for somebody who does
     * not know that a swipe is one. A control that only works in one
     * direction is half a control, so both directions are checked.
     */
    @Test
    fun `a tap on the glass brings the buttons back, and another puts them away`() {
        settled(Face.DIGITAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            val digits = app.digitalForTest()!!
            turnSideways(app)
            assertTrue(app.bedsideForTest())

            digits.onTapped!!.invoke()
            assertFalse("the tap did nothing", app.bedsideForTest())
            assertEquals(
                View.VISIBLE,
                app.findViewById<View>(R.id.home_button_row).visibility
            )
            // The digits stay big while the buttons are up: this is still
            // the bedside clock, with its controls showing for a moment.
            assertTrue(digits.fullScreen)

            digits.onTapped!!.invoke()
            assertTrue("a second tap did not put them away", app.bedsideForTest())
        }
    }

    /**
     * The two switches the bedside clock has of its own.
     *
     * They were reported twice as doing nothing, and the second time it
     * was measured: nothing was wrong with them at all — [Bedside.wanted]
     * asks whether the *pager* is wider than it is tall, and turning a
     * phone over changes the window before it changes the pager, so
     * anything reading the switch at that moment is reading the portrait
     * answer. What was missing was this test, which is why the report kept
     * coming back.
     */
    @Test
    fun `the bedside clock keeps its own seconds and its own date`() {
        settled(Face.DIGITAL)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.BEDSIDE_SECONDS, true)
            .putBoolean(Prefs.BEDSIDE_DATE, true)
            // And the card's own two off, so what is seen can only have
            // come from the pair above.
            .putBoolean(Prefs.DIGITAL_SECONDS, false)
            .putBoolean(Prefs.SHOW_DATE, false)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            val digits = app.digitalForTest()!!
            assertFalse("the card is showing seconds it was not asked for", colons(digits) > 1)
            assertFalse("and a date", digits.datedForTest())

            turnSideways(app)
            assertTrue("full screen and no seconds", colons(digits) > 1)
            assertTrue("full screen and no date", digits.datedForTest())
        }
    }

    /** And with them off, the bedside clock is the time and nothing else. */
    @Test
    fun `switched off, the bedside clock is bare`() {
        settled(Face.DIGITAL)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            // The card's own two on, so a face that read the wrong pair
            // would show both.
            .putBoolean(Prefs.DIGITAL_SECONDS, true)
            .putBoolean(Prefs.SHOW_DATE, true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            val digits = app.digitalForTest()!!
            assertTrue("the card lost the seconds it was asked for", colons(digits) > 1)

            turnSideways(app)
            assertFalse("the bedside clock kept the card's seconds", colons(digits) > 1)
            assertFalse("and the card's date", digits.datedForTest())
        }
    }

    /** How many colons are on the row: one is hours and minutes, two is seconds. */
    private fun colons(digits: DigitalClockView): Int =
        digits.cellsForTest().count { it is Cell.Colon }

    /**
     * Turns the phone over the way the system does: the configuration
     * changes, the window is laid out at the new size, and the activity is
     * told. The pager's own size is what the rule measures, so it is set
     * here rather than trusted to Robolectric's idea of a screen.
     */
    private fun turnSideways(app: MainActivity) {
        val pager = app.findViewById<View>(R.id.pager)
        pager.layout(0, 0, 891, 411)
        app.applyBedsideForTest()
    }
}
