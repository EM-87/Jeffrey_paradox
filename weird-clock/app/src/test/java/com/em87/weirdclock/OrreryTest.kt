package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The solar system, held against the real one.
 *
 * Two sorts of check here, and the second is the one worth having. The
 * first is that the arithmetic says what the sky says: that a year is a
 * year, that the Sun is where it is at the equinox, that the Moon is full
 * when the other half of this app says it is. The second is that the
 * *mechanism* behaves — that there is one shaft turning everything, so
 * forcing an orbit is winding time and the correlations between the bodies
 * are not written down anywhere but simply happen.
 *
 * Nothing here needs a phone, so none of it runs on one.
 */
class OrreryTest {

    /** An instant, from a date, at noon UTC. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        CivilDays.epochDay(year, month, day) * CivilDays.DAY_MS + hour * 3_600_000L

    // ------------------------------------------------------ against the sky

    @Test
    fun `the years are the right length`() {
        fun year(body: Orrery.Body) = Orrery.periodDays(body)
        assertEquals(87.969, year(Orrery.Body.MERCURY), 0.01)
        assertEquals(224.701, year(Orrery.Body.VENUS), 0.01)
        assertEquals(365.256, year(Orrery.Body.EARTH), 0.01)
        assertEquals(686.980, year(Orrery.Body.MARS), 0.05)
        assertEquals(4332.6, year(Orrery.Body.JUPITER), 1.0)
        assertEquals(10759.2, year(Orrery.Body.SATURN), 5.0)
        assertEquals(30685.0, year(Orrery.Body.URANUS), 20.0)
        assertEquals(60190.0, year(Orrery.Body.NEPTUNE), 30.0)
        // A month of the sky, not a month of phases: the Moon is back among
        // the same stars in 27.3 days and back to the same shape in 29.5.
        assertEquals(27.3216, year(Orrery.Body.MOON), 0.001)
    }

    /**
     * The Sun stands at zero at the March equinox, which is what the March
     * equinox is.
     *
     * Three degrees of slack, and the two reasons for it are the honest
     * limits of this whole class. The orbits in here are circles, and a
     * real one runs up to 1.9° ahead of or behind a circular one over the
     * year — in March it is near its worst. And the longitudes are measured
     * from where the equinox stood in 2000, which is not where it stands
     * now: it slides about 1.4° a century, so another 0.4° by 2026.
     * Tightening this would mean writing an ephemeris, and the orrery does
     * not need one — it needs every planet measured from the *same* line,
     * and it is.
     */
    @Test
    fun `the Sun is where the equinox says it is`() {
        val equinox2026 = at(2026, 3, 20, hour = 15)
        assertTrue(
            "the Sun came out at ${Orrery.sunLongitude(equinox2026)}°",
            Orrery.separation(Orrery.sunLongitude(equinox2026), 0.0) < 3.0
        )
        // And half a year later it is opposite, at the September equinox.
        val september = at(2026, 9, 23, hour = 0)
        assertTrue(
            "the Sun came out at ${Orrery.sunLongitude(september)}°",
            Orrery.separation(Orrery.sunLongitude(september), 180.0) < 3.0
        )
    }

    /**
     * The phase agrees with the little moon already on the dial.
     *
     * [SkyGlyph.phaseAt] counts months forward from one observed new moon.
     * This class works the angle out from two orbits. They share no line of
     * code and no constant, so when they agree to within a few hours it is
     * because both are right.
     */
    @Test
    fun `both halves of the app agree about the Moon`() {
        var worst = 0.0
        var worstAt = 0L
        // Twenty years, a fortnight at a time.
        var day = CivilDays.epochDay(2020, 1, 1)
        val end = CivilDays.epochDay(2040, 1, 1)
        while (day < end) {
            val ms = day * CivilDays.DAY_MS
            val gap = Orrery.separation(
                Orrery.moonPhase(ms) * 360.0,
                SkyGlyph.phaseAt(ms) * 360.0
            )
            if (gap > worst) { worst = gap; worstAt = ms }
            day += 14
        }
        // Twelve degrees of elongation is a day. The two are allowed to
        // differ by less than that; more would mean one of them names the
        // wrong day as full.
        assertTrue(
            "they were ${"%.1f".format(worst)}° apart at $worstAt",
            worst < 12.0
        )
    }

