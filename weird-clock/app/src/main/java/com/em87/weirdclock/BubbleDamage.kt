package com.em87.weirdclock

/**
 * What being knocked about does to a clock.
 *
 * The app has had an escalating-damage ladder since the beginning — a
 * movement seizes up or starts running backwards, then the hands come off —
 * and the only thing that ever climbed it was shaking the whole phone. A
 * bubble could be slammed into a wall, batted off the main dial and
 * ricocheted off its neighbours all afternoon and keep perfect time.
 *
 * The order is the point. A struck clock does not simply break: first it
 * loses its sense of direction, and only later does anything fall off. And
 * losing your sense of direction is two different things — a movement that
 * gives up, and one that runs the other way — because half of each is far
 * stranger than all of either.
 *
 * Kept apart from the physics so the ladder can be climbed a rung at a time
 * without throwing anything at anything: which knock does what is a rule,
 * and rules are for measuring.
 */
internal class BubbleDamage {

    enum class Effect {
        /** The movement gives up: the hands stop where they are. */
        SEIZE,

        /** Or it runs the other way, which is worse and better. */
        REVERSE,

        /** And past that, things come off. */
        BREAK
    }

    /** How many knocks worth counting this clock has taken. */
    var bruises = 0
        private set

    /**
     * Takes a knock of [force] — a closing speed in pixels per second —
     * and says what it did, or null for nothing at all.
     *
     * [seizes] decides which way a rattled movement goes wrong, passed in
     * rather than tossed here: a coin is exactly the sort of thing that
     * makes a rule impossible to test.
     */
    fun hit(force: Float, seizes: Boolean): Effect? {
        // A cushion tap is not a knock. Without a floor, a bubble resting
        // against an edge would grind itself to pieces on contact noise.
        if (force < ENOUGH_TO_COUNT) return null
        bruises++
        return when {
            bruises == SENSE_OF_DIRECTION -> if (seizes) Effect.SEIZE else Effect.REVERSE
            bruises >= THINGS_COME_OFF -> Effect.BREAK
            else -> null
        }
    }

    fun heal() {
        bruises = 0
    }

    companion object {
        /** Below this it is a nudge, whatever it sounded like. */
        const val ENOUGH_TO_COUNT = 320f

        /** Knocks before the movement loses its way. */
        const val SENSE_OF_DIRECTION = 3

        /** And before the hands give up holding on. */
        const val THINGS_COME_OFF = 6
    }
}
