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

    // ------------------------------------------------ the shared upright

    /**
     * Rome's modules share the upright between them, and the drawing is
     * what says so.
     *
     * Ten modules in its specimen strip come to eighty-one polygons: ten
     * lots of eight, and one upright over. Each module carries a left-hand
     * upright and the strip closes with one more. Giving every module an
     * upright on both sides puts two strokes a hair apart at every
     * boundary, which is what the display was reported for and what an
     * eleventh polygon nobody counted would have gone on hiding.
     */
    @Test
    fun `ten modules have eleven uprights between them`() {
        assertTrue("the drawing's modules touch", Segments.butted(Segments.Kind.SIXTEEN))
        assertEquals("and there is nothing to put between them", 0f, Segments.gap(Segments.Kind.SIXTEEN), 0f)
        val strip = Segments.plan(Segments.Kind.SIXTEEN, IntArray(10) { Segments.LEFT })
        val uprights = strip.count { it.bar.bit == Segments.LEFT || it.bar.bit == Segments.RIGHT }
        assertEquals("ten modules, eleven uprights", 11, uprights)
        assertEquals("and each one drawn exactly once", 11, uprights)
    }

    /**
     * A shared upright is lit if either letter beside it asks for one, and
     * drawn once either way.
     */
    @Test
    fun `the upright between two letters is one bar with two owners`() {
        val kind = Segments.Kind.SIXTEEN
        // X lights no upright, I lights its left one. The boundary between
        // them belongs to the I and is lit; the one before the X is not.
        val strokes = Segments.plan(kind, Segments.spell(kind, "XI"))
        val posts = strokes.filter { it.bar.bit == Segments.LEFT || it.bar.bit == Segments.RIGHT }
        assertEquals("two modules, three uprights", 3, posts.size)
        assertEquals("only the one the I owns is lit", 1, posts.count { it.lit })
    }

    /**
     * And the price of sharing, paid where it is owed.
     *
     * `MCM` is `M`, `C`, `M`. The `C` has no right-hand upright, the `M`
     * after it does, and a shared boundary stands that upright hard
     * against the `C` and closes it into a `D` — 1980 read as MDMLXXX on
     * the dial, which is where it was caught, by looking at it. So a dark
     * module goes in between: one cell, only where a letter would turn
     * into another letter, instead of prising the whole display apart.
     */
    @Test
    fun `a C beside an M is still a C`() {
        val kind = Segments.Kind.SIXTEEN
        val masks = Segments.spell(kind, "MCM")
        assertEquals("the C needs a dark cell to keep its right side open", 4, masks.size)
        assertEquals(Segments.masksOf(kind, 'C')!!.single(), masks[1])
        assertEquals("and the cell after it is blank", 0, masks[2])
        // Nothing else in the alphabet pays it. `CX` is safe because an X
        // lights no upright at all, and `MM` because both already do.
        assertEquals(2, Segments.width(kind, "CX"))
        assertEquals(2, Segments.width(kind, "MM"))
        assertEquals(3, Segments.width(kind, "MCX"))
    }

    /** What a number costs in modules, which is what the layout reserves. */
    @Test
    fun `a number asks for exactly the modules it uses`() {
        val kind = Segments.Kind.SIXTEEN
        assertEquals(4, Segments.width(kind, "XIV"))
        assertEquals(4f, Segments.span(kind, "XIV"), 0.0001f)
        assertEquals("one module is one module", 1f, Segments.span(kind, "I"), 0.0001f)
        assertEquals("and nothing needs nothing", 0f, Segments.span(kind, ""), 0.0001f)
        assertEquals(
            "the room reserved is the room drawn",
            Segments.spell(kind, "MCMLXXX").size, Segments.width(kind, "MCMLXXX")
        )
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
     * The four bars in the middle that no numeral ever lights.
     *
     * The chart draws them and the display has to have them: the upper
     * star's south arm, the lower star's north arm, and the rail across
     * the waist in its two halves, four bars stopping short of one point.
     * They were one long shared arm here, which is three bars missing and
     * one bar too long, and the only place it shows is the ghosts — which
     * is exactly where it was reported from.
     */
    @Test
    fun `the middle holds four bars no numeral uses`() {
        val kind = Segments.Kind.STAR
        val bars = Segments.bars(kind)
        assertEquals("eighteen arms, not fifteen", 18, bars.size)
        val ever = (0..9).fold(0) { a, d -> a or Segments.spell(kind, "$d").single() }
        for (bit in listOf(Segments.US, Segments.LN, Segments.MW, Segments.ME)) {
            assertEquals("a numeral lights a middle bar", 0, ever and bit)
        }
        // And they are four separate bars meeting at the waist, not one
        // stroke through it: each has an end at the centre of the module.
        val middle = bars.filter { it.bit in listOf(Segments.US, Segments.LN, Segments.MW, Segments.ME) }
        for (bar in middle) {
            val ends = listOf(bar.x0 to bar.y0, bar.x1 to bar.y1)
            assertTrue("${bar.bit} does not reach the waist", (0.5f to 0.5f) in ends)
        }
        // None of them runs past it, which is what one shared arm did.
        for (bar in middle) {
            assertTrue(
                "a middle bar crosses the waist",
                (bar.y0 <= 0.5f && bar.y1 <= 0.5f) || (bar.y0 >= 0.5f && bar.y1 >= 0.5f)
            )
        }
    }

    /** No two arms stand in the same place, whatever their number is. */
    @Test
    fun `every arm has the waist or a star to itself`() {
        val bars = Segments.bars(Segments.Kind.STAR)
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
