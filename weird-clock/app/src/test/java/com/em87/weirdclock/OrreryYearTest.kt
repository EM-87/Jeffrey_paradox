package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How far you have wound, written in the writing of where you got to.
 *
 * The sky can be carried centuries either way and a row of ordinary digits
 * says nothing about the distance: 1804 and 3211 look equally like today
 * until you stop and read them. So the year changes alphabet with the
 * distance — Roman behind us, digits through this millennium, and
 * something that is not ours past three thousand.
 *
 * The whole date goes with it — day, month and year. Half a date in one
 * alphabet and half in another is two displays sharing a row, and the joke
 * is that the sky has been wound somewhere dates are written differently,
 * not that a third of one has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class OrreryYearTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    // ------------------------------------------------- which alphabet

    /**
     * The months are written on the ring, and only when the ring is a
     * calendar.
     *
     * Zoomed out this is a solar system and the year marks are not drawn
     * at all; twelve words round the rim of a solar system would be a
     * label on the wrong thing. They arrive with the day ticks, on the
     * same fade, when the pinch has gone far enough that the Earth's orbit
     * is the rim.
     *
     * Caught as they are drawn rather than looked for in the pixels: a
     * three-letter word bent round a circle at four per cent of the radius
     * is a handful of grey pixels, and "is there ink here" cannot tell it
     * from a tick.
     */
    @Test
    fun `the months are written round the ring, and only when it is a calendar`() {
        val zoomedOut = monthsDrawn(1f)
        assertTrue("a solar system was labelled with months: $zoomedOut", zoomedOut.isEmpty())
        val zoomedIn = monthsDrawn(Orrery.MAX_ZOOM)
        assertEquals("the ring is not labelled with twelve months: $zoomedIn", 12, zoomedIn.size)
        // Every month once, and the year's own first month at both ends of
        // the ring is not two labels — the ring runs solstice to solstice,
        // so December is the first month and the last one is November.
        assertEquals("a month was written twice", zoomedIn.size, zoomedIn.toSet().size)
    }

    /**
     * And they are written in the alphabet of the century they are in.
     *
     * The date under the dial has changed script with the year for several
     * versions — Roman letters behind us, digits through this millennium —
     * and the months round the ring went on saying "Aug" through all of
     * it. Half a display in one alphabet and half in another is the joke
     * failing rather than landing: the point is that the sky has been
     * wound somewhere dates are written differently, not that a third of
     * one has.
     *
     * Before the year one they have no names at all. These are Julian and
     * Gregorian months on a ring wound past the invention of either, and
     * naming them would be the dial claiming a calendar nobody had — which
     * is the rule the date row already follows there.
     */
    @Test
    fun `the months are written in the alphabet of their century`() {
        val res = context.resources
        val latin = res.getStringArray(R.array.lat_months).toList()
        assertEquals(
            "a Roman year is not labelled in Latin",
            latin, OrreryDial.monthNamesFor(res, 1750)?.toList()
        )
        assertEquals(
            "the year one is not labelled in Latin",
            latin, OrreryDial.monthNamesFor(res, 1)?.toList()
        )
        assertNotEquals(
            "this millennium is being labelled in Latin",
            latin, OrreryDial.monthNamesFor(res, 2026)?.toList()
        )
        for (year in listOf(0, -44, -1500, -3500)) {
            assertNull(
                "a ring wound to $year is labelled with months Rome had not invented",
                OrreryDial.monthNamesFor(res, year)
            )
        }
    }

    /** And the Latin ones are Latin, not English with the vowels moved. */
    @Test
    fun `the latin months are spelled the way Rome spelled them`() {
        val latin = context.resources.getStringArray(R.array.lat_months)
        assertEquals("there are not twelve of them", 12, latin.size)
        // Rome had no letter U: Iunius, Iulius, Augustus, Maius.
        assertEquals("IVN", latin[5])
        assertEquals("IVL", latin[6])
        assertEquals("AVG", latin[7])
        assertEquals("MAI", latin[4])
        assertTrue(
            "a Latin month is written with a letter Rome did not have",
            latin.none { m -> m.any { it == 'U' || it == 'J' } }
        )
    }

    /** The words that went onto the ring at a given zoom. */
    private fun monthsDrawn(zoom: Float): List<String> {
        val names = java.text.DateFormatSymbols.getInstance().shortMonths.filter { it.isNotEmpty() }
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = (controller.get() as MainActivity).clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.zoomOrrery(zoom)
        val bitmap = android.graphics.Bitmap.createBitmap(
            clock.width.coerceAtLeast(1), clock.height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val seen = ArrayList<String>()
        clock.draw(object : android.graphics.Canvas(bitmap) {
            override fun drawTextOnPath(
                text: String,
                path: android.graphics.Path,
                hOffset: Float,
                vOffset: Float,
                paint: android.graphics.Paint
            ) {
                if (text in names) seen += text
                super.drawTextOnPath(text, path, hOffset, vOffset, paint)
            }
        })
        bitmap.recycle()
        return seen
    }

    /**
     * The words beside the date change alphabet with it.
     *
     * You asked for the *texts* in the far-future script, not only the
     * numbers, and for a long time only the numbers changed — the event
     * caption under the dial and the little name bubble over a planet went
     * on being written in ours. Half a display in one alphabet and half in
     * another is the joke failing rather than landing.
     *
     * Only in that era. The other scripts on this dial are drawn glyph by
     * glyph rather than typed, so a word in them is not something a
     * typeface can do — and the honest thing there is what the ring already
     * does with month names: say nothing rather than say it wrong.
     */
    @Test
    fun `the words change alphabet with the date`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))

        clock.windOrreryToYearForTest(3400)
        assertNotNull("the far future has no face to speak in", clock.eraFaceForTest())
        for (year in listOf(2026, 1750, -1500)) {
            clock.windOrreryToYearForTest(year)
            assertNull(
                "the year $year is being written in the far-future face",
                clock.eraFaceForTest()
            )
        }
    }

    /** The three eras, and the two years they change on. */
    @Test
    fun `the year changes alphabet at two thousand and at three thousand`() {
        assertEquals(OrreryYear.Script.ROMAN, OrreryYear.scriptFor(1))
        assertEquals(OrreryYear.Script.ROMAN, OrreryYear.scriptFor(1999))
        assertEquals(OrreryYear.Script.DIGITS, OrreryYear.scriptFor(2000))
        assertEquals(OrreryYear.Script.DIGITS, OrreryYear.scriptFor(2999))
        assertEquals(OrreryYear.Script.YAUTJA, OrreryYear.scriptFor(3000))
        assertEquals(OrreryYear.Script.YAUTJA, OrreryYear.scriptFor(4321))
    }

    /** Roman years are Roman; the others stay as digits. */
    @Test
    fun `only the years behind us are written in letters`() {
        assertEquals("MCMXCIX", OrreryYear.yearText(1999, OrreryYear.Script.ROMAN))
        assertEquals("2026", OrreryYear.yearText(2026, OrreryYear.Script.DIGITS))
        assertEquals("3211", OrreryYear.yearText(3211, OrreryYear.Script.YAUTJA))
    }

    /**
     * And a year Roman cannot say is not said in Roman.
     *
     * There is no nought in it and no way to say "before", so wound back
     * past the year one the Roman writer returns an empty string — a date
     * with a hole where its year should be, which looks like a display
     * fault rather than like a very old date.
     */
    @Test
    fun `a year before Rome is not written in Roman`() {
        assertEquals("0", OrreryYear.yearText(0, OrreryYear.Script.ROMAN))
        assertEquals("-44", OrreryYear.yearText(-44, OrreryYear.Script.ROMAN))
        assertEquals("I", OrreryYear.yearText(1, OrreryYear.Script.ROMAN))
    }

    // ------------------------------------------------- on the dial

    /**
     * The whole date changes script together — day, month and year.
     *
     * It used to be the year alone, with the day and the month left in
     * Arabic beside it, which is two displays sharing a row. The joke is
     * that the sky has been wound somewhere the date is written
     * differently, not that a third of it has.
     */
    @Test
    fun `the whole date changes script together`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = (controller.get() as MainActivity).clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))

        for (year in listOf(1750, 2026, 3400)) {
            clock.windOrreryToYearForTest(year)
            val text = clock.orreryDateDigits()
            assertEquals(
                "the sky is writing $year in the wrong alphabet",
                OrreryYear.scriptFor(year), clock.orreryScript()
            )
            assertTrue(
                "'$text' does not end with the year it is standing on",
                text.endsWith(OrreryYear.yearText(year, OrreryYear.scriptFor(year)))
            )
            // Roman groups are spaced; digits are separated by an oblique
            // stroke, which is a thing the seven-bar row draws for itself.
            val groups = text.split(" ", "/")
            assertEquals("'$text' is not a day, a month and a year", 3, groups.size)
            if (OrreryYear.scriptFor(year) == OrreryYear.Script.ROMAN) {
                assertTrue(
                    "'$text' has Arabic digits in it beside a Roman year",
                    groups.all { g -> g.none { it.isDigit() } }
                )
                assertTrue(
                    "'$text' is not written in Roman letters at all",
                    groups.all { g -> g.all { it in "IVXLCDM" } }
                )
            } else {
                assertTrue(
                    "'$text' is not day, month and year in digits",
                    groups.all { g -> g.all { it.isDigit() } }
                )
                assertEquals(
                    "'$text' has no oblique strokes between its groups",
                    2, text.count { it == '/' }
                )
            }
        }
    }

    /**
     * Each script goes to the display that can show it.
     *
     * Counted rather than looked at, because the way this goes wrong is
     * silent: pushed through the seven-bar row, `MDCCL` is not drawn
     * badly, it is not drawn at all — every letter falls through the digit
     * branch and the year simply is not there, leaving a date that reads
     * `06 15` and looks finished.
     */
    @Test
    fun `each script is drawn on the display that can show it`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = (controller.get() as MainActivity).clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        val canvas = android.graphics.Canvas(
            android.graphics.Bitmap.createBitmap(1080, 2000, android.graphics.Bitmap.Config.ARGB_8888)
        )
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1080, 2000)

        // 1980 rather than 1750: the sixteen-bar module is a piece of
        // nineteen-seventies electronics, and a Roman year older than the
        // display is set in type instead — which is the next test.
        clock.windOrreryToYearForTest(1980)
        var date = clock.orreryDateDigits()
        clock.draw(canvas)
        assertEquals(
            "a Roman year did not reach the sixteen-bar display",
            date.length, clock.barsPaintedForTest()
        )
        assertEquals("and it is not in the far-future face", 0, clock.yautjaCharsForTest())

        clock.windOrreryToYearForTest(3400)
        date = clock.orreryDateDigits()
        clock.draw(canvas)
        assertEquals(
            "the whole far-future date is not written in the far-future face",
            date.length, clock.yautjaCharsForTest()
        )
        assertEquals(
            "part of the far-future date is on the other display",
            0, clock.barsPaintedForTest()
        )

        clock.windOrreryToYearForTest(2026)
        clock.draw(canvas)
        assertEquals(
            "an ordinary year was sent somewhere other than the seven-bar row",
            0, clock.barsPaintedForTest() + clock.yautjaCharsForTest()
        )
    }

    /**
     * A Roman year older than the display it would be shown on is set in
     * type instead.
     *
     * The sixteen-bar module is nineteen-seventies electronics. A date
     * from 1750 lit up on one is the same sort of anachronism as Neptune
     * over Babylon, and the line falls inside the Roman era rather than at
     * the edge of it — 1970 is not where the numerals changed, it is where
     * the technology for showing them arrived.
     */
    @Test
    fun `a roman year older than the display is printed rather than lit`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = (controller.get() as MainActivity).clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        val canvas = android.graphics.Canvas(
            android.graphics.Bitmap.createBitmap(
                1080, 2000, android.graphics.Bitmap.Config.ARGB_8888
            )
        )
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1080, 2000)

        clock.windOrreryToYearForTest(1750)
        clock.draw(canvas)
        assertTrue(
            "a date from 1750 was not set in type",
            clock.printedCharsForTest() > 0
        )
        assertEquals(
            "a date from 1750 was lit up on a display that did not exist",
            0, clock.barsPaintedForTest()
        )

        // And on the other side of the line it is lit after all, or the
        // assertion above is only saying that Roman dates are never lit.
        clock.windOrreryToYearForTest(1980)
        clock.draw(canvas)
        assertTrue("a date from 1980 was not lit", clock.barsPaintedForTest() > 0)
        assertEquals(
            "a date from 1980 was set in type",
            0, clock.printedCharsForTest()
        )
    }

    /** The line itself, without a canvas. */
    @Test
    fun `the display arrives in nineteen seventy`() {
        assertTrue(OrreryYear.isPrinted(1969, OrreryYear.Script.ROMAN))
        assertTrue("1970 is the first year there is a display to light",
            !OrreryYear.isPrinted(1970, OrreryYear.Script.ROMAN))
        // Nothing else is ever printed: the hieroglyphs and the wedges are
        // carved and pressed rather than displayed at all, and every year
        // the other two rows are used for is well after 1970.
        assertTrue(!OrreryYear.isPrinted(1750, OrreryYear.Script.DIGITS))
        assertTrue(!OrreryYear.isPrinted(-1250, OrreryYear.Script.EGYPTIAN))
    }

    /**
     * And a Roman year is written in letters, not in whichever digits
     * happened to fit.
     */
    @Test
    fun `a roman year is written in letters`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = (controller.get() as MainActivity).clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.windOrreryToYearForTest(1750)

        val text = clock.orreryDateDigits()
        assertNotNull(text)
        assertTrue(
            "'$text' has no letters in it, so it is not Roman at all",
            text.any { it in 'A'..'Z' }
        )
    }
}
