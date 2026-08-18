package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The record on disk, and the sentence it turns into.
 *
 * [Cycle] is checked without a phone next door; this is the half that needs
 * one — that what is written down comes back, that it travels in the
 * backup, and above all that the words on the sheet never sound surer than
 * the arithmetic behind them. The last of those is the whole risk with a
 * feature like this: a confident date built out of a textbook twenty-eight
 * would be believed, and there would be nothing behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CycleRecordTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        CycleStore.forget()
    }

    // --------------------------------------------------------- the record

    @Test
    fun `what is written down comes back`() {
        CycleStore.replace(
            context,
            listOf(Cycle.Period(20_000, 4), Cycle.Period(20_028, 6))
        )
        CycleStore.forget()

        val back = CycleStore.all(context)
        assertEquals(listOf(20_000, 20_028), back.map { it.start })
        assertEquals(listOf(4, 6), back.map { it.days })
    }

    /**
     * It comes back in order however it is written down.
     *
     * Written straight into the store rather than through [CycleStore.replace],
     * which sorts on the way in: going through it would only ever prove that
     * *it* sorts, and the reading half could be taken out without a murmur.
     * A file from an older build, or one edited by hand, arrives here in
     * whatever order it happens to be in.
     */
    @Test
    fun `it comes back in order, however it was written down`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(
            "pref_cycle_json",
            """[{"start":20060},{"start":20000},{"start":20030}]"""
        ).commit()
        CycleStore.forget()
        assertEquals(listOf(20_000, 20_030, 20_060), CycleStore.all(context).map { it.start })
    }

    /** A corrupt store loses a list rather than refusing to open the app. */
    @Test
    fun `a store that has been scribbled on does not take the app with it`() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("pref_cycle_json", "{not json at all").commit()
        CycleStore.forget()
        assertTrue(CycleStore.all(context).isEmpty())
    }

    /**
     * It travels in the backup, and the cache is dropped on the way in.
     *
     * The backup takes the whole preference store, so the record rides
     * along without being named — but the store caches its list in a static
     * field, and a restore that only rewrote preferences would be invisible
     * until the process died.
     */
    @Test
    fun `the record survives a backup and a restore`() {
        CycleStore.replace(context, listOf(Cycle.Period(20_000, 5), Cycle.Period(20_028, 4)))
        val file = Backup.export(context)

        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        CycleStore.forget()
        assertTrue("set up wrong", CycleStore.all(context).isEmpty())

        assertTrue(Backup.import(context, file) != null)
        assertEquals(
            "the record came back",
            listOf(20_000, 20_028), CycleStore.all(context).map { it.start }
        )
    }

    // ------------------------------------------------------- what it says

    private fun sheet(): CycleSheet {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return CycleSheet(controller.get()) { }
    }

    /**
     * With one period recorded it says outright that it is guessing.
     *
     * This is the one that matters. Somebody shown "expected 14 March"
     * after entering a single date will believe it, and behind it there is
     * nothing but the number twenty-eight.
     */
    @Test
    fun `with one period it admits it has not learned anything`() {
        val s = sheet()
        val one = listOf(Cycle.Period(20_000))
        assertEquals(
            "it must say it is guessing, in so many words",
            context.getString(R.string.cycle_detail_guessing, Cycle.DEFAULT_LENGTH),
            s.detail(one, 20_010)
        )
    }

    /** With a history it gives the window, not a single day. */
    @Test
    fun `with a history it gives a window`() {
        val s = sheet()
        val h = listOf(20_000, 20_028, 20_054, 20_084).map { Cycle.Period(it) }
        val f = Cycle.forecast(h)!!
        val said = s.detail(h, f.expected - 5)
        assertTrue("'$said' does not say when it opens", said.contains(s.dayText(f.from)))
        assertTrue("'$said' does not say when it closes", said.contains(s.dayText(f.to)))
    }

    @Test
    fun `nothing recorded says nothing recorded`() {
        val s = sheet()
        assertEquals(
            context.getString(R.string.cycle_nothing_yet),
            s.headline(emptyList(), 20_000)
        )
    }

    /**
     * And it only calls something late once the window has gone by.
     *
     * A month with a wide window is one where the expected day means less,
     * and announcing a delay the day after it would be announcing one every
     * month — which is how an app comes to be ignored.
     */
    @Test
    fun `late is only said once the window has passed`() {
        val s = sheet()
        val irregular = listOf(20_000, 20_025, 20_056, 20_082, 20_116).map { Cycle.Period(it) }
        val f = Cycle.forecast(irregular)!!
        assertTrue("this history is not regular", f.to > f.expected + 1)

        // What "late" actually reads as, for the number of days it would be.
        fun lateText(days: Int) =
            context.resources.getQuantityString(R.plurals.cycle_late, days, days)

        val insideWindow = s.headline(irregular, f.expected + 1)
        assertEquals(
            "it called a delay while still inside its own window",
            context.getString(R.string.cycle_due_about_now), insideWindow
        )

        val after = f.to + 3
        assertEquals(
            "and never called one after the window had gone by",
            lateText(after - f.expected), s.headline(irregular, after)
        )
    }

    /** While it is happening, it says which day of it this is. */
    @Test
    fun `during a period it counts the days`() {
        val s = sheet()
        val h = listOf(Cycle.Period(20_000, 5))
        assertEquals(
            context.getString(R.string.cycle_now, 3),
            s.headline(h, 20_002)
        )
    }

    // -------------------------------------------------- and on the calendar

    /**
     * The marks reach the calendar, which is the thing that was asked for.
     *
     * Checked by building the page the way the app builds it, rather than
     * by trusting that a map handed to a view is drawn: a phase map that
     * never arrives looks exactly like a month with nothing recorded.
     */
    @Test
    fun `a recorded cycle reaches the calendar page`() {
        // Through the app, not by building the map here: a map built in the
        // test proves only that [Cycle.phase] works, and the wiring that
        // hands it to the view could be cut without a word.
        val now = System.currentTimeMillis()
        val today = Cycle.today(now, java.util.TimeZone.getDefault().getOffset(now))
        CycleStore.replace(context, listOf(Cycle.Period(today - 2, days = 4)))

        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val phases = c.get().calendarCyclePhasesForTest()
            assertTrue("nothing reached the calendar at all", phases.isNotEmpty())
            assertTrue(
                "the days actually bled on are not marked",
                phases.filterValues { it == Cycle.Phase.PERIOD }.isNotEmpty()
            )
        }
        CycleStore.forget()
    }

    /** And with nothing recorded, the calendar is told nothing at all. */
    @Test
    fun `an empty record marks nothing`() {
        CycleStore.replace(context, emptyList())
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertTrue(c.get().calendarCyclePhasesForTest().isEmpty())
        }
    }
}
