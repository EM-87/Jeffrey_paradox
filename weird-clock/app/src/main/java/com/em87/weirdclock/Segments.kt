package com.em87.weirdclock

/**
 * Three displays, described once.
 *
 * Every number this app shows without hands goes through one of these:
 * seven bars for ours, the module below for Rome's, and a pair of stacked
 * stars for theirs. They are described here as topology — where each bar
 * runs inside a unit box, and which bars each character lights — and drawn
 * by [SegmentPainter], so a shape can be counted and compared without a
 * screen and so there is one place where a `V` is wrong rather than four.
 *
 * The alphabets are not inventions. The Roman module is a drawing the
 * owner of this app made and handed over; the alien numerals are read off
 * a chart of the same provenance. Both were reconstructed here once from
 * memory and both came out subtly wrong in the way reconstructions do —
 * the `V` in particular, which is the reason this file exists.
 */
object Segments {

    /** Which of the three displays a character is going on. */
    enum class Kind {

        /** Seven bars. Ten digits, and nothing else. */
        SEVEN,

        /**
         * Rome's, copied from the drawing.
         *
         * Fourteen bars and a dot: a full-height upright each side, a
         * full-width rail top and bottom, four diagonals from the corners
         * to the middle, one short rail out of the left upright, and a
         * round dot right of centre for the separator.
         *
         * What is *not* here is as important. There is no vertical stem
         * through the middle and no right-hand middle rail, so the module
         * cannot make a `V` on its own — see [masksOf].
         */
        SIXTEEN,

        /**
         * Theirs: two eight-armed stars, one above the other, sharing the
         * arm between them. Fifteen bars.
         *
         * Their numerals were a font until now, which meant the one script
         * on this clock that could not have an unlit bar behind it, could
         * not be poked, and could not be made thicker or thinner. A font
         * is a picture of a display; this is the display.
         */
        STAR
    }

    // ------------------------------------------------------- seven bars

    /*
     *      --a--
     *     |     |
     *     f     b
     *     |     |
     *      --g--
     *     |     |
     *     e     c
     *     |     |
     *      --d--
     */
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val C = 1 shl 2
    const val D = 1 shl 3
    const val E = 1 shl 4
    const val F = 1 shl 5
    const val G = 1 shl 6

    private val DIGITS = intArrayOf(
        A or B or C or D or E or F,               // 0
        B or C,                                    // 1
        A or B or G or E or D,                     // 2
        A or B or G or C or D,                     // 3
        F or G or B or C,                          // 4
        A or F or G or C or D,                     // 5
        A or F or G or E or C or D,                // 6
        A or B or C,                               // 7
        A or B or C or D or E or F or G,           // 8
        A or B or C or D or F or G                 // 9
    )

    // ----------------------------------------------------- Rome's module

    /*
     *      ---TOP---
     *     |\       /|
     *     | H     J |
     *   LEFT  \ /   RIGHT
     *     G1---•    ·DOT
     *     | M / \ K |
     *     |/       \|
     *      --BOTTOM--
     */
    const val TOP = 1 shl 0
    const val BOTTOM = 1 shl 1
    const val LEFT = 1 shl 2
    const val RIGHT = 1 shl 3
    const val H = 1 shl 4
    const val J = 1 shl 5
    const val K = 1 shl 6
    const val M = 1 shl 7
    const val G1 = 1 shl 8
    const val DOT = 1 shl 9

    /** The stroke through the module, top-left corner to bottom-right. */
    private const val BACKSLASH = H or K

    /** And the other way: bottom-left corner to top-right. */
    private const val SLASH = J or M

    // ------------------------------------------------------- their stars

    /*
     * Two squares stacked, a star in each. Every arm of a star runs from
     * its middle to a corner or to the middle of a side, so the tips of
     * the upper star's lower diagonals and the lower star's upper ones
     * land on the same two points — which is why a 6 closes into a
     * diamond and does not merely look like one.
     */
    const val UN = 1 shl 0
    const val UNE = 1 shl 1
    const val UE = 1 shl 2
    const val USE = 1 shl 3
    const val US = 1 shl 4
    const val USW = 1 shl 5
    const val UW = 1 shl 6
    const val UNW = 1 shl 7
    const val LNE = 1 shl 8
    const val LE = 1 shl 9
    const val LSE = 1 shl 10
    const val LS = 1 shl 11
    const val LSW = 1 shl 12
    const val LW = 1 shl 13
    const val LNW = 1 shl 14

