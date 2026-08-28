package com.em87.weirdclock

/**
 * Four displays, described once.
 *
 * Every number this app shows without hands goes through one of these:
 * seven bars for ours, the module below for Rome's, a pair of stacked stars
 * for theirs, and a calculator's nine. They are described here as topology
 * — where each bar runs inside a unit box, and which bars each character
 * lights — and drawn by [SegmentPainter], so a shape can be counted and
 * compared without a screen and so there is one place where a `V` is wrong
 * rather than four.
 *
 * The alphabets are not inventions. Three of the four came out of drawings
 * the owner of this app handed over, and the fourth off a chart of the same
 * provenance. Two were reconstructed here from memory first and both came
 * out subtly wrong in the way reconstructions do — the `V` in particular,
 * which is the reason this file exists. Nothing in here is drawn from
 * memory now.
 */
object Segments {

    /** Which of the four displays a character is going on. */
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
        STAR,

        /**
         * The Sharp Comet's nine, copied from the drawing.
         *
         * A calculator display from 1964, and the only one of the four
         * that somebody sat down and *designed*. Seven bars is what falls
         * out of drawing a figure eight with the fewest straight lines;
         * this is what happens when the same problem is handed to a
         * draughtsman who wants the numbers to look like numbers.
         *
         * The nine are a rail top and bottom, four arms into the corners,
         * two stems up the middle, and a short dash at the waist. Three
         * things about it are not what a seven-bar eye expects, and all
         * three are the point of it:
         *
         *  - It leans, and every piece of it is a calligraphic stroke —
         *    thin at the tips, fat in the belly, hooked at the ends. Not
         *    one of the nine is a rectangle.
         *  - There is no middle rail. A `2` runs its top-right arm into
         *    its bottom-left one and they meet in the centre on their own.
         *    The waist dash only fills the corner that crossing leaves
         *    open, which is why it lights on `4`, `5`, `6`, `8` and `9`
         *    and not on `2` or `3`.
         *  - The `1` is a stroke up the middle rather than the two
         *    right-hand bars, so it stands in its own cell instead of
         *    leaning against the next digit.
         */
        NINE
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

    // -------------------------------------------------- the Comet's nine

    /*
     *      ==ROOF==
     *     /       /|
     *  ARM_NW  STEM_UP  ARM_NE
     *    /       /  |
     *     ·     ·  -WAIST
     *    /       /  |
     *  ARM_SW STEM_DOWN ARM_SE
     *   /       /    |
     *    ==FLOOR==
     *
     * Leaning right, because the whole alphabet does.
     */
    const val ROOF = 1 shl 0
    const val ARM_NW = 1 shl 1
    const val STEM_UP = 1 shl 2
    const val ARM_NE = 1 shl 3
    const val WAIST = 1 shl 4
    const val ARM_SW = 1 shl 5
    const val STEM_DOWN = 1 shl 6
    const val ARM_SE = 1 shl 7
    const val FLOOR = 1 shl 8

    /**
     * The Comet's ten, read off the machine.
     *
     * Four of them were in the drawing already — it spells `12:43` in lit
     * segments in one corner — and the owner of this app sent a photograph
     * of the real tube counting `12345678` to settle the rest, which is
     * the only reason `7` is an arm and a stem rather than the two
     * right-hand arms a seven-bar display would use.
     *
     * The tell that this is the machine's table and not a translation of
     * the seven-bar one: `2` and `3` light no waist, where every other
     * display's `2` and `3` light their middle rail. They do not need it —
     * their arms already cross the middle — and putting it in makes both
     * of them read as an `8` with pieces missing.
     */
    private val COMET = intArrayOf(
        ROOF or ARM_NW or ARM_NE or ARM_SW or ARM_SE or FLOOR,           // 0
        STEM_UP or STEM_DOWN,                                            // 1
        ROOF or ARM_NE or ARM_SW or FLOOR,                               // 2
        ROOF or ARM_NE or ARM_SE or FLOOR,                               // 3
        ARM_NW or ARM_NE or WAIST or STEM_DOWN,                          // 4
        ROOF or ARM_NW or WAIST or ARM_SE or FLOOR,                      // 5
        ROOF or ARM_NW or WAIST or ARM_SW or ARM_SE or FLOOR,            // 6
        ROOF or ARM_NE or STEM_DOWN,                                     // 7
        ROOF or ARM_NW or ARM_NE or WAIST or ARM_SW or ARM_SE or FLOOR,  // 8
        ROOF or ARM_NW or ARM_NE or WAIST or ARM_SE or FLOOR             // 9
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
     *
     * [curved] says the outline bends, and so changes which way the
     * thickness knob works on it: a bent bar is grown outwards from its
     * own edges rather than away from the straight line between its ends,
     * or the bend gets deeper instead of the metal getting thicker. Rome's
     * bars are straight and take the simpler treatment; the Comet's are
     * all bend.
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
        val outline: FloatArray? = null,
        val curved: Boolean = false
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
        Kind.NINE -> COMET_BAR
        else -> 0.055f
    }

