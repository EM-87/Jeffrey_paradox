package com.em87.weirdclock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.animation.doOnEnd
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Analog clock face with unusual options: a 24-hour dial, French
 * Revolutionary decimal-time hands, mirrored (counterclockwise) mode,
 * smooth-sweep seconds, selectable numeral styles and themes, a date
 * complication, grabbable hands that spring back when released, and
 * pinch-to-resize.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NumeralStyle { NONE, ARABIC, ROMAN }
    enum class DateFormatStyle { NUMBER, TEXT, ROMAN }
    private enum class Hand { HOUR, MINUTE, SECOND, DECIMAL }

    var use24hDial = false
        set(value) { field = value; invalidate() }
    var showSecondHand = true
        set(value) { field = value; invalidate() }
    var smoothSeconds = false
        set(value) { field = value; invalidate() }
    var showDecimalHand = false
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
    var dialScale = 1f
        set(value) { field = value.coerceIn(MIN_SCALE, MAX_SCALE); invalidate() }
    var onDialScaleChanged: ((Float) -> Unit)? = null
    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; applyTheme(value); invalidate() }

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
    private val decimalHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val decimalSecondHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val decimalTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val decimalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val numberDateFormat by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    private val textDateFormat by lazy { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }

    /** Drag offsets in degrees, applied on top of the true angle of each hand. */
    private val dragOffsets = HashMap<Hand, Float>()
    private val springAnimators = HashMap<Hand, ValueAnimator>()
    private var draggedHand: Hand? = null

    private class Angles(
        val hour: Float,
        val minute: Float,
        val second: Float,
        val decimalDay: Float,
        val decimalSecond: Float,
        val dayFraction: Double
    )

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
        decimalHandPaint.color = t.decimal
        decimalSecondHandPaint.color = t.decimal
        decimalSecondHandPaint.alpha = 190
        decimalTickPaint.color = t.decimal
        decimalTickPaint.alpha = 140
        decimalTextPaint.color = t.decimal
        centerDotPaint.color = t.centerDot
    }

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

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            val animating = draggedHand != null || springAnimators.isNotEmpty()
            val delay = if (animating || (smoothSeconds && showSecondHand)) {
                16L
            } else {
                1000L - (System.currentTimeMillis() % 1000L)
            }
            postDelayed(this, delay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        springAnimators.values.toList().forEach { it.cancel() }
        springAnimators.clear()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pinchZoomEnabled) {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        }
        if (scaleDetector.isInProgress) {
            releaseDraggedHand()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (touchHandsEnabled) grabHandNear(event.x, event.y)
            MotionEvent.ACTION_MOVE -> dragTo(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN -> releaseDraggedHand()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseDraggedHand()
        }
        return true
    }

    private fun grabHandNear(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val a = computeAngles()
        val candidates = ArrayList<Pair<Hand, Float>>(4)
        if (showSecondHand) candidates.add(Hand.SECOND to SECOND_LEN)
        candidates.add(Hand.MINUTE to MINUTE_LEN)
        candidates.add(Hand.HOUR to HOUR_LEN)
        if (showDecimalHand) candidates.add(Hand.DECIMAL to DECIMAL_LEN)

        var best: Hand? = null
        var bestDistance = max(r * 0.18f, 56f)
        for ((hand, lengthFraction) in candidates) {
            val tip = pointAt(cx, cy, drawnAngle(hand, a), r * lengthFraction)
            val distance = hypot(x - tip.x, y - tip.y)
            if (distance < bestDistance) {
                best = hand
                bestDistance = distance
            }
        }
        best?.let {
            springAnimators.remove(it)?.cancel()
            draggedHand = it
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dragTo(x, y)
        }
    }

    private fun dragTo(x: Float, y: Float) {
        val hand = draggedHand ?: return
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        if (hypot(dx, dy) < dialRadius() * 0.05f) return
        var touchDeg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        if (mirrored) touchDeg = -touchDeg
        val trueAngle = trueAngle(hand, computeAngles())
        dragOffsets[hand] = normalizeDeg(touchDeg - trueAngle)
        invalidate()
    }

    private fun releaseDraggedHand() {
        val hand = draggedHand ?: return
        draggedHand = null
        val start = dragOffsets[hand] ?: return
        val animator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 750
            interpolator = OvershootInterpolator(3f)
            addUpdateListener {
                dragOffsets[hand] = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                dragOffsets.remove(hand)
                springAnimators.remove(hand)
                invalidate()
            }
        }
        springAnimators[hand] = animator
        animator.start()
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    // ----------------------------------------------------------------- time

    private fun computeAngles(): Angles {
        val now = Calendar.getInstance()
        val ms = if (smoothSeconds) now.get(Calendar.MILLISECOND) else 0
        val seconds = now.get(Calendar.SECOND) + ms / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR_OF_DAY) + minutes / 60f

        val hourAngle = if (use24hDial) {
            hours / 24f * 360f
        } else {
            (hours % 12f) / 12f * 360f
        }

        // French Revolutionary decimal time: the day is 10 decimal hours of
        // 100 decimal minutes of 100 decimal seconds. The day hand makes one
        // revolution per day; the decimal-second hand makes one revolution
        // per decimal minute (86.4 real seconds), so it visibly rotates.
        val secondOfDay = now.get(Calendar.HOUR_OF_DAY) * 3600.0 +
                now.get(Calendar.MINUTE) * 60.0 +
                now.get(Calendar.SECOND) + ms / 1000.0
        val dayFraction = secondOfDay / 86400.0
        val decimalSeconds = dayFraction * 100000.0

        return Angles(
            hour = hourAngle,
            minute = minutes / 60f * 360f,
            second = seconds / 60f * 360f,
            decimalDay = (dayFraction * 360.0).toFloat(),
            decimalSecond = ((decimalSeconds % 100.0) / 100.0 * 360.0).toFloat(),
            dayFraction = dayFraction
        )
    }

    private fun trueAngle(hand: Hand, a: Angles): Float = when (hand) {
        Hand.HOUR -> a.hour
        Hand.MINUTE -> a.minute
        Hand.SECOND -> a.second
        Hand.DECIMAL -> a.decimalDay
    }

    private fun drawnAngle(hand: Hand, a: Angles): Float =
        trueAngle(hand, a) + (dragOffsets[hand] ?: 0f)

    private fun dialRadius(): Float = min(width, height) / 2f * 0.92f * dialScale

    // ----------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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

        if (showDecimalHand) {
            drawDecimalExtras(canvas, cx, cy, r, a)
        }

        drawHand(canvas, cx, cy, drawnAngle(Hand.HOUR, a), r * HOUR_LEN, r * 0.10f, r * 0.045f, hourHandPaint)
        drawHand(canvas, cx, cy, drawnAngle(Hand.MINUTE, a), r * MINUTE_LEN, r * 0.12f, r * 0.03f, minuteHandPaint)
        if (showSecondHand) {
            drawHand(canvas, cx, cy, drawnAngle(Hand.SECOND, a), r * SECOND_LEN, r * 0.18f, r * 0.012f, secondHandPaint)
        }

        canvas.drawCircle(cx, cy, r * 0.035f, centerDotPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 60 minute ticks on the outer edge.
        for (i in 0 until 60) {
            val angle = i / 60f * 360f
            val isMajor = !use24hDial && i % 5 == 0
            val paint = if (isMajor) tickPaint else minorTickPaint
            paint.strokeWidth = if (isMajor) r * 0.018f else r * 0.008f
            val outerLen = if (isMajor) r * 0.08f else r * 0.045f
            val from = pointAt(cx, cy, angle, r * 0.97f - outerLen)
            val to = pointAt(cx, cy, angle, r * 0.97f)
            canvas.drawLine(from.x, from.y, to.x, to.y, paint)
        }
        // On the 24-hour dial the hour marks (24 divisions) do not line up
        // with the minute ticks, so they get their own slightly inset ring.
        if (use24hDial) {
            for (i in 0 until 24) {
                val angle = i / 24f * 360f
                tickPaint.strokeWidth = r * 0.018f
                val from = pointAt(cx, cy, angle, r * 0.80f)
                val to = pointAt(cx, cy, angle, r * 0.87f)
                canvas.drawLine(from.x, from.y, to.x, to.y, tickPaint)
            }
        }
    }

    private fun drawNumerals(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (numeralStyle == NumeralStyle.NONE) return
        numeralPaint.textSize = if (use24hDial) r * 0.11f else r * 0.16f
        val radius = if (use24hDial) r * 0.68f else r * 0.76f
        val hoursOnDial = if (use24hDial) 24 else 12
        val step = if (use24hDial) 2 else 1
        var h = step
        while (h <= hoursOnDial) {
            val angle = h.toFloat() / hoursOnDial * 360f
            val pos = pointAt(cx, cy, angle, radius)
            val label = if (numeralStyle == NumeralStyle.ROMAN) toRoman(h) else h.toString()
            val baseline = pos.y - (numeralPaint.ascent() + numeralPaint.descent()) / 2f
            canvas.drawText(label, pos.x, baseline, numeralPaint)
            h += step
        }
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

    private fun drawDecimalExtras(canvas: Canvas, cx: Float, cy: Float, r: Float, a: Angles) {
        for (i in 0 until 10) {
            val angle = i / 10f * 360f
            decimalTickPaint.strokeWidth = r * 0.012f
            val from = pointAt(cx, cy, angle, r * 0.30f)
            val to = pointAt(cx, cy, angle, r * 0.36f)
            canvas.drawLine(from.x, from.y, to.x, to.y, decimalTickPaint)
        }

        drawHand(canvas, cx, cy, drawnAngle(Hand.DECIMAL, a), r * DECIMAL_LEN, r * 0.06f, r * 0.014f, decimalHandPaint)
        drawHand(canvas, cx, cy, a.decimalSecond, r * 0.30f, r * 0.05f, r * 0.008f, decimalSecondHandPaint)

        val totalDecimalSeconds = (a.dayFraction * 100000.0).toLong()
        val dh = totalDecimalSeconds / 10000
        val dm = totalDecimalSeconds / 100 % 100
        val ds = totalDecimalSeconds % 100
        decimalTextPaint.textSize = r * 0.09f
        canvas.drawText(
            String.format(Locale.US, "%d.%02d.%02d", dh, dm, ds),
            cx,
            cy + r * 0.56f,
            decimalTextPaint
        )
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

    companion object {
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 1f
        private const val HOUR_LEN = 0.52f
        private const val MINUTE_LEN = 0.74f
        private const val SECOND_LEN = 0.82f
        private const val DECIMAL_LEN = 0.42f
    }
}
