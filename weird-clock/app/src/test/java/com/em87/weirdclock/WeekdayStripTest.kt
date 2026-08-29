package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seven days across an alarm card, which were seven whole words.
 *
 * `SUNDAY MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY`, wrapped
 * onto three lines of a row with space for one, in every photograph of an
 * alarm this project has ever taken — and it went unread every time,
 * because nobody reads the part of a picture whose shape they already
 * know.
 *
 * The same fault was found on the month page a version earlier and fixed
 * there, by measuring the name against the column it had to fit in. This
 * is the second place it lives. The month page's fix did not reach it,
 * which is the argument for the rule being somewhere both of them can
 * see it — see [Weekday.narrow].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeekdayStripTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun cards() = AlarmCards(
        host = context,
        prefs = PreferenceManager.getDefaultSharedPreferences(context),
        alarms = emptyList(),
        dialTheme = { ClockThemes.MIDNIGHT },
        hoursOnDial = { 12 },
        dialShape = { ClockView.DialShape.CIRCLE },
        onToggled = { _, _ -> },
        onOpen = { }
    )

    /**
     * The rule itself: a narrow name is let through and a word is not.
     *
     * Two characters rather than one, because a narrow name is two in
     * some languages — Chinese writes 週一, and cutting that to 週 turns
     * all seven days into the same character.
     */
    @Test
    fun `a narrow name survives and a whole word does not`() {
        assertEquals("S", Weekday.narrow("S"))
        assertEquals("週一", Weekday.narrow("週一"))
        assertEquals("S", Weekday.narrow("SUNDAY"))
        assertEquals("M", Weekday.narrow("MIÉRCOLES"))
        assertEquals("", Weekday.narrow(""))
    }

    /**
     * And the strip an alarm card actually gets.
     *
     * Asked of the thing the row is built from rather than of the rule,
     * because the rule was never the part that was wrong — the calendar
     * was, and this is the only place that asks it.
     */
    @Test
    fun `the seven days fit across one row`() {
        val letters = cards().weekdayLetters()
        assertEquals(7, letters.size)
        for (letter in letters) {
            assertTrue(
                "the days of the week are whole words again: $letters",
                letter.codePointCount(0, letter.length) <= Weekday.NARROW
            )
        }
        // And they are still seven different days rather than seven of
        // the same letter, which is the way this fix could go wrong.
        assertTrue("the week collapsed into one letter: $letters", letters.toSet().size >= 4)
    }
}
