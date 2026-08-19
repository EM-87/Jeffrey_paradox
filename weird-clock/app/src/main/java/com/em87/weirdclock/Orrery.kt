package com.em87.weirdclock

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The solar system as a mechanism: eight planets, one moon, and a single
 * shaft turning all of them.
 *
 * The whole point is that there is one clock in here, not nine. Every body
 * is a function of the same instant, so grabbing one and forcing its orbit
 * is not "moving a planet" — it is winding time, and every other body has
 * no choice but to follow at the speed its own year demands. Carry Neptune
 * a hand's width and a century goes by under it; carry Mercury the same
 * distance and Neptune has not visibly moved. That correlation is the
 * feature, and it falls out of the arithmetic rather than being written
 * anywhere.
 *
 * Every orbit is a real ellipse, solved properly. It started out as circles
 * and the arithmetic put Mars at opposition a fortnight early — Mars's
 * orbit is lopsided enough that a circle is wrong by ten degrees, which is
 * three weeks of Mars. So Kepler's equation is solved for each planet at
 * each instant, which is fifteen lines and makes the dates true to about a
 * day. What is still left out: the orbits are drawn on rings rather than
 * ellipses, they are all treated as lying in one plane, and the longitudes
 * are measured from where the equinox stood in 2000 rather than from where
 * it stands now — a line that slides about 1.4° a century. None of the
 * three changes an angle *between* two planets, which is what this is for.
 *
 * The elements are Standish's, the ones NASA publishes for approximate
 * positions of the major planets over 1800–2050.
 */
object Orrery {

    /** 2000-01-01 12:00 UTC, the epoch every longitude below is measured from. */
    const val J2000_MS = 946_728_000_000L

    private const val DAY_MS = 86_400_000.0

    /**
     * The bodies, innermost first. The Moon is last because it is not a
     * ring of its own: it belongs to Earth and is drawn around it.
     */
    enum class Body { MERCURY, VENUS, EARTH, MARS, JUPITER, SATURN, URANUS, NEPTUNE, MOON }

    /**
     * One body's turn: where it stood at [J2000_MS], how fast its *mean*
     * longitude moves, how lopsided the orbit is, and which way the long
     * axis points.
     *
     * The rate is the stored number and the period is derived from it, not
     * the other way round. Storing both would let them drift apart, and the
     * one thing this class must never do is disagree with itself about how
     * long a year is.
     */
    data class Turn(
        val longitudeAtEpoch: Double,
        val degreesPerDay: Double,
        val eccentricity: Double,
        val perihelion: Double
    ) {
        val periodDays: Double get() = 360.0 / degreesPerDay
    }

    /** Days in a Julian century, the unit Standish's rates are given in. */
    private const val CENTURY = 36525.0

    private val turns: Map<Body, Turn> = mapOf(
        Body.MERCURY to Turn(252.25032350, 149472.67411175 / CENTURY, 0.20563593, 77.45779628),
        Body.VENUS to Turn(181.97909950, 58517.81538729 / CENTURY, 0.00677672, 131.60246718),
        Body.EARTH to Turn(100.46457166, 35999.37244981 / CENTURY, 0.01671123, 102.93768193),
        Body.MARS to Turn(-4.55343205, 19140.30268499 / CENTURY, 0.09339410, -23.94362959),
        Body.JUPITER to Turn(34.39644051, 3034.74612775 / CENTURY, 0.04838624, 14.72847983),
        Body.SATURN to Turn(49.95424423, 1222.49362201 / CENTURY, 0.05386179, 92.59887831),
        Body.URANUS to Turn(313.23810451, 428.48202785 / CENTURY, 0.04725744, 170.95427630),
        Body.NEPTUNE to Turn(-55.12002969, 218.45945325 / CENTURY, 0.00859048, 44.96476227),
        // The Moon's longitude is measured from the Earth, not the Sun, and
        // this is its mean rate: one turn of the sky in 27.32 days. The
        // month you can see out of a window is longer — 29.53 — because by
        // the time the Moon is back where it started the Earth has moved on
        // and the Sun is no longer where it was.
        //
        // Left round on purpose. The Moon's orbit is an ellipse too, but its
        // real wanderings are mostly the Sun pulling on it — a degree and a
        // quarter here, two thirds of a degree there — and none of that is
        // an ellipse. Solving Kepler for the Moon would buy almost nothing
        // and look like precision.
        Body.MOON to Turn(218.3164477, 13.17639648, 0.0, 0.0)
    )

