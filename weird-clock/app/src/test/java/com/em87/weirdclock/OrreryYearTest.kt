package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val clock = controller.get().clockForTest()
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
            val groups = text.split(" ")
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
        val clock = controller.get().clockForTest()
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

        clock.windOrreryToYearForTest(1750)
        var date = clock.orreryDateDigits()
        clock.draw(canvas)
        assertEquals(
            "a Roman year did not reach the sixteen-bar display",
            date.length, clock.barsPaintedForTest()
        )
        assertEquals("and it is not on the star", 0, clock.starsPaintedForTest())

        clock.windOrreryToYearForTest(3400)
        date = clock.orreryDateDigits()
        clock.draw(canvas)
        assertEquals(
            "the whole far-future date is not on the star",
            date.length, clock.starsPaintedForTest()
        )
        assertEquals(
            "part of the far-future date is on the other display",
            0, clock.barsPaintedForTest()
        )

        clock.windOrreryToYearForTest(2026)
        clock.draw(canvas)
        assertEquals(
            "an ordinary year was sent somewhere other than the seven-bar row",
            0, clock.barsPaintedForTest() + clock.starsPaintedForTest()
        )
    }

    /**
     * And a Roman year is written in letters, not in whichever digits
     * happened to fit.
     */
    @Test
    fun `a roman year is written in letters`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
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
