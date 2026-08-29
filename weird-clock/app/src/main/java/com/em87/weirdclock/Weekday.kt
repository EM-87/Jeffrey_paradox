package com.em87.weirdclock

import java.util.Calendar
import java.util.Locale

/**
 * What day it is, in the language of the face saying it.
 *
 * Every digital clock ever built says the day of the week and this one did
 * not — which is the sort of hole that only shows up when you stand the
 * app next to the thing it is a copy of. A Casio has three letters in the
 * corner; a phone's lock screen has the whole word; a bedside clock has it
 * bigger than the date. It is the one thing about today that a number
 * cannot tell you, because 27/08 says nothing at all until you have
 * counted.
 *
 * Three answers, because the three scripts are three different claims
 * about what kind of clock this is and a day name has to belong to each:
 *
 *  - ours: whatever this phone calls it, short, from the system;
 *  - Rome's: the planet, in Latin, which is what a weekday *is* — the
 *    seven names are the seven wandering stars, and this app already draws
 *    them in the sky and names them in Latin when it is being Roman;
 *  - theirs: the number, in their numerals, because their alphabet has
 *    ten glyphs and none of them is a letter.
 */
object Weekday {

    /**
     * The planets, in the order the week is named after them.
     *
     * Indexed by [Calendar.DAY_OF_WEEK] minus one, so Sunday first —
     * which is the Sun's day, and the reason the list starts there rather
     * than on a Monday. Abbreviated to three, the length that fits beside
     * a date on a small face and the length a watch has always used.
     */
    private val LATIN = arrayOf("SOL", "LVN", "MAR", "MER", "IOV", "VEN", "SAT")

    /** The full Latin names, for anywhere with room for them. */
    private val LATIN_FULL = arrayOf(
        "Solis", "Lunae", "Martis", "Mercurii", "Iovis", "Veneris", "Saturni"
    )

    /** Rome's name for the day [dow], which is [Calendar.DAY_OF_WEEK]. */
    fun latin(dow: Int, full: Boolean = false): String {
        val i = (dow - 1).coerceIn(0, 6)
        return if (full) LATIN_FULL[i] else LATIN[i]
    }

    /**
     * The day's number, Monday being one.
     *
     * ISO order rather than the calendar's, because a week that starts on
     * a Sunday is a convention of one part of the world and a number that
     * has to be read as one is worse than no number.
     */
    fun isoNumber(dow: Int): Int = when (dow) {
        Calendar.SUNDAY -> 7
        else -> (dow - 1).coerceIn(1, 7)
    }

    /** What this phone calls the day, short, in its own language. */
    fun local(calendar: Calendar, locale: Locale = Locale.getDefault()): String =
        calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
            ?.uppercase(locale)
            ?: LATIN[(calendar.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]

    /** The label for [calendar]'s day, on a face written in [script]. */
    fun of(calendar: Calendar, script: DigitScript): String = when (script) {
        // The panel writes its day on a rail made of Rome's module, which
        // is the one display here that has letters in it at all.
        DigitScript.ROMAN_COMET -> latin(calendar.get(Calendar.DAY_OF_WEEK))
        // And the display that cannot write a letter says which day it is
        // the only way it can, which is Monday being one.
        DigitScript.YAUTJA -> isoNumber(calendar.get(Calendar.DAY_OF_WEEK)).toString()
        DigitScript.ARABIC -> local(calendar)
    }
}
