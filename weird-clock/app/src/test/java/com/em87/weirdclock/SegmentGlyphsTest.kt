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

    /**
     * The letters use the pieces only letters need.
     *
     * With one exception, and it is the interesting one. `D` is the whole
     * ring — which is what a Roman display does, since it has no nought to
     * clash with — but this row carries the day and the month as digits
     * beside the year, so the nought is there after all. It is slashed to
     * keep them apart, which makes it the one digit with a diagonal in it.
     */
    @Test
    fun `the diagonals are what the letters are for`() {
        val diagonals = SegmentGlyphs.H or SegmentGlyphs.J or
            SegmentGlyphs.K or SegmentGlyphs.M
        assertTrue(
            "X is not made of diagonals, so it is not an X",
            SegmentGlyphs.sixteen('X')!! and diagonals == diagonals
        )
        assertTrue(
            "V is not the two upper diagonals",
            SegmentGlyphs.sixteen('V') == (SegmentGlyphs.H or SegmentGlyphs.J)
        )
        assertTrue(
            "M is not V with both uprights",
            SegmentGlyphs.sixteen('M')!! and SegmentGlyphs.sixteen('V')!! ==
                SegmentGlyphs.sixteen('V')!!
        )
        for (d in digits) {
            if (d == '0') continue
            assertEquals(
                "the digit $d is using a diagonal, which no digit needs",
                0, SegmentGlyphs.sixteen(d)!! and diagonals
            )
        }
        assertTrue(
            "the nought is not slashed, so it is a D",
            SegmentGlyphs.sixteen('0')!! and diagonals != 0
        )
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
