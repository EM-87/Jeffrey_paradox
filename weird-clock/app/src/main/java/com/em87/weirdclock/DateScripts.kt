package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The four other ways this dial writes a date, and the one printed one.
 *
 * Wound far enough, the row of digits under the sky stops being digits.
 * Roman numerals go through a sixteen-bar module because seven bars can
 * make ten shapes and no letters; before 1970 they are set in serif type
 * instead, because a lit bar is nineteen-seventies electronics and a date
 * from 1750 shown on one is the same anachronism as Neptune over Babylon.
 * Further back still there are hieroglyphs, and behind those, wedges.
 *
 * All of it lifted out of [ClockView] whole. It is a thousand lines about
 * the shapes of letters and it borrows almost nothing from a clock: two
 * colours, which way round the day and the month go, and what instant is
 * being written. Everything else — the paints, the scratch paths, the
 * counters the tests read — belongs to the writing rather than to the
 * dial, and it lived in the view only because that is where it was first
 * typed.
 */
class DateScripts {

    /**
     * What a row of writing needs to know about the frame it is on.
     *
     * The four things this thousand lines actually borrows from a clock,
     * gathered into one parameter rather than reached for through a view.
     * [ink] is read for its colour and its alpha and never kept — every
     * display here mixes its own paint and takes those two from the dial,
     * so that a date fading in fades in whatever alphabet it is written
     * in.
     */
    class Frame(
        val ink: Paint,
        val atMs: Long,
        val dayFirst: Boolean,
        val boxWidth: Int,
        val yautja: android.graphics.Typeface? = null
    )

