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
 * The app with the digits on, as far as the screen is concerned.
 *
 * The face is the one setting that changes what the app *is* rather than
 * how it looks, so the things worth checking are the structural ones: that
 * the cards it has not got are gone, that nothing on screen still offers to
 * take you to one, and that the choice is asked for once and then never
 * again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DigitalFaceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs: android.content.SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun settled(face: Face) {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .commit()
    }

    /**
     * The hourglass is sand in a glass, and there is no digital one — so
     * the card is not merely hidden, there is no way to it.
     *
     * The button matters as much as the card. A hidden card with a live
     * button pointing at it is the worse of the two failures: the finger
     * lands, nothing happens, and the app looks broken rather than
     * deliberate.
     */
    @Test
    fun `there is no hourglass on a digital clock, nor a button to it`() {
        settled(Face.DIGITAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertEquals(Face.DIGITAL, app.faceForTest())
            // Asked for outright, which is the strongest form of the
            // question: even told to go there, there is nothing to show.
            app.goToForTest(Card.HOURGLASS)
            assertFalse(
                "the hourglass is on screen",
                app.cardShowingForTest(Card.HOURGLASS)
            )
            // And the app did not go there anyway and land on an empty row,
            // which is what "hide the card" on its own gets you: no
            // hourglass, no clock, and a pager sitting on nothing.
            assertEquals(
                "the clock was left behind",
                Card.CLOCK, app.showingCardForTest()
            )
            assertEquals(
                "the button up to the hourglass is still there",
                android.view.View.GONE, app.modeButtonForTest()?.visibility
            )
        }
    }

    /** And on a dial both are exactly where they have always been. */
    @Test
    fun `the dial still has its hourglass`() {
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            app.goToForTest(Card.HOURGLASS)
            assertTrue("the hourglass went missing", app.cardShowingForTest(Card.HOURGLASS))
            assertEquals(
                android.view.View.VISIBLE, app.modeButtonForTest()?.visibility
            )
        }
    }

    /**
     * Only one of the two clocks is in the card, and the other is not
     * there at all.
     *
     * Taken out rather than hidden. A dial that is merely invisible still
     * asks for a frame a second and still holds the accelerometer open
     * waiting to be shaken — which on the face that chose digits for
     * simplicity is a whole mechanism running behind a wall.
     */
    @Test
    fun `the face that is not being worn is not in the card`() {
        settled(Face.DIGITAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertNotNull("there is no digital face on the digital face", app.digitalForTest())
            assertFalse("the dial is still in the card", app.dialIsInTheCardForTest())
        }
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertTrue("the dial went missing from the dial", app.dialIsInTheCardForTest())
            assertNull("the digits are still in the card", app.digitalForTest())
        }
    }

    /**
     * The little world clocks are little dials, so they stay on the face
     * that has one. Two clocks on one screen disagreeing about what kind of
     * clock this is reads as a bug, whichever of them is right.
     */
    @Test
    fun `no dials float over a screenful of digits`() {
        settled(Face.DIGITAL)
        prefs.edit()
            .putBoolean(Prefs.WORLD_CLOCK, true)
            .putStringSet(Prefs.WORLD_TZS, setOf("UTC", "Europe/Madrid"))
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertTrue(
                "there are little dials on the digital face",
                c.get().worldClocksForTest().isEmpty()
            )
        }
        settled(Face.ANALOG)
        prefs.edit()
            .putBoolean(Prefs.WORLD_CLOCK, true)
            .putStringSet(Prefs.WORLD_TZS, setOf("UTC", "Europe/Madrid"))
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertEquals(
                "and the dial lost its own",
                2, c.get().worldClocksForTest().size
            )
        }
    }

    /**
     * The digits are told what the settings say, including the two
     * questions the dial asks under other names.
     */
    @Test
    fun `the digits are wearing what the settings chose`() {
        settled(Face.DIGITAL)
        prefs.edit()
            .putString(Prefs.DIGIT_STYLE, Prefs.DIGITS_ROLLER)
            .putString(Prefs.DIGIT_SCRIPT, Prefs.SCRIPT_ROMAN)
            .putBoolean(Prefs.HOUR_24, false)
            .putBoolean(Prefs.BLINK_COLON, true)
            .putBoolean(Prefs.SECOND_HAND, false)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val digits = c.get().digitalForTest()!!
            assertEquals(DigitStyle.ROLLER, digits.style)
            assertEquals(DigitScript.ROMAN, digits.script)
            assertFalse(digits.hour24)
            assertTrue(digits.blinkColon)
            // The second hand's switch, which on this face is the seconds.
            assertFalse("the seconds are still being counted", digits.showSeconds)
        }
    }

    /**
     * The question is asked on the first run and the answer is acted on.
     *
     * Not written down until it is answered: a face the app picked on your
     * behalf because a dialog was tapped through is the outcome the whole
     * thing exists to avoid — so an unanswered question leaves no mark and
     * comes back next time.
     */
    @Test
    fun `the first run asks which clock this is`() {
        // Nothing at all, which is what a phone that has never run this
        // app looks like — and the only state in which the question is
        // asked, see below.
        prefs.edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNotNull("nobody was asked anything", c.get().faceDialog)
            assertFalse(
                "the app answered for them",
                prefs.getBoolean(Prefs.FACE_ASKED, false)
            )
            c.get().chooseFace(Face.DIGITAL)
            assertTrue(prefs.getBoolean(Prefs.FACE_ASKED, false))
            assertEquals(Face.DIGITAL, Face.of(prefs))
        }
    }

    /**
     * And not on the first run of a *new version*, which is not the same
     * thing at all.
     *
     * Everybody already using this app has settings, alarms and a dial
     * they have got used to. They never chose it — there was nothing to
     * choose — but a modal question about it on the morning after an
     * update, from the app that is now their alarm clock, is an
     * interruption and not an offer.
     */
    @Test
    fun `somebody who already has the app is not asked`() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putString(Prefs.THEME, "midnight")
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNull("an old hand was asked to choose", c.get().faceDialog)
            assertEquals("and was moved off the dial", Face.ANALOG, c.get().faceForTest())
        }
    }

    /** And only on the first run. */
    @Test
    fun `and never asks again`() {
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNull("asked twice", c.get().faceDialog)
        }
    }

    /**
     * Setting an alarm on the face with no hands.
     *
     * The whole flow is the dial's: the same banner, the same working
     * value, the same confirm. Only the instrument is different — the
     * digits are the setter and a finger rolls them — so what this checks
     * is that the digital face is actually handed the job rather than the
     * screen sitting there with a banner over a clock nobody can change.
     */
    @Test
    fun `an alarm time is set by rolling the digits`() {
        settled(Face.DIGITAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            val digits = app.digitalForTest()!!
            assertNull("the face is setting something before it was asked", digits.settingMs)

            val alarm = app.windAlarmForTest(7, 30)
            assertEquals(
                "the digits were not handed the time to set",
                (7 * 60L + 30L) * 60_000L, digits.settingMs
            )
            // One click of the minutes' units drum, and the value the
            // confirm will read must have moved with it.
            digits.rollForTest(1, 5)
            app.confirmAlarmSetForTest()
            assertEquals("the roll never reached the alarm", 7 to 35, alarm.timeAt(0))
            assertNull("the face is still in setting mode", digits.settingMs)
        }
    }

    /**
     * A way in from outside that names a card this face has not got is
     * swallowed rather than obeyed.
     *
     * The countdown notification and the sand widget both ask for "the
     * timer", and what that means depends on which one was last looked at —
     * a preference that can perfectly well say "the hourglass" on a phone
     * whose face was changed since.
     */
    @Test
    fun `a widget cannot send a digital clock to a card it has not got`() {
        settled(Face.DIGITAL)
        prefs.edit().putBoolean(Prefs.TIMER_ON_DIAL, false).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.newIntent(
                android.content.Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_TIMER, true)
            )
            val app = c.get()
            assertFalse(
                "the hourglass came up on a clock that has not got one",
                app.cardShowingForTest(Card.HOURGLASS)
            )
            // And it landed on the timer this face does have, rather than
            // swallowing the whole request and leaving somebody who tapped
            // a notification looking at the clock.
            assertEquals(
                "the tap went nowhere",
                Card.REVERSE, app.showingCardForTest()
            )
        }
    }
}
