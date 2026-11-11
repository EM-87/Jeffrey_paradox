package com.em87.weirdclock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Changing which clock this is, from the settings and back.
 *
 * Reported as two things: the change takes a long time, and now and then
 * it does not happen at all. Both were here to be measured.
 *
 * The one that does not happen is a face lent by a widget. Opened from a
 * widget pinned to the globe, the app wears the globe "for this visit" —
 * and the visit never ended: not on leaving the app, not on coming back
 * in from the launcher, and not on choosing another face in the
 * settings, which were about the stored face all along and were ignored
 * for as long as a lent one was being worn.
 *
 * The one that takes a long time is three costs in a row on the way back
 * from the settings: a frame of the old face, drawn once more before it
 * is thrown away; every widget on the home screen repainted in the pause
 * that the throwing away goes through; and then the new screen built
 * from nothing while somebody waits for it. The first two are not done
 * any more and the third is done behind the settings, as soon as the
 * face is picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceSwitchTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun settled(face: Face) {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
            .commit()
    }

    private fun idle() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    /** Lets the clock run on, and everything booked within that time with it. */
    private fun later(ms: Long) =
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun ms(block: () -> Unit): Double {
        val began = System.nanoTime()
        block()
        return (System.nanoTime() - began) / 1_000_000.0
    }

    private fun draw(root: View): Double {
        val bitmap = Bitmap.createBitmap(
            root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        return ms { root.draw(Canvas(bitmap)) }
    }

    private fun opened(intent: Intent? = null): ActivityController<MainActivity> {
        val controller = if (intent == null) {
            Robolectric.buildActivity(MainActivity::class.java)
        } else {
            Robolectric.buildActivity(MainActivity::class.java, intent)
        }
        controller.setup()
        idle()
        return controller
    }

    private fun fromTheWidgetOf(face: Face): Intent =
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_FACE, face.key)

    /** Into the settings the way a finger goes: the button, and then their screen over this one. */
    private fun intoSettings(main: ActivityController<MainActivity>): ActivityController<SettingsActivity> {
        main.get().openSettingsForTest()
        main.pause()
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        main.stop()
        idle()
        return settings
    }

    /** And back out of them with the back button, which finishes their screen. */
    private fun backOutOf(
        settings: ActivityController<SettingsActivity>,
        main: ActivityController<MainActivity>
    ) {
        settings.get().finish()
        settings.pause()
        main.restart().start().resume()
        settings.stop().destroy()
        idle()
    }

    /** A finger on one of the four faces in the settings' first row. */
    private fun tap(settings: ActivityController<SettingsActivity>, face: Face) {
        val cell = settings.get().window.decorView.findViewWithTag<View>(face)
        assertNotNull("the settings have no cell for $face", cell)
        cell.performClick()
    }

    // ------------------------------------------------------ the long wait

    /**
     * The face picked in the settings is built behind them, not on the
     * way back.
     *
     * Building this screen is most of what a change of face costs —
     * measured here, two to four hundred milliseconds on a desktop
     * processor, of which the pages being inflated and laid out is the
     * larger part — and it was all spent after the back button, with the
     * old clock on the glass. Now it is spent while the settings are
     * still in front, and nobody is looking at the screen it happens to:
     * so that screen does not tick, light its sensors, or count as having
     * arrived until somebody does come back to it.
     */
    @Test
    fun `a face picked in the settings is built before anybody comes back for it`() {
        settled(Face.HEMISPHERE)
        val main = opened()
        val old = main.get()
        assertEquals(Face.HEMISPHERE, old.faceForTest())
        val settings = intoSettings(main)
        tap(settings, Face.ANALOG)
        assertSame("it was rebuilt before the settings had redrawn", old, main.get())
        later(MainActivity.REBUILD_BEHIND_AFTER_MS + 200)

        val built = main.get()
        assertNotSame("nothing was built behind the settings", old, built)
        assertEquals(Face.ANALOG, built.faceForTest())
        assertTrue("it does not know it is behind anything", built.builtBehindForTest())
        assertEquals("it behaved as if somebody were looking at it", 0, built.arrivalsForTest())
        assertTrue("its pages are not built", built.pagesBuiltForTest() > 0)
        assertFalse("the clock says it is in front, from behind the settings", MainActivity.inFront)

        // And the way back is a restart of what is already there.
        val back = ms { backOutOf(settings, main) }
        assertSame("it was built a second time on the way back", built, main.get())
        assertEquals(1, built.arrivalsForTest())
        assertFalse(built.builtBehindForTest())
        assertTrue(MainActivity.inFront)
        println(
            "SWITCH back to a face built behind the settings: %.0f ms, then a frame of %.0f ms"
                .format(back, draw(built.window.decorView))
        )
        main.pause().stop().destroy()
    }

    /**
     * Somebody faster than the rebuild still gets the face, the way they
     * always did — and without the old one painted first.
     *
     * The screen coming back from the settings is restarted before it
     * can be rebuilt, and a restarted screen draws: one whole frame of
     * the clock that had just been changed, and on the globe that is the
     * most expensive frame in the app. Its pages are hidden the moment
     * the rebuild is asked for.
     */
    @Test
    fun `coming straight back still changes the face, and the old one is not painted again`() {
        settled(Face.HEMISPHERE)
        val main = opened()
        val old = main.get()
        val settings = intoSettings(main)
        tap(settings, Face.ANALOG)
        // Straight back: the rebuild behind the settings has not run.
        settings.get().finish()
        settings.pause()
        main.restart().start().resume()
        assertSame(old, main.get())
        assertFalse("the old face is on the glass on its way out", old.pagesShownForTest())
        val relaunch = ms { idle() }
        settings.stop().destroy()
        idle()
        assertNotSame("the face was not changed", old, main.get())
        assertEquals(Face.ANALOG, main.get().faceForTest())
        assertFalse("built in front and it thinks it is behind", main.get().builtBehindForTest())
        assertEquals(1, main.get().arrivalsForTest())
        assertTrue(main.get().pagesShownForTest())
        println("SWITCH rebuilt on the way back instead: %.0f ms".format(relaunch))
        main.pause().stop().destroy()
    }

    /**
     * A screen that is stopped is not laid out, so one built behind the
     * settings lays itself out.
     *
     * Robolectric lays a rebuilt screen out whatever state it is in; a
     * phone does not, and leaves the pages uninflated until the screen is
     * shown. So this is asked of a screen in the state a phone leaves one
     * in: created, started, resumed, and never measured.
     */
    @Test
    fun `a screen nobody has laid out can lay itself out`() {
        settled(Face.ANALOG)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val app = controller.get()
        assertEquals("the pages were built without a layout", 0, app.pagesBuiltForTest())
        app.layOutUnseen()
        assertEquals("the pages were not built", Cards.pagesOf(Face.ANALOG).size, app.pagesBuiltForTest())
        assertNotNull(app.clockForTest())
        assertTrue("the dial has no size", app.clockForTest().width > 0)
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------ the widgets' repaint

    /**
     * The widgets are repainted when the app is left, and not on the way
     * between its own screens.
     *
     * Every pause repainted every widget — each one a whole clock drawn
     * into a bitmap, fifteen to a hundred milliseconds apiece here — on
     * the thread the next screen was waiting for: on the way into the
     * settings, and again in the middle of every change of face.
     */
    @Test
    fun `the widgets are painted on leaving the app and not between its screens`() {
        settled(Face.HEMISPHERE)
        val main = opened()
        val before = ClockWidgetProvider.refreshes

        val settings = intoSettings(main)
        assertEquals("painted on the way into the settings", before, ClockWidgetProvider.refreshes)

        tap(settings, Face.ANALOG)
        later(MainActivity.REBUILD_BEHIND_AFTER_MS + 200)
        assertEquals(Face.ANALOG, main.get().faceForTest())
        assertEquals("painted by a rebuild", before, ClockWidgetProvider.refreshes)

        // Home, from the settings: that is the app being left.
        settings.pause().stop()
        assertEquals("not painted on leaving through the settings", before + 1, ClockWidgetProvider.refreshes)

        // Back in, back to the clock, and home from there.
        settings.restart().start().resume()
        backOutOf(settings, main)
        assertEquals("painted on the way back to the clock", before + 1, ClockWidgetProvider.refreshes)
        main.pause().stop()
        assertEquals("not painted on leaving from the clock", before + 2, ClockWidgetProvider.refreshes)
        main.destroy()
    }

    /** And a rebuild on the way back does not paint them either. */
    @Test
    fun `a face rebuilt on the way back paints no widgets`() {
        settled(Face.ANALOG)
        val main = opened()
        val old = main.get()
        val settings = intoSettings(main)
        val before = ClockWidgetProvider.refreshes
        prefs.edit().putString(Prefs.FACE, Face.DIGITAL.key).commit()
        backOutOf(settings, main)
        assertNotSame(old, main.get())
        assertEquals(Face.DIGITAL, main.get().faceForTest())
        assertEquals("the rebuild painted the widgets", before, ClockWidgetProvider.refreshes)
        main.pause().stop().destroy()
    }

    // --------------------------------------------------- the face not changed

    /**
     * A face picked in the settings is the face, whatever a widget lent.
     *
     * Opened from the globe's widget with the dial stored, the settings
     * show the dial as the clock that is worn — and a tap on it wrote
     * nothing, because it was already stored, and changed nothing,
     * because a lent face was not compared with the stored one. Picking
     * another face changed nothing either.
     */
    @Test
    fun `a face lent by a widget gives way to one picked in the settings, the stored one too`() {
        for (picked in listOf(Face.ANALOG, Face.DIGITAL)) {
            settled(Face.ANALOG)
            val main = opened(fromTheWidgetOf(Face.HEMISPHERE))
            assertEquals(Face.HEMISPHERE, main.get().faceForTest())
            assertEquals(Face.HEMISPHERE, main.get().borrowedFaceForTest())
            val settings = intoSettings(main)
            tap(settings, picked)
            later(MainActivity.REBUILD_BEHIND_AFTER_MS + 200)
            backOutOf(settings, main)
            assertEquals("the settings said $picked and were not heard", picked, main.get().faceForTest())
            assertNull("the visit is still on", main.get().borrowedFaceForTest())
            main.pause().stop().destroy()
        }
    }

    /**
     * And when the clock's screen is not there to be told.
     *
     * A phone short of room throws away the screens behind the one in
     * front and builds them again from what was written down. A face
     * picked while the clock's screen did not exist has to end the visit
     * all the same, or the clock comes back wearing what the widget lent.
     */
    @Test
    fun `a face picked while the clock's screen is gone still ends the visit`() {
        settled(Face.ANALOG)
        val main = opened(fromTheWidgetOf(Face.HEMISPHERE))
        val settings = intoSettings(main)
        // Thrown away behind the settings, with what it would be rebuilt from.
        val kept = android.os.Bundle()
        main.saveInstanceState(kept)
        main.destroy()
        tap(settings, Face.ANALOG)
        later(MainActivity.REBUILD_BEHIND_AFTER_MS + 200)
        settings.get().finish()
        settings.pause()
        val restored = Robolectric.buildActivity(MainActivity::class.java, fromTheWidgetOf(Face.HEMISPHERE))
            .create(kept).start().resume().visible()
        settings.stop().destroy()
        idle()
        assertEquals("rebuilt from nothing, it borrowed again", Face.ANALOG, restored.get().faceForTest())
        restored.pause().stop().destroy()
    }

    /** Looking at the settings and touching no face is not an answer, and the visit goes on. */
    @Test
    fun `a look at the settings does not end a visit`() {
        settled(Face.ANALOG)
        val main = opened(fromTheWidgetOf(Face.HEMISPHERE))
        val lent = main.get()
        val settings = intoSettings(main)
        later(MainActivity.REBUILD_BEHIND_AFTER_MS + 200)
        backOutOf(settings, main)
        assertSame("the visit was ended by opening the settings", lent, main.get())
        assertEquals(Face.HEMISPHERE, main.get().faceForTest())
        main.pause().stop().destroy()
    }

    /**
     * A lent face is given back when the app has been left.
     *
     * "For this visit" was written above the borrowing and nothing ended
     * the visit: home, and in again from the launcher a day later, was
     * still the globe.
     */
    @Test
    fun `a lent face is given back once the app has been left`() {
        settled(Face.ANALOG)
        val main = opened(fromTheWidgetOf(Face.HEMISPHERE))
        assertEquals(Face.HEMISPHERE, main.get().faceForTest())
        // Home.
        main.pause().stop()
        // And in again, by the launcher or the list of recent apps.
        main.restart().start().resume()
        idle()
        assertEquals("the globe outlived the visit", Face.ANALOG, main.get().faceForTest())
        assertNull(main.get().borrowedFaceForTest())
        // The visit stays over through another rebuild: the request that
        // began it is not read a second time.
        main.recreate()
        idle()
        assertEquals("the widget's request was read again", Face.ANALOG, main.get().faceForTest())
        // And through the app being thrown away and brought back, which
        // hands the screen the request it was first opened with.
        val kept = android.os.Bundle()
        main.saveInstanceState(kept)
        main.pause().stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, fromTheWidgetOf(Face.HEMISPHERE))
            .create(kept).start().resume().visible()
        idle()
        assertEquals("brought back from nothing, it borrowed again", Face.ANALOG, restored.get().faceForTest())
        restored.pause().stop().destroy()
        // Tapped again from cold, the widget is a new visit.
        val again = opened(fromTheWidgetOf(Face.HEMISPHERE))
        assertEquals("the widget no longer lends its face", Face.HEMISPHERE, again.get().faceForTest())
        again.pause().stop().destroy()
    }

    /**
     * The same widget tapped again is a visit to the same face, and
     * nothing is rebuilt for it.
     *
     * The request arrives after the screen has been resumed once, which
     * is why giving the face back waits a moment: ended on arriving, the
     * visit the widget was asking for would be the one just thrown away,
     * and the globe would be torn down to build the globe.
     */
    @Test
    fun `the same widget tapped again goes on with the same face`() {
        settled(Face.ANALOG)
        val main = opened(fromTheWidgetOf(Face.HEMISPHERE))
        val lent = main.get()
        main.pause().stop()
        // The order a phone delivers them in: back to the front, and then
        // the request, between a pause and a resume.
        main.restart().start().resume().pause()
        main.newIntent(fromTheWidgetOf(Face.HEMISPHERE))
        main.resume()
        idle()
        assertSame("the globe was rebuilt to show the globe", lent, main.get())
        assertEquals(Face.HEMISPHERE, main.get().faceForTest())
        assertEquals(Face.HEMISPHERE, main.get().borrowedFaceForTest())
        // And another widget is another visit, to another face. What is
        // built for it is not looked at here: a phone rebuilds a screen
        // with the request it was last handed and Robolectric with the
        // one it was first opened with, so the rebuilt screen here would
        // be the globe again whatever this code did.
        main.pause().stop()
        main.restart().start().resume().pause()
        val arrived = lent.arrivalsForTest()
        main.newIntent(fromTheWidgetOf(Face.SUNDIAL))
        assertEquals(Face.SUNDIAL, lent.borrowedFaceForTest())
        assertTrue(
            "no rebuild was asked for: ${MainActivity.lastRecreateReason}",
            MainActivity.lastRecreateReason.orEmpty().startsWith("borrowed HEMISPHERE->SUNDIAL")
        )
        assertEquals(
            "the request was not kept for the rebuild to read",
            Face.SUNDIAL.key, lent.intent.getStringExtra(MainActivity.EXTRA_SHOW_FACE)
        )
        // The resume that follows the request is of a screen already on
        // its way out, and it does not start ticking to be thrown away.
        main.resume()
        assertEquals("a screen about to be rebuilt arrived in front", arrived, lent.arrivalsForTest())
        idle()
        main.pause().stop().destroy()
    }

    /**
     * A clock hidden for a rebuild that never comes is shown again.
     *
     * The pages are hidden the moment a rebuild is asked for, and a
     * rebuild asked for has always arrived. If one ever did not, the
     * screen would be a blank one for as long as it lived — so a screen
     * resumed well after asking, and still there, puts its pages back.
     */
    @Test
    fun `a rebuild that never happens does not leave the clock hidden`() {
        settled(Face.ANALOG)
        val main = opened()
        val app = main.get()
        val arrived = app.arrivalsForTest()
        app.askToBeRebuiltForTest("a test")
        assertFalse(app.pagesShownForTest())
        // Time passes and the rebuild has not happened: the looper is not
        // run, which is the request being lost.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        main.pause().resume()
        assertSame(app, main.get())
        assertTrue("the clock stayed hidden", app.pagesShownForTest())
        assertEquals("and it did not come back to life", arrived + 1, app.arrivalsForTest())
        idle()
        main.pause().stop().destroy()
    }
}
