package com.em87.weirdclock

import kotlin.math.hypot

/**
 * Where a hand touches a bubble, and how much of the hand's swing that
 * point is worth.
 *
 * A hand is a bar pivoting on the centre of the dial, so it is not moving
 * at one speed: the tip is flying and the boss is barely turning. Hit near
 * the axis and nothing much happens; catch it on the end and it goes like a
 * putt — which is the whole of the golf, and the reason a single "the hand
 * is moving this fast" would be wrong everywhere except at one radius.
 *
 * Pure geometry, kept where it can be measured: this is the arithmetic that
 * decides whether a shot feels like a shot.
 */
internal object HandStrike {

    class Contact(
        /** Unit vector out of the hand, towards the bubble. */
        val nx: Float,
        val ny: Float,
        /** How far the bubble has to move to stop overlapping. */
        val push: Float,
        /**
         * How far along the bar the contact is, 0 at the tail and 1 at the
         * tip — which is also the fraction of the tip's speed that the
         * contact point is travelling at.
         */
        val alongArm: Float
    )

    /**
     * The contact between [bar] and a disc of [radius] centred on
     * ([px], [py]), or null if they are not touching.
     */
    fun contact(bar: ClockView.HandBar, px: Float, py: Float, radius: Float): Contact? {
        val segDx = bar.x2 - bar.x1
        val segDy = bar.y2 - bar.y1
        val len2 = segDx * segDx + segDy * segDy
        if (len2 <= 0f) return null
        val t = (((px - bar.x1) * segDx + (py - bar.y1) * segDy) / len2).coerceIn(0f, 1f)
        val dx = px - (bar.x1 + t * segDx)
        val dy = py - (bar.y1 + t * segDy)
        val d = hypot(dx, dy)
        val minD = radius + bar.halfWidth
        // Dead centre on the bar there is no direction to push in. It is a
        // measure-zero case that a bubble dropped exactly on the axis hits
        // every time, and NaN spreads.
        if (d >= minD || d <= 0.001f) return null
        return Contact(dx / d, dy / d, minD - d, t)
    }
}
