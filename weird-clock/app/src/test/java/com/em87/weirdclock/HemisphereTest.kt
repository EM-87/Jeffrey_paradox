package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The earth as the clock, checked without one.
 *
 * A projection is the kind of code where a sign is either right or puts
 * every morning in the afternoon, and nothing on screen will tell you
 * which — a mirrored world looks exactly as plausible as a correct one
 * until somebody who knows where Japan is looks at it. So the claims here
 * are the ones that can be stated in words: where the pole goes, where
 * noon goes, which way round the thing turns, and that projecting a place
 * and unprojecting it again gives the place back.
 */
class HemisphereTest {

    private fun at(view: Hemisphere.View, lat: Double, lon: Double, sub: Double = 0.0) =
        Hemisphere.project(view, lat, lon, sub, 0.0)

    /**
     * The pole is the middle, the equator is the rim, and the half of the
     * world behind is behind.
     *
     * This is the change the face was asked for and the reason it needed
     * asking for. It used to run every distance from the pole to scale —
     * pole in the middle, equator halfway out, far pole smeared round the
     * whole rim — so a north-polar view had South Africa in it, which is
     * not a thing anybody has ever seen. It is orthographic now: how far
     * out a place lands is how far it stands from the earth's axis, which
     * is the cosine of its latitude, and it crowds towards the rim the
     * way a football does.
     */
    @Test
    fun `the pole is the middle and the equator is the rim`() {
        val north = at(Hemisphere.View.NORTH, 90.0, 0.0)
        assertEquals("the pole is not in the middle", 0.0, hypot(north[0], north[1]), 0.0001)
        assertEquals(
            "the equator is not the rim",
            1.0, hypot(at(Hemisphere.View.NORTH, 0.0, 0.0)[0], 0.0), 0.0001
        )
        // Halfway out is thirty degrees of latitude from the rim, not
        // forty-five: that is the whole difference between a ball and a
        // ruler, and it is what a linear squeeze got wrong.
        assertEquals(
            "the squeeze is even, which no round thing is",
            0.5, hypot(at(Hemisphere.View.NORTH, 60.0, 0.0)[0], 0.0), 0.0001
        )
        // And the far half is behind the near half rather than round the
        // edge of it. Same third number as the globe has always used.
        assertTrue(
            "the southern hemisphere is on the front of the north view",
            at(Hemisphere.View.NORTH, -30.0, 0.0)[2] < 0.0
        )
        assertTrue(
            "the northern hemisphere is on the front of the south view",
            at(Hemisphere.View.SOUTH, 30.0, 0.0)[2] < 0.0
        )
        // And upside down for the other one.
        assertEquals(
            0.0,
            hypot(
                at(Hemisphere.View.SOUTH, -90.0, 0.0)[0],
                at(Hemisphere.View.SOUTH, -90.0, 0.0)[1]
            ),
            0.0001
        )
    }

    /**
     * And the map painted into the disc never reaches the other half.
     *
     * The projection is one thing and the painting is another: the disc
     * is filled by asking, for every pixel of it, which place is there.
     * Under the old rule that question answered "Cape Town" a little way
     * in from the rim of a *north* polar view, and the picture agreed —
     * which is how somebody looking at it noticed. This walks the whole
     * disc and asks whether any point of it comes back from the far
     * hemisphere.
     */
    @Test
    fun `the inverse never lands on the far side`() {
        for ((view, sign) in listOf(Hemisphere.View.NORTH to 1.0, Hemisphere.View.SOUTH to -1.0)) {
            var seen = 0
            var y = -1.0
            while (y <= 1.0) {
                var x = -1.0
                while (x <= 1.0) {
                    val place = Hemisphere.unproject(view, x, y, 0.0)
                    if (place != null) {
                        seen++
                        assertTrue(
                            "$view has the other hemisphere in it, at ($x, $y): ${place[0]}",
                            sign * place[0] >= -0.0001
                        )
                    }
                    x += 0.01
                }
                y += 0.01
            }
            assertTrue("$view painted nothing at all", seen > 20000)
        }
    }

