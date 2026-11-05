package com.em87.weirdclock

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The one Sun and the one Moon, as seen from the Earth on a given day.
 *
 * Everything on the face that depends on where the Sun or the Moon is —
 * the terminator on the globe, the shadows of the hands, the hour of
 * sunrise, the phase in the crown, the moonlight on the night side, the
 * elongation the orrery draws — comes through here, from the same two
 * longitudes. It used to come from three places that agreed with each
 * other only around the present day: a sine of the day of the year for
 * the Sun, a count of months from one new moon for the phase, and the
 * orrery's own orbits, in a frame fixed at J2000, for the dial. Wound a
 * thousand years back they were a month apart.
 *
 * The Sun is the Earth's orbit from [Orrery], turned from the frame of
 * J2000 into the equinox of the date by the precession, so that nought
 * degrees is the equinox of that year and not of ours. The Moon is its
 * mean longitude with the three largest of its wanderings — the equation
 * of the centre, the evection and the variation — which is to a third of
 * a degree, an hour of phase. Held against JPL Horizons at the eclipses
 * of −584, 1715 and 1999 and the new moons of 2026 in [EphemerisTest].
 *
 * Longitudes "of date" are measured from the equinox of that date, the
 * way a season is; longitudes "J2000" are what the orrery draws, with
 * every body measured from one fixed line.
 */
object Ephemeris {

    private const val J2000_JD = 2_451_545.0

    /** Julian centuries since J2000, in the time the orbits keep. */
    fun centuries(atMs: Long): Double =
        (AstroCalendar.julianDay(atMs) + timeOfOrbits(atMs) / 86_400.0 - J2000_JD) / 36_525.0

    /**
     * How far ahead of civil time the orbits run, in seconds: nought for
     * now. ΔT belongs here — see [DeltaT] — and is wired in by the step
     * that proves it against the eclipse of −584.
     */
    private fun timeOfOrbits(atMs: Long): Double = 0.0

    // ------------------------------------------------- the frame of date

    /**
     * The precession in longitude accumulated since J2000, in degrees:
     * how far the equinox has slid back along the ecliptic. About 1.4° a
     * century, which is a day of season every seventy-two years.
     */
    fun precessionDeg(t: Double): Double =
        (5029.0966 * t + 1.11113 * t * t - 0.000006 * t * t * t) / 3600.0

