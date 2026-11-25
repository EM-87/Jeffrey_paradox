package com.em87.weirdclock

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Nobody looking, nothing listening.
 *
 * Two listeners outlived the screen they were for. The sand hung its
 * accelerometer on attach and detach, and the view is never detached
 * while the activity lives, so going to the home screen left it reading
 * at game rate. The compass on the sundial and on the globe was let go
 * only when its switch was turned off, so a face held up at the sun went
 * on hearing the rotation vector in the pocket afterwards. On Android 7
 * and 8, and on any version while the countdown's or the alarm's service
 * runs, a stopped app still gets its readings.
 *
 * And the ring screen, which is the one that runs with that service
 * behind it every time, asked for its light meter and its accelerometer
 * when it was built and gave them back only when it was destroyed. An
 * alarm whose screen has been switched off is stopped, not destroyed.
 *
 * Every question here is asked of Robolectric's sensor manager, which
 * keeps the registrations: the thing that would deliver the readings, and
 * not a flag the app keeps about them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SensorsInTheBackgroundTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val shadow: ShadowSensorManager
        get() = Shadows.shadowOf(context.getSystemService(SensorManager::class.java))

    private lateinit var accelerometer: Sensor
    private lateinit var rotation: Sensor

    /**
     * A window that is on show says so.
     *
     * By default Robolectric adds every window as one the app cannot see,
     * so the window of a resumed activity reports itself gone, and nothing
     * that follows the window's visibility can be tested at all. Its own
     * source calls that a bug and offers this switch while tests move
     * over. It is set for this class only, and only for as long as each
     * test runs.
     *
     * A window on show is drawn on every frame, and the sand asks for the
     * next frame from each one it draws. Left to itself, Robolectric hands
     * over a frame the moment one is asked for and moves its clock on to
     * match, so a looper told to run until it is idle never is. Paused,
     * the frames wait for the clock like a phone's do, one every sixteen
     * milliseconds. Robolectric puts both back after every test.
     */
    @Before
    fun windowsOnShow() {
        System.setProperty(WINDOWS_VISIBLE, "true")
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After
    fun windowsAsTheyWere() {
        System.clearProperty(WINDOWS_VISIBLE)
    }

    /** The service's clock is a static, and outlives the test that set it. */
    @After
    fun nothingRinging() {
        AlarmService.ringingSince = 0L
    }

    @Before
    fun sensors() {
        accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        rotation = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
        shadow.addSensor(accelerometer)
        shadow.addSensor(rotation)
    }

    private fun settled(face: Face, more: (android.content.SharedPreferences.Editor) -> Unit = {}) {
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
        more(edit)
        edit.commit()
    }

    /** Whether anything at all is registered for [sensor]. */
    private fun anyoneHears(sensor: Sensor): Boolean =
        shadow.getListeners().any { shadow.hasListener(it, sensor) }

    /** The looper run on for a second: a card's fade takes 700 milliseconds. */
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    /**
     * Built, resumed, and laid out. The pages are bound on the first
     * layout, which waits for a frame like everything else here.
     */
    private fun open(c: ActivityController<MainActivity>) {
        c.setup()
        idle()
    }

    /**
     * Stopped, and the window hidden.
     *
     * Robolectric stops an activity and leaves its window showing. A
     * phone does both at once: right after onStop the system makes the
     * decor view invisible, and the window's visibility follows on the
     * next traversal. That half is done here by hand, the same way.
     */
    private fun hidden(c: ActivityController<out Activity>) {
        c.stop()
        c.get().window.decorView.visibility = View.INVISIBLE
        idle()
    }

    /**
     * And back: started, resumed, and the window shown again.
     *
     * A tenth of a second of looper, which is a few frames: enough for the
     * window's visibility to reach the views, and well short of the half
     * second the sand waits for its first reading.
     */
    private fun back(c: ActivityController<out Activity>) {
        c.restart().resume().visible()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    /** Readings of gravity down the phone's own axes, to the accelerometer's listeners. */
    private fun reading(x: Float, y: Float, times: Int = 1) {
        repeat(times) {
            val event = shadow.createSensorEvent()
            // A SensorEvent cannot be built by hand: the constructor is
            // hidden and the values sit in a final array — see
            // [HourglassFlipTest].
            val field = SensorEvent::class.java.getField("values")
            field.isAccessible = true
            field.set(event, floatArrayOf(x, y, 0f))
            shadow.sendSensorEventToListeners(event, accelerometer)
        }
    }

    /** Frames of the glass, forty milliseconds apart, each one a step of the sand. */
    private fun frames(sand: SandHourglassView, count: Int) {
        val canvas = Canvas(Bitmap.createBitmap(sand.width, sand.height, Bitmap.Config.ARGB_8888))
        repeat(count) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(40))
            sand.draw(canvas)
        }
    }

    private fun lettingGoOfTheCompass(c: ActivityController<MainActivity>) {
        open(c)
        val app = c.get()
        val held = app.bearingListenerForTest()
        assertNotNull("set up wrong: the face never asked for the compass", held)
        assertTrue("set up wrong: asked for and not registered", shadow.hasListener(held!!, rotation))

        c.pause()
        assertFalse("the compass was still being read after the pause", anyoneHears(rotation))
        assertNull(
            "a listener kept after letting go is one the resume takes for registered",
            app.bearingListenerForTest()
        )

        c.resume()
        val again = app.bearingListenerForTest()
        assertNotNull("the resume did not ask for the compass again", again)
        assertTrue("asked for again and not registered", shadow.hasListener(again!!, rotation))
    }

    /** "Hold it in your hand": the sundial's compass goes with the pause and comes back with the resume. */
    @Test
    fun `the dial in the hand lets go of the compass on pause`() {
        settled(Face.SUNDIAL) { it.putBoolean(Prefs.SUNDIAL_COMPASS, true) }
        Robolectric.buildActivity(MainActivity::class.java).use { lettingGoOfTheCompass(it) }
    }

    /**
     * "Point the phone at the sun": the globe's, the same.
     *
     * Pointing needs a fix. Without one the sun stays where the setting
     * nails it and the compass is never asked for, which would pass this
     * test for the wrong reason.
     */
    @Test
    fun `the world pointed at the sun lets go of the compass on pause`() {
        settled(Face.HEMISPHERE) {
            it.putBoolean(Prefs.HEMISPHERE_COMPASS, true)
                .putFloat(Prefs.LAST_LATITUDE, 40.4f)
                .putFloat(Prefs.LAST_LONGITUDE, -3.7f)
        }
        Robolectric.buildActivity(MainActivity::class.java).use { lettingGoOfTheCompass(it) }
    }

    /** The resume asks the setting again, and a switch turned off while away stays off. */
    @Test
    fun `a compass switched off while away does not come back`() {
        settled(Face.SUNDIAL) { it.putBoolean(Prefs.SUNDIAL_COMPASS, true) }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            open(c)
            assertTrue("set up wrong", anyoneHears(rotation))
            c.pause()
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(Prefs.SUNDIAL_COMPASS, false).commit()
            c.resume()
            assertFalse("the compass came back for a dial that no longer wants it", anyoneHears(rotation))
        }
    }

    /**
     * And nothing else picks it up while the app is away.
     *
     * The preferences are applied at moments besides the resume: an
     * answer to a permission arrives before it, and a page bound on a
     * screen rebuilt behind another applies them with nobody looking.
     * Each of those used to register the compass, and nothing paused
     * afterwards to let go of it.
     */
    @Test
    fun `preferences applied while paused do not pick the compass up`() {
        settled(Face.SUNDIAL) { it.putBoolean(Prefs.SUNDIAL_COMPASS, true) }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            open(c)
            assertTrue("set up wrong", anyoneHears(rotation))
            c.pause()
            c.get().applyPreferencesForTest()
            assertFalse("the compass was picked up by a paused screen", anyoneHears(rotation))
            assertNull("and held as if it were", c.get().bearingListenerForTest())
        }
    }

    /**
     * The sand listens on its own card, and not from behind the clock.
     *
     * The glass is in the clock's page whichever card is showing, and its
     * container is gone everywhere but on the hourglass's card.
     */
    @Test
    fun `the sand listens only while it can be seen`() {
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            open(c)
            val app = c.get()
            val ear = app.sandForTest()!!.sensorListenerForTest()
            assertFalse("the sand was listening from behind the clock", shadow.hasListener(ear))

            app.goToForTest(Card.HOURGLASS)
            idle()
            assertTrue("set up wrong", app.cardShowingForTest(Card.HOURGLASS))
            assertTrue("the sand on screen did not feel the phone", shadow.hasListener(ear, accelerometer))

            app.goToForTest(Card.CLOCK)
            idle()
            assertFalse("set up wrong", app.cardShowingForTest(Card.HOURGLASS))
            assertFalse("the sand went on listening behind the clock", shadow.hasListener(ear))
        }
    }

    /**
     * Nothing at all is left listening once the app has gone.
     *
     * Asked of every registration and not only of the two that were found
     * lingering. On the sundial, which has both the sand and the compass,
     * with the sand on screen and the compass switched on.
     *
     * The compass goes with the pause. The sand goes with the window, which
     * on a phone goes when the app is stopped: a paused screen can still be
     * on show beside another app, and its sand still falls there. Both
     * come back when the app does.
     */
    @Test
    fun `nothing is left listening in the background`() {
        settled(Face.SUNDIAL) { it.putBoolean(Prefs.SUNDIAL_COMPASS, true) }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            open(c)
            val app = c.get()
            app.goToForTest(Card.HOURGLASS)
            idle()
            val ear = app.sandForTest()!!.sensorListenerForTest()
            assertTrue("set up wrong: the sand is not listening", shadow.hasListener(ear, accelerometer))
            assertTrue("set up wrong: the compass is not", anyoneHears(rotation))

            c.pause()
            assertFalse("the compass outlived the pause", anyoneHears(rotation))

            hidden(c)
            assertEquals(
                "still listening with the app in the background",
                emptyList<Any>(), shadow.getListeners()
            )

            back(c)
            assertTrue("the sand did not pick the sensor back up", shadow.hasListener(ear, accelerometer))
            assertTrue("nor the compass", anyoneHears(rotation))
        }
    }

    /**
     * Back on screen, the sand waits to be told which way is down.
     *
     * It was last told upside down, and the phone has been put down the
     * right way up since. Until the first reading arrives the only posture
     * the sand knows is the old one. Poured by that, the pile falls the
     * wrong way the moment the glass is seen again. And the first reading
     * is taken whole: blended into the old posture, the way the readings
     * after it are, it would still say upside down.
     */
    @Test
    fun `the sand does not pour by a posture from before it went away`() {
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            open(c)
            val app = c.get()
            app.goToForTest(Card.HOURGLASS)
            idle()
            val sand = app.sandForTest()!!
            assertTrue("set up wrong: the glass has no size", sand.width > 0 && sand.height > 0)
            // On its head, for long enough that the smoothing believes it.
            reading(0f, -9.81f, times = 80)
            assertTrue("set up wrong: the sand never heard it", sand.gravityForTest().second < -0.5f)

            c.pause()
            hidden(c)
            back(c)
            assertTrue(
                "set up wrong: the sand is not listening again",
                shadow.hasListener(sand.sensorListenerForTest(), accelerometer)
            )

            val before = sand.cellsForTest()
            frames(sand, 8)
            assertArrayEquals("the sand poured by where the phone used to be", before, sand.cellsForTest())

            reading(0f, 9.81f)
            assertTrue(
                "the first reading back was blended with the old posture: ${sand.gravityForTest()}",
                sand.gravityForTest().second > 0.5f
            )
        }
    }

    /**
     * But it does not wait for ever.
     *
     * A sensor that registers and then says nothing is no reason to stop
     * the sand: after a moment it goes on with what it last knew, which is
     * what it always did.
     */
    @Test
    fun `a quiet sensor does not hold the sand for ever`() {
        settled(Face.ANALOG)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            open(c)
            val app = c.get()
            app.goToForTest(Card.HOURGLASS)
            idle()
            val sand = app.sandForTest()!!
            reading(0f, -9.81f, times = 80)
            c.pause()
            hidden(c)
            back(c)

            val before = sand.cellsForTest()
            frames(sand, 25)
            assertFalse(
                "a second with no reading and the sand had not moved",
                before.contentEquals(sand.cellsForTest())
            )
        }
    }

    // ------------------------------------------------------- the ring screen

    /** The ring screen, for an alarm with [mission] and a sunrise of [sunrise] seconds. */
    private fun ringing(mission: String = Mission.NONE, sunrise: Int = 0) =
        Robolectric.buildActivity(
            AlarmRingActivity::class.java,
            android.content.Intent(context, AlarmRingActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_MISSION, mission)
                .putExtra(AlarmScheduler.EXTRA_GENTLE, sunrise)
        )

    /** A light meter, which only a sunrise asks for. */
    private fun lightMeter(): Sensor =
        ShadowSensor.newInstance(Sensor.TYPE_LIGHT).also { shadow.addSensor(it) }

    /** Shakes of the phone: each a swing well past the bar, and back to rest. */
    private fun shakes(count: Int) = repeat(count) {
        reading(Mission.SHAKE_ON + 6f, 0f)
        reading(0f, 9.81f)
    }

    /** What the shake mission shows it has counted. */
    private fun shown(app: AlarmRingActivity): String =
        app.findViewById<android.widget.TextView>(R.id.mission_prompt).text.toString()

    private fun counting(n: Int): String =
        context.getString(R.string.mission_shake_fmt, n, Mission.SHAKES_NEEDED)

    /**
     * The ring screen lets go of both on pause, and takes both back.
     *
     * An alarm with a sunrise and a shake mission, which is the one that
     * has both: the light meter and the accelerometer.
     */
    @Test
    fun `the ring screen lets go of its sensors on pause`() {
        val light = lightMeter()
        ringing(Mission.SHAKE, sunrise = 300).use { c ->
            c.setup()
            assertTrue("set up wrong: the mission is not listening", anyoneHears(accelerometer))
            assertTrue("set up wrong: the sunrise is not", anyoneHears(light))

            c.pause()
            assertEquals(
                "still listening behind a paused ring screen",
                emptyList<Any>(), shadow.getListeners()
            )

            c.resume()
            assertTrue("the mission did not take the accelerometer back", anyoneHears(accelerometer))
            assertTrue("nor the sunrise the light meter", anyoneHears(light))
        }
    }

    /**
     * A shake mission can still be finished after the screen has been off.
     *
     * The power button pressed half way through stops the screen without
     * destroying it. Shaking while it is off counts for nothing; when it
     * comes back the count is where it was and the shaking counts again.
     * This is the screen that stops the alarm, and a mission that could not
     * be finished any more would be an alarm nobody could turn off.
     */
    @Test
    fun `a shake mission can still be finished after the screen has been off`() {
        ringing(Mission.SHAKE).use { c ->
            c.setup()
            val app = c.get()
            shakes(3)
            assertEquals("set up wrong: the shaking was not counted", counting(3), shown(app))

            c.pause()
            hidden(c)
            assertEquals(
                "still listening with the screen off",
                emptyList<Any>(), shadow.getListeners()
            )
            shakes(5)

            back(c)
            assertTrue("the mission did not listen again", anyoneHears(accelerometer))
            assertEquals("the count did not come back as it was", counting(3), shown(app))

            shakes(1)
            assertEquals("a shake after the screen came back was not counted", counting(4), shown(app))

            shakes(Mission.SHAKES_NEEDED - 4)
            assertTrue("the last shake did not stop the alarm", app.isFinishing)
        }
    }

    /**
     * And once it is finished it lets go at once, and stops the alarm once.
     *
     * Readings already on their way when the last shake landed reached a
     * mission that was finished, and each one stopped the alarm all over
     * again, until the screen was taken down.
     */
    @Test
    fun `a finished shake mission stops listening and stops the alarm once`() {
        ringing(Mission.SHAKE).use { c ->
            c.setup()
            shakes(Mission.SHAKES_NEEDED)
            assertTrue("set up wrong: the mission was not finished", c.get().isFinishing)
            assertFalse("still listening to a finished mission", anyoneHears(accelerometer))

            shakes(3)
            val stops = generateSequence {
                Shadows.shadowOf(context as Application).nextStartedService
            }.count { it.action == AlarmService.ACTION_STOP }
            assertEquals("the alarm was stopped more than once", 1, stops)
        }
    }

    /**
     * The light meter goes with the sunrise.
     *
     * How light the room is decides only where the screen settles when it
     * is picked up part way through a sunrise. Once the sunrise is over
     * nothing reads it, so it is let go then, and a resume after that does
     * not ask for it again.
     */
    @Test
    fun `the light meter is let go when the sunrise ends and not taken back`() {
        val light = lightMeter()
        AlarmService.ringingSince = android.os.SystemClock.elapsedRealtime()
        ringing(sunrise = 60).use { c ->
            c.setup()
            assertTrue("set up wrong: the sunrise is not reading the room", anyoneHears(light))

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
            assertFalse("the light meter outlived the sunrise", anyoneHears(light))

            c.pause()
            c.resume()
            assertFalse("the resume took the light meter back after the sunrise", anyoneHears(light))
        }
    }

    private companion object {
        const val WINDOWS_VISIBLE = "robolectric.areWindowsMarkedVisible"
    }
}
