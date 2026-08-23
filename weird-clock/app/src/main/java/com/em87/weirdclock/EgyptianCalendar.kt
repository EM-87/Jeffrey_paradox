package com.em87.weirdclock

/**
 * The Egyptian civil calendar, and the dates written in it.
 *
 * The hieroglyph date this dial used to draw was our own date with our own
 * numbers carved into it: day, month, year, in the Gregorian arrangement,
 * with the year counted back from a Christian epoch nobody in Egypt had
 * heard of. That is a transliteration, not a date, and the user was right
 * to call it out.
 *
 * A real one reads: *regnal year N, month M of season S, day D*, under the
 * king whose year it is. Four facts, and every one of them is different
 * from ours.
 *
 * **The year has 365 days and no leap day.** Twelve months of thirty days
 * in three seasons of four months, and five days over at the end — the
 * "days upon the year", which belonged to no month and on which five gods
 * were born. Because there is no leap day the civil year slips against the
 * sun by a day every four years, so 1 Thoth walks backwards through the
 * seasons and comes home after 1,460 years. Nobody fixed it for three
 * thousand years, which is not an oversight: a calendar that never changes
 * length is a calendar you can do arithmetic in, and Egyptian astronomers
 * kept using it long after Egypt stopped existing. Ptolemy computed in it.
 * Copernicus computed in it.
 *
 * **The seasons are the Nile's, not the sun's.** Akhet is the inundation,
 * Peret the coming-forth of the land from under the water, Shemu the
 * harvest. They were named when the calendar was young and the drift had
 * not started; a thousand years later Akhet fell in the spring and the
 * names went on meaning what they had always meant, which is its own small
 * comedy and is drawn here as it happened.
 *
 * **The year count belongs to the king.** There is no continuous era.
 * Year 30 is the thirtieth year of whoever is on the throne, and the count
 * starts again at 1 when he dies. So a date without a king is not a date,
 * and this one carries his name.
 *
 * The anchor is the Era of Nabonassar — 1 Thoth of year 1 falls on 26
 * February 747 BC in the Julian calendar — which is the standard peg for
 * this calendar and is the one Ptolemy's own tables are reckoned from. The
 * arithmetic from there is exact, because a 365-day year with no
 * exceptions is the easiest calendar there has ever been.
 *
 * What is *not* exact is the king list. Egyptian chronology is a
 * reconstruction: the New Kingdom is good to about a decade, the Middle
 * Kingdom to a few decades, and the Old Kingdom and before to a century or
 * worse. The dates here are the conventional ones and should be read as
 * "about". The alternative was no king at all, and a regnal year with
 * nobody's name on it is not a date either.
 */
object EgyptianCalendar {

    /**
     * The three seasons of the Nile: the flood, the land coming out from
     * under it, and the harvest.
     */
    enum class Season { AKHET, PERET, SHEMU }

    /**
     * One date, as a scribe would have set it down.
     *
     * [epagomenal] marks the five days at the end of the year that belong
     * to no month and no season. They are not a mistake or a remainder:
     * they are the birthdays of Osiris, Horus, Set, Isis and Nephthys, and
     * a date falling on one says so instead of naming a month.
     */
    data class Date(
        val regnalYear: Int,
        val king: King?,
        val season: Season?,
        val monthOfSeason: Int,
        val day: Int,
        val epagomenal: Boolean
    )

    /** A king, and the year he came to the throne. */
    data class King(val name: String, val from: Int)

    /** Days in the civil year: twelve thirties and five over. */
    const val YEAR_DAYS = 365

    /** Months in the year, and days in each of them. */
    const val MONTHS = 12
    const val MONTH_DAYS = 30

    /** The days upon the year, which belong to no month. */
    const val EPAGOMENAL = 5

    /**
     * 1 Thoth of year 1 of Nabonassar, as a Julian Day Number.
     *
     * 26 February 747 BC in the Julian calendar. The standard anchor for
     * this calendar and the one Ptolemy reckoned from, which is why it is
     * used here rather than a date closer to the dynasties: it is the peg
     * every published table of Egyptian dates is hung on, so a date this
     * produces can be checked against one.
     */
    const val NABONASSAR_JDN = 1_448_638

    /** The Julian Day Number of 1 January 1970, to get from one to the other. */
    private const val UNIX_EPOCH_JDN = 2_440_588

