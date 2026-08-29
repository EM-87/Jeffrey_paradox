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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * The diary actually arriving on the clock.
 *
 * [AgendaTest] holds the arithmetic and [AgendaStoreTest] holds the door.
 * Neither of them says the events reach the face — and a feature that
 * reads a calendar perfectly and draws nothing is the shape a whole
 * version of this app once shipped in, when a rule was registered on the
 * wrong fragment and did nothing at all, silently.
 *
 * So this goes through the activity: an appointment in the provider, a
 * wedge on the dial, a mark on the month page, and all of it gone when
 * the switch is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class AgendaOnTheDialTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val real = AgendaStore.reader

    /** Nine to ten this morning, whenever this test is run. */
    private fun todayAt(hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = TimeKeeper.nowMs()
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun handOverADiary() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putBoolean(Prefs.ALARM_MARKERS, true)
            .putBoolean(Prefs.AGENDA, true)
            .commit()
        (context.applicationContext as android.app.Application).let {
            Shadows.shadowOf(it).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        }
        AgendaStore.reader = AgendaStore.Reader { _, from, to ->
            Agenda.between(
                listOf(
                    Agenda.Event(1L, "the dentist", todayAt(9), todayAt(10), false),
                    // Five minutes, which on a twelve-hour dial is a
                    // quarter of a degree and has to be widened to be
                    // pressable. Five and not fifteen: fifteen is the
                    // width it is widened *to*, so a test using it passed
                    // whether or not any widening happened.
                    Agenda.Event(2L, "stand-up", todayAt(11), todayAt(11) + 300_000L, false)
                ),
                from, to
            )
        }
    }

    @After
    fun takeItBack() {
        AgendaStore.reader = real
    }

    private fun arcs(): List<DialArc> =
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            c.get().clockForTest().eventArcs
        }

    /**
     * An appointment in the provider is a wedge on the dial, named.
     *
     * A wedge and not a dot: an appointment has a length, which is the
     * whole difference between it and the reminders this clock has always
     * had.
     */
    @Test
    fun `an appointment turns up on the dial as a wedge with its name on it`() {
        val drawn = arcs()
        val dentist = drawn.firstOrNull { it.label == "the dentist" }
        assertTrue("the diary never reached the dial: ${drawn.map { it.label }}", dentist != null)
        assertTrue("it was not drawn as a calendar thing", dentist!!.fromCalendar)
        assertEquals("it is not an hour long", 60, dentist.endMinute - dentist.startMinute)
        assertEquals("it did not start at nine", 9 * 60, dentist.startMinute)
    }

    /** And five minutes is widened to something a thumb can find. */
    @Test
    fun `a five-minute appointment is drawn wide enough to press`() {
        val standUp = arcs().firstOrNull { it.label == "stand-up" }
        assertTrue("the short one is missing", standUp != null)
        assertEquals(
            "a five-minute wedge was drawn at five minutes",
            Agenda.LEAST_MINUTES, standUp!!.endMinute - standUp.startMinute
        )
    }

    /**
     * Switched off, the dial is the dial it was.
     *
     * The switch is the promise, and a diary that goes on being drawn
     * after it is turned off is worse than one that was never drawn.
     */
    @Test
    fun `switched off, nothing from the diary is on the dial`() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(Prefs.AGENDA, false).commit()
        val drawn = arcs()
        assertFalse(
            "the diary outlived its switch: ${drawn.map { it.label }}",
            drawn.any { it.label == "the dentist" }
        )
    }
}
