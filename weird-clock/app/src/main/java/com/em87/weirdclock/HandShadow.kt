package com.em87.weirdclock

import kotlin.math.tan

/**
 * Shadows under the hands, cast by the real sun.
 *
 * The conceit is that the clock is lying flat on the ground with twelve
 * pointing north, and the sun is wherever the sun is. Everything follows
 * from that and nothing is chosen for looks: the hands sit at three
 * different heights above the face, so they throw three shadows of three
 * different lengths, and all three run along the same bearing — away from
 * the sun.
 *
 * The reason it is worth doing properly rather than picking a light source
 * in the top-left corner, which is what a drawing program would do, is
 * that the honest version says something. On the equator at noon the sun
 * is straight up and there is no shadow at all; in Reykjavík in December
 * it never gets more than three degrees off the horizon and the hands
 * drag shadows twenty times their own height across the dial. The same
 * clock looks different in different places, and it looks different
 * because of where you are standing.
 *
 * The height of each hand is the part that has to be invented, since a
 * drawing has no thickness. The order is not invented: the hour hand is
 * always the lowest and the second hand the highest, because that is how
 * the arbors have to stack for the hands to pass one another.
 */
object HandShadow {

    /**
     * How high each hand rides above the face, as a fraction of the dial's
     * radius.
     *
     * A little more than a real watch and much less than the first
     * attempt. Those were the heights of a station clock, and at a low sun
     * the second hand's shadow ended up half a radius from the hand
     * casting it — which is not a shadow, it is a second hand drawn twice.
     * These are close to the millimetre of arbor a wristwatch actually
     * has: far enough apart that the three still separate, near enough
     * that each one stays under its own hand.
     *
     * The order is not a choice. The hour hand is lowest and the second
     * hand highest because that is how the arbors have to sit for the
     * hands to pass one another.
     */
    internal fun heightOf(hand: ClockView.Hand): Float = when (hand) {
        ClockView.Hand.HOUR -> 0.010f
        ClockView.Hand.MINUTE -> 0.015f
        ClockView.Hand.SECOND -> 0.020f
    }

    /**
     * How many passes a soft shadow is drawn in, and how far out from the
     * hand's own outline each one reaches.
     *
     * A blur, done the only way that is certain to survive a hardware
     * canvas: the same shape several times, each wider and fainter than
     * the last, widest first. A mask filter would be one call and is the
     * obvious thing to reach for, but it is among the operations hardware
     * acceleration quietly declines, and a shadow that is soft on one
     * phone and a hard black bar on another is worse than no shadow.
     *
     * These are fractions of the penumbra, not multiples of the hand's
     * width, and that is the whole correction. Widths that were multiples
     * of the hand made the softness depend on what was casting it: the
     * hour hand is a fat wedge, so three times its width is a wide haze
     * and looked blurred, while the second hand is a hair, so three times
     * *its* width is still a hair — thirteen passes landing on the same
     * two pixels, adding up to one hard black stick beside a red one.
     * A penumbra does not know how wide the thing casting it is.
     */
    val SPREAD: FloatArray = floatArrayOf(
        1.000f, 0.917f, 0.833f, 0.750f, 0.667f, 0.583f,
        0.500f, 0.417f, 0.333f, 0.250f, 0.167f, 0.083f, 0f
    )

    /**
     * And how much of the shadow's darkness each of those passes lays
     * down.
     *
     * Thirteen passes and not five. Five is enough to be soft in the head
     * and not on the glass: each one is a solid shape with an edge, and
     * five edges a third of the hand's width apart are five edges you can
     * count — the shadow came out banded, like a contour map of itself.
     *
     * The numbers are not chosen by eye. Stacked, the passes make a step
     * function of distance from the hand: at a given distance you are
     * under every pass wider than it, so the darkness there is the sum of
     * their weights. Each weight is therefore the *difference* between a
     * gaussian at its own spread and at the one before it, with the
     * profile cut off at three sigma — so the outermost step is a hundredth
     * of a shadow and the edge of the haze arrives at nothing at all.
     */
    val PASS_ALPHA: FloatArray = floatArrayOf(
        0.011f, 0.012f, 0.021f, 0.036f, 0.056f, 0.081f,
        0.108f, 0.133f, 0.149f, 0.148f, 0.128f, 0.087f, 0.031f
    )

