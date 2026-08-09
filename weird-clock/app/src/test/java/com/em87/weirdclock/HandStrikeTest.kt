package com.em87.weirdclock

import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hitting a world-clock bubble with the clock's own hands.
 *
 * The bubbles bounced off this dial as though it were a boulder, which is
 * what it is — a boulder with three arms sweeping round *inside* it, none
 * of which could touch anything. So the case opens up while somebody has
 * hold of a hand: wind one back, let go, and whatever is lying in its arc
 * goes with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandStrikeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun dial(): ClockView = ClockView(context).apply {
        measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 720, 720)
    }

    // ------------------------------------------------------------ the hands

    @Test
    fun `a clock hands out its three arms`() {
        assertEquals(3, dial().mountedHands().size)
    }

    @Test
    fun `a hand that is not shown is not one of them`() {
        assertEquals(2, dial().apply { showSecondHand = false }.mountedHands().size)
    }

    /** A hand on the floor cannot hit anything: it is not on the axis. */
    @Test
    fun `nor one lying at the bottom of the case`() {
        val clock = dial()
        clock.knockHandsOff()
        assertTrue(
            "the hands came off, so there is nothing swinging",
            clock.mountedHands().size < 3
        )
    }

    /** Furniture until you touch it. */
    @Test
    fun `a clock nobody is touching has no hand in play`() {
        assertFalse(dial().handInPlay())
    }

    @Test
    fun `and one being wound does`() {
        val clock = dial()
        // Where the second hand actually is, not where twelve is: a plain
        // clock shows the real time, so aiming at noon catches nothing for
        // fifty-nine minutes out of every hour.
        val angle = clock.handAngleForTest(ClockView.Hand.SECOND)
        val rad = Math.toRadians(angle.toDouble())
        clock.grabHandNear(
            360f + (260.0 * kotlin.math.sin(rad)).toFloat(),
            360f - (260.0 * kotlin.math.cos(rad)).toFloat()
        )
        assertNotNull(clock.draggedHandForTest())
        assertTrue(clock.handInPlay())
    }

    // ------------------------------------------------------------ the strike

    /** A bar lying along the x axis, a hundred long and ten thick. */
    private val bar = ClockView.HandBar(0f, 0f, 100f, 0f, 10f)

    @Test
    fun `a bubble nowhere near the hand is not struck`() {
        assertNull(HandStrike.contact(bar, 50f, 400f, radius = 20f))
    }

    @Test
    fun `one resting against it is pushed clear`() {
        val hit = HandStrike.contact(bar, 50f, 25f, radius = 20f)!!
        assertEquals("straight out of the bar", 0f, hit.nx, 0.001f)
        assertEquals(1f, hit.ny, 0.001f)
        // Twenty-five out, and it may come no closer than thirty.
        assertEquals(5f, hit.push, 0.001f)
    }

    /**
     * The point of the whole thing: a hand pivots, so it is not moving at
     * one speed. Caught on the tip it goes like a putt; caught by the boss,
     * hardly at all. A single "the hand is going this fast" would be wrong
     * everywhere but one radius.
     */
    @Test
    fun `where along the arm it lands is what makes it a shot`() {
        val atTheBoss = HandStrike.contact(bar, 5f, 25f, radius = 20f)!!
        val atTheTip = HandStrike.contact(bar, 95f, 25f, radius = 20f)!!
        assertTrue("near the axis it is barely moving", atTheBoss.alongArm < 0.1f)
        assertTrue("out at the end it is flying", atTheTip.alongArm > 0.9f)
    }

    @Test
    fun `and past the end it is the end that hits`() {
        // Beyond the tip, so the nearest point on the bar is the tip itself.
        val hit = HandStrike.contact(bar, 118f, 0f, radius = 20f)!!
        assertEquals(1f, hit.alongArm, 0.001f)
        assertEquals("pushed out along the bar, not across it", 1f, hit.nx, 0.001f)
    }

    /**
     * A bubble dropped exactly on the bar has no direction to be pushed in,
     * and dividing by that distance spreads NaN through the whole table.
     */
    @Test
    fun `a bubble impaled dead centre is left alone rather than made NaN`() {
        assertNull(HandStrike.contact(bar, 50f, 0f, radius = 20f))
    }
}
