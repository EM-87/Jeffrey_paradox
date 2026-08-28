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
 * The app with the planet on it.
 *
 * This face is the one that swaps a card rather than only losing cards,
 * which is a thing the axis had never been asked to do: the calendar's
 * page holds the solar system here. What is worth checking is that the
 * swap is a swap — the grid of days gone, the sky there — and not two
 * things stacked on one page with one of them invisible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class HemisphereFaceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun settled(face: Face) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .commit()
    }

    /** The clock card is the world, and none of the other three. */
    @Test
    fun `the clock card is the planet`() {
        settled(Face.HEMISPHERE)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            assertEquals(Face.HEMISPHERE, app.faceForTest())
            assertNotNull("there is no world on the world face", app.hemisphereForTest())
            assertNull("the digits came along", app.digitalForTest())
            assertNull("so did the sundial", app.sundialForTest())
            assertFalse("the dial is still in the card", app.dialIsInTheCardForTest())
        }
    }

    /**
     * Sand in a glass is one instrument too many beside a turning planet,
     * and the alarm and both chronographs stay — which is the difference
     * between this face and the sundial.
     */
    @Test
    fun `it keeps the alarm and the chronographs and loses the sand`() {
        settled(Face.HEMISPHERE)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            app.goToForTest(Card.HOURGLASS)
            assertFalse("the sand is on screen", app.cardShowingForTest(Card.HOURGLASS))
            for (card in listOf(Card.ALARM, Card.STOPWATCH, Card.REVERSE)) {
                app.goToForTest(card)
                assertTrue("$card went missing", app.cardShowingForTest(card))
            }
        }
    }

    /**
     * The calendar's card is the solar system, and the grid of days is
     * gone rather than hidden behind it.
     *
     * Taken out of the layout for the same reason the three clocks are:
     * an invisible calendar still asks for its marks and its reminders
     * every time a month changes, and an invisible sky still runs a frame
     * loop for planets nobody is looking at.
     */
    @Test
    fun `the calendar's page holds the sky instead`() {
        settled(Face.HEMISPHERE)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            app.goToForTest(Card.CALENDAR)
            val sky = app.orreryCardForTest()
            assertNotNull("there is no sky where the calendar was", sky)
            assertTrue("the card is a dial and not a sky", sky!!.skyOnly)
            // The sky opens with a fade, so it is not showing in the same
            // frame it was asked for — which is the animation and not the
            // state. A second later it is up.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(1200)
            )
            assertTrue("the sky never opened", sky.orreryShowing())
            assertNull("the month page is still there too", app.calendarViewForTest())
        }
        // And on every other face it is the other way round.
        for (face in Face.entries - Face.HEMISPHERE) {
            settled(face)
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                assertNull("$face grew a sky card", c.get().orreryCardForTest())
                if (Card.CALENDAR in face.cards) {
                    assertNotNull("$face lost its calendar", c.get().calendarViewForTest())
                }
            }
        }
    }

    /** And the world's own rows are on nobody else's screens. */
    @Test
    fun `the world's rows belong to the world`() {
        for (key in listOf(
            Prefs.HEMISPHERE_VIEW, Prefs.HEMISPHERE_SUN_AT, Prefs.HEMISPHERE_RING,
            Prefs.HEMISPHERE_NUMBERS, Prefs.HEMISPHERE_MERIDIANS
        )) {
            assertTrue(key, FaceOptions.shows(Face.HEMISPHERE, key))
            for (other in Face.entries - Face.HEMISPHERE) {
                assertFalse("$key is on $other", FaceOptions.shows(other, key))
            }
        }
    }

    /**
     * The dot is not drawn until the app knows where it is.
     *
     * A clock whose hand sits at nought because it has never had a fix is
     * a clock that is confidently wrong, which is worse than a clock with
     * no hand at all.
     */
    @Test
    fun `no fix, no dot`() {
        settled(Face.HEMISPHERE)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            assertEquals(
                "the dot is drawn from a location nobody has",
                DayNight.hasFix(), world.located
            )
        }
    }
}
