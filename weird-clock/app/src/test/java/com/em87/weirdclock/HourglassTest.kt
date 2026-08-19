package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the phone over turns the countdown over — and the two ways that
 * went wrong.
 *
 * This is the rule behind a real failure: three minutes were set, the phone
 * went into a pocket, and the alarm went off almost at once. Twice. The
 * gesture is a good one and the wiring was not, so the wiring is here now,
 * where it can be held to account.
 */
class HourglassTest {

    private val minute = 60_000L

    // ------------------------------------------------ what turning does

    @Test
    fun `turning the glass swaps what has run through for what is left`() {
        assertEquals("a minute in, two to go", 1 * minute, Hourglass.turned(3 * minute, 2 * minute))
        assertEquals("and back again", 2 * minute, Hourglass.turned(3 * minute, 1 * minute))
        assertEquals("half stays half", 90_000L, Hourglass.turned(180_000L, 90_000L))
    }

    /** A glass that has just been turned over has run nothing through yet. */
    @Test
    fun `a full glass turns to an empty one`() {
        assertEquals(0L, Hourglass.turned(3 * minute, 3 * minute))
        assertEquals(3 * minute, Hourglass.turned(3 * minute, 0L))
    }

    /**
     * And nothing ever comes out negative or longer than the glass.
     *
     * The remaining time is measured against a clock that keeps running, so
     * it can be a few milliseconds past zero by the time this is asked.
     */
    @Test
    fun `the sand cannot overflow the glass`() {
        assertEquals(0L, Hourglass.turned(3 * minute, 3 * minute + 250L))
        assertEquals(3 * minute, Hourglass.turned(3 * minute, -40L))
    }

    // ------------------------------------------------ when it turns at all

    /**
     * A pocket does not turn it.
     *
     * This is the whole bug. A phone on its way into a pocket passes
     * upside down for a moment, and acting on that moment took three
     * minutes to nothing left.
     */
    @Test
    fun `a phone passing upside down does not turn the glass`() {
        for (heldMs in listOf(0L, 40L, 200L, Hourglass.HOLD_MS - 1)) {
            assertFalse(
                "it turned after $heldMs ms upside down",
                Hourglass.turns(Card.REVERSE, running = true, heldMs = heldMs)
            )
        }
        assertTrue(
            "and a phone deliberately stood on its head does turn it",
            Hourglass.turns(Card.REVERSE, running = true, heldMs = Hourglass.HOLD_MS)
        )
    }

    /**
     * And it only turns where the sand can be seen turning.
     *
     * The other half of the bug: it acted from any card that was not on the
     * middle row, so a running countdown could be turned over from the
     * stopwatch, or from a card where it is not drawn at all — an invisible
     * edit to a timer somebody is relying on.
     */
    @Test
    fun `the glass only turns on the cards that show it`() {
        val held = Hourglass.HOLD_MS + 100
        assertTrue("the countdown dial", Hourglass.turns(Card.REVERSE, true, held))
        assertTrue("and the sand itself", Hourglass.turns(Card.HOURGLASS, true, held))
        for (card in listOf(Card.CLOCK, Card.CALENDAR, Card.ALARM, Card.STOPWATCH)) {
            assertFalse(
                "$card turned a countdown nobody could see",
                Hourglass.turns(card, true, held)
            )
        }
        assertFalse("and no card at all", Hourglass.turns(null, true, held))
    }

    /** With nothing running there is nothing to turn. */
    @Test
    fun `an idle countdown is not turned`() {
        assertFalse(
            Hourglass.turns(Card.REVERSE, running = false, heldMs = Hourglass.HOLD_MS + 500)
        )
    }
}
