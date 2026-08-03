package com.em87.weirdclock

import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The note a reminder carries, and the two things the widget's dial was
 * leaving out.
 *
 * The widget renders from its own entry point, which is exactly why it drifts
 * from the app's dial: nothing forces the two to agree, and twice now they
 * have not. Rendering it in a test is the only way to say anything true about
 * what it draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotesAndWidgetTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
        ReminderStore.forget()
        DayNight.configure(context)
    }

    // ------------------------------------------------------------- notes

    /** A note survives being written down and read back. */
    @Test
    fun `a note is stored with its reminder`() {
        val reminders = ReminderStore.all(context)
        reminders.clear()
        reminders.add(
            Reminder(
                id = 1, year = 2026, month = 8, day = 3, hour = 10, minute = 0,
                label = "dentista", notes = "Calle Mayor 4, 2º izq. Llevar la radiografía."
            )
        )
        ReminderStore.save(context)
        ReminderStore.forget()

        val back = ReminderStore.all(context).single()
        assertEquals("Calle Mayor 4, 2º izq. Llevar la radiografía.", back.notes)
        assertEquals("dentista", back.label)
    }

    /** An old store with no note in it still reads, with an empty one. */
    @Test
    fun `a reminder saved before notes existed still loads`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(
                "pref_reminders_json",
                """[{"id":3,"year":2026,"month":8,"day":3,"hour":9,"minute":0,"label":"old"}]"""
            ).commit()
        ReminderStore.forget()
        val back = ReminderStore.all(context).single()
        assertEquals("old", back.label)
        assertEquals("", back.notes)
    }

    /**
     * The bubble reads the note under the name. Without one it is the name
     * alone — no stray blank line, no dangling separator.
     */
    @Test
    fun `a mark reads its name, and its note under it`() {
        assertEquals("gym", DialMark(0f, false, false, "gym").reading())
        assertEquals("gym\nbring shoes", DialMark(0f, false, false, "gym", "bring shoes").reading())
        assertEquals("lunch", DialArc(0f, 30f, false, true, "lunch").reading())
        assertEquals(
            "lunch\nwith Ana",
            DialArc(0f, 30f, false, true, "lunch", "with Ana").reading()
        )
    }

    // ------------------------------------------------------------ widget

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (x in 0 until a.width step 2) {
            for (y in 0 until a.height step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return true
            }
        }
        return false
    }

    private fun countOf(bitmap: Bitmap, color: Int): Int {
        var n = 0
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) if (bitmap.getPixel(x, y) == color) n++
        }
        return n
    }

    /**
     * The widget drew the calendar's wedges and silently dropped the
     * alarms', so a three-hour block set as an alarm was on the app's face
     * and not on the one people actually look at.
     */
    @Test
    fun `an alarm with a duration reaches the widget as a wedge`() {
        val bare = WidgetRenderer.dialBitmap(context, 400)

        val alarms = AlarmStore.all(context)
        alarms.clear()
        alarms.add(
            Alarm(
                id = 1, hour = 3, minute = 0, enabled = true,
                sound = Prefs.ALARM_SOUND_BELLS, durationMinutes = 120, label = "taller"
            )
        )
        AlarmStore.save(context)

        assertTrue(
            "an alarm block must show on the widget's dial",
            differs(bare, WidgetRenderer.dialBitmap(context, 400))
        )
    }

    /** And the ring stays the calendar's own mark, not every wedge's. */
    @Test
    fun `only the calendar's wedges are ringed on the widget`() {
        val theme = ClockThemes.resolve(context, null)
        val ink = ClockThemes.contrastInk(theme)

        val alarms = AlarmStore.all(context)
        alarms.clear()
        alarms.add(
            Alarm(
                id = 1, hour = 3, minute = 0, enabled = true,
                sound = Prefs.ALARM_SOUND_BELLS, durationMinutes = 120, label = "taller"
            )
        )
        AlarmStore.save(context)
        val alarmOnly = countOf(WidgetRenderer.dialBitmap(context, 400), ink)

        val today = java.util.Calendar.getInstance()
        val reminders = ReminderStore.all(context)
        reminders.clear()
        reminders.add(
            Reminder(
                id = 2, year = today.get(java.util.Calendar.YEAR),
                month = today.get(java.util.Calendar.MONTH) + 1,
                day = today.get(java.util.Calendar.DAY_OF_MONTH),
                hour = 9, minute = 0, label = "cita", durationMinutes = 120
            )
        )
        ReminderStore.save(context)
        val withReminder = countOf(WidgetRenderer.dialBitmap(context, 400), ink)

        assertTrue("the calendar's wedge is ringed: $alarmOnly then $withReminder",
            withReminder > alarmOnly)
    }

    /**
     * The sky belongs on the widget too — it is the face most people look
     * at, and knowing whether it is light out is the whole point of it.
     */
    @Test
    fun `the sky complication reaches the widget`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(Prefs.MOON_PHASE, false).commit()
        val without = WidgetRenderer.dialBitmap(context, 400)
        prefs.edit().putBoolean(Prefs.MOON_PHASE, true).commit()
        val with = WidgetRenderer.dialBitmap(context, 400)
        assertTrue("switching the sky on must change the widget", differs(without, with))
    }

    /** And it obeys the same rule everywhere: no fix, no sun. */
    @Test
    fun `the widget's sky follows the same sun the app's does`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(Prefs.MOON_PHASE, true).commit()
        DayNight.configure(context)
        val unlocated = WidgetRenderer.dialBitmap(context, 400)

        // Somewhere the sun is definitely up or definitely down right now,
        // whichever it is — the point is only that knowing where changes
        // what is drawn at some hour of the day.
        prefs.edit()
            .putFloat(Prefs.LAST_LATITUDE, 40.4f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.7f)
            .commit()
        DayNight.configure(context)
        val located = WidgetRenderer.dialBitmap(context, 400)

        val minuteNow = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        if (DayNight.sky(minuteNow) == DayNight.Sky.Night) {
            // Night is what the unlocated dial draws anyway, so the two
            // agree — which is itself the rule, and worth saying.
            assertEquals(false, differs(unlocated, located))
        } else {
            assertTrue("a located daytime dial must not draw the moon",
                differs(unlocated, located))
        }
        assertNotEquals(null, DayNight.sky(minuteNow))
    }

    /**
     * Alarms carry notes too. They arrived on the calendar side first, which
     * left the dial able to read out an appointment's details and not an
     * alarm's — the same dot, on the same face, answering to a different
     * depth depending on which sheet had made it.
     */
    @Test
    fun `a note is stored with its alarm`() {
        val alarms = AlarmStore.all(context)
        alarms.clear()
        alarms.add(
            Alarm(
                id = 1, hour = 7, minute = 0, enabled = true,
                sound = Prefs.ALARM_SOUND_BELLS, label = "gimnasio",
                notes = "Llevar toalla y candado"
            )
        )
        AlarmStore.save(context)
        AlarmStore.forget()

        val back = AlarmStore.all(context).single()
        assertEquals("Llevar toalla y candado", back.notes)
        assertEquals("gimnasio", back.label)
    }

    /** An alarm saved before notes existed still loads. */
    @Test
    fun `an alarm saved before notes existed still loads`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(
                "pref_alarms_json",
                """[{"id":4,"hour":6,"minute":30,"enabled":true,"sound":"bells","label":"old"}]"""
            ).commit()
        AlarmStore.forget()
        val back = AlarmStore.all(context).single()
        assertEquals("old", back.label)
        assertEquals("", back.notes)
    }
}
