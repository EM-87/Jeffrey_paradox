package com.em87.weirdclock

/**
 * Which hours count as night.
 *
 * Ten at night until seven in the morning, hard-coded, since the day the
 * dimming was written — which is somebody else's night. The window wraps
 * midnight, and that is the whole of the difficulty: "after ten and before
 * seven" is never true of any hour if you read it as an ordinary range, and
 * "between seven and ten" is the daytime.
 */
object NightWindow {

    const val DEFAULT_FROM = 22
    const val DEFAULT_TO = 7

    /**
     * True when [hour] falls inside the window that starts at [from] and
     * ends at [to], both whole hours on a 24-hour clock.
     *
     * A window that starts and ends at the same hour is no night at all
     * rather than a whole day of it: somebody dragging the two sliders
     * together means "stop dimming", not "dim for ever".
     */
    fun isNight(hour: Int, from: Int, to: Int): Boolean {
        val h = ((hour % 24) + 24) % 24
        val start = ((from % 24) + 24) % 24
        val end = ((to % 24) + 24) % 24
        if (start == end) return false
        // Wrapping midnight is the ordinary case, not the exception: nights
        // that do not cross it are the odd ones.
        return if (start < end) h in start until end else h >= start || h < end
    }

    /** The hours in a day, and so the number of sections on the bar. */
    const val HOURS = 24

    /**
     * How far apart two hours are, going whichever way round is shorter.
     *
     * Two on a clock face, not two on a ruler: eleven at night and one in
     * the morning are two hours apart, not twenty-two.
     */
    fun apart(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % HOURS
        return kotlin.math.min(d, HOURS - d)
    }

    /**
     * Which of the two pins a finger landing at [hour] takes hold of: true
     * for the one the night starts at, false for the one it ends at.
     *
     * Measured the short way round, so a touch just after midnight reaches
     * for the pin at eleven rather than the one at seven.
     */
    fun grabsEntry(hour: Float, from: Int, to: Int): Boolean =
        apart(hour, from.toFloat()) <= apart(hour, to.toFloat())

    /** The window written out, for the row underneath the bar. */
    fun label(from: Int, to: Int): String = "%02d:00 – %02d:00".format(
        ((from % HOURS) + HOURS) % HOURS,
        ((to % HOURS) + HOURS) % HOURS
    )
}
