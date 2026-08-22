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
 * The oldest writing there is, and where it stops.
 *
 * Two signs and place value in sixties, which is a much better idea than
 * the hieroglyphs next door and three thousand years older than anybody
 * else having it. What is checked here is the arithmetic of the places,
 * the hole where the nought is not, and the two years the writing changes
 * on — including the one where it stops existing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class CuneiformTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    // --------------------------------------------------------- the arithmetic

    /** What is pressed into the clay adds up to what was asked for. */
    @Test
    fun `the places add up to the number`() {
        for (value in listOf(1, 9, 10, 59, 60, 61, 80, 599, 3600, 3601, 3499, 12_959_999)) {
            var sum = 0L
            for (place in Cuneiform.places(value)) sum = sum * Cuneiform.BASE + place.value
            assertEquals("$value came back as $sum", value.toLong(), sum)
        }
    }

    /**
     * Sixty is where a place ends, and this is the line the whole system
     * turns on.
     *
     * Fifty-nine is one group of wedges; sixty is a single wedge that
     * means sixty because of where it is standing. Nothing else in this
     * file would notice a base of ten — every number under sixty would
     * still add up.
     */
    @Test
    fun `sixty is one place and fifty-nine is not`() {
        assertEquals(listOf(Cuneiform.Place(5, 9)), Cuneiform.places(59))
        assertEquals(
            listOf(Cuneiform.Place(0, 1), Cuneiform.Place(0, 0)),
            Cuneiform.places(60)
        )
        assertEquals(
            listOf(Cuneiform.Place(0, 1), Cuneiform.Place(2, 0)),
            Cuneiform.places(80)
        )
        assertEquals(60, Cuneiform.BASE)
    }

    /**
     * An empty place inside a number is kept, and it is the hole the
     * system genuinely had.
     *
     * Dropped, 3601 reads as 61 — the number is not merely drawn wrong, it
     * is a different number.
     */
    @Test
    fun `an empty place is not thrown away`() {
        val places = Cuneiform.places(3601)
        assertEquals("the middle place has fallen out of 3601", 3, places.size)
        assertTrue("the middle of 3601 is not empty", places[1].isEmpty)
        assertEquals("and 61 is not two places", 2, Cuneiform.places(61).size)
    }

    /**
     * Nothing at all for nothing, because there is no nought to write.
     *
     * A row of blanks would look like a display fault; nothing at all
     * makes the caller decide.
     */
    @Test
    fun `there is nothing to press for nothing`() {
        assertTrue(Cuneiform.places(0).isEmpty())
        assertTrue(Cuneiform.places(-7).isEmpty())
        assertEquals(0, Cuneiform.wedgeCount(0))
    }

    /** No group is ever ten wedges: that is what the next sign is for. */
    @Test
    fun `no group runs past nine`() {
        for (value in 1..4000) {
            for (place in Cuneiform.places(value)) {
                assertTrue("$value wants ${place.ones} ones", place.ones in 0..9)
                assertTrue("$value wants ${place.tens} tens", place.tens in 0..5)
            }
        }
    }

    /** Nine wedges in a row is a fence, so they are stacked. */
    @Test
    fun `repeats are stacked into short rows`() {
        assertEquals(1, Cuneiform.rowsFor(3))
        assertEquals(3, Cuneiform.rowsFor(9))
        assertEquals(3, Cuneiform.perRow(9))
        for (n in 1..9) {
            assertTrue(
                "$n does not fit in ${Cuneiform.rowsFor(n)} rows of ${Cuneiform.perRow(n)}",
                Cuneiform.rowsFor(n) * Cuneiform.perRow(n) >= n
            )
        }
    }

    /**
     * And the gap between places is plainly wider than the gap inside one.
     *
     * With no nought, white space is the only thing carrying the place
     * value. One wedge, a gap, two corner wedges is eighty; the same
     * wedges with the gaps alike is twenty-one, or is unreadable, and
     * which of those it is depends on the reader rather than on the
     * number.
     */
    @Test
    fun `the gap between places is not the gap inside one`() {
        assertTrue(
            "a place break looks like a group break, so 80 reads as 21",
            Cuneiform.PLACE_GAP > Cuneiform.GROUP_GAP * 4f
        )
    }

    // ------------------------------------------------------------- on the dial

    private fun sky(): ClockView {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1080, 2000)
        return clock
    }

    private fun canvas() = android.graphics.Canvas(
        android.graphics.Bitmap.createBitmap(
            1080, 2000, android.graphics.Bitmap.Config.ARGB_8888
        )
    )

    /** Three thousand years of hieroglyphs, and wedges before them. */
    @Test
    fun `the scripts change where the writing did`() {
        // The named years are the first ones on the near side of each
        // handover: Egypt begins at -3000 and writing at -3500, so those
        // years themselves are written and the ones before them are not.
        assertEquals(OrreryYear.Script.EGYPTIAN, OrreryYear.scriptFor(-3000))
        assertEquals(OrreryYear.Script.CUNEIFORM, OrreryYear.scriptFor(-3001))
        assertEquals(OrreryYear.Script.CUNEIFORM, OrreryYear.scriptFor(-3500))
        assertEquals(OrreryYear.Script.NONE, OrreryYear.scriptFor(-3501))
        assertEquals(OrreryYear.Script.NONE, OrreryYear.scriptFor(-40_000))
    }

    /**
     * And the dial writes in wedges when it is standing there.
     *
     * Counted rather than looked at, because the way this goes wrong is
     * silent: a date the wedge display cannot take falls through to the
     * seven-bar row and comes out as ordinary digits under a Bronze Age
     * sky, which looks finished.
     */
    @Test
    fun `a date before Egypt is pressed into clay`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-3200)
        assertEquals(
            "a year before Egypt is not in wedges",
            OrreryYear.Script.CUNEIFORM, clock.orreryScript()
        )
        clock.draw(canvas())
        assertTrue(
            "nothing was pressed into the clay",
            clock.wedgesPaintedForTest() > 0
        )
        assertEquals("part of it went to the Roman row", 0, clock.barsPaintedForTest())
        assertEquals("part of it went to the star", 0, clock.starsPaintedForTest())
        assertEquals("part of it was carved", 0, clock.egyptiansPaintedForTest())
    }

    /** And it goes back to hieroglyphs on the other side of the handover. */
    @Test
    fun `a date after Egypt begins is carved and not pressed`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-1250)
        clock.draw(canvas())
        assertTrue("nothing was carved", clock.egyptiansPaintedForTest() > 0)
        assertEquals(
            "a hieroglyphic date is being pressed into clay",
            0, clock.wedgesPaintedForTest()
        )
    }

    /**
     * Before writing there is no date at all — not an empty one.
     *
     * This is the whole point of the [OrreryYear.Script.NONE] branch, and
     * it is the one thing here a blank string would fake: a row that is
     * drawn and happens to be empty still reserves its line and still
     * counts as a display, and the difference only shows in whether
     * anything was painted.
     */
    @Test
    fun `before writing there is no date`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-9000)
        assertEquals(OrreryYear.Script.NONE, clock.orreryScript())
        assertEquals(
            "a date was written eleven thousand years before anybody wrote one",
            "", clock.orreryDateDigits()
        )
        clock.draw(canvas())
        assertEquals("wedges", 0, clock.wedgesPaintedForTest())
        assertEquals("hieroglyphs", 0, clock.egyptiansPaintedForTest())
        assertEquals("Roman bars", 0, clock.barsPaintedForTest())
        assertEquals("marks on the star", 0, clock.starsPaintedForTest())
    }

    /**
     * And the sky says nothing in a script I cannot caption.
     *
     * The same rule the hieroglyphs and the star follow: Latin under a
     * Roman date is a translation, and Sumerian under a wedge date would
     * be an invention.
     */
    @Test
    fun `a wedge date is not captioned in English`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-3200)
        for (day in 0 until 45) {
            assertNull(
                "a date in wedges is captioned in English",
                clock.orreryCaption()
            )
            clock.nudgeOrreryForTest(86_400_000L)
        }
    }
}
