package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * The face's own curve, lit from wherever the light is.
 *
 * Not a shadow cast by anything: it is the dial catching the light across a
 * surface that is not flat — a belly in the middle and a bevel round the
 * edge — and it is what makes the thing read as an object sitting in the
 * sun rather than a circle printed on a screen.
 *
 * Its own file because two dials draw it now. The widget cannot have the
 * hands' shadows — the system rotates a fixed bitmap for each hand, so a
 * shadow drawn into one would swing round with the hand instead of staying
 * where the sun put it, which is the exact "lamp in the corner of the
 * drawing" this whole engine exists to avoid. The dome has no such problem:
 * it depends on where the sun is and not at all on what time it is, so it
 * is right for as long as the sun stays put, and the widget already wakes
 * itself when the sky changes.
 */
object DialDome {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param strength how much dome there is, 0 to 1 — see
     *   [HandShadow.domeStrength]
     * @param towardDeg the bearing of the light, clockwise from twelve
     * @param mirrored whether the dial runs backwards, in which case so
     *   does every bearing on it
     * @param tint the colour of the light itself, for the lit edge of the
     *   bevel — white for the sun, and for the moon the blue the eye
     *   insists moonlight is
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        strength: Float,
        towardDeg: Float,
        mirrored: Boolean = false,
        tint: Int = 0xFFFFFF
    ) {
        if (strength <= 0.02f) return
        val lit = pointAt(cx, cy, towardDeg, r * 0.42f, mirrored)

        // The belly. Clear where the light lands, deepening away from it,
        // and nowhere near black even at its darkest — the moment this is
        // visible as a grey smudge it has stopped being a curve.
        paint.shader = RadialGradient(
            lit.first, lit.second, r * 1.45f,
            intArrayOf(0, 0, ((44 * strength).toInt()) shl 24),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, paint)

        // The bevel: a ring at the rim, lit on the light's side and shaded
        // opposite, which is the one detail that says the edge has a
        // thickness rather than being where the drawing stops.
        val near = pointAt(cx, cy, towardDeg, r, mirrored)
        val far = pointAt(cx, cy, towardDeg + 180f, r, mirrored)
        paint.shader = LinearGradient(
            near.first, near.second, far.first, far.second,
            (((70 * strength).toInt()) shl 24) or (tint and 0xFFFFFF),
            ((90 * strength).toInt()) shl 24,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.045f
        canvas.drawCircle(cx, cy, r * 0.978f, paint)
        paint.shader = null
    }

    private fun pointAt(
        cx: Float, cy: Float, angleDeg: Float, distance: Float, mirrored: Boolean
    ): Pair<Float, Float> {
        val a = Math.toRadians(angleDeg.toDouble())
        val sx = sin(a).toFloat() * if (mirrored) -1f else 1f
        return (cx + sx * distance) to (cy - cos(a).toFloat() * distance)
    }
}