    /**
     * Their ten digits, read off the chart arm by arm.
     *
     * The chart is the check on this: 8 is 2 with the tail added and 9 is
     * 8 with one more arm, which is the thing anybody notices first about
     * these numerals and is not something a wrong reading would produce by
     * accident.
     */
    private val STARS = intArrayOf(
        UN or USE or LNW or LS,                                  // 0
        UN or UE or UW or USW or LE or LS,                       // 1
        UN or UE or UW or USE or USW or LE or LNW,               // 2
        UE or LNW or LE or LS,                                   // 3
        UE or USE or USW or LNW or LE or LS,                     // 4
        UN or UW or USW or LNW or LNE or LE or LS,               // 5
        USE or USW or LNW or LNE or LE or LS,                    // 6
        UN or UW or USW or LNE or LE or LS,                      // 7
        UN or UE or UW or USE or USW or LE or LS or LNW,         // 8
        UN or UE or UW or USE or USW or LNE or LE or LS or LNW   // 9
    )

    // ------------------------------------------------------- the shapes

    /**
     * One bar, in a box one unit wide and one unit tall.
     *
     * [joinsAt] says which ends run into a junction and must overlap it
     * rather than stop short of it: 0 neither, -1 the start, 1 the end, 2
     * both. A bar that stops short at a corner is what gives a `0` its
     * corners; a bar that stops short at the middle of an `X` leaves a
     * hole where the stroke crosses.
     *
     * Nothing overlaps anything except where it must. Every bar stops a
     * hair short of the point it is aimed at, and that hair is the
     * display: take it away where four diagonals cross and the `X` stops
     * being four bars and becomes a painted cross.
     */
    class Bar(
        val bit: Int,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val joinsAt: Int = 0,
        /**
         * How thick this bar is against the others.
         *
         * One, everywhere on the two flat displays. That is a measurement
         * and not a choice: the drawing's uprights come out seven pixels
         * wide and its diagonals six, near enough the same pen. An earlier
         * pass here gave the diagonals two and a half times the weight and
         * a taper to a point, which is what a sixteen-segment module looks
         * like in most people's memory and is not what this one looks
         * like at all.
         */
        val weight: Float = 1f,
        /** How wide the cut end is, against the bar's own width. */
        val flat: Float = 0.42f,
        /**
         * How far in from each end the bar reaches full width, as a share
         * of its length. Nought leaves it to the thickness — a short
         * chamfer, so the end reads as cut. Their arms want a long one:
         * every arm of a star is a spindle, pointed at both ends.
         */
        val shoulder: Float = 0f,
        val round: Boolean = false,
        val dot: Boolean = false
    )

    private val SEVEN_BARS = listOf(
        Bar(A, 0f, 0f, 1f, 0f),
        Bar(B, 1f, 0f, 1f, 0.5f),
        Bar(C, 1f, 0.5f, 1f, 1f),
        Bar(D, 0f, 1f, 1f, 1f),
        Bar(E, 0f, 0.5f, 0f, 1f),
        Bar(F, 0f, 0f, 0f, 0.5f),
        Bar(G, 0f, 0.5f, 1f, 0.5f)
    )

    /*
     * Measured off the drawing rather than remembered.
     *
     * The one thing memory got wrong and the tape got right: the diagonals
     * do not start in the corners. They start on the top and bottom rails,
     * a tenth of the way in, which is what leaves daylight at each corner
     * where three bars would otherwise pile up — and what gives the `M`
     * its shape.
     */
    private const val NOTCH = 0.10f

    private val SIXTEEN_BARS = listOf(
        Bar(TOP, 0f, 0f, 1f, 0f),
        Bar(BOTTOM, 0f, 1f, 1f, 1f),
        Bar(LEFT, 0f, 0f, 0f, 1f),
        Bar(RIGHT, 1f, 0f, 1f, 1f),
        Bar(H, NOTCH, 0f, 0.5f, 0.5f),
        Bar(J, 1f - NOTCH, 0f, 0.5f, 0.5f),
        Bar(K, 1f - NOTCH, 1f, 0.5f, 0.5f),
        Bar(M, NOTCH, 1f, 0.5f, 0.5f),
        Bar(G1, 0.08f, 0.5f, 0.45f, 0.5f, round = true),
        Bar(DOT, 0.68f, 0.5f, 0.68f, 0.5f, dot = true)
    )

