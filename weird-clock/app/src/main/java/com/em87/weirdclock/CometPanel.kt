package com.em87.weirdclock

import java.util.Calendar

/**
 * The Roman Comet: one panel, two alphabets.
 *
 * The last drawing the owner of this app handed over, and the one that
 * settles what the other two were for. A calculator's nine segments can
 * draw a beautiful number and cannot write a single letter; Rome's module
 * can write eight letters and draws numbers that nobody can read at a
 * glance. Neither is a whole clock. Put one above the other and each does
 * the thing it is good at: the time in the Comet's nine, big, and the date
 * in Rome's module, in two rails above and below it.
 *
 * So this replaces both of them rather than joining them on the list. They
 * were never two displays; they were two halves of this one.
 *
 * Everything here is proportion and text — where the rails sit against the
 * digits, how wide the lamps are, what each rail says on a given day — and
 * none of it needs a screen. The drawing is [DigitalClockView]'s.
 *
 * The panel has more on it than this: a battery, two gears and a wifi fan,
 * drawn for an ESP32 that is not this phone. They are deliberately absent.
 * The two that mean something to a clock are here.
 */
object CometPanel {

    /**
     * How tall a Roman rail is against one Comet digit.
     *
     * Out of the drawing: the module measures 12.441 between its rails and
     * the digit 15.228 from the top of its roof to the foot of its floor.
     * Not a guess and not a taste — at any other ratio the two alphabets
     * stop looking like one instrument.
     */
    const val RAIL = 0.8169f

    /**
     * And the daylight between a rail and the digits, in digit heights.
     *
     * The drawing's two gaps are 5.725 above and 5.580 below, which is the
     * same gap drawn twice by hand. Split the difference.
     */
    const val RAIL_GAP = 0.3711f

    /**
     * How far the colon's two dots lean apart, in digit heights.
     *
     * The whole alphabet leans, and on the drawing so does the colon: its
     * upper lamp sits 0.955 to the right of the lower one. A colon drawn
     * as two dots on a vertical line between two leaning digits is the one
     * upright thing on the panel and it shows.
     */
    const val COLON_LEAN = 0.0627f

    /** How wide the lamps stand off the digits, in digit heights. */
    const val LAMP_GAP = 0.22f

    /**
     * The date, as the two rails say it.
     *
     * Day and month above, the year below — which is how the drawing is
     * proportioned and how a clock of this shape has always been laid out:
     * the thing that changes daily on top, the thing that changes once a
     * year underneath.
     *
     * Both in Rome's numerals, because that is the alphabet that rail is
     * made of. There is no choice here about which numerals: a sixteen-bar
     * module cannot write an 8.
     *
     * And there is no day of the week on either rail, because there cannot
     * be. Rome's module writes eight letters — I, V, X, L, C, D, M, N — and
     * every Latin day name wants one it has not got: SOL and SAT an S, IOV
     * an O, MAR and MER an R, VEN an E. Only LVN fits, and a clock that can
     * name one day in seven is worse than a clock that names none. The
     * alternative was the phone's own type beside two rails of lit metal,
     * which is the thing this app keeps finding and calling two clocks in
     * one line. The switch is faded on this script rather than removed —
     * see [SettingsActivity] — because turning it on and then changing
     * script is an ordinary thing to want to do.
     */
    fun rails(calendar: Calendar, dayFirst: Boolean): Pair<String, String> {
        val day = DigitalReadout.roman(calendar.get(Calendar.DAY_OF_MONTH))
        val month = DigitalReadout.roman(calendar.get(Calendar.MONTH) + 1)
        return (if (dayFirst) "$day\u00b7$month" else "$month\u00b7$day") to
            DigitalReadout.roman(calendar.get(Calendar.YEAR))
    }

    /**
     * Whether the moon lamp is lit.
     *
     * It is the panel's whole answer to which half of the day this is, and
     * the drawing gives it one lamp rather than a pair — so it lights
     * before noon and goes out after, which is the same convention the
     * other faces draw as a moon and a sun. On a twenty-four hour clock
     * there is no ambiguity to resolve and it never lights, the way the
     * AM and PM legends on a real panel do not.
     */
    fun moonLit(hour: Int, hour24: Boolean): Boolean = !hour24 && hour < 12

