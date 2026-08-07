package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What being knocked about does to a clock.
 *
 * The ladder — a movement seizes up or starts running backwards, then the
 * hands come off — has been there since the beginning, and the only thing
 * that ever climbed it was shaking the whole phone. A bubble could be
 * slammed into a wall, batted off the main dial and ricocheted off its
 * neighbours all afternoon and keep perfect time.
 */
class BubbleDamageTest {

    private val hard = BubbleDamage.ENOUGH_TO_COUNT * 2f

    @Test
    fun `a nudge is not a knock`() {
        val damage = BubbleDamage()
        repeat(20) { assertNull(damage.hit(BubbleDamage.ENOUGH_TO_COUNT - 1f, seizes = true)) }
        assertEquals("nothing counted", 0, damage.bruises)
    }

    /**
     * Without a floor a bubble resting against an edge would grind itself
     * to pieces on contact noise — the cushion fires on every frame it
     * spends touching the wall.
     */
    @Test
    fun `and a clock leaning on a cushion never breaks`() {
        val damage = BubbleDamage()
        repeat(500) { damage.hit(12f, seizes = false) }
        assertEquals(0, damage.bruises)
    }

    /** The first thing to go is the sense of direction, not a part. */
    @Test
    fun `a few knocks and the movement loses its way`() {
        val damage = BubbleDamage()
        val effects = (1..BubbleDamage.SENSE_OF_DIRECTION).map { damage.hit(hard, seizes = true) }
        assertTrue("too early is as wrong as never", effects.dropLast(1).all { it == null })
        assertEquals(BubbleDamage.Effect.SEIZE, effects.last())
    }

    /**
     * Two ways to lose it, and which one is decided outside: half stopping
     * and half running backwards is far stranger than all of either, and a
     * coin tossed in here would make the rule untestable.
     */
    @Test
    fun `and it can lose it in either direction`() {
        val stopped = BubbleDamage()
        val backwards = BubbleDamage()
        var lastStopped: BubbleDamage.Effect? = null
        var lastBackwards: BubbleDamage.Effect? = null
        repeat(BubbleDamage.SENSE_OF_DIRECTION) {
            lastStopped = stopped.hit(hard, seizes = true)
            lastBackwards = backwards.hit(hard, seizes = false)
        }
        assertEquals(BubbleDamage.Effect.SEIZE, lastStopped)
        assertEquals(BubbleDamage.Effect.REVERSE, lastBackwards)
    }

    /** Keep hitting it and things come off, which is the rung after. */
    @Test
    fun `keep at it and the hands come off`() {
        val damage = BubbleDamage()
        var last: BubbleDamage.Effect? = null
        repeat(BubbleDamage.THINGS_COME_OFF) { last = damage.hit(hard, seizes = true) }
        assertEquals(BubbleDamage.Effect.BREAK, last)
    }

    /** In that order: nothing falls off a clock that still knows the time. */
    @Test
    fun `the order is the point`() {
        assertTrue(
            "a clock loses its way before it loses a hand",
            BubbleDamage.SENSE_OF_DIRECTION < BubbleDamage.THINGS_COME_OFF
        )
    }

    @Test
    fun `and putting everything back forgives it`() {
        val damage = BubbleDamage()
        repeat(BubbleDamage.THINGS_COME_OFF) { damage.hit(hard, seizes = true) }
        damage.heal()
        assertEquals(0, damage.bruises)
        assertNull("it starts again from nothing", damage.hit(hard, seizes = true))
    }
}