    private val STAR_BARS: List<Bar> = buildList {
        val ux = 0.5f
        val uy = 0.25f
        val ly = 0.75f
        // Every arm runs out of its star's middle, so the inner end has
        // to overlap it: eight arms that each stop a hair short leave a
        // hole where the eye is looking, and the glyph reads as a scatter
        // of petals rather than as a stroke.
        //
        // Otherwise an arm is the same bar as every other bar here. The
        // chart's arms measure four pixels across an eighteen-pixel run,
        // which against their module's height is the same pen the Roman
        // module is drawn with — one instrument, three alphabets. They
        // were built as spindles here first, fat in the middle, which is
        // what a small photograph of a thin bar looks like and is not what
        // a thin bar is. The tips do come to a point — two pixels of it in
        // eighteen — where the Roman module's ends are cut square.
        fun arm(bit: Int, cy: Float, tx: Float, ty: Float) =
            Bar(bit, ux, cy, tx, ty, joinsAt = -1, flat = 0.15f, shoulder = 0.13f)
        add(arm(UN, uy, 0.5f, 0f))
        add(arm(UNE, uy, 1f, 0f))
        add(arm(UE, uy, 1f, 0.25f))
        add(arm(USE, uy, 1f, 0.5f))
        add(arm(US, uy, 0.5f, 0.5f))
        add(arm(USW, uy, 0f, 0.5f))
        add(arm(UW, uy, 0f, 0.25f))
        add(arm(UNW, uy, 0f, 0f))
        add(arm(LNE, ly, 1f, 0.5f))
        add(arm(LE, ly, 1f, 0.75f))
        add(arm(LSE, ly, 1f, 1f))
        add(arm(LS, ly, 0.5f, 1f))
        add(arm(LSW, ly, 0f, 1f))
        add(arm(LW, ly, 0f, 0.75f))
        add(arm(LNW, ly, 0f, 0.5f))
    }

    /** Every bar of [kind], lit or not. */
    fun bars(kind: Kind): List<Bar> = when (kind) {
        Kind.SEVEN -> SEVEN_BARS
        Kind.SIXTEEN -> SIXTEEN_BARS
        Kind.STAR -> STAR_BARS
    }

    /**
     * How wide a module of [kind] is against its own height.
     *
     * Measured off the drawing rather than chosen: seven bars make a
     * narrow digit, Rome's module is squarer because four diagonals across
     * a slot read as strokes and not as letters, and the stars are two
     * squares stacked, so exactly half as wide as they are tall.
     */
    fun aspect(kind: Kind): Float = when (kind) {
        Kind.SEVEN -> 0.55f
        Kind.SIXTEEN -> 0.59f
        Kind.STAR -> 0.50f
    }

    /**
     * Whether neighbouring modules share the upright between them.
     *
     * Nothing does, and it was worth finding out the hard way. The drawing
     * this display copies shows its modules touching, with one upright at
     * each boundary — so they were built that way, and every year in the
     * nineteen hundreds came out wrong. `MCM` is `M`, `C`, `M`: the `C`
     * has no right-hand upright of its own, but the `M` after it does, and
     * a shared boundary put that upright hard against the `C` and closed
     * it into a `D`. 1980 read as MDMLXXX.
     *
     * A specimen sheet draws its modules adjacent to show the array. A
     * clock has to be read. So the modules stand apart, each with its own
     * two uprights and daylight between — see [gap] — which is also how
     * every display of this kind that anybody has ever built is laid out.
     */
    fun butted(kind: Kind): Boolean = false

    /**
     * The daylight between two modules, as a share of a module's width.
     *
     * Enough to see, and no more: the letters of a Roman numeral belong to
     * one number and a gap wide enough to read as a space would break
     * `MMXXIV` into pieces.
     */
    fun gap(kind: Kind): Float = if (kind == Kind.SIXTEEN) 0.22f else 0f