    /** The planets, in order out from the Sun. */
    val planets: List<Body> = listOf(
        Body.MERCURY, Body.VENUS, Body.EARTH, Body.MARS,
        Body.JUPITER, Body.SATURN, Body.URANUS, Body.NEPTUNE
    )

    private fun turn(body: Body): Turn = turns.getValue(body)

    /** How long one full orbit takes, in days. */
    fun periodDays(body: Body): Double = turn(body).periodDays

    /**
     * Where a body would stand if its orbit were a circle it went round at
     * a steady rate. Not where it is — see [longitude] — but the number
     * that runs evenly with time, which makes it the one to do arithmetic
     * in.
     */
    fun meanLongitude(body: Body, atMs: Long): Double {
        val t = turn(body)
        val days = (atMs - J2000_MS) / DAY_MS
        return wrap(t.longitudeAtEpoch + t.degreesPerDay * days)
    }

    /**
     * Where a body actually stands at a given instant, in degrees of
     * ecliptic longitude: 0 to 360, anticlockwise seen from the north,
     * which is the way the real thing turns.
     *
     * Heliocentric for the planets — the angle seen from the Sun. The Moon
     * is the exception: its longitude is the angle seen from the Earth,
     * because that is the only place it makes sense from.
     *
     * A planet on an ellipse hurries when it is near the Sun and dawdles
     * when it is far, by as much as ten degrees for Mars, so this is not
     * the mean longitude and the difference is the difference between
     * naming the right day and naming one three weeks off.
     */
    fun longitude(body: Body, atMs: Long): Double =
        trueFromMean(body, meanLongitude(body, atMs))

    // ------------------------------------------------ round the ellipse

    /**
     * Kepler's equation, solved: from where a body would be on an even
     * circle to where it is on its own ellipse.
     *
     * Newton's method from a good first guess. Six rounds is more than
     * enough for every orbit here — Mercury's, the most lopsided of them,
     * settles in three — and the loop stops as soon as it stops moving.
     */
    fun trueFromMean(body: Body, meanLongitudeDeg: Double): Double {
        val t = turn(body)
        if (t.eccentricity == 0.0) return wrap(meanLongitudeDeg)
        val e = t.eccentricity
        val m = Math.toRadians(wrapSigned(meanLongitudeDeg - t.perihelion))
        var ecc = m + e * kotlin.math.sin(m)
        repeat(6) {
            val d = (ecc - e * kotlin.math.sin(ecc) - m) / (1 - e * kotlin.math.cos(ecc))
            ecc -= d
            if (kotlin.math.abs(d) < 1e-12) return@repeat
        }
        val trueAnomaly = 2.0 * kotlin.math.atan2(
            kotlin.math.sqrt(1 + e) * kotlin.math.sin(ecc / 2),
            kotlin.math.sqrt(1 - e) * kotlin.math.cos(ecc / 2)
        )
        return wrap(Math.toDegrees(trueAnomaly) + t.perihelion)
    }

    /**
     * And back the other way, which is what a finger on a planet needs:
     * the touch says where the planet should *appear*, and only the mean
     * longitude can be turned into a date.
     *
     * Kepler's equation run backwards is not a solve, it is a formula —
     * true angle to eccentric angle to mean angle, each step exact.
     */
    fun meanFromTrue(body: Body, trueLongitudeDeg: Double): Double {
        val t = turn(body)
        if (t.eccentricity == 0.0) return wrap(trueLongitudeDeg)
        val e = t.eccentricity
        val v = Math.toRadians(wrapSigned(trueLongitudeDeg - t.perihelion))
        val ecc = 2.0 * kotlin.math.atan2(
            kotlin.math.sqrt(1 - e) * kotlin.math.sin(v / 2),
            kotlin.math.sqrt(1 + e) * kotlin.math.cos(v / 2)
        )
        val m = ecc - e * kotlin.math.sin(ecc)
        return wrap(Math.toDegrees(m) + t.perihelion)
    }

