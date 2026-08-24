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

    /**
     * Where a sign is drawn and which sign a date falls in are the same
     * fact, asked from the two ends.
     *
     * They were two copies of one half turn, and a sabotage that reversed
     * the drawing left the rule answering correctly about a ring that had
     * every sign six months out of place. So the drawing derives from the
     * rule now, and this is the promise that it still does: start a sign,
     * step into it, and ask which sign you are in.
     */
    @Test
    fun `the sign drawn on an arc is the sign that arc belongs to`() {
        for (sign in 0 until 12) {
            val justInside = OrreryDial.signStart(sign) + 1.0
            assertEquals(
                "the arc drawn for ${names[sign]} belongs to ${names[OrreryDial.signAt(justInside)]}",
                sign, OrreryDial.signAt(justInside)
            )
            val justBefore = OrreryDial.signStart(sign) - 1.0
            assertEquals(
                "the arc drawn for ${names[sign]} starts a degree too early or too late",
                (sign + 11) % 12, OrreryDial.signAt(justBefore)
            )
        }
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

    /**
     * One switch puts the door on the dial as well as the sky behind it.
     *
     * The sky used to hang off the moon complication: the glyph was the
     * thing you pressed, so there had to be one. Turned round — one switch
     * for the lot — the switch has to put its own glyph there, or it is a
     * setting that turns on a room with no door into it. Every existing
     * test of the door switched the complication on itself and so could
     * not see the difference.
     */
    @Test
    fun `the sky's one switch puts a door on the dial by itself`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.ORRERY, true)
            // Off, deliberately: this is the case that used to leave the
            // solar system switched on and unreachable.
            .putBoolean(Prefs.MOON_PHASE, false)
            .commit()
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        assertTrue(
            "the sky is on and there is nothing on the dial to press",
            clock.skyTokenAt(clock.skyTokenXForTest(), clock.skyTokenYForTest())
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
