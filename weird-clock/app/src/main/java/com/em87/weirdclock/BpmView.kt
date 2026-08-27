package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * S3: the beat counter. Tap the upper pad in rhythm and the 7-segment
 * display reads your BPM; tap the metronome below and it keeps the beat for
 * you, pendulum swinging, ticking through the app's own sounds. Leave a gap
 * of a few seconds and the next tap starts a fresh measurement.
 */
class BpmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    /** Fired on each tap of the pad (the host plays the tick). */
    var onTap: (() -> Unit)? = null

    /** Fired on each metronome beat while it runs. */
    var onBeat: (() -> Unit)? = null

    private val tapTimes = ArrayDeque<Long>()
    private var bpm = 0.0
    private var metronomeRunning = false
    private var metronomeStartAt = 0L
    private var flashUntil = 0L

    private val digitalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePath = Path()

    private val beatLoop = object : Runnable {
        override fun run() {
            if (!metronomeRunning || bpm <= 0.0) return
            onBeat?.invoke()
            flashUntil = SystemClock.uptimeMillis() + 120L
            invalidate()
            postDelayed(this, beatMs())
        }
    }

    private fun beatMs(): Long = (60_000.0 / bpm).toLong().coerceIn(200L, 3000L)

    fun stopMetronome() {
        metronomeRunning = false
        removeCallbacks(beatLoop)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(beatLoop)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (event.y > height * 0.60f) toggleMetronome() else registerTap()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun toggleMetronome() {
        if (metronomeRunning) {
            stopMetronome()
        } else if (bpm > 0.0) {
            metronomeRunning = true
            metronomeStartAt = SystemClock.uptimeMillis()
            removeCallbacks(beatLoop)
            post(beatLoop)
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    private fun registerTap() {
        val now = SystemClock.uptimeMillis()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > RESET_GAP_MS) tapTimes.clear()
        tapTimes.addLast(now)
        while (tapTimes.size > 12) tapTimes.removeFirst()
        if (tapTimes.size >= 2) {
            bpm = 60_000.0 * (tapTimes.size - 1) / (tapTimes.last() - tapTimes.first())
        }
        flashUntil = now + 120L
        onTap?.invoke()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    // ----------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()

        // Face-colored backdrop so the theme reads on any system background.
        bobPaint.color = theme.face
        canvas.drawRect(0f, 0f, w, h, bobPaint)

        digitalPaint.color = theme.decimal
        labelPaint.color = theme.minorTick
        hintPaint.color = theme.minorTick
        framePaint.color = theme.rim
        armPaint.color = theme.numeral
        bobPaint.color = if (metronomeRunning) theme.decimal else theme.rim

        // 7-segment BPM readout.
        val digitH = min(w, h) * 0.22f
        val text = if (bpm > 0.0) bpm.roundToInt().coerceAtMost(999).toString() else "---"
        drawSevenSegment(canvas, text, w / 2f, h * 0.20f, digitH)
        labelPaint.textSize = digitH * 0.28f
        canvas.drawText("BPM", w / 2f, h * 0.20f + digitH + labelPaint.textSize * 1.6f, labelPaint)

        // Tap flash: the readout blinks with each beat or tap.
        hintPaint.textSize = min(w, h) * 0.042f
        hintPaint.alpha = if (now < flashUntil) 255 else 140
        canvas.drawText(
            context.getString(R.string.bpm_tap_hint),
            w / 2f, h * 0.09f, hintPaint
        )
        hintPaint.alpha = 140

        // Metronome: trapezoid case, pendulum arm, weight bob.
        val mCx = w / 2f
        val mTop = h * 0.64f
        val mBottom = h * 0.94f
        val mH = mBottom - mTop
        val topHalf = mH * 0.22f
        val botHalf = mH * 0.42f
        framePaint.strokeWidth = mH * 0.03f
        framePath.reset()
        framePath.moveTo(mCx - botHalf, mBottom)
        framePath.lineTo(mCx - topHalf, mTop)
        framePath.lineTo(mCx + topHalf, mTop)
        framePath.lineTo(mCx + botHalf, mBottom)
        framePath.close()
        canvas.drawPath(framePath, framePaint)

        // Pendulum: period is one beat per side (tick… tock…).
        val pivotY = mBottom - mH * 0.12f
        val armLen = mH * 0.78f
        val swing = if (metronomeRunning && bpm > 0.0) {
            val phase = (now - metronomeStartAt).toDouble() / beatMs()
            (MAX_SWING_DEG * sin(phase * PI)).toFloat()
        } else {
            0f
        }
        val rad = Math.toRadians(swing.toDouble())
        val tipX = mCx + sin(rad).toFloat() * armLen
        val tipY = pivotY - kotlin.math.cos(rad).toFloat() * armLen
        armPaint.strokeWidth = mH * 0.025f
        canvas.drawLine(mCx, pivotY, tipX, tipY, armPaint)
        // Weight partway up the arm, plus the pivot cap.
        val bobX = mCx + sin(rad).toFloat() * armLen * 0.62f
        val bobY = pivotY - kotlin.math.cos(rad).toFloat() * armLen * 0.62f
        canvas.drawCircle(bobX, bobY, mH * 0.07f, bobPaint)
        canvas.drawCircle(mCx, pivotY, mH * 0.035f, armPaint)

        canvas.drawText(
            context.getString(R.string.bpm_metronome_hint),
            w / 2f, h * 0.985f, hintPaint
        )

        if (metronomeRunning) postInvalidateOnAnimation()
    }

    private fun drawSevenSegment(canvas: Canvas, text: String, cx: Float, top: Float, digitH: Float) {
        val digitW = digitH * 0.55f
        val gap = digitW * 0.45f
        val totalW = text.length * (digitW + gap) - gap
        var x = cx - totalW / 2f
        digitalPaint.strokeWidth = digitH * 0.10f
        for (c in text) {
            if (c == '-') {
                val s = digitalPaint.strokeWidth * 0.8f
                val mid = top + digitH / 2f
                canvas.drawLine(x + s, mid, x + digitW - s, mid, digitalPaint)
            } else {
                val digit = c - '0'
                if (digit in 0..9) drawSegments(canvas, Segments.seven(c), x, top, digitW, digitH)
            }
            x += digitW + gap
        }
    }

    private fun drawSegments(canvas: Canvas, bits: Int, x: Float, y: Float, w: Float, h: Float) {
        val s = digitalPaint.strokeWidth * 0.8f
        val mid = y + h / 2f
        // The bits are [Segments]'s, which is the only table of them left
        // in this app. They were this file's own once, in a different
        // order, and pointing this at the shared one without changing the
        // decoding would have drawn every digit inside out.
        if (bits and Segments.A != 0) canvas.drawLine(x + s, y, x + w - s, y, digitalPaint)
        if (bits and Segments.B != 0) canvas.drawLine(x + w, y + s, x + w, mid - s, digitalPaint)
        if (bits and Segments.C != 0) canvas.drawLine(x + w, mid + s, x + w, y + h - s, digitalPaint)
        if (bits and Segments.D != 0) canvas.drawLine(x + s, y + h, x + w - s, y + h, digitalPaint)
        if (bits and Segments.E != 0) canvas.drawLine(x, mid + s, x, y + h - s, digitalPaint)
        if (bits and Segments.F != 0) canvas.drawLine(x, y + s, x, mid - s, digitalPaint)
        if (bits and Segments.G != 0) canvas.drawLine(x + s, mid, x + w - s, mid, digitalPaint)
    }

    companion object {
        private const val RESET_GAP_MS = 2500L
        private const val MAX_SWING_DEG = 24.0
    }
}
