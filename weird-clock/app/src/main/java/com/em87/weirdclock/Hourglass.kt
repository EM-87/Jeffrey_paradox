package com.em87.weirdclock

/**
 * When turning the phone over turns the countdown over.
 *
 * The countdown is an hourglass: stand the phone on its head and the sand
 * runs back, so three minutes gone become three minutes left. It is a good
 * gesture and it was wired up badly, in a way that cost a real alarm twice
 * in one evening.
 *
 * Two things were wrong. It acted from any card that was not on the middle
 * row — so a countdown could be turned over from the stopwatch, or from a
 * screen where it was not even drawn, without anything on the display
 * changing. And it acted on the *instant* the phone passed upside down,
 * which a phone does on its way into a pocket. Set three minutes, put the
 * phone away, and the three minutes gone become nothing left: the alarm
 * goes off in your hand.
 *
 * So the rule lives here, out of the view, where it can be checked.
 */
object Hourglass {

    /**
     * How long the phone must be held upside down before the glass turns.
     *
     * A phone on its way into a pocket is upside down for a moment; a phone
     * being deliberately turned over is upside down until somebody turns it
     * back. Half a second tells them apart and is short enough that the
     * gesture still feels immediate.
     */
    const val HOLD_MS = 500L

    /**
     * Whether an upside-down phone should turn the glass.
     *
     * @param showing the card on screen, or null if none is
     * @param running whether there is a countdown to turn
     * @param heldMs how long the phone has been upside down
     */
    fun turns(showing: Card?, running: Boolean, heldMs: Long): Boolean {
        if (!running) return false
        if (heldMs < HOLD_MS) return false
        // Only where the sand is actually drawn. Both cards show the same
        // countdown — one as a dial, one as a glass — and turning it over
        // on either is a thing you can watch happen. Anywhere else it is an
        // invisible edit to a running timer.
        return showing == Card.REVERSE || showing == Card.HOURGLASS
    }

    /** What is left after the glass is turned: whatever had run through. */
    fun turned(totalMs: Long, remainingMs: Long): Long =
        (totalMs - remainingMs).coerceIn(0L, totalMs)
}
