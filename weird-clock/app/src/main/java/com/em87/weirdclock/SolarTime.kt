package com.em87.weirdclock

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Local apparent solar time: the clock of sundials, where noon is the moment
 * the sun actually crosses your meridian. Needs only the longitude —
 * `solar = UTC + longitude·4min + equation of time` — so one coarse location
 * fix is enough, no network required.
 */
object SolarTime {

    /**
     * When the sun rises and sets, for a place and a day, as minutes past
     * local midnight. Null when the sun does nothing of the sort — above the
     * Arctic circle in June there is no sunrise to report.
     *
     * The standard sunrise equation, and the reason it belongs here: it needs
     * only the latitude, the longitude and the date. One coarse location fix
     * serves for as long as the user stays in the region, and the seasons
     * come out of the arithmetic rather than out of a network call.
     *
     * Sun's centre 0.833° below the horizon at the moment of rising: half a
     * degree of disc, and a third of a degree of atmospheric refraction
     * lifting the image of it over the edge.
     */
    fun sunriseSunset(
        latitudeDeg: Double,
        longitudeDeg: Double,
        nowMs: Long
    ): Pair<Int, Int>? {
        val zone = TimeZone.getDefault()
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        // Declination: how far north or south the sun stands today.
        val b = 2.0 * Math.PI * (dayOfYear - 81) / 365.0
        val declination = Math.toRadians(23.45) * sin(b)
        val eotMinutes = 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)

        val lat = Math.toRadians(latitudeDeg)
        val zenith = Math.toRadians(90.833)
        val cosH = (cos(zenith) - sin(lat) * sin(declination)) /
            (cos(lat) * cos(declination))
        // Out of range means the sun never crosses the horizon today: the
        // polar day, or the polar night.
        if (cosH > 1.0 || cosH < -1.0) return null
        val halfDayMinutes = Math.toDegrees(acos(cosH)) * 4.0

        // Local clock noon for this longitude, corrected for the zone's own
        // offset and for the wobble in the equation of time.
        val zoneOffsetMinutes = zone.getOffset(nowMs) / 60_000.0
        val solarNoon = 720.0 - longitudeDeg * 4.0 - eotMinutes + zoneOffsetMinutes

        val sunrise = ((solarNoon - halfDayMinutes).roundToInt() % 1440 + 1440) % 1440
        val sunset = ((solarNoon + halfDayMinutes).roundToInt() % 1440 + 1440) % 1440
        return sunrise to sunset
    }

    /**
     * Whether the sun is up at [minutesOfDay], for a place and a date.
     *
     * A day that never sees a sunrise is called night throughout, and one
     * that never sees a sunset, day: the honest reading of a pole in season.
     */
    fun isDaylight(
        latitudeDeg: Double,
        longitudeDeg: Double,
        nowMs: Long,
        minutesOfDay: Int
    ): Boolean {
        val riseSet = sunriseSunset(latitudeDeg, longitudeDeg, nowMs)
        if (riseSet == null) {
            // No crossing today: which side of the year decides it.
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val b = 2.0 * Math.PI * (cal.get(Calendar.DAY_OF_YEAR) - 81) / 365.0
            val declination = Math.toRadians(23.45) * sin(b)
            return (latitudeDeg >= 0) == (declination >= 0)
        }
        val (rise, set) = riseSet
        val m = ((minutesOfDay % 1440) + 1440) % 1440
        // A sunset before its sunrise means the lit stretch straddles
        // midnight, which happens near the poles at the turn of the season.
        return if (rise <= set) m in rise until set else m >= rise || m < set
    }

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
