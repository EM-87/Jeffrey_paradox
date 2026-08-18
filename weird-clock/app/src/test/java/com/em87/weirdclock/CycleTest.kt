package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cycle engine, with the days moved by hand.
 *
 * No Robolectric and no Android: the whole point of the engine being whole
 * days of arithmetic is that what it predicts can be checked without a
 * phone, a calendar or a clock. Every test here is a history somebody could
 * plausibly have, and the question is always the same — given these days,
 * what may honestly be said about the ones that have not happened yet.
 *
 * The ones that matter most are the ones about *not* being confident: the
 * outlier that must not move the prediction, the single record that must
 * not pretend to be one, and the window that has to widen when the cycles
 * do.
 */
class CycleTest {

    /** A history from a first day and a run of gaps, which is how one grows. */
    private fun history(first: Int, vararg gaps: Int): List<Cycle.Period> {
        var day = first
        val out = mutableListOf(Cycle.Period(day))
        for (g in gaps) {
            day += g
            out.add(Cycle.Period(day))
        }
        return out
    }

    // ---------------------------------------------------- what it learns

    @Test
    fun `with nothing recorded there is nothing to say`() {
        assertNull(Cycle.forecast(emptyList()))
        assertEquals(0, Cycle.dayOf(emptyList(), 20_000))
        assertEquals(0, Cycle.delay(emptyList(), 20_000))
        assertFalse(Cycle.late(emptyList(), 20_000))
        assertEquals(Cycle.Phase.NONE, Cycle.phase(emptyList(), 20_000, 20_000))
    }

    /**
     * One period is a record, not a pattern.
     *
     * There is a prediction — twenty-eight days, because that is what a
     * cycle is when nothing is known — but it must be able to say that it
     * learned nothing, so that whatever shows it can avoid dressing a
     * default up as a forecast.
     */
    @Test
    fun `one period predicts, and admits it is guessing`() {
        val f = Cycle.forecast(listOf(Cycle.Period(20_000)))!!
        assertEquals(20_000 + Cycle.DEFAULT_LENGTH, f.expected)
        assertFalse("it has nothing to have learned from", f.learned)
        assertEquals(Cycle.DEFAULT_LENGTH, f.length)
    }

    @Test
    fun `three regular cycles are learned from`() {
        val f = Cycle.forecast(history(20_000, 30, 30, 30))!!
        assertTrue("it has three gaps to go on", f.learned)
        assertEquals(30, f.length)
        assertEquals(20_090 + 30, f.expected)
    }

    /**
     * The one that matters. A single long cycle — an illness, a hard month
     * — must not drag the prediction for the rest of the year, which is
     * exactly what an average would do.
     */
    @Test
    fun `one strange month does not move the prediction`() {
        val steady = history(20_000, 28, 28, 28, 28)
        val withOutlier = history(20_000, 28, 28, 44, 28, 28)

        assertEquals(28, Cycle.typicalLength(steady))
        assertEquals(
            "a median ignores it; an average would be dragged to thirty-one",
            28, Cycle.typicalLength(withOutlier)
        )
        assertEquals(
            "and the average really would have moved",
            31, Cycle.plausibleGaps(withOutlier).average().toInt()
        )
    }

    /**
     * A gap too short to be a cycle is a period entered twice; one too long
     * is a period never entered at all. Neither teaches anything.
     */
    @Test
    fun `a gap that is not a cycle is not learned from`() {
        // A correction three days later, then normal months.
        val doubled = listOf(Cycle.Period(20_000)) + history(20_003, 29, 29, 29)
        assertEquals(
            "three days is not a cycle",
            29, Cycle.typicalLength(doubled)
        )

        // A month nobody wrote down: a sixty-day hole.
        val missed = history(20_000, 29, 60, 29, 29)
        assertEquals(29, Cycle.typicalLength(missed))
        assertFalse("and the hole is not in the lengths", 60 in Cycle.plausibleGaps(missed))
        assertTrue("but it is still in the record", 60 in Cycle.gaps(missed))
    }

    // ------------------------------------------------------- the window

    /**
     * Somebody regular gets a narrow window and somebody irregular an
     * honest wide one. One confident day for both would be a lie told to
     * the second of them every month.
     */
    @Test
    fun `the window is as wide as the cycles have been`() {
        val regular = history(20_000, 28, 28, 28, 28)
        val irregular = history(20_000, 25, 31, 26, 34)

        assertEquals("nothing has varied", Cycle.TIGHTEST, Cycle.spread(regular))
        assertTrue(
            "these have varied by nine days and the window says so",
            Cycle.spread(irregular) > Cycle.spread(regular)
        )
        val f = Cycle.forecast(irregular)!!
        assertEquals(f.expected - Cycle.spread(irregular), f.from)
        assertEquals(f.expected + Cycle.spread(irregular), f.to)
    }

