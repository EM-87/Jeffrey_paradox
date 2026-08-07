package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Does the app open at all.
 *
 * Written the day it did not. A store refactor left MainActivity reading a
 * lateinit list a hundred lines before the line that filled it, and the app
 * crashed on every launch — past a clean compile, 52 green unit tests and a
 * clean lint, because not one of them looks at *when* a field is written.
 *
 * The other tests here check arithmetic, which never needed a device. This
 * one needs an Android, so it borrows Robolectric's. It is deliberately
 * shallow: build the activity, walk it through its lifecycle, and let
 * anything that throws on the way be the failure. That is the whole class of
 * bug it exists to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LaunchTest {

    @Test
    fun `the clock opens, resumes, pauses and closes without throwing`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            assertNotNull(controller.get())
            controller.pause().resume()
        }
    }

    @Test
    fun `it opens again with alarms and reminders already stored`() {
        // An empty store exercises none of the card building. A stocked one
        // walks the adapter, the little faces and the calendar marks.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AlarmStore.all(context).apply {
            clear()
            add(Alarm(1, 7, 30, true, Prefs.ALARM_SOUND_BELLS))
            add(
                Alarm(2, 22, 0, false, Prefs.ALARM_SOUND_DIGITAL, daysMask = Alarm.WEEKDAYS)
                    .apply { extraTimes = mutableListOf(8 * 60, 14 * 60) }
            )
            // No days set: the one-shot, which the cards label rather than
            // draw a weekday strip for.
            add(Alarm(3, 6, 15, true, Prefs.ALARM_SOUND_BELLS, daysMask = 0))
        }
        AlarmStore.save(context)
        ReminderStore.all(context).apply {
            clear()
            add(Reminder(1, 2026, 8, 15, 9, 0, "Dentist"))
            add(Reminder(2, 2026, 8, 20, 18, 30, "Weekly", repeat = Reminder.REPEAT_WEEKLY))
        }
        ReminderStore.save(context)

        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            assertNotNull(controller.get())
        }
    }

    @Test
    fun `the settings screen opens`() {
        Robolectric.buildActivity(SettingsActivity::class.java).use { controller ->
            controller.setup()
            assertNotNull(controller.get())
        }
    }

    @Test
    fun `the ring screen opens for an alarm and for a finished timer`() {
        Robolectric.buildActivity(AlarmRingActivity::class.java).use { controller ->
            controller.setup()
            assertNotNull(controller.get())
        }
    }
}