    /** And nobody is left off the map, because there are two of them. */
    @Test
    fun `the view follows the pole you are standing under`() {
        assertEquals(Hemisphere.View.SOUTH, Hemisphere.defaultView(-33.9))
        assertEquals(Hemisphere.View.NORTH, Hemisphere.defaultView(40.4))
        assertEquals(Hemisphere.View.NORTH, Hemisphere.defaultView(0.0))
    }

    /**
     * Noon is on the right, which is where the sun is nailed.
     *
     * Everything on this face is measured from the sun rather than from
     * Greenwich, because the sun is the thing that is not moving. A place
     * at the subsolar longitude is at three o'clock on the disc whatever
     * the actual longitude is.
     */
    @Test
    fun `the place under the sun is on the right`() {
        for (sub in listOf(-170.0, -30.0, 0.0, 95.0, 179.0)) {
            for (view in Hemisphere.View.entries) {
                val here = Hemisphere.project(view, 0.0, sub, sub, 0.0)
                assertTrue("$view put noon on the wrong side", here[0] > 0.4)
                assertEquals("$view put noon off the equator line", 0.0, here[1], 0.0001)
            }
        }
    }

    /**
     * And the sun can be moved, which is the one thing on this face that
     * is a preference.
     */
    @Test
    fun `the sun can be put anywhere round the rim`() {
        val right = Hemisphere.project(Hemisphere.View.NORTH, 0.0, 0.0, 0.0, 0.0)
        val top = Hemisphere.project(Hemisphere.View.NORTH, 0.0, 0.0, 0.0, 90.0)
        assertTrue("noon did not move", right[0] > 0.4)
        assertTrue("the sun did not go to the top", top[1] < -0.4)
        assertEquals(0.0, top[0], 0.0001)
    }

    /**
     * Seen from above the north pole the earth turns anticlockwise, and
     * from underneath it turns the other way.
     *
     * This is the sign that would look perfectly fine mirrored and put
     * every morning in the afternoon. It is also why clocks go clockwise:
     * the first ones copied a shadow in the northern hemisphere, which is
     * the same rotation seen from the other side.
     */
    @Test
    fun `the world turns anticlockwise over the north pole and the other way over the south`() {
        // A place an hour east of the sun — which is an hour past noon
        // there — should be *above* the noon line seen from the north,
        // since anticlockwise on a screen with y downwards is upwards on
        // the right-hand side.
        val north = at(Hemisphere.View.NORTH, 0.0, 15.0)
        assertTrue("the north view turns the wrong way", north[1] < 0.0)
        val south = at(Hemisphere.View.SOUTH, 0.0, 15.0)
        assertTrue("the south view turns the wrong way", south[1] > 0.0)
    }

    /**
     * Every point on the disc comes back to the place it was drawn from.
     *
     * The inverse is what paints the earth into the circle, so the two
     * have to agree exactly or the map is drawn shifted from the marks on
     * top of it — which is a bug that looks like a slightly wrong map.
     */
    @Test
    fun `projecting a place and unprojecting it gives the place back`() {
        for (view in Hemisphere.View.entries) {
            for (lat in listOf(-80.0, -40.0, -5.0, 0.0, 12.0, 51.0, 85.0)) {
                for (lon in listOf(-175.0, -90.0, -20.0, 0.0, 45.0, 120.0, 178.0)) {
                    val on = Hemisphere.project(view, lat, lon, 0.0, 0.0)
                    // The globe hides half the world, and a place behind
                    // it has no point on the disc to come back from.
                    if (on[2] < 0.0) continue
                    val back = Hemisphere.unproject(view, on[0], on[1], 0.0)
                    assertNotNull("$view lost ($lat, $lon)", back)
                    assertEquals("$view moved the latitude", lat, back!![0], 0.02)
                    assertEquals(
                        "$view moved the longitude",
                        0.0, abs(Hemisphere.wrap(lon - back[1])), 0.05
                    )
                }
            }
        }
    }

