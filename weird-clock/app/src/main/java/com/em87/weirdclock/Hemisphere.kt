package com.em87.weirdclock

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The earth as the clock.
 *
 * There is no dial and there are no hands. The sun is nailed to one side of
 * the screen and the world turns under it, and the red dot where you are
 * standing goes round once a day — which is not a metaphor for a clock, it
 * is what a clock has always been a model of. Reading it is reading your own
 * longitude against the sun, which is what noon means.
 *
 * Everything that makes it work is here and none of it needs a screen. The
 * drawing is [HemisphereView].
 *
 * One consequence worth stating because it looks like a bug: seen from
 * above the north pole the earth turns **anticlockwise**, so the dot runs
 * backwards round the face. That is not an error and it is not a choice —
 * it is why clocks go clockwise in the first place, since the first ones
 * copied a shadow in the northern hemisphere, and it is why sundials in
 * Australia run the other way.
 */
object Hemisphere {

    /** Which way the world is being looked at. */
    enum class View(val key: String) {

        /**
         * Straight down on the north pole, flattened so that every
         * distance from the pole is to scale.
         *
         * The whole world fits: the pole is the middle, the equator is
         * halfway out and the far pole is the rim. That is the point of
         * choosing this projection over the obvious one — a hemisphere
         * that only shows half the earth leaves half the people who might
         * use it off the map.
         */
        NORTH("north"),

        /** The same, from underneath. Longitude runs the other way round. */
        SOUTH("south"),

        /**
         * A ball rather than a map: the same world, tilted, in
         * perspective, with a real edge and a real far side.
         *
         * It shows less and is worth it. The flat projections are honest
         * and read as diagrams; this one reads as the earth, which is
         * what somebody who chose this face came for.
         */
        GLOBE("globe")
    }

    /** How far the globe is tipped towards you, in degrees. */
    const val TILT = 24.0

    /**
     * Where the sun is directly overhead, right now.
     *
     * Latitude is the sun's declination — the same number the sundial's
     * equatorial plate needs — and longitude is wherever it is solar noon,
     * which is the whole of what this clock is reading.
     */
    fun subsolar(atMs: Long): DoubleArray {
        val declination = SolarTime.declinationDeg(atMs)
        // Solar noon is where the hour angle is nought. The hour angle at
        // Greenwich, negated, is the longitude that is under the sun.
        val greenwich = SolarTime.hourAngleDeg(0.0, atMs)
        return doubleArrayOf(declination, wrap(-greenwich))
    }

    /** An angle brought into −180…180, which several things below need. */
    fun wrap(degrees: Double): Double {
        var d = degrees
        while (d > 180.0) d -= 360.0
        while (d <= -180.0) d += 360.0
        return d
    }

    /**
     * Where a place lands on the disc, in units of its radius.
     *
     * Returns x and y with the origin at the middle, y downwards, and a
     * third number that is positive when the point is on the near side —
     * which only ever matters on the globe, where half the world is behind
     * the other half.
     *
     * [sunAt] turns the whole picture so the sun can be put wherever the
     * owner wants it. Nought is the right-hand side, which is where it
     * belongs: the world is read left to right, and a sun on the right is
     * the afternoon side, so the dot climbs towards it all morning.
     */
    fun project(
        view: View,
        latDeg: Double,
        lonDeg: Double,
        subsolarLonDeg: Double,
        sunAtDeg: Double
    ): DoubleArray {
        // How far round from the sun this place is. Everything on this
        // face is measured from the sun rather than from Greenwich,
        // because the sun is the thing that is not moving here.
        val fromSun = wrap(lonDeg - subsolarLonDeg)
        return when (view) {
            View.NORTH -> flat((90.0 - latDeg) / 180.0, fromSun + sunAtDeg)
            // From underneath, east is the other way round the disc.
            View.SOUTH -> flat((90.0 + latDeg) / 180.0, -fromSun + sunAtDeg)
            View.GLOBE -> ball(latDeg, fromSun + sunAtDeg)
        }
    }

    /** The flat projections: a distance from the middle and a bearing. */
    private fun flat(rho: Double, thetaDeg: Double): DoubleArray {
        val t = Math.toRadians(thetaDeg)
        // Anticlockwise on screen, which is which way the earth turns
        // seen from above the north pole.
        return doubleArrayOf(rho * cos(t), -rho * sin(t), 1.0)
    }

