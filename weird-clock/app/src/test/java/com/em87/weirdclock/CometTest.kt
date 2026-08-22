package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The visitors, and the arithmetic that says they are visitors.
 *
 * What makes a comet different from a planet on this dial is not its
 * colour: it is that it spends nearly all of its life at the far end of a
 * long ellipse and crosses the whole inner system in a few months. Every
 * test here is about that asymmetry, because it is the thing that would
 * quietly stop being true if the position were worked out the easy way —
 * an even angle round an oval — and the result would be Halley strolling
 * past the Earth for a decade.
 */
class CometTest {

    private fun at(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        // A Calendar has no negative years. Given one it quietly lands on
        // the same year AD, which would put every wound-back date in this
        // file four thousand years from where it was meant to be — and
        // every assertion about a comet fading would pass or fail for
        // reasons that have nothing to do with comets.
        if (year < 1) {
            cal.set(Calendar.ERA, java.util.GregorianCalendar.BC)
            cal.set(1 - year, month - 1, day)
        } else {
            cal.set(year, month - 1, day)
        }
        return cal.timeInMillis
    }

    private fun yearOf(ms: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR)
    }

    // ------------------------------------------------------ the near end

    /** At perihelion a comet is as close to the Sun as it ever gets. */
    @Test
    fun `each comet is nearest the sun on the day it is said to be`() {
        for (comet in Comets.all) {
            val o = Comets.orbitOf(comet)
            val near = Comets.positionAt(comet, o.perihelionMs).radius
            val expected = o.aphelionRing * (1f - o.eccentricity.toFloat()) /
                (1f + o.eccentricity.toFloat())
            assertEquals(
                "$comet is not at the near end of its own orbit on its perihelion date",
                expected.toDouble(), near.toDouble(), expected * 0.02
            )
        }
    }

    /** And half an orbit later it is as far away as it ever gets. */
    @Test
    fun `half an orbit later each comet is at the far end`() {
        for (comet in Comets.all) {
            val o = Comets.orbitOf(comet)
            val half = (o.periodYears * 365.25 * DAY / 2).toLong()
            val far = Comets.positionAt(comet, o.perihelionMs + half).radius
            assertEquals(
                "$comet does not reach the far end of its orbit",
                o.aphelionRing.toDouble(), far.toDouble(), o.aphelionRing * 0.02
            )
        }
    }

    /** Nothing is ever drawn outside the case. */
    @Test
    fun `no comet goes further out than its own far end`() {
        for (comet in Comets.all) {
            val o = Comets.orbitOf(comet)
            val period = (o.periodYears * 365.25 * DAY).toLong()
            for (step in 0 until 400) {
                val r = Comets.positionAt(comet, o.perihelionMs + period * step / 400).radius
                assertTrue(
                    "$comet reached $r, past its far end of ${o.aphelionRing}",
                    r <= o.aphelionRing * 1.001f
                )
            }
        }
    }

    /**
     * The whole point: a comet hurries through the inner system and
     * dawdles at the far end.
     *
     * Measured rather than asserted about, because it is exactly what an
     * even angle round an oval would get wrong while still producing a
     * position on the right curve at the right two dates. Halley's orbit
     * is 0.967 eccentric, so it should spend a few per cent of its
     * seventy-five years inside the near half of its own range and nearly
     * all of the rest beyond it.
     */
    @Test
    fun `a comet spends almost none of its life near the sun`() {
        val comet = Comets.Comet.HALLEY
        val o = Comets.orbitOf(comet)
        val period = (o.periodYears * 365.25 * DAY).toLong()
        val steps = 2000
        val half = o.aphelionRing / 2f
        val inside = (0 until steps).count {
            Comets.positionAt(comet, o.perihelionMs + period * it / steps).radius < half
        }
        val share = inside.toFloat() / steps
        assertTrue(
            "Halley spends ${(share * 100).toInt()}% of its orbit in the inner half, " +
                "which is a planet's life and not a comet's",
            share < 0.20f
        )
        assertTrue("Halley never comes into the inner half at all", share > 0.01f)
    }

    // --------------------------------------------------------- the dates

    /** The next return of each comet is the one that has been predicted. */
    @Test
    fun `each comet comes back when it is expected to`() {
        val expected = mapOf(
            Comets.Comet.ENCKE to at(2023, 10, 22),
            Comets.Comet.TEMPEL_TUTTLE to at(2031, 5, 20),
            Comets.Comet.HALLEY to at(2061, 7, 28),
            Comets.Comet.SWIFT_TUTTLE to at(2126, 7, 16)
        )
        for ((comet, want) in expected) {
            val got = Comets.nextPerihelion(comet, Comets.orbitOf(comet).perihelionMs + DAY)
            assertEquals(
                "$comet is predicted back on ${java.util.Date(got)}, not ${java.util.Date(want)}",
                0.0, (got - want).toDouble() / DAY, 2.0
            )
        }
    }

    /**
     * Winding backwards asks for the visit you are standing in, not the
     * one after it.
     *
     * Somebody who has wound the sky to 1910 is looking at Halley in 1910.
     * Being told the next one is in 1986 is true and useless.
     */
    @Test
    fun `the date offered is the visit being looked at`() {
        val nineteenTen = Comets.nearestPerihelion(Comets.Comet.HALLEY, at(1910, 6, 1))
        assertEquals(
            "the sky wound to 1910 is naming some other visit",
            1910, yearOf(nineteenTen)
        )
        val today = Comets.nearestPerihelion(Comets.Comet.HALLEY, at(2026, 1, 1))
        assertTrue(
            "standing halfway between two visits it named neither",
            yearOf(today) == 1986 || yearOf(today) == 2061
        )
        // The year after a visit is the case that separates "nearest" from
        // "next", and 1910 does not: a comet has just gone, and the one
        // being looked at is the one that has just gone. Without this the
        // whole test passes with "nearest" replaced by "the one after",
        // because 1910 rounds and ceilings to the same visit.
        assertEquals(
            "eleven months after a visit it is already announcing the next one, " +
                "seventy-five years out",
            1986, yearOf(Comets.nearestPerihelion(Comets.Comet.HALLEY, at(1987, 1, 1)))
        )
    }

    /** A comet gets its name under the dial only when it is actually here. */
    @Test
    fun `a comet is named only around its own visit`() {
        assertEquals(
            "Halley is not named on the day it comes closest",
            Comets.Comet.HALLEY, Comets.visiting(at(1986, 2, 9))
        )
        assertEquals(
            "Halley is still being announced a month and a half after it left",
            null, Comets.visiting(at(1986, 6, 1))
        )
    }

    /**
     * And it is not named most of the time.
     *
     * Encke comes round every three years and three months, so a window
     * wide enough to be generous would put its name under the dial for
     * most of history — which would make the caption a fixture rather than
     * a remark, and hide the eclipses and the full moons behind it.
     */
    @Test
    fun `most days have no comet to announce`() {
        var named = 0
        val from = at(2000, 1, 1)
        for (day in 0 until 3650) {
            if (Comets.visiting(from + day * DAY) != null) named++
        }
        assertTrue(
            "a comet is named on ${named * 100 / 3650}% of days, which is a fixture",
            named * 100 / 3650 < 25
        )
        assertTrue("no comet is ever named", named > 0)
    }

    /** The tail arrives with the visit and is gone the rest of the time. */
    @Test
    fun `the tail grows as the visit comes on`() {
        val comet = Comets.Comet.HALLEY
        val onTheDay = Comets.nearness(comet, at(1986, 2, 9))
        val monthsOut = Comets.nearness(comet, at(1985, 8, 9))
        val decadesOut = Comets.nearness(comet, at(2010, 2, 9))
        assertTrue("no tail at perihelion", onTheDay > 0.95f)
        assertTrue("a comet six months out has no tail at all", monthsOut > 0.01f)
        assertTrue(
            "the tail is the same six months out as it is at perihelion, " +
                "so it is not growing at all",
            monthsOut < onTheDay * 0.9f
        )
        assertEquals("a comet halfway out still has a tail", 0f, decadesOut, 0.0001f)
    }

    // -------------------------------------------------------- the names

    /** Every comet has a name, and they are all different. */
    @Test
    fun `the four are four`() {
        val keys = Comets.all.map { Comets.nameKeyOf(it) }
        assertEquals("two comets share a name", 4, keys.toSet().size)
        assertEquals("a comet is missing from the list", 4, Comets.all.size)
    }

    /** A position is a real number, at every date, for every comet. */
    @Test
    fun `the arithmetic never gives up`() {
        for (comet in Comets.all) {
            val o = Comets.orbitOf(comet)
            val period = (o.periodYears * 365.25 * DAY).toLong()
            for (step in -100..100) {
                val where = Comets.positionAt(comet, o.perihelionMs + period * step / 97)
                assertNotNull("$comet has no position at step $step", where)
                assertTrue(
                    "$comet came out as ${where.radius} at step $step",
                    where.radius.isFinite() && where.radius >= 0f
                )
                assertTrue(
                    "$comet came out pointing at ${where.longitude}",
                    where.longitude.isFinite() && where.longitude in 0.0..360.0
                )
            }
        }
    }

    /** Nothing that is not a comet has an orbit. */
    @Test
    fun `the visitors are the four and no others`() {
        assertNull("something was named on a day nobody visits", Comets.visiting(at(2005, 3, 3)))
    }

    // -------------------------------------------------- how far this is worth

    /**
     * A comet's period is not a constant, and the arithmetic here does not
     * pretend otherwise for five thousand years.
     *
     * Halley's recorded returns are anywhere from 74.4 years apart to
     * 79.1, and counting whole orbits from a known passage picks up a
     * return's worth of that error every time round. Near the pinned
     * passage the answer is as good as it gets; forty returns out it is
     * wrong by a quarter of the orbit, which means the dot on the glass is
     * not a position at all.
     */
    @Test
    fun `a comet is certain near its own passage and gone far from it`() {
        for (comet in Comets.all) {
            val o = Comets.orbitOf(comet)
            assertEquals(
                "$comet is not trusted on the day it was actually seen",
                1f, Comets.trust(comet, o.perihelionMs), 1e-4f
            )
            assertEquals(
                "$comet survives being wound to the Bronze Age",
                0f, Comets.trust(comet, at(-4000, 6, 15)), 1e-6f
            )
        }
    }

    /**
     * And it fades rather than switching off.
     *
     * A comet that vanished between one frame and the next while the sky
     * was being wound would read as a fault; a comet going quietly out is
     * the drawing saying it no longer knows.
     */
    @Test
    fun `the certainty falls away smoothly`() {
        var last = 1f
        var moved = 0
        for (century in 0..30) {
            val t = Comets.trust(Comets.Comet.HALLEY, at(1986 - century * 100, 2, 9))
            assertTrue("Halley got surer the further back it was wound", t <= last + 1e-6f)
            if (t < last - 1e-6f) moved++
            last = t
        }
        assertTrue("the certainty never moved at all, so it is not a fade", moved > 10)
        assertEquals("Halley never runs out", 0f, last, 1e-6f)
    }

    /**
     * The four run out at four different distances, and in the right
     * order.
     *
     * Encke goes round every three years and three months, so it burns
     * through returns quickly however steady each one is; Swift-Tuttle's
     * are a century and a third apart. A single horizon for all four would
     * be a number picked to look reasonable rather than anything the
     * comets said.
     */
    @Test
    fun `the four run out at four different distances`() {
        val inYears = Comets.all.associateWith { Comets.rangeYears(it) }
        assertEquals(
            "something other than Encke is the first to go",
            Comets.Comet.ENCKE, inYears.minByOrNull { it.value }!!.key
        )
        assertEquals(
            "something other than Swift-Tuttle lasts longest",
            Comets.Comet.SWIFT_TUTTLE, inYears.maxByOrNull { it.value }!!.key
        )
        // And the horizons counted in *returns* rather than years are not
        // the same number either, which is the claim with teeth in it. A
        // single wander figure shared by all four, or one taken as a fixed
        // share of each period, would leave every comet good for the same
        // number of trips round — and the whole point is that Halley comes
        // away from the giant planets far more changed than Encke does, so
        // it runs out of certainty in a third as many visits.
        val inReturns = Comets.all.associateWith {
            Comets.rangeYears(it) / Comets.orbitOf(it).periodYears
        }
        assertTrue(
            "Halley survives as many returns as Encke, which means the " +
                "wander is one figure wearing four hats: $inReturns",
            inReturns.getValue(Comets.Comet.HALLEY) <
                inReturns.getValue(Comets.Comet.ENCKE) * 0.5
        )
        // And the horizon is where the arithmetic actually gives out,
        // which is nearer than it feels like it should be. Halley's first
        // predicted return, 1759, is three orbits back and still good;
        // 1066 is twelve orbits back and the fixed period has drifted
        // fourteen years by then — the model really does put the Bayeux
        // comet in 1080. Both of these were checked against the recorded
        // returns rather than reasoned about.
        assertTrue(
            "Halley is not trusted at the first return anybody predicted",
            Comets.trust(Comets.Comet.HALLEY, at(1759, 3, 13)) > 0.7f
        )
        assertTrue(
            "the dial claims to know where Halley was in 1066, and it does " +
                "not — a fixed period puts that return fourteen years late",
            Comets.trust(Comets.Comet.HALLEY, at(1066, 3, 20)) < 0.5f
        )
    }

    /**
     * And a comet nobody can date is not named under the dial.
     *
     * Six weeks either side of perihelion is a claim about a fortnight in
     * a given year. Made about a passage the arithmetic has drifted by
     * decades it is a made-up date said with a straight face, which is
     * worse than saying nothing.
     */
    @Test
    fun `a comet the arithmetic cannot date is not named`() {
        // A perihelion the model computes for a year it cannot vouch for:
        // the date is still returned, and the caption still refuses it.
        val far = Comets.nearestPerihelion(Comets.Comet.HALLEY, at(-3000, 6, 15))
        assertTrue(
            "the trust in Halley three thousand years back is not low",
            Comets.trust(Comets.Comet.HALLEY, far) < 0.5f
        )
        assertNull(
            "the sky named a comet on a date it invented",
            Comets.visiting(far)
        )
        // And on a passage it can vouch for, it still says the name, or
        // the assertion above is only saying that comets are never named.
        assertEquals(
            "no comet is named on a real perihelion date",
            Comets.Comet.HALLEY,
            Comets.visiting(Comets.orbitOf(Comets.Comet.HALLEY).perihelionMs)
        )
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
