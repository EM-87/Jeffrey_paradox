package com.em87.weirdclock

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The visitors: four comets on the long thin orbits that make them
 * visitors rather than residents.
 *
 * The eight planets on this dial go round on circles at even spacings,
 * which is a diagram rather than a map — Neptune is thirty times further
 * out than Mercury and drawing it so would leave seven rings in a smudge
 * at the middle. Comets cannot be flattened the same way, because the
 * shape *is* the fact: what makes Halley a comet and not a planet is that
 * it comes inside the Earth's orbit and goes out past Neptune's, and a
 * comet drawn on a circle is a planet with a different name.
 *
 * So the eccentricity here is real, the period is real, and the perihelion
 * dates are real. What is a diagram is only the scale: each orbit is sized
 * so its far end reaches a chosen ring, rather than by how many astronomical
 * units it actually spans. An ellipse of the right shape in the wrong size
 * still tells you the thing; the right size in the wrong shape does not.
 *
 * The four were not picked for being famous. Each one is the parent of a
 * meteor shower this clock already knows the date of — the Perseids are
 * Swift–Tuttle's dust, the Leonids are Tempel–Tuttle's — so a comet on the
 * dial and a shower in the calendar are two views of one object.
 */
object Comets {

    enum class Comet { ENCKE, TEMPEL_TUTTLE, HALLEY, SWIFT_TUTTLE }

    /**
     * One comet's orbit.
     *
     * [perihelionMs] is an observed passage, not an epoch: the arithmetic
     * counts whole orbits from it in both directions, which is why a
     * recent one matters more than a precise one. [perihelionLongitude] is
     * where the near end of the ellipse points, so the four do not all lie
     * along the same axis.
     *
     * [aphelionRing] is the diagram part — how far out the far end of the
     * orbit is drawn, as a fraction of the dial.
     */
    data class Orbit(
        val periodYears: Double,
        val eccentricity: Double,
        val perihelionMs: Long,
        val perihelionLongitude: Double,
        val aphelionRing: Float,
        val wanderYears: Double
    )

    private const val YEAR_MS = 365.25 * 86_400_000.0

    /**
     * The four, in order of how long they take.
     *
     * The dates are observed perihelion passages and the longitudes are
     * the real orientation of each orbit in the plane of the ecliptic.
     *
     * The periods are not the book figures. A comet does not have one
     * period: every time round it passes the giant planets and comes away
     * on a slightly different orbit, and Halley's returns have been
     * anywhere from seventy-four years apart to seventy-nine. So each
     * period here is set to the gap between the passage above and the next
     * one that has actually been predicted — 1986 to 2061 for Halley,
     * rather than the 75.32 in the tables, which lands the return seven
     * weeks early. That makes the two nearest visits right and the ones
     * centuries out approximate, which is the correct way round for a
     * clock: 1910 comes out some months off, and no arrangement of a
     * single number would have got both.
     *
     * What is not modelled at all is the third dimension. Halley goes
     * round backwards and steeply tilted, and on a flat dial it goes round
     * the same way as everything else. A dial that showed that would have
     * to be a sphere.
     */
    private val orbits = mapOf(
        // 2020-06-26 to 2023-10-22. The wander is not really wander here:
        // the gap between those two passages is 3.3211 years and Encke's
        // book period is 3.30, so every counted orbit is a fifth of a
        // month out — which over a period this short is what runs it out
        // of certainty inside a century and a half.
        Comet.ENCKE to Orbit(3.3211, 0.8483, iso(2020, 6, 26), 161.1, 0.50f, 0.02),
        // 1998-02-28 to 2031-05-20. Crosses Jupiter's orbit, and its
        // recorded returns run from about 32.9 years to 33.5.
        Comet.TEMPEL_TUTTLE to Orbit(33.2200, 0.9055, iso(1998, 2, 28), 47.8, 0.74f, 0.2),
        // 1986-02-09 to 2061-07-28. Halley's thirty recorded returns run
        // from 240 BC to 1986, and they average 74.2 years apart against
        // the 75.46 pinned here — so every orbit counted backwards is
        // about fifteen months out, and by the Norman conquest the sum is
        // fourteen years. Which is the number, checked against the real
        // return of 1066 rather than guessed at.
        Comet.HALLEY to Orbit(75.4629, 0.9671, iso(1986, 2, 9), 169.8, 0.86f, 1.2),
        // 1992-12-11 to 2126-07-16. The longest way out and the biggest
        // kick when it comes back past the giants: its observed returns of
        // 1737, 1862 and 1992 are 125 and 130 years apart, and the one
        // before them is in 188.
        Comet.SWIFT_TUTTLE to Orbit(133.5900, 0.9632, iso(1992, 12, 11), 292.4, 0.94f, 2.0)
    )

