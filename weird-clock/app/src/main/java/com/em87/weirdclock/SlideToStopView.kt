package com.em87.weirdclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Slide to stop the alarm.
 *
 * A button is the wrong control here. The alarm goes off while you are
 * asleep, and the first thing a hand does is swat at the screen — a Stop
 * button under that hand is pressed before anyone is awake enough to have
 * meant it, and the alarm that was supposed to get you up is gone. A slide
 * takes a deliberate gesture the length of the screen, which is about as
 * much intent as a phone can ask for.
 *
 * Snooze stays a button, and a big one: hitting snooze half-asleep is not a
 * failure, it is what snooze is for.
 *
 * Everything here is hand-clocked on [SystemClock.uptimeMillis] rather than
 * animated: the system animator scale is off on the author's phone, and
 * anything on a ValueAnimator simply teleports.
 */
class SlideToStopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSlid: (() -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** How far along the track the knob is, 0 to 1. */
    private var progress = 0f
    private var dragging = false
    private var grabOffset = 0f

    /** Where the knob is springing back from, and when it started. */
    private var releaseFrom = 0f
    private var releaseAt = 0L
    private var fired = false

    private val rect = RectF()

    private fun knobRadius(): Float = height / 2f - dp(4f)

    private fun travel(): Float = width - knobRadius() * 2 - dp(8f)

    private fun knobCenterX(): Float = knobRadius() + dp(4f) + travel() * progress

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(76f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        trackPaint.color = ContextCompat.getColor(context, R.color.m3_surface_variant)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // The label fades out as the knob covers it, so the control stops
        // asking once you have started answering.
        labelPaint.textSize = dp(17f)
        labelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        labelPaint.alpha = ((1f - progress * 1.6f).coerceIn(0f, 1f) * 255).toInt()
        canvas.drawText(
            context.getString(R.string.alarm_slide_to_stop),
            width / 2f + knobRadius() * 0.4f,
            height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f,
            labelPaint
        )

        val cx = knobCenterX()
        knobPaint.color = ContextCompat.getColor(context, R.color.accent)
        canvas.drawCircle(cx, height / 2f, knobRadius(), knobPaint)

        // A chevron pointing the way out, in the knob's own middle.
        arrowPaint.color = 0xFFFFFFFF.toInt()
        arrowPaint.strokeWidth = dp(3f)
        val a = knobRadius() * 0.30f
        canvas.drawLine(cx - a * 0.4f, height / 2f - a, cx + a * 0.6f, height / 2f, arrowPaint)
        canvas.drawLine(cx + a * 0.6f, height / 2f, cx - a * 0.4f, height / 2f + a, arrowPaint)

        // Spring back, on its own clock.
        if (!dragging && progress > 0f && !fired) {
            val t = (SystemClock.uptimeMillis() - releaseAt) / RETURN_MS
            progress = if (t >= 1f) 0f else releaseFrom * (1f - t)
            if (progress > 0f) postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fired) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Anywhere on the knob, or on the track behind it: a hand
                // reaching for this is not aiming carefully.
                if (event.x > knobCenterX() + knobRadius() * 2f) return false
                dragging = true
                grabOffset = event.x - knobCenterX()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val t = travel()
                if (t <= 0f) return true
                progress = ((event.x - grabOffset - knobRadius() - dp(4f)) / t).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                if (progress >= DONE_AT && event.actionMasked == MotionEvent.ACTION_UP) {
                    fired = true
                    progress = 1f
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    performClick()
                    onSlid?.invoke()
                } else {
                    releaseFrom = progress
                    releaseAt = SystemClock.uptimeMillis()
                }
                invalidate()
            }
        }
        return true
    }

    private companion object {
        /** Most of the way, not all: the last few pixels are a cliff edge. */
        const val DONE_AT = 0.85f
        const val RETURN_MS = 220f
    }
}
