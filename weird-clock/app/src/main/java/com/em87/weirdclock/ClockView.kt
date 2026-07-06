package com.em87.weirdclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Analog clock face with unusual options: a 24-hour dial, a French
 * Revolutionary decimal-time hand, mirrored (counterclockwise) mode,
 * smooth-sweep seconds and selectable numeral styles.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NumeralStyle { NONE, ARABIC, ROMAN }

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

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1B1E28")
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4A5163")
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E8E8E8")
        strokeCap = Paint.Cap.ROUND
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#6B7284")
        strokeCap = Paint.Cap.ROUND
    }
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8E8")
        textAlign = Paint.Align.CENTER
    }
    private val hourHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F5F5F5")
        strokeCap = Paint.Cap.ROUND
    }
    private val minuteHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#D8DCE8")
        strokeCap = Paint.Cap.ROUND
    }
    private val secondHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF5252")
        strokeCap = Paint.Cap.ROUND
    }
    private val decimalHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
    }
    private val decimalTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
        alpha = 140
    }
    private val decimalTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textAlign = Paint.Align.CENTER
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5F5F5")
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            val delay = if (smoothSeconds && showSecondHand) {
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
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f

        rimPaint.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, r, facePaint)
        canvas.drawCircle(cx, cy, r, rimPaint)

        drawTicks(canvas, cx, cy, r)
        drawNumerals(canvas, cx, cy, r)

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
        val minuteAngle = minutes / 60f * 360f
        val secondAngle = seconds / 60f * 360f

        if (showDecimalHand) {
            drawDecimalExtras(canvas, cx, cy, r, now, ms)
        }

        drawHand(canvas, cx, cy, hourAngle, r * 0.52f, r * 0.10f, r * 0.045f, hourHandPaint)
        drawHand(canvas, cx, cy, minuteAngle, r * 0.74f, r * 0.12f, r * 0.03f, minuteHandPaint)
        if (showSecondHand) {
            drawHand(canvas, cx, cy, secondAngle, r * 0.82f, r * 0.18f, r * 0.012f, secondHandPaint)
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

    private fun drawDecimalExtras(canvas: Canvas, cx: Float, cy: Float, r: Float, now: Calendar, ms: Int) {
        // French Revolutionary decimal time: the day is 10 decimal hours of
        // 100 decimal minutes of 100 decimal seconds. The hand makes one full
        // revolution per day over an inner ring of 10 divisions.
        val secondOfDay = now.get(Calendar.HOUR_OF_DAY) * 3600.0 +
                now.get(Calendar.MINUTE) * 60.0 +
                now.get(Calendar.SECOND) + ms / 1000.0
        val dayFraction = secondOfDay / 86400.0

        for (i in 0 until 10) {
            val angle = i / 10f * 360f
            decimalTickPaint.strokeWidth = r * 0.012f
            val from = pointAt(cx, cy, angle, r * 0.30f)
            val to = pointAt(cx, cy, angle, r * 0.36f)
            canvas.drawLine(from.x, from.y, to.x, to.y, decimalTickPaint)
        }

        drawHand(canvas, cx, cy, (dayFraction * 360.0).toFloat(), r * 0.42f, r * 0.06f, r * 0.014f, decimalHandPaint)

        val totalDecimalSeconds = (dayFraction * 100000.0).toLong()
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
}
