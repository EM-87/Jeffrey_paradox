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

    /** Every digit and every Roman letter has a shape, and it is not blank. */
    @Test
    fun `every character the sixteen bar module has to write has a shape`() {
        for (c in digits + romanLetters.toList()) {
            val bits = SegmentGlyphs.sixteen(c)
            assertNotNull("'$c' has no shape at all", bits)
            assertTrue("'$c' lights nothing, so it is a gap in the date", bits!! != 0)
        }
    }

    /**
     * And no two of them are the same shape.
     *
     * `I` and `1` are the pair this is really about: a module showing one
     * upright could be either, and in `01·09·MMXXVI` both appear. They are
     * kept apart by the `1` living against the module's right edge and the
     * `I` down its middle.
     */
    @Test
    fun `no two characters are the same shape`() {
        val all = (digits + romanLetters.toList()).associateWith { SegmentGlyphs.sixteen(it)!! }
        for ((a, bitsA) in all) for ((b, bitsB) in all) {
            if (a >= b) continue
            assertTrue("'$a' and '$b' are the same shape", bitsA != bitsB)
        }
    }

    /**
     * A Roman letter is never one bar from anything else.
     *
     * The failure this guards is not somebody typing the table in wrong —
     * it is a date reading as a plausible other date. `MMXXVI` with one
     * bar dead should look broken, not like a different year.
     *
     * The digits are deliberately not held to this, and asking them to be
     * was how this test first failed: `8` and `9` differ by one bar on
     * every segment display ever made, as do `5` and `6` and `3` and `9`.
     * Those are the shapes of the digits, and a `9` drawn some other way
     * to satisfy a rule would be a worse `9`. The rule is for the shapes
     * that were chosen here rather than inherited.
     */
    @Test
    fun `no letter is one bar away from anything`() {
        val all = (digits + romanLetters.toList()).associateWith { SegmentGlyphs.sixteen(it)!! }
        for (a in romanLetters) for ((b, bitsB) in all) {
            if (a == b) continue
            assertTrue(
                "'$a' becomes '$b' if one bar goes",
                apart(all.getValue(a), bitsB) >= 2
            )
        }
    }

    /** The letters use the pieces only letters need. */
    @Test
    fun `the diagonals are what the letters are for`() {
        val diagonals = SegmentGlyphs.H or SegmentGlyphs.J or
            SegmentGlyphs.K or SegmentGlyphs.M
        assertTrue(
            "X is not made of diagonals, so it is not an X",
            SegmentGlyphs.sixteen('X')!! and diagonals == diagonals
        )
        for (d in digits) {
            assertEquals(
                "the digit $d is using a diagonal, which no digit needs",
                0, SegmentGlyphs.sixteen(d)!! and diagonals
            )
        }
    }

    /** Anything that is not in either alphabet has no shape, not a wrong one. */
    @Test
    fun `characters with nothing to say have no shape`() {
        for (c in listOf('Z', '/', '?', 'q')) {
            assertEquals("'$c' was given a shape", null, SegmentGlyphs.sixteen(c))
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

    /** No two the same, and none of them one stroke from another. */
    @Test
    fun `no two marks are the same and none is one stroke from another`() {
        val all = digits.associateWith { SegmentGlyphs.star(it)!! }
        for ((a, bitsA) in all) for ((b, bitsB) in all) {
            if (a >= b) continue
            assertTrue(
                "$a and $b are the same mark",
                bitsA != bitsB
            )
            assertTrue(
                "$a becomes $b if one stroke goes",
                apart(bitsA, bitsB) >= 2
            )
        }
    }

    /**
     * And a mark is a handful of strokes, not a lit-up star.
     *
     * Twelve pieces are available and a glyph that uses most of them is
     * indistinguishable at arm's length from the unlit display behind it.
     */
    @Test
    fun `no mark uses more than a third of the star`() {
        for (c in digits) {
            val used = Integer.bitCount(SegmentGlyphs.star(c)!!)
            assertTrue("$c lights $used of twelve pieces, which is a blob", used <= 4)
        }
    }
}
