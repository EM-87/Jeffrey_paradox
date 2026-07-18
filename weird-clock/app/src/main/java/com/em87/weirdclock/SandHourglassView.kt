package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
import kotlin.random.Random

/**
 * S3: the countdown as an actual particle system. Every grain of sand is a
 * simulated body under the live accelerometer gravity vector; the neck is a
 * gate that only lets through as many grains as the clock has earned, so
 * the pile drains at exactly the countdown's pace — and time becomes
 * physical: tilt the phone sideways and the sand can't reach the neck, so
 * the countdown stops; flip the phone and the fallen sand becomes the sand
 * still to fall. One grain ≈ one quantum of your time.
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

    /** Grains represent time; at most this many are simulated. */
    private fun grainQuantumMs(): Long = (totalMs / MAX_GRAINS).coerceAtLeast(1000L)

    private fun desiredGrainCount(): Int =
        (totalMs / grainQuantumMs()).toInt().coerceIn(1, MAX_GRAINS)

    fun setTime(total: Long, remaining: Long) {
        val totalChanged = total != totalMs
        totalMs = total.coerceAtLeast(1000L)
        remainingMs = remaining.coerceIn(0L, totalMs)
        if (totalChanged) rebuildGrains()
        invalidate()
    }

    // ------------------------------------------------------------- particles

    private class Grain(var x: Float, var y: Float, var vx: Float, var vy: Float)

    private val grains = ArrayList<Grain>()
    private var grainR = 8f
    private var lastStepAt = 0L
    private var lastFlowChangeAt = 0L
    private var lastUpstreamCount = -1
    private var reportedBlocked = false

    // Glass geometry (view coordinates), rebuilt on size/scale changes.
    private var cx = 0f
    private var cy = 0f
    private var halfW = 0f
    private var halfH = 0f
    private var neckHalf = 0f
    /** Wall segments: x1,y1,x2,y2 per wall. */
    private var walls = FloatArray(0)

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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
            lowX = lowX * 0.85f + event.values[0] * 0.15f
            lowY = lowY * 0.85f + event.values[1] * 0.15f
            gravityX = -lowX / 9.81f * GRAVITY
            gravityY = lowY / 9.81f * GRAVITY
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

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

    private fun rebuildGeometry() {
        if (width == 0 || height == 0) return
        cx = width / 2f
        cy = height / 2f
        // Full-screen glass, scaled with the shared dial zoom.
        halfH = min(height * 0.40f, width * 0.72f) * glassScale
        halfW = halfH * 0.60f
        grainR = (halfW / 11f).coerceAtLeast(5f)
        neckHalf = grainR * 1.55f
        walls = floatArrayOf(
            // Top lid.
            cx - halfW, cy - halfH, cx + halfW, cy - halfH,
            // Upper funnel.
            cx - halfW, cy - halfH, cx - neckHalf, cy,
            cx + halfW, cy - halfH, cx + neckHalf, cy,
            // Lower funnel.
            cx - neckHalf, cy, cx - halfW, cy + halfH,
            cx + neckHalf, cy, cx + halfW, cy + halfH,
            // Bottom lid.
            cx - halfW, cy + halfH, cx + halfW, cy + halfH
        )
    }

    /** Rebuilds the two piles to match the clock (used on resize/duration). */
    private fun rebuildGrains() {
        if (width == 0 || halfH <= 0f) return
        grains.clear()
        val n = desiredGrainCount()
        val top = (n * remainingMs.toDouble() / totalMs).roundToInt().coerceIn(0, n)
        repeat(n) { i ->
            val inTop = i < top
            val gy = if (inTop) cy - halfH * 0.55f else cy + halfH * 0.55f
            grains.add(
                Grain(
                    cx + (Random.nextFloat() - 0.5f) * halfW * 1.2f,
                    gy + (Random.nextFloat() - 0.5f) * halfH * 0.5f,
                    0f, 0f
                )
            )
        }
        lastUpstreamCount = -1
    }

    // --------------------------------------------------------------- physics

    private fun stepPhysics() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastStepAt).coerceIn(0, 40)) / 1000f
        lastStepAt = now
        if (dt <= 0f || grains.isEmpty()) return

        // The neck gate: open only while the upstream bulb (against gravity)
        // holds more grains than the clock says should remain.
        val gMag = hypot(gravityX, gravityY)
        val verticalFlow = gMag > 1f && abs(gravityY) > gMag * 0.45f
        val upstreamIsTop = gravityY >= 0f
        var upstream = 0
        for (g in grains) if ((g.y < cy) == upstreamIsTop) upstream++
        val targetUpstream = (grains.size * remainingMs.toDouble() / totalMs).roundToInt()
        val gateOpen = verticalFlow && upstream > targetUpstream

        // Time turns physical here: if grains should be passing but can't
        // (phone flat or sideways), report it so the countdown freezes.
        if (upstream != lastUpstreamCount) {
            lastUpstreamCount = upstream
            lastFlowChangeAt = now
        }
        val blocked = upstream > targetUpstream + 1 && !verticalFlow &&
            now - lastFlowChangeAt > 800L
        if (blocked != reportedBlocked) {
            reportedBlocked = blocked
            onFlowBlocked?.invoke(blocked)
        }

        for (g in grains) {
            g.vx += gravityX * dt
            g.vy += gravityY * dt
            g.vx *= 0.998f
            g.vy *= 0.998f
            g.x += g.vx * dt
            g.y += g.vy * dt
        }

        // Neck gate as an extra wall while closed.
        val gateSolid = !gateOpen

        // Wall + gate constraints, then grain-grain separation.
        for (g in grains) {
            constrainToWalls(g, gateSolid)
        }
        separateGrains()
        for (g in grains) {
            constrainToWalls(g, gateSolid)
        }
    }

    private fun constrainToWalls(g: Grain, gateSolid: Boolean) {
        var i = 0
        while (i < walls.size) {
            pushFromSegment(g, walls[i], walls[i + 1], walls[i + 2], walls[i + 3])
            i += 4
        }
        if (gateSolid) {
            pushFromSegment(g, cx - neckHalf, cy, cx + neckHalf, cy)
        }
        // Hard clamp to the view so nothing escapes numerically.
        g.x = g.x.coerceIn(grainR, width - grainR)
        g.y = g.y.coerceIn(grainR, height - grainR)
    }

    private fun pushFromSegment(g: Grain, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return
        val t = (((g.x - x1) * dx + (g.y - y1) * dy) / len2).coerceIn(0f, 1f)
        val qx = x1 + t * dx
        val qy = y1 + t * dy
        val ox = g.x - qx
        val oy = g.y - qy
        val d = hypot(ox, oy)
        if (d < grainR && d > 0.0001f) {
            val nx = ox / d
            val ny = oy / d
            val push = grainR - d
            g.x += nx * push
            g.y += ny * push
            val vn = g.vx * nx + g.vy * ny
            if (vn < 0f) {
                // Mostly inelastic: sand piles, it doesn't bounce.
                g.vx -= 1.15f * vn * nx
                g.vy -= 1.15f * vn * ny
                g.vx *= 0.92f
                g.vy *= 0.92f
            }
        }
    }

    /** Cheap spatial-hash separation so grains pile instead of overlap. */
    private val cellMap = HashMap<Long, ArrayList<Grain>>()

    private fun separateGrains() {
        val cell = grainR * 2f
        cellMap.values.forEach { it.clear() }
        for (g in grains) {
            val key = (g.x / cell).toLong() * 100_000L + (g.y / cell).toLong()
            cellMap.getOrPut(key) { ArrayList() }.add(g)
        }
        val minDist = grainR * 1.7f
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
                            val push = (minDist - d) / 2f
                            val nx = dx / d
                            val ny = dy / d
                            g.x += nx * push
                            g.y += ny * push
                            o.x -= nx * push
                            o.y -= ny * push
                            g.vx *= 0.985f
                            g.vy *= 0.985f
                        }
                    }
                }
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
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
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
            canvas.drawCircle(g.x, g.y, grainR * 0.72f, sandPaint)
        }

        // Glass on top, so grains read as inside it.
        glassPaint.color = theme.rim
        glassPaint.strokeWidth = grainR * 0.5f
        var i = 4 // skip the top lid, drawn as a frame cap below
        while (i < walls.size - 4) {
            canvas.drawLine(walls[i], walls[i + 1], walls[i + 2], walls[i + 3], glassPaint)
            i += 4
        }
        framePaint.color = theme.tick
        framePaint.strokeWidth = grainR * 0.9f
        canvas.drawLine(cx - halfW * 1.15f, cy - halfH, cx + halfW * 1.15f, cy - halfH, framePaint)
        canvas.drawLine(cx - halfW * 1.15f, cy + halfH, cx + halfW * 1.15f, cy + halfH, framePaint)

        postInvalidateOnAnimation()
    }

    companion object {
        private const val MAX_GRAINS = 220
        private const val GRAVITY = 2400f
    }
}
