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
         * Straight down on the north pole, from a long way off.
         *
         * A hemisphere, and only a hemisphere: the pole is the middle,
         * the equator is the rim, and the southern half of the world is
         * round the back where it belongs. The name of this face said so
         * all along.
         *
         * It used to show the whole world. The rule was that distance
         * from the pole ran to scale — the pole in the middle, the
         * equator halfway out, the far pole smeared all the way round the
         * rim — on the argument that a hemisphere leaves half the people
         * who might use it off the map. That argument was wrong twice.
         * It is wrong because there is a south view, sitting right next
         * to this one, for exactly those people. And it is wrong because
         * the picture it produced was not of the earth: standing over the
         * north pole and reading South Africa off the rim is not
         * something anybody has ever done, and the giveaway was that the
         * squeeze it applied to the map was even all the way out, which
         * no round thing does.
         *
         * So the rule is the ball's rule now: how far out a place lands
         * is how far it is from the axis of the earth, which is the
         * cosine of its latitude. Everything crowds together towards the
         * rim, as it does on a football, and the equator is edge-on. The
         * two flat views and the globe are one projection with the camera
         * in three places.
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

    /**
     * Whether a view's maps are read between their pixels or off the
     * nearest one.
     *
     * Between, on the two views from over a pole: they are painted once
     * and then only turned, so what it costs is paid once. Not on the
     * ball, which is painted again for every degree and a half it is
     * spun through, sixty times a second under a finger: measured, the
     * smoother reading is seven times the arithmetic, and that is the
     * difference between a frame and a frame missed.
     */
    fun smoothMaps(view: View): Boolean = view != View.GLOBE

    /**
     * The colour of a map at a point, from the four pixels round it,
     * each weighed by how near it is — where the ball takes the one
     * the point fell in.
     *
     * A pixel of the maps is a third of a degree, and a globe filling
     * a phone shows it three or four pixels wide: every coast was a
     * staircase. Looked at, on the first picture of the globe taken
     * at a phone's width and magnified. The map goes round the back
     * at the date line and stops at the poles.
     *
     * Two colours at a time, red with blue and green by itself, so
     * that the weighing is four multiplications a pixel and not six.
     */
    fun between(pixels: IntArray, w: Int, h: Int, u: Double, v: Double): Int {
        val x = u * w - 0.5
        val y = (v * h - 0.5).coerceIn(0.0, h - 1.0)
        val left = Math.floor(x).toInt()
        val top = y.toInt()
        val across = ((x - left) * 256.0).toInt()
        val down = ((y - top) * 256.0).toInt()
        val x0 = Math.floorMod(left, w)
        val x1 = if (x0 + 1 == w) 0 else x0 + 1
        val row0 = top * w
        val row1 = minOf(top + 1, h - 1) * w
        val upper = mix(pixels[row0 + x0], pixels[row0 + x1], across)
        val lower = mix(pixels[row1 + x0], pixels[row1 + x1], across)
        return mix(upper, lower, down) or (0xFF shl 24)
    }

    /** [from] and [to], [share] parts in 256 of the way from the one to the other. */
    private fun mix(from: Int, to: Int, share: Int): Int {
        val keep = 256 - share
        // Rounded, not cut off: twice cut off is a map three shades darker.
        val redBlue = (((from and 0xFF00FF) * keep + (to and 0xFF00FF) * share + 0x800080) ushr 8) and 0xFF00FF
        val green = (((from and 0x00FF00) * keep + (to and 0x00FF00) * share + 0x008000) ushr 8) and 0x00FF00
        return redBlue or green
    }

    /**
     * Which pole to look down when nobody has chosen.
     *
     * It used to be the north one for everybody, and it could afford to
     * be: the flat views showed the whole world, so somebody in Santiago
     * got a picture with Santiago in it, upside down and stretched round
     * the rim, but in it. They are hemispheres now, and a southerner
     * handed the north view is handed a picture of the half of the earth
     * they are not standing on, with no dot on it anywhere.
     *
     * Nought degrees is north, which is a coin toss on the equator and
     * the right one: the switch is one tap away and either view shows
     * where you are, on the rim.
     */
    fun defaultView(latDeg: Double): View = if (latDeg < 0.0) View.SOUTH else View.NORTH

    /** How far the globe is tipped towards you, in degrees. */
    const val TILT = 24.0

    // ------------------------------------------------------- how big it is

    /**
     * How far the world can be opened out, as a multiplier.
     *
     * The face is a picture of the earth and it was drawn at a third of
     * the screen so that a ring of numerals could have the rest. Somebody
     * who wants to look at the earth wants the earth, so it opens: pinch
     * and the world grows until it is nearly the whole of the shorter
     * side, and the ring — which is furniture, not the instrument — gets
     * out of the way as it goes.
     */
    const val ZOOM_MIN = 1.0f
    const val ZOOM_MAX = 2.0f

    /** The radii the world can have, as shares of the shorter side. */
    const val WORLD_RINGED = 0.355f
    const val WORLD_BARE = 0.42f
    const val WORLD_FULL = 0.445f

    /**
     * And how big it goes when there is nothing outside it.
     *
     * Half the shorter side is the edge of the screen exactly. With
     * nothing standing outside an opened world the last twentieth is the
     * world's — which was asked for in those words, of the sun, and is
     * the reason the sun has a switch at all.
     */
    const val WORLD_EDGE = 0.5f

    /** How far into the zoom we are, from nought to one. */
    private fun into(zoom: Float): Float =
        ((zoom - ZOOM_MIN) / (ZOOM_MAX - ZOOM_MIN)).coerceIn(0f, 1f)

    /**
     * How big the world is drawn, as a share of the shorter side.
     *
     * It starts where it always was — smaller with a ring round it,
     * because the ring has to go somewhere — and opens to nearly the edge.
     * Not *to* the edge while the moon's mark is out there: it stands
     * outside the world and has to stay in the picture, which is the last
     * twentieth. It was the sun that twentieth was kept for; the sun's
     * mark goes as the world opens now — see [sunFade] — so with no moon
     * on the rim there is nothing left out there to keep it for.
     */
    fun worldRadius(zoom: Float, ringed: Boolean, moonOutside: Boolean = true): Float {
        val t = into(zoom)
        val top = if (moonOutside) WORLD_FULL else WORLD_EDGE
        if (!ringed) return WORLD_BARE + (top - WORLD_BARE) * t
        // With a ring there, the world opens in two goes: it creeps while
        // the ring is still fading, stopping short of the ticks, and then
        // takes the whole of the rest of the room once the ring has gone.
        // One straight line from here to the edge would have the world
        // standing on its own scale before the scale had finished leaving,
        // which is the thing [ringFade] exists to prevent.
        if (t <= RING_GONE) {
            return WORLD_RINGED + (WORLD_UNDER_RING - WORLD_RINGED) * (t / RING_GONE)
        }
        return WORLD_UNDER_RING +
            (top - WORLD_UNDER_RING) * ((t - RING_GONE) / (1f - RING_GONE))
    }

    /** As far as the world may come while its own ring is still there. */
    const val WORLD_UNDER_RING = WORLD_RINGED * 1.04f

    /**
     * How much of the ring of hours is left at this zoom.
     *
     * One while the world is still small enough to leave room for it,
     * then down to nothing well before the world arrives — so the ring
     * gives way rather than being run over. A scale that is half under
     * the thing it is measuring is worse than no scale.
     */
    fun ringFade(zoom: Float): Float {
        val t = into(zoom)
        return ((RING_GONE - t) / (RING_GONE - RING_HOLDS)).coerceIn(0f, 1f)
    }

    /**
     * How much of the sun's mark is left at this zoom.
     *
     * It goes with the ring. The sun stands outside the ring, where the
     * twelve would be, and is the ring's own noon; and opened out the
     * world reaches for the edge of the screen, which is where the sun
     * was standing — it stayed, half off the glass. Somebody who has
     * leaned in to look at the earth is not reading the hour off its
     * rim, and the terminator says where the sun is better than a mark
     * does.
     */
    fun sunFade(zoom: Float): Float = ringFade(zoom)

    /**
     * How far from the middle the sun and the moon stand, as a share of
     * the shorter side, round a world of radius [world].
     *
     * Outside the ring of hours where there is one, and just off the
     * world where there is not — and whichever of those is further out,
     * so that a mark never jumps. It used to: the moon stood outside the
     * ring until the ring had faded and was then, in one frame, just off
     * a world that had not grown yet.
     */
    fun markRadius(world: Float, ringed: Boolean): Float =
        maxOf(if (ringed) WORLD_RINGED * MARK_OUTSIDE_RING else 0f, world * MARK_OFF_WORLD)

    const val MARK_OUTSIDE_RING = 1.22f
    const val MARK_OFF_WORLD = 1.06f

    /**
     * Whole out to here, and gone by here — in fifths of the zoom, so the
     * ring leaves in the first fifth of it and the other four fifths are
     * a clean globe.
     *
     * It has to leave that early because it cannot be *moved*: its ticks
     * sit a twentieth of the screen outside where the world starts, so by
     * the time the world has grown a twentieth it is standing on them.
     * A scale drawn across the thing it is measuring is not a scale, and
     * the choice is between the ring going quickly and the world not
     * opening at all.
     */
    private const val RING_HOLDS = 0.04f
    private const val RING_GONE = 0.28f

    // ------------------------------------------------------ turning it

    /**
     * How much time a turn of the world is worth, in milliseconds per
     * degree.
     *
     * The world goes round once a day, so a degree is four minutes. That
     * is the whole of the conversion and it is why the earth can be
     * *wound*: turning it is not a camera move, it is moving the clock —
     * the terminator goes with it, the dot goes with it, and letting go
     * springs back to now, exactly as winding a hand does.
     */
    const val MS_PER_DEGREE = 240_000L

    /** What a drag of [degrees] round the disc is worth, in milliseconds. */
    fun windBy(view: View, degrees: Double): Long {
        // From over the south pole the world turns the other way round the
        // screen, so a finger going the same way means the other thing.
        val sense = if (view == View.SOUTH) -1.0 else 1.0
        return (-sense * degrees * MS_PER_DEGREE).toLong()
    }

    /**
     * How far round the rim the moon's mark goes in a day, on average:
     * once round in a month of phases.
     */
    const val MOON_DEG_PER_DAY = 360.0 / 29.530589

    /**
     * Where on the rim the moon's mark stands, in degrees anticlockwise
     * from where the sun is nailed.
     */
    fun moonBearing(view: View, atMs: Long): Double = bearingOfTime(view, moonHour(atMs))

    /**
     * How far the clock has to be wound from [atMs] for the moon's mark
     * to stand at [bearingDeg], in milliseconds — the nearer way round.
     *
     * The moon is the slow hand of this face. The world goes round once
     * a day and the moon's mark once a month, so carrying the mark a
     * thumb's width round the rim is a day, and the world goes round
     * once under it: taking hold of the moon is winding the clock by
     * days, where taking hold of the world winds it by hours.
     *
     * Found by walking towards it at the moon's average pace and looking
     * again. Its real pace is up to a quarter either side of that
     * through the month, so each look closes three quarters of what was
     * left or better: a finger's move of a degree or two is home in
     * three, and half the rim in a dozen.
     */
    fun windMoonTo(view: View, atMs: Long, bearingDeg: Double): Long {
        // From over the south pole the rim runs the other way.
        val sense = if (view == View.SOUTH) -1.0 else 1.0
        var ms = atMs
        for (look in 0 until MOON_LOOKS) {
            val short = wrap(bearingDeg - moonBearing(view, ms))
            if (kotlin.math.abs(short) < MOON_NEAR_ENOUGH) break
            ms += (sense * short / MOON_DEG_PER_DAY * DAY_MS).toLong()
        }
        return ms - atMs
    }

    /** As many looks as it will take, and how close is close: a second of the moon's own motion. */
    private const val MOON_LOOKS = 16
    private const val MOON_NEAR_ENOUGH = 0.0002

    /** A day, in milliseconds: one turn of the world. */
    const val DAY_MS = 86_400_000L

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

    /**
     * Where the moon is overhead, as a longitude.
     *
     * The same question as [subsolar] asked of the other light. The moon
     * runs eastward away from the sun as the month goes on — that is what
     * a phase *is* — so it is however far round the month has got, east of
     * wherever the sun is. New moon and the two are in the same place;
     * full moon and they are opposite, which is why a full moon rises as
     * the sun sets.
     */
    fun sublunar(atMs: Long): Double =
        wrap(subsolar(atMs)[1] + moonEastOfSunDeg(atMs))

    /**
     * How far east of the Sun the Moon stands, in the measure the sky
     * turns by: right ascension, from the one Moon and the one Sun of
     * [Ephemeris]. Nought at new moon, a half turn at full.
     */
    fun moonEastOfSunDeg(atMs: Long): Double =
        Orrery.wrap(Ephemeris.moonRightAscensionDeg(atMs) - Ephemeris.sunRightAscensionDeg(atMs))

    /**
     * Where the moon is directly overhead: latitude then longitude, the
     * same shape as [subsolar]. The latitude is the moon's declination
     * from the ephemeris the hand shadows and the orrery already use.
     */
    fun sublunarPoint(atMs: Long): DoubleArray =
        doubleArrayOf(SolarTime.moonDeclinationDeg(atMs), sublunar(atMs))

    /**
     * And which solar hour that meridian is keeping, for the rim.
     *
     * Noon is under the sun by construction on this face, so the moon's
     * hour is twelve plus however far round the month has got.
     */
    fun moonHour(atMs: Long): Double = 12.0 + moonEastOfSunDeg(atMs) / 15.0

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
            View.NORTH -> polar(latDeg, fromSun + sunAtDeg, north = true)
            // From underneath, east is the other way round the disc.
            View.SOUTH -> polar(latDeg, -fromSun + sunAtDeg, north = false)
            View.GLOBE -> ball(latDeg, fromSun + sunAtDeg)
        }
    }

    /**
     * The two views down a pole: a distance from the middle and a bearing.
     *
     * Orthographic, which is the word for looking at a ball from far
     * enough away that the lines of sight are parallel. How far out a
     * place lands is how far it stands from the earth's axis, and that is
     * the cosine of its latitude — one at the equator, nought at either
     * pole.
     *
     * The third number is which side of the world the place is on, the
     * same as it means on the globe: half the earth is behind the other
     * half here too, now that this is a picture of a ball rather than a
     * chart. It is the sine of the latitude, which is the height above
     * the equator — positive for the north view when the place is north,
     * and the other way about from underneath.
     */
    private fun polar(latDeg: Double, thetaDeg: Double, north: Boolean): DoubleArray {
        val lat = Math.toRadians(latDeg)
        val t = Math.toRadians(thetaDeg)
        val rho = cos(lat)
        // Anticlockwise on screen, which is which way the earth turns
        // seen from above the north pole.
        return doubleArrayOf(
            rho * cos(t), -rho * sin(t), if (north) sin(lat) else -sin(lat)
        )
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
                // Back out of the cosine: the colatitude is the arcsine
                // of how far out the point is.
                val theta = Math.toDegrees(atan2(-y, x)) - sunAtDeg
                doubleArrayOf(90.0 - Math.toDegrees(asin(rho.coerceIn(0.0, 1.0))), wrap(theta))
            }
            View.SOUTH -> {
                val theta = Math.toDegrees(atan2(-y, x)) - sunAtDeg
                doubleArrayOf(Math.toDegrees(asin(rho.coerceIn(0.0, 1.0))) - 90.0, wrap(-theta))
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
     * On the two polar ones it is exact, and not approximately: they are
     * looked at straight down the axis, so the angle round the disc from
     * the sun *is* the longitude from the sun, which *is* the hour. That
     * survived the change from a chart to a ball untouched — how far out
     * a place is drawn changed, and which way round from the sun it is
     * did not. The ring is the same fact as the map.
     *
     * On the ball it is neither, and the difference is the tilt rather
     * than the projection: it is looked at from over the tropics rather
     * than from over a pole, so the angle round the disc is not a
     * longitude at all — the
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
    fun bearingOfHour(view: View, hour: Int): Double = bearingOfTime(view, hour.toDouble())

    /**
     * The same for a fraction of an hour, which is what a clock reads.
     *
     * The whole hours are for the numerals; this is for anything that has
     * to point at a *time* — the meridian your official hour is kept by,
     * for one, which is almost never a whole number of hours from the sun
     * and is never on a numeral.
     */
    fun bearingOfTime(view: View, hours: Double): Double {
        val d = wrap((hours - 12.0) * 15.0)
        return if (view == View.SOUTH) -d else d
    }

    /**
     * The meridian a clock in this time zone is keeping, as a longitude.
     *
     * Official time is solar time somewhere else. A zone is a promise that
     * everybody inside it will use the sun of one line of longitude — the
     * offset from Greenwich times fifteen degrees — and the whole of what
     * is strange about clocks is the gap between that line and the one you
     * are standing on. Vigo and Warsaw keep the same hour and the sun is
     * two and a half hours apart between them.
     *
     * Summer time is in the offset and belongs there: it is not a
     * different rule, it is the same rule with the country pretending to
     * be one meridian further east.
     */
    fun zoneMeridian(offsetMs: Int): Double = wrap(offsetMs / 3_600_000.0 * 15.0)

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
