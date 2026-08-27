package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three displays, checked without a screen.
 *
 * A picture of nine Roman letters is a fine way to decide whether they
 * look right and a hopeless way to find out which of ten uprights is
 * missing — which is exactly the bug that hid in here, twice, behind a
 * screenshot that looked nearly right. So the layout is arithmetic now,
 * and this is where the arithmetic is held.
 */
class SegmentsTest {

    private fun plan(text: String, kind: Segments.Kind = Segments.Kind.SIXTEEN) =
        Segments.plan(kind, Segments.spell(kind, text))

    private fun lit(text: String, kind: Segments.Kind = Segments.Kind.SIXTEEN) =
        plan(text, kind).filter { it.lit }

    // ------------------------------------------------------- the letters

    /**
     * `V` is two modules, and that is the whole reason this display is
     * described here rather than guessed at.
     *
     * The module has no vertical stem and its diagonals meet in the
     * middle, so the only `V` it can make alone is a shallow tick across
     * the top half — which is what a reconstruction produced and what
     * reads as half an `X`. A stroke corner-to-corner in one module and
     * the opposite stroke in the next meet at the corner the two of them
     * share: a real `V`, full height, its point on the baseline.
     */
    @Test
    fun `Rome's V takes two modules and meets on the baseline`() {
        val v = Segments.masksOf(Segments.Kind.SIXTEEN, 'V')
        assertNotNull(v)
        assertEquals("a V that fits in one module is the wrong V", 2, v!!.size)
        // The first module's stroke ends at its bottom-right corner and
        // the second's starts at its bottom-left, which is the same point.
        assertTrue("the first half must run top-left to bottom-right", v[0] == Segments.H or Segments.K)
        assertTrue("and the second bottom-left to top-right", v[1] == Segments.J or Segments.M)
        assertEquals("MMXXIV is seven modules wide", 7, Segments.width(Segments.Kind.SIXTEEN, "MMXXIV"))
    }

    /**
     * And an `M` is that same `V` hung between two uprights, which is what
     * the letter is and why the two must not disagree.
     */
    @Test
    fun `an M is a V between two uprights`() {
        val m = Segments.masksOf(Segments.Kind.SIXTEEN, 'M')!!.single()
        assertEquals(Segments.LEFT or Segments.RIGHT or Segments.H or Segments.J, m)
        // Not the same pair of diagonals as the two-module V: those run
        // corner to corner, these stop in the middle.
        assertTrue("an M with no uprights is not an M", m and Segments.LEFT != 0)
    }

    /** Nothing in the Roman alphabet uses a bar the module has not got. */
    @Test
    fun `every Roman letter is made of bars this module has`() {
        val every = Segments.bars(Segments.Kind.SIXTEEN).fold(0) { acc, b -> acc or b.bit }
        for (c in "IVXLCDMN·") {
            for (mask in Segments.masksOf(Segments.Kind.SIXTEEN, c)!!) {
                assertEquals("$c lights a bar that is not there", 0, mask and every.inv())
            }
        }
    }

    /** And no two letters are the same shape, or the display cannot be read. */
    @Test
    fun `no two Roman letters come out identical`() {
        val seen = HashMap<List<Int>, Char>()
        for (c in "IVXLCDMN") {
            val shape = Segments.masksOf(Segments.Kind.SIXTEEN, c)!!.toList()
            val clash = seen.put(shape, c)
            assertNull("$c and $clash light exactly the same bars", clash)
        }
    }

    /**
     * The letters that *can* be told apart by one dead bar, and the ones
     * that cannot.
     *
     * The rule everybody wants here is that no letter is one bar from
     * another, so a date with a segment out reads as broken rather than as
     * a plausible other date. This alphabet cannot have it and should not
     * pretend to: `I` is an upright, `L` is that upright and the foot, `C`
     * is `L` and the head, `D` is `C` and the other upright. Each is the
     * one before it plus a bar, because that is what those letters are.
     *
     * An earlier version of this display passed the strict rule by
     * accident — it cut every upright and rail into halves, so adding "the
     * foot" added two bits instead of one. That is a property of a table,
     * not of a display, and the drawing this one copies has whole bars.
     *
     * What is checked is what is true: every letter has a shape, no two
     * letters share one, and the four that do sit a bar apart are exactly
     * the nested four and nobody else.
     */
    @Test
    fun `the letters a dead bar can confuse are the nested ones and no others`() {
        val single = "IXLCDMN".associateWith {
            Segments.masksOf(Segments.Kind.SIXTEEN, it)!!.single()
        }
        val close = ArrayList<String>()
        for ((a, bitsA) in single) {
            assertTrue("'$a' lights nothing, so it is a gap in the date", bitsA != 0)
            for ((b, bitsB) in single) {
                if (a >= b) continue
                if (Integer.bitCount(bitsA xor bitsB) < 2) close += "$a$b"
            }
        }
        assertEquals(
            "some letter is now one bar from a letter it has no business resembling",
            listOf("CD", "CL", "IL"), close.sorted()
        )
        // And the two-module V cannot be mistaken for any of them at all,
        // which is a stronger promise than two bars.
        assertEquals(2, Segments.masksOf(Segments.Kind.SIXTEEN, 'V')!!.size)
    }

    // ------------------------------------------------- standing apart

