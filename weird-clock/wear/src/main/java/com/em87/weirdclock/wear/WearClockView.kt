package com.em87.weirdclock.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Weird Clock dial, shrunk to a watch. A deliberately small cousin of
 * the phone's ClockView: no winding, no falling hands, no chronograph —
 * a watch face has one job — but the same drawing language, so the
 * dial on your wrist is recognisably the same clock as the one in your
 * pocket, polygonal faces and all.
 */
class WearClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Ambient (always-on) mode strips the face down to bare outlines. */
    var ambient = false
        set(value) { field = value; invalidate() }

    var theme: WearTheme = WearThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    var hoursOnDial = 12
        set(value) { field = value.coerceIn(2, 24); invalidate() }

    var sides = 0
        set(value) { field = value; invalidate() }

    var vertexOffsetDeg = 0f
        set(value) { field = value; invalidate() }

    var showSecondHand = true
        set(value) { field = value; invalidate() }

    var romanNumerals = false
        set(value) { field = value; invalidate() }

    private val cal: Calendar = Calendar.getInstance()
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val facePath = Path()

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            // Ambient mode only needs a minute; interactive wants seconds.
            postDelayed(this, if (ambient) 20_000L else 1000L)
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

    /** Distance to the dial's edge at [angleDeg]; a circle unless polygonal. */
    private fun boundary(angleDeg: Float, r: Float): Float {
        if (sides < 3) return r
        val half = 180f / sides
        var psi = (angleDeg - vertexOffsetDeg) % (2f * half)
        if (psi < 0f) psi += 2f * half
        val apothem = cos(Math.toRadians(half.toDouble())).toFloat()
        return r * apothem / cos(Math.toRadians((psi - half).toDouble())).toFloat()
    }

    private fun pointAt(cx: Float, cy: Float, angleDeg: Float, distance: Float): PointF {
        val a = Math.toRadians(angleDeg.toDouble())
        return PointF(cx + sin(a).toFloat() * distance, cy - cos(a).toFloat() * distance)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f
        if (r <= 0f) return

        facePaint.color = if (ambient) 0xFF000000.toInt() else theme.face
        rimPaint.color = theme.rim
        rimPaint.strokeWidth = r * 0.02f
        tickPaint.color = theme.tick
        numeralPaint.color = theme.numeral

        if (sides < 3) {
            canvas.drawCircle(cx, cy, r, facePaint)
            canvas.drawCircle(cx, cy, r, rimPaint)
        } else {
            facePath.reset()
            for (k in 0 until sides) {
                val p = pointAt(cx, cy, vertexOffsetDeg + k * 360f / sides, r)
                if (k == 0) facePath.moveTo(p.x, p.y) else facePath.lineTo(p.x, p.y)
            }
            facePath.close()
            canvas.drawPath(facePath, facePaint)
            canvas.drawPath(facePath, rimPaint)
        }

        // Hour ticks only — a watch face has no room for sixty of them.
        for (i in 0 until hoursOnDial) {
            val deg = i.toFloat() / hoursOnDial * 360f
            val b = boundary(deg, r)
            tickPaint.strokeWidth = r * 0.02f
            val from = pointAt(cx, cy, deg, b * 0.86f)
            val to = pointAt(cx, cy, deg, b * 0.95f)
            canvas.drawLine(from.x, from.y, to.x, to.y, tickPaint)
        }

        if (!ambient) {
            numeralPaint.textSize = r * 0.17f
            val step = if (hoursOnDial > 12) 3 else 3
            var hour = step
            while (hour <= hoursOnDial) {
                val deg = hour.toFloat() / hoursOnDial * 360f
                val pos = pointAt(cx, cy, deg, boundary(deg, r) * 0.70f)
                val label = if (romanNumerals) roman(hour) else hour.toString()
                canvas.drawText(
                    label, pos.x,
                    pos.y - (numeralPaint.ascent() + numeralPaint.descent()) / 2f,
                    numeralPaint
                )
                hour += step
            }
        }

        cal.timeInMillis = System.currentTimeMillis()
        val seconds = cal.get(Calendar.SECOND).toFloat()
        val minutes = cal.get(Calendar.MINUTE) + seconds / 60f
        val hours = cal.get(Calendar.HOUR_OF_DAY) + minutes / 60f

        val hourDeg = (hours % hoursOnDial) / hoursOnDial * 360f
        val minuteDeg = minutes / 60f * 360f
        val secondDeg = seconds / 60f * 360f

        drawHand(canvas, cx, cy, hourDeg, boundary(hourDeg, r) * 0.52f, r * 0.10f, r * 0.045f, theme.hourHand)
        drawHand(canvas, cx, cy, minuteDeg, boundary(minuteDeg, r) * 0.76f, r * 0.12f, r * 0.03f, theme.minuteHand)
        if (showSecondHand && !ambient) {
            drawHand(
                canvas, cx, cy, secondDeg,
                boundary(secondDeg, r) * 0.84f, r * 0.18f, r * 0.012f, theme.secondHand
            )
        }
        handPaint.style = Paint.Style.FILL
        handPaint.color = theme.centerDot
        canvas.drawCircle(cx, cy, r * 0.035f, handPaint)
        handPaint.style = Paint.Style.STROKE
    }

    private fun drawHand(
        canvas: Canvas, cx: Float, cy: Float, angleDeg: Float,
        length: Float, tail: Float, width: Float, color: Int
    ) {
        handPaint.color = if (ambient) 0xFFDDDDDD.toInt() else color
        handPaint.strokeWidth = width * 2f
        val tip = pointAt(cx, cy, angleDeg, length)
        val back = pointAt(cx, cy, angleDeg + 180f, tail)
        canvas.drawLine(back.x, back.y, tip.x, tip.y, handPaint)
    }

    private fun roman(n: Int): String {
        val values = intArrayOf(10, 9, 5, 4, 1)
        val symbols = arrayOf("X", "IX", "V", "IV", "I")
        var rest = n
        val sb = StringBuilder()
        for (i in values.indices) {
            while (rest >= values[i]) {
                sb.append(symbols[i])
                rest -= values[i]
            }
        }
        return sb.toString()
    }
}