    /** Nothing outside the circle is anywhere on earth. */
    @Test
    fun `off the disc is nowhere`() {
        for (view in Hemisphere.View.entries) {
            assertNull(Hemisphere.unproject(view, 1.2, 0.0, 0.0))
            assertNull(Hemisphere.unproject(view, 0.8, 0.8, 0.0))
        }
    }

    /**
     * It is daylight under the sun and dark on the other side of the
     * world, and the line between is ninety degrees away.
     */
    @Test
    fun `the sun is up under the sun and down behind it`() {
        assertTrue(Hemisphere.isLit(0.0, 0.0, 0.0))
        assertFalse(Hemisphere.isLit(0.0, 180.0, 0.0))
        assertEquals(1.0, Hemisphere.cosZenith(0.0, 0.0, 0.0), 0.0001)
        assertEquals(0.0, Hemisphere.cosZenith(0.0, 90.0, 0.0), 0.0001)
        // At midsummer the north pole is lit all the way round and the
        // south pole is dark all the way round, which is the one fact
        // about the terminator everybody already knows.
        for (lon in listOf(0.0, 90.0, 180.0, -90.0)) {
            assertTrue("the midnight sun went out", Hemisphere.isLit(89.0, lon, 23.4))
            assertFalse("the polar night lit up", Hemisphere.isLit(-89.0, lon, 23.4))
        }
    }

    /**
     * The terminator really is the line where the sun sets.
     *
     * Walked round rather than solved, so what is worth checking is that
     * every point of it is on the edge of the light — which is the one
     * property the walk could get wrong without looking wrong.
     */
    @Test
    fun `every point of the terminator has the sun on the horizon`() {
        for (sub in listOf(-23.4, -10.0, 0.0, 15.0, 23.4)) {
            val ring = Hemisphere.terminator(Hemisphere.View.NORTH, sub, 0.0, points = 36)
            assertEquals(36 * 3, ring.size)
            var seen = 0
            for (i in 0 until 36) {
                // Only the half of it that is in front. The view is a
                // hemisphere now, so the terminator goes round the back
                // like everything else, and a point back there comes out
                // of the inverse as its own reflection on the near side —
                // which is a different place, with the sun somewhere
                // else.
                if (ring[i * 3 + 2] < 0.0) continue
                seen++
                val back = Hemisphere.unproject(
                    Hemisphere.View.NORTH, ring[i * 3], ring[i * 3 + 1], 0.0
                )
                assertNotNull(back)
                assertEquals(
                    "a point of the terminator is not on the horizon",
                    0.0, Hemisphere.cosZenith(back!![0], back[1], sub), 0.02
                )
            }
            assertTrue("no terminator on the near side at all", seen > 8)
        }
    }

    /**
     * The ring of hours, and the fact that it is the whole clock.
     *
     * The sun is at noon and the world turns fifteen degrees an hour, so
     * a quarter of the way round is six hours. Which way round depends on
     * which pole you are over, and getting it backwards would put every
     * morning in the afternoon with nothing on screen to say so.
     */
    @Test
    fun `the ring of hours is fifteen degrees each and runs with the world`() {
        assertEquals(12.0, Hemisphere.hourAt(Hemisphere.View.NORTH, 0.0), 0.0001)
        assertEquals(18.0, Hemisphere.hourAt(Hemisphere.View.NORTH, 90.0), 0.0001)
        assertEquals(6.0, Hemisphere.hourAt(Hemisphere.View.NORTH, -90.0), 0.0001)
        assertEquals(6.0, Hemisphere.hourAt(Hemisphere.View.SOUTH, 90.0), 0.0001)
        // And the two directions agree with each other.
        for (view in Hemisphere.View.entries) {
            for (hour in 0..23) {
                assertEquals(
                    "$view disagrees with itself at $hour",
                    hour.toDouble(),
                    ((Hemisphere.hourAt(view, Hemisphere.bearingOfHour(view, hour)) + 24.0) % 24.0),
                    0.0001
                )
            }
        }
    }

