package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Analog clock face with unusual options.
 *
 * Interaction is built on a single "virtual time offset": winding any hand
 * shifts the whole clock's displayed time, so the other hands follow
 * proportionally, like real gears. While a hand is held the mechanism is
 * frozen. Releasing starts a bouncy spring that unwinds the offset back to
 * zero. A hard knock throws the hands off the axis (in the direction of the
 * blow); further knocks shake the numerals loose too. Fallen pieces tumble
 * under the live accelerometer gravity vector until dragged back into place.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NumeralStyle { NONE, ARABIC, ROMAN }
    enum class DateFormatStyle { NUMBER, TEXT, ROMAN }
    enum class FastHandMode { NONE, TENTHS, DECIMAL_MINUTE }

    /**
     * The dial doesn't have to be round. Polygonal faces keep the same
     * angular time layout, but the boundary breathes in and out between
     * corners — and everything pinned to the rim (ticks, numerals, even the
     * hands' lengths) follows it, so the second hand stretches into the
     * corners as it sweeps. Orientations are chosen symmetric about the
     * vertical axis so mirror mode stays consistent.
     */
    enum class DialShape(val sides: Int, val vertexOffsetDeg: Float) {
        CIRCLE(0, 0f),
        TRIANGLE(3, 0f),
        SQUARE(4, 45f),
        HEXAGON(6, 0f),
        OCTAGON(8, 22.5f)
    }

    /**
     * Magnet layout for wind-to-set. COUNTDOWN is progressive — minute
     * detents up to 5 min, 5-minute up to half an hour, quarter-hour up to
     * two hours, hourly beyond — so sweeping across an hour doesn't rattle
     * through 75 detents. ALARM keeps a flat 5-minute grid.
     */
    enum class MagnetProfile { COUNTDOWN, ALARM }
    internal enum class Hand { HOUR, MINUTE, SECOND }

    /** Sounds triggered by interacting with the clock. */
    interface SoundListener {
        fun onTickCrossed()
        fun onHourCrossed()
        fun onDayCrossed()
        fun onHandMounted()
        fun onExploded()
        fun onCheater()
    }

    var soundListener: SoundListener? = null

    /** Fired on every knock that shakes something loose (hosts react too). */
    var onKnocked: (() -> Unit)? = null

    /** Knocks the hands off programmatically (bubbles echo the main dial). */
    fun knockHandsOff() {
        dropHands(0f, -6f)
    }

    var hoursOnDial = 12
        set(value) { field = value.coerceIn(2, 24); invalidate() }
    var showSecondHand = true
        set(value) { field = value; invalidate() }

    /**
     * Whether the minute hand is drawn.
     *
     * A clock with no minute hand is read to the nearest hour, which is how
     * a great many of them were read for a great many centuries — and on a
     * face with numerals and no minute marks it is not even a loss.
     */
    var showMinuteHand = true
        set(value) { field = value; invalidate() }

    /**
     * Whether a hand is on the dial at all.
     *
     * Asked in a dozen places — drawing, hit testing, falling, colliding,
     * the reading a screen reader gets — and each of them used to carry its
     * own copy of "second hand, and the second hand is switched off". One
     * copy means a second hand that can be switched off; one *predicate*
     * means any of them can.
     */
    internal fun handIsOn(hand: Hand): Boolean = when (hand) {
        Hand.SECOND -> showSecondHand
        Hand.MINUTE -> showMinuteHand
        Hand.HOUR -> true
    }
    var smoothSeconds = false
        set(value) { field = value; invalidate() }
    var fastHand = FastHandMode.NONE
        set(value) { field = value; invalidate() }
    var mirrored = false
        set(value) { field = value; invalidate() }
    var numeralStyle = NumeralStyle.ARABIC
        set(value) { field = value; invalidate() }
    var dialShape = DialShape.CIRCLE
        set(value) { field = value; invalidate() }
    var showDate = false
        set(value) { field = value; invalidate() }
    var dateFormatStyle = DateFormatStyle.NUMBER
        set(value) { field = value; invalidate() }

    /**
     * Which way round the two numbers go. See [DateShape].
     *
     * Resolved to a plain boolean by whoever sets it, because "what does
     * the phone say" is a question for a Context and this is a view that
     * would rather be told.
     */
    var dateDayFirst = true
        set(value) { field = value; invalidate() }
    var touchHandsEnabled = true
    var pinchZoomEnabled = true
    /** The seven-segment readout under the dial. */
    var showDigitalReadout = true
        set(value) { field = value; invalidate() }
    var shakeDropEnabled = true
        set(value) {
            field = value
            // Turned on from settings while already on screen: pick the
            // accelerometer back up, since attaching is long past.
            if (value && isAttachedToWindow) listenForShakes()
        }
    var dialScale = 1f
        set(value) {
            val next = value.coerceIn(MIN_SCALE, MAX_SCALE)
            // Fallen pieces live in dial space: rescale them with it.
            if (next != field && field > 0f && debris.bodies.isNotEmpty()) {
                val f = next / field
                val cx = width / 2f
                val cy = height / 2f
                for (b in debris.bodies) {
                    b.x = cx + (b.x - cx) * f
                    b.y = cy + (b.y - cy) * f
                    b.vx *= f
                    b.vy *= f
                    b.halfLen *= f
                    b.strokeWidth *= f
                    b.textSize *= f
                }
            }
            field = next
            invalidate()
        }
    var onDialScaleChanged: ((Float) -> Unit)? = null
    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; applyTheme(value); invalidate() }
    var timeZone: TimeZone = TimeZone.getDefault()
        set(value) { field = value; cal.timeZone = value; invalidate() }

    /**
     * When true (countdown being set), winding a hand commits the new value
     * through [onChronoAdjusted] with no spring-back, magnetized to round
     * durations, and the minute/hour hands take grab priority.
     */
    var chronoSettable = false

    /**
     * Draws chronograph hardware on the case — start/stop pusher at 2
     * o'clock, crown at 3, reset pusher at 4 — fading in with the mode
     * transition. The pushers are touchable.
     */
    var chronoButtons = false
        set(value) {
            if (field != value) buttonsAnimStart = SystemClock.uptimeMillis()
            field = value
            invalidate()
        }

    /** Tints the start/stop pusher while the chronograph is running. */
    var chronoRunning = false
        set(value) {
            if (field == value) return
            field = value
            // The ticker was already posted at whatever the *old* answer to
            // tickDelayMs() was — and a stopped chronograph only asks for a
            // frame each second. Pressing start used to do nothing but
            // invalidate, so the first frame of a running stopwatch could be
            // most of a second late and the hand sat still under the thumb
            // that had just pressed it. Stopping felt quicker only because
            // it was already running at sixty frames a second.
            kickTicker()
            invalidate()
        }

    var onChronoStartStop: (() -> Unit)? = null
    var onChronoReset: (() -> Unit)? = null

    /**
     * Tapping the crown, with whether it found anything to put right.
     *
     * The flag is what tells the cuckoo from the click: winding a tidy dial
     * used to set a whole bird off for nothing. Five frantic taps blow the
     * hands off instead, which is the other thing a crown is for.
     */
    var onCrownTap: ((tidied: Boolean) -> Unit)? = null
    private val crownTapTimes = ArrayDeque<Long>()

    /** Receives the adjusted duration when the user sets the countdown. */
    var onChronoAdjusted: ((Long) -> Unit)? = null

    var magnetProfile = MagnetProfile.COUNTDOWN

    /**
     * Where the magnet grid counts from, in milliseconds of the day.
     *
     * Zero for a countdown, which really does start at nothing. For a length
     * wound onto a face — "this begins at six and lasts how long?" — it is
     * the hour it begins at, so the detents fall on twenty past, half past,
     * an hour later, rather than on multiples of midnight.
     */
    var magnetOrigin = 0L

    /**
     * True while the value being wound is a time of day rather than a
     * duration, so it lives in a day and wraps at the end of one.
     *
     * Winding past midnight used to keep counting — 25:00, 26:00 — with
     * nothing on the face saying so, and only the commit quietly folded it
     * back. Now the hands say it: cross twenty-four hours and the dial reads
     * 00:00, which is where you actually are.
     */
    var chronoWrapsDay = false

    /**
     * A recorded lap: the angles of all three hands plus the reading they
     * showed, and whether that reading was the truth at the time.
     */
    private class Lap(
        val hour: Float,
        val minute: Float,
        val second: Float,
        val ms: Long,
        val fake: Boolean
    )

    private val laps = mutableListOf<Lap>()

    // The unfolded lap list: opened by a tap on the ladder, scrolled by
    // dragging, closed by another tap.
    private var lapsExpanded = false
    private var lapListScroll = 0f
    private var lapListDragging = false
    private var lapListLastY = 0f
    private var lapListDownY = 0f
    private var ladderTapTop = 0f
    private var tapDownX = 0f
    private var tapDownY = 0f
    private val scrimPaint = Paint()

    /**
     * Alarm dots, as an angle and which turn of the dial the time is on.
     * The angle alone cannot say: that is the whole problem these solve.
     */
    var alarmMarkers: List<DialMark> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * Event wedges: start angle, sweep, and the turn they belong to.
     *
     * Drawn Sectograph style — a wedge covering the time the event actually
     * occupies. Alarms are instants and get dots; only events have a length.
     */
    var eventArcs: List<DialArc> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * The sky complication: the sun while the sun is up where the app was
     * last located, the moon and its phase otherwise. One glyph, one place,
     * one setting — see [drawMoonPhase].
     */
    var showMoonPhase = false
        set(value) { field = value; invalidate() }

    /**
     * Whether that glyph tracks the moon's phase, or is a plain disc.
     *
     * Two facts, not one, since the sky got a single switch of its own.
     * The token has to exist whenever the solar system is on, because it is
     * the thing you press to open it; whether it also reports what the moon
     * is doing tonight is a separate question, and the one the row on the
     * advanced page actually asks.
     */
    var moonPhaseShown = true
        set(value) { field = value; invalidate() }

    /** Whether the year ring carries the twelve signs — see [OrreryDial]. */
    var zodiacShown = false
        set(value) { field = value; invalidate() }

    /**
     * Whether tapping the sky token opens the solar system.
     *
     * Off by default and hung off the token on purpose: the whole gesture
     * is "press on the sky and the sky opens", and that only reads if there
     * is already a sun or a moon there to press.
     */
    var orreryEnabled = false
        set(value) {
            field = value
            if (!value) closeOrrery()
            invalidate()
        }

    /**
     * Whether the four comets are drawn on the solar system.
     *
     * Its own switch under the orrery's, and off unless asked for. Their
     * orbits cross every ring on the dial — that is what a comet is — so
     * they are four long wires laid over the whole picture, and somebody
     * who turned the planets on to look at the planets should get the
     * planets.
     */
    var cometsEnabled = false
        set(value) {
            field = value
            invalidate()
        }

    /** Caption drawn inside the dial's upper half (world-clock city names). */
    var dialLabel: String? = null
        set(value) { field = value; invalidate() }

    fun recordLap() {
        // The honest test, and the only one that catches every trick: does
        // the lap the dial *shows* match the time actually elapsed? Winding
        // a hand does it, and so does catching the spring on its way back —
        // no need to know which stunt was pulled.
        val shown = chronoDisplayMs() ?: 0L
        val truth = chronoProvider?.invoke() ?: 0L
        val fake = chronoRunning && !chronoSettable &&
            kotlin.math.abs(shown - truth) > FAKE_LAP_TOLERANCE_MS
        if (fake) {
            cheaterFlagged = true
            cheaterUntil = SystemClock.uptimeMillis() + 600_000L
            if (cheaterFade >= 1f) cheaterFade = 0f
            soundListener?.onCheater()
        }
        val a = currentAngles()
        laps.add(Lap(a.hour, a.minute, a.second, shown, fake))
        // The ladder shows seven; the unfolded list scrolls, so it is worth
        // keeping far more than that.
        while (laps.size > MAX_LAPS) laps.removeAt(0)
        // Each new lap fades the CHEATER stamp a little; ten honest laps
        // wash the shame off entirely.
        if (cheaterUntil > 0L) cheaterFade = (cheaterFade + 0.1f).coerceAtMost(1f)
        invalidate()
    }

    fun clearLaps() {
        laps.clear()
        invalidate()
    }

    /**
     * A lap, flattened so it can be written down somewhere.
     *
     * The angles travel with it rather than being worked out again from the
     * time: a faked lap is precisely one whose hands disagreed with its
     * number, so deriving the hands from the number would quietly make
     * every restored lap honest.
     */
    class LapRecord(
        val ms: Long,
        val fake: Boolean,
        val hour: Float,
        val minute: Float,
        val second: Float
    )

    fun exportLaps(): List<LapRecord> =
        laps.map { LapRecord(it.ms, it.fake, it.hour, it.minute, it.second) }

    fun importLaps(records: List<LapRecord>) {
        laps.clear()
        for (r in records.takeLast(MAX_LAPS)) {
            laps.add(Lap(r.hour, r.minute, r.second, r.ms, r.fake))
        }
        invalidate()
    }

    /**
     * Fired on a horizontal swipe; the argument is true when the finger moved
     * right. Return true to consume (used for page navigation over the dial).
     */
    var onHorizontalSwipe: ((Boolean) -> Boolean)? = null

    /**
     * A flick up or down, if anybody is listening.
     *
     * The app is not. It used to move between the clock row and the
     * chronograph row, and that was taken off the dials: the hourglass and
     * the two chronographs are secondary things, reached by the button that
     * names them, and a gesture that carried you to one by accident — from
     * a dial you were winding, or a bubble you were flicking — cost more
     * than it ever saved. The transitions themselves are untouched; what
     * went is the way in.
     *
     * The hook stays because it is the view's, not the app's, and because
     * leaving it null now costs nothing: [handleVerticalFling] asks for the
     * listener before it gives anything up, so an unwired flick no longer
     * aborts the hand under your finger.
     */
    var onVerticalSwipe: ((up: Boolean) -> Boolean)? = null

    /**
     * A second row of digits under the readout, or none.
     *
     * Returning null means there is nothing to say, which is not the same
     * as there being no provider: on the countdown this is switched on and
     * off by the crown, and the switch lives with whoever owns the number
     * rather than here.
     */
    var secondReadout: (() -> Long?)? = null

    var chronoProvider: (() -> Long)? = null
        set(value) {
            // Travel from where the hands are to the new mode's positions
            // instead of snapping — but only on a dial that has hands to
            // travel from.
            //
            // A face that has never been drawn is not changing into
            // anything; it is appearing for the first time, and "where the
            // hands are" on it is whatever o'clock it happens to be. That
            // is the reasoning the fade already followed, and the hands
            // were left out of it: every mini dial on the alarm cards was
            // born showing now and wound itself to its alarm's time, so
            // opening the drawer set a dozen little watches going while the
            // ones the list had recycled sat still.
            if (field !== value && hasDrawn) {
                beginTransition(currentAngles(), fades = true)
            }
            field = value
            spring?.cancel()
            spring = null
            draggedHand = null
            activeSoundHand = null
            frozenDisplayMs = null
            chronoFrozenMs = null
            visualOffsetSeconds = 0.0
            invalidate()
            // Restart the ticker: on a slow-ticking clock its next run could
            // be up to a second away, which froze the transition mid-flight
            // and made the hands appear to jump.
            removeCallbacks(ticker)
            post(ticker)
        }

    /**
     * True only while the second hand itself is in the user's fingers.
     *
     * The ticking used to fall silent for any hand at all, from when the
     * second hand was dragged round by whatever else was being wound and a
     * tick a second would have been a lie. It keeps real time now, so it
     * keeps its voice — except while it is the hand being wound, where the
     * winding fires its own ticks and two would be one too many.
     */
    fun isSecondHandGrabbed(): Boolean = draggedHand == Hand.SECOND

    internal fun grabbedHandForTest(): Hand? = draggedHand

    fun isSecondHandFallen(): Boolean = isFallen(Hand.SECOND)

    // ------------------------------------------------------------- painting

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
    /**
     * The four other alphabets, and the one printed face.
     *
     * A thousand lines about the shapes of letters, which is not what a
     * clock is about — see [DateScripts]. What it needs from this view is
     * gathered into a [DateScripts.Frame] and handed over; nothing in it
     * reaches back.
     */
    private val scripts = DateScripts()

    /**
     * The face the century the sky is wound to writes its words in.
     *
     * Null everywhere but the far future, which means "the ordinary one" —
     * the other eras' alphabets are drawn rather than typed, so a word in
     * them is not something a typeface can do. This is the one era where
     * the whole row, words included, can change together.
     */
    private fun eraFace(): android.graphics.Typeface? =
        if (OrreryYear.scriptFor(SkyAge.yearOf(orreryMs())) == OrreryYear.Script.YAUTJA) {
            Yautja.face(context)
        } else {
            null
        }

    /** For the tests: the face this century's words are written in. */
    internal fun eraFaceForTest(): android.graphics.Typeface? = eraFace()

    /** What the writing needs to know about the frame it is being drawn on. */
    private fun scriptFrame(): DateScripts.Frame = DateScripts.Frame(
        digitalPaint, orreryMs(), dateDayFirst, width, Yautja.face(context)
    )

    // The tests ask the dial which display wrote the row, because the dial
    // is the thing on screen. Where the counting happens is this file's
    // business and not theirs, so the questions stay here and the answers
    // come from next door.

    /** For the tests: sixteen-bar modules in the last row the sky wrote. */
    internal fun barsPaintedForTest(): Int = scripts.barsPaintedForTest()

    /** And characters in the far-future face. */
    internal fun yautjaCharsForTest(): Int = scripts.yautjaCharsForTest()

    /** And hieroglyphs. */
    internal fun egyptiansPaintedForTest(): Int = scripts.egyptiansPaintedForTest()

    /** And wedges. */
    internal fun wedgesPaintedForTest(): Int = scripts.wedgesPaintedForTest()

    /** And characters set in type. */
    internal fun printedCharsForTest(): Int = scripts.printedCharsForTest()

    /** For the tests: the Egyptian date the wound sky is standing on. */
    internal fun egyptianDate(): EgyptianCalendar.Date = scripts.egyptianDate(orreryMs())

    /** For the camera: a row of one of the other displays, drawn anywhere. */
    internal fun drawScriptForTest(
        canvas: Canvas, text: String, cx: Float, top: Float, digitH: Float,
        starFrom: Int = Int.MAX_VALUE
    ) = scripts.drawScriptForTest(canvas, text, cx, top, digitH, starFrom)

    /** For the camera: one number in hieroglyphs, drawn anywhere. */
    internal fun drawEgyptianForTest(
        canvas: Canvas, value: Int, x: Float, y: Float, h: Float
    ): Float = scripts.drawEgyptianForTest(canvas, value, x, y, h)

    /** For the camera: one number in wedges, drawn anywhere. */
    internal fun drawCuneiformForTest(
        canvas: Canvas, value: Int, x: Float, y: Float, h: Float
    ): Float = scripts.drawCuneiformForTest(canvas, value, x, y, h)

    private val digitalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    /**
     * The shadow of a hand: the hand's own shape in the dark.
     *
     * Its own paint rather than the hand's with the colour changed,
     * because the hand paints carry stroke widths that the drawing sets
     * as it goes, and borrowing one meant the shadow and the hand were
     * one object arguing about how wide it was.
     */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }


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
    private val fastHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fastTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pusherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val alarmMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val alarmMarkerPath = Path()
    private val moonDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonLitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** For the planets lying in the case, each in the colour it brought. */
    private val fallenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cheaterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Rebuilt when the order changes rather than held for ever: it changes
    // once in the life of an install, and a stale pattern is a date written
    // the way somebody has just said they do not write dates.
    private var numberDateFormat = SimpleDateFormat(DateShape.numberPattern(true), Locale.getDefault())
    private var textDateFormat = SimpleDateFormat(DateShape.textPattern(true), Locale.getDefault())
    private var formatsBuiltDayFirst = true

    private fun dateFormats(): Pair<SimpleDateFormat, SimpleDateFormat> {
        if (formatsBuiltDayFirst != dateDayFirst) {
            numberDateFormat = SimpleDateFormat(DateShape.numberPattern(dateDayFirst), Locale.getDefault())
            textDateFormat = SimpleDateFormat(DateShape.textPattern(dateDayFirst), Locale.getDefault())
            formatsBuiltDayFirst = dateDayFirst
        }
        return numberDateFormat to textDateFormat
    }
    private val cal: Calendar = Calendar.getInstance()

    private var selectedColor = 0

    init {
        applyTheme(theme)
        // A custom View with no text is skipped by default; this one has
        // something to say.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun applyTheme(t: ClockTheme) {
        facePaint.color = t.face
        rimPaint.color = t.rim
        tickPaint.color = t.tick
        minorTickPaint.color = t.minorTick
        numeralPaint.color = t.numeral
        datePaint.color = t.numeral
        datePaint.alpha = 210
        digitalPaint.color = t.decimal
        hourHandPaint.color = t.hourHand
        minuteHandPaint.color = t.minuteHand
        secondHandPaint.color = t.secondHand
        fastHandPaint.color = t.decimal
        fastHandPaint.alpha = 200
        fastTickPaint.color = t.decimal
        fastTickPaint.alpha = 140
        centerDotPaint.color = t.centerDot
        cheaterPaint.color = t.secondHand
        selectedColor = t.secondHand
        lapPaint.color = t.secondHand
        alarmMarkerPaint.color = t.decimal
        alarmMarkerPaint.alpha = 230
        moonDarkPaint.color = t.minorTick
        moonDarkPaint.alpha = 90
        moonLitPaint.color = t.numeral
        moonLitPaint.alpha = 235
        moonRimPaint.color = t.minorTick
    }

    // --------------------------------------------------- virtual time state

    /** Seconds added to display time by winding the hands. Zero at rest. */
    private var visualOffsetSeconds = 0.0

    /** For the tests: how far the hands have been wound off the true time. */
    internal fun handWindForTest(): Double = visualOffsetSeconds
    private var draggedHand: Hand? = null

    /** While a hand is held, the mechanism freezes at this display time. */
    private var frozenDisplayMs: Long? = null

    /** Chrono equivalent of the freeze: the held chronograph value. */
    private var chronoFrozenMs: Long? = null
    private var cheaterFlagged = false
    private var cheaterUntil = 0L

    /** How far the CHEATER stamp has been washed off by honest laps (0–1). */
    private var cheaterFade = 0f

    /**
     * Starts this dial's hands where [other]'s are and lets them travel to
     * their own positions.
     *
     * The clock and the chronograph are two views, not one, and moving
     * between them used to cross-fade: the old face dissolved while the new
     * one appeared, which hid the one thing worth watching. A watch does not
     * dissolve. Its hands go round. Handed the angles the outgoing dial was
     * showing, the arriving one covers the distance in the same seven
     * hundred milliseconds a mode change on a single dial already took, and
     * the two cards read as one dial changing its mind.
     */
    fun handOverFrom(other: ClockView) {
        beginTransition(other.currentAngles(), fades = true)
        // The case hardware is handed over too. A dial that loses its crown
        // does fade it out, but on a diagonal the card it was drawn on is
        // cut away with its page in the same frame, so that fade-out plays
        // to nobody: going out the crown grew in, coming back it simply was
        // not there. The arriving dial inherits it and dissolves it in
        // place instead, which is where the eye already is.
        if (other.chronoButtons && !chronoButtons) {
            buttonsAnimStart = SystemClock.uptimeMillis()
        }
        // And the rest of what it was carrying dissolves here — see
        // [drawGhost].
        ghostDial = other
        removeCallbacks(ticker)
        post(ticker)
        invalidate()
    }

    /** True while the hands are still on their way from a hand-over. */
    internal fun isTravelling(): Boolean = transitionFrom != null

    /**
     * True while the crown and pushers are on the face — worn outright, or
     * still fading after being taken off or inherited.
     *
     * The same question [onDraw] asks before drawing them, so a test can
     * ask it of a dial that has never been on a screen.
     */
    internal fun isCrownShowing(): Boolean =
        chronoButtons || SystemClock.uptimeMillis() - buttonsAnimStart < BUTTONS_MS

    /** Mode-change animation: blend from these angles to the target ones. */
    private var transitionFrom: Angles? = null
    private var transitionStartAt = 0L

    /**
     * Whether this particular transition should fade the face's furniture
     * in as well as move the hands.
     *
     * Only when there was a face on screen to replace. A dial that has
     * never been drawn is not changing into anything — it is appearing for
     * the first time, and appearing at zero alpha is just being invisible,
     * which is what the little faces on the alarm cards started doing: born
     * with a provider, hence born mid-transition, hence born blank.
     */
    private var furnitureFades = false
    private var hasDrawn = false

    /**
     * The dial this one is replacing, for as long as the hand-over lasts.
     * Its furniture is drawn here, fading out. Held only for those seven
     * hundred milliseconds; both dials belong to the activity anyway.
     */
    private var ghostDial: ClockView? = null

    /**
     * [fades] says whether the furniture should cross-fade over this
     * transition or simply be there.
     *
     * A hand-over always fades: there is an outgoing dial by definition,
     * and its furniture has to go somewhere to dissolve. A dial changing
     * its own mode fades only if it has been drawn — otherwise it is not
     * changing into anything, it is being born, and the little faces on the
     * alarm cards are born with a provider and so were born blank.
     */
    private fun beginTransition(from: Angles, fades: Boolean) {
        transitionFrom = from
        transitionStartAt = SystemClock.uptimeMillis()
        furnitureFades = fades
        ghostDial = null
    }
    private val transitionInterpolator = AccelerateDecelerateInterpolator()

    // Chronograph case hardware. Long ago rather than zero: "is the crown
    // still fading" is asked as "was it started less than half a second
    // ago", and zero is less than half a second ago on a machine that has
    // only just started up — which is every test and, briefly, a phone.
    private var buttonsAnimStart = -1_000_000L
    private var pressedPusher = 0 // 0 none, 1 start/stop, 2 reset

    /** Magnet the countdown is currently locked onto while setting it. */
    private var lockedMagnetMs: Long? = null

    /** Which hand's sound profile applies while winding or springing back. */
    private var activeSoundHand: Hand? = null
    private var lastTouchDeg = 0f
    private var dragStartOffset = 0.0
    private var dragAccumDeg = 0.0
    private var spring: SpringAnimation? = null
    private var lastTickSoundAt = 0L
    private var lastBellSoundAt = 0L
    private var lastDaySoundAt = 0L
    private var exploded = false

    // -------------------------------------------------------- numeral state

    private val selectedHours = HashSet<Int>()

    /** Fired when the highlighted hours change, so the widget can match. */
    var onSelectedHoursChanged: ((Set<Int>) -> Unit)? = null

    fun setSelectedHours(hours: Set<Int>) {
        selectedHours.clear()
        selectedHours.addAll(hours)
        invalidate()
    }

    private val numeralToggleTimes = HashMap<Int, ArrayDeque<Long>>()
    private var tapCandidate = false

    // ----------------------------------------------------- fallen-body state

    /**
     * The loose pieces, and the physics that moves them.
     *
     * Its own class: a rigid-body simulation and the code that draws a
     * minute hand are two jobs, and every bug this file has had came from
     * one of them reaching into the other. What stayed here is everything
     * that needs to know what a clock looks like — which pieces exist,
     * where they start, where they belong when you put them back, and how
     * to draw them.
     */
    private val debris = DialDebris(object : DialDebris.Case {
        override val caseWidth: Int get() = width
        override val caseHeight: Int get() = height
        override fun wallAt(angleDeg: Float): Float = boundaryRadius(angleDeg)
    })

    private var lastPhysicsAt = 0L

    /**
     * When the last knock was felt. Long ago, not zero.
     *
     * Zero means "at uptime zero", and uptime is counted from the last time
     * the phone was switched on — so the guard that stops one blow being
     * counted twice was also swallowing the first blow of the first second
     * after a reboot. The fourth field in this file to have been caught
     * saying it; the others are [orreryChangedAt], [moonRejoinAt] and
     * `buttonsAnimStart`.
     */
    private var lastShakeAt = -1_000_000L
    private var lastCarryX = 0f
    private var lastCarryY = 0f
    private var lastCarryAt = 0L
    private var lowPassX = 0f
    private var lowPassY = 9.81f
    private var lowPassZ = 0f

    /**
     * How many readings have arrived since the accelerometer was switched
     * on, and whether the smoothing has been given a starting value.
     *
     * A knock is the difference between the raw reading and the smoothed
     * one, and the smoothed one is a field that outlives the listener. Come
     * back to this dial from another card — which detaches and re-attaches
     * it — and the first reading is measured against a smoothed value from
     * whenever the view was last on screen. Any change of posture in
     * between reads as a blow.
     *
     * That is not a hypothetical: a phone was dropped hard, the clock was
     * fine, and the hands were on the floor after a trip to the calendar
     * and back. The drop had nothing to do with it. So the smoothing is
     * seeded from the first reading rather than converged towards it, and
     * nothing counts as a knock until it has settled.
     */
    private var settleSamples = 0
    private var smoothingSeeded = false

    // Two guards against the same phantom, and either one is enough on its
    // own: seeding the smoothing from the first reading, and refusing to
    // call anything a knock until it has settled. They are both kept
    // because they are two lines between them and because the failure they
    // prevent — a clock quietly taking itself apart in your pocket — is not
    // one anybody would think to look for. A test breaks both at once, since
    // breaking either alone leaves the other doing the work.

    private var sensorManager: SensorManager? = null
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            if (!smoothingSeeded) {
                // Started from the first reading, not walked towards it: a
                // smoothed value carried over from the last time this view
                // was on screen is a phone in a different posture, and the
                // difference between the two reads as a blow.
                smoothingSeeded = true
                lowPassX = ax
                lowPassY = ay
                lowPassZ = az
            }
            if (settleSamples < SETTLE_SAMPLES) settleSamples++
            // Heavy smoothing, then a dead zone: raw accelerometer noise on a
            // phone lying perfectly still was enough to make settled debris
            // shiver in place forever.
            lowPassX = lowPassX * 0.92f + ax * 0.08f
            lowPassY = lowPassY * 0.92f + ay * 0.08f
            lowPassZ = lowPassZ * 0.92f + az * 0.08f
            // Device +X points right, +Y up the screen; view +Y is downward.
            var gx = -lowPassX / 9.81f
            var gy = lowPassY / 9.81f
            if (kotlin.math.abs(gx) < 0.04f) gx = 0f
            if (kotlin.math.abs(gy) < 0.04f) gy = 0f
            tiltX = gx
            tiltY = gy
            applyGravity()

            if (!shakeDropEnabled || chronoProvider != null) return
            // Nothing is a knock until the smoothing has caught up with
            // where the phone actually is.
            if (settleSamples < SETTLE_SAMPLES) return
            val devX = ax - lowPassX
            val devY = ay - lowPassY
            val devZ = az - lowPassZ
            val jolt = sqrt(devX * devX + devY * devY + devZ * devZ)
            val now = SystemClock.uptimeMillis()
            if (jolt > SHAKE_THRESHOLD && now - lastShakeAt > 1200) {
                lastShakeAt = now
                onKnock(-devX, devY)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Which way the phone is leaning, as a fraction of one g. */
    private var tiltX = 0f
    private var tiltY = 1f

    private var carriedAccelX = 0f
    private var carriedAccelY = 0f

    /**
     * The acceleration of whatever is carrying this dial around, in px/s².
     *
     * A world-clock bubble is a clock inside something that flies: shove it
     * across the screen and it stops dead against a wall, and the loose
     * hands inside it went on lying where they were as though nothing had
     * happened. They only ever felt the phone. Now they feel the bubble
     * too, which is the difference between a picture of a broken watch
     * sliding about and a broken watch sliding about.
     *
     * Passed as the force the contents feel, so the caller negates the
     * carrier's own acceleration: brake hard and everything inside pitches
     * forward.
     */
    fun setCarrierAcceleration(ax: Float, ay: Float) {
        carriedAccelX = ax
        carriedAccelY = ay
        applyGravity()
    }

    private fun applyGravity() {
        debris.gravityX = tiltX * DialDebris.BASE_GRAVITY + carriedAccelX
        debris.gravityY = tiltY * DialDebris.BASE_GRAVITY + carriedAccelY
    }

    // -------------------------------------------------------------- ticking

    /**
     * A face standing for one fixed time, which never changes on its own.
     *
     * The little dials on the alarm cards are all like this, and every one
     * of them was redrawing sixty times a second — because "is anything
     * moving" was written as `chronoProvider != null`, and a fixed face has
     * a provider too, one that returns the same number forever. A card with
     * four repetitions is four of them, and a list of alarms is dozens: the
     * jank on C1 was the app drawing several thousand frames a second of
     * clocks that had not moved.
     */
    var staticFace = false

    /**
     * How long until this dial needs drawing again, or -1 for "not until
     * something is done to it".
     *
     * Its own function because it is the whole of the answer to "why is
     * this list stuttering", and a rule nobody can see from outside is a
     * rule nobody checks.
     */
    internal fun tickDelayMs(): Long {
        // Nothing on a still face changes until something is done to it,
        // and whatever is done calls invalidate itself. It winds itself
        // into place when it is born, so the ticker runs while that is
        // going on and then gives up.
        if (staticFace && !isAnimating()) return -1L
        if (wantsFastFrames()) return 16L
        // Otherwise on the second, so the second hand steps when the second
        // does rather than a fraction of a second after it.
        return 1000L - (TimeKeeper.nowMs() % 1000L).coerceIn(0L, 999L)
    }

    /**
     * Whether anything on this dial is moving fast enough to be worth sixty
     * frames a second.
     *
     * Its own function because it is the whole of the answer to "why is
     * this list stuttering", and because the delay it feeds into is partly
     * a matter of where in the second the clock currently is — which makes
     * the number a poor thing to ask questions of.
     */
    internal fun wantsFastFrames(): Boolean {
        if (isAnimating()) return true
        // showsFastHand answers "is a tenths hand drawn", which is not the
        // same question: a paused chronograph draws one and it is frozen.
        // What earns the frames is a hand actually moving, so the time
        // source has to be running for any of them to count.
        if (chronoProvider != null && !chronoRunning) return false
        return chronoProvider != null || showsFastHand() ||
            (smoothSeconds && showSecondHand)
    }

    /**
     * How many times the frame loop has been restarted from scratch,
     * counted so the tests can see it happen.
     *
     * Restarting is the whole of the fix: the delay is worked out when a
     * frame is posted, so anything that changes the answer has to throw
     * away the pending one and ask again.
     */
    internal var tickerKicks = 0
        private set

    private fun kickTicker() {
        tickerKicks++
        removeCallbacks(ticker)
        post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            val delay = tickDelayMs()
            if (delay >= 0L) postDelayed(this, delay)
        }
    }

    private fun isAnimating(): Boolean =
        draggedHand != null || spring?.isRunning == true ||
            debris.bodies.isNotEmpty() ||
            // Asked of the clock, not of the field. `transitionFrom` is
            // only put back to null inside a draw, so a dial that stopped
            // being drawn mid-hand-over went on claiming to be animating
            // for ever — which is a claim that asks for sixty frames a
            // second of nothing.
            (transitionFrom != null &&
                SystemClock.uptimeMillis() - transitionStartAt < TRANSITION_MS) ||
            // The crown and pushers fade on their own clock, and nothing was
            // asking for the frames to draw it with. Arriving, the hand-over
            // happened to provide them; leaving, there was no hand-over and
            // the fade got whatever frames the dial drew anyway — one a
            // second on a stopped chronograph. Hence a crown that grew in
            // and then vanished.
            SystemClock.uptimeMillis() - buttonsAnimStart < BUTTONS_MS ||
            // The same trap, and it caught the solar system next: the fade
            // between the hands and the planets reads a clock every frame
            // and nothing was asking for frames to read it with. It got one
            // a second from the ticking second hand, which is a fade in
            // eight steps — and none at all with the second hand off.
            skyIsMoving()

    /**
     * Whether the sky is mid-fade, or the Moon is sliding back into the
     * mechanism after being let go.
     */
    private fun skyIsMoving(): Boolean {
        // Asked of the clock rather than of the fade's value. At the very
        // first frame the fade is still nought, and a predicate that read
        // the value would decide nothing was happening on the one frame
        // that had to start it off.
        val now = SystemClock.uptimeMillis()
        if (orreryChangedAt != NEVER && now - orreryChangedAt < ORRERY_FADE_MS) return true
        if (winding.travelling()) return true
        if (chronoGlideAt != NEVER) return true
        return now - moonRejoinAt < MOON_REJOIN_MS
    }

    /** For the tests: whether the dial is asking for frames of its own. */
    internal fun isAnimatingForTest(): Boolean = isAnimating()

    /** True when fallen pieces are lying around the dial. */
    fun isDisarranged(): Boolean = debris.bodies.isNotEmpty()

    /** Instantly puts every fallen piece back and resets all play state. */
    fun reassembleAll() {
        timeScale = 1f
        debris.clear()
        fallenPlanets.clear()
        sunFallen = false
        spring?.cancel()
        spring = null
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0
        cheaterUntil = 0L
        dialScale = 1f
        invalidate()
    }

    /**
     * Mirrors another dial's fallen-piece chaos onto this one, so swiping
     * between cards doesn't magically tidy the workshop. Positions scale
     * with any view-size difference.
     */
    fun syncFallenFrom(other: ClockView) {
        if (other === this) return
        debris.clear()
        val sx = if (other.width > 0) width.toFloat() / other.width else 1f
        val sy = if (other.height > 0) height.toFloat() / other.height else 1f
        val s = (sx + sy) / 2f
        for (b in other.debris.bodies) {
            debris.bodies.add(
                DialDebris.Body(
                    b.kind, b.hand, b.numeralHour, b.label,
                    b.x * sx, b.y * sy, b.vx * s, b.vy * s,
                    b.angleDeg, b.angVel,
                    b.halfLen * s, b.strokeWidth * s, b.textSize * s
                )
            )
        }
        if (debris.bodies.isNotEmpty()) lastPhysicsAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
        listenForShakes()
    }

    /**
     * A face that cannot be shaken has no use for the accelerometer, and an
     * alarm list can hold a dozen of these little ones at once.
     */
    private fun listenForShakes() {
        if (!shakeDropEnabled || chronoProvider != null) return
        if (sensorManager != null) return
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // Whatever the smoothing thinks it knows is from the last time this
        // view was on screen, and is not to be believed.
        settleSamples = 0
        smoothingSeeded = false
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        // The sky is a thing you open, look at and leave; coming back to a
        // dial that is still showing the planets of a date you wound to
        // three days ago would read as a clock that had stopped.
        closeOrrery()
        removeCallbacks(ticker)
        sensorManager?.unregisterListener(shakeListener)
        sensorManager = null
        spring?.cancel()
        spring = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- touch

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // With the planets on the dial a pinch means the solar
                // system, not the screen. Making the whole face bigger
                // while looking at eight orbits is answering a question
                // nobody asked; pushing the orbits outwards is the one they
                // did — and at the far end it turns the dial into a
                // calendar of the year.
                if (orreryShowing()) {
                    zoomOrrery(detector.scaleFactor)
                    return true
                }
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
            override fun onDown(e: MotionEvent): Boolean = true

            /**
             * A long press on the open sky goes looking for the next
             * alignment. On a clock it does nothing, as before.
             */
            override fun onLongPress(e: MotionEvent) {
                pressAndHoldOnSky(e.x, e.y)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Nothing, while the planets have the dial. It undid the
                // zoom, which is a thing a pinch already does and which
                // nobody meant every time they tapped a planet twice.
                if (orreryShowing()) return true
                if (!pinchZoomEnabled) return false
                dialScale = 1f
                onDialScaleChanged?.invoke(dialScale)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // The token first: a tap on the sun or the moon opens the
                // sky behind it.
                if (skyTokenAt(e.x, e.y)) {
                    toggleOrrery()
                    return true
                }
                // A planet, named. A tap and not a long press: a finger
                // that comes down on Jupiter and lifts again was asking
                // which one that is, and nothing else on the sky answers a
                // tap in that spot.
                if (orreryUp) {
                    OrreryDial.bodyAt(
                        e.x, e.y, width / 2f, height / 2f, dialRadius(),
                        orreryMs(), orreryMoonLongitude(), orreryZoom, fallenPlanets
                    )?.let { body ->
                        showMarkBubble(bodyName(body), e.x, e.y)
                        return true
                    }
                    // And a visitor, which is worth asking about for the
                    // same reason a planet is: four thin ellipses over
                    // eight circles, and no way to tell which is which.
                    if (cometsEnabled) {
                        OrreryDial.cometAt(
                            e.x, e.y, width / 2f, height / 2f, dialRadius(),
                            orreryMs(), orreryZoom
                        )?.let { comet ->
                            showMarkBubble(cometName(comet), e.x, e.y)
                            return true
                        }
                    }
                }
                // A day of the year, out past the rim, with something on
                // it. Says what.
                // The wound date is the thing on this screen that says
                // where in time you are, so pressing it goes somewhere
                // else in time: the next day the sky does something.
                if (orreryUp && dateRowAt(e.x, e.y)) {
                    if (leapToNextSkyEvent()) return true
                }
                if (orreryUp && tapCandidate) {
                    val day = OrreryDial.dayAt(
                        e.x, e.y, width / 2f, height / 2f, dialRadius(),
                        orreryMs(), orreryZoom
                    )
                    val what = day?.let { orreryBusyDays[it] }
                    if (what != null) {
                        showMarkBubble(what, e.x, e.y)
                        return true
                    }
                }
                // The Sun is the way out, and it takes two presses when
                // the sky has been wound: the first brings the date back to
                // today — travelling, so the years can be seen going past —
                // and the second shuts the sky. Nothing is put away while
                // it is still somewhere else.
                //
                // And not at all while planets are lying in the case. The
                // dial is not tidy, and putting a lid on an untidy dial is
                // how you come back to one and wonder what happened.
                if (orreryUp && sunAt(e.x, e.y)) {
                    if (fallenPlanets.isNotEmpty()) return true
                    if (!glideOrreryHome()) toggleOrrery()
                    return true
                }
                if (tapCandidate) handleNumeralTap(e.x, e.y)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val fastVertical = kotlin.math.abs(velocityY) > 500f &&
                    kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX)
                if (fastVertical) return handleVerticalFling(start, e2, velocityY)
                val fastHorizontal = kotlin.math.abs(velocityX) > 500f &&
                    kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY)
                if (!fastHorizontal) return false
                if (tapCandidate) return onHorizontalSwipe?.invoke(velocityX > 0) ?: false
                // With every hand pointing up (chrono at zero) the grab zones
                // cover the middle of the dial, so a page-style swipe usually
                // lands on a hand and becomes winding. Telling them apart by
                // wound angle fails (a straight pass near the pivot sweeps a
                // huge angle), so discriminate by the shape of the stroke:
                // swipes are long, straight and horizontal; winding is
                // circular. Any straight horizontal stroke in chrono mode is
                // a swipe — abort the drag and switch.
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val straightSwipe = kotlin.math.abs(dx) > width * 0.25f &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                if (chronoProvider != null && straightSwipe) {
                    if (draggedHand != null) abortDragForSwipe()
                    return onHorizontalSwipe?.invoke(velocityX > 0) ?: false
                }
                return false
            }
        }
    )

    /**
     * A flick up or down moves between the clock row and the chronograph
     * row, the way a flick left or right moves along one.
     *
     * Told apart from winding the same way: a wind is circular, a swipe is
     * long and straight. The difference from the horizontal case is that
     * this one applies on the clock as well as on a chronograph — there is
     * no pager underneath to have claimed it first.
     */
    private fun handleVerticalFling(start: MotionEvent, end: MotionEvent, velocityY: Float): Boolean {
        // Asked for first, and before anything is given up. With nobody
        // listening this used to abort a hand you were dragging and then
        // return false anyway: the gesture was gone but its cost was not.
        val listener = onVerticalSwipe ?: return false
        val up = velocityY < 0
        if (tapCandidate) return listener.invoke(up)
        val dx = end.x - start.x
        val dy = end.y - start.y
        val straight = kotlin.math.abs(dy) > height * 0.20f &&
            kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f
        if (!straight) return false
        if (draggedHand != null) abortDragForSwipe()
        return listener.invoke(up)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A passive dial (e.g. the mini world clock) lets touches through —
        // but never while case pushers are present, they must stay pressable.
        // Through the base class, mind: a passive face may still have been
        // given a click or a long-press to answer, and swallowing the event
        // here is what kept the alarm editor's little dials from opening.
        if (!touchHandsEnabled && !pinchZoomEnabled && debris.bodies.isEmpty() && !chronoButtons) {
            return super.onTouchEvent(event)
        }
        // The unfolded lap list owns every touch while it is up.
        if (lapsExpanded) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lapListLastY = event.y
                    lapListDownY = event.y
                    lapListDragging = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lapListLastY
                    lapListLastY = event.y
                    if (kotlin.math.abs(event.y - lapListDownY) > 12f) lapListDragging = true
                    lapListScroll = (lapListScroll - dy).coerceIn(0f, maxLapScroll())
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (!lapListDragging) {
                        lapsExpanded = false
                        invalidate()
                    }
                }
            }
            return true
        }
        gestureDetector.onTouchEvent(event)
        if (pinchZoomEnabled || orreryShowing()) {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                releaseDraggedHand()
                releaseCarriedBody()
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pusher = pusherAt(event.x, event.y)
                if (pusher != 0) {
                    pressedPusher = pusher
                    tapCandidate = false
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                // A mark under the finger takes the touch before the hands
                // do. Its dot is small and deliberate to hit, while a hand
                // can be grabbed anywhere along its length — so the one
                // gesture that has an alternative gives way.
                markUnderFinger = markLabelAt(event.x, event.y)
                // While the planets have the dial they own the touches: the
                // hands are not there to be wound, and a finger that came
                // down on Jupiter meant Jupiter. The token is left out of
                // it, since that is the way back and must stay a tap.
                // Anything lying in the case comes first. A piece on the
                // floor is the thing actually under the finger and whatever
                // is still mounted is behind it — which is how the hands
                // have always worked, and was not how the planets did: a
                // planet knocked out of its orbit could not be picked up,
                // because the orbit it had left took the touch instead.
                val grabbedDebris = markUnderFinger == null &&
                    grabFallenBodyNear(event.x, event.y)
                val grabbedPlanet = markUnderFinger == null && !grabbedDebris &&
                    !skyTokenAt(event.x, event.y) &&
                    grabBodyNear(event.x, event.y)
                if (markUnderFinger == null && !grabbedPlanet && !grabbedDebris &&
                    touchHandsEnabled && !orreryShowing()
                ) {
                    grabHandNear(event.x, event.y)
                }
                tapCandidate = !grabbedDebris && !grabbedPlanet && draggedHand == null
                tapDownX = event.x
                tapDownY = event.y
                // Own the gesture while manipulating the mechanism, so a
                // hosting pager doesn't steal it as a horizontal page swipe.
                if (grabbedDebris || grabbedPlanet || draggedHand != null) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (grabbedBody != null) {
                    dragBodyTo(event.x, event.y)
                } else {
                    debris.carried?.let { moveCarriedBody(it, event.x, event.y) }
                        ?: dragTo(event.x, event.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger on a pusher while the first winds a hand is
                // a *feature*: fake laps are half the fun. Only a genuine
                // pinch drops the wind.
                val idx = event.actionIndex
                val pusher = pusherAt(event.getX(idx), event.getY(idx))
                if (pusher != 0) {
                    when (pusher) {
                        1 -> onChronoStartStop?.invoke()
                        2 -> onChronoReset?.invoke()
                        3 -> handleCrownTap()
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    soundListener?.onTickCrossed()
                    return true
                }
                if (pinchZoomEnabled) parent?.requestDisallowInterceptTouchEvent(true)
                pressedPusher = 0
                releaseDraggedHand()
                releaseCarriedBody()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (pressedPusher != 0) {
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        pusherAt(event.x, event.y) == pressedPusher
                    ) {
                        when (pressedPusher) {
                            1 -> onChronoStartStop?.invoke()
                            2 -> onChronoReset?.invoke()
                            3 -> handleCrownTap()
                        }
                        soundListener?.onTickCrossed()
                    }
                    pressedPusher = 0
                    invalidate()
                }
                // A tap on a mark names it. Only a tap: a finger that
                // wandered was on its way somewhere else.
                markUnderFinger?.let { label ->
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        hypot(event.x - tapDownX, event.y - tapDownY) < 24f
                    ) {
                        showMarkBubble(label, event.x, event.y)
                    }
                    markUnderFinger = null
                }
                // A quiet tap on the lap ladder unfolds the full list. A
                // tap, not the tail end of a swipe: the finger must not
                // have wandered.
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    tapCandidate && draggedHand == null && debris.carried == null &&
                    chronoProvider != null && !chronoSettable && laps.isNotEmpty() &&
                    event.y > ladderTapTop &&
                    hypot(event.x - tapDownX, event.y - tapDownY) < 24f
                ) {
                    lapsExpanded = true
                    lapListScroll = 0f
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                }
                releaseBody()
                releaseDraggedHand()
                releaseCarriedBody()
            }
        }
        return true
    }

    // ------------------------------------------------ naming a mark

    /** The label of the mark the current touch went down on, if any. */
    private var markUnderFinger: String? = null

    /**
     * Fired when the calendar day the dial is *showing* changes — which
     * happens by carrying the hands round past midnight, not only by
     * waiting. The host answers by rebuilding the marks for that day, so a
     * turn of the hour hand really does show you what tomorrow holds.
     */
    var onShownDayChanged: ((Long) -> Unit)? = null
    private var shownDay = Long.MIN_VALUE

    /** The wall-clock instant the dial is showing, winding included. */
    fun shownWallMs(): Long = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()

    private fun checkShownDay() {
        if (onShownDayChanged == null) return
        val ms = shownWallMs()
        cal.timeInMillis = ms
        val day = cal.get(java.util.Calendar.YEAR) * 1000L + cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (day == shownDay) return
        val first = shownDay == Long.MIN_VALUE
        shownDay = day
        if (!first) onShownDayChanged?.invoke(ms)
    }

    /**
     * Pops a mark's bubble without anyone having tapped it, at the mark's
     * own place on the rim.
     */
    fun announceMark(label: String, angleDeg: Float) {
        val at = markCenter(width / 2f, height / 2f, angleDeg)
        bubbleText = label
        bubbleX = at.x
        bubbleY = at.y
        bubbleSince = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    /** What the bubble is currently saying, if anything. */
    internal fun bubbleLabel(): String? = bubbleText

    /** The label currently being named because the hour hand rests on it. */
    private var hoveredLabel: String? = null

    /**
     * The hour hand as a reading head: carry it round and each event it
     * passes over says what it is.
     *
     * Only while the hands are being played with — a clock quietly telling
     * the time has no business shouting the names of the day's
     * appointments. But once you take hold of the hour hand, running it over
     * the marks is the natural way to ask "and what is *that* one?", and
     * without this the dots stay anonymous unless you go and tap each.
     *
     * It holds the bubble open for as long as the hand stays on the mark,
     * and lets it fade on its own once the hand moves off.
     */
    internal fun followHourHand(hourAngle: Float) {
        if (chronoProvider != null || visualOffsetSeconds == 0.0) {
            hoveredLabel = null
            return
        }
        val found = markAtAngle(hourAngle)
        if (found == null) {
            hoveredLabel = null
            return
        }
        if (found.first != hoveredLabel) {
            hoveredLabel = found.first
            announceMark(found.first, found.second)
        } else if (bubbleText == found.first) {
            // Still resting on it, so the bubble does not start fading yet.
            bubbleSince = android.os.SystemClock.uptimeMillis()
        }
    }

    /** The mark an angle falls on: its name, and where to hang the bubble. */
    internal fun markAtAngle(angleDeg: Float): Pair<String, Float>? {
        fun apart(a: Float, b: Float): Float {
            val d = kotlin.math.abs((a - b) % 360f)
            return if (d > 180f) 360f - d else d
        }
        for (mark in alarmMarkers) {
            if (mark.label.isEmpty()) continue
            if (apart(angleDeg, mark.angle) < 4f) return mark.reading() to mark.angle
        }
        for (arc in eventArcs) {
            if (arc.label.isEmpty()) continue
            val left = arcRemaining(arc)
            if (left <= 0f) continue
            // Only the part still on the face: the head has been eaten, and
            // naming an event over ground it no longer covers would be
            // pointing at nothing.
            val from = arc.start + arc.sweep * (1f - left)
            val into = ((angleDeg - from) % 360f + 360f) % 360f
            if (into <= kotlin.math.abs(arc.sweep) * left) return arc.reading() to angleDeg
        }
        return null
    }

    private var bubbleText: String? = null
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var bubbleSince = 0L

    /**
     * Which mark is under (x, y), by name.
     *
     * The dots and wedges are the only thing on the dial that knows what it
     * is for, and until now they could not say: three dots on a face and no
     * way to ask which was the dentist. A dot is matched by distance, a
     * wedge by angle and radius, which is exactly how each is drawn.
     */
    private fun markLabelAt(x: Float, y: Float): String? {
        if (chronoProvider != null) return null
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        // Dots first: they sit outside the rim, where nothing else lives,
        // and they are the smaller target of the two.
        var best: String? = null
        var bestDist = r * 0.075f
        for (mark in alarmMarkers) {
            if (mark.label.isEmpty()) continue
            val at = markCenter(cx, cy, mark.angle)
            val d = hypot(x - at.x, y - at.y)
            if (d < bestDist) {
                bestDist = d
                best = mark.reading()
            }
        }
        if (best != null) return best

        val dist = hypot(x - cx, y - cy)
        val angle = touchAngleDeg(x, y)
        for (arc in eventArcs) {
            if (arc.label.isEmpty()) continue
            if (arcRemaining(arc) <= 0f) continue
            val b = boundaryRadius(angle)
            if (dist < b * 0.86f || dist > b * 0.99f) continue
            val into = ((angle - arc.start) % 360f + 360f) % 360f
            if (into <= kotlin.math.abs(arc.sweep)) return arc.reading()
        }
        return null
    }

    private fun showMarkBubble(label: String, x: Float, y: Float) {
        bubbleText = label
        bubbleX = x
        bubbleY = y
        bubbleSince = android.os.SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    /**
     * The name of the mark just tapped, in a little bubble above it.
     *
     * Hand-clocked and faded out by this view rather than shown as a Toast:
     * a Toast lands at the bottom of the screen, far from the dot that
     * caused it, and says nothing about which of three dots was asked.
     */
    private fun drawMarkBubble(canvas: Canvas, r: Float) {
        val text = bubbleText ?: return
        val age = android.os.SystemClock.uptimeMillis() - bubbleSince
        val life = 2200L
        val fade = 400L
        if (age > life) {
            bubbleText = null
            return
        }
        val alpha = if (age > life - fade) {
            ((life - age).toFloat() / fade).coerceIn(0f, 1f)
        } else {
            1f
        }
        bubbleTextPaint.textSize = r * 0.085f
        // A name spoken in the voice of the century it is asked in.
        bubbleTextPaint.typeface = if (orreryShowing()) eraFace() else null
        // The name on the first line and the note wrapped under it, because
        // a note is where the address and the room number go and neither
        // fits on one line beside a name.
        val lines = wrapBubble(text, r * 0.62f)
        val lineH = bubbleTextPaint.descent() - bubbleTextPaint.ascent()
        val padX = r * 0.05f
        val padY = r * 0.035f
        val w = (lines.maxOf { bubbleTextPaint.measureText(it) }) + padX * 2
        val h = lineH * lines.size + padY * 2
        // Kept inside the view, and above the finger so it is not covered
        // by the hand that asked for it.
        val left = (bubbleX - w / 2).coerceIn(r * 0.04f, width - w - r * 0.04f)
        val top = (bubbleY - h - r * 0.09f).coerceAtLeast(r * 0.04f)
        val rect = RectF(left, top, left + w, top + h)
        bubblePaint.color = theme.face
        bubblePaint.alpha = (238 * alpha).toInt()
        val radius = minOf(h, lineH * 1.6f) * 0.38f
        canvas.drawRoundRect(rect, radius, radius, bubblePaint)
        bubblePaint.color = theme.rim
        bubblePaint.alpha = (255 * alpha).toInt()
        bubblePaint.style = Paint.Style.STROKE
        bubblePaint.strokeWidth = r * 0.006f
        canvas.drawRoundRect(rect, radius, radius, bubblePaint)
        bubblePaint.style = Paint.Style.FILL
        bubbleTextPaint.color = theme.numeral
        bubbleTextPaint.alpha = (255 * alpha).toInt()
        var baseline = top + padY - bubbleTextPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, rect.centerX(), baseline, bubbleTextPaint)
            baseline += lineH
        }
        // Its own clock, so it fades whether or not anything else on the
        // dial happens to be animating.
        invalidate()
    }

    /**
     * Breaks the bubble's text into lines no wider than [maxWidth].
     *
     * Explicit newlines are kept — the name and the note are separate lines
     * by construction — and long notes are wrapped on word boundaries, then
     * cut off, because a bubble that grows to cover the dial is no longer a
     * bubble.
     */
    private fun wrapBubble(text: String, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (bubbleTextPaint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    out.add(line)
                    line = word
                }
                if (out.size >= MAX_BUBBLE_LINES) break
            }
            if (out.size >= MAX_BUBBLE_LINES) break
            out.add(line)
        }
        if (out.isEmpty()) out.add(text)
        return out.take(MAX_BUBBLE_LINES)
    }

    /**
     * The band between the minute hand's tip and the rim. Nobody reaching in
     * there means to grab the minute hand — its tip is the inner edge of the
     * band — so within it the second hand is the only hand on offer.
     *
     * This replaced an older trick that let a finger reach in from outside
     * the glass to catch the second hand. With the band working from the
     * inside, that outside grab was only ever a second way to do the same
     * thing, so it is gone.
     */
    private fun inSecondHandBand(x: Float, y: Float): Boolean {
        val dist = hypot(x - width / 2f, y - height / 2f)
        val b = boundaryRadius(touchAngleDeg(x, y))
        return dist >= b * MINUTE_LEN && dist <= b * 1.02f
    }

    /** True where the second hand outranks the case hardware under it. */
    private fun secondHandRingHit(x: Float, y: Float): Boolean {
        if (!chronoSettable || chronoProvider == null) return false
        if (!showSecondHand || isFallen(Hand.SECOND)) return false
        if (!inSecondHandBand(x, y)) return false
        val handDeg = angleOf(Hand.SECOND, currentAngles())
        return kotlin.math.abs(normalizeDeg(touchAngleDeg(x, y) - handDeg)) < 16f
    }

    /**
     * 1 = start/stop pusher (1:30), 2 = reset pusher (10:30), 3 = crown (12).
     */
    private fun pusherAt(x: Float, y: Float): Int {
        // The second-hand tab outranks the crown: setting seconds beats
        // cuckoo noises.
        if (secondHandRingHit(x, y)) return 0
        if (!chronoButtons) return 0
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val hit = max(48f * resources.displayMetrics.density, r * 0.16f)
        val start = plainPoint(cx, cy, 45f, boundaryRadius(45f) * 1.03f)
        if (hypot(x - start.x, y - start.y) < hit) return 1
        val reset = plainPoint(cx, cy, 315f, boundaryRadius(315f) * 1.03f)
        if (hypot(x - reset.x, y - reset.y) < hit) return 2
        val crown = plainPoint(cx, cy, 0f, boundaryRadius(0f) * 1.04f)
        if (hypot(x - crown.x, y - crown.y) < hit) return 3
        return 0
    }

    /**
     * Crown taps cuckoo — and, being the winding crown, it also remounts any
     * pieces lying on the floor. Five frantic taps overwind the mechanism.
     */
    private fun handleCrownTap() {
        val now = SystemClock.uptimeMillis()
        crownTapTimes.addLast(now)
        while (crownTapTimes.size > 5) crownTapTimes.removeFirst()
        // Five in three seconds shakes the works loose — and only on a
        // face where the crown has nothing else to do.
        //
        // It had nothing else to do when this was written. Then the crown
        // became the place both chronographs keep their second thoughts:
        // press it on a stopped countdown and the length you set comes
        // back. Pressing a control that does something several times in a
        // row is what a person does when it does not seem to have worked,
        // and what they got for it was a cuckoo and the hands on the
        // floor. An egg that eats a working gesture is not an egg.
        val eggAllowed = chronoProvider == null
        if (eggAllowed && crownTapTimes.size >= 5 && now - crownTapTimes.first() < 3000) {
            crownTapTimes.clear()
            soundListener?.onExploded()
            dropHands(0f, -8f)
        } else {
            // Whether the crown found anything to put right. The cuckoo
            // belongs to that and not to the crown: winding it on a tidy
            // dial made a whole bird go off for nothing, several times a
            // minute, which is how a good joke becomes a bad one.
            var tidied = false
            if (debris.bodies.isNotEmpty()) {
                debris.clear()
                fallenPlanets.clear()
                sunFallen = false
                soundListener?.onHandMounted()
                tidied = true
            }
            // Winding the crown resets the mechanism's conscience: the
            // faked laps and the stamp that shamed them both go.
            if (laps.any { it.fake } || cheaterUntil > 0L) {
                laps.removeAll { it.fake }
                cheaterUntil = 0L
                cheaterFade = 0f
                cheaterFlagged = false
                tidied = true
            }
            onCrownTap?.invoke(tidied)
        }
    }

    /** Like [pointAt] but ignoring mirror mode — case hardware is physical. */
    private fun plainPoint(cx: Float, cy: Float, angleDeg: Float, distance: Float): PointF {
        val a = Math.toRadians(angleDeg.toDouble())
        return PointF(cx + sin(a).toFloat() * distance, cy - cos(a).toFloat() * distance)
    }

    private fun touchAngleDeg(x: Float, y: Float): Float {
        val dx = x - width / 2f
        val dy = y - height / 2f
        var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        if (mirrored) deg = -deg
        return deg
    }

    private fun distanceToSegment(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    /**
     * Which hand the finger is asking for, when several lie on top of one
     * another.
     *
     * With all three hands at twelve, distance to the nearest hand cannot
     * choose between them: every one of them is under the finger. But the
     * hands are different *lengths*, and that is what people reach for —
     * out near the rim for the long one, down near the centre for the short
     * one. So the dial is three rings, and where the finger lands says
     * which hand was meant:
     *
     *  - inside the hour hand's tip, the hour hand — it is the only one
     *    that can be caught there and nowhere else;
     *  - between that and the minute hand's tip, the minute hand;
     *  - beyond it, the second hand, out where only it reaches.
     *
     * Only when there is a choice to make. A hand on its own is grabbed
     * anywhere along its length, as before — the rings are for untangling a
     * pile, not a new rule about where hands may be held.
     */
    internal fun handForRing(distFraction: Float): Hand = when {
        distFraction < HOUR_LEN * 0.98f -> Hand.HOUR
        distFraction < MINUTE_LEN * 0.98f -> Hand.MINUTE
        else -> Hand.SECOND
    }

    /**
     * The ring's pick if that hand is actually in the pile, otherwise the
     * nearest one in it — a ring nobody has a hand in cannot decide
     * anything, and the finger still has to catch something.
     */
    internal fun untangle(distFraction: Float, reachable: List<Pair<Hand, Float>>): Hand {
        val wanted = handForRing(distFraction)
        reachable.firstOrNull { it.first == wanted }?.let { return it.first }
        // Falling outwards: the ring above an absent hand belongs to the
        // longest hand still present, which is what "reach further out for
        // the longer one" means when the longest is switched off.
        val order = listOf(Hand.SECOND, Hand.MINUTE, Hand.HOUR)
        val fromWanted = order.indexOf(wanted)
        for (i in order.indices) {
            val hand = order[(fromWanted + i) % order.size]
            reachable.firstOrNull { it.first == hand }?.let { return it.first }
        }
        return reachable.minByOrNull { it.second }!!.first
    }

    /** How generously each hand is caught: the thin one needs more room. */
    private fun grabLeniency(hand: Hand): Float = when (hand) {
        Hand.SECOND -> 1.4f
        else -> 1.2f
    }

    internal fun grabHandNear(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (hypot(x - cx, y - cy) < r * 0.12f) return
        val a = currentAngles()

        val threshold = max(r * 0.10f, 44f * resources.displayMetrics.density)
        var chosen: Hand? = null
        // Two special cases that outrank the rings, both about the second
        // hand being thin: its own grab ring while setting, and the band
        // past the minute hand's tip where nothing else lives.
        if (secondHandRingHit(x, y)) chosen = Hand.SECOND
        if (chosen == null && showSecondHand && !isFallen(Hand.SECOND) &&
            inSecondHandBand(x, y) &&
            // In the band *and* roughly on the hand. The band is radial
            // only — it says "past the minute hand's tip" and nothing about
            // direction — so on its own it handed over the second hand for
            // a touch on the far side of the dial from it.
            distanceToHand(Hand.SECOND, a, x, y, cx, cy, r) < threshold * 2f
        ) {
            chosen = Hand.SECOND
        }

        if (chosen == null) {
            val reachable = mutableListOf<Pair<Hand, Float>>()
            for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
                if (isFallen(hand)) continue
                if (!handIsOn(hand)) continue
                val d = distanceToHand(hand, a, x, y, cx, cy, r)
                if (d < threshold * grabLeniency(hand)) reachable.add(hand to d)
            }
            chosen = when (reachable.size) {
                0 -> null
                1 -> reachable[0].first
                else -> {
                    val boundary = boundaryRadius(touchAngleDeg(x, y))
                    untangle(hypot(x - cx, y - cy) / boundary, reachable)
                }
            }
        }

        // Taking hold of any hand but the second one sets it loose; taking
        // hold of the second one puts it back on the dial, so that winding
        // it is followed and springs home like any other hand.
        chosen?.let { secondLoose = it != Hand.SECOND }

        chosen?.let {
            spring?.cancel()
            spring = null
            draggedHand = it
            activeSoundHand = it
            // Freeze the mechanism while the user holds it.
            val provider = chronoProvider
            if (provider != null) chronoFrozenMs = provider() else frozenDisplayMs = displayNowMs()
            dragStartOffset = visualOffsetSeconds
            dragAccumDeg = 0.0
            exploded = false
            cheaterFlagged = false
            lockedMagnetMs = null
            lastTouchDeg = touchAngleDeg(x, y)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun distanceToHand(
        hand: Hand, a: Angles,
        x: Float, y: Float,
        cx: Float, cy: Float, r: Float
    ): Float {
        val angle = angleOf(hand, a)
        val tip = pointAt(cx, cy, angle, handReach(hand))
        return distanceToSegment(x, y, cx, cy, tip.x, tip.y)
    }

    /**
     * Whether a finger at ([x], [y]) is out in the ring between the numerals
     * and the rim, where the hand goes for fine adjustment.
     *
     * Magnets engage there and nowhere else: whipping a hand round from
     * near the centre spins free, with no detents and no haptic
     * machine-gun. It is a property of where you are holding the dial, not
     * of which hand you are holding, which is why it lives here rather than
     * inside one hand's branch.
     */
    private fun inPrecisionBand(x: Float, y: Float): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val fingerDist = hypot(x - cx, y - cy)
        val bAtFinger = boundaryRadius(touchAngleDeg(x, y))
        return fingerDist >= bAtFinger * numeralRadiusFactor() * 0.95f &&
            fingerDist <= bAtFinger * 1.10f
    }

    private fun dragTo(x: Float, y: Float) {
        val hand = draggedHand ?: return
        val cx = width / 2f
        val cy = height / 2f
        if (hypot(x - cx, y - cy) < dialRadius() * 0.05f) return
        val touchDeg = touchAngleDeg(x, y)
        var delta = (touchDeg - lastTouchDeg) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        dragAccumDeg += delta
        lastTouchDeg = touchDeg
        // Winding a running chronograph forward more than one turn is
        // cheating — a stopped one has nothing to cheat, and setting the
        // countdown is legitimate.
        if (chronoProvider != null && !chronoSettable && chronoRunning &&
            !cheaterFlagged && dragAccumDeg >= 360.0
        ) {
            cheaterFlagged = true
            // Faking laps is encouraged; the stamp just sticks around until
            // ten honest laps have scrubbed it away.
            cheaterUntil = SystemClock.uptimeMillis() + 600_000L
            cheaterFade = 0f
            soundListener?.onCheater()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        // Over-winding blows the mechanism apart, chronograph included (but
        // not while calmly setting the countdown).
        if (!exploded && !(chronoProvider != null && chronoSettable) &&
            kotlin.math.abs(dragAccumDeg) >= explosionDegrees(hand)
        ) {
            exploded = true
            soundListener?.onExploded()
            dropHands(0f, 0f)
            return
        }
        var target = dragStartOffset + dragAccumDeg / 360.0 * secondsPerRevolution(hand)
        // The stop at a full day. A countdown is capped at twenty-four hours
        // when it is committed, but the hands went on turning past it and
        // came back on release, so the last hour of winding did nothing and
        // said nothing. Now they hit something: the hand stands at the top
        // and will not go on, the way the chronograph will not be wound
        // below nothing.
        //
        // The turn beyond the stop is given back rather than banked. Left
        // banked, a hand pushed three hours into the wall would need three
        // hours of unwinding before it moved again, which is a jam and not
        // a stop.
        val stop = windingStopSeconds()
        if (stop != null && target > stop) {
            dragAccumDeg = (stop - dragStartOffset) / secondsPerRevolution(hand) * 360.0
            target = stop
        }
        if (chronoSettable && chronoProvider != null && hand == Hand.SECOND) {
            // The second hand works like the minute hand: it ticks from
            // whole second to whole second — no landing in between — and
            // magnets pull only at the round marks: every five seconds up to
            // the half turn, one more at 45, and that is all.
            val baseMs = chronoFrozenMs ?: 0L
            val wound = baseMs + (target * 1000.0).toLong()
            val whole = Math.floorDiv(wound + 500L, 1000L) * 1000L
            val rem = Math.floorMod(whole, 60_000L)
            var snapped = whole
            var onDetent = false
            // In the precision band, like its sibling hands. This one was
            // detenting wherever the finger happened to be: take hold of it
            // by the body, nowhere near the marks, and it still snapped and
            // still buzzed, once every five seconds of dial all the way
            // round. The hour and minute hands have always spun free from
            // the middle; the second hand never got the same test because
            // the screen this was written for — winding a time to set — is
            // the one screen with no second hand on it.
            if (inPrecisionBand(x, y)) {
                for (c in SECOND_DETENTS) {
                    if (kotlin.math.abs(rem - c) <= 1_100L) {
                        snapped = whole - rem + c
                        onDetent = true
                        break
                    }
                }
            }
            if (onDetent) {
                if (lockedMagnetMs != snapped) {
                    lockedMagnetMs = snapped
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } else {
                lockedMagnetMs = null
            }
            setOffset((snapped - baseMs) / 1000.0)
            return
        }
        if (chronoSettable && chronoProvider != null) {
            val baseMs = chronoFrozenMs ?: 0L
            val durationMs = baseMs + (target * 1000.0).toLong()
            val magnet = if (inPrecisionBand(x, y)) magnetFor(durationMs, hand) else null
            if (magnet != null) {
                if (lockedMagnetMs != magnet) {
                    lockedMagnetMs = magnet
                    // LONG_PRESS: CLOCK_TICK is inaudible on many devices.
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                setOffset((magnet - baseMs) / 1000.0)
            } else {
                lockedMagnetMs = null
                setOffset(target)
            }
        } else {
            setOffset(target)
        }
    }

    /**
     * How far a hand may be wound before the mechanism gives up, measured in
     * degrees of that hand.
     *
     * Ten turns for every hand was the old rule, and it was ten turns of the
     * wrong thing: ten turns of the hour hand is five days, which nobody
     * reaches, while ten turns of the *second* hand is ten minutes, which is
     * an easy accident. The budget is time travelled, not turns:
     *
     *  - the hour hand gets two days, so you can finish today and walk the
     *    calendar forward a couple of days to see what is coming — and then
     *    it goes;
     *  - the minute hand gets twelve hours, one lap of the face;
     *  - the second hand keeps its ten turns, because ten minutes of
     *    winding a second hand is nobody's accident and the explosion is
     *    half the reason to do it.
     */
    internal fun explosionDegrees(hand: Hand): Double {
        val budgetSeconds = when (hand) {
            Hand.HOUR -> 2 * 86_400.0
            Hand.MINUTE -> 12 * 3_600.0
            Hand.SECOND -> 10 * 60.0
        }
        return budgetSeconds / secondsPerRevolution(hand) * 360.0
    }

    private fun secondsPerRevolution(hand: Hand): Double = when (hand) {
        Hand.SECOND -> 60.0
        Hand.MINUTE -> 3600.0
        Hand.HOUR -> hoursOnDial * 3600.0
    }

    /**
     * How far a hand may be wound before it hits something, or null if it
     * may be wound as far as anyone likes.
     *
     * Only a countdown has a far end. A day is where it stops, because that
     * is where the value stops being taken: past twenty-four hours the hands
     * have been round the whole face twice and there is nothing on the glass
     * that could tell you where you are. Something wanted the day after
     * tomorrow is an alarm, and this app has alarms.
     *
     * A time of day has no such end — it comes round again — and neither
     * does a stopwatch being played with, where winding past the end is the
     * point and the mechanism blows apart instead.
     *
     * Measured in the same units as the winding offset: seconds from
     * wherever the hand was taken hold of.
     */
    internal fun windingStopSeconds(): Double? {
        if (!chronoSettable || chronoProvider == null || chronoWrapsDay) return null
        val baseMs = chronoFrozenMs ?: 0L
        // Exactly a day: not a day less a second, so the readout can reach
        // 24:00:00, and not a day plus anything, so what is committed is
        // what was shown.
        return (A_DAY_MS - baseMs) / 1000.0
    }

    /** Drops an in-progress wind without spring-back (the fling was a swipe). */
    private fun abortDragForSwipe() {
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0
        invalidate()
    }

    private fun releaseDraggedHand() {
        val hand = draggedHand ?: return
        draggedHand = null
        // Setting the countdown: commit the wound value (magnetized to round
        // durations) with no spring-back.
        if (chronoSettable && chronoProvider != null) {
            val displayMs = chronoDisplayMs() ?: 0L
            // A time of day is never below zero — chronoDisplayMs has
            // already brought it round the clock — so the spring-back that
            // catches a negative countdown has nothing to do here.
            if (displayMs < 0L && !chronoWrapsDay) {
                // Below zero: commit zero and let the spring pull the hands
                // back up to it.
                chronoFrozenMs = null
                lockedMagnetMs = null
                onChronoAdjusted?.invoke(0L)
                visualOffsetSeconds = displayMs / 1000.0
                startSpringBack()
                return
            }
            // Which hand it was, passed in rather than read from
            // draggedHand — which this method has just cleared, so the
            // commit magnet fell through to the countdown profile: a
            // 60-second grid with ten seconds of capture, and zero is a
            // multiple of sixty. That is what swallowed short settings.
            var adjusted = snapCountdown(displayMs, hand)
            // Set with the minute or hour hand, seconds polarize to zero:
            // nobody means 8:30 and seventeen seconds.
            if (hand != Hand.SECOND) {
                adjusted = (adjusted + 30_000L) / 60_000L * 60_000L
            } else {
                // The offset travels as a double; land it back on the whole
                // second the detents already chose.
                adjusted = (adjusted + 500L) / 1000L * 1000L
            }
            if (chronoWrapsDay) {
                val day = 86_400_000L
                adjusted = ((adjusted % day) + day) % day
            }
            chronoFrozenMs = null
            visualOffsetSeconds = 0.0
            activeSoundHand = null
            lockedMagnetMs = null
            onChronoAdjusted?.invoke(adjusted)
            invalidate()
            return
        }
        // Unfreeze: fold the time that passed while holding into the offset,
        // so the display is continuous and the spring returns to *now*.
        val provider = chronoProvider
        if (provider != null) {
            chronoFrozenMs?.let { frozen ->
                chronoFrozenMs = null
                val displayMs = frozen + (visualOffsetSeconds * 1000.0).toLong()
                visualOffsetSeconds = (displayMs - provider()) / 1000.0
            }
        } else {
            frozenDisplayMs?.let { frozen ->
                frozenDisplayMs = null
                val displayMs = frozen + (visualOffsetSeconds * 1000.0).toLong()
                visualOffsetSeconds = (displayMs - TimeKeeper.nowMs()) / 1000.0
            }
        }
        if (visualOffsetSeconds == 0.0) {
            activeSoundHand = null
            return
        }
        startSpringBack()
    }

    private fun startSpringBack() {
        val holder = FloatValueHolder(visualOffsetSeconds.toFloat())
        spring = SpringAnimation(holder).apply {
            setSpring(
                SpringForce(0f)
                    .setStiffness(38f)
                    .setDampingRatio(0.30f)
            )
            minimumVisibleChange = 0.02f
            addUpdateListener { _, value, _ -> setOffset(value.toDouble()) }
            addEndListener { _, _, _, _ ->
                visualOffsetSeconds = 0.0
                activeSoundHand = null
                spring = null
                invalidate()
            }
            start()
        }
    }

    /** Applies a new offset and fires winding sounds for boundaries crossed. */
    private fun setOffset(newOffset: Double) {
        val chronoBaseMs = chronoProvider?.let { chronoFrozenMs ?: it.invoke() }
        val base = (chronoBaseMs ?: (frozenDisplayMs ?: TimeKeeper.nowMs())) / 1000.0
        val before = base + visualOffsetSeconds
        val after = base + newOffset
        visualOffsetSeconds = newOffset
        emitCrossings(before, after)
        invalidate()
    }

    private fun emitCrossings(before: Double, after: Double) {
        val hand = activeSoundHand ?: return
        val listener = soundListener ?: return
        val now = SystemClock.uptimeMillis()
        when (hand) {
            Hand.SECOND -> {
                if (floor(before) != floor(after) && now - lastTickSoundAt > 45) {
                    lastTickSoundAt = now
                    listener.onTickCrossed()
                }
            }
            Hand.MINUTE, Hand.HOUR -> {
                if (floor(before / 3600.0) != floor(after / 3600.0) &&
                    now - lastBellSoundAt > 150
                ) {
                    lastBellSoundAt = now
                    listener.onHourCrossed()
                }
                if (hand == Hand.HOUR &&
                    floor(before / 86400.0) != floor(after / 86400.0) &&
                    now - lastDaySoundAt > 450
                ) {
                    lastDaySoundAt = now
                    listener.onDayCrossed()
                }
            }
        }
    }

    // ----------------------------------------------------- numeral selection

    private fun numeralRadiusFactor(): Float = if (hoursOnDial == 12) 0.76f else 0.68f

    private fun numeralTextSize(r: Float): Float =
        (if (hoursOnDial > 12) r * 0.11f else r * 0.16f) * faceScale()

    private fun visibleNumeralHours(): List<Int> {
        if (numeralStyle == NumeralStyle.NONE) return emptyList()
        return ChapterRing.numeralHours(hoursOnDial, dialMarks)
    }

    private fun numeralLabel(hour: Int): String =
        if (numeralStyle == NumeralStyle.ROMAN) Roman.of(hour) else hour.toString()

    /**
     * Where an hour's numeral sits.
     *
     * On a ring, not on the outline. Following the edge is fine on a shape
     * with eight sides and looks like an accident on one with three: the
     * numerals string themselves along each slope, four of them nearly
     * touching at a corner and a gap in the middle of every side, and the
     * hour hand points at whichever happens to be nearest rather than at
     * the hour.
     *
     * The ring is inside the flat of each side — the inscribed circle,
     * which is the one circle every face this app draws has room for.
     */
    private fun numeralPosition(hour: Int, cx: Float, cy: Float, r: Float): PointF {
        val angle = hour.toFloat() / hoursOnDial * 360f
        return pointAt(cx, cy, angle, apothemRadius() * numeralRadiusFactor())
    }

    private fun handleNumeralTap(x: Float, y: Float) {
        if (numeralStyle == NumeralStyle.NONE || chronoProvider != null) return
        // Not while the planets have the dial. The numerals are still where
        // they were and still answering to a finger, so a tap aimed at a
        // planet that happened to be standing on the seven marked the seven
        // — and the mark was waiting there, in red, when the sky was shut.
        // Every other thing on this face asks whether the sky is up; this
        // one was the last that did not.
        if (orreryShowing()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val threshold = max(r * 0.10f, 40f * resources.displayMetrics.density)
        for (hour in visibleNumeralHours()) {
            if (isNumeralFallen(hour)) continue
            val pos = numeralPosition(hour, cx, cy, r)
            if (hypot(x - pos.x, y - pos.y) < threshold) {
                if (!selectedHours.remove(hour)) selectedHours.add(hour)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onSelectedHoursChanged?.invoke(selectedHours.toSet())
                registerToggleAndMaybeDrop(hour, pos)
                invalidate()
                return
            }
        }
    }

    /** Toggling a numeral frantically shakes it loose from the dial. */
    private fun registerToggleAndMaybeDrop(hour: Int, pos: PointF) {
        val now = SystemClock.uptimeMillis()
        val times = numeralToggleTimes.getOrPut(hour) { ArrayDeque() }
        times.addLast(now)
        while (times.size > 6) times.removeFirst()
        if (times.size >= 6 && now - times.first() < 2500) {
            numeralToggleTimes.remove(hour)
            selectedHours.remove(hour)
            dropNumeral(hour, pos, 0f, -150f)
            soundListener?.onExploded()
        }
    }

    // -------------------------------------------------- fallen-body physics

    /**
     * For the tests: where a named hand's tip is, right now.
     *
     * By name, because the alternative — picking a hand out of
     * [mountedHands] by how long it is — stops working the moment the
     * lengths are wrong, which is exactly the condition being tested for.
     */
    internal fun handTipForTest(hand: Hand): PointF {
        val cx = width / 2f
        val cy = height / 2f
        return pointAt(cx, cy, angleOf(hand, currentAngles()), handReach(hand))
    }

    /** For the tests: how big a numeral is drawn. */
    internal fun numeralSizeForTest(): Float = numeralTextSize(dialRadius())

    /** For the tests: where an hour's numeral sits. */
    internal fun numeralPositionForTest(hour: Int): PointF =
        numeralPosition(hour, width / 2f, height / 2f, dialRadius())

    /** For the tests: whether that hand is lying in the case. */
    internal fun isFallenForTest(hand: Hand): Boolean = isFallen(hand)

    private fun isFallen(hand: Hand): Boolean =
        debris.bodies.any { it.kind == DialDebris.Kind.HAND && it.hand == hand }

    private fun isFastHandFallen(): Boolean = debris.bodies.any { it.kind == DialDebris.Kind.FAST_HAND }

    /**
     * Whether the tenths hand is on the face.
     *
     * It is the second hand's decoration: a chronograph gets one whether or
     * not the clock asked for it, because sub-second motion is what a
     * chronograph is for. But "a chronograph gets one" was written as
     * `chronoProvider != null`, and the dial that sets an alarm time runs
     * on a chrono provider too — so taking the second hand off that face
     * left the tenths hand spinning there on its own, which is the
     * strangest of both worlds. It goes where the second hand goes.
     */
    internal fun showsFastHand(): Boolean =
        fastHand != FastHandMode.NONE || (chronoProvider != null && showSecondHand)

    private fun isNumeralFallen(hour: Int): Boolean =
        debris.bodies.any { it.kind == DialDebris.Kind.NUMERAL && it.numeralHour == hour }

    private fun anyHandFallen(): Boolean =
        debris.bodies.any { it.kind == DialDebris.Kind.HAND || it.kind == DialDebris.Kind.FAST_HAND }

    /** First knock throws the hands; further knocks shake numerals loose. */
    private fun onKnock(impulseX: Float, impulseY: Float) {
        if (!anyHandFallen()) {
            dropHands(impulseX, impulseY)
        } else {
            dropRandomNumerals(impulseX, impulseY)
        }
        onKnocked?.invoke()
    }

    private fun isMoonFallen(): Boolean = debris.bodies.any { it.kind == DialDebris.Kind.MOON }

    private fun isDateFallen(): Boolean = debris.bodies.any { it.kind == DialDebris.Kind.DATE }

    private fun dropHands(impulseX: Float, impulseY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (r <= 0f) return
        // What the dial is showing, read *before* the wind is thrown away
        // three lines down. It used to be read after, so a dial wound
        // forward to tomorrow dropped a date that said today: the piece
        // that fell was not the piece that had been on the face.
        val fallingDate = if (showDate) dateText() else ""
        val fallingSkyTimeOfDay = shownTimeOfDayMs()
        val fallingSkyWall = shownWallMs()

        // Winding state makes no sense once the hands are off the axis.
        spring?.cancel()
        spring = null
        draggedHand = null
        activeSoundHand = null
        frozenDisplayMs = null
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0

        val a = currentAngles()
        val ivx = impulseX * 35f
        val ivy = impulseY * 35f
        val drops = ArrayList<Hand>(3)
        for (hand in Hand.entries) if (handIsOn(hand)) drops.add(hand)
        for (hand in drops) {
            if (isFallen(hand)) continue
            val len = handReach(hand)
            val tail = tailOf(hand) * r * faceScale()
            addRodBody(
                DialDebris.Kind.HAND, hand, angleOf(hand, a),
                len, tail, widthOf(hand) * r * faceScale() * 2f, cx, cy, ivx, ivy
            )
        }
        if (fastHand != FastHandMode.NONE && !isFastHandFallen()) {
            addRodBody(
                DialDebris.Kind.FAST_HAND, null, a.fast,
                FAST_LEN * r * faceScale(), 0.05f * r * faceScale(),
                0.008f * r * faceScale() * 2f, cx, cy, ivx, ivy
            )
        }
        // Nor are the planets. A knock hard enough to take the hands off
        // takes the solar system with them: eight bodies out of their
        // orbits and rolling about the case, which is the same joke as the
        // hands and the same physics.
        if (orreryShowing()) {
            // The Sun first, and it is the whole point of the joke: a case
            // whose eight planets are on the floor and whose star is still
            // burning in the middle is a case that has been half tidied.
            if (!sunFallen) {
                sunFallen = true
                debris.bodies.add(
                    DialDebris.Body(
                        kind = DialDebris.Kind.PLANET, hand = null, numeralHour = 0,
                        label = "", x = cx, y = cy,
                        vx = ivx + Random.nextFloat() * 160f - 80f,
                        vy = ivy - Random.nextFloat() * 200f,
                        angleDeg = 0f, angVel = Random.nextFloat() * 120f - 60f,
                        halfLen = r * 0.055f, strokeWidth = 0f, textSize = 0f,
                        colour = OrreryDial.sunColour(theme)
                    )
                )
            }
            for (body in Orrery.planets + Orrery.Body.MOON) {
                if (body in fallenPlanets) continue
                val p = OrreryDial.positionOf(
                    body, cx, cy, r, orreryMs(), orreryMoonLongitude(), orreryZoom
                )
                if (hypot(p.x - cx, p.y - cy) > r) continue
                fallenPlanets.add(body)
                debris.bodies.add(
                    DialDebris.Body(
                        kind = DialDebris.Kind.PLANET, hand = null, numeralHour = 0,
                        label = "", x = p.x, y = p.y,
                        vx = ivx + Random.nextFloat() * 220f - 110f,
                        vy = ivy - Random.nextFloat() * 180f,
                        angleDeg = 0f, angVel = Random.nextFloat() * 200f - 100f,
                        halfLen = OrreryDial.dotRadius(body, r, orreryZoom),
                        strokeWidth = 0f, textSize = 0f,
                        // The sky's palette, not the clock's: a planet
                        // knocked out of an orbit drawn on black keeps the
                        // colour it had a moment ago, and changing it on
                        // the way down would read as a different planet
                        // landing.
                        colour = OrreryDial.colourOf(body, skyTheme()),
                        planet = body
                    )
                )
            }
        }

        // Complications aren't screwed on any tighter than the hands.
        if (chronoProvider == null) {
            if (showMoonPhase && !isMoonFallen()) {
                debris.bodies.add(
                    DialDebris.Body(
                        kind = DialDebris.Kind.MOON, hand = null, numeralHour = 0, label = "",
                        x = cx, y = cy + apothemRadius() * 0.45f,
                        vx = ivx + Random.nextFloat() * 200f - 100f,
                        vy = ivy - Random.nextFloat() * 200f,
                        angleDeg = 0f, angVel = Random.nextFloat() * 240f - 120f,
                        halfLen = r * 0.07f, strokeWidth = 0f, textSize = 0f,
                        frozenTimeOfDayMs = fallingSkyTimeOfDay,
                        frozenWallMs = fallingSkyWall
                    )
                )
            }
            if (showDate && !isDateFallen()) {
                val label = fallingDate
                datePaint.textSize = r * 0.085f * faceScale()
                debris.bodies.add(
                    DialDebris.Body(
                        kind = DialDebris.Kind.DATE, hand = null, numeralHour = 0, label = label,
                        x = cx, y = cy - apothemRadius() * 0.42f,
                        vx = ivx + Random.nextFloat() * 200f - 100f,
                        vy = ivy - Random.nextFloat() * 150f,
                        angleDeg = 90f, angVel = Random.nextFloat() * 200f - 100f,
                        halfLen = datePaint.measureText(label) / 2f,
                        strokeWidth = 0f, textSize = r * 0.085f
                    )
                )
            }
        }
        if (debris.bodies.isNotEmpty()) {
            lastPhysicsAt = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun addRodBody(
        kind: DialDebris.Kind, hand: Hand?, angle: Float,
        len: Float, tail: Float, stroke: Float,
        cx: Float, cy: Float, ivx: Float, ivy: Float
    ) {
        val visualAngle = if (mirrored) -angle else angle
        val rad = Math.toRadians(visualAngle.toDouble())
        val mid = (len - tail) / 2f
        debris.bodies.add(
            DialDebris.Body(
                kind = kind,
                hand = hand,
                numeralHour = 0,
                label = "",
                x = cx + sin(rad).toFloat() * mid,
                y = cy - cos(rad).toFloat() * mid,
                vx = ivx + Random.nextFloat() * 400f - 200f,
                vy = ivy - Random.nextFloat() * 300f,
                angleDeg = visualAngle,
                angVel = Random.nextFloat() * 420f - 210f,
                halfLen = (len + tail) / 2f,
                strokeWidth = stroke,
                textSize = 0f
            )
        )
    }

    private fun dropRandomNumerals(impulseX: Float, impulseY: Float) {
        val mounted = visibleNumeralHours().filter { !isNumeralFallen(it) }
        if (mounted.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val total = visibleNumeralHours().size
        val count = max(1, ceil(total / 3.0).toInt())
        for (hour in mounted.shuffled().take(count)) {
            dropNumeral(hour, numeralPosition(hour, cx, cy, r), impulseX * 35f, impulseY * 35f)
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun dropNumeral(hour: Int, pos: PointF, ivx: Float, ivy: Float) {
        if (isNumeralFallen(hour)) return
        val r = dialRadius()
        val textSize = numeralTextSize(r)
        numeralPaint.textSize = textSize
        val label = numeralLabel(hour)
        debris.bodies.add(
            DialDebris.Body(
                kind = DialDebris.Kind.NUMERAL,
                hand = null,
                numeralHour = hour,
                label = label,
                x = pos.x,
                y = pos.y,
                vx = ivx + Random.nextFloat() * 250f - 125f,
                vy = ivy - Random.nextFloat() * 200f,
                angleDeg = 0f,
                angVel = Random.nextFloat() * 360f - 180f,
                halfLen = max(numeralPaint.measureText(label) / 2f, textSize * 0.35f),
                strokeWidth = 0f,
                textSize = textSize
            )
        )
        lastPhysicsAt = SystemClock.uptimeMillis()
    }

    /**
     * One frame of the loose pieces.
     *
     * The order is the physics', the middle step is the dial's: gravity and
     * the walls, then pieces off each other, then the hands still on the
     * axis sweeping through them, then everything that has stopped goes to
     * sleep. A piece batted by the second hand must not be put to sleep in
     * the same frame it was hit, which is why the sweep goes in the middle
     * rather than at either end.
     */
    private fun stepPhysics() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastPhysicsAt).coerceIn(0, 48)) / 1000f
        lastPhysicsAt = now
        if (dt <= 0f) return
        debris.advance(dt)
        debris.resolveCollisions()
        resolveMountedHandCollisions(width / 2f, height / 2f, dialRadius())
        debris.settle()
    }

    /**
     * Fallen pieces collide with the hands still mounted on the axis — the
     * ticking second hand bats debris around the dial.
     */
    private fun resolveMountedHandCollisions(cx: Float, cy: Float, r: Float) {
        val a = currentAngles()
        for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
            if (!handIsOn(hand)) continue
            if (isFallen(hand)) continue
            val angle = angleOf(hand, a)
            val tip = pointAt(cx, cy, angle, handReach(hand))
            val tail = pointAt(cx, cy, angle + 180f, r * tailOf(hand) * faceScale())
            debris.collideWithSegment(tail.x, tail.y, tip.x, tip.y, widthOf(hand) * r * faceScale())
        }
        if (showsFastHand() && !isFastHandFallen()) {
            val tip = pointAt(cx, cy, a.fast, r * FAST_LEN)
            debris.collideWithSegment(cx, cy, tip.x, tip.y, 0.008f * r)
        }
    }

    /**
     * A hand, as a line with a thickness, in this view's own coordinates.
     */
    class HandBar(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val halfWidth: Float
    )

    /**
     * The hands still mounted on the axis, as bars.
     *
     * Already what the dial does to its own fallen debris; handed out so
     * that whatever else shares the screen can be hit by them too. The
     * world-clock bubbles bounced off this dial as though it were a
     * boulder, which is what it is — but a boulder with three arms sweeping
     * round inside it, and none of them could touch anything.
     */
    fun mountedHands(): List<HandBar> {
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (r <= 0f) return emptyList()
        val a = currentAngles()
        val bars = ArrayList<HandBar>(8)
        for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
            if (!handIsOn(hand)) continue
            if (isFallen(hand)) continue
            val angle = angleOf(hand, a)
            val tip = pointAt(cx, cy, angle, handReach(hand))
            val tail = pointAt(cx, cy, angle + 180f, r * tailOf(hand) * faceScale())
            bars.add(HandBar(tail.x, tail.y, tip.x, tip.y, widthOf(hand) * r * faceScale()))
        }
        // And the ones on the floor. A hand that has come off is still a
        // bar of metal sliding about the case — the strangest thing it
        // could do is stop being solid the moment it stops being a hand.
        for (body in debris.bodies) {
            if (body.kind != DialDebris.Kind.HAND && body.kind != DialDebris.Kind.FAST_HAND) {
                continue
            }
            val rad = Math.toRadians(body.angleDeg.toDouble())
            val dx = sin(rad).toFloat() * body.halfLen
            val dy = -cos(rad).toFloat() * body.halfLen
            bars.add(
                HandBar(
                    body.x - dx, body.y - dy,
                    body.x + dx, body.y + dy,
                    max(body.strokeWidth * 0.5f, 2f)
                )
            )
        }
        return bars
    }

    /**
     * True while a hand is in somebody's fingers or on its way back.
     *
     * The moment a clock stops being furniture and starts being a club.
     */
    fun handInPlay(): Boolean = draggedHand != null || spring?.isRunning == true

    private fun grabFallenBodyNear(x: Float, y: Float): Boolean {
        // Where a piece is lying is the debris' business; picking it up is
        // the dial's, because a finger closing on something is a haptic and
        // a sound and neither belongs in a physics loop.
        val found = debris.bodyNear(x, y, 44f * resources.displayMetrics.density)
            ?: return false
        debris.carried = found
        lastCarryX = x
        lastCarryY = y
        lastCarryAt = SystemClock.uptimeMillis()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    private fun moveCarriedBody(b: DialDebris.Body, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastCarryAt).coerceAtLeast(1) / 1000f
        b.vx = (x - lastCarryX) / dt * 0.4f
        b.vy = (y - lastCarryY) / dt * 0.4f
        lastCarryX = x
        lastCarryY = y
        lastCarryAt = now
        b.x = x
        b.y = y
        b.angVel *= 0.9f
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        val remount = when (b.kind) {
            // Hands click back onto the central axis.
            DialDebris.Kind.HAND, DialDebris.Kind.FAST_HAND -> hypot(x - cx, y - cy) < r * 0.18f
            // A planet is put back by carrying it to its own orbit — the
            // ring it belongs on, at any angle. Which angle does not matter:
            // where it goes round to is the date's business, not the
            // finger's.
            DialDebris.Kind.PLANET -> {
                // Its own ring, at any angle — or the middle of the dial,
                // which is where the hands go back and where the Sun
                // belongs, and is the thing anybody tries first. Carrying
                // eight planets each to its own invisible circle was a
                // puzzle nobody asked to be set.
                val out = hypot(x - cx, y - cy)
                val home = b.planet?.let { OrreryDial.ringRadius(it, r, orreryZoom) }
                out < r * 0.16f ||
                    (home != null && kotlin.math.abs(out - home) < r * 0.13f)
            }
            // Complications go back to their own homes.
            DialDebris.Kind.MOON ->
                hypot(x - cx, y - (cy + apothemRadius() * 0.45f)) < r * 0.15f
            DialDebris.Kind.DATE ->
                hypot(x - cx, y - (cy - apothemRadius() * 0.42f)) < r * 0.15f
            // Each numeral has to go back to its own spot on the dial
            // (or to the center, if the dial no longer shows that hour).
            DialDebris.Kind.NUMERAL -> {
                val stillVisible = visibleNumeralHours().contains(b.numeralHour)
                if (stillVisible) {
                    val home = numeralPosition(b.numeralHour, cx, cy, r)
                    hypot(x - home.x, y - home.y) < r * 0.12f
                } else {
                    hypot(x - cx, y - cy) < r * 0.18f
                }
            }
        }
        if (remount) {
            // A planet goes back into the sky as well as out of the case —
            // and the one that goes back is the one that was carried, which
            // it now says for itself.
            if (b.kind == DialDebris.Kind.PLANET) {
                b.planet?.let { fallenPlanets.remove(it) } ?: run { sunFallen = false }
            }
            debris.bodies.remove(b)
            debris.carried = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            soundListener?.onHandMounted()
        }
        invalidate()
    }

    private fun releaseCarriedBody() {
        debris.carried = null
    }

    // ----------------------------------------------------------------- time

    private class Angles(
        val hour: Float,
        val minute: Float,
        val second: Float,
        val fast: Float
    )

    /**
     * Rate at which this dial's time flows: 1 normal, 0 seized, -1 running
     * backwards. Knocked-about world clocks lose their minds this way.
     */
    var timeScale = 1f
        set(value) {
            if (field == value) return
            // Pivot on the current displayed time so the hands don't jump.
            scaleAnchorDisplayMs = displayNowMs()
            scaleAnchorRealMs = TimeKeeper.nowMs()
            field = value
            invalidate()
        }
    private var scaleAnchorDisplayMs = 0L
    private var scaleAnchorRealMs = 0L

    /**
     * For the camera: pin the dial to one instant.
     *
     * The shadows depend on the hour of the day and on nothing the test
     * can otherwise reach, so a picture of them taken at whatever o'clock
     * the build machine happened to be at says nothing about the ones
     * taken beside it.
     */
    internal fun freezeAtForTest(atMs: Long) {
        frozenDisplayMs = atMs
        invalidate()
    }

    private fun displayNowMs(): Long {
        frozenDisplayMs?.let { return it }
        val now = TimeKeeper.nowMs()
        if (timeScale == 1f) return now
        return scaleAnchorDisplayMs + ((now - scaleAnchorRealMs) * timeScale).toLong()
    }

    /**
     * The spacing of the detents at [rel], and how near one has to be to
     * capture — or null for a hand with no grid of its own.
     *
     * Its own function because two things need it now: the magnets a finger
     * feels, and the step a nudge takes when there is no finger at all. Two
     * copies of this table would drift apart, and the one that drifted
     * would be the one nobody can see.
     */
    private fun gridFor(rel: Long, hand: Hand?): Pair<Long, Long>? = when (hand) {
        // The second hand has its own detents, applied while dragging; by
        // release time its value is already where it should be, to the
        // second, and no coarser grid gets to round it away.
        Hand.SECOND -> null
        // The grid follows the hand in your fingers: the familiar 5-minute
        // grid on the minute hand, whole hours on the hour hand.
        Hand.HOUR -> 3_600_000L to 600_000L
        else -> when (magnetProfile) {
            MagnetProfile.ALARM -> 300_000L to 40_000L
            MagnetProfile.COUNTDOWN -> when {
                rel < 5 * 60_000L -> 60_000L to 10_000L
                rel < 30 * 60_000L -> 300_000L to 40_000L
                rel < 120 * 60_000L -> 900_000L to 90_000L
                else -> 3_600_000L to 300_000L
            }
        }
    }

    /**
     * Moves a settable dial one detent on, without a finger.
     *
     * Winding is a drag, and a drag is the one gesture a screen reader
     * takes for itself — so a time of day or a length could be read out
     * loud and never changed. The step is the magnet grid rather than a
     * number of its own: a nudge lands exactly where a finger would have
     * been pulled, and the two cannot drift apart because they are the same
     * table.
     */
    internal fun nudgeSetting(forward: Boolean): Boolean {
        if (!chronoSettable || chronoProvider == null) return false
        val now = chronoDisplayMs() ?: return false
        val rel = if (magnetOrigin != 0L) {
            val day = 86_400_000L
            ((now - magnetOrigin) % day + day) % day
        } else {
            now
        }
        val step = (gridFor(rel, Hand.MINUTE) ?: return false).first
        if (step <= 0L) return false
        // The next detent in that direction, strictly — not "one step from
        // here", which from an odd value leaves the oddness in place for
        // ever. A dial nudged off 5:20 lands on 5:00 or 10:00, not on 10:20.
        var next = if (forward) {
            Math.floorDiv(now, step) * step + step
        } else {
            -Math.floorDiv(-now, step) * step - step
        }
        if (chronoWrapsDay) {
            val day = 86_400_000L
            next = ((next % day) + day) % day
        } else {
            next = next.coerceAtLeast(0L)
        }
        chronoFrozenMs = null
        visualOffsetSeconds = 0.0
        lockedMagnetMs = null
        onChronoAdjusted?.invoke(next)
        invalidate()
        // Said out loud, because the only sign anything happened is a hand
        // moving somewhere the listener cannot see.
        announceForAccessibility(describeDial())
        return true
    }

    internal fun magnetFor(ms: Long, hand: Hand?): Long? {
        // Measured from where the winding started, not from midnight.
        //
        // The countdown grid gets coarser as the duration grows — minutes at
        // first, then five, then quarters, then hours — because that is the
        // shape of the durations people actually set. Read off the absolute
        // time of day it was nonsense for a length: an event beginning at
        // 18:00 landed straight in the "over two hours, hours only" band, so
        // there was no detent anywhere near "and it lasts twenty minutes".
        // The progression is the same one; it just starts where the event
        // does.
        val rel = if (magnetOrigin != 0L) {
            val day = 86_400_000L
            ((ms - magnetOrigin) % day + day) % day
        } else {
            ms
        }
        if (rel < 0) return null
        val (grid, window) = gridFor(rel, hand) ?: return null
        if (grid <= 0L) return null
        val rounded = (rel + grid / 2) / grid * grid
        return if (kotlin.math.abs(rounded - rel) <= window) rounded + magnetOrigin else null
    }

    private fun snapCountdown(ms: Long, hand: Hand?): Long = magnetFor(ms, hand) ?: ms

    /** What the dial currently reads while a time is being set, for tests. */
    internal fun settingValueMs(): Long? = chronoDisplayMs()

    /**
     * What the digital readout says while something is being wound.
     *
     * For a time of day, the time — the hands and the number agree, which is
     * the point. For a length, the *length*: the hands are showing the hour
     * the thing ends at, but what is being chosen is how long it runs, and a
     * readout repeating the hour the hands already show answers a question
     * nobody asked. Measured from the hour it starts at, the same origin the
     * magnets count from.
     */
    internal fun readoutForTest(): Long = settingReadoutMs()

    private fun settingReadoutMs(): Long {
        val shown = chronoDisplayMs() ?: 0L
        if (magnetOrigin == 0L) return shown
        val day = 86_400_000L
        return ((shown - magnetOrigin) % day + day) % day
    }

    /**
     * How far through the journey back to zero the hands are, or null if
     * they are not on one.
     *
     * A stopwatch reset used to put the hands on twelve in a single frame,
     * which on a dial where everything else travels reads as a glitch
     * rather than as an action. They run back on the same curve the hands
     * use crossing between the clock and the chronograph.
     */
    private fun chronoGlideMs(): Long? {
        if (chronoGlideAt == NEVER) return null
        val t = ((SystemClock.uptimeMillis() - chronoGlideAt) / CHRONO_GLIDE_MS)
            .coerceIn(0f, 1f)
        if (t >= 1f) {
            chronoGlideAt = NEVER
            return null
        }
        val eased = transitionInterpolator.getInterpolation(t)
        return chronoGlideFrom + ((chronoGlideTo - chronoGlideFrom) * eased).toLong()
    }

    /**
     * Sends the chronograph hands from where they are to [toMs], travelling.
     *
     * The caller has already told the chronograph itself; this is only
     * about what the dial shows on the way there.
     */
    fun glideChronoTo(fromMs: Long, toMs: Long) {
        if (fromMs == toMs) return
        chronoGlideFrom = fromMs
        chronoGlideTo = toMs
        chronoGlideAt = SystemClock.uptimeMillis()
        kickTicker()
    }

    /** Whether the chronograph hands are on their way somewhere. */
    internal fun chronoGliding(): Boolean = chronoGlideAt != NEVER

    /**
     * Chronograph value including any winding offset and hold-freeze. May be
     * negative while playing — the spring brings it back, and the countdown
     * commit clamps at zero.
     */
    private fun chronoDisplayMs(): Long? = chronoProvider?.let { provider ->
        val raw = (chronoGlideMs() ?: chronoFrozenMs ?: provider()) +
            (visualOffsetSeconds * 1000.0).toLong()
        if (chronoWrapsDay) {
            val day = 86_400_000L
            ((raw % day) + day) % day
        } else {
            raw
        }
    }

    // --------------------------------------------------------- accessibility

    /**
     * A view drawn on a Canvas is, to a screen reader, a blank rectangle.
     * The node the framework builds is where the reading belongs — not an
     * override of getContentDescription(), which also feeds internal View
     * machinery that has no business hearing about the time.
     *
     * Filled in on demand, so it is current without a stream of
     * announcements nobody asked for.
     */
    // ------------------------------------------- the dial, hand by hand

    /**
     * The hands a screen reader can reach, in the order they are read.
     *
     * The whole dial used to be one node. Everything on it is drawn on a
     * canvas, so exploring by touch found a single rectangle that said the
     * time and nothing else — a sighted user sees three hands and where
     * each one points, and a TalkBack user got a number. These are the
     * same three things, as separate nodes with their own bounds, so a
     * finger dragged over the face finds them one at a time.
     *
     * Fallen hands are included on purpose. A hand lying at the bottom of
     * the case is the single most surprising thing this clock does, and it
     * was invisible to anybody not looking at it.
     */
    internal fun spokenHands(): List<Hand> = Hand.entries.filter {
        handIsOn(it)
    }

    /** Where that hand is on screen, as a box a finger can find. */
    internal fun handBounds(hand: Hand): Rect? {
        if (hand !in spokenHands()) return null
        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()
        if (r <= 0f) return null
        if (isFallen(hand)) {
            // Down in the debris. One box for the floor of the case rather
            // than chasing a body that is still rolling: a node that moves
            // under the finger is a node nobody can land on.
            val floor = cy + r * 0.55f
            return Rect(
                (cx - r * 0.9f).toInt(), floor.toInt(),
                (cx + r * 0.9f).toInt(), (cy + r).toInt()
            )
        }
        val angle = angleOf(hand, currentAngles())
        val tip = pointAt(cx, cy, angle, handReach(hand))
        // The box is the tip and the middle, grown enough to be touchable:
        // a hand is a line, and a line has no area to land a finger in.
        val pad = r * 0.10f
        return Rect(
            (minOf(cx, tip.x) - pad).toInt(), (minOf(cy, tip.y) - pad).toInt(),
            (maxOf(cx, tip.x) + pad).toInt(), (maxOf(cy, tip.y) + pad).toInt()
        )
    }

    /** And what it says when the finger lands on it. */
    internal fun handLabel(hand: Hand): CharSequence {
        val name = context.getString(
            when (hand) {
                Hand.HOUR -> R.string.a11y_hand_hour
                Hand.MINUTE -> R.string.a11y_hand_minute
                Hand.SECOND -> R.string.a11y_hand_second
            }
        )
        if (isFallen(hand)) return context.getString(R.string.a11y_hand_fallen, name)
        // Where it points, said as a clock position rather than in degrees:
        // "pointing at 7" is a thing a person can picture.
        val angle = ((angleOf(hand, currentAngles()) % 360f) + 360f) % 360f
        val oClock = Math.round(angle / 30f).let { if (it == 0 || it == 12) 12 else it }
        return context.getString(R.string.a11y_hand_at, name, oClock)
    }

    private val handNodes = object : android.view.accessibility.AccessibilityNodeProvider() {

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_VIEW_ID) {
                val info = AccessibilityNodeInfo.obtain(this@ClockView)
                onInitializeAccessibilityNodeInfo(info)
                for (hand in spokenHands()) {
                    info.addChild(this@ClockView, hand.ordinal)
                }
                return info
            }
            val hand = Hand.entries.getOrNull(virtualViewId) ?: return null
            val bounds = handBounds(hand) ?: return null
            val info = AccessibilityNodeInfo.obtain(this@ClockView, virtualViewId)
            info.setParent(this@ClockView)
            info.className = "android.view.View"
            info.packageName = context.packageName
            info.contentDescription = handLabel(hand)
            info.setBoundsInParent(bounds)
            val onScreen = IntArray(2).also { getLocationOnScreen(it) }
            info.setBoundsInScreen(
                Rect(
                    bounds.left + onScreen[0], bounds.top + onScreen[1],
                    bounds.right + onScreen[0], bounds.bottom + onScreen[1]
                )
            )
            info.isEnabled = true
            info.isVisibleToUser = true
            return info
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?
        ): Boolean {
            // The hands are there to be read, not worked: everything that
            // can be done to the dial is an action on the dial itself, so
            // that a screen reader offers it in one place rather than three.
            if (virtualViewId == HOST_VIEW_ID) {
                return performAccessibilityAction(action, arguments)
            }
            return false
        }
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider =
        handNodes

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (info.contentDescription.isNullOrBlank()) {
            info.contentDescription = describeDial()
        }
        // The crown and the pushers are painted on the canvas, so to a
        // screen reader they are not there at all — and neither is any way
        // of working a stopwatch, since every one of its controls is one of
        // them. Two whole cards that could be read and not used.
        if (chronoButtons) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.a11y_chrono_start_stop,
                    context.getString(
                        if (chronoRunning) R.string.a11y_pause else R.string.a11y_start
                    )
                )
            )
            // The lower pusher does two jobs, as it does on a real
            // chronograph: laps while it runs, zero when it is stopped.
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.a11y_chrono_reset,
                    context.getString(
                        if (chronoRunning) R.string.a11y_lap else R.string.a11y_reset
                    )
                )
            )
        }
        // Shaking the hands off is a gesture; putting them back is dragging
        // each piece home, which is several. Neither is available to
        // somebody driving the app by voice, so there is one action for the
        // way out of it.
        if (isDisarranged()) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.a11y_reassemble, context.getString(R.string.a11y_reassemble)
                )
            )
        }
        // Winding is a drag, and a drag is the one gesture a screen reader
        // keeps for itself. A time of day or a length could be read out and
        // never changed.
        if (chronoSettable && chronoProvider != null) {
            val onwards = if (chronoWrapsDay) R.string.a11y_later else R.string.a11y_longer
            val backwards = if (chronoWrapsDay) R.string.a11y_earlier else R.string.a11y_shorter
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, context.getString(onwards)
                )
            )
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, context.getString(backwards)
                )
            )
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            R.id.a11y_chrono_start_stop -> {
                onChronoStartStop?.invoke() ?: return false
                return true
            }
            R.id.a11y_chrono_reset -> {
                onChronoReset?.invoke() ?: return false
                return true
            }
            R.id.a11y_reassemble -> {
                reassembleAll()
                return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (nudgeSetting(true)) return true
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> if (nudgeSetting(false)) return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /** What this dial would say out loud. */
    private fun describeDial(): CharSequence {
        val chrono = chronoDisplayMs()
        if (chrono != null) return spokenDuration(chrono)
        cal.timeInMillis = displayNowMs()
        val time = java.text.DateFormat
            .getTimeInstance(java.text.DateFormat.SHORT)
            .apply { timeZone = cal.timeZone }
            .format(java.util.Date(cal.timeInMillis))
        // Bubbles carry their city inside the dial; it belongs in the
        // reading too, or six of them all say the same thing.
        return dialLabel?.let { context.getString(R.string.a11y_city_time, it, time) } ?: time
    }

    /** Hours, minutes and seconds in words rather than as a bare 00:00. */
    private fun spokenDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>(3)
        if (h > 0) parts.add(context.getString(R.string.a11y_hours, h))
        if (m > 0) parts.add(context.getString(R.string.a11y_minutes, m))
        if (s > 0 || parts.isEmpty()) parts.add(context.getString(R.string.a11y_seconds, s))
        return parts.joinToString(" ")
    }

    private fun computeAngles(): Angles {
        chronoDisplayMs()?.let { duration ->
            val totalSec = duration / 1000.0
            // Winding one hand used to whirl the second hand round with it,
            // which is not what a watch does and not what anybody is
            // looking at. Here it simply stays where it was — the value is
            // frozen under the finger anyway, so "where it was" is exactly
            // the second the wind started on.
            //
            // The old rule pinned it only when the frozen value happened to
            // land on a whole minute, so setting a countdown of 1:30 still
            // sent it spinning.
            val pinned = draggedHand != null && draggedHand != Hand.SECOND
            val held = chronoFrozenMs ?: duration
            return Angles(
                hour = ((totalSec / 3600.0) % hoursOnDial / hoursOnDial * 360.0).toFloat(),
                minute = ((totalSec / 60.0) % 60.0 / 60.0 * 360.0).toFloat(),
                second = if (pinned) {
                    ((held % 60_000L) / 60_000.0 * 360.0).toFloat()
                } else {
                    ((totalSec % 60.0) / 60.0 * 360.0).toFloat()
                },
                fast = if (pinned) {
                    (held % 1000L) / 1000f * 360f
                } else {
                    (duration % 1000L) / 1000f * 360f
                }
            )
        }

        val nowMs = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
        cal.timeInMillis = nowMs
        val useMs = smoothSeconds || fastHand != FastHandMode.NONE || isAnimating()
        val ms = if (useMs) cal.get(Calendar.MILLISECOND) else 0
        val seconds = cal.get(Calendar.SECOND) + ms / 1000f
        val minutes = cal.get(Calendar.MINUTE) + seconds / 60f
        val hours = cal.get(Calendar.HOUR_OF_DAY) + minutes / 60f
        val n = hoursOnDial

        // On a clock the second hand is not geared to the others: it is the
        // one hand still telling the time while you carry the other two
        // around, and it keeps doing that. So it reads the real clock, past
        // both the wind offset and the freeze the grab puts on the
        // mechanism — while the minute hand is in your fingers the seconds
        // go on ticking as if nothing were happening.
        //
        // It comes loose when another hand is taken hold of, and stays
        // loose until the dial is showing now again — letting go used to
        // re-engage the gearing on the spot and set it spinning at whatever
        // offset you had stopped at, which is precisely the moment you are
        // reading the day's events off the face.
        //
        // But winding the second hand *itself* is the opposite case: it
        // must follow the finger, and it must come back on the spring with
        // the rest. Asking "is the offset non-zero" could not tell the two
        // apart, so it jumped to real time the instant the finger lifted
        // instead of springing home. What matters is which hand put the
        // offset there.
        // Recorded when the hand is taken hold of, not worked out here:
        // inferring it while drawing means the state only exists if a frame
        // happens to be drawn between the grab and the release, which is
        // true of the running app and not of anything else.
        if (draggedHand == null && visualOffsetSeconds == 0.0) secondLoose = false
        val loose = secondLoose && draggedHand != Hand.SECOND
        val secondAngle: Float
        val looseFastMs: Int
        if (loose) {
            secondCal.timeInMillis = TimeKeeper.nowMs()
            // Its own tick, not the dial's: `useMs` is true whenever
            // anything on the face is animating, and a hand being dragged
            // is animating — so a second hand that had come loose to keep
            // telling the time started sweeping smoothly while it did,
            // which is a different clock, not a quieter one.
            val realMs = if (smoothSeconds) secondCal.get(Calendar.MILLISECOND) else 0
            secondAngle = (secondCal.get(Calendar.SECOND) + realMs / 1000f) / 60f * 360f
            looseFastMs = secondCal.get(Calendar.MILLISECOND)
        } else {
            secondAngle = seconds / 60f * 360f
            looseFastMs = cal.get(Calendar.MILLISECOND)
        }

        val fast = when (fastHand) {
            FastHandMode.NONE -> 0f
            FastHandMode.TENTHS -> looseFastMs / 1000f * 360f
            FastHandMode.DECIMAL_MINUTE -> {
                val secondOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600.0 +
                    cal.get(Calendar.MINUTE) * 60.0 +
                    cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
                ((secondOfDay / 86.4) % 1.0 * 360.0).toFloat()
            }
        }
        return Angles(
            hour = (hours % n) / n * 360f,
            minute = minutes / 60f * 360f,
            second = secondAngle,
            fast = fast
        )
    }

    /** Which hand a grab landed on, and what the hands read, for tests. */
    internal fun draggedHandForTest(): Hand? = draggedHand
    internal fun secondAngleForTest(): Float = currentAngles().second

    /** Where a hand is pointing, so a test can reach for it rather than guess. */
    internal fun handAngleForTest(hand: Hand): Float = angleOf(hand, currentAngles())

    /**
     * Winds the dial the way a finger would, without the finger.
     *
     * The wound value lives in the visual offset, not in the provider —
     * grabbing a hand freezes the provider's value on the spot — so a test
     * that changed what the provider returns changed nothing at all, and
     * duly passed with the fix removed.
     */
    internal fun windForTest(seconds: Double) {
        visualOffsetSeconds = seconds
    }

    /**
     * Drags the held hand to a point, through the real winding path —
     * detents, precision band and all. [windForTest] sets the offset
     * directly and so says nothing about any of that.
     */
    internal fun dragToForTest(x: Float, y: Float) = dragTo(x, y)

    /** Lets go of whatever hand is held, without the spring or the commit. */
    internal fun releaseForTest() {
        draggedHand = null
    }

    /**
     * True while the second hand is running on real time rather than on the
     * dial's wound value. Set by taking hold of another hand, cleared by
     * taking hold of this one or by the dial coming home.
     */
    private var secondLoose = false

    /** A second calendar, so reading the real clock never disturbs [cal]. */
    private val secondCal: Calendar = Calendar.getInstance()

    /**
     * Opens a layer the face's furniture is drawn into, faded by how far
     * the hand-over has got, and returns its save count.
     *
     * The hands travel; everything else on the face — the date, the marks,
     * the sky, the readout — used to appear at once, which made the arrival
     * read as two events instead of one. They fade in over the same journey
     * now. Only on the way in: on the way out the card is cut away with the
     * page, and there is nothing left to fade.
     *
     * Returns -1 when there is nothing to fade, so an ordinary frame costs
     * no layer at all.
     */
    private fun beginFurniture(canvas: Canvas): Int {
        val t = transitionProgress()
        if (t >= 1f) return -1
        return canvas.saveLayerAlpha(
            0f, 0f, width.toFloat(), height.toFloat(), (255 * t).toInt()
        )
    }

    private fun endFurniture(canvas: Canvas, layer: Int) {
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /**
     * The same trick for the hand-over to the solar system: one layer for
     * the whole clock, faded as a single object.
     */
    private fun beginOrreryHandover(canvas: Canvas): Int {
        val fade = orreryFade()
        if (fade <= 0.01f) return -1
        return canvas.saveLayerAlpha(
            0f, 0f, width.toFloat(), height.toFloat(), (255 * (1f - fade)).toInt()
        )
    }

    private fun endOrreryHandover(canvas: Canvas, layer: Int) {
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /**
     * Draws the outgoing dial's furniture on this one, fading out as this
     * one's fades in.
     *
     * The other half of the fade. A dial arriving from a hand-over faded
     * its own date, marks, sky and readout in, and the ones it was
     * replacing simply stopped existing — because on a diagonal the card
     * that carried them is cut away with its page in the same frame, so
     * anything it drew after that played to nobody. The eye is on the dial
     * that stays, so the fade-out happens there: the two dials are the same
     * size in the same place, and for seven hundred milliseconds this one
     * wears both sets and dissolves one into the other.
     *
     * Not the hands, which travel rather than dissolve, and not the crown,
     * which is inherited outright by [handOverFrom] — drawing it here as
     * well would fade it twice over.
     */
    private fun drawGhost(canvas: Canvas, draw: (ClockView) -> Unit) {
        val leaving = ghostDial ?: return
        val t = transitionProgress()
        if (t >= 1f) return
        val layer = canvas.saveLayerAlpha(
            0f, 0f, width.toFloat(), height.toFloat(), ((1f - t) * 255).toInt()
        )
        draw(leaving)
        canvas.restoreToCount(layer)
    }

    private fun transitionProgress(): Float {
        if (!furnitureFades || transitionFrom == null) return 1f
        val t = ((SystemClock.uptimeMillis() - transitionStartAt) / TRANSITION_MS).coerceIn(0f, 1f)
        // On the hands' own curve, not a straight line. Two things crossing
        // the same seven hundred milliseconds at different rates is what an
        // asymmetric fade actually looks like: a quarter of the way in, a
        // linear fade is at 25% and the hands are at 15%, so the face keeps
        // arriving ahead of them and leaving behind them. The curve is
        // symmetric about its middle, so fading in on it and out on one
        // minus it are mirror images.
        return transitionInterpolator.getInterpolation(t)
    }

    /** Target angles, blended with the mode-transition animation if active. */
    private fun currentAngles(): Angles {
        val target = computeAngles()
        val from = transitionFrom ?: return target
        val t = (SystemClock.uptimeMillis() - transitionStartAt) / TRANSITION_MS
        if (t >= 1f) {
            transitionFrom = null
            ghostDial = null
            return target
        }
        val f = transitionInterpolator.getInterpolation(t.coerceIn(0f, 1f))
        return Angles(
            hour = lerpAngle(from.hour, target.hour, f),
            minute = lerpAngle(from.minute, target.minute, f),
            second = lerpAngle(from.second, target.second, f),
            fast = lerpAngle(from.fast, target.fast, f)
        )
    }

    private fun lerpAngle(from: Float, to: Float, fraction: Float): Float =
        from + normalizeDeg(to - from) * fraction

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun angleOf(hand: Hand, a: Angles): Float = when (hand) {
        Hand.HOUR -> a.hour
        Hand.MINUTE -> a.minute
        Hand.SECOND -> a.second
    }

    private fun lengthOf(hand: Hand): Float = when (hand) {
        Hand.HOUR -> HOUR_LEN
        Hand.MINUTE -> MINUTE_LEN
        Hand.SECOND -> SECOND_LEN
    }

    private fun tailOf(hand: Hand): Float = when (hand) {
        Hand.HOUR -> 0.10f
        Hand.MINUTE -> 0.12f
        Hand.SECOND -> 0.18f
    }

    private fun widthOf(hand: Hand): Float = when (hand) {
        Hand.HOUR -> 0.045f
        Hand.MINUTE -> 0.03f
        Hand.SECOND -> 0.012f
    }

    private fun paintOf(hand: Hand): Paint = when (hand) {
        Hand.HOUR -> hourHandPaint
        Hand.MINUTE -> minuteHandPaint
        Hand.SECOND -> secondHandPaint
    }

    /**
     * Polygonal faces read smaller than a circle of the same circumradius
     * (their edges sit at the apothem), so each shape gets a size boost that
     * brings its edges near the screen margins without clipping the corners.
     */
    private fun shapeBoost(): Float = when (dialShape) {
        DialShape.TRIANGLE -> 1.25f
        DialShape.SQUARE -> 1.30f
        DialShape.HEXAGON -> 1.12f
        DialShape.OCTAGON -> 1.06f
        else -> 1f
    }

    private fun dialRadius(): Float = min(width, height) / 2f * 0.92f * dialScale * shapeBoost()

    /** For the tests: the date written on the piece lying in the case. */
    internal fun fallenDateForTest(): String? =
        debris.bodies.firstOrNull { it.kind == DialDebris.Kind.DATE }?.label

    /** For the tests: the instant the fallen sky token is stuck at. */
    internal fun fallenSkyMomentForTest(): Long? =
        debris.bodies.firstOrNull { it.kind == DialDebris.Kind.MOON }?.frozenWallMs

    /** For the tests: how big the face is, so a touch can be aimed at it. */
    internal fun dialRadiusForTest(): Float = dialRadius()

    /** The dial's current outer radius, for hosts that need it (bubbles). */
    fun currentDialRadius(): Float = dialRadius()

    /**
     * Distance from the center to the dial's edge at [angleDeg], measured
     * clockwise from 12. A circle returns the radius; a polygon returns the
     * distance to its boundary, largest at the corners and smallest at the
     * edge midpoints (the apothem).
     */
    private fun boundaryRadius(angleDeg: Float): Float {
        val r = dialRadius()
        val n = dialShape.sides
        if (n < 3) return r
        val half = 180f / n
        var psi = (angleDeg - dialShape.vertexOffsetDeg) % (2f * half)
        if (psi < 0f) psi += 2f * half
        val apothemFraction = cos(Math.toRadians(half.toDouble())).toFloat()
        return r * apothemFraction / cos(Math.toRadians((psi - half).toDouble())).toFloat()
    }

    /**
     * How long a hand is, in pixels. The same at every angle.
     *
     * Measured against the circle that fits *inside* the dial rather than
     * against the dial's own edge, which is what it used to be. On a round
     * face the two are the same number and nobody could have noticed; on a
     * square one the edge is half again as far away at the corners, so the
     * hands stretched as they swept towards each corner and shrank back
     * between them — a clock whose minute hand grows and shrinks four
     * times an hour.
     *
     * The cost is that a square face's hands only touch the edge at the
     * middle of each side, which is exactly what a real square clock does:
     * the hand is a rod, and a rod does not know what shape its case is.
     */
    private fun handReach(hand: Hand): Float = apothemRadius() * lengthOf(hand)

    /**
     * How much smaller everything drawn on the face is than it would be on
     * a round dial of the same size.
     *
     * A square face is a circle with the corners added, and the circle
     * inside it is nearly a third smaller across than the square is; a
     * triangle's is half. The hands were the visible half of this and are
     * measured against the inscribed circle now — but their *thickness*,
     * the numerals, the minute marks and the little glyphs all went on
     * being sized against the whole dial, so a triangular clock had half
     * the hand carrying the same weight of ink, numerals nearly touching
     * one another, and a moon the size of a hubcap.
     *
     * One number, applied to every size on the face. Positions are not
     * touched: a numeral belongs near the edge and the edge is where the
     * edge is.
     */
    private fun faceScale(): Float = apothemRadius() / dialRadius().coerceAtLeast(1f)

    /** The polygon's inscribed radius — the safe zone for inner complications. */
    private fun apothemRadius(): Float {
        val n = dialShape.sides
        val r = dialRadius()
        return if (n < 3) r else r * cos(Math.toRadians(180.0 / n)).toFloat()
    }

    private val facePath = Path()

    private fun buildFacePath(cx: Float, cy: Float) {
        val n = dialShape.sides
        val r = dialRadius()
        facePath.reset()
        for (k in 0 until n) {
            val p = pointAt(cx, cy, dialShape.vertexOffsetDeg + k * 360f / n, r)
            if (k == 0) facePath.moveTo(p.x, p.y) else facePath.lineTo(p.x, p.y)
        }
        facePath.close()
    }

    // ----------------------------------------------------------------- draw

    /**
     * A chronograph with a screen in it instead of hands.
     *
     * The case stays: the bezel, the crown, the two pushers, the lap
     * ladder under it. That is the whole idea — a digital chronograph is
     * not a number on a background, it is the same instrument with a
     * different movement in it, and the crown keeps its second thoughts.
     *
     * Only meaningful on a face that is timing something. A clock with a
     * screen where its hands go is not a clock, it is the other face.
     */
    var lcdChrono = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * The sky, and nothing else.
     *
     * For the face that shows the solar system where its calendar would
     * be. The dial is still the host — it owns the sky, the winding, the
     * comets and every gesture that reaches them — but the clock part of
     * it has no business being there: a set of hands over the planets is
     * a clock somebody has to look past.
     *
     * Reuses the same guards the chronograph's screen uses, because they
     * suppress exactly the same list. What was a question about one
     * feature is now a question about whether this dial is being a dial
     * at all, which is what it should have been from the start.
     */
    var skyOnly = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                orreryEnabled = true
                if (!orreryShowing()) toggleOrrery()
            }
            invalidate()
        }

    /** Whether the hands have been swapped for something else on this face. */
    private fun lcd(): Boolean = skyOnly || (lcdChrono && chronoProvider != null)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Noted before the frame rather than after it, so a transition
        // started from inside a draw still counts this face as seen.
        hasDrawn = true
        if (debris.bodies.isNotEmpty()) stepPhysics()

        val cx = width / 2f
        val cy = height / 2f
        val r = dialRadius()

        // Case hardware sits behind the face so it reads as attached to it.
        if (isCrownShowing()) {
            drawChronoHardware(canvas, cx, cy, r)
        }

        rimPaint.strokeWidth = r * 0.02f
        if (dialShape == DialShape.CIRCLE) {
            canvas.drawCircle(cx, cy, r, facePaint)
            darkenToSky(canvas, cx, cy, r)
            canvas.drawCircle(cx, cy, r, rimPaint)
        } else {
            buildFacePath(cx, cy)
            canvas.drawPath(facePath, facePaint)
            darkenToSky(canvas, cx, cy, r)
            canvas.drawPath(facePath, rimPaint)
        }

        // Everything the clock is made of goes into one layer while the
        // solar system is coming up, and the layer is what fades: ticks,
        // numerals, date, marks, hands, the lot. Fading each of them
        // separately would show them through one another on the way out,
        // because a half-transparent hand crossing a half-transparent tick
        // is darker than either.
        val clockLayer = beginOrreryHandover(canvas)
        if (!lcd()) {
            drawTicks(canvas, cx, cy, r)
            drawNumerals(canvas, cx, cy, r)
        }
        dialLabel?.let { label ->
            datePaint.textSize = r * 0.15f
            canvas.drawText(
                label, cx,
                cy - apothemRadius() * 0.42f - (datePaint.ascent() + datePaint.descent()) / 2f,
                datePaint
            )
        }
        // Everything the face carries besides its hands fades in with the
        // hand-over: the date, the marks, the sky. One layer for the lot of
        // them, so they arrive together and at the rate the hands are
        // travelling — furniture that snaps into place while the hands are
        // still moving reads as two events rather than one.
        val furniture = beginFurniture(canvas)
        drawFaceFurniture(canvas, cx, cy, r)
        endFurniture(canvas, furniture)
        // And the outgoing dial's, going the other way.
        drawGhost(canvas) { it.drawFaceFurniture(canvas, cx, cy, r) }

        val a = currentAngles()

        // The lap ghosts and the tenths scale belong to the chronograph and
        // not to the clock, so they cross-fade with the rest of the face.
        // Left outside it they were the two things that snapped: swap to the
        // stopwatch and a ten-division ring appeared in the middle of the
        // dial in one frame while everything around it was still arriving.
        val chronoLayer = beginFurniture(canvas)
        // The scale first and the ghosts on top of it. The other way round
        // the ten division marks were drawn across every recorded lap, so a
        // list of laps read as a list of laps with lines through it.
        if (!lcd()) drawFastScale(canvas, cx, cy, r)
        drawLapGhosts(canvas, cx, cy, r)
        endFurniture(canvas, chronoLayer)
        drawGhost(canvas) {
            it.drawFastScale(canvas, cx, cy, r)
            it.drawLapGhosts(canvas, cx, cy, r)
        }

        // The tenths hand itself is a hand: it travels rather than fades.
        if (showsFastHand() && !isFastHandFallen() && !lcd()) {
            drawHand(
                canvas, cx, cy, a.fast, r * FAST_LEN * faceScale(),
                r * 0.05f * faceScale(), r * 0.008f * faceScale(), fastHandPaint
            )
        }

        // Every shadow before any hand, because all three fall on the
        // face. Drawn hand by hand as they were, the second hand's shadow
        // landed on top of the hour hand, which is a shadow cast onto a
        // thing standing above the surface it is cast on.
        if (!lcd()) drawHandShadows(canvas, cx, cy, r, a)

        for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
            if (lcd()) break
            if (!handIsOn(hand)) continue
            if (isFallen(hand)) continue
            val angle = angleOf(hand, a)
            drawHand(
                canvas, cx, cy,
                angle,
                handReach(hand),
                r * tailOf(hand) * faceScale(),
                r * widthOf(hand) * faceScale(),
                paintOf(hand)
            )
        }

        drawFallenBodies(canvas)

        if (!lcd()) canvas.drawCircle(cx, cy, r * 0.035f, centerDotPaint)
        endOrreryHandover(canvas, clockLayer)

        // The pieces on the floor belong to the case, not to the clock, so
        // they are drawn after the hand-over layer closes. Inside it they
        // were painted at the layer's alpha — which is zero once the
        // planets have the dial, so a knock made them vanish instead of
        // spilling them.
        if (orreryShowing()) drawFallenPlanets(canvas)

        // And the planets in their place.
        //
        // The sky token goes with the hands rather than staying on as the
        // way back, and it has to: a moon glyph the size of Jupiter,
        // sitting on Saturn's ring, in a picture that already has a Moon in
        // it — the first drawing of this had one and it read as a ninth
        // planet. The way back is a tap on any empty piece of sky, which is
        // most of the dial, and the place the token was is part of it.
        if (orreryShowing()) drawOrrery(canvas, cx, cy, r)
        // The host is told how far the sky has come, so it can take its own
        // furniture off the dial — the world clock's bubbles float over the
        // top of everything and have nothing to say about planets.
        val skyNow = orreryFade()
        if (skyNow != reportedSkyFade) {
            reportedSkyFade = skyNow
            onSkyFade?.invoke(skyNow)
        }

        // Digital 7-segment readout: the chronograph value in chrono modes,
        // or the current time while the hands are lying at the bottom of
        // the dial and the analog display is useless.
        val readoutLayer = beginFurniture(canvas)
        drawReadout(canvas, cx, cy, r, live = true)
        endFurniture(canvas, readoutLayer)
        drawGhost(canvas) { it.drawReadout(canvas, cx, cy, r, live = false) }

        if (SystemClock.uptimeMillis() < cheaterUntil && cheaterFade < 1f) {
            cheaterPaint.textSize = r * 0.24f
            cheaterPaint.alpha = ((1f - cheaterFade) * 255).toInt()
            canvas.save()
            canvas.rotate(-18f, cx, cy)
            canvas.drawText(
                context.getString(R.string.cheater_stamp),
                cx,
                cy - (cheaterPaint.ascent() + cheaterPaint.descent()) / 2f,
                cheaterPaint
            )
            canvas.restore()
        }

        // The lap list, unfolded: a tap on the ladder opens it, a drag
        // scrolls it, a tap closes it. Every lap, uniform size, newest
        // first, the faked ones in the second hand's red.
        if (lapsExpanded && laps.isEmpty()) lapsExpanded = false
        if (lapsExpanded && chronoProvider != null) {
            scrimPaint.color = theme.face
            scrimPaint.alpha = 242
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            val rowDigitH = r * 0.105f
            val rowH = rowDigitH * 1.9f
            val topMargin = height * 0.07f
            val keepColor = digitalPaint.color
            canvas.save()
            canvas.clipRect(0f, topMargin * 0.5f, width.toFloat(), height - topMargin * 0.5f)
            var y = topMargin - lapListScroll
            for (i in laps.indices.reversed()) {
                if (y + rowDigitH > topMargin * 0.5f && y < height) {
                    val lap = laps[i]
                    digitalPaint.color = if (lap.fake) theme.secondHand else keepColor
                    drawSevenSegment(
                        canvas,
                        String.format(Locale.US, "%d %s", i + 1, formatDuration(lap.ms)),
                        width / 2f, y, rowDigitH, unitsFor(lap.ms)
                    )
                }
                y += rowH
            }
            canvas.restore()
            digitalPaint.color = keepColor
        }

        // The hour hand names what it is standing on, then the bubble is
        // drawn last of all, over everything including the lap scrim.
        followHourHand(a.hour)
        drawMarkBubble(canvas, r)
        checkShownDay()
    }

    /**
     * Everything the face carries besides its hands: the date, the alarm
     * marks, the sky.
     *
     * Its own function because the dial arriving from a hand-over draws the
     * outgoing dial's copy of it as well as its own — see [drawGhost].
     */
    private fun drawFaceFurniture(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (chronoProvider == null) {
            if (showDate) drawDate(canvas, cx, cy, r)
            if (eventArcs.isNotEmpty() || alarmMarkers.isNotEmpty()) {
                drawMarksLayer(canvas, cx, cy, r)
            }
        }
        // The sky complication is the one that also belongs on a face
        // standing for a fixed time — the little dials on the alarm cards
        // and the big one while a time is being wound onto it. Those all
        // run on a chrono provider, so it is drawn outside that guard;
        // leaving it inside is why 14.1's token appeared on C0 only.
        if (showMoonPhase) drawMoonPhase(canvas, cx, cy, r)
    }

    /**
     * The recent laps, drawn as the hands themselves at a lower alpha:
     * same colour, same length, same width. Dashes and thin outlines read
     * as decoration, not as "this is where the hands were".
     */
    private fun drawLapGhosts(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (chronoProvider == null || laps.isEmpty()) return
        for ((i, lap) in laps.withIndex()) {
            val alpha = 60 + 150 * (i + 1) / laps.size
            for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
                if (!handIsOn(hand)) continue
                val angle = when (hand) {
                    Hand.HOUR -> lap.hour
                    Hand.MINUTE -> lap.minute
                    Hand.SECOND -> lap.second
                }
                val paint = paintOf(hand)
                val was = paint.alpha
                paint.alpha = alpha
                drawHand(
                    canvas, cx, cy, angle,
                    handReach(hand),
                    r * tailOf(hand) * faceScale(),
                    r * widthOf(hand) * faceScale(),
                    paint
                )
                paint.alpha = was
            }
        }
    }

    /** The ten divisions the tenths hand runs against. */
    private fun drawFastScale(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (!showsFastHand()) return
        for (i in 0 until 10) {
            val angle = i / 10f * 360f
            fastTickPaint.strokeWidth = r * 0.012f
            val from = pointAt(cx, cy, angle, r * 0.30f)
            val to = pointAt(cx, cy, angle, r * 0.36f)
            canvas.drawLine(from.x, from.y, to.x, to.y, fastTickPaint)
        }
    }

    /**
     * Digital 7-segment readout: the chronograph value in chrono modes, or
     * the current time while the hands are lying at the bottom of the dial
     * and the analog display is useless. The recent laps stack under it.
     *
     * [live] is false when this is the outgoing dial's readout being drawn
     * as a ghost on the dial that replaced it: the tap target belongs to
     * the dial you can actually touch, not to the one dissolving.
     */
    private fun drawReadout(canvas: Canvas, cx: Float, cy: Float, r: Float, live: Boolean) {
        val digitalText = readoutText()
        digitalText?.let {
            // On a face with a screen in it the readout *is* the movement,
            // so it goes inside the bezel where the hands were and takes
            // as much of it as it can. Everywhere else it is a caption
            // under the dial saying what the hands already said.
            val inside = lcd()
            if (skyOnly) return
            val digitH =
                if (inside) minOf(r * 0.46f, LCD_ROW * r / (0.82f * it.length.coerceAtLeast(1)))
                else r * 0.13f
            val yTop =
                if (inside) cy - digitH / 2f
                else min(cy + boundaryRadius(180f) + digitH * 0.4f, height - digitH * 1.6f)
            val liveUnits = readoutUnits()
            // The ladder is tapped where the laps are, which on a screen
            // face is still under the dial and not through the middle of
            // the reading.
            if (live) {
                ladderTapTop =
                    if (inside) min(cy + boundaryRadius(180f) + r * 0.05f, height - r * 0.2f)
                    else yTop
            }
            // The window the digits sit behind, on the face that is a
            // display. Drawn before them, because it is what they are on.
            if (inside) {
                val underH = if (secondReadout?.invoke() != null) r * 0.10f else 0f
                drawScreenFrame(canvas, cx, cy, r, digitH, underH, it, liveUnits)
            }
            // The unlit bars behind it, on the face that is a display.
            // A screen you can only see the lit half of is a picture of a
            // number; the ghost of the eight is what says it is a machine.
            readoutGhosts = inside
            drawSevenSegment(canvas, it, cx, yTop, digitH, liveUnits)
            readoutGhosts = false

            // A second, smaller row under the first, when something has
            // asked for one. On the countdown it is how long the thing has
            // been running — the number the dial cannot show, because its
            // hands are busy showing what is left. Built like a lap ghost
            // because that is what it is: the same digits, smaller and
            // quieter, saying a second thing about the same run.
            secondReadout?.invoke()?.let { under ->
                val underH = if (inside) r * 0.10f else digitH * 0.62f
                val keep = digitalPaint.alpha
                digitalPaint.alpha = 170
                drawSevenSegment(
                    canvas, formatDuration(under), cx,
                    if (inside) cy + digitH * 0.72f else yTop + digitH * 1.28f,
                    underH, unitsFor(under)
                )
                digitalPaint.alpha = keep
            }

            // Ghost copies of the recent laps, stacked under the readout —
            // as many as the space below the dial can hold.
            if (chronoProvider != null && laps.isNotEmpty()) {
                // Newest lap first and largest, each older one a step
                // smaller and fainter — a receding stack, which also means
                // several more of them fit in the same strip.
                var ghostY =
                    if (inside) ladderTapTop + r * 0.06f else yTop + digitH * 1.28f
                // The strip between the readout and the button row holds
                // exactly seven rungs: with the step ratio fixed, the ladder
                // is a geometric series, so the first rung is the strip
                // divided by what seven rungs come to — no clamp, no
                // leftover gap above the button.
                val bottom = height - BUTTON_RESERVE_DP * resources.displayMetrics.density
                val firstH = ((bottom - ghostY) / LADDER_SPAN)
                    .coerceAtLeast(digitH * 0.20f)
                var ghostH = firstH
                var shown = 0
                for (lap in laps.reversed()) {
                    if (shown >= MAX_GHOST_LAPS) break
                    if (ghostY + ghostH > bottom + 1f) break
                    shown++
                    digitalPaint.alpha = (200f * (ghostH / firstH))
                        .toInt().coerceIn(45, 200)
                    drawSevenSegment(
                        canvas, formatDuration(lap.ms), cx, ghostY, ghostH, unitsFor(lap.ms)
                    )
                    ghostY += ghostH * 1.35f
                    ghostH *= 0.88f
                }
                digitalPaint.alpha = 255
            }
        }
    }

    /** How far the unfolded lap list can scroll past the bottom edge. */
    private fun maxLapScroll(): Float {
        val r = dialRadius()
        val rowH = r * 0.105f * 1.9f
        return (laps.size * rowH - height * 0.86f).coerceAtLeast(0f)
    }

    /**
     * MM:SS:CC with live centiseconds under an hour; from the hour mark on
     * the centiseconds yield their slots to hours — HH:MM:SS. Which is which
     * is written in the corner marks, via [unitsFor].
     */
    private fun formatDuration(ms: Long): String {
        val abs = kotlin.math.abs(ms)
        val sign = if (ms < 0) "-" else ""
        return if (abs < 3_600_000L) {
            String.format(
                Locale.US, "%s%02d:%02d:%02d",
                sign, abs / 60_000, abs / 1000 % 60, abs / 10 % 100
            )
        } else {
            String.format(
                Locale.US, "%s%02d:%02d:%02d",
                sign, abs / 3_600_000 % 100, abs / 60_000 % 60, abs / 1000 % 60
            )
        }
    }

    /**
     * What the digital readout says, if anything.
     *
     * Its own function because *which* format is chosen is a decision with
     * three answers and no way to see it from outside — and the wrong one
     * had been running under the alarm dial since the day it borrowed the
     * chronograph's engine.
     */
    internal fun readoutText(): String? = when {
        // A face used as a plain time-of-day badge wants hands and nothing
        // else.
        !showDigitalReadout -> null
        // A time of day, or a length of one: hours and minutes, steady.
        chronoProvider != null && chronoSettable && chronoWrapsDay ->
            formatClockish(settingReadoutMs())
        // While setting a duration the readout must follow the hands — that
        // is what you are reading as you wind. Otherwise it reports the
        // mechanism's own value, so a wound hand cannot drag the display
        // into negative time.
        chronoProvider != null && chronoSettable -> formatDuration(settingReadoutMs())
        chronoProvider != null -> formatDuration(chronoProvider?.invoke() ?: 0L)
        // The sky has its own row of digits in exactly this place — the
        // date the system is standing on — so the clock does not put a
        // second row on top of it. A knock while the sky is up takes the
        // hands down with the planets, and the hour and the date were being
        // drawn over each other, one segment through the other.
        //
        // The sky wins because it is what is on screen: under a solar
        // system, what the digits are for is saying which year you have
        // wound yourself to.
        orreryShowing() -> null
        anyHandFallen() -> {
            cal.timeInMillis = displayNowMs()
            String.format(
                Locale.US, "%02d:%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND)
            )
        }
        else -> null
    }

    /**
     * The corner marks over the digital readout, or none.
     *
     * They exist to say which unit each pair of digits is standing in,
     * because on a chronograph that changes underneath you: minutes,
     * seconds and hundredths below the hour, hours, minutes and seconds
     * above it. That is the whole of their job.
     *
     * Everywhere else the digits mean the same thing from one end of the
     * readout to the other — a clock spelling out the time because its
     * hands are lying at the bottom of the case, a time of day being wound
     * onto the face, a length being wound the same way. There the marks
     * have nothing to disambiguate and a ° over a quarter past seven reads
     * as a temperature.
     */
    internal fun readoutUnits(): Array<String> = when {
        // No provider: the clock itself, reading out because its hands are
        // down. Always hours, minutes and seconds.
        chronoProvider == null -> UNITS_NONE
        // C0 borrowing the wind-to-set engine. The two flags are set
        // together in one place and mean exactly that; a chronograph card
        // is settable too, but its digits still change meaning.
        chronoSettable && chronoWrapsDay -> UNITS_NONE
        // From the number on the glass, not from the one in the
        // chronograph. They are the same until a hand is being carried, and
        // then the digits show where the hand is while the little marks
        // beside them still described where it had been: wind past an hour
        // and the display read hours with minute-and-second marks under it
        // until the finger came off.
        else -> unitsFor(chronoDisplayMs() ?: 0L)
    }

    /**
     * Hours and minutes, and nothing else.
     *
     * The chronograph's readout swaps units as the value grows — hundredths
     * under the hour, seconds over it — which is right for timing something
     * and wrong for setting an alarm twice over. The digits shift sideways
     * the moment you cross an hour, so the number you are reading moves
     * under your eye; and hundredths of a second are not a thing anybody
     * sets an alarm to. What you want is the minutes, plainly, because your
     * finger is probably covering the hand.
     */
    private fun formatClockish(ms: Long): String {
        val abs = kotlin.math.abs(ms)
        return String.format(Locale.US, "%02d:%02d", abs / 3_600_000L, abs / 60_000L % 60L)
    }

    /**
     * The corner marks for a chronograph reading: degrees-minutes-seconds
     * style, so the tail gives the scale away — a reading whose last group
     * is unmarked ends in hundredths, one ending in \u2033 ends in seconds.
     */
    private fun unitsFor(ms: Long): Array<String> =
        if (kotlin.math.abs(ms) < 3_600_000L) UNITS_STOPWATCH else UNITS_CLOCK

    /**
     * Chronograph furniture, fading in and out with the mode transition:
     * a large crown at 12 (tap it…), the start/stop pusher at 1:30
     * (accent-tinted while running, on the thumb side) and a smaller reset
     * pusher at 10:30.
     */
    private fun drawChronoHardware(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val t = transitionInterpolator.getInterpolation(
            ((SystemClock.uptimeMillis() - buttonsAnimStart) / BUTTONS_MS.toFloat())
                .coerceIn(0f, 1f)
        )
        val visibility = if (chronoButtons) t else 1f - t
        if (visibility <= 0f) return
        val alpha = (visibility * 255).toInt()

        // Crown at 12: big, knurled, capped and jeweled. On polygonal cases
        // the hardware sits on the actual edge, wherever that is.
        val bCrown = boundaryRadius(0f)
        pusherPaint.color = theme.rim
        pusherPaint.alpha = alpha
        val crownOuter = if (pressedPusher == 3) bCrown * 1.08f else bCrown * 1.12f
        drawCaseStub(canvas, cx, cy, bCrown * 0.90f, 0f, r * 0.085f, crownOuter)
        // Knurling: five winding ridges across the crown body.
        rimPaint.strokeWidth = r * 0.010f
        rimPaint.alpha = (alpha * 0.8f).toInt()
        for (i in -2..2) {
            val offset = i * r * 0.032f
            canvas.drawLine(
                cx + offset, cy - bCrown * 1.005f,
                cx + offset, cy - crownOuter + r * 0.012f,
                rimPaint
            )
        }
        rimPaint.alpha = 255
        // Cap band in the tick color, and a jewel in the accent color.
        pusherPaint.color = theme.tick
        pusherPaint.alpha = (alpha * 0.85f).toInt()
        val cap = RectF(
            cx - r * 0.085f, cy - crownOuter,
            cx + r * 0.085f, cy - crownOuter + r * 0.028f
        )
        canvas.drawRoundRect(cap, r * 0.014f, r * 0.014f, pusherPaint)
        pusherPaint.color = theme.decimal
        pusherPaint.alpha = alpha
        canvas.drawCircle(cx, cy - (crownOuter - r * 0.014f), r * 0.015f, pusherPaint)

        // Start/stop pusher at 1:30.
        val bStart = boundaryRadius(45f)
        pusherPaint.color = if (chronoRunning) theme.secondHand else theme.rim
        pusherPaint.alpha = alpha
        drawCaseStub(
            canvas, cx, cy, bStart * 0.90f, 45f, r * 0.06f,
            if (pressedPusher == 1) bStart * 1.06f else bStart * 1.11f
        )

        // Reset pusher at 10:30, smaller.
        val bReset = boundaryRadius(315f)
        pusherPaint.color = theme.rim
        pusherPaint.alpha = alpha
        drawCaseStub(
            canvas, cx, cy, bReset * 0.90f, 315f, r * 0.042f,
            if (pressedPusher == 2) bReset * 1.05f else bReset * 1.09f
        )

        if (t < 1f) invalidate()
    }

    private fun drawCaseStub(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        inner: Float,
        angleDeg: Float,
        halfWidth: Float,
        outer: Float
    ) {
        canvas.save()
        canvas.rotate(angleDeg - 90f, cx, cy)
        val rect = RectF(cx + inner, cy - halfWidth, cx + outer, cy + halfWidth)
        canvas.drawRoundRect(rect, halfWidth * 0.6f, halfWidth * 0.6f, pusherPaint)
        canvas.restore()
    }

    private fun drawFallenBodies(canvas: Canvas) {
        for (b in debris.bodies) {
            when (b.kind) {
                DialDebris.Kind.NUMERAL -> {
                    numeralPaint.textSize = b.textSize
                    canvas.save()
                    canvas.translate(b.x, b.y)
                    canvas.rotate(b.angleDeg)
                    canvas.drawText(
                        b.label, 0f,
                        -(numeralPaint.ascent() + numeralPaint.descent()) / 2f,
                        numeralPaint
                    )
                    canvas.restore()
                }
                DialDebris.Kind.DATE -> {
                    // The capsule runs along the text, so the drawn text is
                    // rotated 90° behind the body angle.
                    datePaint.textSize = b.textSize
                    canvas.save()
                    canvas.translate(b.x, b.y)
                    canvas.rotate(b.angleDeg - 90f)
                    canvas.drawText(
                        b.label, 0f,
                        -(datePaint.ascent() + datePaint.descent()) / 2f,
                        datePaint
                    )
                    canvas.restore()
                }
                DialDebris.Kind.MOON -> {
                    // The sun or the moon it was, not a white bead. It is
                    // the same drawing the dial uses, held at the hour it
                    // was showing when it came off — see [SkyGlyph].
                    moonRimPaint.strokeWidth = b.halfLen * 0.12f
                    canvas.save()
                    canvas.rotate(b.angleDeg, b.x, b.y)
                    SkyGlyph.draw(
                        canvas, b.x, b.y, b.halfLen,
                        moonLitPaint, moonDarkPaint, moonRimPaint,
                        b.frozenTimeOfDayMs, b.frozenWallMs
                    )
                    canvas.restore()
                }
                else -> {
                    val rad = Math.toRadians(b.angleDeg.toDouble())
                    val dirX = sin(rad).toFloat()
                    val dirY = -cos(rad).toFloat()
                    val paint = when {
                        b.kind == DialDebris.Kind.FAST_HAND -> fastHandPaint
                        else -> paintOf(b.hand ?: Hand.HOUR)
                    }
                    paint.strokeWidth = b.strokeWidth
                    canvas.drawLine(
                        b.x - dirX * b.halfLen, b.y - dirY * b.halfLen,
                        b.x + dirX * b.halfLen, b.y + dirY * b.halfLen,
                        paint
                    )
                }
            }
        }
    }

    /**
     * How many of the hours carry a mark.
     *
     * All twelve, or every other one, or the four quarters, or none. A
     * dial with four marks on it is perfectly ordinary and reads at a
     * glance; a dial with none is a Braun, and it is a real thing somebody
     * would choose.
     *
     * Kept as "one in how many" rather than as a count, so it works on the
     * dials that do not have twelve hours on them: a twenty-four hour face
     * asked for four marks gets one every six hours, which is the same
     * quarter of the dial the twelve-hour face gets.
     */
    var dialMarks = 12
        set(value) {
            field = value
            invalidate()
        }

    /** Whether the sixty small ticks between the hours are drawn. */
    var minuteMarks = true
        set(value) {
            field = value
            invalidate()
        }

    /** Which hours carry a mark, given how many were asked for. */
    private fun markedHours(): List<Int> = ChapterRing.markedHours(hoursOnDial, dialMarks)

    /**
     * The minute and hour marks, on a ring rather than along the edge.
     *
     * The marks used to be laid on the outline, which on a square is a
     * choice and on a triangle is a mess: they crowd towards each corner
     * and thin out along each side, so the eye reads the shape instead of
     * the hour, and the numerals — which sit on a circle — no longer stand
     * over their own marks.
     *
     * The chapter ring is a circle on every face now. The polygon is the
     * case, and a case is not the part you read the time off.
     */
    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val hours = markedHours()
        if (minuteMarks) {
            for (i in 0 until 60) {
                val angle = i / 60f * 360f
                val b = apothemRadius()
                // A minute tick that lands on a marked hour becomes the
                // hour's own mark, on a twelve-hour face where the two
                // rings coincide. Everywhere else the hours get a mark of
                // their own below.
                val isMajor = hoursOnDial == 12 && i % 5 == 0 && (i / 5) in hours
                val paint = if (isMajor) tickPaint else minorTickPaint
                paint.strokeWidth =
                    (if (isMajor) r * 0.018f else r * 0.008f) * faceScale()
                val outerLen = (if (isMajor) r * 0.08f else r * 0.045f) * faceScale()
                val from = pointAt(cx, cy, angle, b * 0.97f - outerLen)
                val to = pointAt(cx, cy, angle, b * 0.97f)
                canvas.drawLine(from.x, from.y, to.x, to.y, paint)
            }
        }
        // The hours themselves, wherever the minute ring did not already
        // draw them: on a dial with no minute ticks, and on any dial whose
        // hours do not fall on fifths of the circle.
        //
        // Where they go depends on why the ring did not draw them. With the
        // minute ticks switched off the chapter ring is empty, so the hours
        // take it over — the mark stays where it was and only its smaller
        // neighbours disappear. With the minute ticks on but the hours off
        // the fifths, the outer ring is occupied, so the hours go inside it,
        // on a ring of their own that clears the numerals.
        val ringDrewThem = minuteMarks && hoursOnDial == 12
        if (!ringDrewThem) {
            for (i in hours) {
                val angle = i.toFloat() / hoursOnDial * 360f
                val b = apothemRadius()
                tickPaint.strokeWidth = r * 0.018f * faceScale()
                val outer = if (minuteMarks) b * 0.87f else b * 0.97f
                val len = if (minuteMarks) b * 0.07f else r * 0.08f * faceScale()
                val from = pointAt(cx, cy, angle, outer - len)
                val to = pointAt(cx, cy, angle, outer)
                canvas.drawLine(from.x, from.y, to.x, to.y, tickPaint)
            }
        }
    }

    private fun drawNumerals(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (numeralStyle == NumeralStyle.NONE) return
        numeralPaint.textSize = numeralTextSize(r)
        for (hour in visibleNumeralHours()) {
            if (isNumeralFallen(hour)) continue
            val pos = numeralPosition(hour, cx, cy, r)
            val defaultColor = numeralPaint.color
            // Only on a clock. Tapping a numeral to mark it is already
            // barred wherever there is a chrono provider, but the marks
            // themselves were still painted — so the hours you had picked
            // out on C0 came up in the accent colour on the face you were
            // winding an alarm onto, looking like part of the answer.
            if (chronoProvider == null && selectedHours.contains(hour)) {
                numeralPaint.color = selectedColor
            }
            val baseline = pos.y - (numeralPaint.ascent() + numeralPaint.descent()) / 2f
            canvas.drawText(numeralLabel(hour), pos.x, baseline, numeralPaint)
            numeralPaint.color = defaultColor
        }
    }

    /**
     * Alarms are moments, so they get a dot just outside the rim — nothing
     * on the face itself, nothing implying a span. Calendar events, which
     * really do occupy time, get the Sectograph wedge instead.
     */
    private fun drawAlarmMarkers(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (mark in alarmMarkers) {
            val at = markCenter(cx, cy, mark.angle)
            alarmMarkerPaint.color = DayNight.markColor(theme, mark.pm)
            alarmMarkerPaint.alpha = 230
            canvas.drawCircle(at.x, at.y, r * 0.022f, alarmMarkerPaint)
        }
    }

    /** The dots' outlines, in a second pass so the blending never eats them. */
    private fun drawAlarmMarkerRings(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (mark in alarmMarkers) {
            if (!mark.fromCalendar) continue
            val at = markCenter(cx, cy, mark.angle)
            markRingPaint.color = ClockThemes.contrastInk(theme)
            markRingPaint.strokeWidth = r * 0.009f
            canvas.drawCircle(at.x, at.y, r * 0.028f, markRingPaint)
        }
    }

    private fun markCenter(cx: Float, cy: Float, angle: Float) =
        pointAt(cx, cy, angle, boundaryRadius(angle) * 1.055f)


    /**
     * How much of a wedge is still ahead of the minute hand, 1 down to 0.
     *
     * An event that has run its course leaves the dial rather than sitting
     * there looking pending — and it goes out gradually, as the hand crosses
     * it, so the fading itself reads as "this is happening now".
     */
    private fun arcRemaining(arc: DialArc): Float {
        // An empty range means "no times given"; a negative start means an
        // event that began yesterday, which is a real thing and used to
        // switch the fade off entirely — the guard read one as the other.
        if (arc.endMinute <= arc.startMinute) return 1f
        // The time the dial is *showing*, offset and all. Carry the hands
        // forward and the day's events are consumed under your finger,
        // which is the whole of what a Sectograph dial is for: one turn of
        // a hand and you have seen what is coming.
        val now = shownMinuteOfDay()
        // Plain comparisons, not modular ones: these minutes belong to a
        // named day, and an event whose start is still ahead of the shown
        // time has simply not begun. Winding past midnight is not this
        // method's problem — the host rebuilds the marks for the new date,
        // and until it does everything reads as still to come, which is
        // exactly what tomorrow's events are.
        return when {
            now <= arc.startMinute -> 1f
            now >= arc.endMinute -> 0f
            else -> 1f - (now - arc.startMinute) / (arc.endMinute - arc.startMinute)
        }
    }

    /** Minutes past midnight of the time the dial is showing. */
    private fun shownMinuteOfDay(): Float {
        cal.timeInMillis = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60f +
            cal.get(java.util.Calendar.MINUTE) + cal.get(java.util.Calendar.SECOND) / 60f
    }

    /**
     * The part of a wedge still ahead of the hand.
     *
     * A wedge is eaten from its start, not faded as a whole: the hand
     * crosses it and it gets shorter behind, so what is left on the face is
     * literally the time left. Fading the whole thing said "this is going
     * away" without saying how much of it had gone.
     */
    private fun buildArcPath(arc: DialArc, cx: Float, cy: Float, left: Float) {
        val eaten = 1f - left.coerceIn(0f, 1f)
        val from = arc.start + arc.sweep * eaten
        val sweep = arc.sweep * left.coerceIn(0f, 1f)
        val steps = max(2, (kotlin.math.abs(sweep) / 3f).toInt())
        alarmMarkerPath.reset()
        // Inner edge outward, then back along the outer edge.
        for (i in 0..steps) {
            val a = from + sweep * i / steps
            val p = pointAt(cx, cy, a, boundaryRadius(a) * 0.885f)
            if (i == 0) alarmMarkerPath.moveTo(p.x, p.y) else alarmMarkerPath.lineTo(p.x, p.y)
        }
        for (i in steps downTo 0) {
            val a = from + sweep * i / steps
            val p = pointAt(cx, cy, a, boundaryRadius(a) * 0.965f)
            alarmMarkerPath.lineTo(p.x, p.y)
        }
        alarmMarkerPath.close()
    }

    /**
     * Full strength while an event is still ahead, dimmer once it has
     * started — so the shortening says how much is left and the dimming
     * says it is under way. Two facts, two channels.
     */
    private fun arcAlpha(left: Float): Int = if (left >= 1f) 230 else 150

    private fun drawEventArcs(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (arc in eventArcs) {
            val left = arcRemaining(arc)
            if (left <= 0f) continue
            alarmMarkerPaint.color = DayNight.markColor(theme, arc.pm)
            alarmMarkerPaint.alpha = arcAlpha(left)
            buildArcPath(arc, cx, cy, left)
            canvas.drawPath(alarmMarkerPath, alarmMarkerPaint)
        }
    }

    private fun drawEventArcOutlines(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (arc in eventArcs) {
            if (!arc.fromCalendar) continue
            val left = arcRemaining(arc)
            if (left <= 0f) continue
            markRingPaint.color = ClockThemes.contrastInk(theme)
            markRingPaint.alpha = if (left >= 1f) 255 else 165
            markRingPaint.strokeWidth = r * 0.008f
            buildArcPath(arc, cx, cy, left)
            canvas.drawPath(alarmMarkerPath, markRingPaint)
        }
        markRingPaint.alpha = 255
    }

    /**
     * Marks, drawn as light rather than as paint.
     *
     * Two events at the same hour used to be one event: the second wedge
     * covered the first and the dial simply lied about how busy the day was.
     * Drawn into a transparent layer with the colours adding, an overlap
     * brightens instead — green over blue comes out turquoise, and the rule
     * is one the eye already knows, so the user can predict it without being
     * told. The layer is what makes it work: added against the dial's own
     * face the marks would wash the face out too.
     */
    private fun drawMarksLayer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        alarmMarkerPaint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.ADD
        )
        if (eventArcs.isNotEmpty()) drawEventArcs(canvas, cx, cy, r)
        if (alarmMarkers.isNotEmpty()) drawAlarmMarkers(canvas, cx, cy, r)
        alarmMarkerPaint.xfermode = null
        canvas.restoreToCount(saved)
        // Outlines after the layer, at full strength: they say "today only",
        // and a ring that brightened along with an overlap would say it in a
        // different voice every time two things collided.
        if (eventArcs.isNotEmpty()) drawEventArcOutlines(canvas, cx, cy, r)
        if (alarmMarkers.isNotEmpty()) drawAlarmMarkerRings(canvas, cx, cy, r)
    }

    /**
     * The sky complication: one glyph, low on the face, showing what is
     * actually up there — the sun while the sun is up, the moon and its
     * phase once it has set.
     *
     * There is only ever one of them, in one place, because they are two
     * states of a single thing and not two features. Earlier this was drawn
     * as a second token stacked over the moon, which was both wrong and
     * ugly.
     *
     * The point of the sun half is the person working indoors with no
     * window: the dial tells them the hour, and this tells them whether the
     * hour is a light one. Which needs a real sunrise, so it needs a
     * location. Without one the app does not guess — it shows the moon,
     * whose phase is arithmetic that works anywhere on Earth.
     *
     * It reads the time the dial is *displaying* rather than the wall clock:
     * a fixed little face shows its own hour, and the big dial being wound
     * shows whatever the hands are on this instant. So the sun sets under
     * your finger as you carry the hour hand forward, which is the only way
     * to watch a whole day go past without waiting for one.
     */
    private fun drawMoonPhase(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (isMoonFallen()) return
        moonRimPaint.strokeWidth = r * 0.008f
        SkyGlyph.draw(
            canvas, cx, cy + apothemRadius() * 0.45f, r * 0.07f,
            moonLitPaint, moonDarkPaint, moonRimPaint, shownTimeOfDayMs(),
            withPhase = moonPhaseShown
        )
    }

    // ------------------------------------------------------ the solar system

    /** Whether the planets have the dial. */
    private var orreryUp = false

    /**
     * When the fade between hands and planets started, or [NEVER] if it
     * has not.
     *
     * The sentinel is not decoration. Zero would mean "the fade began at
     * uptime zero", and uptime is measured from the last time the phone was
     * switched on — so for the first half second after a reboot the dial
     * would come up midway through a fade nobody asked for, with the
     * planets showing and the hands half gone.
     */
    private var orreryChangedAt = NEVER

    /**
     * Where the sky has been wound to, and how it gets there — see
     * [SkyWinding].
     *
     * It stays where it is left. The hands spring back because a clock
     * that is not showing the time is broken; the sky does not, because
     * the whole reason to move it is to arrive at a date and read what is
     * there, and a spring would snatch the answer away at the moment of
     * finding it. Closing the sky puts it back to now.
     *
     * And it travels rather than cutting. Two hundred years of dial
     * arriving in one frame is an edit; everything else on this clock
     * moves, and this runs on the same curve the hands use crossing
     * between the clock and the chronograph, so that it reads as the same
     * mechanism rather than as a second idea about motion.
     */
    private val winding = SkyWinding(transitionInterpolator, GLIDE_HOME_MS)

    /**
     * Told whenever the sky fades in or out, so the host can take its own
     * furniture off the dial — the world clock's bubbles, which otherwise
     * float over the planets.
     */
    var onSkyFade: ((Float) -> Unit)? = null
    private var reportedSkyFade = -1f

    /**
     * How far the solar system has been zoomed, 1 to [Orrery.MAX_ZOOM].
     *
     * The pinch means something different once the planets have the dial.
     * On a clock it makes the whole face bigger, which is a thing about the
     * screen; here it pushes the orbits outwards, which is a thing about
     * the solar system — and at the far end the Earth's orbit is the one on
     * the rim and the dial becomes a calendar of the year.
     */
    private var orreryZoom = 1f

    /**
     * The planets that have been knocked off their orbits.
     *
     * They fall like the hands do and roll about the case under the same
     * gravity. Kept as a set here as well as bodies in [debris] because the
     * drawing has to know not to go on drawing them in the sky: a planet
     * both in orbit and on the floor is two planets.
     */
    private val fallenPlanets = HashSet<Orrery.Body>()

    /** Whether the Sun is one of the things rolling about the case. */
    private var sunFallen = false

    /**
     * Which days of the shown year have something on them, and what.
     *
     * Handed in rather than looked up, because the dial has no business
     * knowing what a reminder is. Only used when the Earth's orbit has been
     * zoomed out to the rim, where each day of the year has a mark of its
     * own to hang a dot on.
     */
    var orreryBusyDays: Map<Int, String> = emptyMap()
        set(value) { field = value; invalidate() }

    private var grabbedBody: Orrery.Body? = null
    private var lastBodyLongitude = 0.0

    /** Where the Moon was standing when it let go of the mechanism. */
    private var detachedMoonLongitude = 0.0
    private var moonRejoinFrom = 0.0

    /**
     * When the Moon last began sliding back into the mechanism.
     *
     * Long ago, not zero. Zero means "at uptime zero", and uptime is
     * counted from the last time the phone was switched on — so for the
     * first seven hundred milliseconds after a reboot the Moon would be
     * drawn creeping in from longitude nought, and the dial would be asking
     * for frames to draw it with. The third time this trap has been walked
     * into in this file; the other two are [orreryChangedAt] and
     * `buttonsAnimStart`.
     */
    private var moonRejoinAt = -1_000_000L

    /** The chronograph hands' journey back to zero — see [glideChronoTo]. */
    private var chronoGlideFrom = 0L
    private var chronoGlideTo = 0L
    private var chronoGlideAt = NEVER

    /** The instant the solar system is showing. */
    internal fun orreryMs(): Long =
        displayNowMs() + (visualOffsetSeconds * 1000.0).toLong() + winding.windBack()

    /**
     * How far the sky is wound at this instant — the whole offset, or what
     * is left of it while it runs home.
     */

    /**
     * Carries the sky to a wound offset, travelling rather than cutting.
     *
     * The whole point of this dial is that time is a mechanism: carry a
     * planet and the others follow at the speed their own year demands. A
     * jump throws that away — the planets are simply somewhere else on the
     * next frame, and the thing that made the sky worth winding is the bit
     * you did not see. So every move that is not a finger on a planet goes
     * through here, eased in and out, and you watch the years go past.
     */
    internal fun glideOrreryTo(offsetMs: Long): Boolean {
        if (!winding.glideTo(offsetMs)) return false
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        kickTicker()
        return true
    }

    /** Sends the sky back to today, travelling rather than cutting. */
    internal fun glideOrreryHome(): Boolean = glideOrreryTo(0L)

    /** Whether the sky is on its way back to today. */
    internal fun orreryGlidingHome(): Boolean = winding.travelling()

    /**
     * A finger held still on the open sky.
     *
     * On a planet it is asking what that is; on empty sky it is asking
     * where the next alignment is. One gesture, and which of the two it
     * means depends only on what is under it.
     *
     * Out of the gesture listener so it can be asked directly: a long press
     * delivered through a detector is a thing that either happens or
     * silently does not, and a test that cannot tell those apart proves
     * nothing about either branch.
     */
    internal fun pressAndHoldOnSky(x: Float, y: Float) {
        if (!orreryShowing()) return
        val held = grabbedBody
        if (held != null) {
            showMarkBubble(bodyName(held), x, y)
        } else {
            leapToNextAlignment()
        }
    }

    /**
     * How much of the dial the planets have, 0 to 1.
     *
     * Clocked off [SystemClock.uptimeMillis] like everything else that
     * moves in this app, because the animator duration scale on the phone
     * this is written for is turned off and a ValueAnimator would arrive
     * fully faded on its first frame.
     */
    internal fun orreryFade(): Float {
        if (orreryChangedAt == NEVER) return if (orreryUp) 1f else 0f
        val t = ((SystemClock.uptimeMillis() - orreryChangedAt) / ORRERY_FADE_MS).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return if (orreryUp) eased else 1f - eased
    }

    /** Whether the planets are showing at all — as against fully faded out. */
    internal fun orreryShowing(): Boolean = orreryFade() > 0.01f

    /** Opens or closes the sky. Also the accessibility action. */
    internal fun toggleOrrery() {
        // The frame loop works out its delay when a frame is posted, so a
        // fade started now would get whatever was already in the queue —
        // up to a whole second of nothing, and then the far end. The file
        // says as much above [kickTicker]; the sky was not listening.
        kickTicker()
        if (orreryUp) {
            // Through the one function that knows what closing means. This
            // used to keep its own copy of the tidying-up, and the copy fell
            // behind: shutting the sky left the planets that had been
            // knocked out of it lying in a case with no solar system in it.
            closeOrrery()
        } else {
            orreryUp = true
            orreryChangedAt = SystemClock.uptimeMillis()
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    private fun closeOrrery() {
        if (!orreryUp) return
        orreryUp = false
        orreryChangedAt = SystemClock.uptimeMillis()
        grabbedBody = null
        winding.reset()
        orreryZoom = 1f
        // The planets go back in their orbits with the sky. They are only
        // on the floor of a case that is showing a solar system.
        fallenPlanets.clear()
        sunFallen = false
        debris.bodies.removeAll { it.kind == DialDebris.Kind.PLANET }
    }

    /**
     * Puts the sky away because the dial is no longer being looked at.
     *
     * Walking off to the calendar and coming back to a solar system still
     * standing on a date wound to three days ago reads as a clock that has
     * stopped. It is a thing you open, look at, and leave.
     *
     * [afterMs] is how long this dial still has to be on screen for. Going
     * to a chronograph, the two cards dissolve into one another, and this
     * one is in that picture the whole way through: shutting the sky at the
     * top of it put the hands back on a dial the eye is still looking at,
     * so what you saw was a solar system, a flash of clock, and only then
     * the chronograph arriving. Now the sky simply leaves with the card
     * that was carrying it, and there is one movement instead of two.
     */
    internal fun leaveOrrery(afterMs: Long = 0L) {
        if (!orreryUp) return
        if (afterMs > 0L) {
            removeCallbacks(shutTheSky)
            postDelayed(shutTheSky, afterMs)
            return
        }
        shutTheSky.run()
    }

    private val shutTheSky = Runnable {
        if (!orreryUp) return@Runnable
        closeOrrery()
        // Shut, not faded shut. By the time this runs the card is either
        // gone or going, and a fade begun now would be a fade nobody sees
        // finish — it would be a sky reappearing over the wrong card.
        orreryChangedAt = NEVER
        invalidate()
    }

    /**
     * Where the Moon is drawn.
     *
     * Three answers. Normally it is wherever the mechanism has it. While
     * something other than the Earth is being carried it has let go — see
     * [Orrery.moonFollows] — and it holds the angle it had when the hand
     * went down. And for a moment after the hand comes off it slides the
     * short way back to where it should be, because a body that vanishes
     * from one side of its orbit and reappears on the other looks like a
     * dropped frame rather than a gear re-engaging.
     */
    internal fun orreryMoonLongitude(): Double {
        val live = Orrery.longitude(Orrery.Body.MOON, orreryMs())
        if (grabbedBody != null && !Orrery.moonFollows(grabbedBody)) return detachedMoonLongitude
        val t = (SystemClock.uptimeMillis() - moonRejoinAt) / MOON_REJOIN_MS
        if (t in 0f..1f) {
            val eased = t * t * (3f - 2f * t)
            return Orrery.wrap(moonRejoinFrom + Orrery.shortWay(moonRejoinFrom, live) * eased)
        }
        return live
    }

    /**
     * Which bodies stand in one line at the instant being shown — of the
     * ones that are up there at all.
     *
     * [Orrery] does the arithmetic for all eight and knows nothing about
     * who had been discovered. Wound back to the Bronze Age it went on
     * reporting Neptune, Uranus and Saturn in a row and the dial went on
     * drawing the line — an alignment of three planets over a sky with
     * three bodies in it, two of which nobody would find for four thousand
     * years.
     */
    internal fun orreryAligned(): List<Orrery.Body> {
        val at = orreryMs()
        val unknown = SkyAge.unknownAt(at)
        val up = Orrery.aligned(at, ALIGNMENT_ARC).filterNot { it in unknown }
        // Three is what makes a line. Two planets are always in a line with
        // the sun and it means nothing.
        return if (up.size >= 3) up else emptyList()
    }

    /**
     * Jumps to the next date on which three or more planets stand in one
     * line, and says whether it found one.
     *
     * Dragging a planet about will stumble on an alignment eventually, and
     * "eventually" is the problem — three of them inside twelve degrees is
     * a thing that happens a couple of times a decade, and a finger looking
     * for one by hand is a finger that gives up. So the dial will go and
     * find the next one: a long press, a search forward through forty
     * years a day at a time, and the date it lands on has the line drawn
     * across it.
     *
     * Forty years because that is what can be searched inside the fifth of
     * a second a long press already costs. Beyond it, keep pressing.
     */
    internal fun leapToNextAlignment(): Boolean {
        val from = orreryTargetMs() + CivilDays.DAY_MS
        val found = Orrery.nextAlignment(
            from, ALIGNMENT_ARC, atLeast = 3, limitDays = 40 * 365
        ) ?: return false
        // Travelling, like every other move that is not a finger on a
        // planet: the years going past is the thing worth seeing.
        return glideOrreryTo(orreryTargetOffsetMs() + (found - orreryTargetMs()))
    }

    /**
     * For the tests: finishes whatever journey the sky is on, at once.
     *
     * The tests are about where it arrives; whether it eases on the way is
     * a separate question with a test of its own.
     */
    internal fun settleOrreryForTest() = winding.settleForTest()

    /** For the tests: whether the sky is on its way somewhere. */
    internal fun orreryTravellingForTest(): Boolean = winding.travelling()

    /** Whether the Moon has currently let go of the mechanism. */
    internal fun orreryMoonDetached(): Boolean =
        grabbedBody != null && !Orrery.moonFollows(grabbedBody)

    /** Which body a finger is holding, if any. */
    internal fun orreryGrabbedForTest(): Orrery.Body? = grabbedBody

    /**
     * For the tests: what the sky hands over for a touch at this spot.
     *
     * Not through [onTouchEvent], for once, and for a reason worth writing
     * down: anything lying in the case takes a touch before the sky does,
     * and a planet knocked out of its orbit starts its fall from exactly
     * the place in the orbit this test aims at. So the whole-stack version
     * of the test was answered by the debris every time and would have
     * passed with the sky's own hit test wired up wrong.
     */
    internal fun grabBodyNearForTest(x: Float, y: Float): Boolean = grabBodyNear(x, y)

    /** For the tests: attaching and detaching, without a window to do it. */
    internal fun onAttachedToWindowForTest() = onAttachedToWindow()
    internal fun onDetachedFromWindowForTest() = onDetachedFromWindow()

    /** For the tests: what the little bubble is saying, if anything. */
    internal fun markBubbleForTest(): String? = bubbleText

    /** For the tests: taps the winding crown. */
    internal fun crownTapForTest() = handleCrownTap()

    /** For the tests: the chronograph reading the dial is drawing. */
    internal fun chronoShownForTest(): Long = chronoDisplayMs() ?: 0L

    /**
     * For the tests: where the digital readout starts, in pixels down the
     * view.
     *
     * From the same expression the drawing uses, so a test looking for the
     * row under it is looking where the row actually is rather than where
     * the test thinks it should be.
     */
    internal fun readoutTopForTest(): Float {
        val r = dialRadius()
        val digitH = r * 0.13f
        return min(height / 2f + boundaryRadius(180f) + digitH * 0.4f, height - digitH * 1.6f)
    }

    /** How far the sky is zoomed, 1 to [Orrery.MAX_ZOOM]. */
    internal fun orreryZoomForTest(): Float = orreryZoom

    /** For the tests: which planets are lying in the case. */
    internal fun fallenPlanetsForTest(): Set<Orrery.Body> = fallenPlanets

    /** For the tests: some piece lying in the case, to pick up. */
    internal fun debrisNearestForTest(): DialDebris.Body? =
        // A planet, not the Sun: the Sun is a PLANET body with no planet on
        // it, and a test that picked it up was asserting things about null.
        debris.bodies.firstOrNull { it.kind == DialDebris.Kind.PLANET && it.planet != null }

    /**
     * For the tests: puts a named piece in the hand.
     *
     * Aimed rather than reached for: a touch at a planet's position picks
     * up whatever is nearest, and with the hands on the floor too that is
     * often a hand.
     */
    internal fun carryForTest(b: DialDebris.Body) {
        debris.carried = b
    }

    /** For the tests: what the finger currently has hold of in the case. */
    internal fun carriedForTest(): DialDebris.Body? = debris.carried

    /** For the tests: whether the Sun is on the floor with them. */
    internal fun sunFallenForTest(): Boolean = sunFallen

    /** Pinches the sky, the way two fingers would. */
    internal fun zoomOrrery(by: Float) {
        orreryZoom = (orreryZoom * by).coerceIn(1f, Orrery.MAX_ZOOM)
        invalidate()
    }

    /** For the tests: carries the sky on by a given stretch of time. */
    internal fun nudgeOrreryForTest(ms: Long) {
        winding.nudge(ms)
        invalidate()
    }

    /**
     * For the tests: puts the sky on a given year.
     *
     * By moving the offset rather than by turning a planet, because what
     * is being tested is what the date window says and not how anybody got
     * there — and reaching the year 3400 a planet at a time is a great
     * many revolutions.
     */
    internal fun windOrreryToYearForTest(year: Int) {
        val want = java.util.Calendar.getInstance().apply {
            // Before the year one a Calendar wants the era said out loud
            // and the year given as a positive count back from it; set a
            // negative year and it quietly lands on the same year AD.
            if (year < 1) {
                set(java.util.Calendar.ERA, java.util.GregorianCalendar.BC)
                set(1 - year, 5, 15, 12, 0, 0)
            } else {
                set(year, 5, 15, 12, 0, 0)
            }
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        winding.nudge(want - orreryMs())
        invalidate()
    }

    /**
     * For the tests: carries [body] [degrees] round its orbit, the way a
     * finger would, and hands back the date that lands under it.
     */
    internal fun windOrreryForTest(body: Orrery.Body, degrees: Double): Long {
        val from = Orrery.longitude(body, orreryMs())
        winding.nudge(Orrery.stepMs(body, from, Orrery.wrap(from + degrees)))
        invalidate()
        return orreryMs()
    }

    /** Where the sky token sits, which is also where the sky opens. */
    private fun skyTokenX(): Float = width / 2f
    private fun skyTokenY(): Float = height / 2f + apothemRadius() * 0.45f

    /** For the tests: where the door to the sky is, so it can be pressed. */
    internal fun skyTokenXForTest(): Float = skyTokenX()
    internal fun skyTokenYForTest(): Float = skyTokenY()

    /**
     * Whether a touch landed on the sky token.
     *
     * A good deal wider than the glyph is drawn. It is seven hundredths of
     * the dial across — under five millimetres on the phone this is written
     * for — and a target that size answers about half the taps aimed at it.
     */
    internal fun skyTokenAt(x: Float, y: Float): Boolean {
        if (!orreryEnabled || !showMoonPhase) return false
        val r = dialRadius()
        return hypot(x - skyTokenX(), y - skyTokenY()) < r * 0.16f
    }

    /**
     * Whether a touch landed on the Sun, which is the way back to today.
     *
     * Wider than the Sun is drawn, and it can afford to be: the innermost
     * ring is a fifth of the way out and nothing else lives in between.
     */
    internal fun sunAt(x: Float, y: Float): Boolean =
        orreryShowing() &&
            hypot(x - width / 2f, y - height / 2f) < dialRadius() * 0.12f

    /**
     * Takes hold of a planet, if there is one under the finger.
     *
     * The Moon's position is remembered here rather than read later,
     * because the whole point of letting go is that it stops being a
     * function of the time — and a moment later the time will have moved.
     */
    private fun grabBodyNear(x: Float, y: Float): Boolean {
        if (!orreryShowing()) return false
        val body = OrreryDial.bodyAt(
            x, y, width / 2f, height / 2f, dialRadius(), orreryMs(),
            orreryMoonLongitude(), orreryZoom, fallenPlanets
        ) ?: return false
        grabbedBody = body
        // A hand on a planet ends the journey home wherever it has got to.
        winding.stopHere()
        // From whatever that body actually goes round — which for the Moon
        // is the Earth and not the middle of the dial. This was measured
        // from the middle here and from the Earth on the very next move, so
        // taking hold of the Moon booked the difference between two quite
        // different angles as a movement of the finger: up to half a turn
        // of the Moon's orbit in the first frame, which is a fortnight, and
        // the whole sky jumped. The Moon is the one body that is always
        // sitting on top of another one, so it is the one that gets grabbed
        // by accident, which is why this looked like "they overlap and it
        // goes wrong there".
        detachedMoonLongitude = orreryMoonLongitude()
        lastBodyLongitude = fingerLongitude(body, x, y)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
        return true
    }

    /**
     * Which way the finger lies from whatever [body] goes round.
     *
     * The middle of the dial for a planet, the Earth for the Moon. Shared
     * by the grab and the drag because the two have to agree: they are
     * subtracted from one another every frame, and an angle measured from
     * one centre less an angle measured from another is not a movement.
     */
    private fun fingerLongitude(body: Orrery.Body, x: Float, y: Float): Double {
        val cx = width / 2f
        val cy = height / 2f
        if (body != Orrery.Body.MOON) return OrreryDial.longitudeOf(cx, cy, x, y)
        val earth = OrreryDial.positionOf(
            Orrery.Body.EARTH, cx, cy, dialRadius(), orreryMs(),
            orreryMoonLongitude(), orreryZoom
        )
        return OrreryDial.longitudeOf(earth.x, earth.y, x, y)
    }

    /**
     * Carries the held body round, which is to say winds the whole system.
     *
     * The finger's angle is measured from the middle of the dial for a
     * planet and from the Earth for the Moon, since that is what each of
     * them actually goes round.
     */
    private fun dragBodyTo(x: Float, y: Float) {
        val body = grabbedBody ?: return
        val from = fingerLongitude(body, x, y)
        winding.nudge(Orrery.stepMs(body, lastBodyLongitude, from))
        lastBodyLongitude = from
        invalidate()
    }

    /** Lets go, and starts the Moon sliding back if it had let go too. */
    private fun releaseBody() {
        val body = grabbedBody ?: return
        if (!Orrery.moonFollows(body)) {
            moonRejoinFrom = detachedMoonLongitude
            moonRejoinAt = SystemClock.uptimeMillis()
            kickTicker()
        }
        grabbedBody = null
        invalidate()
    }

    /**
     * The date the solar system is standing on, in the dial's own date
     * style — the same words the date window uses, so winding a planet
     * moves a date that already looks familiar.
     */
    internal fun orreryDateText(): String = dateTextAt(orreryMs())

    /**
     * The same date as bare digits, for a display that has no letters and
     * no oblique stroke in it.
     *
     * Spaces rather than an oblique stroke, because none of the three
     * displays owns one: the only punctuation they have is a colon, and a
     * colon between two numbers means a time. The sixteen-bar row turns
     * each space into a module with its middle dot lit; the seven-bar row
     * leaves it blank. The day and month keep the order the dial writes
     * them in, so it still reads the way the date window does.
     */
    internal fun orreryDateDigits(): String {
        cal.timeInMillis = orreryMs()
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val m = cal.get(Calendar.MONTH) + 1
        val y = signedYear()
        val first = if (dateDayFirst) d else m
        val second = if (dateDayFirst) m else d
        val script = orreryScript()
        // The whole date in one alphabet, day and month included. It used
        // to be the year alone, with the day and month left in Arabic
        // beside it, and that is two displays sharing a row: the joke is
        // that the sky has been wound somewhere the date is written
        // differently, not that a third of it has.
        if (script == OrreryYear.Script.ROMAN) {
            return "${Roman.of(first)} ${Roman.of(second)} " +
                OrreryYear.yearText(y, script)
        }
        // Before writing there is no date at all — not a blank one. An
        // empty string here is what stops the row being drawn, and every
        // caller checks it.
        if (script == OrreryYear.Script.NONE) return ""
        return String.format(
            Locale.US, "%02d/%02d/%s", first, second,
            OrreryYear.yearText(y, script)
        )
    }

    /**
     * Which alphabet the sky is writing its year in — see [OrreryYear].
     *
     * Read from the wound date rather than passed about, because three
     * separate things need it and they must agree: the string, the way the
     * string is drawn, and the test that asks whether they match.
     */
    internal fun orreryScript(): OrreryYear.Script {
        cal.timeInMillis = orreryMs()
        return OrreryYear.scriptFor(signedYear())
    }

    /**
     * The year on [cal] as a number that can be negative.
     *
     * A Calendar never reports one. Wind the sky back past the year one
     * and it hands over a cheerful positive year with a separate flag
     * saying which side of the epoch it is on, so a script chosen from
     * `get(YEAR)` alone sees 1251 where 1250 BC is, and writes it in
     * Roman. Which is what it did.
     */
    private fun signedYear(): Int {
        val year = cal.get(Calendar.YEAR)
        return if (cal.get(Calendar.ERA) == java.util.GregorianCalendar.BC) 1 - year else year
    }

    /**
     * What is worth knowing about the day being shown, if anything — and
     * in the voice of the century it is being shown in.
     *
     * Latin under a Roman date, because a caption in English under a row
     * of Roman numerals is the same fault the date itself had: half of it
     * in one voice and half in another.
     *
     * Nothing at all past three thousand, or before the year one. The
     * honest reason is that I have no alphabet for either — a Yautja
     * script and a hieroglyphic one are both things I would be inventing,
     * and inventing an alphabet is exactly what put noise on this screen
     * before. A sky that far out saying nothing is better than a sky that
     * far out saying it in English.
     */
    internal fun orreryCaption(): String? {
        val script = orreryScript()
        // An Egyptian date is a regnal year, and a regnal year without the
        // king whose year it is is not a date at all — "year fifteen" of
        // whom? So the one thing said under a hieroglyphic date is the
        // name, and it is said in our letters because there is no other
        // way to say it: writing Ramesses in hieroglyphs means drawing his
        // cartouche, which is a different sign for every king in the list
        // and is a book rather than a caption.
        if (script == OrreryYear.Script.EGYPTIAN) {
            val at = orreryMs()
            val year = SkyAge.yearOf(at)
            val date = scripts.egyptianDate(orreryMs())
            // The one thing Egypt did write down, over and over, for three
            // thousand years: the morning Sothis comes back. It is the
            // opening of the year and it arrives with the flood, and it
            // beats the king's name on the day it happens. There is no
            // certain Egyptian record of an eclipse at all — see
            // [EgyptianCalendar.sothicDayOfYear] — so this is the whole of
            // the Egyptian sky calendar, and it is a great deal more than
            // nothing.
            if (EgyptianCalendar.isSothicRising(date, year)) {
                return context.getString(R.string.egy_sothis)
            }
            return date.king?.name
        }
        if (script != OrreryYear.Script.ROMAN && script != OrreryYear.Script.DIGITS) {
            return null
        }
        return OrreryDial.caption(
            resources, orreryMs(),
            TimeZone.getDefault().getOffset(orreryMs()),
            orreryAligned(), cometsEnabled,
            latin = script == OrreryYear.Script.ROMAN
        )
    }

    /**
     * What to call a planet, in the voice of the century the sky is wound
     * to.
     *
     * Three thousand years ago that dot was Dilbat and it took another
     * three thousand to become Venus — see [SkyAge]. One function because
     * two places ask (a tap on a planet, and letting go of one that was
     * being carried) and a label that changed in one of them and not the
     * other would be worse than neither.
     */
    private fun bodyName(body: Orrery.Body): String =
        context.getString(OrreryDial.nameKeyOf(body, SkyAge.yearOf(orreryMs())))

    /** For the tests: what the bubble would say about a planet right now. */
    internal fun bodyNameForTest(body: Orrery.Body): String = bodyName(body)

    /**
     * And what to call a visitor, in the voice of the century the sky is
     * wound to — see [OrreryDial.cometNameKeyOf].
     */
    private fun cometName(comet: Comets.Comet): String =
        context.getString(OrreryDial.cometNameKeyOf(comet, SkyAge.yearOf(orreryMs())))

    /** The planets lying in the case, each in the colour it brought down. */
    private fun drawFallenPlanets(canvas: Canvas) {
        for (b in debris.bodies) {
            if (b.kind != DialDebris.Kind.PLANET) continue
            fallenPaint.color = b.colour
            canvas.drawCircle(b.x, b.y, b.halfLen, fallenPaint)
        }
    }

    /**
     * The face going black as the planets arrive.
     *
     * Space is black in every theme — a white dial with planets on it is a
     * diagram of the solar system, and this face is meant to be a window
     * — so the sky brings its own ground with it and the dial fades to it
     * over the same handover the hands fade out across. Laid over the face
     * rather than swapped for it, so the crossfade is one movement instead
     * of a cut.
     *
     * The rim is not touched: it is the case the thing is built into, and
     * a case does not change colour because of what is being shown inside
     * it.
     */
    private fun darkenToSky(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val fade = orreryFade()
        if (fade <= 0.01f) return
        voidPaint.color = skyTheme().face
        voidPaint.alpha = (255 * fade).toInt()
        if (dialShape == DialShape.CIRCLE) {
            canvas.drawCircle(cx, cy, r, voidPaint)
        } else {
            canvas.drawPath(facePath, voidPaint)
        }
    }

    private val voidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** For the tests: the palette the solar system is drawn in. */
    internal fun skyThemeForTest(): ClockTheme = skyTheme()

    /**
     * The colours the solar system is drawn in.
     *
     * Its own palette rather than the dial's, because everything on it is
     * read against black — the orbits, the day marks, the planets' own
     * rust and straw. Night mode still applies: the sky is a bright thing
     * on a dark face and turning the dial down for the bedroom has to turn
     * it down too.
     */
    private fun skyTheme(): ClockTheme {
        if (theme.dimmed) return ClockThemes.dim(ClockThemes.SKY)
        // A pale clock gets the blue sky and a dark one gets the black.
        // Black in a white case is a hole rather than a window, and the
        // blue is the honest version anyway: Venus at noon is a party
        // trick, not a contradiction, and every orrery ever built stood in
        // front of a daylit room.
        return if (ClockThemes.isPaleFace(theme)) ClockThemes.DAY_SKY else ClockThemes.SKY
    }

    /**
     * Where the wound date is sitting, so a finger can find it.
     *
     * Empty whenever there is no row on the glass — the sky is shut, or it
     * has been wound back past the invention of writing and there is no
     * date to press.
     */
    private val dateRow = RectF()

    /** For the tests: where the wound date ended up on the glass. */
    internal fun dateRowForTest(): RectF = RectF(dateRow)

    /** For the tests: whether a press there would land on the date. */
    internal fun dateRowAtForTest(x: Float, y: Float): Boolean = dateRowAt(x, y)

    /** For the tests: the instant the wound sky is standing at. */
    internal fun orreryMsForTest(): Long = orreryMs()

    /** Whether a touch landed on the wound date under the sky. */
    private fun dateRowAt(x: Float, y: Float): Boolean =
        orreryShowing() && !dateRow.isEmpty && dateRow.contains(x, y)

    /**
     * Winds the sky forward to the next day the sky does something.
     *
     * The date is the thing on this screen that says where in time you are,
     * so it is the thing to press to go somewhere else in time — and the
     * only somewhere worth offering is the next thing worth looking at. An
     * eclipse, a shower, an opposition, or the Moon going new or full,
     * whichever comes first; there is one within a fortnight of any date,
     * because the Moon sees to it.
     *
     * Forward and not back. A sky wound backwards has a "next" too, but the
     * whole gesture is "show me the next one", and a press that sometimes
     * went the other way would be a press with a mood.
     */
    internal fun leapToNextSkyEvent(): Boolean {
        // From where the sky is *going*, not from where it has got to. A
        // second press part way through a journey used to ask "what is the
        // next event after here", find the one already being travelled to,
        // and set off for it again from a little further along — so
        // tapping quickly looked like a dial that had jammed. Asked of the
        // destination, presses queue up: each one adds an event, and a
        // fast run of them goes several events forward at once.
        val from = orreryTargetMs()
        val today = CivilDays.dayOf(from, TimeZone.getDefault().getOffset(from))
        val next = SkyEvents.nextDay(today) ?: return false
        // Noon, so the day is unambiguous whatever the zone does to it.
        val at = (next * CivilDays.DAY_MS + CivilDays.DAY_MS / 2) -
            TimeZone.getDefault().getOffset(from)
        // Travelling, not jumping. The planets swing round their orbits at
        // the speeds their own years demand and you watch the weeks go by,
        // which is the same journey the sun's own press makes back to
        // today — and is most of what makes this a mechanism rather than a
        // date picker.
        return glideOrreryTo(orreryTargetOffsetMs() + (at - from))
    }

    /**
     * Where the sky is heading, as an instant and as an offset.
     *
     * The same as where it is when it is standing still. Everything that
     * decides *where to go next* asks these rather than [orreryMs], so
     * that a press landing part way through a journey adds to it instead
     * of arguing with it.
     */
    private fun orreryTargetOffsetMs(): Long =
        winding.targetOffsetMs()

    private fun orreryTargetMs(): Long =
        displayNowMs() + (visualOffsetSeconds * 1000.0).toLong() + orreryTargetOffsetMs()

    /** For the tests: the instant the sky is on its way to. */
    internal fun orreryTargetForTest(): Long = orreryTargetMs()

    private fun drawOrrery(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val fade = orreryFade()
        if (fade <= 0.01f) {
            dateRow.setEmpty()
            return
        }
        // The face's own outline, handed over so the sky is cut off at the
        // edge of whatever shape the dial is rather than at a circle that
        // happens to fit inside a hexagon.
        if (dialShape.sides < 3) {
            facePath.reset()
            facePath.addCircle(cx, cy, r, Path.Direction.CW)
        } else {
            buildFacePath(cx, cy)
        }
        OrreryDial.draw(
            canvas, cx, cy, r, skyTheme(), orreryMs(), fade,
            orreryMoonLongitude(), orreryAligned(), orreryMoonDetached(), grabbedBody,
            orreryZoom, orreryBusyDays.keys, fallenPlanets, sunFallen, cometsEnabled,
            facePath, zodiacShown, resources
        )
        // Under the dial rather than on it, in the same place and at the
        // same size as the chronograph's readout — which is where this
        // clock already puts a row of digits, so it needs no explaining.
        // On the face it was lying across the orbits, and half of Mars
        // spent the year behind it.
        val digitH = r * 0.13f
        val yTop = min(cy + boundaryRadius(180f) + digitH * 0.4f, height - digitH * 1.6f)
        // Where the row ended up, so a finger can find it. Recorded rather
        // than worked out twice: the row is placed against the bottom of
        // the face and against the bottom of the screen, whichever comes
        // first, and a hit box computed separately would be right on most
        // phones and wrong on the ones that matter.
        dateRow.set(0f, yTop - digitH * 0.45f, width.toFloat(), yTop + digitH * 1.75f)
        val keep = digitalPaint.alpha
        digitalPaint.alpha = (215 * fade).toInt()
        val script = orreryScript()
        val date = orreryDateDigits()
        // Nothing was written down before the wedges, so nothing is drawn.
        // The counters are cleared rather than left at whatever the last
        // legible century put in them, or a test asking "is there a date"
        // gets yesterday's answer.
        if (script == OrreryYear.Script.NONE) {
            scripts.nothingWritten()
            digitalPaint.alpha = keep
            // Nothing drawn, nothing to press.
            dateRow.setEmpty()
            return
        }
        if (script == OrreryYear.Script.EGYPTIAN || script == OrreryYear.Script.CUNEIFORM) {
            if (script == OrreryYear.Script.EGYPTIAN) {
                scripts.drawEgyptianDate(canvas, scriptFrame(), cx, yTop, digitH)
            } else {
                scripts.drawCuneiformDate(canvas, scriptFrame(), cx, yTop, digitH)
            }
            digitalPaint.alpha = keep
            orreryCaption()?.let {
                OrreryDial.drawCaption(canvas, cx, yTop + digitH * 1.45f, r, theme, it, fade, eraFace())
            }
            return
        }
        when (script) {
            // Seven bars can make ten digits and nothing else, so a year
            // in letters needs a display with more bars in it, not a
            // different typeface pushed through the same slot.
            //
            // Unless the year is older than the display is. A row of lit
            // bars is nineteen-seventies electronics, and a date from 1750
            // shown on one is the same sort of anachronism as Neptune over
            // Babylon — so before 1970 the date is set in type instead.
            OrreryYear.Script.ROMAN ->
                if (OrreryYear.isPrinted(SkyAge.yearOf(orreryMs()), script)) {
                    scripts.drawPrintedDate(canvas, scriptFrame(), date, cx, yTop, digitH)
                } else {
                    scripts.drawOtherScript(canvas, scriptFrame(), date, cx, yTop, digitH)
                }
            // Day and month on the sixteen-bar module, which can still
            // write a digit; the year on the star, which cannot write
            // anything anybody here can read.
            OrreryYear.Script.YAUTJA ->
                scripts.drawYautjaDate(canvas, scriptFrame(), date, cx, yTop, digitH)
            else -> drawSevenSegment(canvas, date, cx, yTop, digitH)
        }
        digitalPaint.alpha = keep
        orreryCaption()?.let {
            OrreryDial.drawCaption(canvas, cx, yTop + digitH * 1.45f, r, theme, it, fade, eraFace())
        }
    }

    /**
     * The time of day the dial is showing, wound offset and all: a fixed
     * face reports its own hour, a running one reports now, and one being
     * dragged reports where the hands actually are.
     */
    private fun shownTimeOfDayMs(): Long {
        val chrono = chronoDisplayMs()
        if (chrono != null) return chrono
        cal.timeInMillis = displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 3_600_000L +
            cal.get(java.util.Calendar.MINUTE) * 60_000L
    }

    /**
     * The date the dial is showing, wound offset and all.
     *
     * The hour hand has two days of travel in it, and carrying it round a
     * whole turn of the day left the date sitting on today as if nothing
     * had happened — so the one card that could show you what next Tuesday
     * holds still called it Monday. It moves with the hands now, which is
     * also the only way to see that you have gone a day at all.
     */
    internal fun dateTextForTest(): String = dateText()

    /** For the tests: how this dial would write the date of a given instant. */
    internal fun dateTextAtForTest(atMs: Long): String = dateTextAt(atMs)

    private fun dateText(): String =
        dateTextAt(displayNowMs() + (visualOffsetSeconds * 1000.0).toLong())

    /**
     * Any date, in whatever shape this dial writes dates in — number, text
     * or Roman, day first or month first.
     *
     * Split out for the solar system, which shows a date two centuries off
     * and should not invent a second way of writing one to do it.
     */
    private fun dateTextAt(atMs: Long): String {
        cal.timeInMillis = atMs
        val (number, text) = dateFormats()
        return when (dateFormatStyle) {
            DateFormatStyle.NUMBER -> number.format(Date(cal.timeInMillis))
            DateFormatStyle.TEXT -> text.format(Date(cal.timeInMillis))
            DateFormatStyle.ROMAN -> DateShape.roman(
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.YEAR),
                dateDayFirst
            )
        }
    }

    /** For the tests: the date string the last frame actually painted. */
    internal fun dateShownForTest(): String = datePainted

    /**
     * And how many frames have painted one at all.
     *
     * Counted as well as remembered, because the two questions come apart
     * exactly where the interesting failure is. A date knocked off the
     * dial leaves the last string it showed sitting in [datePainted]
     * forever — so a test that reads only the string cannot tell "frozen
     * because it is lying in the case" from "drawn again, and the same
     * because the wind was thrown away when the mechanism went". The
     * count can: a fallen date is not drawn, so the number stops.
     */
    internal fun datePaintsForTest(): Int = datePaints

    private var datePainted = ""
    private var datePaints = 0

    private fun drawDate(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (isDateFallen()) return
        val text = dateText()
        datePainted = text
        datePaints++
        datePaint.textSize = r * 0.085f * faceScale()
        val baseline = cy - apothemRadius() * 0.42f - (datePaint.ascent() + datePaint.descent()) / 2f
        canvas.drawText(text, cx, baseline, datePaint)
    }

    // Seven-segment bits, ordered a(64) b(32) c(16) d(8) e(4) f(2) g(1).
    /**
     * How much of the bezel the screen may take, across.
     *
     * A chronograph with a screen in it is mostly screen — that is what
     * makes it one rather than a dial with a number written on it. This
     * was a fifth narrower, from before there was a panel round the
     * digits: a row of bars floating in the middle of a circle wants air
     * round it, and a piece of glass cut into the face does not.
     */
    private val LCD_ROW = 1.62f

    /** Scratch for the screen panel, so onDraw allocates nothing. */
    private val screenRect = RectF()
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The screen the readout is printed on.
     *
     * This face was a bezel with a row of lit bars floating in the middle
     * of it, which reads as a number written on a dial rather than as an
     * instrument with a display. What was missing is the thing round the
     * digits: a rectangle of darker glass with its corners taken off,
     * sitting a little below the surface — see [ScreenFrame], which owns
     * the arithmetic, including the part that is easy to miss, that a
     * rectangle inside a circle is limited by its corners and not by its
     * width.
     *
     * Three passes and nothing clever. A soft edge underneath so the panel
     * sits in the face instead of on it, the glass, and one quiet line
     * round the outside that is the edge of the cut.
     */
    private fun drawScreenFrame(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        digitH: Float,
        underH: Float,
        text: String,
        units: Array<String>?
    ) {
        val rowW = sevenSegmentWidth(text, digitH, units)
        val underW = if (underH > 0f) sevenSegmentWidth(text, underH, null) else 0f
        val wanted = maxOf(rowW, underW) / 2f + digitH * ScreenFrame.PAD_X
        // The second row hangs below the middle, so the panel is not
        // centred on the digits — it is centred on everything in it.
        val topEdge = cy - digitH / 2f - digitH * ScreenFrame.PAD_Y
        val bottomEdge =
            if (underH > 0f) cy + digitH * 0.72f + underH + digitH * ScreenFrame.PAD_Y
            else cy + digitH / 2f + digitH * ScreenFrame.PAD_Y
        val middle = (topEdge + bottomEdge) / 2f
        val fitted = ScreenFrame.fit(wanted, (bottomEdge - topEdge) / 2f, r)
        val halfW = fitted[0]
        val halfH = fitted[1]
        val corner = minOf(halfW, halfH) * 2f * ScreenFrame.CORNER

        // The recess. Widest and faintest first, the way the hands' own
        // shadows are drawn and for the same reason — a mask filter is one
        // call and is among the things a hardware canvas declines.
        val blur = digitH * 0.20f
        screenPaint.style = Paint.Style.FILL
        for (i in ScreenFrame.SPREAD.indices) {
            val out = blur * ScreenFrame.SPREAD[i]
            screenPaint.color = 0xFF000000.toInt()
            screenPaint.alpha = (ScreenFrame.WEIGHT[i] * 255f).toInt().coerceIn(0, 255)
            screenRect.set(
                cx - halfW - out, middle - halfH - out + blur * 0.35f,
                cx + halfW + out, middle + halfH + out + blur * 0.35f
            )
            canvas.drawRoundRect(screenRect, corner + out, corner + out, screenPaint)
        }

        screenRect.set(cx - halfW, middle - halfH, cx + halfW, middle + halfH)
        screenPaint.color = ScreenFrame.glass(theme.face)
        screenPaint.alpha = 255
        canvas.drawRoundRect(screenRect, corner, corner, screenPaint)

        screenPaint.style = Paint.Style.STROKE
        screenPaint.strokeWidth = maxOf(1f, r * 0.006f)
        screenPaint.color = theme.rim
        screenPaint.alpha = 0x90
        canvas.drawRoundRect(screenRect, corner, corner, screenPaint)
        screenPaint.style = Paint.Style.FILL
        screenPaint.alpha = 255
    }

    /**
     * How wide that row comes out, without drawing it.
     *
     * Split off the drawing rather than copied out of it, because the two
     * have to agree exactly: this is what centres the row and what the
     * screen behind it is cut to, and a second copy of the arithmetic
     * would be a panel that fits the digits until somebody changes a gap.
     */
    private fun sevenSegmentWidth(
        text: String,
        digitH: Float,
        units: Array<String>? = null
    ): Float {
        val digitW = digitH * 0.55f
        val gap = digitW * 0.45f
        val markW = digitW * 0.34f
        val colonW = digitW * 0.34f
        val tightGap = gap * 0.45f

        fun markWidth(m: String): Float = when (m) {
            "\"" -> markW * 1.55f
            "" -> 0f
            else -> markW
        }

        fun glyphWidth(c: Char): Float = when (c) {
            ':' -> colonW
            '/' -> colonW * 1.35f
            ' ' -> digitW * 0.7f
            else -> digitW
        }

        fun advanceAt(i: Int): Float {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            return glyphWidth(c) + if (c == ':' || next == ':') tightGap else gap
        }

        var totalW = 0f
        var g = 0
        for (i in text.indices) {
            if (text[i] == ':') {
                totalW += (units?.getOrNull(g)?.let { markWidth(it) } ?: 0f)
                g++
            }
            totalW += advanceAt(i)
        }
        totalW += (units?.getOrNull(g)?.let { markWidth(it) } ?: 0f)
        // The last glyph brings no gap after it.
        if (text.isNotEmpty()) {
            totalW -= advanceAt(text.length - 1) - glyphWidth(text.last())
        }
        return totalW
    }

    private fun drawSevenSegment(
        canvas: Canvas,
        text: String,
        cx: Float,
        top: Float,
        digitH: Float,
        units: Array<String>? = null
    ) {
        // This row is on neither of the other two displays, and says so:
        // a test asking which one the sky used gets an honest zero rather
        // than whatever the last row to use one left behind.
        scripts.nothingWritten()
        val digitW = digitH * 0.55f
        val gap = digitW * 0.45f
        val markW = digitW * 0.34f
        // The colon is punctuation, not a digit: it takes less room than one
        // and closes ranks with the groups either side of it.
        val colonW = digitW * 0.34f
        val tightGap = gap * 0.45f

        // Each unit mark sits in the top corner right after its group of
        // digits, superscript-style, before the colon: 01°:23′:45″.
        fun markWidth(m: String): Float = when (m) {
            "\"" -> markW * 1.55f
            "" -> 0f
            else -> markW
        }

        /** Width of one glyph plus the space after it. */
        fun advanceAt(i: Int): Float {
            val c = text[i]
            val w = when (c) {
                ':' -> colonW
                '/' -> colonW * 1.35f
                ' ' -> digitW * 0.7f
                else -> digitW
            }
            val next = text.getOrNull(i + 1)
            return w + if (c == ':' || next == ':') tightGap else gap
        }

        val totalW = sevenSegmentWidth(text, digitH, units)

        var x = cx - totalW / 2f
        digitalPaint.strokeWidth = digitH * 0.10f

        // The marks are annotations, not digits: thinner stroke, and only
        // the top quarter of the line.
        fun drawMark(m: String, at: Float) {
            if (m.isEmpty()) return
            val was = digitalPaint.strokeWidth
            digitalPaint.strokeWidth = was * 0.55f
            when (m) {
                "\u00b0" -> {
                    val wasStyle = digitalPaint.style
                    digitalPaint.style = Paint.Style.STROKE
                    canvas.drawCircle(at + markW * 0.42f, top + digitH * 0.01f, digitH * 0.085f, digitalPaint)
                    digitalPaint.style = wasStyle
                }
                "'" -> canvas.drawLine(
                    at + markW * 0.62f, top - digitH * 0.09f,
                    at + markW * 0.38f, top + digitH * 0.11f, digitalPaint
                )
                "\"" -> {
                    canvas.drawLine(
                        at + markW * 0.62f, top - digitH * 0.09f,
                        at + markW * 0.38f, top + digitH * 0.11f, digitalPaint
                    )
                    canvas.drawLine(
                        at + markW * 1.17f, top - digitH * 0.09f,
                        at + markW * 0.93f, top + digitH * 0.11f, digitalPaint
                    )
                }
            }
            digitalPaint.strokeWidth = was
        }

        var group = 0
        for (i in text.indices) {
            val c = text[i]
            when (c) {
                ':' -> {
                    units?.getOrNull(group)?.let {
                        drawMark(it, x - tightGap * 0.4f)
                        x += markWidth(it)
                    }
                    group++
                    canvas.drawPoint(x + colonW / 2f, top + digitH * 0.30f, digitalPaint)
                    canvas.drawPoint(x + colonW / 2f, top + digitH * 0.70f, digitalPaint)
                }
                '-' -> {
                    // Minus sign: the middle (g) segment on its own.
                    val w = digitalPaint.strokeWidth * 0.8f
                    val mid = top + digitH / 2f
                    canvas.drawLine(x + w, mid, x + digitW - w, mid, digitalPaint)
                }
                '/' -> {
                    // Not a segment any display owns — it is one stroke
                    // across the cell. A date needs something between its
                    // groups, and a blank space reads as the row having
                    // ended rather than as a separator.
                    val w = digitalPaint.strokeWidth * 0.8f
                    canvas.drawLine(
                        x + w, top + digitH - w, x + digitW - w, top + w, digitalPaint
                    )
                }
                ' ' -> Unit
                else -> {
                    val digit = c - '0'
                    if (digit in 0..9) {
                        drawSegments(canvas, Segments.seven(c), x, top, digitW, digitH)
                    }
                }
            }
            x += advanceAt(i)
        }
        units?.getOrNull(group)?.let { drawMark(it, x - gap * 0.55f) }
    }

    /**
     * Whether the readout draws its unlit bars. Only the face that is a
     * display does; on a dial the row is a caption and a caption with
     * grey eights behind it is a smudge.
     */
    private var readoutGhosts = false

    private fun drawSegments(canvas: Canvas, bits: Int, x: Float, y: Float, w: Float, h: Float) {
        if (readoutGhosts) {
            val keep = digitalPaint.alpha
            val keptColour = digitalPaint.color
            digitalPaint.color = theme.minorTick
            digitalPaint.alpha = (keep * 0.16f).toInt().coerceAtLeast(20)
            drawLitSegments(canvas, bits.inv(), x, y, w, h)
            digitalPaint.color = keptColour
            digitalPaint.alpha = keep
        }
        drawLitSegments(canvas, bits, x, y, w, h)
    }

    private fun drawLitSegments(canvas: Canvas, bits: Int, x: Float, y: Float, w: Float, h: Float) {
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


    /**
     * Whether the hands throw shadows, and where the sun is when they do.
     *
     * Off by default: it is a joke about the clock being a real object
     * lying in the sun, and a joke that is always on is furniture.
     */
    var handShadows = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * How many shadows the last frame laid down, hands and wreckage both.
     *
     * Counted rather than looked for in the pixels, because a shadow is
     * black on a face that is nearly black and "is there ink here" is not
     * a question the midnight theme answers usefully.
     */
    private var shadowsPainted = 0

    /** For the tests: shadows drawn on the last frame. */
    internal fun shadowsPaintedForTest(): Int = shadowsPainted

    /**
     * The instant the face is currently depicting.
     *
     * Not the same as "now", and the difference is the whole of a bug the
     * user found: on the screen where a time is wound with the hands, the
     * hands showed eleven at night while the shadows were still being cast
     * by the sun that was actually up, so a face drawn under a moon had
     * daylight shadows on it. The sky glyph had always used the wound time;
     * the shadows were asking the clock.
     *
     * The chronograph is the exception, and [chronoWrapsDay] is what tells
     * them apart: a wound alarm time is a time *of day* and belongs on
     * today's date, while a stopwatch reading is an elapsed duration and
     * means nothing as a time of day. A stopwatch lies in whatever light
     * the room is in, which is the light of now.
     */
    private fun depictedMs(): Long {
        val chrono = chronoDisplayMs()
        if (chrono == null || !chronoWrapsDay) {
            return displayNowMs() + (visualOffsetSeconds * 1000.0).toLong()
        }
        cal.timeInMillis = TimeKeeper.nowMs()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis + ((chrono % CivilDays.DAY_MS) + CivilDays.DAY_MS) % CivilDays.DAY_MS
    }

    /**
     * Which light is on this dial, if any — see [HandShadow.lightAt], which
     * knows the rule, and which the widget asks the same question of.
     */
    internal fun overheadLight(): HandShadow.Light? {
        if (!handShadows) return null
        return HandShadow.lightAt(shadowLatitude, shadowLongitude, depictedMs())
    }

    /**
     * Where the sun is when the sun is what is lighting the dial.
     *
     * Kept as its own question because "is the sun up" and "is there a
     * light" stopped being the same question the moment the moon started
     * casting shadows too.
     */
    internal fun sunOverhead(): SolarTime.Position? =
        overheadLight()?.takeIf { !it.moon }?.let {
            SolarTime.Position(it.altitudeDeg, it.azimuthDeg)
        }

    /** For the tests: the moon's light on the dial, if it has any. */
    internal fun moonOverheadForTest(): HandShadow.Light? =
        overheadLight()?.takeIf { it.moon }

    /**
     * The place the sun is worked out for.
     *
     * Set from outside rather than read here, because a view has no
     * business opening the preferences and this one is drawn in three
     * places that get their settings by three different routes.
     */
    var shadowLatitude = HandShadow.NO_FIX_LATITUDE
        set(value) {
            field = value
            invalidate()
        }

    var shadowLongitude = 0.0
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether the clock is lying on the ground or hanging on a wall.
     *
     * The engine was built on the first and only ever knew the first —
     * see [HandShadow.Surface]. A wall clock is not the same problem
     * turned round: twelve points up rather than north, the light comes
     * at the face rather than across it, and the sun going behind the wall
     * puts the shadow out however high it still is.
     */
    var shadowSurface = HandShadow.Surface.GROUND
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The three shadows, if there is a sun to cast them.
     *
     * Each hand is drawn again, in the same shape and at the same length,
     * shifted along the bearing the sun is not on by however far its own
     * height reaches. Nothing here is a blur or a gradient: a shadow of a
     * flat hand on a flat face in direct sun has an edge, and a soft one
     * would read as a glow.
     */
    private fun drawHandShadows(canvas: Canvas, cx: Float, cy: Float, r: Float, a: Angles) {
        shadowsPainted = 0
        val light = overheadLight() ?: return
        val strength = HandShadow.strength(light.altitudeDeg) * light.brightness
        if (strength <= 0.01f) return
        // Where the shadow runs, and how far for each unit of a hand's
        // height. On the ground both come out of the altitude alone; on a
        // wall the sun's direction has to be projected onto the face, and
        // it can come out pointing anywhere — including straight up, which
        // a ground clock's shadow never does.
        val onGround = shadowSurface == HandShadow.Surface.GROUND
        val wall = if (onGround) {
            null
        } else {
            HandShadow.onWall(light.altitudeDeg, light.azimuthDeg, shadowLatitude)
                ?: return
        }
        val away = wall?.first ?: HandShadow.bearing(light.azimuthDeg)

        drawDialDome(canvas, cx, cy, r, light)

        // Black by day, and by night the blue the eye insists moonlight is.
        // A shadow is the absence of light, so on a pale dial it is grey
        // and on a dark one it is nearly nothing — which is correct, and is
        // also why the alpha is not shy: it has to survive being laid on
        // midnight blue as well as on white.
        shadowPaint.color =
            if (light.moon) HandShadow.MOON_TINT else android.graphics.Color.BLACK
        // Clipped to the face, because the face is what the shadow falls
        // on. Without it a low sun slid the far end of each shadow over
        // the rim and onto the wallpaper, which is not a long shadow, it
        // is a shadow of something standing somewhere else.
        canvas.save()
        clipToFace(canvas, cx, cy, r)
        for (hand in arrayOf(Hand.HOUR, Hand.MINUTE, Hand.SECOND)) {
            if (!handIsOn(hand)) continue
            if (isFallen(hand)) continue
            val height = HandShadow.heightOf(hand)
            val reach = if (wall == null) {
                HandShadow.reach(height, light.altitudeDeg)
            } else {
                (height * wall.second).coerceAtMost(HandShadow.MAX_LENGTH)
            }
            if (reach <= 0f) continue
            val at = pointAt(cx, cy, away, reach * r)
            val width = r * widthOf(hand) * faceScale()
            // The haze is a band of its own width outside the hand's
            // outline, not a multiple of it, so a hair-thin second hand
            // gets the same soft edge the fat hour hand does.
            val haze = r * HandShadow.penumbra(HandShadow.heightOf(hand), light.altitudeDeg)
            // Widest and faintest first, so the haze goes down before the
            // core and the core is not veiled by its own halo.
            for (pass in HandShadow.SPREAD.indices) {
                shadowPaint.alpha =
                    (128 * strength * HandShadow.PASS_ALPHA[pass]).toInt().coerceIn(0, 255)
                drawHand(
                    canvas, at.x, at.y,
                    angleOf(hand, a),
                    handReach(hand),
                    r * tailOf(hand) * faceScale(),
                    width + 2f * haze * HandShadow.SPREAD[pass],
                    shadowPaint
                )
            }
            shadowsPainted++
        }
        drawDebrisShadows(canvas, r, away, strength, light)
        canvas.restore()
    }

    /**
     * And a shadow under whatever is lying in the case.
     *
     * A hand that falls off used to lose its shadow in the same frame,
     * which reads as the light going out rather than as the hand landing.
     * What a real one does is the opposite of nothing: an object lying
     * *on* a surface still has a shadow, a tight dark line right under it
     * where no light can get, and it is the shortest shadow that object
     * will ever cast rather than the absence of one.
     *
     * So the contact height is a fraction of the lowest mounted hand's.
     * The shadow does not vanish when a hand comes off — it collapses,
     * which is what the eye expects from something that has just landed.
     */
    private fun drawDebrisShadows(
        canvas: Canvas,
        r: Float,
        away: Float,
        strength: Float,
        light: HandShadow.Light
    ) {
        if (debris.bodies.isEmpty()) return
        val height = HandShadow.heightOf(Hand.HOUR) * CONTACT_HEIGHT
        val reach = if (shadowSurface == HandShadow.Surface.GROUND) {
            HandShadow.reach(height, light.altitudeDeg)
        } else {
            val wall = HandShadow.onWall(light.altitudeDeg, light.azimuthDeg, shadowLatitude)
                ?: return
            (height * wall.second).coerceAtMost(HandShadow.MAX_LENGTH)
        }
        if (reach <= 0f) return
        val rad = Math.toRadians(away.toDouble())
        val dx = (sin(rad) * reach * r).toFloat() * if (mirrored) -1f else 1f
        val dy = (-cos(rad) * reach * r).toFloat()
        val haze = r * HandShadow.penumbra(height, light.altitudeDeg)
        shadowPaint.color =
            if (light.moon) HandShadow.MOON_TINT else android.graphics.Color.BLACK
        for (b in debris.bodies) {
            // Only the pieces that are a stroke of something: the numerals
            // and the date are text, and a blurred copy of a word offset
            // by three pixels is not a shadow, it is a printing fault.
            if (b.kind == DialDebris.Kind.NUMERAL || b.kind == DialDebris.Kind.DATE) continue
            val bodyRad = Math.toRadians(b.angleDeg.toDouble())
            val dirX = sin(bodyRad).toFloat()
            val dirY = -cos(bodyRad).toFloat()
            for (pass in HandShadow.SPREAD.indices) {
                shadowPaint.alpha =
                    (128 * strength * HandShadow.PASS_ALPHA[pass]).toInt().coerceIn(0, 255)
                shadowPaint.strokeWidth =
                    b.strokeWidth + 2f * haze * HandShadow.SPREAD[pass]
                canvas.drawLine(
                    b.x + dx - dirX * b.halfLen, b.y + dy - dirY * b.halfLen,
                    b.x + dx + dirX * b.halfLen, b.y + dy + dirY * b.halfLen,
                    shadowPaint
                )
            }
            shadowsPainted++
        }
    }

    /**
     * Clips to the shape of the face, whatever shape that is.
     *
     * A round dial is a circle and a polygonal one is its polygon, and a
     * shadow that ran over the flat edge of a hexagonal face would be
     * wrong in a way that is easier to see than the round case.
     */
    private fun clipToFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (dialShape.sides < 3) {
            facePath.reset()
            facePath.addCircle(cx, cy, r, Path.Direction.CW)
        } else {
            // The same outline the face itself was drawn with a moment ago,
            // corners and rotation included, rather than a second guess at
            // what shape the dial is.
            buildFacePath(cx, cy)
        }
        canvas.clipPath(facePath)
    }

    /**
     * The face's own curve: a belly, and a bevel round its edge.
     *
     * Not a shadow cast by anything — it is the dial catching the light
     * across a surface that is not flat, and it is what makes the thing
     * read as an object sitting in the sun rather than a circle printed on
     * a screen. Both follow the same sun the hands do, so the whole
     * picture is lit from one place.
     *
     * It fades out as the sun climbs, because a dome lit from straight
     * overhead has no shaded side — which is the same reason the hands
     * stop casting anything at noon on the equator.
     */
    private fun drawDialDome(
        canvas: Canvas, cx: Float, cy: Float, r: Float, light: HandShadow.Light
    ) {
        DialDome.draw(
            canvas, cx, cy, r,
            HandShadow.domeStrength(light.altitudeDeg) * light.brightness,
            light.azimuthDeg.toFloat(), mirrored,
            if (light.moon) HandShadow.MOON_SHEEN else 0xFFFFFF
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

    companion object {
        /**
         * How high a fallen piece lies above the face, against the lowest
         * mounted hand.
         *
         * A fifth: it is lying *on* the dial rather than turning above it,
         * so what it has is a contact shadow — the tight dark line under
         * something that is touching a surface. Not nothing, which is what
         * it had, and which read as the light going out at the moment the
         * hand came off.
         */
        private const val CONTACT_HEIGHT = 0.2f

        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 1f

        /** A fade that has not started. See the field that holds it. */
        private const val NEVER = Long.MIN_VALUE

        /**
         * The far end of a countdown, and the stop the hands hit there.
         * The same day [Countdown.setTo] caps at, on this side of the glass
         * so the hands and the value agree about where the end is.
         */
        internal const val A_DAY_MS = 24L * 60 * 60 * 1000

        /** How long the hands take to give the dial up to the planets. */
        private const val ORRERY_FADE_MS = 520f

        /** And how long the Moon takes to slide back after being let go. */
        private const val MOON_REJOIN_MS = 700f

        /**
         * How long the sky takes to run back to today.
         *
         * Longer than the fade, because it is a longer journey and the
         * point of it is to be watched: the planets sweep back through
         * however many years were wound on, and that is the only way to see
         * how far you had gone.
         */
        private const val GLIDE_HOME_MS = 1400f

        /** And how long the chronograph hands take to run back to zero. */
        private const val CHRONO_GLIDE_MS = 700f

        /**
         * How wide an arc still counts as a line of planets.
         *
         * Twelve degrees. Wider and something is "aligned" most weeks,
         * which is the same as never saying it; much tighter and three of
         * them together is a once-in-a-lifetime event nobody will ever
         * stumble on by dragging Neptune about.
         */
        private const val ALIGNMENT_ARC = 12.0
        /**
         * How hard a knock has to be to shake something loose, in m/s²
         * beyond gravity.
         *
         * Was 14, which a phone being set down on a table clears easily —
         * so the hands came off on the way to the table rather than when
         * anybody meant them to. Two and a half g is a deliberate rap on
         * the glass: a movement of the hand, not the end of one.
         */
        private const val SHAKE_THRESHOLD = 25f

        /**
         * How many readings the smoothing gets before a knock can be
         * declared.
         *
         * At the rate this listener asks for, about a fifth of a second —
         * long enough for the smoothing to sit on the phone's real posture,
         * short enough that a genuine rap on the glass a moment after
         * arriving still counts.
         */
        private const val SETTLE_SAMPLES = 8

        /** For the tests: how long the sensor is given to settle. */
        internal fun settleSamplesForTest(): Int = SETTLE_SAMPLES

        /** For the tests: how hard a knock has to be. */
        internal fun shakeThresholdForTest(): Float = SHAKE_THRESHOLD
        private const val HOUR_LEN = 0.52f
        private const val MINUTE_LEN = 0.74f
        private const val SECOND_LEN = 0.82f
        private const val FAST_LEN = 0.30f
        /** How tall a bubble is allowed to get before the note is cut off. */
        private const val MAX_BUBBLE_LINES = 5

        private const val TRANSITION_MS = 700f

        /**
         * How long the crown and pushers take to fade in or out.
         *
         * The same as everything else in the gesture, and that is the whole
         * point of the constant: at five hundred against the dial's seven
         * the crown finished a fifth of a second before the face it sits
         * on, which reads as the crown having no fade rather than a shorter
         * one.
         */
        private const val BUTTONS_MS = TRANSITION_MS.toLong()

        /** How far a lap may drift from the truth before it is a fake. */
        private const val FAKE_LAP_TOLERANCE_MS = 400L
        /** Laps kept: seven show on the ladder, the rest wait in the list. */
        private const val MAX_LAPS = 40

        /** Rungs of the lap ladder, before it would reach the buttons. */
        private const val MAX_GHOST_LAPS = 7

        /** Corner marks, degrees-minutes-seconds style: 01°:23′:45″. */
        private val UNITS_CLOCK = arrayOf("\u00b0", "'", "\"")

        /** Nothing over the digits, where nothing needs saying. */
        private val UNITS_NONE = arrayOf("", "", "")

        /** Under the hour the tail is hundredths, left unmarked on purpose. */
        private val UNITS_STOPWATCH = arrayOf("'", "\"", "")

        /** Where the second hand's magnets pull, within its minute. */
        private val SECOND_DETENTS = longArrayOf(
            0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L, 45_000L, 60_000L
        )

        /** Room kept clear at the foot of the card for the button row. */
        private const val BUTTON_RESERVE_DP = 72f

        /**
         * What [MAX_GHOST_LAPS] rungs measure, in units of the first rung:
         * 1.35 of each rung's height as spacing, each 0.88 of the one above,
         * plus the last rung itself.
         */
        private const val LADDER_SPAN = 6.49f
    }
}
