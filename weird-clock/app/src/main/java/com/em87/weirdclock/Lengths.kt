package com.em87.weirdclock

/**
 * The two lengths a countdown remembers, and what the reset pusher does
 * about them.
 *
 * Reset on a kitchen timer means "again", and it earned that meaning back:
 * a countdown that has run down goes back to the length it was set to
 * rather than to nothing. But a countdown sitting untouched at its full
 * length has nothing to go back *to* — the pusher was pressed and the dial
 * did not move, which reads as a broken button rather than as a button
 * with nothing to do.
 *
 * So it becomes the other thing you might want: the length before this one.
 * Three minutes for the tea, five for the eggs, and the pusher swaps
 * between them without winding anything by hand. Press it again and you are
 * back where you were, which is what makes it safe to press at all.
 *
 * Only lengths wound by hand are remembered. A swap does not count as
 * winding — otherwise the two would collapse into one after a single press
 * and the pusher would be dead again by its own doing.
 */
class Lengths {

    /** What the dial is wound to now. */
    var set = 0L
        private set

    /** The one before it, or nothing if there has only ever been one. */
    var kept = 0L
        private set

    /** A length chosen by hand. */
    fun wound(ms: Long) {
        if (ms <= 0L || ms == set) return
        if (set > 0L) kept = set
        set = ms
    }

    /** Whether the pusher has a second length to offer. */
    fun hasOther(): Boolean = kept > 0L && kept != set

    /**
     * What the reset pusher should leave on the dial.
     *
     * While it is running, or stopped part way down, reset means "again"
     * and the answer is the length it was set to. Only a countdown already
     * sitting at its full length — the case where "again" would change
     * nothing — swaps to the other one.
     */
    fun onReset(running: Boolean, remainingMs: Long): Long {
        if (running || remainingMs != set) return set
        if (!hasOther()) return set
        val was = set
        set = kept
        kept = was
        return set
    }

    /** Taken from somewhere else — a countdown adopted from the shade. */
    fun adopt(ms: Long) {
        if (ms > 0L) set = ms
    }
}
