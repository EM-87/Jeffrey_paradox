package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the needle points and how full the tube is.
 *
 * Both are claims about the weather made in the shape of a picture, which
 * is the kind of claim that goes wrong quietly: a needle a third of a turn
 * out still looks like a needle. So the arithmetic lives away from the
 * drawing and is checked here, at the ends of both scales and outside
 * them — because the interesting readings are the ones a garden instrument
 * cannot show, and what it does with those is the whole of whether it is
 * honest.
 */
class WeatherGlassTest {

    /**
     * Each of the five words is chosen at the pressure it was engraved at.
     *
     * The list is in order and the reading is the nearest mark, so this
     * also says the scale has not been reversed — the failure where a
     * deep low reads VERY DRY, which would be a barometer that is exactly
     * as useful as no barometer.
     */
    @Test
    fun `the needle finds the word it was engraved under`() {
        for (i in WeatherGlass.MARKS.indices) {
            assertEquals(
                "the mark at ${WeatherGlass.MARKS[i]} hPa did not read as its own word",
                i, WeatherGlass.legend(WeatherGlass.MARKS[i])
            )
        }
        // And a storm reads as one, at either end and past both.
        assertEquals(0, WeatherGlass.legend(900.0))
        assertEquals(WeatherGlass.MARKS.size - 1, WeatherGlass.legend(1100.0))
    }

    /**
     * Halfway between two marks it takes the lower one, and a hair past
     * takes the upper.
     *
     * Not a rule anybody would notice, and the reason to pin it is that
     * "nearest" and "the band it falls in" agree everywhere except here,
     * so this is the one reading that says which of the two was written.
     */
    @Test
    fun `the halfway point does not wobble`() {
        val between = (WeatherGlass.MARKS[1] + WeatherGlass.MARKS[2]) / 2.0
        assertEquals(1, WeatherGlass.legend(between))
        assertEquals(2, WeatherGlass.legend(between + 0.5))
    }

    /**
     * The arc runs left to right, and a pressure off the end of it puts
     * the needle against the stop rather than off the dial.
     *
     * 940 hPa is a real thing that happens in a deep Atlantic low, and a
     * needle drawn past the end of its own arc is a picture of nothing.
     */
    @Test
    fun `the swing is a fraction of the arc and never leaves it`() {
        assertEquals(0f, WeatherGlass.swing(WeatherGlass.LOW_HPA), 0.0001f)
        assertEquals(1f, WeatherGlass.swing(WeatherGlass.HIGH_HPA), 0.0001f)
        assertEquals(0.5f, WeatherGlass.swing(1000.0), 0.0001f)
        assertEquals("a deep low ran off the end", 0f, WeatherGlass.swing(910.0), 0.0001f)
        assertEquals(1f, WeatherGlass.swing(1090.0), 0.0001f)
    }

    /** And the tube, the same way: empty at the bottom mark, full at the top. */
    @Test
    fun `the column stands where the temperature is`() {
        assertEquals(0f, WeatherGlass.column(WeatherGlass.LOW_C), 0.0001f)
        assertEquals(1f, WeatherGlass.column(WeatherGlass.HIGH_C), 0.0001f)
        assertEquals(0.5f, WeatherGlass.column(15.0), 0.0001f)
        assertEquals("a Siberian morning emptied the tube past the bulb",
            0f, WeatherGlass.column(-45.0), 0.0001f)
        assertEquals(1f, WeatherGlass.column(60.0), 0.0001f)
        // Freezing is a third of the way up this particular tube, which is
        // worth writing down: it is the one reading anybody checks by eye.
        assertEquals(0.2857f, WeatherGlass.column(0.0), 0.0005f)
    }

    /**
     * The marks up the tube are every ten degrees and none of them is
     * outside it.
     *
     * A tick above the top of the glass is a tick drawn on the plate
     * behind it, which is how a scale stops looking like it belongs to the
     * thing it is on.
     */
    @Test
    fun `the ticks are every ten degrees and all of them fit`() {
        val ticks = WeatherGlass.ticks()
        assertEquals("−20 to 50 in tens is eight marks", 8, ticks.size)
        assertEquals(0f, ticks.first(), 0.0001f)
        assertEquals(1f, ticks.last(), 0.0001f)
        for (tick in ticks) assertTrue("a tick at $tick is off the tube", tick in 0f..1f)
        // Evenly spaced, which is the other half of "it is a scale".
        for (i in 1 until ticks.size) {
            assertEquals(1f / 7f, ticks[i] - ticks[i - 1], 0.0005f)
        }
    }

    /**
     * Both instruments or neither.
     *
     * One brass instrument on a pedestal with an empty bracket beside it
     * reads as a broken pedestal. The two quantities come out of the same
     * three services in the same request, so exactly one missing is a
     * service answering half a question — not a thermometer that has
     * fallen off.
     */
    @Test
    fun `the pedestal is empty unless both instruments have a reading`() {
        val both = Weather.Sky(
            temperatureC = Weather.Agreed(18.0, Weather.Trust.AGREED),
            pressureHpa = Weather.Agreed(1013.0, Weather.Trust.AGREED),
            answered = 3
        )
        assertTrue(WeatherGlass.readable(both))
        assertFalse(WeatherGlass.readable(both.copy(pressureHpa = Weather.Agreed.NOTHING)))
        assertFalse(WeatherGlass.readable(both.copy(temperatureC = Weather.Agreed.NOTHING)))
        assertFalse("an empty sky put two needles on the dial",
            WeatherGlass.readable(Weather.Sky()))
    }

    /**
     * A reading nobody confirmed is drawn faintly, the same as the sky
     * token's weather.
     *
     * Shown, because a lone reading is what is left on the day the other
     * services are down and that is the day the whole design exists for.
     * Shown differently, because a number nobody has checked is not the
     * same object as one two services measured — and a needle is exactly
     * the kind of drawing that claims more than it knows.
     */
    @Test
    fun `an unconfirmed reading is drawn faint`() {
        fun sky(t: Weather.Trust, p: Weather.Trust) = Weather.Sky(
            temperatureC = Weather.Agreed(18.0, t),
            pressureHpa = Weather.Agreed(1013.0, p),
            answered = 1
        )
        assertEquals(
            WeatherGlass.SURE,
            WeatherGlass.ink(sky(Weather.Trust.AGREED, Weather.Trust.AGREED))
        )
        assertEquals(
            "one lone reading did not take the pair down with it",
            WeatherGlass.UNSURE,
            WeatherGlass.ink(sky(Weather.Trust.LONE, Weather.Trust.AGREED))
        )
        assertEquals(
            WeatherGlass.UNSURE,
            WeatherGlass.ink(sky(Weather.Trust.AGREED, Weather.Trust.LONE))
        )
        assertTrue("faint is not fainter than solid", WeatherGlass.UNSURE < WeatherGlass.SURE)
    }
}
