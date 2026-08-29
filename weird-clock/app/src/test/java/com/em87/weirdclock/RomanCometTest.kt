package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Roman Comet: the calculator's nine and Rome's module, as one panel.
 *
 * The shapes cannot be wrong — they are the drawing's own polygons and
 * nothing here re-derives them — so what is left to get wrong is which
 * nine light for each digit, and that is a table somebody typed. The four
 * numerals in the drawing pin part of it down; a photograph of the real
 * tube counting `12345678` pinned the rest.
 *
 * What the tests below look for is not "does 5 equal what I typed for 5",
 * which is a mirror. It is the handful of things that are true of this
 * display and would stop being true if the table were a seven-bar table
 * with the names changed.
 */
class RomanCometTest {

    private val nine = Segments.Kind.NINE

    private fun of(c: Char): Int = Segments.masksOf(nine, c)!!.single()

    private val stems = Segments.STEM_UP or Segments.STEM_DOWN

    /**
     * Ten different numbers look like ten different things.
     *
     * The one failure a numeral table can have that nothing else catches:
     * two digits that light the same metal are a clock that cannot tell
     * you which of two times it is, and it reads perfectly well.
     */
    @Test
    fun `no two digits light the same segments`() {
        val seen = HashMap<Int, Char>()
        for (c in '0'..'9') {
            val mask = of(c)
            val already = seen.put(mask, c)
            assertNull("$c and $already are the same shape", already)
            assertTrue("$c lights nothing", mask != 0)
        }
        assertEquals(10, seen.size)
    }

    /**
     * There is no middle rail on this display, and `2` and `3` are where
     * that shows.
     *
     * Every other display's `2` and `3` light their middle bar. These do
     * not, because their arms already cross the centre — the top-right arm
     * of a `2` runs into its bottom-left one and they meet there on their
     * own. The waist dash is not a rail; it is the short piece that fills
     * the corner those crossings leave open, which is why it belongs to
     * `4`, `5`, `6`, `8` and `9` and to nothing else.
     *
     * This is the whole difference between the Comet's table and a
     * seven-bar table with the names changed, so it is the assertion that
     * would catch that mistake.
     */
    @Test
    fun `the waist is not a middle rail`() {
        for (c in "45689") {
            assertTrue("$c has no waist", of(c) and Segments.WAIST != 0)
        }
        for (c in "01237") {
            assertTrue("$c lit the waist", of(c) and Segments.WAIST == 0)
        }
    }

    /**
     * The `1` stands up the middle, which is the other thing this display
     * was designed to fix.
     *
     * On seven bars a one is the two right-hand bars, so it leans against
     * whatever is beside it and a row of them looks like a fence with a
     * gap. Here it is the two stems and nothing else, in the middle of its
     * own cell.
     */
    @Test
    fun `the one is the two stems and stands in the middle`() {
        assertEquals(stems, of('1'))
        // And the stems belong to the three digits with a stroke through
        // the middle. Anything else lighting one is a digit with a bar
        // across its face.
        for (c in '0'..'9') {
            val hasStem = of(c) and stems != 0
            assertEquals("$c disagrees about its stem", c in "147", hasStem)
        }
    }

    /**
     * Eight lights everything except the stems, and every digit without a
     * stroke through it fits inside it.
     *
     * The `8` on a segment display is the test pattern — it is what the
     * display looks like with the mask off — and any digit that lights
     * metal the `8` does not is a digit drawn outside its own glyph.
     */
    @Test
    fun `eight is every digit that has no stroke through it`() {
        val eight = of('8')
        assertEquals("eight lit a stem", 0, eight and stems)
        for (c in "023569") {
            assertEquals("$c goes outside the eight", 0, of(c) and eight.inv())
        }
        // And it really is all seven of the rest.
        val every = Segments.bars(nine).fold(0) { a, b -> a or b.bit }
        assertEquals(every and stems.inv(), eight)
    }

    /** A calculator has ten digits and a minus, and not one letter. */
    @Test
    fun `the alphabet is the ten and a minus`() {
        assertEquals(intArrayOf(Segments.WAIST).toList(), of('-').let { listOf(it) })
        assertNotNull(Segments.masksOf(nine, ' '))
        for (c in "IVXLCDMN ABZ") {
            if (c == ' ') continue
            assertNull("$c is not on a calculator", Segments.masksOf(nine, c))
        }
    }

