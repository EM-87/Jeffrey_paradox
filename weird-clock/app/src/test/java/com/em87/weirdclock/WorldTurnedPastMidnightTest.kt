package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * A world turned by hand past midnight does not stop to load the next
 * day.
 *
 * Reported from a phone: the earth "stutters between one day and the
 * next, to load images, I suppose". It was reading two photographs off
 * the disc for the veil, one for the ground and two for the lights, and
 * making a sky of them, inside the frame in which the day changed. The
 * looking up is done somewhere else now and worn when it comes back.
 *
 * What is held here is where the looking up is done and what the globe
 * wears meanwhile — not how long a frame takes, which is a fact about a
 * phone and not about this machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorldTurnedPastMidnightTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val noon = DailyImageStore.msOf("2026-11-22")!! + 12 * hour

    /** A sky for each day asked about, and a note of every asking: which instant, on which thread. */
    private class Store {
        val asked = ArrayList<Pair<Long, Thread>>()
        private val skies = HashMap<Long, SatelliteClouds.Field>()
        fun sky(dayNumber: Long): SatelliteClouds.Field =
            synchronized(skies) { skies.getOrPut(dayNumber) { SatelliteClouds.Field(4, 2, ByteArray(8)) } }
        val hook: (Long) -> SatelliteClouds.Field? = { ms ->
            synchronized(asked) { asked += ms to Thread.currentThread() }
            sky(ms / 86_400_000L)
        }
    }

    /** Somewhere else, which does nothing until it is told to. */
    private class Elsewhere : Executor {
        val waiting = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            waiting.addLast(command)
        }

        /** The oldest thing waiting, done on a thread that is not this one. */
        fun doOne() {
            val work = waiting.removeFirst()
            val other = Thread(work, "somewhere-else")
            other.start()
            other.join(5_000)
        }
    }

    private fun globe(store: Store, elsewhere: Executor?): HemisphereView = HemisphereView(context).apply {
        theme = ClockThemes.MIDNIGHT
        view = Hemisphere.View.NORTH
        atMs = noon
        showMoon = false
        located = false
        cloudsOn = store.hook
        lookUp = elsewhere
        measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 400, 400)
    }

    private fun draw(v: View) {
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dayOf(ms: Long): Long = ms / 86_400_000L

    /**
     * The day is asked for as the frame is drawn only while the world
     * stands at now. Turned to another day, the frame is drawn in what
     * the world already wears and the question goes somewhere else.
     */
    @Test
    fun `turned past midnight, the next day is looked up somewhere else and worn when it comes`() {
        val store = Store()
        val elsewhere = Elsewhere()
        val world = globe(store, elsewhere)
        val here = Thread.currentThread()

        draw(world)
        assertEquals("a world standing at now did not look its day up as it drew", 1, store.asked.size)
        assertSame(here, store.asked[0].second)
        assertSame(store.sky(dayOf(noon)), world.clouds)
        assertTrue("something was put off for a world standing still", elsewhere.waiting.isEmpty())

        // Thirteen hours on: tomorrow.
        world.windForTest(13 * hour)
        draw(world)
        assertEquals("the next day was looked up inside the frame that crossed midnight", 1, store.asked.size)
        assertEquals(1, elsewhere.waiting.size)
        assertTrue(world.lookingUpForTest())
        assertSame("the world took off its weather to wait for the next day's", store.sky(dayOf(noon)), world.clouds)

        elsewhere.doOne()
        assertEquals(2, store.asked.size)
        assertEquals("the day asked about was not the one turned to", dayOf(noon + 13 * hour), dayOf(store.asked[1].first))
        assertTrue("it was looked up on the thread that draws", store.asked[1].second !== here)
        assertSame("the answer was worn before it had been handed over", store.sky(dayOf(noon)), world.clouds)
        idle()
        assertSame("the next day's weather never arrived", store.sky(dayOf(noon + 13 * hour)), world.clouds)
        assertFalse(world.lookingUpForTest())
    }

    /**
     * A world thrown hard goes through days faster than they can be
     * looked up. One question at a time, and the next is about where the
     * world has got to — not about every day it passed on the way.
     */
    @Test
    fun `days passed while one is being looked up are not looked up`() {
        val store = Store()
        val elsewhere = Elsewhere()
        val world = globe(store, elsewhere)
        draw(world)
        world.windForTest(1 * day)
        draw(world)
        for (days in 2..6) {
            world.windForTest(days * day)
            draw(world)
        }
        assertEquals("a question was put for every day passed", 1, elsewhere.waiting.size)
        elsewhere.doOne()
        idle()
        // The first answer is worn, and the one question that follows is about the sixth day.
        assertSame(store.sky(dayOf(noon + 1 * day)), world.clouds)
        assertEquals(1, elsewhere.waiting.size)
        elsewhere.doOne()
        idle()
        assertSame(store.sky(dayOf(noon + 6 * day)), world.clouds)
        assertTrue("it went on asking with nothing left to ask", elsewhere.waiting.isEmpty())
        assertEquals("the days asked about", listOf(0L, 1L, 6L), store.asked.map { dayOf(it.first) - dayOf(noon) })
    }

    /**
     * Let go, the world springs back to now and asks for today's weather
     * as it draws. An answer still on its way about the day it had been
     * turned to is not laid over that when it lands.
     */
    @Test
    fun `an answer that comes back to a world let go is not worn`() {
        val store = Store()
        val elsewhere = Elsewhere()
        val world = globe(store, elsewhere)
        draw(world)
        world.windForTest(3 * day)
        draw(world)
        assertEquals(1, elsewhere.waiting.size)
        // Let go before the answer is back.
        world.windForTest(0L)
        draw(world)
        assertSame("back at now the world did not ask for today's as it drew", store.sky(dayOf(noon)), world.clouds)
        elsewhere.doOne()
        idle()
        assertSame("the day the world had been turned to was laid over today", store.sky(dayOf(noon)), world.clouds)
        assertFalse(world.lookingUpForTest())
        assertTrue("and it went on asking about a day it had left", elsewhere.waiting.isEmpty())
    }

    /** With nowhere else to ask, it is asked as it always was — which is what the widget's one picture needs. */
    @Test
    fun `a globe with nowhere else to ask looks the day up as it draws`() {
        val store = Store()
        val world = globe(store, null)
        draw(world)
        world.windForTest(13 * hour)
        draw(world)
        assertEquals(2, store.asked.size)
        assertSame(store.sky(dayOf(noon + 13 * hour)), world.clouds)
        assertFalse(world.lookingUpForTest())
    }

    /** A store that cannot answer leaves the world as it was, and able to ask again. */
    @Test
    fun `a day that cannot be looked up leaves the world as it is`() {
        val store = Store()
        val elsewhere = Elsewhere()
        val world = globe(store, elsewhere)
        draw(world)
        var broken = false
        world.cloudsOn = { ms -> if (broken) throw IllegalStateException("no such day") else store.hook(ms) }
        draw(world)
        val worn = world.clouds
        assertSame(store.sky(dayOf(noon)), worn)
        broken = true
        world.windForTest(13 * hour)
        draw(world)
        elsewhere.doOne()
        idle()
        assertSame("a store that threw took the weather off the world", worn, world.clouds)
        assertFalse("and left it waiting for ever", world.lookingUpForTest())
        broken = false
        world.windForTest(37 * hour)
        draw(world)
        elsewhere.doOne()
        idle()
        assertSame(store.sky(dayOf(noon + 37 * hour)), world.clouds)
    }

    /** And the app's own world is given somewhere else to ask, and asks there. */
    @Test
    fun `the app's world asks on a thread of its own`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.HEMISPHERE.key)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            assertSame("the app's world looks things up on the thread that draws", HemisphereView.ELSEWHERE, world.lookUp)
            val store = Store()
            val asked = CountDownLatch(1)
            world.cloudsOn = { ms ->
                val sky = store.hook(ms)
                if (dayOf(ms) != dayOf(TimeKeeper.nowMs())) asked.countDown()
                sky
            }
            draw(world)
            val here = Thread.currentThread()
            world.windForTest(3 * day)
            draw(world)
            assertTrue("the next day was never looked up", asked.await(5, TimeUnit.SECONDS))
            val elsewhere = store.asked.last().second
            assertTrue("it was looked up on the thread that draws", elsewhere !== here)
            assertEquals("world-weather", elsewhere.name)
            // Handed over on this thread, when this thread next looks.
            val deadline = System.currentTimeMillis() + 5_000
            while (world.lookingUpForTest() && System.currentTimeMillis() < deadline) {
                idle()
                Thread.sleep(5)
            }
            assertSame(store.sky(dayOf(store.asked.last().first)), world.clouds)
        }
    }
}