    /** And it never closes to nothing, or opens into a shrug. */
    @Test
    fun `the window has a floor and a ceiling`() {
        assertEquals(Cycle.TIGHTEST, Cycle.spread(history(20_000, 28, 28, 28)))
        assertEquals(
            Cycle.WIDEST,
            Cycle.spread(history(20_000, 21, 45, 21, 45, 21))
        )
    }

    // -------------------------------------------------------- the delay

    /**
     * The number a person counts is from the day it was due. What the app
     * says out loud is a different question, and waits for the window.
     */
    @Test
    fun `a delay is counted from the day, and announced after the window`() {
        val h = history(20_000, 28, 28, 28)   // last start 20_084, expected 20_112
        val f = Cycle.forecast(h)!!
        assertEquals(20_112, f.expected)

        assertEquals(0, Cycle.delay(h, f.expected - 1))
        assertEquals(0, Cycle.delay(h, f.expected))
        assertEquals(3, Cycle.delay(h, f.expected + 3))

        assertFalse("inside the window is not late", Cycle.late(h, f.to))
        assertTrue("past it is", Cycle.late(h, f.to + 1))
    }

    /**
     * Being one day past the middle of a wide window is not a delay — it is
     * the window doing its job. An app that announced it every month would
     * be an app nobody believed.
     */
    @Test
    fun `an irregular cycle is not late the moment it passes the day`() {
        val irregular = history(20_000, 25, 31, 26, 34)
        val f = Cycle.forecast(irregular)!!
        assertTrue("this history is not regular", f.to - f.expected >= 3)
        assertFalse(
            "the day after the expected one, with a window this wide",
            Cycle.late(irregular, f.expected + 1)
        )
    }

    /**
     * And a period that arrived late is remembered as having been late,
     * measured against what was expected of it before it turned up — which
     * is not the same question as "am I late now".
     */
    @Test
    fun `last month's delay is measured against what was expected then`() {
        // Twenty-eight-day cycles, then one that took thirty-four.
        val h = history(20_000, 28, 28, 34)
        assertEquals(6, Cycle.delayOfLast(h))
        assertEquals("and today is not late at all", 0, Cycle.delay(h, 20_090))
    }

    // -------------------------------------------------------- the phases

    @Test
    fun `the days actually bled on outrank everything`() {
        val h = listOf(Cycle.Period(20_000, days = 4), Cycle.Period(20_028, days = 5))
        for (d in 20_028..20_032) {
            assertEquals("day $d", Cycle.Phase.PERIOD, Cycle.phase(h, d, 20_030))
        }
        assertEquals(
            "and the day after it ended is not",
            Cycle.Phase.NONE, Cycle.phase(h, 20_033, 20_033)
        )
    }

    /** A period with no length told covers the usual few days. */
    @Test
    fun `a period nobody measured still covers some days`() {
        val h = listOf(Cycle.Period(20_000))
        assertEquals(Cycle.Phase.PERIOD, Cycle.phase(h, 20_000, 20_000))
        assertEquals(
            Cycle.Phase.PERIOD,
            Cycle.phase(h, 20_000 + Cycle.DEFAULT_BLEED - 1, 20_000)
        )
    }

    @Test
    fun `the predicted window is drawn, and turns into a delay when it passes`() {
        val h = history(20_000, 28, 28, 28)
        val f = Cycle.forecast(h)!!

        assertEquals(
            "before it arrives",
            Cycle.Phase.PREDICTED, Cycle.phase(h, f.expected, f.expected - 10)
        )
        assertEquals(
            "and once the window has gone by without one",
            Cycle.Phase.LATE, Cycle.phase(h, f.to + 2, f.to + 2)
        )
    }

    /**
     * The fertile window is counted back from the predicted start, which is
     * the only way this arithmetic can be done — and makes it exactly as
     * uncertain as that prediction.
     */
    @Test
    fun `the fertile window sits a fortnight before the next period`() {
        val h = history(20_000, 28, 28, 28)
        val f = Cycle.forecast(h)!!
        val fertile = Cycle.fertileWindow(h)!!
        assertEquals(f.expected - Cycle.LUTEAL_DAYS - 5, fertile.first)
        assertEquals(f.expected - Cycle.LUTEAL_DAYS + 1, fertile.last)
        assertTrue("well before the period it is counted back from", fertile.last < f.from)
    }

    // ------------------------------------------------- keeping the record

    /**
     * A tap on the wrong day is the commonest mistake there is, and it
     * looks exactly like a three-day cycle to anything that trusts the
     * record. So a start close to one already there replaces it.
     */
    @Test
    fun `recording again within a few days is a correction, not a cycle`() {
        val h = Cycle.record(listOf(Cycle.Period(20_000)), 20_003)
        assertEquals(1, h.size)
        assertEquals(20_003, h.first().start)

        val far = Cycle.record(h, 20_003 + Cycle.SHORTEST)
        assertEquals("and one a full cycle later is a new one", 2, far.size)
    }

