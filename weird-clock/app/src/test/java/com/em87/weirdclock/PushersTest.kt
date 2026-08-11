package com.em87.weirdclock

import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three things a chronograph is expected to do that it was not doing,
 * pressed the way a thumb presses them.
 *
 * All three were reported off one afternoon with the app on a real phone,
 * and all three are wiring rather than mechanism: the back gesture was
 * never handled at all, the volume keys were handled behind a switch nobody
 * had a reason to find, and stopping fired a haptic constant that cannot be
 * felt through a case. So these drive the buttons and the keys and the
 * gesture, not the functions underneath them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushersTest {

    private fun onApp(body: (MainActivity) -> Unit) =
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            body(controller.get())
        }

    private fun MainActivity.press(id: Int) {
        findViewById<ImageButton>(id).performClick()
    }

    private fun MainActivity.stopwatch(): ClockView = findViewById(R.id.stopwatch_clock_view)

    private fun MainActivity.countdown(): ClockView = findViewById(R.id.countdown_clock_view)

    private fun MainActivity.back() = onBackPressedDispatcher.onBackPressed()

    // ------------------------------------------------------- the back button

    /**
     * The oldest of the lot: with the three-button navigation bar, the one
     * on the left closed the app from wherever you were standing.
     */
    @Test
    fun `back off the stopwatch comes home to the clock`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)

            app.back()

            assertFalse("the app must still be open", app.isFinishing)
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.clock_container).visibility)
        }
    }

    /** And across a page as well as a row: the countdown is a diagonal. */
    @Test
    fun `back off the countdown comes home too`() {
        onApp { app ->
            app.press(R.id.to_countdown_button)
            assertEquals(Cards.PAGE_RIGHT, app.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).currentItem)

            app.back()

            assertFalse(app.isFinishing)
            assertEquals(
                "and on the page the clock lives on",
                Cards.PAGE_HOME,
                app.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.pager).currentItem
            )
        }
    }

    /**
     * From the clock there is nowhere further back, and back must still
     * mean back: a callback that swallowed everything would be a clock you
     * could not leave.
     */
    @Test
    fun `and from the clock it leaves`() {
        onApp { app ->
            app.back()
            assertTrue("the clock is the last card back", app.isFinishing)
        }
    }

    // ------------------------------------------------------ the volume keys

    /**
     * No preference any more: the stopwatch card makes no sound and nobody
     * sits on it, so there is nothing the keys could be wanted for instead.
     */
    @Test
    fun `volume up works the stopwatch with nothing switched on`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)

            assertTrue("the key must be taken", app.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null))
            assertTrue("and start it", app.stopwatch().chronoRunning)
        }
    }

    /**
     * And the release of the same press, which is the half that was
     * getting through. The window handles the volume keys on the way up
     * as well, and that is the half that puts the volume slider on the
     * screen — so the pusher worked and a volume panel slid over the dial
     * at the same time.
     */
    @Test
    fun `the release of the key is taken too`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            assertTrue(app.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, null))
            assertTrue(app.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, null))
        }
    }

    /** On the clock they are the volume keys, as they always were. */
    @Test
    fun `and on the clock they are left alone`() {
        onApp { app ->
            assertFalse(
                "the clock has no pushers to work",
                app.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null)
            )
            assertFalse(
                "on the way up either",
                app.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, null)
            )
        }
    }

    // ------------------------------------------------- the rows, by button

    /**
     * A flick up or down no longer moves between rows.
     *
     * The hourglass and the two chronographs are secondary things, reached
     * by the button that names them. A gesture that took you to one by
     * accident — off a dial you were winding, or a bubble you were
     * flicking — cost more than it ever saved.
     */
    @Test
    fun `no dial answers a flick up or down any more`() {
        onApp { app ->
            for (id in intArrayOf(
                R.id.clock_view, R.id.stopwatch_clock_view, R.id.countdown_clock_view
            )) {
                val dial = app.findViewById<ClockView>(id)
                assertEquals(
                    "a dial still listens for vertical swipes",
                    null, dial?.onVerticalSwipe
                )
            }
        }
    }

    /** And the buttons still do, which is the whole point of keeping them. */
    @Test
    fun `the buttons are the way between rows`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            assertEquals(
                View.VISIBLE,
                app.findViewById<View>(R.id.stopwatch_container).visibility
            )
            app.back()
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.clock_container).visibility)
        }
    }

    /**
     * The stopwatch starts when it is pressed, not up to a second later.
     *
     * How long until the next frame is worked out at the moment a frame is
     * posted, and a stopped chronograph asks for one a second. So the
     * ticker was already sitting on a delay of up to a second when start
     * was pressed, and pressing it only invalidated: the hand stayed still
     * under the thumb that had just pressed it. Stopping felt quicker only
     * because it was already running at sixty frames a second.
     */
    @Test
    fun `starting and stopping the stopwatch ask for a frame at once`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            val dial = app.stopwatch()

            val before = dial.tickerKicks
            dial.chronoRunning = true
            assertTrue(
                "start did not restart the frame loop",
                dial.tickerKicks > before
            )

            val running = dial.tickerKicks
            dial.chronoRunning = false
            assertTrue("nor did stop", dial.tickerKicks > running)

            // And setting it to what it already is costs nothing.
            val settled = dial.tickerKicks
            dial.chronoRunning = false
            assertEquals(settled, dial.tickerKicks)
        }
    }

    // ---------------------------------------------------------- the feel

    /**
     * Starting buzzed and stopping did not. Both fired — stopping asked for
     * VIRTUAL_KEY, the tick a keyboard makes, which through a case is
     * nothing at all — so the fault was never going to show up as a missing
     * call. What has to be different is what is played.
     */
    @Test
    fun `starting and stopping do not feel the same`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)

            app.stopwatch().onChronoStartStop?.invoke()
            val started = app.lastPusherFeel
            app.stopwatch().onChronoStartStop?.invoke()
            val stopped = app.lastPusherFeel

            assertEquals(Pusher.Feel.START, started)
            assertEquals(Pusher.Feel.STOP, stopped)
            assertNotEquals(
                "and they must not play the same thing",
                Pusher.pattern(Pusher.Feel.START).toList(),
                Pusher.pattern(Pusher.Feel.STOP).toList()
            )
        }
    }

    /** Reset had no feel at all, on either dial. */
    @Test
    fun `wiping the stopwatch is felt`() {
        onApp { app ->
            app.press(R.id.to_stopwatch_button)
            app.stopwatch().onChronoReset?.invoke()
            assertEquals(Pusher.Feel.RESET, app.lastPusherFeel)
        }
    }

    @Test
    fun `and wiping the countdown, which never called anything at all`() {
        onApp { app ->
            app.press(R.id.to_countdown_button)
            app.countdown().onChronoReset?.invoke()
            assertEquals(Pusher.Feel.RESET, app.lastPusherFeel)
        }
    }

    /**
     * Four presses, four different things under the thumb — the point of
     * giving them shapes at all is telling them apart without looking.
     */
    @Test
    fun `every pusher has a shape of its own`() {
        val shapes = Pusher.Feel.entries.map { Pusher.pattern(it).toList() }
        assertEquals(shapes.size, shapes.distinct().size)
        for (feel in Pusher.Feel.entries) {
            val pattern = Pusher.pattern(feel)
            assertEquals("$feel starts at once", 0L, pattern[0])
            assertTrue(
                "$feel is a button press, not a notification",
                pattern.sum() < 250L
            )
        }
    }

    /** And a phone told to keep still is kept still. */
    @Test
    fun `the touch feedback setting is honoured`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        android.provider.Settings.System.putInt(
            context.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            0
        )
        assertFalse(Pusher.wanted(context))
        android.provider.Settings.System.putInt(
            context.contentResolver,
            android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        )
        assertTrue(Pusher.wanted(context))
    }
}
