package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two fingers, two bubbles, at the same time.
 *
 * The whole of air hockey is two people shoving two things about at once,
 * and it was resting on a touch listener that read `rawX` — which is
 * pointer zero's position and nobody else's. One bubble each happened to
 * work, because Android hands each child its own pointer; everything past
 * that did not.
 *
 * Pure bookkeeping, tested without a finger: which pointer owns what is
 * exactly the sort of rule that is easy to believe and impossible to see.
 */
class BubbleDragTest {

    @Test
    fun `the first finger to arrive takes the bubble`() {
        val drag = BubbleDrag()
        assertFalse(drag.held)
        assertTrue(drag.down(id = 7, x = 0f, y = 0f, atMs = 0L))
        assertTrue(drag.held)
        assertEquals(7, drag.pointer)
    }

    /**
     * And keeps it. A second finger landing on a bubble already being
     * shoved is somebody reaching for it mid-stroke; handing it over would
     * make it jump to the new finger.
     */
    @Test
    fun `a second finger on the same bubble does not steal it`() {
        val drag = BubbleDrag()
        drag.down(7, 0f, 0f, 0L)
        assertFalse(drag.down(9, 500f, 500f, 10L))
        assertEquals(7, drag.pointer)
    }

    /**
     * The reason all of this exists: every pointer in a gesture arrives in
     * the same event, so a bubble has to answer only to its own. Taking
     * whatever coordinates came first is what would have two bubbles both
     * chasing the finger that went down first.
     */
    @Test
    fun `a bubble ignores a finger that is not its own`() {
        val drag = BubbleDrag()
        drag.down(7, 0f, 0f, 0L)
        assertNull("that is the other player", drag.move(9, 300f, 0f, 16L))
        assertNotNull(drag.move(7, 40f, 0f, 16L))
    }

    @Test
    fun `a move reports how far it went and how fast`() {
        val drag = BubbleDrag()
        drag.down(1, 100f, 100f, 0L)
        val step = drag.move(1, 132f, 84f, 16L)!!
        assertEquals(32f, step.dx, 0.001f)
        assertEquals(-16f, step.dy, 0.001f)
        // Sixteen milliseconds for thirty-two pixels is two thousand a second.
        assertEquals(2000f, step.vx, 1f)
        assertEquals(-1000f, step.vy, 1f)
    }

    /** Each move is measured from the last, not from where the finger landed. */
    @Test
    fun `and each step is measured from the one before it`() {
        val drag = BubbleDrag()
        drag.down(1, 0f, 0f, 0L)
        drag.move(1, 10f, 0f, 16L)
        val second = drag.move(1, 25f, 0f, 32L)!!
        assertEquals(15f, second.dx, 0.001f)
    }

    /**
     * One finger lifting while the other stays down is the ordinary way a
     * two-player rally ends, and the bubble whose finger is still there
     * must not be let go of with it.
     */
    @Test
    fun `only its own finger lifting lets go`() {
        val drag = BubbleDrag()
        drag.down(7, 0f, 0f, 0L)
        assertFalse("the other player let go, not this one", drag.up(9))
        assertTrue(drag.held)
        assertTrue(drag.up(7))
        assertFalse(drag.held)
    }

    /** And once it has let go it is free for the next finger. */
    @Test
    fun `a released bubble can be picked up again`() {
        val drag = BubbleDrag()
        drag.down(7, 0f, 0f, 0L)
        drag.up(7)
        assertTrue(drag.down(9, 0f, 0f, 100L))
        assertEquals(9, drag.pointer)
    }

    /** A cancelled gesture drops it whoever was holding it. */
    @Test
    fun `a cancelled gesture drops the bubble`() {
        val drag = BubbleDrag()
        drag.down(7, 0f, 0f, 0L)
        drag.cancel()
        assertFalse(drag.held)
        assertNull(drag.move(7, 50f, 0f, 16L))
    }
}
