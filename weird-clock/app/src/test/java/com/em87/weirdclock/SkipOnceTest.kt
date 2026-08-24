package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Off tomorrow, on again the day after.
 *
 * Turning a repeating alarm off is nearly always about one morning — a day
 * off, a late night — and it is also the commonest way to miss the morning
 * after it, because nobody remembers to put it back. Samsung's clock asks
 * which you meant, and it is the one thing about Samsung's clock worth
 * copying outright.
 *
 * The interesting half is not the dialog. It is that a skip has to expire:
 * an alarm that goes on remembering "not Tuesdays" is an alarm that never
 * rings on a Tuesday again, which is a far worse failure than the one being
 * fixed and would take weeks to notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkipOnceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
    }

    /** An alarm at [hour]:00 every day. */
    private fun daily(hour: Int) = Alarm(1, hour, 0, true, Prefs.ALARM_SOUND_BELLS)

    /** Nine in the morning, whichever day this test happens to run on. */
    private fun nineAm(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * The skipped one is passed over and the one after it is the answer.
     *
     * Which is the whole feature: the alarm stays armed, and what changes
     * is only which morning it means.
     */
    @Test
    fun `the occurrence let off is not the one it rings on`() {
        val alarm = daily(9)
        val now = nineAm() - 3_600_000L
        val first = AlarmScheduler.nextOccurrence(alarm, now)

        alarm.skippedOccurrence = first
        val second = AlarmScheduler.nextOccurrence(alarm, now)
        assertNotEquals("it still means the morning it was let off", first, second)
        assertEquals(
            "the one after should be a day later",
            86_400_000L, second - first
        )
    }

    /**
     * And only that one. The day after the skip is an ordinary morning.
     */
    @Test
    fun `only one occurrence is let off`() {
        val alarm = daily(9)
        val now = nineAm() - 3_600_000L
        alarm.skippedOccurrence = AlarmScheduler.nextOccurrence(alarm, now)
        val second = AlarmScheduler.nextOccurrence(alarm, now)

        // Stand a moment past the skipped morning and ask again: the answer
        // must be that same second one and not a third.
        assertEquals(
            "the skip carried on into the morning after",
            second, AlarmScheduler.nextOccurrence(alarm, alarm.skippedOccurrence + 60_000L)
        )
    }

    /**
     * A skip in the past is not a skip.
     *
     * This is the failure worth guarding: a flag would make the alarm skip
     * that weekday for ever and nothing would say so. An instant simply
     * stops being in the future.
     */
    @Test
    fun `a skip that has been and gone stands for nothing`() {
        val alarm = daily(9)
        val now = nineAm() + 3_600_000L
        alarm.skippedOccurrence = nineAm() - 86_400_000L
        assertFalse(
            "an alarm was still standing down for a morning last week",
            AlarmScheduler.isSkippingNext(alarm, now)
        )
        assertEquals(
            "and it changed which morning it means",
            AlarmScheduler.nextOccurrence(daily(9), now),
            AlarmScheduler.nextOccurrence(alarm, now)
        )
    }

    /** It survives being written down and read back. */
    @Test
    fun `the skip is remembered across a restart`() {
        val alarm = daily(9).apply { skippedOccurrence = nineAm() + 86_400_000L }
        val list = AlarmStore.all(context)
        list.clear()
        list.add(alarm)
        AlarmStore.save(context)
        AlarmStore.forget()

        val read = AlarmStore.all(context).first()
        assertEquals(
            "the morning it was standing down for was forgotten overnight",
            alarm.skippedOccurrence, read.skippedOccurrence
        )
    }

    /**
     * Switching one back on cancels any morning it was standing down for.
     *
     * Somebody reaching for that switch is making a decision about now, and
     * an alarm that comes back on and then silently misses tomorrow anyway
     * is the original complaint with an extra step.
     */
    @Test
    fun `turning it back on cancels the skip`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true).commit()
        val list = AlarmStore.all(context)
        list.clear()
        list.add(daily(9).apply {
            enabled = false
            skippedOccurrence = nineAm() + 86_400_000L
        })
        AlarmStore.save(context)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            val alarm = activity.alarmsForTest().first()
            assertTrue("set up wrong", alarm.skippedOccurrence > 0L)
            activity.toggleAlarmForTest(alarm, true)
            assertTrue("it did not come back on", alarm.enabled)
            assertEquals(
                "it came back on still standing down for a morning",
                0L, alarm.skippedOccurrence
            )
        }
    }

    /**
     * The switch switches it off, at once, and the other reading is
     * offered afterwards.
     *
     * This was a dialog asked *before* the alarm went off, which made
     * every single switching-off cost a flick and a tap — a toll paid by
     * everybody, including the people who meant exactly what the switch
     * said. So the switch does what a switch does and the offer follows,
     * on a bubble over the card, costing nothing to decline.
     */
    @Test
    fun `switching a repeating alarm off takes one flick and offers the rest`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true).commit()
        val list = AlarmStore.all(context)
        list.clear()
        list.add(daily(9))
        AlarmStore.save(context)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            val alarm = activity.alarmsForTest().first()
            activity.toggleAlarmForTest(alarm, false)
            // Off already, with nothing asked and nothing to dismiss.
            assertFalse("the switch did not switch it off", alarm.enabled)
            assertEquals(
                "it was left standing down for a morning instead of off",
                0L, alarm.skippedOccurrence
            )
        }
    }

    /**
     * And taking the offer puts it back on with one morning let off.
     *
     * The bubble is the whole of the old dialog's useful half. Tapped, the
     * alarm comes back armed and stands down for exactly the occurrence
     * that was next when the switch was flicked — which is what "just
     * tomorrow" always meant.
     */
    @Test
    fun `taking the offer keeps the alarm for the days after`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear().putBoolean(Prefs.OVERLAY_ASKED, true).commit()
        val list = AlarmStore.all(context)
        list.clear()
        list.add(daily(9))
        AlarmStore.save(context)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            val alarm = activity.alarmsForTest().first()
            val next = AlarmScheduler.nextOccurrence(alarm)
            activity.toggleAlarmForTest(alarm, false)
            assertTrue("nothing was offered", activity.skipBubbleShowingForTest())
            activity.takeSkipOfferForTest()
            assertTrue("the offer did not bring it back", alarm.enabled)
            assertEquals(
                "it came back on without letting the morning off",
                next, alarm.skippedOccurrence
            )
            assertFalse("the offer is still on screen", activity.skipBubbleShowingForTest())
        }
    }

    /**
     * A one-shot is never asked which morning it means.
     *
     * It has exactly one, so letting it off and switching it off are the
     * same thing, and a dialog offering the difference would be a dialog
     * offering nothing.
     */
    @Test
    fun `a one-shot is switched off without a question`() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            // A store that has never been migrated has every dayless alarm
            // rewritten to "every day" on the way in — which is right, and
            // which quietly turned this one-shot into a repeating alarm and
            // made the test measure the wrong thing.
            .putBoolean(Prefs.ONCE_MIGRATED, true)
            .commit()
        val list = AlarmStore.all(context)
        list.clear()
        list.add(daily(9).apply { daysMask = 0 })
        AlarmStore.save(context)

        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val activity = c.get()
            val alarm = activity.alarmsForTest().first()
            assertTrue("a one-shot it is not", alarm.once)
            activity.toggleAlarmForTest(alarm, false)
            assertFalse("it was not switched off", alarm.enabled)
            assertEquals(
                "a one-shot was given a morning to stand down for",
                0L, alarm.skippedOccurrence
            )
            assertFalse(
                "a one-shot was offered a choice it does not have",
                activity.skipBubbleShowingForTest()
            )
        }
    }
}
