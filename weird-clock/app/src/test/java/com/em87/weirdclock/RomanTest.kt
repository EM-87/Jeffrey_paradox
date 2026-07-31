package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numerals on the dial, and the date complication's Roman form. */
class RomanTest {

    @Test
    fun `the hours of a twelve-hour dial`() {
        val expected = listOf(
            "I", "II", "III", "IV", "V", "VI",
            "VII", "VIII", "IX", "X", "XI", "XII"
        )
        assertEquals(expected, (1..12).map { Roman.of(it) })
    }

    @Test
    fun `a twenty-four hour dial keeps going`() {
        assertEquals("XIII", Roman.of(13))
        assertEquals("XIX", Roman.of(19))
        assertEquals("XX", Roman.of(20))
        assertEquals("XXIV", Roman.of(24))
    }

    @Test
    fun `the subtractive pairs`() {
        assertEquals("IV", Roman.of(4))
        assertEquals("IX", Roman.of(9))
        assertEquals("XL", Roman.of(40))
        assertEquals("XC", Roman.of(90))
        assertEquals("CD", Roman.of(400))
        assertEquals("CM", Roman.of(900))
    }

    @Test
    fun `years, as the date complication writes them`() {
        assertEquals("MMXXVI", Roman.of(2026))
        assertEquals("MCMXC", Roman.of(1990))
        assertEquals("MMMCMXCIX", Roman.of(3999))
    }

    @Test
    fun `zero has no numeral, which is the honest answer`() {
        assertEquals("", Roman.of(0))
    }

    @Test
    fun `no numeral repeats more than three times running`() {
        // The rule that makes the notation readable; the subtractive pairs
        // exist precisely to avoid IIII and XXXX.
        for (n in 1..3999) {
            val s = Roman.of(n)
            for (c in "IXCM") {
                assertTrue("$n gave $s", !s.contains("$c$c$c$c"))
            }
        }
    }

    @Test
    fun `every numeral up to 3999 reads back as the number it came from`() {
        val values = mapOf(
            'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50,
            'C' to 100, 'D' to 500, 'M' to 1000
        )
        for (n in 1..3999) {
            val s = Roman.of(n)
            // Standard reading: a smaller numeral before a larger subtracts.
            var total = 0
            for (i in s.indices) {
                val here = values.getValue(s[i])
                val next = if (i + 1 < s.length) values.getValue(s[i + 1]) else 0
                total += if (here < next) -here else here
            }
            assertEquals(n, total)
        }
    }
}
