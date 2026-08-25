package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two alphabets the sky writes its far years in, counted.
 *
 * Nothing here says a shape looks right — that is what the chart in
 * [GlyphChartTest] is for, and it is looked at with eyes. What can be
 * checked without eyes is the thing a display has to be true of before
 * looking at it is worth anything: that every character has a shape, that
 * no two characters have the same shape, and that no two shapes are one
 * stroke apart, since a stroke is what a display loses when a bar fails.
 *
 * The last of those is the rule the first attempt at this quietly broke.
 */
class SegmentGlyphsTest {

    private val romanLetters = "IVXLCDM"
    private val digits = ('0'..'9').toList()

    /** How many pieces two glyphs disagree about. */
    private fun apart(a: Int, b: Int): Int = Integer.bitCount(a xor b)

    // ------------------------------------------------- the sixteen bars

    /** Every Roman letter has a shape, and it is not blank. */
    @Test
    fun `every letter the module has to write has a shape`() {
        for (c in romanLetters) {
            val bits = SegmentGlyphs.sixteen(c)
            assertNotNull("'$c' has no shape at all", bits)
            assertTrue("'$c' lights nothing, so it is a gap in the date", bits!! != 0)
        }
    }

    /**
     * And no two of them are the same shape.
     *
     * There are no digits on this row any more — the whole date changes
     * script together — which is what lets `D` be the whole ring. A ring
     * is a `D` and a `0` at once, and the only thing that ever made it
     * read as a nought was a nought sitting beside it.
     */
    @Test
    fun `no two letters are the same shape`() {
        val all = romanLetters.associateWith { SegmentGlyphs.sixteen(it)!! }
        for ((a, bitsA) in all) for ((b, bitsB) in all) {
            if (a >= b) continue
            assertTrue("'$a' and '$b' are the same shape", bitsA != bitsB)
        }
    }

    /**
     * No letter is one bar from another.
     *
     * The failure this guards is not somebody typing the table in wrong —
     * it is a date reading as a plausible other date. `MMXXVI` with one
     * bar dead should look broken, not like a different year.
     */
    @Test
    fun `no letter is one bar away from another`() {
        val all = romanLetters.associateWith { SegmentGlyphs.sixteen(it)!! }
        for ((a, bitsA) in all) for ((b, bitsB) in all) {
            if (a >= b) continue
            assertTrue("'$a' becomes '$b' if one bar goes", apart(bitsA, bitsB) >= 2)
        }
    }

    /** The letters that are made of diagonals, and how they are related. */
    @Test
    fun `the diagonals are what the letters are for`() {
        val diagonals = SegmentGlyphs.H or SegmentGlyphs.J or
            SegmentGlyphs.K or SegmentGlyphs.M
        assertTrue(
            "X is not made of diagonals, so it is not an X",
            SegmentGlyphs.sixteen('X')!! and diagonals == diagonals
        )
        // The V is the left upright and one diagonal each way — a V lying
        // with its point at the bottom-left. Made of the two *upper*
        // diagonals it is the top half of an X, which is what it was.
        assertEquals(
            "V is the top half of an X",
            SegmentGlyphs.F or SegmentGlyphs.E or SegmentGlyphs.M or SegmentGlyphs.J,
            SegmentGlyphs.sixteen('V')
        )
        assertTrue(
            "V and X are the same handful of diagonals",
            SegmentGlyphs.sixteen('V')!! and diagonals != diagonals
        )
        assertEquals(
            "M is not both uprights with the two upper diagonals folded between",
            SegmentGlyphs.F or SegmentGlyphs.E or SegmentGlyphs.B or SegmentGlyphs.C or
                SegmentGlyphs.H or SegmentGlyphs.J,
            SegmentGlyphs.sixteen('M')
        )
    }

    /**
     * Nothing overlaps anything, the middle included.
     *
     * The hair of daylight where two bars meet is the display: a segment
     * is a stamped piece of metal, and the gap between one and the next is
     * what says so. It was taken away where the four diagonals cross, on
     * the theory that the gap was what made the `X` look bitten. The bite
     * was somewhere else — the `V` was the top half of an `X` — and the
     * gap belongs.
     */
    @Test
    fun `no bar overlaps another`() {
        assertEquals(
            "some bar is being run past the point it was aimed at",
            0, SegmentGlyphs.JOINS_MIDDLE
        )
    }
}
