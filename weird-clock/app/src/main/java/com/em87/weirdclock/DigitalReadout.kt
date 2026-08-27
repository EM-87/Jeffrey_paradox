package com.em87.weirdclock

/**
 * How the digits are made: the idiom, not the alphabet.
 *
 * Three ways a clock has ever shown a number without hands. Lit bars is
 * the one everybody pictures; the other two are older and mechanical, and
 * both of them move — which is the whole reason they are here, because a
 * number that changes by being replaced tells you nothing and a number
 * that changes by turning tells you it is a machine.
 */
enum class DigitStyle(val key: String) {

    /** Bars behind a mask, lit and unlit. */
    SEGMENT(Prefs.DIGITS_SEGMENT),

    /** A card with the number on it, hinged across the middle. */
    CARD(Prefs.DIGITS_CARD),

    /** A drum with the numbers round it, seen through a window. */
    ROLLER(Prefs.DIGITS_ROLLER);

    companion object {
        fun of(key: String?): DigitStyle =
            entries.firstOrNull { it.key == key } ?: SEGMENT
    }
}

/**
 * Which numerals, which is a different question from how they are made.
 *
 * A flip card can carry any of the three on its face and a drum can have
 * any of them round it, so the two axes really are independent — with one
 * honest exception, noted on [SEGMENT_SCRIPTS]: bars can draw the shapes
 * they were built to draw and no others.
 */
enum class DigitScript(val key: String) {

    /** 0 to 9, and the reason a seven-bar display has seven bars. */
    ARABIC(Prefs.SCRIPT_ARABIC),

    /** I to M, on the sixteen-bar module that exists to draw letters. */
    ROMAN(Prefs.SCRIPT_ROMAN),

    /** Theirs — see [Yautja]. A font, so it is drawn as writing. */
    YAUTJA(Prefs.SCRIPT_YAUTJA);

    companion object {
        fun of(key: String?): DigitScript =
            entries.firstOrNull { it.key == key } ?: ARABIC
    }
}

/**
 * One place on a digital readout.
 *
 * A cell rather than a character because the two are not the same thing in
 * every script. In Arabic a cell holds one digit, and a drum with ten
 * numbers round it can turn under it; in Roman a cell holds a whole
 * number, because there is no such thing as the second digit of XLVII and
 * nothing sane to put round a drum. Anything that lays these out or
 * animates them has to know which it is looking at, and the shape of this
 * type is where it finds out.
 */
sealed class Cell {

    /** A number, written out. [value] is what it says, for the drums. */
    data class Number(val text: String, val value: Int, val of: Int) : Cell()

    /** The dots between two groups. */
    data object Colon : Cell()

    /** The stroke between two parts of a date. */
    data object Slash : Cell()

    /**
     * What a twelve-hour clock puts where AM and PM go.
     *
     * The sun after noon and the moon before it. Not the sky outside the
     * window — [DayNight] knows that and it is the wrong answer here,
     * because a token that follows the real sunrise says "moon" at both
     * four in the morning and eight at night and stops disambiguating the
     * one thing a twelve-hour clock cannot say for itself. These name
     * their half of the day by what stands over the middle of it, which is
     * exactly what ante and post meridiem mean, and they are true at the
     * two moments that matter most: a moon at midnight and a sun at noon.
     */
    data class Token(val sun: Boolean) : Cell()
}

/**
 * What the digital face is showing, worked out before anything is drawn.
 *
 * Pure, and the reason is that nearly every complaint anybody has about a
 * digital clock is arithmetic: midnight showing as 0 on a twelve-hour
 * face, the leading nought going missing from the seconds and not the
 * minutes, a Roman clock with a gap where a nought should be. None of
 * that is a drawing problem and none of it should need a screen to check.
 */
object DigitalReadout {

    /** The settings that change what the readout says. */
    data class Options(
        val script: DigitScript = DigitScript.ARABIC,
        val hour24: Boolean = true,
        val leadingZero: Boolean = true,
        val seconds: Boolean = false
    )

    /**
     * The cells for a time of day.
     *
     * [hour] is the twenty-four hour one, whatever the face shows.
     */
    fun time(hour: Int, minute: Int, second: Int, options: Options): List<Cell> {
        val shown = if (options.hour24) hour else twelveOf(hour)
        val highest = if (options.hour24) 23 else 12
        val cells = ArrayList<Cell>(9)
        // The nought in front is a question about the hour and nothing
        // else. Nobody writes eight minutes past as 7:8, so the minutes
        // and the seconds are two digits whatever the switch says.
        cells += group(shown, highest, padded = options.leadingZero, options.script)
        cells += Cell.Colon
        cells += group(minute, 59, padded = true, options.script)
        if (options.seconds) {
            cells += Cell.Colon
            cells += group(second, 59, padded = true, options.script)
        }
        // The token goes on the end, where AM and PM go, and only when
        // there is a question for it to answer.
        if (!options.hour24) cells += Cell.Token(sun = hour >= 12)
        return cells
    }

    /**
     * The cells for a date, in the order this phone writes them.
     *
     * The year is one cell however long it is: it is the one number on a
     * clock that is not a position on a wheel, and splitting it into four
     * would offer to roll the millennium.
     */
    fun date(day: Int, month: Int, year: Int, dayFirst: Boolean, options: Options): List<Cell> {
        val cells = ArrayList<Cell>(8)
        cells += group(
            if (dayFirst) day else month, if (dayFirst) 31 else 12,
            padded = true, options.script
        )
        cells += Cell.Slash
        cells += group(
            if (dayFirst) month else day, if (dayFirst) 12 else 31,
            padded = true, options.script
        )
        cells += Cell.Slash
        cells += Cell.Number(
            if (options.script == DigitScript.ROMAN) roman(year) else "$year",
            year, of = 0
        )
        return cells
    }

    /**
     * Twelve rather than nought, and one to twelve rather than thirteen to
     * twenty-three. Midnight is XII on a Roman face and 12 on ours, which
     * is what every twelve-hour clock ever made has done and what writing
     * the hour out arithmetically does not do.
     */
    fun twelveOf(hour: Int): Int = ((hour + 11) % 12) + 1

    /**
     * One number, as the cells it takes up.
     *
     * Arabic and Yautja split into two — a drum can turn under each one,
     * and [Cell.Number.of] says how far round that drum goes, so the tens
     * of an hour stop at two rather than pretending there is a 94 o'clock.
     * Roman does not split: the second digit of XLVII is not a thing, and
     * neither is a drum with XLVII round it.
     */
    private fun group(
        value: Int,
        highest: Int,
        padded: Boolean,
        script: DigitScript
    ): List<Cell> {
        if (script == DigitScript.ROMAN) return listOf(Cell.Number(roman(value), value, highest))
        val tens = value / 10
        val units = value % 10
        if (!padded && tens == 0) return listOf(Cell.Number("$units", units, of = 9))
        return listOf(
            Cell.Number("$tens", tens, of = highest / 10),
            Cell.Number("$units", units, of = 9)
        )
    }

    /**
     * Rome's numerals, with a nought.
     *
     * [Roman] itself hands back an empty string for zero, which is right:
     * there is no Roman numeral for nothing. A clock has to write midnight
     * and "and no minutes" all the same, so it borrows the letter Rome's
     * own arithmeticians used in that column — N, for *nulla*.
     */
    fun roman(value: Int): String = if (value == 0) "N" else Roman.of(value)
}
