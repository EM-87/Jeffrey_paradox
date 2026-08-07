package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Repeating reminders: which days they fall on, and when they next ring.
 *
 * The yearly case is here because it went wrong in the field and could only
 * be caught by waiting a year — or by pinning the clock, as below.
 */
class ReminderTest {

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Long = Calendar.getInstance().apply {
        set(year, month - 1, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun fieldsOf(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    private fun reminder(
        year: Int = 2026, month: Int = 3, day: Int = 15,
        hour: Int = 9, minute: Int = 0,
        repeat: String = Reminder.REPEAT_NEVER,
        lead: Int = 0
    ) = Reminder(
        1, year, month, day, hour, minute, "Dentist",
        repeat = repeat, leadMinutes = lead
    )

    // ------------------------------------------------------------ occursOn

    @Test
    fun `a one-off falls on its own date and no other`() {
        val r = reminder()
        assertTrue(r.occursOn(2026, 3, 15))
        assertFalse(r.occursOn(2027, 3, 15))
        assertFalse(r.occursOn(2026, 4, 15))
        assertFalse(r.occursOn(2026, 3, 16))
    }

    @Test
    fun `a yearly one falls on every anniversary from its own year on`() {
        val r = reminder(repeat = Reminder.REPEAT_YEARLY)
        assertTrue(r.occursOn(2026, 3, 15))
        assertTrue(r.occursOn(2030, 3, 15))
        // Not before it was ever set.
        assertFalse(r.occursOn(2025, 3, 15))
        assertFalse(r.occursOn(2027, 4, 15))
    }

    @Test
    fun `a monthly one falls on its day of every later month`() {
        val r = reminder(repeat = Reminder.REPEAT_MONTHLY)
        assertTrue(r.occursOn(2026, 3, 15))
        assertTrue(r.occursOn(2026, 12, 15))
        assertTrue(r.occursOn(2027, 1, 15))
        // Earlier in the same year, before it existed.
        assertFalse(r.occursOn(2026, 2, 15))
        assertFalse(r.occursOn(2026, 4, 16))
    }

    // ------------------------------------------------------ nextTimeInMillis

    @Test
    fun `a one-off keeps its date even once it is past`() {
        // It does not move: the calendar still shows what that day held.
        val r = reminder()
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 6, 1, 12, 0)))
        assertEquals(2026, next.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, next.get(Calendar.MONTH))
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a yearly one rolls to next year once this year's has gone`() {
        val r = reminder(repeat = Reminder.REPEAT_YEARLY)
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 6, 1, 12, 0)))
        assertEquals(2027, next.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, next.get(Calendar.MONTH))
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a yearly one still standing this year does not roll`() {
        val r = reminder(repeat = Reminder.REPEAT_YEARLY)
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 1, 1, 12, 0)))
        assertEquals(2026, next.get(Calendar.YEAR))
    }

    @Test
    fun `a monthly one moves to the next month`() {
        val r = reminder(repeat = Reminder.REPEAT_MONTHLY)
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 3, 20, 12, 0)))
        assertEquals(Calendar.APRIL, next.get(Calendar.MONTH))
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a monthly 31st stays on the 31st and skips the months without one`() {
        // The trap: adding a month to 31 January lands on 3 March in a
        // sliding calendar. It has to stay the 31st.
        val r = reminder(year = 2026, month = 1, day = 31, repeat = Reminder.REPEAT_MONTHLY)
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 2, 5, 12, 0)))
        assertEquals(31, next.get(Calendar.DAY_OF_MONTH))
        // February has no 31st, so the next one is March.
        assertEquals(Calendar.MARCH, next.get(Calendar.MONTH))
    }

    @Test
    fun `a yearly 29 February keeps its date`() {
        val r = reminder(year = 2024, month = 2, day = 29, repeat = Reminder.REPEAT_YEARLY)
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 6, 1, 12, 0)))
        assertEquals(29, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, next.get(Calendar.MONTH))
        assertEquals(2028, next.get(Calendar.YEAR))
    }

    @Test
    fun `a repeating reminder never returns a time in the past`() {
        for (repeat in listOf(Reminder.REPEAT_MONTHLY, Reminder.REPEAT_YEARLY)) {
            for (month in 1..12) {
                val now = at(2026, month, 10, 12, 0)
                val r = reminder(year = 2020, month = 5, day = 20, repeat = repeat)
                assertTrue("$repeat in month $month", r.nextTimeInMillis(now) > now)
            }
        }
    }

    // ------------------------------------------------------------- weekly

    @Test
    fun `a weekly one falls on the same weekday, week after week`() {
        // 15 March 2026 is a Sunday.
        val r = reminder(repeat = Reminder.REPEAT_WEEKLY)
        assertTrue(r.occursOn(2026, 3, 15))
        assertTrue(r.occursOn(2026, 3, 22))
        assertTrue(r.occursOn(2026, 3, 29))
        assertTrue(r.occursOn(2026, 4, 5))
        assertFalse(r.occursOn(2026, 3, 16))
        assertFalse(r.occursOn(2026, 3, 21))
        // Never before the week it was set.
        assertFalse(r.occursOn(2026, 3, 8))
    }

    @Test
    fun `a weekly one keeps its weekday across a daylight-saving change`() {
        // Europe's clocks go forward on 29 March 2026 — one of these weeks
        // is 23 hours long, which is what an integer division would lose.
        val r = reminder(repeat = Reminder.REPEAT_WEEKLY)
        for (week in 0..30) {
            val cal = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.MARCH, 15)
                add(Calendar.DAY_OF_YEAR, week * 7)
            }
            assertTrue(
                "week $week",
                r.occursOn(
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH)
                )
            )
        }
    }

    @Test
    fun `a weekly one crosses the end of a year`() {
        val r = reminder(year = 2026, month = 12, day = 27, repeat = Reminder.REPEAT_WEEKLY)
        assertTrue(r.occursOn(2027, 1, 3))
        assertFalse(r.occursOn(2027, 1, 2))
    }

    @Test
    fun `a weekly one rings on the next matching weekday`() {
        val r = reminder(repeat = Reminder.REPEAT_WEEKLY)
        val next = fieldsOf(r.nextTimeInMillis(at(2026, 3, 18, 12, 0)))
        assertEquals(22, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.SUNDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `a weekly one never returns a time in the past`() {
        val r = reminder(year = 2020, month = 5, day = 20, repeat = Reminder.REPEAT_WEEKLY)
        for (month in 1..12) {
            val now = at(2026, month, 10, 12, 0)
            assertTrue("month $month", r.nextTimeInMillis(now) > now)
        }
    }

    // ---------------------------------------------------------- lead time

    @Test
    fun `lead time rings early without moving the event`() {
        val r = reminder(repeat = Reminder.REPEAT_YEARLY, lead = 30)
        val now = at(2026, 1, 1, 12, 0)
        assertEquals(30 * 60_000L, r.nextTimeInMillis(now) - r.ringAtMillis(now))
        val ring = fieldsOf(r.ringAtMillis(now))
        assertEquals(8, ring.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, ring.get(Calendar.MINUTE))
    }

    @Test
    fun `a week of lead time crosses back over the day`() {
        val r = reminder(month = 3, day = 15, hour = 9, lead = 7 * 24 * 60)
        val ring = fieldsOf(r.ringAtMillis(at(2026, 1, 1, 12, 0)))
        assertEquals(8, ring.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, ring.get(Calendar.MONTH))
    }

    @Test
    fun `no lead time rings on the dot`() {
        val r = reminder()
        val now = at(2026, 1, 1, 12, 0)
        assertEquals(r.nextTimeInMillis(now), r.ringAtMillis(now))
    }
}
