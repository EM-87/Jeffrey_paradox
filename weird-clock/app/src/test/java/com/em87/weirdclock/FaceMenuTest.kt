package com.em87.weirdclock

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
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
 * The settings, read off the face rather than written down twice.
 *
 * The three XML files hold every row the app has, and each screen is built
 * by taking away what this face cannot answer — so there is one list of
 * rows and one table saying which face each belongs to, and no way for a
 * second copy to disagree with the first.
 *
 * Measured through the built screens and not by reading [FaceOptions] back
 * at itself. The table being right is not the claim; the claim is that the
 * page somebody opens has the rows on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceMenuTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun wearing(face: Face) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .commit()
    }

    /** Every key on a built screen, categories included. */
    private fun keysOn(fragment: PreferenceFragmentCompat): Set<String> {
        val found = HashSet<String>()
        fun walk(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val row = group.getPreference(i)
                row.key?.let { found += it }
                if (row is PreferenceGroup) walk(row)
            }
        }
        walk(fragment.preferenceScreen)
        return found
    }

    /** The three screens of one face, built for real and read back. */
    private fun everyKeyFor(face: Face): Set<String> {
        wearing(face)
        val found = HashSet<String>()
        for (fragment in screensOf(face)) found += keysOn(fragment)
        return found
    }

    private fun screensOf(face: Face): List<PreferenceFragmentCompat> {
        wearing(face)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val root = controller.get().supportFragmentManager.fragments.first()
            as PreferenceFragmentCompat
        val rest = listOf(
            SettingsActivity.AdvancedSettingsFragment(),
            SettingsActivity.VeryAdvancedSettingsFragment()
        )
        for (fragment in rest) {
            controller.get().supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, fragment).commitNow()
        }
        return listOf(root) + rest
    }

    /**
     * Nothing about hands, cases or marks round a rim survives on a face
     * that has none of those things.
     *
     * The list is spelled out here rather than borrowed from [FaceOptions]
     * on purpose: borrowing it would make this test agree with the table
     * whatever the table said, which is a test of nothing at all.
     */
    @Test
    fun `the digital face has no rows about a dial`() {
        val digital = everyKeyFor(Face.DIGITAL)
        for (key in listOf(
            Prefs.NUMERALS, Prefs.DIAL_SHAPE, Prefs.HOURS_PRESET, Prefs.HOURS_CUSTOM,
            Prefs.MIRROR, Prefs.DIAL_MARKS, Prefs.MINUTE_MARKS,
            Prefs.HAND_SHADOWS, Prefs.SHADOW_SURFACE,
            Prefs.MINUTE_HAND, Prefs.SMOOTH_SECONDS, Prefs.FAST_HAND,
            Prefs.TOUCH_HANDS, Prefs.PINCH_ZOOM, Prefs.SHAKE_DROP,
            Prefs.ORRERY, Prefs.MOON_PHASE, Prefs.COMETS, Prefs.ZODIAC,
            Prefs.ALARM_MARKERS, Prefs.MARK_COLORS, Prefs.ALARM_STYLE,
            // Little dials floating over a screenful of digits, until the
            // digital face has bubbles of its own.
            Prefs.WORLD_SECONDS,
            // A row that does nothing on this face: bars cannot spell
            // "August", and which numerals they are is the digits' own
            // question.
            Prefs.DATE_FORMAT
        )) {
            assertFalse("$key is on a screenful of digits", key in digital)
        }
    }

    /** And the digits' own rows are there instead. */
    @Test
    fun `and it has the rows a screenful of digits needs`() {
        val digital = everyKeyFor(Face.DIGITAL)
        for (key in listOf(
            Prefs.DIGIT_STYLE, Prefs.DIGIT_SCRIPT, Prefs.HOUR_24,
            Prefs.LEADING_ZERO, Prefs.BLINK_COLON
        )) {
            assertTrue("$key is missing from the digital settings", key in digital)
        }
    }

    /** The other way round, which is the half that is easy to forget. */
    @Test
    fun `the dial has no rows about digits`() {
        val analog = everyKeyFor(Face.ANALOG)
        for (key in listOf(
            Prefs.DIGIT_STYLE, Prefs.DIGIT_SCRIPT, Prefs.HOUR_24,
            Prefs.LEADING_ZERO, Prefs.BLINK_COLON
        )) {
            assertFalse("$key is on a dial", key in analog)
        }
        assertTrue("the dial lost its own numerals", Prefs.NUMERALS in analog)
    }

    /**
     * What both faces keep. These are the rows the whole scheme rests on:
     * the default is "common", so anything that is genuinely about telling
     * the time and not about how it is drawn survives untouched.
     */
    @Test
    fun `and the questions that mean something on either face are on both`() {
        val analog = everyKeyFor(Face.ANALOG)
        val digital = everyKeyFor(Face.DIGITAL)
        for (key in listOf(
            Prefs.THEME, Prefs.NIGHT_DIM, Prefs.NIGHT_WINDOW, Prefs.SHOW_DATE,
            Prefs.DATE_ORDER, Prefs.CALENDAR_NUMERALS,
            Prefs.BELLS, Prefs.BELL_STYLE, Prefs.SECOND_HAND, Prefs.TICKING,
            Prefs.ALARM_RAMP, Prefs.SOLAR_TIME,
            // The bubbles are readouts on the face with no hands, so the
            // world clock itself is a question both faces can answer.
            Prefs.WORLD_CLOCK, Prefs.WORLD_CITIES
        )) {
            assertTrue("$key vanished from the dial", key in analog)
            assertTrue("$key vanished from the digits", key in digital)
        }
    }

    /**
     * One stored answer, two names.
     *
     * Somebody who turns the second hand off and then changes face finds
     * the seconds already gone — which is what they asked for, because the
     * question was never about a hand. Making it two preferences would have
     * been the obvious thing and would have lost the answer at the door.
     */
    @Test
    fun `the same row asks the same question under two names`() {
        val onADial = screensOf(Face.ANALOG).last()
            .findPreference<Preference>(Prefs.SECOND_HAND)?.title?.toString()
        val onDigits = screensOf(Face.DIGITAL).last()
            .findPreference<Preference>(Prefs.SECOND_HAND)?.title?.toString()
        assertNotNull("the seconds row is not on the dial's screen", onADial)
        assertNotNull("the seconds row is not on the digital screen", onDigits)
        assertFalse(
            "a screenful of digits is still calling it a hand: $onDigits",
            onADial == onDigits
        )
        assertEquals(context.getString(R.string.pref_seconds_title), onDigits)
    }

    /**
     * And the rows whose *explanation* is about a dial say something else.
     *
     * Found by looking at the built page and not at the table: the keys
     * were right, the titles were right, and three summaries under them
     * said "the dial dims", "a small date under the centre of the dial"
     * and "mini dials showing the time in other cities" — on a clock that
     * has not got one. A row whose title is about you and whose subtitle
     * is about somebody else is worse than a row that is simply missing.
     */
    @Test
    fun `no row explains itself in terms of a dial this clock has not got`() {
        // Every face without one, and not just the digits. This test had
        // the one face it was written for hard-coded into it, and two more
        // arrived afterwards — which is how a menu ended up telling
        // somebody looking at a turning planet that the *dial* dims at
        // night, that the date goes under the centre of the *dial*, and
        // offering them mini *dials* for other cities on a face that has
        // none and draws none.
        for (face in Face.entries.filter { !it.hands })
        // A sundial is a dial: its plate is one, the lines on it are dial
        // furniture, and a summary saying so is describing the object
        // rather than borrowing another face's word. What it has not got
        // is hands, and that half of the rule still applies to it.
        for (word in if (face == Face.SUNDIAL) listOf("hands", "agujas")
                     else listOf("dial", "hands", "esfera", "agujas"))
        for (fragment in screensOf(face)) {
            val screen = fragment.preferenceScreen
            fun walk(group: PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    val row = group.getPreference(i)
                    if (row is PreferenceGroup) walk(row)
                    // Except the row that offers the hands in the first
                    // place. "Hands, or digits" is what it is for.
                    if (row.key == Prefs.FACE) continue
                    val said = "${row.title} ${row.summary}".lowercase()
                    assertFalse(
                        "$face — ${row.key}: [${row.title}] [${row.summary}]",
                        said.contains(word)
                    )
                }
            }
            walk(screen)
        }
    }

    /**
     * And the heading over the rows that outlive the dial is renamed too —
     * on every face, and each to something of its own.
     *
     * Named rather than merely "not Dial": four faces sharing one heading
     * would pass a test that only looked for the word, and the point of
     * the heading is that it says which instrument these rows are about.
     */
    @Test
    fun `the dial's heading is not left over a clock that has no dial`() {
        val expected = mapOf(
            Face.ANALOG to R.string.category_dial,
            Face.DIGITAL to R.string.category_screen,
            Face.SUNDIAL to R.string.category_plate,
            Face.HEMISPHERE to R.string.category_world
        )
        assertEquals("a face has no heading of its own", Face.entries.size, expected.size)
        // And the heading over the readout rows, which mean two different
        // things on the two faces that keep them.
        assertEquals(
            context.getString(R.string.category_readouts),
            screensOf(Face.HEMISPHERE)[1]
                .findPreference<Preference>(FaceOptions.CAT_DIGITS)?.title?.toString()
        )
        assertEquals(
            context.getString(R.string.category_digits),
            screensOf(Face.DIGITAL)[1]
                .findPreference<Preference>(FaceOptions.CAT_DIGITS)?.title?.toString()
        )
        val seen = HashSet<String>()
        for ((face, title) in expected) {
            val heading = screensOf(face)[1]
                .findPreference<Preference>(FaceOptions.CAT_DIAL)?.title?.toString()
            assertEquals("$face", context.getString(title), heading)
            assertTrue("$face borrowed another face's heading", seen.add(heading!!))
        }
    }

    /**
     * A row that does nothing on this face is not on this face's menu.
     *
     * The complement of every other test here, and the one that would have
     * caught the whole of what the last two faces got wrong: a switch that
     * is drawn, remembered and obeyed by nobody. Spelled out per face,
     * because the only honest source for "does this do anything" is
     * somebody having looked.
     */
    @Test
    fun `no row on a face is about a thing that face has not got`() {
        val world = everyKeyFor(Face.HEMISPHERE)
        for (key in listOf(
            // No month page — the calendar's card holds the solar system.
            Prefs.BIRTHDAY, Prefs.CYCLE, Prefs.CALENDAR_NUMERALS, Prefs.PAST_DAYS,
            // No other cities, by its owner's own instruction.
            Prefs.WORLD_CLOCK, Prefs.WORLD_CITIES,
            // And nowhere at all that a date is printed.
            Prefs.SHOW_DATE
        )) {
            assertFalse("$key is on the world's menu and does nothing", key in world)
        }
        val plate = everyKeyFor(Face.SUNDIAL)
        for (key in listOf(
            Prefs.WORLD_CLOCK, Prefs.WORLD_CITIES,
            // Nothing on a sundial counts seconds, so a switch marked
            // "second hand" and a mechanical tick under it were two rows
            // about a movement that face has not got.
            Prefs.SECOND_HAND, Prefs.TICKING
        )) {
            assertFalse("$key is on the sundial's menu and does nothing", key in plate)
        }
        // And they stay wherever something does count them: a hand, a
        // readout with seconds in it, a chronograph with a screen.
        for (face in Face.entries - Face.SUNDIAL) {
            assertTrue("$face lost its seconds", Prefs.SECOND_HAND in everyKeyFor(face))
        }
        // And the sundial keeps its date, which is cut into the plate.
        assertTrue(Prefs.SHOW_DATE in plate)
        assertTrue(Prefs.SHOW_DATE in everyKeyFor(Face.ANALOG))
    }

    /**
     * And the reverse: a row that *is* obeyed on this face is on its menu.
     *
     * The turning world has no hands, so it cannot put a dial on an alarm
     * card or a movement inside a chronograph — both become readouts, and
     * both are drawn from these four settings. They were being obeyed
     * there and were reachable only by switching to the digital face,
     * changing them, and switching back.
     */
    @Test
    fun `the rows a face obeys are on its menu`() {
        val world = everyKeyFor(Face.HEMISPHERE)
        for (key in listOf(
            Prefs.DIGIT_STYLE, Prefs.DIGIT_SCRIPT, Prefs.HOUR_24, Prefs.SEGMENT_GHOSTS
        )) {
            assertTrue("$key is obeyed on the world and hidden there", key in world)
        }
        // The dial draws its alarm cards as little dials, so none of the
        // four means anything on it.
        val analog = everyKeyFor(Face.ANALOG)
        for (key in listOf(Prefs.DIGIT_STYLE, Prefs.DIGIT_SCRIPT, Prefs.SEGMENT_GHOSTS)) {
            assertFalse("$key came back to the dial", key in analog)
        }
        // Neither does the sundial: it has no alarm and no chronograph.
        val plate = everyKeyFor(Face.SUNDIAL)
        assertFalse(Prefs.DIGIT_SCRIPT in plate)
    }

    /**
     * A heading with nothing under it is a heading over a hole.
     *
     * Both faces empty a category — the digits take the sky away, the dial
     * takes the digits away — so both directions are checked. Found by
     * building the screen rather than by reasoning about it: removing the
     * rows is one line and noticing what that leaves behind is another.
     */
    @Test
    fun `a category the face empties goes with its rows`() {
        val digital = screensOf(Face.DIGITAL)[1]
        assertNull(
            "the sky's heading is still there with nothing under it",
            headingSaying(digital, R.string.category_orrery)
        )
        val analog = screensOf(Face.ANALOG)[1]
        assertNull(
            "the digits' heading is still there on a dial",
            headingSaying(analog, R.string.category_digits)
        )
        // And a category that keeps something keeps its heading.
        assertNotNull(
            "the alarms' heading went with the rows it did not lose",
            headingSaying(digital, R.string.category_alarms)
        )
    }

    private fun headingSaying(fragment: PreferenceFragmentCompat, title: Int): Preference? {
        val wanted = context.getString(title)
        val screen = fragment.preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            val row = screen.getPreference(i)
            if (row is PreferenceGroup && row.title?.toString() == wanted) return row
        }
        return null
    }
}