    // BELL: 0.3435 wide by 0.5597 tall, in digit heights
    val BELL: Array<FloatArray> = arrayOf(
        floatArrayOf(
            0.8095f, 0.2391f, 0.7945f, 0.1834f, 0.7542f, 0.1326f,
            0.6918f, 0.0911f, 0.6127f, 0.0624f, 0.5891f, 0.0236f,
            0.5337f, 0.0000f, 0.4663f, 0.0000f, 0.4109f, 0.0236f,
            0.3873f, 0.0624f, 0.3082f, 0.0911f, 0.2458f, 0.1326f,
            0.2055f, 0.1834f, 0.1905f, 0.2391f, 0.1872f, 0.3573f,
            0.1767f, 0.3891f, 0.1483f, 0.4164f, 0.0939f, 0.4681f,
            0.0716f, 0.5282f, 0.0859f, 0.5516f, 0.1234f, 0.5615f,
            0.3366f, 0.5616f, 0.3658f, 0.6057f, 0.4246f, 0.6368f,
            0.5000f, 0.6480f, 0.5754f, 0.6368f, 0.6342f, 0.6057f,
            0.6634f, 0.5616f, 0.8766f, 0.5615f, 0.9141f, 0.5516f,
            0.9284f, 0.5282f, 0.9061f, 0.4681f, 0.8517f, 0.4164f,
            0.8233f, 0.3891f, 0.8128f, 0.3573f, 0.8095f, 0.2391f
        ),
        floatArrayOf(
            0.3648f, 0.6723f, 0.3122f, 0.6558f, 0.2538f, 0.6615f,
            0.2120f, 0.6871f, 0.2028f, 0.7229f, 0.2296f, 0.7553f,
            0.3264f, 0.7984f, 0.4402f, 0.8211f, 0.5598f, 0.8211f,
            0.6736f, 0.7984f, 0.7704f, 0.7553f, 0.7972f, 0.7229f,
            0.7880f, 0.6871f, 0.7462f, 0.6615f, 0.6878f, 0.6558f,
            0.6352f, 0.6723f, 0.5732f, 0.6977f, 0.5000f, 0.7066f,
            0.4268f, 0.6977f, 0.3648f, 0.6723f
        ),
        floatArrayOf(
            0.1620f, 0.7967f, 0.1094f, 0.7803f, 0.0510f, 0.7859f,
            0.0092f, 0.8116f, 0.0000f, 0.8474f, 0.0268f, 0.8797f,
            0.1654f, 0.9450f, 0.3268f, 0.9860f, 0.5000f, 1.0000f,
            0.6732f, 0.9860f, 0.8346f, 0.9450f, 0.9732f, 0.8797f,
            1.0000f, 0.8474f, 0.9908f, 0.8116f, 0.9490f, 0.7859f,
            0.8906f, 0.7803f, 0.8380f, 0.7967f, 0.7390f, 0.8434f,
            0.6237f, 0.8727f, 0.5000f, 0.8827f, 0.3763f, 0.8727f,
            0.2610f, 0.8434f, 0.1620f, 0.7967f
        ),
    )

    // MOON: 0.3189 wide by 0.3189 tall, in digit heights
    val MOON: Array<FloatArray> = arrayOf(
        floatArrayOf(
            0.9580f, 0.8980f, 0.9980f, 0.8474f, 1.0000f, 0.7830f,
            0.9633f, 0.7300f, 0.9023f, 0.7091f, 0.7447f, 0.6870f,
            0.5979f, 0.6254f, 0.4718f, 0.5282f, 0.3746f, 0.4021f,
            0.3130f, 0.2553f, 0.2909f, 0.0977f, 0.2700f, 0.0367f,
            0.2170f, 0.0000f, 0.1526f, 0.0020f, 0.1020f, 0.0420f,
            0.0291f, 0.1941f, 0.0000f, 0.3602f, 0.0170f, 0.5280f,
            0.0787f, 0.6850f, 0.1805f, 0.8195f, 0.3150f, 0.9213f,
            0.4720f, 0.9830f, 0.6398f, 1.0000f, 0.8059f, 0.9709f,
            0.9580f, 0.8980f
        ),
    )
}
