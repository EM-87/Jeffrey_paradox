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
     * The sun's centre 0.833° below the horizon: the moment of rising.
     * Half a degree of disc, and a third of a degree of refraction lifting
     * the image of it over the edge.
     */
    const val ZENITH_HORIZON = 90.833

    /**
     * Civil twilight, six degrees down. The end of usable daylight, and the
     * far edge of the period the dial draws the sun sinking through.
     */
    const val ZENITH_CIVIL = 96.0

    /**
     * When the sun rises and sets, for a place and a day, as minutes past
     * local midnight. Null when the sun does nothing of the sort — above the
     * Arctic circle in June there is no sunrise to report.
     *
     * The standard sunrise equation, and the reason it belongs here: it needs
     * only the latitude, the longitude and the date. One coarse location fix
     * serves for as long as the user stays in the region, and the seasons
     * come out of the arithmetic rather than out of a network call.
     */
    fun sunriseSunset(
        latitudeDeg: Double,
        longitudeDeg: Double,
        nowMs: Long,
        zenithDeg: Double = ZENITH_HORIZON
    ): Pair<Int, Int>? {
        val zone = TimeZone.getDefault()
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        // Declination: how far north or south the sun stands today.
        val b = 2.0 * Math.PI * (dayOfYear - 81) / 365.0
        val declination = Math.toRadians(23.45) * sin(b)
        val eotMinutes = 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)

        val lat = Math.toRadians(latitudeDeg)
        val zenith = Math.toRadians(zenithDeg)
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

    /**
     * Where the sun actually is: how high, and which way round.
     *
     * [altitudeDeg] is degrees above the horizon, negative at night.
     * [azimuthDeg] is the compass bearing of the sun — 0 north, 90 east,
     * 180 south — which is the bearing a shadow runs *away* from.
     */
    data class Position(val altitudeDeg: Double, val azimuthDeg: Double)

    /**
     * The sun's altitude and azimuth for a place and an instant.
     *
     * The same declination and equation-of-time approximations the sunrise
     * calculation above uses, so the two never disagree about what day of
     * the year it is; and the standard hour-angle formulae on top. Good to
     * a fraction of a degree, which is a great deal better than anything
     * that depends on it here needs.
     *
     * The one subtlety is which side of the meridian the sun is on:
     * altitude alone cannot say, since the sun is the same height at ten
     * in the morning and two in the afternoon. The sign of the hour angle
     * decides, and getting it wrong would put every morning shadow where
     * its afternoon one belongs.
     */
    fun position(latitudeDeg: Double, longitudeDeg: Double, atMs: Long): Position {
        val sun = sunAngles(longitudeDeg, atMs)
        return placeIn(latitudeDeg, sun.first, sun.second)
    }

    /**
     * The sun's hour angle and declination, in radians.
     *
     * Split out because the Moon needs the sun's own numbers to be worked
     * out from: where the Moon is, in this approximation, is where the Sun
     * is plus however far round the month has got — see [moonPosition].
     */
    private fun sunAngles(longitudeDeg: Double, atMs: Long): Pair<Double, Double> {
        val zone = TimeZone.getDefault()
        val cal = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val b = 2.0 * Math.PI * (dayOfYear - 81) / 365.0
        val declination = Math.toRadians(23.45) * sin(b)
        val eotMinutes = 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)

        val zoneOffsetMinutes = zone.getOffset(atMs) / 60_000.0
        val civilMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60.0 +
            cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60.0
        // True solar minutes past local solar midnight.
        val solarMinutes = civilMinutes + 4.0 * longitudeDeg + eotMinutes - zoneOffsetMinutes
        // Fifteen degrees an hour, zero at solar noon, negative in the
        // morning and positive in the afternoon.
        return Math.toRadians(solarMinutes / 4.0 - 180.0) to declination
    }

    /**
     * Altitude and azimuth for anything with an hour angle and a
     * declination — the Sun, or the Moon standing in for it.
     */
    private fun placeIn(latitudeDeg: Double, hourAngle: Double, declination: Double): Position {
        val lat = Math.toRadians(latitudeDeg)
        val sinAlt = (
            sin(lat) * sin(declination) +
                cos(lat) * cos(declination) * cos(hourAngle)
            ).coerceIn(-1.0, 1.0)
        val altitude = kotlin.math.asin(sinAlt)
        val below = cos(altitude) * cos(lat)
        // Straight overhead, or standing on a pole: every direction is the
        // same direction and the azimuth means nothing. North, so that it
        // is at least a number and not a NaN travelling into the drawing.
        if (kotlin.math.abs(below) < 1e-9) {
            return Position(Math.toDegrees(altitude), 0.0)
        }
        val cosAz = ((sin(declination) - sinAlt * sin(lat)) / below).coerceIn(-1.0, 1.0)
        var azimuth = Math.toDegrees(acos(cosAz))
        if (sin(hourAngle) > 0) azimuth = 360.0 - azimuth
        return Position(Math.toDegrees(altitude), azimuth)
    }

    /**
     * Where the Moon is, to the accuracy this is worth doing at.
     *
     * The Moon's own orbit is a famously miserable thing to compute — the
     * classical theory runs to hundreds of terms and Newton said it was the
     * only problem that ever made his head ache. None of that is needed
     * here. What a shadow needs is which way the light comes from and how
     * high it is, and both fall out of one fact anybody can check by
     * looking up: the phase *is* the angle between the Sun and the Moon.
     * A full moon rises as the sun sets and stands due south at midnight,
     * because it is exactly opposite the Sun; a new moon crosses the sky
     * with the Sun and cannot be seen at all.
     *
     * So the Moon is put where the Sun is, turned through a whole circle
     * over a lunar month, and its declination read off the ecliptic at the
     * point it has reached. What that leaves out is the five degrees the
     * Moon's orbit is tilted by, which moves it up or down by rather less
     * than the width of a hand at arm's length — invisible in a shadow
     * that is a few pixels long, and cheap at the price of not carrying a
     * lunar theory around.
     */
    fun moonPosition(latitudeDeg: Double, longitudeDeg: Double, atMs: Long): Position {
        val (sunHourAngle, _) = sunAngles(longitudeDeg, atMs)
        val phase = SkyGlyph.phaseAt(atMs)
        // The Moon runs eastward away from the Sun as the month goes on, so
        // it crosses the meridian later and later: its hour angle is the
        // Sun's, less however far round it has got.
        val hourAngle = sunHourAngle - 2.0 * Math.PI * phase
        // And its declination is the ecliptic's at the longitude it has
        // reached, which is the Sun's longitude plus the same angle.
        val zone = TimeZone.getDefault()
        val cal = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        // The Sun's ecliptic longitude, zero at the vernal equinox, which
        // is day eighty.
        val sunLongitude = 2.0 * Math.PI * (cal.get(Calendar.DAY_OF_YEAR) - 80) / 365.25
        val moonLongitude = sunLongitude + 2.0 * Math.PI * phase
        val declination = kotlin.math.asin(
            (sin(Math.toRadians(23.45)) * sin(moonLongitude)).coerceIn(-1.0, 1.0)
        )
        return placeIn(latitudeDeg, hourAngle, declination)
    }

    /**
     * How much of the Moon's disc is lit, from 0 at new to 1 at full.
     *
     * Which is how much light there is to cast a shadow with. A new moon is
     * not a dim moon, it is no moon: it is up all day and invisible all
     * night, and a dial that drew a shadow by it would be drawing one by
     * something that is not there.
     */
    fun moonIllumination(atMs: Long): Double =
        (1.0 - cos(2.0 * Math.PI * SkyGlyph.phaseAt(atMs))) / 2.0

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
