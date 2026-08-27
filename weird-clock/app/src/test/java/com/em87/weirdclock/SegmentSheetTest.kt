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
        thickness: Float = 0.055f,
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
            this.thickness = thickness
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
        assertTrue(sheet("segsheet-seven-thin", Segments.Kind.SEVEN, ours, thickness = 0.045f) > 3)
        assertTrue(sheet("segsheet-seven-fat", Segments.Kind.SEVEN, ours, thickness = 0.15f) > 3)
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
