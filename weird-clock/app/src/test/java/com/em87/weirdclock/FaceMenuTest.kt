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
            Prefs.WORLD_CLOCK, Prefs.WORLD_SECONDS, Prefs.WORLD_CITIES
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
            Prefs.DATE_FORMAT, Prefs.DATE_ORDER, Prefs.CALENDAR_NUMERALS,
            Prefs.BELLS, Prefs.BELL_STYLE, Prefs.SECOND_HAND, Prefs.TICKING,
            Prefs.ALARM_RAMP, Prefs.SOLAR_TIME
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

    /** And the heading over the rows that outlive the dial is renamed too. */
    @Test
    fun `the dial's heading is not left over a clock that has no dial`() {
        val heading = screensOf(Face.DIGITAL)[1]
            .findPreference<Preference>(FaceOptions.CAT_DIAL)?.title?.toString()
        assertEquals(context.getString(R.string.category_screen), heading)
        assertEquals(
            context.getString(R.string.category_dial),
            screensOf(Face.ANALOG)[1]
                .findPreference<Preference>(FaceOptions.CAT_DIAL)?.title?.toString()
        )
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