    /**
     * Which bars [c] lights, as one mask per module it takes up.
     *
     * Nearly every character is one module. `V` is two, and that is the
     * whole point of this function existing: the module has no vertical
     * stem and its diagonals meet in the middle, so the only `V` it can
     * make on its own is a shallow tick across the top half — which is
     * what a reconstruction of this display produced, and what reads as
     * half an `X`.
     *
     * Two modules solve it exactly. A stroke from the top-left corner to
     * the bottom-right of one, and from the bottom-left to the top-right
     * of the next, meet at the corner the two modules share: a real `V`,
     * full height, with its point on the baseline. It costs a cell, which
     * is why the drawing calls it a compromise, and it is the difference
     * between a display that can write `MMXXIV` and one that cannot.
     */
    fun masksOf(kind: Kind, c: Char): IntArray? = when (kind) {
        Kind.SEVEN -> if (c in '0'..'9') intArrayOf(DIGITS[c - '0']) else null
        Kind.STAR -> if (c in '0'..'9') intArrayOf(STARS[c - '0']) else null
        Kind.SIXTEEN -> when (c) {
            'I' -> intArrayOf(LEFT)
            'V' -> intArrayOf(BACKSLASH, SLASH)
            'X' -> intArrayOf(H or J or K or M)
            'L' -> intArrayOf(LEFT or BOTTOM)
            'C' -> intArrayOf(TOP or LEFT or BOTTOM)
            'D' -> intArrayOf(TOP or LEFT or RIGHT or BOTTOM)
            'M' -> intArrayOf(LEFT or RIGHT or H or J)
            // Nulla — see [DigitalReadout.roman]. The uprights with the
            // stroke between them, which is what an N is.
            'N' -> intArrayOf(LEFT or RIGHT or H or K)
            '·' -> intArrayOf(DOT)
            ' ' -> intArrayOf(0)
            else -> null
        }
    }

    /**
     * The seven-bar mask for a digit, or nothing lit if it is not one.
     *
     * For the two places that lay a seven-bar row out themselves — the
     * dial's readout and the metronome — and want the table without the
     * rest of this.
     */
    fun seven(c: Char): Int = masksOf(Kind.SEVEN, c)?.single() ?: 0

    /** How many modules [text] takes on [kind], `V` counting for two. */
    fun width(kind: Kind, text: String): Int =
        text.sumOf { masksOf(kind, it)?.size ?: 0 }

    /** [text] as one mask per module, unknown characters left out. */
    fun spell(kind: Kind, text: String): IntArray {
        val out = ArrayList<Int>(text.length + 2)
        for (c in text) masksOf(kind, c)?.forEach { out += it }
        return out.toIntArray()
    }

    /**
     * One bar as it is about to be drawn: which module, and whether lit.
     */
    class Stroke(val at: Int, val bar: Bar, val lit: Boolean)

    /**
     * What a row of modules comes to, before any of it is drawn.
     *
     * Separated from the drawing because everything that can be wrong here
     * is arithmetic — a poked-out bar that lights anyway, a module that
     * lights a bar its display has not got — and none of it needs a canvas
     * to catch. Eyeballing a picture of nine letters to work out which of
     * nine uprights is missing is not a method.
     *
     * The ghosts come first, so nothing lit is ever drawn under something
     * faint.
     */
    fun plan(kind: Kind, masks: IntArray, burnt: IntArray? = null): List<Stroke> {
        val bars = bars(kind)

        fun on(i: Int, bit: Int): Boolean {
            if (i !in masks.indices || bit == 0) return false
            if (masks[i] and bit == 0) return false
            // A bar somebody has poked out stays dark however hard the
            // number tries to light it. That is what a dead segment is,
            // and it is why a poked clock can end up lying to you.
            return (burnt?.getOrNull(i) ?: 0) and bit == 0
        }

        val out = ArrayList<Stroke>(masks.size * bars.size)
        for (wanted in listOf(false, true)) {
            for (i in masks.indices) {
                for (bar in bars) {
                    if (on(i, bar.bit) != wanted) continue
                    out += Stroke(i, bar, wanted)
                }
            }
        }
        return out
    }

    /**
     * How much room [text] needs, in module widths, gaps included.
     *
     * The gaps are inside the number: `XIV` is four modules with three
     * gaps between them, and whatever is laid out next to it needs to know
     * that before it can be centred.
     */
    fun span(kind: Kind, text: String): Float {
        val n = width(kind, text)
        if (n == 0) return 0f
        return n + (n - 1) * gap(kind)
    }
}
