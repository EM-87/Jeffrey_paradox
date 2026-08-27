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

    // ------------------------------------------------- seven bars

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
    private val SEVEN = intArrayOf(
        0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
        0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011
    )

    /**
     * The ten digits, ordered a(64) b(32) c(16) d(8) e(4) f(2) g(1).
     *
     * Written down once. This table was in three places — the dial's
     * readout, the metronome, and then the digital face wanted it too —
     * and three copies of a table is three chances to fix a `6` in two of
     * them.
     */
    fun seven(digit: Int): Int = SEVEN[digit]

    /** The same, for a character, or null if it is not a digit. */
    fun seven(c: Char): Int? = if (c in '0'..'9') SEVEN[c - '0'] else null

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
     * The seven Roman letters, and nothing else.
     *
     * No digits. The whole date changes script together — a day in Arabic
     * beside a year in Roman is two displays sharing a row, which is what
     * this looked like on the glass and was wrong twice over: wrong as a
     * picture, and wrong as an idea, since the joke is that the sky has
     * been wound somewhere the date is written differently, not that half
     * of it has.
     *
     * Dropping the digits also takes a hack with them. `D` and `0` are the
     * same ring on a display like this, so the nought had been slashed to
     * keep them apart; with no nought there is no clash, and `D` can be
     * what it should be — the centre upright with the ring's right-hand
     * half round it. Drawn as the full ring, which is what it was, a `D`
     * simply reads as an `O`.
     *
     * Each letter is a shape rather than a lookup:
     *
     *  - `I` is the left upright. One stroke, and it is the *left* one so
     *    that a row of them reads as a row of strokes rather than as bars
     *    floating in the middle of boxes.
     *  - `V` is the left upright, the lower-left diagonal climbing back to
     *    the middle, and the upper-right one carrying on to the corner: a
     *    V lying with its point at the bottom-left. Made instead of the
     *    two *upper* diagonals it comes out as the top half of an `X`,
     *    which is what it was and what it read as — an X somebody had cut
     *    in half, sitting where a V should be.
     *  - `X` is all four diagonals.
     *  - `M` is both uprights with the two upper diagonals folded between
     *    them, which is how the letter is built.
     *  - `L` is the left upright and the foot, and `C` adds the head.
     *  - `D` is the whole ring, which is what it is on the display this
     *    was read off — and which only works because there are no digits
     *    on the row. A ring is a `D` and a `0` at once, and the reason it
     *    was reading as an `0` was not the shape: it was the day and the
     *    month sitting beside it in Arabic. With those gone the ring has
     *    nothing to be confused with, which is why a Roman display can
     *    afford it and a mixed one cannot.
     *
     *    It was tried as a centre upright with the right half of the ring
     *    round it, which is the usual sixteen-segment `D`. On a module
     *    this narrow the bowl comes out half a module wide and a whole
     *    module tall, and reads as a nought with a bar beside it.
     */
    private val SIXTEEN = mapOf(
        'I' to LEFT,
        'V' to (LEFT or M or J),
        'X' to (H or J or K or M),
        'L' to (LEFT or FOOT),
        'C' to (TOP or LEFT or FOOT),
        'D' to RING,
        'M' to (LEFT or RIGHT or H or J),
        // Nulla \u2014 the word medieval computists wrote in the column where a
        // Roman table needed a nothing, abbreviated to its initial. A clock
        // has to write midnight and it has to write "and no minutes", and
        // Rome left no numeral for either; N is the answer Rome's own
        // arithmeticians reached for, which is a better one than leaving
        // the space empty and letting it read as a display with a fault.
        'N' to (LEFT or RIGHT or H or K),
        '\u00b7' to DOT
    )

    /** Which of the bars [c] lights, or nothing if it has no shape. */
    fun sixteen(c: Char): Int? = SIXTEEN[c]

    /**
     * The pairs that are one bar when both halves are lit.
     *
     * The module is built in halves because that is what the bars of a
     * display like this are, but no letter ever lights half an upright,
     * and an upright drawn as two bars with a nick between them is an
     * upright with a nick in it. So when both halves are on, one long bar
     * is drawn instead of two.
     */
    val JOINED: List<Pair<Int, Int>> = listOf(
        A1 to A2, D1 to D2, F to E, B to C, G1 to G2, I to L
    )

    /**
     * Nothing overlaps anything. Every bar stops a hair short of the point
     * it is aimed at, including the middle.
     *
     * That hair is the display. A segment is a stamped piece of metal or a
     * cut in a mask, and the daylight between one and the next is what
     * says so — take it away where the four diagonals cross and the `X`
     * stops being four bars and becomes a painted cross. It was taken
     * away, on the theory that the gap read as a bite out of the letter.
     * The bite was somewhere else entirely: the `V`.
     */
    val JOINS_MIDDLE: Int = 0

    // The eight-armed star that wrote the far-future dates is gone. It
    // was a reconstruction of an alphabet worked out from a photograph of
    // a chart, and the real font arrived — see [Yautja]. A reconstruction
    // kept beside the thing it was standing in for is a second answer to
    // a question that now has one.

    /** Every one of the sixteen bars, in the same way. */
    val SIXTEEN_BARS = intArrayOf(
        A1, A2, B, C, D2, D1, E, F, H, I, J, K, L, M, G1, G2
    )
}
