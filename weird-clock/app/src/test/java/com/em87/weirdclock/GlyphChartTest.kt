package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A chart of every glyph in the two other alphabets, written to a PNG.
 *
 * Shapes cannot be asserted. There is no number that says whether an `M`
 * looks like an `M` or whether two marks on a star are far enough apart to
 * be different digits, and the previous attempt at this alphabet was
 * written blind and came out as noise — bits chosen so the counts came out
 * right, never once drawn and looked at.
 *
 * So this is a camera. It checks only that ink went down, and its real
 * output is the file: build/screenshots/glyphs-*.png, which somebody
 * opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlyphChartTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun view(w: Int, h: Int): ClockView {
        val v = ClockView(context)
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, w, h)
        return v
    }

    /** Draws [rows] of text, one under the other, and returns the ink count. */
    private fun chart(
        name: String, rows: List<Pair<String, Int>>, digitH: Float, w: Int = 1080
    ): Int {
        val rowH = (digitH * 1.9f).toInt()
        val h = rowH * rows.size + rowH / 2
        val v = view(w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x1d, 0x21, 0x29))
        rows.forEachIndexed { i, (text, starFrom) ->
            v.drawScriptForTest(
                canvas, text, w / 2f, rowH * i + rowH * 0.55f, digitH, starFrom
            )
        }
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        var ink = 0
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            if (bitmap.getPixel(x, y) != Color.rgb(0x1d, 0x21, 0x29)) ink++
        }
        return ink
    }

    /** Every shape the sixteen-bar module can make, big enough to judge. */
    @Test
    fun `the sixteen bar alphabet`() {
        val ink = chart(
            "glyphs-sixteen",
            listOf(
                "IVXLCDM" to Int.MAX_VALUE,
                "MDCLXVI" to Int.MAX_VALUE,
                "III·IX·MMXXVI" to Int.MAX_VALUE,
                "XXXI·XII·MDCCCLXXXVIII" to Int.MAX_VALUE
            ),
            digitH = 120f
        )
        assertTrue("nothing was drawn", ink > 500)
    }

    /** And the same at the size the orrery card actually uses. */
    @Test
    fun `the sixteen bar alphabet at the size it is used`() {
        // digitH is r * 0.13 and r is about a third of the width.
        val ink = chart(
            "glyphs-sixteen-small",
            listOf(
                "III·IX·MMXXVI" to Int.MAX_VALUE,
                "XXXI·XII·MDCCCLXXXVIII" to Int.MAX_VALUE
            ),
            digitH = 47f
        )
        assertTrue("nothing was drawn", ink > 100)
    }

    /**
     * And the sky itself, wound to a year in each script.
     *
     * A chart of glyphs says the alphabet is right; it says nothing about
     * whether the row fits under the orrery, sits where the ordinary date
     * sits, or is bright enough to read against the orbits. Only the real
     * card can say that, so here it is, three times.
     */
    @Test
    fun `the sky at a year in each script`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            val clock = activity.clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            val root = activity.window.decorView
            root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2200, android.view.View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2200)
            for (year in listOf(-4000, -3200, -2560, -1250, 1750, 1800, 1888, 2026, 3400)) {
                clock.windOrreryToYearForTest(year)
                val bitmap = Bitmap.createBitmap(1080, 2200, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(outDir, "sky-$year.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    /**
     * The visitors, at a few of the years they visit in.
     *
     * Four ellipses laid over eight circles is the kind of thing that
     * either reads at a glance or is a ball of wool, and no number says
     * which.
     */
    @Test
    fun `the comets on the sky`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .putBoolean(Prefs.COMETS, true)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            val clock = activity.clockForTest()
            clock.toggleOrrery()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(900)
            )
            val root = activity.window.decorView
            root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2200, android.view.View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 1080, 2200)
            // 1986 is Halley at perihelion; 2061 is the next one; 2026 is
            // an ordinary year with all four out at the far end. The other
            // three are the wind back: Encke is the first to go, then
            // Halley, and by the Bronze Age there are no visitors at all,
            // because a fixed period counted that many times over is not a
            // position — see [Comets.trust].
            for (year in listOf(1986, 2026, 2061, 1900, 1200, -2000)) {
                clock.windOrreryToYearForTest(year)
                val bitmap = Bitmap.createBitmap(1080, 2200, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(outDir, "comets-$year.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    /**
     * The shadows, at three latitudes and three times of day.
     *
     * Nine dials in a grid, because the whole point of doing this with the
     * real sun is that the answer is different in different places, and a
     * single picture of one of them proves nothing about that.
     */
    @Test
    fun `the hand shadows around the world`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().commit()
        val side = 340
        val places = listOf("equator" to 0.0, "madrid" to 40.4, "tromso" to 69.6)
        val hours = listOf(9, 12, 16)
        val w = side * hours.size
        val h = side * places.size
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x1d, 0x21, 0x29))
        var ink = 0
        places.forEachIndexed { row, (_, lat) ->
            hours.forEachIndexed { col, hour ->
                val v = view(side, side)
                v.showDate = false
                v.handShadows = true
                v.shadowLatitude = lat
                v.shadowLongitude = 0.0
                // June, so the sun is as high as it gets and the three
                // latitudes are as far apart as they get.
                val cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC")
                )
                cal.clear()
                cal.set(2026, 5, 21, hour, 0)
                v.freezeAtForTest(cal.timeInMillis)
                canvas.save()
                canvas.translate(side * col.toFloat(), side * row.toFloat())
                v.draw(canvas)
                canvas.restore()
                if (v.sunOverhead() != null) ink++
            }
        }
        // And one big one, because nine small dials show that the three
        // rows differ and nothing about whether a single shadow is any
        // good. Tromsø in the late afternoon: the sun low, the shadows
        // long, the three of them plainly three.
        val big = view(900, 900)
        big.showDate = false
        big.handShadows = true
        big.shadowLatitude = 69.6
        big.shadowLongitude = 0.0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2026, 5, 21, 19, 40)
        big.freezeAtForTest(cal.timeInMillis)
        val one = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        val oneCanvas = Canvas(one)
        oneCanvas.drawColor(Color.rgb(0x1d, 0x21, 0x29))
        big.draw(oneCanvas)
        File(outDir, "hand-shadows-close.png").outputStream().use {
            one.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        File(outDir, "hand-shadows.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("the sun was down in every one of the nine", ink > 0)
    }

    /**
     * The dial by moonlight.
     *
     * The thing that cannot be asserted: whether a shadow at a third of a
     * daylight one, in a blue nobody can name, over a dial turned down for
     * the bedroom, is a mood or a smudge. Beside it the same dial in the
     * afternoon, so the two can be set against each other.
     */
    @Test
    fun `the dial by moonlight`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().commit()
        val side = 900
        // A night with the moon well up and nearly full, found rather than
        // guessed: a picture taken on a new-moon night would correctly show
        // nothing at all and prove only that the search was not done.
        var night = 0L
        for (step in 0 until 24 * 40) {
            val at = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                .apply { clear(); set(2026, 0, 1, 0, 0) }.timeInMillis + step * 3_600_000L
            if (SolarTime.position(40.0, 0.0, at).altitudeDeg > -8.0) continue
            if (SolarTime.moonPosition(40.0, 0.0, at).altitudeDeg < 30.0) continue
            if (SolarTime.moonIllumination(at) < 0.9) continue
            night = at
            break
        }
        assertTrue("no bright moonlit night in forty days", night != 0L)
        val day = night + 12 * 3_600_000L

        for ((name, at) in listOf("moonlit" to night, "sunlit" to day)) {
            val v = view(side, side)
            v.showDate = false
            v.handShadows = true
            v.shadowLatitude = 40.0
            v.shadowLongitude = 0.0
            v.freezeAtForTest(at)
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0x1d, 0x21, 0x29))
            v.draw(canvas)
            File(outDir, "shadows-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /**
     * The hieroglyph numerals, one of each sign and a few real numbers.
     *
     * The only way to find out whether a coil of rope at eight pixels is
     * a coil of rope or a smudge.
     */
    @Test
    fun `the hieroglyph numerals`() {
        val w = 1080
        val rowH = 150
        val rows = listOf(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
            listOf(10, 100, 1000, 10_000, 100_000, 1_000_000),
            listOf(15, 6, 1251),
            listOf(31, 12, 3999)
        )
        val bitmap = Bitmap.createBitmap(w, rowH * rows.size + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x1d, 0x21, 0x29))
        val v = view(w, rowH * rows.size + 40)
        rows.forEachIndexed { r, values ->
            var x = 40f
            for (value in values) {
                x += v.drawEgyptianForTest(canvas, value, x, 20f + r * rowH.toFloat(), 110f)
                x += 46f
            }
        }
        File(outDir, "glyphs-egyptian.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        var ink = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until w step 2) {
            if (bitmap.getPixel(x, y) != Color.rgb(0x1d, 0x21, 0x29)) ink++
        }
        assertTrue("nothing was carved", ink > 300)
    }

    /**
     * The wedges, one to nine, the tens, and a few real dates.
     *
     * There are only two shapes in the whole script, so the only thing
     * worth looking at is whether they can be told apart in a heap and
     * whether the gap between one sexagesimal place and the next is
     * plainly wider than the gap inside one — which is the entire
     * difference between "one, twenty" and "eighty".
     */
    @Test
    fun `the cuneiform numerals`() {
        val w = 1080
        val rowH = 150
        val rows = listOf(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
            listOf(10, 20, 30, 40, 50, 59),
            listOf(60, 61, 80, 3600, 3601),
            listOf(15, 6, 3001),
            listOf(31, 12, 3499)
        )
        val bitmap = Bitmap.createBitmap(w, rowH * rows.size + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x1d, 0x21, 0x29))
        val v = view(w, rowH * rows.size + 40)
        rows.forEachIndexed { r, values ->
            var x = 40f
            for (value in values) {
                x += v.drawCuneiformForTest(canvas, value, x, 20f + r * rowH.toFloat(), 110f)
                x += 52f
            }
        }
        File(outDir, "glyphs-cuneiform.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        var ink = 0
        for (y in 0 until bitmap.height step 2) for (x in 0 until w step 2) {
            if (bitmap.getPixel(x, y) != Color.rgb(0x1d, 0x21, 0x29)) ink++
        }
        assertTrue("nothing was pressed into the clay", ink > 300)
    }

    /** The ten marks on the star, in a row like the table they came from. */
    @Test
    fun `the star alphabet`() {
        val ink = chart(
            "glyphs-star",
            listOf(
                "0123456789" to 0,
                "0·1·2·3·4·5·6·7·8·9" to 0,
                "03 13 3400" to 0,
                "31 12 9999" to 0
            ),
            digitH = 120f
        )
        assertTrue("nothing was drawn", ink > 500)
    }
}
