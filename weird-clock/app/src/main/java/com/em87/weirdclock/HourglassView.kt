package com.em87.weirdclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import java.util.Locale

/**
 * A small hourglass for the floating countdown bubble: sand proportional to
 * the remaining time in the top bulb, elapsed in the bottom, a falling
 * stream between them and the remaining time printed underneath.
 */
class HourglassView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    var totalMs: Long = 1L
        set(value) { field = value.coerceAtLeast(1L); invalidate() }
    var remainingMs: Long = 0L
        set(value) { field = value.coerceAtLeast(0L); invalidate() }
    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    /** When true the sand stream flickers on its own (the S3 card). */
    var live = false

    /**
     * The panel the glass stands on.
     *
     * The theme's own face, not a hardcoded near-black. It was
     * `0xCC10121A` — a dark slab whatever the dial was made of — so the
     * countdown widget stayed night-coloured on a home screen where every
     * other thing this app draws had gone light. The rim and the sand were
     * themed all along, which made it worse rather than better: dark
     * panel, light everything else.
     *
     * Kept a little transparent, as it was, so the wallpaper shows through
     * the corners the way it does on the round widgets.
     */
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bulbPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        backgroundPaint.color = theme.face
        backgroundPaint.alpha = 0xCC
        canvas.drawRoundRect(RectF(0f, 0f, w, h), w * 0.16f, w * 0.16f, backgroundPaint)

        val glassTop = h * 0.10f
        val glassBottom = h * 0.72f
        val midY = (glassTop + glassBottom) / 2f
        val left = w * 0.18f
        val right = w * 0.82f
        val neckHalf = w * 0.035f
        val cx = w / 2f

        glassPaint.color = theme.rim
        glassPaint.strokeWidth = w * 0.035f
        sandPaint.color = theme.decimal

        val fraction = (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
        val bulbHeight = midY - glassTop
        // Real hourglass filling: the bulbs are cones, so there's hardly any
        // volume near the tips — sand level = sqrt of the volume fraction,
        // not a linear bar. The top pile collapses fast at the end; the
        // bottom pile shoots up at the start.
        val topLevel = bulbHeight * kotlin.math.sqrt(fraction)
        val bottomLevel = bulbHeight * (1f - kotlin.math.sqrt(fraction))

        // Top sand: clip to the upper bulb, fill up from the neck.
        bulbPath.reset()
        bulbPath.moveTo(left, glassTop)
        bulbPath.lineTo(right, glassTop)
        bulbPath.lineTo(cx + neckHalf, midY)
        bulbPath.lineTo(cx - neckHalf, midY)
        bulbPath.close()
        canvas.save()
        canvas.clipPath(bulbPath)
        canvas.drawRect(left, midY - topLevel, right, midY, sandPaint)
        canvas.restore()
        canvas.drawPath(bulbPath, glassPaint)

        // Bottom sand: clip to the lower bulb, pile up from the base.
        bulbPath.reset()
        bulbPath.moveTo(cx - neckHalf, midY)
        bulbPath.lineTo(cx + neckHalf, midY)
        bulbPath.lineTo(right, glassBottom)
        bulbPath.lineTo(left, glassBottom)
        bulbPath.close()
        canvas.save()
        canvas.clipPath(bulbPath)
        canvas.drawRect(left, glassBottom - bottomLevel, right, glassBottom, sandPaint)
        canvas.restore()
        canvas.drawPath(bulbPath, glassPaint)

        // Falling stream, flickering slightly.
        if (remainingMs > 0L) {
            sandPaint.alpha = if (SystemClock.uptimeMillis() / 250L % 2L == 0L) 255 else 170
            canvas.drawRect(
                cx - w * 0.012f, midY,
                cx + w * 0.012f, glassBottom - bottomLevel,
                sandPaint
            )
            sandPaint.alpha = 255
        }

        // Frame caps.
        framePaint.color = theme.tick
        canvas.drawRoundRect(
            RectF(left - w * 0.06f, glassTop - h * 0.045f, right + w * 0.06f, glassTop),
            w * 0.03f, w * 0.03f, framePaint
        )
        canvas.drawRoundRect(
            RectF(left - w * 0.06f, glassBottom, right + w * 0.06f, glassBottom + h * 0.045f),
            w * 0.03f, w * 0.03f, framePaint
        )

        // Remaining time.
        textPaint.color = theme.tick
        textPaint.textSize = h * 0.14f
        val total = remainingMs / 1000L
        val text = if (total >= 3600L) {
            String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        } else {
            String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
        }
        canvas.drawText(text, cx, h * 0.93f, textPaint)

        if (live && remainingMs > 0L) postInvalidateDelayed(250L)
    }
}
