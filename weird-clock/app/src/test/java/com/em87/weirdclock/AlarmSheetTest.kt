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
            R.id.sheet_mission_row to "mission",
            R.id.sheet_row_snooze to "snooze",
            R.id.sheet_gentle_row to "gentle",
            R.id.sheet_gentle_flash to "gentle flash"
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
     * Snooze sits third from the bottom, immediately above the sunrise.
     *
     * Asked for, and it reads properly too: mission and snooze are both
     * "how hard is this one to get away from", so they belong together, and
     * the two sunrise rows are one setting and its follow-on so they belong
     * at the end.
     */
    @Test
    fun `snooze is third from last and directly above the gentle wake`() {
        val rows = optionRows()
        assertEquals(
            "the tail of the sheet is in the wrong order: $rows",
            listOf("mission", "snooze", "gentle", "gentle flash"),
            rows.takeLast(4)
        )
        assertEquals("snooze is not third from last: $rows", "snooze", rows[rows.size - 3])
    }

    // ------------------------------------------------------------- the torch

    /**
     * The torch has left the sheet. It is the app's answer now, so there is
     * no row for it here and nothing per-alarm left to disagree with.
     */
    @Test
    fun `the sheet no longer asks about the torch`() {
        val names = mutableListOf<String>()
        fun walk(view: View) {
            if (view.id != View.NO_ID) {
                runCatching { context.resources.getResourceEntryName(view.id) }
                    .onSuccess { names.add(it) }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(inflate(R.layout.sheet_alarm_edit))

        assertTrue(
            "the torch is still a row on every alarm",
            "sheet_flash" !in names
        )
        assertTrue(
            "and the sunrise's own torch, which is a different thing, has gone with it",
            "sheet_gentle_flash" in names
        )
    }

    /**
     * And an alarm strobes because the app says so, not because that alarm
     * was ever told to.
     */
    @Test
    fun `the torch follows the setting and not the alarm`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("off unless asked for", false, AlarmScheduler.wantsFlash(context))

        prefs.edit().putBoolean(Prefs.ALARM_FLASH, true).commit()
        assertEquals(true, AlarmScheduler.wantsFlash(context))

        AlarmStore.forget()
        AlarmStore.all(context).add(Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS))
        AlarmStore.save(context)
        AlarmScheduler.update(context)

        val armed = org.robolectric.Shadows
            .shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
            .scheduledAlarms
            .mapNotNull { org.robolectric.Shadows.shadowOf(it.operation).savedIntent }
            .firstOrNull { it.component?.className?.endsWith("AlarmReceiver") == true }
        assertTrue("nothing was armed", armed != null)
        assertEquals(
            "an alarm that was never told to flash still has to, now that the app says so",
            true, armed!!.getBooleanExtra(AlarmScheduler.EXTRA_FLASH, false)
        )
        AlarmStore.forget()
    }
}