    /**
     * How soft the edge is, as a fraction of the dial's radius.
     *
     * It grows with the distance the shadow has travelled, which is the
     * one part of a penumbra that is real: the further a shadow falls from
     * the thing casting it, the wider the band where the sun is only half
     * hidden. A hand lying almost on the face has an almost sharp shadow;
     * a hand throwing one across the dial has a soft one.
     */
    fun penumbra(height: Float, altitudeDeg: Double): Float =
        0.008f + reach(height, altitudeDeg) * 0.10f

    /**
     * The longest a shadow is allowed to get, as a fraction of the dial.
     *
     * Not physics — physics says a shadow at sunset is a mile long, and
     * for a while this drew something close to it: at sunrise and sunset
     * the shadows slid most of a radius off their hands and over the rim,
     * which does not read as a low sun, it reads as hands floating a foot
     * above the dial.
     *
     * So it saturates early, and at a twentieth of the radius. It has been
     * a fifth and then a tenth, and both were still reported from the phone
     * as hands floating too high — the tell is the last hour before sunset,
     * when a shadow sitting at its stop is the only thing on the dial that
     * is not moving with the light. A twentieth is about the offset a real
     * hand a millimetre off the dial throws, which is the thing being
     * drawn.
     */
    const val MAX_LENGTH = 0.05f

    /**
     * Below this the sun is too low to cast anything worth drawing.
     *
     * Eighteen degrees rather than twelve, so that the fade is well under
     * way by the time the length is against its stop — otherwise the last
     * stretch before sunset is a shadow at full strength sitting at its
     * maximum reach, which is the one arrangement that looks pinned rather
     * than cast.
     */
    private const val FADE_FROM_DEG = 18.0

    /**
     * How much light a full moon casts, against the sun.
     *
     * Nothing like the truth, which is about a four-hundred-thousandth —
     * an honest number here would be a shadow nobody could see on any
     * screen. What is honest is the *shape* of it: the moonlight shadow
     * follows the moon rather than the sun, it is far fainter than a
     * daylight one, and it goes out entirely as the moon does.
     */
    const val MOONLIGHT = 0.34f

    /**
     * And what colour it is.
     *
     * Moonlight is reflected sunlight and is very nearly the same white,
     * but a shadow seen by it does not look neutral — the eye's colour
     * vision gives out at that light level and the blue-sensitive rods
     * take over, so a moonlit night reads as blue. That is the Purkinje
     * shift, and it is why every painter since the Renaissance has painted
     * moonlight blue while every photometer says it is not.
     */
    const val MOON_TINT = 0xFF0B1638.toInt()

    /**
     * And the colour of the moonlight itself, for the lit side of things.
     *
     * The same shift, seen from the other end: where a moonlit shadow
     * reads as blue, a moonlit edge reads as a cold blue-white rather than
     * the warm one the sun leaves.
     */
    const val MOON_SHEEN = 0xC8D6FF

    /**
     * Below this much of a lit disc there is no moonlight worth drawing.
     *
     * A new moon is not a dim moon: it is up all day, invisible all night,
     * and a shadow cast by it would be a shadow cast by nothing.
     */
    const val MOON_FLOOR = 0.12

    /**
     * A latitude to stand at when the phone has never had a fix.
     *
     * Somewhere in the middle, because the two honest alternatives are
     * both worse: the equator makes the feature look broken — the noon sun
     * is overhead and there is no shadow — and refusing to draw anything
     * makes a switch that does nothing when you turn it on. The setting
     * says which it is using.
     */
    const val NO_FIX_LATITUDE = 40.0

    /**
     * And a longitude, from the time zone: fifteen degrees to the hour.
     *
     * Coarse, but it is only deciding what o'clock the sun thinks it is,
     * and the worst a whole zone can be wrong by is half an hour of solar
     * time — which moves a shadow by a few degrees of bearing.
     */
    fun longitudeFromZone(offsetMs: Int): Double = offsetMs / 3_600_000.0 * 15.0

