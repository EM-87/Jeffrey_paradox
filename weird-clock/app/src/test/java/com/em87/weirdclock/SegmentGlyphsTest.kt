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
        assertEquals(
            "V is not the two upper diagonals",
            SegmentGlyphs.H or SegmentGlyphs.J, SegmentGlyphs.sixteen('V')
        )
        assertEquals(
            "M is not V with both uprights",
            SegmentGlyphs.sixteen('V'),
            SegmentGlyphs.sixteen('M')!! and SegmentGlyphs.sixteen('V')!!
        )
    }

    /**
     * A bar that runs to the middle overlaps there instead of stopping
     * short of it.
     *
     * Every other bar leaves a hair of daylight at its corner, which is
     * what gives an `M` its notches. The four diagonals all end at the
     * same point, so the same hair leaves a hole where they cross — and an
     * `X` with a hole in the middle is an `X` somebody has taken a bite
     * out of, which is what was on the glass.
     */
    @Test
    fun `the bars that meet in the middle are the ones that run to it`() {
        val toMiddle = SegmentGlyphs.H or SegmentGlyphs.J or SegmentGlyphs.K or
            SegmentGlyphs.M or SegmentGlyphs.I or SegmentGlyphs.L or
            SegmentGlyphs.G1 or SegmentGlyphs.G2
        assertEquals(
            "a bar that does not reach the middle is being joined there anyway",
            toMiddle, SegmentGlyphs.JOINS_MIDDLE
        )
        val outer = SegmentGlyphs.A1 or SegmentGlyphs.A2 or SegmentGlyphs.B or
            SegmentGlyphs.C or SegmentGlyphs.D1 or SegmentGlyphs.D2 or
            SegmentGlyphs.E or SegmentGlyphs.F
        assertEquals(
            "a bar round the outside is being overlapped into the middle",
            0, SegmentGlyphs.JOINS_MIDDLE and outer
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
     * Ten digits, nine marks: the 2 and the 8 are one symbol.
     *
     * That is a fact about the alphabet and not a slip in reading it. A
     * numeral set where two digits share a glyph is not something anybody
     * arrives at by tidying, which is exactly why it is kept and why it is
     * asserted here rather than quietly allowed — the obvious "fix" is to
     * invent a different 8, and that would be inventing.
     *
     * Every other pair is distinct and no two are one stroke apart.
     */
    @Test
    fun `the twos and eights are one mark and the rest are nine`() {
        assertEquals(
            "the 2 and the 8 have drifted apart",
            SegmentGlyphs.star('2'), SegmentGlyphs.star('8')
        )
        val all = digits.associateWith { SegmentGlyphs.star(it)!! }
        assertEquals("ten digits should make nine shapes", 9, all.values.toSet().size)
        for ((a, bitsA) in all) for ((b, bitsB) in all) {
            if (a >= b) continue
            if (a == '2' && b == '8') continue
            assertTrue("$a and $b are the same mark", bitsA != bitsB)
            assertTrue("$a becomes $b if one stroke goes", apart(bitsA, bitsB) >= 2)
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
