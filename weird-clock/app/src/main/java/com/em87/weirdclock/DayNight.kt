package com.em87.weirdclock

/**
 * Which turn of the dial a time belongs to, and what colour that is.
 *
 * A twelve-hour dial says "seven" and means one of two things twelve hours
 * apart, and nothing on the face tells you which. Every mark this app draws
 * on a dial — an alarm's dot, an event's wedge, a day on the calendar —
 * inherited that silence.
 *
 * The split is noon and midnight, and it has to be. The tempting rules do
 * not work: colour by civil hours and seven in the morning and seven in the
 * evening both come out "day"; colour by real sunrise and sunset and in June
 * they still both do. Anything that answers "is it light out" cannot answer
 * "which of the two sevens", because for most of the year the two sevens are
 * on the same side of it. Only the turn of the dial separates them.
 *
 * So: the first turn is the morning side and wears warm light, the second is
 * the evening side and wears cool. Every surface asks here. That is the whole
 * point of the file — a repeating reminder once marked the calendar and not
 * the dial because two places answered the same question separately, and this
 * question is asked in six.
 */
object DayNight {

    /**
     * Whether the marks follow the sun rather than the clock's two turns,
     * and from where. Configured once from preferences and read by every
     * surface — including the widget, which runs from its own entry point
     * and so calls [configure] for itself.
     */
    private var solar = false
    private var latitude = 0.0
    private var longitude = 0.0

    fun configure(context: android.content.Context) {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        val hasFix = prefs.contains(Prefs.LAST_LATITUDE) &&
            prefs.contains(Prefs.LAST_LONGITUDE)
        // Solar marks without a location would be a guess dressed as an
        // answer, so they fall back to the turn of the dial until there is
        // one fix to work from. After that the arithmetic carries the year.
        solar = prefs.getBoolean(Prefs.SOLAR_MARKS, false) && hasFix
        latitude = prefs.getFloat(Prefs.LAST_LATITUDE, 0f).toDouble()
        longitude = prefs.getFloat(Prefs.LAST_LONGITUDE, 0f).toDouble()
    }

    /** True while a location fix is still wanted for the solar marks. */
    fun solarWantsLocation(context: android.content.Context): Boolean {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        return prefs.getBoolean(Prefs.SOLAR_MARKS, false) &&
            !prefs.contains(Prefs.LAST_LATITUDE)
    }

    /**
     * The one question every mark asks: warm or cool?
     *
     * In the default reading it is the turn of the dial — the only rule that
     * separates the two sevens. Switched to solar it becomes the literal
     * question instead: was the sun up at that minute, on that date, here.
     * Then seven in the morning really can be dark in December and light in
     * June, which is truthful and seasonal and no longer unambiguous — so it
     * is a choice, not the default.
     */
    fun isDark(minutesOfDay: Int, whenMs: Long = System.currentTimeMillis()): Boolean =
        if (solar) {
            !SolarTime.isDaylight(latitude, longitude, whenMs, minutesOfDay)
        } else {
            isPm(minutesOfDay / 60)
        }

    fun isDarkAt(hour: Int, minute: Int, whenMs: Long = System.currentTimeMillis()): Boolean =
        isDark(hour * 60 + minute, whenMs)

    /** From a time of day in milliseconds, as the dials hold it. */
    fun isDarkMs(millisOfDay: Long, whenMs: Long = System.currentTimeMillis()): Boolean {
        val day = 86_400_000L
        val wrapped = ((millisOfDay % day) + day) % day
        return isDark((wrapped / 60_000L).toInt(), whenMs)
    }

    /** True for the second turn of the dial: noon to midnight. */
    fun isPm(hour: Int): Boolean = (hour % 24) >= 12

    /** True for the second turn, from a time of day in milliseconds. */
    fun isPm(millisOfDay: Long): Boolean {
        val day = 86_400_000L
        val wrapped = ((millisOfDay % day) + day) % day
        return wrapped >= day / 2
    }

    /** The mark colour for a time of day, from the dial's own palette. */
    fun markColor(theme: ClockTheme, hour: Int): Int =
        if (isPm(hour)) theme.pmMark else theme.amMark

    fun markColor(theme: ClockTheme, pm: Boolean): Int =
        if (pm) theme.pmMark else theme.amMark
}

/**
 * One alarm dot: where on the dial, and which of its two turns.
 *
 * A bare angle was enough while every mark looked the same. It is not enough
 * to tell seven in the morning from seven at night, which is the one thing
 * the dot is there to say.
 */
data class DialMark(val angle: Float, val pm: Boolean)

/** One event wedge: where it starts, how far it runs, and on which turn. */
data class DialArc(val start: Float, val sweep: Float, val pm: Boolean)
