package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dates, and whether they can be believed.
 *
 * Half of [SkyEvents] is worked out and half of it is remembered, and it is
 * the remembered half that needs watching: a table of eclipse dates typed
 * out by hand looks exactly the same whether it is right or wrong. So the
 * two halves are held against each other. An eclipse is a new or a full
 * moon by definition — the Moon in front of the Sun, or the Earth's shadow
 * across the Moon — and the orbits say when those fall without consulting
 * the table at all. Any date in the table that does not land on one is a
 * typo, and this says so.
 */
class SkyEventsTest {

    private fun day(year: Int, month: Int, dayOfMonth: Int) =
        CivilDays.epochDay(year, month, dayOfMonth)

    private fun noon(day: Int) = day * CivilDays.DAY_MS + 12 * 3_600_000L

    // ------------------------------------------- the table against the sky

    /**
     * Every eclipse in the table falls on a syzygy.
     *
     * The tolerance is in degrees of elongation because that is what is
     * actually being measured: the Moon pulls away from the Sun at about
     * 12° a day, so 18° is a day and a half. A date mistyped by two days
     * fails; a date right to the day passes with room for the couple of
     * degrees a circular orbit is out by.
     */
    @Test
    fun `every eclipse in the table lands on a new or a full moon`() {
        val complaints = mutableListOf<String>()
        for ((d, kind) in SkyEvents.eclipseDays()) {
            val elongation = Orrery.moonPhase(noon(d)) * 360.0
            val wanted = if (kind == SkyEvents.Kind.SOLAR_ECLIPSE) 0.0 else 180.0
            val off = Orrery.separation(elongation, wanted)
            val (y, m, dd) = CivilDays.dateOf(d)
            if (off > 18.0) {
                complaints.add("$y-$m-$dd is down as $kind but the Moon was ${"%.0f".format(off)}° off")
            }
        }
        assertTrue(complaints.joinToString("\n"), complaints.isEmpty())
    }

    /**
     * And they are in order, with no two on the same day.
     *
     * A table maintained by hand grows duplicates: the same eclipse entered
     * twice, once from each of two lists.
     */
    @Test
    fun `the table has no repeats and runs forwards`() {
        val days = SkyEvents.eclipseDays().map { it.first }
        assertEquals("out of order", days.sorted(), days)
        assertEquals("something is in there twice", days.distinct().size, days.size)
    }

    /**
     * A solar eclipse is never listed a fortnight from a lunar one by
     * accident — they come in pairs about a fortnight apart, which is a
     * real thing and not a mistake, but three in a row would be.
     */
    @Test
    fun `eclipses come no closer together than a fortnight`() {
        val days = SkyEvents.eclipseDays().map { it.first }
        for (i in 1 until days.size) {
            assertTrue(
                "two eclipses ${days[i] - days[i - 1]} days apart",
                days[i] - days[i - 1] >= 13
            )
        }
    }

    // -------------------------------------------------- what it works out

    /**
     * The full moons it names are a month apart, every time, for a decade.
     *
     * The test that catches the obvious way to get this wrong: asking "is
     * the Moon nearly full at noon" names two days running some months and
     * neither on others, and the gaps give it away immediately.
     */
    @Test
    fun `full moons come one a month, for ten years`() {
        val full = mutableListOf<Int>()
        var d = day(2026, 1, 1)
        val end = day(2036, 1, 1)
        while (d < end) {
            // A lunar eclipse counts, and has to: it *is* a full moon, and
            // the only reason it is not announced as one is that saying so
            // twice on the same line reads as two nights out.
            if (SkyEvents.on(d).any {
                    it.kind == SkyEvents.Kind.FULL_MOON ||
                        it.kind == SkyEvents.Kind.LUNAR_ECLIPSE
                }
            ) full.add(d)
            d++
        }
        assertTrue("hardly any full moons in ten years: ${full.size}", full.size > 110)
        for (i in 1 until full.size) {
            val gap = full[i] - full[i - 1]
            assertTrue("two full moons $gap days apart", gap in 29..30)
        }
    }

    /** And a new moon never falls on the same day as a full one. */
    @Test
    fun `the Moon is not new and full at once`() {
        var d = day(2026, 1, 1)
        val end = day(2031, 1, 1)
        while (d < end) {
            val kinds = SkyEvents.on(d).map { it.kind }
            assertFalse(
                "both on day $d",
                kinds.contains(SkyEvents.Kind.FULL_MOON) &&
                    kinds.contains(SkyEvents.Kind.NEW_MOON)
            )
            d++
        }
    }

