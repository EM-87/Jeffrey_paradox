package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the app, written down where it can be checked.
 *
 * It grew as two rows of three, which put the hourglass squarely between
 * the stopwatch and the countdown: the two cards that most belong side by
 * side had a third one, unrelated to either, parked in the middle of the
 * swipe. This is that fixed, and the fix stated as tests rather than as a
 * diagram in a commit message.
 */
class CardsTest {

    @Test
    fun `the stopwatch and the countdown are neighbours`() {
        assertEquals(Card.REVERSE, Cards.neighbour(Card.STOPWATCH, Direction.RIGHT, Face.ANALOG))
        assertEquals(Card.STOPWATCH, Cards.neighbour(Card.REVERSE, Direction.LEFT, Face.ANALOG))
    }

    /**
     * The whole point of the change. One swipe, and nothing in between:
     * the two cards sit on adjacent pages of the same row, so there is no
     * third page for the pager to travel through on its way.
     */
    @Test
    fun `and nothing sits between them`() {
        assertEquals(
            "adjacent pages, or the swipe crosses a card that is not there",
            1, Card.REVERSE.page - Card.STOPWATCH.page
        )
        assertEquals("and the same row", Card.STOPWATCH.row, Card.REVERSE.row)
        assertTrue(
            "the hourglass must not be in the way",
            Card.HOURGLASS.row != Card.STOPWATCH.row
        )
    }

    /**
     * Everything is one step from home, which is what makes it the centre.
     *
     * One step, not one swipe: four cards are a swipe away and the
     * countdown is a page *and* a row from the clock, which is the diagonal
     * its button on the clock face exists to make. Written first as "the
     * four directions reach the other five", which is five things through
     * four doors and duly failed.
     */
    @Test
    fun `every card is one step from the clock`() {
        for (card in Card.entries - Card.CLOCK) {
            val across = kotlin.math.abs(card.page - Card.CLOCK.page)
            val down = kotlin.math.abs(card.row.ordinal - Card.CLOCK.row.ordinal)
            assertTrue("$card is $across pages and $down rows away", across <= 1 && down <= 1)
        }
    }

    /** Four of them by swiping, each in its own direction. */
    @Test
    fun `and four of them are a swipe away`() {
        assertEquals(Card.HOURGLASS, Cards.neighbour(Card.CLOCK, Direction.UP, Face.ANALOG))
        assertEquals(Card.STOPWATCH, Cards.neighbour(Card.CLOCK, Direction.DOWN, Face.ANALOG))
        assertEquals(Card.CALENDAR, Cards.neighbour(Card.CLOCK, Direction.LEFT, Face.ANALOG))
        assertEquals(Card.ALARM, Cards.neighbour(Card.CLOCK, Direction.RIGHT, Face.ANALOG))
    }

    @Test
    fun `and the way back is the way you came`() {
        val opposite = mapOf(
            Direction.UP to Direction.DOWN,
            Direction.DOWN to Direction.UP,
            Direction.LEFT to Direction.RIGHT,
            Direction.RIGHT to Direction.LEFT
        )
        for (card in Card.entries) {
            for ((there, back) in opposite) {
                val next = Cards.neighbour(card, there, Face.ANALOG) ?: continue
                assertEquals(
                    "$card -> $there -> $back must come home",
                    card, Cards.neighbour(next, back, Face.ANALOG)
                )
            }
        }
    }

    /** The holes are real, and a swipe into one has to be swallowed. */
    @Test
    fun `there is nothing beside the hourglass, nor left of the stopwatch`() {
        assertNull(Cards.neighbour(Card.HOURGLASS, Direction.LEFT, Face.ANALOG))
        assertNull(Cards.neighbour(Card.HOURGLASS, Direction.RIGHT, Face.ANALOG))
        assertNull(Cards.neighbour(Card.HOURGLASS, Direction.UP, Face.ANALOG))
        assertNull(Cards.neighbour(Card.STOPWATCH, Direction.LEFT, Face.ANALOG))
        assertNull(Cards.neighbour(Card.STOPWATCH, Direction.DOWN, Face.ANALOG))
        assertNull(Cards.neighbour(Card.CALENDAR, Direction.DOWN, Face.ANALOG))
        assertNull(Cards.neighbour(Card.CALENDAR, Direction.UP, Face.ANALOG))
        assertNull(Cards.neighbour(Card.ALARM, Direction.UP, Face.ANALOG))
    }

