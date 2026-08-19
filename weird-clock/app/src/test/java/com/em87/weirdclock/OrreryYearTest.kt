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

    // ------------------------------------------------- the far-future glyphs

    /**
     * Ten glyphs, all different and none of them blank.
     *
     * The one rule an unreadable alphabet still has to obey: a digit that
     * lights nothing is a gap in the year, and two digits that light the
     * same bars are one digit as far as anybody looking can tell.
     */
    @Test
    fun `the far-future digits are ten distinct marks`() {
        val marks = ('0'..'9').map { OrreryYear.segmentsOf(it) }
        assertTrue("some digit has no glyph", marks.all { it != null })
        assertTrue("some glyph lights nothing at all", marks.all { it!! != 0 })
        assertEquals("two digits look the same", 10, marks.toSet().size)
    }

    /** And they are not the ordinary digits with a hat on. */
    @Test
    fun `the far-future alphabet is not the one we use`() {
        val ours = listOf(
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011
        )
        val theirs = ('0'..'9').map { OrreryYear.segmentsOf(it)!! }
        assertTrue(
            "the far-future year reads exactly like an ordinary one",
            ours.zip(theirs).count { (a, b) -> a == b } <= 1
        )
    }

    /** Anything that is not a digit has no glyph, rather than a wrong one. */
    @Test
    fun `only digits have glyphs`() {
        for (c in listOf(' ', ':', 'M', '-', '/')) {
            assertEquals("'$c' was given a glyph", null, OrreryYear.segmentsOf(c))
        }
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
     * And a Roman year is drawn, rather than pushed through a display that
     * cannot show it.
     *
     * M and D and X are not shapes seven bars can make. Forced through the
     * segment display they would come out as whichever digits happened to
     * fit — a row of nonsense that looks deliberate.
     */
    @Test
    fun `a roman year is not pushed through the segment display`() {
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
