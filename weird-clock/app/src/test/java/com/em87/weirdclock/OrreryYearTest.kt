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
            latin, OrreryDial.monthNamesFor(res, 1599)?.toList()
        )
        assertNotEquals(
            "a year written in figures is labelled in Latin",
            latin, OrreryDial.monthNamesFor(res, 1600)?.toList()
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

    /**
     * The four eras this side of the year one, and the years they change
     * on: Roman numerals to 1599, the figures we use set in type from
     * 1600, the same figures lit from 1970, and the star past 3000.
     *
     * It was Roman to 1999 — on a lit display from 1970, which is Roman
     * numerals on a calculator — and the line at 1600 is where Europe had
     * gone over to figures; see [OrreryYear.ARABIC_FROM] for whose word
     * that is.
     */
    @Test
    fun `the year changes its writing at 1600, at 1970 and at three thousand`() {
        assertEquals(OrreryYear.Script.ROMAN, OrreryYear.scriptFor(1))
        assertEquals(OrreryYear.Script.ROMAN, OrreryYear.scriptFor(1599))
        assertEquals(OrreryYear.Script.PRINTED, OrreryYear.scriptFor(1600))
        assertEquals(OrreryYear.Script.PRINTED, OrreryYear.scriptFor(1969))
        assertEquals(OrreryYear.Script.DIGITS, OrreryYear.scriptFor(1970))
        assertEquals(OrreryYear.Script.DIGITS, OrreryYear.scriptFor(1999))
        assertEquals(OrreryYear.Script.DIGITS, OrreryYear.scriptFor(2999))
        assertEquals(OrreryYear.Script.YAUTJA, OrreryYear.scriptFor(3000))
        assertEquals(OrreryYear.Script.YAUTJA, OrreryYear.scriptFor(4321))
        assertEquals(1600, OrreryYear.ARABIC_FROM)
        assertEquals(1970, OrreryYear.SEGMENTS_FROM)
    }

    /** Roman years are Roman; the others stay as figures. */
    @Test
    fun `only the years before 1600 are written in letters`() {
        assertEquals("MDXCIX", OrreryYear.yearText(1599, OrreryYear.Script.ROMAN))
        assertEquals("1750", OrreryYear.yearText(1750, OrreryYear.Script.PRINTED))
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

        for (year in listOf(1500, 1750, 2026, 3400)) {
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

        // A Roman year is set in type, every letter of it, and on no
        // display at all: it was lit on the sixteen-bar module from 1970
        // to 1999, which is thirty years of Roman numerals on a
        // calculator.
        clock.windOrreryToYearForTest(1500)
        var date = clock.orreryDateDigits()
        clock.draw(canvas)
        assertEquals(
            "a Roman year was not set in type",
            date.length, clock.printedCharsForTest()
        )
        assertEquals("a Roman year was lit", 0, clock.barsPaintedForTest() + clock.sevenBarCharsForTest())
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

        for (year in listOf(1980, 2026)) {
            clock.windOrreryToYearForTest(year)
            date = clock.orreryDateDigits()
            clock.draw(canvas)
            assertEquals(
                "$year was sent somewhere other than the seven-bar row",
                0, clock.barsPaintedForTest() + clock.yautjaCharsForTest() + clock.printedCharsForTest()
            )
            assertEquals(
                "$year is not lit, figure for figure",
                date.count { it.isDigit() }, clock.sevenBarCharsForTest()
            )
        }
    }

    /**
     * A date older than any display is set in type, in the numerals of
     * its own century.
     *
     * Lit bars are nineteen-seventies electronics. A date from 1750 lit
     * up on them is the same sort of anachronism as Neptune over Babylon,
     * and so is a date from 1750 in Roman numerals: by then the figures
     * were the ones on this dial, and what had not been invented was
     * anything to light them on.
     */
    @Test
    fun `a date older than the display is printed, in the figures of its century`() {
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

        for (year in listOf(1600, 1750, 1969)) {
            clock.windOrreryToYearForTest(year)
            val date = clock.orreryDateDigits()
            clock.draw(canvas)
            assertEquals(
                "a date from $year was not set in type: '$date'",
                date.length, clock.printedCharsForTest()
            )
            assertEquals(
                "a date from $year was lit up on a display that did not exist",
                0, clock.barsPaintedForTest() + clock.sevenBarCharsForTest()
            )
            assertTrue(
                "'$date' is not $year in figures",
                date.endsWith("/$year") && date.none { it in 'A'..'Z' }
            )
            assertTrue(
                "'$date' has a nought in front of a single figure, the way a display writes one",
                date.split("/").none { it.length > 1 && it.startsWith("0") }
            )
        }

        // And on the other side of the line it is lit after all, or the
        // assertion above is only saying that dates are never lit.
        clock.windOrreryToYearForTest(1970)
        clock.draw(canvas)
        assertTrue("a date from 1970 was not lit", clock.sevenBarCharsForTest() > 0)
        assertEquals("a date from 1970 was set in type", 0, clock.printedCharsForTest())
        assertEquals(
            "a date from 1970 is in Roman numerals, on a calculator",
            0, clock.barsPaintedForTest()
        )
    }

    /** The two lines themselves, without a canvas. */
    @Test
    fun `the display arrives in nineteen seventy and the figures in sixteen hundred`() {
        assertTrue(OrreryYear.isPrinted(OrreryYear.scriptFor(1969)))
        assertTrue(
            "1970 is the first year there is a display to light",
            !OrreryYear.isPrinted(OrreryYear.scriptFor(1970))
        )
        assertTrue(OrreryYear.isPrinted(OrreryYear.scriptFor(1599)))
        assertTrue(OrreryYear.isPrinted(OrreryYear.scriptFor(1)))
        // Nothing else is ever printed: the hieroglyphs and the wedges are
        // carved and pressed rather than displayed at all.
        assertTrue(!OrreryYear.isPrinted(OrreryYear.scriptFor(-1250)))
        assertTrue(!OrreryYear.isPrinted(OrreryYear.scriptFor(-3200)))
        assertTrue(!OrreryYear.isPrinted(OrreryYear.scriptFor(3400)))
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
        clock.windOrreryToYearForTest(1500)

        val text = clock.orreryDateDigits()
        assertNotNull(text)
        assertTrue(
            "'$text' has no letters in it, so it is not Roman at all",
            text.any { it in 'A'..'Z' }
        )
    }
}