    /** The mean obliquity of the ecliptic, in degrees, at [t] centuries from J2000. */
    fun obliquityDeg(t: Double): Double =
        23.0 + (26.0 + 21.448 / 60.0) / 60.0 -
            (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0

    /** Light takes a third of a second to arrive: the Sun is seen this far back along its path. */
    private const val ABERRATION_DEG = 0.00569

    // -------------------------------------------------------------- the Sun

    /** The Sun's apparent longitude, from the equinox of the date. */
    fun sunLongitudeOfDate(atMs: Long): Double =
        Orrery.wrap(Orrery.sunLongitude(atMs) + precessionDeg(centuries(atMs)) - ABERRATION_DEG)

    /** The Sun's mean longitude from the equinox of the date: what a clock would say. */
    fun sunMeanLongitudeOfDate(atMs: Long): Double =
        Orrery.wrap(Orrery.meanLongitude(Orrery.Body.EARTH, atMs) + 180.0 + precessionDeg(centuries(atMs)))

    /** The Sun's declination, in degrees. */
    fun sunDeclinationDeg(atMs: Long): Double =
        declinationOf(sunLongitudeOfDate(atMs), obliquityDeg(centuries(atMs)))

    /** The Sun's right ascension, in degrees. */
    fun sunRightAscensionDeg(atMs: Long): Double =
        rightAscensionOf(sunLongitudeOfDate(atMs), obliquityDeg(centuries(atMs)))

    /**
     * The equation of time, in minutes: how far the sundial is ahead of
     * the clock. Positive when the real Sun crosses the meridian before
     * mean noon — early November, by a quarter of an hour — and negative
     * in February.
     */
    fun equationOfTimeMinutes(atMs: Long): Double =
        4.0 * Orrery.wrapSigned(sunMeanLongitudeOfDate(atMs) - sunRightAscensionDeg(atMs))

    // ------------------------------------------------------------- the Moon

    /**
     * The Moon's longitude from the equinox of the date: the mean
     * longitude and the three largest terms of its motion, each a
     * wandering the Sun's pull imposes on it.
     */
    fun moonLongitudeOfDate(atMs: Long): Double {
        val t = centuries(atMs)
        val meanLongitude = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
            t * t * t / 538841.0 - t * t * t * t / 65194000.0
        // Mean elongation from the Sun, the Sun's mean anomaly, and the
        // Moon's.
        val d = Math.toRadians(
            297.8501921 + 445267.1114034 * t - 0.0018819 * t * t +
                t * t * t / 545868.0 - t * t * t * t / 113065000.0
        )
        val mPrime = Math.toRadians(
            134.9633964 + 477198.8675055 * t + 0.0087414 * t * t +
                t * t * t / 69699.0 - t * t * t * t / 14712000.0
        )
        val equationOfCentre = 6.288774 * sin(mPrime)
        val evection = 1.274027 * sin(2 * d - mPrime)
        val variation = 0.658314 * sin(2 * d)
        return Orrery.wrap(meanLongitude + equationOfCentre + evection + variation)
    }

    /** The Moon's mean longitude from the equinox of the date, with none of its wanderings. */
    fun moonMeanLongitudeOfDate(atMs: Long): Double {
        val t = centuries(atMs)
        return Orrery.wrap(
            218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
                t * t * t / 538841.0 - t * t * t * t / 65194000.0
        )
    }

    /** The Moon's longitude in the orrery's frame, measured from the equinox of J2000. */
    fun moonLongitudeJ2000(atMs: Long): Double =
        Orrery.wrap(moonLongitudeOfDate(atMs) - precessionDeg(centuries(atMs)))

    /** And its mean longitude there, which runs evenly with time. */
    fun moonMeanLongitudeJ2000(atMs: Long): Double =
        Orrery.wrap(moonMeanLongitudeOfDate(atMs) - precessionDeg(centuries(atMs)))

    /**
     * How far through its phases the Moon is: 0 new, 0.25 first quarter,
     * 0.5 full, 0.75 last quarter. The elongation of the Moon from the
     * Sun, and nothing else.
     */
    fun moonPhase(atMs: Long): Double =
        Orrery.wrap(moonLongitudeOfDate(atMs) - sunLongitudeOfDate(atMs)) / 360.0

    /**
     * The Moon's declination, in degrees, taking it to lie on the
     * ecliptic. Its orbit is tilted five degrees to that, which moves the
     * place under it by as much; the tilt waits for a later version.
     */
    fun moonDeclinationDeg(atMs: Long): Double =
        declinationOf(moonLongitudeOfDate(atMs), obliquityDeg(centuries(atMs)))

    /** The Moon's right ascension, in degrees, on the same footing. */
    fun moonRightAscensionDeg(atMs: Long): Double =
        rightAscensionOf(moonLongitudeOfDate(atMs), obliquityDeg(centuries(atMs)))

    // ------------------------------------------------------------- helpers

    private fun declinationOf(longitudeDeg: Double, obliquityDeg: Double): Double =
        Math.toDegrees(
            asin((sin(Math.toRadians(obliquityDeg)) * sin(Math.toRadians(longitudeDeg))).coerceIn(-1.0, 1.0))
        )

    private fun rightAscensionOf(longitudeDeg: Double, obliquityDeg: Double): Double {
        val lambda = Math.toRadians(longitudeDeg)
        val eps = Math.toRadians(obliquityDeg)
        return Orrery.wrap(Math.toDegrees(atan2(cos(eps) * sin(lambda), cos(lambda))))
    }
}
