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
            "a sun ten degrees up throws a shadow shorter than four heights",
            HandShadow.reach(h, 10.0) > h * 4f
        )
        assertEquals("the sun below the horizon casts something", 0f, HandShadow.reach(h, -1.0), 1e-6f)
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
        assertEquals("there are shadows at night", 0f, HandShadow.strength(-1.0), 1e-6f)
        assertEquals("there are shadows at the horizon", 0f, HandShadow.strength(0.0), 1e-6f)
        assertEquals("the midday shadow is not solid", 1f, HandShadow.strength(45.0), 1e-6f)
        val low = HandShadow.strength(3.0)
        assertTrue("a shadow at three degrees is as dark as one at noon: $low", low < 0.5f)
        assertTrue("a shadow at three degrees is not there at all", low > 0f)
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
    fun `no shadows after dark`() {
        val v = dial()
        v.handShadows = true
        v.freezeAtForTest(utc(2026, 12, 21, 1))
        paint(v)
        assertEquals("the hands throw shadows in the dark", 0, v.shadowsPaintedForTest())
        assertNull("the dial reckons there is a sun up at one in the morning", v.sunOverhead())
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
