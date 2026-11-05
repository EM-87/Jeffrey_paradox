package com.em87.weirdclock

import android.os.HandlerThread
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * What a page swipe that starts on a hand does to the clock.
 *
 * A swipe from the clock to the alarms was felt to stick, and the seconds
 * tick to miss beats while it did. Not a long frame: measured in
 * [SwipeStallTest], the page change is a hundred milliseconds of work and
 * nothing in it decodes or bakes. It is the dial's own doing, in two parts.
 *
 * First, a finger landing anywhere near a hand takes hold of it on the
 * way down, and taking hold **freezes the whole mechanism** at that
 * instant and, for the second hand, **silences the beat**, which the sound
 * loop only reconsiders once a second. The dial then waits to see whether
 * the drag is a wind or a page turn, and if the pager takes the gesture
 * the hands spring home from the frozen instant. Every page swipe over a
 * hand costs the clock the length of that wait.
 *
 * Second, the wait is decided by one sample: the first move past the
 * ordinary touch slop (eight dp), and a stroke steeper than about
 * twenty-two degrees off level is a wind. The pager would decide at twice
 * that distance. A thumb setting off for the alarms is rarely level in
 * its first eight dp, and once the dial has claimed the gesture the page
 * cannot turn: the swipe winds the hand instead — the second hand, from
 * the edge of the screen where a swipe starts — and the beat is silent
 * for as long as the finger is down.
 *
 * Both measured here through the real touch path, on the real pager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SwipeFreezeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private var controller: ActivityController<MainActivity>? = null

    @After
    fun putTheClockBack() {
        TimeKeeper.solarOffsetMs = 0L
        controller?.pause()?.stop()?.destroy()
    }

    /**
     * The clock with its second hand at three o'clock, fifty milliseconds
     * before the next beat, so a finger put down now is held through the
     * sound loop's once-a-second look.
     */
    private fun clockAtQuarterPast(): Pair<MainActivity, ClockView> {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.ANALOG.key)
            .putBoolean(Prefs.TICKING, true)
            .putBoolean(Prefs.SECOND_HAND, true)
            .commit()
        AlarmStore.forget()
        AlarmStore.all(context).clear()
        AlarmStore.save(context)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = c
        val activity = c.get()
        val clock = activity.clockForTest()
        // Let the sound loop settle into its once-a-second rhythm.
        tick(2_100)
        // Fifteen seconds past the minute, fifty milliseconds short of the beat.
        TimeKeeper.solarOffsetMs = 14_950L - Math.floorMod(TimeKeeper.nowMs(), 60_000L)
        assertEquals(14_950L, Math.floorMod(TimeKeeper.nowMs(), 60_000L))
        assertTrue("the tick is not even wanted before the swipe", activity.ticksWantedForTest())
        return activity to clock
    }

    private fun ticksLooper() =
        (Thread.getAllStackTraces().keys.firstOrNull { it.name == "ticks" } as? HandlerThread)?.looper

    private fun tick(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        ticksLooper()?.let { Shadows.shadowOf(it).idle() }
    }

    /** A touch on the window, where the gesture really arrives. */
    private fun touch(activity: MainActivity, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        activity.window.decorView.dispatchTouchEvent(e)
        e.recycle()
    }

    /** The second hand's tip, in window coordinates, a little short of the end. */
    private fun secondHandTip(clock: ClockView): Pair<Float, Float> {
        val loc = IntArray(2)
        clock.getLocationInWindow(loc)
        val cx = clock.width / 2f
        val cy = clock.height / 2f
        val tip = clock.handTipForTest(ClockView.Hand.SECOND)
        return (loc[0] + cx + (tip.x - cx) * 0.97f) to (loc[1] + cy + (tip.y - cy) * 0.97f)
    }

    private val dp: Float get() = context.resources.displayMetrics.density

    /**
     * A finger that lands on the second hand and is held, barely moving,
     * for three tenths of a second — the time a slow swipe takes to cover
     * the pager's slop — before the pager takes the gesture.
     */
    @Test
    fun `a hand held while the swipe makes up its mind neither stops the clock nor silences the beat`() {
        val (activity, clock) = clockAtQuarterPast()
        val (x, y) = secondHandTip(clock)
        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        assertEquals("the swipe did not start on the second hand", ClockView.Hand.SECOND, clock.grabbedHandForTest())
        var silentWhileHeld = 0
        var frozenWhileHeld = 0
        for (step in 1..3) {
            tick(100)
            touch(activity, MotionEvent.ACTION_MOVE, x - step * 2f * dp, y)
            if (!activity.ticksWantedForTest()) silentWhileHeld++
            if (clock.mechanismFrozenForTest()) frozenWhileHeld++
        }
        touch(activity, MotionEvent.ACTION_CANCEL, x - 6f * dp, y)
        // Whatever the hold did to the reading is now the spring's to undo.
        val sprung = clock.handWindForTest()

        // Then three seconds of watching, a tenth at a time.
        var silentAfter = 0
        var springingFor = 0
        for (i in 1..30) {
            tick(100)
            if (!activity.ticksWantedForTest()) silentAfter++
            if (clock.isAnimatingForTest()) springingFor++
        }
        // The beat itself is not counted here: the beat thread keeps time
        // in uptime and the clock in wall time, and only the first of the
        // two moves under Robolectric, so its count means nothing. What
        // the sound loop *wants* is the whole of what the swipe can change.
        println(
            "FREEZE held 300 ms: frozen for %d of 3 looks, let go %.3f s off the time, springing for %d tenths after; beat unwanted for %d tenths held + %d tenths after".format(
                frozenWhileHeld, sprung, springingFor, silentWhileHeld, silentAfter
            )
        )
        assertEquals("the mechanism froze under a finger that was only passing", 0, frozenWhileHeld)
        assertEquals("the hands were let go off the time and had to spring back", 0.0, sprung, 0.0)
        assertEquals(
            "the beat was unwanted for ${silentWhileHeld + silentAfter} tenths of a second",
            0, silentWhileHeld + silentAfter
        )
    }

    /**
     * Drives one swipe towards the alarms: the first [slantDeg] degrees
     * off level for the first ten dp, then straight across. Returns how
     * many tenths the beat was unwanted for, over the swipe and the two
     * seconds after.
     */
    private fun swipeToTheAlarms(activity: MainActivity, clock: ClockView, slantDeg: Double): Int {
        val (x0, y0) = secondHandTip(clock)
        var x = x0
        var y = y0
        var silent = 0
        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        assertEquals("the swipe did not start on the second hand", ClockView.Hand.SECOND, clock.grabbedHandForTest())
        // A quarter more than the ordinary touch slop at the slant, in
        // three moves eight milliseconds apart — enough to cross the slop
        // the dial used to decide at, and not the pager's, which is twice
        // it. On a phone that is ten dp; Robolectric's ViewConfiguration
        // doubles both, so it is read rather than assumed.
        val vc = android.view.ViewConfiguration.get(context)
        println("FREEZE slops: touch %d px, paging %d px".format(vc.scaledTouchSlop, vc.scaledPagingTouchSlop))
        val slanted = vc.scaledTouchSlop * 1.25f / 3f
        val slant = Math.toRadians(slantDeg)
        for (i in 1..3) {
            tick(8)
            x -= (slanted * Math.cos(slant)).toFloat()
            y += (slanted * Math.sin(slant)).toFloat()
            touch(activity, MotionEvent.ACTION_MOVE, x, y)
            if (!activity.ticksWantedForTest()) silent++
        }
        // Then level, two hundred dp in a quarter of a second.
        for (i in 1..10) {
            tick(24)
            x -= 20f * dp
            touch(activity, MotionEvent.ACTION_MOVE, x, y)
            if (!activity.ticksWantedForTest()) silent++
        }
        touch(activity, MotionEvent.ACTION_UP, x, y)
        for (i in 1..20) {
            tick(100)
            if (!activity.ticksWantedForTest()) silent++
        }
        return silent
    }

    @Test
    fun `a level swipe towards the alarms turns the page`() {
        val (activity, clock) = clockAtQuarterPast()
        val pager = activity.findViewById<ViewPager2>(R.id.pager)
        val silent = swipeToTheAlarms(activity, clock, 0.0)
        println(
            "FREEZE level swipe: page %d, card %s, hand wound %.2f s, beat unwanted %d samples".format(
                pager.currentItem, activity.showingCardForTest(), clock.handWindForTest(), silent
            )
        )
        assertEquals("the page did not turn", Card.ALARM, activity.showingCardForTest())
    }

    /**
     * The one that failed. Thirty degrees off level for the first ten dp
     * is an ordinary thumb setting off sideways; the pager would still
     * have called it a page turn.
     */
    @Test
    fun `a swipe towards the alarms that starts a little off level turns the page too`() {
        val (activity, clock) = clockAtQuarterPast()
        val pager = activity.findViewById<ViewPager2>(R.id.pager)
        val silent = swipeToTheAlarms(activity, clock, 30.0)
        println(
            "FREEZE swipe 30° off level: page %d, card %s, hand wound %.2f s, beat unwanted %d samples".format(
                pager.currentItem, activity.showingCardForTest(), clock.handWindForTest(), silent
            )
        )
        assertEquals("the page did not turn", Card.ALARM, activity.showingCardForTest())
        assertEquals("the beat was unwanted for $silent samples", 0, silent)
    }
}
