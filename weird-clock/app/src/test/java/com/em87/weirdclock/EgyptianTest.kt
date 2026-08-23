package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The oldest counting there is a picture of.
 *
 * Additive, no place value and no nought: a sign for each power of ten,
 * written as many times as it is needed, biggest first. Everything here is
 * about that — that the signs add up to the number, that they come out in
 * the order they are written in, and that a system with no nought is not
 * asked to write one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class EgyptianTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    /** What is drawn adds up to what was asked for. */
    @Test
    fun `the signs add up to the number`() {
        for (value in listOf(1, 7, 10, 15, 31, 99, 100, 1251, 3999, 40_506, 9_999_999)) {
            val sum = Egyptian.tally(value).sumOf { (sign, count) ->
                Egyptian.valueOf(sign).toLong() * count
            }
            assertEquals("$value was drawn as $sum", value.toLong(), sum)
        }
    }

    /**
     * Biggest sign first, which is the order they are written in.
     *
     * Handed back the other way round every date would be drawn backwards
     * — and would still add up, so nothing else here would notice.
     */
    @Test
    fun `the signs come out biggest first`() {
        // Named outright rather than checked for being sorted. Walked the
        // other way the whole number comes back as one heap of strokes,
        // which is in order by accident — a list of one always is.
        assertEquals(
            "the signs are not in the order they are written in",
            listOf(
                Egyptian.Sign.GOD, Egyptian.Sign.TADPOLE, Egyptian.Sign.FINGER,
                Egyptian.Sign.LOTUS, Egyptian.Sign.COIL, Egyptian.Sign.HEEL,
                Egyptian.Sign.STROKE
            ),
            Egyptian.tally(1_234_567).map { it.first }
        )
    }

    /** No sign is ever written ten times: that is what the next one is for. */
    @Test
    fun `no sign is written ten times`() {
        for (value in 1..2000) {
            for ((sign, count) in Egyptian.tally(value)) {
                assertTrue(
                    "$value wants $count of $sign, which is a sign nobody stacks",
                    count in 1..9
                )
            }
        }
    }

    /**
     * A system with no nought is not asked to write one.
     *
     * Nothing comes back rather than a blank, so the caller has to decide
     * what to do about it instead of being handed an empty row that looks
     * like a display fault.
     */
    @Test
    fun `there is nothing to draw for nothing`() {
        assertTrue(Egyptian.tally(0).isEmpty())
        assertTrue(Egyptian.tally(-5).isEmpty())
        assertEquals(0, Egyptian.signCount(0))
    }

    /** Nine strokes in a row is a fence, so they are stacked. */
    @Test
    fun `repeats are stacked into short rows`() {
        assertEquals("three go side by side", 1, Egyptian.rowsFor(3))
        assertEquals("nine is three by three", 3, Egyptian.rowsFor(9))
        assertEquals(3, Egyptian.perRow(9))
        assertEquals("four is two by two", 2, Egyptian.rowsFor(4))
        assertEquals(2, Egyptian.perRow(4))
        for (n in 1..9) {
            assertTrue(
                "$n does not fit in ${Egyptian.rowsFor(n)} rows of ${Egyptian.perRow(n)}",
                Egyptian.rowsFor(n) * Egyptian.perRow(n) >= n
            )
        }
    }

    // ------------------------------------------------------------ on the dial

    /**
     * The year one is where the scripts change, and a Calendar will not
     * say so on its own.
     *
     * It never reports a negative year: wound back past the epoch it hands
     * over a cheerful positive one with a separate flag saying which side
     * of it you are on. Read without the flag, 1250 BC comes out as 1251
     * and is written in Roman — which is what it did, and which nothing
     * else here would have caught.
     */
    @Test
    fun `the years before the year one are drawn in hieroglyphs`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
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

        clock.windOrreryToYearForTest(-1250)
        assertEquals(
            "a year before the year one is not in hieroglyphs",
            OrreryYear.Script.EGYPTIAN, clock.orreryScript()
        )
        clock.draw(canvas)
        assertTrue(
            "nothing was carved for a date before the year one",
            clock.egyptiansPaintedForTest() > 0
        )
        assertEquals("part of it went to the Roman row", 0, clock.barsPaintedForTest())
        assertEquals("part of it went to the star", 0, clock.starsPaintedForTest())

        clock.windOrreryToYearForTest(1750)
        clock.draw(canvas)
        assertEquals(
            "a Roman year is being carved in hieroglyphs",
            0, clock.egyptiansPaintedForTest()
        )
    }

    /**
     * The caption goes with the script.
     *
     * Both of these first passed for the wrong reason: they asked the dial
     * for a caption on one arbitrary day, and most days have nothing to
     * say, so `null` came back whatever the wiring did. Now the sky is
     * walked forward a day at a time until it does have something — a full
     * moon is never more than a month off — and only then is the answer
     * looked at.
     */
    private fun captionSomewhereFrom(clock: ClockView): String? {
        for (day in 0 until 45) {
            clock.orreryCaption()?.let { return it }
            clock.nudgeOrreryForTest(86_400_000L)
        }
        return null
    }

    private fun sky(): ClockView {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        return clock
    }

    /**
     * And the sky stops explaining itself where I have no alphabet to
     * explain it in.
     *
     * A caption in English under a row of hieroglyphs is the fault the
     * date itself used to have. Latin under a Roman date is a translation;
     * a hieroglyphic or Yautja caption would be an invention, and
     * inventing an alphabet is what put noise on this screen before.
     */
    @Test
    fun `the far scripts say nothing rather than saying it in English`() {
        val far = sky()
        far.windOrreryToYearForTest(3400)
        assertNull("a far-future date is captioned", captionSomewhereFrom(far))
    }

    /**
     * The hieroglyphs are the exception, and the exception is a name.
     *
     * A carved date is a *regnal* year — the thirtieth year of somebody —
     * and a regnal year with nobody's name on it is not a date at all.
     * There is no way round it: writing Ramesses in hieroglyphs means
     * drawing his cartouche, which is a different set of signs for every
     * king who ever reigned and is a book rather than a caption. So the
     * one word said under a hieroglyphic date is a proper noun, which is
     * the same concession the Roman date makes when it says Iuppiter.
     */
    @Test
    fun `a hieroglyphic date is captioned with a name and nothing else`() {
        val old = sky()
        old.windOrreryToYearForTest(-1250)
        val said = captionSomewhereFrom(old)
        assertTrue("a carved date says nothing at all", said != null)
        assertTrue(
            "the caption under a carved date is a sentence rather than a name: $said",
            said!!.split(" ").size <= 2
        )
        assertTrue(
            "and it is not a name off the king list or the one star Egypt " +
                "wrote about: $said",
            EgyptianCalendar.kings.any { it.name == said } ||
                said == context.getString(R.string.egy_sothis)
        )
    }

    /** But an ordinary year does explain itself, or the test above is empty. */
    @Test
    fun `an ordinary year still says something within the month`() {
        val now = sky()
        now.windOrreryToYearForTest(2026)
        assertTrue(
            "the sky has nothing to say in a month and a half, so the " +
                "silence of the far scripts proves nothing",
            captionSomewhereFrom(now) != null
        )
    }

    /**
     * A Roman date is captioned in Latin, on the dial and not merely in
     * the drawing code that could do it.
     */
    @Test
    fun `a roman date is captioned in latin`() {
        val roman = sky()
        roman.windOrreryToYearForTest(1750)
        val caption = captionSomewhereFrom(roman)
        assertTrue("a Roman year said nothing at all in a month and a half", caption != null)
        assertTrue(
            "the caption under a Roman date is not Latin: $caption",
            caption!!.none { it in 'a'..'z' && it in "wkyj" } &&
                LATIN.any { caption.contains(it) }
        )
    }

    private companion object {
        /** A word from every caption the dial can produce, in Latin. */
        val LATIN = listOf(
            "Luna", "Defectio", "Stellae", "Cometes", "ordine", "oppositione",
            "Mercurius", "Terra", "Mars", "Iuppiter", "Saturnus", "Neptunus"
        )
    }
}