    /**
     * The kings, and the years they came to the throne — negative for BC.
     *
     * Conventional dates, in the middle of the range the Egyptologists
     * argue over, and they should be read as "about": a decade either way
     * in the New Kingdom, a century or more in the Old. The list is a
     * ladder rather than a census — co-regents and the two Intermediate
     * Periods, where several kings ruled at once in different cities and
     * nobody can put them in one line, are left out, and a year in one of
     * those gaps is counted from the last king before it. That is wrong,
     * and it is wrong in a way that is stated rather than hidden: the
     * alternative is a date with no name on it, and an Egyptian date with
     * no king in it is not a date.
     */
    val kings: List<King> = listOf(
        // Early Dynastic — the first kings of a united Egypt, and the
        // shakiest dates in the list.
        King("Narmer", -3100),
        King("Aha", -3080),
        King("Djer", -3050),
        King("Den", -2970),
        King("Khasekhemwy", -2720),
        // Old Kingdom: the pyramid builders.
        King("Djoser", -2670),
        King("Sneferu", -2613),
        King("Khufu", -2589),
        King("Khafre", -2558),
        King("Menkaure", -2532),
        King("Userkaf", -2494),
        King("Sahure", -2487),
        King("Nyuserre", -2445),
        King("Unas", -2375),
        King("Teti", -2345),
        King("Pepi I", -2321),
        King("Pepi II", -2278),
        // Middle Kingdom.
        King("Mentuhotep II", -2055),
        King("Amenemhat I", -1985),
        King("Senusret I", -1956),
        King("Amenemhat II", -1911),
        King("Senusret II", -1877),
        King("Senusret III", -1870),
        King("Amenemhat III", -1831),
        King("Amenemhat IV", -1786),
        King("Sobekneferu", -1777),
        // New Kingdom. Hatshepsut is left out on purpose: she and Thutmose
        // III were on the throne together and the regnal years were
        // counted by his reign throughout, so a list that switched to her
        // would restart a count that never restarted.
        King("Ahmose I", -1550),
        King("Amenhotep I", -1525),
        King("Thutmose I", -1504),
        King("Thutmose II", -1492),
        King("Thutmose III", -1479),
        King("Amenhotep II", -1427),
        King("Thutmose IV", -1400),
        King("Amenhotep III", -1390),
        King("Akhenaten", -1352),
        King("Tutankhamun", -1336),
        King("Ay", -1327),
        King("Horemheb", -1323),
        King("Ramesses I", -1295),
        King("Seti I", -1294),
        King("Ramesses II", -1279),
        King("Merenptah", -1213),
        King("Seti II", -1200),
        King("Ramesses III", -1184),
        King("Ramesses IV", -1153),
        King("Ramesses IX", -1126),
        King("Ramesses XI", -1099),
        // Third Intermediate Period and the Kushite kings.
        King("Smendes", -1069),
        King("Psusennes I", -1039),
        King("Shoshenq I", -945),
        King("Osorkon II", -874),
        King("Piye", -747),
        King("Shabaka", -716),
        King("Taharqa", -690),
        // Late Period.
        King("Psamtik I", -664),
        King("Necho II", -610),
        King("Psamtik II", -595),
        King("Apries", -589),
        King("Amasis", -570),
        King("Cambyses", -525),
        King("Darius I", -522),
        King("Nectanebo I", -380),
        King("Nectanebo II", -360),
        // Macedonian and Ptolemaic. The calendar did not change with the
        // dynasty; Ptolemy III tried to add a leap day in 238 BC and the
        // priests ignored him, which is why it still had not one two
        // hundred years later.
        King("Alexander", -332),
        King("Ptolemy I", -305),
        King("Ptolemy II", -282),
        King("Ptolemy III", -246),
        King("Ptolemy IV", -221),
        King("Ptolemy V", -204),
        King("Ptolemy VI", -180),
        King("Ptolemy VIII", -145),
        King("Ptolemy XII", -80),
        King("Cleopatra VII", -51),
        // And Rome, which finally did fix the leap day: from 25 BC Egypt
        // kept an "Alexandrian" year with one, and the old wandering year
        // went on beside it in the astronomers' tables.
        King("Augustus", -30)
    )

    /** Whose year it is, or null before the first king in the list. */
    fun kingIn(year: Int): King? = kings.lastOrNull { it.from <= year }

