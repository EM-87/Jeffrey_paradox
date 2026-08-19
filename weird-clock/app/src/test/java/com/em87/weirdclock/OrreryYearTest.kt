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
 * A joke on a clock still has to be legible, so the day and the month keep
 * their digits in every script: a date with nothing readable in it is a
 * smudge rather than a joke.
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

    // ------------------------------------------------- on the dial

    /** The date the sky writes carries the day and month as digits, always. */
    @Test
    fun `the day and month stay readable in every script`() {
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
                "'$text' does not start with a day and a month in digits",
                Regex("^\\d\\d \\d\\d ").containsMatchIn(text)
            )
            assertTrue(
                "'$text' does not end with the year it is standing on",
                text.endsWith(OrreryYear.yearText(year, OrreryYear.scriptFor(year)))
            )
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
            "the far-future year is not four marks on the star",
            4, clock.starsPaintedForTest()
        )
        assertEquals(
            "the day and month did not stay on a display we can read",
            date.length - 4, clock.barsPaintedForTest()
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
