package com.em87.weirdclock

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Working a clock you cannot see.
 *
 * The dial has read itself aloud for a long time, which is the easy half.
 * The other half was missing entirely: every control a chronograph has —
 * start, stop, lap, reset — is a pusher painted onto the canvas, and paint
 * is not a button. A screen reader found a rectangle that announced the
 * time and offered no way to do anything with it, so two of the six cards
 * could be read and not used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccessibilityTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun dial(build: ClockView.() -> Unit = {}): ClockView =
        ClockView(context).apply {
            build()
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
        }

    private fun node(view: ClockView): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().also { view.onInitializeAccessibilityNodeInfo(it) }

    private fun labelFor(view: ClockView, id: Int): CharSequence? =
        node(view).actionList.firstOrNull { it.id == id }?.label

    @Test
    fun `a plain clock says what time it is`() {
        assertFalse(node(dial()).contentDescription.isNullOrBlank())
    }

    /** And offers nothing to press, because it has nothing to press. */
    @Test
    fun `a plain clock offers no chronograph actions`() {
        val actions = node(dial()).actionList.map { it.id }
        assertFalse(actions.contains(R.id.a11y_chrono_start_stop))
        assertFalse(actions.contains(R.id.a11y_chrono_reset))
    }

    @Test
    fun `a stopwatch can be started without seeing the pusher`() {
        var started = 0
        val watch = dial {
            chronoProvider = { 0L }
            chronoButtons = true
            onChronoStartStop = { started++ }
        }
        assertEquals(context.getString(R.string.a11y_start), labelFor(watch, R.id.a11y_chrono_start_stop))

        assertTrue(watch.performAccessibilityAction(R.id.a11y_chrono_start_stop, null))
        assertEquals("the pusher must actually have been pressed", 1, started)
    }

    /**
     * And the lower pusher says which of its two jobs it is doing, as it
     * does on a real chronograph: laps while it runs, zero when stopped.
     * A label that says "reset" on a running watch is worse than none.
     */
    @Test
    fun `the lower pusher renames itself when the watch is running`() {
        val watch = dial {
            chronoProvider = { 0L }
            chronoButtons = true
            onChronoReset = { }
        }
        assertEquals(
            context.getString(R.string.a11y_reset),
            labelFor(watch, R.id.a11y_chrono_reset)
        )

        watch.chronoRunning = true
        assertEquals(
            context.getString(R.string.a11y_lap),
            labelFor(watch, R.id.a11y_chrono_reset)
        )
        assertEquals(
            context.getString(R.string.a11y_pause),
            labelFor(watch, R.id.a11y_chrono_start_stop)
        )
    }

    /**
     * Shaking the hands off is one gesture; putting them back is dragging
     * each piece home, which is several — and neither is available to
     * somebody driving the app by voice. So the way out of the mess is
     * offered exactly while there is a mess to get out of.
     */
    @Test
    fun `there is a way to put a knocked dial back together`() {
        val clock = dial()
        assertFalse(
            "nothing to tidy yet",
            node(clock).actionList.any { it.id == R.id.a11y_reassemble }
        )

        clock.knockHandsOff()
        assertTrue("the dial must be in pieces", clock.isDisarranged())
        assertTrue(
            "and there must be a way back",
            node(clock).actionList.any { it.id == R.id.a11y_reassemble }
        )

        assertTrue(clock.performAccessibilityAction(R.id.a11y_reassemble, null))
        assertFalse("which must actually work", clock.isDisarranged())
    }

    // ------------------------------------------------- winding without a finger

    /**
     * A dial you can set, driven by the two actions a screen reader offers
     * for a value: the setting is a drag, and a drag is the one gesture a
     * screen reader keeps for itself.
     */
    private fun settable(profile: ClockView.MagnetProfile, start: Long): Pair<ClockView, () -> Long> {
        var value = start
        val dial = dial {
            chronoProvider = { value }
            chronoSettable = true
            magnetProfile = profile
            onChronoAdjusted = { value = it }
        }
        return dial to { value }
    }

    @Test
    fun `a countdown can be lengthened without touching it`() {
        val (dial, value) = settable(ClockView.MagnetProfile.COUNTDOWN, 0L)
        // Offered, not merely obeyed: an action a screen reader is never
        // told about is one nobody can reach, and driving it straight from
        // a test says nothing at all about that.
        assertTrue(
            "the action has to be advertised",
            node(dial).actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        )
        assertTrue(dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        // Under five minutes the grid is whole minutes, so that is the step.
        assertEquals(60_000L, value())
    }

    /**
     * The step is the magnet grid, not a number of its own — so a nudge
     * lands exactly where a finger would have been pulled, and the two
     * cannot drift apart because they are the same table. Past half an hour
     * the countdown's grid is quarters.
     */
    @Test
    fun `the step follows the grid the finger would have felt`() {
        val (dial, value) = settable(ClockView.MagnetProfile.COUNTDOWN, 45 * 60_000L)
        dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(60 * 60_000L, value())
    }

    /**
     * The *next* detent in that direction, strictly. Stepping by a fixed
     * amount from an odd value carries the oddness along for ever: nudged
     * off 5:20 a dial must land on 5:00, not on 10:20.
     */
    @Test
    fun `an odd setting is tidied by the first nudge`() {
        val (dial, value) = settable(ClockView.MagnetProfile.ALARM, 5 * 60_000L + 20_000L)
        dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(5 * 60_000L, value())
    }

    @Test
    fun `and a duration is never wound below nothing`() {
        val (dial, value) = settable(ClockView.MagnetProfile.COUNTDOWN, 0L)
        dial.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(0L, value())
    }

    /** A dial that is not for setting offers nothing to set. */
    @Test
    fun `a running clock cannot be wound by the screen reader`() {
        val clock = dial()
        val actions = node(clock).actionList.map { it.id }
        assertFalse(actions.contains(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertFalse(clock.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
    }

    /** And the labels say which kind of dial it is. */
    @Test
    fun `a time of day goes later, a length goes longer`() {
        val (length, _) = settable(ClockView.MagnetProfile.COUNTDOWN, 0L)
        assertEquals(
            context.getString(R.string.a11y_longer),
            node(length).actionList
                .firstOrNull { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }?.label
        )

        val (timeOfDay, _) = settable(ClockView.MagnetProfile.ALARM, 0L)
        timeOfDay.chronoWrapsDay = true
        assertEquals(
            context.getString(R.string.a11y_later),
            node(timeOfDay).actionList
                .firstOrNull { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }?.label
        )
    }
}
