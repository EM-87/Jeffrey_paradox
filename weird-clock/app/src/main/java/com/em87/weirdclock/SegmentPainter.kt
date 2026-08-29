package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.hypot

/**
 * Draws the displays [Segments] describes.
 *
 * One painter for all four, because a bar is a bar: a stamped sliver of
 * metal or a cut in a mask, wider in the middle than at its ends, stopping
 * a hair short of whatever it points at. That hair is the display — take
 * it away where four diagonals cross and the `X` stops being four bars and
 * becomes a painted cross.
 *
 * The unlit bars are drawn too, faintly. That is not decoration: a display
 * you can only see the lit half of is a picture of a number, and the ghost
 * of the eight behind the seven is the thing that says there is a
 * mechanism here at all. It is also the only thing that says the space
 * beside `MMXXIV` is a display with nothing lit in it rather than the row
 * having ended.
 */
class SegmentPainter {

    private companion object {
        /** How far a corner may stretch when a curved bar is grown. */
        const val MITRE = 2.2f
    }

    /**
     * How thick the bars are, as a multiple of what the display was drawn
     * at — see [Segments.native].
     *
     * A multiple and not a share of the module, because the four displays
     * were not drawn to the same weight and one number cannot be "normal"
     * on all of them. Rome's came out of a file with its thickness in it;
     * setting this to one is that display at 1:1 and nothing else.
     */
    var weight: Float = 1f

    /** Whether the unlit bars are drawn behind the lit ones. */
    var ghosts: Boolean = true

    /** How faint they are when they are. */
    var ghostAlpha: Int = 34

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * A row of modules, laid out from [x] and [width] wide.
     *
     * [width] is the room the whole row gets, daylight between the modules
     * included — measure it with [Segments.span], which knows how much of
     * that is gap.
     */
    fun row(
        canvas: Canvas,
        kind: Segments.Kind,
        masks: IntArray,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        lit: Int,
        dark: Int,
        burnt: IntArray? = null
    ) {
        if (masks.isEmpty()) return
        val gap = Segments.gap(kind)
        val cell = width / (masks.size + (masks.size - 1) * gap)
        val stride = cell * (1f + gap)
        val t = height * Segments.native(kind) * weight
        for (stroke in Segments.plan(kind, masks, burnt)) {
            if (!stroke.lit && !ghosts) continue
            paint.color = if (stroke.lit) lit else dark
            paint.alpha = if (stroke.lit) 255 else ghostAlpha
            val bar = stroke.bar
            val at = x + stride * stroke.at
            if (bar.dot) {
                val r = if (bar.radius > 0f) height * bar.radius * weight else t * 1.15f
                canvas.drawCircle(at + bar.x0 * cell, y + bar.y0 * height, r, paint)
                continue
            }
            if (bar.outline != null) {
                if (bar.curved) {
                    swollen(bar, at, y, cell, height, (weight - 1f) * Segments.native(kind) / 2f)
                } else {
                    outlineOf(bar, at, y, cell, height, weight)
                }
            } else {
                sliver(
                    at + bar.x0 * cell, y + bar.y0 * height,
                    at + bar.x1 * cell, y + bar.y1 * height,
                    t, bar
                )
            }
            canvas.drawPath(path, paint)
        }
    }

    /**
     * A bar whose exact shape is known, at whatever thickness this display
     * is set to.
     *
     * The vertices are the drawing's, so the only freedom left is how fat
     * the bar is — and a bar gets fatter by its edges moving away from its
     * own axis, not by being scaled. Each vertex is split into how far
     * along the bar it is and how far off to the side, and only the second
     * half is multiplied. At [swell] of one this is the drawing, vertex for
     * vertex; at two it is the same shape with twice the metal, ends and
     * mitres and all.
     *
     * Done in units of the module's height so the normal is a real normal:
     * measuring across a diagonal in a box that is half as wide as it is
     * tall would put the extra metal on the skew.
     */
    private fun outlineOf(
        bar: Segments.Bar, x: Float, y: Float, cell: Float, height: Float, swell: Float
    ) {
        val pts = bar.outline ?: return
        // The axis, in height units.
        val ratio = cell / height
        val ax = bar.x0 * ratio
        val ay = bar.y0
        var vx = bar.x1 * ratio - ax
        var vy = bar.y1 - ay
        val len = hypot(vx, vy)
        if (len < 0.0001f) return
        vx /= len
        vy /= len
        path.reset()
        var i = 0
        while (i < pts.size) {
            val px = pts[i] * ratio - ax
            val py = pts[i + 1] - ay
            val along = px * vx + py * vy
            val off = (-px * vy + py * vx) * swell
            val hx = ax + along * vx - off * vy
            val hy = ay + along * vy + off * vx
            // Back out of height units. Both axes are in them now, so both
            // come back the same way.
            val sx = x + hx * height
            val sy = y + hy * height
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            i += 2
        }
        path.close()
    }

