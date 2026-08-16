package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One bar, twenty-four sections, two pins.
 *
 * The night used to be two separate sliders, which is two rows to read a
 * single fact off and no way to see the shape of what you had set. The
 * shape is the whole difficulty: nearly every night crosses midnight, so
 * the band runs off the right-hand end of the bar and comes back on the
 * left, and a pin at 23 is *before* a pin at 7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NightBarTest {

    private fun bar(): NightBar {
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_WeirdClock
        )
        return NightBar(themed).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            layout(0, 0, 720, measuredHeight)
        }
    }

    /** Twenty-four sections across whatever width it is given. */
    @Test
    fun `the bar is a day laid out flat`() {
        val bar = bar()
        assertEquals(0f, bar.hourAt(0f), 0.01f)
        assertEquals(24f, bar.hourAt(bar.width.toFloat()), 0.01f)
        assertEquals(12f, bar.hourAt(bar.width / 2f), 0.3f)
    }

    /** Dragging a pin lands it on an hour mark, never between two. */
    @Test
    fun `a pin comes to rest on an hour`() {
        val bar = bar()
        bar.holdForTest(true)
        bar.moveTo(bar.width * 0.51f)
        assertEquals(12, bar.from)
    }

    /**
     * Whichever pin is nearer, measured round the clock rather than along
     * the bar: a touch just after midnight reaches for the pin at eleven,
     * not the one at seven, even though seven is nearer on the ruler.
     */
    @Test
    fun `the nearer pin is the one that moves`() {
        assertTrue("half past midnight is the entry pin's", NightWindow.grabsEntry(0.5f, 23, 7))
        assertFalse("but six in the morning is the exit pin's", NightWindow.grabsEntry(6f, 23, 7))
        assertEquals("eleven at night to one in the morning", 2f, NightWindow.apart(23f, 1f), 0.01f)
    }

    /** The far end of the bar is midnight at the near end, not a 25th hour. */
    @Test
    fun `there is no hour after the last one`() {
        val bar = bar()
        bar.holdForTest(false)
        bar.moveTo(bar.width * 2f)
        assertTrue("$bar.to", bar.to in 0..23)
    }

    /** Nothing held, nothing moves — a stray move event is not a drag. */
    @Test
    fun `a pin nobody has hold of stays put`() {
        val bar = bar()
        val before = bar.from
        bar.moveTo(0f)
        assertEquals(before, bar.from)
    }

    /** And it says which window it is describing, in one line. */
    @Test
    fun `the window is written out`() {
        assertEquals("22:00 – 07:00", NightWindow.label(22, 7))
        assertEquals("00:00 – 23:00", NightWindow.label(0, 23))
    }

    // ------------------------------------------------- the menu itself

    /**
     * Every settings screen still has everything on it.
     *
     * Moving one row out of a screen took eight others with it, and the
     * only thing that noticed was a lint warning counted by hand. A menu
     * is a list of promises; this is the list.
     */
    @Test
    fun `no settings screen has quietly lost a row`() {
        val screens = mapOf(
            R.xml.root_preferences to listOf(
                Prefs.NIGHT_DIM, Prefs.NIGHT_WINDOW, Prefs.THEME, Prefs.SHOW_DATE,
                Prefs.BELLS, Prefs.BELL_STYLE, Prefs.BELL_MARKS, Prefs.BELLS_BACKGROUND,
                Prefs.TEST_BELLS, Prefs.TICKING, Prefs.ALARM_RAMP,
                Prefs.RING_TIMEOUT_MIN, Prefs.SNOOZE_LIMIT,
                Prefs.WORLD_CLOCK, Prefs.ADVANCED
            ),
            R.xml.very_advanced_preferences to listOf(
                Prefs.HOURS_PRESET, Prefs.HOURS_CUSTOM, Prefs.MIRROR,
                Prefs.TIME_SPEED, Prefs.SOLAR_TIME, "pref_system_time"
            ),
            R.xml.advanced_preferences to listOf(
                Prefs.MARK_COLORS, Prefs.BIRTHDAY
            )
        )
        for ((xml, keys) in screens) {
            val text = readXml(xml)
            for (key in keys) {
                assertTrue("$key has gone missing", text.contains(key))
            }
        }
        // And the rows that really did move are gone from where they were.
        assertFalse(readXml(R.xml.very_advanced_preferences).contains(Prefs.BELL_MARKS))
        // These two are properties of an alarm now, not of the app, and the
        // old app-wide keys are written out rather than named through
        // Prefs: the constants are gone, and a row that came back under the
        // old key would have nothing left to fail against.
        assertFalse(readXml(R.xml.root_preferences).contains("pref_mission"))
        assertFalse(readXml(R.xml.root_preferences).contains("pref_gentle_wake"))
    }

    /** Every key named in a preference screen, as a set of strings. */
    private fun readXml(xml: Int): Set<String> {
        val parser = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.getXml(xml)
        val keys = HashSet<String>()
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android", "key"
                )?.let { keys.add(it) }
            }
        }
        return keys
    }

    // ------------------------------------------------- the row it lives in

    /**
     * And the whole row is out of sight until there is a night to set the
     * hours of: it was two rows asking a question about a feature that is
     * off by default.
     */
    @Test
    fun `the hours are hidden until night mode is switched on`() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
        prefs.edit().clear().commit()

        org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val fragment = c.get().supportFragmentManager.fragments.first()
                as SettingsActivity.RootSettingsFragment
            val row = fragment.findPreference<androidx.preference.Preference>(Prefs.NIGHT_WINDOW)
            assertNotNull("the row must exist to be hidden", row)
            assertFalse("nothing to set until dimming is on", row!!.isVisible)

            val dim = fragment.findPreference<androidx.preference.SwitchPreferenceCompat>(
                Prefs.NIGHT_DIM
            )!!
            dim.performClick()

            assertTrue("and it appears the moment it is", row.isVisible)
        }
    }
}