    /** Longitudes come back inside one turn, whatever is asked for. */
    @Test
    fun `angles stay on the circle`() {
        for (body in Orrery.planets + Orrery.Body.MOON) {
            for (year in listOf(1900, 1970, 2000, 2026, 2200)) {
                val a = Orrery.longitude(body, at(year, 6, 1))
                assertTrue("$body in $year came out at $a", a >= 0.0 && a < 360.0)
            }
        }
    }

    // ------------------------------------------------------- the mechanism

    /**
     * Carrying a body all the way round moves time by exactly its year.
     *
     * In steps, because that is how a finger does it: the drag is a
     * hundred small moves and they must add up to one orbit, not to a
     * hundred separate opinions about where the body started.
     */
    @Test
    fun `one turn of a planet is one of its years`() {
        for (body in Orrery.planets) {
            var total = 0L
            var last = 0.0
            for (step in 1..360) {
                total += Orrery.stepMs(body, last, step.toDouble())
                last = step.toDouble()
            }
            val days = total / CivilDays.DAY_MS.toDouble()
            assertEquals(
                "$body did not come back to where it started",
                Orrery.periodDays(body), days, 0.5
            )
        }
    }

    /**
     * The planet stays under the finger.
     *
     * The one thing the drag has to do: asked to move a body to a place,
     * the instant it hands back must be an instant at which the body is
     * *at* that place. It is not the obvious identity it looks like —
     * winding produces a time, and the body's position at that time comes
     * back through Kepler's equation, so the two conversions have to be
     * genuine inverses. Working in mean longitudes and drawing in true ones
     * would leave Mercury trailing its own finger by weeks at one end of
     * its orbit.
     */
    @Test
    fun `a planet ends up where the finger put it`() {
        val start = at(2026, 8, 18)
        for (body in Orrery.planets) {
            for (carry in listOf(7.0, 37.0, 123.0, -95.0)) {
                val from = Orrery.longitude(body, start)
                val to = Orrery.wrap(from + carry)
                val landed = Orrery.longitude(body, start + Orrery.stepMs(body, from, to))
                assertTrue(
                    "$body was put at $to and turned up at $landed",
                    Orrery.separation(landed, to) < 0.01
                )
            }
        }
    }

    /**
     * Across the top of the circle it goes the short way.
     *
     * 359° to 1° is forward two degrees. Read the other way it is backward
     * three hundred and fifty-eight, and every time a finger crossed that
     * one spot the date would jump — a year for the Earth, a century and a
     * half for Neptune.
     */
    @Test
    fun `crossing the top does not throw the date across the room`() {
        val forward = Orrery.stepMs(Orrery.Body.EARTH, 359.0, 1.0)
        assertTrue("it went backwards", forward > 0)
        val days = forward / CivilDays.DAY_MS.toDouble()
        assertEquals("two degrees of a year", 365.256 * 2 / 360, days, 0.05)

        val back = Orrery.stepMs(Orrery.Body.EARTH, 1.0, 359.0)
        assertTrue("it went forwards", back < 0)
        assertEquals("and the same, the other way", -forward.toDouble(), back.toDouble(), 1000.0)
    }