    /**
     * How far a shadow reaches, as a fraction of the dial's radius.
     *
     * Straight trigonometry: a thing [height] above the ground with the
     * sun [altitudeDeg] up throws a shadow height over the tangent of the
     * altitude. Zero when the sun is overhead — which is the answer, not a
     * failure — and zero when the sun is down.
     */
    fun reach(height: Float, altitudeDeg: Double): Float {
        if (altitudeDeg <= 0.0) return 0f
        if (altitudeDeg >= 89.99) return 0f
        val long = height / tan(Math.toRadians(altitudeDeg)).toFloat()
        return long.coerceAtMost(MAX_LENGTH)
    }

    /**
     * Which way a shadow runs, as a bearing clockwise from twelve.
     *
     * Twelve is north, so a compass bearing and a dial angle are the same
     * number — which is the whole reason the conceit is "lying flat with
     * twelve to the north" rather than any other orientation. A shadow
     * runs away from the sun, so it is the sun's bearing turned round.
     */
    fun bearing(azimuthDeg: Double): Float =
        (((azimuthDeg + 180.0) % 360.0 + 360.0) % 360.0).toFloat()

    /**
     * How much of a dome the dial reads as, from 0 to 1.
     *
     * Not a shadow of anything — it is the face itself catching the light
     * across its own curve, which is what makes it look like an object
     * with a belly rather than a circle printed on a screen. Strongest
     * with the sun low and across, gone with the sun overhead, since a
     * dome lit from straight above has no shaded side.
     */
    fun domeStrength(altitudeDeg: Double): Float {
        // No horizon test of its own. [strength] is already zero below the
        // horizon and this multiplies by it, so a second check here is a
        // line that cannot run — which a sabotage found by deleting it and
        // watching nothing fail.
        val across = kotlin.math.cos(Math.toRadians(altitudeDeg)).toFloat()
        return (across * strength(altitudeDeg)).coerceIn(0f, 1f)
    }

    /**
     * Which surface the clock is standing on, which decides what the sun
     * does to it.
     *
     * [GROUND] is the conceit this engine was built on: the clock lying
     * flat with twelve pointing north, so a compass bearing and a dial
     * angle are the same number and a shadow runs away from the sun.
     *
     * [WALL] is the other half of the world's clocks, and it is a
     * different problem rather than the same one turned round. The face is
     * vertical, so twelve points *up* and not north; the light comes at
     * the face instead of across it; the shadow of a hand standing off the
     * face is the sun's direction projected onto the face, which can point
     * anywhere including straight up; and when the sun goes round behind
     * the wall there is no shadow at all, however high it still is. A
     * ground clock never has that last problem and a wall clock has it for
     * half of every day.
     */
    enum class Surface { GROUND, WALL }

    /**
     * Which way the wall faces, as a bearing.
     *
     * Towards the equator, which is where anybody hangs a clock they want
     * the light on: south in the northern hemisphere and north in the
     * southern. It is the same assumption every vertical sundial makes,
     * and it is the only free parameter in the wall case — the ground case
     * has none, because a horizontal face has no direction to face.
     */
    fun wallFacing(latitudeDeg: Double): Double = if (latitudeDeg >= 0.0) 180.0 else 0.0

    /**
     * Where a shadow falls on a vertical face, and how far.
     *
     * Returns the bearing clockwise from twelve and the reach as a
     * multiple of the hand's height, or null when the sun is behind the
     * wall and there is nothing to cast.
     *
     * The arithmetic is one projection. Put the sun's direction in
     * ordinary compass-and-altitude terms, split it into the part along
     * the face and the part into it, and the shadow is the first divided
     * by the second, reversed. The part *into* the wall is what makes this
     * different from the ground: as the sun swings round towards the plane
     * of the face that divisor goes to nothing and the shadow runs away to
     * infinity, which is exactly what happens on a real wall at the moment
     * the light goes grazing along it.
     */
    fun onWall(
        altitudeDeg: Double,
        azimuthDeg: Double,
        latitudeDeg: Double
    ): Pair<Float, Float>? {
        val alt = Math.toRadians(altitudeDeg)
        val az = Math.toRadians(azimuthDeg)
        val facing = Math.toRadians(wallFacing(latitudeDeg))
        // East, north and up.
        val sx = kotlin.math.cos(alt) * kotlin.math.sin(az)
        val sy = kotlin.math.cos(alt) * kotlin.math.cos(az)
        val sz = kotlin.math.sin(alt)
        // The face's own axes: the way it looks, and the way "right" lies
        // when you are looking at it.
        val nx = kotlin.math.sin(facing)
        val ny = kotlin.math.cos(facing)
        val intoWall = sx * nx + sy * ny
        // Behind the wall, or grazing it: no shadow, and no divisor either.
        if (intoWall <= 0.02) return null
        // "Right" belongs to whoever is looking at the clock, not to the
        // wall: they stand in the room with their back to the light the
        // wall faces, so for a south-facing wall they are looking north and
        // their right hand is east. That is the normal turned a quarter
        // anticlockwise, not clockwise — taken the other way round the
        // whole model still works, still goes dark at the right hour and
        // still stretches with the sun, and puts every morning shadow where
        // its afternoon one belongs.
        val right = -sx * kotlin.math.cos(facing) + sy * kotlin.math.sin(facing)
        // The shadow runs away from the light across the face.
        val alongFace = -right / intoWall
        val upFace = -sz / intoWall
        val reach = kotlin.math.hypot(alongFace, upFace)
        // Twelve is up on a wall clock, so a bearing clockwise from twelve
        // is measured from the face's own up.
        val bearing = Math.toDegrees(kotlin.math.atan2(alongFace, upFace))
        return (((bearing % 360.0 + 360.0) % 360.0).toFloat()) to reach.toFloat()
    }

