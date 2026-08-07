package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * When an alarm next goes off.
 *
 * Every case pins a fixed "now" rather than the machine's clock, so a test
 * that passes at ten in the morning still passes at midnight — which is
 * exactly the kind of thing this arithmetic gets wrong.
 */
class AlarmSchedulerTest {

    /** A local-time instant, built the same way the scheduler reads one. */
    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Long = Calendar.getInstance().apply {
        set(year, month - 1, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun fieldsOf(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    private fun alarm(hour: Int, minute: Int = 0, mask: Int = Alarm.ALL_DAYS) =
        Alarm(1, hour, minute, true, Prefs.ALARM_SOUND_BELLS, daysMask = mask)

    // Wednesday 15 July 2026, quarter past ten in the morning.
    private val wednesdayMorning = at(2026, 7, 15, 10, 15)

    @Test
    fun `later today is today`() {
        val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(18, 30), wednesdayMorning))
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, next.get(Calendar.MINUTE))
    }

    @Test
    fun `earlier today is tomorrow`() {
        val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(7, 0), wednesdayMorning))
        assertEquals(16, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `the very minute it is now counts as gone`() {
        // Ten fifteen, on the dot, is not a future alarm — waiting for it
        // would mean arming something already in the past.
        val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(10, 15), wednesdayMorning))
        assertEquals(16, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a weekday alarm skips the weekend`() {
        // Friday evening: the next weekday morning is Monday, not Saturday.
        val fridayEvening = at(2026, 7, 17, 20, 0)
        val next = fieldsOf(
            AlarmScheduler.nextOccurrence(alarm(7, 0, Alarm.WEEKDAYS), fridayEvening)
        )
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(20, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a weekend alarm from midweek lands on Saturday`() {
        val next = fieldsOf(
            AlarmScheduler.nextOccurrence(alarm(9, 0, Alarm.WEEKENDS), wednesdayMorning)
        )
        assertEquals(Calendar.SATURDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(18, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a one-shot takes the next time that hour comes round`() {
        val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(7, 0, mask = 0), wednesdayMorning))
        assertEquals(16, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `several times a day picks the soonest still ahead`() {
        // Pills at 8, 14 and 22. At quarter past ten, the next one is two.
        val pills = alarm(8, 0).apply { extraTimes = mutableListOf(14 * 60, 22 * 60) }
        val next = fieldsOf(AlarmScheduler.nextOccurrence(pills, wednesdayMorning))
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `after the last time of day it rolls to the first one tomorrow`() {
        val pills = alarm(8, 0).apply { extraTimes = mutableListOf(14 * 60, 22 * 60) }
        val lateNight = at(2026, 7, 15, 23, 30)
        val next = fieldsOf(AlarmScheduler.nextOccurrence(pills, lateNight))
        assertEquals(16, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `the answer is always in the future, whatever the hour`() {
        // The property that matters most: an alarm armed in the past never
        // rings at all. Swept across every hour of the dial and every mask.
        for (mask in listOf(0, Alarm.ALL_DAYS, Alarm.WEEKDAYS, Alarm.WEEKENDS)) {
            for (hour in 0..23) {
                for (nowHour in 0..23) {
                    val now = at(2026, 7, 15, nowHour, 30)
                    val next = AlarmScheduler.nextOccurrence(alarm(hour, 0, mask), now)
                    assertTrue(
                        "mask=$mask alarm=$hour:00 now=$nowHour:30 gave a past time",
                        next > now
                    )
                }
            }
        }
    }

    @Test
    fun `a weekday alarm always lands on a weekday`() {
        for (nowDay in 13..19) {
            val now = at(2026, 7, nowDay, 12, 0)
            val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(7, 0, Alarm.WEEKDAYS), now))
            val day = next.get(Calendar.DAY_OF_WEEK)
            assertTrue(
                "landed on day $day",
                day != Calendar.SATURDAY && day != Calendar.SUNDAY
            )
        }
    }

    @Test
    fun `it crosses the end of a month`() {
        val lastDay = at(2026, 7, 31, 23, 0)
        val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(7, 0), lastDay))
        assertEquals(1, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.AUGUST, next.get(Calendar.MONTH))
    }

    @Test
    fun `it crosses the end of a year`() {
        val newYearsEve = at(2026, 12, 31, 23, 30)
        val next = fieldsOf(AlarmScheduler.nextOccurrence(alarm(7, 0), newYearsEve))
        assertEquals(2027, next.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, next.get(Calendar.MONTH))
        assertEquals(1, next.get(Calendar.DAY_OF_MONTH))
    }
}