    /**
     * An eclipse day is not also announced as a full moon.
     *
     * It is one, of course. But "total lunar eclipse · full moon" reads as
     * two things to anybody scanning a line of dates, and the second one is
     * the eclipse restating itself.
     */
    @Test
    fun `an eclipse does not also announce the moon it is`() {
        for ((d, _) in SkyEvents.eclipseDays()) {
            val kinds = SkyEvents.on(d).map { it.kind }
            assertFalse(
                "day $d says $kinds",
                kinds.contains(SkyEvents.Kind.FULL_MOON) ||
                    kinds.contains(SkyEvents.Kind.NEW_MOON)
            )
        }
    }

    /**
     * Mars comes to opposition every twenty-six months or so, and the app
     * finds it without being told when.
     */
    @Test
    fun `Mars is found at opposition about every two years`() {
        val found = mutableListOf<Int>()
        var d = day(2020, 1, 1)
        val end = day(2040, 1, 1)
        while (d < end) {
            if (SkyEvents.on(d).any {
                    it.kind == SkyEvents.Kind.OPPOSITION && it.body == Orrery.Body.MARS
                }
            ) found.add(d)
            d++
        }
        assertTrue("nothing like enough oppositions: ${found.size}", found.size >= 8)
        for (i in 1 until found.size) {
            val months = (found[i] - found[i - 1]) / 30.4
            assertTrue("two Mars oppositions $months months apart", months in 24.0..28.0)
        }
    }

    /**
     * And a known one lands where it should: Mars was at opposition in
     * January 2025, which anybody who looked up that month saw.
     */
    @Test
    fun `the January 2025 opposition of Mars is on the right day`() {
        val around = (day(2025, 1, 1)..day(2025, 2, 1)).filter { d ->
            SkyEvents.on(d).any {
                it.kind == SkyEvents.Kind.OPPOSITION && it.body == Orrery.Body.MARS
            }
        }
        assertEquals("it did not find one at all", 1, around.size)
        val (_, month, dayOfMonth) = CivilDays.dateOf(around.first())
        assertEquals(1, month)
        assertTrue("it came out on the ${dayOfMonth}th", dayOfMonth in 12..20)
    }

    /**
     * Jupiter's opposition comes round faster than Mars's, as it must: the
     * further out a planet is the less it moves while the Earth catches it
     * up again, and in the limit an opposition would come once a year.
     *
     * Counted over a century, because Jupiter's and Saturn's are only three
     * weeks apart in period and twenty years of them cannot be told apart.
     */
    @Test
    fun `the outer a planet is, the more often it is opposite`() {
        fun countOf(body: Orrery.Body): Int {
            var n = 0
            var d = day(2000, 1, 1)
            val end = day(2100, 1, 1)
            while (d < end) {
                if (SkyEvents.on(d).any {
                        it.kind == SkyEvents.Kind.OPPOSITION && it.body == body
                    }
                ) n++
                d++
            }
            return n
        }
        val mars = countOf(Orrery.Body.MARS)
        val jupiter = countOf(Orrery.Body.JUPITER)
        val saturn = countOf(Orrery.Body.SATURN)
        assertTrue("Jupiter ($jupiter) should beat Mars ($mars)", jupiter > mars)
        assertTrue("Saturn ($saturn) should beat Jupiter ($jupiter)", saturn > jupiter)
    }

    // ------------------------------------------------------- the showers

    /** The Perseids are on the night the Perseids are on. */
    @Test
    fun `the showers come round every year`() {
        for (year in 2024..2035) {
            val d = day(year, 8, 12)
            assertTrue(
                "no Perseids in $year",
                SkyEvents.on(d).any { it.shower == SkyEvents.Shower.PERSEIDS }
            )
        }
        assertTrue(
            "no Geminids",
            SkyEvents.on(day(2026, 12, 14))
                .any { it.shower == SkyEvents.Shower.GEMINIDS }
        )
    }

    /** And two of them never land on the same night. */
    @Test
    fun `no two showers peak together`() {
        val nights = SkyEvents.Shower.entries.map { it.month to it.day }
        assertEquals(nights.distinct().size, nights.size)
    }

    /** Most days have nothing on them at all, or the mark would mean nothing. */
    @Test
    fun `an ordinary day is left alone`() {
        var busy = 0
        var d = day(2026, 1, 1)
        val end = day(2027, 1, 1)
        while (d < end) {
            if (SkyEvents.anythingOn(d)) busy++
            d++
        }
        assertTrue("$busy days of the year had something on", busy in 15..45)
    }
}
