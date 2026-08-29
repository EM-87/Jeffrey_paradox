package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the digital face says, before anything is drawn.
 *
 * Nearly every complaint anybody has ever had about a digital clock is
 * arithmetic and not drawing: midnight showing as 0 on a twelve-hour face,
 * the nought going missing from the minutes and not the hours, a Roman
 * clock with a gap where a nought belongs. None of that needs a screen to
 * catch, so none of it is behind one.
 */
class DigitalReadoutTest {

    private fun say(cells: List<Cell>): String = cells.joinToString("") {
        when (it) {
            is Cell.Number -> it.text
            Cell.Colon -> ":"
            Cell.Slash -> "/"
            is Cell.Token -> if (it.sun) "☀" else "☾"
        }
    }

    private fun at(
        hour: Int, minute: Int, second: Int = 0,
        script: DigitScript = DigitScript.ARABIC,
        hour24: Boolean = true,
        leadingZero: Boolean = true,
        seconds: Boolean = false
    ): String = say(
        DigitalReadout.time(
            hour, minute, second,
            DigitalReadout.Options(script, hour24, leadingZero, seconds)
        )
    )

    @Test
    fun `the ordinary case`() {
        assertEquals("07:05", at(7, 5))
        assertEquals("23:59", at(23, 59))
        assertEquals("00:00", at(0, 0))
        assertEquals("07:05:09", at(7, 5, 9, seconds = true))
    }

    /**
     * The nought in front is a question about the hour and nothing else.
     * Nobody writes eight minutes past as 7:8, and a switch that took the
     * nought off the minutes too would be reading its own name too
     * literally.
     */
    @Test
    fun `the nought in front comes off the hour alone`() {
        assertEquals("7:05", at(7, 5, leadingZero = false))
        assertEquals("7:05:09", at(7, 5, 9, leadingZero = false, seconds = true))
        assertEquals("0:00", at(0, 0, leadingZero = false))
        assertEquals("23:59", at(23, 59, leadingZero = false))
    }

    /**
     * Midnight is twelve, not nought, and one in the afternoon is one.
     *
     * The arithmetic that catches people out is the wrap at both ends: a
     * remainder gives 0 o'clock at midnight and 0 o'clock again at noon,
     * and every twelve-hour clock ever made says twelve at both.
     */
    @Test
    fun `a twelve-hour face says twelve at both ends of the day`() {
        assertEquals("12:00☾", at(0, 0, hour24 = false, leadingZero = false))
        assertEquals("12:00☀", at(12, 0, hour24 = false, leadingZero = false))
        assertEquals("1:30☀", at(13, 30, hour24 = false, leadingZero = false))
        assertEquals("11:59☾", at(11, 59, hour24 = false, leadingZero = false))
        for (hour in 0..23) {
            assertTrue("$hour o'clock", DigitalReadout.twelveOf(hour) in 1..12)
        }
    }

    /**
     * The sun stands for the half of the day it is over the middle of, so
     * the token is right at the two moments anybody checks.
     */
    @Test
    fun `the sun takes the afternoon and the moon the morning`() {
        for (hour in 0..11) {
            assertTrue("$hour o'clock is the moon's", at(hour, 0, hour24 = false).endsWith("☾"))
        }
        for (hour in 12..23) {
            assertTrue("$hour o'clock is the sun's", at(hour, 0, hour24 = false).endsWith("☀"))
        }
    }

    /** And a twenty-four hour face has nothing for it to say. */
    @Test
    fun `a twenty-four hour face carries no token`() {
        for (hour in 0..23) {
            val cells = DigitalReadout.time(hour, 0, 0, DigitalReadout.Options(hour24 = true))
            assertEquals(
                "$hour o'clock is carrying a sun or a moon it does not need",
                0, cells.count { it is Cell.Token }
            )
        }
    }

    /**
     * Rome had no nought and a clock has to write one twice a day.
     *
     * N is not an invention: it is what Rome's own computists put in that
     * column, short for *nulla*. The alternative was an empty space, which
     * on a display reads as a fault rather than as a number.
     */
    @Test
    fun `Rome writes its nothing`() {
        assertEquals("N", DigitalReadout.roman(0))
        assertEquals("MMXXVI", DigitalReadout.roman(2026))
        // Rome does not write the *time* any more — see [CometPanel]. It
        // writes the date on a rail, where a nought never comes up, and
        // the letter stays because the function is still the one place
        // that knows Rome has no numeral for nothing.
    }

    /**
     * Every script splits its numbers into digits now, because every one
     * of them writes the time with our ten.
     *
     * There used to be an exception: Rome's numerals were the time, and
     * the second digit of XLVII is not a thing, so the whole group was one
     * uncuttable cell that no drum could turn. Rome writes the date now
     * and the exception went with it — which means the drums work on every
     * script there is, and setting a time by rolling it is no longer a
     * thing two of the four could not do.
     */
    @Test
    fun `every script splits its numbers into digits`() {
        for (script in DigitScript.entries) {
            val cells = DigitalReadout.time(
                23, 59, 0, DigitalReadout.Options(script = script)
            )
            assertEquals("$script", 5, cells.size)
            assertEquals("$script", 4, cells.count { it is Cell.Number })
        }
    }

    /**
     * The drums only go as far round as the number they carry can.
     *
     * A roller showing the tens of an hour has three faces on it, not ten:
     * there is no 94 o'clock, and a drum that offers one is a drum that has
     * not been told what it is counting.
     */
    @Test
    fun `each drum goes as far as its number does and no further`() {
        fun ceilings(cells: List<Cell>) = cells.filterIsInstance<Cell.Number>().map { it.of }
        assertEquals(
            "hours to 23, minutes to 59",
            listOf(2, 9, 5, 9), ceilings(DigitalReadout.time(9, 30, 0, DigitalReadout.Options()))
        )
        assertEquals(
            "a twelve-hour face never shows a 2 in front",
            listOf(1, 9, 5, 9),
            ceilings(DigitalReadout.time(9, 30, 0, DigitalReadout.Options(hour24 = false)))
        )
    }

    @Test
    fun `the date is written the way the phone writes dates`() {
        val options = DigitalReadout.Options()
        assertEquals("27/08/2026", say(DigitalReadout.date(27, 8, 2026, true, options)))
        assertEquals("08/27/2026", say(DigitalReadout.date(27, 8, 2026, false, options)))
        // And the same on every script, because the date line is our ten
        // digits wherever it appears. The panel does not use this at all:
        // its date is two rails of Rome's module — see [CometPanel].
        for (script in DigitScript.entries) {
            assertEquals(
                "$script",
                "27/08/2026",
                say(
                    DigitalReadout.date(
                        27, 8, 2026, true,
                        DigitalReadout.Options(script = script)
                    )
                )
            )
        }
    }

    /** The year is one cell however long it is: nobody rolls a millennium. */
    @Test
    fun `the year is not a row of drums`() {
        val cells = DigitalReadout.date(1, 1, 2026, true, DigitalReadout.Options())
        assertEquals("2026", (cells.last() as Cell.Number).text)
        assertEquals("and it turns nowhere", 0, (cells.last() as Cell.Number).of)
    }

    /** Theirs is a font, so it lays out exactly as ours does. */
    @Test
    fun `the alien numerals sit where ours do`() {
        assertEquals(
            say(DigitalReadout.time(7, 5, 0, DigitalReadout.Options())),
            say(DigitalReadout.time(7, 5, 0, DigitalReadout.Options(script = DigitScript.YAUTJA)))
        )
    }
}
