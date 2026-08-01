package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that six surfaces now share.
 *
 * Worth pinning precisely because it is shared: the last time two places
 * answered the same question separately, a repeating reminder marked the
 * calendar and not the dial for a whole release.
 */
class DayNightTest {

    @Test
    fun `midnight begins the first turn and noon begins the second`() {
        assertFalse(DayNight.isPm(0))
        assertFalse(DayNight.isPm(11))
        assertTrue(DayNight.isPm(12))
        assertTrue(DayNight.isPm(23))
    }

    @Test
    fun `the two sevens land on opposite sides`() {
        // The whole reason the feature exists: seven and seven, twelve hours
        // apart, indistinguishable on a twelve-hour face.
        assertFalse(DayNight.isPm(7))
        assertTrue(DayNight.isPm(19))
    }

    @Test
    fun `every hour of the clock disagrees with its opposite`() {
        // No hour may share a colour with the one twelve hours away, or the
        // mark says nothing at the exact moment it is needed.
        for (h in 0..11) {
            assertEquals("hour $h", DayNight.isPm(h), !DayNight.isPm(h + 12))
        }
    }

    @Test
    fun `hours past the end of the day wrap`() {
        // A wound time can run over midnight before it is normalised.
        // Counted out rather than guessed: 24 is the next midnight, 30 is
        // six in the morning, 36 is the next noon, 47 is eleven at night.
        assertFalse(DayNight.isPm(24))
        assertFalse(DayNight.isPm(30))
        assertTrue(DayNight.isPm(36))
        assertTrue(DayNight.isPm(47))
    }

    @Test
    fun `milliseconds agree with hours, all day long`() {
        for (h in 0..23) {
            for (m in intArrayOf(0, 1, 30, 59)) {
                val ms = h * 3_600_000L + m * 60_000L
                assertEquals("$h:$m", DayNight.isPm(h), DayNight.isPm(ms))
            }
        }
    }

    @Test
    fun `a negative or overwound value still lands somewhere sane`() {
        // The wind-to-set engine hands out both: turn the hands backwards
        // past midnight and the value goes negative before it is wrapped.
        assertTrue(DayNight.isPm(-1 * 3_600_000L))
        assertFalse(DayNight.isPm(-13 * 3_600_000L))
        assertFalse(DayNight.isPm(25 * 3_600_000L))
    }

    @Test
    fun `the two marks are never the same colour, in any theme`() {
        val themes = listOf(
            ClockThemes.MIDNIGHT, ClockThemes.DAYLIGHT, ClockThemes.IVORY,
            ClockThemes.NEON, ClockThemes.TERMINAL, ClockThemes.SUNSET
        )
        for (t in themes) {
            assertTrue(t.toString(), t.amMark != t.pmMark)
            assertEquals(t.amMark, DayNight.markColor(t, 9))
            assertEquals(t.pmMark, DayNight.markColor(t, 21))
        }
    }

    @Test
    fun `night mode dims both marks and still keeps them apart`() {
        val dimmed = ClockThemes.dim(ClockThemes.MIDNIGHT)
        assertTrue(dimmed.amMark != ClockThemes.MIDNIGHT.amMark)
        assertTrue(dimmed.pmMark != ClockThemes.MIDNIGHT.pmMark)
        assertTrue(dimmed.amMark != dimmed.pmMark)
    }
}
