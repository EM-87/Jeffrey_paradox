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
 * An alarm that keeps coming back.
 *
 * This is the most dangerous thing in the app — it is a phone that rings
 * at somebody until they deal with it — so almost everything here is about
 * the three ways it ends rather than the way it starts. The one test that
 * matters most is that a stop is a stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NagTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun wipe() {
        // The first-run hourglass offer would otherwise be sitting on
        // screen in front of the dialog these tests are about.
        prefs.edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true)
                            .putBoolean(Prefs.FACE_ASKED, true).commit()
    }

    /**
     * One ringing, as it arrives: the intent an alarm is carried on.
     *
     * Everything the nag and the snooze have to hand on lives here, because
     * both of them now book the next go by carrying this forward rather
     * than by remembering a list of fields.
     */
    private fun ringing(
        sound: String = Prefs.ALARM_SOUND_BELLS,
        label: String = "",
        mission: String = Mission.MATHS,
        level: Int = 4,
        vibrate: Boolean = true,
        flash: Boolean = false,
        gentle: Int = 0,
        gentleFlash: Boolean = false,
        snoozeMinutes: Int = 5
    ) = android.content.Intent()
        .putExtra(AlarmScheduler.EXTRA_SOUND, sound)
        .putExtra(AlarmScheduler.EXTRA_SOUND_URI, "")
        .putExtra(AlarmScheduler.EXTRA_LABEL, label)
        .putExtra(AlarmScheduler.EXTRA_SNOOZE, snoozeMinutes)
        .putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
        .putExtra(AlarmScheduler.EXTRA_FLASH, flash)
        .putExtra(AlarmScheduler.EXTRA_MISSION, mission)
        .putExtra(AlarmScheduler.EXTRA_MISSION_LEVEL, level)
        .putExtra(AlarmScheduler.EXTRA_GENTLE, gentle)
        .putExtra(AlarmScheduler.EXTRA_GENTLE_FLASH, gentleFlash)

    // ------------------------------------------------------ when it books

    /**
     * Only behind a mission. Without one the slider is the way out and the
     * timeout is the whole design: an alarm that returned after every
     * unanswered ring would be a change nobody asked for.
     */
    @Test
    fun `no mission, no coming back`() {
        assertFalse(Nag.wantsAnother(guarded = false, roundsSoFar = 0))
        assertTrue(Nag.wantsAnother(guarded = true, roundsSoFar = 0))
    }

    /**
     * And it stops eventually. Not a get-out — an hour of an alarm every
     * five minutes is not something anybody sleeps through — but a stop on
     * a runaway, so a bug in the other two ends can never mean a phone
     * that rings for ever.
     */
    @Test
    fun `it gives up in the end`() {
        assertTrue(Nag.wantsAnother(true, Nag.GIVES_UP_AFTER - 1))
        assertFalse(Nag.wantsAnother(true, Nag.GIVES_UP_AFTER))
        assertFalse(Nag.wantsAnother(true, 500))
        assertTrue(
            "and not before it has been a real nuisance",
            Nag.GIVES_UP_AFTER * Nag.MINUTES >= 30
        )
    }

    // ---------------------------------------------------- and how it ends

    @Test
    fun `booking one leaves something to cancel and something to say`() {
        Nag.arm(context, ringing(label = "Work"), roundsSoFar = 0)

        assertTrue(Nag.pending(prefs))
        assertEquals(1, Nag.rounds(prefs))
        assertTrue(
            "booked ahead, not behind",
            Nag.bookedAt(prefs) > System.currentTimeMillis()
        )
    }

    /** Each round counts, or the limit above would never be reached. */
    @Test
    fun `the rounds add up`() {
        Nag.arm(context, ringing(), Nag.rounds(prefs))
        Nag.arm(context, ringing(), Nag.rounds(prefs))
        Nag.arm(context, ringing(), Nag.rounds(prefs))
        assertEquals(3, Nag.rounds(prefs))
    }

    /**
     * How many alarms this app has sitting in the system's queue.
     *
     * The preference is only the app's own note of what it did. What
     * actually rings the phone is the alarm booked with the system, and
     * the two are not the same thing: clearing the note and leaving the
     * booking is a phone that rings after you have told it not to, and the
     * app saying it will not.
     */
    private fun booked(): Int = org.robolectric.Shadows
        .shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        .scheduledAlarms.size

    /** Calling it off clears the booking and the tally with it. */
    @Test
    fun `calling it off really calls it off`() {
        Nag.arm(context, ringing(), 0)
        assertEquals("nothing was booked to start with", 1, booked())

        Nag.callOff(context)

        assertFalse(Nag.pending(prefs))
        assertEquals("and the count starts again next time", 0, Nag.rounds(prefs))
        assertEquals("and the phone is not still going to ring", 0, booked())
    }

    /**
     * A booking in the past is over. Otherwise the app would go on
     * offering to call off something that has already happened — or,
     * worse, never happened because the phone was off.
     */
    @Test
    fun `a booking that has already come round is not pending`() {
        Nag.arm(context, ringing(), 0)
        val after = Nag.bookedAt(prefs) + 1
        assertFalse(Nag.pending(prefs, now = after))
        assertTrue(Nag.pending(prefs, now = after - 2))
    }

    // ------------------------------------------------- the way out, wired

    private fun ring(mission: String = Mission.NONE, body: (AlarmRingActivity) -> Unit) {
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_MISSION, mission)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            body(c.get())
        }
    }

    /**
     * Passing the mission ends it. This is the way out the whole feature
     * exists for, and a nag surviving it would mean an alarm that could
     * not be dealt with at all.
     */
    @Test
    fun `passing the mission ends the nagging`() {
        Nag.arm(context, ringing(), 3)
        assertTrue("set up wrong", Nag.pending(prefs))

        ring(mission = Mission.MATHS) { app ->
            val answer = app.findViewById<android.widget.EditText>(R.id.mission_answer)
            val button = app.findViewById<android.widget.Button>(R.id.mission_button)
            // Answer it correctly, the way a person would — the point is
            // that the real path clears the booking.
            answer.setText(app.wantedAnswer.toString())
            button.performClick()
            assertTrue("the right answer must close the screen", app.isFinishing)
        }

        assertFalse("nothing may be left booked", Nag.pending(prefs))
    }

    /** And so does an ordinary stop, from a morning with no mission on it. */
    @Test
    fun `the slider ends it too`() {
        Nag.arm(context, ringing(), 1)
        ring { app ->
            app.findViewById<SlideToStopView>(R.id.stop_slider).onSlid?.invoke()
        }
        assertFalse(Nag.pending(prefs))
    }

    /** Snoozing takes over, so anything booked would land in the middle. */
    @Test
    fun `snoozing calls it off as well`() {
        Nag.arm(context, ringing(), 1)
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE, 5)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            c.get().findViewById<android.widget.Button>(R.id.snooze_button).performClick()
        }
        assertFalse(Nag.pending(prefs))
    }

    /**
     * There are two Snooze buttons, and only one of them called it off.
     *
     * The ring screen's did. The notification's — the one you can reach on
     * the lock screen, without opening anything, which is the one a hand at
     * six in the morning actually finds — booked the snooze and left the
     * nagging armed behind it, so the next round went off in the middle of
     * the ten minutes that had just been asked for.
     */
    @Test
    fun `the notification's snooze calls the nag off too`() {
        Nag.arm(context, ringing(), 1)
        assertTrue("set up wrong", Nag.pending(prefs))

        val controller = Robolectric.buildService(
            AlarmService::class.java,
            android.content.Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SNOOZE)
        )
        try {
            controller.create().startCommand(0, 0)
        } finally {
            controller.destroy()
        }

        assertFalse("the next round would land inside the snooze", Nag.pending(prefs))
    }

    /**
     * And the app says so and offers the way out, every time it comes
     * back. The one thing worse than an alarm that keeps returning is one
     * that keeps returning and never mentions it.
     */
    @Test
    fun `the app owns up to it and offers the way out`() {
        Nag.arm(context, ringing(), 2)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val dialog = c.get().nagDialog
            assertTrue("nothing was said about it", dialog != null && dialog.isShowing)
            dialog!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            // A dialog button hands its click to the controller through a
            // Handler message, so nothing has happened yet.
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertFalse("and the offer has to actually work", Nag.pending(prefs))
    }

    /** With nothing booked the app says nothing, which is most mornings. */
    @Test
    fun `and keeps quiet when there is nothing to say`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertTrue("nobody asked for a dialog", c.get().nagDialog == null)
        }
    }
}
