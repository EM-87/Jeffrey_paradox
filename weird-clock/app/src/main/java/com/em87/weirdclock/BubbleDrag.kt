package com.em87.weirdclock

/**
 * Which finger owns one bubble, and where that finger has moved it.
 *
 * Two people pushing two bubbles about at once is the whole of air hockey,
 * and it was resting on a listener that read `rawX` — which is pointer
 * zero's position and nobody else's. One bubble each worked by luck,
 * because Android hands each child its own pointer; a second finger landing
 * on a bubble that already had one, or the first finger lifting while the
 * second stayed down, did not.
 *
 * Kept apart from the View so the bookkeeping can be driven without a
 * finger: a rule about which pointer owns what is exactly the sort of thing
 * that is easy to believe and impossible to see.
 */
internal class BubbleDrag {

    /** The pointer holding this bubble, or [NOBODY]. */
    var pointer: Int = NOBODY
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var lastAt = 0L

    val held: Boolean get() = pointer != NOBODY

    /**
     * A finger goes down. The first one to arrive keeps the bubble: a
     * second finger on a bubble that is already held is somebody reaching
     * for it mid-shove, and handing it over mid-stroke makes it jump.
     */
    fun down(id: Int, x: Float, y: Float, atMs: Long): Boolean {
        if (held) return false
        pointer = id
        lastX = x
        lastY = y
        lastAt = atMs
        return true
    }

    /**
     * A finger moves. Ignored unless it is *this* bubble's finger — which
     * is the whole point: with two bubbles held at once, every move event
     * carries both, and taking the first set of coordinates in the event
     * would have each bubble following whichever finger went down first.
     */
    fun move(id: Int, x: Float, y: Float, atMs: Long): Step? {
        if (id != pointer) return null
        val dt = (atMs - lastAt).coerceAtLeast(1L) / 1000f
        val step = Step(x - lastX, y - lastY, (x - lastX) / dt, (y - lastY) / dt)
        lastX = x
        lastY = y
        lastAt = atMs
        return step
    }

    /** Lets go, if it is this bubble's finger lifting. */
    fun up(id: Int): Boolean {
        if (id != pointer) return false
        pointer = NOBODY
        return true
    }

    /** Everything lets go — the gesture was taken away from us. */
    fun cancel() {
        pointer = NOBODY
    }

    /** How far the finger moved, and how fast it was going. */
    class Step(val dx: Float, val dy: Float, val vx: Float, val vy: Float)

    companion object {
        const val NOBODY = -1
    }
}
