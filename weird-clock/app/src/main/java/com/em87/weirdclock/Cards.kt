package com.em87.weirdclock

/**
 * The six cards, and how you get from one to another.
 *
 * They used to be called C-1, C0, C1, S-1, S0 and S1, which are
 * coordinates: the name said where the card was, so moving a card meant
 * renaming it and everything that mentioned it. These names say what each
 * card *is*, and survive being moved.
 *
 * Navigation used to be a page index and a boolean row — clock or
 * chronograph — which worked while the layout really was two rows of
 * three, and cost a special case every time it was not: a flag for
 * diagonal moves, four near-identical "go there" functions, and a leaving
 * card that nothing could name, let alone animate. Here a move is just a
 * card and a direction.
 *
 * Up and down are organisational rather than literal. What matters about
 * this shape is that the stopwatch and the countdown are side by side:
 * with the hourglass sitting between them, going from one to the other
 * meant passing through a third card that had nothing to do with either.
 */
enum class Row { TOP, MIDDLE, BOTTOM }

enum class Direction { UP, DOWN, LEFT, RIGHT }

/**
 * A card's address: which page of the pager it lives on, and which row of
 * that page. Two cards never share an address.
 */
enum class Card(val page: Int, val row: Row) {
    /** The sand hourglass, above the clock. */
    HOURGLASS(Cards.PAGE_HOME, Row.TOP),

    /** The month, a swipe left of the clock. */
    CALENDAR(Cards.PAGE_LEFT, Row.MIDDLE),

    /** The clock, and the card the app opens on. */
    CLOCK(Cards.PAGE_HOME, Row.MIDDLE),

    /** The alarms and reminders, a swipe right of the clock. */
    ALARM(Cards.PAGE_RIGHT, Row.MIDDLE),

    /** The stopwatch, below the clock. */
    STOPWATCH(Cards.PAGE_HOME, Row.BOTTOM),

    /** The countdown — a stopwatch run backwards — beside the stopwatch. */
    REVERSE(Cards.PAGE_RIGHT, Row.BOTTOM);
}

object Cards {

    const val PAGE_LEFT = 0
    const val PAGE_HOME = 1
    const val PAGE_RIGHT = 2

    /** The card the app opens on, and the one every other is a step from. */
    val HOME = Card.CLOCK

    /**
     * What lies [direction] of [from], or null if nothing does.
     *
     * Worked out from the addresses rather than written down as a table:
     * a table can disagree with the layout, and this cannot. The holes are
     * real — there is no card left of the stopwatch, and a swipe that way
     * has to be swallowed rather than carried out.
     */
    fun neighbour(from: Card, direction: Direction): Card? {
        val page = from.page + when (direction) {
            Direction.LEFT -> -1
            Direction.RIGHT -> 1
            else -> 0
        }
        val row = from.row.ordinal + when (direction) {
            Direction.UP -> -1
            Direction.DOWN -> 1
            else -> 0
        }
        if (row !in Row.entries.indices) return null
        return Card.entries.firstOrNull { it.page == page && it.row.ordinal == row }
    }

    /** Which card of [row] is on [page], if that page has one. */
    fun on(page: Int, row: Row): Card? =
        Card.entries.firstOrNull { it.page == page && it.row == row }

    /**
     * Where a card arriving in place of one in [from] comes from: -1 for
     * above, 1 for below, 0 when the row has not changed and the move is
     * across rather than between.
     */
    fun slideFrom(from: Row, to: Row): Int = to.ordinal.compareTo(from.ordinal)
}
