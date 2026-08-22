package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    /**
     * Which days of the shown month hold a reminder, split by the half of
     * the day it falls in.
     * A day with both a morning and an evening gets both dots — the calendar
     * should not have to pick one and lie about the other.
     */
    var morningDays: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    var eveningDays: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** Anything at all, which is what the past-day styling asks. */
    private val markedDays: Set<Int> get() = morningDays + eveningDays

    /**
     * The user's birthday as month * 100 + day, or 0.
     *
     * It is not a reminder: it does not ring, cannot be deleted by accident
     * from the calendar, and comes back every year without anyone renewing
     * it. It is one date the calendar simply knows, which is why it lives in
     * a preference and gets a star of its own rather than another dot.
     */
    var birthday = 0

    /**
     * What each day of the shown month is, cycle-wise. Empty when nothing
     * has been recorded, which is most people and every fresh install.
     *
     * Handed in already worked out rather than given the record and a
     * calendar: [Cycle] answers in whole days counted from 1970 and this
     * view thinks in days of a month, and the translation belongs at the
     * one place that knows which month is on screen.
     */
    var cyclePhases: Map<Int, Cycle.Phase> = emptyMap()
        set(value) { field = value; invalidate() }

    /**
     * What the sky is doing on each day of the shown month, where it is
     * doing anything.
     *
     * Handed in worked out rather than asked for here, for the reason the
     * cycle phases are: [SkyEvents] answers in days counted from 1970 and
     * this view thinks in days of a month, and the translation belongs at
     * the one place that knows which month is on screen.
     */
    var skyDays: Map<Int, SkyEvents.Kind> = emptyMap()
        set(value) { field = value; invalidate() }

    /** How days already gone are shown, if at all. */
    enum class PastStyle { NONE, DIM, CROSS, RING }

    var pastStyle = PastStyle.NONE
        set(value) { field = value; invalidate() }

    /** Fired when the user taps a day cell of the shown month. */
    var onDayTap: ((day: Int) -> Unit)? = null

    /** Fired when the shown month changes (chevrons or today-jump). */
    var onMonthChanged: (() -> Unit)? = null

    /** Week start: swipe over the weekday header to toggle it. */
    var weekStartsMonday = false
        set(value) {
            if (field == value) return
            field = value
            weekSlideStart = android.os.SystemClock.uptimeMillis()
            invalidate()
        }
    private var weekSlideStart = 0L
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
            info.contentDescription = describeMonth()
        }
    }

    /** Names the month a swipe has landed on. */
    private fun describeMonth(): CharSequence =
        java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
            .format(shown.time)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

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
    private val starPath = Path()
    private val moonLitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cycleRect = android.graphics.RectF()

    private val titleFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
    private val weekdayFormat = SimpleDateFormat("EEEEE", Locale.getDefault())

    private fun dayLabel(day: Int): String =
        if (numeralStyle == ClockView.NumeralStyle.ROMAN) Roman.of(day) else day.toString()

    // ---------------------------------------------------------------- touch

    /** Pinched out, the card zooms from one month to the whole year. */
    private var yearView = false
    private var zoomStart = 0L

    /** Pivot the zoom grows from, when it came from a tapped month cell. */
    private var zoomFrom: android.graphics.PointF? = null

    private fun monthCellCenter(monthIndex: Int): android.graphics.PointF {
        val top = height * 0.16f
        val cellW = width / 3f
        val cellH = (height * 0.93f - top) / 4f
        return android.graphics.PointF(
            cellW * (monthIndex % 3) + cellW / 2f,
            top + cellH * (monthIndex / 3) + cellH / 2f
        )
    }
    private var pinchHandled = false
    private var spanAtBegin = 0f

    /**
     * True from the moment a second finger lands until the next fresh
     * touch. Lifting a pinch leaves one finger down, and its ACTION_UP was
     * landing on a month cell — which promptly zoomed into that month, so
     * the year view "would not stay" no matter how well the pinch worked.
     */
    private var gestureWasPinch = false
    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                pinchHandled = false
                spanAtBegin = detector.currentSpan
                return true
            }

            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                // Measured against the span the gesture started with: frame
                // to frame the span barely moves, so comparing consecutive
                // frames demanded an absurdly sharp squeeze. One decision
                // per gesture, then it is spent.
                if (pinchHandled || spanAtBegin <= 0f) return true
                val ratio = detector.currentSpan / spanAtBegin
                val out = ratio < 0.75f
                val into = ratio > 1.35f
                if (!out && !into) return true
                pinchHandled = true
                if (out != yearView) {
                    yearView = out
                    zoomFrom = null
                    zoomStart = android.os.SystemClock.uptimeMillis()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                }
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureWasPinch = false
            MotionEvent.ACTION_POINTER_DOWN -> gestureWasPinch = true
        }
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        // The finger still on screen after a pinch is not a tap.
        if (gestureWasPinch) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureWasPinch = false
            }
            return true
        }
        if (yearView) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
                // Only the year row claims the gesture; over the months the
                // pager keeps its swipe, so home is still one flick away.
                if (event.y < height * 0.14f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            // In year view a tap picks a month and zooms back into it.
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val dx = event.x - downX
                if (downY < height * 0.14f) {
                    if (kotlin.math.abs(dx) > width * 0.12f) {
                        pageYear(if (dx < 0) 1 else -1)
                    } else if (event.x < width * 0.28f) {
                        pageYear(-1)
                    } else if (event.x > width * 0.72f) {
                        pageYear(1)
                    } else {
                        // The year itself is the way home, as the month name
                        // is in the month view. Wandering off to 2031 should
                        // not be a one-way trip.
                        jumpToToday()
                    }
                    return true
                }
                if (kotlin.math.abs(dx) > width * 0.12f) return true
                monthAt(event.x, event.y)?.let { monthIndex ->
                    shown.set(Calendar.DAY_OF_MONTH, 1)
                    shown.set(Calendar.MONTH, monthIndex)
                    yearView = false
                    slideDir = 0
                    // Grow out of the cell that was tapped, not the middle
                    // of the screen: the month you picked expands to fill.
                    zoomFrom = monthCellCenter(monthIndex)
                    zoomStart = android.os.SystemClock.uptimeMillis()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onMonthChanged?.invoke()
                    invalidate()
                }
            }
            return true
        }
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
                    // There is nothing to schedule in the past; an old day
                    // opens only to show what it already holds.
                    if (isPast(day) && !markedDays.contains(day)) return true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onDayTap?.invoke(day)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pageYear(delta: Int) {
        shown.add(Calendar.YEAR, delta)
        slideDir = delta
        slideStart = android.os.SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onMonthChanged?.invoke()
        invalidate()
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

    /** As [isPast], for an arbitrary month of the shown year. */
    private fun isPastIn(monthIndex: Int, day: Int, now: Calendar): Boolean {
        val y = shown.get(Calendar.YEAR)
        return when {
            y != now.get(Calendar.YEAR) -> y < now.get(Calendar.YEAR)
            monthIndex != now.get(Calendar.MONTH) -> monthIndex < now.get(Calendar.MONTH)
            else -> day < now.get(Calendar.DAY_OF_MONTH)
        }
    }

    /** True once a day of the shown month is behind us. */
    private fun isPast(day: Int): Boolean {
        val now = Calendar.getInstance().apply { timeInMillis = TimeKeeper.nowMs() }
        val y = shown.get(Calendar.YEAR)
        val m = shown.get(Calendar.MONTH)
        return when {
            y != now.get(Calendar.YEAR) -> y < now.get(Calendar.YEAR)
            m != now.get(Calendar.MONTH) -> m < now.get(Calendar.MONTH)
            else -> day < now.get(Calendar.DAY_OF_MONTH)
        }
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

        // Zooming between month and year really zooms: the outgoing view
        // scales away under the incoming one instead of blinking out.
        val zoomT = ((android.os.SystemClock.uptimeMillis() - zoomStart) / 260f)
        val zooming = zoomT < 1f
        if (zooming) postInvalidateOnAnimation()
        val eased = zoomT.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        canvas.save()
        if (zooming) {
            // Year view falls back from the month; the month grows out of
            // the cell it was picked from.
            val from = if (yearView) 1.35f else 0.30f
            val scale = from + (1f - from) * eased
            val pivot = zoomFrom
            if (pivot != null && !yearView) {
                val px = pivot.x + (w / 2f - pivot.x) * eased
                val py = pivot.y + (h / 2f - pivot.y) * eased
                canvas.scale(scale, scale, px, py)
            } else {
                canvas.scale(scale, scale, w / 2f, h / 2f)
            }
        }
        if (yearView) {
            drawYear(canvas, w, h)
            canvas.restore()
            return
        }
        headerPaint.color = theme.minorTick
        todayRingPaint.color = theme.decimal
        moonDarkPaint.color = theme.minorTick
        moonDarkPaint.alpha = 90
        moonLitPaint.color = theme.numeral
        moonLitPaint.alpha = 220

        // Title row with chevrons. The month name rides the same slide as
        // the grid, so nothing on the card changes with a hard cut.
        val titleY = h * 0.09f
        titlePaint.textSize = h * 0.036f
        chevronPaint.textSize = h * 0.036f
        val titleSlide = if (slideDir != 0) {
            val t = ((android.os.SystemClock.uptimeMillis() - slideStart) / 280f).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            (1f - eased) * w * 0.35f * slideDir
        } else {
            0f
        }
        canvas.save()
        canvas.translate(titleSlide, 0f)
        titlePaint.alpha = (255 * (1f - kotlin.math.abs(titleSlide) / (w * 0.35f))).toInt()
            .coerceIn(40, 255)
        canvas.drawText(titleFormat.format(Date(shown.timeInMillis)), w / 2f, titleY, titlePaint)
        titlePaint.alpha = 255
        canvas.restore()
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
        // Flipping the week start slides the letters across — and the whole
        // month with them, since every day shifts a column too. Sliding one
        // without the other is exactly the sort of thing that makes a
        // calendar feel broken.
        val weekT = ((android.os.SystemClock.uptimeMillis() - weekSlideStart) / 260f)
        val weekSlide = if (weekT < 1f) {
            postInvalidateOnAnimation()
            val e = weekT.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
            (1f - e) * cellW * (if (weekStartsMonday) 1f else -1f)
        } else {
            0f
        }
        canvas.save()
        canvas.translate(weekSlide, 0f)
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
        canvas.restore()

        canvas.save()
        canvas.translate(weekSlide, 0f)
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
            // A past day with nothing on it is inert, and reads that way —
            // unless the user asked to leave spent days alone, in which case
            // it looks like any other day.
            if (pastStyle != PastStyle.NONE && isPast(day) && !markedDays.contains(day)) {
                dayPaint.alpha = 90
            }
            canvas.drawText(dayLabel(day), cx, baseline, dayPaint)
            dayPaint.alpha = 255

            if (isThisMonth && day == todayDay) {
                canvas.drawCircle(cx, cy, minOf(cellW, cellH) * 0.40f, todayRingPaint)
            }

            // Days already gone, marked however the user asked for.
            val past = isPast(day)
            if (past && pastStyle != PastStyle.NONE) {
                when (pastStyle) {
                    PastStyle.DIM -> Unit
                    PastStyle.CROSS -> {
                        todayRingPaint.strokeWidth = h * 0.0035f
                        val s2 = minOf(cellW, cellH) * 0.26f
                        canvas.drawLine(cx - s2, cy - s2, cx + s2, cy + s2, todayRingPaint)
                        canvas.drawLine(cx + s2, cy - s2, cx - s2, cy + s2, todayRingPaint)
                    }
                    PastStyle.RING -> {
                        todayRingPaint.strokeWidth = h * 0.0028f
                        canvas.drawCircle(cx, cy, minOf(cellW, cellH) * 0.36f, todayRingPaint)
                    }
                    else -> Unit
                }
            }

            // Reminder markers in the cell's corner, warm for the morning
            // half and cool for the evening one. A day holding both shows
            // both, side by side, rather than choosing.
            val am = morningDays.contains(day)
            val pm = eveningDays.contains(day)
            if (am || pm) {
                val dotR = minOf(cellW, cellH) * 0.07f
                val dotY = cy - cellH * 0.26f
                val gap = dotR * 1.25f
                moonLitPaint.alpha = 255
                if (am && pm) {
                    moonLitPaint.color = DayNight.markColor(theme, false)
                    canvas.drawCircle(cx + cellW * 0.32f - gap, dotY, dotR, moonLitPaint)
                    moonLitPaint.color = DayNight.markColor(theme, true)
                    canvas.drawCircle(cx + cellW * 0.32f + gap, dotY, dotR, moonLitPaint)
                } else {
                    moonLitPaint.color = DayNight.markColor(theme, pm)
                    canvas.drawCircle(cx + cellW * 0.32f, dotY, dotR, moonLitPaint)
                }
                moonLitPaint.color = theme.numeral
                moonLitPaint.alpha = 220
            }

            // The cycle, as a bar under the number: a solid one for the days
            // it happened, a hollow one for the days it is expected, and a
            // thin one for the days between. Under, rather than a dot in a
            // corner, because a cycle is a *run* of days and a bar reads as
            // one — a row of dots would read as five separate things.
            cyclePhases[day]?.takeIf { it != Cycle.Phase.NONE }?.let { phase ->
                drawCycleBar(canvas, cx, cy + cellH * 0.20f, cellW, cellH, phase)
            }

            // The birthday star, in the corner the reminder dots do not use,
            // so a birthday with a reminder on it shows both.
            if (birthday != 0 && birthday == (shown.get(Calendar.MONTH) + 1) * 100 + day) {
                drawStar(canvas, cx - cellW * 0.32f, cy - cellH * 0.26f, minOf(cellW, cellH) * 0.11f)
            }

            // What the sky is doing, in the one corner nothing else uses.
            // The moon's own nights are left to the little moon below —
            // drawing a mark for a full moon beside a picture of a full
            // moon is saying it twice.
            skyDays[day]?.let { kind ->
                drawSkyMark(
                    canvas, cx - cellW * 0.30f, cy + cellH * 0.34f,
                    minOf(cellW, cellH) * 0.12f, kind
                )
            }

            drawMiniMoon(canvas, cx, cy + cellH * 0.34f, minOf(cellW, cellH) * 0.10f, scratch.timeInMillis)
        }
        canvas.restore()
        canvas.restore()
        canvas.restore()
    }

    /**
     * A day's sky event, small enough to sit in the corner of a date.
     *
     * Three shapes, because at eight pixels three is what can be told
     * apart: a ring for an eclipse — the thing itself, one disc over
     * another with a rim of light left — a streak for a meteor shower, and
     * a pair of dots on a line for an opposition, which is what an
     * opposition is: the Sun, the Earth and the planet in a row.
     *
     * The moons are not drawn here. Every cell already carries a little
     * moon showing its own phase, and a mark saying "full moon" beside a
     * picture of a full moon is the calendar saying it twice.
     */
    private fun drawSkyMark(
        canvas: Canvas, cx: Float, cy: Float, size: Float, kind: SkyEvents.Kind
    ) {
        val colour = theme.decimal
        when (kind) {
            SkyEvents.Kind.SOLAR_ECLIPSE, SkyEvents.Kind.LUNAR_ECLIPSE -> {
                todayRingPaint.color = colour
                todayRingPaint.strokeWidth = size * 0.34f
                canvas.drawCircle(cx, cy, size * 0.62f, todayRingPaint)
                todayRingPaint.color = theme.decimal
            }
            SkyEvents.Kind.METEORS -> {
                todayRingPaint.color = colour
                todayRingPaint.strokeWidth = size * 0.30f
                canvas.drawLine(
                    cx - size * 0.62f, cy + size * 0.62f,
                    cx + size * 0.62f, cy - size * 0.62f,
                    todayRingPaint
                )
            }
            SkyEvents.Kind.OPPOSITION, SkyEvents.Kind.COMET -> {
                cyclePaint.color = colour
                cyclePaint.style = Paint.Style.FILL
                canvas.drawCircle(cx - size * 0.55f, cy, size * 0.30f, cyclePaint)
                canvas.drawCircle(cx + size * 0.55f, cy, size * 0.42f, cyclePaint)
            }
            // The moons have a picture of themselves already.
            else -> Unit
        }
    }

    /** Which month cell of the year grid is under (x, y), or null. */
    private fun monthAt(x: Float, y: Float): Int? {
        val top = height * 0.16f
        val bottom = height * 0.93f
        if (y < top || y > bottom) return null
        val col = (x / (width / 3f)).toInt().coerceIn(0, 2)
        val row = ((y - top) / ((bottom - top) / 4f)).toInt().coerceIn(0, 3)
        return row * 3 + col
    }

    /** Year view: twelve tiny months, today's marked. */
    private fun drawYear(canvas: Canvas, w: Float, h: Float) {
        headerPaint.color = theme.minorTick
        moonDarkPaint.color = theme.face
        moonDarkPaint.alpha = 255
        canvas.drawRoundRect(
            RectF(w * 0.015f, h * 0.015f, w * 0.985f, h * 0.96f),
            w * 0.05f, w * 0.05f, moonDarkPaint
        )

        // The year slides in like the months do.
        val yearSlide = if (slideDir != 0) {
            val t = ((android.os.SystemClock.uptimeMillis() - slideStart) / 280f)
            if (t < 1f) {
                postInvalidateOnAnimation()
                val e = t.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
                (1f - e) * w * 0.35f * slideDir
            } else {
                slideDir = 0
                0f
            }
        } else {
            0f
        }
        canvas.save()
        canvas.translate(yearSlide, 0f)

        titlePaint.textSize = h * 0.040f
        canvas.drawText(shown.get(Calendar.YEAR).toString(), w / 2f, h * 0.10f, titlePaint)
        canvas.restore()
        // Chevrons stay put while the year slides between them, the same
        // way the month header works.
        chevronPaint.textSize = h * 0.040f
        canvas.drawText("‹", w * 0.14f, h * 0.10f, chevronPaint)
        canvas.drawText("›", w * 0.86f, h * 0.10f, chevronPaint)
        canvas.save()
        canvas.translate(yearSlide, 0f)

        val monthFormat = SimpleDateFormat("LLL", Locale.getDefault())
        val top = h * 0.16f
        val cellW = w / 3f
        val cellH = (h * 0.93f - top) / 4f
        val today = Calendar.getInstance().apply { timeInMillis = TimeKeeper.nowMs() }

        for (m in 0 until 12) {
            val cx = cellW * (m % 3) + cellW / 2f
            val cy = top + cellH * (m / 3)
            scratch.timeInMillis = shown.timeInMillis
            scratch.set(Calendar.DAY_OF_MONTH, 1)
            scratch.set(Calendar.MONTH, m)

            val isThisMonth = today.get(Calendar.YEAR) == shown.get(Calendar.YEAR) &&
                today.get(Calendar.MONTH) == m
            titlePaint.textSize = cellH * 0.17f
            titlePaint.color = if (isThisMonth) theme.decimal else theme.numeral
            canvas.drawText(
                monthFormat.format(Date(scratch.timeInMillis)).uppercase(Locale.getDefault()),
                cx, cy + cellH * 0.24f, titlePaint
            )
            titlePaint.color = theme.numeral

            // A real (tiny) month grid, laid out on the correct weekday,
            // with busy days circled in the accent colour.
            val days = scratch.getActualMaximum(Calendar.DAY_OF_MONTH)
            val lead = ((scratch.get(Calendar.DAY_OF_WEEK) - firstDow()) + 7) % 7
            val colW = cellW * 0.108f
            val rowH = cellH * 0.118f
            val gridLeft = cx - colW * 3f
            val gridTop = cy + cellH * 0.36f
            dayPaint.textSize = rowH * 0.62f
            dayPaint.typeface = Typeface.DEFAULT
            for (d in 1..days) {
                val slot = lead + d - 1
                val px = gridLeft + (slot % 7) * colW
                val py = gridTop + (slot / 7) * rowH
                // The birthday counts as a busy day in the year grid too:
                // it is marked in the month view and it would be odd for
                // the whole year to forget it.
                val busy = yearMarks.contains(m * 100 + d) ||
                    (birthday != 0 && birthday == (m + 1) * 100 + d)
                val isToday = isThisMonth && d == today.get(Calendar.DAY_OF_MONTH)
                val gone = isPastIn(m, d, today)
                dayPaint.color = if (busy) theme.decimal else theme.numeral
                dayPaint.alpha = when {
                    busy -> 255
                    gone && pastStyle != PastStyle.NONE -> 80
                    else -> 150
                }
                canvas.drawText(
                    d.toString(), px,
                    py - (dayPaint.ascent() + dayPaint.descent()) / 2f,
                    dayPaint
                )
                // Whatever marks the spent days in the month marks them here.
                if (gone) {
                    when (pastStyle) {
                        PastStyle.CROSS -> {
                            todayRingPaint.strokeWidth = rowH * 0.07f
                            val q = rowH * 0.30f
                            canvas.drawLine(px - q, py - q, px + q, py + q, todayRingPaint)
                            canvas.drawLine(px + q, py - q, px - q, py + q, todayRingPaint)
                        }
                        PastStyle.RING -> {
                            todayRingPaint.strokeWidth = rowH * 0.06f
                            canvas.drawCircle(px, py, rowH * 0.46f, todayRingPaint)
                        }
                        else -> Unit
                    }
                }
                if (isToday) {
                    // Today is circled here too, and the ring survives the
                    // trip back into the month.
                    todayRingPaint.strokeWidth = rowH * 0.09f
                    canvas.drawCircle(px, py, rowH * 0.52f, todayRingPaint)
                }
            }
            dayPaint.alpha = 255
            // The current month needs no frame: its accent-coloured name and
            // the circled day already say "you are here".
        }
        canvas.restore()
    }

    /**
     * Days carrying reminders across the whole shown year, encoded as
     * month * 100 + day, so year view can dot them without a second pass.
     */
    var yearMarks: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** A five-pointed star, drawn by alternating outer and inner radius. */
    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        starPath.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) radius else radius * 0.42f
            val a = Math.toRadians(i * 36.0 - 90.0)
            val x = cx + (Math.cos(a) * r).toFloat()
            val y = cy + (Math.sin(a) * r).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        val color = moonLitPaint.color
        val alpha = moonLitPaint.alpha
        moonLitPaint.color = theme.numeral
        moonLitPaint.alpha = 255
        canvas.drawPath(starPath, moonLitPaint)
        moonLitPaint.color = color
        moonLitPaint.alpha = alpha
    }

    /**
     * One day of the cycle, as a bar under the number.
     *
     * Four weights for four degrees of certainty, which is the whole point
     * of the thing: a day that was written down is drawn solid, a day that
     * is only predicted is drawn hollow, and a day that has gone past
     * without the period arriving is drawn solid again but in the warning
     * colour — because a delay is a fact, even if what it is a delay *from*
     * was a guess.
     */
    private fun drawCycleBar(
        canvas: Canvas,
        cx: Float,
        y: Float,
        cellW: Float,
        cellH: Float,
        phase: Cycle.Phase
    ) {
        val halfWidth = cellW * 0.28f
        val thickness = minOf(cellW, cellH) * when (phase) {
            Cycle.Phase.FERTILE -> 0.045f
            else -> 0.085f
        }
        cyclePaint.color = when (phase) {
            Cycle.Phase.PERIOD, Cycle.Phase.LATE -> theme.secondHand
            else -> theme.numeral
        }
        cyclePaint.alpha = when (phase) {
            Cycle.Phase.PERIOD -> 255
            Cycle.Phase.LATE -> 255
            Cycle.Phase.PREDICTED -> 110
            else -> 80
        }
        cyclePaint.style = if (phase == Cycle.Phase.PREDICTED) Paint.Style.STROKE else Paint.Style.FILL
        cyclePaint.strokeWidth = thickness * 0.5f
        cycleRect.set(cx - halfWidth, y - thickness / 2f, cx + halfWidth, y + thickness / 2f)
        canvas.drawRoundRect(cycleRect, thickness / 2f, thickness / 2f, cyclePaint)
        cyclePaint.style = Paint.Style.FILL
        cyclePaint.alpha = 255
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
