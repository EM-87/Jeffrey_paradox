package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the year really starts, and why the ring does not pretend
 * otherwise.
 *
 * The report was that the first of January is not at twelve on the year
 * ring — "it is more like ten to twelve". It is not, and it should not be.
 * Twelve on this dial is ecliptic longitude ninety, which is the December
 * solstice; the calendar's New Year is ten days after that, because Rome
 * put it where the consuls took office. Turning the ring to hide the gap
 * would break the one thing the ring is for, which is that the Earth
 * stands exactly on today's mark. So the four dates that *are* facts about
 * the sky get long marks of their own, and the gap becomes legible.
 *
 * This asks whether those four marks are the real four.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuarterDaysTest {

    private val dial = OrreryDial

    private fun date(day: Int): Triple<Int, Int, Int> = CivilDays.dateOf(day)

    /** Four a year, no more and no less, for centuries either way. */
    @Test
    fun `a year has four quarter days`() {
        for (year in listOf(1066, 1582, 1900, 2000, 2024, 2025, 2026, 2100, 3000)) {
            assertEquals(
                "the year $year did not have four quarter days",
                4, dial.quarterDays(year).size
            )
        }
    }

    /**
     * And they are the solstices and the equinoxes, on the days the almanac
     * prints them.
     *
     * Within a day. The ring has whole days to put a mark on and the
     * crossing is an instant, found by sampling each day at midnight UTC —
     * so a crossing near midnight, or a model running the few hours late
     * that a two-body solve does, lands on the next tick along. That is one
     * tick out of three hundred and sixty-five and invisible on the dial.
     * Two days out would mean the ring's idea of where the Earth is has
     * drifted from the sky's, which is a different kind of wrong.
     */
    @Test
    fun `the four are the solstices and the equinoxes`() {
        val wanted = listOf(
            2025 to listOf(3 to 20, 6 to 21, 9 to 22, 12 to 21),
            2026 to listOf(3 to 20, 6 to 21, 9 to 23, 12 to 21),
            2027 to listOf(3 to 20, 6 to 21, 9 to 23, 12 to 22)
        )
        for ((year, dates) in wanted) {
            val found = dial.quarterDays(year).map { date(it) }.sortedBy { it.second * 100 + it.third }
            assertEquals("wrong number of quarter days in $year", dates.size, found.size)
            for ((i, want) in dates.withIndex()) {
                val (_, month, day) = found[i]
                assertEquals("the ${want.first}th-month crossing landed in month $month", want.first, month)
                assertTrue(
                    "the crossing in month $month landed on the ${day}th, not the ${want.second}th",
                    kotlin.math.abs(day - want.second) <= 1
                )
            }
        }
    }

    /** They are a season apart, not bunched. */
    @Test
    fun `the quarter days are spread a season apart`() {
        val days = dial.quarterDays(2026).sorted()
        for (i in 1 until days.size) {
            val gap = days[i] - days[i - 1]
            assertTrue("two quarter days are $gap days apart", gap in 85..99)
        }
    }

    /**
     * And New Year is not one of them, which is the whole point.
     *
     * The December solstice comes ten or eleven days before the first of
     * January — the gap the user was looking at. Asserting the size of it
     * fixes the thing the dial is claiming: not "the ring is aligned" but
     * "the ring is aligned to the sky, and the calendar is the thing that
     * is ten days out".
     */
    @Test
    fun `new year is ten days after the solstice, not on it`() {
        val solstice = dial.quarterDays(2025).max()
        assertEquals("the last quarter day of the year is not in December", 12, date(solstice).second)
        val newYear = CivilDays.epochDay(2026, 1, 1)
        val gap = newYear - solstice
        assertTrue("the solstice is $gap days before New Year", gap in 9..12)
        assertFalse(
            "the first of January is being marked as a fact about the sky",
            newYear in dial.quarterDays(2026)
        )
    }

    // -------------------------------------------------- where the ring starts

    /**
     * The ring begins at the December solstice, not at New Year.
     *
     * This is the answer to the question, chosen for astronomical
     * precision: a year starts when the sun turns. It is also the only
     * answer that puts the start of the year at the top of this dial,
     * because the top of this dial is ecliptic longitude ninety and that
     * *is* the December solstice — so the alternative was a ring whose
     * first day sat ten days round from twelve for reasons to do with
     * Roman consuls.
     */
    @Test
    fun `the year on the ring starts at the solstice`() {
        val midMarch = CivilDays.epochDay(2026, 3, 15)
        val start = dial.yearStart(midMarch)
        val (year, month, _) = date(start)
        assertEquals("the ring did not start in December", 12, month)
        assertEquals("the ring started in the wrong December", 2025, year)
        assertTrue(
            "the ring's first day is not a solstice",
            start in dial.quarterDays(2025)
        )
    }

    /** A day after that December's solstice belongs to the next ring. */
    @Test
    fun `the ten days before new year belong to the year that has just begun`() {
        val solstice = dial.quarterDays(2025).max()
        assertEquals(
            "the day after the solstice looked back to the old year",
            solstice, dial.yearStart(solstice + 1)
        )
        assertEquals(
            "new year's eve looked back to the old year",
            solstice, dial.yearStart(CivilDays.epochDay(2025, 12, 31))
        )
        assertEquals(
            "new year's day started a ring of its own",
            solstice, dial.yearStart(CivilDays.epochDay(2026, 1, 1))
        )
        // And the day before it still belongs to the ring before.
        assertTrue(
            "the day before the solstice was pulled into the new year",
            dial.yearStart(solstice - 1) < solstice
        )
    }

    /** And it runs a whole year, solstice to solstice. */
    @Test
    fun `the ring is one turn of the earth long`() {
        for (day in listOf(
            CivilDays.epochDay(2024, 5, 1),
            CivilDays.epochDay(2025, 5, 1),
            CivilDays.epochDay(2026, 5, 1),
            CivilDays.epochDay(2027, 5, 1)
        )) {
            val start = dial.yearStart(day)
            val length = dial.yearLength(start)
            assertTrue("a year on the ring is $length days long", length in 363..368)
            assertTrue("the day being shown is not on its own ring", day in start until start + length)
        }
    }

    /** Asked twice, it answers the same — the cache is a cache. */
    @Test
    fun `the answer does not change when it is asked again`() {
        val once = dial.quarterDays(2026)
        val other = dial.quarterDays(1999)
        val again = dial.quarterDays(2026)
        assertEquals("the second asking gave a different year's answer", once, again)
        assertTrue("two different years gave the same days", once != other)
    }
}
