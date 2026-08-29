package com.em87.weirdclock

/**
 * What is already in the diary, as arithmetic.
 *
 * The clock has had its own reminders since early on, and they are the
 * ones somebody sets *at* a clock: feed the cat, take the pills, the bins
 * go out tonight. What it has never known is the other list — the one with
 * the dentist and the flight and the meeting in it, which lives in Google
 * Calendar or in whatever else the phone syncs and which is the actual
 * answer to "what is today".
 *
 * Read and never written. This app does not create events, move them,
 * delete them or ask for permission to: it is a clock that can see the
 * diary, not a diary. That is one permission with one word in it, and it
 * is the difference between a feature and a liability.
 *
 * Nothing here touches Android. The provider, the permission and the
 * cursor are [AgendaStore]; what is here is the shape of an appointment
 * and the two awkward questions about one — which day it is on, and where
 * it goes on a dial.
 */
object Agenda {

    /**
     * One appointment, as far as a clock cares.
     *
     * Six fields out of the thirty a calendar event has. No location, no
     * guests, no organiser, no description: a dial can draw a wedge and
     * write a name on it, and everything else would be read out of
     * somebody's diary for no reason.
     *
     * [allDay] is not "it lasts twenty-four hours". It is a different kind
     * of object — a birthday, a holiday, a deadline — with no time of day
     * in it at all, and a clock that draws it as a wedge from midnight to
     * midnight has told a lie about a birthday.
     */
    data class Event(
        val id: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val colour: Int = 0
    )

    /**
     * How far ahead the year view looks, in days.
     *
     * A year and a bit. The dial and the month page ask for what they are
     * showing; this is the one query that is genuinely open-ended, and
     * "everything" is not a window a content provider should be given.
     */
    const val YEAR_DAYS = 400

    /** The shortest wedge a dial will draw, in minutes. */
    const val LEAST_MINUTES = 15

    /**
     * Whether an event is happening at any point between two instants.
     *
     * Overlap rather than containment, and it matters at both ends: an
     * event that started yesterday evening and runs until this morning is
     * on today, and one that starts at ten to midnight is on today as
     * well. The half-open comparison is the usual one — an event that ends
     * exactly at midnight is on the day it ended, not the next.
     */
    fun overlaps(event: Event, fromMs: Long, toMs: Long): Boolean =
        event.startMs < toMs && event.endMs > fromMs

    /** Every event touching a window, in the order they start. */
    fun between(events: List<Event>, fromMs: Long, toMs: Long): List<Event> =
        events.filter { overlaps(it, fromMs, toMs) }.sortedBy { it.startMs }

    /**
     * Where an event sits inside one day, in minutes from its midnight.
     *
     * Clipped to the day at both ends, because a dial has twenty-four
     * hours on it and an event running from Friday night to Sunday
     * morning has to be drawn as the part of Saturday it covers, not as a
     * wedge three times round the face.
     *
     * Returns null for an all-day event and for one clipped to nothing —
     * neither has a place on a dial.
     */
    fun minutesOn(event: Event, dayStartMs: Long): IntArray? {
        if (event.allDay) return null
        val dayEnd = dayStartMs + DAY_MS
        val from = maxOf(event.startMs, dayStartMs)
        val to = minOf(event.endMs, dayEnd)
        if (to <= from) return null
        val start = ((from - dayStartMs) / 60_000L).toInt().coerceIn(0, 1439)
        val length = ((to - from) / 60_000L).toInt()
        return intArrayOf(start, length)
    }

    /** A day, in milliseconds. */
    const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * The wedge an event earns on the dial, in minutes, never thinner than
     * a hair.
     *
     * A fifteen-minute stand-up on a twelve-hour dial is three quarters of
     * one degree, which is not a wedge, it is a line that looks like a
     * scratch. Widened to something that can be seen and, more to the
     * point, that can be pressed — the marks on this dial are tappable and
     * a target under a millimetre is a target nobody hits.
     */
    fun wedgeMinutes(lengthMinutes: Int): Int = maxOf(lengthMinutes, LEAST_MINUTES)

    /**
     * What to call an event with no name.
     *
     * Handed in rather than looked up, the same as everywhere else in this
     * app that needs a word: this file is arithmetic.
     */
    fun titleOf(event: Event, untitled: String): String =
        event.title.trim().ifBlank { untitled }

    /**
     * Whether an event is worth putting on a clock at all.
     *
     * Two are not. One that has been declined is somebody else's meeting,
     * and one with no name and no length is a stray row from a sync that
     * went wrong — the provider is full of them. Declined-ness is decided
     * by [AgendaStore] before it gets here, because it is a column.
     */
    fun worthDrawing(event: Event): Boolean =
        event.endMs > event.startMs || event.allDay
}
