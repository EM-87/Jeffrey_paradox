package com.em87.weirdclock

import android.os.SystemClock
import android.view.animation.Interpolator

/**
 * How far the sky has been wound from now, and how it travels there.
 *
 * Six numbers and one rule, which lived in [ClockView] among six thousand
 * lines about drawing a clock. They are not about drawing anything: they
 * are a position, a journey, and the easing between them, and the only
 * reason they were in the view is that the view is where the finger lands.
 *
 * Out here they can be asked questions directly. The interesting one is
 * the difference between where the sky *is* and where it is *going*, which
 * is the whole of why pressing the date quickly used to jam: every press
 * asked what came after the planets' current positions, found the event it
 * was already travelling to, and set off for it again. [targetOffsetMs] is
 * the answer to the other question, and everything that decides where to
 * go next asks that one.
 */
class SkyWinding(private val easing: Interpolator, private val travelMs: Float) {

    /** The sentinel for "not travelling", which is not zero — see [ClockView]. */
    private val never = Long.MIN_VALUE

    private var offsetMs = 0L
    private var fromMs = 0L
    private var toMs = 0L
    private var startedAt = never

    /**
     * How far the sky is wound at this instant — the whole offset, or what
     * is left of it while it runs.
     *
     * Reading this is what retires a finished journey. That is deliberate
     * and it is the reason there is no separate "has it arrived" tick: the
     * dial asks where the sky is on every frame it draws, so the journey
     * ends the first time anybody looks after it should have.
     */
    fun windBack(): Long {
        if (startedAt == never) return offsetMs
        val t = ((SystemClock.uptimeMillis() - startedAt) / travelMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            offsetMs = toMs
            startedAt = never
            return offsetMs
        }
        val eased = easing.getInterpolation(t)
        // In floating point: the two ends can be centuries apart in
        // milliseconds, and a long multiplied by an eased fraction the
        // integer way is a long that has already overflowed.
        return fromMs + ((toMs - fromMs).toDouble() * eased).toLong()
    }

    /**
     * Sets off for [offset], and says whether that was a journey at all.
     *
     * False when it is already there, so a caller can decline to make a
     * noise about going nowhere.
     */
    fun glideTo(offset: Long): Boolean {
        val from = windBack()
        if (from == offset) return false
        fromMs = from
        toMs = offset
        offsetMs = from
        startedAt = SystemClock.uptimeMillis()
        return true
    }

    /** Whether the sky is on its way somewhere. */
    fun travelling(): Boolean = startedAt != never

    /**
     * Where the sky is heading, which is where it is when it is standing
     * still.
     *
     * Everything that decides *where to go next* asks this rather than
     * [windBack], so that a press landing part way through a journey adds
     * to it instead of arguing with it.
     */
    fun targetOffsetMs(): Long = if (startedAt == never) offsetMs else toMs

    /** Stops wherever it has got to, and stays there. */
    fun stopHere() {
        if (startedAt == never) return
        offsetMs = windBack()
        startedAt = never
    }

    /** Back to now, at once: closing the sky is not a journey. */
    fun reset() {
        offsetMs = 0L
        startedAt = never
    }

    /** Winds by [ms] without travelling — a finger on a planet moves it directly. */
    fun nudge(ms: Long) {
        offsetMs += ms
    }

    /**
     * For the tests: finishes whatever journey is under way, at once.
     *
     * By winding the clock back rather than by setting the offset, so that
     * the arriving happens in [windBack] and nowhere else. It did have its
     * own copy of the arriving, and that copy hid a sabotage: a real
     * journey made to end at the wrong end changed nothing any test could
     * see, because every test that cared came through here.
     */
    fun settleForTest() {
        if (startedAt == never) return
        startedAt -= (travelMs.toLong() + 1L)
        windBack()
    }
}
