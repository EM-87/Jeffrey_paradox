package com.em87.weirdclock

/**
 * When the clock takes the whole screen.
 *
 * Turning a digital clock on its side and standing it on the bedside table
 * is most of what a digital clock is for, and it is the one thing this app
 * did not do: rotate it and you got the same card with the same gear, the
 * same row of five buttons and the same digits a third of the way up, in a
 * window that is now half as tall. Everything in that sentence is the card
 * rather than the clock.
 *
 * So the card gets out of the way. Three things have to agree before it
 * does, and they are here rather than spread through the activity because
 * every one of them can change independently and the wrong answer is
 * invisible until somebody turns their phone over:
 *
 *  - the face has to be one that fills a screen. A dial is a round thing
 *    in a square window and gains nothing from losing its buttons;
 *  - the card showing has to be the clock. Somebody who rotates the phone
 *    on the alarm list wants a wider list, not a clock;
 *  - and the screen has to actually be wider than it is tall.
 *
 * It is deliberately not a preference. A setting for this would be a
 * setting for "do what I obviously meant".
 */
object Bedside {

    /** Whether the clock should take the screen, given all three. */
    fun wanted(face: Face, card: Card?, landscape: Boolean): Boolean =
        face.fills && card == Card.CLOCK && landscape

    /** Whether [width] and [height] describe a screen on its side. */
    fun landscape(width: Int, height: Int): Boolean = width > height

    /**
     * How long the buttons stay after a tap brings them back.
     *
     * Long enough to reach one, short enough that the clock is a clock
     * again by the time you have looked away and back.
     */
    const val CHROME_MS = 4000L
}
