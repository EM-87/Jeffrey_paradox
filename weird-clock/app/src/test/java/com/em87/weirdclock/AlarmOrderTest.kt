package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The order the alarm cards sit in.
 *
 * Two answers and no third: the clock's, or yours. What makes this worth a
 * test of its own is that they cannot both be true — the list sorted itself
 * chronologically on every refresh, so a card dragged anywhere was back
 * where it started a moment later, and dragging would have looked broken
 * rather than unimplemented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmOrderTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
    }

    private fun at(id: Int, hour: Int) =
        Alarm(id, hour, 0, true, Prefs.ALARM_SOUND_BELLS)

    private fun listOfAlarms(vararg hours: Int): MutableList<Alarm> =
        hours.mapIndexed { i, h -> at(i + 1, h) }.toMutableList()

    /** Until anybody intervenes, the clock decides. */
    @Test
    fun `an untouched list is in the order the day happens`() {
        val alarms = listOfAlarms(22, 7, 13)
        assertFalse(AlarmOrder.isManual(alarms))
        AlarmOrder.sort(alarms)
        assertEquals(listOf(7, 13, 22), alarms.map { it.hour })
    }

    /**
     * And once a card has been dragged, the clock stops deciding.
     *
     * This is the whole point. Sorting after a drag would put the card
     * straight back, which is worse than not being able to drag at all.
     */
    @Test
    fun `a card dragged stays where it was put`() {
        val alarms = listOfAlarms(7, 13, 22)
        AlarmOrder.moved(alarms, 0, 2)
        assertEquals(listOf(13, 22, 7), alarms.map { it.hour })

        assertTrue("the list did not notice it had been arranged", AlarmOrder.isManual(alarms))
        AlarmOrder.sort(alarms)
        assertEquals(
            "the list put itself back in the clock's order",
            listOf(13, 22, 7), alarms.map { it.hour }
        )
    }

    /** Every card is numbered afterwards, not only the two that moved. */
    @Test
    fun `the whole list is numbered once any of it is`() {
        val alarms = listOfAlarms(7, 13, 22)
        AlarmOrder.moved(alarms, 2, 0)
        assertEquals(
            "half a list with places and half without will jump when sorted",
            listOf(0, 1, 2), alarms.map { it.order }
        )
    }

    /**
     * An alarm made after the list was arranged goes to the end.
     *
     * It has no place in an arrangement that was made without it, and the
     * end is the one spot that is not a guess about where somebody would
     * have put it.
     */
    @Test
    fun `a new alarm joins a hand-arranged list at the end`() {
        val alarms = listOfAlarms(7, 13, 22)
        AlarmOrder.moved(alarms, 0, 2)
        alarms.add(at(9, 6))
        AlarmOrder.sort(alarms)
        assertEquals(
            "the newcomer was slotted in by the clock, on a list that has stopped using it",
            6, alarms.last().hour
        )
    }

    /** And there is a way back. */
    @Test
    fun `the clock can be given the list back`() {
        val alarms = listOfAlarms(7, 13, 22)
        AlarmOrder.moved(alarms, 0, 2)
        AlarmOrder.clear(alarms)
        assertFalse(AlarmOrder.isManual(alarms))
        AlarmOrder.sort(alarms)
        assertEquals(listOf(7, 13, 22), alarms.map { it.hour })
    }

    /** A drag that goes nowhere changes nothing, including whose order it is. */
    @Test
    fun `a card dropped where it was picked up is not an arrangement`() {
        val alarms = listOfAlarms(7, 13, 22)
        AlarmOrder.moved(alarms, 1, 1)
        assertFalse(
            "putting a card back where it came from took the list off the clock",
            AlarmOrder.isManual(alarms)
        )
    }

    /** The arrangement survives being written down and read back. */
    @Test
    fun `the order is remembered across a restart`() {
        val alarms = AlarmStore.all(context)
        alarms.clear()
        alarms.addAll(listOfAlarms(7, 13, 22))
        AlarmOrder.moved(alarms, 0, 2)
        AlarmStore.save(context)
        AlarmStore.forget()

        val read = AlarmStore.all(context)
        AlarmOrder.sort(read)
        assertEquals(
            "the list went back to the clock's order overnight",
            listOf(13, 22, 7), read.map { it.hour }
        )
    }

    /**
     * The way back is offered only when there is something to go back from.
     *
     * The same rule the toolbox follows: a button that undoes a mess is
     * there when there is a mess, and is not one more thing to read past
     * the rest of the time.
     */
    @Test
    fun `the way back appears only on a list that has been arranged`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true).commit()
        val stored = AlarmStore.all(context)
        stored.clear()
        stored.addAll(listOfAlarms(7, 13, 22))
        AlarmStore.save(context)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.ALARM)
            val back = activity.findViewById<android.view.View>(R.id.alarms_by_time)
            assertEquals(
                "a list nobody has touched is offering to put itself in order",
                android.view.View.GONE, back.visibility
            )

            AlarmOrder.moved(activity.alarmsForTest().toMutableList(), 0, 2)
            AlarmOrder.renumber(activity.alarmsForTest())
            activity.refreshAlarmsForTest()
            assertEquals(
                "a hand-arranged list offers no way back to the clock's order",
                android.view.View.VISIBLE, back.visibility
            )
        }
    }

    /**
     * A card being carried is not made bigger.
     *
     * It was scaled up a little while held, which looked right in the head
     * and wrong on the glass: a list clips its children, so the card grew
     * past the row it lives in and had its own edges sliced off — the one
     * card you are looking at, with its margins cut. The lift the drag
     * helper gives it is a shadow, and a shadow has no size to clip.
     */
    @Test
    fun `a card being carried keeps its own size`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true).commit()
        val stored = AlarmStore.all(context)
        stored.clear()
        stored.addAll(listOfAlarms(7, 13, 22))
        AlarmStore.save(context)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            activity.showCardForTest(Card.ALARM)
            val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.alarms_recycler
            )
            list.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(3000, android.view.View.MeasureSpec.EXACTLY)
            )
            list.layout(0, 0, 1000, 3000)
            assertTrue("no cards were laid out", list.childCount > 0)

            val card = list.getChildAt(0)
            activity.dragAlarmCardForTest(list, card)
            assertEquals("the card grew sideways", 1f, card.scaleX, 0.001f)
            assertEquals("and upwards", 1f, card.scaleY, 0.001f)
        }
    }
}
