package com.em87.weirdclock

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Local apparent solar time: the clock of sundials, where noon is the moment
 * the sun actually crosses your meridian. Needs only the longitude —
 * `solar = UTC + longitude·4min + equation of time` — so one coarse location
 * fix is enough, no network required.
 */
object SolarTime {

    /** Display offset (ms) that turns civil time into local solar time. */
    fun offsetMs(longitudeDeg: Double, nowMs: Long): Long {
        val civilOffset = TimeZone.getDefault().getOffset(nowMs).toLong()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMs
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        // Equation of time (minutes), Spencer-style approximation: the ±16
        // minute wobble from Earth's tilted, elliptical orbit.
        val b = 2.0 * Math.PI * (dayOfYear - 81) / 364.0
        val eotMinutes = 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val solarVsUtcMs = ((longitudeDeg * 4.0 + eotMinutes) * 60_000.0).toLong()
        return solarVsUtcMs - civilOffset
    }
}
