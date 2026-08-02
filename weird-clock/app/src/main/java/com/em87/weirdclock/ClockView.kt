package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
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
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
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

    /**
     * The dial doesn't have to be round. Polygonal faces keep the same
     * angular time layout, but the boundary breathes in and out between
     * corners — and everything pinned to the rim (ticks, numerals, even the
     * hands' lengths) follows it, so the second hand stretches into the
     * corners as it sweeps. Orientations are chosen symmetric about the
     * vertical axis so mirror mode stays consistent.
     */
    enum class DialShape(val sides: Int, val vertexOffsetDeg: Float) {
        CIRCLE(0, 0f),
        TRIANGLE(3, 0f),
        SQUARE(4, 45f),
        HEXAGON(6, 0f),
        OCTAGON(8, 22.5f)
    }

    /**
     * Magnet layout for wind-to-set. COUNTDOWN is progressive — minute
     * detents up to 5 min, 5-minute up to half an hour, quarter-hour up to
     * two hours, hourly beyond — so sweeping across an hour doesn't rattle
     * through 75 detents. ALARM keeps a flat 5-minute grid.
     */
    enum class MagnetProfile { COUNTDOWN, ALARM }
    internal enum class Hand { HOUR, MINUTE, SECOND }
    private enum class BodyKind { HAND, FAST_HAND, NUMERAL, MOON, DATE }

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

    /** Fired on every knock that shakes something loose (hosts react too). */
    var onKnocked: (() -> Unit)? = null

    /** Knocks the hands off programmatically (bubbles echo the main dial). */
    fun knockHandsOff() {
        dropHands(0f, -6f)
    }

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
    var dialShape = DialShape.CIRCLE
        set(value) { field = value; invalidate() }
    var showDate = false
        set(value) { field = value; invalidate() }
    var dateFormatStyle = DateFormatStyle.NUMBER
        set(value) { field = value; invalidate() }
    var touchHandsEnabled = true
    var pinchZoomEnabled = true
    /** The seven-segment readout under the dial. */
    var showDigitalReadout = true
        set(value) { field = value; invalidate() }
    var shakeDropEnabled = true
        set(value) {
            field = value
            // Turned on from settings while already on screen: pick the
            // accelerometer back up, since attaching is long past.
            if (value && isAttachedToWindow) listenForShakes()
        }
    var dialScale = 1f
        set(value) {
            val next = value.coerceIn(MIN_SCALE, MAX_SCALE)
            // Fallen pieces live in dial space: rescale them with it.
            if (next != field && field > 0f && fallenBodies.isNotEmpty()) {
                val f = next / field
                val cx = width / 2f
                val cy = height / 2f
                for (b in fallenBodies) {
                    b.x = cx + (b.x - cx) * f
                    b.y = cy + (b.y - cy) * f
                    b.vx *= f
                    b.vy *= f
                    b.halfLen *= f
                    b.strokeWidth *= f
                    b.textSize *= f
                }
            }
            field = next
            invalidate()
        }
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
    /**
     * When true (countdown being set), winding a hand commits the new value
     * through [onChronoAdjusted] with no spring-back, magnetized to round
     * durations, and the minute/hour hands take grab priority.
     */
    var chronoSettable = false

    /**
     * Draws chronograph hardware on the case — start/stop pusher at 2
     * o'clock, crown at 3, reset pusher at 4 — fading in with the mode
     * transition. The pushers are touchable.
     */
    var chronoButtons = false
        set(value) {
            if (field != value) buttonsAnimStart = SystemClock.uptimeMillis()
            field = value
            invalidate()
        }

    /** Tints the start/stop pusher while the chronograph is running. */
    var chronoRunning = false
        set(value) { field = value; invalidate() }

    var onChronoStartStop: (() -> Unit)? = null
    var onChronoReset: (() -> Unit)? = null

    /** Easter egg: tapping the crown. Five frantic taps blow the hands off. */
    var onCrownTap: (() -> Unit)? = null
    private val crownTapTimes = ArrayDeque<Long>()

    /** Receives the adjusted duration when the user sets the countdown. */
    var onChronoAdjusted: ((Long) -> Unit)? = null

    var magnetProfile = MagnetProfile.COUNTDOWN

    /**
     * Where the magnet grid counts from, in milliseconds of the day.
     *
     * Zero for a countdown, which really does start at nothing. For a length
     * wound onto a face — "this begins at six and lasts how long?" — it is
     * the hour it begins at, so the detents fall on twenty past, half past,
     * an hour later, rather than on multiples of midnight.
     */
    var magnetOrigin = 0L

    /**
     * True while the value being wound is a time of day rather than a
     * duration, so it lives in a day and wraps at the end of one.
     *
     * Winding past midnight used to keep counting — 25:00, 26:00 — with
     * nothing on the face saying so, and only the commit quietly folded it
     * back. Now the hands say it: cross twenty-four hours and the dial reads
     * 00:00, which is where you actually are.
     */
    var chronoWrapsDay = false

    /**
     * A recorded lap: the angles of all three hands plus the reading they
     * showed, and whether that reading was the truth at the time.
     */
    private class Lap(
        val hour: Float,
        val minute: Float,
        val second: Float,
        val ms: Long,
        val fake: Boolean
    )

    private val laps = mutableListOf<Lap>()

    // The unfolded lap list: opened by a tap on the ladder, scrolled by
    // dragging, closed by another tap.
    private var lapsExpanded = false
    private var lapListScroll = 0f
    private var lapListDragging = false
    private var lapListLastY = 0f
    private var lapListDownY = 0f
    private var ladderTapTop = 0f
    private var tapDownX = 0f
    private var tapDownY = 0f
    private val scrimPaint = Paint()

    /** Enabled alarms as dial angles, drawn as dots just outside the rim. */
    /**
     * Alarm dots, as an angle and which turn of the dial the time is on.
     * The angle alone cannot say: that is the whole problem these solve.
     */
    var alarmMarkers: List<DialMark> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * Calendar events as (startAngle, sweepAngle) pairs, drawn Sectograph
     * style: a wedge covering the time the event actually occupies. Alarms
     * are instants and get dots; only events have duration.
     */
    /** Event wedges: start angle, sweep, and the turn they belong to. */
    var eventArcs: List<DialArc> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * The sky complication: the sun while the sun is up where the app was
     * last located, the moon and its phase otherwise. One glyph, one place,
     * one setting — see [drawMoonPhase].
     */
    var showMoonPhase = false
        set(value) { field = value; invalidate() }

    /** Caption drawn inside the dial's upper half (world-clock city names). */
    var dialLabel: String? = null
        set(value) { field = value; invalidate() }

    fun recordLap() {
        // The honest test, and the only one that catches every trick: does
        // the lap the dial *shows* match the time actually elapsed? Winding
        // a hand does it, and so does catching the spring on its way back —
        // no need to know which stunt was pulled.
        val shown = chronoDisplayMs() ?: 0L
        val truth = chronoProvider?.invoke() ?: 0L
        val fake = chronoRunning && !chronoSettable &&
            kotlin.math.abs(shown - truth) > FAKE_LAP_TOLERANCE_MS
        if (fake) {
            cheaterFlagged = true
            cheaterUntil = SystemClock.uptimeMillis() + 600_000L
            if (cheaterFade >= 1f) cheaterFade = 0f
            soundListener?.onCheater()
        }
        val a = currentAngles()
        laps.add(Lap(a.hour, a.minute, a.second, shown, fake))
        // The ladder shows seven; the unfolded list scrolls, so it is worth
        // keeping far more than that.
        while (laps.size > MAX_LAPS) laps.removeAt(0)
        // Each new lap fades the CHEATER stamp a little; ten honest laps
        // wash the shame off entirely.
        if (cheaterUntil > 0L) cheaterFade = (cheaterFade + 0.1f).coerceAtMost(1f)
        invalidate()
    }

    fun clearLaps() {
        laps.clear()
        invalidate()
    }

    /**
     * Fired on a horizontal swipe; the argument is true when the finger moved
     * right. Return true to consume (used for page navigation over the dial).
     */
    var onHorizontalSwipe: ((Boolean) -> Boolean)? = null

    var chronoProvider: (() -> Long)? = null
        set(value) {
            if (field !== value) {
                // Animate the hands from where they are to the new mode's
                // positions instead of snapping.
                transitionFrom = currentAngles()
                transitionStartAt = SystemClock.uptimeMillis()
            }
            field = value
            spring?.cancel()
            spring = null
            draggedHand = null
            activeSoundHand = null
            frozenDisplayMs = null
            chronoFrozenMs = null
            visualOffsetSeconds = 0.0
            invalidate()
            // Restart the ticker: on a slow-ticking clock its next run could
            // be up to a second away, which froze the transition mid-flight
            // and made the hands appear to jump.
            removeCallbacks(ticker)
            post(ticker)
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
    private val pusherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val alarmMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val alarmMarkerPath = Path()
    private val moonDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonLitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        // A custom View with no text is skipped by default; this one has
        // something to say.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
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
        lapPaint.color = t.secondHand
        alarmMarkerPaint.color = t.decimal
        alarmMarkerPaint.alpha = 230
        moonDarkPaint.color = t.minorTick
        moonDarkPaint.alpha = 90
        moonLitPaint.color = t.numeral
        moonLitPaint.alpha = 235
        moonRimPaint.color = t.minorTick
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

    /** How far the CHEATER stamp has been washed off by honest laps (0–1). */
    private var cheaterFade = 0f

    /** Mode-change animation: blend from these angles to the target ones. */
    private var transitionFrom: Angles? = null
    private var transitionStartAt = 0L
    private val transitionInterpolator = AccelerateDecelerateInterpolator()

    // Chronograph case hardware.
    private var buttonsAnimStart = 0L
    private var pressedPusher = 0 // 0 none, 1 start/stop, 2 reset

    /** Magnet the countdown is currently locked onto while setting it. */
    private var lockedMagnetMs: Long? = null

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

    /** Fired when the highlighted hours change, so the widget can match. */
    var onSelectedHoursChanged: ((Set<Int>) -> Unit)? = null

    fun setSelectedHours(hours: Set<Int>) {
        selectedHours.clear()
        selectedHours.addAll(hours)
        invalidate()
    }

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
        var halfLen: Float,
        var strokeWidth: Float,
        var textSize: Float
    )

    private val fallenBodies = ArrayList<FallingBody>()
    private val sampleBufA = FloatArray(SAMPLE_COUNT * 2)
    private val sampleBufB = FloatArray(SAMPLE_COUNT * 2)
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
            // Heavy smoothing, then a dead zone: raw accelerometer noise on a
            // phone lying perfectly still was enough to make settled debris
            // shiver in place forever.
            lowPassX = lowPassX * 0.92f + ax * 0.08f
            lowPassY = lowPassY * 0.92f + ay * 0.08f
            lowPassZ = lowPassZ * 0.92f + az * 0.08f
            // Device +X points right, +Y up the screen; view +Y is downward.
            var gx = -lowPassX / 9.81f
            var gy = lowPassY / 9.81f
            if (kotlin.math.abs(gx) < 0.04f) gx = 0f
            if (kotlin.math.abs(gy) < 0.04f) gy = 0f
            gravityX = gx * BASE_GRAVITY
            gravityY = gy * BASE_GRAVITY

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
                fastHand != FastHandMode.NONE || (smoothSeconds && showSecondHand)
            val delay = if (fast) 16L else 1000L - (TimeKeeper.nowMs() % 1000L).coerceIn(0L, 999L)
            postDelayed(this, delay)
        }
    }

    private fun isAnimating(): Boolean =
        draggedHand != null || spring?.isRunning == true ||
            fallenBodies.isNotEmpty() || transitionFrom != null

    /** True when fallen pieces are lying around the dial. */
    fun isDisarranged(): Boolean = fallenBodies.isNotEmpty()

    /** Instantly puts every fallen piece back and resets all play state. */
    fun reassembleAll() {
        timeScale = 1f
        fallenBodies.clear()
        carriedBody = null
        spring?.cancel()
        spring = null
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0
        cheaterUntil = 0L
        dialScale = 1f
        invalidate()
    }

    /**
     * Mirrors another dial's fallen-piece chaos onto this one, so swiping
     * between cards doesn't magically tidy the workshop. Positions scale
     * with any view-size difference.
     */
    fun syncFallenFrom(other: ClockView) {
        if (other === this) return
        fallenBodies.clear()
        carriedBody = null
        val sx = if (other.width > 0) width.toFloat() / other.width else 1f
        val sy = if (other.height > 0) height.toFloat() / other.height else 1f
        val s = (sx + sy) / 2f
        for (b in other.fallenBodies) {
            fallenBodies.add(
                FallingBody(
                    b.kind, b.hand, b.numeralHour, b.label,
                    b.x * sx, b.y * sy, b.vx * s, b.vy * s,
                    b.angleDeg, b.angVel,
                    b.halfLen * s, b.strokeWidth * s, b.textSize * s
                )
            )
        }
        if (fallenBodies.isNotEmpty()) lastPhysicsAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
        listenForShakes()
    }

    /**
     * A face that cannot be shaken has no use for the accelerometer, and an
     * alarm list can hold a dozen of these little ones at once.
     */
    private fun listenForShakes() {
        if (!shakeDropEnabled || chronoProvider != null) return
        if (sensorManager != null) return
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        sensorManager?.unregisterListener(shakeListener)
        sensorManager = null
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

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val fastHorizontal = kotlin.math.abs(velocityX) > 500f &&
                    kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY)
                if (!fastHorizontal) return false
                if (tapCandidate) return onHorizontalSwipe?.invoke(velocityX > 0) ?: false
                // With every hand pointing up (chrono at zero) the grab zones
                // cover the middle of the dial, so a page-style swipe usually
                // lands on a hand and becomes winding. Telling them apart by
                // wound angle fails (a straight pass near the pivot sweeps a
                // huge angle), so discriminate by the shape of the stroke:
                // swipes are long, straight and horizontal; winding is
                // circular. Any straight horizontal stroke in chrono mode is
                // a swipe — abort the drag and switch.
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val straightSwipe = kotlin.math.abs(dx) > width * 0.25f &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                if (chronoProvider != null && straightSwipe) {
                    if (draggedHand != null) abortDragForSwipe()
                    return onHorizontalSwipe?.invoke(velocityX > 0) ?: false
                }
                return false
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A passive dial (e.g. the mini world clock) lets touches through —
        // but never while case pushers are present, they must stay pressable.
        // Through the base class, mind: a passive face may still have been
        // given a click or a long-press to answer, and swallowing the event
        // here is what kept the alarm editor's little dials from opening.
        if (!touchHandsEnabled && !pinchZoomEnabled && fallenBodies.isEmpty() && !chronoButtons) {
            return super.onTouchEvent(event)
        }
        // The unfolded lap list owns every touch while it is up.
        if (lapsExpanded) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lapListLastY = event.y
                    lapListDownY = event.y
                    lapListDragging = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lapListLastY
                    lapListLastY = event.y
                    if (kotlin.math.abs(event.y - lapListDownY) > 12f) lapListDragging = true
                    lapListScroll = (lapListScroll - dy).coerceIn(0f, maxLapScroll())
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (!lapListDragging) {
                        lapsExpanded = false
                        invalidate()
                    }
                }
            }
            return true
        }
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
                val pusher = pusherAt(event.x, event.y)
                if (pusher != 0) {
                    pressedPusher = pusher
                    tapCandidate = false
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                // A mark under the finger takes the touch before the hands
                // do. Its dot is small and deliberate to hit, while a hand
                // can be grabbed anywhere along its length — so the one
                // gesture that has an alternative gives way.
                markUnderFinger = markLabelAt(event.x, event.y)
                val grabbedBody = markUnderFinger == null &&
                    grabFallenBodyNear(event.x, event.y)
                if (markUnderFinger == null && !grabbedBody && touchHandsEnabled) {
                    grabHandNear(event.x, event.y)
                }
                tapCandidate = !grabbedBody && draggedHand == null
                tapDownX = event.x
                tapDownY = event.y
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
                // A second finger on a pusher while the first winds a hand is
                // a *feature*: fake laps are half the fun. Only a genuine
                // pinch drops the wind.
                val idx = event.actionIndex
                val pusher = pusherAt(event.getX(idx), event.getY(idx))
                if (pusher != 0) {
                    when (pusher) {
                        1 -> onChronoStartStop?.invoke()
                        2 -> onChronoReset?.invoke()
                        3 -> handleCrownTap()
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    soundListener?.onTickCrossed()
                    return true
                }
                if (pinchZoomEnabled) parent?.requestDisallowInterceptTouchEvent(true)
                pressedPusher = 0
                releaseDraggedHand()
                releaseCarriedBody()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (pressedPusher != 0) {
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        pusherAt(event.x, event.y) == pressedPusher
                    ) {
                        when (pressedPusher) {
                            1 -> onChronoStartStop?.invoke()
                            2 -> onChronoReset?.invoke()
                            3 -> handleCrownTap()
                        }
                        soundListener?.onTickCrossed()
                    }
                    pressedPusher = 0
                    invalidate()
                }
                // A tap on a mark names it. Only a tap: a finger that
                // wandered was on its way somewhere else.
                markUnderFinger?.let { label ->
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        hypot(event.x - tapDownX, event.y - tapDownY) < 24f
                    ) {
                        showMarkBubble(label, event.x, event.y)
                    }
                    markUnderFinger = null
                }
                // A quiet tap on the lap ladder unfolds the full list. A
                // tap, not the tail end of a swipe: the finger must not
                // have wandered.
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    tapCandidate && draggedHand == null && carriedBody == null &&
                    chronoProvider != null && !chronoSettable && laps.isNotEmpty() &&
                    event.y > ladderTapTop &&
                    hypot(event.x - tapDownX, event.y - tapDownY) < 24f
                ) {
                    lapsExpanded = true
                    lapListScroll = 0f
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                }
                releaseDraggedHand()
                releaseCarriedBody()
            }
        }
        return true
    }

    // ------------------------------------------------ naming a mark

    /** The label of the mark the current touch went down on, if any. */
    private var markUnderFinger: String? = null

    /**
     * Fired when the calendar day the dial is *showing* changes — which
     * happens by carrying the hands round past midnight, not only by
     * waiting. The host answers by rebuilding the marks for that day, so a
     * turn of the hour hand really does show you what tomorrow holds.
     */
    var onShownDayChanged: ((Long) -> Unit)? = null
    private var shownDay = Long.MIN_VALUE

    /** The wall-clock instant the dial is showing, winding included. */
    fun shownWallMs(): Long = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()

    private fun checkShownDay() {
        if (onShownDayChanged == null) return
        val ms = shownWallMs()
        cal.timeInMillis = ms
        val day = cal.get(java.util.Calendar.YEAR) * 1000L + cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (day == shownDay) return
        val first = shownDay == Long.MIN_VALUE
        shownDay = day
        if (!first) onShownDayChanged?.invoke(ms)
    }

    /**
     * Pops a mark's bubble without anyone having tapped it, at the mark's
     * own place on the rim.
     */
    fun announceMark(label: String, angleDeg: Float) {
        val at = markCenter(width / 2f, height / 2f, angleDeg)
        bubbleText = label
        bubbleX = at.x
        bubbleY = at.y
        bubbleSince = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    /** What the bubble is currently saying, if anything. */
    internal fun bubbleLabel(): String? = bubbleText

    /** The label currently being named because the hour hand rests on it. */
    private var hoveredLabel: String? = null

    /**
     * The hour hand as a reading head: carry it round and each event it
     * passes over says what it is.
     *
     * Only while the hands are being played with — a clock quietly telling
     * the time has no business shouting the names of the day's
     * appointments. But once you take hold of the hour hand, running it over
     * the marks is the natural way to ask "and what is *that* one?", and
     * without this the dots stay anonymous unless you go and tap each.
     *
     * It holds the bubble open for as long as the hand stays on the mark,
     * and lets it fade on its own once the hand moves off.
     */
    internal fun followHourHand(hourAngle: Float) {
        if (chronoProvider != null || visualOffsetSeconds == 0.0) {
            hoveredLabel = null
            return
        }
        val found = markAtAngle(hourAngle)
        if (found == null) {
            hoveredLabel = null
            return
        }
        if (found.first != hoveredLabel) {
            hoveredLabel = found.first
            announceMark(found.first, found.second)
        } else if (bubbleText == found.first) {
            // Still resting on it, so the bubble does not start fading yet.
            bubbleSince = android.os.SystemClock.uptimeMillis()
        }
    }

    /** The mark an angle falls on: its name, and where to hang the bubble. */
    internal fun markAtAngle(angleDeg: Float): Pair<String, Float>? {
        fun apart(a: Float, b: Float): Float {
            val d = kotlin.math.abs((a - b) % 360f)
            return if (d > 180f) 360f - d else d
        }
        for (mark in alarmMarkers) {
            if (mark.label.isEmpty()) continue
            if (apart(angleDeg, mark.angle) < 4f) return mark.reading() to mark.angle
        }
        for (arc in eventArcs) {
            if (arc.label.isEmpty()) continue
            val left = arcRemaining(arc)
            if (left <= 0f) continue
            // Only the part still on the face: the head has been eaten, and
            // naming an event over ground it no longer covers would be
            // pointing at nothing.
            val from = arc.start + arc.sweep * (1f - left)
            val into = ((angleDeg - from) % 360f + 360f) % 360f
            if (into <= kotlin.math.abs(arc.sweep) * left) return arc.reading() to angleDeg
        }
        return null
    }

    private var bubbleText: String? = null
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var bubbleSince = 0L

    /**
     * Which mark is under (x, y), by name.
     *
     * The dots and wedges are the only thing on the dial that knows what it
     * is for, and until now they could not say: three dots on a face and no
     * way to ask which was the dentist. A dot is matched by distance, a
     * wedge by angle and radius, which is exactly how each is drawn.
     */
    private fun markLabelAt(x: Float, y: Float): String? {
        if (chronoProvider != null) return null
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        // Dots first: they sit outside the rim, where nothing else lives,
        // and they are the smaller target of the two.
        var best: String? = null
        var bestDist = r * 0.075f
        for (mark in alarmMarkers) {
            if (mark.label.isEmpty()) continue
            val at = markCenter(cx, cy, mark.angle)
            val d = hypot(x - at.x, y - at.y)
            if (d < bestDist) {
                bestDist = d
                best = mark.reading()
            }
        }
        if (best != null) return best

        val dist = hypot(x - cx, y - cy)
        val angle = touchAngleDeg(x, y)
        for (arc in eventArcs) {
            if (arc.label.isEmpty()) continue
            if (arcRemaining(arc) <= 0f) continue
            val b = boundaryRadius(angle)
            if (dist < b * 0.86f || dist > b * 0.99f) continue
            val into = ((angle - arc.start) % 360f + 360f) % 360f
            if (into <= kotlin.math.abs(arc.sweep)) return arc.reading()
        }
        return null
    }

    private fun showMarkBubble(label: String, x: Float, y: Float) {
        bubbleText = label
        bubbleX = x
        bubbleY = y
        bubbleSince = android.os.SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    /**
     * The name of the mark just tapped, in a little bubble above it.
     *
     * Hand-clocked and faded out by this view rather than shown as a Toast:
     * a Toast lands at the bottom of the screen, far from the dot that
     * caused it, and says nothing about which of three dots was asked.
     */
    private fun drawMarkBubble(canvas: Canvas, r: Float) {
        val text = bubbleText ?: return
        val age = android.os.SystemClock.uptimeMillis() - bubbleSince
        val life = 2200L
        val fade = 400L
        if (age > life) {
            bubbleText = null
            return
        }
        val alpha = if (age > life - fade) {
            ((life - age).toFloat() / fade).coerceIn(0f, 1f)
        } else {
            1f
        }
        bubbleTextPaint.textSize = r * 0.085f
        // The name on the first line and the note wrapped under it, because
        // a note is where the address and the room number go and neither
        // fits on one line beside a name.
        val lines = wrapBubble(text, r * 0.62f)
        val lineH = bubbleTextPaint.descent() - bubbleTextPaint.ascent()
        val padX = r * 0.05f
        val padY = r * 0.035f
        val w = (lines.maxOf { bubbleTextPaint.measureText(it) }) + padX * 2
        val h = lineH * lines.size + padY * 2
        // Kept inside the view, and above the finger so it is not covered
        // by the hand that asked for it.
        val left = (bubbleX - w / 2).coerceIn(r * 0.04f, width - w - r * 0.04f)
        val top = (bubbleY - h - r * 0.09f).coerceAtLeast(r * 0.04f)
        val rect = RectF(left, top, left + w, top + h)
        bubblePaint.color = theme.face
        bubblePaint.alpha = (238 * alpha).toInt()
        val radius = minOf(h, lineH * 1.6f) * 0.38f
        canvas.drawRoundRect(rect, radius, radius, bubblePaint)
        bubblePaint.color = theme.rim
        bubblePaint.alpha = (255 * alpha).toInt()
        bubblePaint.style = Paint.Style.STROKE
        bubblePaint.strokeWidth = r * 0.006f
        canvas.drawRoundRect(rect, radius, radius, bubblePaint)
        bubblePaint.style = Paint.Style.FILL
        bubbleTextPaint.color = theme.numeral
        bubbleTextPaint.alpha = (255 * alpha).toInt()
        var baseline = top + padY - bubbleTextPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, rect.centerX(), baseline, bubbleTextPaint)
            baseline += lineH
        }
        // Its own clock, so it fades whether or not anything else on the
        // dial happens to be animating.
        invalidate()
    }

    /**
     * Breaks the bubble's text into lines no wider than [maxWidth].
     *
     * Explicit newlines are kept — the name and the note are separate lines
     * by construction — and long notes are wrapped on word boundaries, then
     * cut off, because a bubble that grows to cover the dial is no longer a
     * bubble.
     */
    private fun wrapBubble(text: String, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (bubbleTextPaint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    out.add(line)
                    line = word
                }
                if (out.size >= MAX_BUBBLE_LINES) break
            }
            if (out.size >= MAX_BUBBLE_LINES) break
            out.add(line)
        }
        if (out.isEmpty()) out.add(text)
        return out.take(MAX_BUBBLE_LINES)
    }

    /**
     * A narrow grab tab just outside the rim, following the second hand's
     * tip. It only covers the sector the hand actually occupies — so it
     * doesn't swallow the background — but it makes the thin hand reachable
     * even when every other hand is stacked on top of it at twelve. It also
     * takes precedence over the crown, which lives at exactly that spot.
     */
    /**
     * The band between the minute hand's tip and the rim. Nobody reaching in
     * there means to grab the minute hand — its tip is the inner edge of the
     * band — so within it the second hand is the only hand on offer.
     *
     * This replaced an older trick that let a finger reach in from outside
     * the glass to catch the second hand. With the band working from the
     * inside, that outside grab was only ever a second way to do the same
     * thing, so it is gone.
     */
    private fun inSecondHandBand(x: Float, y: Float): Boolean {
        val dist = hypot(x - width / 2f, y - height / 2f)
        val b = boundaryRadius(touchAngleDeg(x, y))
        return dist >= b * MINUTE_LEN && dist <= b * 1.02f
    }

    /** True where the second hand outranks the case hardware under it. */
    private fun secondHandRingHit(x: Float, y: Float): Boolean {
        if (!chronoSettable || chronoProvider == null) return false
        if (!showSecondHand || isFallen(Hand.SECOND)) return false
        if (!inSecondHandBand(x, y)) return false
        val handDeg = angleOf(Hand.SECOND, currentAngles())
        return kotlin.math.abs(normalizeDeg(touchAngleDeg(x, y) - handDeg)) < 16f
    }

    /**
     * 1 = start/stop pusher (1:30), 2 = reset pusher (10:30), 3 = crown (12).
     */
    private fun pusherAt(x: Float, y: Float): Int {
        // The second-hand tab outranks the crown: setting seconds beats
        // cuckoo noises.
        if (secondHandRingHit(x, y)) return 0
        if (!chronoButtons) return 0
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val hit = max(48f * resources.displayMetrics.density, r * 0.16f)
        val start = plainPoint(cx, cy, 45f, boundaryRadius(45f) * 1.03f)
        if (hypot(x - start.x, y - start.y) < hit) return 1
        val reset = plainPoint(cx, cy, 315f, boundaryRadius(315f) * 1.03f)
        if (hypot(x - reset.x, y - reset.y) < hit) return 2
        val crown = plainPoint(cx, cy, 0f, boundaryRadius(0f) * 1.04f)
        if (hypot(x - crown.x, y - crown.y) < hit) return 3
        return 0
    }

    /**
     * Crown taps cuckoo — and, being the winding crown, it also remounts any
     * pieces lying on the floor. Five frantic taps overwind the mechanism.
     */
    private fun handleCrownTap() {
        val now = SystemClock.uptimeMillis()
        crownTapTimes.addLast(now)
        while (crownTapTimes.size > 5) crownTapTimes.removeFirst()
        if (crownTapTimes.size >= 5 && now - crownTapTimes.first() < 3000) {
            crownTapTimes.clear()
            soundListener?.onExploded()
            dropHands(0f, -8f)
        } else {
            if (fallenBodies.isNotEmpty()) {
                fallenBodies.clear()
                carriedBody = null
                soundListener?.onHandMounted()
            }
            // Winding the crown resets the mechanism's conscience: the
            // faked laps and the stamp that shamed them both go.
            if (laps.any { it.fake } || cheaterUntil > 0L) {
                laps.removeAll { it.fake }
                cheaterUntil = 0L
                cheaterFade = 0f
                cheaterFlagged = false
            }
            onCrownTap?.invoke()
        }
    }

    /** Like [pointAt] but ignoring mirror mode — case hardware is physical. */
    private fun plainPoint(cx: Float, cy: Float, angleDeg: Float, distance: Float): PointF {
        val a = Math.toRadians(angleDeg.toDouble())
        return PointF(cx + sin(a).toFloat() * distance, cy - cos(a).toFloat() * distance)
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
        val a = currentAngles()

        val threshold = max(r * 0.10f, 44f * resources.displayMetrics.density)
        var chosen: Hand? = null
        if (chronoSettable && chronoProvider != null) {
            // Setting a duration: within the dial proper, minutes and hours
            // matter and seconds barely.
            if (secondHandRingHit(x, y)) chosen = Hand.SECOND
            // Past the minute hand's tip there is nothing but second hand:
            // offering the minute one out there only ever stole the grab.
            val outerBand = showSecondHand && !isFallen(Hand.SECOND) &&
                inSecondHandBand(x, y)
            val order = if (outerBand) {
                arrayOf(Triple(Hand.SECOND, 1.8f, true))
            } else {
                arrayOf(Triple(Hand.MINUTE, 1.4f, true),
                    Triple(Hand.HOUR, 1.2f, true),
                    Triple(Hand.SECOND, 0.8f, showSecondHand))
            }
            if (chosen == null) {
                for ((hand, factor, enabled) in order) {
                    if (!enabled || isFallen(hand)) continue
                    if (distanceToHand(hand, a, x, y, cx, cy, r) < threshold * factor) {
                        chosen = hand
                        break
                    }
                }
            }
        } else if (showSecondHand && !isFallen(Hand.SECOND) &&
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
            lockedMagnetMs = null
            lastTouchDeg = touchAngleDeg(x, y)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun distanceToHand(
        hand: Hand, a: Angles,
        x: Float, y: Float,
        cx: Float, cy: Float, r: Float
    ): Float {
        val angle = angleOf(hand, a)
        val tip = pointAt(cx, cy, angle, boundaryRadius(angle) * lengthOf(hand))
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
        // Winding a running chronograph forward more than one turn is
        // cheating — a stopped one has nothing to cheat, and setting the
        // countdown is legitimate.
        if (chronoProvider != null && !chronoSettable && chronoRunning &&
            !cheaterFlagged && dragAccumDeg >= 360.0
        ) {
            cheaterFlagged = true
            // Faking laps is encouraged; the stamp just sticks around until
            // ten honest laps have scrubbed it away.
            cheaterUntil = SystemClock.uptimeMillis() + 600_000L
            cheaterFade = 0f
            soundListener?.onCheater()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        // Over-winding blows the mechanism apart, chronograph included (but
        // not while calmly setting the countdown).
        if (!exploded && !(chronoProvider != null && chronoSettable) &&
            kotlin.math.abs(dragAccumDeg) >= explosionDegrees(hand)
        ) {
            exploded = true
            soundListener?.onExploded()
            dropHands(0f, 0f)
            return
        }
        val target = dragStartOffset + dragAccumDeg / 360.0 * secondsPerRevolution(hand)
        if (chronoSettable && chronoProvider != null && hand == Hand.SECOND) {
            // The second hand works like the minute hand: it ticks from
            // whole second to whole second — no landing in between — and
            // magnets pull only at the round marks: every five seconds up to
            // the half turn, one more at 45, and that is all.
            val baseMs = chronoFrozenMs ?: 0L
            val wound = baseMs + (target * 1000.0).toLong()
            val whole = Math.floorDiv(wound + 500L, 1000L) * 1000L
            val rem = Math.floorMod(whole, 60_000L)
            var snapped = whole
            var onDetent = false
            for (c in SECOND_DETENTS) {
                if (kotlin.math.abs(rem - c) <= 1_100L) {
                    snapped = whole - rem + c
                    onDetent = true
                    break
                }
            }
            if (onDetent) {
                if (lockedMagnetMs != snapped) {
                    lockedMagnetMs = snapped
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } else {
                lockedMagnetMs = null
            }
            setOffset((snapped - baseMs) / 1000.0)
            return
        }
        if (chronoSettable && chronoProvider != null) {
            // Magnets only engage in the precision band: the ring between
            // the numerals and the rim, where the finger goes for fine
            // adjustment. Whipping the hand around from near the center
            // spins free — no detents, no haptic machine-gun.
            val fingerDist = hypot(x - cx, y - cy)
            val bAtFinger = boundaryRadius(touchAngleDeg(x, y))
            val inPrecisionBand =
                fingerDist >= bAtFinger * numeralRadiusFactor() * 0.95f &&
                    fingerDist <= bAtFinger * 1.10f
            val baseMs = chronoFrozenMs ?: 0L
            val durationMs = baseMs + (target * 1000.0).toLong()
            val magnet = if (inPrecisionBand) magnetFor(durationMs, hand) else null
            if (magnet != null) {
                if (lockedMagnetMs != magnet) {
                    lockedMagnetMs = magnet
                    // LONG_PRESS: CLOCK_TICK is inaudible on many devices.
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                setOffset((magnet - baseMs) / 1000.0)
            } else {
                lockedMagnetMs = null
                setOffset(target)
            }
        } else {
            setOffset(target)
        }
    }

    /**
     * How far a hand may be wound before the mechanism gives up, measured in
     * degrees of that hand.
     *
     * Ten turns for every hand was the old rule, and it was ten turns of the
     * wrong thing: ten turns of the hour hand is five days, which nobody
     * reaches, while ten turns of the *second* hand is ten minutes, which is
     * an easy accident. The budget is time travelled, not turns:
     *
     *  - the hour hand gets two days, so you can finish today and walk the
     *    calendar forward a couple of days to see what is coming — and then
     *    it goes;
     *  - the minute hand gets twelve hours, one lap of the face;
     *  - the second hand keeps its ten turns, because ten minutes of
     *    winding a second hand is nobody's accident and the explosion is
     *    half the reason to do it.
     */
    internal fun explosionDegrees(hand: Hand): Double {
        val budgetSeconds = when (hand) {
            Hand.HOUR -> 2 * 86_400.0
            Hand.MINUTE -> 12 * 3_600.0
            Hand.SECOND -> 10 * 60.0
        }
        return budgetSeconds / secondsPerRevolution(hand) * 360.0
    }

    private fun secondsPerRevolution(hand: Hand): Double = when (hand) {
        Hand.SECOND -> 60.0
        Hand.MINUTE -> 3600.0
        Hand.HOUR -> hoursOnDial * 3600.0
    }

    /** Drops an in-progress wind without spring-back (the fling was a swipe). */
    private fun abortDragForSwipe() {
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0
        invalidate()
    }

    private fun releaseDraggedHand() {
        val hand = draggedHand ?: return
        draggedHand = null
        // Setting the countdown: commit the wound value (magnetized to round
        // durations) with no spring-back.
        if (chronoSettable && chronoProvider != null) {
            val displayMs = chronoDisplayMs() ?: 0L
            // A time of day is never below zero — chronoDisplayMs has
            // already brought it round the clock — so the spring-back that
            // catches a negative countdown has nothing to do here.
            if (displayMs < 0L && !chronoWrapsDay) {
                // Below zero: commit zero and let the spring pull the hands
                // back up to it.
                chronoFrozenMs = null
                lockedMagnetMs = null
                onChronoAdjusted?.invoke(0L)
                visualOffsetSeconds = displayMs / 1000.0
                startSpringBack()
                return
            }
            // Which hand it was, passed in rather than read from
            // draggedHand — which this method has just cleared, so the
            // commit magnet fell through to the countdown profile: a
            // 60-second grid with ten seconds of capture, and zero is a
            // multiple of sixty. That is what swallowed short settings.
            var adjusted = snapCountdown(displayMs, hand)
            // Set with the minute or hour hand, seconds polarize to zero:
            // nobody means 8:30 and seventeen seconds.
            if (hand != Hand.SECOND) {
                adjusted = (adjusted + 30_000L) / 60_000L * 60_000L
            } else {
                // The offset travels as a double; land it back on the whole
                // second the detents already chose.
                adjusted = (adjusted + 500L) / 1000L * 1000L
            }
            if (chronoWrapsDay) {
                val day = 86_400_000L
                adjusted = ((adjusted % day) + day) % day
            }
            chronoFrozenMs = null
            visualOffsetSeconds = 0.0
            activeSoundHand = null
            lockedMagnetMs = null
            onChronoAdjusted?.invoke(adjusted)
            invalidate()
            return
        }
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
        startSpringBack()
    }

    private fun startSpringBack() {
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
        val chronoBaseMs = chronoProvider?.let { chronoFrozenMs ?: it.invoke() }
        val base = (chronoBaseMs ?: (frozenDisplayMs ?: TimeKeeper.nowMs())) / 1000.0
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

    private fun numeralRadiusFactor(): Float = if (hoursOnDial == 12) 0.76f else 0.68f

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

    private fun numeralPosition(hour: Int, cx: Float, cy: Float, r: Float): PointF {
        val angle = hour.toFloat() / hoursOnDial * 360f
        return pointAt(cx, cy, angle, boundaryRadius(angle) * numeralRadiusFactor())
    }

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
                onSelectedHoursChanged?.invoke(selectedHours.toSet())
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
        onKnocked?.invoke()
    }

    private fun isMoonFallen(): Boolean = fallenBodies.any { it.kind == BodyKind.MOON }

    private fun isDateFallen(): Boolean = fallenBodies.any { it.kind == BodyKind.DATE }

    private fun dropHands(impulseX: Float, impulseY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (r <= 0f) return
        // Winding state makes no sense once the hands are off the axis.
        spring?.cancel()
        spring = null
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0

        val a = currentAngles()
        val ivx = impulseX * 35f
        val ivy = impulseY * 35f
        val drops = ArrayList<Hand>(3)
        drops.add(Hand.HOUR)
        drops.add(Hand.MINUTE)
        if (showSecondHand) drops.add(Hand.SECOND)
        for (hand in drops) {
            if (isFallen(hand)) continue
            val len = lengthOf(hand) * boundaryRadius(angleOf(hand, a))
            val tail = tailOf(hand) * r
            addRodBody(
                BodyKind.HAND, hand, angleOf(hand, a),
                len, tail, widthOf(hand) * r * 2f, cx, cy, ivx, ivy
            )
        }
        if (fastHand != FastHandMode.NONE && !isFastHandFallen()) {
            addRodBody(
                BodyKind.FAST_HAND, null, a.fast,
                FAST_LEN * r, 0.05f * r, 0.008f * r * 2f, cx, cy, ivx, ivy
            )
        }
        // Complications aren't screwed on any tighter than the hands.
        if (chronoProvider == null) {
            if (showMoonPhase && !isMoonFallen()) {
                fallenBodies.add(
                    FallingBody(
                        kind = BodyKind.MOON, hand = null, numeralHour = 0, label = "",
                        x = cx, y = cy + apothemRadius() * 0.45f,
                        vx = ivx + Random.nextFloat() * 200f - 100f,
                        vy = ivy - Random.nextFloat() * 200f,
                        angleDeg = 0f, angVel = Random.nextFloat() * 240f - 120f,
                        halfLen = r * 0.07f, strokeWidth = 0f, textSize = 0f
                    )
                )
            }
            if (showDate && !isDateFallen()) {
                val label = dateText()
                datePaint.textSize = r * 0.085f
                fallenBodies.add(
                    FallingBody(
                        kind = BodyKind.DATE, hand = null, numeralHour = 0, label = label,
                        x = cx, y = cy - apothemRadius() * 0.42f,
                        vx = ivx + Random.nextFloat() * 200f - 100f,
                        vy = ivy - Random.nextFloat() * 150f,
                        angleDeg = 90f, angVel = Random.nextFloat() * 200f - 100f,
                        halfLen = datePaint.measureText(label) / 2f,
                        strokeWidth = 0f, textSize = r * 0.085f
                    )
                )
            }
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
        for (b in fallenBodies) {
            if (b === carriedBody) continue
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
                // Contain each end inside the dial's (possibly polygonal)
                // boundary; the push-back is radial, which is a good enough
                // approximation for toy debris.
                val endAngle = Math.toDegrees(
                    atan2((ex - cx).toDouble(), -(ey - cy).toDouble())
                ).toFloat()
                val rIn = boundaryRadius(endAngle) * 0.96f
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
        resolveBodyBodyCollisions()
        resolveMountedHandCollisions(cx, cy, dialRadius())
        // Rest: pieces that have all but stopped are put fully to sleep, so
        // a settled heap stays settled instead of buzzing.
        for (b in fallenBodies) {
            if (b === carriedBody) continue
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

    private fun sampleBodyPoints(b: FallingBody, out: FloatArray) {
        val rad = Math.toRadians(b.angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        for (k in 0 until SAMPLE_COUNT) {
            val t = k / (SAMPLE_COUNT - 1f) * 2f - 1f
            out[k * 2] = b.x + dx * b.halfLen * t
            out[k * 2 + 1] = b.y + dy * b.halfLen * t
        }
    }

    private fun bodyRadius(b: FallingBody): Float = max(
        when (b.kind) {
            BodyKind.NUMERAL -> b.textSize * 0.30f
            BodyKind.DATE -> b.textSize * 0.35f
            BodyKind.MOON -> b.halfLen * 0.9f
            else -> b.strokeWidth * 0.5f
        },
        10f
    )

    /** Fallen pieces bump into each other (sampled-circle approximation). */
    private fun resolveBodyBodyCollisions() {
        val n = fallenBodies.size
        if (n < 2) return
        for (i in 0 until n - 1) {
            val a = fallenBodies[i]
            sampleBodyPoints(a, sampleBufA)
            for (j in i + 1 until n) {
                val b = fallenBodies[j]
                sampleBodyPoints(b, sampleBufB)
                val minDist = bodyRadius(a) + bodyRadius(b)
                contact@ for (p in 0 until SAMPLE_COUNT) {
                    for (q in 0 until SAMPLE_COUNT) {
                        val dx = sampleBufA[p * 2] - sampleBufB[q * 2]
                        val dy = sampleBufA[p * 2 + 1] - sampleBufB[q * 2 + 1]
                        val d = hypot(dx, dy)
                        if (d < minDist && d > 0.001f) {
                            val nx = dx / d
                            val ny = dy / d
                            val push = (minDist - d) / 2f
                            if (a !== carriedBody) {
                                a.x += nx * push
                                a.y += ny * push
                            }
                            if (b !== carriedBody) {
                                b.x -= nx * push
                                b.y -= ny * push
                            }
                            val relVn = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny
                            if (relVn < 0f) {
                                val impulse = -1.4f * relVn / 2f
                                val spin = min(kotlin.math.abs(impulse), 200f)
                                if (a !== carriedBody) {
                                    a.vx += impulse * nx
                                    a.vy += impulse * ny
                                    a.angVel += (Random.nextFloat() - 0.5f) * spin
                                }
                                if (b !== carriedBody) {
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
     * Fallen pieces collide with the hands still mounted on the axis — the
     * ticking second hand bats debris around the dial.
     */
    private fun resolveMountedHandCollisions(cx: Float, cy: Float, r: Float) {
        val a = currentAngles()
        for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
            if (hand == Hand.SECOND && !showSecondHand) continue
            if (isFallen(hand)) continue
            val angle = angleOf(hand, a)
            val tip = pointAt(cx, cy, angle, boundaryRadius(angle) * lengthOf(hand))
            val tail = pointAt(cx, cy, angle + 180f, r * tailOf(hand))
            collideBodiesWithSegment(tail.x, tail.y, tip.x, tip.y, widthOf(hand) * r)
        }
        if ((fastHand != FastHandMode.NONE || chronoProvider != null) && !isFastHandFallen()) {
            val tip = pointAt(cx, cy, a.fast, r * FAST_LEN)
            collideBodiesWithSegment(cx, cy, tip.x, tip.y, 0.008f * r)
        }
    }

    private fun collideBodiesWithSegment(x1: Float, y1: Float, x2: Float, y2: Float, halfWidth: Float) {
        val segDx = x2 - x1
        val segDy = y2 - y1
        val len2 = segDx * segDx + segDy * segDy
        if (len2 <= 0f) return
        for (b in fallenBodies) {
            if (b === carriedBody) continue
            sampleBodyPoints(b, sampleBufA)
            val minDist = bodyRadius(b) + halfWidth + 2f
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
            // Complications go back to their own homes.
            BodyKind.MOON ->
                hypot(x - cx, y - (cy + apothemRadius() * 0.45f)) < r * 0.15f
            BodyKind.DATE ->
                hypot(x - cx, y - (cy - apothemRadius() * 0.42f)) < r * 0.15f
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

    /**
     * Rate at which this dial's time flows: 1 normal, 0 seized, -1 running
     * backwards. Knocked-about world clocks lose their minds this way.
     */
    var timeScale = 1f
        set(value) {
            if (field == value) return
            // Pivot on the current displayed time so the hands don't jump.
            scaleAnchorDisplayMs = displayNowMs()
            scaleAnchorRealMs = TimeKeeper.nowMs()
            field = value
            invalidate()
        }
    private var scaleAnchorDisplayMs = 0L
    private var scaleAnchorRealMs = 0L

    private fun displayNowMs(): Long {
        frozenDisplayMs?.let { return it }
        val now = TimeKeeper.nowMs()
        if (timeScale == 1f) return now
        return scaleAnchorDisplayMs + ((now - scaleAnchorRealMs) * timeScale).toLong()
    }

    /**
     * Magnet grid for setting durations: 5-minute multiples (which covers
     * quarter, half and full hours) above 5 minutes, 30-second multiples
     * below. Returns the detent value when [ms] is within its capture
     * window, null otherwise.
     */
    internal fun magnetFor(ms: Long, hand: Hand?): Long? {
        // Measured from where the winding started, not from midnight.
        //
        // The countdown grid gets coarser as the duration grows — minutes at
        // first, then five, then quarters, then hours — because that is the
        // shape of the durations people actually set. Read off the absolute
        // time of day it was nonsense for a length: an event beginning at
        // 18:00 landed straight in the "over two hours, hours only" band, so
        // there was no detent anywhere near "and it lasts twenty minutes".
        // The progression is the same one; it just starts where the event
        // does.
        val rel = if (magnetOrigin != 0L) {
            val day = 86_400_000L
            ((ms - magnetOrigin) % day + day) % day
        } else {
            ms
        }
        if (rel < 0) return null
        // The grid follows the hand in your fingers: the familiar 5-minute
        // grid on the minute hand, whole hours on the hour hand.
        val (grid, window) = when (hand) {
            // The second hand has its own detents, applied while dragging;
            // by release time its value is already where it should be, to
            // the second, and no coarser grid gets to round it away.
            Hand.SECOND -> return null
            Hand.HOUR -> 3_600_000L to 600_000L
            else -> when (magnetProfile) {
                MagnetProfile.ALARM -> 300_000L to 40_000L
                MagnetProfile.COUNTDOWN -> when {
                    rel < 5 * 60_000L -> 60_000L to 10_000L
                    rel < 30 * 60_000L -> 300_000L to 40_000L
                    rel < 120 * 60_000L -> 900_000L to 90_000L
                    else -> 3_600_000L to 300_000L
                }
            }
        }
        if (grid <= 0L) return null
        val rounded = (rel + grid / 2) / grid * grid
        return if (kotlin.math.abs(rounded - rel) <= window) rounded + magnetOrigin else null
    }

    private fun snapCountdown(ms: Long, hand: Hand?): Long = magnetFor(ms, hand) ?: ms

    /**
     * Chronograph value including any winding offset and hold-freeze. May be
     * negative while playing — the spring brings it back, and the countdown
     * commit clamps at zero.
     */
    /** What the dial currently reads while a time is being set, for tests. */
    internal fun settingValueMs(): Long? = chronoDisplayMs()

    /**
     * What the digital readout says while something is being wound.
     *
     * For a time of day, the time — the hands and the number agree, which is
     * the point. For a length, the *length*: the hands are showing the hour
     * the thing ends at, but what is being chosen is how long it runs, and a
     * readout repeating the hour the hands already show answers a question
     * nobody asked. Measured from the hour it starts at, the same origin the
     * magnets count from.
     */
    internal fun readoutForTest(): Long = settingReadoutMs()

    private fun settingReadoutMs(): Long {
        val shown = chronoDisplayMs() ?: 0L
        if (magnetOrigin == 0L) return shown
        val day = 86_400_000L
        return ((shown - magnetOrigin) % day + day) % day
    }

    private fun chronoDisplayMs(): Long? = chronoProvider?.let { provider ->
        val raw = (chronoFrozenMs ?: provider()) + (visualOffsetSeconds * 1000.0).toLong()
        if (chronoWrapsDay) {
            val day = 86_400_000L
            ((raw % day) + day) % day
        } else {
            raw
        }
    }

    // --------------------------------------------------------- accessibility

    /**
     * A view drawn on a Canvas is, to a screen reader, a blank rectangle.
     * The node the framework builds is where the reading belongs — not an
     * override of getContentDescription(), which also feeds internal View
     * machinery that has no business hearing about the time.
     *
     * Filled in on demand, so it is current without a stream of
     * announcements nobody asked for.
     */
    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (info.contentDescription.isNullOrBlank()) {
            info.contentDescription = describeDial()
        }
    }

    /** What this dial would say out loud. */
    private fun describeDial(): CharSequence {
        val chrono = chronoDisplayMs()
        if (chrono != null) return spokenDuration(chrono)
        cal.timeInMillis = displayNowMs()
        val time = java.text.DateFormat
            .getTimeInstance(java.text.DateFormat.SHORT)
            .apply { timeZone = cal.timeZone }
            .format(java.util.Date(cal.timeInMillis))
        // Bubbles carry their city inside the dial; it belongs in the
        // reading too, or six of them all say the same thing.
        return dialLabel?.let { context.getString(R.string.a11y_city_time, it, time) } ?: time
    }

    /** Hours, minutes and seconds in words rather than as a bare 00:00. */
    private fun spokenDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>(3)
        if (h > 0) parts.add(context.getString(R.string.a11y_hours, h))
        if (m > 0) parts.add(context.getString(R.string.a11y_minutes, m))
        if (s > 0 || parts.isEmpty()) parts.add(context.getString(R.string.a11y_seconds, s))
        return parts.joinToString(" ")
    }

    private fun computeAngles(): Angles {
        chronoDisplayMs()?.let { duration ->
            val totalSec = duration / 1000.0
            // Winding the minute or hour hand on a whole-minute value: the
            // seconds are already polarized to zero, so spinning them like
            // mad adds nothing but noise. They stay put.
            val secondsPinned = chronoSettable && draggedHand != null &&
                draggedHand != Hand.SECOND && (chronoFrozenMs ?: 0L) % 60_000L == 0L
            return Angles(
                hour = ((totalSec / 3600.0) % hoursOnDial / hoursOnDial * 360.0).toFloat(),
                minute = ((totalSec / 60.0) % 60.0 / 60.0 * 360.0).toFloat(),
                second = if (secondsPinned) 0f else ((totalSec % 60.0) / 60.0 * 360.0).toFloat(),
                fast = if (secondsPinned) 0f else (duration % 1000L) / 1000f * 360f
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

    /** Target angles, blended with the mode-transition animation if active. */
    private fun currentAngles(): Angles {
        val target = computeAngles()
        val from = transitionFrom ?: return target
        val t = (SystemClock.uptimeMillis() - transitionStartAt) / TRANSITION_MS
        if (t >= 1f) {
            transitionFrom = null
            return target
        }
        val f = transitionInterpolator.getInterpolation(t.coerceIn(0f, 1f))
        return Angles(
            hour = lerpAngle(from.hour, target.hour, f),
            minute = lerpAngle(from.minute, target.minute, f),
            second = lerpAngle(from.second, target.second, f),
            fast = lerpAngle(from.fast, target.fast, f)
        )
    }

    private fun lerpAngle(from: Float, to: Float, fraction: Float): Float =
        from + normalizeDeg(to - from) * fraction

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
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

    /**
     * Polygonal faces read smaller than a circle of the same circumradius
     * (their edges sit at the apothem), so each shape gets a size boost that
     * brings its edges near the screen margins without clipping the corners.
     */
    private fun shapeBoost(): Float = when (dialShape) {
        DialShape.TRIANGLE -> 1.25f
        DialShape.SQUARE -> 1.30f
        DialShape.HEXAGON -> 1.12f
        DialShape.OCTAGON -> 1.06f
        else -> 1f
    }

    private fun dialRadius(): Float = min(width, height) / 2f * 0.92f * dialScale * shapeBoost()

    /** The dial's current outer radius, for hosts that need it (bubbles). */
    fun currentDialRadius(): Float = dialRadius()

    /**
     * Distance from the center to the dial's edge at [angleDeg], measured
     * clockwise from 12. A circle returns the radius; a polygon returns the
     * distance to its boundary, largest at the corners and smallest at the
     * edge midpoints (the apothem).
     */
    private fun boundaryRadius(angleDeg: Float): Float {
        val r = dialRadius()
        val n = dialShape.sides
        if (n < 3) return r
        val half = 180f / n
        var psi = (angleDeg - dialShape.vertexOffsetDeg) % (2f * half)
        if (psi < 0f) psi += 2f * half
        val apothemFraction = cos(Math.toRadians(half.toDouble())).toFloat()
        return r * apothemFraction / cos(Math.toRadians((psi - half).toDouble())).toFloat()
    }

    /** The polygon's inscribed radius — the safe zone for inner complications. */
    private fun apothemRadius(): Float {
        val n = dialShape.sides
        val r = dialRadius()
        return if (n < 3) r else r * cos(Math.toRadians(180.0 / n)).toFloat()
    }

    private val facePath = Path()

    private fun buildFacePath(cx: Float, cy: Float) {
        val n = dialShape.sides
        val r = dialRadius()
        facePath.reset()
        for (k in 0 until n) {
            val p = pointAt(cx, cy, dialShape.vertexOffsetDeg + k * 360f / n, r)
            if (k == 0) facePath.moveTo(p.x, p.y) else facePath.lineTo(p.x, p.y)
        }
        facePath.close()
    }

    // ----------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fallenBodies.isNotEmpty()) stepPhysics()

        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()

        // Case hardware sits behind the face so it reads as attached to it.
        if (chronoButtons || SystemClock.uptimeMillis() - buttonsAnimStart < 500L) {
            drawChronoHardware(canvas, cx, cy, r)
        }

        rimPaint.strokeWidth = r * 0.02f
        if (dialShape == DialShape.CIRCLE) {
            canvas.drawCircle(cx, cy, r, facePaint)
            canvas.drawCircle(cx, cy, r, rimPaint)
        } else {
            buildFacePath(cx, cy)
            canvas.drawPath(facePath, facePaint)
            canvas.drawPath(facePath, rimPaint)
        }

        drawTicks(canvas, cx, cy, r)
        drawNumerals(canvas, cx, cy, r)
        dialLabel?.let { label ->
            datePaint.textSize = r * 0.15f
            canvas.drawText(
                label, cx,
                cy - apothemRadius() * 0.42f - (datePaint.ascent() + datePaint.descent()) / 2f,
                datePaint
            )
        }
        if (chronoProvider == null) {
            if (showDate) drawDate(canvas, cx, cy, r)
            if (eventArcs.isNotEmpty() || alarmMarkers.isNotEmpty()) {
                drawMarksLayer(canvas, cx, cy, r)
            }
        }
        // The sky complication is the one that also belongs on a face
        // standing for a fixed time — the little dials on the alarm cards
        // and the big one while a time is being wound onto it. Those all
        // run on a chrono provider, so it is drawn outside that guard;
        // leaving it inside is why 14.1's token appeared on C0 only.
        if (showMoonPhase) drawMoonPhase(canvas, cx, cy, r)

        val a = currentAngles()

        if (chronoProvider != null && laps.isNotEmpty()) {
            // A ghost is the hand itself, faded: same colour, same length,
            // same width. Dashes and thin outlines read as decoration, not
            // as "this is where the hands were".
            for ((i, lap) in laps.withIndex()) {
                val alpha = 60 + 150 * (i + 1) / laps.size
                for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
                    if (hand == Hand.SECOND && !showSecondHand) continue
                    val angle = when (hand) {
                        Hand.HOUR -> lap.hour
                        Hand.MINUTE -> lap.minute
                        Hand.SECOND -> lap.second
                    }
                    val paint = paintOf(hand)
                    val was = paint.alpha
                    paint.alpha = alpha
                    drawHand(
                        canvas, cx, cy, angle,
                        boundaryRadius(angle) * lengthOf(hand),
                        r * tailOf(hand),
                        r * widthOf(hand),
                        paint
                    )
                    paint.alpha = was
                }
            }

        }

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
            val angle = angleOf(hand, a)
            drawHand(
                canvas, cx, cy,
                angle,
                boundaryRadius(angle) * lengthOf(hand),
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
            // A face used as a plain time-of-day badge wants hands and
            // nothing else.
            !showDigitalReadout -> null
            // While setting a duration the readout must follow the hands —
            // that is what you are reading as you wind. Otherwise it reports
            // the mechanism's own value, so a wound hand cannot drag the
            // display into negative time.
            chronoProvider != null && chronoSettable ->
                formatDuration(settingReadoutMs())
            chronoProvider != null -> formatDuration(chronoProvider?.invoke() ?: 0L)
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
            val yTop = min(cy + boundaryRadius(180f) + digitH * 0.4f, height - digitH * 1.6f)
            val liveUnits = when {
                chronoProvider != null && chronoSettable -> unitsFor(settingReadoutMs())
                chronoProvider != null -> unitsFor(chronoProvider?.invoke() ?: 0L)
                else -> UNITS_CLOCK
            }
            ladderTapTop = yTop
            drawSevenSegment(canvas, it, cx, yTop, digitH, liveUnits)

            // Ghost copies of the recent laps, stacked under the readout —
            // as many as the space below the dial can hold.
            if (chronoProvider != null && laps.isNotEmpty()) {
                // Newest lap first and largest, each older one a step
                // smaller and fainter — a receding stack, which also means
                // several more of them fit in the same strip.
                var ghostY = yTop + digitH * 1.28f
                // The strip between the readout and the button row holds
                // exactly seven rungs: with the step ratio fixed, the ladder
                // is a geometric series, so the first rung is the strip
                // divided by what seven rungs come to — no clamp, no
                // leftover gap above the button.
                val bottom = height - BUTTON_RESERVE_DP * resources.displayMetrics.density
                val firstH = ((bottom - ghostY) / LADDER_SPAN)
                    .coerceAtLeast(digitH * 0.20f)
                var ghostH = firstH
                var shown = 0
                for (lap in laps.reversed()) {
                    if (shown >= MAX_GHOST_LAPS) break
                    if (ghostY + ghostH > bottom + 1f) break
                    shown++
                    digitalPaint.alpha = (200f * (ghostH / firstH))
                        .toInt().coerceIn(45, 200)
                    drawSevenSegment(
                        canvas, formatDuration(lap.ms), cx, ghostY, ghostH, unitsFor(lap.ms)
                    )
                    ghostY += ghostH * 1.35f
                    ghostH *= 0.88f
                }
                digitalPaint.alpha = 255
            }
        }

        if (SystemClock.uptimeMillis() < cheaterUntil && cheaterFade < 1f) {
            cheaterPaint.textSize = r * 0.24f
            cheaterPaint.alpha = ((1f - cheaterFade) * 255).toInt()
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

        // The lap list, unfolded: a tap on the ladder opens it, a drag
        // scrolls it, a tap closes it. Every lap, uniform size, newest
        // first, the faked ones in the second hand's red.
        if (lapsExpanded && laps.isEmpty()) lapsExpanded = false
        if (lapsExpanded && chronoProvider != null) {
            scrimPaint.color = theme.face
            scrimPaint.alpha = 242
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            val rowDigitH = r * 0.105f
            val rowH = rowDigitH * 1.9f
            val topMargin = height * 0.07f
            val keepColor = digitalPaint.color
            canvas.save()
            canvas.clipRect(0f, topMargin * 0.5f, width.toFloat(), height - topMargin * 0.5f)
            var y = topMargin - lapListScroll
            for (i in laps.indices.reversed()) {
                if (y + rowDigitH > topMargin * 0.5f && y < height) {
                    val lap = laps[i]
                    digitalPaint.color = if (lap.fake) theme.secondHand else keepColor
                    drawSevenSegment(
                        canvas,
                        String.format(Locale.US, "%d %s", i + 1, formatDuration(lap.ms)),
                        width / 2f, y, rowDigitH, unitsFor(lap.ms)
                    )
                }
                y += rowH
            }
            canvas.restore()
            digitalPaint.color = keepColor
        }

        // The hour hand names what it is standing on, then the bubble is
        // drawn last of all, over everything including the lap scrim.
        followHourHand(a.hour)
        drawMarkBubble(canvas, r)
        checkShownDay()
    }

    /** How far the unfolded lap list can scroll past the bottom edge. */
    private fun maxLapScroll(): Float {
        val r = dialRadius()
        val rowH = r * 0.105f * 1.9f
        return (laps.size * rowH - height * 0.86f).coerceAtLeast(0f)
    }

    /**
     * MM:SS:CC with live centiseconds under an hour; from the hour mark on
     * the centiseconds yield their slots to hours — HH:MM:SS. Which is which
     * is written in the corner marks, via [unitsFor].
     */
    private fun formatDuration(ms: Long): String {
        val abs = kotlin.math.abs(ms)
        val sign = if (ms < 0) "-" else ""
        return if (abs < 3_600_000L) {
            String.format(
                Locale.US, "%s%02d:%02d:%02d",
                sign, abs / 60_000, abs / 1000 % 60, abs / 10 % 100
            )
        } else {
            String.format(
                Locale.US, "%s%02d:%02d:%02d",
                sign, abs / 3_600_000 % 100, abs / 60_000 % 60, abs / 1000 % 60
            )
        }
    }

    /**
     * The corner marks for a chronograph reading: degrees-minutes-seconds
     * style, so the tail gives the scale away — a reading whose last group
     * is unmarked ends in hundredths, one ending in \u2033 ends in seconds.
     */
    private fun unitsFor(ms: Long): Array<String> =
        if (kotlin.math.abs(ms) < 3_600_000L) UNITS_STOPWATCH else UNITS_CLOCK

    /**
     * Chronograph furniture, fading in and out with the mode transition:
     * a large crown at 12 (tap it…), the start/stop pusher at 1:30
     * (accent-tinted while running, on the thumb side) and a smaller reset
     * pusher at 10:30.
     */
    private fun drawChronoHardware(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val t = ((SystemClock.uptimeMillis() - buttonsAnimStart) / 500f).coerceIn(0f, 1f)
        val visibility = if (chronoButtons) t else 1f - t
        if (visibility <= 0f) return
        val alpha = (visibility * 255).toInt()

        // Crown at 12: big, knurled, capped and jeweled. On polygonal cases
        // the hardware sits on the actual edge, wherever that is.
        val bCrown = boundaryRadius(0f)
        pusherPaint.color = theme.rim
        pusherPaint.alpha = alpha
        val crownOuter = if (pressedPusher == 3) bCrown * 1.08f else bCrown * 1.12f
        drawCaseStub(canvas, cx, cy, bCrown * 0.90f, 0f, r * 0.085f, crownOuter)
        // Knurling: five winding ridges across the crown body.
        rimPaint.strokeWidth = r * 0.010f
        rimPaint.alpha = (alpha * 0.8f).toInt()
        for (i in -2..2) {
            val offset = i * r * 0.032f
            canvas.drawLine(
                cx + offset, cy - bCrown * 1.005f,
                cx + offset, cy - crownOuter + r * 0.012f,
                rimPaint
            )
        }
        rimPaint.alpha = 255
        // Cap band in the tick color, and a jewel in the accent color.
        pusherPaint.color = theme.tick
        pusherPaint.alpha = (alpha * 0.85f).toInt()
        val cap = RectF(
            cx - r * 0.085f, cy - crownOuter,
            cx + r * 0.085f, cy - crownOuter + r * 0.028f
        )
        canvas.drawRoundRect(cap, r * 0.014f, r * 0.014f, pusherPaint)
        pusherPaint.color = theme.decimal
        pusherPaint.alpha = alpha
        canvas.drawCircle(cx, cy - (crownOuter - r * 0.014f), r * 0.015f, pusherPaint)

        // Start/stop pusher at 1:30.
        val bStart = boundaryRadius(45f)
        pusherPaint.color = if (chronoRunning) theme.secondHand else theme.rim
        pusherPaint.alpha = alpha
        drawCaseStub(
            canvas, cx, cy, bStart * 0.90f, 45f, r * 0.06f,
            if (pressedPusher == 1) bStart * 1.06f else bStart * 1.11f
        )

        // Reset pusher at 10:30, smaller.
        val bReset = boundaryRadius(315f)
        pusherPaint.color = theme.rim
        pusherPaint.alpha = alpha
        drawCaseStub(
            canvas, cx, cy, bReset * 0.90f, 315f, r * 0.042f,
            if (pressedPusher == 2) bReset * 1.05f else bReset * 1.09f
        )

        if (t < 1f) invalidate()
    }

    private fun drawCaseStub(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        inner: Float,
        angleDeg: Float,
        halfWidth: Float,
        outer: Float
    ) {
        canvas.save()
        canvas.rotate(angleDeg - 90f, cx, cy)
        val rect = RectF(cx + inner, cy - halfWidth, cx + outer, cy + halfWidth)
        canvas.drawRoundRect(rect, halfWidth * 0.6f, halfWidth * 0.6f, pusherPaint)
        canvas.restore()
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
                BodyKind.DATE -> {
                    // The capsule runs along the text, so the drawn text is
                    // rotated 90° behind the body angle.
                    datePaint.textSize = b.textSize
                    canvas.save()
                    canvas.translate(b.x, b.y)
                    canvas.rotate(b.angleDeg - 90f)
                    canvas.drawText(
                        b.label, 0f,
                        -(datePaint.ascent() + datePaint.descent()) / 2f,
                        datePaint
                    )
                    canvas.restore()
                }
                BodyKind.MOON -> {
                    canvas.drawCircle(b.x, b.y, b.halfLen, moonLitPaint)
                    moonRimPaint.strokeWidth = b.halfLen * 0.12f
                    canvas.drawCircle(b.x, b.y, b.halfLen, moonRimPaint)
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
            val b = boundaryRadius(angle)
            val isMajor = hoursOnDial == 12 && i % 5 == 0
            val paint = if (isMajor) tickPaint else minorTickPaint
            paint.strokeWidth = if (isMajor) r * 0.018f else r * 0.008f
            val outerLen = if (isMajor) r * 0.08f else r * 0.045f
            val from = pointAt(cx, cy, angle, b * 0.97f - outerLen)
            val to = pointAt(cx, cy, angle, b * 0.97f)
            canvas.drawLine(from.x, from.y, to.x, to.y, paint)
        }
        if (hoursOnDial != 12) {
            for (i in 0 until hoursOnDial) {
                val angle = i.toFloat() / hoursOnDial * 360f
                val b = boundaryRadius(angle)
                tickPaint.strokeWidth = r * 0.018f
                val from = pointAt(cx, cy, angle, b * 0.80f)
                val to = pointAt(cx, cy, angle, b * 0.87f)
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

    /**
     * Alarms are moments, so they get a dot just outside the rim — nothing
     * on the face itself, nothing implying a span. Calendar events, which
     * really do occupy time, get the Sectograph wedge instead.
     */
    private fun drawAlarmMarkers(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (mark in alarmMarkers) {
            val at = markCenter(cx, cy, mark.angle)
            alarmMarkerPaint.color = DayNight.markColor(theme, mark.pm)
            alarmMarkerPaint.alpha = 230
            canvas.drawCircle(at.x, at.y, r * 0.022f, alarmMarkerPaint)
        }
    }

    /** The dots' outlines, in a second pass so the blending never eats them. */
    private fun drawAlarmMarkerRings(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (mark in alarmMarkers) {
            if (!mark.fromCalendar) continue
            val at = markCenter(cx, cy, mark.angle)
            markRingPaint.color = ClockThemes.contrastInk(theme)
            markRingPaint.strokeWidth = r * 0.009f
            canvas.drawCircle(at.x, at.y, r * 0.028f, markRingPaint)
        }
    }

    private fun markCenter(cx: Float, cy: Float, angle: Float) =
        pointAt(cx, cy, angle, boundaryRadius(angle) * 1.055f)


    /**
     * How much of a wedge is still ahead of the minute hand, 1 down to 0.
     *
     * An event that has run its course leaves the dial rather than sitting
     * there looking pending — and it goes out gradually, as the hand crosses
     * it, so the fading itself reads as "this is happening now".
     */
    private fun arcRemaining(arc: DialArc): Float {
        // An empty range means "no times given"; a negative start means an
        // event that began yesterday, which is a real thing and used to
        // switch the fade off entirely — the guard read one as the other.
        if (arc.endMinute <= arc.startMinute) return 1f
        // The time the dial is *showing*, offset and all. Carry the hands
        // forward and the day's events are consumed under your finger,
        // which is the whole of what a Sectograph dial is for: one turn of
        // a hand and you have seen what is coming.
        val now = shownMinuteOfDay()
        // Plain comparisons, not modular ones: these minutes belong to a
        // named day, and an event whose start is still ahead of the shown
        // time has simply not begun. Winding past midnight is not this
        // method's problem — the host rebuilds the marks for the new date,
        // and until it does everything reads as still to come, which is
        // exactly what tomorrow's events are.
        return when {
            now <= arc.startMinute -> 1f
            now >= arc.endMinute -> 0f
            else -> 1f - (now - arc.startMinute) / (arc.endMinute - arc.startMinute)
        }
    }

    /** Minutes past midnight of the time the dial is showing. */
    private fun shownMinuteOfDay(): Float {
        cal.timeInMillis = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60f +
            cal.get(java.util.Calendar.MINUTE) + cal.get(java.util.Calendar.SECOND) / 60f
    }

    /**
     * The part of a wedge still ahead of the hand.
     *
     * A wedge is eaten from its start, not faded as a whole: the hand
     * crosses it and it gets shorter behind, so what is left on the face is
     * literally the time left. Fading the whole thing said "this is going
     * away" without saying how much of it had gone.
     */
    private fun buildArcPath(arc: DialArc, cx: Float, cy: Float, left: Float) {
        val eaten = 1f - left.coerceIn(0f, 1f)
        val from = arc.start + arc.sweep * eaten
        val sweep = arc.sweep * left.coerceIn(0f, 1f)
        val steps = max(2, (kotlin.math.abs(sweep) / 3f).toInt())
        alarmMarkerPath.reset()
        // Inner edge outward, then back along the outer edge.
        for (i in 0..steps) {
            val a = from + sweep * i / steps
            val p = pointAt(cx, cy, a, boundaryRadius(a) * 0.885f)
            if (i == 0) alarmMarkerPath.moveTo(p.x, p.y) else alarmMarkerPath.lineTo(p.x, p.y)
        }
        for (i in steps downTo 0) {
            val a = from + sweep * i / steps
            val p = pointAt(cx, cy, a, boundaryRadius(a) * 0.965f)
            alarmMarkerPath.lineTo(p.x, p.y)
        }
        alarmMarkerPath.close()
    }

    /**
     * Full strength while an event is still ahead, dimmer once it has
     * started — so the shortening says how much is left and the dimming
     * says it is under way. Two facts, two channels.
     */
    private fun arcAlpha(left: Float): Int = if (left >= 1f) 230 else 150

    private fun drawEventArcs(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (arc in eventArcs) {
            val left = arcRemaining(arc)
            if (left <= 0f) continue
            alarmMarkerPaint.color = DayNight.markColor(theme, arc.pm)
            alarmMarkerPaint.alpha = arcAlpha(left)
            buildArcPath(arc, cx, cy, left)
            canvas.drawPath(alarmMarkerPath, alarmMarkerPaint)
        }
    }

    private fun drawEventArcOutlines(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (arc in eventArcs) {
            if (!arc.fromCalendar) continue
            val left = arcRemaining(arc)
            if (left <= 0f) continue
            markRingPaint.color = ClockThemes.contrastInk(theme)
            markRingPaint.alpha = if (left >= 1f) 255 else 165
            markRingPaint.strokeWidth = r * 0.008f
            buildArcPath(arc, cx, cy, left)
            canvas.drawPath(alarmMarkerPath, markRingPaint)
        }
        markRingPaint.alpha = 255
    }

    /**
     * Marks, drawn as light rather than as paint.
     *
     * Two events at the same hour used to be one event: the second wedge
     * covered the first and the dial simply lied about how busy the day was.
     * Drawn into a transparent layer with the colours adding, an overlap
     * brightens instead — green over blue comes out turquoise, and the rule
     * is one the eye already knows, so the user can predict it without being
     * told. The layer is what makes it work: added against the dial's own
     * face the marks would wash the face out too.
     */
    private fun drawMarksLayer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        alarmMarkerPaint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.ADD
        )
        if (eventArcs.isNotEmpty()) drawEventArcs(canvas, cx, cy, r)
        if (alarmMarkers.isNotEmpty()) drawAlarmMarkers(canvas, cx, cy, r)
        alarmMarkerPaint.xfermode = null
        canvas.restoreToCount(saved)
        // Outlines after the layer, at full strength: they say "today only",
        // and a ring that brightened along with an overlap would say it in a
        // different voice every time two things collided.
        if (eventArcs.isNotEmpty()) drawEventArcOutlines(canvas, cx, cy, r)
        if (alarmMarkers.isNotEmpty()) drawAlarmMarkerRings(canvas, cx, cy, r)
    }

    /**
     * The sky complication: one glyph, low on the face, showing what is
     * actually up there — the sun while the sun is up, the moon and its
     * phase once it has set.
     *
     * There is only ever one of them, in one place, because they are two
     * states of a single thing and not two features. Earlier this was drawn
     * as a second token stacked over the moon, which was both wrong and
     * ugly.
     *
     * The point of the sun half is the person working indoors with no
     * window: the dial tells them the hour, and this tells them whether the
     * hour is a light one. Which needs a real sunrise, so it needs a
     * location. Without one the app does not guess — it shows the moon,
     * whose phase is arithmetic that works anywhere on Earth.
     *
     * It reads the time the dial is *displaying* rather than the wall clock:
     * a fixed little face shows its own hour, and the big dial being wound
     * shows whatever the hands are on this instant. So the sun sets under
     * your finger as you carry the hour hand forward, which is the only way
     * to watch a whole day go past without waiting for one.
     */
    private fun drawMoonPhase(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (isMoonFallen()) return
        moonRimPaint.strokeWidth = r * 0.008f
        SkyGlyph.draw(
            canvas, cx, cy + apothemRadius() * 0.45f, r * 0.07f,
            moonLitPaint, moonDarkPaint, moonRimPaint, shownTimeOfDayMs()
        )
    }

    /**
     * The time of day the dial is showing, wound offset and all: a fixed
     * face reports its own hour, a running one reports now, and one being
     * dragged reports where the hands actually are.
     */
    private fun shownTimeOfDayMs(): Long {
        val chrono = chronoDisplayMs()
        if (chrono != null) return chrono
        cal.timeInMillis = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 3_600_000L +
            cal.get(java.util.Calendar.MINUTE) * 60_000L
    }

    private fun dateText(): String {
        cal.timeInMillis = displayNowMs()
        return when (dateFormatStyle) {
            DateFormatStyle.NUMBER -> numberDateFormat.format(Date(cal.timeInMillis))
            DateFormatStyle.TEXT -> textDateFormat.format(Date(cal.timeInMillis))
            DateFormatStyle.ROMAN -> {
                val day = Roman.of(cal.get(Calendar.DAY_OF_MONTH))
                val month = Roman.of(cal.get(Calendar.MONTH) + 1)
                val year = Roman.of(cal.get(Calendar.YEAR))
                "$day·$month·$year"
            }
        }
    }

    private fun drawDate(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (isDateFallen()) return
        val text = dateText()
        datePaint.textSize = r * 0.085f
        val baseline = cy - apothemRadius() * 0.42f - (datePaint.ascent() + datePaint.descent()) / 2f
        canvas.drawText(text, cx, baseline, datePaint)
    }

    // Seven-segment bits, ordered a(64) b(32) c(16) d(8) e(4) f(2) g(1).
    private fun drawSevenSegment(
        canvas: Canvas,
        text: String,
        cx: Float,
        top: Float,
        digitH: Float,
        units: Array<String>? = null
    ) {
        val digitW = digitH * 0.55f
        val gap = digitW * 0.45f
        val markW = digitW * 0.34f
        // The colon is punctuation, not a digit: it takes less room than one
        // and closes ranks with the groups either side of it.
        val colonW = digitW * 0.34f
        val tightGap = gap * 0.45f

        // Each unit mark sits in the top corner right after its group of
        // digits, superscript-style, before the colon: 01°:23′:45″.
        fun markWidth(m: String): Float = when (m) {
            "\"" -> markW * 1.55f
            "" -> 0f
            else -> markW
        }

        /** Width of one glyph plus the space after it. */
        fun advanceAt(i: Int): Float {
            val c = text[i]
            val w = when (c) {
                ':' -> colonW
                ' ' -> digitW * 0.7f
                else -> digitW
            }
            val next = text.getOrNull(i + 1)
            return w + if (c == ':' || next == ':') tightGap else gap
        }

        var totalW = 0f
        var g = 0
        for (i in text.indices) {
            if (text[i] == ':') {
                totalW += (units?.getOrNull(g)?.let { markWidth(it) } ?: 0f)
                g++
            }
            totalW += advanceAt(i)
        }
        totalW += (units?.getOrNull(g)?.let { markWidth(it) } ?: 0f)
        totalW -= if (text.isEmpty()) 0f else
            (advanceAt(text.length - 1) - when (text.last()) {
                ':' -> colonW
                ' ' -> digitW * 0.7f
                else -> digitW
            })

        var x = cx - totalW / 2f
        digitalPaint.strokeWidth = digitH * 0.10f

        // The marks are annotations, not digits: thinner stroke, and only
        // the top quarter of the line.
        fun drawMark(m: String, at: Float) {
            if (m.isEmpty()) return
            val was = digitalPaint.strokeWidth
            digitalPaint.strokeWidth = was * 0.55f
            when (m) {
                "\u00b0" -> {
                    val wasStyle = digitalPaint.style
                    digitalPaint.style = Paint.Style.STROKE
                    canvas.drawCircle(at + markW * 0.42f, top + digitH * 0.01f, digitH * 0.085f, digitalPaint)
                    digitalPaint.style = wasStyle
                }
                "'" -> canvas.drawLine(
                    at + markW * 0.62f, top - digitH * 0.09f,
                    at + markW * 0.38f, top + digitH * 0.11f, digitalPaint
                )
                "\"" -> {
                    canvas.drawLine(
                        at + markW * 0.62f, top - digitH * 0.09f,
                        at + markW * 0.38f, top + digitH * 0.11f, digitalPaint
                    )
                    canvas.drawLine(
                        at + markW * 1.17f, top - digitH * 0.09f,
                        at + markW * 0.93f, top + digitH * 0.11f, digitalPaint
                    )
                }
            }
            digitalPaint.strokeWidth = was
        }

        var group = 0
        for (i in text.indices) {
            val c = text[i]
            when (c) {
                ':' -> {
                    units?.getOrNull(group)?.let {
                        drawMark(it, x - tightGap * 0.4f)
                        x += markWidth(it)
                    }
                    group++
                    canvas.drawPoint(x + colonW / 2f, top + digitH * 0.30f, digitalPaint)
                    canvas.drawPoint(x + colonW / 2f, top + digitH * 0.70f, digitalPaint)
                }
                '-' -> {
                    // Minus sign: the middle (g) segment on its own.
                    val w = digitalPaint.strokeWidth * 0.8f
                    val mid = top + digitH / 2f
                    canvas.drawLine(x + w, mid, x + digitW - w, mid, digitalPaint)
                }
                ' ' -> Unit
                else -> {
                    val digit = c - '0'
                    if (digit in 0..9) drawSegments(canvas, SEGMENT_BITS[digit], x, top, digitW, digitH)
                }
            }
            x += advanceAt(i)
        }
        units?.getOrNull(group)?.let { drawMark(it, x - gap * 0.55f) }
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
        /** How tall a bubble is allowed to get before the note is cut off. */
        private const val MAX_BUBBLE_LINES = 5

        private const val TRANSITION_MS = 700f
        private const val SAMPLE_COUNT = 5

        /** How far a lap may drift from the truth before it is a fake. */
        private const val FAKE_LAP_TOLERANCE_MS = 400L
        private val END_SIDES = floatArrayOf(1f, -1f)
        /** Laps kept: seven show on the ladder, the rest wait in the list. */
        private const val MAX_LAPS = 40

        /** Rungs of the lap ladder, before it would reach the buttons. */
        private const val MAX_GHOST_LAPS = 7

        /** Corner marks, degrees-minutes-seconds style: 01°:23′:45″. */
        private val UNITS_CLOCK = arrayOf("\u00b0", "'", "\"")

        /** Under the hour the tail is hundredths, left unmarked on purpose. */
        private val UNITS_STOPWATCH = arrayOf("'", "\"", "")

        /** Where the second hand's magnets pull, within its minute. */
        private val SECOND_DETENTS = longArrayOf(
            0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L, 45_000L, 60_000L
        )

        /** Room kept clear at the foot of the card for the button row. */
        private const val BUTTON_RESERVE_DP = 72f

        /**
         * What [MAX_GHOST_LAPS] rungs measure, in units of the first rung:
         * 1.35 of each rung's height as spacing, each 0.88 of the one above,
         * plus the last rung itself.
         */
        private const val LADDER_SPAN = 6.49f

        private val SEGMENT_BITS = intArrayOf(
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011
        )
    }
}
