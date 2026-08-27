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

    /** The one engine all three lit displays go through. */
    private val segments = SegmentPainter()

    /** How thick a bar is, as a share of the module's height. */
    var weight: Float = 0.055f
        set(value) {
            field = value
            invalidate()
        }

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
        DigitStyle.CARD -> FLIP_MS
        DigitStyle.ROLLER -> ROLL_MS
    }

    // ----------------------------------------------------- what is shown

    private fun options() = DigitalReadout.Options(
        script = script,
        hour24 = hour24,
        leadingZero = leadingZero,
        seconds = showSeconds
    )

    /** The time this face is showing, which the tests can move. */
    internal var atMs: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    private fun nowMs(): Long = atMs ?: TimeKeeper.nowMs()

    private fun readout(): List<Cell> {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs() }
        return DigitalReadout.time(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND),
            options()
        )
    }

    private fun dateLine(): List<Cell> {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs() }
        return DigitalReadout.date(
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.YEAR),
            dateDayFirst,
            options()
        )
    }

    /** For the tests: the cells this face is showing right now. */
    internal fun cellsForTest(): List<Cell> = readout()

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
        // On Rome's display the separator is a module with its dot lit, so
        // it takes a module's room. On the other two it is punctuation and
        // closes ranks with the groups either side of it.
        Cell.Colon -> if (dotSeparator()) 1f else 0.34f
        Cell.Slash -> if (dotSeparator()) 1f else 0.5f
        is Cell.Token -> 1f
    }

    /** Which of the three displays this script goes on. */
    private fun kind(): Segments.Kind = when (script) {
        DigitScript.ROMAN -> Segments.Kind.SIXTEEN
        DigitScript.YAUTJA -> Segments.Kind.STAR
        else -> Segments.Kind.SEVEN
    }

    /** Whether the separator is a module of its own rather than two dots. */
    private fun dotSeparator(): Boolean =
        style == DigitStyle.SEGMENT && script == DigitScript.ROMAN

    /**
     * How wide a cell is against its own height.
     *
     * The lit displays each have their own answer and it is a measurement,
     * not a preference — see [Segments.aspect]. A card or a drum is a
     * different object and keeps the proportions a card has.
     */
    private fun cellRatio(): Float = when {
        style == DigitStyle.SEGMENT -> Segments.aspect(kind())
        script == DigitScript.ROMAN -> ROMAN_RATIO
        else -> DIGIT_RATIO
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
        canvas.drawColor(theme.face)
        if (width <= 0 || height <= 0) return

        laid.clear()
        val cells = readout()
        val date = if (showDate) dateLine() else emptyList()

        // One digit's width, chosen so the longest thing on the face fits
        // with a margin either side. Roman at twenty-three fifty-nine is
        // three times the width of the same time in ours, and the row that
        // decides is whichever is longer today — asked every frame, because
        // it changes when the hour does.
        val room = width * (1f - 2f * MARGIN)
        val widest = maxOf(rowWidth(cells), rowWidth(date) * DATE_SCALE)
        val digitW = if (widest > 0f) minOf(room / widest, height * TALLEST) else 0f
        val digitH = digitW / cellRatio()

        lit.strokeWidth = digitH * weight * 1.6f
        ink.textSize = digitH * 0.92f

        // The date sits under the time, and the two together are centred on
        // the face rather than the time alone: a clock with the date on
        // reads as one block, and centring the big row leaves the small one
        // hanging off the bottom of it.
        val dateH = if (date.isEmpty()) 0f else digitH * DATE_SCALE + digitH * DATE_GAP
        val top = (height - digitH - dateH) / 2f
        drawRow(canvas, cells, top, digitW, digitH, "t")
        if (date.isNotEmpty()) {
            drawRow(
                canvas, date, top + digitH + digitH * DATE_GAP,
                digitW * DATE_SCALE, digitH * DATE_SCALE, "d"
            )
        }
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
        tag: String
    ) {
        val total = rowWidth(cells) * digitW
        var x = (width - total) / 2f
        val wasStroke = lit.strokeWidth
        val wasText = ink.textSize
        lit.strokeWidth = digitH * weight * 1.6f
        ink.textSize = digitH * 0.92f
        for ((i, cell) in cells.withIndex()) {
            val w = widthOf(cell) * digitW
            when (cell) {
                is Cell.Number -> drawNumber(canvas, cell, "$tag$i", x, top, w, digitH)
                // Rome's separator is a module with its dot lit, which is
                // what the drawing does and what makes VII·XII read as one
                // instrument rather than as two with a colon between them.
                Cell.Colon -> if (dotSeparator()) {
                    drawAsSegments(canvas, "·", x, top, w, digitH, "$tag$i")
                } else {
                    drawColon(canvas, x + w / 2f, top, digitH)
                }
                Cell.Slash -> if (dotSeparator()) {
                    drawAsSegments(canvas, "·", x, top, w, digitH, "$tag$i")
                } else {
                    drawSlash(canvas, x, top, w, digitH)
                }
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
            DigitStyle.CARD -> drawAsCard(canvas, cell.text, from, progress, x, top, w, h)
            DigitStyle.ROLLER -> drawAsRoller(canvas, cell.text, from, progress, x, top, w, h)
        }
        if (progress >= 1f) leaving.remove(key)
    }

    /**
     * Bars behind a mask, on whichever of the three displays this script
     * belongs to — see [Segments].
     *
     * All three are lit now. Their numerals were a font here until the
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
        segments.thickness = weight
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
        if (!pokeable || style != DigitStyle.SEGMENT) return super.onTouchEvent(event)
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
        val mid = top + h / 2f
        card.color = theme.rim
        card.alpha = 60
        canvas.drawRoundRect(x, top, x + w, top + h, h * 0.08f, h * 0.08f, card)
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
        // The hinge, which is the whole reason the thing reads as two cards
        // and not as one number cut in half.
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
        canvas.drawText(text, x + w / 2f, mid + inkOffset(text), ink)
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
        val baseline = top + h / 2f + inkOffset(text)
        if (from != null && progress < 1f) {
            canvas.drawText(from, x + w / 2f, baseline - h * eased, ink)
            canvas.drawText(text, x + w / 2f, baseline + h * (1f - eased), ink)
        } else {
            canvas.drawText(text, x + w / 2f, baseline, ink)
        }
        canvas.restore()
    }

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
        val bounds = android.graphics.Rect()
        ink.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        ink.textSize = h * minOf(
            w * FILL / bounds.width(),
            h * FILL / bounds.height()
        )
    }

    /**
     * Where the baseline goes so the ink sits in the middle of its box.
     *
     * Not the font's own middle: a digit has no descender and their
     * alphabet hangs differently again, so centring on the metrics leaves
     * the row sitting high or low depending on which alphabet it is in.
     */
    private fun inkOffset(text: String): Float {
        val bounds = android.graphics.Rect()
        ink.getTextBounds(text, 0, text.length, bounds)
        return -(bounds.top + bounds.bottom) / 2f
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

    private fun drawColon(canvas: Canvas, cx: Float, top: Float, h: Float) {
        val on = !blinkColon || (nowMs() / 1000L) % 2L == 0L
        punctuation(on)
        canvas.drawPoint(cx, top + h * 0.32f, lit)
        canvas.drawPoint(cx, top + h * 0.68f, lit)
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
         * is a stroke, not a letter. Rome's clock comes out shorter as
         * well as wider, which is the honest price of writing twenty-three
         * fifty-nine as XXIII:LIX and not something to hide by squeezing.
         */
        private const val DIGIT_RATIO = 0.55f
        private const val ROMAN_RATIO = 0.72f

        /** The space between two digits, and the tighter one by the dots. */
        private const val GAP = 0.28f
        private const val TIGHT_GAP = 0.13f

        /** How much of the face is left clear either side. */
        private const val MARGIN = 0.06f

        /** And the tallest a digit may be, as a share of the face. */
        private const val TALLEST = 0.34f

        /** The date's row, against the time's. */
        private const val DATE_SCALE = 0.34f
        private const val DATE_GAP = 0.22f

        /** How much of its box a printed glyph fills. */
        private const val FILL = 0.86f

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
        private const val GHOST_ALPHA_SIXTEEN = 20

        private const val FRAME_MS = 16L
        private const val FLIP_MS = 260L
        private const val ROLL_MS = 320L

        private val ROLL_EASE = android.view.animation.DecelerateInterpolator(1.6f)
    }
}
