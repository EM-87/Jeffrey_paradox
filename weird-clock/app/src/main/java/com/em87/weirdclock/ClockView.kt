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
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.ceil
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
 * proportionally, like real gears. While a hand is held the mechanism is
 * frozen. Releasing starts a bouncy spring that unwinds the offset back to
 * zero. A hard knock throws the hands off the axis (in the direction of the
 * blow); further knocks shake the numerals loose too. Fallen pieces tumble
 * under the live accelerometer gravity vector until dragged back into place.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NumeralStyle { NONE, ARABIC, ROMAN }
    enum class DateFormatStyle { NUMBER, TEXT, ROMAN }
    enum class FastHandMode { NONE, TENTHS, DECIMAL_MINUTE }
    private enum class Hand { HOUR, MINUTE, SECOND }
    private enum class BodyKind { HAND, FAST_HAND, NUMERAL }

    /** Sounds triggered by interacting with the clock. */
    interface SoundListener {
        fun onTickCrossed()
        fun onHourCrossed()
        fun onDayCrossed()
        fun onHandMounted()
        fun onExploded()
        fun onCheater()
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
    var timeZone: TimeZone = TimeZone.getDefault()
        set(value) { field = value; cal.timeZone = value; invalidate() }

    /**
     * When set, the dial shows a duration (stopwatch/countdown) instead of
     * the time of day. Hands stay grabbable (winding forward more than one
     * turn stamps CHEATER on the dial); shake-to-drop is disabled.
     */
    var chronoProvider: (() -> Long)? = null
        set(value) {
            field = value
            spring?.cancel()
            spring = null
            draggedHand = null
            activeSoundHand = null
            frozenDisplayMs = null
            chronoFrozenMs = null
            visualOffsetSeconds = 0.0
            invalidate()
        }

    fun isHandGrabbed(): Boolean = draggedHand != null

    fun isSecondHandFallen(): Boolean = isFallen(Hand.SECOND)

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
    private val digitalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
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
    private val cheaterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val numberDateFormat by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    private val textDateFormat by lazy { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    private val cal: Calendar = Calendar.getInstance()

    private var selectedColor = 0

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
        digitalPaint.color = t.decimal
        hourHandPaint.color = t.hourHand
        minuteHandPaint.color = t.minuteHand
        secondHandPaint.color = t.secondHand
        fastHandPaint.color = t.decimal
        fastHandPaint.alpha = 200
        fastTickPaint.color = t.decimal
        fastTickPaint.alpha = 140
        centerDotPaint.color = t.centerDot
        cheaterPaint.color = t.secondHand
        selectedColor = t.secondHand
    }

    // --------------------------------------------------- virtual time state

    /** Seconds added to display time by winding the hands. Zero at rest. */
    private var visualOffsetSeconds = 0.0
    private var draggedHand: Hand? = null

    /** While a hand is held, the mechanism freezes at this display time. */
    private var frozenDisplayMs: Long? = null

    /** Chrono equivalent of the freeze: the held chronograph value. */
    private var chronoFrozenMs: Long? = null
    private var cheaterFlagged = false
    private var cheaterUntil = 0L

    /** Which hand's sound profile applies while winding or springing back. */
    private var activeSoundHand: Hand? = null
    private var lastTouchDeg = 0f
    private var dragStartOffset = 0.0
    private var dragAccumDeg = 0.0
    private var spring: SpringAnimation? = null
    private var lastTickSoundAt = 0L
    private var lastBellSoundAt = 0L
    private var lastDaySoundAt = 0L
    private var exploded = false

    // -------------------------------------------------------- numeral state

    private val selectedHours = HashSet<Int>()
    private val numeralToggleTimes = HashMap<Int, ArrayDeque<Long>>()
    private var tapCandidate = false

    // ----------------------------------------------------- fallen-body state

    private class FallingBody(
        val kind: BodyKind,
        val hand: Hand?,
        val numeralHour: Int,
        val label: String,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var angleDeg: Float,
        var angVel: Float,
        val halfLen: Float,
        val strokeWidth: Float,
        val textSize: Float
    )

    private val fallenBodies = ArrayList<FallingBody>()
    private var carriedBody: FallingBody? = null
    private var lastPhysicsAt = 0L
    private var lastShakeAt = 0L
    private var lastCarryX = 0f
    private var lastCarryY = 0f
    private var lastCarryAt = 0L

    /** Live gravity vector in view coordinates (px/s²), from the sensor. */
    private var gravityX = 0f
    private var gravityY = BASE_GRAVITY
    private var lowPassX = 0f
    private var lowPassY = 9.81f
    private var lowPassZ = 0f

    private var sensorManager: SensorManager? = null
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            lowPassX = lowPassX * 0.8f + ax * 0.2f
            lowPassY = lowPassY * 0.8f + ay * 0.2f
            lowPassZ = lowPassZ * 0.8f + az * 0.2f
            // Device +X points right, +Y up the screen; view +Y is downward.
            gravityX = -lowPassX / 9.81f * BASE_GRAVITY
            gravityY = lowPassY / 9.81f * BASE_GRAVITY

            if (!shakeDropEnabled || chronoProvider != null) return
            val devX = ax - lowPassX
            val devY = ay - lowPassY
            val devZ = az - lowPassZ
            val jolt = sqrt(devX * devX + devY * devY + devZ * devZ)
            val now = SystemClock.uptimeMillis()
            if (jolt > SHAKE_THRESHOLD && now - lastShakeAt > 1200) {
                lastShakeAt = now
                onKnock(-devX, devY)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // -------------------------------------------------------------- ticking

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            val fast = isAnimating() || chronoProvider != null ||
                SystemClock.uptimeMillis() < cheaterUntil ||
                fastHand != FastHandMode.NONE || (smoothSeconds && showSecondHand)
            val delay = if (fast) 16L else 1000L - (TimeKeeper.nowMs() % 1000L).coerceIn(0L, 999L)
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
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!pinchZoomEnabled) return false
                dialScale = 1f
                onDialScaleChanged?.invoke(dialScale)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (tapCandidate) handleNumeralTap(e.x, e.y)
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A passive dial (e.g. the mini world clock) lets touches through.
        if (!touchHandsEnabled && !pinchZoomEnabled && fallenBodies.isEmpty()) return false
        gestureDetector.onTouchEvent(event)
        if (pinchZoomEnabled) {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                releaseDraggedHand()
                releaseCarriedBody()
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val grabbedBody = grabFallenBodyNear(event.x, event.y)
                if (!grabbedBody && touchHandsEnabled) {
                    grabHandNear(event.x, event.y)
                }
                tapCandidate = !grabbedBody && draggedHand == null
                // Own the gesture while manipulating the mechanism, so a
                // hosting pager doesn't steal it as a horizontal page swipe.
                if (grabbedBody || draggedHand != null) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                carriedBody?.let { moveCarriedBody(it, event.x, event.y) }
                    ?: dragTo(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pinchZoomEnabled) parent?.requestDisallowInterceptTouchEvent(true)
                releaseDraggedHand()
                releaseCarriedBody()
            }
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
            // Freeze the mechanism while the user holds it.
            val provider = chronoProvider
            if (provider != null) chronoFrozenMs = provider() else frozenDisplayMs = displayNowMs()
            dragStartOffset = visualOffsetSeconds
            dragAccumDeg = 0.0
            exploded = false
            cheaterFlagged = false
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
        if (chronoProvider != null) {
            // Winding a chronograph forward more than one turn is cheating.
            if (!cheaterFlagged && dragAccumDeg >= 360.0) {
                cheaterFlagged = true
                cheaterUntil = SystemClock.uptimeMillis() + 3000L
                soundListener?.onCheater()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        } else if (!exploded && kotlin.math.abs(dragAccumDeg) >= 3600.0) {
            // Over-winding by more than 10 full turns blows the mechanism apart.
            exploded = true
            soundListener?.onExploded()
            dropHands(0f, 0f)
            return
        }
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
        // Unfreeze: fold the time that passed while holding into the offset,
        // so the display is continuous and the spring returns to *now*.
        val provider = chronoProvider
        if (provider != null) {
            chronoFrozenMs?.let { frozen ->
                chronoFrozenMs = null
                val displayMs = frozen + (visualOffsetSeconds * 1000.0).toLong()
                visualOffsetSeconds = (displayMs - provider()) / 1000.0
            }
        } else {
            frozenDisplayMs?.let { frozen ->
                frozenDisplayMs = null
                val displayMs = frozen + (visualOffsetSeconds * 1000.0).toLong()
                visualOffsetSeconds = (displayMs - TimeKeeper.nowMs()) / 1000.0
            }
        }
        if (visualOffsetSeconds == 0.0) {
            activeSoundHand = null
            return
        }
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
        val base = (
            chronoProvider?.let { chronoFrozenMs ?: it.invoke() }
                ?: (frozenDisplayMs ?: TimeKeeper.nowMs())
            ) / 1000.0
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

    // ----------------------------------------------------- numeral selection

    private fun numeralRadius(r: Float): Float = if (hoursOnDial == 12) r * 0.76f else r * 0.68f

    private fun numeralTextSize(r: Float): Float = if (hoursOnDial > 12) r * 0.11f else r * 0.16f

    private fun visibleNumeralHours(): List<Int> {
        if (numeralStyle == NumeralStyle.NONE) return emptyList()
        val n = hoursOnDial
        val step = if (n > 12) 2 else 1
        val list = ArrayList<Int>()
        var h = step
        while (h <= n) {
            list.add(h)
            h += step
        }
        if (n % step != 0) list.add(n)
        return list
    }

    private fun numeralLabel(hour: Int): String =
        if (numeralStyle == NumeralStyle.ROMAN) Roman.of(hour) else hour.toString()

    private fun numeralPosition(hour: Int, cx: Float, cy: Float, r: Float): PointF =
        pointAt(cx, cy, hour.toFloat() / hoursOnDial * 360f, numeralRadius(r))

    private fun handleNumeralTap(x: Float, y: Float) {
        if (numeralStyle == NumeralStyle.NONE || chronoProvider != null) return
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val threshold = max(r * 0.10f, 40f * resources.displayMetrics.density)
        for (hour in visibleNumeralHours()) {
            if (isNumeralFallen(hour)) continue
            val pos = numeralPosition(hour, cx, cy, r)
            if (hypot(x - pos.x, y - pos.y) < threshold) {
                if (!selectedHours.remove(hour)) selectedHours.add(hour)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                registerToggleAndMaybeDrop(hour, pos)
                invalidate()
                return
            }
        }
    }

    /** Toggling a numeral frantically shakes it loose from the dial. */
    private fun registerToggleAndMaybeDrop(hour: Int, pos: PointF) {
        val now = SystemClock.uptimeMillis()
        val times = numeralToggleTimes.getOrPut(hour) { ArrayDeque() }
        times.addLast(now)
        while (times.size > 6) times.removeFirst()
        if (times.size >= 6 && now - times.first() < 2500) {
            numeralToggleTimes.remove(hour)
            selectedHours.remove(hour)
            dropNumeral(hour, pos, 0f, -150f)
            soundListener?.onExploded()
        }
    }

    // -------------------------------------------------- fallen-body physics

    private fun isFallen(hand: Hand): Boolean =
        fallenBodies.any { it.kind == BodyKind.HAND && it.hand == hand }

    private fun isFastHandFallen(): Boolean = fallenBodies.any { it.kind == BodyKind.FAST_HAND }

    private fun isNumeralFallen(hour: Int): Boolean =
        fallenBodies.any { it.kind == BodyKind.NUMERAL && it.numeralHour == hour }

    private fun anyHandFallen(): Boolean =
        fallenBodies.any { it.kind == BodyKind.HAND || it.kind == BodyKind.FAST_HAND }

    /** First knock throws the hands; further knocks shake numerals loose. */
    private fun onKnock(impulseX: Float, impulseY: Float) {
        if (!anyHandFallen()) {
            dropHands(impulseX, impulseY)
        } else {
            dropRandomNumerals(impulseX, impulseY)
        }
    }

    private fun dropHands(impulseX: Float, impulseY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (r <= 0f || chronoProvider != null) return
        // Winding state makes no sense once the hands are off the axis.
        spring?.cancel()
        spring = null
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        visualOffsetSeconds = 0.0

        val a = computeAngles()
        val ivx = impulseX * 35f
        val ivy = impulseY * 35f
        val drops = ArrayList<Hand>(3)
        drops.add(Hand.HOUR)
        drops.add(Hand.MINUTE)
        if (showSecondHand) drops.add(Hand.SECOND)
        for (hand in drops) {
            if (isFallen(hand)) continue
            val len = lengthOf(hand) * r
            val tail = tailOf(hand) * r
            addRodBody(
                BodyKind.HAND, hand, angleOf(hand, a),
                len, tail, widthOf(hand) * r * 2f, cx, cy, ivx, ivy
            )
        }
        if (fastHand != FastHandMode.NONE && !isFastHandFallen()) {
            addRodBody(
                BodyKind.FAST_HAND, null, computeAngles().fast,
                FAST_LEN * r, 0.05f * r, 0.008f * r * 2f, cx, cy, ivx, ivy
            )
        }
        if (fallenBodies.isNotEmpty()) {
            lastPhysicsAt = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun addRodBody(
        kind: BodyKind, hand: Hand?, angle: Float,
        len: Float, tail: Float, stroke: Float,
        cx: Float, cy: Float, ivx: Float, ivy: Float
    ) {
        val visualAngle = if (mirrored) -angle else angle
        val rad = Math.toRadians(visualAngle.toDouble())
        val mid = (len - tail) / 2f
        fallenBodies.add(
            FallingBody(
                kind = kind,
                hand = hand,
                numeralHour = 0,
                label = "",
                x = cx + sin(rad).toFloat() * mid,
                y = cy - cos(rad).toFloat() * mid,
                vx = ivx + Random.nextFloat() * 400f - 200f,
                vy = ivy - Random.nextFloat() * 300f,
                angleDeg = visualAngle,
                angVel = Random.nextFloat() * 420f - 210f,
                halfLen = (len + tail) / 2f,
                strokeWidth = stroke,
                textSize = 0f
            )
        )
    }

    private fun dropRandomNumerals(impulseX: Float, impulseY: Float) {
        val mounted = visibleNumeralHours().filter { !isNumeralFallen(it) }
        if (mounted.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val total = visibleNumeralHours().size
        val count = max(1, ceil(total / 3.0).toInt())
        for (hour in mounted.shuffled().take(count)) {
            dropNumeral(hour, numeralPosition(hour, cx, cy, r), impulseX * 35f, impulseY * 35f)
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun dropNumeral(hour: Int, pos: PointF, ivx: Float, ivy: Float) {
        if (isNumeralFallen(hour)) return
        val r = dialRadius()
        val textSize = numeralTextSize(r)
        numeralPaint.textSize = textSize
        val label = numeralLabel(hour)
        fallenBodies.add(
            FallingBody(
                kind = BodyKind.NUMERAL,
                hand = null,
                numeralHour = hour,
                label = label,
                x = pos.x,
                y = pos.y,
                vx = ivx + Random.nextFloat() * 250f - 125f,
                vy = ivy - Random.nextFloat() * 200f,
                angleDeg = 0f,
                angVel = Random.nextFloat() * 360f - 180f,
                halfLen = max(numeralPaint.measureText(label) / 2f, textSize * 0.35f),
                strokeWidth = 0f,
                textSize = textSize
            )
        )
        lastPhysicsAt = SystemClock.uptimeMillis()
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
            b.vx += gravityX * dt
            b.vy += gravityY * dt
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
        val r = dialRadius()
        val remount = when (b.kind) {
            // Hands click back onto the central axis.
            BodyKind.HAND, BodyKind.FAST_HAND -> hypot(x - cx, y - cy) < r * 0.18f
            // Each numeral has to go back to its own spot on the dial
            // (or to the center, if the dial no longer shows that hour).
            BodyKind.NUMERAL -> {
                val stillVisible = visibleNumeralHours().contains(b.numeralHour)
                if (stillVisible) {
                    val home = numeralPosition(b.numeralHour, cx, cy, r)
                    hypot(x - home.x, y - home.y) < r * 0.12f
                } else {
                    hypot(x - cx, y - cy) < r * 0.18f
                }
            }
        }
        if (remount) {
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

    private fun displayNowMs(): Long = frozenDisplayMs ?: TimeKeeper.nowMs()

    /** Chronograph value including any winding offset and hold-freeze. */
    private fun chronoDisplayMs(): Long? = chronoProvider?.let { provider ->
        ((chronoFrozenMs ?: provider()) + (visualOffsetSeconds * 1000.0).toLong())
            .coerceAtLeast(0L)
    }

    private fun computeAngles(): Angles {
        chronoDisplayMs()?.let { duration ->
            val totalSec = duration / 1000.0
            return Angles(
                hour = ((totalSec / 3600.0) % hoursOnDial / hoursOnDial * 360.0).toFloat(),
                minute = ((totalSec / 60.0) % 60.0 / 60.0 * 360.0).toFloat(),
                second = ((totalSec % 60.0) / 60.0 * 360.0).toFloat(),
                fast = (duration % 1000L) / 1000f * 360f
            )
        }

        val nowMs = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
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
        if (showDate && chronoProvider == null) drawDate(canvas, cx, cy, r)

        val a = computeAngles()

        if (fastHand != FastHandMode.NONE || chronoProvider != null) {
            for (i in 0 until 10) {
                val angle = i / 10f * 360f
                fastTickPaint.strokeWidth = r * 0.012f
                val from = pointAt(cx, cy, angle, r * 0.30f)
                val to = pointAt(cx, cy, angle, r * 0.36f)
                canvas.drawLine(from.x, from.y, to.x, to.y, fastTickPaint)
            }
            if (!isFastHandFallen()) {
                drawHand(canvas, cx, cy, a.fast, r * FAST_LEN, r * 0.05f, r * 0.008f, fastHandPaint)
            }
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

        drawFallenBodies(canvas)

        canvas.drawCircle(cx, cy, r * 0.035f, centerDotPaint)

        // Digital 7-segment readout: the chronograph value in chrono modes,
        // or the current time while the hands are lying at the bottom of
        // the dial and the analog display is useless.
        val digitalText = when {
            chronoProvider != null -> formatDuration(chronoDisplayMs() ?: 0L)
            anyHandFallen() -> {
                cal.timeInMillis = displayNowMs()
                String.format(
                    Locale.US, "%02d:%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    cal.get(Calendar.SECOND)
                )
            }
            else -> null
        }
        digitalText?.let {
            val digitH = r * 0.13f
            val yTop = min(cy + r + digitH * 0.4f, height - digitH * 1.6f)
            drawSevenSegment(canvas, it, cx, yTop, digitH)
        }

        if (SystemClock.uptimeMillis() < cheaterUntil) {
            cheaterPaint.textSize = r * 0.24f
            canvas.save()
            canvas.rotate(-18f, cx, cy)
            canvas.drawText(
                context.getString(R.string.cheater_stamp),
                cx,
                cy - (cheaterPaint.ascent() + cheaterPaint.descent()) / 2f,
                cheaterPaint
            )
            canvas.restore()
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            total / 3600 % 100, total / 60 % 60, total % 60
        )
    }

    private fun drawFallenBodies(canvas: Canvas) {
        for (b in fallenBodies) {
            when (b.kind) {
                BodyKind.NUMERAL -> {
                    numeralPaint.textSize = b.textSize
                    canvas.save()
                    canvas.translate(b.x, b.y)
                    canvas.rotate(b.angleDeg)
                    canvas.drawText(
                        b.label, 0f,
                        -(numeralPaint.ascent() + numeralPaint.descent()) / 2f,
                        numeralPaint
                    )
                    canvas.restore()
                }
                else -> {
                    val rad = Math.toRadians(b.angleDeg.toDouble())
                    val dirX = sin(rad).toFloat()
                    val dirY = -cos(rad).toFloat()
                    val paint = when {
                        b.kind == BodyKind.FAST_HAND -> fastHandPaint
                        else -> paintOf(b.hand ?: Hand.HOUR)
                    }
                    paint.strokeWidth = b.strokeWidth
                    canvas.drawLine(
                        b.x - dirX * b.halfLen, b.y - dirY * b.halfLen,
                        b.x + dirX * b.halfLen, b.y + dirY * b.halfLen,
                        paint
                    )
                }
            }
        }
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float) {
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
        numeralPaint.textSize = numeralTextSize(r)
        for (hour in visibleNumeralHours()) {
            if (isNumeralFallen(hour)) continue
            val pos = numeralPosition(hour, cx, cy, r)
            val defaultColor = numeralPaint.color
            if (selectedHours.contains(hour)) numeralPaint.color = selectedColor
            val baseline = pos.y - (numeralPaint.ascent() + numeralPaint.descent()) / 2f
            canvas.drawText(numeralLabel(hour), pos.x, baseline, numeralPaint)
            numeralPaint.color = defaultColor
        }
    }

    private fun drawDate(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        cal.timeInMillis = displayNowMs()
        val text = when (dateFormatStyle) {
            DateFormatStyle.NUMBER -> numberDateFormat.format(Date(cal.timeInMillis))
            DateFormatStyle.TEXT -> textDateFormat.format(Date(cal.timeInMillis))
            DateFormatStyle.ROMAN -> {
                val day = Roman.of(cal.get(Calendar.DAY_OF_MONTH))
                val month = Roman.of(cal.get(Calendar.MONTH) + 1)
                val year = Roman.of(cal.get(Calendar.YEAR))
                "$day·$month·$year"
            }
        }
        datePaint.textSize = r * 0.085f
        val baseline = cy - r * 0.42f - (datePaint.ascent() + datePaint.descent()) / 2f
        canvas.drawText(text, cx, baseline, datePaint)
    }

    // Seven-segment bits, ordered a(64) b(32) c(16) d(8) e(4) f(2) g(1).
    private fun drawSevenSegment(canvas: Canvas, text: String, cx: Float, top: Float, digitH: Float) {
        val digitW = digitH * 0.55f
        val gap = digitW * 0.45f
        val colonW = digitW * 0.5f
        var totalW = 0f
        for (c in text) totalW += if (c == ':') colonW + gap else digitW + gap
        totalW -= gap
        var x = cx - totalW / 2f
        digitalPaint.strokeWidth = digitH * 0.10f
        for (c in text) {
            if (c == ':') {
                canvas.drawPoint(x + colonW / 2f, top + digitH * 0.30f, digitalPaint)
                canvas.drawPoint(x + colonW / 2f, top + digitH * 0.70f, digitalPaint)
                x += colonW + gap
            } else {
                val digit = c - '0'
                if (digit in 0..9) drawSegments(canvas, SEGMENT_BITS[digit], x, top, digitW, digitH)
                x += digitW + gap
            }
        }
    }

    private fun drawSegments(canvas: Canvas, bits: Int, x: Float, y: Float, w: Float, h: Float) {
        val s = digitalPaint.strokeWidth * 0.8f
        val mid = y + h / 2f
        if (bits and 64 != 0) canvas.drawLine(x + s, y, x + w - s, y, digitalPaint)
        if (bits and 32 != 0) canvas.drawLine(x + w, y + s, x + w, mid - s, digitalPaint)
        if (bits and 16 != 0) canvas.drawLine(x + w, mid + s, x + w, y + h - s, digitalPaint)
        if (bits and 8 != 0) canvas.drawLine(x + s, y + h, x + w - s, y + h, digitalPaint)
        if (bits and 4 != 0) canvas.drawLine(x, mid + s, x, y + h - s, digitalPaint)
        if (bits and 2 != 0) canvas.drawLine(x, y + s, x, mid - s, digitalPaint)
        if (bits and 1 != 0) canvas.drawLine(x + s, mid, x + w - s, mid, digitalPaint)
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

    companion object {
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 1f
        private const val SHAKE_THRESHOLD = 14f // m/s² beyond gravity
        private const val BASE_GRAVITY = 2600f // px/s²
        private const val HOUR_LEN = 0.52f
        private const val MINUTE_LEN = 0.74f
        private const val SECOND_LEN = 0.82f
        private const val FAST_LEN = 0.30f
        private val END_SIDES = floatArrayOf(1f, -1f)
        private val SEGMENT_BITS = intArrayOf(
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011
        )
    }
}
