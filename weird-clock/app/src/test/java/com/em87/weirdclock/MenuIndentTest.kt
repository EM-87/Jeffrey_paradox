package com.em87.weirdclock

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The nested rows, as they land on the screen.
 *
 * There was a test for this before and it checked the XML — that the rows
 * meant to be nested carried the attribute that nests them. They did, and
 * the menu came out looking exactly as it had before, because an attribute
 * is a request and not a result. This one lays the list out and measures
 * where the words actually start.
 *
 * Which is the lesson twice over: a test that reads back the thing you
 * wrote is a test of your typing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class MenuIndentTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        // Everything on, so the rows that hide under a switch are present
        // to be measured.
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.BELLS, true)
            .putBoolean(Prefs.NIGHT_DIM, true)
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.WORLD_CLOCK, true)
            .commit()
    }

    /**
     * Where the row for [key] starts, in pixels from the left of the list.
     *
     * The whole list, laid out for real. A single row bound on its own
     * looks right whatever the list would do with it — the inset that
     * indents these is applied by the list as it places its children, and
     * a test that measured one row in isolation could not see it. Which is
     * how the first version of this passed against a menu that had not
     * changed at all.
     */
    private fun rowLeft(fragment: PreferenceFragmentCompat, key: String): Int {
        val list = fragment.listView
        list.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(9000, android.view.View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1000, 9000)
        val wanted = fragment.findPreference<Preference>(key)?.title?.toString()
        assertTrue("$key is not on this screen", wanted != null)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val title = child.findViewById<android.widget.TextView>(android.R.id.title)
            if (title?.text?.toString() == wanted) return child.left
        }
        throw AssertionError("$key was never laid out, so nothing was measured")
    }

    private fun rootScreen(c: Robolectric.() -> Unit = {}): PreferenceFragmentCompat {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        return controller.get().supportFragmentManager.fragments.first()
            as PreferenceFragmentCompat
    }

    /**
     * A row that hangs off the switch above it starts further in than the
     * switch does.
     *
     * Not "by a particular number of pixels": what matters is that the eye
     * can see one belongs to the other, and any indent that is visible at
     * all does that. What it must not be is zero, which is what it was.
     */
    @Test
    fun `the rows under the bells start further in than the bells`() {
        val fragment = rootScreen()
        val parent = rowLeft(fragment, Prefs.BELLS)
        for (child in listOf(
            Prefs.BELL_MARKS, Prefs.BELL_STYLE, Prefs.BELLS_BACKGROUND,
            Prefs.BELL_PRIORITY, Prefs.TEST_BELLS
        )) {
            val at = rowLeft(fragment, child)
            assertTrue(
                "$child starts at $at, the same place as the switch it hangs off",
                at > parent
            )
        }
    }

    /**
     * And the ones that govern nothing start at the left edge.
     *
     * At the edge, not merely "no further in than the bells" — which is
     * what this asked first, and which stays true when every row in the
     * list is indented by the same amount. An indent everything shares is
     * not an indent; it says nothing about which row belongs to which.
     */
    @Test
    fun `a row that answers to nobody starts at the left`() {
        val fragment = rootScreen()
        for (key in listOf(Prefs.BELLS, Prefs.THEME, Prefs.SHOW_DATE, Prefs.MOON_PHASE)) {
            assertEquals(
                "$key is indented as though it hung off something",
                0, rowLeft(fragment, key)
            )
        }
    }

    /** The other screens nest the same way. */
    @Test
    fun `the refinements under the second hand start further in`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val fragment = SettingsActivity.VeryAdvancedSettingsFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment).commitNow()
        val parent = rowLeft(fragment, Prefs.SECOND_HAND)
        for (child in listOf(Prefs.SMOOTH_SECONDS, Prefs.FAST_HAND, Prefs.TICKING)) {
            assertTrue(
                "$child starts level with the hand it belongs to",
                rowLeft(fragment, child) > parent
            )
        }
    }
}
