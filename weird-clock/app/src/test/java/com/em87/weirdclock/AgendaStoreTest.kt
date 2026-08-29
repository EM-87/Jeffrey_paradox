package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * The gate in front of somebody's diary.
 *
 * Two locks and both have to be open: the switch, and the one permission
 * this app has ever asked for that is about a person rather than about a
 * clock. What is behind it is a list of where somebody will be and who
 * with, so the tests here are almost all about the provider *not* being
 * read — and about the two ways of refusing looking identical from the
 * outside, which is the point: a clock that drew "no permission"
 * differently from "nothing on today" would be announcing which.
 *
 * The rules about what an event is are [AgendaTest]. This is the door.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgendaStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val asked = ArrayList<Pair<Long, Long>>()
    private val real = AgendaStore.reader

    private fun at(hour: Int) = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.JUNE, 21, hour, 0, 0)
    }.timeInMillis

    private val diary = listOf(
        Agenda.Event(1L, "the dentist", at(9), at(10), false),
        Agenda.Event(2L, "a birthday", at(0), at(0) + Agenda.DAY_MS, true),
        // The kind of row a half-finished sync leaves behind.
        Agenda.Event(3L, "", at(14), at(14), false)
    )

    @Before
    fun handOverADiary() {
        asked.clear()
        AgendaStore.reader = AgendaStore.Reader { _, from, to ->
            asked += from to to
            diary
        }
        switchedOn(true)
        allow(true)
    }

    @After
    fun takeItBack() {
        AgendaStore.reader = real
    }

    private fun switchedOn(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(Prefs.AGENDA, on).commit()
    }

    private fun allow(granted: Boolean) {
        val app = Shadows.shadowOf(
            context.packageManager.let { context.applicationContext as android.app.Application }
        )
        if (granted) {
            app.grantPermissions(android.Manifest.permission.READ_CALENDAR)
        } else {
            app.denyPermissions(android.Manifest.permission.READ_CALENDAR)
        }
    }

    /** Off means the provider is never asked. Not asked and discarded — not asked. */
    @Test
    fun `switched off, the diary is never read`() {
        switchedOn(false)
        assertFalse(AgendaStore.wanted(context))
        assertTrue(AgendaStore.between(context, at(0), at(23)).isEmpty())
        assertTrue("it read the calendar with the switch off", asked.isEmpty())
    }

    /**
     * And on without the permission is also off, in exactly the same way.
     *
     * The same empty list and no query, so nothing anywhere can tell the
     * two apart — which is deliberate. Somebody's phone should not show a
     * different clock face depending on whether they refused a permission.
     */
    @Test
    fun `switched on and refused, the diary is never read either`() {
        allow(false)
        assertFalse(AgendaStore.allowed(context))
        assertFalse(AgendaStore.wanted(context))
        assertTrue(AgendaStore.between(context, at(0), at(23)).isEmpty())
        assertTrue("it read the calendar without permission", asked.isEmpty())
    }

    /** On and allowed, the window asked for is the window that was wanted. */
    @Test
    fun `on, it reads exactly the window it was asked for`() {
        val events = AgendaStore.between(context, at(0), at(0) + Agenda.DAY_MS)
        assertEquals(1, asked.size)
        assertEquals(at(0) to at(0) + Agenda.DAY_MS, asked.single())
        // The stray zero-length row is dropped and the other two survive.
        assertEquals(2, events.size)
        assertTrue(events.any { it.title == "the dentist" })
        assertTrue(events.any { it.allDay })
    }

    /**
     * A provider that throws is an empty diary, not a clock that will not
     * open.
     *
     * All the ordinary ways: no calendar app at all, an account removed
     * while the cursor is open, a permission revoked between the check and
     * the query. Every one of them is one blank day.
     */
    @Test
    fun `a provider that falls over is one empty day`() {
        AgendaStore.reader = AgendaStore.Reader { _, _, _ ->
            throw SecurityException("permission revoked mid-query")
        }
        assertTrue(AgendaStore.between(context, at(0), at(23)).isEmpty())
    }

    /**
     * Nothing about the diary is written into a backup.
     *
     * The switch is a preference and belongs there. The events are not
     * preferences at all — they are read, drawn and forgotten — and there
     * is nothing anywhere in this app that could put one in a file.
     */
    @Test
    fun `the diary is not in the backup`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.AGENDA, true).commit()
        val file = Backup.export(context)
        for (event in diary) {
            if (event.title.isBlank()) continue
            assertFalse(
                "an appointment was written into the backup file",
                file.contains(event.title)
            )
        }
        // The switch itself does survive, so a restored phone is in the
        // same state as the one it came from.
        assertTrue(
            org.json.JSONObject(file).getJSONObject("entries").has(Prefs.AGENDA)
        )
    }
}
