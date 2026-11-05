package com.em87.weirdclock

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The kind of alarm a widget books for its own repaint.
 *
 * The digital widget was reported stuck — half an hour behind, on a home
 * screen somebody was looking at. It was. The platform rations ordinary
 * alarms by how recently the app was opened, ten an hour once it has not
 * been for a day, and a clock repainting once a minute spent its ten in
 * ten minutes. The only ordinary alarm exempt from the ration is exact
 * and allowed while idle, so that is what every clock widget books — on
 * the clock that does not wake the phone, because a widget nobody can see
 * has nothing to be on time for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetTickTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private fun shadow() = Shadows.shadowOf(alarmManager)

    @Before
    fun nothingArmed() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().commit()
        while (shadow().nextScheduledAlarm != null) {
            // getNextScheduledAlarm removes as it reads.
        }
    }

    private fun armed() = shadow().peekNextScheduledAlarm()
        ?: throw AssertionError("nothing was armed")

    /** Exact, to the minute, and of the kind the ration does not touch. */
    @Test
    fun `the digital widget's minute may not be rationed`() {
        ClockWidgetProvider.scheduleSkyTick(
            context, DigitalWidgetProvider::class.java, Face.DIGITAL
        )
        val alarm = armed()
        assertEquals("inexact: window ${alarm.windowLengthMs}", 0L, alarm.windowLengthMs)
        assertTrue("not allowed while idle", alarm.isAllowWhileIdle)
        assertEquals(
            "not on the minute: ${alarm.triggerAtTime}", 0L, alarm.triggerAtTime % 60_000L
        )
        assertTrue(alarm.triggerAtTime > System.currentTimeMillis())
    }

    /**
     * And none of them wakes the phone to do it. An exact wake-up every
     * minute all night would be the battery cost everybody fears from a
     * widget, for a picture in a pocket.
     */
    @Test
    fun `no clock widget wakes the phone for a repaint`() {
        for ((cls, pinned) in ClockWidgetProvider.KINDS) {
            while (shadow().nextScheduledAlarm != null) {
                // Start each kind from nothing.
            }
            ClockWidgetProvider.scheduleSkyTick(context, cls, pinned)
            val alarm = armed()
            assertEquals("${cls.simpleName} wakes the phone", AlarmManager.RTC, alarm.type)
            assertTrue("${cls.simpleName} is rationed", alarm.isAllowWhileIdle)
            assertEquals("${cls.simpleName} is inexact", 0L, alarm.windowLengthMs)
        }
    }
}
