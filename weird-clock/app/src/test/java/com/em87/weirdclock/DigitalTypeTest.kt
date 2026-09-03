package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Three things about the printed face that only a photograph could find,
 * measured so that a photograph does not have to be looked at again.
 *
 * All three were reported by somebody looking at a page of screenshots,
 * and all three had been in every screenshot since the mechanism existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DigitalTypeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** Nineteen minutes past six: a one, an eight, a one and a nine. */
    private fun oneAndEight(): Long =
        java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.AUGUST, 27, 18, 19, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun face(
        style: DigitStyle,
        w: Int = 1080,
        h: Int = 1400,
        weekday: Boolean = true,
        date: Boolean = true
    ): DigitalClockView = DigitalClockView(context).apply {
        theme = ClockThemes.MIDNIGHT
        this.style = style
        hour24 = true
        showSeconds = false
        showDate = date
        showWeekday = weekday
        atMs = oneAndEight()
        measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, w, h)
    }

    private fun shoot(view: View): Bitmap {
        val map = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(map))
        return map
    }

    /**
     * A one is the same size as an eight.
     *
     * Each cell was sized to fill its own box, and a box has two sides:
     * the size that fits across and the size that fits down, whichever
     * binds. A `1` is a quarter as wide as an `8` in the ink, so its
     * width never came near binding and it was drawn at the full height
     * of the cell, while every other digit was squeezed to fit across.
     * The face had been printing one numeral a fifth taller than its
     * neighbours since the first flip card, in every photograph, and it
     * reads as a broken font rather than as a layout fault.
     *
     * Measured as the height of the ink in each digit's own column,
     * which is the thing that was different.
     */
    @Test
    fun `every printed digit is the same height`() {
        for (style in listOf(DigitStyle.PLAIN, DigitStyle.CARD, DigitStyle.ROLLER)) {
            val map = shoot(face(style))
            // The time's own band: the tallest run of rows with ink in
            // them, which on this face is the row of big digits.
            val band = tallestBand(map, ClockThemes.MIDNIGHT.decimal)
            val runs = inkRuns(map, band, ClockThemes.MIDNIGHT.decimal)
            // Four digits and a colon. The colon is thrown away by its
            // width: it is a third of a cell and the digits are a cell.
            val widest = runs.maxOf { it.second - it.first }
            val digits = runs.filter { it.second - it.first > widest / 2 }
            assertEquals("$style did not draw four digits: $runs", 4, digits.size)
            val heights = digits.map { (from, to) -> inkHeight(map, band, from, to, ClockThemes.MIDNIGHT.decimal) }
            val tallest = heights.max()
            val shortest = heights.min()
            assertTrue(
                "$style drew one digit bigger than the others: $heights",
                tallest - shortest <= tallest * 0.06
            )
        }
    }

    /**
     * The day of the week is on a card too, hinged like the numbers.
     *
     * It was the one thing on a stack of leaves that was not printed on
     * one — a word floating beside them, in the phone's own typeface, on
     * nothing. The leaf starts to the left of the label, so on a card
     * face there is card *before* there is ink; without one, the first
     * thing on the row is the word itself.
     */
    @Test
    fun `the weekday is printed on a leaf of its own`() {
        val map = shoot(face(DigitStyle.CARD))
        val ground = ClockThemes.MIDNIGHT.face
        val band = dateBand(map, ground)
        var firstLeaf = Int.MAX_VALUE
        var firstInk = Int.MAX_VALUE
        val tall = band.second - band.first
        for (x in 0 until map.width) {
            var leaf = 0
            var ink = 0
            for (y in band.first until band.second) {
                val p = map.getPixel(x, y)
                if (p == ground) continue
                // A leaf is the rim colour at a sixth of its strength
                // over the face: barely off the ground, and nothing else
                // on this row is. Ink is anything louder.
                if (apart(p, ground) < 24) leaf++ else ink++
            }
            // Counted down the column rather than taken from one pixel.
            // A letter's anti-aliased edge is two or three pixels that
            // are also barely off the ground, so a single sample called
            // the first stroke of the T a leaf and the test passed with
            // the leaf taken away — which is what it was written to
            // catch. A card is a block: it fills most of the row.
            if (leaf > tall / 2 && x < firstLeaf) firstLeaf = x
            if (ink > 0 && x < firstInk) firstInk = x
        }
        assertTrue("nothing was drawn on the date row at all", firstInk < map.width)
        assertTrue(
            "the weekday sits on nothing: leaf at $firstLeaf, ink at $firstInk",
            firstLeaf < firstInk
        )
    }

    /**
     * A clock on its side drops the date.
     *
     * The block is centred as one object, so under a row of digits that
     * already fills a landscape screen the date pushes the time up off
     * the middle and hangs a small line of numbers in the space below it.
     */
    @Test
    fun `the date comes off when the clock is lying down`() {
        assertTrue("a portrait face lost its date", face(DigitStyle.PLAIN).datedForTest())
        assertFalse(
            "a landscape face kept its date",
            face(DigitStyle.PLAIN, w = 2340, h = 900).datedForTest()
        )
        // Square enough is standing up: a widget two cells by two is a
        // small clock, not a clock on its side.
        assertTrue(
            "a square widget was called landscape",
            face(DigitStyle.PLAIN, w = 400, h = 400).datedForTest()
        )
    }

    // ------------------------------------------------------------ reading

    private fun apart(a: Int, b: Int): Int = maxOf(
        Math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        Math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
        Math.abs((a and 0xFF) - (b and 0xFF))
    )

    /** Rows that hold ink of [colour], as the tallest unbroken stretch. */
    private fun tallestBand(map: Bitmap, colour: Int): Pair<Int, Int> {
        var best = 0 to 0
        var from = -1
        for (y in 0 until map.height) {
            var lit = false
            for (x in 0 until map.width) {
                if (apart(map.getPixel(x, y), colour) < 40) { lit = true; break }
            }
            if (lit && from < 0) from = y
            if (!lit && from >= 0) {
                if (y - from > best.second - best.first) best = from to y
                from = -1
            }
        }
        if (from >= 0 && map.height - from > best.second - best.first) best = from to map.height
        return best
    }

    /** And the lower one, which is the date. */
    private fun dateBand(map: Bitmap, ground: Int): Pair<Int, Int> {
        val bands = ArrayList<Pair<Int, Int>>()
        var from = -1
        for (y in 0 until map.height) {
            var lit = false
            for (x in 0 until map.width) {
                if (apart(map.getPixel(x, y), ground) >= 6) { lit = true; break }
            }
            if (lit && from < 0) from = y
            if (!lit && from >= 0) { bands += from to y; from = -1 }
        }
        if (from >= 0) bands += from to map.height
        return bands.last()
    }

    /** Stretches of columns holding ink, in [band]. */
    private fun inkRuns(map: Bitmap, band: Pair<Int, Int>, colour: Int): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var from = -1
        for (x in 0 until map.width) {
            var lit = false
            for (y in band.first until band.second) {
                if (apart(map.getPixel(x, y), colour) < 40) { lit = true; break }
            }
            if (lit && from < 0) from = x
            if (!lit && from >= 0) { runs += from to x; from = -1 }
        }
        if (from >= 0) runs += from to map.width
        return runs
    }

    /** How tall the ink is between two columns. */
    private fun inkHeight(
        map: Bitmap,
        band: Pair<Int, Int>,
        from: Int,
        to: Int,
        colour: Int
    ): Int {
        var top = -1
        var bottom = -1
        for (y in band.first until band.second) {
            var lit = false
            for (x in from until to) {
                if (apart(map.getPixel(x, y), colour) < 40) { lit = true; break }
            }
            if (lit) {
                if (top < 0) top = y
                bottom = y
            }
        }
        return if (top < 0) 0 else bottom - top
    }
}
