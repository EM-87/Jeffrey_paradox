package com.em87.weirdclock

/**
 * Egyptian numerals, for the years before the year one.
 *
 * The oldest counting there is a picture of. It is additive and has no
 * nought and no place value: a symbol for each power of ten, written as
 * many times as you need it, biggest first. Two thousand and forty is two
 * lotuses, four heel bones, and nothing at all where a zero would go —
 * which is the point, since there is nothing there to say.
 *
 * That makes it the honest end of the road the other scripts are on.
 * Roman is what the years behind us were written in; Egyptian is what was
 * written before anybody had thought of Roman, and it stops being a way
 * of writing numbers and starts being a way of drawing them.
 *
 * The symbols are here as a tally — how many of each power — and are
 * drawn in [ClockView], the same division the other two alphabets keep:
 * what can be counted lives where it can be counted without a screen.
 */
object Egyptian {

    /**
     * The powers, smallest first, and what each one is a picture of.
     *
     * Named for the things rather than the numbers because that is what
     * they are: the Egyptians did not draw a symbol meaning a hundred,
     * they drew a coiled rope, and a hundred is what a coiled rope was
     * worth.
     */
    enum class Sign { STROKE, HEEL, COIL, LOTUS, FINGER, TADPOLE, GOD }

    /** The signs in order, each ten times the one before it. */
    val signs: List<Sign> = listOf(
        Sign.STROKE, Sign.HEEL, Sign.COIL, Sign.LOTUS,
        Sign.FINGER, Sign.TADPOLE, Sign.GOD
    )

    /** What a sign is worth. */
    fun valueOf(sign: Sign): Int = when (sign) {
        Sign.STROKE -> 1
        Sign.HEEL -> 10
        Sign.COIL -> 100
        Sign.LOTUS -> 1_000
        Sign.FINGER -> 10_000
        Sign.TADPOLE -> 100_000
        Sign.GOD -> 1_000_000
    }

    /** The largest number this can write: nine of every sign. */
    const val MAX = 9_999_999

    /**
     * How many of each sign a number needs, biggest first.
     *
     * The order matters: it is the order they are written in, and a tally
     * handed back smallest-first would draw every date backwards.
     *
     * Nothing at all for zero or less, because there is nothing to draw —
     * a numeral system with no nought cannot write one, and the caller has
     * to decide what to do about it rather than being handed a blank.
     */
    fun tally(value: Int): List<Pair<Sign, Int>> {
        // No guard for nothing. A count of zero is skipped below and a
        // negative one is too, so a nought and a minus both come back as
        // an empty list without being asked about — and a guard that
        // cannot change an answer is a line that cannot be tested.
        var left = value.coerceAtMost(MAX)
        val out = ArrayList<Pair<Sign, Int>>(signs.size)
        for (sign in signs.reversed()) {
            val worth = valueOf(sign)
            val count = left / worth
            if (count > 0) {
                out.add(sign to count)
                left -= count * worth
            }
        }
        return out
    }

    /** How many signs altogether, which is how wide the number is drawn. */
    fun signCount(value: Int): Int = tally(value).sumOf { it.second }

    /**
     * How the repeats of one sign are stacked.
     *
     * Nine strokes in a row is a fence; the Egyptians wrote them in two or
     * three short rows, and so does this. Up to three across, and as many
     * rows as that needs — which for nine is three by three, and for four
     * is two by two.
     */
    fun rowsFor(count: Int): Int = when {
        count <= 3 -> 1
        count <= 6 -> 2
        else -> 3
    }

    /** And how many go in each row. */
    fun perRow(count: Int): Int = (count + rowsFor(count) - 1) / rowsFor(count)
}
