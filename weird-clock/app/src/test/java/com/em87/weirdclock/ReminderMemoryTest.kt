package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * How long a reminder that has already happened is kept.
 *
 * Long enough to look back over, short enough that the store does not grow
 * for ever. It was three months, and the solar system moved it: zoom the
 * Earth's orbit out to the rim and every day of the year gets a mark, with
 * a dot on the ones that were busy — and a whole turn of the Earth is
 * exactly a year of them. Three months of memory would have left three
 * quarters of that circle blank whatever the year had actually held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderMemoryTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ReminderStore.forget()
    }

    /** One reminder, [daysAgo] days back, written straight into the store. */
    private fun aged(id: Int, daysAgo: Int, repeat: String = Reminder.REPEAT_NEVER): Reminder {
        val when_ = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
        return Reminder(
            id = id,
            label = "Then",
            year = when_.get(Calendar.YEAR),
            month = when_.get(Calendar.MONTH) + 1,
            day = when_.get(Calendar.DAY_OF_MONTH),
            hour = 9, minute = 0,
            repeat = repeat
        )
    }

    private fun keepAndReload(vararg reminders: Reminder): List<Int> {
        val list = ReminderStore.all(context)
        list.clear()
        list.addAll(reminders)
        ReminderStore.save(context)
        ReminderStore.forget()
        return ReminderStore.all(context).map { it.id }
    }

    /**
     * A year of the past is kept, which is what the ring of days needs.
     *
     * Six months back used to be gone. Now it is still there, and so is
     * eleven months back — one whole turn of the Earth.
     */
    @Test
    fun `a year of spent reminders is remembered`() {
        val kept = keepAndReload(aged(1, 30), aged(2, 120), aged(3, 200), aged(4, 340))
        assertTrue("a month ago was forgotten", 1 in kept)
        assertTrue("four months ago was forgotten", 2 in kept)
        assertTrue("half a year ago was forgotten", 3 in kept)
        assertTrue("eleven months ago was forgotten", 4 in kept)
    }

    /**
     * And beyond the year they do go, or the store grows for ever.
     *
     * The dial can only show one turn of the Earth at a time, so a
     * reminder older than that has nowhere left to be drawn.
     */
    @Test
    fun `anything older than a year is let go`() {
        val kept = keepAndReload(aged(1, 200), aged(2, 500), aged(3, 1200))
        assertTrue("inside the year", 1 in kept)
        assertFalse("a year and a half ago is still here", 2 in kept)
        assertFalse("three years ago is still here", 3 in kept)
    }

    /** A repeating reminder never expires: it is not in the past at all. */
    @Test
    fun `a repeating reminder is kept however old it is`() {
        val kept = keepAndReload(aged(1, 2000, repeat = Reminder.REPEAT_YEARLY))
        assertTrue("a birthday from years ago was thrown away", 1 in kept)
    }
}
