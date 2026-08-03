package com.em87.weirdclock

/**
 * Two questions that look like one, kept apart on purpose.
 *
 * **Which turn of the dial is this?** A twelve-hour dial says "seven" and
 * means one of two things twelve hours apart, and nothing on the face tells
 * you which. Every mark this app draws — an alarm's dot, an event's wedge, a
 * day on the calendar — inherited that silence. The split that answers it is
 * noon, and it has to be: colour by real sunrise and sunset and in June the
 * two sevens are both daylight, so the mark stops separating them.
 *
 * **Is the sun up right now?** A different question, with a different use:
 * you are indoors, there is no window, and you want to know whether it is
 * light outside. Noon cannot answer that one; only the sunrise equation can,
 * and only if the app knows where it is standing.
 *
 * So the marks answer the first question by default and can be switched to
 * the second ([markMode]), while the sky token on the dial always answers
 * the second — and says nothing rather than guessing when there is no
 * location to work from. That is why [sunIsUp] returns null: "I do not know"
 * is an answer the caller has to handle, not one to paper over.
 */
object DayNight {

    /** Marks read by the dial's two turns: green morning, blue evening. */
    const val MARKS_CLOCK = "clock"

    /** Marks read by the real sun: yellow daylight, blue night. */
    const val MARKS_SUN = "sun"

    private var markMode = MARKS_CLOCK
    private var located = false
    private var latitude = 0.0
    private var longitude = 0.0

    /**
     * Read once from preferences and used by every surface — including the
     * widget, which runs from its own entry point and so calls this itself.
     */
    fun configure(context: android.content.Context) {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        located = prefs.contains(Prefs.LAST_LATITUDE) &&
            prefs.contains(Prefs.LAST_LONGITUDE)
        latitude = prefs.getFloat(Prefs.LAST_LATITUDE, 0f).toDouble()
        longitude = prefs.getFloat(Prefs.LAST_LONGITUDE, 0f).toDouble()
        // Solar marks without a location would be a guess dressed as an
        // answer, so they fall back to the turn of the dial until there is
        // one fix to work from. After that the arithmetic carries the year.
        val asked = prefs.getString(Prefs.MARK_COLORS, MARKS_CLOCK) ?: MARKS_CLOCK
        markMode = if (asked == MARKS_SUN && located) MARKS_SUN else MARKS_CLOCK
    }

    /** True while a fix would change what the app can show. */
    fun wantsLocation(context: android.content.Context): Boolean {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        return !prefs.contains(Prefs.LAST_LATITUDE)
    }

    /**
     * Whether the sun is above the horizon at that minute of that day, here.
     *
     * Null when the app has never had a fix — it genuinely does not know,
     * and the sky token draws the moon rather than inventing a sunrise.
     */
    fun sunIsUp(minutesOfDay: Int, whenMs: Long = System.currentTimeMillis()): Boolean? =
        if (!located) null
        else SolarTime.isDaylight(latitude, longitude, whenMs, minutesOfDay)

    /**
     * What the sky is doing, finely enough to draw it.
     *
     * The binary answer is right for a coloured dot and wrong for a picture:
     * at 20:44 the dial would show a sun and at 20:45 a moon, which is a
     * flicker, not a sunset. Between the sun touching the horizon and civil
     * twilight there is a real half-hour of half-light, and that is what
     * [Twilight] covers — with how far the sun has sunk through it, so the
     * glyph can go down with it instead of jumping.
     */
    sealed interface Sky {
        /** The sun is up. */
        object Day : Sky

        /** The sun is down; the moon and its phase have the dial. */
        object Night : Sky

        /**
         * The sun is crossing the horizon: [sunk] runs 0 at the moment its
         * centre touches the edge to 1 at the end of civil twilight. Rising
         * runs the same numbers backwards, so one drawing serves both.
         */
        data class Twilight(val sunk: Float) : Sky
    }

    /** Null when there has never been a location fix: see [sunIsUp]. */
    fun sky(minutesOfDay: Int, whenMs: Long = System.currentTimeMillis()): Sky? {
        if (!located) return null
        val horizon = SolarTime.sunriseSunset(latitude, longitude, whenMs)
            // A pole in season has no sunrise to be near, so there is no
            // twilight to draw either: the day is all one thing.
            ?: return if (SolarTime.isDaylight(latitude, longitude, whenMs, minutesOfDay)) {
                Sky.Day
            } else {
                Sky.Night
            }
        val (rise, set) = horizon
        // Civil twilight fails first at high latitudes — there are weeks
        // where the sun dips below the horizon but never six degrees below.
        // Half an hour is the honest stand-in: it is what twilight lasts at
        // middle latitudes, and it keeps the glyph moving rather than
        // snapping.
        val civil = SolarTime.sunriseSunset(latitude, longitude, whenMs, SolarTime.ZENITH_CIVIL)
        val dawn = civil?.first ?: (rise - 30)
        val dusk = civil?.second ?: (set + 30)

        progressIn(minutesOfDay, dawn, rise)?.let { return Sky.Twilight(1f - it) }
        progressIn(minutesOfDay, set, dusk)?.let { return Sky.Twilight(it) }
        return if (SolarTime.isDaylight(latitude, longitude, whenMs, minutesOfDay)) {
            Sky.Day
        } else {
            Sky.Night
        }
    }

