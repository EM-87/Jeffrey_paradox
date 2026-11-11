package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The second shadow: the clock's time beside the sun's.
 *
 * A dial tells the sun's time and a phone tells the zone's. In Madrid in
 * July they are two hours and twenty minutes apart — two hours of zone
 * and summer time, a quarter of an hour of standing west of the zone's
 * meridian, and a few minutes of the equation of time. The second shadow
 * is blue and lies where the first would if the sun kept the phone's
 * time, so the gap is on the plate to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SundialClockShadowTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun inMadrid() {
        PhoneZone.move("Europe/Madrid")
    }

    @After
    fun home() {
        PhoneZone.goHome()
        TimeKeeper.solarOffsetMs = 0L
    }

    /** Two in the afternoon on the phone, the first of July 2026, in Madrid. */
    private val twoByTheClock: Long =
        java.util.GregorianCalendar(TimeZone.getTimeZone("Europe/Madrid")).apply {
            set(2026, java.util.Calendar.JULY, 1, 14, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dial(at: Long, clockShadow: Boolean): SundialView =
        SundialView(context).apply {
            // A pale stone, where a shadow is a thing that can be seen.
            theme = ClockThemes.IVORY
            latitude = 40.4168
            longitude = -3.7038
            compass = false
            instruments = false
            civilShadow = clockShadow
            atMs = at
            measure(
                View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, SIZE, SIZE)
        }

    private fun drawn(view: SundialView): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Which way a ray points, in degrees clockwise from straight up the plate. */
    private fun bearing(ray: FloatArray): Double =
        Math.toDegrees(atan2(ray[2].toDouble(), -ray[3].toDouble()))

    /**
     * How many pixels along a ray are plainly blue: thirty-one of them,
     * from three tenths of its length — clear of the gnomon, which stands
     * on the foot of every shadow — to six tenths, where it starts to go
     * out. A hair to one side of the middle, because the clock's shadow
     * at a whole hour lies exactly along an hour line, and the line is
     * cut through it.
     */
    private fun blueAlong(map: Bitmap, ray: FloatArray): Int {
        var blue = 0
        for (k in 30..60) {
            val x = (ray[0] + ray[2] * ray[4] * k / 100f - ray[3] * 7f).toInt()
            val y = (ray[1] + ray[3] * ray[4] * k / 100f + ray[2] * 7f).toInt()
            val p = map.getPixel(x, y)
            if (Color.blue(p) > Color.red(p) + 25) blue++
        }
        return blue
    }

    /** One shadow is what a dial has, until the second is asked for. */
    @Test
    fun `there is no second shadow unless it is asked for`() {
        val plain = dial(twoByTheClock, clockShadow = false)
        val map = drawn(plain)
        assertNotNull("the sun casts no shadow at two in July", plain.shadowRay)
        assertNull("a second shadow nobody asked for", plain.clockShadowRay)
        // And nothing blue anywhere the second one would lie.
        val asked = dial(twoByTheClock, clockShadow = true)
        drawn(asked)
        assertEquals("blue on a plate with one shadow", 0, blueAlong(map, asked.clockShadowRay!!))
    }

    /**
     * Asked for, it lies on the line of the hour the phone says, and the
     * sun's lies where the sun is.
     *
     * Two o'clock on the phone is twenty to twelve by the sun in Madrid
     * in July: the blue shadow is on the II line and the black one just
     * short of the noon line, and the two are two hours and twenty
     * minutes of dial apart.
     */
    @Test
    fun `the second shadow lies on the hour the clock says`() {
        val view = dial(twoByTheClock, clockShadow = true)
        val map = drawn(view)
        File("build/screenshots").mkdirs()
        File("build/screenshots/sundial-clock-shadow.png").outputStream()
            .use { map.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val sun = view.shadowRay
        val clock = view.clockShadowRay
        assertNotNull("the sun's shadow went when the clock's came", sun)
        assertNotNull("asked for, there is no second shadow", clock)
        val twoLine = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, 40.4168, 2.0)
        assertEquals("the clock's shadow is not on the II line", twoLine, bearing(clock!!), 0.01)
        // By the sun it is 11:41: fourteen minutes and three quarters for
        // the longitude, four for the equation of time, two hours of zone.
        val bySun = view.hoursFromNoonForTest()
        assertEquals("the sun is not where Madrid has it", -0.31, bySun, 0.03)
        assertEquals(
            "the sun's shadow moved with the clock's",
            Sundial.lineAngle(Sundial.Kind.HORIZONTAL, 40.4168, bySun), bearing(sun!!), 0.01
        )
        assertTrue("the two shadows lie together", abs(bearing(clock) - bearing(sun)) > 20.0)

        // And it is blue, and the sun's is not.
        assertTrue("the clock's shadow is not blue: ${blueAlong(map, clock)} of 31", blueAlong(map, clock) >= 28)
        assertEquals("the sun's shadow has gone blue", 0, blueAlong(map, sun))
    }

    /**
     * In the hand the second shadow is cast the way the first is: by a
     * sun in the sky, on a style that turns with the plate.
     *
     * Held, the plate turns under the shadows and the time is right at
     * the one heading where they meet the lines. A second shadow drawn on
     * its hour line whatever the phone was doing would be right at every
     * heading, which is a picture of a dial and not a dial. Lined up on
     * the meridian it lies on the II line; turned thirty degrees off, it
     * lies where a sun at two o'clock's place would throw the style's
     * shadow on a plate turned thirty degrees — which is not thirty
     * degrees back, because the style has turned too.
     */
    @Test
    fun `held in the hand, the second shadow is cast by a sun and not drawn on a line`() {
        val twoLine = Sundial.lineAngle(Sundial.Kind.HORIZONTAL, 40.4168, 2.0)
        val declination = SolarTime.declinationDeg(twoByTheClock)
        val clockSun = SolarTime.positionAt(40.4168, 2.0 * Sundial.DEGREES_PER_HOUR, declination)
        for (turned in listOf(0.0, 30.0)) {
            val view = dial(twoByTheClock, clockShadow = true).apply {
                compass = true
                phoneBearing = turned
            }
            drawn(view)
            val clock = view.clockShadowRay
            assertNotNull("no second shadow in the hand, turned $turned°", clock)
            val axes = Sundial.levelPlate(turned)
            val cast = Sundial.shadowOnPlate(
                clockSun.altitudeDeg, clockSun.azimuthDeg, axes[0], axes[1], axes[2], 40.4168
            )!!
            assertEquals("turned $turned° from north", cast, bearing(clock!!), 0.01)
            if (turned == 0.0) {
                assertEquals("lined up, it is not on the II line", twoLine, bearing(clock), 0.5)
            } else {
                assertTrue(
                    "turned $turned°, it is still on the II line",
                    abs(bearing(clock) - twoLine) > 5.0
                )
            }
        }
    }

    /** A dial shows nothing after sunset, and that goes for both shadows. */
    @Test
    fun `there is no second shadow once the sun is down`() {
        // Half past ten at night on the phone: 20:10 by the sun, and the
        // sun set at 19:25 by the sun. The clock's own sun would be down
        // too; an hour earlier it is the real one that decides.
        val night = dial(twoByTheClock + (8 * 60 + 30) * 60_000L, clockShadow = true)
        drawn(night)
        assertNull(night.shadowRay)
        assertNull("a shadow at half past ten at night", night.clockShadowRay)
        // Half past six in the morning on the phone is ten past four by
        // the sun, before sunrise: the hour is on the plate and the sun
        // is not in the sky.
        val early = dial(twoByTheClock - (7 * 60 + 30) * 60_000L, clockShadow = true)
        drawn(early)
        assertNull("the sun is up at ten past four", early.shadowRay)
        assertNull("the clock casts a shadow before the sun is up", early.clockShadowRay)
        // And the other way about. Half past five on a December afternoon
        // in Madrid: the real sun is two and a half degrees up, at a
        // quarter past four by its own time, and the sun that kept the
        // clock's would have set — so there is the sun's shadow and no
        // second one, although half past five has a line on the plate.
        val winter = java.util.GregorianCalendar(TimeZone.getTimeZone("Europe/Madrid")).apply {
            set(2026, java.util.Calendar.DECEMBER, 21, 17, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dusk = dial(winter, clockShadow = true)
        drawn(dusk)
        assertNotNull("the sun has set at a quarter past four by the sun", dusk.shadowRay)
        assertNull("a shadow cast by a sun that would have set", dusk.clockShadowRay)
    }

    /**
     * This face keeps the sun's time by being a sundial, not by being
     * told to.
     *
     * "Solar time" moves every dial's clock to the sun's. Handed that, the
     * sundial worked out where the sun was at an instant already moved,
     * and its shadow stood out by the same amount again.
     */
    @Test
    fun `solar time switched on does not move the shadow`() {
        val view = SundialView(context).apply {
            latitude = 40.4168
            longitude = -3.7038
        }
        TimeKeeper.solarOffsetMs = 0L
        val plain = view.hoursFromNoonForTest()
        TimeKeeper.solarOffsetMs = -2 * 3_600_000L - 20 * 60_000L
        val shifted = view.hoursFromNoonForTest()
        assertEquals("the shadow moved with the dials' clock", plain, shifted, 0.01)
    }

    private companion object {
        const val SIZE = 1000
    }
}
