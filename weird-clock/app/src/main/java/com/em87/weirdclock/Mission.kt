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

    // ---------------------------------------------------------- the sums

    /** How many rungs the ladder has. */
    const val LEVELS = 5

    /** The rung an alarm gets if nobody has said otherwise. */
    const val DEFAULT_LEVEL = 3

    /**
     * Something with a number for an answer.
     *
     * Written out rather than held as operands: by the top of the ladder
     * the question is not "a something b" at all — a root has one operand
     * and an equation has an unknown — and a shape that fits every rung is
     * a shape that fits none of them well.
     */
    data class Problem(val text: String, val answer: Int)

    /**
     * One to be going on with, at [level].
     *
     * Five rungs, and the whole ladder is a compromise between two ways of
     * failing. Too easy and it does not wake anybody, so the alarm is off
     * and you are asleep again. Too hard and the alarm becomes a thing you
     * dread — and a sum nobody can do at six in the morning is an alarm
     * that cannot be turned off at all, which is worse than one that can be
     * turned off too easily. So the bottom rung is arithmetic a child does
     * and the top is a minute's thought, not a puzzle.
     */
    fun problem(level: Int, random: Random = Random.Default): Problem =
        when (level.coerceIn(1, LEVELS)) {
            // Barely awake: single digits, no carrying.
            1 -> random.nextInt(2, 10).let { a ->
                val b = random.nextInt(2, 10)
                Problem("$a + $b", a + b)
            }
            // Two digits, with a carry to keep it honest.
            2 -> random.nextInt(13, 49).let { a ->
                val b = random.nextInt(13, 49)
                Problem("$a + $b", a + b)
            }
            // Out of the times tables most people have by heart.
            3 -> random.nextInt(3, 10).let { a ->
                val b = random.nextInt(12, 20)
                Problem("$a × $b", a * b)
            }
            // Both sides two digits: doable in the head, with effort.
            4 -> random.nextInt(12, 20).let { a ->
                val b = random.nextInt(12, 20)
                Problem("$a × $b", a * b)
            }
            // Roots and an unknown. Still whole numbers — an alarm is a bad
            // place to find out your answer was right to two decimals.
            else -> if (random.nextBoolean()) {
                val root = random.nextInt(11, 32)
                Problem("√${root * root}", root)
            } else {
                val x = random.nextInt(3, 20)
                val a = random.nextInt(2, 10)
                val b = random.nextInt(2, 30)
                Problem("${a}x + $b = ${a * x + b}", x)
            }
        }

    /**
     * Whether [typed] answers [problem].
     *
     * Blank is wrong rather than an error: the field starts empty and
     * pressing the button with nothing in it is a thing a half-asleep hand
     * does, and it should cost a new problem like any other wrong answer.
     */
    fun solved(problem: Problem, typed: String): Boolean =
        typed.trim().toIntOrNull() == problem.answer

    /** Any level outside the ladder is the middle of it. */
    fun level(stored: Int): Int =
        if (stored in 1..LEVELS) stored else DEFAULT_LEVEL

    // ----------------------------------------------------------- shaking

    /** How many shakes it takes. Enough to need sitting up for. */
    const val SHAKES_NEEDED = 15

    /**
     * Above this, in m/s², counts as a shake.
     *
     * Gravity alone is 9.81, so anything at rest reads about that however
     * it is lying — but tilting a phone briskly swings that vector about
     * and peaks well past it, and at sixteen a firm tilt was passing for a
     * shake. This is roughly two and a half g: a movement of the arm, not
     * of the wrist.
     */
    const val SHAKE_ON = 24f

    /** And it has to come back below this before the next one counts. */
    const val SHAKE_OFF = 12f

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
