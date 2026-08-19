package com.em87.weirdclock

/**
 * When the next tick is due.
 *
 * The second hand's tick used to be posted on the same thread that draws
 * the dial, and the dial is not a cheap thing to draw — eight planets, a
 * physics step for anything lying on the floor of the case, a layer or two
 * of transparency. Any frame that ran long pushed the tick along with it,
 * so the ticks arrived late and sometimes two seconds apart, which on a
 * clock is the one fault nobody can be talked out of hearing.
 *
 * The arithmetic is here, away from the thread that has the problem, so it
 * can be checked without one.
 */
object Ticker {

    /** How long until the next whole second after [nowMs]. */
    fun delayToNext(nowMs: Long, periodMs: Long = 1000L): Long {
        val into = Math.floorMod(nowMs, periodMs)
        // Exactly on the boundary means the next one is a whole period
        // away, not now — otherwise a tick that lands dead on the second
        // reschedules itself for zero milliseconds and spins.
        return periodMs - into
    }

    /**
     * How many whole seconds went by between two instants.
     *
     * Used to notice when a tick was missed rather than to make it up.
     * Playing the missed ones would fire two or three ticks in a burst,
     * which sounds far worse than one that was late — a clock that stutters
     * once is a clock, a clock that machine-guns is broken.
     */
    fun missed(lastMs: Long, nowMs: Long, periodMs: Long = 1000L): Int {
        if (lastMs <= 0L || nowMs <= lastMs) return 0
        return ((nowMs - lastMs) / periodMs - 1).coerceAtLeast(0L).toInt()
    }

    /**
     * Whether a tick is close enough to its second to be worth playing.
     *
     * A tick a quarter of a second late is still the tick for that second.
     * One that is most of a second late belongs to the second after it, and
     * playing it would put two ticks nearly on top of each other before the
     * next one arrives on time.
     */
    fun onTime(nowMs: Long, periodMs: Long = 1000L, slackMs: Long = 250L): Boolean {
        val into = Math.floorMod(nowMs, periodMs)
        return into <= slackMs || periodMs - into <= slackMs
    }
}
