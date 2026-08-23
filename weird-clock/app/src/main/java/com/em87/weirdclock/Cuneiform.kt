package com.em87.weirdclock

/**
 * Sumerian and Babylonian numerals, for the years before Egypt.
 *
 * Wound back far enough the hieroglyphs run out. Egyptian numerals and the
 * Egyptian calendar are both a little over five thousand years old, and
 * before them the oldest writing anybody has is Mesopotamian: wedges
 * pressed into wet clay with the corner of a cut reed, in the cities on
 * the Euphrates, a few centuries before the first pharaoh.
 *
 * It is a stranger system than the Egyptian one and much better. There are
 * only two signs — a vertical wedge worth one and a corner wedge worth ten
 * — and they are written together up to fifty-nine; past that the whole
 * group shifts left and means sixty times as much. That is place value,
 * three thousand years before anyone else had it, and it is why there are
 * sixty minutes in an hour and sixty seconds in a minute. A clock counting
 * in this is not a novelty: it is the notation the clock is written in,
 * put back the way it was found.
 *
 * The one thing it does not have is a nought, which for a place-value
 * system is a real hole — a blank column and a missing column look the
 * same, and for two thousand years they simply lived with it. So does
 * this: an empty place is an empty place, drawn as the gap it was.
 */
object Cuneiform {

    /**
     * One sexagesimal place: however many tens and however many ones.
     *
     * Both at once, because that is how a place is written — the corner
     * wedges first and the vertical ones after them, in one group, and it
     * is the group as a whole that is worth sixty times the group to its
     * right.
     */
    data class Place(val tens: Int, val ones: Int) {
        /** What this place is worth before the shift is applied. */
        val value: Int get() = tens * 10 + ones

        /** A place with nothing in it, which is the hole where a nought is not. */
        val isEmpty: Boolean get() = tens == 0 && ones == 0
    }

    /** Sixty, and the reason an hour has sixty minutes in it. */
    const val BASE = 60

    /** The largest number four places can say. */
    const val MAX = BASE * BASE * BASE * BASE - 1

    /**
     * A number split into its places, most significant first.
     *
     * Nothing at all for nothing: with no nought there is no way to write
     * a zero, and the caller has to decide what to do about that rather
     * than being handed a row of blanks that looks like a display fault.
     * An empty place *inside* a number is kept, though — that is the hole
     * the system genuinely had, and closing it would make 3601 read as 61.
     */
    fun places(value: Int): List<Place> {
        if (value <= 0) return emptyList()
        var left = value.coerceAtMost(MAX)
        val digits = ArrayList<Int>(4)
        while (left > 0) {
            digits.add(left % BASE)
            left /= BASE
        }
        return digits.reversed().map { Place(it / 10, it % 10) }
    }

    /**
     * How the repeats of one wedge are stacked.
     *
     * Nine wedges side by side is a fence. The scribes wrote them in two
     * or three short rows and so does this — the same rule the hieroglyphs
     * follow, for the same reason, which is that a number has to be read
     * at a glance rather than counted.
     */
    fun rowsFor(count: Int): Int = when {
        count <= 3 -> 1
        count <= 6 -> 2
        else -> 3
    }

    /** And how many go in each row. */
    fun perRow(count: Int): Int = (count + rowsFor(count) - 1) / rowsFor(count)

    /** How many wedges altogether, which is how much clay the number takes. */
    fun wedgeCount(value: Int): Int = places(value).sumOf { it.tens + it.ones }

    // ------------------------------------------------------------ the words

    /**
     * The three signs that say what a number is counting.
     *
     * The user asked whether the wedge date was a straight transcription
     * of ours with no word for day or month in it, and it was. A
     * Mesopotamian date is not three numbers: it is *MU* n, *ITI* n, *UD*
     * n — year, month, day — with the word written in front of each. They
     * are among the commonest signs on any tablet, because almost every
     * tablet is dated.
     *
     * How honest this can be has a limit, and it is worth being plain
     * about. These signs and this way of dating belong to Sumerian and
     * Babylonian scribes of the third millennium and after. The window
     * this dial writes in wedges is earlier than that — proto-cuneiform,
     * the Uruk tablets, where the marks are still pictures and almost
     * every surviving one is an account of barley or beer rather than a
     * dated record. There is no reconstructable civil calendar for it. So
     * the words are right for cuneiform and early for the exact centuries
     * they are drawn in, and the numbers underneath are what can honestly
     * be shown: the year, month and day of a calendar the dial has to have
     * in order to have a date at all.
     *
     * Nor was the Babylonian calendar a fixed thing. It was lunisolar —
     * months began when the new crescent was seen, and a thirteenth month
     * was inserted when the king or, later, the astronomers said so. The
     * regular nineteen-year cycle is a fifth-century invention, two and a
     * half thousand years after the window this writes in.
     */
    enum class Word { YEAR, MONTH, DAY }

    /**
     * The gap between the tens and the ones of one place, in wedge widths.
     *
     * Small: they are one group and have to read as one, the way the two
     * halves of "45" do.
     */
    const val GROUP_GAP = 0.16f

    /**
     * And the gap between one place and the next.
     *
     * Much bigger, and that is not decoration. With no nought, white space
     * is the only thing carrying the place value: one wedge, a gap, two
     * corner wedges is eighty, and the same wedges without the gap is
     * twenty-one. If the two gaps look alike the number cannot be read,
     * and an empty place — the hole where a nought is not — is nothing but
     * this gap, twice.
     */
    const val PLACE_GAP = 1.30f
}
