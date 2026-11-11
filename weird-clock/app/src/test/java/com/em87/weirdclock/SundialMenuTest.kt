package com.em87.weirdclock

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
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
 * The sundial's settings, where they said Alarms.
 *
 * The one face that cannot wake anybody had a heading called Alarms on
 * its first screen, over the hourly bells and nothing else; a row on its
 * second about which wins when an alarm and a bell fall on the same hour;
 * and nothing anywhere about the one thing it does have that rings, which
 * is the sand running out. That row had gone with the alarm's, so the
 * glass rang until it was stopped and there was no way to say it should
 * not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SundialMenuTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun settled(face: Face) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .commit()
    }

    /** The first screen and the second, as [face] builds them. */
    private fun <T> onScreens(
        face: Face,
        look: (root: SettingsActivity.Screen, advanced: SettingsActivity.Screen) -> T
    ): T {
        settled(face)
        return Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val manager = c.get().supportFragmentManager
            val root = manager.findFragmentById(R.id.settings_container) as SettingsActivity.Screen
            val advanced = SettingsActivity.AdvancedSettingsFragment()
            manager.beginTransaction().add(advanced, "advanced").commitNow()
            look(root, advanced)
        }
    }

    private fun rowsOf(category: PreferenceCategory): List<Preference> =
        (0 until category.preferenceCount).map { category.getPreference(it) }.filter { it.isVisible }

    /** On the sundial the heading over the bells says Bells. */
    @Test
    fun `the heading over the bells does not say Alarms on a face with none`() {
        onScreens(Face.SUNDIAL) { root, _ ->
            val heading = root.findPreference<PreferenceCategory>(FaceOptions.CAT_ALARMS)
            assertNotNull("the bells have lost their heading", heading)
            assertEquals(context.getString(R.string.category_bells_only), heading!!.title.toString())
            assertEquals(
                "something other than the bells is under it",
                listOf(Prefs.BELLS), rowsOf(heading).map { it.key }
            )
        }
        // And everywhere there is an alarm it still says so.
        for (face in listOf(Face.ANALOG, Face.DIGITAL, Face.HEMISPHERE)) {
            onScreens(face) { root, _ ->
                assertEquals(
                    "$face", context.getString(R.string.category_alarms),
                    root.findPreference<PreferenceCategory>(FaceOptions.CAT_ALARMS)!!.title.toString()
                )
            }
        }
    }

    /**
     * And the sand has its row back, under its own name.
     *
     * "Persistent timer alarm — when the countdown ends, ring until you
     * dismiss it" is the sand's row on every face, and on this one it is
     * the only thing left under what was the Alarms heading.
     */
    @Test
    fun `the sand's own row is back on the sundial, under a heading that names it`() {
        assertTrue(FaceOptions.shows(Face.SUNDIAL, Prefs.COUNTDOWN_PERSISTENT))
        onScreens(Face.SUNDIAL) { _, advanced ->
            val heading = advanced.findPreference<PreferenceCategory>(FaceOptions.CAT_ALARMS_DEEP)
            assertNotNull("the sand has no heading", heading)
            assertEquals(context.getString(R.string.category_sand), heading!!.title.toString())
            assertEquals(
                "something other than the sand is under it",
                listOf(Prefs.COUNTDOWN_PERSISTENT), rowsOf(heading).map { it.key }
            )
            val row = advanced.findPreference<Preference>(Prefs.COUNTDOWN_PERSISTENT)!!
            assertEquals(context.getString(R.string.pref_sand_persistent_title), row.title.toString())
            assertEquals(context.getString(R.string.pref_sand_persistent_summary), row.summary.toString())
        }
        // Where there are alarms it is one of their rows, by its old name.
        onScreens(Face.ANALOG) { _, advanced ->
            assertEquals(
                context.getString(R.string.category_alarms),
                advanced.findPreference<PreferenceCategory>(FaceOptions.CAT_ALARMS_DEEP)!!.title.toString()
            )
            assertEquals(
                context.getString(R.string.pref_countdown_persistent_title),
                advanced.findPreference<Preference>(Prefs.COUNTDOWN_PERSISTENT)!!.title.toString()
            )
        }
    }

    /** Which wins, the alarm or the bell, is not asked where there is no alarm. */
    @Test
    fun `alarm against bell is not asked on a face with no alarm`() {
        onScreens(Face.SUNDIAL) { _, advanced ->
            assertNull(advanced.findPreference<Preference>(Prefs.BELL_PRIORITY))
        }
        onScreens(Face.ANALOG) { _, advanced ->
            assertNotNull(advanced.findPreference<Preference>(Prefs.BELL_PRIORITY))
        }
        assertFalse(FaceOptions.shows(Face.SUNDIAL, Prefs.BELL_PRIORITY))
    }

    /** The second shadow and the fourth calendar are on the sundial's screen and no other. */
    @Test
    fun `the clock's shadow is a row on the sundial only, and off`() {
        for (face in Face.entries) {
            assertEquals(
                "$face", face == Face.SUNDIAL, FaceOptions.shows(face, Prefs.SUNDIAL_CIVIL_SHADOW)
            )
        }
        onScreens(Face.SUNDIAL) { _, advanced ->
            val row = advanced.findPreference<androidx.preference.SwitchPreferenceCompat>(
                Prefs.SUNDIAL_CIVIL_SHADOW
            )
            assertNotNull("the sundial has no row for its second shadow", row)
            assertFalse("the second shadow comes switched on", row!!.isChecked)
            val calendars = advanced.findPreference<androidx.preference.ListPreference>(Prefs.SUNDIAL_CALENDAR)!!
            assertEquals(
                "the row does not offer every calendar the plate can be cut in",
                Sundial.Reckoning.entries.map { it.key }, calendars.entryValues.map { it.toString() }
            )
            assertEquals(calendars.entries.size, calendars.entryValues.size)
        }
    }

    /** And the dial is told: the switch reaches the plate. */
    @Test
    fun `the switch for the second shadow reaches the plate`() {
        settled(Face.SUNDIAL)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertFalse(c.get().sundialForTest()!!.civilShadow)
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.SUNDIAL_CIVIL_SHADOW, true)
            .putString(Prefs.SUNDIAL_CALENDAR, Sundial.Reckoning.ZODIAC.key)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertTrue("the plate was not told", c.get().sundialForTest()!!.civilShadow)
            assertEquals(Sundial.Reckoning.ZODIAC, c.get().sundialForTest()!!.reckoning)
        }
    }
}