    /**
     * The far-future face — see [Yautja].
     *
     * Set up once and handed the typeface per frame, because the frame is
     * where anything belonging to the outside world comes in.
     */
    private val yautjaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.10f
    }

    /** For the tests: characters written in the far-future face. */
    fun yautjaCharsForTest(): Int = yautjaChars

    private var yautjaChars = 0

    /**
     * A date in the alphabet nobody here can read.
     *
     * It used to go through a reconstruction of that alphabet, worked out
     * arm by arm from a photograph — which got the digits about right and
     * the letters wrong, and could only ever write digits anyway. With the
     * real font in hand it is simply text, so the row can say a word.
     *
     * Fitted to the box the way the printed date is, and for the same
     * reason: a far-future date is not a fixed number of characters wide.
     */
    fun drawYautjaDate(
        canvas: Canvas, frame: Frame, text: String, cx: Float, top: Float, digitH: Float
    ) {
        nothingWritten()
        val face = frame.yautja ?: return
        if (text.isEmpty()) return
        yautjaPaint.typeface = face
        yautjaPaint.color = frame.ink.color
        yautjaPaint.alpha = frame.ink.alpha
        yautjaPaint.textSize = digitH * 1.25f
        val room = frame.boxWidth * 0.86f
        val wide = yautjaPaint.measureText(text)
        if (wide > room && wide > 0f) yautjaPaint.textSize *= room / wide
        val metrics = yautjaPaint.fontMetrics
        canvas.drawText(text, cx, top - metrics.ascent, yautjaPaint)
        yautjaChars = text.length
    }

    /** A lit bar on one of the other two displays — see [Segments]. */
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ------------------------------------------------- the other alphabets

    /** Scratch, because a glyph is rebuilt on every frame. */
    private val glyphPath = Path()

    /**
     * The one engine every lit display in this app goes through.
     *
     * The unlit bars are off here. On the dial the date is a caption under
     * the hands, not the instrument itself, and a row of faint ghosts
     * under it reads as smudge rather than as mechanism — which is the one
     * place in this app where that is true.
     */
    private val segments = SegmentPainter().apply {
        ghosts = false
    }

    /** And scratch for the round parts of a hieroglyph. */
    private val signOval = RectF()

    /**
     * The line a hieroglyph is drawn with.
     *
     * Stroked, not filled: a carved sign is a line round a thing, and the
     * other two alphabets on this dial are lit bars, which is the whole
     * difference between a display and an inscription.
     */
    private val carvedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * How many of each kind of module the last row put down.
     *
     * The only thing a picture of a date proves is that ink went down
     * somewhere. These say which display was actually used and how much of
     * it, which is what tells a test that a Roman year went through the
     * sixteen-bar row rather than through the seven-bar one — where every
     * letter in it would simply have been dropped on the floor.
     */
    private var barsPainted = 0

    /**
     * A row that used none of these displays, and says so.
     *
     * Cleared rather than left at whatever the last legible century put
     * there, or a test asking "which display wrote this" gets yesterday's
     * answer about a row that is not on screen any more.
     */
    fun nothingWritten() {
        barsPainted = 0
        yautjaChars = 0
        egyptiansPainted = 0
        wedgesPainted = 0
        printedChars = 0
    }

    /** For the tests: sixteen-bar modules in the last row the sky wrote. */
    internal fun barsPaintedForTest(): Int = barsPainted

    /**
     * One bar of a segment display: a long hexagon, pointed at both ends.
     *
     * The points are what make a row of these read as a display rather
     * than as a diagram of one. Two bars meeting at a corner leave a thin
     * V of background between them, so the corner of an `0` is a corner
     * and not a blob, and the four diagonals of an `X` cross at a waist
     * instead of a lump.
     */
    private fun barPath(
        x0: Float, y0: Float, x1: Float, y1: Float, t: Float,
        // How far each end stops short of the point it was given, in
        // thicknesses. The default leaves a hairline where two bars meet
        // at a corner, which is what makes an `0` have corners. An arm of
        // the star wants the opposite at its inner end: every arm of one
        // star starts at that star's middle and they have to join there,
        // or the glyph is a scatter of petals rather than a stroke.
        startInset: Float = 0.8f,
        endInset: Float = 0.8f
    ) {
        glyphPath.reset()
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 0.01f) return
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val nx = -uy * t
        val ny = ux * t
        // The tip stops short of the true corner, which is where the gap
        // between two bars comes from.
        val head = t * startInset
        val foot = t * endInset
        val sh = min(t * 1.3f, (len - head - foot) * 0.35f).coerceAtLeast(0f)
        val ax = x0 + ux * head
        val ay = y0 + uy * head
        val bx = x1 - ux * foot
        val by = y1 - uy * foot
        // The ends are flat, not pointed. A real segment is a stamped
        // shape with a short square end and the corners cut off it, and a
        // row of needle-sharp bars reads as a drawing of a display rather
        // than as one.
        val fx = -uy * t * 0.34f
        val fy = ux * t * 0.34f
        glyphPath.moveTo(ax + fx, ay + fy)
        glyphPath.lineTo(ax + ux * sh + nx, ay + uy * sh + ny)
        glyphPath.lineTo(bx - ux * sh + nx, by - uy * sh + ny)
        glyphPath.lineTo(bx + fx, by + fy)
        glyphPath.lineTo(bx - fx, by - fy)
        glyphPath.lineTo(bx - ux * sh - nx, by - uy * sh - ny)
        glyphPath.lineTo(ax + ux * sh - nx, ay + uy * sh - ny)
        glyphPath.lineTo(ax - fx, ay - fy)
        glyphPath.close()
    }

    /**
     * The word signs a date is built from — see [Egyptian.Word].
     *
     * Drawn with the same stroked line the numerals use, because they are
     * the same kind of thing: a carved outline round a picture of
     * something, and at this size the picture has to be reduced to the two
     * or three lines that make it recognisable. A palm rib is a stem with
     * notches down one side. A moon is a crescent. The sun is a disc with
     * a dot in it, which is exactly how the Egyptians drew it and is why
     * every astronomer since has used the same mark.
     */
    private fun drawEgyptianWord(
        canvas: Canvas, word: Egyptian.Word, x: Float, y: Float, w: Float, h: Float
    ) {
        val cx = x + w / 2f
        glyphPath.reset()
        when (word) {
            // The year: a palm rib stripped of its leaves, notched down one
            // side. The Egyptians counted years on one, and the word for
            // "year" is the word for the stick.
            Egyptian.Word.YEAR -> {
                glyphPath.moveTo(cx, y + h * 0.94f)
                glyphPath.lineTo(cx, y + h * 0.16f)
                glyphPath.moveTo(cx, y + h * 0.16f)
                glyphPath.lineTo(x + w * 0.22f, y + h * 0.06f)
                for (i in 0 until 4) {
                    val ny = y + h * (0.26f + i * 0.17f)
                    glyphPath.moveTo(cx, ny)
                    glyphPath.lineTo(cx + w * 0.26f, ny - h * 0.07f)
                }
            }
            // The month: the moon, lying on its back the way it does in
            // Egypt, where it never stands up on its end as it does in the
            // north.
            Egyptian.Word.MONTH -> {
                glyphPath.moveTo(x + w * 0.14f, y + h * 0.66f)
                glyphPath.cubicTo(
                    x + w * 0.18f, y + h * 0.26f,
                    x + w * 0.82f, y + h * 0.26f,
                    x + w * 0.86f, y + h * 0.66f
                )
                glyphPath.moveTo(x + w * 0.14f, y + h * 0.66f)
                glyphPath.cubicTo(
                    x + w * 0.30f, y + h * 0.50f,
                    x + w * 0.70f, y + h * 0.50f,
                    x + w * 0.86f, y + h * 0.66f
                )
            }
            // The day: the sun, a disc with its own centre marked.
            Egyptian.Word.DAY -> {
                signOval.set(x + w * 0.16f, y + h * 0.26f, x + w * 0.84f, y + h * 0.74f)
                glyphPath.addOval(signOval, Path.Direction.CW)
                signOval.set(x + w * 0.45f, y + h * 0.46f, x + w * 0.55f, y + h * 0.54f)
                glyphPath.addOval(signOval, Path.Direction.CW)
            }
            // Akhet, the inundation: water. Three ripples, which is how
            // water was written for three thousand years.
            Egyptian.Word.AKHET -> {
                for (i in 0 until 3) {
                    val wy = y + h * (0.34f + i * 0.16f)
                    glyphPath.moveTo(x + w * 0.10f, wy)
                    glyphPath.cubicTo(
                        x + w * 0.32f, wy - h * 0.09f,
                        x + w * 0.52f, wy + h * 0.09f,
                        x + w * 0.90f, wy
                    )
                }
            }
            // Peret, the coming-forth: a shoot rising out of the ground as
            // the flood goes down.
            Egyptian.Word.PERET -> {
                glyphPath.moveTo(x + w * 0.08f, y + h * 0.82f)
                glyphPath.lineTo(x + w * 0.92f, y + h * 0.82f)
                glyphPath.moveTo(cx, y + h * 0.82f)
                glyphPath.lineTo(cx, y + h * 0.28f)
                glyphPath.moveTo(cx, y + h * 0.46f)
                glyphPath.cubicTo(
                    x + w * 0.30f, y + h * 0.38f,
                    x + w * 0.24f, y + h * 0.18f,
                    x + w * 0.40f, y + h * 0.20f
                )
                glyphPath.moveTo(cx, y + h * 0.40f)
                glyphPath.cubicTo(
                    x + w * 0.72f, y + h * 0.32f,
                    x + w * 0.78f, y + h * 0.12f,
                    x + w * 0.60f, y + h * 0.14f
                )
            }
            // Shemu, the harvest: an ear of grain on its stalk.
            Egyptian.Word.SHEMU -> {
                glyphPath.moveTo(cx, y + h * 0.94f)
                glyphPath.lineTo(cx, y + h * 0.36f)
                for (i in 0 until 3) {
                    val gy = y + h * (0.20f + i * 0.16f)
                    glyphPath.moveTo(cx, gy + h * 0.10f)
                    glyphPath.cubicTo(
                        x + w * 0.24f, gy + h * 0.06f,
                        x + w * 0.26f, gy - h * 0.04f,
                        x + w * 0.40f, gy - h * 0.02f
                    )
                    glyphPath.moveTo(cx, gy + h * 0.10f)
                    glyphPath.cubicTo(
                        x + w * 0.76f, gy + h * 0.06f,
                        x + w * 0.74f, gy - h * 0.04f,
                        x + w * 0.60f, gy - h * 0.02f
                    )
                }
            }
            // The five days upon the year: a star, for the gods born on
            // them. They belong to no month and no season, and this is the
            // sign that says so.
            Egyptian.Word.UPON_THE_YEAR -> {
                for (i in 0 until 5) {
                    val a = Math.toRadians(-90.0 + i * 72.0)
                    glyphPath.moveTo(cx, y + h * 0.50f)
                    glyphPath.lineTo(
                        cx + (kotlin.math.cos(a) * w * 0.40f).toFloat(),
                        y + h * 0.50f + (kotlin.math.sin(a) * h * 0.36f).toFloat()
                    )
                }
            }
        }
        canvas.drawPath(glyphPath, carvedPaint)
    }

    /**
     * One Egyptian sign, drawn in the box from (x, y) to (x + w, y + h).
     *
     * Outlines rather than filled shapes, because that is what a carved
     * hieroglyph is: a line round a thing. They are simplified hard — a
     * coil of rope at eight pixels is a curl, and a god with his arms up
     * is a stick with two arms up — but each keeps the one feature that
     * tells it from its neighbours, which is the whole job at this size.
     */
    private fun drawEgyptianSign(
        canvas: Canvas, sign: Egyptian.Sign, x: Float, y: Float, w: Float, h: Float
    ) {
        val cx = x + w / 2f
        glyphPath.reset()
        when (sign) {
            // One: a plain upright stroke, which is what a tally is.
            Egyptian.Sign.STROKE -> {
                glyphPath.moveTo(cx, y + h * 0.10f)
                glyphPath.lineTo(cx, y + h * 0.90f)
            }
            // Ten: a heel bone, drawn as the arch everyone draws it as.
            Egyptian.Sign.HEEL -> {
                glyphPath.moveTo(x + w * 0.12f, y + h * 0.85f)
                glyphPath.cubicTo(
                    x + w * 0.12f, y + h * 0.10f,
                    x + w * 0.88f, y + h * 0.10f,
                    x + w * 0.88f, y + h * 0.85f
                )
            }
            // A hundred: a coil of rope. A curl with its end turned in,
            // which is the part that stops it reading as another arch.
            Egyptian.Sign.COIL -> {
                glyphPath.moveTo(x + w * 0.10f, y + h * 0.80f)
                glyphPath.cubicTo(
                    x + w * 0.05f, y + h * 0.20f,
                    x + w * 0.95f, y + h * 0.20f,
                    x + w * 0.80f, y + h * 0.62f
                )
                glyphPath.cubicTo(
                    x + w * 0.70f, y + h * 0.88f,
                    x + w * 0.35f, y + h * 0.72f,
                    x + w * 0.48f, y + h * 0.50f
                )
            }
            // A thousand: a lotus. A stem, two leaves off it, and the
            // flower opening at the top.
            Egyptian.Sign.LOTUS -> {
                glyphPath.moveTo(cx, y + h * 0.95f)
                glyphPath.lineTo(cx, y + h * 0.42f)
                glyphPath.moveTo(cx, y + h * 0.62f)
                glyphPath.cubicTo(
                    x + w * 0.10f, y + h * 0.58f,
                    x + w * 0.08f, y + h * 0.34f,
                    x + w * 0.26f, y + h * 0.34f
                )
                glyphPath.moveTo(cx, y + h * 0.62f)
                glyphPath.cubicTo(
                    x + w * 0.90f, y + h * 0.58f,
                    x + w * 0.92f, y + h * 0.34f,
                    x + w * 0.74f, y + h * 0.34f
                )
                glyphPath.moveTo(x + w * 0.24f, y + h * 0.12f)
                glyphPath.lineTo(cx, y + h * 0.42f)
                glyphPath.lineTo(x + w * 0.76f, y + h * 0.12f)
            }
            // Ten thousand: a finger, bent, with the nail on it.
            Egyptian.Sign.FINGER -> {
                glyphPath.moveTo(x + w * 0.34f, y + h * 0.92f)
                glyphPath.lineTo(x + w * 0.34f, y + h * 0.30f)
                glyphPath.cubicTo(
                    x + w * 0.34f, y + h * 0.08f,
                    x + w * 0.72f, y + h * 0.08f,
                    x + w * 0.70f, y + h * 0.26f
                )
                glyphPath.moveTo(x + w * 0.40f, y + h * 0.22f)
                glyphPath.lineTo(x + w * 0.64f, y + h * 0.20f)
            }
            // A hundred thousand: a tadpole, all head and tail.
            Egyptian.Sign.TADPOLE -> {
                signOval.set(x + w * 0.08f, y + h * 0.24f, x + w * 0.56f, y + h * 0.66f)
                glyphPath.addOval(signOval, Path.Direction.CW)
                glyphPath.moveTo(x + w * 0.54f, y + h * 0.50f)
                glyphPath.cubicTo(
                    x + w * 0.78f, y + h * 0.36f,
                    x + w * 0.78f, y + h * 0.82f,
                    x + w * 0.96f, y + h * 0.72f
                )
            }
            // A million: the god with his arms up, who is holding the
            // years apart. A stick figure, because at this size that is
            // all a figure can be.
            Egyptian.Sign.GOD -> {
                signOval.set(x + w * 0.38f, y + h * 0.06f, x + w * 0.62f, y + h * 0.28f)
                glyphPath.addOval(signOval, Path.Direction.CW)
                glyphPath.moveTo(cx, y + h * 0.28f)
                glyphPath.lineTo(cx, y + h * 0.66f)
                glyphPath.moveTo(x + w * 0.06f, y + h * 0.12f)
                glyphPath.lineTo(x + w * 0.30f, y + h * 0.40f)
                glyphPath.moveTo(x + w * 0.94f, y + h * 0.12f)
                glyphPath.lineTo(x + w * 0.70f, y + h * 0.40f)
                glyphPath.moveTo(cx, y + h * 0.66f)
                glyphPath.lineTo(x + w * 0.24f, y + h * 0.94f)
                glyphPath.moveTo(cx, y + h * 0.66f)
                glyphPath.lineTo(x + w * 0.76f, y + h * 0.94f)
            }
        }
        canvas.drawPath(glyphPath, carvedPaint)
    }

    /**
     * A whole number in hieroglyphs: the signs it tallies to, biggest
     * first, each one's repeats stacked in short rows.
     *
     * Nine strokes side by side is a fence rather than a nine, which is
     * why the Egyptians stacked them and why this does. The returned width
     * is how much of the row the number took, since unlike every other
     * script here a number's width depends on the number.
     */
    private fun drawEgyptianNumber(
        canvas: Canvas, value: Int, x: Float, y: Float, h: Float, unit: Float
    ): Float {
        var at = x
        for ((sign, count) in Egyptian.tally(value)) {
            val rows = Egyptian.rowsFor(count)
            val across = Egyptian.perRow(count)
            val cellH = h / rows
            for (i in 0 until count) {
                val row = i / across
                val col = i % across
                // The last row is centred under the ones above it, the way
                // a short row of tally marks sits.
                val inRow = minOf(across, count - row * across)
                val indent = (across - inRow) / 2f
                drawEgyptianSign(
                    canvas, sign,
                    at + (col + indent) * unit, y + row * cellH,
                    unit, cellH
                )
            }
            at += across * unit + unit * 0.35f
        }
        return at - x
    }

    /**
     * A whole date on one of the two other displays.
     *
     * Both scripts go through here because both have the same problem the
     * seven-bar row does not: the year is not four characters wide.
     * `MDCCCLXXXVIII` is thirteen, and a row of thirteen modules at the
     * size four digits want would run off both edges of the card, so the
     * row is measured first and shrunk to fit.
     *
     * [starFrom] is where the language changes. Everything before it is
     * drawn on the sixteen-bar module — which can write the day and the
     * month as ordinary digits — and everything from it on is drawn on the
     * star. That is the whole grammar of the far-future date: the part
     * that has to stay readable is in a display we can read, and the year
     * is in one we cannot.
     */
    fun drawOtherScript(
        canvas: Canvas, frame: Frame, text: String, cx: Float, top: Float, digitH: Float,
        starFrom: Int = Int.MAX_VALUE
    ) {
        if (text.isEmpty()) return
        // The separator is a module with only its middle dot lit, rather
        // than a hole in the row: an empty space in a row of displays
        // looks like the row stopped.
        val masks = Segments.spell(Segments.Kind.SIXTEEN, text.replace(' ', '·'))
        if (masks.isEmpty()) return
        // Fourteen per cent of the width kept clear, not six. A Roman date
        // can run to two dozen modules — XXVIII·XII·MDCCCLXXXVIII is
        // twenty-four, and a V is two of them — and shrunk to fill every
        // last pixel it ends up touching both edges of the phone, which
        // reads as a row that has overflowed even though it has not.
        val room = frame.boxWidth * 0.86f
        val gap = Segments.gap(Segments.Kind.SIXTEEN)
        val span = masks.size + (masks.size - 1) * gap
        var h = digitH
        var moduleW = h * Segments.aspect(Segments.Kind.SIXTEEN)
        if (moduleW * span > room) {
            moduleW = room / span
            h = moduleW / Segments.aspect(Segments.Kind.SIXTEEN)
        }
        val wide = moduleW * span
        val keepStyle = glyphPaint.style
        glyphPaint.style = Paint.Style.FILL
        barsPainted = masks.size
        egyptiansPainted = 0
        wedgesPainted = 0
        printedChars = 0
        // Vertically centred on the line the seven-bar row would have
        // used, so switching script does not make the date jump.
        segments.row(
            canvas, Segments.Kind.SIXTEEN, masks,
            cx - wide / 2f, top + (digitH - h) / 2f, wide, h,
            frame.ink.color, frame.ink.color
        )
        glyphPaint.style = keepStyle
    }

    /**
     * The date in hieroglyphs: three numbers, biggest sign first.
     *
     * Measured before it is drawn, because unlike every other script here
     * a number's width depends on the number — nine strokes is wider than
     * one — so where the row starts cannot be known until it is known how
     * long the row is.
     */
    fun drawEgyptianDate(canvas: Canvas, frame: Frame, cx: Float, top: Float, digitH: Float) {
        val at = frame.atMs
        val date = EgyptianCalendar.dateOf(
            at, TimeZone.getDefault().getOffset(at), SkyAge.yearOf(at)
        )
        // Regnal year, then month-of-season with its season, then day —
        // the order a scribe wrote them in, and the order they are read
        // in. Each number is preceded by the word it counts, which is the
        // part a transliteration of our own date can never have: a bare
        // "15" means nothing, and 𓆳 15 means "regnal year 15".
        val words = ArrayList<Egyptian.Word>(4)
        val counts = ArrayList<Int>(4)
        if (date.regnalYear > 0) {
            words.add(Egyptian.Word.YEAR)
            counts.add(date.regnalYear)
        }
        if (date.epagomenal) {
            // The five days upon the year belong to no month and no
            // season, and a date on one says so instead of naming either.
            words.add(Egyptian.Word.UPON_THE_YEAR)
            counts.add(date.day)
        } else {
            words.add(Egyptian.Word.MONTH)
            counts.add(date.monthOfSeason)
            words.add(
                when (date.season) {
                    EgyptianCalendar.Season.AKHET -> Egyptian.Word.AKHET
                    EgyptianCalendar.Season.PERET -> Egyptian.Word.PERET
                    else -> Egyptian.Word.SHEMU
                }
            )
            counts.add(0)
            words.add(Egyptian.Word.DAY)
            counts.add(date.day)
        }

        val h = digitH * 1.15f
        var unit = h * 0.34f
        var gap = unit * 1.1f
        fun widthOfGroup(word: Egyptian.Word, value: Int): Float {
            val sign = unit * 1.05f + unit * 0.25f
            if (value <= 0) return sign
            return sign + Egyptian.tally(value).sumOf { Egyptian.perRow(it.second) } * unit +
                Egyptian.tally(value).size * unit * 0.35f
        }
        var wide = words.indices.sumOf { widthOfGroup(words[it], counts[it]).toDouble() }
            .toFloat() + gap * (words.size - 1)
        val room = frame.boxWidth * 0.86f
        if (wide > room && wide > 0f) {
            val k = room / wide
            unit *= k
            gap *= k
            wide = room
        }
        carvedPaint.color = frame.ink.color
        carvedPaint.alpha = frame.ink.alpha
        carvedPaint.strokeWidth = (unit * 0.13f).coerceAtLeast(1.2f)
        var x = cx - wide / 2f
        var carved = 0
        for (i in words.indices) {
            drawEgyptianWord(canvas, words[i], x, top, unit * 1.05f, h)
            x += unit * 1.05f + unit * 0.25f
            carved++
            if (counts[i] > 0) {
                x += drawEgyptianNumber(canvas, counts[i], x, top, h, unit)
                carved += Egyptian.signCount(counts[i])
            }
            if (i < words.size - 1) x += gap
        }
        egyptiansPainted = carved
        barsPainted = 0
        wedgesPainted = 0
        printedChars = 0
    }

    /**
     * The date the sky is standing on, as a scribe would have set it down.
     *
     * Asked for by the drawing, by the caption that names the king, and by
     * the tests — which is why it is one function rather than three copies
     * of the same conversion.
     */
    fun egyptianDate(atMs: Long): EgyptianCalendar.Date {
        val at = atMs
        return EgyptianCalendar.dateOf(
            at, TimeZone.getDefault().getOffset(at), SkyAge.yearOf(at)
        )
    }

    /** For the tests: hieroglyphs in the last row the sky wrote. */
    internal fun egyptiansPaintedForTest(): Int = egyptiansPainted

    private var egyptiansPainted = 0

    /** For the camera: one number in hieroglyphs, drawn anywhere. */
    internal fun drawEgyptianForTest(
        canvas: Canvas, value: Int, x: Float, y: Float, h: Float
    ): Float {
        carvedPaint.color = android.graphics.Color.WHITE
        carvedPaint.alpha = 255
        carvedPaint.strokeWidth = (h * 0.34f * 0.13f).coerceAtLeast(1.2f)
        return drawEgyptianNumber(canvas, value, x, y, h, h * 0.34f)
    }

    // -------------------------------------------------------------- the wedges

    /**
     * The word signs a wedge date is built from — see [Cuneiform.Word].
     *
     * Made of the same two impressions everything else in this script is,
     * because that is all a reed has: MU is a vertical wedge crossed by
     * two horizontals, ITI is a vertical with a corner wedge tucked into
     * it, and UD is the sun on the horizon — a horizontal with a wedge
     * rising out of it. Reduced hard, as everything on this row is, to
     * whatever tells them apart at forty pixels.
     */
    private fun drawCuneiformWord(
        canvas: Canvas, word: Cuneiform.Word, x: Float, y: Float, w: Float, h: Float
    ) {
        when (word) {
            // MU, the year: an upright with two strokes across it.
            Cuneiform.Word.YEAR -> {
                drawWedge(canvas, false, x + w * 0.34f, y, w * 0.42f, h)
                drawHorizontalWedge(canvas, x, y + h * 0.20f, w, h * 0.20f)
                drawHorizontalWedge(canvas, x, y + h * 0.58f, w, h * 0.20f)
            }
            // ITI, the month: an upright with the corner wedge beside it.
            Cuneiform.Word.MONTH -> {
                drawWedge(canvas, false, x + w * 0.04f, y, w * 0.40f, h)
                drawWedge(canvas, true, x + w * 0.46f, y + h * 0.10f, w * 0.54f, h * 0.60f)
            }
            // UD, the day: the sun coming up over a line.
            Cuneiform.Word.DAY -> {
                drawHorizontalWedge(canvas, x, y + h * 0.62f, w, h * 0.22f)
                drawWedge(canvas, true, x + w * 0.18f, y + h * 0.10f, w * 0.64f, h * 0.46f)
            }
        }
    }

    /**
     * A wedge lying on its side: the same impression, made with the reed
     * turned through a right angle.
     *
     * A third shape only in the sense that a letter turned on its side is
     * a third letter — the scribes had the one stylus and used it every
     * way round, and the horizontal is as common on a tablet as the
     * upright. It is not one of the two *numerals*, which is why it lives
     * here with the words rather than in [drawWedge].
     */
    private fun drawHorizontalWedge(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float
    ) {
        glyphPath.reset()
        val cy = y + h / 2f
        glyphPath.moveTo(x + w * 0.06f, cy - h * 0.34f)
        glyphPath.lineTo(x + w * 0.06f, cy + h * 0.34f)
        glyphPath.lineTo(x + w * 0.46f, cy + h * 0.11f)
        glyphPath.lineTo(x + w * 0.96f, cy)
        glyphPath.lineTo(x + w * 0.46f, cy - h * 0.11f)
        glyphPath.close()
        canvas.drawPath(glyphPath, wedgePaint)
    }

    /**
     * One impression of a reed in clay: either the vertical wedge worth
     * one or the corner wedge worth ten.
     *
     * There are only two signs in the whole system, and they are the same
     * stylus held two ways: end-on it leaves a long tapering nail, and
     * turned on its corner it leaves a short hook. So they are drawn as
     * two triangles with deliberately opposite proportions — the one tall
     * and narrow with its point straight down, the ten wide and short with
     * its point down and to the left — because the only thing a reader has
     * to do with these is tell them apart at a glance in a row of nine.
     *
     * Filled, not stroked, which is where they differ from the hieroglyphs
     * next door: a carved outline is a drawing of a thing, and a wedge is
     * a hole punched in a surface.
     */
    private fun drawWedge(canvas: Canvas, ten: Boolean, x: Float, y: Float, w: Float, h: Float) {
        glyphPath.reset()
        if (ten) {
            // The corner wedge: a flat head across the top and the point
            // dropping away to the left, the way the corner of the reed
            // comes out of the clay.
            glyphPath.moveTo(x + w * 0.06f, y + h * 0.16f)
            glyphPath.lineTo(x + w * 0.94f, y + h * 0.30f)
            glyphPath.lineTo(x + w * 0.10f, y + h * 0.84f)
        } else {
            // The vertical wedge: a head at the top and a long tail to a
            // point at the bottom. Five corners rather than three, because
            // a plain triangle at this size is a spike and this has to
            // read as something that was pressed.
            val cx = x + w / 2f
            glyphPath.moveTo(cx - w * 0.34f, y + h * 0.08f)
            glyphPath.lineTo(cx + w * 0.34f, y + h * 0.08f)
            glyphPath.lineTo(cx + w * 0.11f, y + h * 0.46f)
            glyphPath.lineTo(cx, y + h * 0.94f)
            glyphPath.lineTo(cx - w * 0.11f, y + h * 0.46f)
        }
        glyphPath.close()
        canvas.drawPath(glyphPath, wedgePaint)
    }

    /**
     * A whole number in wedges: its sexagesimal places, most significant
     * first, tens before ones inside each place.
     *
     * The gap between places is wider than the gap inside one, and it has
     * to be: place value with no nought means the only thing separating
     * "one, twenty" from "eighty" is white space, and if the two gaps look
     * alike the number cannot be read at all. An empty place — the hole
     * where a nought would go — is that gap and nothing else, which is
     * precisely the ambiguity the scribes lived with.
     *
     * Comes back with the width it used, since like the hieroglyphs a
     * number's width here depends on the number.
     */
    private fun drawCuneiformNumber(
        canvas: Canvas, value: Int, x: Float, y: Float, h: Float, unit: Float
    ): Float {
        var at = x
        val places = Cuneiform.places(value)
        for ((i, place) in places.withIndex()) {
            for ((isTen, count) in listOf(true to place.tens, false to place.ones)) {
                if (count == 0) continue
                val rows = Cuneiform.rowsFor(count)
                val across = Cuneiform.perRow(count)
                val cellH = h / rows
                // The ten is a squat hook and the one a tall nail, so they
                // are given cells of different shapes to sit in.
                val cellW = if (isTen) unit else unit * 0.62f
                for (k in 0 until count) {
                    val row = k / across
                    val col = k % across
                    val inRow = minOf(across, count - row * across)
                    val indent = (across - inRow) / 2f
                    drawWedge(
                        canvas, isTen,
                        at + (col + indent) * cellW, y + row * cellH,
                        cellW, cellH
                    )
                }
                at += across * cellW + unit * Cuneiform.GROUP_GAP
            }
            if (i < places.size - 1) at += unit * Cuneiform.PLACE_GAP
        }
        return at - x
    }

    /**
     * The date in wedges: three numbers, each in sixties.
     *
     * Measured before it is drawn for the same reason the hieroglyphs are
     * — nine wedges is wider than one — and laid out on the same line, so
     * winding across the handover at three thousand before Christ changes
     * the writing without moving the row.
     */
    fun drawCuneiformDate(canvas: Canvas, frame: Frame, cx: Float, top: Float, digitH: Float) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = frame.atMs
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val m = cal.get(Calendar.MONTH) + 1
        val y = SkyAge.yearOf(frame.atMs)
        val parts = intArrayOf(
            if (frame.dayFirst) d else m,
            if (frame.dayFirst) m else d,
            (1 - y).coerceAtLeast(1)
        )
        val h = digitH * 1.05f
        var unit = h * 0.30f
        // Twice the gap between two sexagesimal places, which is itself
        // much wider than the gap inside one. Three widths of white space,
        // each meaning something different, and they have to be plainly
        // ordered or the year's own place break reads as the break between
        // the month and the year — which is what it did.
        var gap = unit * Cuneiform.PLACE_GAP * 2.2f
        fun widthOfNumber(v: Int): Float {
            val places = Cuneiform.places(v)
            var w = 0f
            for (place in places) {
                if (place.tens > 0) w += Cuneiform.perRow(place.tens) * unit + unit * Cuneiform.GROUP_GAP
                if (place.ones > 0) w += Cuneiform.perRow(place.ones) * unit * 0.62f + unit * Cuneiform.GROUP_GAP
            }
            return w + (places.size - 1).coerceAtLeast(0) * unit * Cuneiform.PLACE_GAP
        }
        val words = if (frame.dayFirst) {
            listOf(Cuneiform.Word.DAY, Cuneiform.Word.MONTH, Cuneiform.Word.YEAR)
        } else {
            listOf(Cuneiform.Word.MONTH, Cuneiform.Word.DAY, Cuneiform.Word.YEAR)
        }
        var wide = parts.sumOf { widthOfNumber(it).toDouble() }.toFloat() + gap * 2f +
            parts.size * (unit * 1.15f + unit * Cuneiform.GROUP_GAP * 2f)
        val room = frame.boxWidth * 0.86f
        if (wide > room && wide > 0f) {
            val k = room / wide
            unit *= k
            gap *= k
            wide = room
        }
        wedgePaint.color = frame.ink.color
        wedgePaint.alpha = frame.ink.alpha
        var x = cx - wide / 2f
        for ((i, value) in parts.withIndex()) {
            // The word first, then the number it counts. A tablet does not
            // date itself with three bare numbers in a row: it writes MU,
            // ITI, UD — year, month, day — in front of each one, and those
            // are among the commonest signs there are, because nearly
            // every tablet is dated. See [Cuneiform.Word] for how far this
            // can honestly be taken.
            drawCuneiformWord(canvas, words[i], x, top, unit * 1.15f, h)
            x += unit * 1.15f + unit * Cuneiform.GROUP_GAP * 2f
            x += drawCuneiformNumber(canvas, value, x, top, h, unit)
            if (i < parts.size - 1) x += gap
        }
        wedgesPainted = parts.sumOf { Cuneiform.wedgeCount(it) } + words.size
        barsPainted = 0
        egyptiansPainted = 0
        printedChars = 0
    }

    /** For the tests: wedges in the last row the sky wrote. */
    internal fun wedgesPaintedForTest(): Int = wedgesPainted

    private var wedgesPainted = 0

    /**
     * The clay the wedges are pressed into.
     *
     * Solid, where the hieroglyphs next door are outlines: a carved sign
     * is a line drawn round a thing and a wedge is a hole punched in a
     * surface, and at this size the difference between the two is most of
     * what tells the two scripts apart.
     */
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ------------------------------------------------------------- printed

    /**
     * The type a date is set in when it is older than any display.
     *
     * A serif face, letter-spaced the way an inscription is: Roman
     * numerals were cut into stone and set on title pages for two thousand
     * years before anybody could light one up, and both of those put air
     * between the letters. Nothing else on this dial uses a serif, which
     * is what makes the change of century visible at a glance rather than
     * only on reading the year.
     */
    private val printedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL
        )
        letterSpacing = 0.16f
    }

    /**
     * A date set in type rather than lit up.
     *
     * Fitted to the row the same way the bar displays are, because a Roman
     * date is not a fixed number of characters wide —
     * `XXVIII·XII·MDCCCLXXXVIII` is twenty-four of them — and a size that
     * suits `I·I·MCM` runs a long year off both edges of the phone.
     *
     * Sat on the same line as every other display, so winding across 1970
     * changes the face without moving the row.
     */
    fun drawPrintedDate(
        canvas: Canvas, frame: Frame, text: String, cx: Float, top: Float, digitH: Float
    ) {
        barsPainted = 0
        egyptiansPainted = 0
        wedgesPainted = 0
        printedChars = 0
        if (text.isEmpty()) return
        // The groups are separated by a space in the string; on a printed
        // date a middle dot is what a Roman inscription actually used, and
        // it stops a long date reading as one word.
        val set = text.replace(' ', '·')
        printedPaint.color = frame.ink.color
        printedPaint.alpha = frame.ink.alpha
        printedPaint.textSize = digitH * 1.10f
        val room = frame.boxWidth * 0.86f
        val wide = printedPaint.measureText(set)
        if (wide > room && wide > 0f) printedPaint.textSize *= room / wide
        // Centred on the line the lit displays use, so the row does not
        // jump as the sky is wound across 1970.
        val metrics = printedPaint.fontMetrics
        val y = top + (digitH - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        canvas.drawText(set, cx, y, printedPaint)
        printedChars = set.length
    }

    /** For the tests: characters set in type in the last row the sky wrote. */
    internal fun printedCharsForTest(): Int = printedChars

    private var printedChars = 0

    /**
     * A frame for the camera: white ink on a box of a given width.
     *
     * The glyph charts are pictures of shapes, not of a clock — there is
     * no measurement of a hieroglyph that says whether it looks like a
     * coil of rope — so what they need from a frame is ink they can see
     * and room to fit.
     */
    private fun whiteFrame(boxWidth: Int): Frame = Frame(
        Paint().apply { color = android.graphics.Color.WHITE; alpha = 255 },
        TimeKeeper.nowMs(),
        dayFirst = true,
        boxWidth = boxWidth
    )

    /** For the camera: one number in wedges, drawn anywhere. */
    internal fun drawCuneiformForTest(
        canvas: Canvas, value: Int, x: Float, y: Float, h: Float
    ): Float {
        wedgePaint.color = android.graphics.Color.WHITE
        wedgePaint.alpha = 255
        return drawCuneiformNumber(canvas, value, x, y, h, h * 0.30f)
    }

    /**
     * For the camera: a row of one of the other displays, drawn anywhere.
     *
     * These alphabets are shapes and nothing else — there is no
     * measurement of them that says whether an `M` looks like an `M` — so
     * the way they get checked is by drawing a chart of every glyph and
     * looking at it.
     */
    internal fun drawScriptForTest(
        canvas: Canvas, text: String, cx: Float, top: Float, digitH: Float,
        starFrom: Int = Int.MAX_VALUE, boxWidth: Int = 4000
    ) {
        drawOtherScript(canvas, whiteFrame(boxWidth), text, cx, top, digitH, starFrom)
    }
}
