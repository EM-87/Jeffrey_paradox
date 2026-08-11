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
     * thing an alarm screen must never do is look broken. Low enough to be
     * no more than a glow in a dark room, high enough that there is
     * plainly something there.
     */
    const val FLOOR = 0.05f

    /** Off, and the two ends of what can be asked for, in seconds. */
    const val OFF = 0
    const val LONGEST = 300

    /** How long the ramp lasts, read off the setting. */
    fun seconds(stored: String?): Int =
        stored?.toIntOrNull()?.coerceIn(OFF, LONGEST) ?: OFF

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
