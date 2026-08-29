package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Somebody else's diary, put on a dial.
 *
 * The two questions here are the two that a calendar always gets wrong,
 * and both are about edges. Which day is an event *on*, when it starts on
 * Friday night and ends on Saturday morning? And where does it go on a
 * face with twenty-four hours on it, when it is longer than a day?
 *
 * Getting either wrong is not a crash — it is a wedge running three times
 * round the dial, or a birthday marked on the wrong day, both of which
 * look deliberate.
 */
class AgendaTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun event(
        fromMs: Long, toMs: Long, title: String = "the dentist", allDay: Boolean = false
    ) = Agenda.Event(1L, title, fromMs, toMs, allDay)

    private fun midnight(year: Int, month: Int, day: Int) = at(year, month, day, 0)

    /**
     * An event is on a day if any part of it is, at either end.
     *
     * The overnight case is the one worth having: something that started
     * last night and runs until this morning is on today, and a clock that
     * said otherwise would show an empty day to somebody who is in the
     * middle of a thing.
     */
    @Test
    fun `an event that touches a day is on that day`() {
        val day = midnight(2026, 6, 21)
        val next = day + Agenda.DAY_MS
        assertTrue(Agenda.overlaps(event(at(2026, 6, 21, 9), at(2026, 6, 21, 10)), day, next))
        assertTrue(
            "an overnight event was not on the morning it ended",
            Agenda.overlaps(event(at(2026, 6, 20, 22), at(2026, 6, 21, 7)), day, next)
        )
        assertTrue(
            "an event starting at ten to midnight was not on today",
            Agenda.overlaps(event(at(2026, 6, 21, 23, 50), at(2026, 6, 22, 1)), day, next)
        )
        assertFalse("tomorrow leaked into today",
            Agenda.overlaps(event(at(2026, 6, 22, 9), at(2026, 6, 22, 10)), day, next))
        // And the half-open end: something finishing exactly at midnight
        // belongs to the day it finished, not to the next one.
        assertFalse(
            "an event ending at midnight was counted twice",
            Agenda.overlaps(event(at(2026, 6, 21, 23), at(2026, 6, 22, 0)), next, next + Agenda.DAY_MS)
        )
    }

    /**
     * On the dial an event is clipped to the day it is being drawn on.
     *
     * A conference from Friday to Sunday drawn unclipped is a wedge more
     * than three times round the face, which on a twelve-hour dial is a
     * ring — a solid ring, over every hour, saying nothing.
     */
    @Test
    fun `a long event is cut down to the day being drawn`() {
        val saturday = midnight(2026, 6, 20)
        val long = event(at(2026, 6, 19, 18), at(2026, 6, 21, 11))
        val on = Agenda.minutesOn(long, saturday)!!
        assertEquals("it did not start at midnight", 0, on[0])
        assertEquals("it did not run the whole day", 24 * 60, on[1])

        // The two half-days at each end of it.
        val friday = midnight(2026, 6, 19)
        val start = Agenda.minutesOn(long, friday)!!
        assertEquals(18 * 60, start[0])
        assertEquals(6 * 60, start[1])
        val sunday = midnight(2026, 6, 21)
        val end = Agenda.minutesOn(long, sunday)!!
        assertEquals(0, end[0])
        assertEquals(11 * 60, end[1])
    }

    /**
     * An all-day event has no place on a dial at all.
     *
     * It is not "a thing lasting twenty-four hours" — it is a birthday, a
     * holiday, a deadline, an object with no time of day in it. Drawn as a
     * wedge from midnight to midnight it would black out the whole face
     * and say something about somebody's Tuesday that is not true.
     */
    @Test
    fun `an all-day event is not a wedge`() {
        val day = midnight(2026, 6, 21)
        assertNull(
            Agenda.minutesOn(event(day, day + Agenda.DAY_MS, allDay = true), day)
        )
        // And an event on a different day gets nothing either.
        assertNull(Agenda.minutesOn(event(at(2026, 6, 22, 9), at(2026, 6, 22, 10)), day))
    }

    /**
     * A very short event is still drawn wide enough to see and to press.
     *
     * A fifteen-minute stand-up on a twelve-hour dial is three quarters of
     * one degree. The marks on this dial are tappable, and a target under
     * a millimetre is a target nobody hits.
     */
    @Test
    fun `a short appointment is widened to something you can press`() {
        assertEquals(Agenda.LEAST_MINUTES, Agenda.wedgeMinutes(1))
        assertEquals(Agenda.LEAST_MINUTES, Agenda.wedgeMinutes(Agenda.LEAST_MINUTES))
        assertEquals("a real hour was stretched", 60, Agenda.wedgeMinutes(60))
        assertTrue("the least wedge is invisible anyway", Agenda.LEAST_MINUTES >= 10)
    }

    /** They come back in the order they start, whatever order they arrived in. */
    @Test
    fun `the diary is read in the order the day happens`() {
        val day = midnight(2026, 6, 21)
        val events = listOf(
            event(at(2026, 6, 21, 17), at(2026, 6, 21, 18), "the dentist"),
            event(at(2026, 6, 21, 9), at(2026, 6, 21, 10), "the meeting"),
            event(at(2026, 6, 25, 9), at(2026, 6, 25, 10), "next week")
        )
        val today = Agenda.between(events, day, day + Agenda.DAY_MS)
        assertEquals(2, today.size)
        assertEquals("the meeting", today[0].title)
        assertEquals("the dentist", today[1].title)
    }

    /** An event with no name is called something rather than nothing. */
    @Test
    fun `an unnamed appointment still has a label`() {
        val day = midnight(2026, 6, 21)
        assertEquals(
            "Appointment",
            Agenda.titleOf(event(day, day + 3600_000L, title = "   "), "Appointment")
        )
        assertEquals(
            "the dentist",
            Agenda.titleOf(event(day, day + 3600_000L, title = "  the dentist "), "Appointment")
        )
    }

    /**
     * A row with no length and no all-day flag is not an appointment.
     *
     * The provider is full of them — half-synced rows, cancelled instances,
     * odds and ends left by an account that was removed. They are not worth
     * a mark on a dial.
     */
    @Test
    fun `a zero-length row is not drawn`() {
        val day = midnight(2026, 6, 21)
        assertFalse(Agenda.worthDrawing(event(day, day)))
        assertTrue(Agenda.worthDrawing(event(day, day, allDay = true)))
        assertTrue(Agenda.worthDrawing(event(day, day + 60_000L)))
    }
}
