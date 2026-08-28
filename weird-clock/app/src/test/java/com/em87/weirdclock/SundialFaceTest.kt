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
 * The app with a shadow on it.
 *
 * The face axis was built for exactly this: a third clock that keeps some
 * cards, loses others, and answers a different set of questions. What is
 * worth checking is the structural half — that what it has not got is
 * gone *and* unreachable, and that what it has kept is genuinely there —
 * because that is the half a screenshot cannot show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SundialFaceTest {

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
     * A shadow cannot wake you and has nothing to start or stop.
     *
     * Three cards go and each one goes for a reason about the object
     * rather than about the app: an alarm needs something that rings at a
     * time you are not looking, and a stopwatch and a countdown need a
     * moving part. What is left is the dial, the sand and the calendar —
     * which are exactly the two things a sundial cannot do and would have
     * had on the same table.
     */
    @Test
    fun `there is no alarm and no chronograph on a sundial`() {
        settled(Face.SUNDIAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertEquals(Face.SUNDIAL, app.faceForTest())
            for (card in listOf(Card.ALARM, Card.STOPWATCH, Card.REVERSE)) {
                app.goToForTest(card)
                assertFalse("$card is on screen", app.cardShowingForTest(card))
            }
            // And the two it keeps are reachable.
            app.goToForTest(Card.HOURGLASS)
            assertTrue("the sand went with the alarm", app.cardShowingForTest(Card.HOURGLASS))
            app.goToForTest(Card.CALENDAR)
            assertTrue("the calendar went too", app.cardShowingForTest(Card.CALENDAR))
        }
    }

    /** And the clock card is a plate with a shadow on it, not a dial. */
    @Test
    fun `the clock card is the shadow`() {
        settled(Face.SUNDIAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertNotNull("there is no sundial on the sundial face", app.sundialForTest())
            assertNull("the digits came along", app.digitalForTest())
            assertFalse("the dial is still in the card", app.dialIsInTheCardForTest())
        }
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNull("a sundial turned up on the dial", c.get().sundialForTest())
        }
    }

    /**
     * The latitude reaches the dial, from the slider when it is set by
     * hand.
     *
     * The one number the instrument cannot do without — it is the angle
     * the style stands at and it is inside every hour line — and the one
     * a face that only worked with a location fix would not have indoors.
     */
    @Test
    fun `the latitude set by hand is the one the dial uses`() {
        settled(Face.SUNDIAL)
        prefs.edit()
            .putBoolean(Prefs.SUNDIAL_LATITUDE_FIXED, true)
            .putInt(Prefs.SUNDIAL_LATITUDE, 57)
            .putString(Prefs.SUNDIAL_KIND, Sundial.Kind.VERTICAL.key)
            .putString(Prefs.SUNDIAL_PLATE, Sundial.Plate.OCTAGON.key)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val dial = c.get().sundialForTest()!!
            assertEquals(57.0, dial.latitude, 0.0001)
            assertEquals(Sundial.Kind.VERTICAL, dial.kind)
            assertEquals(Sundial.Plate.OCTAGON, dial.plate)
        }
    }

    /**
     * The rows about a thing that rings are gone with the thing that
     * rings.
     *
     * Not "analogue only" and not "digital only": these are rows about a
     * card this face has not got, which is a third reason for a row to be
     * missing and needed a rule of its own.
     */
    @Test
    fun `a face with no alarm has no rows about one`() {
        for (key in listOf(
            Prefs.ALARM_RAMP, Prefs.RING_TIMEOUT_MIN,
            Prefs.GENTLE_FLASH, Prefs.COUNTDOWN_PERSISTENT
        )) {
            assertFalse(
                "$key is on a clock that cannot wake anybody",
                FaceOptions.shows(Face.SUNDIAL, key)
            )
            assertTrue("$key vanished from the dial", FaceOptions.shows(Face.ANALOG, key))
            assertTrue("$key vanished from the digits", FaceOptions.shows(Face.DIGITAL, key))
        }
    }

    /** And the sundial's own rows are on nobody else's screens. */
    @Test
    fun `the plate's rows belong to the plate`() {
        for (key in listOf(
            Prefs.SUNDIAL_KIND, Prefs.SUNDIAL_PLATE, Prefs.SUNDIAL_LATITUDE,
            Prefs.SUNDIAL_LATITUDE_FIXED, Prefs.SUNDIAL_COMPASS,
            Prefs.SUNDIAL_MOTTO, Prefs.SUNDIAL_HALVES, Prefs.SUNDIAL_ROMAN
        )) {
            assertTrue(key, FaceOptions.shows(Face.SUNDIAL, key))
            assertFalse("$key is on a dial", FaceOptions.shows(Face.ANALOG, key))
            assertFalse("$key is on a screenful of digits", FaceOptions.shows(Face.DIGITAL, key))
        }
        // And the digits' rows are not on the plate, which is the check
        // that adding a third face did not quietly widen the second.
        for (key in listOf(Prefs.DIGIT_STYLE, Prefs.BLINK_COLON, Prefs.BEDSIDE_DATE)) {
            assertFalse("$key is on a sundial", FaceOptions.shows(Face.SUNDIAL, key))
        }
    }
}