    /**
     * How fat the dots of a separator are on [kind], across, as a share of
     * a digit's height.
     *
     * A separate measurement from [native], because on the two displays
     * that were drawn with one it is not the bar's thickness: Rome's dot
     * is a third again as wide as its rails, and the Comet's colon is
     * three pen widths across — a pair of round lamps beside a display of
     * hairline strokes, which is what the drawing shows and what a colon
     * made out of the bar's own width threw away.
     */
    fun separator(kind: Kind): Float = when (kind) {
        Kind.SIXTEEN -> 0.1080f
        Kind.NINE -> 0.1510f
        else -> native(kind) * 1.6f
    }

    /** Whether [kind]'s bars have an exact shape to copy rather than a
     * sliver to make up. */
    fun drawn(kind: Kind): Boolean = kind == Kind.SIXTEEN || kind == Kind.NINE

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

    /*
     * The Comet's nine, copied out of the drawing the owner of this app
     * handed over. The panel it came on has a bell on it, a SET label, and
     * AM and PM — which is a clock's furniture and not a calculator's, so
     * the drawing had already worked out what this display was for.
     *
     * Every vertex here is the file's. The curves in it are arcs, which a
     * polygon cannot hold, so each one is walked at a step fine enough that
     * it never sags more than a four-hundredth of a module — under a pixel
     * at any size a phone will draw this at.
     *
     * Two measurements out of the same file:
     *
     *  - The module is 0.656 as wide as it is tall, and the digits sit on
     *    a pitch a fifth wider than that. This is the one display here
     *    with daylight between its cells and it needs it — see [gap].
     *  - The pen is 0.032 of a module's height, measured across the waist
     *    dash, which is the only piece of the nine that is a plain stadium
     *    and the same width all the way along. Everything else tapers,
     *    which is why the thickness knob has to grow these outwards
     *    instead of measuring across them — see [SegmentPainter].
     */
    private const val COMET_BAR = 0.0320f

