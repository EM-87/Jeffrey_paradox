package com.em87.weirdclock

/**
 * Whether the clock's rim stops a bubble, or lets it through.
 *
 * The dial used to be a boulder: everything bounced off it, so nothing
 * could ever reach the hands sweeping round inside. Opening it altogether
 * would lose what the rim is good at — a bubble flung across the screen
 * cannoning off the clock and ringing it like a bell.
 *
 * So it is one-way. Push a bubble in with a finger and it goes in. Throw
 * one at the clock and it bounces. Once inside, it may leave whenever it
 * likes. A membrane rather than a wall, which also happens to describe how
 * you would get a ball onto a table: you place it, you do not fire it
 * through the side.
 */
internal object DialMembrane {

    enum class Verdict {
        /** Bounce it: it came at the clock from outside, on its own. */
        BOUNCE,

        /** Let it through: a finger is carrying it, or it is already in. */
        PASS
    }

    /**
     * [centreDistance] is from the middle of the dial to the middle of the
     * bubble, and [dialRadius] the rim. [dragged] is whether a finger has
     * hold of it.
     */
    fun verdict(centreDistance: Float, dialRadius: Float, dragged: Boolean): Verdict {
        // A finger outranks the rim in both directions: whatever you are
        // holding goes where you put it.
        if (dragged) return Verdict.PASS
        // Already inside, by whatever means it got there. Leaving is always
        // allowed, or a bubble placed on the face would be trapped there
        // for good — and being unable to take your ball back is a worse
        // toy than being unable to throw it in.
        if (centreDistance < dialRadius) return Verdict.PASS
        return Verdict.BOUNCE
    }
}
