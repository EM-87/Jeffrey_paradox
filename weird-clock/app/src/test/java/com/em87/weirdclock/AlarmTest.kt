package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The day mask and the several times of day one alarm can hold.
 *
 * The one-shot case is the reason this file exists: "no days set" claimed in
 * the source to mean "rings once" and for a long time meant "rings every
 * day", which is what the assistant sends for "set an alarm for seven".
 */
class AlarmTest {

    private fun alarm(hour: Int = 7, minute: Int = 0, mask: Int = Alarm.ALL_DAYS) =
        Alarm(1, hour, minute, true, Prefs.ALARM_SOUND_BELLS, daysMask = mask)

    @Test
    fun `no days set is a one-shot`() {
        val a = alarm(mask = 0)
        assertTrue(a.once)
        // A one-shot takes whichever day arrives first, so every day answers
        // yes — what makes it single-use is being retired after it fires.
        for (day in Calendar.SUNDAY..Calendar.SATURDAY) assertTrue(a.ringsOn(day))
    }

    @Test
    fun `a full week is not a one-shot`() {
        assertFalse(alarm(mask = Alarm.ALL_DAYS).once)
    }

    @Test
    fun `weekdays ring Monday to Friday only`() {
        val a = alarm(mask = Alarm.WEEKDAYS)
        assertFalse(a.ringsOn(Calendar.SUNDAY))
        assertTrue(a.ringsOn(Calendar.MONDAY))
        assertTrue(a.ringsOn(Calendar.FRIDAY))
        assertFalse(a.ringsOn(Calendar.SATURDAY))
    }

    @Test
    fun `weekends ring Saturday and Sunday only`() {
        val a = alarm(mask = Alarm.WEEKENDS)
        assertTrue(a.ringsOn(Calendar.SUNDAY))
        assertTrue(a.ringsOn(Calendar.SATURDAY))
        for (day in Calendar.MONDAY..Calendar.FRIDAY) assertFalse(a.ringsOn(day))
    }

    @Test
    fun `weekday and weekend masks together cover the week exactly`() {
        assertEquals(Alarm.ALL_DAYS, Alarm.WEEKDAYS or Alarm.WEEKENDS)
        assertEquals(0, Alarm.WEEKDAYS and Alarm.WEEKENDS)
    }

    @Test
    fun `extra times are sorted and the alarm's own time is included`() {
        val a = alarm(hour = 14, minute = 30)
        a.extraTimes = mutableListOf(8 * 60, 22 * 60 + 15)
        assertEquals(
            listOf(8 to 0, 14 to 30, 22 to 15),
            a.allTimes()
        )
        assertEquals(3, a.timeCount())
    }

    @Test
    fun `a duplicated time is only counted once`() {
        val a = alarm(hour = 9, minute = 0)
        a.extraTimes = mutableListOf(9 * 60)
        assertEquals(listOf(9 to 0), a.allTimes())
    }

    @Test
    fun `setTime and timeAt agree, index zero being the alarm's own`() {
        val a = alarm(hour = 6, minute = 0)
        a.extraTimes = mutableListOf(12 * 60, 18 * 60)

        a.setTime(0, 5, 45)
        assertEquals(5 to 45, a.timeAt(0))
        assertEquals(5, a.hour)
        assertEquals(45, a.minute)

        a.setTime(2, 19, 30)
        assertEquals(19 to 30, a.timeAt(2))
        assertEquals(12 to 0, a.timeAt(1))
    }

    @Test
    fun `setTime past the end of the list changes nothing`() {
        val a = alarm(hour = 6, minute = 0)
        a.setTime(3, 23, 0)
        assertEquals(6 to 0, a.timeAt(0))
        assertEquals(1, a.timeCount())
    }
}
