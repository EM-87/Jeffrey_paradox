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
        // Stamped with the real clock. The gesture detector tells a double
        // tap from a stutter by the gap between the two, and events all
        // stamped zero are no gap at all — so a double tap delivered that
        // way is never recognised as one, and a test of it proves nothing.
        val at = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
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
     * A tap on the token opens the sky; a tap on the Sun shuts it again.
     *
     * Delivered as touches and taken all the way through the gesture
     * detector, which is the part that was untested when this only asked
     * whether the hit region answered: the region can be right and nothing
     * be listening to it.
     */
    @Test
    fun `a tap on the token opens the sky, and the Sun shuts it`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        assertFalse("it opened by itself", clock.orreryShowing())

        tap(clock, clock.width / 2f, clock.height / 2f + clock.dialRadiusForTest() * 0.45f)
        assertTrue("the token did not open anything", clock.orreryShowing())

        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        tap(clock, clock.width / 2f, clock.height / 2f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertFalse("the Sun would not shut it", clock.orreryShowing())
    }

    /**
     * And empty sky no longer shuts it, because a tap out there is now for
     * naming things.
     */
    @Test
    fun `a tap on empty sky leaves the sky open`() {
        val clock = openSky()
        val (x, y) = emptySky(clock)
        tap(clock, x, y)
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertTrue("empty sky put the whole thing away", clock.orreryShowing())
    }

    /**
     * The Sun will not close over an untidy dial.
     *
     * Putting a lid on a case with planets rolling about in it is how you
     * come back to one and wonder what happened. Pick them up first — the
     * toolbox is there for exactly this.
     */
    @Test
    fun `the Sun refuses to shut over planets on the floor`() {
        val clock = openSky()
        clock.knockHandsOff()
        assertTrue("nothing fell", clock.fallenPlanetsForTest().isNotEmpty())
        tap(clock, clock.width / 2f, clock.height / 2f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertTrue("it shut with the planets still on the floor", clock.orreryShowing())

        clock.reassembleAll()
        tap(clock, clock.width / 2f, clock.height / 2f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertFalse("and would not shut once tidied", clock.orreryShowing())
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

    /**
     * The fade asks for the frames it needs to be a fade.
     *
     * It reads a clock every frame and nothing was requesting any, so it
     * got whatever the dial happened to draw for other reasons — one a
     * second from the ticking second hand, and none at all with the second
     * hand switched off. The fade did not fail; it stuttered, which is
     * harder to see and worse to look at.
     */
    @Test
    fun `the sky asks for frames while it is fading`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.showSecondHand = false
        assertFalse("it wanted frames with nothing happening", clock.isAnimatingForTest())

        clock.toggleOrrery()
        assertTrue("the fade is not asking for frames", clock.isAnimatingForTest())
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        assertTrue("it stopped asking halfway through", clock.isAnimatingForTest())
        ShadowSystemClock.advanceBy(Duration.ofMillis(2000))
        assertFalse("it went on asking after it had finished", clock.isAnimatingForTest())
    }

    /**
     * And it throws away the frame already queued.
     *
     * Asking for frames is not the same as getting one. The loop works out
     * its delay when a frame is posted, so a fade started a moment after a
     * frame went into the queue would wait up to a whole second for it —
     * and then arrive at the far end. Nothing but the counter can tell that
     * apart from a fade that worked.
     */
    @Test
    fun `opening the sky kicks the frame loop`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        val before = clock.tickerKicks
        clock.toggleOrrery()
        assertTrue("the queued frame was left where it was", clock.tickerKicks > before)
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
     * Holding Jupiter lets the Moon go; holding the Earth does not.
     *
     * The rule is [Orrery]'s and is tested there. What is tested here is
     * that the dial obeys it — that the Moon the *view* draws stops moving,
     * rather than the rule being true in a class nobody consults.
     */
    @Test
    fun `the drawn Moon lets go past Mars and not before`() {
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

        grab(Orrery.Body.JUPITER)
        val heldAt = clock.orreryMoonLongitude()
        clock.windOrreryForTest(Orrery.Body.JUPITER, 200.0)
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
            "and Mars, which is inside the line, must carry it too",
            Orrery.moonFollows(Orrery.Body.MARS)
        )
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
        val held = OrreryDial.positionOf(
            Orrery.Body.JUPITER, cx, cy, r, clock.orreryMs(), clock.orreryMoonLongitude()
        )
        touch(clock, MotionEvent.ACTION_DOWN, held.x, held.y)
        val heldAt = clock.orreryMoonLongitude()
        clock.windOrreryForTest(Orrery.Body.JUPITER, 137.0)
        touch(clock, MotionEvent.ACTION_UP, held.x, held.y)

        val home = Orrery.longitude(Orrery.Body.MOON, clock.orreryMs())
        assertTrue(
            "this drag did not move the Moon far enough to be worth sliding",
            Orrery.separation(heldAt, home) > 20.0
        )
        // And the dial must be asking for frames to slide it with: the
        // slide reads a clock every frame, and a fade nobody draws is a
        // jump with a pause in front of it.
        assertTrue(
            "nothing was asking for the frames the slide needs",
            clock.isAnimatingForTest()
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
        // The sky travels there rather than arriving, so the journey is
        // finished here: what is on trial is the date it picks, not the
        // easing that carries it.
        clock.settleOrreryForTest()
        assertTrue("it went backwards", clock.orreryMs() > before)
        assertTrue(
            "it landed on a date with nothing in line on it",
            clock.orreryAligned().size >= 3
        )
        // And again, so it moves on rather than sticking on the one it found.
        val first = clock.orreryMs()
        if (clock.leapToNextAlignment()) {
            clock.settleOrreryForTest()
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

    // ------------------------------------------------------- the way back

    /**
     * The Sun is the way back to today, and it is a journey rather than a
     * cut.
     *
     * Two hundred years of dial arriving in one frame is not something the
     * eye reads as movement, and everything else on this clock travels. It
     * runs home on the same curve the hands use crossing between the clock
     * and the chronograph.
     */
    @Test
    fun `tapping the Sun brings the sky home, travelling`() {
        val clock = openSky()
        clock.windOrreryForTest(Orrery.Body.NEPTUNE, 120.0)
        val wound = clock.orreryMs()
        assertTrue(
            "the wind did nothing",
            wound - TimeKeeper.nowMs() > 30L * 365 * 86_400_000L
        )

        tap(clock, clock.width / 2f, clock.height / 2f)
        assertTrue("it is not travelling", clock.orreryGlidingHome())
        // Partway: somewhere between where it was and today, and at neither
        // end. A cut with a delay in front of it would sit at one of them.
        ShadowSystemClock.advanceBy(Duration.ofMillis(600))
        val midway = clock.orreryMs()
        assertTrue("it jumped home", midway < wound)
        assertTrue("it has not set off", midway > TimeKeeper.nowMs() + 86_400_000L)

        ShadowSystemClock.advanceBy(Duration.ofMillis(1200))
        assertTrue(
            "it never arrived",
            kotlin.math.abs(clock.orreryMs() - TimeKeeper.nowMs()) < 60_000L
        )
        assertFalse("it is still travelling", clock.orreryGlidingHome())
    }

    /** And tapping the Sun does not shut the sky, the way empty space does. */
    @Test
    fun `the Sun is not a way out`() {
        val clock = openSky()
        clock.windOrreryForTest(Orrery.Body.MARS, 60.0)
        tap(clock, clock.width / 2f, clock.height / 2f)
        // Past the fade before asking: a sky that has just been told to
        // shut is still fully drawn for another half second, so asking
        // straight away answers yes whatever happened.
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertTrue("the sky shut instead of coming home", clock.orreryShowing())
    }

    /** A hand on a planet stops the journey where it has got to. */
    @Test
    fun `taking hold of a planet stops the journey home`() {
        val clock = openSky()
        clock.windOrreryForTest(Orrery.Body.JUPITER, 120.0)
        tap(clock, clock.width / 2f, clock.height / 2f)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))

        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val p = OrreryDial.positionOf(
            Orrery.Body.JUPITER, cx, cy, clock.dialRadiusForTest(),
            clock.orreryMs(), clock.orreryMoonLongitude()
        )
        touch(clock, MotionEvent.ACTION_DOWN, p.x, p.y)
        assertFalse("it went on running home under the finger", clock.orreryGlidingHome())
        val caught = clock.orreryMs()
        ShadowSystemClock.advanceBy(Duration.ofMillis(2000))
        assertEquals(
            "it carried on home anyway",
            caught.toDouble(), clock.orreryMs().toDouble(), 3000.0
        )
    }

    /**
     * A long press on a planet names it; a long press on empty sky goes
     * hunting for an alignment.
     *
     * One gesture, and which of the two it is depends only on what is under
     * the finger. Told apart here by what moves: naming a planet must not
     * throw the date across the years.
     */
    @Test
    fun `a long press on a planet names it instead of leaping`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val p = OrreryDial.positionOf(
            Orrery.Body.SATURN, cx, cy, clock.dialRadiusForTest(),
            clock.orreryMs(), clock.orreryMoonLongitude()
        )
        touch(clock, MotionEvent.ACTION_DOWN, p.x, p.y)
        val before = clock.orreryMs()
        clock.pressAndHoldOnSky(p.x, p.y)
        assertEquals(
            "naming a planet moved the date",
            before.toDouble(), clock.orreryMs().toDouble(), 5000.0
        )
        touch(clock, MotionEvent.ACTION_UP, p.x, p.y)

        // And the same gesture on empty sky does leap, or the test above
        // would pass just as well on a dial where nothing happens at all.
        val (ex, ey) = emptySky(clock)
        clock.pressAndHoldOnSky(ex, ey)
        clock.settleOrreryForTest()
        // It went somewhere — days at least, which a running clock does not
        // do by itself — and the somewhere it went is a sky with three
        // planets lined up in it.
        //
        // Asked that way round rather than as a distance. This used to
        // require thirty days, on the reasoning that "an alignment of three
        // planets is a long way off", and that reasoning is simply wrong:
        // three of eight planets inside a twelve degree arc is a common
        // event, and the test sat there for months waiting for a year in
        // which the next one happened to fall inside a month. It did —
        // twenty-nine days — and the failure said nothing at all about the
        // gesture it was supposed to be testing.
        val moved = clock.orreryMs() - before
        assertTrue("holding on empty sky did not move the sky: $moved ms", moved > 86_400_000L)
        val there = Orrery.aligned(clock.orreryMs(), 12.0)
        assertTrue(
            "the sky it leapt to has only ${there.size} planets lined up: $there",
            there.size >= 3
        )
    }

    /** Walking away from the clock puts the sky away. */
    @Test
    fun `leaving the clock card shuts the sky`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val clock = activity.clockForTest()
        clock.toggleOrrery()
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertTrue(clock.orreryShowing())

        activity.showCardForTest(Card.CALENDAR)
        // The sky now leaves with the card that was carrying it rather than
        // being taken out from under it, so the closing is a beat behind
        // the card change — long enough for the two to dissolve into one
        // another without a flash of clock face in between.
        assertTrue(
            "the sky was pulled out from under the card that was carrying it",
            clock.orreryShowing()
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(900))
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertFalse("it was still up on the way back", clock.orreryShowing())
    }

    /**
     * The host is told how far the sky has come.
     *
     * The world clock's bubbles float over the dial and have nothing to say
     * about planets, so they leave with the hands — which they can only do
     * if somebody tells them the hands are leaving.
     */
    @Test
    fun `the host is told the sky is arriving`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        val heard = mutableListOf<Float>()
        clock.onSkyFade = { heard.add(it) }
        clock.toggleOrrery()
        repeat(6) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(120))
            clock.draw(android.graphics.Canvas(
                android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            ))
        }
        assertTrue("nobody was told anything: $heard", heard.size >= 3)
        assertTrue("it never reached the far end: $heard", heard.last() > 0.9f)
    }

    // ------------------------------------------------------------ the zoom

    /**
     * The pinch goes as far as the Earth's orbit and no further.
     *
     * That is the end of the journey rather than an arbitrary stop: with the
     * Earth on the rim, one turn of the dial is one year, and the face can be
     * marked out in days the way an ordinary one is marked out in hours.
     */
    @Test
    fun `the zoom stops with the Earth on the rim`() {
        val clock = openSky()
        val r = clock.dialRadiusForTest()
        clock.zoomOrrery(100f)
        // The Earth ends up exactly where Neptune sits at rest, which is
        // what "the outermost ring" means — stated that way rather than as
        // a number, so the two cannot drift apart.
        assertEquals(
            "the Earth is not on the outermost ring at full zoom",
            OrreryDial.ringRadius(Orrery.Body.NEPTUNE, r, 1f),
            OrreryDial.ringRadius(Orrery.Body.EARTH, r, clock.orreryZoomForTest()),
            r * 0.005f
        )
        clock.zoomOrrery(0.001f)
        assertEquals("it zoomed out past the whole system", 1f, clock.orreryZoomForTest(), 0.001f)
    }

    /**
     * Zoomed in, the small bodies become things a finger can hit.
     *
     * The reason for the whole gesture: the Earth is a dozen pixels across
     * at rest and the Moon half that, and two things that small cannot be
     * chosen between.
     */
    @Test
    fun `zooming in makes the Earth and Moon reachable`() {
        val clock = openSky()
        val r = clock.dialRadiusForTest()
        val restingMoon = OrreryDial.dotRadius(Orrery.Body.MOON, r, 1f)
        val zoomedMoon = OrreryDial.dotRadius(Orrery.Body.MOON, r, Orrery.MAX_ZOOM)
        assertTrue(
            "the Moon is no bigger zoomed in: $restingMoon to $zoomedMoon",
            zoomedMoon > restingMoon * 1.5f
        )
    }

    /** And the outer planets, pushed off the dial, stop answering touches. */
    @Test
    fun `a planet pushed off the dial cannot be grabbed`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val zoom = Orrery.MAX_ZOOM
        val p = OrreryDial.positionOf(
            Orrery.Body.NEPTUNE, cx, cy, r, clock.orreryMs(), 0.0, zoom
        )
        assertTrue(
            "Neptune is still on the dial at full zoom",
            kotlin.math.hypot(p.x - cx, p.y - cy) > r
        )
        assertNull(
            "something off the edge of the case answered a touch",
            OrreryDial.bodyAt(p.x, p.y, cx, cy, r, clock.orreryMs(), 0.0, zoom)
        )
    }

    /**
     * The days of the year arrive with the zoom, not before it.
     *
     * Three hundred and sixty-five marks appearing in one frame is a
     * flicker; arriving over the last third of the journey they read as
     * something the zoom is uncovering.
     */
    @Test
    fun `the year is marked out in days only at the far end of the zoom`() {
        assertEquals("marks at rest", 0f, Orrery.dayMarkFade(1f), 0.001f)
        assertEquals("marks halfway", 0f, Orrery.dayMarkFade(1f + (Orrery.MAX_ZOOM - 1f) * 0.4f), 0.001f)
        assertTrue("no marks at the far end", Orrery.dayMarkFade(Orrery.MAX_ZOOM) > 0.99f)
        val most = Orrery.dayMarkFade(1f + (Orrery.MAX_ZOOM - 1f) * 0.85f)
        assertTrue("they arrive in one frame: $most", most > 0f && most < 1f)
    }

    /**
     * A leap year gets its extra day, and gets it in the right place.
     *
     * Nothing counts to 365 anywhere: each mark is put where the Earth
     * actually stands on that date, so a leap year simply has one more of
     * them and no special case at all.
     */
    @Test
    fun `a leap year has three hundred and sixty-six marks`() {
        assertEquals(366, Orrery.daysInYear(2028))
        assertEquals(365, Orrery.daysInYear(2026))
        assertEquals("a century is not a leap year", 365, Orrery.daysInYear(2100))
        assertEquals("but every fourth century is", 366, Orrery.daysInYear(2000))
    }

    /** A touch on the ring of days finds the day it points at. */
    @Test
    fun `a touch on the rim finds the day it points at`() {
        val clock = openSky()
        clock.zoomOrrery(100f)
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val today = CivilDays.dayOf(clock.orreryMs(), 0)
        // Where today's mark is: the Earth's own place on the dial, out at
        // the ring the dots sit on.
        val angle = Orrery.longitude(Orrery.Body.EARTH, today * CivilDays.DAY_MS)
        val ring = r * 0.94f + r * 0.035f
        val x = cx + (ring * kotlin.math.cos(Math.toRadians(angle))).toFloat()
        val y = cy - (ring * kotlin.math.sin(Math.toRadians(angle))).toFloat()
        assertEquals(
            "the rim does not know what day it is pointing at",
            today,
            OrreryDial.dayAt(x, y, cx, cy, r, clock.orreryMs(), clock.orreryZoomForTest())
        )
        // And a touch in the middle of the dial is not a day at all.
        assertNull(OrreryDial.dayAt(cx, cy, cx, cy, r, clock.orreryMs(), Orrery.MAX_ZOOM))
    }

    /** Zoomed out, there are no days to touch. */
    @Test
    fun `there is no ring of days until the year is showing`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        assertNull(
            OrreryDial.dayAt(cx, cy - r * 0.97f, cx, cy, r, clock.orreryMs(), 1f)
        )
    }

    // -------------------------------------------------------- knocked off

    /**
     * A knock takes the planets off their orbits too.
     *
     * The same joke as the hands and the same physics: they fall into the
     * case and roll about under the phone's own gravity.
     */
    @Test
    fun `a knock spills the planets into the case`() {
        val clock = openSky()
        assertTrue("something had already fallen", clock.fallenPlanetsForTest().isEmpty())
        clock.knockHandsOff()
        val fallen = clock.fallenPlanetsForTest()
        assertTrue("nothing came off: $fallen", fallen.size >= 5)
        assertTrue("the Earth stayed in orbit", Orrery.Body.EARTH in fallen)
        assertTrue("and the Moon with it", Orrery.Body.MOON in fallen)
    }

    /** And putting everything back puts them back in the sky. */
    @Test
    fun `the toolbox puts the planets back in orbit`() {
        val clock = openSky()
        clock.knockHandsOff()
        assertTrue(clock.fallenPlanetsForTest().isNotEmpty())
        clock.reassembleAll()
        assertTrue(
            "the planets stayed on the floor",
            clock.fallenPlanetsForTest().isEmpty()
        )
    }

    /** Shutting the sky also puts them back: they belong to it. */
    @Test
    fun `shutting the sky sweeps the planets up`() {
        val clock = openSky()
        clock.knockHandsOff()
        assertTrue(clock.fallenPlanetsForTest().isNotEmpty())
        clock.toggleOrrery()
        assertTrue(
            "planets were left lying in a case with no solar system in it",
            clock.fallenPlanetsForTest().isEmpty()
        )
    }

    /**
     * The planets go down with the dial at night.
     *
     * Their colours do not follow the theme — that is the point of them,
     * since rust and straw are how you tell Mars from Venus — and "not
     * themed" was being read as "not dimmed". Eight bright lamps over a
     * dial turned down for the bedroom is worse than no dial at all.
     */
    @Test
    fun `the planets are dimmed at night`() {
        val day = ClockThemes.MIDNIGHT
        val night = ClockThemes.dim(day)
        fun brightness(c: Int) =
            0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)
        for (body in Orrery.planets + Orrery.Body.MOON) {
            val lit = brightness(OrreryDial.colourOf(body, day))
            val dark = brightness(OrreryDial.colourOf(body, night))
            assertTrue(
                "$body is as bright at night ($dark) as by day ($lit)",
                dark < lit * 0.6
            )
        }
    }

    /**
     * A tap on a planet gives its name.
     *
     * A tap, not a long press: a finger that comes down on Jupiter and
     * lifts again was asking which one that is, and nothing else on the sky
     * answers a tap in that spot.
     */
    @Test
    fun `a tap on a planet gives its name`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val p = OrreryDial.positionOf(
            Orrery.Body.JUPITER, cx, cy, clock.dialRadiusForTest(),
            clock.orreryMs(), clock.orreryMoonLongitude()
        )
        tap(clock, p.x, p.y)
        assertEquals(
            "no bubble, or the wrong one",
            context.getString(R.string.body_jupiter), clock.markBubbleForTest()
        )
    }

    /**
     * Two taps in a row on the sky do nothing.
     *
     * They used to undo the zoom, which is a thing the pinch already does
     * and which nobody meant every time they tapped a planet twice.
     */
    @Test
    fun `a double tap leaves the zoom where it was`() {
        val clock = openSky()
        clock.zoomOrrery(100f)
        val zoomed = clock.orreryZoomForTest()
        assertTrue("it did not zoom at all", zoomed > 1.5f)
        val (x, y) = emptySky(clock)
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        touch(clock, MotionEvent.ACTION_UP, x, y)
        ShadowSystemClock.advanceBy(Duration.ofMillis(120))
        touch(clock, MotionEvent.ACTION_DOWN, x, y)
        touch(clock, MotionEvent.ACTION_UP, x, y)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(600))
        assertEquals(
            "a double tap threw the zoom away",
            zoomed, clock.orreryZoomForTest(), 0.001f
        )
    }

    /**
     * A finger comes down on the piece lying in the case, not on the orbit
     * it fell out of.
     *
     * The orbit is still drawn under it and used to take the touch first,
     * so a planet knocked loose could not be picked up at all — which made
     * the case impossible to tidy by hand.
     */
    @Test
    fun `a touch on a fallen planet picks up the planet, not its orbit`() {
        val clock = openSky()
        clock.knockHandsOff()
        val body = clock.debrisNearestForTest()
        assertNotNull("nothing fell", body)
        touch(clock, MotionEvent.ACTION_DOWN, body!!.x, body.y)
        assertNotNull(
            "the orbit it fell out of took the touch",
            clock.carriedForTest()
        )
        assertNull(
            "and it grabbed something still in the sky as well",
            clock.orreryGrabbedForTest()
        )
    }

    /** The Sun comes off with the planets, or the case is half tidied. */
    @Test
    fun `a knock takes the Sun down too`() {
        val clock = openSky()
        assertFalse("it was already down", clock.sunFallenForTest())
        clock.knockHandsOff()
        assertTrue("the star is still burning in the middle", clock.sunFallenForTest())
        clock.reassembleAll()
        assertFalse("and it never went back up", clock.sunFallenForTest())
    }

    /**
     * A planet is put back by carrying it to its own ring or to the middle.
     *
     * Eight planets each to its own invisible circle was a puzzle nobody
     * asked to be set; the middle is where the hands go back, and it is the
     * thing anybody tries first.
     */
    @Test
    fun `a planet goes back to its ring or to the middle`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        clock.knockHandsOff()
        val before = clock.fallenPlanetsForTest().size
        assertTrue("nothing fell", before > 0)

        // Pick one up off the floor and carry it to the middle.
        val body = clock.debrisNearestForTest()
        assertNotNull("nothing to pick up", body)
        clock.carryForTest(body!!)
        touch(clock, MotionEvent.ACTION_MOVE, cx, cy)
        touch(clock, MotionEvent.ACTION_UP, cx, cy)
        assertFalse(
            "carrying ${body.planet} to the middle did not put it back",
            body.planet in clock.fallenPlanetsForTest()
        )
        // And only that one: putting Neptune back must not quietly put
        // Mercury back with it.
        assertEquals(
            "more than the planet in hand went home",
            before - 1, clock.fallenPlanetsForTest().size
        )
    }

    /** And a touch on a planet is a target a finger can actually hit. */
    @Test
    fun `a planet is a target a finger can hit`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val density = clock.resources.displayMetrics.density
        val now = TimeKeeper.nowMs()
        // Ten density-independent pixels out, which is well inside how well
        // a finger aims — and was outside the old target for every planet
        // smaller than Jupiter.
        val slip = 14f * density
        for (body in Orrery.planets) {
            val p = OrreryDial.positionOf(body, cx, cy, r, now, 0.0)
            val hit = OrreryDial.bodyAt(p.x + slip, p.y, cx, cy, r, now, 0.0)
            // The Earth and its Moon sit on top of one another, so a touch
            // between the two is a fair answer either way — which is what
            // the zoom is for. Every other planet is alone out there.
            val fair = if (body == Orrery.Body.EARTH) {
                hit == Orrery.Body.EARTH || hit == Orrery.Body.MOON
            } else {
                hit == body
            }
            assertTrue("$body cannot be hit from ${slip.toInt()}px away, got $hit", fair)
        }
    }

    // ------------------------------------------ which planet a finger meant

    /**
     * Taking hold of the Moon does not throw the sky across the years.
     *
     * The angle a grab starts from was measured about the middle of the
     * dial and the angle every frame after it about the Earth, and the two
     * were then subtracted from one another as though they were the same
     * measurement. For a planet they are; for the Moon they are two quite
     * different angles, so the very first move booked the difference
     * between them as a movement of the finger — up to half a turn of an
     * orbit that takes a month, in one frame.
     *
     * The Moon is the one body permanently sitting on top of another, so it
     * is the one that gets taken hold of by mistake, which is why this
     * showed up as "they overlap and it goes wrong there".
     */
    @Test
    fun `taking hold of the Moon and not moving moves nothing`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        val moon = OrreryDial.positionOf(
            Orrery.Body.MOON, cx, cy, r, clock.orreryMs(), clock.orreryMoonLongitude()
        )
        touch(clock, MotionEvent.ACTION_DOWN, moon.x, moon.y)
        assertEquals(
            "the Moon was not the body under the finger",
            Orrery.Body.MOON, clock.orreryGrabbedForTest()
        )
        val before = clock.orreryMs()
        // The finger has not gone anywhere.
        touch(clock, MotionEvent.ACTION_MOVE, moon.x, moon.y)
        // A second of slack for the arithmetic and not a millisecond more
        // of intent: with the two angles crossed this was days.
        assertTrue(
            "the sky jumped ${(clock.orreryMs() - before) / 86_400_000.0} days " +
                "without the finger moving",
            kotlin.math.abs(clock.orreryMs() - before) < 1000L
        )
    }

    /**
     * Where two rings are within a finger's width of each other, the ring
     * under the finger decides which planet was meant.
     *
     * A reach wide enough for a finger is a little wider than the space
     * between two rings, so there is a band in which a touch aimed at one
     * planet is within reach of its neighbour as well — and whichever was
     * nearest in plain pixels used to win it. Nearest is the wrong
     * question there: the finger is on one ring or the other, and that is
     * the answer everybody can see. Taking the wrong one is not a small
     * error either, since the whole system then winds at that planet's
     * speed, and Mercury's year is Neptune's fortnight.
     */
    @Test
    fun `a finger on a ring takes the planet on that ring`() {
        val cx = 500f
        val cy = 500f
        val r = 460f
        // A moment when a pair of neighbours stand so that a point on the
        // inner ring is nearer to the outer planet than to the inner one:
        // found by looking, because the arithmetic decides where planets
        // are and no date written down here would stay true of it.
        var at = 0L
        var inner = Orrery.Body.MERCURY
        var outer = Orrery.Body.VENUS
        var found = false
        var day = 0L
        outer@ while (day < 4000) {
            val t = TimeKeeper.nowMs() + day * 86_400_000L
            for (i in 0 until Orrery.planets.size - 1) {
                val a = Orrery.planets[i]
                val b = Orrery.planets[i + 1]
                val ringA = OrreryDial.ringRadius(a, r)
                val gap = OrreryDial.ringRadius(b, r) - ringA
                val sep = Orrery.separation(
                    Orrery.longitude(a, t), Orrery.longitude(b, t)
                )
                // How far round A's own ring the outer planet's direction
                // is, as a straight line: that is how far the touch will be
                // from A, while it is exactly [gap] from B.
                val chord = 2f * ringA * kotlin.math.sin(Math.toRadians(sep / 2)).toFloat()
                val reach = maxOf(OrreryDial.dotRadius(a, r) * 2.4f, r * 0.115f)
                if (chord > gap * 1.05f && chord < reach * 0.92f) {
                    at = t; inner = a; outer = b; found = true
                    break@outer
                }
            }
            day++
        }
        assertTrue("no such moment in eleven years of sky", found)

        // On the inner planet's own ring, pointing the way the outer one
        // lies.
        val ring = OrreryDial.ringRadius(inner, r)
        val angle = Math.toRadians(Orrery.longitude(outer, at))
        val x = cx + (ring * kotlin.math.cos(angle)).toFloat()
        val y = cy - (ring * kotlin.math.sin(angle)).toFloat()

        val innerPos = OrreryDial.positionOf(inner, cx, cy, r, at, 0.0)
        val outerPos = OrreryDial.positionOf(outer, cx, cy, r, at, 0.0)
        assertTrue(
            "the touch was not actually nearer the wrong planet, so this proves nothing",
            kotlin.math.hypot(x - outerPos.x, y - outerPos.y) <
                kotlin.math.hypot(x - innerPos.x, y - innerPos.y)
        )
        assertEquals(
            "the touch went to $outer, one ring out from the ring it was on",
            inner, OrreryDial.bodyAt(x, y, cx, cy, r, at, 0.0)
        )
    }

    /**
     * And a planet lying in the case is not in the sky to be taken hold of.
     *
     * It keeps its place in the arithmetic while it is on the floor — that
     * is how it knows where to go back to — and the hit test read that
     * place as though the planet were still standing in it. So a touch on
     * an empty stretch of orbit took hold of nothing you could see and
     * wound the whole system by it.
     */
    @Test
    fun `an empty orbit hands out no grabs`() {
        val clock = openSky()
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val r = clock.dialRadiusForTest()
        clock.knockHandsOff()
        val down = clock.fallenPlanetsForTest()
        assertTrue("nothing fell", down.isNotEmpty())
        // Asked of the sky's own grab rather than of the geometry it calls:
        // the hit test knowing about the floor is no use at all if the
        // thing that calls it does not pass it on, and a version of this
        // test that went straight to OrreryDial passed with that wire cut.
        for (body in down) {
            val where = OrreryDial.positionOf(
                body, cx, cy, r, clock.orreryMs(), clock.orreryMoonLongitude()
            )
            val got = if (clock.grabBodyNearForTest(where.x, where.y)) {
                clock.orreryGrabbedForTest()
            } else {
                null
            }
            assertTrue(
                "$body is on the floor and was still handed out from its orbit",
                got !in down
            )
        }
    }

    /**
     * And with the planets down, the clock does not put a second row of
     * digits over the sky's own.
     *
     * A knock takes the hands off with the planets, and hands on the floor
     * make the clock spell the time out under the dial — in exactly the
     * place the sky puts the date it is standing on. Two readouts, one
     * through the other.
     */
    @Test
    fun `a knocked sky shows the date and not the time as well`() {
        val clock = openSky()
        clock.knockHandsOff()
        assertNull(
            "the clock spelled the hour out over the sky's date",
            clock.readoutText()
        )
    }
}
