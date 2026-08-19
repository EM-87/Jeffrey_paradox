package com.em87.weirdclock

/**
 * What alphabet the sky writes its year in.
 *
 * The solar system can be wound centuries either way, and a row of ordinary
 * digits says nothing about how far you have gone — 1804 and 3211 look
 * equally like today until you read them. So the writing changes with the
 * distance: Roman before the year two thousand, plain digits through this
 * millennium, and something that is not ours at all past three thousand.
 *
 * It is a joke about how far you have wound, and jokes on a clock have to
 * still be legible. Each script keeps the same three groups in the same
 * order — day, month, year — so a year you cannot read is still in the
 * place a year goes.
 */
object OrreryYear {

    enum class Script {
        /** Before 2000: the way the years were written at the time. */
        ROMAN,

        /** This millennium, which needs no comment. */
        DIGITS,

        /**
         * Past 3000. Segmented, like the digits beside it, and not a
         * script anybody can read — which is the point of having wound
         * yourself a thousand years into the future.
         */
        YAUTJA
    }

    /** Which alphabet a given year is written in. */
    fun scriptFor(year: Int): Script = when {
        year < 2000 -> Script.ROMAN
        year < 3000 -> Script.DIGITS
        else -> Script.YAUTJA
    }

    /**
     * The seven segments each digit lights in the far-future alphabet.
     *
     * Ordered a(64) b(32) c(16) d(8) e(4) f(2) g(1), the same as the
     * ordinary digits, so one renderer draws both — which is what makes
     * this feel like the same display showing something else rather than
     * like a picture pasted over it.
     *
     * These are made up, in the manner of the numerals in the films rather
     * than copied from them: short strokes, corners rather than curves,
     * and no two alike at a glance. Said plainly because a comment
     * claiming they were authentic would be the kind of thing nobody could
     * check and everybody would repeat.
     *
     * The one rule they must obey is the one ordinary digits obey: each
     * lights a different set, and none is empty, or a year would read as a
     * gap.
     */
    private val SEGMENTS = intArrayOf(
        0b0011100, // 0 — the low corner
        0b0000110, // 1
        0b1000110, // 2
        0b1001001, // 3
        0b0101001, // 4
        0b1010010, // 5
        0b0110101, // 6
        0b1100001, // 7
        0b1011100, // 8
        0b0111010  // 9
    )

    /** Which segments [digit] lights, or nothing if it is not a digit. */
    fun segmentsOf(digit: Char): Int? =
        if (digit in '0'..'9') SEGMENTS[digit - '0'] else null

    /**
     * How the year part of a date is written, given the script.
     *
     * Only the year changes alphabet. The day and month keep their digits
     * in every script: they are what tells you it is a date at all, and a
     * date with nothing readable in it is a smudge rather than a joke.
     */
    fun yearText(year: Int, script: Script): String = when (script) {
        Script.ROMAN -> Roman.of(year)
        else -> year.toString()
    }
}
