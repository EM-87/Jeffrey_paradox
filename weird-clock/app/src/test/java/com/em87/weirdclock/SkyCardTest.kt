package com.em87.weirdclock

import android.view.MotionEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
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
 * The card that is nothing but sky, and the two ways out of it that were
 * missing.
 *
 * On the face made of planet the solar system gets a card of its own where
 * the month page would be. It is the dial, but not being a dial: no hands,
 * no readout, no sun token. Which means the two gestures the dial answers
 * on the *clock* card had nowhere to land here — one of them left a blank
 * rectangle with no way back into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SkyCardTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun onTheWorld() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.HEMISPHERE.key)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    private fun tap(view: ClockView, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val at = android.os.SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(at, at, action, x, y, 0)
            view.onTouchEvent(e)
            e.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(600))
    }

    /**
     * The Sun does not shut a card that has nothing else on it.
     *
     * On the clock card the Sun is the way out: press it and the planets
     * give the dial back to the hands. Here there are no hands to give it
     * back to — the readout is suppressed too — so pressing it emptied the
     * card and left no way to fill it again. The way out of this card is
     * the swipe that got you onto it.
     */
    @Test
    fun `the sun cannot empty the card that is only sky`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val sky = c.get().orreryCardForTest()
            assertNotNull("the world's face has no sky card", sky)
            // The sky opens with a fade, and a fade that has not been
            // given any time has not started.
            ShadowSystemClock.advanceBy(Duration.ofMillis(900))
            assertTrue("the sky card did not open its sky", sky!!.orreryShowing())
            tap(sky, sky.width / 2f, sky.height / 2f)
            ShadowSystemClock.advanceBy(Duration.ofMillis(900))
            assertTrue(
                "the sun emptied the card and left nothing to tap",
                sky.orreryShowing()
            )
        }
    }

    /**
     * And there is a toolbox on this page too.
     *
     * There is one on the clock's own page and it could not reach this
     * card: the button is found by id inside the page it is on, and this
     * card is on a different page. So planets knocked out of their orbits
     * here stayed on the floor, with the one thing that picks them up a
     * swipe away and looking at a different mess.
     */
    @Test
    fun `the sky card has something to tidy it with`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val sky = c.get().orreryCardForTest()!!
            assertTrue("no toolbox is offered before there is a mess to tidy",
                !c.get().orreryToolboxShowing())
            sky.knockHandsOff()
            assertTrue("nothing fell", sky.isDisarranged())
            c.get().showReassembleForTest()
            assertTrue(
                "the planets are on the floor and there is nothing to pick them up with",
                c.get().orreryToolboxShowing()
            )
        }
    }
}