    /**
     * Nine pieces of metal, every one of them a shape out of the file.
     *
     * There is nothing to make up on this display — no sliver, no dot, no
     * bar the painter has to guess the ends of. A bar here without an
     * outline is a bar that got lost between the drawing and the file.
     */
    @Test
    fun `there are nine bars and all nine are drawings`() {
        val bars = Segments.bars(nine)
        assertEquals(9, bars.size)
        assertTrue(Segments.drawn(nine))
        for (bar in bars) {
            val outline = bar.outline
            assertNotNull("a bar has no shape", outline)
            assertTrue("a bar is not a polygon", outline!!.size >= 8)
            assertEquals("a bar has half a vertex", 0, outline.size % 2)
            assertTrue("a bar is not curved", bar.curved)
            // Inside its own module, near enough — the arms lean out over
            // the edges by a hair and that is what the gap is for.
            var i = 0
            while (i < outline.size) {
                assertTrue("a vertex is off the module: ${outline[i]}", outline[i] in -0.05f..1.05f)
                assertTrue("a vertex is off the module: ${outline[i + 1]}", outline[i + 1] in -0.05f..1.05f)
                i += 2
            }
        }
        // Every one of the nine is somebody's, and no two share a bit.
        assertEquals(9, bars.map { it.bit }.toSet().size)
        val every = bars.fold(0) { a, b -> a or b.bit }
        for (c in '0'..'9') assertEquals("$c lights metal that is not there", 0, of(c) and every.inv())
    }

    /**
     * This display has daylight between its cells, and it is the only one
     * that does.
     *
     * Not a taste: the arms lean out past the edges of their own module,
     * so two digits butted together interleave — the bottom of one sits
     * inside the top of the next. The drawing puts four digits on a pitch
     * a fifth wider than their ink, and that fifth is this number.
     */
    @Test
    fun `the cells stand apart, and none of them is shared`() {
        assertTrue(Segments.gap(nine) > 0.15f)
        for (other in Segments.Kind.entries - nine) {
            assertEquals("$other grew a gap", 0f, Segments.gap(other), 0f)
        }
        // Nothing is shared and nothing is spelled around, so a four digit
        // number is four cells and the room it needs is those four plus
        // the three gaps between them.
        assertFalse(Segments.butted(nine))
        assertEquals(4, Segments.width(nine, "1234"))
        assertEquals(4f + 3f * Segments.gap(nine), Segments.span(nine, "1234"), 0.0001f)
    }

    /**
     * The module is wider than the others, because it leans.
     *
     * Out of the drawing: 9.987 of ink across, 15.228 tall. A display of
     * upright bars can be narrow; one where every stroke is at an angle
     * has to pay for the run of the slope.
     */
    @Test
    fun `the module is the drawing's proportion`() {
        assertEquals(0.6558f, Segments.aspect(nine), 0.0005f)
        assertTrue(Segments.aspect(nine) > Segments.aspect(Segments.Kind.SIXTEEN))
    }

    /**
     * The colon beside these digits is the drawing's two lamps and not two
     * dots of the bar's own width.
     *
     * The pen here is a third of what the other displays draw with, so a
     * separator sized from it comes out as two specks. The drawing has the
     * answer and it is three pen widths across.
     */
    @Test
    fun `the separator is the drawing's, not the pen's`() {
        assertTrue(Segments.separator(nine) > Segments.native(nine) * 3f)
        // And nothing changed for the two displays that never had one
        // drawn: their colon is still made out of their own bar.
        for (other in listOf(Segments.Kind.SEVEN, Segments.Kind.STAR)) {
            assertEquals(Segments.native(other) * 1.6f, Segments.separator(other), 0.0001f)
        }
    }

    /**
     * These numerals cannot leave their own displays.
     *
     * A flip card with them printed on it would be a photograph of a
     * display, which is the one thing this app has consistently refused to
     * be — so asking for a drum and getting the bars is the honest reading
     * of the request. The rule lives in one place because the face, the
     * widget and the alarm card all read the same two settings.
     */
    @Test
    fun `the panel is bars whatever else is asked for`() {
        for (key in listOf(Prefs.DIGITS_ROLLER, Prefs.DIGITS_CARD, Prefs.DIGITS_PLAIN, null)) {
            assertEquals(
                "$key got past the panel",
                DigitStyle.SEGMENT, DigitStyle.of(key, DigitScript.ROMAN_COMET)
            )
        }
        // And it is the only script that takes the choice away.
        for (script in DigitScript.entries - DigitScript.ROMAN_COMET) {
            assertFalse(script.barsOnly)
            assertEquals(DigitStyle.ROLLER, DigitStyle.of(Prefs.DIGITS_ROLLER, script))
        }
    }

    /**
     * Nobody who had chosen either half of this wakes up with an ordinary
     * clock.
     *
     * Two entries on the numerals list became one, and both of the old
     * keys are still written down in the settings of the phones that had
     * them. Falling through to the default would turn a Roman clock into
     * our ten digits overnight, silently, and the person it would happen
     * to first is the one who drew the module.
     */
    @Test
    fun `both of the scripts this replaced land on it`() {
        assertEquals(DigitScript.ROMAN_COMET, DigitScript.of(Prefs.SCRIPT_ROMAN))
        assertEquals(DigitScript.ROMAN_COMET, DigitScript.of(Prefs.SCRIPT_COMET))
        assertEquals(DigitScript.ROMAN_COMET, DigitScript.of(Prefs.SCRIPT_ROMAN_COMET))
        // And nothing else moved.
        assertEquals(DigitScript.ARABIC, DigitScript.of(Prefs.SCRIPT_ARABIC))
        assertEquals(DigitScript.YAUTJA, DigitScript.of(Prefs.SCRIPT_YAUTJA))
        assertEquals(DigitScript.ARABIC, DigitScript.of("something else entirely"))
        assertEquals(DigitScript.ARABIC, DigitScript.of(null))
        // The list itself is three now, not four.
        assertEquals(3, DigitScript.entries.size)
    }