    /**
     * The date, from an instant and the zone it is being read in.
     *
     * The whole conversion is one division, because a calendar of 365 days
     * with no exceptions has nothing else in it. The awkward part is the
     * signs: every date this is asked for is thousands of years before the
     * epoch, so the day count is negative and an ordinary division would
     * round the wrong way at every year boundary — [Math.floorDiv] and
     * [Math.floorMod] rather than `/` and `%`.
     */
    fun dateOf(atMs: Long, zoneOffsetMs: Int, civilYear: Int): Date {
        val jdn = CivilDays.dayOf(atMs, zoneOffsetMs) + UNIX_EPOCH_JDN
        val since = (jdn - NABONASSAR_JDN).toLong()
        val dayOfYear = Math.floorMod(since, YEAR_DAYS.toLong()).toInt()
        val king = kingIn(civilYear)
        // Counted from the year of accession, inclusive: the year a king
        // takes the throne is his year 1. Egypt did it both ways at
        // different periods — some reigns counted from the accession day
        // and some restarted the count at the next new year — and one rule
        // stated is better than two guessed at.
        val regnal = if (king == null) 0 else civilYear - king.from + 1
        if (dayOfYear >= MONTHS * MONTH_DAYS) {
            return Date(
                regnalYear = regnal,
                king = king,
                season = null,
                monthOfSeason = 0,
                day = dayOfYear - MONTHS * MONTH_DAYS + 1,
                epagomenal = true
            )
        }
        val month = dayOfYear / MONTH_DAYS
        return Date(
            regnalYear = regnal,
            king = king,
            season = Season.entries[month / 4],
            monthOfSeason = month % 4 + 1,
            day = dayOfYear % MONTH_DAYS + 1,
            epagomenal = false
        )
    }

    /**
     * The year the rising of Sothis fell on 1 Thoth.
     *
     * AD 139, and it is the one hard peg this whole calendar has. Censorinus
     * wrote in 238 that the two had coincided a hundred years earlier, and
     * because the civil year loses a day every four years against the star,
     * that one coincidence fixes where the rising falls in the civil year
     * for every other year there has ever been.
     */
    private const val SOTHIC_YEAR = 139

    /**
     * Which day of the civil year the star Sothis rises on, in a given
     * year — 0 being 1 Thoth.
     *
     * Sopdet to the Egyptians, Sirius to us: the brightest star in the
     * sky, which spends seventy days each year too near the sun to be
     * seen and then comes back, rising in the east a few minutes before
     * dawn. That morning was the Egyptian new year — *wepet renpet*, the
     * opening of the year — and it arrived within days of the Nile's
     * flood, which is the whole reason anybody was watching.
     *
     * It is the one astronomical event Egypt genuinely wrote down, over
     * and over, for three thousand years. There is no certain Egyptian
     * record of a solar eclipse at all, which is a strange and famous
     * hole in the record of a civilisation that watched the sky as
     * carefully as this one did.
     *
     * The arithmetic is the wandering year run backwards from Censorinus:
     * the civil calendar loses a day against the star every four years, so
     * the rising walks forward through the civil year at exactly that
     * rate, and comes home after 1,460 of them.
     */
    fun sothicDayOfYear(civilYear: Int): Int =
        Math.floorMod(Math.floorDiv(SOTHIC_YEAR - civilYear, 4), YEAR_DAYS)

    /** Which day of the civil year a date falls on, 0 being 1 Thoth. */
    fun dayOfYear(date: Date): Int = if (date.epagomenal) {
        MONTHS * MONTH_DAYS + date.day - 1
    } else {
        (
            (date.season?.ordinal ?: 0) * 4 + date.monthOfSeason - 1
            ) * MONTH_DAYS + date.day - 1
    }

    /** Whether Sothis rises on this date, opening the year. */
    fun isSothicRising(date: Date, civilYear: Int): Boolean =
        dayOfYear(date) == sothicDayOfYear(civilYear)

    /**
     * How far the civil year has slipped against the sun, in days.
     *
     * The whole point of the wandering year, made into a number: zero when
     * the calendar was set up and one more every four years, coming home
     * after 1,460 of them. Not drawn anywhere — it is here because it is
     * the one thing about this calendar that can be checked against the
     * arithmetic rather than against a book, and a test that watches it go
     * round is a test that the year really is 365 days long and really has
     * no leap day in it.
     */
    fun driftDays(atMs: Long, zoneOffsetMs: Int): Double {
        val since = (CivilDays.dayOf(atMs, zoneOffsetMs) + UNIX_EPOCH_JDN - NABONASSAR_JDN).toDouble()
        // A tropical year against a civil one of exactly 365.
        val years = since / 365.0
        return years * 0.2422
    }
}