    /**
     * The correlation, which is the whole feature: the same movement of the
     * finger means wildly different things depending on what it is on.
     *
     * Nothing in the code says "Neptune is slow". It says a period, once,
     * and this falls out.
     */
    @Test
    fun `dragging an outer planet moves far more time than an inner one`() {
        // Measured over a whole turn, because that is the only arc on which
        // the two are strictly comparable: on an ellipse a planet covers
        // thirty degrees quickly at one end of its orbit and slowly at the
        // other, and Mercury's two ends differ by half.
        fun wholeTurn(body: Orrery.Body): Long {
            var total = 0L
            var last = 0.0
            for (step in 1..360) {
                total += Orrery.stepMs(body, last, step.toDouble())
                last = step.toDouble()
            }
            return total
        }
        val ratio = wholeTurn(Orrery.Body.NEPTUNE).toDouble() / wholeTurn(Orrery.Body.MERCURY)
        assertEquals(
            "the ratio must be the ratio of their years",
            Orrery.periodDays(Orrery.Body.NEPTUNE) / Orrery.periodDays(Orrery.Body.MERCURY),
            ratio, 1.0
        )
        // And a hand's width of Neptune, wherever it is taken from, is
        // still more than a decade, while the same hand's width of Mercury
        // is a week or so.
        val byNeptune = Orrery.stepMs(Orrery.Body.NEPTUNE, 0.0, 30.0)
        val byMercury = Orrery.stepMs(Orrery.Body.MERCURY, 0.0, 30.0)
        assertTrue("a hand's width of Neptune is not decades", byNeptune > 13L * 365 * 86_400_000L)
        assertTrue("the same movement means the same thing on both", byNeptune / byMercury > 300)
    }

    /**
     * And what the correlation looks like from the other end: while the
     * finger takes Neptune a little way, Mercury goes round hundreds of
     * times and the Earth hundreds too.
     */
    @Test
    fun `while Neptune is carried the inner planets run`() {
        val start = at(2026, 8, 18)
        val moved = Orrery.stepMs(Orrery.Body.NEPTUNE, 0.0, 30.0)
        val turnsOfMercury = moved / CivilDays.DAY_MS / Orrery.periodDays(Orrery.Body.MERCURY)
        assertTrue("Mercury barely moved", turnsOfMercury > 50)
        // And it is a real position at the far end, not a wrapped one.
        assertTrue(Orrery.longitude(Orrery.Body.MERCURY, start + moved) in 0.0..360.0)
    }

    // -------------------------------------------------- the Moon lets go

    /**
     * Force Mars and the Moon disengages; force the Earth and it comes
     * along.
     *
     * This is the rule that keeps the thing usable. A month is small enough
     * that any grab on anything further out than the Earth would spin the
     * Moon into a grey ring, saying nothing and hiding the phase, which is
     * the one thing the Moon is on this dial for.
     */
    @Test
    fun `the Moon lets go of everything except the Earth`() {
        assertTrue("nothing held, it turns", Orrery.moonFollows(null))
        assertTrue("the Earth drives it", Orrery.moonFollows(Orrery.Body.EARTH))
        assertTrue("and so does taking hold of the Moon itself", Orrery.moonFollows(Orrery.Body.MOON))
        for (body in listOf(
            Orrery.Body.MERCURY, Orrery.Body.VENUS, Orrery.Body.MARS,
            Orrery.Body.JUPITER, Orrery.Body.SATURN,
            Orrery.Body.URANUS, Orrery.Body.NEPTUNE
        )) {
            assertFalse("$body must not drag the Moon with it", Orrery.moonFollows(body))
        }
    }

    /**
     * And the reason, measured: carrying the Earth spins the Moon a dozen
     * times, which can be watched. Carrying Saturn would spin it thousands,
     * which cannot.
     */
    @Test
    fun `the Earth drives the Moon at a speed a person can follow`() {
        fun moonTurnsFor(body: Orrery.Body): Double {
            var total = 0L
            var last = 0.0
            for (step in 1..360) {
                total += Orrery.stepMs(body, last, step.toDouble())
                last = step.toDouble()
            }
            return total / CivilDays.DAY_MS / Orrery.periodDays(Orrery.Body.MOON)
        }
        assertTrue(
            "a year is a dozen moons, which can be counted going past",
            moonTurnsFor(Orrery.Body.EARTH) in 13.0..14.0
        )
        assertTrue(
            "and this is what disengaging is for",
            moonTurnsFor(Orrery.Body.SATURN) > 350
        )
    }

