package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The ring screen once somebody has picked the phone up.
 *
 * The sunrise is for somebody asleep, and it went on being for somebody
 * asleep after they were not: two minutes into a five-minute ramp the
 * screen is at a fifth of full, which in a room that is already light is a
 * black rectangle — and with a sum on it, a black rectangle with the
 * answer hidden inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WokenScreenTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun ringing(): android.content.Intent =
        android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_GENTLE, 300)

    @Test
    fun `a touch takes the screen up to what the room needs`() {
        AlarmService.ringingSince = android.os.SystemClock.elapsedRealtime()
        Robolectric.buildActivity(AlarmRingActivity::class.java, ringing()).use { c ->
            c.setup()
            val screen = c.get()
            val asleep = screen.screenBrightness
            assertTrue("the sunrise did not start dim: $asleep", asleep < 0.3f)

            screen.roomForTest(400f)
            screen.touchForTest()
            ShadowSystemClock.advanceBy(Duration.ofMillis(GentleWake.TAKEOVER_MS + 200))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(Duration.ofMillis(GentleWake.TAKEOVER_MS + 400))
            assertEquals(
                "the screen stayed dark under somebody's thumb",
                GentleWake.forRoom(400f), screen.screenBrightness, 0.02f
            )
        }
    }

    /**
     * And when the sunrise is over the phone gets its own brightness back,
     * rather than being left at full.
     */
    @Test
    fun `the sunrise hands the screen back at the end`() {
        AlarmService.ringingSince = android.os.SystemClock.elapsedRealtime()
        Robolectric.buildActivity(
            AlarmRingActivity::class.java,
            android.content.Intent(context, AlarmRingActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_GENTLE, 1)
        ).use { c ->
            c.setup()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(Duration.ofSeconds(3))
            assertEquals(
                "the screen was left at whatever the sunrise ended on",
                GentleWake.THE_PHONE_S_OWN, c.get().screenBrightness, 0.0001f
            )
        }
    }
}
