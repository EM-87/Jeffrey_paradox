package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * C3: a month calendar drawn in the clock's theme. The chevrons page through
 * months, tapping the title jumps back to today, and every day carries its
 * own tiny moon phase. If the dial uses Roman numerals, so does the calendar
 * — obviously.
 */
class CalendarPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }
    var numeralStyle = ClockView.NumeralStyle.ARABIC
        set(value) { field = value; invalidate() }

    /** Days of the shown month carrying a reminder (accent-dotted). */
    var markedDays: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** Fired when the user taps a day cell of the shown month. */
    var onDayTap: ((day: Int) -> Unit)? = null

    /** Fired when the shown month changes (chevrons or today-jump). */
    var onMonthChanged: (() -> Unit)? = null

    /** Week start: swipe over the weekday header to toggle it. */
    var weekStartsMonday = false
        set(value) { field = value; invalidate() }
    var onWeekStartChanged: ((Boolean) -> Unit)? = null

    private fun firstDow(): Int =
        if (weekStartsMonday) Calendar.MONDAY else Calendar.SUNDAY

    // Month-change slide: the day grid glides in like a mini card.
    private var slideDir = 0
    private var slideStart = 0L
    private var downX = 0f
    private var downY = 0f

    val shownYear: Int get() = shown.get(Calendar.YEAR)

    /** 1–12. */
    val shownMonth1: Int get() = shown.get(Calendar.MONTH) + 1

    /** First day of the month currently on screen. */
    private val shown: Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }
    private val scratch: Calendar = Calendar.getInstance()

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val todayRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val moonDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonLitPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val titleFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
    private val weekdayFormat = SimpleDateFormat("EEEEE", Locale.getDefault())

    private fun dayLabel(day: Int): String =
        if (numeralStyle == ClockView.NumeralStyle.ROMAN) Roman.of(day) else day.toString()

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                // Title and header rows own their horizontal swipes; the
                // hosting pager must not steal them.
                if (event.y < height * 0.19f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val swipe = kotlin.math.abs(dx) > width * 0.12f
                if (downY < height * 0.10f && swipe) {
                    // Swiping the month name pages months, card-style.
                    pageMonth(if (dx < 0) 1 else -1)
                    return true
                }
                if (downY in height * 0.10f..height * 0.19f && swipe) {
                    // Swiping the weekday header flips the week start.
                    weekStartsMonday = !weekStartsMonday
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onWeekStartChanged?.invoke(weekStartsMonday)
                    return true
                }
                if (swipe) return true
                if (event.y < height * 0.16f) {
                    when {
                        event.x < width * 0.28f -> pageMonth(-1)
                        event.x > width * 0.72f -> pageMonth(+1)
                        else -> jumpToToday()
                    }
                    return true
                }
                dayAt(event.x, event.y)?.let { day ->
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDayTap?.invoke(day)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pageMonth(delta: Int) {
        shown.add(Calendar.MONTH, delta)
        slideDir = delta
        slideStart = android.os.SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onMonthChanged?.invoke()
        invalidate()
    }

    private fun jumpToToday() {
        shown.timeInMillis = TimeKeeper.nowMs()
        shown.set(Calendar.DAY_OF_MONTH, 1)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onMonthChanged?.invoke()
        invalidate()
    }

    /** The day of the shown month under (x, y), or null. */
    private fun dayAt(x: Float, y: Float): Int? {
        val gridLeft = width * 0.06f
        val gridRight = width * 0.94f
        val gridTop = height * 0.19f
        val gridBottom = height * 0.88f
        if (x < gridLeft || x > gridRight || y < gridTop || y > gridBottom) return null
        val col = ((x - gridLeft) / ((gridRight - gridLeft) / 7f)).toInt().coerceIn(0, 6)
        val row = ((y - gridTop) / ((gridBottom - gridTop) / 6f)).toInt().coerceIn(0, 5)
        val leadBlank = ((shown.get(Calendar.DAY_OF_WEEK) - firstDow()) + 7) % 7
        val day = row * 7 + col - leadBlank + 1
        return if (day in 1..shown.getActualMaximum(Calendar.DAY_OF_MONTH)) day else null
    }

    // ----------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Face-colored card behind the grid, so the theme's colors stay
        // readable whatever the system light/dark background does.
        moonDarkPaint.color = theme.face
        moonDarkPaint.alpha = 255
        canvas.drawRoundRect(
            RectF(w * 0.015f, h * 0.015f, w * 0.985f, h * 0.93f),
            w * 0.05f, w * 0.05f, moonDarkPaint
        )

        titlePaint.color = theme.numeral
        chevronPaint.color = theme.decimal
        headerPaint.color = theme.minorTick
        todayRingPaint.color = theme.decimal
        moonDarkPaint.color = theme.minorTick
        moonDarkPaint.alpha = 90
        moonLitPaint.color = theme.numeral
        moonLitPaint.alpha = 220

        // Title row with chevrons.
        val titleY = h * 0.09f
        titlePaint.textSize = h * 0.036f
        chevronPaint.textSize = h * 0.036f
        canvas.drawText(titleFormat.format(Date(shown.timeInMillis)), w / 2f, titleY, titlePaint)
        canvas.drawText("‹", w * 0.14f, titleY, chevronPaint)
        canvas.drawText("›", w * 0.86f, titleY, chevronPaint)

        // Month change slides the grid in like a card, no hard cuts.
        val st = (android.os.SystemClock.uptimeMillis() - slideStart) / 280f
        val slideOffset = if (slideDir != 0 && st < 1f) {
            postInvalidateOnAnimation()
            val t = st.coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            (1f - eased) * w * 0.35f * slideDir
        } else {
            0f
        }
        canvas.save()
        canvas.translate(slideOffset, 0f)

        // Weekday header; swipe over it to flip the week start.
        val firstDow = firstDow()
        val gridLeft = w * 0.06f
        val gridRight = w * 0.94f
        val cellW = (gridRight - gridLeft) / 7f
        val headerY = h * 0.155f
        headerPaint.textSize = h * 0.022f
        for (i in 0 until 7) {
            scratch.set(2024, Calendar.JANUARY, 1)
            while (scratch.get(Calendar.DAY_OF_WEEK) != ((firstDow - 1 + i) % 7 + 1)) {
                scratch.add(Calendar.DAY_OF_MONTH, 1)
            }
            canvas.drawText(
                weekdayFormat.format(Date(scratch.timeInMillis)).uppercase(Locale.getDefault()),
                gridLeft + cellW * (i + 0.5f),
                headerY,
                headerPaint
            )
        }

        // Day grid: up to 6 rows of 7.
        val gridTop = h * 0.19f
        val gridBottom = h * 0.88f
        val cellH = (gridBottom - gridTop) / 6f
        dayPaint.textSize = minOf(cellH * 0.34f, cellW * 0.34f)
        todayRingPaint.strokeWidth = h * 0.004f

        val daysInMonth = shown.getActualMaximum(Calendar.DAY_OF_MONTH)
        val leadBlank = ((shown.get(Calendar.DAY_OF_WEEK) - firstDow) + 7) % 7
        scratch.timeInMillis = TimeKeeper.nowMs()
        val todayDay = scratch.get(Calendar.DAY_OF_MONTH)
        val isThisMonth = scratch.get(Calendar.YEAR) == shown.get(Calendar.YEAR) &&
            scratch.get(Calendar.MONTH) == shown.get(Calendar.MONTH)

        for (day in 1..daysInMonth) {
            val slot = leadBlank + day - 1
            val col = slot % 7
            val row = slot / 7
            val cx = gridLeft + cellW * (col + 0.5f)
            val cy = gridTop + cellH * (row + 0.42f)

            scratch.timeInMillis = shown.timeInMillis
            scratch.set(Calendar.DAY_OF_MONTH, day)
            val dow = scratch.get(Calendar.DAY_OF_WEEK)
            dayPaint.color = if (dow == Calendar.SUNDAY) theme.secondHand else theme.numeral
            dayPaint.typeface = if (isThisMonth && day == todayDay) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            val baseline = cy - (dayPaint.ascent() + dayPaint.descent()) / 2f
            canvas.drawText(dayLabel(day), cx, baseline, dayPaint)

            if (isThisMonth && day == todayDay) {
                canvas.drawCircle(cx, cy, minOf(cellW, cellH) * 0.40f, todayRingPaint)
            }

            if (markedDays.contains(day)) {
                // Reminder marker: an accent dot in the cell's corner.
                moonLitPaint.color = theme.decimal
                moonLitPaint.alpha = 255
                canvas.drawCircle(
                    cx + cellW * 0.32f, cy - cellH * 0.26f,
                    minOf(cellW, cellH) * 0.07f, moonLitPaint
                )
                moonLitPaint.color = theme.numeral
                moonLitPaint.alpha = 220
            }

            drawMiniMoon(canvas, cx, cy + cellH * 0.34f, minOf(cellW, cellH) * 0.10f, scratch.timeInMillis)
        }
        canvas.restore()
    }

    /** The same terminator-ellipse moon as the dial, in miniature. */
    private fun drawMiniMoon(canvas: Canvas, cx: Float, cy: Float, mr: Float, timeMs: Long) {
        val synodicDays = 29.530588853
        val julian = timeMs / 86_400_000.0 + 2_440_587.5
        val phase = (((julian - 2_451_550.26) / synodicDays) % 1.0 + 1.0) % 1.0
        val cosPhase = cos(2.0 * Math.PI * phase)
        val litRight = phase < 0.5

        canvas.drawCircle(cx, cy, mr, moonDarkPaint)
        canvas.save()
        if (litRight) {
            canvas.clipRect(cx, cy - mr, cx + mr, cy + mr)
        } else {
            canvas.clipRect(cx - mr, cy - mr, cx, cy + mr)
        }
        canvas.drawCircle(cx, cy, mr, moonLitPaint)
        canvas.restore()
        val ellipseHalf = (mr * abs(cosPhase)).toFloat()
        if (ellipseHalf > 0.5f) {
            val oval = RectF(cx - ellipseHalf, cy - mr, cx + ellipseHalf, cy + mr)
            canvas.drawOval(oval, if (cosPhase > 0) moonDarkPaint else moonLitPaint)
        }
    }
}
