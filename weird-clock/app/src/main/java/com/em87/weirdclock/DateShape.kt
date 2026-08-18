package com.em87.weirdclock

/**
 * Which way round a date is written.
 *
 * The dial has always written `15/03/2026` and never asked. Half the world
 * writes `03/15/2026`, and the two are indistinguishable for twelve days of
 * every month — `03/04` is the third of April or the fourth of March and
 * there is nothing in it to say which. A clock that shows a date it cannot
 * be read off is showing a decoration.
 *
 * Three answers rather than two, and the third is the default: most people
 * have already told their phone which way round they write dates, and
 * asking them again is asking a question that has an answer sitting right
 * there. [AUTO] goes and reads it.
 */
object DateShape {

    enum class Order { AUTO, DAY_FIRST, MONTH_FIRST }

    const val AUTO = "auto"
    const val DAY_FIRST = "day_first"
    const val MONTH_FIRST = "month_first"

    /** Anything unrecognised follows the phone, which is the safe answer. */
    fun order(stored: String?): Order = when (stored) {
        DAY_FIRST -> Order.DAY_FIRST
        MONTH_FIRST -> Order.MONTH_FIRST
        else -> Order.AUTO
    }

    /**
     * Whether the day goes first, given [order] and what the phone says.
     *
     * [phoneSaysDayFirst] comes from the system's own date-order preference
     * — the same one that decides which way round every other app writes a
     * date — so a clock left on [Order.AUTO] agrees with the phone it is
     * running on rather than with the country this code was written in.
     */
    fun dayFirst(order: Order, phoneSaysDayFirst: Boolean): Boolean = when (order) {
        Order.DAY_FIRST -> true
        Order.MONTH_FIRST -> false
        Order.AUTO -> phoneSaysDayFirst
    }

    /** `15/03/2026`, or `03/15/2026`. */
    fun numberPattern(dayFirst: Boolean): String =
        if (dayFirst) "dd/MM/yyyy" else "MM/dd/yyyy"

    /**
     * `Sun 15 Mar`, or `Sun Mar 15`.
     *
     * The weekday stays at the front either way: nobody writes `15 Sun Mar`,
     * and the question this setting asks is only about the two numbers that
     * can be mistaken for each other.
     */
    fun textPattern(dayFirst: Boolean): String =
        if (dayFirst) "EEE d MMM" else "EEE MMM d"

    /** `XV·III·MMXXVI`, or `III·XV·MMXXVI`. */
    fun roman(day: Int, month: Int, year: Int, dayFirst: Boolean): String {
        val d = Roman.of(day)
        val m = Roman.of(month)
        val y = Roman.of(year)
        return if (dayFirst) "$d·$m·$y" else "$m·$d·$y"
    }
}