    /** Where the Sun stands as seen from the Earth: opposite the Earth. */
    fun sunLongitude(atMs: Long): Double = wrap(longitude(Body.EARTH, atMs) + 180.0)

    // ------------------------------------------------------------ the Moon

    /**
     * How far through its phases the Moon is: 0 new, 0.25 first quarter,
     * 0.5 full, 0.75 last quarter.
     *
     * This is the elongation — how far the Moon has pulled away from the
     * Sun in the sky — and nothing else. A phase is not a property of the
     * Moon; it is the angle between three things, which is why forcing the
     * Earth's orbit changes it and forcing Mars's does not.
     */
    fun moonPhase(atMs: Long): Double =
        wrap(longitude(Body.MOON, atMs) - sunLongitude(atMs)) / 360.0

    // ------------------------------------------------- forcing an orbit

    /**
     * How far the whole system moves when [body] is carried from one
     * longitude to another.
     *
     * The shorter way round, always. A finger that crosses the top of the
     * screen goes from 359° to 1° and means *forward two degrees*, not
     * backward three hundred and fifty-eight, and a drag that read it the
     * other way would fling the date a century each time it passed noon.
     * Because it is the short way, a continuous drag adds up correctly: the
     * caller keeps its own running total and the many small steps compose
     * into as many turns as the finger actually made.
     *
     * Both angles are where the planet *appears*, because that is what a
     * finger can point at. They are turned into mean longitudes before
     * anything is measured, since only the mean one runs evenly with time:
     * measuring the true angle directly would make Mercury lag its own
     * finger by a fifth of its orbit at aphelion and overshoot at
     * perihelion.
     */
    fun stepMs(body: Body, fromLongitude: Double, toLongitude: Double): Long {
        val moved = shortWay(
            meanFromTrue(body, fromLongitude),
            meanFromTrue(body, toLongitude)
        )
        return (moved / 360.0 * periodDays(body) * DAY_MS).roundToLong()
    }

    /**
     * Whether the Moon still turns while [grabbed] is being carried.
     *
     * The line falls between Mars and Jupiter, and it falls there because
     * of what a person can watch. Carrying a planet a whole turn moves time
     * by that planet's year, and the Moon goes round in a month, so a turn
     * of Mars is twenty-five moons — quick, but each one is a shape that
     * arrives. A turn of Jupiter is a hundred and fifty-nine, which is not
     * a moon at all but a grey ring, and it hides the one thing the Moon is
     * on this dial to say.
     *
     * So the inner four drive it and the outer four do not: past Mars the
     * Moon lets go of the mechanism and holds where it was until the hand
     * comes off.
     */
    fun moonFollows(grabbed: Body?): Boolean = when (grabbed) {
        null, Body.MOON, Body.MERCURY, Body.VENUS, Body.EARTH, Body.MARS -> true
        else -> false
    }

    // -------------------------------------------------------- alignments

    /**
     * The longest run of bodies standing inside one arc of [withinDeg] at
     * this instant, in order out from the Sun; empty unless at least three
     * of them are in it.
     *
     * Sorted by longitude and read round the circle, because "in line" is a
     * fact about the gaps between them and not about any particular one:
     * the run that matters is the one with the widest empty sky behind it.
     *
     * Two planets are always within some arc of each other and saying so
     * would be saying nothing, so three is the least this will report.
     */
    fun aligned(atMs: Long, withinDeg: Double): List<Body> {
        // Each longitude worked out once and kept. It used to be read from
        // inside both loops, which is sixty-four solves of Kepler's equation
        // for one day — fine for one day, and a minute of dead phone for the
        // twenty thousand [nextAlignment] looks at.
        val angles = planets.associateWith { longitude(it, atMs) }
        val byAngle = planets.sortedBy { angles.getValue(it) }
        val n = byAngle.size
        var best = emptyList<Body>()
        for (start in 0 until n) {
            val from = angles.getValue(byAngle[start])
            val run = mutableListOf(byAngle[start])
            for (k in 1 until n) {
                val body = byAngle[(start + k) % n]
                if (wrap(angles.getValue(body) - from) > withinDeg) break
                run.add(body)
            }
            if (run.size > best.size) best = run
        }
        if (best.size < 3) return emptyList()
        return planets.filter { it in best }
    }