    fun skyMs(millisOfDay: Long, whenMs: Long = System.currentTimeMillis()): Sky? =
        sky((wrapDay(millisOfDay) / 60_000L).toInt(), whenMs)

    /**
     * Where [minute] falls between [from] and [to], as 0..1, or null if it
     * is outside. Both ends are minutes past local midnight and the window
     * may cross midnight, which is why this is not a subtraction.
     */
    private fun progressIn(minute: Int, from: Int, to: Int): Float? {
        val span = ((to - from) % 1440 + 1440) % 1440
        if (span == 0) return null
        val into = ((minute - from) % 1440 + 1440) % 1440
        return if (into < span) into.toFloat() / span else null
    }

    /**
     * The one question every mark asks: near side or far, warm or blue?
     *
     * In the default reading it is the turn of the dial — the only rule that
     * separates the two sevens. Switched to the sun it becomes the literal
     * question instead, which is truthful and seasonal and no longer
     * unambiguous. Hence a choice, not a default.
     */
    fun isDark(minutesOfDay: Int, whenMs: Long = System.currentTimeMillis()): Boolean =
        if (markMode == MARKS_SUN) {
            sunIsUp(minutesOfDay, whenMs)?.not() ?: isPm(minutesOfDay / 60)
        } else {
            isPm(minutesOfDay / 60)
        }

    fun isDarkAt(hour: Int, minute: Int, whenMs: Long = System.currentTimeMillis()): Boolean =
        isDark(hour * 60 + minute, whenMs)

    /** True for the second turn of the dial: noon to midnight. */
    fun isPm(hour: Int): Boolean = (hour % 24) >= 12

    /** True for the second turn, from a time of day in milliseconds. */
    fun isPm(millisOfDay: Long): Boolean = wrapDay(millisOfDay) >= 43_200_000L

    /** The mark colour for a time of day, from the dial's own palette. */
    fun markColor(theme: ClockTheme, hour: Int): Int = markColor(theme, isPm(hour))

    /**
     * Blue for the far side either way; the near side is green when the
     * marks count turns of the dial and yellow when they follow the sun.
     */
    fun markColor(theme: ClockTheme, dark: Boolean): Int = when {
        dark -> theme.pmMark
        markMode == MARKS_SUN -> theme.sunMark
        else -> theme.amMark
    }

    private fun wrapDay(millisOfDay: Long): Long {
        val day = 86_400_000L
        return ((millisOfDay % day) + day) % day
    }
}

/**
 * One alarm dot: where on the dial, and which side of the split.
 *
 * A bare angle was enough while every mark looked the same. It is not enough
 * to tell seven in the morning from seven at night, which is the one thing
 * the dot is there to say.
 */
data class DialMark(
    val angle: Float,
    val pm: Boolean,
    /**
     * True for anything that came off the calendar, and so happens once on
     * this date rather than every day. Drawn with a ring round it: the fill
     * still says morning or evening, and the ring says "today only", so one
     * glance separates a dentist's appointment from the alarm that goes off
     * every morning without having to remember which dot was which.
     */
    val fromCalendar: Boolean = false,
    /** What it is called, for the bubble a tap on it opens. */
    val label: String = "",
    /** Whatever the user wrote down about it, read out in the same bubble. */
    val notes: String = ""
) {
    /** What the bubble says: the name, and the note under it if there is one. */
    fun reading(): String = if (notes.isBlank()) label else "$label\n$notes"
}

/** One event wedge: where it starts, how far it runs, and on which side. */
data class DialArc(
    val start: Float,
    val sweep: Float,
    val pm: Boolean,
    val fromCalendar: Boolean = false,
    val label: String = "",
    val notes: String = "",
    /**
     * When the event begins and ends, as minutes past midnight, so the
     * wedge can fade itself out as the minute hand crosses it. Given here
     * rather than as a precomputed fraction because the dial redraws sixty
     * times a second and nothing else does — anything precomputed would be
     * stale between refreshes.
     */
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun reading(): String = if (notes.isBlank()) label else "$label\n$notes"
}
