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

    // -------------------------------------------- once somebody is awake

    /**
     * What a window asks for when it wants the phone's own brightness
     * back.
     *
     * `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`, written out
     * so this file stays arithmetic and can be tested without a window.
     */
    const val THE_PHONE_S_OWN = -1f

    /**
     * How long the screen takes to catch up once it has been touched.
     *
     * Short, but not instant. Somebody who has just picked the phone up
     * has been looking at a dim screen for a moment and their eyes are
     * where the screen was; a jump to full is the slab of white light
     * this whole file exists to avoid, half a second later than usual.
     */
    const val TAKEOVER_MS = 900L

    /**
     * The dimmest the screen is allowed to settle at once it is being
     * looked at.
     *
     * A sunrise that is still creeping up from a twelfth after somebody
     * has picked the phone up is a screen they cannot read — and if the
     * alarm has a sum on it, one they cannot answer. Whatever else
     * happens, a touched screen is a legible screen.
     */
    const val AWAKE_FLOOR = 0.25f

    /** And what to settle at on a phone with no light sensor in it. */
    const val NO_SENSOR = 0.7f

    /**
     * How bright the screen has to be to be read in a room of [lux].
     *
     * Logarithmic, because light is: a bedroom at night is under a lux, a
     * lamp on is a hundred, and a window in the morning is thousands, and
     * those three are one, two and four decades apart rather than
     * anything a straight line can join. A thousand lux is a room with
     * daylight in it and takes the whole screen; a dark one takes
     * [AWAKE_FLOOR] and no more, which is the point of the mode.
     */
    fun forRoom(lux: Float): Float {
        val l = lux.coerceAtLeast(1f)
        val t = (Math.log10(l.toDouble()) / 3.0).coerceIn(0.0, 1.0).toFloat()
        return AWAKE_FLOOR + (1f - AWAKE_FLOOR) * t
    }

    /**
     * The hand-over: from where the sunrise had got to, up to what the
     * room needs, over [TAKEOVER_MS].
     *
     * Straight rather than squared. The squaring in [brightness] is there
     * to keep the first seconds of a sunrise slow for somebody asleep;
     * this is for somebody awake and holding the phone, who wants the
     * screen legible and is not going to be startled by it.
     */
    fun handover(sinceTouchMs: Long, from: Float, to: Float): Float {
        if (sinceTouchMs >= TAKEOVER_MS) return to
        val x = (sinceTouchMs.toFloat() / TAKEOVER_MS).coerceIn(0f, 1f)
        return from + (to - from) * x
    }

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