    /**
     * The first instant from [fromMs] at which [aligned] would name at
     * least [atLeast] bodies, or null if it never happens inside
     * [limitDays].
     *
     * A day at a time. The bodies that decide whether a run holds together
     * are the slow ones, and none of them moves far enough in a day to open
     * or close a gap of several degrees unnoticed; stepping finer would
     * cost eight longitudes a step to find the same dates.
     */
    fun nextAlignment(
        fromMs: Long,
        withinDeg: Double,
        atLeast: Int,
        limitDays: Int
    ): Long? {
        for (day in 0..limitDays) {
            val at = fromMs + (day * DAY_MS).toLong()
            if (aligned(at, withinDeg).size >= atLeast) return at
        }
        return null
    }

    // ------------------------------------------------------ where to draw

    /**
     * How far out a body's ring sits, as a fraction of the dial's radius.
     *
     * Evenly spaced, which is a lie and a deliberate one. Drawn to scale
     * with Neptune on the rim, the four inner planets would share the
     * outermost two percent of the dial and the Earth would be a pixel from
     * the Sun — the only orrery anybody has ever built that is honest about
     * distance is one you walk between. Spacing them evenly keeps every
     * ring a place a finger can land on, which is what this one is for.
     */
    fun ringFraction(body: Body): Float {
        val i = planets.indexOf(body)
        if (i < 0) return moonRingFraction()
        return INNERMOST + (OUTERMOST - INNERMOST) * i / (planets.size - 1)
    }

    /** The Moon's ring, drawn about the Earth and sized against it. */
    private fun moonRingFraction(): Float = MOON_RING

    private const val INNERMOST = 0.20f
    /**
     * The outermost ring, as a fraction of the dial.
     *
     * A little inside the rim, which is where the day marks live. It used
     * to be 0.94 here *and* another 0.94 in the drawing, so the two agreed
     * about the picture and disagreed about the arithmetic: [MAX_ZOOM]
     * thought it was putting the Earth on the rim and was putting it six
     * per cent short of it.
     */
    private const val OUTERMOST = 0.88f

    /**
     * How far the rings can be pushed out: until the Earth's orbit is the
     * one on the rim.
     *
     * That is the end of the journey and not an arbitrary limit. With the
     * Earth on the rim, one turn of the dial is one year, and the face can
     * be marked out in days the way an ordinary one is marked out in hours.
     * Anything beyond it would be a calendar with no year on it.
     */
    val MAX_ZOOM: Float = OUTERMOST / ringFraction(Body.EARTH)

    /**
     * How far out the days of the year have come, 0 to 1.
     *
     * They arrive over the last third of the journey rather than at the
     * end of it: three hundred and sixty-five marks appearing in one frame
     * is a flicker, and appearing gradually they read as something the
     * zoom is uncovering.
     */
    fun dayMarkFade(zoom: Float): Float {
        val from = 1f + (MAX_ZOOM - 1f) * 0.62f
        if (zoom <= from) return 0f
        return ((zoom - from) / (MAX_ZOOM - from)).coerceIn(0f, 1f)
    }

    /** Days in a civil year: 366 in a leap year, 365 otherwise. */
    fun daysInYear(year: Int): Int =
        CivilDays.epochDay(year + 1, 1, 1) - CivilDays.epochDay(year, 1, 1)

    /** The Moon's orbit, as a fraction of the dial — a small ring on Earth's. */
    const val MOON_RING = 0.055f

    // ----------------------------------------------------------- helpers

    /** An angle brought back into 0 until 360. */
    fun wrap(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** An angle brought back into -180 until 180. */
    fun wrapSigned(deg: Double): Double {
        val d = wrap(deg)
        return if (d > 180.0) d - 360.0 else d
    }

    /**
     * The way round from one angle to another that is shorter, as a signed
     * number of degrees in -180 until 180.
     */
    fun shortWay(fromDeg: Double, toDeg: Double): Double = wrapSigned(toDeg - fromDeg)

    /** How far apart two angles are, never more than half a turn. */
    fun separation(aDeg: Double, bDeg: Double): Double = abs(shortWay(aDeg, bDeg))
}
