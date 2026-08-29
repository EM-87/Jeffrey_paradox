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

    /**
     * The phone's own type, and nothing else.
     *
     * The one idiom here that is not a machine. It reads at a glance from
     * across a room, it can write any of the three alphabets without a
     * table, and it is what somebody who simply wants the time actually
     * wants — which is a good enough reason on a face whose whole premise
     * is that its owner is not looking for a puzzle.
     */
    PLAIN(Prefs.DIGITS_PLAIN),

    /** A card with the number on it, hinged across the middle. */
    CARD(Prefs.DIGITS_CARD),

    /** A drum with the numbers round it, seen through a window. */
    ROLLER(Prefs.DIGITS_ROLLER);

    companion object {
        fun of(key: String?): DigitStyle =
            entries.firstOrNull { it.key == key } ?: SEGMENT

        /**
         * The same, for a script that may not have a choice — see
         * [DigitScript.barsOnly].
         *
         * Resolved here rather than at each of the three places that read
         * the two settings, because a rule kept in three places is a rule
         * that holds in two: the face, the widget and the alarm card would
         * each have to remember it, and the one that forgot would draw a
         * Comet clock in the phone's own type and look like a setting that
         * had not taken.
         */
        fun of(key: String?, script: DigitScript): DigitStyle =
            if (script.barsOnly) SEGMENT else of(key)
    }
}

/**
 * Which numerals, which is a different question from how they are made.
 *
 * A flip card can carry any of the first three on its face and a drum can
 * have any of them round it, so the two axes really are independent — with
 * one honest exception, which is [barsOnly].
 */
enum class DigitScript(val key: String) {

    /** 0 to 9, and the reason a seven-bar display has seven bars. */
    ARABIC(Prefs.SCRIPT_ARABIC),

    /** Theirs — see [Yautja]. A font, so it is drawn as writing. */
    YAUTJA(Prefs.SCRIPT_YAUTJA),

    /**
     * Both of the drawn displays at once — see [CometPanel].
     *
     * This was two entries on this list for a while: Rome's module, which
     * writes letters and draws numbers nobody can read at a glance, and a
     * calculator's nine, which draws a beautiful number and cannot write a
     * letter at all. Neither was a whole clock. They are one panel now,
     * with the time in the calculator's digits and the date in Rome's
     * module on rails above and below it, and each alphabet does the one
     * thing it is good at.
     *
     * The one entry here that names machines rather than an alphabet, and
     * the only one that cannot leave its own displays.
     */
    ROMAN_COMET(Prefs.SCRIPT_ROMAN_COMET);

    /**
     * Whether this script exists as lit bars and nothing else.
     *
     * The other three are alphabets, and a flip card can be printed with
     * any of them. The Comet's numerals are not an alphabet — they are the
     * shape nine pieces of metal make when they are lit, and a card with
     * them printed on it would be a photograph of a display. Asked for on
     * a drum this quietly gives back the display instead, which is the
     * only honest reading of the request.
     */
    val barsOnly: Boolean get() = this == ROMAN_COMET

    companion object {

        /**
         * The script stored under that key, with the two that were merged
         * landing on the one that replaced them.
         *
         * Somebody had Rome's numerals set — the owner of this app did —
         * and somebody had the calculator's. Neither key exists any more,
         * and without this line both of them would fall through to the
         * default and quietly turn a Roman clock into an ordinary one.
         */
        fun of(key: String?): DigitScript = when (key) {
            Prefs.SCRIPT_ROMAN, Prefs.SCRIPT_COMET -> ROMAN_COMET
            else -> entries.firstOrNull { it.key == key } ?: ARABIC
        }
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

    /**
     * A number, written out.
     *
     * [value] is what it says and [of] how far its drum goes round, both
     * for the idioms that turn. [weight] is what one detent of that drum
     * is worth in minutes when somebody is setting a time on it — sixty
     * for an hour's units, six hundred for its tens, one for a minute's —
     * and nought for a number that is not a time and does not turn.
     *
     * Minutes rather than a digit, because that is what makes the carry
     * fall out for free: roll the minute units past fifty-nine and the
     * hour goes up, the way a counter does, without anybody writing a
     * rule about it.
     */
    data class Number(
        val text: String,
        val value: Int,
        val of: Int,
        val weight: Int = 0
    ) : Cell()

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
        cells += group(shown, highest, padded = options.leadingZero, unit = 60)
        cells += Cell.Colon
        cells += group(minute, 59, padded = true, unit = 1)
        if (options.seconds) {
            cells += Cell.Colon
            // Seconds have no weight: nobody sets an alarm for twenty past
            // seven and eleven seconds, and a drum that turns under a
            // finger had better be a drum worth turning.
            cells += group(second, 59, padded = true, unit = 0)
        }
        // The token goes on the end, where AM and PM go, and only when
        // there is a question for it to answer.
        if (!options.hour24) cells += Cell.Token(sun = hour >= 12)
        return cells
    }

    /**
     * The cells for a date, in the order this phone writes them.
     *
     * The day and the month, and not the year. A clock says what a clock
     * is asked: what time is it, and — because the answer is often "too
     * late for that" — what day. Nobody has ever looked at a clock to
     * find out what year it is, and four digits that never change are
     * four digits of the row's width spent on the one number the reader
     * already knows. It cost the rest of the line a third of its size to
     * say it.
     *
     * The panel is the exception and keeps its year, on a rail of its
     * own: there it is not competing with the date for a row, it is the
     * bottom edge of a drawn instrument — see [CometPanel.rails].
     */
    fun date(day: Int, month: Int, dayFirst: Boolean, options: Options): List<Cell> {
        val cells = ArrayList<Cell>(5)
        cells += group(
            if (dayFirst) day else month, if (dayFirst) 31 else 12,
            padded = true
        )
        cells += Cell.Slash
        cells += group(
            if (dayFirst) month else day, if (dayFirst) 12 else 31,
            padded = true
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
     * Always two, now that every script on this face writes its numbers
     * with our ten digits — a drum can turn under each one, and
     * [Cell.Number.of] says how far round that drum goes, so the tens of
     * an hour stop at two rather than pretending there is a 94 o'clock.
     *
     * There used to be an exception. Rome's numerals were the *time* on
     * this face, and the second digit of XLVII is not a thing, so the
     * whole group was one uncuttable cell. Rome writes the date now and
     * the exception went with it.
     */
    private fun group(
        value: Int,
        highest: Int,
        padded: Boolean,
        unit: Int = 0
    ): List<Cell> {
        val tens = value / 10
        val units = value % 10
        if (!padded && tens == 0) return listOf(Cell.Number("$units", units, of = 9, weight = unit))
        return listOf(
            Cell.Number("$tens", tens, of = highest / 10, weight = unit * 10),
            Cell.Number("$units", units, of = 9, weight = unit)
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
