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
     * Five rungs, and every one of them has an answer a person can give.
     *
     * The whole ladder is a compromise between two ways of failing: too
     * easy and it does not wake anybody, too hard and the alarm becomes a
     * thing you dread — and a problem nobody can do at six in the morning
     * is an alarm that cannot be turned off, which is worse than one that
     * can be turned off too easily.
     */
    @Test
    fun `every rung asks something with a whole-number answer`() {
        val random = Random(7)
        for (level in 1..Mission.LEVELS) {
            repeat(300) {
                val problem = Mission.problem(level, random)
                assertTrue("$level: '${problem.text}' is blank", problem.text.isNotBlank())
                assertTrue(
                    "$level: '${problem.text}' answers ${problem.answer}",
                    problem.answer > 0
                )
                assertTrue(
                    "$level: '${problem.text}' wants ${problem.answer}, which is a lot",
                    problem.answer <= 1000
                )
            }
        }
    }

    /** And they get harder going up, rather than merely different. */
    @Test
    fun `the ladder goes upwards`() {
        val random = Random(3)
        // The size of the answer is a poor measure of difficulty on its
        // own, so this compares the bottom with the top, where the
        // difference is not in doubt.
        val easiest = (1..200).map { Mission.problem(1, random).answer }.average()
        val hardest = (1..200).map { Mission.problem(4, random).answer }.average()
        assertTrue("$easiest then $hardest", hardest > easiest * 10)
        // The first rung is arithmetic a child does.
        repeat(50) { assertTrue(Mission.problem(1, random).answer <= 18) }
    }

    /** The top rung is roots and an unknown, not more multiplying. */
    @Test
    fun `the top of the ladder is not just a bigger sum`() {
        val random = Random(5)
        val texts = (1..200).map { Mission.problem(5, random).text }
        assertTrue("no roots in $texts", texts.any { it.contains("√") })
        assertTrue("no unknowns", texts.any { it.contains("x") })
    }

    /** A level nobody recognises is the middle of the ladder, not a crash. */
    @Test
    fun `an impossible level is the middle one`() {
        assertEquals(Mission.DEFAULT_LEVEL, Mission.level(0))
        assertEquals(Mission.DEFAULT_LEVEL, Mission.level(99))
        assertEquals(1, Mission.level(1))
        assertEquals(Mission.LEVELS, Mission.level(Mission.LEVELS))
        // And asking for one out of range still gives a real problem.
        assertTrue(Mission.problem(0).answer > 0)
        assertTrue(Mission.problem(99).answer > 0)
    }

    /** It is not the same problem every time, or the answer becomes a habit. */
    @Test
    fun `it is a different problem each time`() {
        val random = Random(11)
        for (level in 1..Mission.LEVELS) {
            val seen = (1..40).map { Mission.problem(level, random) }.toSet()
            assertTrue("level $level: only ${seen.size} distinct", seen.size > 10)
        }
    }

    @Test
    fun `a right answer is right and everything else is not`() {
        val problem = Mission.Problem("7 × 14", 98)
        assertTrue(Mission.solved(problem, "98"))
        assertTrue("spaces are not a wrong answer", Mission.solved(problem, "  98 "))
        assertFalse(Mission.solved(problem, "97"))
        assertFalse("blank is wrong, not an error", Mission.solved(problem, ""))
        assertFalse(Mission.solved(problem, "abc"))
        assertFalse("and no cheating with the working", Mission.solved(problem, "7*14"))
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
     * Everything set on the sheet is what the alarm ends up with.
     *
     * Not "the mission survives" and "the sunrise survives", which is the
     * shape of test that let this through: the copy back from the sheet
     * was a field per line, and two new fields were simply not on the
     * list. Picking "straight on" and saving gave you back thirty seconds,
     * because what you chose had been thrown away.
     *
     * So this compares the *whole* alarm. A field added tomorrow and
     * forgotten in the copy fails here without anybody remembering to come
     * back and add a line.
     */
    @Test
    fun `saving an alarm keeps every single thing that was set on it`() {
        val existing = Alarm(3, 6, 0, true, Prefs.ALARM_SOUND_BELLS)
        AlarmStore.all(context).add(existing)
        AlarmStore.save(context)

        // A draft with every field moved off its default.
        val draft = Alarm(
            id = 999, hour = 9, minute = 45, enabled = true,
            sound = Prefs.ALARM_SOUND_BABY,
            daysMask = Alarm.WEEKDAYS, snoozeMinutes = 10, label = "Gym",
            soundUri = "content://x", vibrate = false, durationMinutes = 40,
            flash = true, notes = "bring shoes",
            mission = Mission.SHAKE, gentleWakeSeconds = 300,
            extraTimes = mutableListOf(500)
        )

        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().commitDraftForTest(existing, draft, isNew = false)
        }

        val saved = AlarmStore.all(context).first { it.id == 3 }
        assertEquals(
            "something set on the sheet did not survive being saved",
            draft.copy(id = 3, enabled = saved.enabled), saved
        )
    }

    /**
     * And in particular the two that could not be turned off: choosing
     * "none" or "straight on" has to stick.
     */
    @Test
    fun `both of them can be turned off again`() {
        val existing = Alarm(4, 6, 0, true, Prefs.ALARM_SOUND_BELLS).apply {
            mission = Mission.MATHS
            gentleWakeSeconds = 60
        }
        AlarmStore.all(context).add(existing)
        AlarmStore.save(context)

        val draft = existing.copy(mission = Mission.NONE, gentleWakeSeconds = 0)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            c.get().commitDraftForTest(existing, draft, isNew = false)
        }

        val saved = AlarmStore.all(context).first { it.id == 4 }
        assertEquals(Mission.NONE, saved.mission)
        assertEquals(0, saved.gentleWakeSeconds)
    }

    /**
     * A sunrise is measured in minutes.
     *
     * Half a minute is not a sunrise, it is a screen coming on slightly
     * late — the point of the thing is that it is already happening by the
     * time you notice it.
     */
    @Test
    fun `the lengths offered are sunrises and not delays`() {
        assertEquals("off has to be one of them", GentleWake.OFF, GentleWake.CHOICES.first())
        val real = GentleWake.CHOICES.drop(1)
        assertTrue("the shortest is $real", real.min() >= 60)
        assertEquals("and they go up", real.sorted(), real)
        for (choice in GentleWake.CHOICES) {
            assertEquals("$choice is not offerable", choice, GentleWake.clamp(choice))
        }
    }

    /**
     * Two missions, two icons. A single mark saying only "there is a
     * mission" leaves you opening the alarm to find out which.
     */
    @Test
    fun `each mission wears its own mark`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val cards = c.get().alarmCardsForTest()
            val maths = cards.missionIcon(Mission.MATHS)
            val shake = cards.missionIcon(Mission.SHAKE)
            assertNotEquals("both missions look the same", maths, shake)
            assertEquals("a sum wants a sum sign", R.drawable.ic_sigma, maths)
            assertEquals(R.drawable.ic_shake, shake)
            assertNotEquals(
                "and neither may be the plain vibration mark, which is a"
                    + " different thing two icons along",
                R.drawable.ic_vibrate, shake
            )
        }
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
     * The rung reaches the screen: an alarm set to the first rung must not
     * be asking for square roots.
     */
    @Test
    fun `the screen asks at the level the alarm was set to`() {
        val intent = android.content.Intent(context, AlarmRingActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_MISSION, Mission.MATHS)
            .putExtra(AlarmScheduler.EXTRA_MISSION_LEVEL, 1)
        Robolectric.buildActivity(AlarmRingActivity::class.java, intent).use { c ->
            c.setup()
            val app = c.get()
            assertEquals(1, app.missionLevel)
            val shown = app.findViewById<android.widget.TextView>(R.id.mission_prompt)
                .text.toString()
            assertTrue("the first rung must be a sum: '$shown'", shown.contains("+"))
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
