package com.em87.weirdclock

import kotlin.math.abs

/**
 * The two instruments that stood beside a sundial, as arithmetic.
 *
 * A garden dial was rarely alone. The pedestal it stood on carried a
 * weather glass and a thermometer, because the three of them answered the
 * three questions somebody walking outside actually had: what time is it,
 * is it going to rain, and how cold is it. This app has had the first for
 * thirty-four versions and the third quantity has been sitting unread in
 * [Weather.Sky] since the weather arrived.
 *
 * Everything here is a number turned into a position on a scale, kept
 * apart from the drawing for the usual reason: where a needle points is a
 * claim about the weather and can be wrong, and a claim that can be wrong
 * should be measurable without a screen.
 *
 * **The barometer is not a rain forecast** and the legend on its face is
 * not one either. Those five words are what was engraved on English
 * aneroids from about 1850 — they are a rule of thumb about pressure over
 * the British Isles, they are wrong at altitude, wrong in the tropics and
 * only ever half right anywhere. They are on this dial because they were
 * on that dial. What is honest is the needle and the number under it.
 */
object WeatherGlass {

    // -------------------------------------------------------- the glass

    /**
     * The ends of the barometer's arc, in hectopascals.
     *
     * A real aneroid is engraved 28 to 31 inches of mercury, which is
     * 948 to 1050 hPa — near enough the range sea-level pressure has ever
     * been recorded in outside a hurricane. Rounded to the fifties because
     * a scale that starts at 948 is a scale that was converted from
     * something else in front of you.
     */
    const val LOW_HPA = 950.0
    const val HIGH_HPA = 1050.0

    /**
     * The five words, each at the pressure it was engraved at.
     *
     * Taken off the traditional face, where they sit at the half-inch
     * marks: STORMY at 28½, RAIN at 29, CHANGE at 29½, FAIR at 30 and
     * VERY DRY at 30½ inches of mercury. Converted once, here, rather than
     * being invented as round numbers.
     */
    val MARKS = listOf(965.0, 982.0, 999.0, 1016.0, 1033.0)

    /**
     * Which of the five a needle at [hPa] is pointing at.
     *
     * The nearest mark, which is how anybody reads a needle against words
     * rather than against numbers — not a band, because bands have edges
     * and a needle sitting exactly on RAIN would otherwise have to be
     * ruled into one side of it.
     *
     * Returns the index into [MARKS], so the caller can look up whichever
     * of the two languages it is drawing in.
     */
    fun legend(hPa: Double): Int {
        var best = 0
        for (i in MARKS.indices) {
            if (abs(MARKS[i] - hPa) < abs(MARKS[best] - hPa)) best = i
        }
        return best
    }

    /**
     * Where the needle sits on its arc: nought at the left stop, one at
     * the right.
     *
     * Clamped, and that matters more than it looks. A pressure outside
     * the scale is not a reading to be thrown away — 940 hPa is a real
     * thing that happens in a deep Atlantic low, and the honest drawing of
     * it is a needle hard against the stop, which is what a real aneroid
     * does too.
     */
    fun swing(hPa: Double): Float =
        (((hPa - LOW_HPA) / (HIGH_HPA - LOW_HPA)).toFloat()).coerceIn(0f, 1f)

    // --------------------------------------------------- the thermometer

    /**
     * The ends of the thermometer's tube, in degrees Celsius.
     *
     * A garden thermometer's range rather than a laboratory's: cold enough
     * for a hard frost anywhere this app is likely to be standing, hot
     * enough for a bad August in Seville, and no wider — a tube marked to
     * a hundred spends its whole life with the column in the bottom third.
     */
    const val LOW_C = -20.0
    const val HIGH_C = 50.0

    /** How full the tube is: nought at the bulb, one at the top mark. */
    fun column(celsius: Double): Float =
        (((celsius - LOW_C) / (HIGH_C - LOW_C)).toFloat()).coerceIn(0f, 1f)

    /**
     * Where the ticks go up the tube, as fractions of it.
     *
     * Every ten degrees, which on this range is eight marks. Worked out
     * rather than listed so the range and the marks cannot drift apart.
     */
    fun ticks(everyC: Double = 10.0): List<Float> {
        val out = ArrayList<Float>()
        var c = Math.ceil(LOW_C / everyC) * everyC
        while (c <= HIGH_C + 1e-9) {
            out += column(c)
            c += everyC
        }
        return out
    }

    /**
     * Whether either instrument has anything to show.
     *
     * Both are drawn or neither is. One brass instrument on a pedestal
     * with an empty bracket beside it reads as a broken pedestal, and the
     * two quantities come from the same three services in the same
     * request — so the case where exactly one is missing is a service
     * answering half a question, not a thermometer that has fallen off.
     */
    fun readable(sky: Weather.Sky): Boolean =
        sky.pressureHpa.value != null && sky.temperatureC.value != null

    /**
     * How faint to draw them, out of 255.
     *
     * The same rule the sky token follows: a reading no second service
     * confirmed is shown, because a lone reading is what is left on the
     * day the others are down, and shown faintly, because a number nobody
     * has checked is not the same object as one two services measured.
     */
    fun ink(sky: Weather.Sky): Int =
        if (sky.pressureHpa.trust == Weather.Trust.AGREED &&
            sky.temperatureC.trust == Weather.Trust.AGREED
        ) SURE else UNSURE

    const val SURE = 200
    const val UNSURE = 105
}