    /**
     * The two rails say the date, and they say it in Rome's numerals
     * because that is what the rail is made of.
     *
     * Day and month above, the year below. There is no choice about the
     * numerals: a sixteen-bar module cannot write an 8.
     */
    @Test
    fun `the rails carry the date, and the year underneath`() {
        val day = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 29)
        }
        assertEquals(
            "XXIX\u00b7VIII" to "MMXXVI",
            CometPanel.rails(day, dayFirst = true)
        )
        assertEquals(
            "VIII\u00b7XXIX" to "MMXXVI",
            CometPanel.rails(day, dayFirst = false)
        )
        // And every rail this panel can be asked to draw is a rail it can
        // actually spell: no character falls out.
        //
        // This is the assertion that found the weekday. Rome's module has
        // eight letters and every Latin day name wants one it has not got,
        // so a rail reading IOV\u00b7I\u00b7I silently dropped its O and
        // came out as IV — a Thursday that reads as the fourth of the
        // month. There is no weekday on this panel because of this test.
        for (month in 1..12) {
            for (dayOfMonth in 1..28) {
                val at = java.util.Calendar.getInstance().apply {
                    set(2026, month - 1, dayOfMonth)
                }
                val (top, bottom) = CometPanel.rails(at, dayFirst = true)
                for (text in listOf(top, bottom)) {
                    for (c in text) {
                        assertNotNull(
                            "the rail cannot write $c in $text",
                            Segments.masksOf(Segments.Kind.SIXTEEN, c)
                        )
                    }
                }
            }
        }
    }

    /**
     * The moon lamp names the half of the day, and only when there is one
     * to name.
     *
     * The drawing gives the panel one lamp and no sun, so it lights before
     * noon and goes out after. On a twenty-four hour clock there is no
     * ambiguity to resolve and it never lights — the way the AM and PM
     * legends printed on a real panel do not.
     */
    @Test
    fun `the moon lights in the morning and never on a twenty-four hour clock`() {
        assertTrue(CometPanel.moonLit(0, hour24 = false))
        assertTrue(CometPanel.moonLit(11, hour24 = false))
        assertFalse(CometPanel.moonLit(12, hour24 = false))
        assertFalse(CometPanel.moonLit(23, hour24 = false))
        for (hour in 0..23) {
            assertFalse("the moon lit at $hour", CometPanel.moonLit(hour, hour24 = true))
        }
    }

    /**
     * The panel's proportions are the drawing's, and the rails really are
     * shorter than the digits.
     *
     * Two displays drawn to two different sizes, put on one panel. Get the
     * ratio wrong and they stop reading as one instrument — which is the
     * only thing this fusion has to get right.
     */
    @Test
    fun `the rail is the drawing's fraction of a digit`() {
        // 12.441 between the module's rails over 15.228 of Comet digit.
        assertEquals(12.441f / 15.228f, CometPanel.RAIL, 0.001f)
        assertTrue(CometPanel.RAIL < 1f)
        assertTrue(CometPanel.RAIL_GAP > 0f && CometPanel.RAIL_GAP < CometPanel.RAIL)
        // And the colon leans, because everything on this display does.
        assertTrue(CometPanel.COLON_LEAN > 0f)
    }

    /** The two lamps are shapes out of the file, not drawn from memory. */
    @Test
    fun `the lamps are the drawing's own outlines`() {
        assertEquals("the bell lost its ringing", 3, CometPanel.BELL.size)
        assertEquals(1, CometPanel.MOON.size)
        for (shape in CometPanel.BELL + CometPanel.MOON) {
            assertTrue("a lamp is not a polygon", shape.size >= 8)
            assertEquals("a lamp has half a vertex", 0, shape.size % 2)
            for (v in shape) assertTrue("a lamp runs off its own box: $v", v in -0.001f..1.001f)
        }
    }

    /**
     * A display with no letters says the day of the week as a number.
     *
     * The panel is the exception and the reason it exists: its rails are
     * made of the one display here that has letters in it, so it says the
     * day in Latin rather than as a figure.
     */
    @Test
    fun `the panel says its weekday in Latin and the star display in numbers`() {
        val monday = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 24)
        }
        assertEquals("LVN", Weekday.of(monday, DigitScript.ROMAN_COMET))
        assertEquals("1", Weekday.of(monday, DigitScript.YAUTJA))
    }
}
