package com.em87.weirdclock

/**
 * The light on the globe: how brightly the sun lights a place, the air at
 * the edge of the world, and where a cloud's shadow falls.
 *
 * The globe was a photograph laid on a disc with the night put over half
 * of it. Every place the sun was up over was lit alike, noon and the
 * last minute before sunset; the world ended at a line drawn round it;
 * and the cloud was a white veil that touched nothing. Held beside a
 * globe that is drawn as a lit ball it looked like what it was, which is
 * a map. These three things are most of the difference, and none of
 * them needs the earth drawn any other way than it is: each is one more
 * picture laid over the disc, worked out once for where the sun stands.
 *
 * Everything here is asked in the frame the globe is drawn in — the
 * middle of the disc at nought, its edge at one, the far side behind —
 * and of the sun by how high it stands over a place, as the cosine of
 * its angle from straight up: see [Hemisphere.cosZenith].
 */
object GlobeLight {

    // ---------------------------------------------------------- the ground

    /**
     * How much of its light the ground keeps with the sun on the horizon,
     * and how fast it comes up to all of it as the sun climbs.
     *
     * Not the law a matt ball obeys, which is the cosine itself: nought
     * at the terminator and half with the sun thirty degrees up. A ball
     * lit that way is a crescent of bright earth in a black disc, and a
     * clock's face that cannot be read over half of Europe on a winter
     * noon. The real earth is not lit that way either — the air scatters
     * light into the low-sun side, which is why a photograph from far
     * off is evenly bright nearly to the edge of the night. So a floor,
     * and a curve that rises quickly from it: with the sun fifteen
     * degrees up a place has seven tenths of its light, with it thirty
     * up more than four fifths.
     *
     * Looked at, both of them: at a third and a curve of 0.6 the ice
     * round the south pole in November was grey; at these it is white
     * again and the ball is still a ball.
     */
    const val FLOOR = 0.40
    const val CURVE = 0.5

    /**
     * How much of its full brightness a place has with the sun this high:
     * all of it under the sun, [FLOOR] of it on the terminator and on
     * into the twilight, where the night is laid over it anyway.
     */
    fun sunlight(cosZenith: Double): Double {
        if (cosZenith <= 0.0) return FLOOR
        return FLOOR + (1.0 - FLOOR) * Math.pow(cosZenith.coerceAtMost(1.0), CURVE)
    }

    // ------------------------------------------------------------- the air

    /** The colour of the air seen edge-on with the sun on it. */
    const val AIR = 0x78B6FF

    /** How far outside the edge of the world the air can be seen, in radii of the world. */
    const val AIR_REACH = 0.035

    /** How much of what is behind it the air hides at the very edge, where it is thickest. */
    const val AIR_THICKEST = 0.55

    /**
     * How quickly the air thins inward from the edge. Looking straight
     * down there is next to none between the eye and the ground; at the
     * edge the line of sight runs along it. A power of how far the
     * ground is turned away, steep enough that the middle three quarters
     * of the disc are left alone.
     */
    const val AIR_INWARD = 3.0

    /**
     * The sun's height at which the air is not lit at all, and at which
     * it is lit outright: a little below the horizon, since the air is
     * above the ground and sees the sun after the ground has lost it,
     * to ten degrees up.
     */
    const val AIR_DARK = -0.08
    const val AIR_LIT = 0.17

    /**
     * What to lay over a point of the picture for the air there, as a
     * colour with how much of it: [rho] is how far the point is from the
     * middle of the disc, one being the edge of the world, and
     * [cosZenith] how high the sun stands over the nearest ground — the
     * ground at that point inside the edge, the edge itself outside it.
     *
     * Thickest at the edge and the same on both sides of it, so that the
     * edge is not a seam: thinning inward as the ground turns to face
     * the eye, and outward to nothing at [AIR_REACH]. None on the night
     * side. The air there is as thick and nothing lights it.
     */
    fun air(rho: Double, cosZenith: Double): Int {
        val lit = ((cosZenith - AIR_DARK) / (AIR_LIT - AIR_DARK)).coerceIn(0.0, 1.0)
        val depth = if (rho <= 1.0) {
            val facing = Math.sqrt((1.0 - rho * rho).coerceAtLeast(0.0))
            Math.pow(1.0 - facing, AIR_INWARD)
        } else {
            val out = ((rho - 1.0) / AIR_REACH).coerceIn(0.0, 1.0)
            (1.0 - out) * (1.0 - out)
        }
        val share = AIR_THICKEST * depth * lit * lit * (3.0 - 2.0 * lit)
        return (Math.round(share * 255.0).toInt() shl 24) or AIR
    }

    // ---------------------------------------------------------- the shadow

    /**
     * How far from a cloud its shadow is laid, in radii of the world,
     * and how dark it is.
     *
     * Twelve thousandths of the radius is seventy-six kilometres, and
     * no cloud stands that far from its shadow while the sun is well up:
     * ten times life size, as the mountains on a relief globe are. At
     * life size the shadow is half a pixel from its cloud on a phone,
     * and the cloud is a veil again.
     *
     * Looked at, like the rest: the first try was a hundredth and 0.42,
     * and these are a little more of both.
     */
    const val SHADOW_REACH = 0.012
    const val SHADOW_DEPTH = 0.45

    /**
     * Which way a cloud's shadow is thrown on the picture, and how far,
     * given the way to the sun in the picture's own frame — across,
     * down, and towards the eye, as [Hemisphere.project] gives it for
     * the place the sun is overhead.
     *
     * Straight away from the sun, as far as [SHADOW_REACH]. Less when
     * the sun is nearly behind the eye, since a shadow thrown straight
     * away from the eye is thrown under the cloud: by as much of the
     * way to the sun as lies across the picture.
     */
    fun shadowOffset(sunX: Double, sunY: Double): DoubleArray =
        doubleArrayOf(-sunX * SHADOW_REACH, -sunY * SHADOW_REACH)
}
