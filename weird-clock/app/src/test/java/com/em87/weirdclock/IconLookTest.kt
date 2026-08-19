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
 * A picture of the toolbox, written out so somebody can look at it.
 *
 * It was a spanner and a screwdriver crossed, and at the size it is
 * actually drawn the two solid shapes had no daylight between them: what
 * arrived on the glass was a blot. That is not a thing arithmetic can
 * catch — it is a thing you have to see — so this renders the icon and
 * writes the PNG, and asserts only the two properties a blot fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconLookTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /**
     * Draws [icon] at the size a button actually uses and reports how much
     * of it is ink.
     */
    private fun shoot(icon: Int, name: String, scale: Int = 1): Float {
        val px = (24 * context.resources.displayMetrics.density).toInt() * scale
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, icon)!!
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF101010.toInt())
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        var ink = 0
        for (y in 0 until px) for (x in 0 until px) {
            if (bitmap.getPixel(x, y) != 0xFF101010.toInt()) ink++
        }
        bitmap.recycle()
        return ink.toFloat() / (px * px)
    }

    /**
     * The icon is a shape, not a blot.
     *
     * A blot is ink over most of its own square; a shape leaves the
     * background visible around and inside it. Nothing here says the
     * drawing is *good* — that is what the PNG beside this test is for —
     * only that it is not solid.
     */
    @Test
    fun `the toolbox is a shape with daylight in it`() {
        // Twice: once at the size it is used, which is the one that
        // matters, and once blown up, which is the one you can see the
        // shape of the jaw in.
        shoot(R.drawable.ic_toolbox, "ic_toolbox-large", scale = 6)
        val ink = shoot(R.drawable.ic_toolbox, "ic_toolbox")
        assertTrue("the icon is empty: $ink", ink > 0.12f)
        assertTrue("the icon is a solid blot: $ink of its square is ink", ink < 0.55f)
    }

    /**
     * And the spanner is in two pieces, with the screwdriver between them.
     *
     * The failure this icon keeps having is not "too little ink" or "too
     * much" — it is the two shapes fusing into one lump, which happens the
     * moment the channel between them closes. So walk the spanner's own
     * axis, corner to corner, and count the runs of ink: jaw and shaft,
     * then a hair of background, then the screwdriver's handle lying
     * across, then background again, then the other shaft and jaw. Three
     * runs if the channel is open, one if it has closed.
     *
     * The first version of this walked a horizontal line a quarter of the
     * way down, found the spanner's jaw and the screwdriver's blade, and
     * reported two runs whatever happened at the crossing — it was
     * measuring that there are two tools, which was never in doubt.
     */
    @Test
    fun `the spanner is broken where the screwdriver crosses it`() {
        val px = (24 * context.resources.displayMetrics.density).toInt() * 6
        val drawable = androidx.core.content.ContextCompat.getDrawable(
            context, R.drawable.ic_toolbox
        )!!
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF101010.toInt())
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))

        var runs = 0
        var wasInk = false
        for (i in 0 until px) {
            val ink = bitmap.getPixel(i, i) != 0xFF101010.toInt()
            if (ink && !wasInk) runs++
            wasInk = ink
        }
        bitmap.recycle()
        assertTrue(
            "the spanner runs unbroken under the screwdriver: " +
                "$runs run(s) of ink along its own axis",
            runs >= 3
        )
    }
}
