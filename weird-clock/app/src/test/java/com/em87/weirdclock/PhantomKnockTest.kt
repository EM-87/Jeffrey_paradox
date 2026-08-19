package com.em87.weirdclock

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/**
 * The blow that never happened.
 *
 * A phone was dropped hard, the clock was fine, and the hands were lying on
 * the floor of the case after a trip to the calendar and back. The drop had
 * nothing to do with it.
 *
 * A knock is the difference between a raw accelerometer reading and a
 * smoothed one, and the smoothed one is a field that outlives the listener.
 * Leaving the dial for another card detaches it and stops the sensor;
 * coming back starts it again, and the first reading is measured against a
 * smoothed value from whenever the view was last on screen. Any change of
 * posture in between reads as a blow — and a phone that has been carried to
 * another room is in a different posture by definition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class PhantomKnockTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .commit()
        Shadows.shadowOf(context.getSystemService(SensorManager::class.java))
            .addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
    }

    private fun dial(): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        val clock = ClockView(themed)
        clock.shakeDropEnabled = true
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1000, 1600)
        return clock
    }

    /** A reading of the phone's own gravity, down whichever axis. */
    private fun reading(x: Float, y: Float, z: Float, times: Int = 1) {
        val shadow = Shadows.shadowOf(context.getSystemService(SensorManager::class.java))
        repeat(times) {
            val event = shadow.createSensorEvent()
            val field = android.hardware.SensorEvent::class.java.getField("values")
            field.isAccessible = true
            field.set(event, floatArrayOf(x, y, z))
            shadow.sendSensorEventToListeners(event)
        }
    }

    /**
     * Coming back to the dial in a different posture does not knock the
     * hands off.
     *
     * Upright when the dial was last seen, flat on a table when it comes
     * back — which is nine and a half metres per second squared of
     * difference, four times the threshold, and no blow at all.
     */
    @Test
    fun `a dial coming back in a different posture is not being hit`() {
        val clock = dial()
        clock.onAttachedToWindowForTest()
        reading(0f, 9.81f, 0f, times = 30)
        assertFalse("something fell while the phone sat still", clock.isDisarranged())

        // The drop: a real one, hard enough that the smoothed value is
        // dragged a long way from where the phone actually is. This is the
        // part that matters — a gentle change of posture would not have
        // been enough to fake a blow, and the phone in the story was
        // dropped on the floor.
        reading(0f, 60f, 0f, times = 6)
        // Which knocks them off, quite rightly. Put back, so that anything
        // on the floor afterwards is the phantom and not the drop.
        clock.reassembleAll()

        // A few seconds pass, which is what going to another card and back
        // takes. Without them the guard that stops one blow being counted
        // twice would swallow the phantom, and the test would pass for a
        // reason that has nothing to do with the fix.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofSeconds(4)
        )

        // Away to another card, and back with the phone lying flat.
        clock.onDetachedFromWindowForTest()
        clock.onAttachedToWindowForTest()
        reading(0f, 0f, 9.81f, times = 30)
        assertFalse(
            "the hands came off on the way back from the calendar",
            clock.isDisarranged()
        )
    }

    /**
     * And a real rap on the glass still takes them off.
     *
     * The settling has to be short enough that a knock a moment after
     * arriving still counts — otherwise the fix is just a way of never
     * noticing anything.
     */
    @Test
    fun `a real knock still knocks`() {
        val clock = dial()
        clock.onAttachedToWindowForTest()
        reading(0f, 9.81f, 0f, times = 30)
        assertFalse("set up wrong", clock.isDisarranged())

        // A blow: one reading a long way from where the phone has been
        // sitting, which is what a rap on the glass looks like.
        reading(0f, 9.81f + 40f, 0f)
        assertTrue("a genuine knock was ignored", clock.isDisarranged())
    }

    /**
     * The settling is a handful of readings, not a wait.
     *
     * Long enough for the smoothing to sit on the phone's real posture and
     * short enough to be over before anybody could rap the glass.
     */
    @Test
    fun `the settling is over almost at once`() {
        val clock = dial()
        clock.onAttachedToWindowForTest()
        reading(0f, 0f, 9.81f, times = ClockView.settleSamplesForTest() + 2)
        reading(0f, 0f, 9.81f + 40f)
        assertTrue(
            "the dial was still settling long after it should have been",
            clock.isDisarranged()
        )
    }
}
