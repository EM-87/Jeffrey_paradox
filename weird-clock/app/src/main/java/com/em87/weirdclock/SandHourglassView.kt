package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
 * S0: the countdown as falling sand, one pixel at a time.
 *
 * The sand is not a crowd of little bodies with velocities and collisions —
 * it is a grid, and each cell is either sand or it is not. Every frame each
 * grain looks at the cell gravity points to, and at the two beside it, and
 * takes the first that is free. That single rule is the whole simulation:
 * heaps hold a slope, streams pour, and a pile shaken sideways slumps,
 * without anyone computing a force. It also cannot explode, tunnel through
 * the glass, or jitter forever, which the bodies-and-forces version could.
 *
 * The glass is baked into the grid as wall cells, so containment is not a
 * calculation at all — there is simply nowhere else to go. The neck is a
 * turnstile: a grain may only step from the upstream half into the
 * downstream one if the clock has already spent it. Tilt the phone so the
 * sand can't reach the neck and the countdown stops with it.
 */
class SandHourglassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) {
            field = value
            rebuildPalette()
            invalidate()
        }

    /** Shared dial zoom: the glass scales with every other dial. */
    var glassScale = 1f
        set(value) {
            val next = value.coerceIn(ClockView.MIN_SCALE, ClockView.MAX_SCALE)
            if (next != field) {
                field = next
                if (rebuildGeometry()) refill()
                invalidate()
            }
        }
    var onScaleChanged: ((Float) -> Unit)? = null

    /** Reports whether gravity currently lets sand reach the neck. */
    var onFlowBlocked: ((Boolean) -> Unit)? = null

    /** Freeze: the grid stops updating and the sand hangs where it is. */
    var frozen = false
        set(value) {
            field = value
            invalidate()
        }

    // ------------------------------------------------------------- time link

    private var totalMs = 300_000L
    private var remainingMs = 300_000L

    /**
     * How much work the phone is asked to do. On a grid this is resolution:
     * more means finer sand and more cells to step every frame.
     */
    var maxGrains = 260
        set(value) {
            val next = value.coerceIn(40, 900)
            if (next != field) {
                field = next
                if (rebuildGeometry()) refill()
            }
        }

    fun setTime(total: Long, remaining: Long) {
        val totalChanged = total != totalMs
        val previous = remainingMs
        totalMs = total.coerceAtLeast(1000L)
        remainingMs = remaining.coerceIn(0L, totalMs)
        // A different countdown is a different hourglass, and one that gained
        // time was reset — either way the glass is turned over rather than
        // reconciled, since sand does not climb back up on its own.
        if (totalChanged || remainingMs > previous + totalMs / 50L) refill()
        invalidate()
    }

    // ---------------------------------------------------------------- grid

    private var cols = 0
    private var rows = 0
    private var cells = ByteArray(0)
    private var moved = BooleanArray(0)

    /** A stable speckle per cell, so the sand has grain instead of flatness. */
    private var speckle = ByteArray(0)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private val palette = IntArray(4)

    private var cellSize = 4f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var neckRow = 0
    private var sandTotal = 0

    private fun index(i: Int, j: Int) = j * cols + i

    // Glass geometry (view coordinates).
    private var cx = 0f
    private var cy = 0f
    private var bulbHalf = 0f
    private var halfH = 0f
    private var neckHalf = 0f
    private val glassPath = Path()
    private val sandSrc = Rect()
    private val sandDst = RectF()

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** No smoothing anywhere: the whole point is that the grains are pixels. */
    private val sandPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = false
    }

    // ------------------------------------------------------------- gravity

    private var gravityX = 0f
    private var gravityY = 1f
    private var lowX = 0f
    private var lowY = 9.81f
    private var sensorManager: SensorManager? = null
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Heavier smoothing than the dial's: sand should not twitch with
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
            gravityX = gx
            gravityY = gy
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // Finger stir state, in cells.
    private var stirActive = false
    private var stirX = 0f
    private var stirY = 0f
    private var stirDx = 0f
    private var stirDy = 0f

    // Flow bookkeeping.
    private var lastUpstream = -1
    private var lastFlowChangeAt = 0L
    private var reportedBlocked = false
    private var scanFlip = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        sensorManager?.unregisterListener(sensorListener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (rebuildGeometry()) refill()
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

    /**
     * Returns true when the grid itself changed shape and the sand has to be
     * laid out again. Zooming does not: the grid is defined in cells, and
     * every length in it scales together, so a pinch only moves where those
     * cells are drawn — the pile survives untouched.
     */
    private fun rebuildGeometry(): Boolean {
        if (width == 0 || height == 0) return false
        cx = width / 2f
        cy = height / 2f
        halfH = min(height * 0.40f, width * 0.72f) * glassScale
        bulbHalf = halfH * 0.58f

        val wantCols = (sqrt(maxGrains.toFloat()) * 4.2f).roundToInt().coerceIn(28, 150)
        val wantRows = (wantCols / 0.58f).roundToInt().coerceAtLeast(8)
        val structural = wantCols != cols || wantRows != rows || cells.isEmpty()
        cols = wantCols
        rows = wantRows
        cellSize = (bulbHalf * 2f) / cols
        gridLeft = cx - bulbHalf
        gridTop = cy - halfH
        neckRow = rows / 2
        // Wide enough that a stream actually fits through it, whatever the
        // resolution: about four cells across.
        neckHalf = cellSize * 2f
        sandSrc.set(0, 0, cols, rows)
        sandDst.set(gridLeft, gridTop, gridLeft + cols * cellSize, gridTop + rows * cellSize)

        if (structural) {
            cells = ByteArray(cols * rows)
            moved = BooleanArray(cols * rows)
            speckle = ByteArray(cols * rows) { Random.nextInt(4).toByte() }
            pixels = IntArray(cols * rows)
            bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)

            // The glass, baked into the grid. Everything outside is wall, so
            // no grain has anywhere to escape to.
            for (j in 0 until rows) {
                val y = gridTop + (j + 0.5f) * cellSize
                val limit = halfWidthAt(y) - cellSize * 0.5f
                for (i in 0 until cols) {
                    val x = gridLeft + (i + 0.5f) * cellSize
                    if (abs(x - cx) > limit) cells[index(i, j)] = WALL
                }
            }
        }

        glassPath.reset()
        val steps = 36
        for (i in 0..steps) {
            val y = cy - halfH + (2f * halfH) * i / steps
            val hw = halfWidthAt(y)
            if (i == 0) glassPath.moveTo(cx - hw, y) else glassPath.lineTo(cx - hw, y)
        }
        for (i in steps downTo 0) {
            val y = cy - halfH + (2f * halfH) * i / steps
            glassPath.lineTo(cx + halfWidthAt(y), y)
        }
        glassPath.close()

        rebuildPalette()
        return structural
    }

    /** Four shades around the sand colour, picked per cell and never changing. */
    private fun rebuildPalette() {
        val base = theme.decimal
        for (k in 0 until 4) {
            val f = 0.86f + k * 0.09f
            palette[k] = Color.argb(
                255,
                (Color.red(base) * f).toInt().coerceIn(0, 255),
                (Color.green(base) * f).toInt().coerceIn(0, 255),
                (Color.blue(base) * f).toInt().coerceIn(0, 255)
            )
        }
    }

    /**
     * Turns the glass over: the sand is laid out again, piled at the bottom
     * of each half, with as much still to fall as the clock has left.
     */
    private fun refill() {
        if (cols == 0 || rows == 0) return
        for (k in cells.indices) if (cells[k] == SAND) cells[k] = EMPTY

        // One bulb's worth, a little short of the brim so a full glass still
        // has somewhere to put the last grains.
        var capacity = 0
        for (j in 0 until neckRow) {
            for (i in 0 until cols) if (cells[index(i, j)] == EMPTY) capacity++
        }
        sandTotal = (capacity * 0.82f).roundToInt().coerceAtLeast(1)
        val above = (sandTotal * remainingMs.toDouble() / totalMs).roundToInt()
            .coerceIn(0, sandTotal)

        var left = above
        for (j in neckRow - 1 downTo 0) {
            if (left <= 0) break
            left = fillRow(j, left)
        }
        left = sandTotal - above
        for (j in rows - 1 downTo neckRow + 1) {
            if (left <= 0) break
            left = fillRow(j, left)
        }
        lastUpstream = -1
    }

    /** Fills one row from the middle outwards, returning what is left over. */
    private fun fillRow(j: Int, budget: Int): Int {
        var left = budget
        val mid = cols / 2
        for (step in 0 until cols) {
            if (left <= 0) break
            val i = if (step % 2 == 0) mid + step / 2 else mid - 1 - step / 2
            if (i !in 0 until cols) continue
            val k = index(i, j)
            if (cells[k] == EMPTY) {
                cells[k] = SAND
                left--
            }
        }
        return left
    }

    // -------------------------------------------------------------- the rule

    private fun step() {
        if (frozen || cols == 0) return

        // Which way is down, as a step on the grid: one of the eight
        // neighbours, so a tilted phone really does pour sideways.
        val mag = hypot(gravityX, gravityY)
        if (mag < 0.05f) return
        var dx = 0
        var dy = 0
        if (gravityX > mag * 0.4f) dx = 1 else if (gravityX < -mag * 0.4f) dx = -1
        if (gravityY > mag * 0.4f) dy = 1 else if (gravityY < -mag * 0.4f) dy = -1
        if (dx == 0 && dy == 0) dy = 1

        // The turnstile at the waist. Sand only crosses into the half it is
        // falling towards, and only as fast as the clock spends it.
        gateSide = if (gravityY >= 0f) 1 else -1
        val verticalFlow = abs(gravityY) > mag * 0.45f
        var upstream = 0
        for (j in 0 until rows) {
            val side = sideOf(j)
            if (side == 0 || side == gateSide) continue
            for (i in 0 until cols) if (cells[index(i, j)] == SAND) upstream++
        }
        val target = (sandTotal * remainingMs.toDouble() / totalMs).roundToInt()
        gateBudget = if (verticalFlow) (upstream - target).coerceAtLeast(0) else 0

        // Physical time: sand that should be flowing but cannot reports it,
        // and the countdown stops until the glass is upright again.
        val now = SystemClock.uptimeMillis()
        if (upstream != lastUpstream) {
            lastUpstream = upstream
            lastFlowChangeAt = now
        }
        val blocked = upstream > target + 1 && !verticalFlow && now - lastFlowChangeAt > 800L
        if (blocked != reportedBlocked) {
            reportedBlocked = blocked
            onFlowBlocked?.invoke(blocked)
        }

        // Diagonals: the two neighbours either side of straight-down. Sand
        // that can't drop tries to slide, which is what gives a heap its
        // slope — always 45 degrees, exactly like real sand at rest.
        val px = -dy
        val py = dx
        val ax = (dx + px).coerceIn(-1, 1)
        val ay = (dy + py).coerceIn(-1, 1)
        val bx = (dx - px).coerceIn(-1, 1)
        val by = (dy - py).coerceIn(-1, 1)

        repeat(PASSES) {
            java.util.Arrays.fill(moved, false)
            if (stirActive) stir(dx, dy)
            scanFlip = !scanFlip
            // Scanned from the downhill end, so the grain in front moves out
            // of the way before the one behind asks to.
            val jRange = if (dy >= 0) (rows - 1) downTo 0 else 0 until rows
            for (j in jRange) {
                val iRange = if (scanFlip) 0 until cols else (cols - 1) downTo 0
                for (i in iRange) {
                    val k = index(i, j)
                    if (cells[k] != SAND || moved[k]) continue
                    if (tryMove(i, j, dx, dy)) continue
                    // A grain walled in on all three sides is asleep, and
                    // most of a settled pile is: worth the two lookups to
                    // skip the dice roll for every one of them.
                    if (!isFree(i + ax, j + ay) && !isFree(i + bx, j + by)) continue
                    // Which diagonal first is a coin toss, or the sand would
                    // drift steadily to one side.
                    if (Random.nextFloat() < 0.75f) {
                        if (Random.nextBoolean()) {
                            if (tryMove(i, j, ax, ay)) continue
                            tryMove(i, j, bx, by)
                        } else {
                            if (tryMove(i, j, bx, by)) continue
                            tryMove(i, j, ax, ay)
                        }
                    }
                }
            }
        }
    }

    private var gateBudget = 0
    private var gateSide = 1

    private fun isFree(i: Int, j: Int): Boolean =
        i >= 0 && i < cols && j >= 0 && j < rows && cells[index(i, j)] == EMPTY

    /** -1 above the waist, +1 below it, 0 in the neck row itself. */
    private fun sideOf(j: Int): Int = when {
        j < neckRow -> -1
        j > neckRow -> 1
        else -> 0
    }

    /**
     * One grain, one step — if the cell it wants is inside the glass, empty,
     * and not on the far side of a closed turnstile.
     */
    private fun tryMove(i: Int, j: Int, dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return false
        val ni = i + dx
        val nj = j + dy
        if (ni < 0 || ni >= cols || nj < 0 || nj >= rows) return false
        val to = index(ni, nj)
        if (cells[to] != EMPTY) return false
        // Entering the downstream half is the one move the clock controls,
        // and nothing goes back up the neck the other way.
        val from = sideOf(j)
        val to2 = sideOf(nj)
        if (to2 != from) {
            if (to2 == gateSide) {
                if (gateBudget <= 0) return false
                gateBudget--
            } else if (from == gateSide) {
                return false
            }
        }
        cells[index(i, j)] = EMPTY
        cells[to] = SAND
        moved[to] = true
        return true
    }

    /**
     * The finger doesn't apply a force — there are no forces here. It simply
     * picks grains up and puts them down a little further along, which from
     * the outside is indistinguishable from shoving them.
     */
    private fun stir(dx: Int, dy: Int) {
        val reach = (cols * 0.07f).coerceIn(3f, 9f)
        val ci = ((stirX - gridLeft) / cellSize).toInt()
        val cj = ((stirY - gridTop) / cellSize).toInt()
        val r = (reach + 2f).toInt()
        val sx = if (abs(stirDx) < 0.35f) 0 else if (stirDx > 0) 1 else -1
        val sy = if (abs(stirDy) < 0.35f) 0 else if (stirDy > 0) 1 else -1
        for (jj in (cj - r)..(cj + r)) {
            if (jj < 0 || jj >= rows) continue
            for (ii in (ci - r)..(ci + r)) {
                if (ii < 0 || ii >= cols) continue
                val k = index(ii, jj)
                if (cells[k] != SAND || moved[k]) continue
                if (hypot((ii - ci).toFloat(), (jj - cj).toFloat()) > reach) continue
                if (sx != 0 || sy != 0) {
                    if (tryMove(ii, jj, sx, sy)) continue
                }
                // A poke with no direction still unsettles the pile.
                tryMove(ii, jj, Random.nextInt(3) - 1, if (dy >= 0) -1 else 1)
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
                stirDx = 0f
                stirDy = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    stirDx = (event.x - stirX) / cellSize
                    stirDy = (event.y - stirY) / cellSize
                    stirX = event.x
                    stirY = event.y
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
        if (cols == 0 || halfH <= 0f) return
        step()

        val bmp = bitmap
        if (bmp != null) {
            for (k in cells.indices) {
                pixels[k] = if (cells[k] == SAND) palette[speckle[k].toInt()] else 0
            }
            bmp.setPixels(pixels, 0, cols, 0, 0, cols, rows)
            canvas.drawBitmap(bmp, sandSrc, sandDst, sandPaint)
        }

        // Glass on top, so the sand reads as inside it.
        glassPaint.color = theme.rim
        glassPaint.strokeWidth = (cellSize * 1.2f).coerceAtLeast(3f)
        canvas.drawPath(glassPath, glassPaint)

        framePaint.color = theme.tick
        framePaint.strokeWidth = (cellSize * 1.8f).coerceAtLeast(4f)
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
        private const val EMPTY: Byte = 0
        private const val SAND: Byte = 1
        private const val WALL: Byte = 2

        /** Grid steps per frame: how fast a falling grain crosses the glass. */
        private const val PASSES = 3
    }
}
