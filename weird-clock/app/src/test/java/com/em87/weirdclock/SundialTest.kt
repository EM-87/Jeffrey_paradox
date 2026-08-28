package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The oldest clock in the app, and the only one that can be checked
 * against a book.
 *
 * A sundial's hour lines are not a design decision — they are a projection
 * with a closed form, and the numbers for a few famous latitudes are
 * printed in every book on the subject. So this test does not check that
 * the drawing looks like a sundial; it checks that the arithmetic agrees
 * with the arithmetic, which is the only part a picture cannot tell you.
 */
class SundialTest {

    /**
     * The equatorial dial is the reason all the others are hard.
     *
     * Its plate is already square to the sun's daily circle, so there is
     * nothing to project and the hours are fifteen degrees apart —
     * everywhere on earth, at every latitude, in both hemispheres. Any
     * latitude appearing in its answer is a bug.
     */
    @Test
    fun `the equatorial dial has even hours everywhere on earth`() {
        for (lat in listOf(-70.0, -23.4, 0.0, 40.4, 51.5, 78.0)) {
            for (h in -6..6) {
                assertEquals(
                    "the equatorial dial noticed the latitude",
                    h * 15.0,
                    Sundial.lineAngle(Sundial.Kind.EQUATORIAL, lat, h.toDouble()),
                    0.0001
                )
            }
        }
    }

