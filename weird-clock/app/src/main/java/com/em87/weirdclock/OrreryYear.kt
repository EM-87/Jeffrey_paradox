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
        /**
         * Before anybody was writing dates down: nothing at all.
         *
         * Not an empty string in a display, which reads as a fault — the
         * row is not drawn. The sky is still there and still turning; it
         * is the date that has not been invented yet.
         */
        NONE,

        /**
         * Before Egypt: wedges pressed into clay, in sixties — see
         * [Cuneiform]. The oldest writing there is, and the reason an hour
         * has sixty minutes in it.
         */
        CUNEIFORM,

        /**
         * Before the year one: the oldest counting there is a picture of.
         * Additive, no nought, a symbol for each power of ten written as
         * many times as it is needed — see [Egyptian].
         */
        EGYPTIAN,

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

    /**
     * The year Mesopotamian numerals give out, going back.
     *
     * Wedges in clay are the oldest writing anybody has, and this is about
     * where they start: the Uruk tablets are a little after it, and the
     * clay tokens they grew out of a little before. Past here there is no
     * writing, and therefore no dates — not an unknown date, no such
     * thing as a date.
     */
    const val WRITING_BEGINS = -3500

    /**
     * And the year the hieroglyphs take over, coming forward.
     *
     * Egyptian numerals and the Egyptian civil calendar are both a little
     * over five thousand years old, which puts the handover here — the
     * user's own guess, and near enough right that there is no reason to
     * argue with it.
     */
    const val EGYPT_BEGINS = -3000

    /**
     * The year a display made of lit bars starts existing.
     *
     * The sixteen-bar module the Roman date is written on is a piece of
     * nineteen-seventies electronics — the first calculator displays are
     * 1970 and 1971 — and a date from 1750 shown on one is an anachronism
     * of exactly the kind everything else on this dial is trying not to
     * be. Before it there is no lit display of any sort to write on, so
     * the date is *printed*: a serif face, the way a date was set on a
     * page for the four hundred years before anybody could light one up.
     *
     * The line falls inside the Roman era rather than at the edge of it,
     * so the two halves of that era look different from each other. That
     * is the point: 1970 is not where the numerals changed, it is where
     * the technology for showing them arrived.
     */
    const val SEGMENTS_FROM = 1970

    /**
     * Whether a date is printed rather than lit.
     *
     * Only the Roman years are ever printed. The hieroglyphs and the
     * wedges are carved and pressed rather than displayed at all, so the
     * question does not arise for them, and every year the seven-bar row
     * and the star are used for is comfortably after 1970.
     */
    fun isPrinted(year: Int, script: Script): Boolean =
        script == Script.ROMAN && year < SEGMENTS_FROM

    /** Which alphabet a given year is written in. */
    fun scriptFor(year: Int): Script = when {
        // Before writing there is no date. Everything else on this dial
        // still means something that far back — the moon has phases and
        // the planets have positions whether or not anybody was counting
        // — but the row of numerals under it is an anachronism, so it
        // goes away rather than being written in the nearest script to
        // hand.
        year < WRITING_BEGINS -> Script.NONE
        year < EGYPT_BEGINS -> Script.CUNEIFORM
        // Roman has no nought and no way to say "before", so the year one
        // is where it gives out — and what is on the other side of it is
        // not an earlier way of writing the same thing, it is an earlier
        // idea of what writing a number is.
        year < 1 -> Script.EGYPTIAN
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
        // Nothing was written down that far back, so nothing is written
        // here either.
        script == Script.NONE -> ""
        script == Script.ROMAN && year >= 1 -> Roman.of(year)
        // Egyptian is drawn from the number rather than spelled out of
        // characters — a tally of signs, not a string — so what comes back
        // here is the number itself, and how far back it is. Years before
        // the year one are counted forwards from it the way anybody
        // counts backwards out loud: 44 before, not minus 44.
        script == Script.EGYPTIAN || script == Script.CUNEIFORM ->
            (1 - year).coerceAtLeast(1).toString()
        else -> year.toString()
    }
}