    /**
     * What is lighting the dial, if anything.
     *
     * By day the sun; by night the moon, which is a much stranger light and
     * is drawn as one. [brightness] is how much of a shadow it casts
     * against full sunlight, and [moon] is what colour it is.
     */
    data class Light(
        val altitudeDeg: Double,
        val azimuthDeg: Double,
        val moon: Boolean,
        val brightness: Float
    )

    /**
     * Which light is on the dial at a place and an instant, and how much of
     * it there is.
     *
     * Day and night are asked of [DayNight] rather than of the sun's
     * altitude, so the shadows change over at the same moment everything
     * else on the face does — the complication showing the sun setting, the
     * mark colours, the theme. Twilight is the handover, and both lights
     * are drawn through it at whatever share they have.
     *
     * At night there may be no light at all, and that is the answer rather
     * than a failure: a new moon is up all day and invisible all night, and
     * a shadow cast by it would be a shadow cast by nothing.
     *
     * Here rather than in the dial that draws it, because two dials draw it
     * — the app's and the widget's — and the last time a piece of this
     * arithmetic was written twice the two copies disagreed for three
     * versions before anybody noticed.
     */
    fun lightAt(latitudeDeg: Double, longitudeDeg: Double, atMs: Long): Light? {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = atMs
        val ofDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 3_600_000L +
            cal.get(java.util.Calendar.MINUTE) * 60_000L
        val sun = SolarTime.position(latitudeDeg, longitudeDeg, atMs)
        val night = when (val sky = DayNight.skyMs(ofDay, atMs)) {
            DayNight.Sky.Day -> 0f
            is DayNight.Sky.Twilight -> sky.sunk
            DayNight.Sky.Night -> 1f
            // Nowhere to stand: no fix has ever been taken, so the dial has
            // no opinion about when the day ends here. The sun's own
            // altitude decides, which is what this did before there was a
            // moon to hand over to.
            else -> if (sun.altitudeDeg > 0.0) 0f else 1f
        }
        if (night < 1f && sun.altitudeDeg > 0.0) {
            return Light(sun.altitudeDeg, sun.azimuthDeg, false, 1f - night)
        }
        if (night <= 0f) return null
        val moon = SolarTime.moonPosition(latitudeDeg, longitudeDeg, atMs)
        if (moon.altitudeDeg <= 0.0) return null
        val lit = SolarTime.moonIllumination(atMs)
        if (lit < MOON_FLOOR) return null
        return Light(
            moon.altitudeDeg, moon.azimuthDeg, true,
            (night * MOONLIGHT * lit).toFloat()
        )
    }

    /**
     * How dark the shadow is, from 0 to 1.
     *
     * Full while the sun is properly up, fading out over the last twelve
     * degrees before it sets — which is both what happens and what stops
     * the shadow snapping off at the horizon like a light being switched.
     */
    fun strength(altitudeDeg: Double): Float {
        if (altitudeDeg <= 0.0) return 0f
        if (altitudeDeg >= FADE_FROM_DEG) return 1f
        return (altitudeDeg / FADE_FROM_DEG).toFloat()
    }
}
