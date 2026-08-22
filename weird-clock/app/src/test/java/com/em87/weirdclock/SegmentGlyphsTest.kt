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

    /** Anything that is not a Roman letter has no shape, not a wrong one. */
    @Test
    fun `characters with nothing to say have no shape`() {
        for (c in listOf('Z', '/', '?', 'q', '0', '7')) {
            assertEquals("'$c' was given a shape", null, SegmentGlyphs.sixteen(c))
        }
        for (c in listOf('Z', '/', '?', 'q')) {
            assertEquals("'$c' was given a mark", null, SegmentGlyphs.star(c))
        }
    }

    // ------------------------------------------------- the star

    /** Ten marks, all there and none of them blank. */
    @Test
    fun `every digit has a mark on the star`() {
        for (c in digits) {
            val bits = SegmentGlyphs.star(c)
            assertNotNull("'$c' has no mark", bits)
            assertTrue("'$c' lights nothing", bits!! != 0)
        }
    }

    /**
     * Ten digits, ten marks — including the 2 and the 8.
     *
     * The table these were read off writes both with the same symbol. The
     * later revisions of the numerals separate them and so does this: a
     * date that cannot tell 2 from 8 is a date with a hole in it, and the
     * mirror is where the second one goes, since the 2 leans left.
     */
    @Test
    fun `the ten marks are ten and none is one stroke from another`() {
        val all = digits.associateWith { SegmentGlyphs.star(it)!! }
        assertEquals("two digits share a mark", 10, all.values.toSet().size)
        assertTrue(
            "the 8 is not the mirror of the 2",
            SegmentGlyphs.star('8') != SegmentGlyphs.star('2')
        )
        for ((a, bitsA) in all) for ((b, bitsB) in all) {
            if (a >= b) continue
            assertTrue("$a becomes $b if one stroke goes", apart(bitsA, bitsB) >= 2)
        }
    }

    /**
     * And the break between groups is not a numeral.
     *
     * A blank space separates and looks like nothing; the mark sits on the
     * axis between the two stars, where no digit puts anything, so it can
     * never be read as one.
     */
    @Test
    fun `the break between groups is nothing a digit could be`() {
        val brk = SegmentGlyphs.star('\u00b7')
        assertEquals("the separator is not its own mark", SegmentGlyphs.STAR_BREAK, brk)
        for (c in digits) {
            assertEquals(
                "the digit $c uses the piece the separator is made of",
                0, SegmentGlyphs.star(c)!! and SegmentGlyphs.STAR_BREAK
            )
        }
    }

    /**
     * And a mark is a handful of strokes, not two lit-up stars.
     *
     * Sixteen arms are available across the two, and a glyph that lights
     * most of them is a rosette rather than a numeral: the eye reads these
     * by which few arms are on.
     */
    @Test
    fun `no mark uses more than half the arms`() {
        for (c in digits) {
            val used = Integer.bitCount(SegmentGlyphs.star(c)!!)
            assertTrue("$c lights $used of sixteen arms, which is a rosette", used <= 8)
        }
    }

    /**
     * Nearly every mark has the stem: top arm of the upper star, bottom
     * arm of the lower one, meeting in the middle.
     *
     * It is what makes a row of them read as writing rather than as
     * scattered marks, and it is the piece a wrong reading would lose
     * first — with the two stars set anywhere but where they are, the two
     * halves of the stem do not join and every digit is broken in the
     * middle.
     */
    @Test
    fun `the marks are built on a stem`() {
        val stem = SegmentGlyphs.U_N or SegmentGlyphs.L_S
        val stemmed = digits.count { SegmentGlyphs.star(it)!! and stem == stem }
        assertTrue("only $stemmed of the ten marks stand on the stem", stemmed >= 8)
    }

    /** The 6 is the closed diamond, and nothing else is. */
    @Test
    fun `the six is the diamond`() {
        val diamond = SegmentGlyphs.U_SW or SegmentGlyphs.U_SE or
            SegmentGlyphs.L_NW or SegmentGlyphs.L_NE
        assertTrue(
            "the 6 is not the closed diamond",
            SegmentGlyphs.star('6')!! and diamond == diamond
        )
        val closed = digits.filter { SegmentGlyphs.star(it)!! and diamond == diamond }
        assertEquals("more than the 6 and the 9 close the diamond", listOf('6', '9'), closed)
    }
}
