package com.em87.weirdclock

import android.view.View
import android.widget.ImageButton
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving between cards, driven the way a finger drives it.
 *
 * Every one of the three things wrong with the last build was a mechanism
 * that worked, tested on its own, wired up wrong — the crown knew how to be
 * inherited and was asked for it a line too late; the cards knew not to
 * slide when the hands carry the move and were told to anyway; the swipe
 * into the empty page was swallowed by a dial that the pager had stopped
 * listening to. None of them could fail a test of the mechanism, because
 * none of them was a fault in the mechanism.
 *
 * So these press the buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavigationTest {

    private fun <T> onApp(body: (android.app.Activity) -> T): T =
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            body(controller.get())
        }

    private fun android.app.Activity.card(id: Int): View = findViewById(id)

    private fun android.app.Activity.press(id: Int) {
        findViewById<ImageButton>(id).performClick()
    }

    private fun android.app.Activity.pager(): ViewPager2 = findViewById(R.id.pager)

    private fun android.app.Activity.clock(): ClockView = findViewById(R.id.clock_view)

    private fun android.app.Activity.stopwatch(): ClockView =
        findViewById(R.id.stopwatch_clock_view)

    /**
     * The stopwatch is below the clock now, so the button that goes there
     * stays on the same page — and both cards have hands, so the hands make
     * the journey and the cards hold still.
     */
    @Test
    fun `the clock hands the stopwatch its hands, and nothing slides`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)

            assertEquals("no page change", Cards.PAGE_HOME, app.pager().currentItem)
            assertEquals(View.VISIBLE, app.card(R.id.stopwatch_container).visibility)
            assertEquals(View.GONE, app.card(R.id.clock_container).visibility)
            assertTrue(
                "the hands must be travelling, which is the whole transition",
                app.stopwatch().isTravelling()
            )
            assertEquals(
                "and the card must not be sliding underneath them",
                0f, app.card(R.id.stopwatch_container).translationY, 0.01f
            )
        }
    }

    /**
     * And coming back, the crown comes with them.
     *
     * The dial being left is not on screen to fade anything, so the clock
     * inherits its crown and dissolves it in place. That is asked of the
     * outgoing dial — "did you have one?" — and it was being asked after
     * the outgoing dial had already been told to take it off, so the answer
     * was always no and the whole mechanism sat there doing nothing.
     */
    @Test
    fun `and coming back the clock wears the crown it inherited`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            assertTrue("the stopwatch wears one", app.stopwatch().isCrownShowing())

            app.press(R.id.stopwatch_back_button)

            assertFalse("the clock has no crown of its own", app.clock().chronoButtons)
            assertTrue(
                "but it must be wearing the one it was handed, and fading it",
                app.clock().isCrownShowing()
            )
        }
    }

    /**
     * The bottom row is two cards wide and the pager is three, so there is
     * a page under it with nothing on it. Swallowing the gesture in the
     * dial did not stop the pager reaching it: by the time a swipe has been
     * recognised the pager has been dragging for a while.
     */
    @Test
    fun `there is no dragging onto the empty page below the calendar`() {
        onApp { app ->
            assertTrue("the middle row is three wide", app.pager().isUserInputEnabled)
            app.press(R.id.to_stopwatch_button)
            assertFalse(
                "the bottom row is not, so it must not be draggable",
                app.pager().isUserInputEnabled
            )
        }
    }

    /** Sideways along the bottom row still works, by the dial's own hand. */
    @Test
    fun `the stopwatch and the countdown are one move apart`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            app.press(R.id.to_countdown_button)
            assertEquals(Cards.PAGE_RIGHT, app.pager().currentItem)
            assertEquals(View.VISIBLE, app.card(R.id.countdown_container).visibility)
        }
    }

    /**
     * And by swiping, which is the whole point of putting them side by
     * side — driven through the dial's own callback, because the button
     * and the swipe are two different paths to the same place and testing
     * one of them says nothing about the other.
     */
    @Test
    fun `and one swipe apart`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            // Finger to the left: the card to the right comes over.
            val handled = app.stopwatch().onHorizontalSwipe?.invoke(false)
            assertEquals(true, handled)
            assertEquals(Cards.PAGE_RIGHT, app.pager().currentItem)
        }
    }

    /** The other way there is nothing, and the swipe dies where it is. */
    @Test
    fun `and a swipe off the far side of the stopwatch goes nowhere`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            val handled = app.stopwatch().onHorizontalSwipe?.invoke(true)
            assertEquals("swallowed rather than passed on", true, handled)
            assertEquals("and nothing moved", Cards.PAGE_HOME, app.pager().currentItem)
        }
    }

    /**
     * The hourglass is above the clock and has no hands, so there is
     * nothing to hand over and the card itself makes the journey.
     *
     * Which is the other half of the first test: a slide is right here and
     * wrong between two dials, and the difference is visible in one number.
     */
    @Test
    fun `the hourglass arrives from above, sliding`() {
        onApp { app ->
            app.press(R.id.mode_button)
            assertEquals(Cards.PAGE_HOME, app.pager().currentItem)
            assertEquals(View.VISIBLE, app.card(R.id.hourglass_container).visibility)
            assertTrue(
                "it must start off screen and travel, not simply appear",
                app.card(R.id.hourglass_container).translationY != 0f
            )
            // And the clock is still there, on its way out, until it lands.
            assertEquals(View.VISIBLE, app.card(R.id.clock_container).visibility)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                1, java.util.concurrent.TimeUnit.SECONDS
            )
            assertEquals(View.GONE, app.card(R.id.clock_container).visibility)
        }
    }
}
