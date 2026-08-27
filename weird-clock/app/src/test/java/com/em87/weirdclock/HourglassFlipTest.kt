package com.em87.weirdclock

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The accelerometer, the countdown, and the evening the alarm went off in
 * somebody's hand.
 *
 * [HourglassTest] checks the rule. This checks that the rule is what the
 * phone actually obeys — driven with real accelerometer readings through
 * the real listener, because the bug was never in the arithmetic. Three
 * minutes were set, the phone went into a pocket, and the countdown read
 * the moment it passed upside down as somebody turning an hourglass over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class HourglassFlipTest {

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

    /** Gravity down the phone's Y axis: +9.81 upright, -9.81 on its head. */
    private fun gravity(activity: MainActivity, y: Float, samples: Int = 24) {
        val manager = context.getSystemService(SensorManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        repeat(samples) {
            val event = shadow.createSensorEvent()
            // A SensorEvent cannot be built by hand — the constructor is
            // hidden and the readings arrive in a final array the framework
            // fills. Reflection is the only way in, and the alternative is
            // not testing the listener at all, which is where the bug was.
            val field = android.hardware.SensorEvent::class.java.getField("values")
            field.isAccessible = true
            field.set(event, floatArrayOf(0f, y, 0f))
            shadow.sendSensorEventToListeners(event)
        }
    }

    private fun runningCountdown(card: Card, block: (MainActivity) -> Unit) {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(card)
            activity.startCountdownForTest(3 * 60_000L)
            // Upright first, so the phone has somewhere to be turned from.
            gravity(activity, 9.81f)
            block(activity)
        }
    }

    /**
     * A phone that passes upside down on its way somewhere does not turn
     * the glass.
     *
     * The failure exactly: with three minutes on it, a countdown turned
     * over has five seconds left, and the alarm goes off in your pocket.
     */
    @Test
    fun `a phone passing upside down leaves the countdown alone`() {
        runningCountdown(Card.REVERSE) { activity ->
            val before = activity.countdownRemainingForTest()
            gravity(activity, -9.81f)
            // Through and out again inside the time it takes to put a phone
            // in a pocket.
            ShadowSystemClock.advanceBy(Duration.ofMillis(200))
            gravity(activity, 9.81f)
            assertEquals(
                "a pocket turned the hourglass over",
                before.toDouble(), activity.countdownRemainingForTest().toDouble(), 1500.0
            )
        }
    }

    /** Held there, it does turn — the gesture still works. */
    @Test
    fun `a phone stood on its head turns the glass`() {
        runningCountdown(Card.REVERSE) { activity ->
            val before = activity.countdownRemainingForTest()
            gravity(activity, -9.81f)
            ShadowSystemClock.advanceBy(Duration.ofMillis(Hourglass.HOLD_MS + 200))
            // A further reading, because the decision is made when one
            // arrives and not by a timer of its own.
            gravity(activity, -9.81f, samples = 1)
            val after = activity.countdownRemainingForTest()
            assertTrue(
                "three minutes barely started should have turned to almost nothing, not $after",
                after < 20_000L
            )
            assertTrue("and it did not simply stop", before > after)
        }
    }

    /**
     * And it does not turn from a card where the sand is not on screen.
     *
     * The other half: it used to act from anywhere off the middle row, so
     * the stopwatch could silently rewrite a countdown running behind it.
     */
    @Test
    fun `the stopwatch cannot turn the countdown behind its back`() {
        runningCountdown(Card.STOPWATCH) { activity ->
            val before = activity.countdownRemainingForTest()
            gravity(activity, -9.81f)
            ShadowSystemClock.advanceBy(Duration.ofMillis(Hourglass.HOLD_MS + 400))
            gravity(activity, -9.81f, samples = 1)
            assertEquals(
                "a card that does not draw the countdown turned it over",
                before.toDouble(), activity.countdownRemainingForTest().toDouble(), 2000.0
            )
        }
    }

    /**
     * Held upside down, it turns once and not again.
     *
     * Otherwise a phone left face-down on a desk would flip the sand back
     * and forth on every reading, which is both wrong and unwatchable.
     */
    @Test
    fun `holding it upside down turns it once`() {
        runningCountdown(Card.REVERSE) { activity ->
            gravity(activity, -9.81f)
            ShadowSystemClock.advanceBy(Duration.ofMillis(Hourglass.HOLD_MS + 200))
            gravity(activity, -9.81f, samples = 1)
            val afterOne = activity.countdownRemainingForTest()
            ShadowSystemClock.advanceBy(Duration.ofMillis(2000))
            // One further reading, and one only. An even number would turn
            // it and turn it back, landing on the right answer for the
            // wrong reason — which is exactly what this test did at first.
            gravity(activity, -9.81f, samples = 1)
            assertEquals(
                "it kept turning while nobody touched it",
                afterOne.toDouble(), activity.countdownRemainingForTest().toDouble(), 2500.0
            )
        }
    }
}
