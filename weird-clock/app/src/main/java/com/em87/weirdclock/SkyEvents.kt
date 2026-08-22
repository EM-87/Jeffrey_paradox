package com.em87.weirdclock

/**
 * The things worth looking up for, by date.
 *
 * Two kinds, and the difference matters. Most of what is here is *worked
 * out* — a full moon, a new moon, Mars at opposition — from the same
 * arithmetic that turns the orrery, so it is right for any date the dial
 * can be wound to, forwards or back, without anybody keeping a table up to
 * date. The rest is *remembered*: eclipses and meteor showers cannot be had
 * from mean circular orbits, so they are written down.
 *
 * The written-down half is short and deliberately so. A long table of dates
 * nobody has checked is worse than a short one: it looks the same and it is
 * wrong. What is here are the meteor showers, whose peaks land on the same
 * few days every year, and the eclipses of the next few years — each of
 * which [SkyEventsTest] checks against the orbits, because an eclipse that
 * does not fall on a new or full moon is not an eclipse, it is a typo.
 *
 * There are no comets. There is no bright one predicted in the years this
 * table covers, and inventing one to fill the row would be the exact
 * failure the paragraph above is about. The kind exists so a date can be
 * added the week one is announced.
 */
object SkyEvents {

    enum class Kind {
        SOLAR_ECLIPSE, LUNAR_ECLIPSE, METEORS, COMET, FULL_MOON, NEW_MOON, OPPOSITION
    }

    /** How much of it is covered, for the eclipses. */
    enum class Grade { TOTAL, ANNULAR, PARTIAL }

    /** The showers that come round every year, with the night they peak. */
    enum class Shower(val month: Int, val day: Int) {
        QUADRANTIDS(1, 3),
        LYRIDS(4, 22),
        ETA_AQUARIIDS(5, 6),
        PERSEIDS(8, 12),
        ORIONIDS(10, 21),
        LEONIDS(11, 17),
        GEMINIDS(12, 14),
        URSIDS(12, 22)
    }

    /**
     * One thing happening on one day. Everything but [kind] is optional
     * because the kinds do not carry the same freight: a shower has a name,
     * an eclipse has a grade, an opposition has a planet, and a full moon
     * has only itself.
     */
    data class Event(
        val kind: Kind,
        val day: Int,
        val shower: Shower? = null,
        val grade: Grade? = null,
        val body: Orrery.Body? = null
    )

    /**
     * The eclipses, as year, month, day.
     *
     * Only the ones that can be seen without instruments: penumbral lunar
     * eclipses are left out, because telling somebody to go outside for one
     * is telling them to go and look at a full moon.
     */
    private data class Eclipse(
        val year: Int, val month: Int, val day: Int,
        val kind: Kind, val grade: Grade
    )

    private val eclipses = listOf(
        Eclipse(2026, 2, 17, Kind.SOLAR_ECLIPSE, Grade.ANNULAR),
        Eclipse(2026, 3, 3, Kind.LUNAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2026, 8, 12, Kind.SOLAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2026, 8, 28, Kind.LUNAR_ECLIPSE, Grade.PARTIAL),
        Eclipse(2027, 2, 6, Kind.SOLAR_ECLIPSE, Grade.ANNULAR),
        Eclipse(2027, 8, 2, Kind.SOLAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2028, 1, 12, Kind.LUNAR_ECLIPSE, Grade.PARTIAL),
        Eclipse(2028, 1, 26, Kind.SOLAR_ECLIPSE, Grade.ANNULAR),
        Eclipse(2028, 7, 6, Kind.LUNAR_ECLIPSE, Grade.PARTIAL),
        Eclipse(2028, 7, 22, Kind.SOLAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2028, 12, 31, Kind.LUNAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2029, 6, 26, Kind.LUNAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2029, 12, 20, Kind.LUNAR_ECLIPSE, Grade.TOTAL),
        Eclipse(2030, 6, 1, Kind.SOLAR_ECLIPSE, Grade.ANNULAR),
        Eclipse(2030, 6, 15, Kind.LUNAR_ECLIPSE, Grade.PARTIAL),
        Eclipse(2030, 11, 25, Kind.SOLAR_ECLIPSE, Grade.TOTAL)
    )

    /** Every eclipse in the table, as days — for the tests, and for search. */
    internal fun eclipseDays(): List<Pair<Int, Kind>> =
        eclipses.map { CivilDays.epochDay(it.year, it.month, it.day) to it.kind }

    /** The planets whose opposition is worth being told about. */
    private val opposable = listOf(Orrery.Body.MARS, Orrery.Body.JUPITER, Orrery.Body.SATURN)

