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
 * How far a knock reaches, which is not as far as it did.
 *
 * A view stays attached for as long as the app exists — Android does not
 * take the hierarchy apart when an activity stops — so a dial that picks
 * the accelerometer up on attach and puts it down on detach is listening
 * with the screen off, in a pocket, with the app closed. Somebody set
 * their phone down hard one evening and opened the clock the next morning
 * to find its hands on the floor, having never touched it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class KnockReachTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .commit()
        Shadows.shadowOf(context.getSystemService(SensorManager::class.java))
            .addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
    }

    private fun dial(): ClockView {
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return ClockView(themed).apply {
            shakeDropEnabled = true
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 1600)
            onAttachedToWindowForTest()
        }
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

    /** A rap on the glass, hard enough to take the hands off. */
    private fun rap() {
        reading(0f, 9.81f, 0f, times = 30)
        reading(0f, 9.81f + 40f, 0f)
    }

    @Test
    fun `a knock on a dial in front of you takes the hands off`() {
        val clock = dial()
        rap()
        assertTrue("a genuine knock was ignored", clock.isDisarranged())
    }

    /**
     * And the same knock with the window gone does nothing at all.
     *
     * This is the whole of the fix: the window going away is the one
     * signal that says the app is not being looked at — stopped, screen
     * off, another app in front — and the view is still very much
     * attached through all of it.
     */
    @Test
    fun `a knock with the screen off does not reach the dial`() {
        val clock = dial()
        clock.windowVisibilityForTest(visible = false)
        rap()
        assertFalse("the hands came off with the app closed", clock.isDisarranged())

        // And it comes back when the window does, without waiting for a
        // detach and re-attach that will never happen.
        clock.windowVisibilityForTest(visible = true)
        rap()
        assertTrue("the dial never picked the accelerometer back up", clock.isDisarranged())
    }

    /** A card that is not the one being looked at hears nothing either. */
    @Test
    fun `a dial on another card hears nothing`() {
        val clock = dial()
        clock.shakeDropEnabled = false
        rap()
        assertFalse("a dial nobody is looking at took the knock", clock.isDisarranged())
    }
}
