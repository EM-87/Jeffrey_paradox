package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One widget per clock, and each one is the clock it says it is.
 *
 * There was a single clock widget that drew whichever face the app was set
 * to, which is a sensible thing for a clock to do and the wrong thing for
 * a widget to be: somebody who wants the sundial beside the digital clock
 * could have exactly one, and it was whichever they had last chosen in the
 * app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetPanoplyTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString(Prefs.FACE, Face.ANALOG.key)
            .commit()
    }

    /** Every face has one, and the original still follows the app. */
    @Test
    fun `there is one of each and one that follows`() {
        val kinds = ClockWidgetProvider.KINDS
        assertEquals("a face is missing a widget of its own", Face.entries.size + 1, kinds.size)
        assertEquals("the first is not the one that follows the app", null, kinds.first().second)
        for (face in Face.entries) {
            assertTrue(
                "$face has no widget of its own",
                kinds.any { it.second == face }
            )
        }
        // And each of them is a real, declared receiver: a provider the
        // manifest has never heard of is a widget nobody can add.
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        assertNotNull(manager)
        for ((cls, _) in kinds) {
            val info = context.packageManager.getReceiverInfo(
                android.content.ComponentName(context, cls), 0
            )
            assertNotNull("${cls.simpleName} is not in the manifest", info)
        }
    }

    /**
     * And a pinned widget wakes at its own face's rate whatever the app
     * is set to.
     *
     * A digital clock is wrong the moment the minute turns and a sundial's
     * shadow is worth redrawing every ten minutes; a widget that took the
     * app's answer would have the sundial repainting sixty times an hour
     * or the digital clock sitting on the same minute until sunset.
     */
    @Test
    fun `each kind sleeps for its own face`() {
        val digits = ClockWidgetProvider.nextRepaintMs(context, Face.DIGITAL)
        val plate = ClockWidgetProvider.nextRepaintMs(context, Face.SUNDIAL)
        assertTrue("the digital clock sleeps past a minute", digits <= 60_000L)
        assertTrue("the sundial repaints as often as a digital clock", plate > 60_000L)
        // The app is set to the dial, whose own answer is the next change
        // in the sky — hours away. Asking without a face gives that one,
        // which is the whole point of being able to pass one in.
        val following = ClockWidgetProvider.nextRepaintMs(context)
        assertTrue(
            "the pinned face was ignored: following $following, digital $digits",
            following > 5 * 60_000L
        )
    }
}
