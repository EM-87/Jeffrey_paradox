package com.em87.weirdclock

import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The solar system as it behaves under a finger.
 *
 * [OrreryTest] proves the arithmetic; this proves that the arithmetic is
 * what the dial is actually running on. The two things that could be right
 * separately and wrong together are the ones checked hardest here: that the
 * place a planet is *drawn* is the place a touch on it is understood to be,
 * and that carrying a planet moves the date the dial shows rather than some
 * other number kept somewhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class OrreryDialTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun turnItOn() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    /** A clock on screen, with the sky already open and the fade done. */
    private fun openSky(): ClockView {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        return clock
    }

    private fun touch(view: ClockView, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    /**
     * A whole tap, taken through the gesture detector.
     *
     * The idle at the end is what makes it a tap: a single tap is only
     * confirmed once the detector has waited long enough to be sure a
     * second one is not coming.
     */
    private fun tap(view: ClockView, x: Float, y: Float) {
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(600))
    }

    /** A spot on the dial with no planet on it. */
    private fun emptySky(clock: ClockView): Pair<Float, Float> {
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val mid = (OrreryDial.ringRadius(Orrery.Body.URANUS, r) +
            OrreryDial.ringRadius(Orrery.Body.NEPTUNE, r)) / 2f
        for (angle in 0 until 360 step 5) {
            val a = Math.toRadians(angle.toDouble())
            val x = cx + (mid * kotlin.math.cos(a)).toFloat()
            val y = cy - (mid * kotlin.math.sin(a)).toFloat()
            if (OrreryDial.bodyAt(
                    x, y, cx, cy, r, clock.orreryMs(), clock.orreryMoonLongitude()
                ) == null && !clock.skyTokenAt(x, y)
            ) return x to y
        }
        throw AssertionError("the whole dial is planets")
    }

    // ---------------------------------------------------- opening and shutting

    /**
     * A tap on the token opens the sky, and a tap on the empty sky shuts it.
     *
     * Delivered as touches and taken all the way through the gesture
     * detector, which is the part that was untested when this only asked
     * whether the hit region answered: the region can be right and nothing
     * be listening to it.
     */
    @Test
    fun `a tap on the token opens the sky, and a tap on the sky shuts it`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        assertFalse("it opened by itself", clock.orreryShowing())

        tap(clock, clock.width / 2f, clock.height / 2f + clock.dialRadiusForTest() * 0.45f)
        assertTrue("the token did not open anything", clock.orreryShowing())

        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        // Empty sky: between the two outermost rings, at an angle with
        // nothing on it, found by asking rather than by guessing.
        val (x, y) = emptySky(clock)
        tap(clock, x, y)
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertFalse("it would not shut again", clock.orreryShowing())
    }

    /**
     * And with the setting off the token does not answer at all — the dial
     * keeps whatever it did with a tap there before.
     */
    @Test
    fun `with the setting off the token is not a door`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.orreryEnabled = false
        assertFalse(clock.skyTokenAt(
            clock.width / 2f,
            clock.height / 2f + clock.dialRadiusForTest() * 0.45f
        ))
    }

    /** The fade is a fade: partway through, it is partway through. */
    @Test
    fun `the hands do not vanish in one frame`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        assertTrue("it arrived fully faded", clock.orreryFade() < 0.2f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        val middle = clock.orreryFade()
        assertTrue("nothing happened in two hundred milliseconds", middle > 0.2f)
        assertTrue("it was already over", middle < 0.95f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        assertEquals("it never finished", 1f, clock.orreryFade(), 0.001f)
    }

    /** Closing it puts the date back to today. */
    @Test
    fun `shutting the sky brings the date back to now`() {
        val clock = openSky()
        clock.windOrreryForTest(Orrery.Body.NEPTUNE, 90.0)
        assertTrue(
            "the wind did nothing",
            kotlin.math.abs(clock.orreryMs() - TimeKeeper.nowMs()) > 365L * 20 * 86_400_000L
        )
        clock.toggleOrrery()
        assertTrue(
            "it stayed in the future after being shut",
            kotlin.math.abs(clock.orreryMs() - TimeKeeper.nowMs()) < 60_000L
        )
    }

    // ------------------------------------------------- the finger on a planet

    /**
     * A touch where a planet is drawn is understood as that planet.
     *
     * Every planet, all the way round its orbit — the two halves of this
     * feature agreeing about geometry is the thing most likely to come
     * apart later, and it comes apart silently: the wrong planet is grabbed
     * and the date runs at the wrong speed, which looks like a design
     * decision rather than a bug.
     */
    @Test
    fun `a touch on a planet finds that planet`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        for (body in Orrery.planets) {
            // At its real position now, and at three made-up ones, so the
            // check is of the geometry and not of one lucky afternoon.
            for (turn in listOf(0.0, 90.0, 180.0, 270.0)) {
                val at = TimeKeeper.nowMs() +
                    (turn / 360.0 * Orrery.periodDays(body) * 86_400_000L).toLong()
                val p = OrreryDial.positionOf(body, cx, cy, r, at, 0.0)
                assertEquals(
                    "$body at $turn° was not found where it was drawn",
                    body, OrreryDial.bodyAt(p.x, p.y, cx, cy, r, at, 0.0)
                )
            }
        }
    }

    /**
     * A finger *near* a planet finds it, not only a finger exactly on it.
     *
     * Mercury is a handful of pixels across. Somebody aiming at it will
     * miss it by more than its own width nearly every time, and a hit test
     * the size of the dot would answer about one tap in three — which reads
     * as a dial that ignores you rather than as a target that is too small.
     */
    @Test
    fun `a finger near a planet still finds it`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val now = TimeKeeper.nowMs()
        // Three millimetres out, which is about how well a finger aims.
        val slip = clock.resources.displayMetrics.density * 3f
        for (body in Orrery.planets) {
            val p = OrreryDial.positionOf(body, cx, cy, r, now, 0.0)
            for (dx in listOf(-slip, slip)) {
                assertEquals(
                    "$body was missed by a finger ${slip.toInt()}px off",
                    body, OrreryDial.bodyAt(p.x + dx, p.y, cx, cy, r, now, 0.0)
                )
            }
        }
    }

    /**
     * And the Moon can be taken hold of without the Earth answering for it.
     *
     * It sits on the Earth, a fifth of the way out on a ring the Earth is
     * the middle of, so for half of every month it is drawn on top of it.
     * If the Earth answers first, the one body whose orbit is worth forcing
     * by hand cannot be got at for a fortnight at a time.
     */
    @Test
    fun `the Moon can be grabbed off the Earth`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val now = TimeKeeper.nowMs()
        // All the way round its own ring, since where it sits relative to
        // the Earth is the whole difficulty. Both directions are checked:
        // a Moon drawn on top of the Earth would pass the first half of
        // this and make the Earth unreachable for good.
        for (angle in 0 until 360 step 30) {
            val moon = OrreryDial.positionOf(
                Orrery.Body.MOON, cx, cy, r, now, angle.toDouble()
            )
            assertEquals(
                "the Earth answered for the Moon at $angle°",
                Orrery.Body.MOON,
                OrreryDial.bodyAt(moon.x, moon.y, cx, cy, r, now, angle.toDouble())
            )
            val earth = OrreryDial.positionOf(
                Orrery.Body.EARTH, cx, cy, r, now, angle.toDouble()
            )
            assertEquals(
                "the Moon was sitting on the Earth at $angle°",
                Orrery.Body.EARTH,
                OrreryDial.bodyAt(earth.x, earth.y, cx, cy, r, now, angle.toDouble())
            )
        }
    }

    /** And a touch on empty sky finds nothing. */
    @Test
    fun `a touch on nothing grabs nothing`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        // Between two rings, at an angle nothing happens to be at: found by
        // asking, rather than by choosing a spot and hoping.
        var found: Orrery.Body? = Orrery.Body.EARTH
        var angle = 0
        while (found != null && angle < 360) {
            val mid = (OrreryDial.ringRadius(Orrery.Body.URANUS, r) +
                OrreryDial.ringRadius(Orrery.Body.NEPTUNE, r)) / 2f
            val a = Math.toRadians(angle.toDouble())
            found = OrreryDial.bodyAt(
                cx + (mid * kotlin.math.cos(a)).toFloat(),
                cy - (mid * kotlin.math.sin(a)).toFloat(),
                cx, cy, r, TimeKeeper.nowMs(), 0.0
            )
            angle += 7
        }
        assertNull("every point between two rings was a planet", found)
    }

    /**
     * Carrying a planet moves the date the dial shows, and by the amount
     * that planet's year says.
     *
     * Through the touch handler rather than through the helper, because the
     * helper is the part that already has a test: what is on trial here is
     * the wiring — that a finger down and a finger moved reach the
     * mechanism at all.
     */
    @Test
    fun `dragging a planet winds the date`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val before = clock.orreryMs()
        val start = OrreryDial.positionOf(
            Orrery.Body.JUPITER, cx, cy, r, before, clock.orreryMoonLongitude()
        )
        touch(clock, MotionEvent.ACTION_DOWN, start.x, start.y)
        assertEquals(
            "the finger did not take hold of anything",
            Orrery.Body.JUPITER, clock.orreryGrabbedForTest()
        )
        // A quarter of the way round its ring, in steps, the way a finger
        // travels — one jump would be indistinguishable from a bug that
        // only reads the first and last point.
        val ring = OrreryDial.ringRadius(Orrery.Body.JUPITER, r)
        val from = OrreryDial.longitudeOf(cx, cy, start.x, start.y)
        for (step in 1..30) {
            val a = Math.toRadians(from + step * 3.0)
            touch(
                clock, MotionEvent.ACTION_MOVE,
                cx + (ring * kotlin.math.cos(a)).toFloat(),
                cy - (ring * kotlin.math.sin(a)).toFloat()
            )
        }
        touch(clock, MotionEvent.ACTION_UP, start.x, start.y)
        assertNull("it never let go", clock.orreryGrabbedForTest())

        val movedDays = (clock.orreryMs() - before) / 86_400_000.0
        // A quarter of its year, give or take what an ellipse does: ninety
        // degrees of sky is not a quarter of an orbit unless the orbit is a
        // circle, and Jupiter's is out by about a tenth.
        assertEquals(
            "ninety degrees of Jupiter is about a quarter of its year",
            Orrery.periodDays(Orrery.Body.JUPITER) / 4, movedDays,
            Orrery.periodDays(Orrery.Body.JUPITER) * 0.04
        )
    }

    /**
     * The planets take the touches: no hand is wound while the sky is open.
     *
     * Proved by contrast, because the obvious version of this test proves
     * nothing — a drag that misses the hands moves nothing either way, and
     * a test that cannot tell "the sky blocked it" from "I missed" passes
     * whatever the code does. So the same drag is made twice, on a dial
     * with hands and on a dial with planets, and it must do something the
     * first time and nothing the second.
     */
    @Test
    fun `the hands are not wound behind the planets`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()

        // Measured on the hands' own wind rather than on the date, which
        // also moves because time passes between one reading and the next.
        // A hand is only taken hold of where it actually is, and where it
        // is depends on what time the test happens to run at. So the minute
        // hand is worked out from the clock's own displayed time — a drag
        // aimed at twelve o'clock passes or fails by the hour of the day,
        // which is the definition of a test not worth having.
        fun sweep(): Double {
            val was = clock.handWindForTest()
            val cx = clock.width / 2f
            val cy = clock.height / 2f
            val reach = clock.dialRadiusForTest() * 0.45f
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = clock.shownWallMs()
            }
            val onTheMinuteHand = cal.get(java.util.Calendar.MINUTE) * 6.0 - 90.0
            fun at(deg: Double) = Pair(
                cx + (reach * kotlin.math.cos(Math.toRadians(deg))).toFloat(),
                cy + (reach * kotlin.math.sin(Math.toRadians(deg))).toFloat()
            )
            val (dx, dy) = at(onTheMinuteHand)
            touch(clock, MotionEvent.ACTION_DOWN, dx, dy)
            for (step in 1..12) {
                val (mx, my) = at(onTheMinuteHand + step * 10.0)
                touch(clock, MotionEvent.ACTION_MOVE, mx, my)
            }
            touch(clock, MotionEvent.ACTION_UP, dx, dy)
            return clock.handWindForTest() - was
        }

        assertTrue("this drag never reached a hand, so it proves nothing", sweep() != 0.0)

        clock.toggleOrrery()
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertEquals("a hand was wound behind the planets", 0.0, sweep(), 0.0001)
    }

    // --------------------------------------------------------- the Moon

    /**
     * Holding Mars lets the Moon go; holding the Earth does not.
     *
     * The rule is [Orrery]'s and is tested there. What is tested here is
     * that the dial obeys it — that the Moon the *view* draws stops moving,
     * rather than the rule being true in a class nobody consults.
     */
    @Test
    fun `the drawn Moon lets go of everything but the Earth`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()

        fun grab(body: Orrery.Body) {
            val p = OrreryDial.positionOf(
                body, cx, cy, r, clock.orreryMs(), clock.orreryMoonLongitude()
            )
            touch(clock, MotionEvent.ACTION_DOWN, p.x, p.y)
            assertEquals("did not take hold of $body", body, clock.orreryGrabbedForTest())
        }

        grab(Orrery.Body.MARS)
        val heldAt = clock.orreryMoonLongitude()
        clock.windOrreryForTest(Orrery.Body.MARS, 200.0)
        assertTrue("the Moon came along for the ride", clock.orreryMoonDetached())
        assertEquals(
            "the Moon moved while it was supposed to have let go",
            heldAt, clock.orreryMoonLongitude(), 0.001
        )
        touch(clock, MotionEvent.ACTION_UP, cx, cy)

        // And past the slide back, so the Moon is on the mechanism again.
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        grab(Orrery.Body.EARTH)
        val was = clock.orreryMoonLongitude()
        clock.windOrreryForTest(Orrery.Body.EARTH, 40.0)
        assertFalse("the Moon let go of the Earth too", clock.orreryMoonDetached())
        assertTrue(
            "the Earth did not carry the Moon with it",
            Orrery.separation(was, clock.orreryMoonLongitude()) > 30.0
        )
    }

    /**
     * A Moon that has let go slides back rather than jumping.
     *
     * Halfway through the slide it must be somewhere between the two, and
     * not at either end: an animation that is only ever at its start or its
     * finish is a snap with a delay in front of it.
     */
    @Test
    fun `the Moon slides back into the mechanism`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val mars = OrreryDial.positionOf(
            Orrery.Body.MARS, cx, cy, r, clock.orreryMs(), clock.orreryMoonLongitude()
        )
        touch(clock, MotionEvent.ACTION_DOWN, mars.x, mars.y)
        val heldAt = clock.orreryMoonLongitude()
        clock.windOrreryForTest(Orrery.Body.MARS, 137.0)
        touch(clock, MotionEvent.ACTION_UP, mars.x, mars.y)

        val home = Orrery.longitude(Orrery.Body.MOON, clock.orreryMs())
        assertTrue(
            "this drag did not move the Moon far enough to be worth sliding",
            Orrery.separation(heldAt, home) > 20.0
        )
        ShadowSystemClock.advanceBy(Duration.ofMillis(350))
        val midway = clock.orreryMoonLongitude()
        assertTrue("it snapped home instead of sliding", Orrery.separation(midway, home) > 1.0)
        assertTrue("it never set off", Orrery.separation(midway, heldAt) > 1.0)
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        assertEquals(
            "it never arrived",
            0.0, Orrery.separation(clock.orreryMoonLongitude(), home), 0.5
        )
    }

    // ---------------------------------------------------------- the readout

    /** The date under the planets is the date the planets are standing on. */
    @Test
    fun `the date follows the planets`() {
        val clock = openSky()
        val today = clock.orreryDateText()
        clock.windOrreryForTest(Orrery.Body.SATURN, 180.0)
        assertNotNull(clock.orreryDateText())
        assertTrue(
            "the date did not move with the system",
            clock.orreryDateText() != today
        )
    }

    /** On a night with something on, it says so. */
    @Test
    fun `a night with meteors on it says so`() {
        val perseids = SkyEvents.on(CivilDays.epochDay(2027, 8, 12))
            .firstOrNull { it.shower == SkyEvents.Shower.PERSEIDS }
        assertNotNull("the table lost the Perseids", perseids)
        val said = OrreryDial.nameOf(context.resources, perseids!!)
        assertTrue(
            "'$said' does not name them",
            said.contains(context.getString(R.string.shower_perseids))
        )
    }

    /**
     * And on a night with two things on it, the rarer one is read first.
     *
     * There is such a night, which is how this was noticed: the total solar
     * eclipse of 12 August 2026 falls on the Perseids. One line has room for
     * one of them, and it should not be the one that comes round every year.
     */
    @Test
    fun `the rarer of two things on one night is the one named`() {
        val both = SkyEvents.on(CivilDays.epochDay(2026, 8, 12))
        assertEquals("this night is supposed to be a double", 2, both.size)
        val said = OrreryDial.caption(
            context.resources,
            CivilDays.epochDay(2026, 8, 12) * CivilDays.DAY_MS + 43_200_000L,
            0, emptyList()
        )
        assertEquals(context.getString(R.string.sky_solar_total), said)
    }

    /** And on an ordinary night it says nothing at all. */
    @Test
    fun `an ordinary night is left without a caption`() {
        // A day picked by asking, not by guessing: most are quiet, but the
        // one this test hard-coded would eventually not be.
        var day = CivilDays.epochDay(2026, 4, 1)
        while (SkyEvents.anythingOn(day)) day++
        assertNull(
            OrreryDial.caption(
                context.resources, day * CivilDays.DAY_MS + 43_200_000L, 0, emptyList()
            )
        )
    }

    /**
     * The dial will go and find the next alignment rather than waiting to
     * be dragged onto one.
     *
     * Three planets inside twelve degrees happens a couple of times a
     * decade. Found by hand it would not be found.
     */
    @Test
    fun `the sky goes looking for the next alignment`() {
        val clock = openSky()
        val before = clock.orreryMs()
        assertTrue("it found nothing in forty years", clock.leapToNextAlignment())
        assertTrue("it went backwards", clock.orreryMs() > before)
        assertTrue(
            "it landed on a date with nothing in line on it",
            clock.orreryAligned().size >= 3
        )
        // And again, so it moves on rather than sticking on the one it found.
        val first = clock.orreryMs()
        if (clock.leapToNextAlignment()) {
            assertTrue("it found the same day twice", clock.orreryMs() > first)
        }
    }

    /** When three planets do line up, the caption names them. */
    @Test
    fun `an alignment is named`() {
        val at = Orrery.nextAlignment(
            TimeKeeper.nowMs(), 12.0, atLeast = 3, limitDays = 20_000
        )
        assertNotNull("nothing lined up in fifty years", at)
        val bodies = Orrery.aligned(at!!, 12.0)
        val said = OrreryDial.caption(context.resources, at, 0, bodies)
        assertNotNull("it found an alignment and said nothing", said)
        for (body in bodies) {
            assertTrue(
                "'$said' leaves out $body",
                said!!.contains(context.getString(OrreryDial.nameKeyOf(body)))
            )
        }
    }
}
