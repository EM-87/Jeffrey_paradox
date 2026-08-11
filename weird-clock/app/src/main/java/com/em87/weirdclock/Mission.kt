package com.em87.weirdclock

import kotlin.random.Random

/**
 * Something to get right before the alarm will stop.
 *
 * The slide-to-stop asks for a deliberate gesture, which is the most a
 * phone can ask of a hand that is only swatting at it. It is still a thing
 * a sleeping person can do, and it is meant to be — but somebody who keeps
 * turning their alarm off and going back to sleep needs the stop to cost
 * something a sleeping brain cannot pay.
 *
 * So the mission replaces the slider rather than sitting beside it. Snooze
 * stays a plain button: snooze is not the thing being defended against.
 */
object Mission {

    const val NONE = "none"
    const val MATHS = "maths"
    const val SHAKE = "shake"

    // ------------------------------------------------------------- sums

    /**
     * A sum, and the answer it wants.
     *
     * Multiplication rather than addition, and out of the times tables
     * most people have by heart: the point is a few seconds of actual
     * thinking, and 6 + 9 is not thinking, it is reading.
     */
    data class Sum(val a: Int, val b: Int) {
        val answer: Int get() = a * b

        fun text(): String = "$a × $b"
    }

    /**
     * One to be going on with.
     *
     * Both ends bounded: too easy and it does not wake anybody, too hard
     * and the alarm becomes a thing you dread rather than a thing that
     * works — and a sum nobody can do at six in the morning is an alarm
     * that cannot be turned off, which is worse than one that can be
     * turned off too easily.
     */
    fun sum(random: Random = Random.Default): Sum =
        Sum(random.nextInt(3, 10), random.nextInt(12, 20))

    /**
     * Whether [typed] answers [sum].
     *
     * Blank is wrong rather than an error: the field starts empty and
     * pressing the button with nothing in it is a thing a half-asleep hand
     * does, and it should cost a new sum like any other wrong answer.
     */
    fun solved(sum: Sum, typed: String): Boolean =
        typed.trim().toIntOrNull() == sum.answer

    // ----------------------------------------------------------- shaking

    /** How many shakes it takes. Enough to need sitting up for. */
    const val SHAKES_NEEDED = 15

    /**
     * Above this, in m/s², counts as a shake. Gravity alone is 9.81, so
     * anything at rest reads about that however it is lying.
     */
    const val SHAKE_ON = 16f

    /** And it has to come back below this before the next one counts. */
    const val SHAKE_OFF = 11.5f

    /**
     * Counts shakes, with a gap in the middle of the two thresholds.
     *
     * One threshold would not do. The accelerometer is sampled many times
     * a second, so a single swing spends a dozen readings above any line
     * you draw and would count as a dozen shakes — a phone waved once
     * would finish the mission. Coming back down below a lower line before
     * the next one counts is what makes a shake a *shake*: an up and a
     * down, not a moment of being fast.
     */
    class Shakes(val needed: Int = SHAKES_NEEDED) {

        var count = 0
            private set

        /** True while the phone is still moving from the last one. */
        private var swinging = false

        /** Feeds one reading; returns true once the mission is finished. */
        fun feed(magnitude: Float): Boolean {
            if (!swinging && magnitude >= SHAKE_ON) {
                swinging = true
                if (count < needed) count++
            } else if (swinging && magnitude <= SHAKE_OFF) {
                swinging = false
            }
            return count >= needed
        }

        /** How far along, 0 to 1. */
        fun progress(): Float = (count.toFloat() / needed).coerceIn(0f, 1f)
    }

    /** The length of an acceleration reading, gravity included. */
    fun magnitude(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt(x * x + y * y + z * z)

    /**
     * Whether [stored] names a mission that has to be passed.
     *
     * Anything unrecognised is no mission at all. A setting written by a
     * later version, or a backup restored from one, must not leave an
     * alarm that cannot be turned off by any means the phone in front of
     * you has.
     */
    fun required(stored: String?): String = when (stored) {
        MATHS, SHAKE -> stored
        else -> NONE
    }

    /** True when there is one, which is what most callers want to know. */
    fun any(stored: String?): Boolean = required(stored) != NONE
}
