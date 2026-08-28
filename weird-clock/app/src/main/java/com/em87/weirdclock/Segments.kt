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
         * Theirs: two eight-armed stars, one above the other. Eighteen
         * bars.
         *
         * The two stars do not share an arm, which is the correction the
         * chart forced. Where they meet, four bars come to a point and
         * stop short of it: the upper star's south arm, the lower star's
         * north arm, and the middle rail split into its two halves. No
         * numeral lights any of the four. They are the whole of what this
         * display can do and never does, and the chart draws all four —
         * so drawing one long arm through the middle instead was a
         * display with three bars missing and one bar too long.
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
     *
     * The waist between them is its own crossing: [US] down to it, [LN]
     * up to it, [MW] and [ME] out to the sides, four bars stopping short
     * of one point.
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

    /*
     * And the four in the middle that no numeral lights: the two stars'
     * facing arms, and the rail across the waist in its two halves. They
     * exist because the chart draws them, and they are only ever ghosts.
     */
    const val LN = 1 shl 15
    const val MW = 1 shl 16
    const val ME = 1 shl 17

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
     * One bar, in a box one unit wide and one unit tall, y downwards.
     *
     * [x0],[y0]–[x1],[y1] is the bar's axis. [outline] is its exact shape
     * when there is one to copy: the Roman module's eight pieces are the
     * polygons out of the drawing itself, vertex for vertex, and are
     * neither approximated nor re-derived. Its vertices are in the same
     * box, and the thickness knob moves each of them along the bar's own
     * normal — see [SegmentPainter.outlineOf] — so a fatter display is the
     * drawing's shape widened rather than a different shape.
     *
     * Where there is no outline the painter makes a sliver — see
     * [SegmentPainter]. [joinsAt] says which ends run into a junction and
     * must overlap it rather than stop short: 0 neither, -1 the start, 1
     * the end, 2 both.
     */
    class Bar(
        val bit: Int,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val joinsAt: Int = 0,
        val weight: Float = 1f,
        val flat: Float = 0.42f,
        val shoulder: Float = 0f,
        val round: Boolean = false,
        val dot: Boolean = false,
        val radius: Float = 0f,
        val outline: FloatArray? = null
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
     * Rome's module, copied out of the drawing.
     *
     * Not measured off a photograph this time and not reconstructed: the
     * file itself was read and these are its polygons. Three things in it
     * are not what a reconstruction would have guessed, and all three were
     * wrong here before:
     *
     *  - There is one upright, on the left. The right-hand one belongs to
     *    the module after it, and ten modules have eleven uprights between
     *    them. Two neighbours drawing an upright each is the double stroke
     *    that gave this away.
     *  - The diagonals are a little fatter than the rails — 0.090 against
     *    0.081 — and they start almost in the corners rather than a tenth
     *    of the way along the rail.
     *  - The module is 0.541 as wide as it is tall, not 0.59, and every
     *    bar stops short of what it points at by about half its own width.
     *    That daylight is the display.
     *
     * The rails are 0.0811 of a module's height thick in the file, and the
     * thickness knob is read against that: at 0.0811 this display is the
     * drawing at 1:1, and every other setting is the drawing's own outlines
     * moved along their normals. The diagonals stay proportionally fatter
     * because that difference is inside the polygons and not applied to
     * them.
     */
    private const val ROMAN_BAR = 0.0811f

    /**
     * How thick a bar of [kind] is when nobody has asked for anything, as
     * a share of a module's height.
     *
     * The thickness knob is a multiple of this rather than a number of its
     * own, because "normal" does not mean the same thing on three displays
     * that were not drawn to the same weight. Rome's is the figure out of
     * the file, so at normal that display is the drawing at 1:1; the other
     * two are what they were tuned to by eye.
     */
    fun native(kind: Kind): Float = when (kind) {
        Kind.SIXTEEN -> ROMAN_BAR
        else -> 0.055f
    }

    /** Whether [kind]'s bars have an exact shape to copy rather than a
     * sliver to make up. */
    fun drawn(kind: Kind): Boolean = kind == Kind.SIXTEEN

    private val SIXTEEN_BARS = listOf(
        Bar(
            TOP, 0.069f, 0.004f, 0.931f, 0.004f,
            outline = floatArrayOf(
                0.0873f, -0.0406f, 0.0688f, -0.0116f, 0.1653f, 0.0291f,
                0.2268f, 0.0405f, 0.7732f, 0.0405f, 0.8347f, 0.0291f,
                0.9312f, -0.0116f, 0.9127f, -0.0406f
            )
        ),
        Bar(
            BOTTOM, 0.069f, 0.996f, 0.931f, 0.996f,
            outline = floatArrayOf(
                0.0873f, 1.0406f, 0.0688f, 1.0115f, 0.1653f, 0.9709f,
                0.2268f, 0.9595f, 0.7732f, 0.9595f, 0.8347f, 0.9709f,
                0.9312f, 1.0115f, 0.9127f, 1.0406f
            )
        ),
        Bar(
            LEFT, 0f, 0.041f, 0f, 0.959f,
            outline = floatArrayOf(
                0.0732f, 0.8377f, 0.0750f, 0.8273f, 0.0750f, 0.1727f,
                0.0732f, 0.1623f, 0.0294f, 0.0411f, -0.0294f, 0.0411f,
                -0.0732f, 0.1623f, -0.0750f, 0.1727f, -0.0750f, 0.8273f,
                -0.0732f, 0.8377f, -0.0294f, 0.9589f, 0.0294f, 0.9589f
            )
        ),
        Bar(
            RIGHT, 1f, 0.041f, 1f, 0.959f,
            outline = floatArrayOf(
                1.0732f, 0.8377f, 1.0750f, 0.8273f, 1.0750f, 0.1727f,
                1.0732f, 0.1623f, 1.0294f, 0.0411f, 0.9706f, 0.0411f,
                0.9268f, 0.1623f, 0.9250f, 0.1727f, 0.9250f, 0.8273f,
                0.9268f, 0.8377f, 0.9706f, 0.9589f, 1.0294f, 0.9589f
            )
        ),
        Bar(
            G1, 0.105f, 0.5f, 0.376f, 0.5f,
            outline = floatArrayOf(
                0.3266f, 0.5405f, 0.3534f, 0.5316f, 0.3759f, 0.5072f,
                0.3759f, 0.4927f, 0.3534f, 0.4684f, 0.3266f, 0.4595f,
                0.1350f, 0.4595f, 0.1050f, 0.4757f, 0.1050f, 0.5243f,
                0.1350f, 0.5405f
            )
        ),
        Bar(
            H, 0.085f, 0.031f, 0.472f, 0.488f,
            outline = floatArrayOf(
                0.4850f, 0.4757f, 0.4550f, 0.4919f, 0.4272f, 0.4919f,
                0.4004f, 0.4829f, 0.1098f, 0.1688f, 0.1011f, 0.1549f,
                0.0601f, 0.0414f, 0.1080f, 0.0255f, 0.1508f, 0.0435f,
                0.1787f, 0.0620f, 0.4744f, 0.3817f, 0.4850f, 0.4059f
            )
        ),
        Bar(
            J, 0.915f, 0.031f, 0.528f, 0.488f,
            outline = floatArrayOf(
                0.5150f, 0.4757f, 0.5450f, 0.4919f, 0.5728f, 0.4919f,
                0.5996f, 0.4829f, 0.8902f, 0.1688f, 0.8990f, 0.1549f,
                0.9399f, 0.0414f, 0.8920f, 0.0255f, 0.8492f, 0.0435f,
                0.8213f, 0.0620f, 0.5256f, 0.3817f, 0.5150f, 0.4059f
            )
        ),
        Bar(
            K, 0.915f, 0.969f, 0.528f, 0.512f,
            outline = floatArrayOf(
                0.5150f, 0.5243f, 0.5450f, 0.5081f, 0.5728f, 0.5081f,
                0.5996f, 0.5171f, 0.8902f, 0.8312f, 0.8990f, 0.8451f,
                0.9399f, 0.9586f, 0.8920f, 0.9745f, 0.8492f, 0.9565f,
                0.8213f, 0.9380f, 0.5256f, 0.6183f, 0.5150f, 0.5941f
            )
        ),
        Bar(
            M, 0.085f, 0.969f, 0.472f, 0.512f,
            outline = floatArrayOf(
                0.4850f, 0.5243f, 0.4550f, 0.5081f, 0.4272f, 0.5081f,
                0.4004f, 0.5171f, 0.1098f, 0.8312f, 0.1011f, 0.8451f,
                0.0601f, 0.9586f, 0.1080f, 0.9745f, 0.1508f, 0.9565f,
                0.1787f, 0.9380f, 0.4744f, 0.6183f, 0.4850f, 0.5941f
            )
        ),
        Bar(DOT, 0.764f, 0.5f, 0.764f, 0.5f, dot = true, radius = 0.0540f)
    )

    private val STAR_BARS: List<Bar> = buildList {
        val ux = 0.5f
        val uy = 0.25f
        val ly = 0.75f
        // Every arm runs out of its star's middle and stops short of it,
        // the same way the four at the waist do. It was drawn the other
        // way round first — the arms overlapping the centre so the glyph
        // read as one stroke — and that was the wrong instinct twice over:
        // it makes eight bars into a blob at exactly the point the eye
        // lands, and it is not what the chart shows. Every arm on the
        // chart is a separate piece of metal with daylight round its foot,
        // which is what a segment display is.
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
            Bar(bit, ux, cy, tx, ty, flat = 0.15f, shoulder = 0.13f)
        add(arm(UN, uy, 0.5f, 0f))
        add(arm(UNE, uy, 1f, 0f))
        add(arm(UE, uy, 1f, 0.25f))
        add(arm(USE, uy, 1f, 0.5f))
        add(arm(US, uy, 0.5f, 0.5f))
        add(arm(USW, uy, 0f, 0.5f))
        add(arm(UW, uy, 0f, 0.25f))
        add(arm(UNW, uy, 0f, 0f))
        // The waist. The two stars face each other across it without
        // touching, and the rail through it is two bars and not one, so
        // all four stop short of the point they meet at — see the ghosts
        // on the chart, which is the only place they are ever seen.
        add(arm(LN, ly, 0.5f, 0.5f))
        val rail = { bit: Int, tx: Float ->
            Bar(bit, ux, 0.5f, tx, 0.5f, flat = 0.15f, shoulder = 0.13f)
        }
        add(rail(MW, 0f))
        add(rail(ME, 1f))
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
        // The pitch between two uprights over the height between the two
        // rails, out of the drawing: 6.725 over 12.441.
        Kind.SIXTEEN -> 0.5406f
        Kind.STAR -> 0.50f
    }

    /**
     * Whether neighbouring modules share the upright between them.
     *
     * Rome's do, and the drawing settles it: ten modules in the specimen
     * strip have eighty-one polygons between them, which is ten lots of
     * eight and one upright over. Each module carries a left-hand upright
     * and the strip carries one more at the end. Drawing an upright on
     * each side of every module puts two strokes a hair apart at every
     * boundary, which is exactly what it looked like.
     *
     * Sharing costs something, and [spell] pays it rather than this. `MCM`
     * is `M`, `C`, `M`; the `C` has no upright of its own on the right but
     * the `M` after it lights the shared one, and 1980 came out MDMLXXX.
     * So a module whose right-hand neighbour would close it into a
     * different letter gets a blank module after it — one dark cell, only
     * where it is needed, instead of prising the whole display apart.
     */
    fun butted(kind: Kind): Boolean = kind == Kind.SIXTEEN

    /**
     * The daylight between two modules, as a share of a module's width.
     *
     * None, anywhere. The modules that share an upright have nothing to
     * put between them, and the two displays that do not share are grids
     * that were drawn touching.
     */
    fun gap(kind: Kind): Float = 0f

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

    /**
     * How many modules [text] takes on [kind].
     *
     * Not the number of characters: a `V` is two modules, and a `C` whose
     * neighbour would close it into a `D` buys a dark one — so this asks
     * [spell] rather than counting, and every caller that reserves room
     * gets the same answer as the thing that draws it.
     */
    fun width(kind: Kind, text: String): Int = spell(kind, text).size

    /**
     * Whether two letters standing next to each other would run together.
     *
     * Read off the drawings rather than reasoned about, and the reasoning
     * was wrong. The owner of this display sent two specimens — `MMXXIV`
     * and `VII·XII` — and both were decoded bar by bar off the image
     * rather than eyeballed. Ten modules each, and the dark ones fall in
     * exactly the places this predicate puts them:
     *
     *     M _ M _ X X _ I V V          V V _ I I · _ X _ I I
     *
     * Two things in that are not what a rule about ambiguity produces.
     * `XX` has no gap, and `II` has no gap either — but `MM`, `MX` and
     * `XI` all do. What separates them is not whether the pair could be
     * misread; it is whether there is a full-height upright lit between
     * them *and* something else pressed against it. Two `X`s share no
     * upright at all and have daylight between their diagonals. Two `I`s
     * share one, but an `I` is nothing but that upright, so what you see
     * is two strokes a whole module apart — which is exactly what two
     * ones should look like. An `M` beside anything glues its own upright
     * to the next letter's corner, and that is the join the eye reads as
     * one glyph.
     *
     * This replaces a narrower rule that only broke up pairs which would
     * spell a different letter. That rule was right about `MCM` and wrong
     * about everything else, and the display it produced was the one that
     * came back with "there are two Ms stuck together".
     */
    private fun runsTogether(a: Int, b: Int): Boolean {
        // The separator is the one exception, and its own shape explains
        // it: the dot sits right of centre in its module, so it already
        // has air on its left and none on its right. Both specimens show
        // a dark module after the dot and none before it.
        if (a and DOT != 0) return true
        val upright = a and RIGHT != 0 || b and LEFT != 0
        if (!upright) return false
        // Anything of a's that reaches its right-hand edge, or of b's that
        // reaches its left-hand one. The rails end a fifteenth of a module
        // short of the corner and the diagonals start a twelfth short,
        // which at this size is the same thing as touching.
        return a and (TOP or BOTTOM or J or K) != 0 ||
            b and (TOP or BOTTOM or H or M) != 0
    }

    /**
     * [text] as one mask per module, unknown characters left out.
     *
     * On a display whose modules share their uprights this is also where
     * the letters are spaced — see [runsTogether]. The gap goes between
     * *characters* and never inside one: a `V` is two modules of one
     * letter and putting a dark cell between its halves would take the
     * point off the V.
     */
    fun spell(kind: Kind, text: String): IntArray {
        val out = ArrayList<Int>(text.length * 2 + 2)
        var previous: Int? = null
        for (c in text) {
            val masks = masksOf(kind, c) ?: continue
            if (butted(kind) && previous != null && runsTogether(previous, masks.first())) {
                out += 0
            }
            masks.forEach { out += it }
            previous = masks.last()
        }
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
     *
     * Where the modules share their uprights this is also the only place
     * that knows it: N modules have N+1 uprights between and around them,
     * each drawn once, and each lit if either of the two letters it stands
     * between asks for it. Drawing one per side instead is two strokes a
     * hair apart at every boundary — the thing this display was reported
     * for.
     */
    fun plan(kind: Kind, masks: IntArray, burnt: IntArray? = null): List<Stroke> {
        if (masks.isEmpty()) return emptyList()
        val bars = bars(kind)
        val shared = butted(kind)

        fun dead(i: Int, bit: Int) = (burnt?.getOrNull(i) ?: 0) and bit != 0

        fun on(i: Int, bit: Int): Boolean {
            if (i !in masks.indices || bit == 0) return false
            if (masks[i] and bit == 0) return false
            // A bar somebody has poked out stays dark however hard the
            // number tries to light it. That is what a dead segment is,
            // and it is why a poked clock can end up lying to you.
            return !dead(i, bit)
        }

        /**
         * Whether the upright to the left of module [i] is lit. Either
         * neighbour can light it, and a poke on either side kills it —
         * there is one piece of metal there, not two.
         */
        fun upright(i: Int): Boolean {
            if (dead(i, LEFT) || (i > 0 && dead(i - 1, RIGHT))) return false
            if (i in masks.indices && masks[i] and LEFT != 0) return true
            return i > 0 && masks[i - 1] and RIGHT != 0
        }

        val left = bars.firstOrNull { it.bit == LEFT }
        val right = bars.firstOrNull { it.bit == RIGHT }
        val out = ArrayList<Stroke>(masks.size * bars.size)
        for (wanted in listOf(false, true)) {
            for (i in masks.indices) {
                for (bar in bars) {
                    if (shared && (bar.bit == LEFT || bar.bit == RIGHT)) continue
                    if (on(i, bar.bit) != wanted) continue
                    out += Stroke(i, bar, wanted)
                }
            }
            if (!shared || left == null || right == null) continue
            for (i in masks.indices) {
                if (upright(i) == wanted) out += Stroke(i, left, wanted)
            }
            // And the one that closes the row, which belongs to no module
            // and is drawn on the right-hand edge of the last.
            val last = masks.size - 1
            val end = !dead(last, RIGHT) && masks[last] and RIGHT != 0
            if (end == wanted) out += Stroke(last, right, wanted)
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
