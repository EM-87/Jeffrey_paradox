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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Something to get right before the alarm will stop.
 *
 * The whole feature is one claim — that there is no easier way out than
 * the one that needs you awake — and a feature like that is only as good
 * as its worst escape route. So most of what is here is about the ways
 * round it rather than the mission itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MissionTest {

    private val prefs get() = PreferenceManager
        .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())

    @Before
    fun wipe() {
        prefs.edit().clear().commit()
        // The store keeps the list in memory between reads, so clearing the
        // preferences alone leaves the previous test's alarms in place.
        AlarmStore.forget()
    }

    // ------------------------------------------------------------- the sum

    /**
     * Out of the times tables most people have by heart, and inside what
     * anybody can do at six in the morning. A sum nobody can do is an
     * alarm that cannot be turned off, which is worse than one that can be
     * turned off too easily.
     */
    @Test
    fun `the sum needs thinking about but is not cruel`() {
        val random = Random(7)
        repeat(500) {
            val sum = Mission.sum(random)
            assertTrue("${sum.text()}", sum.a in 3..9)
            assertTrue("${sum.text()}", sum.b in 12..19)
            assertTrue("nothing trivial", sum.a > 2 && sum.b > 10)
            assertTrue("nor beyond doing in the head", sum.answer <= 200)
        }
    }

    /** It is not the same sum every time, or the answer becomes a habit. */
    @Test
    fun `it is a different sum each time`() {
        val random = Random(11)
        val seen = (1..40).map { Mission.sum(random) }.toSet()
        assertTrue("only ${seen.size} distinct", seen.size > 10)
    }

    @Test
    fun `a right answer is right and everything else is not`() {
        val sum = Mission.Sum(7, 14)
        assertTrue(Mission.solved(sum, "98"))
        assertTrue("spaces are not a wrong answer", Mission.solved(sum, "  98 "))
        assertFalse(Mission.solved(sum, "97"))
        assertFalse("blank is wrong, not an error", Mission.solved(sum, ""))
        assertFalse(Mission.solved(sum, "abc"))
        assertFalse("and no cheating with the working", Mission.solved(sum, "7*14"))
    }

    // ---------------------------------------------------------- the shaking

    /**
     * One threshold would not do. The accelerometer is sampled many times
     * a second, so a single swing spends a dozen readings above any line
     * you draw — and a phone waved once would finish the mission.
     */
    @Test
    fun `one long swing is one shake, not a dozen`() {
        val shakes = Mission.Shakes()
        repeat(30) { shakes.feed(25f) }
        assertEquals(1, shakes.count)
    }

    /** It has to come back down before the next one counts. */
    @Test
    fun `up and down is what makes a shake`() {
        val shakes = Mission.Shakes(needed = 3)
        assertFalse(shakes.feed(25f))
        assertFalse(shakes.feed(5f))
        assertFalse(shakes.feed(25f))
        assertFalse(shakes.feed(5f))
        assertTrue("the third finishes it", shakes.feed(25f))
        assertEquals(3, shakes.count)
    }

    /**
     * A phone lying on a table reads about 9.81 whichever way up it is,
     * and must never count: an alarm that turns itself off while nobody
     * touches it is not an alarm.
     */
    @Test
    fun `a phone at rest never counts a thing`() {
        val shakes = Mission.Shakes()
        repeat(2000) { shakes.feed(9.81f + (it % 7) * 0.1f) }
        assertEquals(0, shakes.count)
    }

    /**
     * A brisk tilt is not a shake.
     *
     * Tilting swings the gravity vector about and peaks well past the 9.81
     * a still phone reads — at sixteen a firm tilt was passing for a shake,
     * and the mission could be finished by rocking the phone on a bedside
     * table. The bar is roughly two and a half g now: a movement of the
     * arm, not of the wrist.
     */
    @Test
    fun `tilting the phone is not shaking it`() {
        val shakes = Mission.Shakes()
        // A tilt swinging gravity around, sampled the way a sensor would.
        repeat(200) { n ->
            val swing = 9.81f + 5f * kotlin.math.sin(n / 4.0).toFloat()
            shakes.feed(kotlin.math.abs(swing))
        }
        assertEquals("a tilt counted as shaking", 0, shakes.count)
        // And a real shake still counts.
        assertTrue(Mission.SHAKE_ON >= 20f)
        shakes.feed(30f)
        assertEquals(1, shakes.count)
    }

    /** Gravity alone is what a still phone reads, so that is the floor. */
    @Test
    fun `the threshold is above gravity`() {
        assertTrue(Mission.SHAKE_ON > 9.81f)
        assertTrue("with room to fall back into", Mission.SHAKE_OFF < Mission.SHAKE_ON)
        assertEquals(9.81f, Mission.magnitude(0f, 0f, -9.81f), 0.01f)
        assertEquals(5f, Mission.magnitude(3f, 4f, 0f), 0.01f)
    }

    /** And it counts up rather than needing all fifteen at once. */
    @Test
    fun `it never counts past what it needs`() {
        val shakes = Mission.Shakes(needed = 2)
        repeat(20) { shakes.feed(25f); shakes.feed(0f) }
        assertEquals(2, shakes.count)
        assertEquals(1f, shakes.progress(), 0.001f)
    }

    // ----------------------------------------------------- what counts as one

    /**
     * A setting written by a later version, or restored from a backup made
     * by one, must not leave an alarm that cannot be turned off by any
     * means the phone in front of you has.
     */
    @Test
    fun `a mission nobody recognises is no mission`() {
        assertEquals(Mission.NONE, Mission.required("wat"))
        assertEquals(Mission.NONE, Mission.required(null))
        assertEquals(Mission.MATHS, Mission.required(Mission.MATHS))
        assertEquals(Mission.SHAKE, Mission.required(Mission.SHAKE))
        assertFalse(Mission.any("wat"))
        assertTrue(Mission.any(Mission.SHAKE))
    }

    // -------------------------------------------------- one alarm at a time

    /**
     * Somebody who had a mission switched on this morning still has it
     * tomorrow.
     *
     * It was a setting of the app for one version and is a property of an
     * alarm now, and the whole point of a mission is that it is there when
     * you would rather it were not — silently dropping it in an update is
     * exactly the failure it exists to prevent.
     */
    @Test
    fun `the app-wide answer is carried onto the alarms that existed`() {
        prefs.edit()
            .putString(Prefs.MISSION, Mission.SHAKE)
            .putString(Prefs.GENTLE_WAKE, "60")
            .commit()
        AlarmStore.all(context).add(Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS))
        AlarmStore.save(context)

        AlarmStore.adoptGlobals(context)

        val alarm = AlarmStore.all(context).first { it.id == 1 }
        assertEquals(Mission.SHAKE, alarm.mission)
        assertEquals(60, alarm.gentleWakeSeconds)
    }

    /**
     * And only once. An alarm turned back to "none" on purpose must stay
     * that way, rather than having the old app-wide answer put back on it
     * every time the app starts.
     */
    @Test
    fun `and never put back after it has been turned off`() {
        prefs.edit().putString(Prefs.MISSION, Mission.SHAKE).commit()
        AlarmStore.all(context).add(Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_BELLS))
        AlarmStore.save(context)
        AlarmStore.adoptGlobals(context)

        AlarmStore.all(context).first { it.id == 1 }.mission = Mission.NONE
        AlarmStore.save(context)
        AlarmStore.adoptGlobals(context)

        assertEquals(
            Mission.NONE,
            AlarmStore.all(context).first { it.id == 1 }.mission
        )
    }

    /** And both survive being written down and read back. */
    @Test
    fun `an alarm remembers its own mission and its own sunrise`() {
        AlarmStore.all(context).add(
            Alarm(2, 7, 0, true, Prefs.ALARM_SOUND_BELLS).apply {
                mission = Mission.MATHS
                gentleWakeSeconds = 180
            }
        )
        AlarmStore.save(context)
        AlarmStore.forget()

        val alarm = AlarmStore.all(context).first { it.id == 2 }
        assertEquals(Mission.MATHS, alarm.mission)
        assertEquals(180, alarm.gentleWakeSeconds)
    }

    // ------------------------------------------------------ and the screen

    /**
     * Gives the phone an accelerometer.
     *
     * Robolectric's has none by default, and that turned out to matter:
     * the shake mission was being tested down a path a phone without one
     * never takes — and, once there was a fallback for that phone, the
     * test was quietly exercising the fallback instead of the mission.
     */
    private fun withAccelerometer() {
        val manager = context.getSystemService(android.hardware.SensorManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).addSensor(
            org.robolectric.shadows.ShadowSensor.newInstance(
                android.hardware.Sensor.TYPE_ACCELEROMETER
            )
        )
    }

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun ring(
        fromTimer: Boolean = false,
        mission: String = Mission.NONE,
        body: (AlarmRingActivity) -> Unit
    ) {
        val intent = android.content.Intent(
            ApplicationProvider.getApplicationContext(), AlarmRingActivity::class.java
        ).putExtra(AlarmScheduler.EXTRA_FROM_TIMER, fromTimer)
            .putExtra(AlarmScheduler.EXTRA_MISSION, mission)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            body(c.get())
        }
    }

    /**
     * The mission takes the slider's place. Never both: a mission beside a
     * working slide-to-stop is decoration.
     */
    @Test
    fun `the mission replaces the slider rather than joining it`() {
                ring(mission = Mission.MATHS) { app ->
            assertEquals(Mission.MATHS, app.missionKind)
            assertEquals(View.GONE, app.findViewById<View>(R.id.stop_slider).visibility)
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.mission_block).visibility)
        }
    }

    /** With none set, nothing changes: the slider is still the way out. */
    @Test
    fun `without one the slider is untouched`() {
        ring { app ->
            assertEquals(Mission.NONE, app.missionKind)
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.stop_slider).visibility)
            assertEquals(View.GONE, app.findViewById<View>(R.id.mission_block).visibility)
        }
    }

    /**
     * A finished countdown is not a wake-up. Making somebody do sums to
     * silence the pasta timer is a joke that stops being funny the first
     * time it happens.
     */
    @Test
    fun `a finished timer asks nothing of anybody`() {
        ring(fromTimer = true, mission = Mission.MATHS) { app ->
            assertEquals(Mission.NONE, app.missionKind)
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.stop_slider).visibility)
        }
    }

    /**
     * A wrong answer costs a new sum. Otherwise the button could be
     * pressed over and over with one number in the box until it happened
     * to be right — which is a thing a sleeping hand can do.
     */
    @Test
    fun `a wrong answer does not stop it, and the sum changes`() {
                ring(mission = Mission.MATHS) { app ->
            val prompt = app.findViewById<android.widget.TextView>(R.id.mission_prompt)
            val answer = app.findViewById<android.widget.EditText>(R.id.mission_answer)
            val button = app.findViewById<android.widget.Button>(R.id.mission_button)

            val asked = prompt.text.toString()
            answer.setText("1")
            var changed = false
            // Any one new sum may by chance be the one before it, so this
            // asks whether it ever moves rather than whether it moved once.
            repeat(20) {
                button.performClick()
                if (prompt.text.toString() != asked) changed = true
            }
            assertFalse("a wrong answer must not close the screen", app.isFinishing)
            assertTrue("and must not leave the same sum on screen", changed)
        }
    }

    /** Shaking shows how far along it is, so it is clear it is working. */
    @Test
    fun `the shake mission counts out loud`() {
                withAccelerometer()
        ring(mission = Mission.SHAKE) { app ->
            assertEquals(Mission.SHAKE, app.missionKind)
            val prompt = app.findViewById<android.widget.TextView>(R.id.mission_prompt)
            assertTrue("${prompt.text}", prompt.text.contains("0"))
            assertTrue("${prompt.text}", prompt.text.contains("${Mission.SHAKES_NEEDED}"))
            // The box and the button belong to the sum, not to this.
            assertEquals(View.GONE, app.findViewById<View>(R.id.mission_answer).visibility)
            assertEquals(View.GONE, app.findViewById<View>(R.id.mission_button).visibility)
        }
    }

    /**
     * A phone with no accelerometer cannot be shaken, and a shake mission
     * on one would be an alarm with no way to turn it off at all — the
     * exact failure every other guard here exists to prevent. The slider
     * comes back rather than leaving somebody with a screen asking for
     * something impossible.
     */
    @Test
    fun `a phone that cannot feel a shake gets its slider back`() {
        // No accelerometer added on purpose.
        ring(mission = Mission.SHAKE) { app ->
            assertEquals(Mission.NONE, app.missionKind)
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.stop_slider).visibility)
            assertEquals(View.GONE, app.findViewById<View>(R.id.mission_block).visibility)
        }
    }

    /**
     * And the shade's Stop button is the escape that would make the whole
     * thing decoration: one pull and one tap, from bed, with your eyes
     * shut. With a mission set it has to lead to the mission instead.
     */
    @Test
    fun `the notification cannot stop it behind the mission's back`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(
            "with no mission it is an ordinary Stop",
            AlarmService.guardsStop(Mission.NONE, fromTimer = false)
        )

        assertTrue(
            "with one, the shade must not be the easy way out",
            AlarmService.guardsStop(Mission.MATHS, fromTimer = false)
        )
        assertFalse(
            "but a finished timer keeps its Stop",
            AlarmService.guardsStop(Mission.MATHS, fromTimer = true)
        )

        assertNotEquals(
            "and the button has to say something else, or it lies",
            context.getString(R.string.alarm_stop),
            context.getString(R.string.mission_open)
        )
    }
}
