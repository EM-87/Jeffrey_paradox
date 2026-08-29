package com.em87.weirdclock

import android.view.View
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rows drawn faded, measured off the row rather than off the rule.
 *
 * A row is faded when the thing that gives it its effect is off, and the
 * fading is looked up **on the screen the row is on**. That is the whole
 * of what this test exists for: a rule can be perfectly written, compile,
 * read correctly, and be registered on a different fragment from the row
 * it names — at which point nothing happens at all, silently, and the row
 * sits there bright and doing nothing.
 *
 * That is exactly what happened to the weekday. The panel cannot write a
 * day of the week — Rome's module has eight letters in it and every Latin
 * day name wants one it has not got — so the switch was told to fade on
 * that script, and the telling was done two screens away from the switch.
 * It shipped doing nothing.
 *
 * So this reads the alpha of the actual view, after the list has laid
 * itself out, which is the only place the answer is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class FadedRowTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun wearing(script: DigitScript) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.DIGITAL.key)
            .putString(Prefs.DIGIT_SCRIPT, script.key)
            .commit()
    }

    /**
     * The alpha the row with this title is drawn at, off the laid-out list.
     *
     * A preference list is a RecyclerView, so nothing is bound until it has
     * been measured — and the fading happens as a row is bound. Measured
     * tall enough for every row, so the one being asked about is really on
     * screen and really painted.
     */
    private fun alphaOf(title: String): Float? {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val fragment = controller.get().supportFragmentManager.fragments.first()
            as PreferenceFragmentCompat
        val list = fragment.listView
        val tall = 300 * fragment.preferenceScreen.preferenceCount + 600
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, tall)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        fun walk(view: View): Float? {
            if (view is android.view.ViewGroup) {
                val label = view.findViewById<android.widget.TextView>(android.R.id.title)
                if (label?.text?.toString() == title) return view.alpha
                for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(list)
    }

    /**
     * The weekday is faded on the one script that cannot write one, and
     * ordinary on the three that can.
     *
     * Faded rather than taken away, and that is deliberate: turning it on
     * and then changing numerals is a perfectly ordinary thing to want to
     * do, and a row that had been removed would have refused it.
     */
    @Test
    fun `the weekday fades on the display that has no letters for it`() {
        val name = context.getString(R.string.pref_show_weekday_title)
        wearing(DigitScript.ROMAN_COMET)
        val onThePanel = alphaOf(name)
        assertNotNull("the weekday row is not on the first screen at all", onThePanel)
        assertTrue(
            "the weekday is drawn at $onThePanel on a display that cannot write one",
            onThePanel!! < 0.99f
        )
        for (script in DigitScript.entries - DigitScript.ROMAN_COMET) {
            wearing(script)
            assertEquals("$script lost its weekday", 1f, alphaOf(name)!!, 0.001f)
        }
    }

    /**
     * And the mechanism it borrowed still works for the switches it was
     * built for: the night hours wait on the night switch.
     */
    @Test
    fun `a row still fades behind the switch that gives it its effect`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putBoolean(Prefs.NIGHT_DIM, false)
            .commit()
        val dark = advancedAlphaOf(context.getString(R.string.pref_night_window_title))
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.NIGHT_DIM, true).commit()
        val lit = advancedAlphaOf(context.getString(R.string.pref_night_window_title))
        assertNotNull(dark)
        assertTrue("the night hours never faded", dark!! < 0.99f)
        assertEquals("and never came back", 1f, lit!!, 0.001f)
    }

    /** The same reading, on the screen behind the first one. */
    private fun advancedAlphaOf(title: String): Float? {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val fragment = SettingsActivity.AdvancedSettingsFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment).commitNow()
        val list = fragment.listView
        val tall = 300 * fragment.preferenceScreen.preferenceCount + 600
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, tall)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        fun walk(view: View): Float? {
            if (view is android.view.ViewGroup) {
                val label = view.findViewById<android.widget.TextView>(android.R.id.title)
                if (label?.text?.toString() == title) return view.alpha
                for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(list)
    }
}