    /**
     * The weather is behind a door and not on the way to anything.
     *
     * It has an address like every card — over the month, beside the
     * hourglass — and the address is all it shares with them: nothing
     * the grid knows about leads to it, it leads to nothing, and the way
     * out of it is the way out of every card, back to the clock. Only
     * the dial has it, because only the dial has the token that opens it.
     */
    @Test
    fun `the weather is behind a door, and only on the dial`() {
        assertEquals(Cards.PAGE_LEFT, Card.WEATHER.page)
        assertEquals(Row.TOP, Card.WEATHER.row)
        assertEquals(listOf(Card.WEATHER), Card.entries.filter { it.behindADoor })
        for (face in Face.entries) {
            for (card in Card.entries) {
                for (direction in Direction.entries) {
                    assertTrue(
                        "$direction of $card on $face is the weather",
                        Cards.neighbour(card, direction, face) != Card.WEATHER
                    )
                }
            }
            for (direction in Direction.entries) {
                assertNull(
                    "$direction of the weather on $face",
                    Cards.neighbour(Card.WEATHER, direction, face)
                )
            }
            assertEquals(
                "the weather's card on $face",
                face == Face.ANALOG, Card.WEATHER in face.cards
            )
            assertEquals(
                if (face == Face.ANALOG) Card.WEATHER else null,
                Cards.on(Cards.PAGE_LEFT, Row.TOP, face)
            )
        }
        assertEquals(Card.CLOCK, Cards.back(Card.WEATHER))
        // And it empties no page and adds none: the left-hand page was
        // already there for the month.
        assertEquals(
            listOf(Cards.PAGE_LEFT, Cards.PAGE_HOME, Cards.PAGE_RIGHT), Cards.pagesOf(Face.ANALOG)
        )
    }

    /** No two cards share an address, or one would hide the other. */
    @Test
    fun `every card has the place to itself`() {
        val addresses = Card.entries.map { it.page to it.row }
        assertEquals(addresses.size, addresses.distinct().size)
    }

    /**
     * The digital face has no hourglass, so above the clock is a hole and
     * not a card that happens to be hidden. Checked through [Cards] rather
     * than by reading [Face.cards] back at itself: what matters is that the
     * navigation agrees, since a live swipe onto an invisible card is the
     * failure this is here to stop.
     */
    @Test
    fun `there is nothing above a digital clock`() {
        assertNull(Cards.neighbour(Card.CLOCK, Direction.UP, Face.DIGITAL))
        assertNull(Cards.on(Cards.PAGE_HOME, Row.TOP, Face.DIGITAL))
        assertEquals(Card.HOURGLASS, Cards.on(Cards.PAGE_HOME, Row.TOP, Face.ANALOG))
    }

    /** And everything else is still where it was. */
    @Test
    fun `the digital face keeps the other five cards`() {
        for (card in Card.entries - Card.HOURGLASS - Card.WEATHER) {
            assertEquals(
                "$card must still be reachable",
                card, Cards.on(card.page, card.row, Face.DIGITAL)
            )
        }
        assertEquals(Card.STOPWATCH, Cards.neighbour(Card.CLOCK, Direction.DOWN, Face.DIGITAL))
        assertEquals(Card.CALENDAR, Cards.neighbour(Card.CLOCK, Direction.LEFT, Face.DIGITAL))
        assertEquals(Card.ALARM, Cards.neighbour(Card.CLOCK, Direction.RIGHT, Face.DIGITAL))
    }

    @Test
    fun `a row change knows which way it came from`() {
        assertEquals(1, Cards.slideFrom(Row.MIDDLE, Row.BOTTOM))
        assertEquals(-1, Cards.slideFrom(Row.MIDDLE, Row.TOP))
        assertEquals(0, Cards.slideFrom(Row.MIDDLE, Row.MIDDLE))
    }

    /**
     * Every card is one move from the clock, so back is one step deep:
     * anywhere else goes to the clock, and the clock leaves.
     */
    @Test
    fun `back is the clock, and from the clock it is the way out`() {
        for (card in Card.entries) {
            if (card == Card.CLOCK) continue
            assertEquals("$card", Card.CLOCK, Cards.back(card))
        }
        assertNull("nowhere further back", Cards.back(Card.CLOCK))
    }
}
