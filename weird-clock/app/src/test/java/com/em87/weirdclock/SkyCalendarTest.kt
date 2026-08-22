package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sky, put where somebody would look for it.
 *
 * Two halves of one idea. The calendar knows which nights are worth going
 * outside for and never said so, and the wound sky can be carried to any
 * date at all, which is a great deal of dial for a thing whose interesting
 * days are about twenty a year. So the calendar marks them, and pressing
 * the date on the sky goes to the next one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SkyCalendarTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    // ------------------------------------------------------- finding the next

    /**
     * There is always something coming, and it is never far.
     *
     * The Moon sees to it: new or full every fortnight, so no date in the
     * calendar is more than about a fortnight from something. A search that
     * came back empty would mean the walk was looking at the wrong thing
     * rather than that the sky had gone quiet.
     */
    @Test
    fun `something is always coming and never far`() {
        var worst = 0
        for (start in 0 until 365) {
            val day = CivilDays.epochDay(2026, 1, 1) + start
            val next = SkyEvents.nextDay(day)
            assertNotNull("nothing at all happens after day $day", next)
            worst = maxOf(worst, next!! - day)
        }
        assertTrue("the sky went quiet for $worst days", worst <= 20)
    }

    /** And "next" means after, not on. */
    @Test
    fun `the next day is a later day`() {
        val busy = SkyEvents.eclipseDays().first().first
        assertTrue("a day full of eclipse is not itself busy", SkyEvents.anythingOn(busy))
        val next = SkyEvents.nextDay(busy)
        assertNotNull(next)
        assertTrue("the search handed back the day it started on", next!! > busy)
    }

    /** Everything it finds is a day with something on it. */
    @Test
    fun `what it finds is a day with something on it`() {
        var day = CivilDays.epochDay(2026, 1, 1)
        repeat(30) {
            val next = SkyEvents.nextDay(day) ?: return@repeat
            assertTrue(
                "the search stopped on a day with nothing on it",
                SkyEvents.anythingOn(next)
            )
            // And nothing was skipped: every day in between is empty.
            for (between in (day + 1) until next) {
                assertTrue(
                    "day $between was stepped over and it had something on it",
                    !SkyEvents.anythingOn(between)
                )
            }
            day = next
        }
    }

    /**
     * The rarest thing on a day is the one the calendar names.
     *
     * The twelfth of August 2026 is a total solar eclipse and the peak of
     * the Perseids, and there is room in the corner of a date cell for one
     * mark. A day is not going to be remembered for the shower.
     */
    @Test
    fun `the rarest thing on a day wins the corner`() {
        val both = CivilDays.epochDay(2026, 8, 12)
        val all = SkyEvents.on(both).map { it.kind }
        assertTrue("the test's own day is not doubly busy: $all", all.size >= 2)
        assertTrue("the shower is not on it", SkyEvents.Kind.METEORS in all)
        assertEquals(
            "a total eclipse lost the corner to a meteor shower",
            SkyEvents.Kind.SOLAR_ECLIPSE, SkyEvents.headline(both)?.kind
        )
    }

    /** And a quiet day names nothing rather than naming something dull. */
    @Test
    fun `a quiet day has no headline`() {
        var quiet = CivilDays.epochDay(2026, 1, 1)
        while (SkyEvents.anythingOn(quiet)) quiet++
        assertNull(SkyEvents.headline(quiet))
    }

    // --------------------------------------------------------- on the calendar

    /**
     * The marks reach the calendar, for the month the calendar is showing.
     *
     * The wiring is the part that rots: [SkyEvents] answers in days counted
     * from 1970 and the view thinks in days of a month, and a translation
     * that is a month or a day out puts the eclipse on the wrong square
     * without anything looking broken.
     */
    @Test
    fun `the calendar is told what the sky is doing`() {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.showCardForTest(Card.CALENDAR)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val marks = activity.calendarSkyForTest()
        assertTrue("the calendar knows of no sky at all", marks.isNotEmpty())
        val year = activity.calendarYearForTest()
        val month = activity.calendarMonthForTest()
        for ((day, kind) in marks) {
            assertEquals(
                "the calendar has $kind on $day, and the sky does not",
                kind, SkyEvents.headline(CivilDays.epochDay(year, month, day))?.kind
            )
        }
        // And nothing was missed: every busy day of the month is marked.
        val probe = java.util.Calendar.getInstance()
        probe.set(year, month - 1, 1)
        for (day in 1..probe.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)) {
            val real = SkyEvents.headline(CivilDays.epochDay(year, month, day))?.kind
            assertEquals("day $day of the month disagrees", real, marks[day])
        }
    }

    // ------------------------------------------------------------- on the sky

    private fun sky(): ClockView {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1080, 2000)
        return clock
    }

    private fun dayOf(clock: ClockView): Int = CivilDays.dayOf(
        clock.orreryMsForTest(),
        java.util.TimeZone.getDefault().getOffset(clock.orreryMsForTest())
    )

    /**
     * Pressing the date carries the sky to the next thing worth looking at.
     *
     * Forward, and onto a day that actually has something on it. A leap
     * that landed a day either side would look right — the sky barely
     * changes overnight — and would be wrong every time.
     */
    @Test
    fun `the date carries the sky to the next event`() {
        val clock = sky()
        val from = dayOf(clock)
        assertTrue("the sky would not move", clock.leapToNextSkyEvent())
        val landed = dayOf(clock)
        assertTrue("the sky went backwards or stayed put", landed > from)
        assertTrue(
            "the sky landed on a day with nothing on it",
            SkyEvents.anythingOn(landed)
        )
        assertEquals(
            "the sky stepped over something on the way",
            SkyEvents.nextDay(from), landed
        )
    }

    /** And pressing it again goes on to the one after. */
    @Test
    fun `pressing again goes on to the next one`() {
        val clock = sky()
        clock.leapToNextSkyEvent()
        val first = dayOf(clock)
        clock.leapToNextSkyEvent()
        val second = dayOf(clock)
        assertTrue("the second press did not move the sky", second > first)
        assertTrue(SkyEvents.anythingOn(second))
    }

    /**
     * The date is only pressable where it is drawn.
     *
     * The hit box is recorded by the drawing rather than worked out beside
     * it, because the row is placed against the bottom of the face and
     * against the bottom of the screen, whichever comes first — a box
     * computed separately would be right on most phones and wrong on the
     * ones that matter.
     */
    @Test
    fun `the date is pressable where it is drawn and nowhere else`() {
        val clock = sky()
        val canvas = android.graphics.Canvas(
            android.graphics.Bitmap.createBitmap(
                1080, 2000, android.graphics.Bitmap.Config.ARGB_8888
            )
        )
        clock.draw(canvas)
        val row = clock.dateRowForTest()
        assertTrue("the date row has no place on the glass", !row.isEmpty)
        assertTrue(
            "the date row is not under the dial",
            row.top > clock.height / 2f
        )
        assertTrue("the middle of the dial presses the date", !clock.dateRowAtForTest(540f, 700f))
        assertTrue(
            "the date does not press itself",
            clock.dateRowAtForTest(row.centerX(), row.centerY())
        )
    }

    /**
     * And a sky with no date on it has nothing to press.
     *
     * Wound back past the invention of writing the row is not drawn at
     * all, and a hit box left behind from the last century that had one is
     * a press that does something invisible.
     */
    @Test
    fun `a sky with no date has nothing to press`() {
        val clock = sky()
        val canvas = android.graphics.Canvas(
            android.graphics.Bitmap.createBitmap(
                1080, 2000, android.graphics.Bitmap.Config.ARGB_8888
            )
        )
        clock.draw(canvas)
        val was = clock.dateRowForTest()
        assertTrue(!was.isEmpty)
        clock.windOrreryToYearForTest(-9000)
        clock.draw(canvas)
        assertTrue(
            "there is a date to press eleven thousand years before writing",
            clock.dateRowForTest().isEmpty
        )
        assertTrue(
            "the old hit box outlived the row it belonged to",
            !clock.dateRowAtForTest(was.centerX(), was.centerY())
        )
    }
}
