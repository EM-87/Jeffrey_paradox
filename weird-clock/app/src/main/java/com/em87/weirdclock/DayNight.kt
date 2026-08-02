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

    /** True once one fix has been stored; the year follows from arithmetic. */
    fun hasLocation(): Boolean = located

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

    /** The same, from a time of day in milliseconds, as the dials hold it. */
    fun sunIsUpMs(millisOfDay: Long, whenMs: Long = System.currentTimeMillis()): Boolean? =
        sunIsUp((wrapDay(millisOfDay) / 60_000L).toInt(), whenMs)

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

    /** From a time of day in milliseconds, as the dials hold it. */
    fun isDarkMs(millisOfDay: Long, whenMs: Long = System.currentTimeMillis()): Boolean =
        isDark((wrapDay(millisOfDay) / 60_000L).toInt(), whenMs)

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
data class DialMark(val angle: Float, val pm: Boolean)

/** One event wedge: where it starts, how far it runs, and on which side. */
data class DialArc(val start: Float, val sweep: Float, val pm: Boolean)
