package com.em87.weirdclock

/**
 * When the next tick is due, and how well the last ones landed.
 *
 * The tick has been wrong twice now, in two different ways, and both were
 * failures of scheduling rather than of sound. First it rode the thread
 * that draws the dial, so a long frame carried it along. Then it ran on a
 * thread of its own but asked "how long until the next second?" every time
 * round — which re-derives the phase from a clock that can be corrected
 * underneath it, and lets each late callback push the next one later
 * still.
 *
 * So the beat is laid out in advance instead: one anchor, and every tick
 * after it at an exact multiple of a second from that anchor, posted at an
 * absolute time. A callback that arrives thirty milliseconds late does not
 * move the one after it by thirty milliseconds; it does not move it at all.
 *
 * The arithmetic is here, away from the thread that has to run on it, so it
 * can be held to the millisecond without a device.
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
     * When beat number [beat] falls, counting from an anchor.
     *
     * The whole of the fix: every tick's time is a multiple of a second
     * from one fixed point, so lateness never accumulates. Beat zero is the
     * anchor itself.
     */
    fun beatAt(anchorUptimeMs: Long, beat: Long, periodMs: Long = 1000L): Long =
        anchorUptimeMs + beat * periodMs

    /**
     * How far off the wall clock the beat has wandered.
     *
     * The anchor is in uptime and the seconds it is meant to land on are in
     * wall-clock time, and the two are not the same clock: uptime stops in
     * deep sleep and the wall clock is corrected from the network. Over
     * hours they part company, and the tick would end up sounding halfway
     * between two seconds while the hand steps on them.
     *
     * Returns the signed distance to the nearest whole second, so a tick
     * that is early gives a negative number and one that is late a positive
     * one.
     */
    fun driftMs(wallMs: Long, periodMs: Long = 1000L): Long {
        val into = Math.floorMod(wallMs, periodMs)
        return if (into > periodMs / 2) into - periodMs else into
    }

    /**
     * Whether the beat has drifted far enough from the wall clock to be
     * worth laying out again.
     *
     * Re-anchoring is a jump, and a jump is exactly what a clock's tick
     * must not do, so it happens only when the alternative is worse: a
     * fifth of a second off the second is audible against a hand that steps
     * on it.
     */
    fun needsResync(wallMs: Long, periodMs: Long = 1000L, toleranceMs: Long = 200L): Boolean =
        kotlin.math.abs(driftMs(wallMs, periodMs)) > toleranceMs

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
     * Whether a tick is still worth playing, or has lost its second.
     *
     * This once refused anything more than a quarter second late, which
     * turned every late tick into a missing one — and a clock that misses a
     * tick is a worse clock than one that ticks a little late. What is
     * still refused is a tick so late that the next one is about to land,
     * because playing it would put two of them almost together.
     */
    fun onTime(nowMs: Long, periodMs: Long = 1000L, slackMs: Long = 150L): Boolean =
        periodMs - Math.floorMod(nowMs, periodMs) > slackMs

    /**
     * What the beat has been doing, kept so somebody can be told.
     *
     * A tick that is heard to skip and cannot be measured is an argument,
     * not a bug. These three numbers settle which half is at fault: lateness
     * is the scheduler's, a refusal from the sound pool is the audio
     * system's, and a beat that never ran at all is neither.
     */
    class Record {
        /** The worst a tick has been late by, in milliseconds. */
        @Volatile
        var worstLagMs = 0L
            private set

        /** How many ticks have sounded. */
        @Volatile
        var played = 0L
            private set

        /** How many were dropped for arriving with their second already gone. */
        @Volatile
        var lost = 0L
            private set

        /** How many the sound system refused to play when asked. */
        @Volatile
        var refused = 0L
            private set

        fun sounded(lagMs: Long) {
            played++
            if (lagMs > worstLagMs) worstLagMs = lagMs
        }

        fun missedIt() {
            lost++
        }

        fun refusedIt() {
            refused++
        }

        fun forget() {
            worstLagMs = 0L
            played = 0L
            lost = 0L
            refused = 0L
        }
    }
}
