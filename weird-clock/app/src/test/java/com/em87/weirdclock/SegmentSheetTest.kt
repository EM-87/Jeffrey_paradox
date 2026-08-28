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
}