    /**
     * And the horizontal dial is `tan θ = sin φ · tan H`, which anybody
     * can check on a calculator.
     *
     * Madrid at forty and a half: three in the afternoon is forty-five
     * degrees of hour angle, and sin(40.4°)·tan(45°) is 0.6483, whose
     * arctangent is 32.96°. Spelled out rather than computed from the
     * same formula the code uses, which would be a test of nothing.
     */
    @Test
    fun `the horizontal dial is the textbook projection`() {
        val lat = 40.4
        assertEquals(32.96, Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, 3.0), 0.02)
        // And one hour out, where sin(40.4°)·tan(15°) is 0.1737.
        assertEquals(9.85, Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, 1.0), 0.02)
        assertEquals(0.0, Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, 0.0), 0.0001)
        // Six o'clock is a right angle on every horizontal dial at every
        // latitude, which is where the tangent form falls over and this
        // one does not.
        assertEquals(90.0, Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, 6.0), 0.0001)
        assertEquals(-90.0, Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, -6.0), 0.0001)
        assertEquals(90.0, Sundial.lineAngle(Sundial.Kind.HORIZONTAL, 12.0, 6.0), 0.0001)
    }

    /**
     * Morning is one side of the noon line and afternoon is the other,
     * and the dial is symmetrical about it.
     *
     * The sort of thing that is obviously true and comes out inverted
     * exactly once in the life of any dial anybody writes.
     */
    @Test
    fun `the morning is the mirror of the afternoon`() {
        for (kind in Sundial.Kind.entries) {
            for (h in 1..5) {
                val pm = Sundial.lineAngle(kind, 51.5, h.toDouble())
                val am = Sundial.lineAngle(kind, 51.5, -h.toDouble())
                assertEquals("$kind is not symmetrical about noon", pm, -am, 0.0001)
            }
        }
    }

    /**
     * The dial on the wall reads anticlockwise, and the two on the ground
     * read clockwise.
     *
     * Not a drawing choice — it is why clocks go clockwise in the first
     * place. A dial on the ground in the northern hemisphere has its
     * shadow sweep from west through north to east, which is the
     * direction the hands of every clock ever built copy. Stand a dial on
     * a south-facing wall and you are looking at the same sky from behind
     * it: morning is on the right, afternoon on the left, and the numbers
     * count backwards. Every real vertical dial in Europe is numbered
     * that way, and drawing this one forwards would have been an hour
     * wrong in each direction with nothing on screen to say so.
     */
    @Test
    fun `the wall dial reads backwards, because a wall is seen from behind`() {
        for (h in 1..5) {
            assertTrue(
                "the garden dial lost its clockwise afternoon",
                Sundial.lineAngle(Sundial.Kind.HORIZONTAL, 51.5, h.toDouble()) > 0
            )
            assertTrue(
                "the equatorial dial lost its clockwise afternoon",
                Sundial.lineAngle(Sundial.Kind.EQUATORIAL, 51.5, h.toDouble()) > 0
            )
            assertTrue(
                "the wall dial is numbered like a clock, and should not be",
                Sundial.lineAngle(Sundial.Kind.VERTICAL, 51.5, h.toDouble()) < 0
            )
        }
    }

    /**
     * South of the equator the sun goes round the sky the other way, and
     * so does the dial.
     *
     * It falls out of the sign of the latitude rather than out of a rule,
     * which is why it is worth a test: a formula that used the absolute
     * latitude would pass everything above and draw Sydney's dial back to
     * front.
     */
    @Test
    fun `a dial in the southern hemisphere runs the other way`() {
        val north = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, 40.0, 3.0)
        val south = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, -40.0, 3.0)
        assertEquals("Sydney's dial is Madrid's", north, -south, 0.0001)
    }

    /**
     * Two dials that are real objects and tell no time at all.
     *
     * A horizontal dial on the equator has its style lying flat, so every
     * hour line lands on the noon line; a vertical one at the pole has the
     * same problem the other way up. Both are one line of arithmetic from
     * any latitude somebody might be standing at, and the honest answer is
     * to say so rather than draw a fan that has collapsed into a stick.
     */
    @Test
    fun `a dial that cannot work where you are standing says so`() {
        assertTrue(Sundial.collapses(Sundial.Kind.HORIZONTAL, 0.0))
        assertTrue(Sundial.collapses(Sundial.Kind.HORIZONTAL, -2.0))
        assertFalse(Sundial.collapses(Sundial.Kind.HORIZONTAL, 40.0))
        assertTrue(Sundial.collapses(Sundial.Kind.VERTICAL, 89.0))
        assertFalse(Sundial.collapses(Sundial.Kind.VERTICAL, 40.0))
        // And the one that works everywhere works everywhere.
        for (lat in listOf(-90.0, 0.0, 45.0, 90.0)) {
            assertFalse(Sundial.collapses(Sundial.Kind.EQUATORIAL, lat))
        }
    }

    /**
     * The style is the latitude on the ground and its complement on a
     * wall, which is the one number cut into every real dial.
     */
    @Test
    fun `the style stands at the latitude`() {
        assertEquals(51.5, Sundial.styleAngle(Sundial.Kind.HORIZONTAL, 51.5), 0.0001)
        assertEquals(38.5, Sundial.styleAngle(Sundial.Kind.VERTICAL, 51.5), 0.0001)
        assertEquals(90.0, Sundial.styleAngle(Sundial.Kind.EQUATORIAL, 51.5), 0.0001)
        // The two of them always come to a right angle, which is what
        // "parallel to the axis" means and is the check that neither has
        // been written down the wrong way up.
        for (lat in listOf(10.0, 40.0, 60.0)) {
            assertEquals(
                90.0,
                Sundial.styleAngle(Sundial.Kind.HORIZONTAL, lat) +
                    Sundial.styleAngle(Sundial.Kind.VERTICAL, lat),
                0.0001
            )
        }
    }

    /**
     * The shadow falls on the hour line, and that is the whole instrument.
     *
     * The style is parallel to the earth's axis, so where its shadow lands
     * depends only on how far round the sun has got and not at all on the
     * time of year. Checked across the seasons at one time of day: if the
     * declination ever leaks into the answer, the dial is not a sundial.
     */
    @Test
    fun `the shadow lands on the hour line whatever the month`() {
        val lat = 40.4
        val atThree = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, lat, 3.0)
        for (declination in listOf(-23.4, -10.0, 0.5, 10.0, 23.4)) {
            val shadow = Sundial.shadowAngle(
                Sundial.Kind.HORIZONTAL, lat, 3.0,
                sunAltitudeDeg = 30.0, sunDeclinationDeg = declination
            )
            assertNotNull("no shadow with the sun thirty degrees up", shadow)
            assertEquals("the season moved the shadow", atThree, shadow!!, 0.0001)
        }
    }

    /**
     * And there is no shadow when there is no sun on the plate.
     *
     * Three ways for that to be true and the dial has to know all three:
     * the sun is down, the hour is past the edge of the plate, and — only
     * on the equatorial dial — the sun is shining on the other face of it,
     * which is half the year and neither face at the equinox.
     */
    @Test
    fun `a dial with no sun on it draws no shadow`() {
        assertNull(
            "a shadow at night",
            Sundial.shadowAngle(Sundial.Kind.HORIZONTAL, 40.0, 1.0, -5.0, 10.0)
        )
        assertNull(
            "a shadow on a wall at eight in the evening",
            Sundial.shadowAngle(Sundial.Kind.VERTICAL, 40.0, 8.0, 10.0, 10.0)
        )
        assertNull(
            "the equatorial plate is lit from underneath in winter",
            Sundial.shadowAngle(Sundial.Kind.EQUATORIAL, 40.0, 1.0, 20.0, -15.0)
        )
        assertNotNull(
            "and lit from above in summer",
            Sundial.shadowAngle(Sundial.Kind.EQUATORIAL, 40.0, 1.0, 20.0, 15.0)
        )
        assertNull(
            "the equinox lights neither face",
            Sundial.shadowAngle(Sundial.Kind.EQUATORIAL, 40.0, 1.0, 20.0, 0.1)
        )
        // South of the equator it is the other way round, which is the
        // same test upside down and the one an absolute value would fail.
        assertNotNull(
            Sundial.shadowAngle(Sundial.Kind.EQUATORIAL, -40.0, 1.0, 20.0, -15.0)
        )
    }

    /** A low sun throws a long shadow and a high one throws a short one. */
    @Test
    fun `the shadow lengthens as the sun sinks`() {
        val high = Sundial.shadowReach(70.0)
        val low = Sundial.shadowReach(8.0)
        assertTrue("a high sun casts a long shadow", high < low)
        assertEquals("a shadow after sunset", 0f, Sundial.shadowReach(-1.0), 0f)
        assertTrue("the shadow left the plate", low <= 1f)
    }

    /**
     * Which way to turn, for the mode where the dial is in your hand.
     *
     * Signed and wrapped, because the interesting case is the one that
     * crosses north: the sun at 350° and the phone at 10° is twenty
     * degrees apart and not three hundred and forty.
     */
    @Test
    fun `the arrow knows which way round is shorter`() {
        assertEquals(20.0, Sundial.offBy(10.0, 30.0), 0.0001)
        assertEquals(-20.0, Sundial.offBy(30.0, 10.0), 0.0001)
        assertEquals(-20.0, Sundial.offBy(10.0, 350.0), 0.0001)
        assertEquals(20.0, Sundial.offBy(350.0, 10.0), 0.0001)
        assertTrue(abs(Sundial.offBy(0.0, 180.0)) <= 180.0)
    }

    /**
     * The mottoes are Latin and stay Latin.
     *
     * There is no J and no U in a Roman inscription, and a sundial with
     * "TEMPUS FUGIT" cut into it in this app's own Roman display would be
     * a display asked for two letters it has never had. It is also simply
     * wrong, on a face whose entire purpose is looking old.
     */
    @Test
    fun `nothing on the rim is spelled with a letter Rome did not have`() {
        for (motto in Sundial.mottoes()) {
            assertFalse("$motto has a U in it", motto.contains('U'))
            assertFalse("$motto has a J in it", motto.contains('J'))
            assertEquals("$motto is not in capitals", motto.uppercase(), motto)
        }
        // One a day, and the same one all day: a motto that shuffles every
        // time you look at the clock is a fortune cookie.
        assertEquals(Sundial.motto(200), Sundial.motto(200))
        assertTrue(Sundial.motto(1).isNotEmpty())
        assertTrue(Sundial.motto(366).isNotEmpty())
    }

    /**
     * How much of the day each dial can show.
     *
     * A wall facing the equator loses the sun at six each way whatever the
     * season, because after that the sun is behind the wall. A dial on the
     * ground keeps it for as long as the sun is up, which at a high enough
     * latitude in June is a good deal longer.
     */
    @Test
    fun `each dial covers as much of the day as its plate can see`() {
        assertEquals(6.0, Sundial.readableHours(Sundial.Kind.VERTICAL, 60.0), 0.0001)
        assertTrue(
            "a garden dial in Scotland is dark by six in June",
            Sundial.readableHours(Sundial.Kind.HORIZONTAL, 57.0) > 6.0
        )
        assertEquals(
            "the tropics get more of the day than they should",
            6.0, Sundial.readableHours(Sundial.Kind.HORIZONTAL, 0.0), 0.0001
        )
        // The lines are the whole hours inside that reach, noon included.
        val lines = Sundial.hourLines(Sundial.Kind.VERTICAL, 51.5)
        assertEquals(13, lines.size)
        assertTrue(0 in lines)
        assertEquals(-6, lines.first())
        assertEquals(6, lines.last())
    }
}
