package com.em87.weirdclock

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * That the alarms are still armed after the ordinary things that disarm
 * them.
 *
 * The whole of an armed alarm is one AlarmManager entry holding the next
 * ring. Four everyday events either delete that entry or make it point at
 * the wrong instant, and until this test existed exactly one of them was
 * handled. The worst was the one nobody would ever see: **installing a new
 * build cancels every alarm the old one had set**, and this app is handed a
 * new build every few days by somebody who then opens it — which re-armed
 * everything and hid the fault completely.
 *
 * So the check is not "does the receiver call the scheduler". It is: send
 * the broadcast the *system* sends, through the manifest, and see whether
 * anything is armed afterwards. A receiver that is correct and not
 * registered for the action is the actual bug, and only going through the
 * manifest catches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RearmTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private fun shadow() = Shadows.shadowOf(alarmManager)

    @Before
    fun anAlarmIsSet() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().commit()
        AlarmStore.forget()
        AlarmStore.all(context).apply {
            clear()
            add(Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS))
        }
        AlarmStore.save(context)
    }

    /** Pops every entry, the way the four events below really do. */
    private fun disarm() {
        while (shadow().nextScheduledAlarm != null) {
            // getNextScheduledAlarm removes as it reads.
        }
        assertNull("the wipe did not wipe", shadow().peekNextScheduledAlarm())
    }

    private fun afterBroadcast(action: String): Boolean {
        AlarmScheduler.update(context)
        assertNotNull("nothing was armed to begin with", shadow().peekNextScheduledAlarm())
        disarm()
        context.sendBroadcast(Intent(action).setPackage(context.packageName))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return shadow().peekNextScheduledAlarm() != null
    }

    /**
     * A new build of this app does not leave its owner unarmed.
     *
     * The system cancels every alarm belonging to a package it replaces.
     * This is the one that was actually happening, every few days, to the
     * phone this clock is the alarm on.
     */
    @Test
    fun `an update re-arms the alarms`() {
        assertTrue(afterBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    /** A reboot, which is the one that was always handled. */
    @Test
    fun `a reboot re-arms the alarms`() {
        assertTrue(afterBroadcast(Intent.ACTION_BOOT_COMPLETED))
    }

    /**
     * And the two that make the entry wrong rather than absent.
     *
     * An armed alarm is an absolute instant worked out from what the time
     * was when it was written. Move the clock or the zone and it is still
     * that instant, which is no longer seven in the morning — three hours
     * out after a flight, in the direction that makes you miss things.
     */
    @Test
    fun `setting the clock or changing zone re-arms the alarms`() {
        assertTrue(afterBroadcast(Intent.ACTION_TIME_CHANGED))
        assertTrue(afterBroadcast(Intent.ACTION_TIMEZONE_CHANGED))
    }

    /**
     * The fifth way, which no broadcast can cover: a force-stop takes the
     * alarms with it and tells the app nothing. Opening it is the only
     * moment left to notice, so opening it arms them.
     */
    @Test
    fun `opening the app arms whatever the list says`() {
        disarm()
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .commit()
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNotNull(
                "the app opened with nothing armed", shadow().peekNextScheduledAlarm()
            )
        }
    }

    /**
     * And the bells, which are their own chain of alarms with the same four
     * problems.
     *
     * They had exactly one of the four too, and for the same reason: the
     * chain is re-armed by each ring, so anything that breaks the chain
     * stops it for good.
     */
    @Test
    fun `the bells come back too`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            // The chain only exists when the bells are allowed to ring with
            // the app shut, which is the only case that needs re-arming.
            .putBoolean(Prefs.BELLS, true)
            .putBoolean(Prefs.BELLS_BACKGROUND, true)
            .commit()
        AlarmStore.all(context).clear()
        AlarmStore.save(context)
        disarm()
        context.sendBroadcast(
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName)
        )
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull("the bells never started again", shadow().peekNextScheduledAlarm())
    }
}