    /**
     * Rome's modules stand apart, each with its own two uprights.
     *
     * They were butted at first, sharing one upright at every boundary,
     * because that is what the drawing shows — and every year in the
     * nineteen hundreds came out wrong. `MCM` is `M`, `C`, `M`: the `C`
     * has no right-hand upright, the `M` after it does, and a shared
     * boundary stood that upright hard against the `C` and closed it into
     * a `D`. 1980 read as MDMLXXX on the dial, which is where it was
     * caught — by looking at it.
     *
     * So the rule is the one every display of this kind actually uses, and
     * this is the test that says so: a `C` beside an `M` lights the bars a
     * `C` lights and no others.
     */
    @Test
    fun `a C beside an M is still a C`() {
        val masks = Segments.spell(Segments.Kind.SIXTEEN, "MCM")
        assertEquals(3, masks.size)
        assertEquals(
            "the C picked up an upright from its neighbour",
            Segments.masksOf(Segments.Kind.SIXTEEN, 'C')!!.single(), masks[1]
        )
        assertFalse("nothing here shares an upright", Segments.butted(Segments.Kind.SIXTEEN))
        assertTrue("and there is daylight between the modules", Segments.gap(Segments.Kind.SIXTEEN) > 0f)
    }

    /** The gaps are inside the number, so whatever is laid out beside it knows. */
    @Test
    fun `a number asks for the room its gaps take`() {
        val kind = Segments.Kind.SIXTEEN
        assertEquals(4, Segments.width(kind, "XIV"))
        assertEquals(4f + 3f * Segments.gap(kind), Segments.span(kind, "XIV"), 0.0001f)
        assertEquals("one module needs no gap", 1f, Segments.span(kind, "I"), 0.0001f)
        assertEquals("and nothing needs nothing", 0f, Segments.span(kind, ""), 0.0001f)
    }

    // ------------------------------------------------------- their stars

    /**
     * Their numerals, read off the chart.
     *
     * The check that this is a reading and not an invention is the family
     * likeness the chart has and a wrong reading would not: 8 is 2 with
     * the tail added, and 9 is 8 with one more arm.
     */
    @Test
    fun `their eight is their two with a tail, and nine is eight with an arm`() {
        fun star(d: Int) = Segments.masksOf(Segments.Kind.STAR, '0' + d)!!.single()
        assertEquals("8 is not 2 plus the tail", star(2) or Segments.LS, star(8))
        assertEquals("9 is not 8 plus one arm", star(8) or Segments.LNE, star(9))
    }

    /** Ten numerals, ten different shapes. */
    @Test
    fun `no two of their numerals are the same shape`() {
        val seen = HashMap<Int, Int>()
        for (d in 0..9) {
            val shape = Segments.masksOf(Segments.Kind.STAR, '0' + d)!!.single()
            val clash = seen.put(shape, d)
            assertNull("$d and $clash are the same glyph", clash)
        }
    }

    /**
     * The arm between the two stars is one arm and not two.
     *
     * Two stacked stars have sixteen arms between them and this display
     * has fifteen: the upper one's downward arm and the lower one's upward
     * arm stand in exactly the same place, so there is one bar there. Drawn
     * as two it is twice as bright as every other arm, which is the sort of
     * thing nobody sees and everybody notices.
     */
    @Test
    fun `the two stars share the arm between them`() {
        val bars = Segments.bars(Segments.Kind.STAR)
        assertEquals("fifteen arms, not sixteen", 15, bars.size)
        val places = bars.map { setOf(it.x0 to it.y0, it.x1 to it.y1) }
        assertEquals("two arms are in the same place", places.size, places.distinct().size)
    }

    /**
     * And the diamond closes. The upper star's lower diagonals and the
     * lower star's upper ones have to land on the same two points, or a 6
     * is four strokes that nearly meet.
     */
    @Test
    fun `the diamond in their six actually closes`() {
        val bars = Segments.bars(Segments.Kind.STAR).associateBy { it.bit }
        val upperSW = bars[Segments.USW]!!
        val lowerNW = bars[Segments.LNW]!!
        assertEquals(upperSW.x1, lowerNW.x1, 0.0001f)
        assertEquals(upperSW.y1, lowerNW.y1, 0.0001f)
    }

    // --------------------------------------------------------- poked out

    /** A bar somebody poked out stays dark however hard the number tries. */
    @Test
    fun `a poked bar does not light`() {
        val masks = Segments.spell(Segments.Kind.SEVEN, "8")
        val whole = Segments.plan(Segments.Kind.SEVEN, masks).count { it.lit }
        assertEquals("an eight lights all seven", 7, whole)
        val poked = Segments.plan(Segments.Kind.SEVEN, masks, intArrayOf(Segments.G or Segments.D))
        assertEquals("two bars poked out and the eight still lights seven", 5, poked.count { it.lit })
        assertTrue(
            "the poked bars vanished instead of going dark",
            poked.count { !it.lit } == 2
        )
    }

    /** Ours: ten digits, seven bars, and the eight lights all of them. */
    @Test
    fun `our own ten still add up`() {
        val every = Segments.bars(Segments.Kind.SEVEN).fold(0) { acc, b -> acc or b.bit }
        assertEquals(every, Segments.masksOf(Segments.Kind.SEVEN, '8')!!.single())
        val seen = HashMap<Int, Char>()
        for (c in '0'..'9') {
            val clash = seen.put(Segments.masksOf(Segments.Kind.SEVEN, c)!!.single(), c)
            assertNull("$c and $clash are the same digit", clash)
        }
    }
}
