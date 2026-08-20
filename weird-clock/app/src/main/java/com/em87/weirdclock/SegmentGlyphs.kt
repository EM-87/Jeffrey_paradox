package com.em87.weirdclock

/**
 * Two alphabets drawn as lit bars rather than typed as letters.
 *
 * The ordinary date on this clock goes through a seven-bar display, the
 * same one the chronograph uses. Seven bars can make the ten digits and
 * nothing else, so when the sky is wound somewhere the year is not four
 * digits the display has to change, not the font.
 *
 * Roman years get a sixteen-bar module — the outline of a rectangle cut in
 * half along both axes, plus the four diagonals and a bar to each side of
 * the middle. That is the shape of display that exists for exactly this
 * reason: it can draw letters. The first attempt at this drew the Roman
 * year as text in the clock's own typeface, which was honest about seven
 * bars not being able to do it and dishonest about what the reader was
 * looking at — a row of segments with one word of ordinary print in it.
 *
 * Far-future years get a star: eight arms out of a centre and the four
 * chords that close the rim between them. Nothing in that shape is a
 * letter we use, which is the point, and it is a display rather than a
 * picture because every glyph is a subset of the same twelve pieces.
 *
 * Both are described here as bit masks and drawn in [ClockView], so the
 * shapes can be counted and compared without a screen.
 */
object SegmentGlyphs {

    // ------------------------------------------------- sixteen bars

    /*
     *      --A1-- --A2--
     *     |\      |      /|
     *     F  H    I    J  B
     *     |    \  |  /    |
     *      --G1-- ‧ --G2--
     *     |    /  |  \    |
     *     E  M    L    K  C
     *     |/      |      \|
     *      --D1-- --D2--
     */
    const val A1 = 1 shl 0
    const val A2 = 1 shl 1
    const val B = 1 shl 2
    const val C = 1 shl 3
    const val D2 = 1 shl 4
    const val D1 = 1 shl 5
    const val E = 1 shl 6
    const val F = 1 shl 7
    const val H = 1 shl 8
    const val I = 1 shl 9
    const val J = 1 shl 10
    const val K = 1 shl 11
    const val L = 1 shl 12
    const val M = 1 shl 13
    const val G1 = 1 shl 14
    const val G2 = 1 shl 15

    /** The dot in the middle of the module, used as the separator. */
    const val DOT = 1 shl 16

    private const val RING = A1 or A2 or B or C or D1 or D2 or E or F
    private const val G = G1 or G2

    /** Both halves of an upright, which is how every letter uses them. */
    private const val LEFT = F or E
    private const val RIGHT = B or C
    private const val TOP = A1 or A2
    private const val FOOT = D1 or D2

    /**
     * The seven Roman letters, and the digits that share the row with them.
     *
     * The letters are the reason the module has these bars at all, and each
     * one is a shape rather than a lookup:
     *
     *  - `I` is the left upright. One stroke, and it is the *left* one so
     *    that a row of them reads as a row of strokes rather than as three
     *    bars floating in the middle of three boxes.
     *  - `V` is the two upper diagonals, meeting in the middle. A V's
     *    vertex is at the bottom of the letter and here it is halfway up,
     *    which is what a segment display does to a V and is exactly how the
     *    real ones look.
     *  - `X` is all four diagonals.
     *  - `M` is `V` with both uprights added, which is also how the letter
     *    is built: two legs and a fold between them.
     *  - `L` is the left upright and the foot, `C` adds the head, and `D`
     *    is the whole ring.
     *
     * The first version of this had `V` as an upright falling to a corner
     * with a diagonal climbing out of it — a shape arrived at by asking
     * what could be made rather than by looking at one.
     *
     * `D` being the whole ring is why `0` is slashed. On a display that
     * only ever writes Roman there is no clash, since there is no nought;
     * this one carries the day and the month as digits in the same row, so
     * the nought needs a mark on it. Slashed by a whole diagonal rather
     * than half of one, so the two are two bars apart and not one.
     */
    private val SIXTEEN = mapOf(
        '0' to (RING or H or K),
        '1' to RIGHT,
        '2' to (TOP or B or G or E or FOOT),
        '3' to (TOP or RIGHT or G or FOOT),
        '4' to (F or RIGHT or G),
        '5' to (TOP or F or G or C or FOOT),
        '6' to (TOP or LEFT or G or C or FOOT),
        '7' to (TOP or RIGHT),
        '8' to (RING or G),
        '9' to (TOP or F or B or G or C or FOOT),
        'I' to LEFT,
        'V' to (H or J),
        'X' to (H or J or K or M),
        'L' to (LEFT or FOOT),
        'C' to (TOP or LEFT or FOOT),
        'D' to RING,
        'M' to (LEFT or RIGHT or H or J),
        '\u00b7' to DOT
    )

