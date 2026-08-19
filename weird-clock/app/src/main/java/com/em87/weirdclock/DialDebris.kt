package com.em87.weirdclock

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The loose pieces lying on a dial, and the physics that pushes them about.
 *
 * Knock a clock hard enough and its hands come off, its numerals shake
 * loose and the whole lot piles up at the bottom of the case under real
 * gravity. That is a rigid-body simulation living inside a custom View,
 * which is a lot of arithmetic to have in the same file as the thing that
 * draws a minute hand — and every bug this file has ever had came from one
 * of those two jobs quietly reaching into the other.
 *
 * What lives here is everything a piece does once it has fallen: gravity,
 * the case walls, pieces bouncing off each other, and going to sleep when
 * they have all but stopped. What does *not* live here is anything that
 * needs to know what a clock looks like — which pieces exist, where they
 * start, where they belong when you put them back, and how to draw them.
 * The dial keeps all of that and hands this class the one thing the
 * physics genuinely needs: where its walls are.
 */
internal class DialDebris(private val dial: Case) {

    /** The case the pieces rattle around inside. */
    interface Case {
        val caseWidth: Int
        val caseHeight: Int

        /** Distance from the centre to the wall at [angleDeg]. */
        fun wallAt(angleDeg: Float): Float
    }

    enum class Kind { HAND, FAST_HAND, NUMERAL, MOON, DATE }

    class Body(
        val kind: Kind,
        val hand: ClockView.Hand?,
        val numeralHour: Int,
        val label: String,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var angleDeg: Float,
        var angVel: Float,
        var halfLen: Float,
        var strokeWidth: Float,
        var textSize: Float,
        /**
         * For the sky token: the hour of the day it was showing when it
         * came off, and the instant it came off.
         *
         * A piece that has fallen off the dial has stopped being told the
         * time — it is a bead of glass on the floor of the case. Without
         * these it was redrawn every frame from the live clock, which is
         * why the sun could set while it lay there.
         */
        val frozenTimeOfDayMs: Long = 0L,
        val frozenWallMs: Long = 0L
    )

    val bodies = ArrayList<Body>()

    /** The piece currently under a finger, which gravity does not touch. */
    var carried: Body? = null

    /** Live gravity vector in view coordinates (px/s²), from the sensor. */
    var gravityX = 0f
    var gravityY = BASE_GRAVITY

    private val sampleBufA = FloatArray(SAMPLE_COUNT * 2)
    private val sampleBufB = FloatArray(SAMPLE_COUNT * 2)

    /** Everything goes back where it came from, hands included. */
    fun clear() {
        bodies.clear()
        carried = null
    }

    /**
     * One step of the simulation: gravity, then the case walls.
     *
     * Split from [settle] rather than run as one call because the dial
     * slips its own pass in between — the hands still on the axis sweep the
     * debris around, and a piece batted by the second hand must not be put
     * to sleep in the same frame it was hit.
     */
    fun advance(dt: Float) {
        val cx = dial.caseWidth / 2f
        val cy = dial.caseHeight / 2f
        for (b in bodies) {
            if (b === carried) continue
            b.vx += gravityX * dt
            b.vy += gravityY * dt
            // Speed cap: a piece may never travel more than its own half
            // length per step, which is what let trapped debris tunnel
            // clean through its neighbours.
            val speed = hypot(b.vx, b.vy)
            val maxSpeed = max(b.halfLen, 20f) / dt
            if (speed > maxSpeed) {
                b.vx *= maxSpeed / speed
                b.vy *= maxSpeed / speed
            }
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.angleDeg += b.angVel * dt
            val rad = Math.toRadians(b.angleDeg.toDouble())
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()
            for (side in END_SIDES) {
                val ex = b.x + dirX * b.halfLen * side
                val ey = b.y + dirY * b.halfLen * side
                val d = hypot(ex - cx, ey - cy)
                // Contain each end inside the case's (possibly polygonal)
                // boundary; the push-back is radial, which is a good enough
                // approximation for toy debris.
                val endAngle = Math.toDegrees(
                    atan2((ex - cx).toDouble(), -(ey - cy).toDouble())
                ).toFloat()
                val rIn = dial.wallAt(endAngle) * 0.96f
                if (d > rIn) {
                    val nx = (ex - cx) / d
                    val ny = (ey - cy) / d
                    val overlap = d - rIn
                    b.x -= nx * overlap
                    b.y -= ny * overlap
                    val vn = b.vx * nx + b.vy * ny
                    if (vn > 0f) {
                        b.vx -= 1.5f * vn * nx
                        b.vy -= 1.5f * vn * ny
                        b.angVel = -b.angVel * 0.45f +
                            (Random.nextFloat() - 0.5f) * min(vn, 400f)
                    }
                    b.vx *= 0.97f
                    b.vy *= 0.97f
                }
            }
            b.angVel *= 0.99f
        }
    }

