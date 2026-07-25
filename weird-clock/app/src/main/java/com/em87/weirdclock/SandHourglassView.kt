package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * S3: the countdown as an actual particle system. Every grain of sand is a
 * simulated body under the live accelerometer gravity vector; the neck is a
 * gate that only lets through as many grains as the clock has earned, so
 * the pile drains at exactly the countdown's pace — and time becomes
 * physical: tilt the phone sideways and the sand can't reach the neck, so
 * the countdown stops; flip the phone and the fallen sand becomes the sand
 * still to fall. Poke the pile with a finger and it scatters.
 *
 * The glass is a real hourglass profile — a smooth curve from the wide
 * bulbs to the neck — and containment is analytic (the glass half-width is
 * a function of height), so no grain can ever tunnel out.
 */
class SandHourglassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    /** Shared dial zoom: the glass scales with every other dial. */
    var glassScale = 1f
        set(value) {
            val next = value.coerceIn(ClockView.MIN_SCALE, ClockView.MAX_SCALE)
            if (next != field) {
                field = next
                rebuildGeometry()
                rebuildGrains()
                invalidate()
            }
        }
    var onScaleChanged: ((Float) -> Unit)? = null

    /** Reports whether gravity currently lets sand reach the neck. */
    var onFlowBlocked: ((Boolean) -> Unit)? = null

    // ------------------------------------------------------------- time link

    private var totalMs = 300_000L
    private var remainingMs = 300_000L

    /** How many grains the user is willing to let their phone simulate. */
    var maxGrains = 260
        set(value) {
            val next = value.coerceIn(40, 900)
            if (next != field) {
                field = next
                rebuildGeometry()
                rebuildGrains()
            }
        }

    private fun grainQuantumMs(): Long = (totalMs / maxGrains).coerceAtLeast(1000L)

    private fun desiredGrainCount(): Int =
        (totalMs / grainQuantumMs()).toInt().coerceIn(1, maxGrains)

    fun setTime(total: Long, remaining: Long) {
        val totalChanged = total != totalMs
        totalMs = total.coerceAtLeast(1000L)
        remainingMs = remaining.coerceIn(0L, totalMs)
        if (totalChanged) rebuildGrains()
        invalidate()
    }

    // ------------------------------------------------------------- particles

    private class Grain(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var topSide: Boolean
    )

    private val grains = ArrayList<Grain>()
    private var grainR = 5f
    private var lastStepAt = 0L
    private var lastTopCount = -1
    private var lastFlowChangeAt = 0L
    private var reportedBlocked = false

    // Glass geometry (view coordinates).
    private var cx = 0f
    private var cy = 0f
    private var bulbHalf = 0f
    private var halfH = 0f
    private var neckHalf = 0f
    private val glassPath = Path()

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ------------------------------------------------------------- gravity

    private var gravityX = 0f
    private var gravityY = GRAVITY
    private var lowX = 0f
    private var lowY = 9.81f
    private var sensorManager: SensorManager? = null
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Heavier smoothing than the dial's: sand should not jitter with
            // sensor noise, and a hand-held phone is never perfectly still.
            lowX = lowX * 0.93f + event.values[0] * 0.07f
            lowY = lowY * 0.93f + event.values[1] * 0.07f
            var gx = -lowX / 9.81f
            var gy = lowY / 9.81f
            val inPlane = hypot(gx, gy)
            if (inPlane < 0.35f) {
                // Phone lying flat: gravity points into the screen, so there
                // is no in-plane component at all and the sand would just
                // hang there. Pretend the glass is standing up — a table is
                // not an excuse to stop counting.
                val blend = 1f - inPlane / 0.35f
                gy += blend * 0.9f
                gx *= (1f - blend)
            }
            gravityX = gx * GRAVITY
            gravityY = gy * GRAVITY
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // Finger stir state.
    private var stirActive = false
    private var stirX = 0f
    private var stirY = 0f
    private var stirVx = 0f
    private var stirVy = 0f
    private var lastStirAt = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        lastStepAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        sensorManager?.unregisterListener(sensorListener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
        rebuildGrains()
    }

    // ------------------------------------------------------------- geometry

    /**
     * Glass half-width at height [y]: neck-width at the waist, swelling as
     * the square root of the distance toward each bulb — the classic
     * hourglass silhouette.
     */
    private fun halfWidthAt(y: Float): Float {
        val t = (abs(y - cy) / halfH).coerceIn(0f, 1f)
        return neckHalf + (bulbHalf - neckHalf) * sqrt(t)
    }

    private fun rebuildGeometry() {
        if (width == 0 || height == 0) return
        cx = width / 2f
        cy = height / 2f
        halfH = min(height * 0.40f, width * 0.72f) * glassScale
        bulbHalf = halfH * 0.58f
        // Grain size follows the count: the more grains, the finer the sand,
        // so the two bulbs stay about as full either way.
        grainR = (bulbHalf / sqrt(maxGrains.toFloat()) * 1.5f).coerceAtLeast(1.6f)
        neckHalf = grainR * 2.2f

        glassPath.reset()
        val steps = 36
        for (i in 0..steps) {
            val y = cy - halfH + (2f * halfH) * i / steps
            val hw = halfWidthAt(y)
            if (i == 0) glassPath.moveTo(cx - hw, y) else glassPath.lineTo(cx - hw, y)
        }
        for (i in steps downTo 0) {
            val y = cy - halfH + (2f * halfH) * i / steps
            val hw = halfWidthAt(y)
            glassPath.lineTo(cx + hw, y)
        }
        glassPath.close()
    }

    private fun rebuildGrains() {
        if (width == 0 || halfH <= 0f) return
        grains.clear()
        val n = desiredGrainCount()
        val top = (n * remainingMs.toDouble() / totalMs).roundToInt().coerceIn(0, n)
        repeat(n) { i ->
            val inTop = i < top
            val gy = if (inTop) {
                cy - halfH * (0.25f + Random.nextFloat() * 0.65f)
            } else {
                cy + halfH * (0.25f + Random.nextFloat() * 0.65f)
            }
            val hw = (halfWidthAt(gy) - grainR * 2f).coerceAtLeast(1f)
            grains.add(
                Grain(
                    cx + (Random.nextFloat() * 2f - 1f) * hw,
                    gy, 0f, 0f,
                    topSide = inTop
                )
            )
        }
        lastTopCount = -1
    }

    // --------------------------------------------------------------- physics

    private fun stepPhysics() {
        val now = SystemClock.uptimeMillis()
        val frameDt = ((now - lastStepAt).coerceIn(0, 40)) / 1000f
        lastStepAt = now
        if (frameDt <= 0f || grains.isEmpty()) return

        // Gate bookkeeping: the upstream bulb (against gravity) may only
        // shed grains the clock has already spent.
        val gMag = hypot(gravityX, gravityY)
        val verticalFlow = gMag > 1f && abs(gravityY) > gMag * 0.45f
        val upstreamIsTop = gravityY >= 0f
        val topCount = grains.count { it.topSide }
        val upstream = if (upstreamIsTop) topCount else grains.size - topCount
        val targetUpstream = (grains.size * remainingMs.toDouble() / totalMs).roundToInt()
        var budget = if (verticalFlow) upstream - targetUpstream else 0

        // Physical time: sand that should flow but can't freezes the clock.
        if (topCount != lastTopCount) {
            lastTopCount = topCount
            lastFlowChangeAt = now
        }
        val blocked = upstream > targetUpstream + 1 && !verticalFlow &&
            now - lastFlowChangeAt > 800L
        if (blocked != reportedBlocked) {
            reportedBlocked = blocked
            onFlowBlocked?.invoke(blocked)
        }

        // Two substeps keep fast grains from skipping across the neck.
        repeat(2) {
            val dt = frameDt / 2f
            for (g in grains) {
                g.vx += gravityX * dt
                g.vy += gravityY * dt
                // Finger stir: grains near the finger get shoved along.
                if (stirActive) {
                    val dx = g.x - stirX
                    val dy = g.y - stirY
                    val d = hypot(dx, dy)
                    val reach = grainR * 9f
                    if (d < reach && d > 0.001f) {
                        val push = (1f - d / reach)
                        g.vx += (dx / d * 2600f + stirVx * 0.9f) * push * dt * 8f
                        g.vy += (dy / d * 2600f + stirVy * 0.9f) * push * dt * 8f
                    }
                }
                // Speed cap: nothing may cross a grain diameter per substep.
                val sp = hypot(g.vx, g.vy)
                val maxSp = grainR * 1.6f / dt
                if (sp > maxSp) {
                    g.vx *= maxSp / sp
                    g.vy *= maxSp / sp
                }
                g.vx *= 0.999f
                g.vy *= 0.999f
                g.x += g.vx * dt
                g.y += g.vy * dt

                // Neck gate: crossing is only for grains the clock released.
                val nowTop = g.y < cy
                if (nowTop != g.topSide) {
                    val crossingDownstream = g.topSide == upstreamIsTop
                    if (crossingDownstream && budget > 0) {
                        budget--
                        g.topSide = nowTop
                    } else {
                        // Bounce back off the closed gate.
                        if (g.topSide) {
                            g.y = cy - grainR
                            if (g.vy > 0f) g.vy = -g.vy * 0.2f
                        } else {
                            g.y = cy + grainR
                            if (g.vy < 0f) g.vy = -g.vy * 0.2f
                        }
                    }
                }

                constrainToGlass(g)
            }
            separateGrains()
            for (g in grains) constrainToGlass(g)
        }
    }

    /** Analytic containment: escape is geometrically impossible. */
    private fun constrainToGlass(g: Grain) {
        val topLimit = cy - halfH + grainR
        val bottomLimit = cy + halfH - grainR
        if (g.y < topLimit) {
            g.y = topLimit
            if (g.vy < 0f) g.vy = -g.vy * 0.3f
        }
        if (g.y > bottomLimit) {
            g.y = bottomLimit
            if (g.vy > 0f) g.vy = -g.vy * 0.3f
        }
        val maxX = (halfWidthAt(g.y) - grainR).coerceAtLeast(0.5f)
        val dx = g.x - cx
        if (dx > maxX) {
            g.x = cx + maxX
            if (g.vx > 0f) g.vx = -g.vx * 0.3f
        } else if (dx < -maxX) {
            g.x = cx - maxX
            if (g.vx < 0f) g.vx = -g.vx * 0.3f
        }
    }

    /** Cheap spatial-hash separation so grains pile instead of overlap. */
    private val cellMap = HashMap<Long, ArrayList<Grain>>()

    /**
     * Grain-grain contact. Sand is not water: contacts are almost perfectly
     * inelastic and rub hard against each other, which is what lets a heap
     * hold a slope instead of levelling out like a liquid. The positional
     * push is capped so a compressed pile can't explode into a volcano.
     */
    private fun separateGrains() {
        val cell = grainR * 2f
        cellMap.values.forEach { it.clear() }
        for (g in grains) {
            val key = (g.x / cell).toLong() * 100_000L + (g.y / cell).toLong()
            cellMap.getOrPut(key) { ArrayList() }.add(g)
        }
        val minDist = grainR * 1.75f
        val maxPush = grainR * 0.30f
        for (g in grains) {
            val cxi = (g.x / cell).toLong()
            val cyi = (g.y / cell).toLong()
            for (ix in -1..1) {
                for (iy in -1..1) {
                    val list = cellMap[(cxi + ix) * 100_000L + (cyi + iy)] ?: continue
                    for (o in list) {
                        if (o === g) continue
                        val dx = g.x - o.x
                        val dy = g.y - o.y
                        val d = hypot(dx, dy)
                        if (d < minDist && d > 0.0001f) {
                            val push = min((minDist - d) / 2f, maxPush)
                            val nx = dx / d
                            val ny = dy / d
                            g.x += nx * push
                            g.y += ny * push
                            o.x -= nx * push
                            o.y -= ny * push
                            // Normal impulse: kill the approach speed almost
                            // entirely instead of bouncing it back.
                            val rvn = (g.vx - o.vx) * nx + (g.vy - o.vy) * ny
                            if (rvn < 0f) {
                                val j = rvn * 0.52f
                                g.vx -= j * nx
                                g.vy -= j * ny
                                o.vx += j * nx
                                o.vy += j * ny
                            }
                            // Friction: rub away the sliding component, so
                            // grains lock into a slope.
                            val tx = -ny
                            val ty = nx
                            val rvt = (g.vx - o.vx) * tx + (g.vy - o.vy) * ty
                            val f = rvt * FRICTION
                            g.vx -= f * tx
                            g.vy -= f * ty
                            o.vx += f * tx
                            o.vy += f * ty
                        }
                    }
                }
            }
        }
        // Grains that have all but stopped go to sleep, so a settled pile
        // stops shivering.
        for (g in grains) {
            if (hypot(g.vx, g.vy) < grainR * 1.2f) {
                g.vx *= 0.55f
                g.vy *= 0.55f
            }
        }
    }

    // ----------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                glassScale *= detector.scaleFactor
                onScaleChanged?.invoke(glassScale)
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stirActive = true
                stirX = event.x
                stirY = event.y
                stirVx = 0f
                stirVy = 0f
                lastStirAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastStirAt).coerceAtLeast(1L) / 1000f
                    stirVx = (event.x - stirX) / dt
                    stirVy = (event.y - stirY) / dt
                    stirX = event.x
                    stirY = event.y
                    lastStirAt = now
                    stirActive = true
                } else {
                    stirActive = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                stirActive = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stirActive = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || halfH <= 0f) return
        stepPhysics()

        // Sand.
        sandPaint.color = theme.decimal
        for (g in grains) {
            canvas.drawCircle(g.x, g.y, grainR * 0.8f, sandPaint)
        }

        // Glass on top, so grains read as inside it.
        glassPaint.color = theme.rim
        glassPaint.strokeWidth = (grainR * 0.9f).coerceAtLeast(3f)
        canvas.drawPath(glassPath, glassPaint)

        framePaint.color = theme.tick
        framePaint.strokeWidth = (grainR * 1.4f).coerceAtLeast(4f)
        canvas.drawLine(
            cx - bulbHalf * 1.18f, cy - halfH,
            cx + bulbHalf * 1.18f, cy - halfH, framePaint
        )
        canvas.drawLine(
            cx - bulbHalf * 1.18f, cy + halfH,
            cx + bulbHalf * 1.18f, cy + halfH, framePaint
        )

        postInvalidateOnAnimation()
    }

    companion object {
        private const val GRAVITY = 2000f

        /** How hard grains rub against each other; sand, not water. */
        private const val FRICTION = 0.34f
    }
}
