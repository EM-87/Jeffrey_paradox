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
        // Four months, not a fortnight. The moons are no longer travelled
        // to — see [SkyEvents.WORTH_A_JOURNEY] — and what is left is about
        // seventeen days a year: eight showers, three or four oppositions
        // and four to six eclipses, unevenly spread. That is the point of
        // dropping the moons: a press now lands on something worth the
        // journey rather than on the next fortnight.
        assertTrue("the sky went quiet for $worst days", worst <= 130)
    }

    /**
     * And the moons are not among them.
     *
     * The fortnightly ones are the whole reason this needed saying: left
     * in, walking forward through next year meant twenty-six presses of
     * "full moon, new moon, full moon" for every eclipse, and the eclipses
     * were hidden among them. They are still on the calendar, where every
     * day already carries a picture of the moon anyway.
     */
    @Test
    fun `the journey does not stop for a moon`() {
        var day = CivilDays.epochDay(2026, 1, 1)
        var moonless = 0
        repeat(12) {
            val next = SkyEvents.nextDay(day) ?: return@repeat
            val kinds = SkyEvents.on(next).map { e -> e.kind }
            assertTrue(
                "the sky was carried to a day whose only news is the moon: $kinds",
                kinds.any { k ->
                    k != SkyEvents.Kind.FULL_MOON && k != SkyEvents.Kind.NEW_MOON
                }
            )
            moonless++
            day = next
        }
        assertTrue("nothing was found to travel to at all", moonless > 6)
        // But a moon is still a thing that happens, and the calendar still
        // knows: the day it is on is busy, it simply is not worth a
        // journey.
        var moon = CivilDays.epochDay(2026, 1, 1)
        while (SkyEvents.headline(moon)?.kind.let {
                it != SkyEvents.Kind.FULL_MOON && it != SkyEvents.Kind.NEW_MOON
            }
        ) {
            moon++
        }
        assertTrue("a full moon is not on the calendar at all", SkyEvents.anythingOn(moon))
        assertTrue(
            "a full moon is worth winding the sky a month for",
            !SkyEvents.worthTravellingTo(moon)
        )
    }

    /** And "next" means after, not on. */
    @Test
    fun `the next day is a later day`() {
        val busy = SkyEvents.eclipseDays().first().first
        assertTrue("a day full of eclipse is not itself busy", SkyEvents.worthTravellingTo(busy))
        val next = SkyEvents.nextDay(busy)
        assertNotNull(next)
        assertTrue("the search handed back the day it started on", next!! > busy)
    }

    /** Everything it finds is a day worth the journey, and nothing is skipped. */
    @Test
    fun `what it finds is a day with something on it`() {
        var day = CivilDays.epochDay(2026, 1, 1)
        repeat(30) {
            val next = SkyEvents.nextDay(day) ?: return@repeat
            assertTrue(
                "the search stopped on a day with nothing worth the journey on it",
                SkyEvents.worthTravellingTo(next)
            )
            // And nothing was skipped: every day in between has nothing on
            // it worth crossing a month for.
            for (between in (day + 1) until next) {
                assertTrue(
                    "day $between was stepped over and it was worth stopping at",
                    !SkyEvents.worthTravellingTo(between)
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
        // It travels rather than arriving — see the test below — so the
        // journey is finished here and the question is where it ends up.
        clock.settleOrreryForTest()
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
        clock.settleOrreryForTest()
        val first = dayOf(clock)
        clock.leapToNextSkyEvent()
        clock.settleOrreryForTest()
        val second = dayOf(clock)
        assertTrue("the second press did not move the sky", second > first)
        assertTrue(SkyEvents.anythingOn(second))
    }

    /**
     * And it travels there rather than arriving.
     *
     * The whole point of this dial is that time is a mechanism: carry a
     * planet and the others follow at the speed their own year demands. A
     * jump throws that away — the planets are simply somewhere else on the
     * next frame, and the thing that made the sky worth winding is the bit
     * you did not see.
     */
    @Test
    fun `the sky travels to the next event rather than jumping`() {
        val clock = sky()
        val from = clock.orreryMsForTest()
        assertTrue(clock.leapToNextSkyEvent())
        assertTrue("the sky is not travelling", clock.orreryTravellingForTest())
        // Within a millisecond: the clock itself is still running under it,
        // and what is being asked is whether the *wound* offset jumped.
        assertTrue(
            "the sky arrived on the very frame the date was pressed",
            kotlin.math.abs(clock.orreryMsForTest() - from) < 50L
        )
        // Part way along it is somewhere in between, at neither end.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
        val midway = clock.orreryMsForTest()
        assertTrue("it has not set off", midway > from)
        clock.settleOrreryForTest()
        assertTrue("it went no further than the first half of the journey",
            clock.orreryMsForTest() > midway)
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
