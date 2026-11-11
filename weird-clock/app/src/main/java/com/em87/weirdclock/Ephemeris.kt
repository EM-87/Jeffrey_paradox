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
 * mean longitude with the six largest of its wanderings. Held against
 * JPL Horizons at the eclipses of −584, 1715 and 1999, at the new moons
 * of 2026, and day by day through three whole years twenty-six centuries
 * apart, in [EphemerisTest].
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
     * How far ahead of civil time the orbits run, in seconds: ΔT, see
     * [DeltaT]. A minute now and five hours at the eclipse of −584, where
     * without it the Moon stood three degrees from the Sun it was in
     * front of.
     */
    private fun timeOfOrbits(atMs: Long): Double = DeltaT.secondsAt(atMs)

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
     * longitude and the six largest terms of its motion.
     *
     * The first three are the ones with names as old as the telescope —
     * the equation of the centre, which is the ellipse; the evection,
     * which is the Sun stretching that ellipse; the variation, which is
     * the Sun speeding the Moon up at new and full. The next three are a
     * fifth of a degree each and less: the second term of the ellipse,
     * the annual equation — the Moon runs slow while the Earth is near
     * the Sun — and the reduction to the ecliptic, which is the orbit's
     * five degrees of tilt seen flat. The coefficients are the first six
     * rows of Meeus's table 47.A.
     */
    fun moonLongitudeOfDate(atMs: Long): Double {
        val t = centuries(atMs)
        val meanLongitude = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
            t * t * t / 538841.0 - t * t * t * t / 65194000.0
        // Mean elongation from the Sun, the Sun's mean anomaly, the
        // Moon's, and the Moon's distance from its node.
        val d = Math.toRadians(
            297.8501921 + 445267.1114034 * t - 0.0018819 * t * t +
                t * t * t / 545868.0 - t * t * t * t / 113065000.0
        )
        val m = Math.toRadians(
            357.5291092 + 35999.0502909 * t - 0.0001536 * t * t + t * t * t / 24490000.0
        )
        val mPrime = Math.toRadians(
            134.9633964 + 477198.8675055 * t + 0.0087414 * t * t +
                t * t * t / 69699.0 - t * t * t * t / 14712000.0
        )
        val f = Math.toRadians(
            93.2720950 + 483202.0175233 * t - 0.0036539 * t * t -
                t * t * t / 3526000.0 + t * t * t * t / 863310000.0
        )
        val equationOfCentre = 6.288774 * sin(mPrime) + 0.213618 * sin(2 * mPrime)
        val evection = 1.274027 * sin(2 * d - mPrime)
        val variation = 0.658314 * sin(2 * d)
        // Meeus scales this one by the Earth's eccentricity, which is
        // shrinking: six per cent larger at the eclipse of Thales, a
        // hundredth of a degree. Left out, because nothing here can see a
        // hundredth — a year of days against Horizons reads the same to
        // four places with it and without.
        val annualEquation = -0.185116 * sin(m)
        val reduction = -0.114332 * sin(2 * f)
        return Orrery.wrap(
            meanLongitude + equationOfCentre + evection + variation + annualEquation + reduction
        )
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
