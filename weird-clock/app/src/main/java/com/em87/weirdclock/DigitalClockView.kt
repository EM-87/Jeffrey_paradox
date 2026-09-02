package com.em87.weirdclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * The clock with no hands.
 *
 * Everything here follows from one thing the dial is not: a person who
 * chooses digits wants the time, big, from across a room, and wants it
 * without deciding anything. So there is no case, no rim, nothing to
 * knock over and nothing to press — the screen is the readout, and the
 * only questions it asks are the five on the digits page.
 *
 * What it does keep is that it is an object. A digital clock is not a
 * label with the time on it; it is a machine with a mechanism, and which
 * mechanism is the one choice worth offering: bars behind a mask, a stack
 * of hinged cards, or a set of drums. All three are drawn here from the
 * same cells — see [DigitalReadout] — so the arithmetic is decided once
 * and each idiom only has to say how a number appears and how it leaves.
 *
 * The unlit bars are drawn. That is not decoration: a segment display you
 * can only see the lit half of is a picture of a number, and the faint
 * eight behind the seven is the thing that says there is a display here at
 * all.
 */
class DigitalClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ------------------------------------------------------- the settings

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) {
            field = value
            invalidate()
        }

    /** How the digits are made. */
    var style: DigitStyle = DigitStyle.SEGMENT
        set(value) {
            if (field == value) return
            field = value
            drawn.clear()
            invalidate()
        }

    /** Which numerals they are made of. */
    var script: DigitScript = DigitScript.ARABIC
        set(value) {
            if (field == value) return
            field = value
            drawn.clear()
            invalidate()
        }

    var hour24: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var leadingZero: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether the two dots go out on every other second.
     *
     * Off by default. It is the one thing on this face that moves when
     * nothing has happened, and a clock that blinks at you across a dark
     * bedroom is the reason people put a sock over the alarm clock.
     */
    var blinkColon: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether the colon breathes instead of blinking.
     *
     * A blink is a square wave: on for a second, off for a second, which
     * is what a cheap clock does because a cheap clock has one bit to
     * spend on it. This is the other thing the same second can be spent
     * on — the dots swell and fade over the whole of it, so the face is
     * alive without anything on it ever being *gone*. Which also answers
     * the reason blinking is off by default: nothing here switches off in
     * a dark bedroom, it only leans.
     */
    var breathingColon: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showSeconds: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showDate: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Which of the two numbers in the date is the day. */
    var dateDayFirst: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Which city's time this is, or the phone's own when nobody says.
     *
     * The little world clocks are readouts on this face rather than
     * dials, and a readout that cannot be told which zone it is in is a
     * readout that can only ever say one thing.
     */
    var zone: java.util.TimeZone? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The other cities this clock is also showing, stacked under it.
     *
     * The face with no hands used to float them as little readouts over
     * the big one, which is the dial's idiom borrowed by a face that has
     * no business with it: somebody who chose a screenful of digits did
     * not choose six draggable toys, they want to know what time it is in
     * Tokyo. So they are a ladder under the time, in the same bars, the
     * way a chronograph stacks its laps.
     */
    var cities: List<WorldClocks.City> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether the day of the week goes beside the date.
     *
     * Its own switch rather than part of the date, because they answer
     * different questions: the date is which day of the month it is and
     * this is which day of the *week* — the one thing about today that a
     * number cannot tell you, since 27/08 says nothing until you have
     * counted. Every digital clock ever built has it.
     */
    var showWeekday: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * When the next alarm goes off, or nothing if none is armed.
     *
     * Handed in rather than looked up, because a view that reads the alarm
     * list is a view that has to be told when the alarm list changes and
     * has no way of finding out. What it answers is not "when is my alarm"
     * — the list is for that — it is "did I actually set one", which is
     * the question somebody asks at midnight with the light off.
     */
    var nextAlarmMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    /** A city under the digits, on the readouts that carry one. */
    var caption: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Draws a rounded panel behind the digits instead of filling the view.
     *
     * For a readout that sits on something that is not ours: a world-clock
     * bubble lying on the clock, or the home-screen widget lying on
     * somebody's wallpaper. Both have to read as an object on top of
     * something rather than as a hole cut in it, and both are the whole of
     * what they show — so this also takes the ladder of other cities off,
     * which is a list for a clock that has a screen to spare.
     */
    var chip: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Their alphabet, when it is wanted and could be loaded. */
    var yautja: Typeface? = null
        set(value) {
            field = value
            invalidate()
        }

    // ------------------------------------------------------------- paints

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val card = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The one engine every lit display goes through. */
    private val segments = SegmentPainter()

    /**
     * How thick the bars are, as a multiple of what the display was drawn
     * at — see [SegmentPainter.weight].
     */
    var weight: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether this clock has the screen to itself.
     *
     * Set when the phone goes on its side on the face that fills one — see
     * [Bedside]. It only changes how big the digits are allowed to get:
     * the card's own furniture is taken away by whoever owns the card, and
     * a view that hid other people's buttons would be a view reaching out
     * of its own window.
     */
    var fullScreen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * What the clock keeps when it has the screen to itself.
     *
     * Their own switches rather than the card's, because they are a
     * different question: somebody who wants the seconds on the card is
     * not thereby asking for them across a bedroom at three in the
     * morning. Both off by default — full screen was asked for as a way
     * of getting rid of things.
     */
    var bedsideSeconds: Boolean = false
        set(value) { field = value; invalidate() }

    var bedsideDate: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * A tap on the glass, when there is nothing else for a tap to do.
     *
     * The bedside clock has no buttons on it, and something has to bring
     * them back for somebody who does not know that a swipe is the way
     * out. A tap is that something; it is not offered while a bar can be
     * poked out or a drum turned, because those are what a tap means then.
     */
    var onTapped: (() -> Unit)? = null

    /** Whether the unlit bars are drawn faintly behind the lit ones. */
    var ghosts: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether a bar can be poked out with a finger.
     *
     * Off unless asked for. Somebody who chose digits chose them for
     * clarity, and a clock that quietly starts lying because a sleeve
     * brushed it is not that — but this app has always let you knock the
     * hands off a dial, and a display with a dead segment in it is the
     * same joke on the same object. The toolbox button puts them back.
     */
    var pokeable: Boolean = false
        set(value) {
            field = value
            if (!value) burnt.clear()
            invalidate()
        }

    /**
     * The bars somebody has poked out, by the place they are in.
     *
     * By place and not by value, because that is what a dead segment is: a
     * bar in the third digit stays dead whatever number the third digit is
     * showing. Which is exactly what makes it worth doing — the clock goes
     * on telling the time and the time it tells is wrong.
     */
    private val burnt = HashMap<String, IntArray>()

    private fun burntAt(key: String, modules: Int): IntArray? {
        val held = burnt[key] ?: return null
        return if (held.size == modules) held else null
    }

    /** Whether anything on this face has been poked out. */
    fun isDisarranged(): Boolean = burnt.values.any { row -> row.any { it != 0 } }

    /** Puts every poked-out bar back. */
    fun reassembleAll() {
        burnt.clear()
        invalidate()
    }

    /** The typeface a printed number is printed in, for this script. */
    private fun faceFor(): Typeface =
        if (script == DigitScript.YAUTJA) yautja ?: PRINT else PRINT

    // --------------------------------------------------------- the clock

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, delayToNextFrame())
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

    /**
     * How long until the next frame is worth drawing.
     *
     * A clock showing whole minutes needs one frame a minute and asks for
     * one a second, because the blinking colon and a card still falling
     * both live inside that minute. While something is moving it asks for
     * every frame there is, and stops the moment it lands.
     */
    private fun delayToNextFrame(): Long =
        if (moving()) FRAME_MS else Ticker.delayToNext(System.currentTimeMillis())

    private fun moving(): Boolean =
        drawn.values.any { android.os.SystemClock.uptimeMillis() - it.changedAt < changeMs() }

    private fun changeMs(): Long = when (style) {
        DigitStyle.SEGMENT -> 0L
        // Type does not move. A number that fades or slides is a number
        // pretending to be a machine, which is the one thing this idiom
        // is for not being.
        DigitStyle.PLAIN -> 0L
        DigitStyle.CARD -> FLIP_MS
        DigitStyle.ROLLER -> ROLL_MS
    }

    // ----------------------------------------------------- what is shown

    private fun options() = DigitalReadout.Options(
        script = script,
        hour24 = hour24,
        leadingZero = leadingZero,
        // No seconds while a time is being set. Nobody sets an alarm for
        // twenty past seven and eleven seconds, and two more drums on the
        // row are two more things to catch by accident.
        //
        // And the clock with the screen to itself asks its own switch: a
        // bedside clock is a clock and nothing else, which is what makes
        // it worth standing a phone on its side for.
        seconds = (if (fullScreen) bedsideSeconds else showSeconds) && settingMs == null
    )

    /**
     * A time being set, instead of the time it is.
     *
     * Null when the clock is a clock. Not null and the face shows this
     * value, drops the seconds, and lets a finger roll the digits — which
     * is the digital answer to winding the hands round the dial, and the
     * one gesture on this face that has anything to grab: there is nothing
     * to set on a clock showing the time, so nothing there takes a drag
     * and the swipes between cards go on working.
     */
    var settingMs: Long? = null
        set(value) {
            field = value
            rolling = null
            invalidate()
        }

    /**
     * A time of day to show and leave alone, as milliseconds since
     * midnight.
     *
     * For the little faces that stand for an alarm: a still reading of one
     * fixed time, with nothing to drag and no clock behind it. [settingMs]
     * wins if both are set, because a time being edited is the one the
     * finger is on.
     */
    var frozenMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Told when a finger moves the time being set. */
    var onSettingChanged: ((Long) -> Unit)? = null

    /** Told once per detent, so the app can make the noise. */
    var onDetent: (() -> Unit)? = null

    /** The time this face is showing, which the tests can move. */
    internal var atMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    private fun nowMs(): Long = atMs ?: TimeKeeper.nowMs()

    /** A calendar in this readout's own zone, at the time it is showing. */
    private fun calendar(): java.util.Calendar =
        (zone?.let { java.util.Calendar.getInstance(it) } ?: java.util.Calendar.getInstance())
            .apply { timeInMillis = nowMs() }

    private fun readout(): List<Cell> {
        (settingMs ?: frozenMs)?.let { ms ->
            val day = ((ms % DAY_MS) + DAY_MS) % DAY_MS
            return DigitalReadout.time(
                (day / 3_600_000L).toInt(),
                (day / 60_000L % 60L).toInt(),
                0,
                options()
            )
        }
        val calendar = calendar()
        return DigitalReadout.time(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND),
            options()
        )
    }

    private fun dateLine(): List<Cell> {
        val calendar = calendar()
        return DigitalReadout.date(
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.MONTH) + 1,
            dateDayFirst,
            options()
        )
    }

    /** For the tests: the cells this face is showing right now. */
    internal fun cellsForTest(): List<Cell> = readout()

    /**
     * Whether the face is on its side — see [LYING_DOWN].
     *
     * Not the bedside clock, which is a landscape clock on purpose: the
     * whole reason somebody stands a phone on its edge by the bed is to
     * be read from across the room, and that mode has its own switch for
     * whether the date comes along.
     */
    private fun lyingDown(): Boolean = !fullScreen && width > height * LYING_DOWN

    /** For the tests: whether this face is showing a date at all. */
    internal fun datedForTest(): Boolean =
        (if (fullScreen) bedsideDate else showDate) && !lyingDown()

    // ------------------------------------------------------------ layout

    /**
     * How wide a cell is, in units of one digit's width.
     *
     * The punctuation is narrow and closes ranks with the groups either
     * side of it, the way punctuation does. A Roman number is as wide as
     * the letters in it, which is the price of Roman numerals and not
     * something to hide by squeezing them.
     */
    private fun widthOf(cell: Cell): Float = when (cell) {
        is Cell.Number ->
            if (style == DigitStyle.SEGMENT) Segments.span(kind(), cell.text)
            else cell.text.length.toFloat()
        // Punctuation, which closes ranks with the groups either side of
        // it. It was a whole module on Rome's display for a while — back
        // when Rome's module wrote the *time*, and lighting the dot inside
        // a module of its own was what made VII\u00b7XII read as one
        // instrument. Rome writes the date now, on a rail of its own, and
        // nothing on this face wants a module for a colon any more.
        Cell.Colon -> 0.34f
        Cell.Slash -> 0.5f
        is Cell.Token -> 1f
    }

    /**
     * Which display the *numbers* on this face go on.
     *
     * The panel has two, and this is the one the time is written in. Its
     * other alphabet is Rome's module and it never writes a number here —
     * see [drawRail].
     */
    private fun kind(): Segments.Kind = when (script) {
        DigitScript.YAUTJA -> Segments.Kind.STAR
        DigitScript.ROMAN_COMET -> Segments.Kind.NINE
        else -> Segments.Kind.SEVEN
    }

    /**
     * How thick a bar comes out at this size, in pixels.
     *
     * The punctuation is drawn with a stroke rather than through
     * [SegmentPainter], so it has to arrive at the same number the painter
     * does or the colon stops matching the digits it stands between. It
     * did not, for one build: [weight] became a multiple of what each
     * display was drawn at instead of a share of the digit's height, and
     * this line went on multiplying by the digit's height — a colon one
     * and a half digits across, which a screenshot showed as a blob with
     * the numerals somewhere underneath it.
     */
    private fun barWidth(digitH: Float): Float =
        digitH * Segments.native(kind()) * weight * 1.6f

    /**
     * How wide a cell is against its own height.
     *
     * The lit displays each have their own answer and it is a measurement,
     * not a preference — see [Segments.aspect]. A card or a drum is a
     * different object and keeps the proportions a card has.
     */
    private fun cellRatio(): Float =
        if (style == DigitStyle.SEGMENT) Segments.aspect(kind()) else DIGIT_RATIO

    /**
     * How tall everything this face is about to draw comes to, measured in
     * digit heights.
     *
     * The same sum the layout below does, done before there is a digit
     * height to do it with — which is the point: it is what turns "how
     * tall may one digit be" into "how tall may all of this be", and it
     * has to agree with the drawing exactly or the block is centred on a
     * height it does not have.
     */
    private fun stackOf(
        cells: List<Cell>,
        date: List<Cell>,
        said: String?,
        ladder: List<WorldClocks.City>,
        alarm: Boolean
    ): Float {
        var stack = if (cells.isEmpty()) 0f else 1f
        if (date.isNotEmpty()) stack += DATE_SCALE + DATE_GAP
        if (said != null) stack += CAPTION_SCALE * 1.9f
        if (ladder.isNotEmpty()) {
            stack += CITY_GAP + ladder.size * CITY_SCALE +
                (ladder.size - 1) * CITY_SCALE * CITY_STEP
        }
        if (alarm) stack += ALARM_GAP + ALARM_SCALE
        return stack.coerceAtLeast(1f)
    }

    /** The whole row's width, in the same units, gaps included. */
    private fun rowWidth(cells: List<Cell>): Float {
        var total = 0f
        for ((i, cell) in cells.withIndex()) {
            total += widthOf(cell)
            if (i < cells.lastIndex) total += gapAfter(cells, i)
        }
        return total
    }

    private fun gapAfter(cells: List<Cell>, i: Int): Float {
        val here = cells[i]
        val next = cells.getOrNull(i + 1)
        val punctuation = here !is Cell.Number || next !is Cell.Number
        return if (punctuation) TIGHT_GAP else GAP
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (chip) {
            // A panel and a rim. The fill alone is invisible: a bubble is
            // the app's own face colour lying on the app's own background,
            // and what makes it read as an object on top of the clock
            // rather than a hole cut in it is the edge.
            // Measured against the shorter side, not the width. A bubble
            // is square so the two agree; a widget stretched four cells
            // wide is not, and a corner radius that is a fifth of *that*
            // is a lozenge with a clock in it.
            val side = minOf(width, height).toFloat()
            val inset = side * 0.02f
            val corner = side * 0.18f
            card.style = Paint.Style.FILL
            card.color = theme.face
            card.alpha = 0xE8
            canvas.drawRoundRect(
                inset, inset, width - inset, height - inset, corner, corner, card
            )
            card.style = Paint.Style.STROKE
            card.strokeWidth = side * 0.018f
            card.color = theme.rim
            card.alpha = 0xB0
            canvas.drawRoundRect(
                inset, inset, width - inset, height - inset, corner, corner, card
            )
            card.style = Paint.Style.FILL
            card.alpha = 255
        } else {
            canvas.drawColor(theme.face)
        }

        laid.clear()
        grabs.clear()
        // The panel is this script's whole clock card: two lamps at the
        // ends of the time, and the date on rails above and below it when
        // there is a date to show. The lamps come with the display and the
        // rails are the date's own switch — see [CometPanel].
        val panel = script == DigitScript.ROMAN_COMET && style == DigitStyle.SEGMENT &&
            settingMs == null && frozenMs == null
        val cells = readout().let {
            // The moon at the end of a twelve-hour reading is a lamp on
            // this panel's glass rather than a glyph in the row — it is
            // drawn outside the digits, where the drawing puts it. Two
            // moons saying the same thing was the first thing a picture of
            // this face showed.
            if (panel) it.filterNot { cell -> cell is Cell.Token } else it
        }
        // No date under a time being set, nor under a still one standing
        // for an alarm. It is not today's date that is being set, and a
        // row of numbers nobody can touch under a row of numbers they can
        // is an invitation to touch the wrong one.
        val dated = (if (fullScreen) bedsideDate else showDate) && !lyingDown()
        // The panel writes its date on two rails of Rome's module rather
        // than as a line of digits under the time — see [CometPanel]. Not
        // while a time is being wound or standing for an alarm: it is not
        // today's date that is being set, and the rails would be saying
        // something about a different day from the one on screen.
        val rails =
            if (script == DigitScript.ROMAN_COMET && dated &&
                settingMs == null && frozenMs == null
            ) {
                CometPanel.rails(calendar(), dateDayFirst)
            } else {
                null
            }
        val date =
            if (rails == null && dated && settingMs == null && frozenMs == null) {
                dateLine()
            } else {
                emptyList()
            }
        val said = caption
        // The ladder of other cities, which is only ever on the main face:
        // a chip on a home screen and a readout being wound are not places
        // to put a list of the world.
        val ladder = if (chip || settingMs != null || frozenMs != null) emptyList() else cities
        val day =
            if (showWeekday && date.isNotEmpty()) {
                Weekday.of(calendar(), script, lit = style == DigitStyle.SEGMENT)
            } else {
                null
            }
        // The alarm goes on the face and not on a bubble or a wound time:
        // it is a thing about today, and neither of those is today.
        val alarm =
            if (chip || settingMs != null || frozenMs != null) null
            else nextAlarmMs?.let { alarmCells(it) }

        // One digit's width, chosen so the longest thing on the face fits
        // with a margin either side. Roman at twenty-three fifty-nine is
        // three times the width of the same time in ours, and the row that
        // decides is whichever is longer today — asked every frame, because
        // it changes when the hour does.
        val room = width * (1f - 2f * (if (fullScreen) BEDSIDE_MARGIN else MARGIN))
        // Room either side for whichever lamps are going to light, and for
        // no others. They sit outside the digits on the drawing and a lamp
        // hanging off the edge of the screen is worse than a slightly
        // smaller clock — but reserving for both of them always made the
        // time a third smaller than it needed to be on a panel showing
        // neither, which is most of the day on a twenty-four hour clock.
        // Worked out once. Both of these ask the calendar what hour it is,
        // and this runs every frame.
        val bellLit = panel && nextAlarmMs != null && !chip
        val moonLit = panel && CometPanel.moonLit(hourNow(), hour24)
        val lamps = (if (bellLit) 1 else 0) + (if (moonLit) 1 else 0)
        val widest = maxOf(
            rowWidth(cells) + lamps * lampRoom(),
            // The day sits beside the date, so the pair of them is one row
            // and it is the pair that has to fit.
            (rowWidth(date) + weekdayCells(day)) * DATE_SCALE,
            alarm?.let {
                (rowWidth(it) + if (panel) 0f else ALARM_FLAG) * ALARM_SCALE
            } ?: 0f
        )
        val tallest = height * (if (fullScreen) BEDSIDE_TALLEST else TALLEST)
        // And the whole block has to fit as well as one digit of it. Only
        // the digit was capped for a long time, which is right until the
        // width stops being what binds — the in-app face is always too
        // narrow before it is too short, and a home-screen widget pulled
        // four cells wide and one tall is the other way round. That widget
        // drew its date through the bottom of its own panel.
        val stack = stackOf(cells, date, said, ladder, alarm != null) +
            if (rails != null) 2f * (CometPanel.RAIL + CometPanel.RAIL_GAP) else 0f
        val byHeight = height * (if (chip) CHIP_BLOCK_FILL else BLOCK_FILL) / stack * cellRatio()
        val digitW = if (widest > 0f) minOf(room / widest, tallest, byHeight) else 0f
        val digitH = digitW / cellRatio()

        lit.strokeWidth = barWidth(digitH)
        ink.textSize = digitH * 0.92f

        // The date sits under the time, and the two together are centred on
        // the face rather than the time alone: a clock with the date on
        // reads as one block, and centring the big row leaves the small one
        // hanging off the bottom of it.
        val dateH = if (date.isEmpty()) 0f else digitH * DATE_SCALE + digitH * DATE_GAP
        val capH = if (said == null) 0f else digitH * CAPTION_SCALE * 1.9f
        val rungH = digitH * CITY_SCALE
        val ladderH =
            if (ladder.isEmpty()) 0f
            else digitH * CITY_GAP + ladder.size * rungH + (ladder.size - 1) * rungH * CITY_STEP
        val alarmH = if (alarm == null) 0f else digitH * (ALARM_GAP + ALARM_SCALE)
        // The rails fit themselves to the clock rather than the other way
        // round. Rome's module is narrow and a date is a lot of them —
        // XXVIII·VIII is sixteen — so a panel sized by its longest rail
        // puts a postage stamp of a time between two long grey bars, which
        // is a date with a clock on it. The drawing's own ratio is what a
        // rail gets when it fits; a rail that does not fit gets shorter,
        // and the time stays the size of the time.
        val railH = if (rails == null) 0f else minOf(
            digitH * CometPanel.RAIL,
            railFit(rails.first, room),
            railFit(rails.second, room)
        )
        val railGap = digitH * CometPanel.RAIL_GAP
        val railsH = if (rails == null) 0f else 2f * (railH + railGap)
        val top = (height - digitH - railsH - dateH - capH - ladderH - alarmH) / 2f
        // The panel, top rail first, so the time lands between the two.
        val timeTop = if (rails == null) top else top + railH + railGap
        rails?.let { drawRail(canvas, it.first, top, railH) }
        drawRow(canvas, cells, timeTop, digitW, digitH, "t")
        rails?.let { drawRail(canvas, it.second, timeTop + digitH + railGap, railH) }
        if (panel) {
            drawLamps(canvas, timeTop, digitW, digitH, rowWidth(cells) * digitW, bellLit, moonLit)
        }
        if (date.isNotEmpty()) {
            drawDateLine(
                canvas, date, day, timeTop + digitH + digitH * DATE_GAP,
                digitW * DATE_SCALE, digitH * DATE_SCALE
            )
        }
        // The city rides under the digits, the way it rides inside the
        // dial on the face that has one: a caption hanging off the bubble
        // would make it two objects.
        said?.let {
            ink.typeface = PRINT
            ink.color = theme.numeral
            ink.textSize = digitH * CAPTION_SCALE
            canvas.drawText(
                it, width / 2f,
                timeTop + digitH + railsH / 2f + dateH + digitH * CAPTION_SCALE * 1.2f, ink
            )
        }
        if (ladder.isNotEmpty()) {
            drawCities(
                canvas, ladder,
                timeTop + digitH + railsH / 2f + dateH + capH + digitH * CITY_GAP,
                digitW * CITY_SCALE, rungH
            )
        }
        if (alarm != null) {
            drawAlarmLine(
                canvas, alarm,
                timeTop + digitH + railsH / 2f + dateH + capH + ladderH + digitH * ALARM_GAP,
                digitW * ALARM_SCALE, digitH * ALARM_SCALE,
                // The panel has a bell lamp of its own, at the end of the
                // time. Two bells on one card is the same fault as the two
                // moons: the lamp says something is armed, the line says
                // when, and the reader sees one alarm drawn twice.
                flagged = !panel
            )
        }
    }

    /**
     * The date, with the day of the week beside it.
     *
     * One row and not two, the way a watch has always done it: the day is
     * a short label sitting in front of the numbers, close enough to
     * belong to them. Which language it is in is [Weekday]'s question and
     * not this one's.
     *
     * Drawn on whatever the rest of the row is drawn on, which is the
     * thing this had wrong. A face made of lit bars was writing the day
     * of the week in the phone's own user-interface typeface, in bold,
     * beside a display: a photograph of it shows `SAT` in Roboto standing
     * next to numerals made of glowing metal, and it reads as a caption
     * the app forgot to finish rather than as part of the clock.
     *
     * A display cannot spell it. Seven bars have ten digits and no
     * letters, the stars have ten and no letters, the Comet's nine were
     * drawn for a calculator, and Rome's module has exactly the seven
     * numerals and a dot — none of them can write SAT, or SÁB, or 土, and
     * inventing the letters would be inventing a machine that never
     * existed. What a display *can* light is a number, so a lit face says
     * the day the way this app's alien face has always said it: Monday is
     * one. The name comes back the moment the numbers are printed rather
     * than lit, because then it is type beside type.
     */
    private fun drawDateLine(
        canvas: Canvas,
        date: List<Cell>,
        day: String?,
        top: Float,
        digitW: Float,
        digitH: Float
    ) {
        if (day == null) {
            drawRow(canvas, date, top, digitW, digitH, "d")
            return
        }
        val gap = digitH * (if (style == DigitStyle.SEGMENT) WEEKDAY_LIT_GAP else WEEKDAY_GAP)
        val dateW = rowWidth(date) * digitW
        if (style == DigitStyle.SEGMENT) {
            // A module of the same display, at the same size, with the
            // same unlit bars behind it as every other cell on the face.
            val label = Segments.span(kind(), day) * digitW
            val left = (width - (label + gap + dateW)) / 2f
            drawAsSegments(canvas, day, left, top, label, digitH, "w")
            drawRow(canvas, date, top, digitW, digitH, "d", leftEdge = left + label + gap)
            return
        }
        ink.typeface = if (script == DigitScript.YAUTJA) yautja ?: PRINT else PRINT
        ink.textSize = digitH * WEEKDAY_SIZE
        ink.textAlign = Paint.Align.LEFT
        val label = ink.measureText(day)
        val left = (width - (label + gap + dateW)) / 2f
        // On its own leaf, hinged like the numbers beside it. A card is
        // an object, and the one thing on the stack that was not printed
        // on one was the only word on the row.
        val pad = digitH * WEEKDAY_PAD
        if (style == DigitStyle.CARD) {
            drawLeaf(canvas, left - pad, top, label + pad * 2f, digitH)
        }
        ink.color = theme.numeral
        ink.alpha = WEEKDAY_ALPHA
        canvas.drawText(day, left, top + digitH * 0.76f, ink)
        ink.alpha = 255
        if (style == DigitStyle.CARD) {
            drawHinge(canvas, left - pad, top, label + pad * 2f, digitH)
        }
        ink.textAlign = Paint.Align.CENTER
        drawRow(canvas, date, top, digitW, digitH, "d", leftEdge = left + label + gap)
    }

    // ------------------------------------------------------- the panel

    /**
     * The tallest a rail of [text] may be and still fit across [room].
     *
     * Rome's module and the calculator's digit are two different sizes of
     * two different displays, and this is the one place that converts
     * between them: how many modules the text spells, and how wide a
     * module is against its own height.
     */
    private fun railFit(text: String, room: Float): Float {
        val span = Segments.span(Segments.Kind.SIXTEEN, text)
        if (span <= 0f) return Float.MAX_VALUE
        return room / (span * Segments.aspect(Segments.Kind.SIXTEEN))
    }

    /** And how much a lamp claims at the end of the time row. */
    private fun lampRoom(): Float =
        (CometPanel.LAMP_GAP + BELL_WIDE) / cellRatio()

    /**
     * One rail of Rome's module, centred, with the date on it.
     *
     * Drawn straight through [SegmentPainter] rather than through a row of
     * cells, because a rail is not a readout: nothing on it turns, nothing
     * on it can be wound, and a `V` on this display is two modules of one
     * letter — which is a fact about the alphabet and not about the layout
     * above it.
     */
    private fun drawRail(canvas: Canvas, text: String, top: Float, height: Float) {
        val kind = Segments.Kind.SIXTEEN
        val masks = Segments.spell(kind, text)
        if (masks.isEmpty()) return
        val wide = Segments.span(kind, text) * height * Segments.aspect(kind)
        segments.weight = weight
        segments.ghosts = ghosts
        segments.row(
            canvas, kind, masks, (width - wide) / 2f, top, wide, height,
            // Lit in the face's own ink rather than in the colour the
            // digits are. There are two displays on this panel and they
            // are two different machines: the Sharp's nine glow, because
            // that is what a Comet's tube did, and Rome's module is a
            // bank of white bars above and below them. Drawn in the same
            // cyan they read as one display in three rows, and a panel
            // whose whole idea is that it is two instruments bolted
            // together was hiding it.
            ClockThemes.contrastInk(theme), theme.minorTick
        )
    }

    /**
     * The two lamps at the ends of the panel: an alarm, and a moon.
     *
     * The bell follows the same switch the line under the clock does, and
     * the KDoc here said the opposite for one build: "a different
     * question, so not governed by it". It is governed by it — the face is
     * only ever told when the next alarm is when that switch is on — and a
     * comment claiming otherwise is worse than no comment, because the
     * next person to read it stops checking.
     *
     * Which turns out to be the better rule anyway. The switch means "does
     * this clock talk about my alarm", and on this panel it talks about it
     * the way the drawing does: a lamp at the end of the time rather than
     * a line of text. The line is still underneath saying *when*, with its
     * own bell taken off, because two bells is one alarm drawn twice.
     *
     * The moon is the panel's whole answer to which half of the day this
     * is. The drawing gives it one lamp and no sun, so it lights before
     * noon and goes out after, and on a twenty-four hour clock it never
     * lights at all — see [CometPanel.moonLit].
     *
     * Both are on the drawing's own outlines. The gears, the battery and
     * the wifi fan beside them are for a machine that is not this phone.
     */
    private fun drawLamps(
        canvas: Canvas,
        top: Float,
        digitW: Float,
        digitH: Float,
        timeWide: Float,
        bell: Boolean,
        moon: Boolean
    ) {
        val left = (width - timeWide) / 2f
        val cy = top + digitH / 2f
        if (bell) {
            drawIcon(
                canvas, CometPanel.BELL, digitH * BELL_WIDE, digitH * BELL_TALL,
                left - digitW * CometPanel.LAMP_GAP - digitH * BELL_WIDE, cy
            )
        }
        if (moon) {
            drawIcon(
                canvas, CometPanel.MOON, digitH * MOON_SIDE, digitH * MOON_SIDE,
                left + timeWide + digitW * CometPanel.LAMP_GAP, cy
            )
        }
    }

    /** One lamp, from its outline, with its left edge at [x] and centred on [cy]. */
    private fun drawIcon(
        canvas: Canvas,
        shape: Array<FloatArray>,
        wide: Float,
        tall: Float,
        x: Float,
        cy: Float
    ) {
        punctuation(true)
        val was = lit.style
        lit.style = Paint.Style.FILL
        val path = android.graphics.Path()
        for (poly in shape) {
            var i = 0
            while (i < poly.size) {
                val px = x + poly[i] * wide
                val py = cy - tall / 2f + poly[i + 1] * tall
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                i += 2
            }
            path.close()
        }
        canvas.drawPath(path, lit)
        lit.style = was
    }

    /**
     * The hour this face is showing, for the lamp that names the half-day.
     *
     * Through [calendar], which is already wound to now and already in this
     * readout's own zone — so a world clock's panel says morning when it is
     * morning there.
     */
    private fun hourNow(): Int = calendar().get(java.util.Calendar.HOUR_OF_DAY)

    /** What time the next alarm goes off, in this face's own numerals. */
    private fun alarmCells(atMs: Long): List<Cell> {
        val calendar = calendar()
        calendar.timeInMillis = atMs
        return DigitalReadout.time(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            0,
            options().copy(seconds = false)
        )
    }

    /**
     * The next alarm, with a bell in front of it.
     *
     * A bell rather than a word, because it has to survive three
     * alphabets and every language the phone speaks, and because that is
     * what a watch puts there. Drawn small and quiet: it is a flag saying
     * something is armed, not a second time to read.
     */
    private fun drawAlarmLine(
        canvas: Canvas,
        alarm: List<Cell>,
        top: Float,
        digitW: Float,
        digitH: Float,
        flagged: Boolean
    ) {
        val flag = if (flagged) digitH * ALARM_FLAG else 0f
        val row = rowWidth(alarm) * digitW
        val left = (width - (flag + row)) / 2f
        if (flagged) {
            drawBell(canvas, left + flag * 0.42f, top + digitH * 0.5f, digitH * 0.42f)
        }
        drawRow(canvas, alarm, top, digitW, digitH, "a", leftEdge = left + flag)
    }

    /**
     * A bell, in the ink the bars are lit in.
     *
     * Four strokes and no font: a glyph borrowed from a typeface is a
     * glyph that is missing on somebody's phone, and this one has to be
     * there or the row is a time with nothing saying what it is.
     */
    private fun drawBell(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        lit.style = Paint.Style.FILL
        lit.color = theme.decimal
        lit.alpha = 255
        bell.reset()
        // The dome, sitting on its rim, with the clapper under it.
        bell.moveTo(cx - r * 0.72f, cy + r * 0.42f)
        bell.quadTo(cx - r * 0.60f, cy + r * 0.30f, cx - r * 0.56f, cy - r * 0.10f)
        bell.quadTo(cx - r * 0.50f, cy - r * 0.86f, cx, cy - r * 0.86f)
        bell.quadTo(cx + r * 0.50f, cy - r * 0.86f, cx + r * 0.56f, cy - r * 0.10f)
        bell.quadTo(cx + r * 0.60f, cy + r * 0.30f, cx + r * 0.72f, cy + r * 0.42f)
        bell.close()
        canvas.drawPath(bell, lit)
        canvas.drawCircle(cx, cy + r * 0.62f, r * 0.20f, lit)
    }

    private val bell = android.graphics.Path()

    /** How wide a weekday label is, in date-cell widths. */
    private fun weekdayCells(day: String?): Float {
        if (day == null) return 0f
        // A lit face writes it on the display, where a cell is a cell and
        // there is nothing to estimate.
        if (style == DigitStyle.SEGMENT) return Segments.span(kind(), day) + WEEKDAY_LIT_GAP
        // Printed, it is type, and type is measured in the same units the
        // row is so the pair can be capped together: a three-letter label
        // is about two thirds of a cell.
        return day.length * WEEKDAY_CELLS + WEEKDAY_GAP
    }

    /**
     * The other cities, as a ladder of readouts under the time.
     *
     * Two columns, and both of them justified rather than centred: the
     * names end where the digits begin, so five cities of five different
     * lengths read as a list and not as a heap. The times are the same
     * bars the big one is made of, at a quarter of the size, which is what
     * makes this a clock with a second display on it rather than a clock
     * with a caption.
     *
     * Hours and minutes only. A second hand on a city eight time zones
     * away is a number that changes every second and tells you nothing:
     * the question is what part of the day it is over there.
     */
    private fun drawCities(
        canvas: Canvas,
        ladder: List<WorldClocks.City>,
        top: Float,
        digitW: Float,
        digitH: Float
    ) {
        val rows = ladder.map { it to cityCells(it) }
        val timeW = rows.maxOf { rowWidth(it.second) } * digitW
        ink.typeface = PRINT
        ink.textSize = digitH * CITY_NAME
        ink.textAlign = Paint.Align.RIGHT
        val nameW = rows.maxOf { ink.measureText(it.first.name) }
        val gutter = digitH * CITY_GUTTER
        val block = nameW + gutter + timeW
        val left = (width - block) / 2f
        var y = top
        for ((city, cells) in rows) {
            ink.color = theme.numeral
            ink.alpha = CITY_ALPHA
            canvas.drawText(city.name, left + nameW, y + digitH * 0.78f, ink)
            ink.alpha = 255
            // Right-aligned against the far edge, so the minutes of every
            // city line up whatever the hours are written in.
            drawRow(
                canvas, cells, y, digitW, digitH, "w${city.tzId}",
                leftEdge = left + nameW + gutter + timeW - rowWidth(cells) * digitW
            )
            y += digitH * (1f + CITY_STEP)
        }
        ink.textAlign = Paint.Align.CENTER
    }

    /** What time it is in one of them, to the minute. */
    private fun cityCells(city: WorldClocks.City): List<Cell> {
        val calendar = java.util.Calendar.getInstance(city.zone)
        calendar.timeInMillis = nowMs()
        return DigitalReadout.time(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            0,
            options().copy(seconds = false)
        )
    }

    /**
     * One row of cells, centred, each drawn in the chosen idiom.
     *
     * [tag] names the row so the two of them keep their own memories of
     * what was there before: the date changes once a day and the seconds
     * sixty times a minute, and a cell that took the other row's history
     * for its own would flip at midnight because the seconds had.
     */
    private fun drawRow(
        canvas: Canvas,
        cells: List<Cell>,
        top: Float,
        digitW: Float,
        digitH: Float,
        tag: String,
        leftEdge: Float? = null
    ) {
        val total = rowWidth(cells) * digitW
        var x = leftEdge ?: ((width - total) / 2f)
        val wasStroke = lit.strokeWidth
        val wasText = ink.textSize
        lit.strokeWidth = barWidth(digitH)
        ink.textSize = digitH * 0.92f
        for ((i, cell) in cells.withIndex()) {
            val w = widthOf(cell) * digitW
            when (cell) {
                is Cell.Number -> {
                    drawNumber(canvas, cell, "$tag$i", x, top, w, digitH)
                    // Only while there is something to set, and only on a
                    // number worth turning. A grab box taller than the
                    // digit, because a finger aiming at a bar half a
                    // millimetre wide is a finger that misses.
                    if (settingMs != null && cell.weight > 0) {
                        grabs += Grab(
                            x - digitW * 0.12f, top - digitH * 0.35f,
                            x + w + digitW * 0.12f, top + digitH * 1.35f,
                            cell.weight
                        )
                        drawHandles(canvas, x + w / 2f, top, digitH)
                    }
                }
                Cell.Colon -> drawColon(canvas, x + w / 2f, top, digitH)
                Cell.Slash -> drawSlash(canvas, x, top, w, digitH)
                is Cell.Token -> drawToken(canvas, cell.sun, x + w / 2f, top, digitH)
            }
            x += w + gapAfter(cells, i) * digitW
        }
        lit.strokeWidth = wasStroke
        ink.textSize = wasText
    }

    // ------------------------------------------------- the three idioms

    /**
     * What each place last had in it, and when it changed.
     *
     * Kept by position rather than by value, because that is what moves: a
     * card falls in the units place when the units change, and the tens
     * card beside it stays put. Keyed by the row and the index within it.
     */
    private class Was(var text: String, var changedAt: Long)

    private val drawn = HashMap<String, Was>()

    /** How far through its change the cell at [key] is, 1 when settled. */
    private fun progressOf(key: String, text: String): Float {
        val now = android.os.SystemClock.uptimeMillis()
        val was = drawn[key]
        if (was == null) {
            drawn[key] = Was(text, now - changeMs())
            return 1f
        }
        if (was.text != text) {
            was.changedAt = now
            was.text = text
            return 0f
        }
        val span = changeMs()
        if (span <= 0L) return 1f
        return ((now - was.changedAt) / span.toFloat()).coerceIn(0f, 1f)
    }

    /** What the cell at [key] is coming from, or null once it has landed. */
    private val leaving = HashMap<String, String>()

    private fun drawNumber(
        canvas: Canvas,
        cell: Cell.Number,
        key: String,
        x: Float,
        top: Float,
        w: Float,
        h: Float
    ) {
        val before = drawn[key]?.text
        val progress = progressOf(key, cell.text)
        if (progress == 0f && before != null && before != cell.text) leaving[key] = before
        val from = if (progress < 1f) leaving[key] else null
        when (style) {
            DigitStyle.SEGMENT -> drawAsSegments(canvas, cell.text, x, top, w, h, key)
            DigitStyle.PLAIN -> drawAsPrint(canvas, cell.text, x, top, w, h)
            DigitStyle.CARD -> drawAsCard(canvas, cell.text, from, progress, x, top, w, h)
            DigitStyle.ROLLER -> drawAsRoller(canvas, cell.text, from, progress, x, top, w, h)
        }
        if (progress >= 1f) leaving.remove(key)
    }

    /**
     * Bars behind a mask, on whichever display this script writes its
     * numbers on — see [Segments].
     *
     * Every one of them is lit now. Theirs were a font here until the
     * chart they come from was read arm by arm; a font is a picture of a
     * display, and the one script on this clock that could not have an
     * unlit bar behind it, could not be poked and could not be made
     * thicker was the one that most looked like it should.
     */
    private fun drawAsSegments(
        canvas: Canvas, text: String, x: Float, top: Float, w: Float, h: Float, key: String
    ) {
        val kind = kind()
        val masks = Segments.spell(kind, text)
        if (masks.isEmpty()) return
        segments.weight = weight
        segments.ghosts = ghosts
        segments.row(
            canvas, kind, masks, x, top, w, h,
            theme.decimal, theme.minorTick, burntAt(key, masks.size)
        )
        if (pokeable) {
            val gap = Segments.gap(kind)
            val cell = w / (masks.size + (masks.size - 1) * gap)
            laid += Laid(key, kind, x, top, cell, cell * (1f + gap), h, masks.size)
        }
    }

    /**
     * Where a row of modules ended up, so a finger can be told which bar
     * it landed on.
     *
     * Recorded as the row is drawn rather than worked out again on touch:
     * the layout depends on what the clock says, and the number a finger
     * arrives at is the number that was on the glass, not the one the
     * arithmetic would give a frame later.
     */
    private class Laid(
        val key: String,
        val kind: Segments.Kind,
        val x: Float,
        val top: Float,
        val cell: Float,
        val stride: Float,
        val height: Float,
        val modules: Int
    )

    private val laid = ArrayList<Laid>()

    /** Told when a bar goes out, so the app can make the noise. */
    var onPoked: (() -> Unit)? = null

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (settingMs != null) return rollTouch(event)
        if (pokeable && style == DigitStyle.SEGMENT) return pokeTouch(event)
        if (onTapped != null) return tapTouch(event)
        return super.onTouchEvent(event)
    }

    /** Where and when a finger came down, for telling a tap from a swipe. */
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L

    /**
     * A tap, and only a tap.
     *
     * Down and up in the same place inside the time it takes to tap is a
     * tap; anything else is the pager's sideways swipe or a finger resting
     * on the glass, and both of those have to go on working — which is why
     * this measures rather than treating every touch as a press.
     */
    private fun tapTouch(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tapX = event.x
                tapY = event.y
                tapAt = android.os.SystemClock.uptimeMillis()
            }
            android.view.MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                val moved = kotlin.math.hypot(event.x - tapX, event.y - tapY)
                val took = android.os.SystemClock.uptimeMillis() - tapAt
                if (moved <= slop && took <= TAP_MS) {
                    performClick()
                    onTapped?.invoke()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * The drum a finger has hold of, while it has hold of it.
     *
     * [weight] is what one detent is worth in minutes — see
     * [Cell.Number.weight] — and [taken] how many detents have been paid
     * out already, so a drag that goes down and comes back up ends where
     * it started rather than somewhere further on.
     */
    private class Rolling(val weight: Int, val downY: Float, var taken: Int = 0)

    private var rolling: Rolling? = null
    private var lastMoveY = 0f
    private var lastMoveAt = 0L
    private var glide: android.animation.ValueAnimator? = null

    /**
     * Rolling a drum to set a time.
     *
     * Detents, because a number is not a continuous quantity: a drum lands
     * on a number and clicks, and between two of them it is not showing
     * half past anything. Inertia, because a drum has mass — and because
     * reaching fifty-nine minutes one detent at a time is not a gesture
     * anybody makes twice.
     *
     * The carry is not written down anywhere. Each drum is worth so many
     * minutes and the total wraps into a day, so rolling the minutes past
     * fifty-nine takes the hour with it exactly as a counter does — see
     * [Cell.Number.weight].
     */
    private fun rollTouch(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                glide?.cancel()
                val weight = weightUnder(event.x, event.y)
                if (weight == 0) return false
                rolling = Rolling(weight, event.y)
                lastMoveY = event.y
                lastMoveAt = android.os.SystemClock.uptimeMillis()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val hold = rolling ?: return false
                // Up is forwards. A drum turns towards you as its numbers
                // increase, which is the way every mechanical counter and
                // every picker on every phone has ever gone.
                val wanted = ((hold.downY - event.y) / detent()).toInt()
                if (wanted != hold.taken) {
                    step(hold.weight, wanted - hold.taken)
                    hold.taken = wanted
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastMoveAt > 24L) {
                    lastMoveY = event.y
                    lastMoveAt = now
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val hold = rolling ?: return false
                val elapsed =
                    (android.os.SystemClock.uptimeMillis() - lastMoveAt).coerceAtLeast(1L)
                val perSecond = (lastMoveY - event.y) / elapsed * 1000f
                rolling = null
                flingOn(hold.weight, perSecond)
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                rolling = null
                return true
            }
        }
        return false
    }

    /** How far a finger travels for one click of the drum. */
    private fun detent(): Float = resources.displayMetrics.density * DETENT_DP

    /** Which drum is under the finger, as its worth in minutes. */
    private fun weightUnder(px: Float, py: Float): Int {
        for (grab in grabs) {
            if (px >= grab.left && px <= grab.right && py >= grab.top && py <= grab.bottom) {
                return grab.weight
            }
        }
        return 0
    }

    private fun step(weight: Int, by: Int) {
        if (by == 0) return
        val was = settingMs ?: return
        val moved = was + by.toLong() * weight * 60_000L
        val wrapped = ((moved % DAY_MS) + DAY_MS) % DAY_MS
        if (wrapped == was) return
        // Set without letting the setter drop the drag that is turning it.
        val hold = rolling
        settingMs = wrapped
        rolling = hold
        onSettingChanged?.invoke(wrapped)
        repeat(kotlin.math.abs(by).coerceAtMost(3)) { onDetent?.invoke() }
    }

    /**
     * The drum keeps turning after the finger lets go, and slows to a stop
     * on a detent.
     *
     * Capped, and hard. An uncapped fling on a drum worth ten hours a
     * click is a flick that puts the alarm most of a day from where
     * anybody meant it — and the wrap makes that invisible: you look up
     * and it is showing a plausible wrong time.
     */
    private fun flingOn(weight: Int, perSecond: Float) {
        val steps = (perSecond / detent() * FLING_SECONDS).toInt()
            .coerceIn(-MAX_FLING, MAX_FLING)
        if (steps == 0) return
        var paid = 0
        glide = android.animation.ValueAnimator.ofInt(0, steps).apply {
            duration = (kotlin.math.abs(steps) * FLING_MS_PER_STEP).coerceAtMost(900L)
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener {
                val wanted = it.animatedValue as Int
                step(weight, wanted - paid)
                paid = wanted
            }
            start()
        }
    }

    /** For the tests: turn the drum worth [weight] minutes by [steps]. */
    internal fun rollForTest(weight: Int, steps: Int) = step(weight, steps)

    /** For the tests: what a finger at this point would take hold of. */
    internal fun weightUnderForTest(px: Float, py: Float): Int = weightUnder(px, py)

    /** For the tests: the middle of the drum worth [weight] minutes. */
    internal fun grabForTest(weight: Int): Pair<Float, Float> {
        val grab = grabs.first { it.weight == weight }
        return (grab.left + grab.right) / 2f to (grab.top + grab.bottom) / 2f
    }

    /**
     * Where each drum ended up, so a finger can be told which it landed on.
     *
     * Only filled while a time is being set. A clock showing the time has
     * nothing to grab, which is what keeps this gesture out of the way of
     * the swipes between cards.
     */
    private class Grab(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val weight: Int
    )

    private val grabs = ArrayList<Grab>()

    private fun pokeTouch(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN) {
            return event.actionMasked == android.view.MotionEvent.ACTION_UP
        }
        val row = laid.firstOrNull {
            event.y >= it.top - it.height * 0.15f &&
                event.y <= it.top + it.height * 1.15f &&
                event.x >= it.x && event.x <= it.x + it.stride * it.modules
        } ?: return false
        val index = ((event.x - row.x) / row.stride).toInt().coerceIn(0, row.modules - 1)
        val bit = segments.barUnder(
            row.kind, event.x, event.y,
            row.x + row.stride * index, row.top, row.cell, row.height
        )
        if (bit == 0) return false
        val held = burnt.getOrPut(row.key) { IntArray(row.modules) }
        if (held.size != row.modules) {
            burnt[row.key] = IntArray(row.modules).also { it[index] = bit }
        } else {
            held[index] = held[index] xor bit
        }
        onPoked?.invoke()
        invalidate()
        return true
    }

    /**
     * Type, centred in its cell, and nothing round it.
     *
     * No panel, no hinge, no drum. What makes the other three idioms
     * objects is exactly what this one has to be without, or it is a
     * flip card with the card taken away rather than a clock printed on
     * a screen.
     */
    private fun drawAsPrint(
        canvas: Canvas, text: String, x: Float, top: Float, w: Float, h: Float
    ) {
        ink.color = theme.decimal
        ink.typeface = faceFor()
        ink.textAlign = Paint.Align.CENTER
        fitInk(text, w, h)
        canvas.drawText(text, x + w / 2f, top + h / 2f + inkOffset(), ink)
    }

    /**
     * A card with the number printed on it, hinged across the middle.
     *
     * The leaf falls in two halves of the same movement: the old card's top
     * folds down over its own bottom, and when it is flat the new card's
     * top swings up from underneath. Drawn with a scale rather than a real
     * rotation because the two look the same edge-on and one of them needs
     * a camera.
     */
    private fun drawAsCard(
        canvas: Canvas,
        text: String,
        from: String?,
        progress: Float,
        x: Float,
        top: Float,
        w: Float,
        h: Float
    ) {
        drawLeaf(canvas, x, top, w, h)
        ink.color = theme.decimal
        ink.typeface = faceFor()
        fitInk(text, w, h)
        // The half that is not moving shows the new number at once — which
        // is what a split-flap does, and why the top and bottom of one
        // briefly disagree.
        val settled = from == null || progress >= 1f
        drawHalf(canvas, if (settled) text else from!!, x, top, w, h, upper = true, at = 1f)
        drawHalf(canvas, text, x, top, w, h, upper = false, at = 1f)
        if (!settled) {
            if (progress < 0.5f) {
                drawHalf(canvas, from!!, x, top, w, h, upper = true, at = 1f - progress * 2f)
            } else {
                drawHalf(canvas, text, x, top, w, h, upper = true, at = (progress - 0.5f) * 2f)
            }
        }
        drawHinge(canvas, x, top, w, h)
    }

    /** The blank card a number is printed on. */
    private fun drawLeaf(canvas: Canvas, x: Float, top: Float, w: Float, h: Float) {
        card.color = theme.rim
        card.alpha = 60
        canvas.drawRoundRect(x, top, x + w, top + h, h * 0.08f, h * 0.08f, card)
    }

    /**
     * The hinge, which is the whole reason the thing reads as two cards
     * and not as one number cut in half.
     *
     * Its own function because the numbers are not the only thing on this
     * face printed on a card. The day of the week sat beside them on
     * nothing at all — a word floating next to a stack of leaves — and a
     * card with no hinge in a row of cards with hinges is the one place
     * the eye goes.
     */
    private fun drawHinge(canvas: Canvas, x: Float, top: Float, w: Float, h: Float) {
        val mid = top + h / 2f
        card.color = theme.face
        card.alpha = 255
        canvas.drawRect(x, mid - h * 0.012f, x + w, mid + h * 0.012f, card)
    }

    private fun drawHalf(
        canvas: Canvas,
        text: String,
        x: Float,
        top: Float,
        w: Float,
        h: Float,
        upper: Boolean,
        at: Float
    ) {
        val mid = top + h / 2f
        canvas.save()
        canvas.clipRect(x, if (upper) top else mid, x + w, if (upper) mid else top + h)
        canvas.scale(1f, at.coerceAtLeast(0.001f), x + w / 2f, mid)
        canvas.drawText(text, x + w / 2f, mid + inkOffset(), ink)
        canvas.restore()
    }

    /**
     * A drum with the numbers round it, seen through a window.
     *
     * The one leaving climbs out of the top as the one arriving comes up
     * from the bottom, which is the way a mechanical counter goes: numbers
     * increase, so the drum turns towards you and the digits rise.
     */
    private fun drawAsRoller(
        canvas: Canvas,
        text: String,
        from: String?,
        progress: Float,
        x: Float,
        top: Float,
        w: Float,
        h: Float
    ) {
        card.color = theme.rim
        card.alpha = 50
        canvas.drawRoundRect(x, top, x + w, top + h, h * 0.22f, h * 0.22f, card)
        ink.color = theme.decimal
        ink.typeface = faceFor()
        fitInk(text, w, h)
        canvas.save()
        canvas.clipRect(x, top, x + w, top + h)
        val eased = ROLL_EASE.getInterpolation(progress.coerceIn(0f, 1f))
        val baseline = top + h / 2f + inkOffset()
        if (from != null && progress < 1f) {
            canvas.drawText(from, x + w / 2f, baseline - h * eased, ink)
            canvas.drawText(text, x + w / 2f, baseline + h * (1f - eased), ink)
        } else {
            canvas.drawText(text, x + w / 2f, baseline, ink)
        }
        drawCurve(canvas, x, top, w, h)
        canvas.restore()
    }

    /**
     * The shading that makes a drum a drum.
     *
     * The window is flat and the thing behind it is not: a cylinder turns
     * away from the eye at both ends of the slot, so the number goes into
     * shadow before it reaches the edge and there is a band across the
     * middle where the drum is facing you square on. Painted over the
     * digit rather than under it, because what is being drawn is the
     * light on the paper of the drum, and the number is printed on that
     * paper.
     *
     * In the face's own colour, so it reads as the number going away into
     * the dark of the case rather than as a grey wash laid on top of it —
     * and it works on a light theme for the same reason, where going away
     * means going pale.
     */
    private fun drawCurve(canvas: Canvas, x: Float, top: Float, w: Float, h: Float) {
        val dark = theme.face and 0xFFFFFF
        curve.shader = android.graphics.LinearGradient(
            0f, top, 0f, top + h,
            intArrayOf(
                dark or (DRUM_SHADE shl 24),
                dark,
                (theme.rim and 0xFFFFFF) or (DRUM_SHEEN shl 24),
                dark,
                dark or (DRUM_SHADE shl 24)
            ),
            floatArrayOf(0f, DRUM_REACH, 0.5f, 1f - DRUM_REACH, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(x, top, x + w, top + h, curve)
        curve.shader = null
    }

    private val curve = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Sizes [ink] so [text] fills a box of [w] by [h].
     *
     * Measured off the glyphs rather than off the font size, because the
     * two are not the same number and one of the three alphabets here is
     * not ours: a point size that fills the box in a grotesque leaves
     * theirs sitting in the middle of it like a footnote, which is what it
     * did. What matters is how big the ink is, so the ink is what is
     * asked.
     */
    private fun fitInk(text: String, w: Float, h: Float) {
        if (text.isEmpty()) return
        ink.textSize = h
        val gauge = gauge()
        if (gauge.wide <= 0f || gauge.tall <= 0f) return
        // One character to a cell, whatever the cell is holding.
        val cell = w / text.length
        ink.textSize = h * minOf(cell * FILL / gauge.wide, h * FILL / gauge.tall)
    }

    /** The ink of the widest and the tallest digit, at [ink]'s size now. */
    private class Gauge(val wide: Float, val tall: Float, val top: Float, val bottom: Float)

    /**
     * All ten digits measured together, rather than the one in this cell.
     *
     * The reason a `1` on this face was a fifth taller than the digits
     * either side of it, in every photograph of a card, a drum or a
     * printed clock this app has ever taken.
     *
     * A cell is sized to fill its box, and a box has two sides: the size
     * that fits across and the size that fits down, whichever is smaller.
     * A `1` is a quarter as wide as an `8` in the ink, so its width never
     * came close to binding and it was drawn at the full height of the
     * cell — while every other digit was squeezed to fit the width. The
     * cell was the same size in both cases. What differed was the glyph
     * inside it, which is exactly what must *not* decide the size of
     * type: a row of numbers is a row of one size, and a display where
     * one numeral is bigger than its neighbours reads as a broken font.
     *
     * So the ten are measured as a set and the widest and the tallest of
     * them settle the size, once, for whatever the cell happens to be
     * showing. The baseline comes from the same measurement for the same
     * reason — centring each glyph on its own ink puts a `1` and a `7` at
     * two different heights.
     *
     * Not cached: it is ten calls to the text engine per row per frame,
     * and the alternative is a cache to invalidate whenever the typeface,
     * the size or the script changes, which is three ways to draw the
     * wrong thing to save arithmetic nobody can measure.
     */
    private fun gauge(): Gauge {
        val bounds = android.graphics.Rect()
        var wide = 0
        var tall = 0
        var top = 0
        var bottom = 0
        for (i in DIGITS.indices) {
            ink.getTextBounds(DIGITS, i, i + 1, bounds)
            wide = maxOf(wide, bounds.width())
            tall = maxOf(tall, bounds.height())
            top = minOf(top, bounds.top)
            bottom = maxOf(bottom, bounds.bottom)
        }
        return Gauge(wide.toFloat(), tall.toFloat(), top.toFloat(), bottom.toFloat())
    }

    /**
     * Where the baseline goes so the ink sits in the middle of its box.
     *
     * Not the font's own middle: a digit has no descender and their
     * alphabet hangs differently again, so centring on the metrics leaves
     * the row sitting high or low depending on which alphabet it is in.
     */
    private fun inkOffset(): Float {
        val gauge = gauge()
        return -(gauge.top + gauge.bottom) / 2f
    }

    // ------------------------------------------------------ punctuation

    /**
     * The ink the punctuation is drawn in, lit or unlit.
     *
     * The same two colours the bars use, because the colon is part of the
     * readout and not a caption on it.
     */
    private fun punctuation(on: Boolean) {
        lit.color = if (on) theme.decimal else theme.minorTick
        lit.alpha = if (on) 255 else GHOST_ALPHA
    }

    /**
     * The two small arrows that say a drum can be turned.
     *
     * Without them this face is a number and a banner, and nothing on it
     * says the number is the control. A dial does not have this problem —
     * a hand sticking out of a clock is a handle and everybody knows it —
     * and a row of digits has to be told to look grabbable.
     *
     * Faint on purpose. They are a hint and not furniture: once a finger
     * is on the drum they have done their job, and a clock covered in
     * arrows is a form.
     */
    private fun drawHandles(canvas: Canvas, cx: Float, top: Float, h: Float) {
        punctuation(false)
        lit.alpha = HANDLE_ALPHA
        val wide = h * 0.13f
        val tall = h * 0.075f
        val away = h * 0.24f
        for (up in listOf(true, false)) {
            val tip = if (up) top - away - tall else top + h + away + tall
            val base = if (up) top - away else top + h + away
            val path = android.graphics.Path().apply {
                moveTo(cx, tip)
                lineTo(cx + wide / 2f, base)
                lineTo(cx - wide / 2f, base)
                close()
            }
            val was = lit.style
            lit.style = Paint.Style.FILL
            canvas.drawPath(path, lit)
            lit.style = was
        }
    }

    /**
     * The two dots between the groups.
     *
     * A third and two thirds of the way down, which is where every clock
     * with a colon on it puts them and — checked afterwards, not chosen —
     * within half a percent of where the Comet's drawing puts its two
     * lamps. Their size is the display's own and not the bar's: see
     * [Segments.separator], which is there because a colon drawn at the
     * thickness of a hairline stroke disappears.
     */
    private fun drawColon(canvas: Canvas, cx: Float, top: Float, h: Float) {
        val breathing = blinkColon && breathingColon
        val on = !blinkColon || breathing || (nowMs() / 1000L) % 2L == 0L
        punctuation(on)
        // Breathing takes the same second the blink would have used and
        // spends it going round rather than switching: full at the top of
        // the second, down to a quarter at the half, and back. Applied
        // over the lit colour rather than instead of it, so the dots stay
        // the display's own colour throughout.
        if (breathing) {
            lit.alpha = (255 * DigitalReadout.breath(nowMs())).toInt().coerceIn(0, 255)
        }
        val was = lit.strokeWidth
        lit.strokeWidth = h * Segments.separator(kind()) * weight
        // The panel's whole alphabet leans, and on the drawing so does its
        // colon: the upper lamp sits to the right of the lower one. Two
        // dots on a vertical line between two leaning digits are the only
        // upright thing on the panel, and they show.
        val lean = if (script == DigitScript.ROMAN_COMET) h * CometPanel.COLON_LEAN else 0f
        canvas.drawPoint(cx + lean, top + h * 0.32f, lit)
        canvas.drawPoint(cx - lean, top + h * 0.68f, lit)
        lit.strokeWidth = was
    }

    /**
     * The sun or the moon, where AM and PM go.
     *
     * Drawn here rather than borrowed from [SkyGlyph], which is the dial's
     * sky and answers a different question: that one asks where the sun
     * actually is, works out the moon's phase and slides one into the other
     * through a real sunset. This is a mark on a display saying which half
     * of the day the reading belongs to — it must be the same two shapes
     * every time or it stops telling you anything, and it is drawn in the
     * bars' own ink because it is part of the readout and not a picture on
     * top of it.
     */
    private fun drawToken(canvas: Canvas, sun: Boolean, cx: Float, top: Float, h: Float) {
        punctuation(true)
        val r = h * 0.20f
        val cy = top + h / 2f
        if (sun) {
            canvas.drawCircle(cx, cy, r * 0.62f, lit)
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val dx = kotlin.math.cos(angle).toFloat()
                val dy = kotlin.math.sin(angle).toFloat()
                canvas.drawLine(
                    cx + dx * r * 0.95f, cy + dy * r * 0.95f,
                    cx + dx * r * 1.35f, cy + dy * r * 1.35f, lit
                )
            }
            return
        }
        // A crescent, cut rather than drawn: an arc with a second disc
        // taken out of it is the shape, and two arcs meeting at their
        // horns is a leaf.
        val path = android.graphics.Path().apply {
            addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            op(
                android.graphics.Path().apply {
                    addCircle(cx + r * 0.62f, cy - r * 0.24f, r * 0.92f, android.graphics.Path.Direction.CW)
                },
                android.graphics.Path.Op.DIFFERENCE
            )
        }
        val wasStyle = lit.style
        lit.style = Paint.Style.FILL
        canvas.drawPath(path, lit)
        lit.style = wasStyle
    }

    private fun drawSlash(canvas: Canvas, x: Float, top: Float, w: Float, h: Float) {
        punctuation(true)
        val s = lit.strokeWidth * 0.8f
        canvas.drawLine(x + s, top + h - s, x + w - s, top + s, lit)
    }

    companion object {

        /**
         * How wide a cell is against its height.
         *
         * Seven bars make a narrow digit and sixteen make a squarer one —
         * there are four diagonals in there and a diagonal across a slot
         * is a stroke, not a letter.
         *
         * There was a second one here for Rome's module printed as type,
         * back when Rome's numerals were the time on a flip card. Rome
         * writes the date now, on a rail made of its own metal, and a
         * rail is never printed — so the number went with the case that
         * used it.
         */
        private const val DIGIT_RATIO = 0.55f

        /** The space between two digits, and the tighter one by the dots. */
        private const val GAP = 0.28f
        private const val TIGHT_GAP = 0.13f

        /** How much of the face is left clear either side. */
        private const val MARGIN = 0.06f

        /** And the tallest a digit may be, as a share of the face. */
        private const val TALLEST = 0.34f

        /**
         * The same two, for a clock that has the screen to itself.
         *
         * The card's numbers are a compromise with the gear in one corner,
         * the toolbox in the other and five buttons along the bottom. With
         * those gone the only thing left to be polite to is the edge of
         * the glass, and a bedside clock that is still a third of the
         * height of the screen is a bedside clock nobody can read from the
         * bed.
         */
        private const val BEDSIDE_MARGIN = 0.035f
        private const val BEDSIDE_TALLEST = 0.62f

        /**
         * How much of the height everything drawn may take between it.
         *
         * Generous, because on every face laid out in a phone-shaped
         * window the width runs out first and this never bites. It bites
         * on the one thing whose shape somebody else chooses: a widget
         * pulled four cells wide and one tall, where the room is all
         * horizontal — and where a panel with rounded corners is drawn
         * round the lot, so the block has to keep clear of the corners as
         * well as the edges.
         */
        private const val BLOCK_FILL = 0.95f
        private const val CHIP_BLOCK_FILL = 0.80f

        /** The longest a press can be and still count as a tap. */
        private const val TAP_MS = 400L

        /**
         * The ladder of other cities: how big a rung is, how much air is
         * above the first one and between them, how far the names sit from
         * the digits, and how loud a name is against a lit bar.
         *
         * Quiet on purpose. This is a second display on the same
         * instrument, not a second clock: it has to be readable from the
         * same distance and it must never be the first thing the eye lands
         * on, which is what a row of digits the same size as the time
         * would be.
         */
        private const val CITY_SCALE = 0.26f
        private const val CITY_GAP = 0.30f
        private const val CITY_STEP = 0.34f
        private const val CITY_NAME = 0.62f
        private const val CITY_GUTTER = 0.55f
        private const val CITY_ALPHA = 170

        /**
         * The day of the week beside the date: how big the label is
         * against a date digit, how far it sits from the numbers, how
         * loud it is, and roughly how much of a cell one letter of it
         * takes — which is only used for reserving room, so an estimate
         * that errs generous is the right kind of wrong.
         */
        private const val WEEKDAY_SIZE = 0.80f
        private const val WEEKDAY_GAP = 0.55f
        private const val WEEKDAY_ALPHA = 190

        /** And the air either side of it on the card it is printed on. */
        private const val WEEKDAY_PAD = 0.16f

        /**
         * Wider on a lit face, where the day is a numeral like the ones
         * beside it.
         *
         * `4 27/08` with the ordinary gap in it is one number and a date;
         * with a whole dark cell between them it is two readings, which
         * is what it is.
         */
        private const val WEEKDAY_LIT_GAP = 1.10f

        /**
         * How much wider than tall a face has to be before it is lying
         * down.
         *
         * Anything past this and the date comes off. A clock on its side
         * has the width for the time and nothing else: the block is
         * centred as one object, so a date under a row of digits that
         * already fills the screen pushes the time up off the middle and
         * hangs a small line of numbers in the space underneath it. A
         * phone turned sideways showed exactly that, in all four
         * mechanisms.
         *
         * Well past square, because a widget two cells by two is not a
         * clock lying down, it is a small clock.
         */
        private const val LYING_DOWN = 1.30f

        /**
         * The two lamps, in digit heights, straight off the drawing.
         *
         * The bell is taller than it is wide because two arcs of ringing
         * hang under it, and those arcs are half its height — leave them
         * out and it is a bell that is not doing anything.
         */
        private const val BELL_WIDE = 0.3435f
        private const val BELL_TALL = 0.5597f
        private const val MOON_SIDE = 0.3189f
        private const val WEEKDAY_CELLS = 0.42f

        /** And the alarm flag under everything: its size and its bell. */
        private const val ALARM_SCALE = 0.24f
        private const val ALARM_GAP = 0.34f
        private const val ALARM_FLAG = 1.15f

        /** How big the city under the digits is, against a digit. */
        private const val CAPTION_SCALE = 0.30f

        /** The date's row, against the time's. */
        private const val DATE_SCALE = 0.34f
        private const val DATE_GAP = 0.22f

        /** How much of its box a printed glyph fills. */
        private const val FILL = 0.86f

        /** The ten, for measuring a row of type against — see [gauge]. */
        private const val DIGITS = "0123456789"

        /**
         * How dark the ends of a drum's window go, and how far in the
         * shade reaches from each end.
         *
         * A drum is a cylinder seen through a slot, so the number on it
         * curves away at the top and the bottom and the light leaves it
         * before the edge of the window does. Without this the roller and
         * the flip card were the same object at rest: a rounded panel
         * with a number in it, told apart only by watching one of them
         * change. A photograph of the four mechanisms had two that were
         * the same picture.
         */
        private const val DRUM_SHADE = 0xD0
        private const val DRUM_REACH = 0.34f

        /** And the sheen across the middle of it, where the drum faces you. */
        private const val DRUM_SHEEN = 0x24

        /**
         * And how much of its cell a lit one does. The rest is the daylight
         * between one module and the next, which is what stops the foot of
         * an L running into the letter beside it.
         */
        private const val LETTER_FILL = 0.80f

        /** What a card or a drum has its numbers printed in. */
        private val PRINT: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

        /**
         * How faint an unlit bar is.
         *
         * Fainter on the sixteen-bar module, which has more than twice as
         * many of them: the same grey that reads as a ghost behind a digit
         * reads as a thicket behind a letter.
         */
        private const val GHOST_ALPHA = 34

        /** How faint the two arrows over a drum are. */
        private const val HANDLE_ALPHA = 110
        private const val GHOST_ALPHA_SIXTEEN = 20

        private const val DAY_MS = 86_400_000L

        /**
         * How far a finger travels for one click of the drum.
         *
         * A whole screen-height of dragging comes to about thirty clicks,
         * which is the right order: the tens drum reaches any hour in two
         * or three and the units drum any minute in a flick.
         */
        private const val DETENT_DP = 26f

        /** How long a fling is worth, and how far it is allowed to carry. */
        private const val FLING_SECONDS = 0.22f
        private const val MAX_FLING = 22
        private const val FLING_MS_PER_STEP = 34L

        private const val FRAME_MS = 16L
        private const val FLIP_MS = 260L
        private const val ROLL_MS = 320L

        private val ROLL_EASE = android.view.animation.DecelerateInterpolator(1.6f)
    }
}
