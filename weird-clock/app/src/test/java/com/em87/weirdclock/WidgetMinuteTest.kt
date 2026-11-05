package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The digital widget's minute is booked from one reading of the clock.
 *
 * It was two: the instant the alarm was added to, and the instant the
 * distance to the minute was measured from, read a few lines apart. A
 * millisecond between them and the sum landed a millisecond before the
 * minute — a widget that woke at :59.999, drew the old minute, and slept
 * on it through the whole of the next. The suite caught it once in a
 * while as a trigger at 59 999 past the minute, which is this test's
 * number made from two clocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetMinuteTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the distance to the minute is measured from the reading it is added to`() {
        // Twelve seconds and 345 milliseconds into some minute: the base
        // is a whole number of minutes since the epoch.
        val now = 1_700_000_040_000L + 12_345L
        val wait = ClockWidgetProvider.nextRepaintMs(context, Face.DIGITAL, now)
        assertEquals("not the rest of this minute", 60_000L - 12_345L, wait)
        assertEquals("not on the minute", 0L, (now + wait) % 60_000L)
        // And exactly on the boundary it is the whole of the next minute,
        // not nought: a wait of nothing is a widget repainting in a loop.
        assertEquals(60_000L, ClockWidgetProvider.nextRepaintMs(context, Face.DIGITAL, 1_700_000_040_000L))
    }
}