    /**
     * A bar whose shape is a curve, made thicker or thinner by growing.
     *
     * The other outline above widens a bar by pushing its vertices away
     * from its own straight axis, which is exactly right for a straight
     * piece of metal and wrong for a bent one: the Comet's top bar is a
     * thin rail with a hook on the end of it, and measuring off the chord
     * from one tip to the other would make the hook curl twice as far at
     * twice the weight instead of the rail getting twice as thick. The
     * shape would stop being the drawing's.
     *
     * So this one grows the outline outwards instead — every vertex slid
     * along the bisector of the two edges meeting at it, which moves each
     * piece of the edge away from itself and leaves the curvature alone.
     * [d] is how far, in module heights, and it is negative for a thinner
     * bar. The mitre is capped because the bars come to points, and an
     * uncapped bisector at a point runs off to infinity.
     */
    private fun swollen(
        bar: Segments.Bar, x: Float, y: Float, cell: Float, height: Float, d: Float
    ) {
        val pts = bar.outline ?: return
        val n = pts.size / 2
        if (n < 3) return
        val ratio = cell / height
        fun px(i: Int) = pts[(i % n + n) % n * 2] * ratio
        fun py(i: Int) = pts[(i % n + n) % n * 2 + 1]
        // Which side is out. Twice the signed area, whose sign is the
        // winding, and with y downwards a positive one turns clockwise.
        var twice = 0f
        for (i in 0 until n) twice += px(i) * py(i + 1) - px(i + 1) * py(i)
        val side = if (twice > 0f) 1f else -1f
        path.reset()
        for (i in 0 until n) {
            // The outward normals of the two edges meeting here.
            var ax = py(i) - py(i - 1)
            var ay = px(i - 1) - px(i)
            var bx = py(i + 1) - py(i)
            var by = px(i) - px(i + 1)
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            if (la > 0.0001f) { ax /= la; ay /= la }
            if (lb > 0.0001f) { bx /= lb; by /= lb }
            var nx = (ax + bx) * side
            var ny = (ay + by) * side
            val len = hypot(nx, ny)
            var out = d
            if (len > 0.0001f) {
                nx /= len
                ny /= len
                // How far along the bisector one unit of thickness is:
                // one over the cosine of half the corner, capped.
                val cos = (nx * bx * side + ny * by * side).coerceAtLeast(0.001f)
                out = d * minOf(1f / cos, MITRE)
            }
            val sx = x + (px(i) + nx * out) * height
            val sy = y + (py(i) + ny * out) * height
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
    }

    /**
     * One bar as a six-sided sliver.
     *
     * The ends are cut, not pointed: a real segment is a stamped shape
     * with a short square end and the corners taken off it, and a row of
     * needle-sharp bars reads as a drawing of a display rather than as
     * one. Where the bar runs into a junction — the middle of an `X`, the
     * middle of a star — it overlaps instead, or the stroke has a hole in
     * it exactly where the eye is looking.
     */
    private fun sliver(
        x0: Float, y0: Float, x1: Float, y1: Float, unit: Float, bar: Segments.Bar
    ) {
        val t = unit * bar.weight
        path.reset()
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 0.01f) return
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val h = t / 2f
        val headJoins = bar.joinsAt == -1 || bar.joinsAt == 2
        val tailJoins = bar.joinsAt == 1 || bar.joinsAt == 2
        val head = if (headJoins) -t * 0.30f else t * 0.55f
        val tail = if (tailJoins) -t * 0.30f else t * 0.55f
        val ax = x0 + ux * head
        val ay = y0 + uy * head
        val bx = x1 - ux * tail
        val by = y1 - uy * tail
        // How far in from each end the bar reaches its full width.
        val run = (len - head - tail).coerceAtLeast(t * 0.4f)
        val sh = if (bar.shoulder > 0f) run * bar.shoulder else minOf(t * 0.8f, run * 0.32f)
        val nAx = -uy * h
        val nAy = ux * h
        val nBx = nAx
        val nBy = nAy
        // The flat of each end.
        val fAx = -uy * h * bar.flat
        val fAy = ux * h * bar.flat
        val fBx = fAx
        val fBy = fAy
        path.moveTo(ax + fAx, ay + fAy)
        path.lineTo(ax + ux * sh + nAx, ay + uy * sh + nAy)
        path.lineTo(bx - ux * sh + nBx, by - uy * sh + nBy)
        path.lineTo(bx + fBx, by + fBy)
        path.lineTo(bx - fBx, by - fBy)
        path.lineTo(bx - ux * sh - nBx, by - uy * sh - nBy)
        path.lineTo(ax + ux * sh - nAx, ay + uy * sh - nAy)
        path.lineTo(ax - fAx, ay - fAy)
        path.close()
        if (bar.round) {
            // The one bar the drawing gives a stadium to, because it is
            // the only one that ends in mid-air rather than at a corner.
            path.reset()
            path.addRoundRect(
                minOf(ax, bx) - h, minOf(ay, by) - h,
                maxOf(ax, bx) + h, maxOf(ay, by) + h,
                h, h, Path.Direction.CW
            )
        }
    }

    /**
     * Which bar of the module at [x], [y] a finger at [px], [py] is on, or
     * zero if it is on none of them.
     *
     * Nearest-bar rather than hit-testing the sliver itself: the bars are
     * thin, a fingertip is not, and asking "which of these did you mean"
     * is the question a touch on a display is actually asking. The
     * distance is capped so a tap in the empty corner of a module hits
     * nothing rather than lighting whatever was least far away.
     */
    fun barUnder(
        kind: Segments.Kind,
        px: Float, py: Float,
        x: Float, y: Float, width: Float, height: Float
    ): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (bar in Segments.bars(kind)) {
            val ax = x + bar.x0 * width
            val ay = y + bar.y0 * height
            val bx = x + bar.x1 * width
            val by = y + bar.y1 * height
            val d = distanceToBar(px, py, ax, ay, bx, by)
            if (d < bestD) {
                bestD = d
                best = bar.bit
            }
        }
        return if (bestD <= height * (Segments.native(kind) * weight + 0.09f)) best else 0
    }

    private fun distanceToBar(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 < 0.0001f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }
}
