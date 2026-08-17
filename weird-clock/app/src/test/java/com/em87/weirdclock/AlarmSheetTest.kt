package com.em87.weirdclock

import android.view.View
import android.view.ViewGroup
import androidx.core.view.NestedScrollingChild3
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor sheet: whether you can get back to the top of it, and what
 * order the rows come in.
 *
 * Both of these are things a screenshot cannot answer. A picture of the
 * sheet shows the rows in order and says nothing about whether a finger
 * dragging downward scrolls them or closes the whole thing — and that was
 * the bug: with enough options on an alarm, scrolling down was a one-way
 * trip. The way back up dragged the sheet shut instead, so the time at the
 * top could not be reached at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmSheetTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun inflate(layout: Int): View {
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return android.view.LayoutInflater.from(themed).inflate(layout, null)
    }

    // ------------------------------------------------------- getting back up

    /**
     * A sheet you can scroll back up.
     *
     * A bottom sheet works out whether a downward drag belongs to the
     * content or to itself by *asking the content*, through the nested
     * scrolling protocol. A plain ScrollView does not speak it, so the
     * sheet never heard "I still have room to scroll up", took every
     * downward drag for itself, and closed. Nothing about that is visible
     * in a layout file unless you know to look for the class name — which
     * is exactly why it is checked here rather than trusted.
     */
    @Test
    fun `both sheets can hand a drag back to the content`() {
        for (layout in intArrayOf(R.layout.sheet_alarm_edit, R.layout.sheet_reminder_edit)) {
            val root = inflate(layout)
            assertTrue(
                "${root.javaClass.simpleName} cannot tell the sheet it has room " +
                    "to scroll, so scrolling down is a one-way trip",
                root is NestedScrollingChild3
            )
        }
    }

    // ------------------------------------------------------ the order of them

    /** The rows of the alarm sheet, top to bottom, by the id each carries. */
    private fun optionRows(): List<String> {
        val root = inflate(R.layout.sheet_alarm_edit)
        val content = (root as ViewGroup).getChildAt(0) as ViewGroup
        val wanted = listOf(
            R.id.sheet_row_name to "name",
            R.id.sheet_row_notes to "notes",
            R.id.sheet_row_sound to "sound",
            R.id.sheet_row_duration to "duration",
            R.id.sheet_vibrate to "vibrate",
            R.id.sheet_flash to "torch",
            R.id.sheet_row_snooze to "snooze",
            R.id.sheet_row_snooze_limit to "snooze limit",
            R.id.sheet_gentle_row to "gentle",
            R.id.sheet_mission_row to "mission"
        )
        // Where each one sits, judged by which direct child of the column
        // contains it — the switches live one level down inside their row.
        return wanted
            .map { (id, name) ->
                val view = root.findViewById<View>(id)
                assertTrue("the $name row is not in the sheet at all", view != null)
                var walk: View = view
                while (walk.parent !== content) walk = walk.parent as View
                content.indexOfChild(walk) to name
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    /**
     * The whole sheet, in the order it was asked for.
     *
     * Written out rather than checked pair by pair, because the order *is*
     * the design here: what wakes you first, then how hard it is to get
     * away from, with the mission last because it is the last thing the
     * alarm asks.
     */
    @Test
    fun `the sheet asks its questions in the order it was given`() {
        assertEquals(
            listOf(
                "name", "notes", "sound", "duration",
                "vibrate", "torch", "snooze", "snooze limit", "gentle", "mission"
            ),
            optionRows()
        )
    }

    // ------------------------------------------------------------- the torch

    /**
     * The sunrise's own torch has left the sheet; the plain one has come
     * back to it.
     *
     * They are two different things and they belong on different sides of
     * that line. Whether *this* morning should light the room is about the
     * morning. Whether a sleeper the light cannot reach wants the light
     * turned up is about the sleeper, and the answer never changes.
     */
    @Test
    fun `the sheet asks about the torch and not about the sunrise's own`() {
        val names = mutableListOf<String>()
        fun walk(view: View) {
            if (view.id != View.NO_ID) {
                runCatching { context.resources.getResourceEntryName(view.id) }
                    .onSuccess { names.add(it) }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(inflate(R.layout.sheet_alarm_edit))

        assertTrue("the plain torch is not on the alarm", "sheet_flash" in names)
        assertTrue(
            "the sunrise's own torch is still a row on every alarm",
            "sheet_gentle_flash" !in names
        )
    }

    /** The plain torch is this alarm's, and rides on this alarm's intent. */
    @Test
    fun `the torch follows the alarm`() {
        AlarmStore.forget()
        AlarmStore.all(context).add(
            Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS).apply { flash = true }
        )
        AlarmStore.save(context)
        AlarmScheduler.update(context)
        assertEquals(
            true, armed()!!.getBooleanExtra(AlarmScheduler.EXTRA_FLASH, false)
        )
        AlarmStore.forget()
    }

    /** And the sunrise's own follows the app. */
    @Test
    fun `the sunrise's torch follows the setting`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("off unless asked for", false, AlarmScheduler.wantsGentleFlash(context))
        prefs.edit().putBoolean(Prefs.GENTLE_FLASH, true).commit()

        AlarmStore.forget()
        AlarmStore.all(context).add(Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS))
        AlarmStore.save(context)
        AlarmScheduler.update(context)
        assertEquals(
            "an alarm that was never told to still has to, now that the app says so",
            true, armed()!!.getBooleanExtra(AlarmScheduler.EXTRA_GENTLE_FLASH, false)
        )
        AlarmStore.forget()
    }

    // ------------------------------------------------------- the snooze limit

    /**
     * How many times *this* alarm may be put off rides with it.
     *
     * It was one number for the whole app, which could only ever be right
     * for one of the alarms it applied to — the one you have to be up for
     * and the one about the bread deserve different answers, and they were
     * getting the same one.
     */
    @Test
    fun `the snooze limit belongs to the alarm and rides in the intent`() {
        AlarmStore.forget()
        AlarmStore.all(context).add(
            Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS).apply { snoozeLimit = 2 }
        )
        AlarmStore.save(context)
        AlarmScheduler.update(context)

        val intent = armed()
        assertEquals(2, intent!!.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_LIMIT, -1))
        assertEquals(2, AlarmScheduler.snoozeLimit(intent))
        AlarmStore.forget()
    }

    /**
     * And an alarm written before it moved keeps the app-wide number that
     * was in force. Losing a limit somebody had set is the one thing a move
     * like this must not do quietly.
     */
    @Test
    fun `an alarm from before the move keeps the limit it was under`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        AlarmStore.forget()
        prefs.edit()
            .putString(Prefs.SNOOZE_LIMIT, "3")
            .putString(
                "pref_alarms_json",
                """[{"id":1,"hour":7,"minute":0,"enabled":true,"sound":"bells"}]"""
            )
            .commit()
        assertEquals(3, AlarmStore.all(context).first().snoozeLimit)
        AlarmStore.forget()
    }

    /** The intent this app has just armed with the system, if any. */
    private fun armed(): android.content.Intent? = org.robolectric.Shadows
        .shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        .scheduledAlarms
        .mapNotNull { org.robolectric.Shadows.shadowOf(it.operation).savedIntent }
        .firstOrNull { it.component?.className?.endsWith("AlarmReceiver") == true }
}