    /**
     * And the ball: a point on a unit sphere, turned to put the sun where
     * it is wanted, tipped towards the viewer, and then simply looked at.
     */
    private fun ball(latDeg: Double, thetaDeg: Double): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val t = Math.toRadians(thetaDeg)
        // Pole up the y axis before the tilt, so the tilt is one rotation.
        val x = cos(lat) * cos(t)
        val z = -cos(lat) * sin(t)
        val y = sin(lat)
        val tilt = Math.toRadians(TILT)
        val y2 = y * cos(tilt) - z * sin(tilt)
        val z2 = y * sin(tilt) + z * cos(tilt)
        // Screen y is downwards, and the near side is the one facing us.
        return doubleArrayOf(x, -y2, z2)
    }

    /**
     * The inverse, for painting the earth into the disc.
     *
     * Given a point on the disc, which place on the world is there — or
     * nothing at all, when the point is off the map. Returns latitude then
     * longitude, both in degrees, the longitude measured from the sun.
     */
    fun unproject(view: View, x: Double, y: Double, sunAtDeg: Double): DoubleArray? {
        val rho = hypot(x, y)
        if (rho > 1.0) return null
        return when (view) {
            View.NORTH -> {
                val theta = Math.toDegrees(atan2(-y, x)) - sunAtDeg
                doubleArrayOf(90.0 - rho * 180.0, wrap(theta))
            }
            View.SOUTH -> {
                val theta = Math.toDegrees(atan2(-y, x)) - sunAtDeg
                doubleArrayOf(rho * 180.0 - 90.0, wrap(-theta))
            }
            View.GLOBE -> {
                // Back out of the orthographic: the near half of a unit
                // sphere, untilted, then read off as a latitude and a
                // bearing.
                val z = kotlin.math.sqrt((1.0 - rho * rho).coerceAtLeast(0.0))
                val tilt = Math.toRadians(TILT)
                val yUp = -y
                val y2 = yUp * cos(tilt) + z * sin(tilt)
                val z2 = -yUp * sin(tilt) + z * cos(tilt)
                val lat = Math.toDegrees(asin(y2.coerceIn(-1.0, 1.0)))
                val theta = Math.toDegrees(atan2(-z2, x)) - sunAtDeg
                doubleArrayOf(lat, wrap(theta))
            }
        }
    }

    /**
     * Whether the sun is up at a place, given where it is overhead.
     *
     * The great circle ninety degrees from the subsolar point, which is
     * the terminator — and the one piece of arithmetic on this face that
     * anybody can check by looking out of the window.
     */
    fun isLit(latDeg: Double, lonFromSunDeg: Double, subsolarLatDeg: Double): Boolean =
        cosZenith(latDeg, lonFromSunDeg, subsolarLatDeg) > 0.0

    /**
     * How high the sun is at a place, as the cosine of its angle from
     * straight up: one at the subsolar point, nought on the terminator,
     * negative at night.
     *
     * Returned rather than a yes or no because the interesting part of the
     * picture is the few degrees either side of nought, where the light
     * goes red and then blue and then out.
     */
    fun cosZenith(latDeg: Double, lonFromSunDeg: Double, subsolarLatDeg: Double): Double {
        val lat = Math.toRadians(latDeg)
        val sub = Math.toRadians(subsolarLatDeg)
        val h = Math.toRadians(lonFromSunDeg)
        return sin(lat) * sin(sub) + cos(lat) * cos(sub) * cos(h)
    }

    /**
     * The terminator, as a ring of points on the disc.
     *
     * A great circle at ninety degrees from the subsolar point. Walked
     * round rather than solved, because the shape of it depends on the
     * projection and a hundred and twenty points is smoother than any
     * screen this will be drawn on.
     */
    fun terminator(
        view: View,
        subsolarLatDeg: Double,
        sunAtDeg: Double,
        points: Int = 120
    ): DoubleArray {
        val out = DoubleArray(points * 3)
        val sub = Math.toRadians(subsolarLatDeg)
        // Two directions at right angles to the subsolar point: one along
        // its meridian and one due east of it. Every point ninety degrees
        // away is a turn of those two, which is the terminator.
        for (i in 0 until points) {
            val a = 2.0 * Math.PI * i / points
            val px = -sin(sub) * cos(a)
            val py = cos(sub) * cos(a)
            val pz = -sin(a)
            val lat = Math.toDegrees(asin(py.coerceIn(-1.0, 1.0)))
            val lon = Math.toDegrees(atan2(-pz, px))
            val at = project(view, lat, lon, 0.0, sunAtDeg)
            out[i * 3] = at[0]
            out[i * 3 + 1] = at[1]
            out[i * 3 + 2] = at[2]
        }
        return out
    }

    /**
     * What time it is at a bearing round the rim, in hours.
     *
     * The whole clock, in one line: the sun is at nought and the world
     * turns fifteen degrees an hour, so a place a quarter of the way round
     * from the sun is six hours from noon. Which direction that is depends
     * on which pole you are standing over, and getting it backwards would
     * put every morning in the afternoon.
     */
    fun hourAt(view: View, bearingFromSunDeg: Double): Double {
        val d = wrap(if (view == View.SOUTH) -bearingFromSunDeg else bearingFromSunDeg)
        return 12.0 + d / 15.0
    }

    /**
     * Whether anything round the rim means anything on this view.
     *
     * Covers all four things this face draws in *bearing* — the ring of
     * hours, its numerals, the meridian notches inside the rim, and the
     * line from the dot out to the hour it is pointing at. They are one
     * question because they are one claim: that the angle round the disc
     * is a longitude.
     *
     * On the two flat ones it is exact, and not approximately: those are
     * azimuthal projections about the pole, so the angle round the disc
     * from the sun *is* the longitude from the sun, which *is* the hour.
     * The ring is the same fact as the map.
     *
     * On the ball it is neither. That projection is orthographic and
     * tipped, so the angle round the disc is not a longitude at all — the
     * equator at three hours from noon is drawn at 202° where the ring
     * prints its numeral at 225°, an hour and a half out. And half the
     * hours it labels are longitudes on the far side of the world, which
     * is a scale with numbers on it for places that are not in the
     * picture.
     *
     * So the ball does not get one. The face is bigger without it, which
     * is the right trade for the view somebody chooses because it looks
     * like the earth rather than like a diagram.
     */
    fun hasRimScale(view: View): Boolean = view != View.GLOBE

    /**
     * And the other way: where on the rim a whole hour of the day falls.
     *
     * For the ring of numerals, which is the only part of this face that
     * has to be laid out rather than painted. Only asked on the views
     * [hasRimScale] allows, because it is only true on those.
     */
    fun bearingOfHour(view: View, hour: Int): Double {
        val d = wrap((hour - 12) * 15.0)
        return if (view == View.SOUTH) -d else d
    }

    /**
     * How coarsely a compass reading is taken, in degrees.
     *
     * A phone lying still on a table reports a bearing that wanders a
     * degree or two, and a world that twitches while nobody is touching it
     * is a world that looks broken. Rounding the reading is what stops
     * that.
     *
     * It also happens to be what makes the globe affordable. That view is
     * a sphere, so turning it is not turning a finished picture — the map
     * has to be projected again, a quarter of a million points of it, and
     * it is already only redrawn every degree and a half. Handing it a raw
     * compass would have it doing that several times a second for as long
     * as the phone is in a hand.
     */
    const val STEADY = 5.0

    /** A compass reading, rounded until it holds still. */
    fun steady(degrees: Double): Double = Math.round(degrees / STEADY) * STEADY

    /**
     * Where the sun belongs on the disc when the phone is doing the
     * pointing.
     *
     * The other way of using this face: instead of nailing the sun to one
     * side of the screen and reading the dot against it, hold the phone
     * flat and turn until the picture agrees with the sky. The sun on
     * screen then really is in the direction of the sun, the lit half of
     * the world is the half the light is coming from, and the red dot is
     * standing where you are standing, facing the way you are facing.
     *
     * Screen up is wherever the phone's top points, so the sun's place on
     * screen is how far round the sun is from that — and [sunAtDeg] is
     * measured from the right rather than from the top, anticlockwise,
     * which is the quarter turn in here.
     */
    fun sunAtFrom(phoneBearingDeg: Double, sunAzimuthDeg: Double): Double =
        wrap(90.0 - (sunAzimuthDeg - steady(phoneBearingDeg)))

    /**
     * The angular distance between two places, in degrees.
     *
     * For the dot: a place on the far side of a globe is behind the world
     * and must not be drawn in front of it.
     */
    fun awayFrom(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val a = Math.toRadians(latA)
        val b = Math.toRadians(latB)
        val d = Math.toRadians(lonA - lonB)
        return Math.toDegrees(
            acos((sin(a) * sin(b) + cos(a) * cos(b) * cos(d)).coerceIn(-1.0, 1.0))
        )
    }

    /**
     * The twenty-four meridians an hour apart, as bearings from the sun.
     *
     * Not time zones — those are a political map with a hundred and
     * thirty-eight edges in it, most of which follow a river. These are
     * the meridians the zones were meant to be, which is what a mark on a
     * turning globe can honestly say: fifteen degrees is an hour, and
     * crossing one of these is the moment the hour changes where you are
     * standing if nobody had ever drawn a border.
     */
    fun meridians(): IntArray = IntArray(24) { it }

    /** Whether [degrees] is within a hair of one of them. */
    fun onAMeridian(degrees: Double, slack: Double = 0.75): Boolean {
        val into = abs(wrap(degrees)) % 15.0
        return into < slack || into > 15.0 - slack
    }
}
