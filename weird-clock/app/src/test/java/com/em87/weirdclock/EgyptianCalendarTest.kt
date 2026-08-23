package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A date written the way Egypt wrote them, and not ours in fancy dress.
 *
 * The old hieroglyph date was our own date carved: day, month, year, in the
 * Gregorian arrangement, with the year counted back from an epoch nobody in
 * Egypt had heard of. Every claim in this file is a way of asking whether
 * what replaced it is a different *calendar* rather than a different
 * typeface — a year of 365 days that never has a leap day, three seasons
 * named for the Nile, five days at the end that belong to no month, and a
 * year count that starts again with every king.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class EgyptianCalendarTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    /** An instant, given in UTC so the test does not depend on a zone. */
    private fun at(year: Int, month: Int, day: Int): Long {
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        if (year < 1) {
            cal.set(java.util.Calendar.ERA, java.util.GregorianCalendar.BC)
            cal.set(1 - year, month - 1, day, 12, 0)
        } else {
            cal.set(year, month - 1, day, 12, 0)
        }
        return cal.timeInMillis
    }

    private fun dateOf(year: Int, month: Int, day: Int): EgyptianCalendar.Date =
        EgyptianCalendar.dateOf(at(year, month, day), 0, year)

    // ------------------------------------------------------------ the anchor

    /**
     * The peg the whole calendar hangs on: 1 Thoth of year 1 of
     * Nabonassar, 26 February 747 BC.
     *
     * Checked against the Julian Day Number rather than against the
     * arithmetic that uses it, because the arithmetic is one division and
     * the only thing that can be wrong with it is the number it divides
     * from. Every published table of Egyptian dates is hung on this peg,
     * which is why it is this one.
     */
    @Test
    fun `the era of nabonassar falls where the tables put it`() {
        // 26 February 747 BC, Julian — which is what a Calendar gives for a
        // date that far back, since it keeps the Julian reckoning before
        // 1582.
        val jdn = CivilDays.dayOf(at(-746, 2, 26), 0) + 2_440_588
        assertEquals(
            "the anchor is not 26 February 747 BC",
            EgyptianCalendar.NABONASSAR_JDN, jdn
        )
        val date = EgyptianCalendar.dateOf(at(-746, 2, 26), 0, -746)
        assertEquals("the anchor is not 1 Thoth", 1, date.day)
        assertEquals(EgyptianCalendar.Season.AKHET, date.season)
        assertEquals("nor month one of it", 1, date.monthOfSeason)
    }

    // -------------------------------------------------------- the year itself

    /**
     * Three hundred and sixty-five days, every year, with no exceptions.
     *
     * This is the whole of what makes it a different calendar and not a
     * relabelling of ours: walked a day at a time, the date comes round
     * after exactly 365 days whatever year it is in, including the ones
     * ours calls leap years.
     */
    @Test
    fun `the year is three hundred and sixty five days and never three hundred and sixty six`() {
        var seen = 0
        var day = at(-1250, 1, 1)
        val first = EgyptianCalendar.dateOf(day, 0, -1250)
        for (step in 1..365) {
            day += CivilDays.DAY_MS
            seen++
            val d = EgyptianCalendar.dateOf(day, 0, -1250)
            if (step < 365) {
                assertTrue(
                    "the date came round after only $step days",
                    d.day != first.day || d.season != first.season ||
                        d.monthOfSeason != first.monthOfSeason ||
                        d.epagomenal != first.epagomenal
                )
            }
        }
        val round = EgyptianCalendar.dateOf(day, 0, -1250)
        assertEquals(365, seen)
        assertEquals("the year is not 365 days long", first.day, round.day)
        assertEquals(first.season, round.season)
        assertEquals(first.monthOfSeason, round.monthOfSeason)
        assertEquals(first.epagomenal, round.epagomenal)
    }

    /**
     * Twelve months of thirty and five days over, and every day of the year
     * is one or the other.
     */
    @Test
    fun `every day of the year is a month day or one of the five`() {
        var months = 0
        var over = 0
        var day = at(-1250, 1, 1)
        for (step in 0 until 365) {
            val d = EgyptianCalendar.dateOf(day, 0, -1250)
            if (d.epagomenal) {
                over++
                assertNull("a day upon the year was given a season", d.season)
                assertEquals("and a month", 0, d.monthOfSeason)
                assertTrue("there are more than five of them", d.day in 1..5)
            } else {
                months++
                assertNotNull(d.season)
                assertTrue("month ${d.monthOfSeason} of a season", d.monthOfSeason in 1..4)
                assertTrue("day ${d.day} of a month", d.day in 1..30)
            }
            day += CivilDays.DAY_MS
        }
        assertEquals("the months do not add up to twelve thirties", 360, months)
        assertEquals("there are not five days upon the year", 5, over)
    }

    /**
     * The seasons come round in order, four months each.
     *
     * Named for the Nile and not for the sun: the flood, the land coming
     * out from under it, and the harvest.
     */
    @Test
    fun `the three seasons are four months each and come round in order`() {
        val seen = mutableListOf<Pair<EgyptianCalendar.Season, Int>>()
        var day = at(-746, 2, 26)
        for (step in 0 until 360) {
            val d = EgyptianCalendar.dateOf(day, 0, -746)
            val key = d.season!! to d.monthOfSeason
            if (seen.lastOrNull() != key) seen.add(key)
            day += CivilDays.DAY_MS
        }
        assertEquals(
            "the year is not three seasons of four months",
            listOf(
                EgyptianCalendar.Season.AKHET to 1, EgyptianCalendar.Season.AKHET to 2,
                EgyptianCalendar.Season.AKHET to 3, EgyptianCalendar.Season.AKHET to 4,
                EgyptianCalendar.Season.PERET to 1, EgyptianCalendar.Season.PERET to 2,
                EgyptianCalendar.Season.PERET to 3, EgyptianCalendar.Season.PERET to 4,
                EgyptianCalendar.Season.SHEMU to 1, EgyptianCalendar.Season.SHEMU to 2,
                EgyptianCalendar.Season.SHEMU to 3, EgyptianCalendar.Season.SHEMU to 4
            ),
            seen
        )
    }

    /**
     * And the year wanders: 1 Thoth walks all the way round the sun's year
     * and comes home after fourteen hundred and sixty of them.
     *
     * The famous consequence of having no leap day, and the reason the
     * seasons stopped matching their own names. Measured against our own
     * calendar, which does keep step with the sun: the day of *our* year
     * that 1 Thoth falls on slides forward by about a day every four
     * years.
     */
    @Test
    fun `the civil year wanders against the sun`() {
        fun thothIn(year: Int): Int {
            var day = at(year, 1, 1)
            for (step in 0 until 366) {
                val d = EgyptianCalendar.dateOf(day, 0, year)
                if (!d.epagomenal && d.season == EgyptianCalendar.Season.AKHET &&
                    d.monthOfSeason == 1 && d.day == 1
                ) {
                    return CivilDays.dayOf(day, 0) - CivilDays.epochDay(year, 1, 1)
                }
                day += CivilDays.DAY_MS
            }
            return -1
        }
        val early = thothIn(-1250)
        val late = thothIn(-1210)
        assertTrue("1 Thoth was not found at all", early >= 0 && late >= 0)
        // *Backwards*, and that is the direction, not a sign slip: the
        // Egyptian year is 365 days and the Julian one 365¼, so after four
        // Egyptian years only 1,460 days have gone by against our 1,461
        // and New Year has arrived a day early. Forty years is ten days of
        // it. Said the other way round, which is how it is usually said:
        // the seasons walk *forwards* through the Egyptian year, and after
        // fourteen and a half centuries they have been all the way round.
        val moved = late - early
        assertTrue(
            "1 Thoth moved $moved days of our year in forty of them, and it " +
                "should have gone ten days backwards",
            moved in -11..-9
        )
    }

    /**
     * And all the way round in fourteen hundred and sixty years.
     *
     * The Sothic cycle, which is the one identity this whole calendar
     * turns on and which is worth writing down because it is easy to get
     * backwards: 1,461 Egyptian years of 365 days are 1,460 Julian years
     * of 365¼. The *shorter* year needs one more of them to cover the same
     * ground, so the cycle is named for the 1,460 and the Egyptian count
     * is the 1,461.
     */
    @Test
    fun `the wandering year comes home after fourteen hundred and sixty`() {
        assertEquals(
            "1461 Egyptian years are not 1460 Julian ones",
            1461 * 365, 1460 * 1461 / 4
        )
        assertEquals("which is 533,265 days", 533_265, 1461 * 365)
        // And the star agrees: a Sothic cycle back from Censorinus and the
        // rising is on 1 Thoth again.
        assertEquals(0, EgyptianCalendar.sothicDayOfYear(139))
        assertEquals(0, EgyptianCalendar.sothicDayOfYear(139 - 1460))
        assertEquals(0, EgyptianCalendar.sothicDayOfYear(139 - 2920))
        // But not partway through it, or the test above says only that a
        // constant is a constant.
        assertTrue(EgyptianCalendar.sothicDayOfYear(139 - 700) != 0)
    }

    // -------------------------------------------------------------- the kings

    /** The year count starts again with each king. */
    @Test
    fun `the year count belongs to the king`() {
        // Ramesses II came to the throne in 1279 BC, so 1250 BC is his
        // thirtieth year — the year of his first jubilee, as it happens.
        val d = dateOf(-1250, 6, 15)
        assertEquals("Ramesses II", d.king?.name)
        assertEquals("the regnal year is not counted from the accession", 30, d.regnalYear)
        // And the year he came to the throne is his year one, not his year
        // nought.
        assertEquals(1, dateOf(-1279, 6, 15).regnalYear)
        // The year before it belongs to somebody else and starts again.
        val before = dateOf(-1280, 6, 15)
        assertTrue("the reign did not change", before.king?.name != "Ramesses II")
        assertTrue("the count did not start again", before.regnalYear < 30)
    }

    /** The list is in order, which is what makes the lookup mean anything. */
    @Test
    fun `the kings are in the order they reigned`() {
        val years = EgyptianCalendar.kings.map { it.from }
        assertEquals("the king list is not sorted", years.sorted(), years)
        assertEquals("two kings share an accession year", years.size, years.toSet().size)
    }

    /** And it covers the whole stretch the dial writes in hieroglyphs. */
    @Test
    fun `there is a king for every year the dial carves`() {
        for (year in OrreryYear.EGYPT_BEGINS..0 step 37) {
            assertNotNull(
                "no king in $year, so the date has no year number",
                EgyptianCalendar.kingIn(year)
            )
        }
        // And nobody before the first dynasty, which is the point of where
        // the hieroglyphs stop.
        assertNull(EgyptianCalendar.kingIn(-3200))
    }

    // ------------------------------------------------------------ the star

    /**
     * Sothis rises on 1 Thoth in AD 139, and walks forward through the
     * civil year at a day every four years either side of it.
     *
     * The one hard peg: Censorinus wrote in 238 that the two had coincided
     * a hundred years before, and that single coincidence fixes where the
     * rising falls in the civil year for every other year there has been.
     */
    @Test
    fun `sothis rises on the first of thoth in the year censorinus names`() {
        assertEquals("the star does not rise on new year's day in 139", 0, EgyptianCalendar.sothicDayOfYear(139))
        // Four years earlier it was one day later in the civil year; four
        // hundred earlier, a hundred days later.
        assertEquals(1, EgyptianCalendar.sothicDayOfYear(135))
        assertEquals(100, EgyptianCalendar.sothicDayOfYear(-261))
        // And after a whole Sothic cycle it is back on 1 Thoth.
        assertEquals(
            "the cycle does not come home after 1460 years",
            0, EgyptianCalendar.sothicDayOfYear(139 - 1460)
        )
    }

    /** Once a year, on one day, and the dial says so. */
    @Test
    fun `the rising happens once a year and only on its own day`() {
        var risings = 0
        var day = at(-1250, 1, 1)
        for (step in 0 until 365) {
            val d = EgyptianCalendar.dateOf(day, 0, -1250)
            if (EgyptianCalendar.isSothicRising(d, -1250)) risings++
            day += CivilDays.DAY_MS
        }
        assertEquals("the star rose $risings times in a year", 1, risings)
    }

    /** Every day of the year is somewhere in the year, and only once. */
    @Test
    fun `each day of the year has its own number`() {
        val seen = mutableSetOf<Int>()
        var day = at(-1250, 1, 1)
        for (step in 0 until 365) {
            val n = EgyptianCalendar.dayOfYear(EgyptianCalendar.dateOf(day, 0, -1250))
            assertTrue("day $n came round twice", seen.add(n))
            assertTrue("day $n is outside the year", n in 0..364)
            day += CivilDays.DAY_MS
        }
        assertEquals(365, seen.size)
    }

    // ------------------------------------------------------------ on the dial

    private fun sky(): ClockView {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1080, 2000)
        return clock
    }

    /**
     * The dial carves the Egyptian date and not ours.
     *
     * The way this goes wrong is silent and was the whole complaint: a row
     * of hieroglyphs that is beautiful, correct as numerals, and saying
     * the fifteenth of June.
     */
    @Test
    fun `the dial carves an egyptian date rather than ours`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-1250)
        val date = clock.egyptianDate()
        assertEquals("the sky is not standing in Ramesses' reign", "Ramesses II", date.king?.name)
        assertEquals(30, date.regnalYear)
        // Our own date that day is the fifteenth of June. If the row were
        // still a transliteration, that is what would be in it.
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = clock.orreryMsForTest()
        val ourDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val ourMonth = cal.get(java.util.Calendar.MONTH) + 1
        assertFalse(
            "the carved date is our own day and month with hieroglyphs on it",
            date.day == ourDay && !date.epagomenal &&
                (date.season!!.ordinal * 4 + date.monthOfSeason) == ourMonth
        )
    }

    /** And it says whose year it is, because a regnal year needs a king. */
    @Test
    fun `the caption under a carved date names the king`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-1250)
        assertEquals(
            "a regnal year with nobody's name on it is not a date",
            "Ramesses II", clock.orreryCaption()
        )
    }

    /** Except on the one morning of the year Egypt actually wrote about. */
    @Test
    fun `on the morning sothis returns the caption names the star`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-1250)
        var found = false
        for (day in 0 until 366) {
            if (EgyptianCalendar.isSothicRising(clock.egyptianDate(), -1250)) {
                assertEquals(
                    "the opening of the year went unremarked",
                    context.getString(R.string.egy_sothis), clock.orreryCaption()
                )
                found = true
                break
            }
            clock.nudgeOrreryForTest(CivilDays.DAY_MS)
        }
        assertTrue("the star never rose in a whole year", found)
    }
}
