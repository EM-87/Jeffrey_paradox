package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The weather, as a card of its own.
 *
 * The dial has carried the weather for a long time as a few strokes round
 * a token the size of a fingernail — a cloud, three drops, a bolt — which
 * says what the sky is doing and nothing about how cold it is, how hard
 * it is raining or what tomorrow looks like. This is where that goes:
 * what the three services agree on about now, the next day in eight
 * steps, and the four days after it.
 *
 * It is the token's card. The token on the dial is the way in, the same
 * token grown large at the top of this card is a way back out, and every
 * picture on it is drawn by the thing that draws the token — see
 * [SkyGlyph] — so a cloud here is the cloud there.
 *
 * It draws what [WeatherStore] has written down and asks for nothing
 * itself. What is not known is not drawn, and what only one service said
 * is drawn faintly, as it is everywhere else on this clock.
 */
class WeatherCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    /** The sky now, as agreed. */
    var sky: Weather.Sky = Weather.Sky()
        set(value) { field = value; refreshDescription(); invalidate() }

    /** And the hours to come, from the one it is. */
    var hours: List<Weather.AgreedHour> = emptyList()
        set(value) { field = value; invalidate() }

    /** Why there is nothing to show, when there is nothing. */
    enum class Missing {

        /** Nothing is: there is weather. */
        NOTHING,

        /** The switch is off, and this card is where it can be turned on. */
        OFF,

        /** Nothing has ever said where this phone is. */
        NOWHERE,

        /** On, located, and nobody has answered yet. */
        WAITING
    }

    var missing = Missing.OFF
        set(value) { field = value; refreshDescription(); invalidate() }

    /** The moment drawn, when it is not now: the tests' clock. */
    var atMs: Long? = null
        set(value) { field = value; invalidate() }

    /** Whose midnights the days run between. */
    var zone: TimeZone = TimeZone.getDefault()
        set(value) { field = value; invalidate() }

    /** Whether an hour is written 14h or 2p. */
    var hour24 = true
        set(value) { field = value; invalidate() }

    /** The token at the top was pressed: back to where the token lives. */
    var onLeave: (() -> Unit)? = null

    /** The switch on the card was pressed. */
    var onSwitchOn: (() -> Unit)? = null

    private fun nowMs(): Long = atMs ?: TimeKeeper.nowMs()

    // ------------------------------------------------------------ the words

    /** One step of the day to come: three hours, said as one. */
    internal data class Step(
        val atMs: Long,
        val label: String,
        val degrees: Double?,
        val rainMm: Double,
        val look: Weather.Look,
        val sure: Boolean
    )

    /**
     * The next day, in steps of three hours from the next hour to strike.
     *
     * Each is the temperature at its start, the rain of all three hours
     * and the worst thing any of the three is expected to do: a step with
     * one hour of rain in it is a step somebody gets wet in. From the next
     * hour and not this one, because the hour it is has mostly happened
     * and is what the top of the card is for.
     */
    internal fun steps(): List<Step> {
        val next = (Math.floorDiv(nowMs(), Weather.HOUR_MS) + 1) * Weather.HOUR_MS
        val byHour = hours.associateBy { it.atMs }
        val out = ArrayList<Step>(STEPS)
        for (i in 0 until STEPS) {
            val from = next + i * STEP_HOURS * Weather.HOUR_MS
            val block = (0 until STEP_HOURS).mapNotNull { byHour[from + it * Weather.HOUR_MS] }
            val first = block.firstOrNull { it.atMs == from } ?: continue
            out.add(
                Step(
                    atMs = from,
                    label = hourLabel(from),
                    degrees = first.temperatureC.value,
                    rainMm = block.sumOf { it.rainMm.value ?: 0.0 },
                    look = block.maxOf { it.look },
                    sure = first.sure
                )
            )
        }
        return out
    }

    /** The whole days after today, at most [DAYS] of them. */
    internal fun days(): List<Weather.Day> {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = nowMs()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val starts = ArrayList<Long>(DAYS + 1)
        for (i in 0..DAYS) {
            // A day at a time on the calendar, never twenty-four hours at
            // a time on the clock: the day the clocks change is not
            // twenty-four hours long.
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            starts.add(calendar.timeInMillis)
        }
        return Weather.days(hours, starts)
    }

    /** An hour of the day, short enough to stand over a column. */
    internal fun hourLabel(atMs: Long): String {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = atMs
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour24) return context.getString(R.string.weather_hour_24, hour)
        val twelve = if (hour % 12 == 0) 12 else hour % 12
        return context.getString(
            if (hour < 12) R.string.weather_hour_am else R.string.weather_hour_pm, twelve
        )
    }

    /** The day of the week a day starts on, as the phone's language shortens it. */
    internal fun dayName(startMs: Long): String {
        val format = SimpleDateFormat("EEE", Locale.getDefault())
        format.timeZone = zone
        return format.format(Date(startMs)).trimEnd('.')
    }

    /** A temperature to the degree, or two dashes. */
    internal fun degrees(value: Double?): String =
        if (value == null) "--°" else context.getString(R.string.weather_degrees, value.roundToInt())

    /** Rain to the tenth, in the phone's own way of writing a decimal. */
    private fun tenth(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

    /** The one word for a sky. */
    internal fun word(look: Weather.Look?): String = when (look) {
        Weather.Look.CLEAR -> context.getString(R.string.weather_look_clear)
        Weather.Look.CLOUDY -> context.getString(R.string.weather_look_cloudy)
        Weather.Look.OVERCAST -> context.getString(R.string.weather_look_overcast)
        Weather.Look.RAIN -> context.getString(R.string.weather_look_rain)
        Weather.Look.STORM -> context.getString(R.string.weather_look_storm)
        null -> ""
    }

    /** The four numbers under the temperature, each with what it is. */
    internal fun readings(): List<Pair<String, String>> {
        val none = "–"
        return listOf(
            (sky.windKph.value?.let { context.getString(R.string.weather_kph, it.roundToInt()) } ?: none) to
                context.getString(R.string.weather_wind),
            (sky.rainMmPerHour.value?.let { context.getString(R.string.weather_mm_hour, tenth(it)) } ?: none) to
                context.getString(R.string.weather_rain),
            (sky.cloudPercent.value?.let { context.getString(R.string.weather_percent, it.roundToInt()) } ?: none) to
                context.getString(R.string.weather_cloud),
            (sky.pressureHpa.value?.let { context.getString(R.string.weather_hpa, it.roundToInt()) } ?: none) to
                context.getString(R.string.weather_pressure)
        )
    }

    /** How many answered, and how long ago. */
    internal fun standing(): String {
        val heard = context.getString(
            R.string.weather_answered, sky.answered, WeatherSources.all().size
        )
        return "$heard · ${age()}"
    }

    /** How old the reading is, in the largest unit that fits. */
    internal fun age(): String {
        // A clock put back makes this negative, which is under a minute.
        val minutes = (nowMs() - sky.atMs) / 60_000L
        return when {
            minutes < 1L -> context.getString(R.string.weather_age_now)
            minutes < 60L -> context.getString(R.string.weather_age_min, minutes.toInt())
            else -> context.getString(R.string.weather_age_hours, (minutes / 60L).toInt())
        }
    }

    /**
     * Whether the reading at the top is too old to be said plainly.
     *
     * The same three hours past which a service's answer stops counting
     * towards an agreement — see [Weather.FRESH_MS]. It is still drawn,
     * because the last thing known is worth more than a blank, and it is
     * drawn the way an unconfirmed one is.
     */
    internal fun old(): Boolean = nowMs() - sky.atMs > Weather.FRESH_MS

    /** What is said in place of the weather, when there is none. */
    internal fun apology(): String = when (missing) {
        Missing.NOTHING -> ""
        Missing.OFF -> context.getString(R.string.weather_card_off)
        Missing.NOWHERE -> context.getString(R.string.weather_card_nowhere)
        Missing.WAITING -> context.getString(R.string.weather_card_waiting)
    }

    private fun refreshDescription() {
        contentDescription = if (missing != Missing.NOTHING) {
            context.getString(R.string.widget_weather_label) + ". " + apology()
        } else {
            listOf(
                context.getString(R.string.widget_weather_label),
                degrees(sky.temperatureC.value),
                word(Weather.look(sky))
            ).filter { it.isNotEmpty() }.joinToString(", ")
        }
    }

    // ----------------------------------------------------------- the layout

    /**
     * Where everything goes: one box, as wide as the card or as wide as
     * its height allows, whichever is less.
     *
     * Everything is measured from the box's width, the way everything on
     * the dial is measured from its radius, so the card is the same card
     * on a narrow phone and on one lying on its side.
     */
    private class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        fun x(share: Float): Float = left + width * share
        fun y(share: Float): Float = top + height * share
    }

    private fun box(): Box {
        val card = cardRect()
        val inset = card.width() * 0.04f
        val availableW = card.width() - 2 * inset
        val availableH = card.height() - 2 * inset
        val width = minOf(availableW, availableH / TALL)
        val height = minOf(availableH, width * TALL)
        return Box(
            card.centerX() - width / 2f,
            card.top + inset + (availableH - height) / 2f,
            width, height
        )
    }

    /** The face-coloured card everything sits on, clear of the button under it. */
    private fun cardRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return RectF(w * 0.015f, h * 0.015f, w * 0.985f, h * 0.93f)
    }

    /**
     * The token: beside the temperature, or in the middle of the card
     * when there is no temperature to be beside.
     */
    private fun tokenX(box: Box): Float =
        box.x(if (missing == Missing.NOTHING) 0.25f else 0.5f)
    private fun tokenY(box: Box): Float = box.y(0.125f)
    private fun tokenRadius(box: Box): Float = box.width * 0.085f

    /** The switch, when the card is all there is to say about the weather. */
    private fun switchRect(box: Box): RectF =
        RectF(box.x(0.28f), box.y(0.54f), box.x(0.72f), box.y(0.605f))

    internal fun tokenXForTest(): Float = tokenX(box())
    internal fun tokenYForTest(): Float = tokenY(box())
    internal fun tokenRadiusForTest(): Float = tokenRadius(box())
    internal fun switchXForTest(): Float = switchRect(box()).centerX()
    internal fun switchYForTest(): Float = switchRect(box()).centerY()

    // ----------------------------------------------------------- the paints

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val prose = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /** How strongly a thing nobody confirmed is drawn, out of 255. */
    private fun strength(sure: Boolean): Int = if (sure) 255 else WeatherGlyph.LONE_ALPHA

    private fun text(
        canvas: Canvas, what: String, x: Float, baseline: Float, size: Float,
        colour: Int = theme.numeral, alpha: Int = 255, align: Paint.Align = Paint.Align.CENTER
    ) {
        ink.color = colour
        ink.alpha = alpha
        ink.textSize = size
        ink.textAlign = align
        canvas.drawText(what, x, baseline, ink)
    }

    /** The sky token, at any size: sun or moon for [atMs], and [look] in front of it. */
    private fun token(
        canvas: Canvas, x: Float, y: Float, radius: Float, atMs: Long,
        look: Weather.Look?, sure: Boolean
    ) {
        lit.color = theme.numeral
        dark.color = theme.face
        rim.color = theme.rim
        rim.strokeWidth = radius * 0.09f
        SkyGlyph.draw(
            canvas, x, y, radius, lit, dark, rim, timeOfDay(atMs),
            whenMs = atMs, weather = look, weatherSure = sure
        )
    }

    private fun timeOfDay(atMs: Long): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = atMs
        return calendar.get(Calendar.HOUR_OF_DAY) * Weather.HOUR_MS +
            calendar.get(Calendar.MINUTE) * 60_000L
    }

    // ---------------------------------------------------------- the drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val card = cardRect()
        fill.color = theme.face
        fill.alpha = 255
        canvas.drawRoundRect(card, width * 0.05f, width * 0.05f, fill)
        val box = box()
        drawNow(canvas, box)
        if (missing != Missing.NOTHING) {
            drawApology(canvas, box)
            return
        }
        drawReadings(canvas, box)
        rule(canvas, box, 0.355f)
        drawSteps(canvas, box)
        rule(canvas, box, 0.615f)
        drawDays(canvas, box)
        drawStanding(canvas, box)
    }

    /** The token, the temperature and the one word. */
    private fun drawNow(canvas: Canvas, box: Box) {
        val known = missing == Missing.NOTHING
        val look = if (known) Weather.look(sky) else null
        val plain = known && !old()
        token(
            canvas, tokenX(box), tokenY(box), tokenRadius(box), nowMs(), look,
            sure = plain && sky.cloudPercent.trust != Weather.Trust.LONE
        )
        // With no weather there is the token and what is said in place
        // of the rest: two dashes where a temperature goes are a card
        // that looks broken, and it is not.
        if (!known) return
        val degrees = sky.temperatureC
        ink.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text(
            canvas, degrees(degrees.value), box.x(0.66f), box.y(0.165f), box.width * 0.25f,
            alpha = strength(plain && degrees.trust == Weather.Trust.AGREED)
        )
        ink.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text(
            canvas, word(look), box.x(0.66f), box.y(0.215f), box.width * 0.052f,
            alpha = if (plain) 220 else WeatherGlyph.LONE_ALPHA
        )
    }

    /** Wind, rain, cloud and pressure, each over its name. */
    private fun drawReadings(canvas: Canvas, box: Box) {
        val all = readings()
        val trust = listOf(sky.windKph, sky.rainMmPerHour, sky.cloudPercent, sky.pressureHpa)
        for ((i, reading) in all.withIndex()) {
            val x = box.x((i + 0.5f) / all.size)
            text(
                canvas, reading.first, x, box.y(0.295f), box.width * 0.043f,
                alpha = strength(!old() && trust[i].trust == Weather.Trust.AGREED)
            )
            text(canvas, reading.second, x, box.y(0.325f), box.width * 0.030f, alpha = 150)
        }
    }

    private fun rule(canvas: Canvas, box: Box, at: Float) {
        fill.color = theme.numeral
        fill.alpha = 45
        val y = box.y(at)
        canvas.drawRect(box.x(0.02f), y, box.x(0.98f), y + maxOf(1f, box.width * 0.002f), fill)
    }

    /**
     * The next day, eight steps across.
     *
     * The temperatures ride up and down with their value, so the shape of
     * the day can be seen without reading a single number: warmest in the
     * column that stands highest.
     */
    private fun drawSteps(canvas: Canvas, box: Box) {
        val steps = steps()
        if (steps.isEmpty()) return
        val known = steps.mapNotNull { it.degrees }
        val low = known.minOrNull() ?: 0.0
        val high = known.maxOrNull() ?: 0.0
        for ((i, step) in steps.withIndex()) {
            val x = box.x((i + 0.5f) / STEPS)
            text(canvas, step.label, x, box.y(0.395f), box.width * 0.034f, alpha = 170)
            token(canvas, x, box.y(0.440f), box.width * 0.030f, step.atMs, step.look, step.sure)
            val lift = if (step.degrees == null || high - low < 0.5) 0.5f
            else ((step.degrees - low) / (high - low)).toFloat()
            text(
                canvas, degrees(step.degrees), x, box.y(0.545f - 0.040f * lift), box.width * 0.045f,
                alpha = strength(step.sure)
            )
            if (step.rainMm >= Weather.RAIN_WORTH_SAYING) {
                text(
                    canvas, context.getString(R.string.weather_mm, tenth(step.rainMm)),
                    x, box.y(0.590f), box.width * 0.029f, colour = theme.pmMark,
                    alpha = strength(step.sure)
                )
            }
        }
    }

    /**
     * The days after, a row each: the day, its sky, its rain, and how
     * cold and how warm on one scale for all four — so a bar further to
     * the right is a warmer day and a longer one is a day with more
     * weather in it.
     */
    private fun drawDays(canvas: Canvas, box: Box) {
        val days = days()
        if (days.isEmpty()) return
        val low = days.mapNotNull { it.minC }.minOrNull()
        val high = days.mapNotNull { it.maxC }.maxOrNull()
        for ((i, day) in days.withIndex()) {
            val middle = box.y(0.665f + 0.068f * i)
            val baseline = middle + box.width * 0.016f
            val alpha = strength(day.sure)
            text(
                canvas, dayName(day.startMs), box.x(0.03f), baseline, box.width * 0.045f,
                alpha = alpha, align = Paint.Align.LEFT
            )
            // At noon, so that a day is always drawn under the sun.
            token(
                canvas, box.x(0.25f), middle - box.width * 0.006f, box.width * 0.027f,
                day.startMs + NOON_MS, day.look, day.sure
            )
            if (day.rainMm >= Weather.WET_DAY_MM) {
                text(
                    canvas, context.getString(R.string.weather_mm, day.rainMm.roundToInt().toString()),
                    box.x(0.41f), baseline, box.width * 0.034f, colour = theme.pmMark, alpha = alpha
                )
            }
            text(
                canvas, degrees(day.minC), box.x(0.60f), baseline, box.width * 0.043f,
                alpha = minOf(alpha, 190), align = Paint.Align.RIGHT
            )
            text(
                canvas, degrees(day.maxC), box.x(0.88f), baseline, box.width * 0.043f,
                alpha = alpha, align = Paint.Align.LEFT
            )
            val from = box.x(0.63f)
            val to = box.x(0.86f)
            val thick = box.width * 0.012f
            fill.color = theme.numeral
            fill.alpha = 40
            canvas.drawRoundRect(RectF(from, middle - thick / 2f, to, middle + thick / 2f), thick, thick, fill)
            val min = day.minC
            val max = day.maxC
            if (min != null && max != null && low != null && high != null && high > low) {
                val start = from + (to - from) * ((min - low) / (high - low)).toFloat()
                val end = from + (to - from) * ((max - low) / (high - low)).toFloat()
                fill.color = theme.sunMark
                fill.alpha = alpha
                canvas.drawRoundRect(
                    RectF(start, middle - thick / 2f, maxOf(end, start + thick), middle + thick / 2f),
                    thick, thick, fill
                )
            }
        }
    }

    /** Who was asked, how many answered, and how long ago. */
    private fun drawStanding(canvas: Canvas, box: Box) {
        text(canvas, standing(), box.x(0.5f), box.y(0.950f), box.width * 0.030f, alpha = 160)
        text(
            canvas, context.getString(R.string.weather_sources), box.x(0.5f), box.y(0.985f),
            box.width * 0.028f, alpha = 120
        )
    }

    /** What is said in place of the weather, and the switch when that is why. */
    private fun drawApology(canvas: Canvas, box: Box) {
        prose.color = theme.numeral
        prose.alpha = 220
        prose.textSize = box.width * 0.046f
        val words = apology()
        val layout = StaticLayout.Builder
            .obtain(words, 0, words.length, prose, (box.width * 0.84f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.2f)
            .build()
        canvas.save()
        canvas.translate(box.x(0.08f), box.y(0.27f))
        layout.draw(canvas)
        canvas.restore()
        if (missing != Missing.OFF) return
        val button = switchRect(box)
        rim.color = theme.numeral
        rim.strokeWidth = box.width * 0.006f
        canvas.drawRoundRect(button, button.height() / 2f, button.height() / 2f, rim)
        text(
            canvas, context.getString(R.string.weather_card_turn_on),
            button.centerX(), button.centerY() + box.width * 0.017f, box.width * 0.048f
        )
    }

    // ------------------------------------------------------------ the touch

    private var downOn = Target.NOTHING

    private enum class Target { NOTHING, TOKEN, SWITCH }

    private fun targetAt(x: Float, y: Float): Target {
        val box = box()
        // Wider than the token is drawn, as the one on the dial is: a
        // finger is a centimetre across whatever it is aiming at.
        if (hypot(x - tokenX(box), y - tokenY(box)) < tokenRadius(box) * 2.2f) return Target.TOKEN
        if (missing == Missing.OFF) {
            val button = switchRect(box)
            val slack = button.height() * 0.5f
            if (x in (button.left - slack)..(button.right + slack) &&
                y in (button.top - slack)..(button.bottom + slack)
            ) {
                return Target.SWITCH
            }
        }
        return Target.NOTHING
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOn = targetAt(event.x, event.y)
                return downOn != Target.NOTHING
            }
            MotionEvent.ACTION_UP -> {
                val on = targetAt(event.x, event.y)
                if (on != downOn || on == Target.NOTHING) return false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (on) {
                    Target.TOKEN -> onLeave?.invoke()
                    Target.SWITCH -> onSwitchOn?.invoke()
                    Target.NOTHING -> Unit
                }
                return true
            }
        }
        return downOn != Target.NOTHING
    }

    companion object {

        /** How many steps the next day is said in, and how long each is. */
        const val STEPS = 8
        const val STEP_HOURS = 3

        /** How many whole days follow. */
        const val DAYS = 4

        /** How tall the card's box is against its width. */
        private const val TALL = 1.78f

        private const val NOON_MS = 12L * Weather.HOUR_MS
    }
}