    /** The comets, innermost first — the order they are drawn in. */
    val all: List<Comet> = listOf(
        Comet.ENCKE, Comet.TEMPEL_TUTTLE, Comet.HALLEY, Comet.SWIFT_TUTTLE
    )

    fun orbitOf(comet: Comet): Orbit = orbits.getValue(comet)

    /** Midnight UTC on a civil date, without java.time, which minSdk 24 lacks. */
    private fun iso(year: Int, month: Int, day: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day)
        return cal.timeInMillis
    }

    // ------------------------------------------------------ round the orbit

    /**
     * Where a comet is on its own ellipse, as a distance from the Sun and a
     * direction.
     *
     * [radius] is a fraction of the dial and [longitude] is degrees
     * anticlockwise from the right, the same as everything else on this
     * dial. Both come out of Kepler rather than out of a lookup: a comet
     * on a 0.97 ellipse spends nine tenths of its life crawling round the
     * far end and crosses the whole inner system in months, and a position
     * that ran evenly with time would have Halley strolling past the Earth
     * for a decade.
     */
    data class Where(val radius: Float, val longitude: Double)

    fun positionAt(comet: Comet, atMs: Long): Where {
        val o = orbitOf(comet)
        val a = o.aphelionRing / (1.0 + o.eccentricity)
        val ecc = eccentricAnomaly(comet, atMs)
        // The distance from the focus, which is where the Sun is — not
        // from the middle of the ellipse. Drawing it from the middle puts
        // the Sun nowhere and the comet through the wrong end.
        val r = a * (1.0 - o.eccentricity * cos(ecc))
        val v = trueAnomaly(o.eccentricity, ecc)
        return Where(r.toFloat(), Orrery.wrap(Math.toDegrees(v) + o.perihelionLongitude))
    }

    /**
     * The angle Kepler's equation is solved for, in radians.
     *
     * Newton's method, as the planets use, but from a different first
     * guess and with more rounds allowed. At an eccentricity of 0.97 the
     * usual guess is nowhere near the answer near perihelion and the
     * iteration wanders; starting from the cube-root approximation that
     * near-parabolic orbits want gets it inside a dozen rounds.
     */
    private fun eccentricAnomaly(comet: Comet, atMs: Long): Double {
        val o = orbitOf(comet)
        val period = o.periodYears * YEAR_MS
        val turns = (atMs - o.perihelionMs) / period
        val m = 2.0 * Math.PI * (turns - floor(turns))
        val e = o.eccentricity
        var ecc = if (e < 0.8) m else Math.PI
        repeat(60) {
            val d = (ecc - e * sin(ecc) - m) / (1 - e * cos(ecc))
            ecc -= d
            if (abs(d) < 1e-12) return ecc
        }
        return ecc
    }

    private fun trueAnomaly(e: Double, ecc: Double): Double = 2.0 * atan2(
        sqrt(1 + e) * sin(ecc / 2),
        sqrt(1 - e) * cos(ecc / 2)
    )

    // ------------------------------------------------------------ the dates

    /**
     * The perihelion passage nearest to [atMs] — the date the comet is
     * closest to the Sun, which is the only date a comet has.
     *
     * Nearest rather than next, because the sky can be wound backwards and
     * "the next time Halley comes round" is a strange thing to be told
     * while standing in 1910. What somebody winding wants to know is which
     * visit they are looking at.
     */
    fun nearestPerihelion(comet: Comet, atMs: Long): Long {
        val o = orbitOf(comet)
        val period = o.periodYears * YEAR_MS
        val turns = Math.round((atMs - o.perihelionMs) / period)
        return o.perihelionMs + (turns * period).toLong()
    }

    /** The passage after this one, for a comet on its way in. */
    fun nextPerihelion(comet: Comet, atMs: Long): Long {
        val nearest = nearestPerihelion(comet, atMs)
        if (nearest > atMs) return nearest
        return nearest + (orbitOf(comet).periodYears * YEAR_MS).toLong()
    }

    /**
     * How near a comet's visit the dial is standing, from 0 to 1.
     *
     * 1 at perihelion, falling to 0 a year either side of it. This is what
     * decides whether the comet gets a tail and whether the caption
     * mentions it — a comet three decades out is a dot on a wire, and
     * saying its name every time the sky is wound past would make the
     * caption a list rather than a remark.
     */
    fun nearness(comet: Comet, atMs: Long): Float {
        val gap = abs(atMs - nearestPerihelion(comet, atMs))
        val window = YEAR_MS
        if (gap >= window) return 0f
        return (1.0 - gap / window).toFloat()
    }

    /**
     * Which comet, if any, is worth naming under the dial at [atMs].
     *
     * A much tighter window than the tail's, and deliberately so. Encke
     * comes round every three years and three months, so a year-wide
     * window would have its name under the dial for most of the time the
     * sky is ever wound to — and a caption that is nearly always saying
     * the same thing has stopped being a remark about today. Six weeks
     * either side of the closest approach is about as long as a comet is
     * actually an event.
     */
    fun visiting(atMs: Long, withinDays: Int = 42): Comet? =
        all.asSequence()
            // And not a comet whose visit this cannot put a date on. Six
            // weeks either side is a claim about a fortnight in a given
            // year; made about a passage the arithmetic has drifted by
            // decades, it is a made-up date said with a straight face.
            .filter { trust(it, atMs) > 0.5f }
            .map { it to abs(atMs - nearestPerihelion(it, atMs)) }
            .filter { it.second <= withinDays * 86_400_000L }
            .minByOrNull { it.second }?.first

    // ------------------------------------------------- how far this is worth

    /**
     * How many returns away from the observed passage the dial is standing.
     *
     * Both directions: winding back three thousand years is the same
     * arithmetic as winding forward three thousand, and the error grows
     * the same way in each.
     */
    fun orbitsFrom(comet: Comet, atMs: Long): Double {
        val o = orbitOf(comet)
        return abs(atMs - o.perihelionMs) / (o.periodYears * YEAR_MS)
    }

    /**
     * How wrong the date of a visit has become, in years.
     *
     * A comet does not have a period. Every time round it passes the giant
     * planets and comes away on a slightly different orbit, and Halley's
     * recorded returns are anywhere from seventy-four years apart to
     * seventy-nine. Counting whole orbits from a known passage is exactly
     * right for the visit before and the visit after, and the error is
     * cumulative — one return's worth of wander for every return counted.
     *
     * A random walk would grow as the square root of the count and this
     * grows linearly, which is the pessimistic reading. That is the right
     * way to be wrong here: the failure being guarded against is a clock
     * confidently drawing Halley in the wrong quarter of its orbit in 2000
     * BC, and being early to admit it costs nothing but a fading dot.
     */
    fun driftYears(comet: Comet, atMs: Long): Double =
        orbitsFrom(comet, atMs) * orbitOf(comet).wanderYears

    /**
     * How much of a comet is left, from 1 to 0.
     *
     * It fades as the drift grows and is gone once the drift is a quarter
     * of the orbit — the point at which the comet could be anywhere on the
     * near half of its ellipse and the dot on the glass is a decoration
     * rather than a position.
     *
     * The four fade at four different rates, and they should: Encke goes
     * round every three years so it runs out of returns quickly, while
     * Swift-Tuttle's are a century and a third apart and it survives most
     * of recorded history. What comes out is that Halley is on the dial
     * for the whole span anybody has actually watched it — the Chinese
     * records of 240 BC are inside the fade — and gone before the
     * hieroglyphs give out, which is about right for a thing this model
     * cannot know.
     */
    fun trust(comet: Comet, atMs: Long): Float =
        (1.0 - driftYears(comet, atMs) / lostBy(comet)).coerceIn(0.0, 1.0).toFloat()

    /**
     * The drift at which a comet is no longer a position: a quarter of its
     * own orbit.
     *
     * Its own function because two places need it and they must agree —
     * how much is left, and how far out there is nothing left at all.
     */
    private fun lostBy(comet: Comet): Double = orbitOf(comet).periodYears / 4.0

    /**
     * How far either way a comet is still drawn at all, in years.
     *
     * Asked of [driftYears] rather than written out a second time. It was
     * written out a second time, and a sabotage that changed how the drift
     * accumulates left this function cheerfully reporting the old
     * horizons — two expressions for one fact are two facts waiting to
     * disagree.
     */
    fun rangeYears(comet: Comet): Double {
        val o = orbitOf(comet)
        // One orbit on from the pinned passage, which is one orbit's worth
        // of wander whatever shape the accumulation has.
        val perOrbit = driftYears(comet, o.perihelionMs + (o.periodYears * YEAR_MS).toLong())
        if (perOrbit <= 0.0) return Double.MAX_VALUE
        return lostBy(comet) / perOrbit * o.periodYears
    }

    /** The name to put under the dial. */
    fun nameKeyOf(comet: Comet): Int = when (comet) {
        Comet.ENCKE -> R.string.comet_encke
        Comet.TEMPEL_TUTTLE -> R.string.comet_tempel_tuttle
        Comet.HALLEY -> R.string.comet_halley
        Comet.SWIFT_TUTTLE -> R.string.comet_swift_tuttle
    }
}