    /** Which of the sixteen bars [c] lights, or nothing if it has no shape. */
    fun sixteen(c: Char): Int? = SIXTEEN[c]

    /**
     * The pairs that are one bar when both halves are lit.
     *
     * The module is built in halves because the digits need them — a `2`
     * lights the top right upright and not the bottom one — but no letter
     * ever does, and a letter drawn as two bars with a nick between them
     * is a letter with a nick in it. So when both halves of an upright or
     * a rail are on, one long bar is drawn instead of two.
     */
    val JOINED: List<Pair<Int, Int>> = listOf(
        A1 to A2, D1 to D2, F to E, B to C, G1 to G2
    )

    // ------------------------------------------------- the star

    /*
     * Two eight-armed stars, one above the other, sharing an axis.
     *
     * That is the whole trick of this alphabet and it took three goes to
     * see. One star cannot make the shapes: the `6` is a closed diamond,
     * and a diamond is not a subset of anything radiating from one point.
     * With two stars it is four arms — the upper star's two lower
     * diagonals and the lower star's two upper ones, meeting in pairs at a
     * left point and a right point. Everything else falls out of the same
     * arrangement: a chevron is half a diamond, and the long stem down the
     * middle of nearly every digit is the top star's north arm and the
     * bottom star's south arm.
     *
     * Sixteen arms, then, and no chords: nothing in this alphabet joins
     * two points that are not both ends of arms.
     */
    const val U_N = 1 shl 0
    const val U_NE = 1 shl 1
    const val U_E = 1 shl 2
    const val U_SE = 1 shl 3
    const val U_S = 1 shl 4
    const val U_SW = 1 shl 5
    const val U_W = 1 shl 6
    const val U_NW = 1 shl 7
    const val L_N = 1 shl 8
    const val L_NE = 1 shl 9
    const val L_E = 1 shl 10
    const val L_SE = 1 shl 11
    const val L_S = 1 shl 12
    const val L_SW = 1 shl 13
    const val L_W = 1 shl 14
    const val L_NW = 1 shl 15

    /** The stem: down the middle, top to bottom, past both stars. */
    private const val STEM = U_N or L_S

    /** The closed rhombus between the two stars, which is the `6`. */
    private const val DIAMOND = U_SW or U_SE or L_NW or L_NE

    /** Its left half and its right half, which are the chevrons. */
    private const val LEFT_CHEVRON = U_SW or L_NW
    private const val RIGHT_CHEVRON = U_SE or L_NE

    /**
     * Ten marks, nine shapes.
     *
     * A reading of a table rather than an invention, and said plainly
     * because it matters to anybody deciding how much to trust the
     * shapes. What is certain is the construction — two stars, sixteen
     * arms, the stem through the middle, the diamond and its halves — and
     * that `2` and `8` are the same symbol, which is a fact about the
     * system and not a mistake in the reading. A numeral set where two
     * digits share a glyph is the sort of thing you cannot arrive at by
     * being sensible, so it is kept exactly as it is.
     *
     * Where an arm was ambiguous the tie went to keeping the rest apart,
     * since a display whose 3 and 7 are a coin toss has stopped being a
     * display.
     */
    private val STAR = mapOf(
        '0' to (STEM or U_SW or L_NE),
        '1' to (STEM or U_W or L_E),
        '2' to (STEM or U_W or LEFT_CHEVRON or L_E),
        '3' to (U_E or L_SW or L_S),
        '4' to (STEM or LEFT_CHEVRON),
        '5' to (STEM or RIGHT_CHEVRON or L_SW),
        '6' to (STEM or DIAMOND),
        '7' to (STEM or U_NE or L_SW),
        // The same as the 2. Not a slip: the table has one symbol for
        // both, and a numeral set that reuses a glyph is not something
        // anybody would arrive at by tidying.
        '8' to (STEM or U_W or LEFT_CHEVRON or L_E),
        '9' to (STEM or DIAMOND or U_NW or U_NE)
    )

    /** Which arms [c] lights, or nothing if it is not a digit. */
    fun star(c: Char): Int? = STAR[c]

    /** Every arm of the two stars, in drawing order. */
    val STAR_ARMS = intArrayOf(
        U_N, U_NE, U_E, U_SE, U_S, U_SW, U_W, U_NW,
        L_N, L_NE, L_E, L_SE, L_S, L_SW, L_W, L_NW
    )

    /** Every one of the sixteen bars, in the same way. */
    val SIXTEEN_BARS = intArrayOf(
        A1, A2, B, C, D2, D1, E, F, H, I, J, K, L, M, G1, G2
    )
}
