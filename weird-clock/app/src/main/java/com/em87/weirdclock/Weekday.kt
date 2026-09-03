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

    /**
     * How many characters a *narrow* weekday name may honestly be.
     *
     * One in most languages and two in a few — Chinese writes 週一, and
     * cutting that to 週 turns seven different days into one character
     * repeated seven times.
     */
    const val NARROW = 2

    /**
     * One day's initial for a strip of seven, whatever the calendar hands
     * back.
     *
     * The narrow name is asked for with a five-letter pattern, and on a
     * phone that is what it gives. It is not a promise: the pattern is
     * only defined up to four letters, so an implementation is free to
     * return the whole word — and one does. Seven whole words where seven
     * initials were expected is not a strip, it is a smear, and it has
     * been in every photograph of an alarm this app has ever taken.
     *
     * The same fault was found and fixed on the month page a version ago
     * by measuring the name against the column it had to fit in. There is
     * no column here — the strip is a run of text in a row of a list — so
     * the rule is the length instead: anything longer than a narrow name
     * could honestly be is cut to its first character.
     */
    fun narrow(name: String): String {
        if (name.isEmpty()) return name
        if (name.codePointCount(0, name.length) <= NARROW) return name
        return name.substring(0, name.offsetByCodePoints(0, 1))
    }

    /**
     * The label for [calendar]'s day, on a face written in [script].
     *
     * [lit] is whether the face is made of bars rather than of type, and
     * it decides the whole question. Not one of the four displays this
     * clock draws can spell a day of the week: seven bars and the Sharp's
     * nine and the eighteen-arm stars are ten digits each with no letters
     * at all, and Rome's module has exactly I V X L C D M and a dot. So a
     * lit face says the day the only way a display can, which is Monday
     * being one — and that is not a compromise invented here, it is what
     * the alien face has done since it arrived, for the same reason.
     *
     * Printed on a card or a drum, it is type beside type, and type can
     * say Thursday.
     */
    fun of(calendar: Calendar, script: DigitScript, lit: Boolean = false): String = when {
        lit -> isoNumber(calendar.get(Calendar.DAY_OF_WEEK)).toString()
        // The panel writes its day in Latin: its numbers are printed, and
        // Rome is what this script is being.
        script == DigitScript.ROMAN_COMET -> latin(calendar.get(Calendar.DAY_OF_WEEK))
        else -> local(calendar)
    }
}
