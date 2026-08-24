package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The month page writes its days in its own numerals.
 *
 * It borrowed the dial's, which is two questions sharing one row. Roman
 * numerals are a fine thing to have on a clock face — twelve of them,
 * three characters at the longest, in the places everybody already knows.
 * A grid of thirty-one of them is a puzzle: XXVIII and XXVII differ by one
 * character at the end and sit next to each other, and nobody reads a
 * calendar character by character.
 *
 * So they are two settings now, and this is the promise that they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class CalendarNumeralsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
    }

    private fun styles(dial: String, calendar: String): Pair<ClockView.NumeralStyle, ClockView.NumeralStyle> {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.NUMERALS, dial)
            .putString(Prefs.CALENDAR_NUMERALS, calendar)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CALENDAR)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            return c.get().clockForTest().numeralStyle to
                (c.get().calendarNumeralsForTest() ?: ClockView.NumeralStyle.ARABIC)
        }
    }

    /** Roman on the dial, Arabic on the month page, at the same time. */
    @Test
    fun `the month page writes its days in its own numerals`() {
        val (dial, calendar) = styles(Prefs.NUMERALS_ROMAN, Prefs.NUMERALS_ARABIC)
        assertEquals("the dial did not take the Roman numerals", ClockView.NumeralStyle.ROMAN, dial)
        assertEquals(
            "the month page followed the dial instead of its own setting",
            ClockView.NumeralStyle.ARABIC, calendar
        )
    }

    /** And the other way round, so neither is simply ignoring the other. */
    @Test
    fun `the dial keeps its own when the month page goes Roman`() {
        val (dial, calendar) = styles(Prefs.NUMERALS_ARABIC, Prefs.NUMERALS_ROMAN)
        assertEquals(ClockView.NumeralStyle.ARABIC, dial)
        assertEquals(ClockView.NumeralStyle.ROMAN, calendar)
    }

    /** Untouched, the month page is in Arabic, whatever the dial is doing. */
    @Test
    fun `an untouched month page is in ordinary digits`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.NUMERALS, Prefs.NUMERALS_ROMAN)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CALENDAR)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(
                "a fresh install writes its calendar in Roman numerals",
                ClockView.NumeralStyle.ARABIC, c.get().calendarNumeralsForTest()
            )
        }
    }
}
