package com.em87.weirdclock

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every row of every settings screen on every face, written out.
 *
 * A picture of a settings page says what it looks like; it does not say
 * what is on it, because a page nine thousand pixels tall is not read, it
 * is glanced at. This walks the built screens instead and writes down
 * what each one holds, in order, with its heading and its explanation —
 * which is the only way to answer "what options are there" without
 * anybody trusting a list written by hand next to the code.
 *
 * Built rather than read off the XML on purpose. Half the rows on these
 * pages are put there, taken away or renamed at runtime by [FaceOptions]
 * depending on which clock is on, so the XML is the superset and no face
 * ever shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class MenuInventoryTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun walk(group: PreferenceGroup, heading: String, into: StringBuilder, face: Face, screen: String) {
        for (i in 0 until group.preferenceCount) {
            val row: Preference = group.getPreference(i)
            if (row is PreferenceGroup) {
                walk(row, row.title?.toString() ?: "", into, face, screen)
                continue
            }
            val kind = when (row) {
                is androidx.preference.SwitchPreferenceCompat,
                is androidx.preference.CheckBoxPreference -> "switch"
                is androidx.preference.ListPreference -> "list"
                is androidx.preference.SeekBarPreference -> "slider"
                is androidx.preference.EditTextPreference -> "text"
                else -> "row"
            }
            val choices = (row as? androidx.preference.ListPreference)
                ?.entries?.joinToString(" · ") ?: ""
            into.append(
                listOf(
                    face.key, screen, heading,
                    row.title?.toString() ?: "",
                    row.summary?.toString()?.replace('\n', ' ') ?: "",
                    row.key ?: "", kind, choices
                ).joinToString("\t")
            ).append('\n')
        }
    }

    /**
     * Written out for every face, because the four pages are four
     * different pages and not one page with rows hidden on it.
     */
    @Test
    fun `write down every row of every screen`() {
        val out = StringBuilder("face\tscreen\theading\ttitle\tsummary\tkey\tkind\tchoices\n")
        for (face in Face.entries) {
            prefs.edit().clear()
                .putBoolean(Prefs.OVERLAY_ASKED, true)
                .putBoolean(Prefs.FACE_ASKED, true)
                .putString(Prefs.FACE, face.key)
                // On, so the rows that only appear once something is
                // switched on appear: a page photographed with every
                // switch off is a page with holes in it.
                .putBoolean(Prefs.BELLS, true)
                .putBoolean(Prefs.NIGHT_DIM, true)
                .putBoolean(Prefs.WORLD_CLOCK, true)
                .putBoolean(Prefs.WEATHER, true)
                .putBoolean(Prefs.IFTTT, true)
                .putBoolean(Prefs.SHOW_DATE, true)
                .commit()
            Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                c.setup()
                val root = c.get().supportFragmentManager.fragments.first()
                    as PreferenceFragmentCompat
                walk(root.preferenceScreen, "", out, face, "root")
            }
            for ((screen, fragment) in listOf(
                "advanced" to SettingsActivity.AdvancedSettingsFragment(),
                "very-advanced" to SettingsActivity.VeryAdvancedSettingsFragment()
            )) {
                Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                    c.setup()
                    c.get().supportFragmentManager.beginTransaction()
                        .replace(R.id.settings_container, fragment).commitNow()
                    walk(fragment.preferenceScreen, "", out, face, screen)
                }
            }
        }
        val dir = File("build/reports").apply { mkdirs() }
        File(dir, "menu.tsv").writeText(out.toString())
        // A page with nothing on it would write a header and pass.
        assertTrue("the menu came out empty", out.lineSequence().count() > 100)
    }
}
