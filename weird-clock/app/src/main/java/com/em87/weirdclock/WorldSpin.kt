package com.em87.weirdclock

/**
 * What the earth does after you let go of it.
 *
 * The hands of the dial are light things on a spring: push one and it goes
 * back where it was, quickly, and that is right for a piece of brass a
 * centimetre long. The planet is not a piece of brass. Shoved hard it
 * should keep going — that is the one property of it everybody knows — and
 * only then find its way back to the time it is.
 *
 * So this is a flywheel on a spring rather than a hand on one, and the two
 * differ in exactly one place: whether the spring is connected while the
 * thing is still moving. A hand's is, and pulls proportionally, which from
 * a quarter of a turn away is an enormous force and is why a hand snaps
 * back. The world's is not: shoved, it simply turns, losing a little every
 * second to the air until it is slow enough for the spring to catch it —
 * so a hard flick coasts, slows, finds somewhere to stop and *then* comes
 * home with a bounce.
 *
 * Pure and in its own file because the whole of the feel is four numbers,
 * and four numbers that can be measured are four numbers that can be got
 * right. Degrees and seconds throughout; [HemisphereView] does the
 * converting, since a degree of turn is four minutes of clock — see
 * [Hemisphere.MS_PER_DEGREE].
 */
object WorldSpin {

    /**
     * Where the world is and how fast it is going, both measured against
     * the time it actually is.
     *
     * [degrees] is how far it has been turned off now, and [rate] is in
     * degrees a second. Positive is the direction a finger dragging
     * clockwise sends it, which is the direction the world turns.
     */
    data class State(
        val degrees: Double = 0.0,
        val rate: Double = 0.0,
        val coasting: Boolean = false
    )

    /**
     * How hard the spring pulls, in degrees per second squared for each
     * degree it is wound off, and what settles it.
     *
     * These two are a proper damped spring and they are what the world
     * comes home on: [SETTLING] is a little under critical for [PULL], so
     * it arrives, goes a touch past, and rocks into place rather than
     * stopping dead. That part is exactly what a clock hand does, and it
     * is right for the last few degrees of anything.
     */
    const val PULL = 8.0
    const val SETTLING = 2.6

    /**
     * And the air, which is the only thing acting on it while it is still
     * going round.
     *
     * The spring is not connected while the world is coasting — that is
     * the whole of what makes this a planet rather than a hand. A hand's
     * spring pulls all the way and pulls proportionally, so a hand shoved
     * a quarter of a turn is a hand under an enormous force and comes back
     * in the same fifth of a second whatever you did to it. Shove the
     * world and nothing pulls at all: it simply turns, losing a little
     * every second to this, until it is slow enough for the spring to
     * catch it.
     */
    const val COASTING = 1.1

    /** Where the air gives way to the spring, in degrees a second. */
    const val HANDOVER = 90.0

    /**
     * The fastest it may be going, in degrees a second.
     *
     * Four turns a second. Not for the physics — nothing here diverges —
     * but for the picture: past about this the disc is a strobe and the
     * next thing to happen is somebody wondering what day it is now.
     */
    const val TOP_RATE = 1440.0

    /** Near enough to home, and slow enough, to call it home. */
    const val STILL_DEG = 0.5
    const val STILL_RATE = 3.0

    /**
     * The longest step it will take in one go.
     *
     * A frame that arrives late — a garbage collection, a card being
     * built — would otherwise integrate a tenth of a second in one jump
     * and throw the world across the screen. Clamped, a slow frame makes
     * the world turn slightly slowly, which nobody can see.
     */
    const val STEP_CAP = 1.0 / 30.0

    /** A world let go of at this rate, from this far off the time it is. */
    fun let(degrees: Double, rate: Double): State = State(
        degrees,
        rate.coerceIn(-TOP_RATE, TOP_RATE),
        coasting = kotlin.math.abs(rate) > HANDOVER
    )

    /** One frame of it. */
    fun step(state: State, dtSec: Double): State {
        if (dtSec <= 0.0) return state
        val dt = dtSec.coerceAtMost(STEP_CAP)
        var coasting = state.coasting
        var rate = if (coasting) {
            // Air alone. Once it has slowed enough the spring takes it,
            // and it does not hand back: coming home from half a turn away
            // the spring itself goes faster than this threshold, and a
            // world that started coasting again there would never arrive.
            val slowed = state.rate - COASTING * state.rate * dt
            if (kotlin.math.abs(slowed) <= HANDOVER) coasting = false
            slowed
        } else {
            state.rate + (-PULL * state.degrees - SETTLING * state.rate) * dt
        }
        rate = rate.coerceIn(-TOP_RATE, TOP_RATE)
        return State(state.degrees + rate * dt, rate, coasting)
    }

    /** Whether it has arrived, and can stop being stepped. */
    fun resting(state: State): Boolean =
        !state.coasting &&
            kotlin.math.abs(state.degrees) < STILL_DEG &&
            kotlin.math.abs(state.rate) < STILL_RATE
}
