package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sky, drawn small: the sun while the sun is up, the moon and its phase
 * once it is down, and the sun crossing the horizon in between.
 *
 * Lives on its own because two dials draw it — the app's and the widget's —
 * and the last time a piece of dial arithmetic was written twice, the two
 * copies disagreed about repeating reminders for three versions before
 * anybody noticed. One drawing, one set of rules, both callers.
 *
 * It takes the paints rather than the colours: both callers already keep a
 * lit, a dark and a rim paint for the moon, and the whole point of the sun
 * sharing them is that the pair reads as one complication changing state
 * rather than two ornaments.
 */
object SkyGlyph {

    /** Days from one new moon to the next. */
    private const val SYNODIC = 29.530588853

    /**
     * @param cx horizontal centre
     * @param cy vertical centre, and the horizon the sun sets through
     * @param mr the moon's radius; everything else is scaled from it
     * @param timeOfDayMs the time of day being depicted, which for a wound
     *   dial is the time the *hands show*, not the time it is
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        mr: Float,
        lit: Paint,
        dark: Paint,
        rim: Paint,
        timeOfDayMs: Long,
        whenMs: Long = TimeKeeper.nowMs(),
        withPhase: Boolean = true
    ) {
        when (val sky = DayNight.skyMs(timeOfDayMs, whenMs)) {
            DayNight.Sky.Day -> drawSun(canvas, cx, cy, mr, lit, rim)
            is DayNight.Sky.Twilight -> drawSettingSun(canvas, cx, cy, mr, sky.sunk, lit, rim)
            // Night, or nowhere to stand: the moon and its phase, which is
            // arithmetic that works anywhere on Earth.
            else -> drawMoon(canvas, cx, cy, mr, lit, dark, rim, whenMs, withPhase)
        }
    }

    /**
     * How far through its phases the Moon is, 0 new and 0.5 full: one known
     * new moon and the length of a month, counted forward.
     *
     * [Orrery.moonPhase] arrives at the same number from the other end,
     * out of the Moon's and the Earth's orbits, and a test holds the two
     * against each other. Neither is derived from the other, so agreement
     * is worth something — the last time this app worked a thing out twice
     * the copies disagreed for three versions.
     */
    fun phaseAt(whenMs: Long): Double {
        // Julian date of a known new moon: 2000-01-06 18:14 UTC.
        val julianNow = whenMs / 86_400_000.0 + 2_440_587.5
        return (((julianNow - 2_451_550.26) / SYNODIC) % 1.0 + 1.0) % 1.0
    }

    /**
     * The classic two-shape construction — a dark disc, the lit half, and a
     * terminator ellipse whose signed width follows cos(2π·phase), painted
     * dark for crescents and lit for gibbous moons.
     */
    fun drawMoon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        mr: Float,
        lit: Paint,
        dark: Paint,
        rim: Paint,
        whenMs: Long = TimeKeeper.nowMs(),
        withPhase: Boolean = true
    ) {
        // A plain disc when the phase is not wanted. The token still has to
        // be there — it is the door to the solar system, and a door has to
        // be somewhere — so switching the phase off leaves the moon whole
        // rather than leaving nothing to press.
        if (!withPhase) {
            canvas.drawCircle(cx, cy, mr, lit)
            canvas.drawCircle(cx, cy, mr, rim)
            return
        }
        val phase = phaseAt(whenMs)
        val cosPhase = cos(2.0 * PI * phase)
        val litRight = phase < 0.5

        canvas.drawCircle(cx, cy, mr, dark)
        canvas.save()
        if (litRight) {
            canvas.clipRect(cx, cy - mr, cx + mr, cy + mr)
        } else {
            canvas.clipRect(cx - mr, cy - mr, cx, cy + mr)
        }
        canvas.drawCircle(cx, cy, mr, lit)
        canvas.restore()
        val ellipseHalf = (mr * kotlin.math.abs(cosPhase)).toFloat()
        if (ellipseHalf > 0.5f) {
            val oval = RectF(cx - ellipseHalf, cy - mr, cx + ellipseHalf, cy + mr)
            canvas.drawOval(oval, if (cosPhase > 0) dark else lit)
        }
        canvas.drawCircle(cx, cy, mr, rim)
    }

    /**
     * The moon's daylight twin: same size, same spot, same paints. Drawing
     * it in any other colour made the pair look like two unrelated
     * ornaments.
     */
    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, mr: Float, lit: Paint, rim: Paint) {
        canvas.drawCircle(cx, cy, mr * 0.62f, lit)
        rays(canvas, cx, cy, mr, lit)
        canvas.drawCircle(cx, cy, mr * 0.62f, rim)
    }

    /**
     * The sun crossing the horizon, [sunk] from 0 (touching it) to 1 (gone).
     *
     * Between the sun and the moon there was a step: at one minute a sun, at
     * the next a moon, with nothing in between to say the day was ending.
     * The horizon is a line, the disc slides down through it and is cut off
     * where it passes, so the drawing takes as long as the sunset does.
     * Sunrise is the same picture run backwards.
     */
    private fun drawSettingSun(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        mr: Float,
        sunk: Float,
        lit: Paint,
        rim: Paint
    ) {
        val disc = mr * 0.62f
        val centre = cy - disc * (1f - 2f * sunk.coerceIn(0f, 1f))
        val saved = canvas.save()
        canvas.clipRect(cx - mr * 1.4f, cy - mr * 1.6f, cx + mr * 1.4f, cy)
        canvas.drawCircle(cx, centre, disc, lit)
        rays(canvas, cx, centre, mr, lit)
        canvas.restoreToCount(saved)
        canvas.drawLine(cx - mr * 1.25f, cy, cx + mr * 1.25f, cy, rim)
    }

    /** Eight short rays. drawLine always frames, whatever the style says. */
    private fun rays(canvas: Canvas, cx: Float, cy: Float, mr: Float, lit: Paint) {
        val width = lit.strokeWidth
        lit.strokeWidth = mr * 0.20f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val sx = cx + sin(a).toFloat() * mr * 0.86f
            val sy = cy - cos(a).toFloat() * mr * 0.86f
            val ex = cx + sin(a).toFloat() * mr * 1.18f
            val ey = cy - cos(a).toFloat() * mr * 1.18f
            canvas.drawLine(sx, sy, ex, ey, lit)
        }
        lit.strokeWidth = width
    }
}
