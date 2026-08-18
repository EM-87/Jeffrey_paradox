package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which way round the date is written.
 *
 * The dial wrote `15/03/2026` and never asked. Half the world writes
 * `03/15/2026`, and for twelve days of every month the two are the same
 * string meaning different days — `03/04` is the third of April or the
 * fourth of March and there is nothing in it to say which. A date you
 * cannot read off is a decoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DateShapeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the two orders really are different strings`() {
        assertNotEquals(
            DateShape.numberPattern(true), DateShape.numberPattern(false)
        )
        assertNotEquals(
            DateShape.textPattern(true), DateShape.textPattern(false)
        )
        assertEquals("XV·III·MMXXVI", DateShape.roman(15, 3, 2026, dayFirst = true))
        assertEquals("III·XV·MMXXVI", DateShape.roman(15, 3, 2026, dayFirst = false))
    }

    /**
     * The weekday stays at the front either way. Nobody writes "15 Sun Mar";
     * the question is only about the two numbers that can be mistaken for
     * each other.
     */
    @Test
    fun `the weekday does not move`() {
        for (dayFirst in listOf(true, false)) {
            assertTrue(
                DateShape.textPattern(dayFirst),
                DateShape.textPattern(dayFirst).startsWith("EEE")
            )
        }
    }

    /**
     * Left alone, it agrees with the phone.
     *
     * Which is the answer most people want and have already given once,
     * somewhere else, to the setting every other app on the phone reads.
     */
    @Test
    fun `unasked, it follows the phone`() {
        assertEquals(true, DateShape.dayFirst(DateShape.Order.AUTO, phoneSaysDayFirst = true))
        assertEquals(false, DateShape.dayFirst(DateShape.Order.AUTO, phoneSaysDayFirst = false))
        // And asked, it does not care what the phone thinks.
        assertEquals(true, DateShape.dayFirst(DateShape.Order.DAY_FIRST, false))
        assertEquals(false, DateShape.dayFirst(DateShape.Order.MONTH_FIRST, true))
    }

    /** Anything unrecognised follows the phone, which is the safe answer. */
    @Test
    fun `a value from another version does not break it`() {
        assertEquals(DateShape.Order.AUTO, DateShape.order(null))
        assertEquals(DateShape.Order.AUTO, DateShape.order("something else"))
        assertEquals(DateShape.Order.MONTH_FIRST, DateShape.order(DateShape.MONTH_FIRST))
    }

    /**
     * And the dial really writes it that way round, which is the half the
     * arithmetic above cannot answer.
     */
    @Test
    fun `the dial writes the date the way it is told`() {
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            context, R.style.Theme_WeirdClock
        )
        val view = ClockView(themed).apply {
            dateFormatStyle = ClockView.DateFormatStyle.NUMBER
        }
        view.dateDayFirst = true
        val dayFirst = view.dateTextForTest()
        view.dateDayFirst = false
        val monthFirst = view.dateTextForTest()

        assertNotEquals(
            "the dial ignored the setting: '$dayFirst' both ways",
            dayFirst, monthFirst
        )
        // The same two numbers, swapped — not two different dates.
        assertEquals(
            dayFirst.split("/").let { listOf(it[1], it[0], it[2]) },
            monthFirst.split("/")
        )
    }

    /**
     * The two date rows are on the screen where a date is spelled, and both
     * of them go when the dial is not showing one.
     *
     * "Date format" was on the first screen, where it was read as this
     * setting and opened by mistake. They are different questions: one is
     * how the date is *spelled*, the other is which number means what.
     */
    @Test
    fun `both date rows live with the dial and follow the date switch`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        for (showing in listOf(false, true)) {
            prefs.edit().clear().putBoolean(Prefs.SHOW_DATE, showing).commit()
            org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
                c.setup()
                val advanced = SettingsActivity.AdvancedSettingsFragment()
                c.get().supportFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, advanced).commitNow()
                for (key in listOf(Prefs.DATE_FORMAT, Prefs.DATE_ORDER)) {
                    val row = advanced.findPreference<androidx.preference.Preference>(key)
                    assertTrue("$key is not on the advanced screen", row != null)
                    assertEquals(
                        "$key with the date ${if (showing) "shown" else "hidden"}",
                        showing, row!!.isVisible
                    )
                }
            }
        }
    }
}
