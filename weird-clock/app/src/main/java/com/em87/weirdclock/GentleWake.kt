package com.em87.weirdclock

/**
 * Coming up rather than switching on.
 *
 * The sound has been ramping for a while — an alarm that starts at full
 * volume in a dark room is a fright, not a wake-up — and the screen was
 * still doing the other thing: black, then a full-brightness slab of white
 * light about a foot from a face with its eyes shut.
 *
 * So the ring screen comes up too, over as long as you ask for.
 */
object GentleWake {

    /**
     * The dimmest it ever starts.
     *
     * Not zero, and this is the whole of the care needed here: a screen at
     * nothing looks like a screen that failed to come on, and the one
     * thing an alarm screen must never do is look broken. It was 0.05 and
     * that was too near nothing — reported as a black screen, which is
     * exactly the failure this number exists to avoid.
     */
    const val FLOOR = 0.12f

    /** Off, and the two ends of what can be asked for, in seconds. */
    const val OFF = 0
    const val LONGEST = 900

    /** How long the ramp lasts, read off the old app-wide setting. */
    fun seconds(stored: String?): Int = clamp(stored?.toIntOrNull() ?: OFF)

    /** And off an alarm, which keeps it as a number. */
    fun clamp(seconds: Int): Int = seconds.coerceIn(OFF, LONGEST)

    /**
     * The lengths offered, in seconds.
     *
     * Minutes rather than seconds. Thirty seconds is not a sunrise, it is
     * a screen coming on slightly late; the point of the thing is that it
     * is already happening by the time you notice it.
     */
    val CHOICES = intArrayOf(OFF, 60, 180, 300, 600)

    /**
     * How bright the screen should be [elapsedMs] into a ramp of
     * [rampMs], as a fraction of full.
     *
     * Squared rather than straight. Brightness is not perceived in
     * proportion to the number: a straight ramp from the floor spends most
     * of its length looking like it has already finished, and all of the
     * gentleness in the first second or two. Squaring puts the slow part
     * at the start, where somebody is still asleep.
     *
     * A ramp of nothing is full brightness, not a division by zero and not
     * a screen that stays dark: every way of asking for no ramp has to
     * end with the screen simply on.
     */
    fun brightness(elapsedMs: Long, rampMs: Long): Float {
        if (rampMs <= 0L) return 1f
        val x = (elapsedMs.toFloat() / rampMs).coerceIn(0f, 1f)
        return FLOOR + (1f - FLOOR) * x * x
    }

    /** Whether there is any of the ramp left to run. */
    fun ramping(elapsedMs: Long, rampMs: Long): Boolean =
        rampMs > 0L && elapsedMs < rampMs

    /**
     * When the torch should start, in ms from the beginning of the
     * ringing — or -1 for never.
     *
     * For the sleeper the sunrise does not reach. The point is that it
     * comes *after*: a flash from the first second is the alarm equivalent
     * of shouting, and there is already a per-alarm setting for people who
     * want that. This one waits for the gentle half to have had its go and
     * failed.
     *
     * Never without a sunrise to follow, because then there is nothing for
     * it to be the second half of.
     */
    fun flashAfterMs(gentleSeconds: Int, wanted: Boolean): Long {
        if (!wanted) return -1L
        val ramp = clamp(gentleSeconds)
        if (ramp <= 0) return -1L
        return ramp * 1000L
    }

    /**
     * How far into the ramp we are, counted from when the *ringing*
     * started rather than from when the screen was built.
     *
     * These come apart. A screen rebuilt half way through — a rotation,
     * the system putting it back over the lock screen — would otherwise
     * start the ramp again from the dark, which is the one moment it must
     * not: by then somebody is looking at it.
     *
     * A [ringingSince] of nothing means no service is ringing and there is
     * no history to pick up, so the ramp starts at the beginning.
     */
    fun elapsed(ringingSince: Long, now: Long): Long =
        if (ringingSince <= 0L) 0L else (now - ringingSince).coerceAtLeast(0L)
}