    /**
     * Everything happening on one day, in the order it should be read: what
     * is rare first.
     *
     * Days are counted in UTC, so a shower that peaks after midnight
     * somewhere may be listed on the day before it there. Naming an
     * eclipse a day out would matter; naming the Perseids a day out does
     * not, they run for a fortnight.
     */
    fun on(day: Int): List<Event> {
        val found = mutableListOf<Event>()

        for (e in eclipses) {
            if (CivilDays.epochDay(e.year, e.month, e.day) == day) {
                found.add(Event(e.kind, day, grade = e.grade))
            }
        }

        val (_, month, dayOfMonth) = CivilDays.dateOf(day)
        for (s in Shower.entries) {
            if (s.month == month && s.day == dayOfMonth) {
                found.add(Event(Kind.METEORS, day, shower = s))
            }
        }

        for (body in opposable) {
            if (crossesOpposition(body, day)) {
                found.add(Event(Kind.OPPOSITION, day, body = body))
            }
        }

        // A moon named beside an eclipse says nothing new — an eclipse *is*
        // a syzygy, and "total eclipse, full moon" reads as two nights out
        // to somebody scanning a line of dates.
        //
        // Beside, not on: the eclipse dates are observed ones and the moons
        // are worked out from mean orbits, which are good to about a day, so
        // the two disagree about which side of midnight the moment falls on
        // roughly half the time. When they do, the observed date is the one
        // to keep — it is the one somebody would go outside for.
        if (!eclipsedNear(day)) {
            when (moonCrossing(day)) {
                Kind.FULL_MOON -> found.add(Event(Kind.FULL_MOON, day))
                Kind.NEW_MOON -> found.add(Event(Kind.NEW_MOON, day))
                else -> Unit
            }
        }

        return found
    }

    /** Whether anything at all is happening — cheap enough to ask per day. */
    fun anythingOn(day: Int): Boolean = on(day).isNotEmpty()

    /**
     * The next day after [afterDay] with anything on it, or null.
     *
     * Walked a day at a time rather than solved, because the four kinds of
     * event are found four different ways — a table lookup, a fixed date in
     * the year, a Kepler solve and a phase crossing — and the first day on
     * which *any* of them fires has no closed form. The walk is short: the
     * Moon is new or full every fortnight, so nothing is ever more than
     * about a fortnight away, and the limit is there for the case where the
     * arithmetic has been wound somewhere it stops working rather than
     * because a long search is expected.
     */
    fun nextDay(afterDay: Int, limitDays: Int = 400): Int? {
        for (ahead in 1..limitDays) {
            val day = afterDay + ahead
            if (anythingOn(day)) return day
        }
        return null
    }

    /**
     * What to call a day's events on a calendar, when there is only room
     * for one mark.
     *
     * The rarest thing wins, and [on] already returns them rarest first: an
     * eclipse on the night of a meteor shower is an eclipse, and a day is
     * not going to be remembered for the shower.
     */
    fun headline(day: Int): Event? = on(day).firstOrNull()

    /** Whether an eclipse falls on this day or either side of it. */
    private fun eclipsedNear(day: Int): Boolean = eclipses.any {
        kotlin.math.abs(CivilDays.epochDay(it.year, it.month, it.day) - day) <= 1
    }

    /**
     * Whether the Moon passes new or full during this day.
     *
     * By watching the phase cross a boundary between one midnight and the
     * next rather than by asking how close to full it is at noon: the phase
     * moves about twelve degrees a day, so "close enough at noon" would
     * name two days running as full and one month in six as neither.
     */
    private fun moonCrossing(day: Int): Kind? {
        val start = Orrery.moonPhase(day * CivilDays.DAY_MS)
        val end = Orrery.moonPhase((day + 1) * CivilDays.DAY_MS)
        if (end < start) return Kind.NEW_MOON      // wrapped past 1 back to 0
        if (start < 0.5 && end >= 0.5) return Kind.FULL_MOON
        return null
    }

    /**
     * Whether a planet comes to opposition during this day: the moment the
     * Earth passes between it and the Sun, when it is up all night and at
     * its brightest.
     */
    private fun crossesOpposition(body: Orrery.Body, day: Int): Boolean {
        val before = Orrery.shortWay(
            Orrery.longitude(Orrery.Body.EARTH, day * CivilDays.DAY_MS),
            Orrery.longitude(body, day * CivilDays.DAY_MS)
        )
        val after = Orrery.shortWay(
            Orrery.longitude(Orrery.Body.EARTH, (day + 1) * CivilDays.DAY_MS),
            Orrery.longitude(body, (day + 1) * CivilDays.DAY_MS)
        )
        // The Earth is the faster of the two and always gaining, so the gap
        // only ever runs downhill: positive today and not positive tomorrow
        // is the moment it passes through zero.
        //
        // Which is also why nothing extra is needed to keep conjunction out
        // — the day the planet is behind the Sun and cannot be seen at all.
        // There the gap runs down through half a turn, and measured the
        // short way round that reads as -179 becoming +179: uphill, and not
        // this.
        return before > 0.0 && after <= 0.0
    }
}
