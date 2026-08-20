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
     * Taller than a real watch, and knowingly. A millimetre of arbor on a
     * forty-millimetre dial is a fiftieth of the radius, and at a sun
     * fifty degrees up that throws a shadow a fiftieth of the radius long
     * — two pixels on a phone, which is not a shadow, it is a hand with a
     * soft edge. These are the heights of a station clock rather than a
     * wristwatch: far enough apart that the three shadows separate, close
     * enough that they still read as one stack.
     *
     * The order is not a choice. The hour hand is lowest and the second
     * hand highest because that is how the arbors have to sit for the
     * hands to pass one another.
     */
    internal fun heightOf(hand: ClockView.Hand): Float = when (hand) {
        ClockView.Hand.HOUR -> 0.045f
        ClockView.Hand.MINUTE -> 0.070f
        ClockView.Hand.SECOND -> 0.095f
    }

    /**
     * The longest a shadow is allowed to get, as a fraction of the dial.
     *
     * Not physics — physics says a shadow at sunset is a mile long. It is
     * the point at which a shadow stops being a shadow and becomes a line
     * across the face pointing at nothing, and the alpha is falling to
     * nothing over the same stretch anyway, so what the cap actually stops
     * is the arithmetic dividing by a tangent on its way to zero.
     */
    const val MAX_LENGTH = 0.9f

    /** Below this the sun is too low to cast anything worth drawing. */
    private const val FADE_FROM_DEG = 12.0

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
