package com.em87.weirdclock

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(12f, bar.hourAt(bar.width / 2f), 0.3f)
        assertEquals("the left end is midnight", 0f, bar.hourAt(bar.leftEndForTest()), 0.01f)
        assertEquals(
            "and the right end is midnight again",
            24f, bar.hourAt(bar.width - bar.leftEndForTest()), 0.05f
        )
    }

    /**
     * A day is round, so the bar is: drag the entry pin off the right-hand
     * end and it comes back on at midnight.
     *
     * Ten at night to midnight is *forwards*, and forwards on this bar is
     * rightwards — so that is the gesture somebody makes to ask for a night
     * that starts at midnight. It used to stop dead at 23, which left the
     * two hours either side of midnight as the only ones a drag could not
     * reach: the band wrapped and the pin did not.
     */
    @Test
    fun `a pin dragged off the end comes back on at the other`() {
        val bar = bar()
        bar.setWindow(22, 7)
        bar.holdForTest(true)
        // On to the right-hand end, which is where 22 → 23 → midnight goes.
        bar.moveTo(bar.width - bar.leftEndForTest())
        assertEquals("midnight, not stuck at eleven", 0, bar.from)

        // And the same the other way, for the pin that lives near midnight.
        bar.holdForTest(false)
        bar.moveTo(-bar.width / 24f)
        assertEquals("eleven at night", 23, bar.to)
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

    /**
     * The bar is grabbed by its pins and nowhere else.
     *
     * It used to take hold anywhere along its length and jump the nearer
     * pin to the finger — so a scroll of the settings list that happened to
     * start on the bar changed the hours on the way past, silently. The
     * rest of the bar belongs to the list.
     */
    @Test
    fun `only the pins take hold, and the rest belongs to the list`() {
        val bar = bar()
        bar.setWindow(22, 7)
        assertNotNull("the entry pin", bar.pinUnderForTest(bar.xOfForTest(22f)))
        assertNotNull("the exit pin", bar.pinUnderForTest(bar.xOfForTest(7f)))
        assertEquals("and it knows which is which", true, bar.pinUnderForTest(bar.xOfForTest(22f)))
        assertEquals(false, bar.pinUnderForTest(bar.xOfForTest(7f)))

        for (hour in intArrayOf(2, 4, 12, 15, 18)) {
            assertNull(
                "hour $hour is nowhere near a pin and the bar took it anyway",
                bar.pinUnderForTest(bar.xOfForTest(hour.toFloat()))
            )
        }
    }

    /** And a touch on the bare track is handed back, so the list can scroll. */
    @Test
    fun `a touch away from the pins is not consumed`() {
        val bar = bar()
        bar.setWindow(22, 7)
        val away = bar.xOfForTest(12f)
        val event = android.view.MotionEvent.obtain(
            0, 0, android.view.MotionEvent.ACTION_DOWN, away, 10f, 0
        )
        assertFalse("the bar swallowed a scroll", bar.onTouchEvent(event))
        assertEquals("and moved a pin while it was at it", 22, bar.from)
    }

    // ------------------------------------------------------- the toolbox

    /**
     * The way to put the hands back is on the glass, and only when there is
     * something to put back.
     *
     * It was a row three screens into the settings, which is the one place
     * you cannot look while looking at the mess it fixes — and a dial with
     * its hands on the floor is a dial you cannot wind, so the moment you
     * need it is the moment the app is least willing to be navigated.
     */
    @Test
    fun `the toolbox is only there when there is something to put back`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        ).edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true).commit()

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertFalse("nothing has fallen and it is offering to help", c.get().reassembleShowing())
        }
    }

    /**
     * And a knock has to be a knock.
     *
     * Fourteen was clearing the jolt of a phone being set down on a table,
     * so the hands came off on the way to the table rather than when
     * anybody meant them to — which turns a thing you do for fun into a
     * thing that happens to you.
     */
    @Test
    fun `setting the phone down is not a knock`() {
        assertTrue(
            "a knock has to be a rap on the glass, not the end of a journey",
            ClockView.shakeThresholdForTest() >= 20f
        )
    }

    // ------------------------------------------------- the menu itself

    /**
     * Every settings screen, in full.
     *
     * Not "these rows are still there" but "these rows and no others".
     * Moving one row out of a screen once took eight others with it, and
     * the only thing that noticed was a lint warning counted by hand — a
     * partial list would have missed that, because everything it named was
     * still present. Written out as sets, a row that vanishes and a row
     * that turns up on the wrong screen both fail here.
     *
     * It is a long test to read and that is the point: a menu is a list of
     * promises, and this is the list.
     */
    @Test
    fun `each settings screen holds exactly the rows it should`() {
        val screens = mapOf(
            R.xml.root_preferences to setOf(
                // Dial
                "pref_night_dim", "pref_night_window", "pref_theme",
                "pref_show_date",
                // Alarm
                "pref_bells", "pref_bell_marks", "pref_bell_style", "pref_test_bells",
                "pref_bells_background",
                // Calendar
                "pref_past_days", "pref_birthday", "pref_cycle",
                // General
                "pref_moon_phase", "pref_orrery",
                "pref_world_clock", "pref_world_cities",
                // And the ways on
                "pref_advanced", "pref_very_advanced", "pref_version"
            ),
            R.xml.advanced_preferences to setOf(
                "pref_numerals", "pref_dial_shape", "pref_hours_preset",
                "pref_hours_custom", "pref_date_format", "pref_date_order",
                "pref_mirror",
                "pref_alarm_ramp", "pref_ring_timeout",
                "pref_countdown_persistent", "pref_alarm_style",
                "pref_gentle_flash", "pref_alarm_markers",
                "pref_ticking"
            ),
            R.xml.very_advanced_preferences to setOf(
                "pref_mark_colors", "pref_second_hand", "pref_smooth_seconds",
                "pref_fast_hand", "pref_touch_hands", "pref_pinch_zoom",
                "pref_shake_drop",
                "pref_countdown_float",
                "pref_solar_time", "pref_system_time", "pref_time_speed",
                "pref_backup_export", "pref_backup_import"
            )
        )
        for ((xml, expected) in screens) {
            assertEquals(expected, readXml(xml))
        }
    }

    /**
     * And the two deeper screens hang off the first one, not off each
     * other.
     *
     * The ladder was the reason a change made at the far end took three
     * presses of Back to go and look at — which is three presses of Back
     * every time you adjust something and want to see what it did.
     */
    @Test
    fun `both deeper screens are reachable from the first`() {
        val root = readXml(R.xml.root_preferences)
        assertTrue("advanced", "pref_advanced" in root)
        assertTrue("too advanced", "pref_very_advanced" in root)
        assertFalse(
            "the too-advanced screen is still buried inside the advanced one",
            "pref_very_advanced" in readXml(R.xml.advanced_preferences)
        )
    }

    /**
     * Nothing depends on a row that is not on its own screen.
     *
     * Android resolves `android:dependency` within one screen and throws
     * when it cannot find the other end — so a row that moves screens takes
     * its dependents down with it, at the moment the screen is opened and
     * not before. Which is exactly what "ring with the app closed" did when
     * the bells moved up to the first screen.
     */
    @Test
    fun `no row depends on one from another screen`() {
        for (xml in intArrayOf(
            R.xml.root_preferences,
            R.xml.advanced_preferences,
            R.xml.very_advanced_preferences
        )) {
            val keys = readXml(xml)
            for (needed in dependenciesIn(xml)) {
                assertTrue(
                    "a row here depends on $needed, which is on another screen",
                    needed in keys
                )
            }
        }
    }

    /**
     * A row that only matters once another is on is not there until it is.
     *
     * This is the whole reorganisation in one test. Hiding rather than
     * greying out, because a disabled row still costs a line of scrolling
     * and still has to be read past to find out it is not the one you
     * want — and the sheet and the menu had both grown long enough that
     * scrolling was the problem.
     */
    @Test
    fun `a conditional row appears with the switch above it`() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext<android.content.Context>()
            )
        for ((screen, pairs) in mapOf<() -> androidx.preference.PreferenceFragmentCompat?, List<Pair<String, String>>>(
            { null } to listOf(
                Prefs.NIGHT_DIM to Prefs.NIGHT_WINDOW,
                Prefs.BELLS to Prefs.BELL_MARKS,
                Prefs.BELLS to Prefs.BELL_STYLE,
                Prefs.BELLS to Prefs.TEST_BELLS,
                Prefs.BELLS to Prefs.BELLS_BACKGROUND,
                Prefs.WORLD_CLOCK to "pref_world_cities",
                // The solar system hangs off the sky token, because the
                // whole gesture is a tap on the sun or the moon and there
                // is nothing to tap without one.
                Prefs.MOON_PHASE to Prefs.ORRERY
            ),
            { SettingsActivity.VeryAdvancedSettingsFragment() } to listOf(
                Prefs.SECOND_HAND to Prefs.SMOOTH_SECONDS,
                Prefs.SECOND_HAND to Prefs.FAST_HAND
            )
        )) {
            for ((parent, child) in pairs) {
                for (on in listOf(false, true)) {
                    prefs.edit().clear().putBoolean(parent, on).commit()
                    org.robolectric.Robolectric
                        .buildActivity(SettingsActivity::class.java).use { c ->
                            c.setup()
                            val made = screen()
                            val fragment = if (made == null) {
                                c.get().supportFragmentManager.fragments.first()
                                    as androidx.preference.PreferenceFragmentCompat
                            } else {
                                c.get().supportFragmentManager.beginTransaction()
                                    .replace(R.id.settings_container, made).commitNow()
                                made
                            }
                            val row =
                                fragment.findPreference<androidx.preference.Preference>(child)
                            assertNotNull("$child is not on the screen at all", row)
                            assertEquals(
                                "$child with $parent ${if (on) "on" else "off"}",
                                on, row!!.isVisible
                            )
                        }
                }
            }
        }
    }

    /**
     * And it hides the moment the switch is flicked, not on the next visit.
     *
     * The state on opening is the easy half. What somebody actually does is
     * turn the switch off and watch the row below it — and a row that only
     * takes the hint next time the screen is opened reads as a setting that
     * did nothing.
     */
    @Test
    fun `a conditional row goes as soon as the switch is turned off`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit().clear().putBoolean(Prefs.SECOND_HAND, true).commit()
        org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val f = SettingsActivity.VeryAdvancedSettingsFragment()
            c.get().supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, f).commitNow()
            val hand = f.findPreference<androidx.preference.SwitchPreferenceCompat>(
                Prefs.SECOND_HAND
            )!!
            val smooth = f.findPreference<androidx.preference.Preference>(Prefs.SMOOTH_SECONDS)!!
            assertTrue("set up wrong", smooth.isVisible)

            hand.performClick()

            assertFalse("the second hand is off and its refinement is still there", hand.isChecked)
            assertFalse("smooth sweep outlived the hand it smooths", smooth.isVisible)
        }
    }

    /** Every key a row on [xml] says it depends on. */
    private fun dependenciesIn(xml: Int): Set<String> {
        val parser = ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.getXml(xml)
        val found = HashSet<String>()
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android", "dependency"
                )?.let { found.add(it) }
            }
        }
        return found
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
