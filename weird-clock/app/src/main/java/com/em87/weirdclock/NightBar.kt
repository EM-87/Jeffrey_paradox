package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * One day, twenty-four sections, and two pins: where the night starts and
 * where it ends.
 *
 * This began as two separate sliders, one for each hour, which is two rows
 * of settings to read a single fact off and no way at all to see the shape
 * of what you have set. A night is one thing — a stretch — and a stretch
 * wants one bar.
 *
 * The bar is a day laid out flat, so the band is drawn where the night
 * actually falls, and a night that crosses midnight (which is most of them)
 * runs off the right-hand end and comes back on the left. That wrapping is
 * the reason this is not a Material RangeSlider: a range slider's two
 * thumbs are always in order and the band is always between them, so it can
 * express "asleep from two in the afternoon until six" and cannot express
 * "asleep from ten until seven", which is the case everybody has.
 */
class NightBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** The hour the night starts at, and the hour it is over. */
    var from = NightWindow.DEFAULT_FROM
        private set
    var to = NightWindow.DEFAULT_TO
        private set

    /** Called on every change, mid-drag included. */
    var onChanged: ((Int, Int) -> Unit)? = null

    /** Which pin the finger has hold of; null when nothing is being moved. */
    private var holding: Boolean? = null

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()

    private val pinRadius = 10f * density
    private val trackHeight = 14f * density

    init {
        val accent = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, 0xFF3F51B5.toInt()
        )
        val quiet = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceVariant, 0x33888888
        )
        val page = MaterialColors.getColor(
            this, android.R.attr.colorBackground, 0xFFFFFFFF.toInt()
        )
        trackPaint.color = quiet
        bandPaint.color = accent
        tickPaint.color = page
        tickPaint.strokeWidth = 1f * density
        pinPaint.color = accent
        holePaint.color = page
        isClickable = true
    }

    fun setWindow(newFrom: Int, newTo: Int) {
        from = ((newFrom % NightWindow.HOURS) + NightWindow.HOURS) % NightWindow.HOURS
        to = ((newTo % NightWindow.HOURS) + NightWindow.HOURS) % NightWindow.HOURS
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (40f * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    /** The pins sit on the ends of the track, so the track is inset by one. */
    private fun left() = paddingLeft + pinRadius
    private fun span() = (width - paddingLeft - paddingRight - 2f * pinRadius).coerceAtLeast(1f)

    /** For the tests: where hour zero sits, one pin in from the edge. */
    internal fun leftEndForTest(): Float = left()

    private fun xOf(hour: Float): Float = left() + hour / NightWindow.HOURS * span()

    /**
     * The hour under a finger at [x], as a fraction of the day.
     *
     * Not clamped: a finger that has run off the end of the bar is still
     * saying something, and what it is saying is "keep going" — see
     * [moveTo], which takes it round. Clamped, the two hours either side of
     * midnight were the only two on the bar a drag could not reach.
     */
    internal fun hourAt(x: Float): Float =
        (x - left()) / span() * NightWindow.HOURS

    override fun onDraw(canvas: Canvas) {
        val midY = (height + paddingTop - paddingBottom) / 2f
        val top = midY - trackHeight / 2f
        val bottom = midY + trackHeight / 2f
        val radius = trackHeight / 2f

        rect.set(xOf(0f), top, xOf(NightWindow.HOURS.toFloat()), bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // The band, in one piece or in two — a night that crosses midnight
        // runs off the right and comes back on the left, which is the shape
        // of the thing being described.
        if (from != to) {
            if (from < to) {
                band(canvas, from.toFloat(), to.toFloat(), top, bottom, radius)
            } else {
                band(canvas, from.toFloat(), NightWindow.HOURS.toFloat(), top, bottom, radius)
                band(canvas, 0f, to.toFloat(), top, bottom, radius)
            }
        }

        // Twenty-four sections: hairlines rather than notches, so the count
        // is there to be read without the bar turning into a ladder.
        for (hour in 1 until NightWindow.HOURS) {
            val x = xOf(hour.toFloat())
            canvas.drawLine(x, top, x, bottom, tickPaint)
        }

        // Entry solid, exit hollow: two pins that look the same are two pins
        // you have to experiment with.
        canvas.drawCircle(xOf(from.toFloat()), midY, pinRadius, pinPaint)
        canvas.drawCircle(xOf(to.toFloat()), midY, pinRadius, pinPaint)
        canvas.drawCircle(xOf(to.toFloat()), midY, pinRadius - 3.5f * density, holePaint)
    }

    private fun band(canvas: Canvas, a: Float, b: Float, top: Float, bottom: Float, r: Float) {
        rect.set(xOf(a), top, xOf(b), bottom)
        canvas.drawRoundRect(rect, r, r, bandPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The settings list scrolls, and it would happily take this
                // gesture away half way through a drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                holding = NightWindow.grabsEntry(hourAt(event.x), from, to)
                moveTo(event.x)
            }
            MotionEvent.ACTION_MOVE -> moveTo(event.x)
            MotionEvent.ACTION_UP -> {
                moveTo(event.x)
                holding = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                holding = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /**
     * Puts whichever pin is held on the nearest hour mark, going round.
     *
     * Round, because a day is. Ten at night to midnight is *forwards*, and
     * forwards on this bar is rightwards — so dragging the entry pin from
     * 22 towards the right-hand end and on is the natural way to ask for a
     * night that starts at midnight. It used to stop dead at 23: the band
     * could wrap midnight but the pin could not, and the only way to reach
     * hour 0 was to drag the whole way back round the other side, which is
     * a thing nobody thinks of doing.
     */
    internal fun moveTo(x: Float) {
        val entry = holding ?: return
        val hour = wrapped(hourAt(x))
        if (entry) from = hour else to = hour
        invalidate()
        onChanged?.invoke(from, to)
    }

    /** An hour off the end of the bar comes back on at the other end. */
    private fun wrapped(hour: Float): Int {
        val rounded = hour.roundToInt()
        return ((rounded % NightWindow.HOURS) + NightWindow.HOURS) % NightWindow.HOURS
    }

    /** For the tests: take hold of a pin without a MotionEvent. */
    internal fun holdForTest(entry: Boolean) {
        holding = entry
    }
}

/**
 * The settings row the bar lives in: the bar itself, and the window it
 * currently describes written out underneath.
 *
 * It keeps the same two preference keys the two sliders used, so a phone
 * updating to this build keeps the night it was already set to.
 */
class NightWindowPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_night_window
        isPersistent = false
    }

    private fun currentFrom() =
        sharedPreferences?.getInt(Prefs.NIGHT_FROM, NightWindow.DEFAULT_FROM)
            ?: NightWindow.DEFAULT_FROM

    private fun currentTo() =
        sharedPreferences?.getInt(Prefs.NIGHT_TO, NightWindow.DEFAULT_TO)
            ?: NightWindow.DEFAULT_TO

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Rows are recycled, so nothing here may assume it is the first time.
        val bar = holder.findViewById(R.id.night_bar) as? NightBar ?: return
        val caption = holder.findViewById(R.id.night_window_caption) as? android.widget.TextView
        bar.setWindow(currentFrom(), currentTo())
        say(bar, caption, currentFrom(), currentTo())
        bar.onChanged = { from, to ->
            sharedPreferences?.edit()
                ?.putInt(Prefs.NIGHT_FROM, from)
                ?.putInt(Prefs.NIGHT_TO, to)
                ?.apply()
            say(bar, caption, from, to)
        }
    }

    /**
     * Written out under the bar rather than in the preference's own summary
     * line: setting a summary asks the whole row to be bound again, which
     * is a poor thing to do to a view somebody has a finger on.
     *
     * Both pins on the same mark is "stop dimming" rather than "dim for
     * ever" — the difference being a screen that never comes back — and the
     * row has to say so, or it looks like a bar somebody has broken.
     */
    private fun say(bar: NightBar, caption: android.widget.TextView?, from: Int, to: Int) {
        val text = if (from == to) {
            context.getString(R.string.pref_night_window_off)
        } else {
            NightWindow.label(from, to)
        }
        caption?.text = text
        bar.contentDescription = text
    }
}