    /**
     * Pieces that have all but stopped are put fully to sleep, so a settled
     * heap stays settled instead of buzzing.
     */
    fun settle() {
        for (b in bodies) {
            if (b === carried) continue
            if (hypot(b.vx, b.vy) < 12f && kotlin.math.abs(b.angVel) < 12f) {
                b.vx *= 0.5f
                b.vy *= 0.5f
                b.angVel *= 0.5f
                if (hypot(b.vx, b.vy) < 3f) {
                    b.vx = 0f
                    b.vy = 0f
                    b.angVel = 0f
                }
            }
        }
    }

    fun resolveCollisions() {
        val n = bodies.size
        if (n < 2) return
        for (i in 0 until n - 1) {
            val a = bodies[i]
            samplePoints(a, sampleBufA)
            for (j in i + 1 until n) {
                val b = bodies[j]
                samplePoints(b, sampleBufB)
                val minDist = radiusOf(a) + radiusOf(b)
                contact@ for (p in 0 until SAMPLE_COUNT) {
                    for (q in 0 until SAMPLE_COUNT) {
                        val dx = sampleBufA[p * 2] - sampleBufB[q * 2]
                        val dy = sampleBufA[p * 2 + 1] - sampleBufB[q * 2 + 1]
                        val d = hypot(dx, dy)
                        if (d < minDist && d > 0.001f) {
                            val nx = dx / d
                            val ny = dy / d
                            val push = (minDist - d) / 2f
                            if (a !== carried) {
                                a.x += nx * push
                                a.y += ny * push
                            }
                            if (b !== carried) {
                                b.x -= nx * push
                                b.y -= ny * push
                            }
                            val relVn = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny
                            if (relVn < 0f) {
                                val impulse = -1.4f * relVn / 2f
                                val spin = min(kotlin.math.abs(impulse), 200f)
                                if (a !== carried) {
                                    a.vx += impulse * nx
                                    a.vy += impulse * ny
                                    a.angVel += (Random.nextFloat() - 0.5f) * spin
                                }
                                if (b !== carried) {
                                    b.vx -= impulse * nx
                                    b.vy -= impulse * ny
                                    b.angVel += (Random.nextFloat() - 0.5f) * spin
                                }
                            }
                            break@contact
                        }
                    }
                }
            }
        }
    }

    /**
     * Shoves the debris away from a line — how the hands still mounted on
     * the axis bat the loose pieces around the dial. Where those lines are
     * is the dial's business; what happens to a piece hit by one is this
     * class's.
     */
    fun collideWithSegment(x1: Float, y1: Float, x2: Float, y2: Float, halfWidth: Float) {
        val segDx = x2 - x1
        val segDy = y2 - y1
        val len2 = segDx * segDx + segDy * segDy
        if (len2 <= 0f) return
        for (b in bodies) {
            if (b === carried) continue
            samplePoints(b, sampleBufA)
            val minDist = radiusOf(b) + halfWidth + 2f
            for (k in 0 until SAMPLE_COUNT) {
                val px = sampleBufA[k * 2]
                val py = sampleBufA[k * 2 + 1]
                val t = (((px - x1) * segDx + (py - y1) * segDy) / len2).coerceIn(0f, 1f)
                val qx = x1 + t * segDx
                val qy = y1 + t * segDy
                val dx = px - qx
                val dy = py - qy
                val d = hypot(dx, dy)
                if (d < minDist && d > 0.001f) {
                    val nx = dx / d
                    val ny = dy / d
                    val overlap = minDist - d
                    b.x += nx * overlap
                    b.y += ny * overlap
                    val vn = b.vx * nx + b.vy * ny
                    if (vn < 0f) {
                        b.vx -= 1.5f * vn * nx
                        b.vy -= 1.5f * vn * ny
                        b.angVel += (Random.nextFloat() - 0.5f) * 150f
                    }
                    break
                }
            }
        }
    }

    /** The piece lying under a finger at ([x], [y]), if any. */
    fun bodyNear(x: Float, y: Float, threshold: Float): Body? {
        for (b in bodies) {
            val rad = Math.toRadians(b.angleDeg.toDouble())
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()
            val d = distanceToSegment(
                x, y,
                b.x - dirX * b.halfLen, b.y - dirY * b.halfLen,
                b.x + dirX * b.halfLen, b.y + dirY * b.halfLen
            )
            if (d < threshold) return b
        }
        return null
    }

    private fun samplePoints(b: Body, out: FloatArray) {
        val rad = Math.toRadians(b.angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        for (k in 0 until SAMPLE_COUNT) {
            val t = k / (SAMPLE_COUNT - 1f) * 2f - 1f
            out[k * 2] = b.x + dx * b.halfLen * t
            out[k * 2 + 1] = b.y + dy * b.halfLen * t
        }
    }

    private fun radiusOf(b: Body): Float = max(
        when (b.kind) {
            Kind.NUMERAL -> b.textSize * 0.30f
            Kind.DATE -> b.textSize * 0.35f
            Kind.MOON -> b.halfLen
            else -> b.strokeWidth * 0.5f
        },
        2f
    )

    private fun distanceToSegment(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    companion object {
        /** Downward acceleration on a piece lying in the case, px/s². */
        const val BASE_GRAVITY = 2600f

        /** Points sampled along each piece when looking for contacts. */
        private const val SAMPLE_COUNT = 5

        private val END_SIDES = floatArrayOf(1f, -1f)
    }
}
