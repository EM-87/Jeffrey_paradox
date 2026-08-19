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
         * Past 3000. Marks on a star rather than bars in a rectangle, and
         * not a script anybody can read — which is the point of having
         * wound yourself a thousand years into the future.
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
