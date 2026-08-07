package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup, round-tripped.
 *
 * This exists because of a real loss: a phone refused to update the app, the
 * only way forward was to uninstall it, and uninstalling an Android app
 * destroys its preferences — which is where every alarm and every reminder
 * lives. A backup nobody has tested is a promise, and a promise is exactly
 * what is worth nothing on the day you need the file to open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
        ReminderStore.forget()
    }

    private fun populate() {
        val alarms = AlarmStore.all(context)
        alarms.clear()
        alarms.add(Alarm(id = 1, hour = 7, minute = 15, enabled = true,
            sound = Prefs.ALARM_SOUND_BELLS, label = "trabajo"))
        alarms.add(Alarm(id = 2, hour = 21, minute = 0, enabled = false,
            sound = Prefs.ALARM_SOUND_BELLS, label = "pastilla"))
        AlarmStore.save(context)

        val reminders = ReminderStore.all(context)
        reminders.clear()
        reminders.add(
            Reminder(
                id = 9, year = 2026, month = 3, day = 14, hour = 9, minute = 30,
                label = "dentista", repeat = Reminder.REPEAT_YEARLY
            )
        )
        ReminderStore.save(context)

        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.THEME, "ivory")
            .putBoolean(Prefs.MOON_PHASE, true)
            .putInt(Prefs.BIRTHDAY, 1207)
            .putFloat(Prefs.DIAL_SCALE, 1.25f)
            .putStringSet(Prefs.SELECTED_HOURS, setOf("3", "9"))
            .commit()
    }

    @Test
    fun `everything comes back`() {
        populate()
        val file = Backup.export(context)

        // The uninstall: preferences gone, caches gone, as after a fresh
        // install of the app.
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
        ReminderStore.forget()
        assertEquals(0, AlarmStore.all(context).size)

        val restored = Backup.import(context, file)
        assertEquals(2, restored?.alarms)
        assertEquals(1, restored?.reminders)

        val alarms = AlarmStore.all(context)
        assertEquals(7, alarms[0].hour)
        assertEquals("trabajo", alarms[0].label)
        assertTrue(alarms[0].enabled)
        assertEquals("pastilla", alarms[1].label)

        val reminder = ReminderStore.all(context)[0]
        assertEquals("dentista", reminder.label)
        assertEquals(Reminder.REPEAT_YEARLY, reminder.repeat)
        assertEquals(14, reminder.day)

        // Every preference type, because each is stored differently and a
        // boolean read back as a string throws where it is read.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("ivory", prefs.getString(Prefs.THEME, null))
        assertEquals(true, prefs.getBoolean(Prefs.MOON_PHASE, false))
        assertEquals(1207, prefs.getInt(Prefs.BIRTHDAY, 0))
        assertEquals(1.25f, prefs.getFloat(Prefs.DIAL_SCALE, 0f), 0.0001f)
        assertEquals(setOf("3", "9"), prefs.getStringSet(Prefs.SELECTED_HOURS, emptySet()))
    }

    /** A restore replaces; it does not leave the previous clock's leftovers. */
    @Test
    fun `a restore replaces rather than merges`() {
        populate()
        val file = Backup.export(context)

        val alarms = AlarmStore.all(context)
        alarms.clear()
        alarms.add(Alarm(id = 5, hour = 3, minute = 0, enabled = true,
            sound = Prefs.ALARM_SOUND_BELLS, label = "otra"))
        AlarmStore.save(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.THEME, "neon").commit()

        Backup.import(context, file)
        assertEquals(2, AlarmStore.all(context).size)
        assertEquals("ivory", PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Prefs.THEME, null))
    }

    /** Anything that is not ours is refused rather than half-applied. */
    @Test
    fun `a foreign file is refused`() {
        populate()
        assertNull(Backup.import(context, "{\"hello\":1}"))
        assertNull(Backup.import(context, "not json at all"))
        // And refusing must not have wiped what was already there.
        assertEquals(2, AlarmStore.all(context).size)
    }
}
