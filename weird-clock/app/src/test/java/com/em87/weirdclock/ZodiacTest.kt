package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The twelve signs on the year ring.
 *
 * A sign is a thirty-degree slice of ecliptic, not a constellation and not
 * a month: Leo is where the Sun stands from about the twenty-third of July
 * to the twenty-second of August, and it says so because Aries starts at
 * the March equinox by definition and the rest follow at thirty degrees
 * each. That makes every claim here checkable against an almanac rather
 * than against the drawing.
 *
 * The half turn is the part that is easy to get wrong and invisible when
 * it is: the dial plots the Earth and a sign is where the *Sun* is, so a
 * version that forgets to turn it round puts every sign over the dates of
 * the one across the year from it — Leo where Aquarius belongs. It looks
 * perfectly tidy and is exactly six months wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZodiacTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** The Earth's ecliptic longitude on a given date, at noon UTC. */
    private fun earthOn(year: Int, month: Int, day: Int): Double =
        Orrery.longitude(
            Orrery.Body.EARTH,
            CivilDays.epochDay(year, month, day) * CivilDays.DAY_MS + CivilDays.DAY_MS / 2
        )

    private val names = listOf(
        "Aries", "Taurus", "Gemini", "Cancer", "Leo", "Virgo",
        "Libra", "Scorpio", "Sagittarius", "Capricorn", "Aquarius", "Pisces"
    )

    /**
     * Each sign stands over the dates the Sun actually spends in it.
     *
     * The middle of each sign's run, a fortnight either side of the
     * boundary, so a day or two of model error cannot move an answer.
     */
    @Test
    fun `each sign stands over the dates the Sun spends in it`() {
        val wanted = listOf(
            Triple(4, 1, 0) to "Aries",       // 1 April
            Triple(5, 1, 0) to "Taurus",      // 1 May
            Triple(6, 1, 0) to "Gemini",
            Triple(7, 1, 0) to "Cancer",
            Triple(8, 1, 0) to "Leo",
            Triple(9, 1, 0) to "Virgo",
            Triple(10, 1, 0) to "Libra",
            Triple(11, 1, 0) to "Scorpio",
            Triple(12, 1, 0) to "Sagittarius",
            Triple(1, 5, 0) to "Capricorn",
            Triple(2, 1, 0) to "Aquarius",
            Triple(3, 1, 0) to "Pisces"
        )
        for ((date, name) in wanted) {
            val (month, day, _) = date
            val sign = OrreryDial.signAt(earthOn(2026, month, day))
            assertEquals(
                "on the ${day}th of month $month the Sun is in ${names[sign]}",
                name, names[sign]
            )
        }
    }

    /**
     * The equinox is the seam: Aries begins there and Pisces ends there.
     *
     * The one date the whole scheme is anchored to, so it is worth asking
     * about directly rather than only through the months around it.
     */
    @Test
    fun `Aries begins at the March equinox`() {
        // A few days either side, since the sign changes on the equinox
        // itself and the model puts that within a day.
        assertEquals("Pisces", names[OrreryDial.signAt(earthOn(2026, 3, 15))])
        assertEquals("Aries", names[OrreryDial.signAt(earthOn(2026, 3, 25))])
    }

    /** Twelve of them, each a twelfth, and no gaps. */
    @Test
    fun `the ecliptic is cut into twelve equal signs`() {
        val seen = IntArray(12)
        var degrees = 0
        while (degrees < 3600) {
            seen[OrreryDial.signAt(degrees / 10.0)]++
            degrees++
        }
        assertTrue("the signs are not all used: ${seen.toList()}", seen.all { it > 0 })
        assertEquals("the signs are not equal slices", 1, seen.toSet().size)
    }

    /**
     * And they are not drawn before anybody had thought of them.
     *
     * The twelve equal houses are Babylonian and roughly fifth century BC.
     * Before that the ecliptic had constellations strung along it and had
     * not been cut into twelve equal thirty-degree slices, which is what a
     * sign is — so the ring goes quiet, in the same way it stops writing
     * dates before there were calendars.
     */
    @Test
    fun `the signs are not drawn before the Babylonians thought of them`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.ORRERY, true)
            .putBoolean(Prefs.ZODIAC, true)
            .commit()
        assertTrue("the cut-off is not in the right millennium",
            OrreryDial.ZODIAC_FROM_YEAR in -800..-300)
        assertTrue(
            "the signs were drawn on a ring wound to the Bronze Age",
            signsDrawn(-2000).isEmpty()
        )
        assertTrue(
            "the signs are missing from a ring wound to a year that had them",
            signsDrawn(-200).isNotEmpty()
        )
        assertEquals(
            "a modern ring is not carrying all twelve signs",
            12, signsDrawn(2026).size
        )
    }

    /** Which signs were written on a ring wound to [year]. */
    private fun signsDrawn(year: Int): List<String> {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.windOrreryToYearForTest(year)
        clock.zoomOrrery(Orrery.MAX_ZOOM)
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
                // The signs live in one Unicode block, which is how they are
                // told from the month names sharing this ring.
                if (text.isNotEmpty() && text[0].code in 0x2648..0x2653) seen += text
                super.drawTextOnPath(text, path, hOffset, vOffset, paint)
            }
        })
        bitmap.recycle()
        return seen
    }
}
