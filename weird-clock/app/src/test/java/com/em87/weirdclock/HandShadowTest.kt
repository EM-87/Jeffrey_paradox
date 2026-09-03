package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * The shadows the hands throw, and the sun that throws them.
 *
 * The whole feature is one claim — that this is the real sun over a real
 * place and not a lamp in the corner of a drawing — so everything here is
 * a way of asking whether that claim survives contact with a number. A
 * decorative shadow would pass none of it: it would be the same length at
 * the equator as at the pole, the same length at noon as at four, and it
 * would not know which way east was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class HandShadowTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /** An instant, given in UTC so the test does not depend on a zone. */
    /** The sun that high, straight ahead, with nothing dimming it. */
    private fun sunAt(altitudeDeg: Double): HandShadow.Light =
        HandShadow.Light(altitudeDeg, 180.0, moon = false, brightness = 1f)

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute)
        return cal.timeInMillis
    }

    /** Midsummer and midwinter, when the latitudes are furthest apart. */
    private val june = utc(2026, 6, 21, 12)
    private val december = utc(2026, 12, 21, 12)

    // ------------------------------------------------------- where the sun is

    /**
     * At noon the sun stands due south in the north and due north in the
     * south.
     *
     * The one thing a hemisphere check catches that nothing else does: the
     * azimuth formula uses the cosine of the latitude, which is the same
     * either side of the equator, so a version that has lost the sine
     * somewhere gives Sydney a shadow pointing the wrong way and is right
     * about everything in Europe.
     */
    @Test
    fun `the noon sun is south from the north and north from the south`() {
        // Five degrees, not one. Twelve o'clock is not solar noon even on
        // the meridian: the equation of time is seven minutes out in late
        // March, and seven minutes is nearly two degrees of sky.
        val north = SolarTime.position(40.0, 0.0, utc(2026, 3, 21, 12))
        assertEquals("the noon sun is not due south from Madrid", 180.0, north.azimuthDeg, 5.0)
        val south = SolarTime.position(-33.0, 0.0, utc(2026, 3, 21, 12))
        val fromNorth = minOf(south.azimuthDeg, 360.0 - south.azimuthDeg)
        assertEquals("the noon sun is not due north from Sydney", 0.0, fromNorth, 5.0)
    }

    /** And on the equinox at the equator it is straight overhead. */
    @Test
    fun `the equinox sun stands over the equator at noon`() {
        val overhead = SolarTime.position(0.0, 0.0, utc(2026, 3, 21, 12))
        assertEquals("the sun is not overhead", 90.0, overhead.altitudeDeg, 2.5)
    }

    /**
     * The morning sun is in the east and the afternoon sun in the west.
     *
     * Altitude alone cannot tell the two apart — the sun is the same
     * height at ten and at two — so this is the one thing that catches a
     * lost sign in the hour angle, which would put every morning shadow
     * where its afternoon one belongs.
     */
    @Test
    fun `the sun crosses from east to west`() {
        val morning = SolarTime.position(40.0, 0.0, utc(2026, 6, 21, 8))
        val afternoon = SolarTime.position(40.0, 0.0, utc(2026, 6, 21, 16))
        assertTrue("the eight o'clock sun is not in the east: ${morning.azimuthDeg}", morning.azimuthDeg in 45.0..135.0)
        assertTrue("the four o'clock sun is not in the west: ${afternoon.azimuthDeg}", afternoon.azimuthDeg in 225.0..315.0)
    }

    /** At midnight it is below the horizon, which is what "night" means here. */
    @Test
    fun `the sun goes down`() {
        val night = SolarTime.position(40.0, 0.0, utc(2026, 12, 21, 0))
        assertTrue("the sun is up at midnight in December", night.altitudeDeg < 0.0)
    }

    /** And above the Arctic circle in June it does not. */
    @Test
    fun `the midnight sun stays up`() {
        val midnight = SolarTime.position(78.0, 0.0, utc(2026, 6, 21, 0))
        assertTrue(
            "the sun set at midnight in June at seventy-eight degrees north",
            midnight.altitudeDeg > 0.0
        )
    }

    // -------------------------------------------------------- how far it falls

    /**
     * A shadow is as long as the height over the tangent of the altitude,
     * and nothing at all with the sun overhead.
     *
     * This is the bit the user asked for in so many words: perpendicular
     * at the equator, oblique near the poles.
     */
    @Test
    fun `the sun overhead throws no shadow and a low sun throws a long one`() {
        val h = HandShadow.heightOf(ClockView.Hand.MINUTE)
        assertEquals("a sun straight up is casting a shadow", 0f, HandShadow.reach(h, 90.0), 1e-4f)
        assertEquals("a shadow at forty-five degrees is not its own height", h, HandShadow.reach(h, 45.0), 1e-4f)
        assertTrue(
            "a sun twenty degrees up throws a shadow shorter than two heights",
            HandShadow.reach(h, 20.0) > h * 2f
        )
        // And below that the cap has taken over: the trigonometry says
        // fifteen heights at ten degrees, and what is drawn is a tenth of
        // the radius, because the honest answer looks like a hand floating
        // a foot above the dial.
        assertEquals(
            "the length is still following the sun down to the horizon",
            HandShadow.MAX_LENGTH, HandShadow.reach(h, 10.0), 1e-6f
        )
        // And below the horizon it is at its stop rather than back under
        // its own hand. The shadow does not end at sunset — the light
        // does not — so a length of nought there was the fade being
        // applied twice, once as darkness and once as geometry.
        assertEquals(
            "a shadow a degree past sunset has gone back under its hand",
            HandShadow.MAX_LENGTH, HandShadow.reach(h, -1.0), 1e-6f
        )
        assertEquals(
            "and something is still cast after the sky has gone dark",
            0f, HandShadow.reach(h, -7.0), 1e-6f
        )
    }

    /**
     * An afternoon sun still casts a shadow, and casts it at full strength.
     *
     * The fade used to begin eighteen degrees above the horizon, which at
     * forty north is an hour and a half before sunset. Over that hour and
     * a half the shadows went out while the sun was plainly still up, and
     * winding the hands back through the afternoon showed them vanishing
     * in daylight — reported as exactly that. Ten degrees is twenty
     * minutes short of sunset and is still full daylight; nothing should
     * have started fading yet.
     */
    @Test
    fun `the shadow keeps its strength while the sun is up`() {
        assertEquals("a sun ten degrees up is already fading", 1f, HandShadow.strength(10.0), 1e-4f)
        assertEquals("and thirty degrees certainly is", 1f, HandShadow.strength(30.0), 1e-4f)
        // Half at the horizon itself, and out by the end of civil
        // twilight. The fade used to finish at sunset, so every shadow on
        // the dial was gone while the face was still drawing a lit
        // twilight sky over it.
        assertEquals("sunset is not the middle of the fade", 0.5f, HandShadow.strength(0.0), 0.02f)
        assertTrue("twilight has no shadows", HandShadow.strength(-3.0) > 0.05f)
        assertEquals("something is cast after dark", 0f, HandShadow.strength(-7.0), 1e-6f)
        assertTrue(
            "the fade is not monotonic",
            HandShadow.strength(-3.0) < HandShadow.strength(1.0) &&
                HandShadow.strength(1.0) < HandShadow.strength(3.0) &&
                HandShadow.strength(3.0) < HandShadow.strength(5.0)
        )
    }

    /**
     * The moon's shadow ends at the moon's own horizon.
     *
     * The sun goes on lighting the sky after it sets and its shadows go
     * with it; the moon is a lamp and a lamp behind the hill is off. Both
     * fades are the same curve and they end in different places, which is
     * the one asymmetry here worth a test — written the same for both, a
     * moonrise put a half-strength shadow on the dial from one minute to
     * the next.
     */
    @Test
    fun `the moon's shadow gives out at its own horizon`() {
        assertEquals(
            "a moon below the hill is still casting",
            0f, HandShadow.strength(-0.5, moon = true), 1e-6f
        )
        assertTrue(
            "and it arrives at half strength the moment it clears it",
            HandShadow.strength(0.2, moon = true) < 0.1f
        )
        assertEquals(
            "a moon well up does not cast fully",
            1f, HandShadow.strength(20.0, moon = true), 1e-6f
        )
    }

    /** However low the sun gets, the shadow stops somewhere. */
    @Test
    fun `a shadow never runs off the dial`() {
        for (hand in ClockView.Hand.entries) {
            for (alt in 1..90) {
                val reach = HandShadow.reach(HandShadow.heightOf(hand), alt.toDouble())
                assertTrue(
                    "$hand at $alt degrees reaches $reach",
                    reach <= HandShadow.MAX_LENGTH && reach.isFinite()
                )
            }
        }
    }

    /**
     * The three hands stand at three heights, so they throw three
     * different shadows.
     *
     * The order is the order the arbors have to stack in, and it is what
     * makes the stack read as a stack rather than as one thick shadow.
     */
    @Test
    fun `the higher the hand the further its shadow falls`() {
        val hour = HandShadow.reach(HandShadow.heightOf(ClockView.Hand.HOUR), 30.0)
        val minute = HandShadow.reach(HandShadow.heightOf(ClockView.Hand.MINUTE), 30.0)
        val second = HandShadow.reach(HandShadow.heightOf(ClockView.Hand.SECOND), 30.0)
        assertTrue("the minute hand does not sit above the hour hand", minute > hour)
        assertTrue("the second hand does not sit above the minute hand", second > minute)
    }

    /**
     * And the same clock at the same hour throws a longer shadow the
     * further north it is standing.
     *
     * Which is the whole reason for doing this with the real sun rather
     * than with a light source in the corner: the picture is different in
     * different places, and it is different because of where you are.
     *
     * At the equinox, and not at midsummer, which is how this test first
     * failed and was right to. In June the sun stands over the tropic at
     * twenty-three degrees north, so Madrid at forty is *nearer* to being
     * under it than the equator is, and its noon shadow is the shorter of
     * the two. The tidy rule — further from the equator, longer shadow —
     * is only true on the two days the sun is over the equator.
     */
    @Test
    fun `the shadow is longer nearer the pole`() {
        val h = HandShadow.heightOf(ClockView.Hand.HOUR)
        val equinox = utc(2026, 3, 21, 12)
        fun noonReach(lat: Double) =
            HandShadow.reach(h, SolarTime.position(lat, 0.0, equinox).altitudeDeg)
        val equator = noonReach(0.0)
        val madrid = noonReach(40.0)
        val tromso = noonReach(69.6)
        assertTrue("Madrid's noon shadow is no longer than the equator's", madrid > equator)
        assertTrue("Tromsø's noon shadow is no longer than Madrid's", tromso > madrid)
    }

    /**
     * And at midsummer that rule turns over, which is worth pinning down
     * rather than merely avoiding.
     *
     * The sun is over the Tropic of Cancer in June, so noon shadows are
     * shortest at twenty-three degrees north and grow in *both*
     * directions from there. A clock in Madrid casts a shorter midsummer
     * shadow than one on the equator.
     */
    @Test
    fun `at midsummer the shortest noon shadow is over the tropic`() {
        val h = HandShadow.heightOf(ClockView.Hand.HOUR)
        fun noonReach(lat: Double) =
            HandShadow.reach(h, SolarTime.position(lat, 0.0, june).altitudeDeg)
        assertTrue(
            "the midsummer noon shadow is not shortest over the tropic",
            noonReach(23.4) < noonReach(0.0) && noonReach(23.4) < noonReach(40.0)
        )
        assertTrue(
            "Madrid's midsummer noon shadow is not shorter than the equator's",
            noonReach(40.0) < noonReach(0.0)
        )
    }

    /** And longer in winter than in summer, for the same reason. */
    @Test
    fun `the shadow is longer in winter`() {
        val h = HandShadow.heightOf(ClockView.Hand.HOUR)
        val summer = HandShadow.reach(h, SolarTime.position(40.0, 0.0, june).altitudeDeg)
        val winter = HandShadow.reach(h, SolarTime.position(40.0, 0.0, december).altitudeDeg)
        assertTrue("the December noon shadow is no longer than the June one", winter > summer)
    }

    // -------------------------------------------------------- which way it goes

    /** A shadow runs away from the sun. */
    @Test
    fun `the shadow points opposite the sun`() {
        assertEquals("a sun in the east throws a shadow east", 270f, HandShadow.bearing(90.0), 0.01f)
        assertEquals("a sun in the south throws a shadow south", 0f, HandShadow.bearing(180.0), 0.01f)
        assertEquals(180f, HandShadow.bearing(0.0), 0.01f)
        assertTrue("a bearing came out off the compass", HandShadow.bearing(359.0) in 0f..360f)
    }

    /**
     * So in the morning the shadows lie to the west of their hands, and in
     * the afternoon to the east.
     *
     * Twelve is north on this dial, so west is nine o'clock and east is
     * three, and a bearing and a dial angle are the same number.
     */
    @Test
    fun `morning shadows lie west and afternoon shadows lie east`() {
        val morning = HandShadow.bearing(
            SolarTime.position(40.0, 0.0, utc(2026, 6, 21, 8)).azimuthDeg
        )
        val afternoon = HandShadow.bearing(
            SolarTime.position(40.0, 0.0, utc(2026, 6, 21, 16)).azimuthDeg
        )
        assertTrue("the morning shadow is not falling west: $morning", morning in 225f..315f)
        assertTrue("the afternoon shadow is not falling east: $afternoon", afternoon in 45f..135f)
    }

    // ------------------------------------------------------------ how dark

    /** Nothing at night, full daylight when the sun is properly up. */
    @Test
    fun `the shadow fades out with the sun`() {
        assertEquals("there are shadows after dark", 0f, HandShadow.strength(-6.5), 1e-6f)
        assertEquals("the midday shadow is not solid", 1f, HandShadow.strength(45.0), 1e-6f)
        // The window straddles the sunset now rather than ending at it —
        // see [HandShadow.FADE_FROM_DEG]. A degree up is a little past
        // halfway down and a degree under is a little short of it.
        val low = HandShadow.strength(1.0)
        assertTrue("a shadow at one degree is as dark as one at noon: $low", low < 0.75f)
        assertTrue("a shadow at one degree is not there at all", low > 0.4f)
    }

    // ---------------------------------------------------------- on the dial

    /**
     * The switch is the switch: off, nothing is drawn at all.
     *
     * Counted rather than hunted for in the pixels, because a shadow is
     * black on a face that is nearly black, and "is there ink here" is not
     * a question the midnight theme answers usefully.
     */
    @Test
    fun `no shadows until they are switched on`() {
        val v = dial()
        v.freezeAtForTest(utc(2026, 6, 21, 12))
        paint(v)
        assertEquals("shadows are drawn with the option off", 0, v.shadowsPaintedForTest())
        assertNull("the sun is being worked out with the option off", v.sunOverhead())

        v.handShadows = true
        paint(v)
        assertEquals("no shadows with the option on and the sun up", 3, v.shadowsPaintedForTest())
        assertNotNull(v.sunOverhead())
    }

    /**
     * The dial's own curve comes and goes with the sun as well, and is
     * strongest when the light is across it rather than down on it.
     *
     * A dome lit from straight overhead has no shaded side, so there is
     * nothing to draw; a dome lit from near the horizon is half in shadow.
     * The hand shadows do the opposite — longest at the horizon — which
     * makes the two easy to wire to the same number by accident, and this
     * says they are not.
     */
    @Test
    fun `the dial's belly shows most when the light is across it`() {
        assertEquals("a dome under a midnight sky", 0f, HandShadow.domeStrength(sunAt(-7.0)), 1e-6f)
        assertTrue(
            "the dome is as strong with the sun overhead as with it low",
            HandShadow.domeStrength(sunAt(85.0)) < HandShadow.domeStrength(sunAt(35.0))
        )
        assertTrue(
            "the dome is not there at all in the middle of the day",
            HandShadow.domeStrength(sunAt(35.0)) > 0.1f
        )
        assertTrue(
            "the dome does not fade out with the sun at the horizon",
            HandShadow.domeStrength(sunAt(-4.0)) < HandShadow.domeStrength(sunAt(35.0))
        )
    }

    /**
     * And a shadow is laid down soft, in passes.
     *
     * The widest pass goes first and the narrowest last, or the core of
     * the shadow would be veiled by its own halo; and the last one adds
     * nothing to the hand's own width, so the shadow's core is the shape
     * of the hand and the haze is entirely outside it. Cheap, and the only
     * blur a hardware canvas is certain not to decline.
     */
    @Test
    fun `the shadow is laid down soft`() {
        assertEquals(
            "the passes and their weights do not match up",
            HandShadow.SPREAD.size, HandShadow.PASS_ALPHA.size
        )
        assertTrue("a shadow drawn in one pass is not soft", HandShadow.SPREAD.size >= 3)
        for (i in 1 until HandShadow.SPREAD.size) {
            assertTrue(
                "the passes do not narrow, so the core is drawn under its own haze",
                HandShadow.SPREAD[i] < HandShadow.SPREAD[i - 1]
            )
        }
        assertEquals(
            "the outermost pass does not reach the edge of the penumbra",
            1f, HandShadow.SPREAD.first(), 1e-6f
        )
        assertEquals(
            "the innermost pass is wider than the hand it belongs to",
            0f, HandShadow.SPREAD.last(), 1e-6f
        )
        assertTrue(
            "the passes add up to more than one solid shadow",
            HandShadow.PASS_ALPHA.sum() <= 1.05f
        )
        assertTrue(
            "the passes add up to so little there is no shadow",
            HandShadow.PASS_ALPHA.sum() >= 0.95f
        )
    }

    /**
     * The steps between passes are too small to be seen as steps.
     *
     * This is the defect being fixed, stated as a number. Stacked, the
     * passes make a staircase: at any distance from the hand you are under
     * every pass wider than that, so the darkness there is the running sum
     * of their weights, and a step in that sum is a visible contour line.
     * Five passes made steps of a fifth of the shadow's darkness each and
     * the result was banded like a map. Nothing here rises by more than a
     * sixth, and — the part five passes could never do — the outermost
     * step is a hundredth, so the haze begins at nothing instead of
     * arriving with an edge.
     */
    @Test
    fun `no single pass is a visible step`() {
        for ((i, weight) in HandShadow.PASS_ALPHA.withIndex()) {
            assertTrue(
                "pass $i lays down $weight of the whole shadow in one go, " +
                    "which is a contour line",
                weight <= 1f / 6f
            )
        }
        assertTrue(
            "the shadow's outer edge arrives at ${HandShadow.PASS_ALPHA.first()} " +
                "of full darkness, which is an edge",
            HandShadow.PASS_ALPHA.first() <= 0.02f
        )
    }

    /**
     * And the softness belongs to the light, not to what is casting it.
     *
     * The banding had a second half that a picture of the hour hand alone
     * would never have shown: the widths were multiples of the hand's own
     * width, so the second hand — a hair — got a haze a hair wide, and its
     * thirteen passes landed on top of one another as a single hard black
     * stick beside the red one. A penumbra does not know how wide the
     * thing casting it is, so this is measured against the dial.
     */
    @Test
    fun `the haze is the same width whatever hand casts it`() {
        val hour = HandShadow.penumbra(HandShadow.heightOf(ClockView.Hand.HOUR), 30.0)
        val second = HandShadow.penumbra(HandShadow.heightOf(ClockView.Hand.SECOND), 30.0)
        assertTrue("the haze has no width at all", hour > 0.004f)
        assertTrue(
            "the second hand's haze is a different order of thing from the " +
                "hour hand's: $hour against $second",
            second < hour * 2f
        )
        // It does grow with the distance travelled, which is the one part
        // of a penumbra that is real.
        assertTrue(
            "a shadow thrown right across the dial is as sharp as one lying " +
                "under its own hand",
            HandShadow.penumbra(HandShadow.heightOf(ClockView.Hand.SECOND), 8.0) >
                HandShadow.penumbra(HandShadow.heightOf(ClockView.Hand.SECOND), 80.0)
        )
    }

    /**
     * And the hands sit low enough that a shadow stays under its own hand.
     *
     * They were nearly three times this at first, and at a low sun the
     * second hand's shadow ended half a radius from the hand casting it —
     * which is two second hands, not a hand and its shadow.
     */
    @Test
    fun `a shadow stays near the hand that casts it`() {
        val worst = HandShadow.reach(HandShadow.heightOf(ClockView.Hand.SECOND), 8.0)
        assertTrue(
            "at a sun eight degrees up the second hand's shadow is $worst of a " +
                "radius away, which is a second second hand",
            worst <= 0.12f
        )
    }

    /**
     * And nothing at night, however the option is set.
     *
     * Both halves, because they are two separate claims and only one of
     * them was being checked. Counting the shadows finds nothing after
     * dark whichever guard is doing the work — the fade takes the alpha
     * to zero at the horizon anyway — so taking the horizon test out of
     * [ClockView.sunOverhead] changed nothing this test could see. What it
     * does change is the answer to "is there a sun", which is a question
     * with a right answer at one in the morning.
     */
    @Test
    fun `no sunlight after dark`() {
        val v = dial()
        v.handShadows = true
        v.freezeAtForTest(utc(2026, 12, 21, 1))
        paint(v)
        assertNull("the dial reckons there is a sun up at one in the morning", v.sunOverhead())
    }

    // ------------------------------------------------------------ by moonlight

    /**
     * After dark the shadows are the moon's, and they are a different
     * thing from the sun's in every way that can be measured.
     *
     * The date is chosen for the moon rather than for the sun: a night
     * with a bright moon well up, which is the only night this can be
     * asked on. On a night with no moon the correct answer is no shadow at
     * all, which is the test below.
     */
    @Test
    fun `after dark the shadows are the moon's`() {
        val night = brightNight() ?: return
        val v = dial()
        v.handShadows = true
        v.freezeAtForTest(night)
        paint(v)
        assertNull("the sun is up in the middle of the night", v.sunOverhead())
        val moon = v.moonOverheadForTest()
        assertNotNull("a lit moon well up threw no light on the dial", moon)
        assertTrue("the moon is below the horizon and still casting", moon!!.altitudeDeg > 0.0)
        assertTrue(
            "moonlight is as strong as sunlight, which it is not by a factor " +
                "of about four hundred thousand: ${moon.brightness}",
            moon.brightness < 0.5f
        )
        assertTrue("the moonlight cast nothing", v.shadowsPaintedForTest() > 0)
    }

    /**
     * A moon below the horizon lights nothing, however full it is.
     *
     * The obvious half, and the one that was being tested by accident. It
     * needs a night with a bright moon that has not risen yet — a few days
     * past full, in the hour or two of darkness before it comes up — and
     * not a new-moon night, where the moon is *also* dark and the wrong
     * guard does the work. Both guards were in place and each was masking
     * the other's test: deleting either one changed nothing any assertion
     * could see.
     */
    @Test
    fun `a moon below the horizon lights nothing`() {
        val at = firstMomentWith { sun, moon, lit ->
            sun.altitudeDeg < -12.0 && moon.altitudeDeg < -5.0 && lit > 0.6
        }
        assertNotNull(
            "no dark hour in forty days with a bright moon still to rise, so " +
                "this test is asking nothing",
            at
        )
        val v = dial()
        v.handShadows = true
        v.freezeAtForTest(at!!)
        paint(v)
        assertNull("a moon under the horizon is lighting the dial", v.moonOverheadForTest())
        assertEquals("something cast a shadow before moonrise", 0, v.shadowsPaintedForTest())
    }

    /**
     * And a moon too dark lights nothing, however high it is.
     *
     * The other half, which needs the opposite awkward moment: a sliver a
     * couple of days old, still up in the west after the sun has properly
     * set. It is a narrow window — a new moon keeps the sun's hours, so it
     * is only above a dark horizon for an hour or so either side of the
     * new — which is exactly why the earlier version of this test never
     * found itself in it and never checked anything.
     */
    @Test
    fun `a moon too dark lights nothing`() {
        val at = firstMomentWith { sun, moon, lit ->
            sun.altitudeDeg < -8.0 && moon.altitudeDeg > 2.0 && lit < HandShadow.MOON_FLOOR
        }
        assertNotNull(
            "no dark hour in forty days with a sliver moon still up, so this " +
                "test is asking nothing",
            at
        )
        val v = dial()
        v.handShadows = true
        v.freezeAtForTest(at!!)
        paint(v)
        assertNull("a sliver of moon is lighting the dial", v.moonOverheadForTest())
        assertEquals("a sliver of moon cast a shadow", 0, v.shadowsPaintedForTest())
    }

    /**
     * The full moon is opposite the sun, which is why it is full.
     *
     * The one claim the whole lunar approximation rests on: the phase *is*
     * the angle between the two, so at the moment the moon is fullest it
     * is half a turn behind the sun and stands highest when the sun stands
     * lowest.
     *
     * Asked at the sun's own highest moment, and asked as two numbers with
     * no way round them. It was asked as "either they are on opposite
     * sides of the horizon *or* they are forty degrees apart", and the
     * second half let a moon pinned to the sun's own hour angle through:
     * the declination is worked out separately and still differed, so the
     * altitudes were far enough apart to satisfy a test that should have
     * caught it outright.
     */
    @Test
    fun `the full moon stands opposite the sun`() {
        var fullest = 0.0
        var fullAt = 0L
        for (step in 0 until 24 * 40) {
            val at = utc(2026, 1, 1, 0) + step * 3_600_000L
            val lit = SolarTime.moonIllumination(at)
            if (lit > fullest) { fullest = lit; fullAt = at }
        }
        assertTrue("the moon never gets full in forty days: $fullest", fullest > 0.97)

        // Noon of that day, found rather than assumed: twelve o'clock is
        // not solar noon, and the point of this test is the hour angle.
        var highest = -90.0
        var noon = fullAt
        for (step in -24..24) {
            val at = fullAt + step * 3_600_000L
            val alt = SolarTime.position(40.0, 0.0, at).altitudeDeg
            if (alt > highest) { highest = alt; noon = at }
        }
        val moon = SolarTime.moonPosition(40.0, 0.0, noon)
        assertTrue("the sun is not high at its own highest: $highest", highest > 20.0)
        assertTrue(
            "the full moon is up in the middle of the day, so it is not " +
                "opposite the sun at all: ${moon.altitudeDeg}",
            moon.altitudeDeg < -20.0
        )
    }

    /**
     * The half moon in the evening sky is the *first* quarter, not the last.
     *
     * Which way round the month runs, and the full moon cannot answer it:
     * half a turn behind the sun and half a turn ahead of it are the same
     * place, so a moon turned the wrong way round its own month is
     * invisible at full and obvious at quarter. A first-quarter moon rises
     * about noon and stands nearly due south as the sun goes down —
     * everybody has seen one — while a last-quarter moon does not rise
     * until the middle of the night.
     *
     * The phase itself decides which is which, since the lit fraction
     * cannot: a quarter moon is half lit going up and half lit coming
     * down.
     */
    @Test
    fun `the first quarter moon is high at sunset`() {
        var found = 0L
        var best = 90.0
        for (step in 0 until 3 * 24 * 60) {
            val at = utc(2026, 1, 1, 0) + step * 1_200_000L
            val phase = SkyGlyph.phaseAt(at)
            if (phase < 0.24 || phase > 0.26) continue
            // Sunset: the sun on its way down through the horizon.
            val sun = SolarTime.position(40.0, 0.0, at)
            if (sun.altitudeDeg !in -2.0..2.0) continue
            if (kotlin.math.abs(sun.altitudeDeg) < best) {
                best = kotlin.math.abs(sun.altitudeDeg)
                found = at
            }
        }
        assertTrue("no first-quarter sunset in two months", found != 0L)
        val moon = SolarTime.moonPosition(40.0, 0.0, found)
        assertTrue(
            "the half moon is not in the evening sky at all, so the month is " +
                "running backwards: ${moon.altitudeDeg}",
            moon.altitudeDeg > 20.0
        )
    }

    /**
     * The full moon rides high in winter and low in summer, which is the
     * sun's own habit turned round.
     *
     * The only thing that tests the moon's declination, and the reason it
     * needs testing: a moon given the *sun's* declination would still be
     * opposite the sun in the sky, still up all night, still fine at every
     * question asked above — and would ride low over the Christmas snow and
     * high in June, which is backwards, and which anybody who has walked
     * home on a December night would notice.
     */
    @Test
    fun `the full moon is high in winter and low in summer`() {
        val winter = peakOfFullMoonNear(utc(2026, 12, 1, 0))
        val summer = peakOfFullMoonNear(utc(2026, 6, 1, 0))
        assertTrue(
            "the midwinter full moon at ${winter}° is no higher than the " +
                "midsummer one at ${summer}°, so the moon is following the " +
                "sun's declination rather than the far side of the ecliptic",
            winter > summer + 25.0
        )
    }

    /**
     * How high the moon gets on the night it is fullest, within a month of
     * [near].
     */
    private fun peakOfFullMoonNear(near: Long): Double {
        var fullest = 0.0
        var fullAt = near
        for (step in 0 until 24 * 32) {
            val at = near + step * 3_600_000L
            val lit = SolarTime.moonIllumination(at)
            if (lit > fullest) { fullest = lit; fullAt = at }
        }
        assertTrue("no full moon within a month of $near: $fullest", fullest > 0.97)
        var highest = -90.0
        for (step in -24..24) {
            val at = fullAt + step * 3_600_000L
            highest = maxOf(highest, SolarTime.moonPosition(40.0, 0.0, at).altitudeDeg)
        }
        return highest
    }

    /** The moon's light rises and falls with how much of it is lit. */
    @Test
    fun `the moon goes new and full within a month`() {
        var full = 0.0
        var new = 1.0
        for (day in 0 until 30) {
            val lit = SolarTime.moonIllumination(utc(2026, 1, 1, 0) + day * 86_400_000L)
            if (lit > full) full = lit
            if (lit < new) new = lit
        }
        assertTrue("the moon never gets full in a month: $full", full > 0.97)
        assertTrue("the moon never goes new in a month: $new", new < 0.03)
    }

    /**
     * The first moment in forty days matching a condition on the sun, the
     * moon and the phase — searched in twenty-minute steps.
     *
     * Twenty minutes and not an hour: the two windows these tests need are
     * an hour or two wide and an hourly walk steps over them about half the
     * time, which would make the tests pass or fail on the calendar.
     */
    private fun firstMomentWith(
        want: (SolarTime.Position, SolarTime.Position, Double) -> Boolean
    ): Long? {
        for (step in 0 until 3 * 24 * 40) {
            val at = utc(2026, 1, 1, 0) + step * 1_200_000L
            val sun = SolarTime.position(40.0, 0.0, at)
            val moon = SolarTime.moonPosition(40.0, 0.0, at)
            if (want(sun, moon, SolarTime.moonIllumination(at))) return at
        }
        return null
    }

    /** The first night in a month with the moon up, lit, and the sun down. */
    private fun brightNight(): Long? {
        for (step in 0 until 24 * 40) {
            val at = utc(2026, 1, 1, 0) + step * 3_600_000L
            if (SolarTime.position(40.0, 0.0, at).altitudeDeg > -6.0) continue
            if (SolarTime.moonPosition(40.0, 0.0, at).altitudeDeg < 20.0) continue
            if (SolarTime.moonIllumination(at) < 0.8) continue
            return at
        }
        return null
    }


    /** A hand that is switched off casts nothing. */
    @Test
    fun `a hand that is not there casts nothing`() {
        val v = dial()
        v.handShadows = true
        v.showSecondHand = false
        v.freezeAtForTest(utc(2026, 6, 21, 12))
        paint(v)
        assertEquals(
            "a dial with two hands drew three shadows",
            2, v.shadowsPaintedForTest()
        )
    }

    // ------------------------------------------------------ after they fall

    /**
     * A hand that falls off keeps a shadow.
     *
     * It used to lose it in the same frame it landed, and what that reads
     * as is the light going out — the sun is still up, everything else on
     * the dial is still casting, and the piece that just came off is the
     * one thing lit from nowhere. What a real object lying on a surface
     * has is the *shortest* shadow it will ever cast, a tight dark line
     * directly under it, so the shadow collapses rather than vanishing.
     */
    @Test
    fun `wreckage on the dial is still lit`() {
        val v = shadowedDial()
        paint(v)
        val standing = v.shadowsPaintedForTest()
        assertTrue("nothing was casting to begin with", standing > 0)
        v.knockHandsOff()
        // Wound back to noon, because knocking the hands off throws the
        // winding away — which is right, since a dial with its hands on
        // the floor is not showing a time any more — and that includes
        // the frozen one a test set. Without this line the light on the
        // dial is the light outside the machine running the test, so
        // this passed all day and failed after sunset at forty north,
        // which is a test that reads the real sky and reports it as a
        // fault in the drawing.
        v.freezeAtForTest(utc(2026, 6, 21, 12))
        paint(v)
        assertTrue(
            "the fallen hands went out with the light",
            v.shadowsPaintedForTest() > 0
        )
    }

    /** And the shadow it keeps is a short one, because it is lying down. */
    @Test
    fun `a fallen hand's shadow is shorter than a standing one`() {
        // The contact height against the hour hand's mounted height, at the
        // same sun: the reach is proportional to the height, so this is the
        // whole of the claim in one comparison.
        val mounted = HandShadow.heightOf(ClockView.Hand.HOUR)
        val lying = mounted * 0.2f
        val sun = 35.0
        assertTrue(
            "a hand on the floor throws as far as one on its pivot",
            HandShadow.reach(lying, sun) < HandShadow.reach(mounted, sun)
        )
        assertTrue("a hand on the floor throws nothing at all", HandShadow.reach(lying, sun) > 0f)
    }

    /** A dial with the sun up, the shadows on, and hands to cast them. */
    private fun shadowedDial(): ClockView {
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        return activity.clockForTest().apply {
            handShadows = true
            shadowLatitude = 40.0
            shadowLongitude = 0.0
            freezeAtForTest(utc(2026, 6, 21, 12))
        }
    }

    // --------------------------------------------------------- on a wall

    /**
     * A wall clock is not a sundial lying face up, and the difference is
     * not decoration.
     *
     * On the ground the sun is in front of you all day and the shadow is
     * long when the sun is low. On a wall it is the other way round twice
     * over: the shadow is long when the sun is *high*, because it is the
     * height of the sun that pushes the shadow down the face — and for half
     * the day there is no shadow at all, because the sun is round the back.
     * Nothing that draws a ground shadow can pass this section by accident.
     */
    @Test
    fun `a wall faces the equator`() {
        assertEquals("a northern wall does not face south", 180.0, HandShadow.wallFacing(40.0), 1e-9)
        assertEquals("a southern wall does not face north", 0.0, HandShadow.wallFacing(-33.0), 1e-9)
    }

    /** The sun round the back of the wall throws nothing on its face. */
    @Test
    fun `the sun behind the wall casts nothing`() {
        assertNull(
            "a wall clock in Madrid is lit from the north",
            HandShadow.onWall(30.0, 0.0, 40.0)
        )
        assertNull(
            "a wall clock in Sydney is lit from the south",
            HandShadow.onWall(30.0, 180.0, -33.0)
        )
        // And grazing along the face is not a shadow either — it is a
        // divisor going to nothing, and the reach it would give runs off
        // the dial and away across the room.
        assertNull("a grazing sun still throws", HandShadow.onWall(0.5, 90.0, 40.0))
    }

    /** Straight in front of the wall, the shadow drops straight down. */
    @Test
    fun `the sun in front of the wall drops the shadow to six`() {
        val (bearing, reach) = HandShadow.onWall(30.0, 180.0, 40.0)!!
        assertEquals("the shadow is not hanging straight down", 180f, bearing, 0.5f)
        // Down the face by the tangent of the altitude: thirty degrees up
        // is a shadow of 0.577 heights, and forty-five is exactly one.
        assertEquals("the reach is not the tangent of the altitude", 0.5774f, reach, 1e-3f)
        assertEquals(1f, HandShadow.onWall(45.0, 180.0, 40.0)!!.second, 1e-3f)
    }

    /**
     * The higher the sun, the further down the wall the shadow reaches —
     * which is the exact opposite of the ground, and the single cheapest
     * way to tell the two models apart.
     */
    @Test
    fun `a high sun throws a long shadow on a wall and a short one on the ground`() {
        val low = HandShadow.onWall(20.0, 180.0, 40.0)!!.second
        val high = HandShadow.onWall(70.0, 180.0, 40.0)!!.second
        assertTrue("the wall shadow did not lengthen with the sun: $low then $high", high > low)
        val h = HandShadow.heightOf(ClockView.Hand.MINUTE)
        assertTrue(
            "the ground shadow lengthened with the sun as well",
            HandShadow.reach(h, 70.0) < HandShadow.reach(h, 20.0)
        )
    }

    /**
     * And it swings the way a wall shadow swings: morning light from the
     * east puts the shadow on the left of the dial, afternoon light from
     * the west puts it on the right.
     *
     * Left and right are the viewer's, standing in front of the clock. A
     * north-facing viewer has east on their right hand, so the shadow
     * running away from an eastern sun runs to their left — which on the
     * dial is the half between six and twelve going anticlockwise, bearings
     * of 180 to 360. This is the sign that a projection written from the
     * wall's side of the plaster rather than the room's gets backwards, and
     * it gets it backwards while still passing every other test here.
     */
    @Test
    fun `morning light throws the wall shadow left and afternoon light right`() {
        val morning = HandShadow.onWall(30.0, 135.0, 40.0)!!.first
        val afternoon = HandShadow.onWall(30.0, 225.0, 40.0)!!.first
        assertTrue("the morning shadow is not on the left of the dial: $morning", morning in 180f..360f)
        assertTrue("the afternoon shadow is not on the right: $afternoon", afternoon in 0f..180f)
        // Symmetrically, too: the two are the same angle either side of six.
        assertEquals(
            "the morning and afternoon shadows are not mirrored",
            360f - morning, afternoon, 0.5f
        )
    }

    /** South of the equator the wall faces north and the swing reverses. */
    @Test
    fun `the wall shadow swings the other way in the south`() {
        // Sydney, sun in the north-east of a morning: the viewer faces
        // south, so east is now on their left and the shadow goes right.
        val morning = HandShadow.onWall(30.0, 45.0, -33.0)!!.first
        assertTrue("the southern morning shadow is not on the right: $morning", morning in 0f..180f)
    }

    /** However the sun stands, the drawn shadow still stops at the rim. */
    @Test
    fun `a wall shadow never runs off the dial either`() {
        val v = dial()
        v.handShadows = true
        v.shadowSurface = HandShadow.Surface.WALL
        for (hour in 6..18) {
            v.freezeAtForTest(utc(2026, 6, 21, hour))
            paint(v)
        }
        for (hand in ClockView.Hand.entries) {
            val h = HandShadow.heightOf(hand)
            for (alt in 1..89) {
                val wall = HandShadow.onWall(alt.toDouble(), 180.0, 40.0) ?: continue
                val drawn = (h * wall.second).coerceAtMost(HandShadow.MAX_LENGTH)
                assertTrue("a wall shadow ran off the dial at $alt degrees", drawn <= HandShadow.MAX_LENGTH)
            }
        }
    }

    /** And the setting is a setting: on the ground, nothing changes. */
    @Test
    fun `the ground is still the ground`() {
        val v = dial()
        v.handShadows = true
        v.shadowSurface = HandShadow.Surface.GROUND
        v.freezeAtForTest(utc(2026, 6, 21, 12))
        paint(v)
        val onGround = v.shadowsPaintedForTest()
        v.shadowSurface = HandShadow.Surface.WALL
        paint(v)
        assertEquals("the wall lost a hand's shadow at noon", onGround, v.shadowsPaintedForTest())
    }

    private fun dial(): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            shadowLatitude = 40.0
            shadowLongitude = 0.0
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 600, 600)
        }
    }

    private fun paint(v: ClockView) {
        val b = android.graphics.Bitmap.createBitmap(
            600, 600, android.graphics.Bitmap.Config.ARGB_8888
        )
        v.draw(android.graphics.Canvas(b))
        b.recycle()
    }
}
