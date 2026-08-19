package com.em87.weirdclock

/**
 * What order the alarm cards are in, and who decided.
 *
 * There are two answers and no third. Chronological, which is what a list
 * of alarms wants to be and what it has always been; or the order somebody
 * has dragged them into. The moment a card is dragged the list stops
 * arranging itself, because a list that re-sorted itself after every drag
 * would make dragging a thing you do and then watch being undone.
 *
 * The way back is not hidden: the alarms card offers it, and only while
 * there is something to go back from.
 */
object AlarmOrder {

    /** Nobody has moved this one; it takes its place by the clock. */
    const val UNSET = -1

    /** Whether the list has been arranged by hand. */
    fun isManual(alarms: List<Alarm>): Boolean = alarms.any { it.order != UNSET }

    /**
     * The order the cards are shown in.
     *
     * By the clock while nobody has intervened — by the earliest time each
     * alarm rings at, which is the time its card leads with, and by id
     * between alarms that share it so the list does not shuffle on every
     * save. By hand once anybody has, with anything unplaced at the end:
     * an alarm made after the list was arranged has no place in that
     * arrangement, and the end is the one spot that is not a guess.
     */
    fun sort(alarms: MutableList<Alarm>) {
        if (!isManual(alarms)) {
            alarms.sortWith(compareBy({ firstMinute(it) }, { it.id }))
            return
        }
        alarms.sortWith(
            compareBy(
                { if (it.order == UNSET) Int.MAX_VALUE else it.order },
                { firstMinute(it) },
                { it.id }
            )
        )
    }

    /**
     * Records a card dragged from one place in the list to another.
     *
     * The list is expected to be in the order it is drawn in, and comes
     * back the same way. Every alarm is numbered afterwards and not only
     * the two that moved — half a list with places and half without is a
     * list that will jump the next time it is sorted.
     */
    fun moved(alarms: MutableList<Alarm>, from: Int, to: Int) {
        if (from !in alarms.indices || to !in alarms.indices || from == to) return
        alarms.add(to, alarms.removeAt(from))
        renumber(alarms)
    }

    /** Numbers the list as it stands, which is how a drag is remembered. */
    fun renumber(alarms: List<Alarm>) {
        for ((i, alarm) in alarms.withIndex()) alarm.order = i
    }

    /** Back to the clock's own order, and to it staying that way. */
    fun clear(alarms: List<Alarm>) {
        for (alarm in alarms) alarm.order = UNSET
    }

    /** The earliest minute of the day this alarm rings at. */
    private fun firstMinute(alarm: Alarm): Int =
        alarm.allTimes().first().let { (h, m) -> h * 60 + m }
}
