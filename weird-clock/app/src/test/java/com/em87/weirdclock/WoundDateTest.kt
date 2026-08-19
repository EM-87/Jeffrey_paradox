package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The date follows the hands.
 *
 * Carrying the hands round to tomorrow and having the dial show tomorrow is
 * the one thing a twelve-hour face can do that a list cannot, and it is the
 * reason the date window is on the face at all. It is also the sort of
 * thing that can quietly stop working — nothing crashes, the date simply
 * sits on today while the hands go round — so it is pinned here across
 * every way this clock can write a date and both hands that can carry it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class WoundDateTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun dial(style: ClockView.DateFormatStyle, hours: Int = 12): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            showDate = true
            showSecondHand = false
            dateFormatStyle = style
            hoursOnDial = hours
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1000)
        }
    }

    private fun touch(v: ClockView, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = android.view.MotionEvent.obtain(at, at, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    /** Takes hold of a hand and turns it [degrees], the way a finger does. */
    private fun wind(v: ClockView, hand: ClockView.Hand, degrees: Double) {
        val cx = v.width / 2f
        val cy = v.height / 2f
        val box = v.handBounds(hand)!!
        val grip = kotlin.math.hypot(box.exactCenterX() - cx, box.exactCenterY() - cy)
        var deg = Math.toDegrees(
            kotlin.math.atan2(
                (box.exactCenterY() - cy).toDouble(), (box.exactCenterX() - cx).toDouble()
            )
        )
        touch(v, android.view.MotionEvent.ACTION_DOWN, box.exactCenterX(), box.exactCenterY())
        var turned = 0.0
        while (turned < degrees) {
            turned += 15.0
            deg += 15.0
            val a = Math.toRadians(deg)
            touch(
                v, android.view.MotionEvent.ACTION_MOVE,
                (cx + grip * kotlin.math.cos(a)).toFloat(),
                (cy + grip * kotlin.math.sin(a)).toFloat()
            )
        }
    }

    /** Draws a frame and hands back the date that frame actually painted. */
    private fun painted(v: ClockView): String {
        val b = android.graphics.Bitmap.createBitmap(
            v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        v.draw(android.graphics.Canvas(b))
        b.recycle()
        return v.dateShownForTest()
    }

    /** Turns [hand] forward by [hours] of clock time, whatever that is in degrees. */
    private fun windHours(v: ClockView, hand: ClockView.Hand, hours: Double) {
        val perTurn = when (hand) {
            ClockView.Hand.HOUR -> v.hoursOnDial.toDouble()
            ClockView.Hand.MINUTE -> 1.0
            ClockView.Hand.SECOND -> 1.0 / 60.0
        }
        wind(v, hand, hours / perTurn * 360.0)
    }

    /**
     * Whichever hand carries the day round, and however the date is
     * written, the window follows.
     *
     * Wound in hours rather than in degrees, because degrees mean different
     * amounts of time on different faces — and each hand may only be wound
     * so far before the mechanism blows apart, which is a feature and not
     * something this test is about. A day and a half of hour hand crosses
     * midnight whatever time it is; eleven hours of minute hand may or may
     * not, so what is checked there is that the date is the right one for
     * where the hands have been put, whether or not that is a new day.
     */
    @Test
    fun `winding the hands past midnight moves the date`() {
        for (style in ClockView.DateFormatStyle.entries) {
            for (hours in listOf(2, 12, 24)) {
                val v = dial(style, hours)
                val before = painted(v)
                assertTrue("nothing was painted at all for $style", before.isNotBlank())
                val from = System.currentTimeMillis()
                windHours(v, ClockView.Hand.HOUR, 36.0)
                assertNotEquals(
                    "$style on a $hours-hour face did not carry its date round",
                    before, painted(v)
                )
                assertEquals(
                    "and the date it landed on is not the one it was wound to",
                    v.dateTextAtForTest(from + 36 * 3_600_000L), painted(v)
                )
            }
        }
    }

    /**
     * And by the minute hand, which cannot always reach tomorrow.
     *
     * Twelve hours is as far as it goes before the mechanism gives up, so
     * whether midnight is crossed depends on the hour the test runs at.
     * What must hold either way is that the window shows the date the
     * hands are standing on.
     */
    @Test
    fun `the minute hand carries the date as far as it reaches`() {
        val v = dial(ClockView.DateFormatStyle.NUMBER)
        val from = System.currentTimeMillis()
        windHours(v, ClockView.Hand.MINUTE, 11.0)
        assertEquals(
            "the window is not showing the day the hands were wound to",
            v.dateTextAtForTest(from + 11 * 3_600_000L), painted(v)
        )
    }

    /**
     * And it is the drawn date that moves, not merely the computed one.
     *
     * The two are one line apart and the interesting failure lives between
     * them: a value that is right and a face that goes on showing the old
     * one is exactly what "the date stopped updating" looks like.
     */
    @Test
    fun `the date on the glass is the date the dial worked out`() {
        val v = dial(ClockView.DateFormatStyle.NUMBER)
        wind(v, ClockView.Hand.HOUR, 900.0)
        assertTrue(
            "the face is showing '${painted(v)}' and the dial says " +
                "'${v.dateTextForTest()}'",
            painted(v) == v.dateTextForTest()
        )
    }
}
