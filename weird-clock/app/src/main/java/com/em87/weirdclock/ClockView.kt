package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Analog clock face with unusual options.
 *
 * Interaction is built on a single "virtual time offset": winding any hand
 * shifts the whole clock's displayed time, so the other hands follow
 * proportionally, like real gears. Releasing starts a bouncy spring that
 * unwinds the offset back to zero — the hands spin back exactly as far as
 * they were wound. A hard shake knocks the hands off the axis; they tumble
 * inside the dial with simple physics until dragged back to the center.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NumeralStyle { NONE, ARABIC, ROMAN }
    enum class DateFormatStyle { NUMBER, TEXT, ROMAN }
    enum class FastHandMode { NONE, TENTHS, DECIMAL_MINUTE }
    private enum class Hand { HOUR, MINUTE, SECOND }

    /** Sounds triggered by interacting with the hands. */
    interface SoundListener {
        /** The second hand crossed a second boundary while being wound. */
        fun onTickCrossed()
        /** Any hand crossed an hour boundary while being wound. */
        fun onHourCrossed()
        /** The hour hand crossed a day (calendar) boundary while being wound. */
        fun onDayCrossed()
        /** A fallen hand was placed back on the axis. */
        fun onHandMounted()
    }

    var soundListener: SoundListener? = null

    var hoursOnDial = 12
        set(value) { field = value.coerceIn(2, 24); invalidate() }
    var showSecondHand = true
        set(value) { field = value; invalidate() }
    var smoothSeconds = false
        set(value) { field = value; invalidate() }
    var fastHand = FastHandMode.NONE
        set(value) { field = value; invalidate() }
    var mirrored = false
        set(value) { field = value; invalidate() }
    var numeralStyle = NumeralStyle.ARABIC
        set(value) { field = value; invalidate() }
    var showDate = false
        set(value) { field = value; invalidate() }
    var dateFormatStyle = DateFormatStyle.NUMBER
        set(value) { field = value; invalidate() }
    var touchHandsEnabled = true
    var pinchZoomEnabled = true
    var shakeDropEnabled = true
    var dialScale = 1f
        set(value) { field = value.coerceIn(MIN_SCALE, MAX_SCALE); invalidate() }
    var onDialScaleChanged: ((Float) -> Unit)? = null
    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; applyTheme(value); invalidate() }

    // ------------------------------------------------------------- painting

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hourHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val minuteHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val secondHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fastHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fastTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val numberDateFormat by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    private val textDateFormat by lazy { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    private val cal: Calendar = Calendar.getInstance()

    init {
        applyTheme(theme)
    }

    private fun applyTheme(t: ClockTheme) {
        facePaint.color = t.face
        rimPaint.color = t.rim
        tickPaint.color = t.tick
        minorTickPaint.color = t.minorTick
        numeralPaint.color = t.numeral
        datePaint.color = t.numeral
        datePaint.alpha = 210
        hourHandPaint.color = t.hourHand
        minuteHandPaint.color = t.minuteHand
        secondHandPaint.color = t.secondHand
        fastHandPaint.color = t.decimal
        fastHandPaint.alpha = 200
        fastTickPaint.color = t.decimal
        fastTickPaint.alpha = 140
        centerDotPaint.color = t.centerDot
    }

    // --------------------------------------------------- virtual time state

    /** Seconds added to real time by winding the hands. Zero at rest. */
    private var visualOffsetSeconds = 0.0
    private var draggedHand: Hand? = null

    /** Which hand's sound profile applies while winding or springing back. */
    private var activeSoundHand: Hand? = null
    private var lastTouchDeg = 0f
    private var dragStartOffset = 0.0
    private var dragAccumDeg = 0.0
    private var spring: SpringAnimation? = null
    private var lastTickSoundAt = 0L
    private var lastBellSoundAt = 0L
    private var lastDaySoundAt = 0L

    // ----------------------------------------------------- fallen-hand state

    private class FallingBody(
        val hand: Hand,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var angleDeg: Float,
        var angVel: Float,
        val halfLen: Float,
        val strokeWidth: Float
    )

    private val fallenBodies = ArrayList<FallingBody>()
    private var carriedBody: FallingBody? = null
    private var lastPhysicsAt = 0L
    private var lastShakeAt = 0L
    private var lastCarryX = 0f
    private var lastCarryY = 0f
    private var lastCarryAt = 0L

    private var sensorManager: SensorManager? = null
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!shakeDropEnabled) return
            val (x, y, z) = event.values
            val g = sqrt(x * x + y * y + z * z)
            val now = SystemClock.uptimeMillis()
            if (g > SHAKE_THRESHOLD && now - lastShakeAt > 1500) {
                lastShakeAt = now
                dropHands()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // -------------------------------------------------------------- ticking

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            val delay = if (isAnimating() || fastHand != FastHandMode.NONE ||
                (smoothSeconds && showSecondHand)
            ) {
                16L
            } else {
                1000L - (System.currentTimeMillis() % 1000L)
            }
            postDelayed(this, delay)
        }
    }

    private fun isAnimating(): Boolean =
        draggedHand != null || spring?.isRunning == true || fallenBodies.isNotEmpty()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        sensorManager?.unregisterListener(shakeListener)
        spring?.cancel()
        spring = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!pinchZoomEnabled) return false
                dialScale *= detector.scaleFactor
                onDialScaleChanged?.invoke(dialScale)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!pinchZoomEnabled) return false
                dialScale = 1f
                onDialScaleChanged?.invoke(dialScale)
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pinchZoomEnabled) {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        }
        if (scaleDetector.isInProgress) {
            releaseDraggedHand()
            releaseCarriedBody()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!grabFallenBodyNear(event.x, event.y) && touchHandsEnabled) {
                    grabHandNear(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                carriedBody?.let { moveCarriedBody(it, event.x, event.y) }
                    ?: dragTo(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                releaseDraggedHand()
                releaseCarriedBody()
            }
        }
        return true
    }

    private fun touchAngleDeg(x: Float, y: Float): Float {
        val dx = x - width / 2f
        val dy = y - height / 2f
        var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        if (mirrored) deg = -deg
        return deg
    }

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

    private fun grabHandNear(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (hypot(x - cx, y - cy) < r * 0.12f) return
        val a = computeAngles()

        // Hit test against the whole hand line, not just the tip, with a
        // generous finger-sized threshold. The thin second hand gets an even
        // wider margin and first pick, since it's the hardest to catch.
        val threshold = max(r * 0.10f, 44f * resources.displayMetrics.density)
        var chosen: Hand? = null
        if (showSecondHand && !isFallen(Hand.SECOND) &&
            distanceToHand(Hand.SECOND, a, x, y, cx, cy, r) < threshold * 1.4f
        ) {
            chosen = Hand.SECOND
        } else {
            var bestDist = threshold
            for (hand in arrayOf(Hand.MINUTE, Hand.HOUR)) {
                if (isFallen(hand)) continue
                val d = distanceToHand(hand, a, x, y, cx, cy, r)
                if (d < bestDist) {
                    bestDist = d
                    chosen = hand
                }
            }
        }

        chosen?.let {
            spring?.cancel()
            spring = null
            draggedHand = it
            activeSoundHand = it
            dragStartOffset = visualOffsetSeconds
            dragAccumDeg = 0.0
            lastTouchDeg = touchAngleDeg(x, y)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun distanceToHand(
        hand: Hand, a: Angles,
        x: Float, y: Float,
        cx: Float, cy: Float, r: Float
    ): Float {
        val tip = pointAt(cx, cy, angleOf(hand, a), r * lengthOf(hand))
        return distanceToSegment(x, y, cx, cy, tip.x, tip.y)
    }

    private fun dragTo(x: Float, y: Float) {
        val hand = draggedHand ?: return
        val cx = width / 2f
        val cy = height / 2f
        if (hypot(x - cx, y - cy) < dialRadius() * 0.05f) return
        val touchDeg = touchAngleDeg(x, y)
        var delta = (touchDeg - lastTouchDeg) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        dragAccumDeg += delta
        lastTouchDeg = touchDeg
        setOffset(dragStartOffset + dragAccumDeg / 360.0 * secondsPerRevolution(hand))
    }

    private fun secondsPerRevolution(hand: Hand): Double = when (hand) {
        Hand.SECOND -> 60.0
        Hand.MINUTE -> 3600.0
        Hand.HOUR -> hoursOnDial * 3600.0
    }

    private fun releaseDraggedHand() {
        if (draggedHand == null) return
        draggedHand = null
        if (visualOffsetSeconds == 0.0) {
            activeSoundHand = null
            return
        }
        // Real damped-spring physics: the offset oscillates around zero, so
        // the hands unwind the same number of turns they were wound, overshoot
        // and wobble before settling on the true time.
        val holder = FloatValueHolder(visualOffsetSeconds.toFloat())
        spring = SpringAnimation(holder).apply {
            setSpring(
                SpringForce(0f)
                    .setStiffness(38f)
                    .setDampingRatio(0.30f)
            )
            minimumVisibleChange = 0.02f
            addUpdateListener { _, value, _ -> setOffset(value.toDouble()) }
            addEndListener { _, _, _, _ ->
                visualOffsetSeconds = 0.0
                activeSoundHand = null
                spring = null
                invalidate()
            }
            start()
        }
    }

    /** Applies a new offset and fires winding sounds for boundaries crossed. */
    private fun setOffset(newOffset: Double) {
        val base = System.currentTimeMillis() / 1000.0
        val before = base + visualOffsetSeconds
        val after = base + newOffset
        visualOffsetSeconds = newOffset
        emitCrossings(before, after)
        invalidate()
    }

    private fun emitCrossings(before: Double, after: Double) {
        val hand = activeSoundHand ?: return
        val listener = soundListener ?: return
        val now = SystemClock.uptimeMillis()
        when (hand) {
            Hand.SECOND -> {
                if (floor(before) != floor(after) && now - lastTickSoundAt > 45) {
                    lastTickSoundAt = now
                    listener.onTickCrossed()
                }
            }
            Hand.MINUTE, Hand.HOUR -> {
                if (floor(before / 3600.0) != floor(after / 3600.0) &&
                    now - lastBellSoundAt > 150
                ) {
                    lastBellSoundAt = now
                    listener.onHourCrossed()
                }
                if (hand == Hand.HOUR &&
                    floor(before / 86400.0) != floor(after / 86400.0) &&
                    now - lastDaySoundAt > 450
                ) {
                    lastDaySoundAt = now
                    listener.onDayCrossed()
                }
            }
        }
    }

    // -------------------------------------------------- fallen-hand physics

    private fun isFallen(hand: Hand): Boolean = fallenBodies.any { it.hand == hand }

    private fun dropHands() {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (r <= 0f) return
        // Winding state makes no sense once the hands are off the axis.
        spring?.cancel()
        spring = null
        draggedHand = null
        activeSoundHand = null
        visualOffsetSeconds = 0.0

        val a = computeAngles()
        val drops = ArrayList<Hand>(3)
        drops.add(Hand.HOUR)
        drops.add(Hand.MINUTE)
        if (showSecondHand) drops.add(Hand.SECOND)
        for (hand in drops) {
            if (isFallen(hand)) continue
            val len = lengthOf(hand) * r
            val tail = tailOf(hand) * r
            val angle = angleOf(hand, a)
            val visualAngle = if (mirrored) -angle else angle
            val rad = Math.toRadians(visualAngle.toDouble())
            val mid = (len - tail) / 2f
            fallenBodies.add(
                FallingBody(
                    hand = hand,
                    x = cx + sin(rad).toFloat() * mid,
                    y = cy - cos(rad).toFloat() * mid,
                    vx = Random.nextFloat() * 500f - 250f,
                    vy = -Random.nextFloat() * 350f,
                    angleDeg = visualAngle,
                    angVel = Random.nextFloat() * 420f - 210f,
                    halfLen = (len + tail) / 2f,
                    strokeWidth = widthOf(hand) * r * 2f
                )
            )
        }
        if (fallenBodies.isNotEmpty()) {
            lastPhysicsAt = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun stepPhysics() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastPhysicsAt).coerceIn(0, 48)) / 1000f
        lastPhysicsAt = now
        if (dt <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val rIn = dialRadius() * 0.96f
        for (b in fallenBodies) {
            if (b === carriedBody) continue
            b.vy += 2600f * dt
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
            b.angVel *= 0.995f
        }
    }

    private fun grabFallenBodyNear(x: Float, y: Float): Boolean {
        if (fallenBodies.isEmpty()) return false
        val threshold = 44f * resources.displayMetrics.density
        for (b in fallenBodies) {
            val rad = Math.toRadians(b.angleDeg.toDouble())
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()
            val d = distanceToSegment(
                x, y,
                b.x - dirX * b.halfLen, b.y - dirY * b.halfLen,
                b.x + dirX * b.halfLen, b.y + dirY * b.halfLen
            )
            if (d < threshold) {
                carriedBody = b
                lastCarryX = x
                lastCarryY = y
                lastCarryAt = SystemClock.uptimeMillis()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
        }
        return false
    }

    private fun moveCarriedBody(b: FallingBody, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastCarryAt).coerceAtLeast(1) / 1000f
        b.vx = (x - lastCarryX) / dt * 0.4f
        b.vy = (y - lastCarryY) / dt * 0.4f
        lastCarryX = x
        lastCarryY = y
        lastCarryAt = now
        b.x = x
        b.y = y
        b.angVel *= 0.9f
        val cx = width / 2f
        val cy = height / 2f
        if (hypot(x - cx, y - cy) < dialRadius() * 0.18f) {
            // Close enough to the axis: the hand clicks back into place.
            fallenBodies.remove(b)
            carriedBody = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            soundListener?.onHandMounted()
        }
        invalidate()
    }

    private fun releaseCarriedBody() {
        carriedBody = null
    }

    // ----------------------------------------------------------------- time

    private class Angles(
        val hour: Float,
        val minute: Float,
        val second: Float,
        val fast: Float
    )

    private fun computeAngles(): Angles {
        val nowMs = System.currentTimeMillis() + (visualOffsetSeconds * 1000.0).toLong()
        cal.timeInMillis = nowMs
        val useMs = smoothSeconds || fastHand != FastHandMode.NONE || isAnimating()
        val ms = if (useMs) cal.get(Calendar.MILLISECOND) else 0
        val seconds = cal.get(Calendar.SECOND) + ms / 1000f
        val minutes = cal.get(Calendar.MINUTE) + seconds / 60f
        val hours = cal.get(Calendar.HOUR_OF_DAY) + minutes / 60f
        val n = hoursOnDial
        val fast = when (fastHand) {
            FastHandMode.NONE -> 0f
            FastHandMode.TENTHS -> cal.get(Calendar.MILLISECOND) / 1000f * 360f
            FastHandMode.DECIMAL_MINUTE -> {
                val secondOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600.0 +
                    cal.get(Calendar.MINUTE) * 60.0 +
                    cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
                ((secondOfDay / 86.4) % 1.0 * 360.0).toFloat()
            }
        }
        return Angles(
            hour = (hours % n) / n * 360f,
            minute = minutes / 60f * 360f,
            second = seconds / 60f * 360f,
            fast = fast
        )
    }

    private fun angleOf(hand: Hand, a: Angles): Float = when (hand) {
        Hand.HOUR -> a.hour
        Hand.MINUTE -> a.minute
        Hand.SECOND -> a.second
    }

    private fun lengthOf(hand: Hand): Float = when (hand) {
        Hand.HOUR -> HOUR_LEN
        Hand.MINUTE -> MINUTE_LEN
        Hand.SECOND -> SECOND_LEN
    }

    private fun tailOf(hand: Hand): Float = when (hand) {
        Hand.HOUR -> 0.10f
        Hand.MINUTE -> 0.12f
        Hand.SECOND -> 0.18f
    }

    private fun widthOf(hand: Hand): Float = when (hand) {
        Hand.HOUR -> 0.045f
        Hand.MINUTE -> 0.03f
        Hand.SECOND -> 0.012f
    }

    private fun paintOf(hand: Hand): Paint = when (hand) {
        Hand.HOUR -> hourHandPaint
        Hand.MINUTE -> minuteHandPaint
        Hand.SECOND -> secondHandPaint
    }

    private fun dialRadius(): Float = min(width, height) / 2f * 0.92f * dialScale

    // ----------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fallenBodies.isNotEmpty()) stepPhysics()

        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()

        rimPaint.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, r, facePaint)
        canvas.drawCircle(cx, cy, r, rimPaint)

        drawTicks(canvas, cx, cy, r)
        drawNumerals(canvas, cx, cy, r)
        if (showDate) drawDate(canvas, cx, cy, r)

        val a = computeAngles()

        if (fastHand != FastHandMode.NONE) {
            for (i in 0 until 10) {
                val angle = i / 10f * 360f
                fastTickPaint.strokeWidth = r * 0.012f
                val from = pointAt(cx, cy, angle, r * 0.30f)
                val to = pointAt(cx, cy, angle, r * 0.36f)
                canvas.drawLine(from.x, from.y, to.x, to.y, fastTickPaint)
            }
            drawHand(canvas, cx, cy, a.fast, r * 0.30f, r * 0.05f, r * 0.008f, fastHandPaint)
        }

        for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
            if (hand == Hand.SECOND && !showSecondHand) continue
            if (isFallen(hand)) continue
            drawHand(
                canvas, cx, cy,
                angleOf(hand, a),
                r * lengthOf(hand),
                r * tailOf(hand),
                r * widthOf(hand),
                paintOf(hand)
            )
        }

        for (b in fallenBodies) {
            val rad = Math.toRadians(b.angleDeg.toDouble())
            val dirX = sin(rad).toFloat()
            val dirY = -cos(rad).toFloat()
            val paint = paintOf(b.hand)
            paint.strokeWidth = b.strokeWidth
            canvas.drawLine(
                b.x - dirX * b.halfLen, b.y - dirY * b.halfLen,
                b.x + dirX * b.halfLen, b.y + dirY * b.halfLen,
                paint
            )
        }

        canvas.drawCircle(cx, cy, r * 0.035f, centerDotPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 60 minute ticks on the outer edge; they double as hour marks only
        // on the classic 12-hour dial, where the divisions line up.
        for (i in 0 until 60) {
            val angle = i / 60f * 360f
            val isMajor = hoursOnDial == 12 && i % 5 == 0
            val paint = if (isMajor) tickPaint else minorTickPaint
            paint.strokeWidth = if (isMajor) r * 0.018f else r * 0.008f
            val outerLen = if (isMajor) r * 0.08f else r * 0.045f
            val from = pointAt(cx, cy, angle, r * 0.97f - outerLen)
            val to = pointAt(cx, cy, angle, r * 0.97f)
            canvas.drawLine(from.x, from.y, to.x, to.y, paint)
        }
        if (hoursOnDial != 12) {
            for (i in 0 until hoursOnDial) {
                val angle = i.toFloat() / hoursOnDial * 360f
                tickPaint.strokeWidth = r * 0.018f
                val from = pointAt(cx, cy, angle, r * 0.80f)
                val to = pointAt(cx, cy, angle, r * 0.87f)
                canvas.drawLine(from.x, from.y, to.x, to.y, tickPaint)
            }
        }
    }

    private fun drawNumerals(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (numeralStyle == NumeralStyle.NONE) return
        val n = hoursOnDial
        val crowded = n > 12
        numeralPaint.textSize = if (crowded) r * 0.11f else r * 0.16f
        val radius = if (n == 12) r * 0.76f else r * 0.68f
        val step = if (crowded) 2 else 1
        var h = step
        while (h <= n) {
            drawNumeral(canvas, cx, cy, radius, h, n)
            h += step
        }
        // With an odd hour count and step 2 the top numeral would be skipped.
        if (n % step != 0) drawNumeral(canvas, cx, cy, radius, n, n)
    }

    private fun drawNumeral(canvas: Canvas, cx: Float, cy: Float, radius: Float, hour: Int, n: Int) {
        val angle = hour.toFloat() / n * 360f
        val pos = pointAt(cx, cy, angle, radius)
        val label = if (numeralStyle == NumeralStyle.ROMAN) toRoman(hour) else hour.toString()
        val baseline = pos.y - (numeralPaint.ascent() + numeralPaint.descent()) / 2f
        canvas.drawText(label, pos.x, baseline, numeralPaint)
    }

    private fun drawDate(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val now = Calendar.getInstance()
        val text = when (dateFormatStyle) {
            DateFormatStyle.NUMBER -> numberDateFormat.format(Date())
            DateFormatStyle.TEXT -> textDateFormat.format(Date())
            DateFormatStyle.ROMAN -> {
                val day = toRoman(now.get(Calendar.DAY_OF_MONTH))
                val month = toRoman(now.get(Calendar.MONTH) + 1)
                val year = toRoman(now.get(Calendar.YEAR))
                "$day·$month·$year"
            }
        }
        datePaint.textSize = r * 0.085f
        val baseline = cy - r * 0.42f - (datePaint.ascent() + datePaint.descent()) / 2f
        canvas.drawText(text, cx, baseline, datePaint)
    }

    private fun drawHand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        length: Float,
        tailLength: Float,
        width: Float,
        paint: Paint
    ) {
        paint.strokeWidth = width * 2f
        val tip = pointAt(cx, cy, angleDeg, length)
        val tail = pointAt(cx, cy, angleDeg + 180f, tailLength)
        canvas.drawLine(tail.x, tail.y, tip.x, tip.y, paint)
    }

    /**
     * Point at [distance] from the center, at [angleDeg] measured clockwise
     * from 12 o'clock. In mirrored mode the horizontal component is negated,
     * which makes the whole clock run counterclockwise.
     */
    private fun pointAt(cx: Float, cy: Float, angleDeg: Float, distance: Float): PointF {
        val a = Math.toRadians(angleDeg.toDouble())
        val sx = sin(a).toFloat() * if (mirrored) -1f else 1f
        return PointF(cx + sx * distance, cy - cos(a).toFloat() * distance)
    }

    private fun toRoman(value: Int): String {
        val numerals = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
        )
        var remainder = value
        val sb = StringBuilder()
        for ((weight, symbol) in numerals) {
            while (remainder >= weight) {
                sb.append(symbol)
                remainder -= weight
            }
        }
        return sb.toString()
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]

    companion object {
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 1f
        private const val SHAKE_THRESHOLD = 30f // m/s², about 3 g
        private const val HOUR_LEN = 0.52f
        private const val MINUTE_LEN = 0.74f
        private const val SECOND_LEN = 0.82f
        private val END_SIDES = floatArrayOf(1f, -1f)
    }
}