    private val NINE_BARS = listOf(
        Bar(
            ROOF, 0.8812f, 0.0054f, 0.2475f, 0.0875f, curved = true,
            outline = floatArrayOf(
                0.5076f, 0.0000f, 0.4119f, 0.0092f, 0.3244f, 0.0360f,
                0.2523f, 0.0782f, 0.2486f, 0.0826f, 0.2475f, 0.0875f,
                0.2581f, 0.1135f, 0.2845f, 0.1340f, 0.3033f, 0.1361f,
                0.3167f, 0.1273f, 0.3484f, 0.0852f, 0.3983f, 0.0516f,
                0.4616f, 0.0297f, 0.5320f, 0.0216f, 0.8670f, 0.0216f,
                0.8812f, 0.0162f, 0.8812f, 0.0054f, 0.8670f, 0.0000f
            )
        ),
        Bar(
            ARM_NW, 0.1858f, 0.1476f, 0.4994f, 0.5196f, curved = true,
            outline = floatArrayOf(
                0.4783f, 0.4956f, 0.3333f, 0.4956f, 0.2817f, 0.4855f,
                0.2469f, 0.4586f, 0.2404f, 0.4235f, 0.2965f, 0.2066f,
                0.2936f, 0.1897f, 0.2776f, 0.1763f, 0.2492f, 0.1604f,
                0.2252f, 0.1416f, 0.2024f, 0.1359f, 0.1858f, 0.1476f,
                0.1217f, 0.4144f, 0.1255f, 0.4563f, 0.1557f, 0.4933f,
                0.2068f, 0.5187f, 0.2693f, 0.5276f, 0.4783f, 0.5276f,
                0.4994f, 0.5196f, 0.4994f, 0.5036f
            )
        ),
        Bar(
            STEM_UP, 0.7597f, 0.0630f, 0.5230f, 0.4734f, curved = true,
            outline = floatArrayOf(
                0.6363f, 0.1162f, 0.5078f, 0.3795f, 0.4976f, 0.4191f,
                0.5050f, 0.4590f, 0.5230f, 0.4734f, 0.5515f, 0.4739f,
                0.5708f, 0.4603f, 0.6155f, 0.3724f, 0.6602f, 0.2846f,
                0.7714f, 0.0946f, 0.7730f, 0.0776f, 0.7597f, 0.0630f,
                0.7360f, 0.0560f, 0.6678f, 0.0587f, 0.6055f, 0.0769f,
                0.5569f, 0.1084f, 0.5570f, 0.1124f, 0.5626f, 0.1136f,
                0.5933f, 0.1088f, 0.6247f, 0.1064f, 0.6345f, 0.1093f
            )
        ),
        Bar(
            ARM_NE, 0.9598f, 0.0694f, 0.5884f, 0.4927f, curved = true,
            outline = floatArrayOf(
                0.9952f, 0.1266f, 0.9598f, 0.0694f, 0.9420f, 0.0583f,
                0.9175f, 0.0595f, 0.9026f, 0.0722f, 0.8520f, 0.1818f,
                0.7828f, 0.2869f, 0.6959f, 0.3862f, 0.5925f, 0.4784f,
                0.5884f, 0.4927f, 0.6046f, 0.5025f, 0.6259f, 0.4987f,
                0.7682f, 0.3969f, 0.8912f, 0.2849f, 0.9934f, 0.1642f,
                1.0004f, 0.1455f
            )
        ),
        Bar(
            WAIST, 0.6940f, 0.5036f, 0.8808f, 0.5196f, curved = true,
            outline = floatArrayOf(
                0.8597f, 0.5276f, 0.8808f, 0.5196f, 0.8808f, 0.5036f,
                0.8597f, 0.4956f, 0.7151f, 0.4956f, 0.6940f, 0.5036f,
                0.6940f, 0.5196f, 0.7151f, 0.5276f
            )
        ),
        Bar(
            ARM_SW, 0.4462f, 0.5821f, 0.0567f, 0.8302f, curved = true,
            outline = floatArrayOf(
                0.4127f, 0.5736f, 0.2764f, 0.6481f, 0.1570f, 0.7342f,
                0.0567f, 0.8302f, 0.1745f, 0.8302f, 0.2501f, 0.7457f,
                0.3389f, 0.6670f, 0.4402f, 0.5950f, 0.4462f, 0.5821f,
                0.4330f, 0.5719f
            )
        ),
        Bar(
            STEM_DOWN, 0.5135f, 0.6048f, 0.3154f, 0.9469f, curved = true,
            outline = floatArrayOf(
                0.4305f, 0.9469f, 0.5222f, 0.6219f, 0.5135f, 0.6048f,
                0.4868f, 0.6006f, 0.4673f, 0.6132f, 0.3154f, 0.9469f
            )
        ),
        Bar(
            ARM_SE, 0.5497f, 0.5488f, 0.7900f, 0.8302f, curved = true,
            outline = floatArrayOf(
                0.7214f, 0.7479f, 0.7155f, 0.7722f, 0.7052f, 0.7959f,
                0.6871f, 0.8302f, 0.7900f, 0.8302f, 0.7977f, 0.8195f,
                0.8239f, 0.7614f, 0.8214f, 0.7008f, 0.7905f, 0.6436f,
                0.7343f, 0.5955f, 0.6581f, 0.5611f, 0.5695f, 0.5438f,
                0.5497f, 0.5488f, 0.5453f, 0.5625f, 0.5604f, 0.5723f,
                0.6226f, 0.5910f, 0.6736f, 0.6210f, 0.7089f, 0.6595f,
                0.7253f, 0.7031f
            )
        ),
        Bar(
            FLOOR, 0.7668f, 0.8622f, -0.0008f, 0.9242f, curved = true,
            outline = floatArrayOf(
                0.1225f, 1.0000f, 0.5001f, 1.0000f, 0.5735f, 0.9932f,
                0.6411f, 0.9733f, 0.6975f, 0.9418f, 0.7384f, 0.9013f,
                0.7668f, 0.8622f, 0.6701f, 0.8622f, 0.6575f, 0.8859f,
                0.6279f, 0.9223f, 0.5829f, 0.9511f, 0.5266f, 0.9695f,
                0.4644f, 0.9759f, 0.1945f, 0.9759f, 0.1569f, 0.9696f,
                0.1286f, 0.9522f, 0.1163f, 0.9281f, 0.1232f, 0.9031f,
                0.1365f, 0.8825f, 0.1505f, 0.8622f, 0.0293f, 0.8622f,
                0.0204f, 0.8733f, 0.0117f, 0.8846f, -0.0008f, 0.9242f,
                0.0178f, 0.9627f, 0.0627f, 0.9903f, 0.1225f, 1.0000f
            )
        )
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
        Kind.NINE -> NINE_BARS
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
        // The ink of one digit, out of the drawing: 9.987 over 15.228.
        // Wider than the rest because it leans, and a leaning digit has to
        // pay for the run of its own slope.
        Kind.NINE -> 0.6558f
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
     * None on three of the four. The modules that share an upright have
     * nothing to put between them, and the two displays that do not share
     * are grids that were drawn touching.
     *
     * The Comet is the exception and its own drawing settles it: four
     * digits on a pitch of twelve, each of them ten wide. Two of its cells
     * butted together would interleave, because its arms lean out past the
     * edges of their own module — the foot of one digit would sit inside
     * the head of the next.
     */
    fun gap(kind: Kind): Float = if (kind == Kind.NINE) 0.2016f else 0f

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
        // A calculator's alphabet, which is the ten and a minus and
        // nothing else. There is not a letter in this display's reach.
        Kind.NINE -> when {
            c in '0'..'9' -> intArrayOf(COMET[c - '0'])
            c == '-' -> intArrayOf(WAIST)
            c == ' ' -> intArrayOf(0)
            else -> null
        }
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
