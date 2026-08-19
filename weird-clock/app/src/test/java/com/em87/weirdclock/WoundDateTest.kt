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

    /**
     * Takes hold of a hand and turns it [degrees], the way a finger does.
     *
     * Where along the hand matters, and getting it wrong is silent. This
     * dial untangles a pile of hands by how far out the finger landed —
     * near the middle is the short hand, out by the rim is the long one —
     * so a grab aimed at the *middle* of the minute hand is inside the
     * hour hand's ring and comes away with the hour hand. Which is what
     * happened: eleven turns meant for the minute hand went onto the hour
     * hand instead, blew its two-day budget, and threw the whole
     * mechanism — date included — onto the floor of the case. The test
     * then reported that the date was not being drawn, which was true and
     * was not the fault of anything it was testing.
     *
     * So the grab is aimed at the hand's own ring, and the hand that came
     * away is checked before anything is wound.
     */
    private fun wind(v: ClockView, hand: ClockView.Hand, degrees: Double) {
        val cx = v.width / 2f
        val cy = v.height / 2f
        val tip = v.handTipForTest(hand)
        val reach = kotlin.math.hypot(tip.x - cx, tip.y - cy)
        // Between the tip of the hand below and this hand's own tip, which
        // is the band the dial reserves for it.
        val inner = when (hand) {
            ClockView.Hand.HOUR -> 0.15f
            ClockView.Hand.MINUTE -> 0.52f / 0.74f
            ClockView.Hand.SECOND -> 0.74f / 0.82f
        }
        val grip = reach * (inner + 1f) / 2f
        var deg = Math.toDegrees(
            kotlin.math.atan2((tip.y - cy).toDouble(), (tip.x - cx).toDouble())
        )
        val a0 = Math.toRadians(deg)
        touch(
            v, android.view.MotionEvent.ACTION_DOWN,
            (cx + grip * kotlin.math.cos(a0)).toFloat(),
            (cy + grip * kotlin.math.sin(a0)).toFloat()
        )
        assertEquals("the grab came away with the wrong hand", hand, v.grabbedHandForTest())
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

    /**
     * The one way the date really does stop following the hands: it is not
     * on the dial any more.
     *
     * Wind a hand past what the mechanism will take and the whole thing
     * comes apart — hands, numerals, moon and the date, all of it lying in
     * the bottom of the case. The piece that falls keeps the date it was
     * showing when it fell, frozen, because it is a piece of debris and
     * not a display; and from then on winding the hands moves nothing,
     * because there is nothing up there to move.
     *
     * Which is exactly what "the date has stopped updating" looks like
     * from the outside, and worth pinning down as a thing the clock does
     * on purpose rather than a thing it does wrong. The way back is the
     * tidy-up button, and this checks that it is a way back.
     */
    @Test
    fun `a date knocked off the dial stops following, and comes back tidied`() {
        val v = dial(ClockView.DateFormatStyle.NUMBER)
        val before = painted(v)
        assertTrue("nothing was painted at all", before.isNotBlank())

        // Two days is the hour hand's whole budget; three is past it.
        windHours(v, ClockView.Hand.HOUR, 72.0)
        assertTrue("winding a hand three days did not blow the mechanism", v.isDisarranged())

        // Counted, not read. The explosion also throws the wind away, so
        // the date the dial *would* compute goes back to today — which is
        // the string that was there before it fell. Reading the string
        // therefore cannot tell a frozen date from a redrawn one, and an
        // earlier version of this test could not: it passed just as
        // happily with the fallen check taken out.
        val paints = v.datePaintsForTest()
        painted(v)
        painted(v)
        assertEquals(
            "a date lying in the case is still being drawn on the dial as well",
            paints, v.datePaintsForTest()
        )
        assertEquals(
            "and the string it is frozen at is not the one it fell with",
            before, v.dateShownForTest()
        )

        v.reassembleAll()
        val from = System.currentTimeMillis()
        windHours(v, ClockView.Hand.HOUR, 36.0)
        assertEquals(
            "the date did not start following again once the dial was tidied",
            v.dateTextAtForTest(from + 36 * 3_600_000L), painted(v)
        )
    }
}
