package com.em87.weirdclock

import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Three things that cost nothing to get wrong and a lot to leave wrong:
 * how often a still face redraws, when the widget wakes up, and whether
 * the face's furniture arrives with its hands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IdleWorkTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        DayNight.configure(context)
    }

    private fun laidOut(view: ClockView): ClockView = view.apply {
        measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 720, 720)
    }

    /**
     * The little faces on the alarm cards show one fixed time and never
     * change, and every one of them was redrawing sixty times a second —
     * "is anything moving" had been written as "does it have a provider",
     * and a still face has one too, returning the same number forever. A
     * card with four repetitions is four of them and a list of alarms is
     * dozens, which is what made C1 stutter.
     *
     * Measured as the rule rather than as frames: a view that is not in a
     * window never draws at all under Robolectric, so counting frames
     * would report a happy zero whatever the rule said.
     */
    @Test
    fun `a still face stops asking to be redrawn`() {
        val still = laidOut(
            ClockView(context).apply {
                staticFace = true
                chronoProvider = { 7 * 3_600_000L }
            }
        )
        // Not even at birth. It used to wind itself in from whatever
        // o'clock it happened to be, because being handed a provider
        // started a journey — which is the right thing when a dial changes
        // from one mode to another and nonsense on a face that has never
        // been drawn, since it is not changing into anything. Opening the
        // alarm drawer set a dozen little watches going for a second.
        assertEquals("a still face asked for frames on its first breath", -1L, still.tickDelayMs())
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        still.handAngleForTest(ClockView.Hand.HOUR)
        assertEquals("and it asks for none later either", -1L, still.tickDelayMs())
    }

    /**
     * And that the faces C1 builds really are declared still ones — the
     * rule above is worth nothing if nothing sets the flag it reads, which
     * a sabotage of AlarmCards duly proved by breaking no test at all.
     */
    @Test
    fun `the little faces on the alarm cards are still ones`() {
        val cards = AlarmCards(
            host = context,
            prefs = PreferenceManager.getDefaultSharedPreferences(context),
            alarms = emptyList(),
            dialTheme = { ClockThemes.MIDNIGHT },
            hoursOnDial = { 12 },
            dialShape = { ClockView.DialShape.CIRCLE },
            onToggled = { _, _ -> },
            onOpen = { }
        )
        val mini = laidOut(cards.miniDial(7, 15))
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        mini.handAngleForTest(ClockView.Hand.HOUR)

        assertEquals("a face showing a fixed time redraws once", -1L, mini.tickDelayMs())
    }

    /** A running chronograph is the case that really does need the frames. */
    @Test
    fun `a running chronograph keeps its frames`() {
        val running = laidOut(
            ClockView(context).apply {
                chronoProvider = { 1234L }
                chronoRunning = true
            }
        )
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        running.handAngleForTest(ClockView.Hand.HOUR)
        assertTrue("sixty a second", running.wantsFastFrames())
        assertEquals(16L, running.tickDelayMs())
    }

    /** A stopped one has nothing to say more than once a second. */
    @Test
    fun `a stopped chronograph slows down`() {
        val stopped = laidOut(
            ClockView(context).apply {
                chronoProvider = { 1234L }
                chronoRunning = false
            }
        )
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        stopped.handAngleForTest(ClockView.Hand.HOUR)
        assertFalse("once a second is plenty", stopped.wantsFastFrames())
    }

    /**
     * The crown and pushers fade over half a second on their own clock, and
     * nothing was asking for the frames to draw that with. Arriving, the
     * hand-over happened to be asking for them; leaving, nothing was, and
     * on a stopped chronograph the fade-out got one frame a second. Hence a
     * crown that grew in and then simply vanished.
     */
    @Test
    fun `a fading crown gets the frames to fade with`() {
        val dial = laidOut(
            ClockView(context).apply {
                chronoProvider = { 1234L }
                chronoRunning = false
            }
        )
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        dial.handAngleForTest(ClockView.Hand.HOUR)
        assertFalse("nothing is moving yet", dial.wantsFastFrames())

        dial.chronoButtons = true
        assertTrue("but a fade is a fade", dial.wantsFastFrames())
    }

    /** And a plain clock keeps its second hand ticking on the second. */
    @Test
    fun `a clock still ticks once a second`() {
        val clock = laidOut(ClockView(context).apply { showSecondHand = true })
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        clock.handAngleForTest(ClockView.Hand.HOUR)
        assertFalse("a ticking hand does not need sixty frames", clock.wantsFastFrames())
        val delay = clock.tickDelayMs()
        assertTrue("but it must keep drawing: $delay", delay in 1L..1000L)
    }

    // ------------------------------------------------------- the widget

    private fun located() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putFloat(Prefs.LAST_LATITUDE, 40.4f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.7f)
            .commit()
        DayNight.configure(context)
    }

    /**
     * There is no broadcast for "the sun came up", so the widget books its
     * own wake-up. Without one it drew the moon all morning and the sun all
     * night until the app happened to be opened — the one complication
     * whose whole purpose is telling you it is light out, wrong for hours.
     */
    @Test
    fun `the widget books a wake-up for the next change in the sky`() {
        located()
        val wait = ClockWidgetProvider.nextSkyChangeMs(context)
        assertTrue("something must be booked", wait > 0)
        assertTrue("and within the day", wait <= 24 * 60 * 60_000L)

        val now = java.util.Calendar.getInstance()
        val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        val skyNow = DayNight.sky(minuteNow)
        // Mid-crossing it polls instead, which is the next test.
        if (skyNow is DayNight.Sky.Twilight) return

        // The *next* change, not merely some change. Written first as "the
        // sky is different by the time it wakes", which a wake-up six hours
        // out satisfies at most hours of the day for no reason at all: the
        // sabotage that always booked six hours passed it.
        val minutes = (wait / 60_000L).toInt()
        for (ahead in 1 until minutes) {
            assertEquals(
                "nothing should have happened yet at +$ahead (from $skyNow at $minuteNow)",
                skyNow, DayNight.sky((minuteNow + ahead) % 1440)
            )
        }
        assertNotEquals(
            "and it must wake to a different sky",
            skyNow, DayNight.sky((minuteNow + minutes) % 1440)
        )
    }

    /** Mid-crossing it looks again soon, because the glyph is sliding. */
    @Test
    fun `it looks again quickly while the sun is crossing the horizon`() {
        located()
        val now = java.util.Calendar.getInstance()
        val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        if (DayNight.sky(minuteNow) is DayNight.Sky.Twilight) {
            assertTrue(ClockWidgetProvider.nextSkyChangeMs(context) <= 5 * 60_000L)
        }
    }

    /** With nowhere to stand there is no sunrise to wait for, and it says so. */
    @Test
    fun `with no location it sleeps rather than spinning`() {
        assertEquals(6 * 60 * 60_000L, ClockWidgetProvider.nextSkyChangeMs(context))
    }

    // -------------------------------------------------- the bubble physics

    private fun opened(cities: Set<String>): MainActivity {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WORLD_CLOCK, cities.isNotEmpty())
            .putStringSet(Prefs.WORLD_TZS, cities)
            .commit()
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
            .setup().get()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        return activity
    }

    /**
     * With no world clocks there is nothing to bounce, and nothing to
     * bounce should cost nothing.
     *
     * The loop that steps the bubbles used to be posted in onResume and
     * re-post itself every sixteen milliseconds until onPause, whatever
     * was on the screen. The cities are off out of the box, so the common
     * case — a phone sitting on a desk with the clock card open — was
     * sixty wake-ups a second to hand an empty list to a function that
     * returns immediately. No work and a great many wake-ups, which is the
     * shape of a thing a battery notices and a profiler does not.
     */
    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `with no world clocks the physics loop never runs`() {
        val activity = opened(emptySet())
        assertFalse(
            "sixty frames a second for an empty list",
            activity.bubblesRunningForTest()
        )
    }

    /**
     * And parked ones out of the hands' reach cost nothing either.
     *
     * Docked, a bubble sits above or below the dial and nothing can happen
     * to it that is not somebody doing something — so the loop is allowed
     * to hang up until they do.
     */
    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `docked bubbles let the loop hang up`() {
        val activity = opened(setOf("Europe/Madrid", "Asia/Tokyo"))
        assertEquals("the cities did not arrive", 2, activity.worldClocksForTest().size)
        assertFalse(
            "a scene standing still kept asking for frames",
            activity.bubblesRunningForTest()
        )
    }

    /** A knock shakes them loose, and that has to start it again. */
    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a knock starts the physics again`() {
        val activity = opened(setOf("Europe/Madrid", "Asia/Tokyo"))
        activity.worldBubblesForTest().free()
        assertTrue(
            "the bubbles were let go and nothing was stepping them",
            activity.bubblesRunningForTest()
        )
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertTrue(
            "and it stopped while they were still in flight",
            activity.bubblesRunningForTest()
        )
    }

    /** And putting everything back stops it again, without being told. */
    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the loop hangs itself up when the scene settles`() {
        val activity = opened(setOf("Europe/Madrid", "Asia/Tokyo"))
        activity.worldBubblesForTest().free()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertTrue("nothing was moving to begin with", activity.bubblesRunningForTest())

        activity.worldBubblesForTest().dock()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse(
            "everything was put back and the loop kept going",
            activity.bubblesRunningForTest()
        )
    }
}
