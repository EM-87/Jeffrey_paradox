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
     * The day and the month change with it — see
     * [ClockView.orreryDateDigits]. This is only the year part, kept
     * separate because it is the part that decides which alphabet the
     * whole row is in.
     */
    fun yearText(year: Int, script: Script): String = when {
        // Roman has no nought and no way to say "before". Wound back past
        // the year one the sky drops to digits for the year rather than
        // writing an empty group, which is what Roman.of(0) is.
        script == Script.ROMAN && year >= 1 -> Roman.of(year)
        else -> year.toString()
    }
}