    /**
     * The record itself stays in order, not just the view of it.
     *
     * The first version of this test asked [Cycle.starts], which sorts on
     * the way out — so it passed whatever order the list was really in, and
     * would have gone on passing with the sort taken out. What actually
     * depends on the order is [Cycle.typicalBleed], which takes the *last*
     * few lengths: fed an unsorted list it takes the last few written down
     * rather than the last few that happened.
     */
    @Test
    fun `the record stays in order however it is written`() {
        var h = Cycle.record(emptyList(), 20_060)
        h = Cycle.record(h, 20_000)
        h = Cycle.record(h, 20_030)
        assertEquals(
            "the list itself, not a sorted view of it",
            listOf(20_000, 20_030, 20_060), h.map { it.start }
        )
    }

    /** And the thing that reads it in order gets the recent ones. */
    @Test
    fun `how long it lasts is learned from the recent ones, not the newest written`() {
        // Seven months at four days, then this month at seven — written
        // down out of order, oldest last, the way a catch-up entry goes in.
        var h = emptyList<Cycle.Period>()
        h = Cycle.record(h, 20_196, 7)
        for (i in 0..6) h = Cycle.record(h, 20_000 + i * 28, 4)
        assertEquals(
            "the seven-day month is the most recent and must count",
            listOf(4, 4, 4, 4, 4, 4, 4, 7), h.map { it.days }
        )
        assertEquals(4, Cycle.typicalBleed(h))
    }

    @Test
    fun `one can be taken out again, and its length told`() {
        var h = history(20_000, 28, 28)
        h = Cycle.forget(h, 20_028)
        assertEquals(listOf(20_000, 20_056), Cycle.starts(h))

        h = Cycle.setLength(h, 20_056, 6)
        assertEquals(6, h.last().days)
        h = Cycle.setLength(h, 20_056, 99)
        assertTrue("nothing absurd gets stored", h.last().days <= 15)
    }

    @Test
    fun `how long it usually lasts is learned too`() {
        assertEquals(Cycle.DEFAULT_BLEED, Cycle.typicalBleed(history(20_000, 28, 28)))
        val told = listOf(
            Cycle.Period(20_000, 4), Cycle.Period(20_028, 4), Cycle.Period(20_056, 6)
        )
        assertEquals(4, Cycle.typicalBleed(told))
    }

    /** And which day of the cycle today is, which is the thing on the tin. */
    @Test
    fun `it says which day of the cycle today is`() {
        val h = history(20_000, 28, 28)
        assertEquals("the first day is day one", 1, Cycle.dayOf(h, 20_056))
        assertEquals(10, Cycle.dayOf(h, 20_065))
        assertEquals("and nothing sensible before it", 0, Cycle.dayOf(h, 19_999))
    }

    // ------------------------------------------------------- days and dates

    /**
     * The date arithmetic, which everything above is measured in.
     *
     * Checked against dates whose day number is known independently, and
     * round-tripped across a leap day and a century that is not a leap year
     * — the two places this kind of arithmetic goes wrong.
     */
    @Test
    fun `a date and its day number agree`() {
        assertEquals(0, Cycle.epochDay(1970, 1, 1))
        assertEquals(Triple(1970, 1, 1), Cycle.dateOf(0))
        assertEquals(19_723, Cycle.epochDay(2024, 1, 1))
        assertEquals(Triple(2024, 2, 29), Cycle.dateOf(Cycle.epochDay(2024, 2, 29)))
        assertEquals(
            "the day after a leap day",
            Triple(2024, 3, 1), Cycle.dateOf(Cycle.epochDay(2024, 2, 29) + 1)
        )
        assertEquals(
            "1900 was not a leap year",
            Triple(1900, 3, 1), Cycle.dateOf(Cycle.epochDay(1900, 2, 28) + 1)
        )
    }

    @Test
    fun `every day of a long stretch round-trips`() {
        var day = Cycle.epochDay(1999, 11, 20)
        repeat(2000) {
            val (y, m, d) = Cycle.dateOf(day)
            assertEquals("day $day is $y-$m-$d", day, Cycle.epochDay(y, m, d))
            day++
        }
    }

    /**
     * And a cycle that runs across a month end, a year end and a leap day
     * still counts in plain days.
     */
    @Test
    fun `a cycle across the new year is still twenty-eight days`() {
        val december = Cycle.epochDay(2023, 12, 20)
        val january = Cycle.epochDay(2024, 1, 17)
        assertEquals(28, january - december)

        val h = listOf(Cycle.Period(december), Cycle.Period(january))
        assertEquals(28, Cycle.typicalLength(h))
        assertEquals(
            Triple(2024, 2, 14),
            Cycle.dateOf(Cycle.forecast(h)!!.expected)
        )
    }
}