    /**
     * The marks are meridians and not time zones, and there are
     * twenty-four of them.
     *
     * A time zone map has a hundred and thirty-eight edges in it and most
     * of them follow a river; a turning globe cannot honestly draw those.
     * What it can draw is the fifteen-degree meridians the zones were
     * meant to be.
     */
    @Test
    fun `there are twenty-four meridians, fifteen degrees apart`() {
        assertEquals(24, Hemisphere.meridians().size)
        assertTrue(Hemisphere.onAMeridian(0.0))
        assertTrue(Hemisphere.onAMeridian(15.0))
        assertTrue(Hemisphere.onAMeridian(-45.2))
        assertFalse(Hemisphere.onAMeridian(7.5))
        assertFalse(Hemisphere.onAMeridian(22.0))
    }

    /**
     * With the compass on, the sun on screen is the sun in the sky.
     *
     * Screen up is wherever the phone's top is pointing. So facing north
     * with the sun due east puts it on the right; facing east with the sun
     * ahead puts it at the top; and turning the phone turns the picture
     * under it, which is the whole point of holding it flat.
     */
    @Test
    fun `pointing the phone puts the sun where the sun is`() {
        // Facing north, sun due east: the sun is to the right, which is
        // where this face nails it by default.
        assertEquals(0.0, Hemisphere.sunAtFrom(0.0, 90.0), 0.0001)
        // Facing north, sun due south: below.
        assertEquals(-90.0, Hemisphere.sunAtFrom(0.0, 180.0), 0.0001)
        // Facing east, sun due east: straight ahead, so up the screen.
        assertEquals(90.0, Hemisphere.sunAtFrom(90.0, 90.0), 0.0001)
        // Facing south, sun due north: behind you, so down.
        assertEquals(-90.0, Hemisphere.sunAtFrom(180.0, 0.0), 0.0001)
        // And turning the phone one way turns the picture the other, which
        // is what keeps the sun pointing at the sun.
        val still = Hemisphere.sunAtFrom(0.0, 120.0)
        val turned = Hemisphere.sunAtFrom(30.0, 120.0)
        assertEquals(30.0, Hemisphere.wrap(turned - still), 0.0001)
    }

    /**
     * And the reading is rounded until it holds still.
     *
     * A phone lying on a table wanders a degree or two, and this face
     * cannot afford to follow it: the globe is a sphere, so turning it
     * means projecting a quarter of a million points again. A world that
     * twitches while nobody is touching it looks broken, and one that
     * re-projects itself several times a second while it does so is worse.
     */
    @Test
    fun `a compass reading is rounded until it holds still`() {
        // Same bucket, so the same answer. The boundary is at 102.5, and
        // writing this test with 101 and 103.9 in it was a reminder that a
        // rounding rule has edges wherever you put them.
        assertEquals(Hemisphere.steady(101.0), Hemisphere.steady(102.4), 0.0001)
        assertEquals(100.0, Hemisphere.steady(101.0), 0.0001)
        assertEquals(105.0, Hemisphere.steady(104.0), 0.0001)
        // The jitter of a phone at rest never moves the picture.
        val at = Hemisphere.sunAtFrom(200.0, 45.0)
        for (wobble in listOf(-1.5, -0.7, 0.0, 0.9, 1.9)) {
            assertEquals(
                "the world moved on a phone nobody touched",
                at, Hemisphere.sunAtFrom(200.0 + wobble, 45.0), 0.0001
            )
        }
        // A real turn does move it.
        assertTrue(
            kotlin.math.abs(
                Hemisphere.wrap(Hemisphere.sunAtFrom(215.0, 45.0) - at)
            ) > 10.0
        )
    }

    /** And the sun really is overhead where the sun is overhead. */
    @Test
    fun `the subsolar point is where it is noon`() {
        val at = java.util.Calendar.getInstance().apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
            set(2026, java.util.Calendar.JUNE, 21, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sub = Hemisphere.subsolar(at)
        // Midsummer: the sun is over the tropic of Cancer.
        assertEquals("the sun is not over the tropic at midsummer", 23.4, sub[0], 0.6)
        // And at noon UTC it is near Greenwich, give or take the equation
        // of time, which is a couple of degrees at most.
        assertTrue("noon UTC is not near Greenwich: ${sub[1]}", abs(sub[1]) < 4.0)
    }
}
