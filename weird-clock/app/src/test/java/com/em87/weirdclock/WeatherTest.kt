package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather, agreed rather than looked up.
 *
 * The claim being tested is not "does it parse a forecast". It is the one
 * the owner of this app actually made: whichever service you ask, it may
 * be gone tomorrow — so ask several, keep what they agree on, and losing
 * some of them should cost confidence and not the reading.
 *
 * Which means the cases worth writing are the failures. One service lying,
 * one service down, all but one down, two services disagreeing so badly
 * that the honest answer is silence. A test that only checks three sources
 * agreeing is a test of the easy day.
 */
class WeatherTest {

    private val now = 1_700_000_000_000L

    private fun reading(
        source: String,
        temperature: Double? = null,
        pressure: Double? = null,
        cloud: Double? = null,
        rain: Double? = null,
        wind: Double? = null,
        thunder: Boolean? = null,
        agoMs: Long = 0L
    ) = Weather.Reading(
        source, now - agoMs, temperature, pressure, cloud, rain, wind, thunder
    )

    /**
     * One service reporting nonsense does not move the answer at all.
     *
     * This is the whole reason there is no average in this file. Twenty,
     * twenty and forty average to twenty-six point seven — a temperature
     * nobody measured, nowhere near the truth, and drawn on the clock with
     * exactly as much confidence as a correct one.
     */
    @Test
    fun `a broken service cannot drag the reading anywhere`() {
        val sky = Weather.agree(
            listOf(
                reading("a", temperature = 20.0),
                reading("b", temperature = 20.4),
                reading("c", temperature = 40.0)
            ),
            now
        )
        assertEquals(20.2, sky.temperatureC.value!!, 0.001)
        assertEquals(Weather.Trust.AGREED, sky.temperatureC.trust)
        // And the mean, which is what this refuses to be.
        assertTrue("that is suspiciously close to the average", sky.temperatureC.value!! < 25.0)
    }

    /**
     * The day two of the three are down is the day this design is for.
     *
     * The reading survives, and it says out loud that nothing confirmed
     * it — which is a different claim from an agreed one and the clock
     * draws it differently.
     */
    @Test
    fun `one service left still tells you the weather, and says so`() {
        val sky = Weather.agree(listOf(reading("a", temperature = 18.0, cloud = 90.0)), now)
        assertEquals(18.0, sky.temperatureC.value!!, 0.001)
        assertEquals(Weather.Trust.LONE, sky.temperatureC.trust)
        assertEquals(1, sky.answered)
        assertTrue(sky.known)
        // Two make it an agreement.
        val two = Weather.agree(
            listOf(reading("a", temperature = 18.0), reading("b", temperature = 18.9)),
            now
        )
        assertEquals(Weather.Trust.AGREED, two.temperatureC.trust)
    }

    /** Everybody down is silence, not a guess. */
    @Test
    fun `nobody answering is nothing, and nothing is a state`() {
        val sky = Weather.agree(emptyList(), now)
        assertEquals(0, sky.answered)
        assertNull(sky.temperatureC.value)
        assertEquals(Weather.Trust.NONE, sky.temperatureC.trust)
        assertTrue("silence is being drawn as weather", !sky.known)
        assertNull(Weather.look(sky))
    }

    /**
     * A service that answered this morning and has been down since is not
     * a service that agrees with anything.
     */
    @Test
    fun `a stale answer is not an answer`() {
        val old = Weather.agree(
            listOf(reading("a", temperature = 5.0, agoMs = Weather.FRESH_MS + 60_000L)),
            now
        )
        assertEquals(0, old.answered)
        assertNull(old.temperatureC.value)
        // Just inside the window it still counts. Weather changes slowly
        // and networks fail often; an old reading beats no reading.
        val nearly = Weather.agree(
            listOf(reading("a", temperature = 5.0, agoMs = Weather.FRESH_MS - 60_000L)),
            now
        )
        assertEquals(1, nearly.answered)
        // And a reading from the future is not one either — a clock that
        // has been wound forward must not believe yesterday's cache.
        val ahead = Weather.agree(listOf(reading("a", temperature = 5.0, agoMs = -60_000L)), now)
        assertEquals(0, ahead.answered)
    }

    /**
     * Two services that disagree by more than the weather ever does leave
     * one of them standing, not the midpoint of the argument.
     *
     * With two readings and no third to break the tie, the median is the
     * midpoint — and the tolerance then throws *both* of them out, because
     * neither is within two degrees of a number halfway between them. What
     * is left is nothing, and nothing is the honest answer to "one of these
     * two is broken and I cannot tell which".
     */
    @Test
    fun `two services in flat contradiction agree on nothing`() {
        val sky = Weather.agree(
            listOf(reading("a", temperature = 2.0), reading("b", temperature = 30.0)),
            now
        )
        assertNull("it picked a side, or split the difference", sky.temperatureC.value)
        assertEquals(Weather.Trust.NONE, sky.temperatureC.trust)
        // But it did hear from both, which is a different fact.
        assertEquals(2, sky.answered)
    }

