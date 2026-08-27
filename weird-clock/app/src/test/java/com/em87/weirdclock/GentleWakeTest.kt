package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coming up rather than switching on.
 *
 * The sound has ramped for a long time; the screen was still doing the
 * other thing — black, then a slab of white light a foot from a face with
 * its eyes shut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GentleWakeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun wipe() {
        prefs.edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true)
                            .putBoolean(Prefs.FACE_ASKED, true).commit()
    }

    /**
     * It starts as a glow and never as nothing. A screen at zero looks
     * like a screen that failed to come on, and the one thing an alarm
     * screen must never do is look broken.
     */
    @Test
    fun `it starts as a glow, not as darkness`() {
        val at0 = GentleWake.brightness(0L, 60_000L)
        assertEquals(GentleWake.FLOOR, at0, 0.0001f)
        assertTrue("a screen at nothing looks broken", at0 > 0.02f)
        assertTrue("but it is still only a glow", at0 < 0.2f)
    }

    /** And arrives at full, and stays there. */
    @Test
    fun `it gets all the way up and stops`() {
        assertEquals(1f, GentleWake.brightness(60_000L, 60_000L), 0.0001f)
        assertEquals(1f, GentleWake.brightness(600_000L, 60_000L), 0.0001f)
        assertFalse(GentleWake.ramping(60_000L, 60_000L))
        assertTrue(GentleWake.ramping(59_000L, 60_000L))
    }

    /** Never backwards: a ramp that dipped would read as a fault. */
    @Test
    fun `it only ever goes up`() {
        var last = -1f
        for (ms in 0..60_000 step 250) {
            val now = GentleWake.brightness(ms.toLong(), 60_000L)
            assertTrue("$ms: $now after $last", now >= last)
            last = now
        }
    }

    /**
     * Slow at the start, where somebody is still asleep. Brightness is not
     * perceived in proportion to the number, so a straight ramp spends
     * most of its length looking finished and all of its gentleness in the
     * first second.
     */
    @Test
    fun `the slow part is at the beginning`() {
        val half = GentleWake.brightness(30_000L, 60_000L)
        val straight = GentleWake.FLOOR + (1f - GentleWake.FLOOR) * 0.5f
        assertTrue("$half vs $straight", half < straight)
        // A quarter of the way in it is barely up off the floor.
        assertTrue(GentleWake.brightness(15_000L, 60_000L) < 0.2f)
    }

    /**
     * Every way of asking for no ramp ends with the screen simply on.
     * Nothing here may divide by zero, and nothing may leave a dark screen
     * in front of a ringing alarm.
     */
    @Test
    fun `no ramp means on, never dark and never a crash`() {
        assertEquals(1f, GentleWake.brightness(0L, 0L), 0.0001f)
        assertEquals(1f, GentleWake.brightness(5_000L, -1L), 0.0001f)
        assertFalse(GentleWake.ramping(0L, 0L))
        assertEquals(GentleWake.OFF, GentleWake.seconds(null))
        assertEquals(GentleWake.OFF, GentleWake.seconds("wat"))
        assertEquals(60, GentleWake.seconds("60"))
        assertEquals("and nothing absurd", GentleWake.LONGEST, GentleWake.seconds("99999"))
    }

    /**
     * And then the torch, for the sleeper the sunrise does not reach.
     *
     * After, and never instead. A flash from the first second is the alarm
     * equivalent of shouting, and there is a separate per-alarm setting
     * for people who want that; this one waits for the gentle half to have
     * had its go and failed. Without a sunrise there is nothing for it to
     * be the second half of, so it never fires at all.
     */
    @Test
    fun `the torch waits for the sunrise to have failed`() {
        assertEquals(60_000L, GentleWake.flashAfterMs(60, wanted = true))
        assertEquals(600_000L, GentleWake.flashAfterMs(600, wanted = true))
        assertEquals("not asked for", -1L, GentleWake.flashAfterMs(60, wanted = false))
        assertEquals(
            "nothing to be the second half of",
            -1L, GentleWake.flashAfterMs(0, wanted = true)
        )
        assertTrue(
            "and never at the same moment the screen starts",
            GentleWake.flashAfterMs(60, true) > 0L
        )
    }

    // -------------------------------------------------------- on the screen

    private fun ring(seconds: Int = 0, body: (AlarmRingActivity) -> Unit) {
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_GENTLE, seconds)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            body(c.get())
        }
    }

    /**
     * With nothing asked for, the window is left exactly as it was. Taking
     * the screen brightness over at all is a thing to do only when
     * somebody has said so — otherwise the alarm screen would override
     * whatever the phone was set to, every morning, for no reason.
     */
    @Test
    fun `off means the screen brightness is never touched`() {
        ring { app ->
            assertEquals(0L, app.gentleRampMs)
            assertEquals(
                "the window must be left alone",
                android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                app.screenBrightness,
                0.0001f
            )
        }
    }

    /**
     * And it is down at the glow before the window has drawn once.
     *
     * Set from a posted message instead, the screen came on at whatever
     * brightness the phone was on and dropped to the glow a frame later —
     * a blink in a dark room, which is the exact opposite of the point.
     * Read here before the queue is allowed to run, because after it has
     * run the two are indistinguishable.
     */
    @Test
    fun `the glow is in place before the first frame`() {
        val controller = Robolectric.buildActivity(
            AlarmRingActivity::class.java,
            android.content.Intent(context, AlarmRingActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_GENTLE, 60)
        ).create()
        try {
            assertEquals(
                "the screen was left at full brightness for a frame",
                GentleWake.FLOOR, controller.get().screenBrightness, 0.01f
            )
        } finally {
            controller.destroy()
        }
    }

    /** And with one, the screen starts down at the glow. */
    @Test
    fun `on means it comes up from the floor`() {
        ring(seconds = 60) { app ->
            assertEquals(60_000L, app.gentleRampMs)
            assertEquals(GentleWake.FLOOR, app.screenBrightness, 0.01f)
        }
    }

    /**
     * A screen rebuilt half way through — a rotation, the system putting
     * it back over the lock screen — must not start again from the dark.
     * By then somebody is looking at it, and a screen that dived back to a
     * glow under their eyes would look like a fault.
     */
    @Test
    fun `the ramp is counted from the ringing, not from the screen`() {
        // The service started ringing thirty seconds ago; the screen is
        // being built now.
        val elapsed = GentleWake.elapsed(ringingSince = 1_000L, now = 31_000L)
        assertEquals(30_000L, elapsed)
        assertTrue(
            "it must arrive already half way up",
            GentleWake.brightness(elapsed, 60_000L) > GentleWake.FLOOR + 0.1f
        )
    }

    /**
     * And with nothing ringing there is no history to pick up, so the ramp
     * starts at the beginning rather than at some huge number that would
     * put the screen straight to full.
     */
    @Test
    fun `with nothing ringing the ramp starts at the start`() {
        assertEquals(0L, GentleWake.elapsed(ringingSince = 0L, now = 900_000L))
        assertEquals("nor ever backwards", 0L, GentleWake.elapsed(5_000L, 1_000L))
    }
}
