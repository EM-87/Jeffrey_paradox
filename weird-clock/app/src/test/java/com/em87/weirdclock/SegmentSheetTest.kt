package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Specimen sheets, drawn to be held against the drawings they copy.
 *
 * The two Roman ones are the same two strings the original drawing shows —
 * `MMXXIV` and `VII·XII` — so the copy can be compared with the thing it
 * is a copy of rather than admired on its own. That is the whole method
 * here: a display is not right because the code says the right bars are
 * lit, it is right when it looks like the drawing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentSheetTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun sheet(
        name: String,
        kind: Segments.Kind,
        masks: IntArray,
        weight: Float = 1f,
        ghosts: Boolean = true,
        burnt: IntArray? = null,
        background: Int = 0xFF1B1E28.toInt(),
        ink: Int = 0xFF00E5FF.toInt(),
        dark: Int = 0xFF6B7284.toInt()
    ): Int {
        val height = 260
        val cell = height * Segments.aspect(kind)
        val span = masks.size + (masks.size - 1) * Segments.gap(kind)
        val width = (cell * span).toInt() + 80
        val bitmap = Bitmap.createBitmap(width, height + 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        SegmentPainter().apply {
            this.weight = weight
            this.ghosts = ghosts
        }.row(
            canvas, kind, masks,
            40f, 40f, cell * span, height.toFloat(),
            ink, dark, burnt
        )
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val seen = HashSet<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen.add(bitmap.getPixel(x, y)); x += 3
            }
            y += 3
        }
        return seen.size
    }

    /**
     * How thick the bars actually come out, measured off the pixels.
     *
     * This is the test the thickness knob needed and did not have. It was
     * a share of the module's height and became a multiple of what each
     * display was drawn at — a change of meaning, not of value — and three
     * places went on passing the old number to the new knob. The suite was
     * green: not one of nine hundred tests looked at how much metal was on
     * the glass. A screenshot did, and the display was a cobweb.
     *
     * So: light one module, count the lit pixels, and compare with what
     * the bars in it ought to cover. Loose bounds on purpose — this is not
     * a golden image, it is a smoke alarm.
     */
    @Test
    fun `a bar is as thick as the display it belongs to`() {
        for ((kind, glyph) in listOf(
            Segments.Kind.SEVEN to '8',
            Segments.Kind.SIXTEEN to 'D',
            Segments.Kind.STAR to '8'
        )) {
            val height = 260f
            val cell = height * Segments.aspect(kind)
            val bitmap = Bitmap.createBitmap(cell.toInt() + 40, height.toInt() + 40, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF000000.toInt())
            SegmentPainter().apply { ghosts = false }.row(
                canvas, kind, Segments.spell(kind, "$glyph"),
                20f, 20f, cell, height, 0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            )
            var ink = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) and 0xFF > 128) ink++
                }
            }
            // Each bar covers about its own length times its thickness,
            // and the lengths come out of the same table the drawing does.
            val lit = Segments.plan(kind, Segments.spell(kind, "$glyph")).filter { it.lit }
            val run = lit.sumOf {
                val dx = (it.bar.x1 - it.bar.x0) * cell
                val dy = (it.bar.y1 - it.bar.y0) * height
                kotlin.math.hypot(dx, dy).toDouble()
            }.toFloat()
            val expected = run * height * Segments.native(kind)
            assertTrue(
                "$kind draws $ink lit pixels where about ${expected.toInt()} was expected",
                ink > expected * 0.4f && ink < expected * 2.5f
            )
        }
    }

    /** The year the drawing shows, in the display the drawing describes. */
    @Test
    fun `Rome writes the year on the drawing's own module`() {
        assertTrue(
            shootRoman("segsheet-roman-mmxxiv", "MMXXIV") > 3
        )
        assertTrue(
            shootRoman("segsheet-roman-viixii", "VII·XII") > 3
        )
        // And the two the year hides: a nulla, and the letters that only
        // turn up in the hundreds.
        assertTrue(shootRoman("segsheet-roman-alphabet", "IVXLCDMN") > 3)
        assertTrue(shootRoman("segsheet-roman-nulla", "N·N·N") > 3)
    }

    private fun shootRoman(name: String, text: String): Int =
        sheet(name, Segments.Kind.SIXTEEN, Segments.spell(Segments.Kind.SIXTEEN, text))

    /**
     * The specimen strip itself: ten modules with every bar lit.
     *
     * This is the one picture that can be laid straight over the drawing,
     * because the drawing *is* ten blank modules. Everything the file
     * settled — one upright per boundary and eleven for ten modules, the
     * fatter diagonals, the daylight where a bar points at another bar —
     * is visible in it or is not there.
     */
    @Test
    fun `the drawing's own specimen strip`() {
        val every = Segments.bars(Segments.Kind.SIXTEEN).fold(0) { a, b -> a or b.bit }
        assertTrue(
            sheet("segsheet-roman-strip", Segments.Kind.SIXTEEN, IntArray(10) { every }) > 3
        )
        // And theirs, which has four bars in the middle that no numeral
        // ever lights and that only a sheet like this shows.
        val stars = Segments.bars(Segments.Kind.STAR).fold(0) { a, b -> a or b.bit }
        assertTrue(
            sheet("segsheet-star-strip", Segments.Kind.STAR, IntArray(3) { stars }) > 3
        )
    }

    /** Their ten numerals, in the order the chart lists them. */
    @Test
    fun `their numerals, nought to nine`() {
        assertTrue(
            sheet(
                "segsheet-star-digits", Segments.Kind.STAR,
                Segments.spell(Segments.Kind.STAR, "0123456789")
            ) > 3
        )
        // With the unlit arms taken away, which is the shape on its own.
        assertTrue(
            sheet(
                "segsheet-star-bare", Segments.Kind.STAR,
                Segments.spell(Segments.Kind.STAR, "0123456789"), ghosts = false
            ) > 3
        )
    }

    /** And ours, for the family likeness. */
    @Test
    fun `our ten, and the thickness knob`() {
        val ours = Segments.spell(Segments.Kind.SEVEN, "0123456789")
        assertTrue(sheet("segsheet-seven-digits", Segments.Kind.SEVEN, ours) > 3)
        assertTrue(sheet("segsheet-seven-thin", Segments.Kind.SEVEN, ours, weight = 0.7f) > 3)
        assertTrue(sheet("segsheet-seven-fat", Segments.Kind.SEVEN, ours, weight = 2.5f) > 3)
    }

    /** A display with three bars poked out of it, still trying to say 08:15. */
    @Test
    fun `a display somebody has poked`() {
        val masks = Segments.spell(Segments.Kind.SEVEN, "0815")
        assertTrue(
            sheet(
                "segsheet-seven-burnt", Segments.Kind.SEVEN, masks,
                burnt = intArrayOf(Segments.A, Segments.G, 0, Segments.C)
            ) > 3
        )
    }

    /**
     * The Comet's ten, and the strip with all nine lit.
     *
     * The strip is the one to hold against the drawing: the drawing *is*
     * four cells with every segment lit, so anything in it that is not in
     * the picture — the hook on the end of the top rail, the taper on the
     * arms, the daylight between the cells — is a thing that got lost on
     * the way out of the file.
     */
    @Test
    fun `the calculator's ten, and its specimen strip`() {
        val nine = Segments.Kind.NINE
        val ten = Segments.spell(nine, "0123456789")
        assertTrue(sheet("segsheet-comet-digits", nine, ten) > 3)
        assertTrue(sheet("segsheet-comet-bare", nine, ten, ghosts = false) > 3)
        val every = Segments.bars(nine).fold(0) { a, b -> a or b.bit }
        assertTrue(sheet("segsheet-comet-strip", nine, IntArray(4) { every }) > 3)
        // The four the drawing itself spells, and a clock time.
        assertTrue(sheet("segsheet-comet-1243", nine, Segments.spell(nine, "1243")) > 3)
        assertTrue(
            sheet(
                "segsheet-comet-thin", nine, Segments.spell(nine, "1243"), weight = 0.6f
            ) > 3
        )
        assertTrue(
            sheet(
                "segsheet-comet-fat", nine, Segments.spell(nine, "1243"), weight = 1.8f
            ) > 3
        )
    }

    /**
     * The thickness knob on a bar that bends.
     *
     * The other displays widen a bar by pushing its edges away from the
     * straight line between its ends, and on this one that is the wrong
     * operation — the top rail is a hairline with a hook on it, and
     * measuring off the chord between the hook's tip and the rail's end
     * would deepen the hook instead of thickening the rail. So these bars
     * are grown outwards from their own edges instead, and this is what
     * says the growing works: more metal at every step up, and none of it
     * outside the cell it belongs to.
     *
     * The second half is the one that matters. An outward offset with its
     * sign the wrong way round makes a heavier setting *thinner*, which is
     * exactly what the first picture of this showed and what no assertion
     * about "it drew something" would have caught.
     */
    @Test
    fun `a curved bar grows outwards and stays in its cell`() {
        val nine = Segments.Kind.NINE
        val masks = Segments.spell(nine, "8")
        val height = 300f
        val cell = height * Segments.aspect(nine)
        var last = 0
        for (weight in listOf(0.6f, 1.0f, 1.4f, 1.8f)) {
            // Room all round, so ink that escapes the cell is visible
            // rather than clipped off the edge of the bitmap.
            val pad = 60
            val bitmap = Bitmap.createBitmap(
                cell.toInt() + pad * 2, height.toInt() + pad * 2, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF000000.toInt())
            SegmentPainter().apply { ghosts = false; this.weight = weight }.row(
                canvas, nine, masks, pad.toFloat(), pad.toFloat(), cell, height,
                0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            )
            var ink = 0
            var minX = bitmap.width
            var maxX = 0
            var minY = bitmap.height
            var maxY = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) and 0xFF <= 128) continue
                    ink++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            assertTrue(
                "at $weight the display has $ink lit pixels, against $last before it",
                ink > last
            )
            last = ink
            // The arms lean out over the module's edges by a hair, which
            // is what the gap between cells is for. A tenth of a module is
            // that hair; anything more is metal running away.
            val slack = cell * 0.10f
            assertTrue(
                "at $weight the ink runs from $minX to $maxX outside its cell",
                minX > pad - slack && maxX < pad + cell + slack
            )
            assertTrue(
                "at $weight the ink runs from $minY to $maxY outside its cell",
                minY > pad - slack && maxY < pad + height + slack
            )
        }
    }
}
