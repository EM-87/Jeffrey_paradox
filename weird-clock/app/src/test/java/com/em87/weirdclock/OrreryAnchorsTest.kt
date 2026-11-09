package com.em87.weirdclock

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The eight planets, held against JPL Horizons across five millennia.
 *
 * Every number here was asked of Horizons (ssd.jpl.nasa.gov/api/horizons
 * .api, vector table, heliocentric, ecliptic of J2000, times in TDB) and
 * copied, not worked out: the longitude of each planet — the barycentre
 * of each giant planet's system, which is what Standish's elements
 * describe — at six instants from the start of 3000 BC to the start of
 * 3000 AD, the ends of the long table's validity and four points
 * between. The instants are given in the orbits' own time, so the test
 * hands the orrery the civil instant that ΔT turns back into them and
 * ΔT cancels out of the comparison.
 *
 * The allowances are twice the nominal errors Standish quotes for the
 * long table — 20″ for Mercury up to 2000″ for Uranus — with a floor of
 * a twentieth of a degree, which is a tenth of a planet's dot.
 */
class OrreryAnchorsTest {

    /** A TDB instant as a Julian Day, turned into the civil instant that ΔT maps back onto it. */
    private fun civilOf(jd: Double): Long {
        val ttMs = Math.round((jd - 2_440_587.5) * 86_400_000.0)
        return ttMs - Math.round(DeltaT.secondsAt(ttMs) * 1000.0)
    }

    private val instants = listOf(
        "3000 BC" to 625_307.5, "1000" to 2_086_307.5, "1715" to 2_347_572.5,
        "J2000" to 2_451_545.0, "2026" to 2_461_349.5, "3000" to 2_816_787.5
    )

    /** Horizons' heliocentric J2000 ecliptic longitudes, in the order of [instants]. */
    private val horizons = mapOf(
        Orrery.Body.MERCURY to doubleArrayOf(262.96048, 283.68872, 273.44014, 253.78293, 44.19726, 238.04073),
        Orrery.Body.VENUS to doubleArrayOf(30.20577, 23.10669, 284.78144, 182.60290, 49.34098, 347.70145),
        Orrery.Body.EARTH to doubleArrayOf(145.92492, 119.72097, 225.83444, 100.37782, 42.20928, 86.17291),
        Orrery.Body.MARS to doubleArrayOf(216.87070, 124.16232, 220.44913, 359.44728, 102.32226, 224.45448),
        Orrery.Body.JUPITER to doubleArrayOf(208.36595, 281.19583, 37.71116, 36.29460, 133.91459, 147.12568),
        Orrery.Body.SATURN to doubleArrayOf(152.73970, 66.58276, 176.74671, 45.72223, 12.00011, 25.63235),
        Orrery.Body.URANUS to doubleArrayOf(126.85979, 349.17467, 173.91919, 316.41864, 63.05365, 284.28702),
        Orrery.Body.NEPTUNE to doubleArrayOf(181.29555, 279.13741, 42.93157, 303.92886, 2.86027, 327.83717)
    )

    /** Standish's nominal errors for 3000 BC – 3000 AD, in arcseconds, doubled, floored. */
    private fun allowance(body: Orrery.Body): Double {
        val arcsec = when (body) {
            Orrery.Body.MERCURY -> 20.0
            Orrery.Body.VENUS -> 40.0
            Orrery.Body.EARTH -> 40.0
            Orrery.Body.MARS -> 100.0
            Orrery.Body.JUPITER -> 600.0
            Orrery.Body.SATURN -> 1000.0
            Orrery.Body.URANUS -> 2000.0
            Orrery.Body.NEPTUNE -> 400.0
            Orrery.Body.MOON -> 0.0
        }
        return maxOf(0.05, 2 * arcsec / 3600.0)
    }

    @Test
    fun `every planet stands where Horizons has it, from 3000 BC to 3000 AD`() {
        val wrong = ArrayList<String>()
        for (body in Orrery.planets) {
            val expected = horizons.getValue(body)
            val line = StringBuilder("ANCHORS $body:")
            for ((i, instant) in instants.withIndex()) {
                val (name, jd) = instant
                val lon = Orrery.longitude(body, civilOf(jd))
                val off = abs(Orrery.wrapSigned(lon - expected[i]))
                line.append(" $name %.3f°".format(off))
                if (off >= allowance(body)) {
                    wrong += "$body at $name: the orrery reads %.4f°, Horizons %.4f°, off %.3f° (allowed %.3f°)".format(
                        lon, expected[i], off, allowance(body)
                    )
                }
            }
            println(line)
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}
