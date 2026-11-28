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
            .putBoolean(Prefs.FACE_ASKED, true)
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

    private fun pageOn(face: Face): ClockView.NumeralStyle? {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(Prefs.FACE, face.key).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().showCardForTest(Card.CALENDAR)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            return c.get().calendarNumeralsForTest()
        }
    }

    /**
     * Three clocks on one phone, and the page behind each writes its days
     * its own way: under the sundial in Roman numerals from the start,
     * behind the other two in ordinary digits.
     */
    @Test
    fun `a phone that was never asked has Roman days under the sundial and nowhere else`() {
        assertEquals(ClockView.NumeralStyle.ROMAN, pageOn(Face.SUNDIAL))
        assertEquals(ClockView.NumeralStyle.ARABIC, pageOn(Face.ANALOG))
        assertEquals(ClockView.NumeralStyle.ARABIC, pageOn(Face.DIGITAL))
    }

    /** An answer given on one face is that face's, and the other two keep theirs. */
    @Test
    fun `each face keeps its own answer`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(Prefs.CALENDAR_NUMERALS, Prefs.NUMERALS_NONE)
            .putString(Prefs.CALENDAR_NUMERALS_DIGITAL, Prefs.NUMERALS_ROMAN)
            .putString(Prefs.CALENDAR_NUMERALS_SUNDIAL, Prefs.NUMERALS_ARABIC)
            .commit()
        assertEquals(ClockView.NumeralStyle.NONE, pageOn(Face.ANALOG))
        assertEquals(ClockView.NumeralStyle.ROMAN, pageOn(Face.DIGITAL))
        assertEquals(ClockView.NumeralStyle.ARABIC, pageOn(Face.SUNDIAL))
        // And the row each face shows is the one its page reads.
        assertEquals(Prefs.CALENDAR_NUMERALS, Prefs.calendarNumeralsKey(Face.ANALOG))
        assertEquals(Prefs.CALENDAR_NUMERALS_DIGITAL, Prefs.calendarNumeralsKey(Face.DIGITAL))
        assertEquals(Prefs.CALENDAR_NUMERALS_SUNDIAL, Prefs.calendarNumeralsKey(Face.SUNDIAL))
    }

    /**
     * The one answer there used to be goes on being the dial's, is handed
     * to the digits once, and is not handed to the sundial — which is the
     * face the question was split for.
     */
    @Test
    fun `the answer all three shared is the dial's and the digits', and not the sundial's`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(Prefs.CALENDAR_NUMERALS, Prefs.NUMERALS_NONE).commit()
        assertEquals("the dial lost the answer it had", ClockView.NumeralStyle.NONE, pageOn(Face.ANALOG))
        assertEquals("the digits were not handed it", ClockView.NumeralStyle.NONE, pageOn(Face.DIGITAL))
        assertEquals(Prefs.NUMERALS_NONE, prefs.getString(Prefs.CALENDAR_NUMERALS_DIGITAL, null))
        assertEquals("the sundial took the other faces' answer", ClockView.NumeralStyle.ROMAN, pageOn(Face.SUNDIAL))
        assertEquals("the sundial was given an answer nobody gave it", null, prefs.getString(Prefs.CALENDAR_NUMERALS_SUNDIAL, null))
        // Once: the digits' own answer, given since, is not written over
        // by the dial's the next time the app is opened.
        prefs.edit().putString(Prefs.CALENDAR_NUMERALS_DIGITAL, Prefs.NUMERALS_ROMAN).commit()
        assertEquals(ClockView.NumeralStyle.ROMAN, pageOn(Face.DIGITAL))
        prefs.edit().putString(Prefs.CALENDAR_NUMERALS, Prefs.NUMERALS_ARABIC).commit()
        assertEquals("the dial's answer was handed over a second time", ClockView.NumeralStyle.ROMAN, pageOn(Face.DIGITAL))
    }

    /** A phone that never gave the one answer has nothing handed over, and nothing written down. */
    @Test
    fun `nothing is handed over on a phone that was never asked`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        pageOn(Face.DIGITAL)
        assertEquals(null, prefs.getString(Prefs.CALENDAR_NUMERALS_DIGITAL, null))
    }
}