    /**
     * Winding the Earth really does change the phase — the point of letting
     * the Moon follow it at all.
     */
    @Test
    fun `winding the Earth walks the Moon through its phases`() {
        val start = at(2026, 8, 18)
        val seen = mutableSetOf<Int>()
        var t = start
        // A fortnight of Earth-winding at a time, over half a year.
        repeat(13) {
            t += Orrery.stepMs(Orrery.Body.EARTH, 0.0, 360.0 * 14 / 365.256)
            seen.add((Orrery.moonPhase(t) * 8).toInt())
        }
        assertTrue("it never got past one shape: $seen", seen.size >= 5)
    }

    // -------------------------------------------------------- alignments

    /** Three or more, or it says nothing: two of anything are always in line. */
    @Test
    fun `two bodies are never called an alignment`() {
        var everSaidTwo = false
        var day = CivilDays.epochDay(2000, 1, 1)
        val end = CivilDays.epochDay(2100, 1, 1)
        while (day < end) {
            val n = Orrery.aligned(day * CivilDays.DAY_MS, 12.0).size
            if (n in 1..2) everSaidTwo = true
            day += 7
        }
        assertFalse("it named a run of fewer than three", everSaidTwo)
    }

    /** What it names really is inside the arc it claims. */
    @Test
    fun `what it calls aligned is aligned`() {
        val within = 15.0
        val found = Orrery.nextAlignment(at(2026, 1, 1), within, atLeast = 3, limitDays = 4000)
        assertNotNull("a hundred-odd years with nothing in line is not credible", found)
        val bodies = Orrery.aligned(found!!, within)
        assertTrue(bodies.size >= 3)
        val angles = bodies.map { Orrery.longitude(it, found) }
        for (a in angles) for (b in angles) {
            assertTrue(
                "$bodies were $within° apart at best but ${Orrery.separation(a, b)}° at worst",
                Orrery.separation(a, b) <= within + 0.001
            )
        }
    }

    /** And they are named in the order they stand out from the Sun. */
    @Test
    fun `an alignment is read outwards`() {
        val found = Orrery.nextAlignment(at(2026, 1, 1), 15.0, atLeast = 3, limitDays = 4000)!!
        val bodies = Orrery.aligned(found, 15.0)
        val order = bodies.map { Orrery.planets.indexOf(it) }
        assertEquals("out from the Sun, not round the circle", order.sorted(), order)
    }

    /**
     * The tighter the arc asked for, the longer the wait. A search that
     * ignored its own tolerance would answer both the same.
     */
    @Test
    fun `a tighter alignment is a rarer one`() {
        val from = at(2026, 1, 1)
        val loose = Orrery.nextAlignment(from, 30.0, atLeast = 3, limitDays = 30_000)
        val tight = Orrery.nextAlignment(from, 6.0, atLeast = 3, limitDays = 30_000)
        assertNotNull(loose)
        if (tight != null) assertTrue("a needle came up sooner than a haystack", tight >= loose!!)
    }

    /** Nothing lines up for ever: with no time to look in, it finds nothing. */
    @Test
    fun `a search with nowhere to look finds nothing`() {
        assertEquals(
            null,
            Orrery.nextAlignment(at(2026, 1, 1), 0.5, atLeast = 5, limitDays = 400)
        )
    }

    // ------------------------------------------------------------ drawing

    /** The rings come out in order, and none of them lands on another. */
    @Test
    fun `the rings run outwards without touching`() {
        val radii = Orrery.planets.map { Orrery.ringFraction(it) }
        for (i in 1 until radii.size) {
            assertTrue(
                "${Orrery.planets[i]} is not outside ${Orrery.planets[i - 1]}",
                radii[i] > radii[i - 1]
            )
            assertTrue(
                "the rings are closer together than the Moon's orbit is wide",
                radii[i] - radii[i - 1] > Orrery.MOON_RING
            )
        }
        assertTrue("the innermost ring is inside the dial", radii.first() > 0.1f)
        assertTrue("the outermost ring is on the dial", radii.last() <= 1.0f)
    }
}
