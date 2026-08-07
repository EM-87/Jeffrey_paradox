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
}
