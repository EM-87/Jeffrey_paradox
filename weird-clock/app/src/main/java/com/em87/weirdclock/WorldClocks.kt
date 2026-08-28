package com.em87.weirdclock

import android.content.SharedPreferences
import java.util.TimeZone

/**
 * Which other cities this clock is also showing.
 *
 * One reading of the settings for both faces, because there are two very
 * different things drawn from it and they must not disagree about which
 * cities were asked for. The dial floats them as little clocks you can
 * throw about; the face with no hands stacks them under the time as a
 * ladder of readouts, which is the same information without the toy.
 */
object WorldClocks {

    /** One city: what it is called, and what time it is there. */
    class City(val tzId: String) {

        /** The last part of the zone's name, which is the city in it. */
        val name: String = tzId.substringAfterLast('/').replace('_', ' ')

        val zone: TimeZone by lazy { TimeZone.getTimeZone(tzId) }
    }

    /**
     * How many are shown.
     *
     * Six is what fits on the dial at full zoom, and the picker enforces
     * it. The ladder could hold more and does not, because the number of
     * cities a clock shows should not depend on which clock it is.
     */
    const val MOST = 6

    /** The cities chosen, or nothing if the world clock is switched off. */
    fun chosen(prefs: SharedPreferences): List<City> {
        if (!prefs.getBoolean(Prefs.WORLD_CLOCK, false)) return emptyList()
        val set = prefs.getStringSet(Prefs.WORLD_TZS, null)
        val ids =
            if (set != null) set.toList().sorted()
            // Migration from the old single-city preference.
            else listOf(prefs.getString(Prefs.WORLD_TZ, "UTC") ?: "UTC")
        return ids.take(MOST).map { City(it) }
    }
}