    /**
     * A service that only answers half the questions is still worth
     * asking.
     *
     * They really are like this: one has no pressure, another has no
     * lightning, a third added rain last year. A reading is agreed field
     * by field, so a gap in one service is a gap in one field.
     */
    @Test
    fun `each field is agreed on its own`() {
        val sky = Weather.agree(
            listOf(
                reading("a", temperature = 12.0, pressure = 1013.0),
                reading("b", temperature = 12.5, cloud = 40.0),
                reading("c", cloud = 45.0, rain = 0.0)
            ),
            now
        )
        assertEquals(Weather.Trust.AGREED, sky.temperatureC.trust)
        // Only one service carries a pressure, so it is lone rather than
        // absent: still shown, and shown as unconfirmed.
        assertEquals(Weather.Trust.LONE, sky.pressureHpa.trust)
        assertEquals(1013.0, sky.pressureHpa.value!!, 0.001)
        assertEquals(Weather.Trust.AGREED, sky.cloudPercent.trust)
        assertEquals(42.5, sky.cloudPercent.value!!, 0.001)
        assertEquals(3, sky.answered)
    }

    /**
     * Lightning is a vote, and a tie is not an answer.
     *
     * Two services saying there is a storm and two saying there is not is
     * exactly the moment a clock should draw nothing rather than pick.
     */
    @Test
    fun `lightning is a vote and a tie draws nothing`() {
        fun thunderOf(vararg votes: Boolean) = Weather.agree(
            votes.mapIndexed { i, v -> reading("s$i", thunder = v) }, now
        ).thunder
        assertEquals(true, thunderOf(true, true, false))
        assertEquals(false, thunderOf(false, false, true))
        assertNull("it broke a tie it had no way of breaking", thunderOf(true, false))
        assertNull(thunderOf(true, true, false, false))
        assertNull(Weather.agree(emptyList<Boolean>()))
    }

    /**
     * The middle value, checked on its own, including the even case.
     *
     * The median is the only load-bearing arithmetic in this file and it
     * is four lines, which is exactly the sort of thing that is wrong for
     * a year.
     */
    @Test
    fun `the middle value is the middle value`() {
        assertEquals(2.0, Weather.median(listOf(1.0, 2.0, 3.0))!!, 0.001)
        assertEquals(2.5, Weather.median(listOf(1.0, 2.0, 3.0, 4.0))!!, 0.001)
        assertEquals(7.0, Weather.median(listOf(7.0))!!, 0.001)
        // Order cannot matter.
        assertEquals(2.0, Weather.median(listOf(3.0, 1.0, 2.0))!!, 0.001)
        assertNull(Weather.median(emptyList()))
    }

    /**
     * What a person standing outside would say first.
     *
     * The order is the point: lightning beats rain, rain beats cloud, and
     * one cloud on a blue sky is a clear day.
     */
    @Test
    fun `the sky gets one word, and the worst thing in it wins`() {
        fun look(vararg r: Weather.Reading) = Weather.look(Weather.agree(r.toList(), now))
        assertEquals(
            Weather.Look.STORM,
            look(reading("a", cloud = 90.0, rain = 4.0, thunder = true))
        )
        assertEquals(Weather.Look.RAIN, look(reading("a", cloud = 90.0, rain = 4.0)))
        assertEquals(Weather.Look.OVERCAST, look(reading("a", cloud = 90.0, rain = 0.0)))
        assertEquals(Weather.Look.CLOUDY, look(reading("a", cloud = 60.0)))
        assertEquals(Weather.Look.CLEAR, look(reading("a", cloud = 10.0)))
        // A trace of rain is not rain: a clock that draws a raincloud
        // because a model said a hundredth of a millimetre is a clock
        // nobody believes twice.
        assertEquals(Weather.Look.CLEAR, look(reading("a", cloud = 5.0, rain = 0.05)))
        // And a service that only knows the temperature says nothing about
        // the sky rather than claiming it is clear.
        assertEquals(Weather.Look.CLEAR, look(reading("a", temperature = 20.0)))
    }

    /**
     * The reading is stamped with the freshest thing in it, not the
     * stalest.
     *
     * Which is what anything drawing an age off it wants: "as of" the last
     * time anybody answered.
     */
    @Test
    fun `the sky is dated by its freshest source`() {
        val sky = Weather.agree(
            listOf(
                reading("a", temperature = 10.0, agoMs = 60L * 60L * 1000L),
                reading("b", temperature = 10.2, agoMs = 5L * 60L * 1000L)
            ),
            now
        )
        assertEquals(now - 5L * 60L * 1000L, sky.atMs)
        assertNotNull(sky.temperatureC.value)
    }
}
