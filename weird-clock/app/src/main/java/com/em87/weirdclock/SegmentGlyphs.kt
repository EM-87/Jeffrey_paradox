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

    /**
     * The seven Roman letters, and the digits that share the row with them.
     *
     * The letters are the reason the module has sixteen bars at all:
     * `X` is the four diagonals, `M` is both uprights with the top two
     * diagonals meeting in the middle, `V` is the left upright falling to a
     * corner and a diagonal climbing out of it. `D` needs its straight
     * stroke somewhere other than the module's own left edge, so it uses
     * the middle upright and the whole ring — which is why a sixteen-bar
     * `D` and a sixteen-bar `0` are different shapes rather than the same
     * shape twice.
     */
    private val SIXTEEN = mapOf(
        '0' to RING,
        '1' to (B or C),
        '2' to (A1 or A2 or B or G or E or D1 or D2),
        '3' to (A1 or A2 or B or C or G or D1 or D2),
        '4' to (F or B or C or G),
        '5' to (A1 or A2 or F or G or C or D1 or D2),
        '6' to (A1 or A2 or F or E or G or C or D1 or D2),
        '7' to (A1 or A2 or B or C),
        '8' to (RING or G),
        '9' to (A1 or A2 or F or B or G or C or D1 or D2),
        'I' to (I or L),
        'V' to (F or E or M or J),
        'X' to (H or J or K or M),
        'L' to (F or E or D1 or D2),
        'C' to (A1 or A2 or F or E or D1 or D2),
        'D' to (RING and (F or E).inv() or I or L),
        'M' to (F or E or B or C or H or J),
        '·' to DOT
    )

    /** Which of the sixteen bars [c] lights, or nothing if it has no shape. */
    fun sixteen(c: Char): Int? = SIXTEEN[c]

    // ------------------------------------------------- the star

    /*
     * Eight arms out of the middle, numbered clockwise from straight up,
     * and the four chords that close the rim between the arms at the
     * corners. A glyph is two to four of them.
     */
    const val N = 1 shl 0
    const val NE = 1 shl 1
    const val EAST = 1 shl 2
    const val SE = 1 shl 3
    const val S = 1 shl 4
    const val SW = 1 shl 5
    const val WEST = 1 shl 6
    const val NW = 1 shl 7

    /**
     * The rim: the four sides of the diamond that joins the tips of the
     * four upright arms.
     *
     * They run corner to corner past the ends of the diagonal arms rather
     * than between them, which is the difference between a `6` that is a
     * closed diamond and a `6` that is four unconnected dashes — the first
     * version joined each arm to the next and came out as a flower.
     */
    const val RIM_NE = 1 shl 8
    const val RIM_SE = 1 shl 9
    const val RIM_SW = 1 shl 10
    const val RIM_NW = 1 shl 11

    private const val DIAMOND = RIM_NE or RIM_SE or RIM_SW or RIM_NW

    /**
     * Ten marks on the star.
     *
     * Drawn from the table of Yautja numerals rather than invented, and
     * said plainly because the difference matters to somebody deciding
     * whether to trust the shapes: they are a reading of a small picture,
     * built out of the pieces that picture is built out of. Where a stroke
     * was ambiguous the tie went to keeping the ten apart, since a display
     * whose 3 and 8 are a coin toss has stopped being a display.
     *
     * The rules they do keep are the ones our own digits cannot: none is
     * empty, no two are the same, and no two are one stroke apart, so a
     * dead stroke cannot turn one digit into another. An `8` and a `9` on
     * an ordinary segment display differ by a single bar, which nobody has
     * ever minded; an alphabet nobody can read has no such luck, since
     * there is no word to fall back on.
     */
    private val STAR = mapOf(
        '0' to (NW or SW or S),
        '1' to (NW or EAST or S),
        '2' to (N or SE or SW),
        '3' to (NW or SE),
        '4' to (NW or SW or N),
        '5' to (NE or SE or N),
        '6' to DIAMOND,
        '7' to (NW or NE or S),
        '8' to (NW or NE or SE or SW),
        '9' to (RIM_NE or RIM_SE or NW or SW)
    )

    /** Which pieces of the star [c] lights, or nothing if it is not a digit. */
    fun star(c: Char): Int? = STAR[c]

    /** Every piece of the star, for drawing the unlit ones behind a glyph. */
    val STAR_PIECES = intArrayOf(
        N, NE, EAST, SE, S, SW, WEST, NW, RIM_NE, RIM_SE, RIM_SW, RIM_NW
    )

    /** Every one of the sixteen bars, in the same way. */
    val SIXTEEN_BARS = intArrayOf(
        A1, A2, B, C, D2, D1, E, F, H, I, J, K, L, M, G1, G2
    )
}
