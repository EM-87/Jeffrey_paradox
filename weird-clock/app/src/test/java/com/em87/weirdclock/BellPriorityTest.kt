package com.em87.weirdclock

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seven in the morning, which is two things at once.
 *
 * It is an hour to strike and, for most people, an hour to be woken at.
 * The two sounded together and which of them you heard depended on the
 * order two services happened to start in — so an alarm could arrive
 * underneath eight strikes of a grandfather clock. Somebody has to give
 * way, and which one is now a setting.
 *
 * Worth pinning here rather than trusting to a morning: the failure is
 * once a day at most, at an hour when nobody is taking notes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BellPriorityTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------- who gives way

    /** By default the alarm wins and the bells stay in. */
    @Test
    fun `an alarm ringing keeps the bells quiet`() {
        assertFalse(
            "the bells struck over a ringing alarm",
            Bells.mayStrike(Bells.PRIORITY_ALARM, alarmRinging = true)
        )
        assertTrue(
            "the bells went quiet with no alarm anywhere near",
            Bells.mayStrike(Bells.PRIORITY_ALARM, alarmRinging = false)
        )
    }

    /** And the other way round, for somebody who would rather hear the hour. */
    @Test
    fun `given the right of way the bells strike anyway`() {
        assertTrue(
            Bells.mayStrike(Bells.PRIORITY_BELLS, alarmRinging = true)
        )
    }

    /**
     * An unset preference reads as the alarm coming first.
     *
     * The default matters more than most: it is what every phone that
     * already has this app installed will be running tomorrow morning,
     * without anybody choosing anything.
     */
    @Test
    fun `with nothing chosen the alarm comes first`() {
        assertFalse(Bells.mayStrike(null, alarmRinging = true))
    }

    // ------------------------------------------------- how long it waits

    /**
     * With the alarm first there is no waiting, whatever the bells are
     * doing — and the same with nothing chosen at all.
     *
     * The unset case is the one that matters most and the one this test
     * was missing: it is what every phone already carrying this app will
     * be running tomorrow morning, without anybody having chosen anything.
     * A sabotage that made an unset preference wait out the bells broke
     * nothing here until this line was added.
     */
    @Test
    fun `the alarm does not wait when it has the right of way`() {
        assertEquals(
            0L,
            Bells.alarmHoldMs(Bells.PRIORITY_ALARM, soundingUntilMs = 90_000L, nowMs = 80_000L)
        )
        assertEquals(
            "an alarm was held up by the bells on a phone that had chosen nothing",
            0L,
            Bells.alarmHoldMs(null, soundingUntilMs = 90_000L, nowMs = 80_000L)
        )
        assertEquals(
            "and by a setting from some future version nobody here knows about",
            0L,
            Bells.alarmHoldMs("whatever", soundingUntilMs = 90_000L, nowMs = 80_000L)
        )
    }

    /** With the bells first it waits exactly as long as they have left. */
    @Test
    fun `the alarm waits out the peal and no longer`() {
        assertEquals(
            10_000L,
            Bells.alarmHoldMs(Bells.PRIORITY_BELLS, soundingUntilMs = 90_000L, nowMs = 80_000L)
        )
        assertEquals(
            "it went on waiting for a peal that had already finished",
            0L,
            Bells.alarmHoldMs(Bells.PRIORITY_BELLS, soundingUntilMs = 70_000L, nowMs = 80_000L)
        )
    }

    /**
     * And never long, whatever the number says.
     *
     * That instant is written down by whichever service started the peal
     * and read by another, so it is a number that can be stale — a clock
     * correction, a service that died mid-strike. An alarm that does not go
     * off is the worst thing this app could do, so the wait has a ceiling
     * shorter than anybody would sleep through.
     */
    @Test
    fun `a nonsense figure cannot hold the alarm back`() {
        val held = Bells.alarmHoldMs(
            Bells.PRIORITY_BELLS,
            soundingUntilMs = 80_000L + 3_600_000L,
            nowMs = 80_000L
        )
        assertTrue("an alarm was held for ${held / 1000}s", held <= 20_000L)
    }

    // ------------------------------------------------- how long a peal is

    /**
     * The length of a peal, checked against the sound that actually gets
     * made.
     *
     * [Bells.pealSeconds] is a second copy of arithmetic that lives in
     * [ChimePlayer], and it has to be: the alarm needs to know how long the
     * bells will be *before* they have been. A copy nobody checks is a copy
     * that drifts, so this measures the buffer of samples the player builds
     * and holds the prediction against it.
     */
    @Test
    fun `the predicted length is the length of the sound`() {
        val rate = 44100
        for (peal in listOf(
            Bells.sample(Prefs.BELL_STYLE_COUNT),
            Bells.sample(Prefs.BELL_STYLE_SHIPS),
            Bells.sample(Prefs.BELL_STYLE_SINGLE),
            Bells.sample(Prefs.BELL_STYLE_BEEP),
            // Twelve strikes of a grandfather clock, which is the longest
            // thing this app ever plays and the one an alarm would have to
            // wait behind at midnight.
            Bells.peal(Prefs.BELL_STYLE_COUNT, 12, 0, Bells.MARKS_HOUR)!!,
            Bells.peal(Prefs.BELL_STYLE_COUNT, 9, 45, Bells.MARKS_QUARTERS)!!
        )) {
            val player = ChimePlayer()
            val samples = when (peal.voice) {
                Bells.Voice.BEEP -> player.beepBuffer(
                    peal.count, peal.frequency, peal.ringSeconds, peal.interval
                ).size
                Bells.Voice.QUARTER_CHIME -> player.quarterBuffer(peal.count).size
                Bells.Voice.BELL -> player.bellBuffer(
                    peal.count, peal.pairGrouping, peal.frequency,
                    peal.ringSeconds, peal.interval
                ).size
            }
            assertEquals(
                "a $peal is predicted at ${Bells.pealSeconds(peal)}s and is " +
                    "${samples.toDouble() / rate}s long",
                samples.toDouble() / rate, Bells.pealSeconds(peal), 0.01
            )
        }
    }

    // ------------------------------------------------- the wiring

    /**
     * Playing a peal writes down when it will be over.
     *
     * The number the alarm reads comes from here and nowhere else, so a
     * peal that plays without setting it is a peal the alarm cannot know
     * about.
     */
    @Test
    fun `a peal says when it will have finished`() {
        Bells.soundingUntil = 0L
        val peal = Bells.sample(Prefs.BELL_STYLE_SINGLE)
        ChimePlayer().play(peal)
        val left = Bells.soundingUntil - android.os.SystemClock.elapsedRealtime()
        assertEquals(
            "the peal did not say how long it would be",
            Bells.pealSeconds(peal) * 1000, left.toDouble(), 200.0
        )
    }

    /** And cutting it short says so, so nothing goes on waiting for it. */
    @Test
    fun `silencing a peal clears the wait`() {
        ChimePlayer().play(Bells.sample(Prefs.BELL_STYLE_SINGLE))
        assertTrue("nothing was sounding to begin with", Bells.soundingUntil > 0L)
        ChimePlayer.silencePeal()
        assertEquals(
            "an alarm would still be waiting for a peal that has been cut off",
            0L,
            Bells.alarmHoldMs(
                Bells.PRIORITY_BELLS, Bells.soundingUntil,
                android.os.SystemClock.elapsedRealtime()
            )
        )
    }

    /**
     * The row is in the menu, under the bells it is about.
     *
     * A rule nobody can reach is a rule that does not exist, and this one
     * has no other way in: it decides what happens at an hour you are
     * asleep for.
     *
     * On the advanced page since the bells' own settings moved there —
     * which is why this asks the menu rather than naming the screen it
     * used to be on. What matters is that it is reachable, not where.
     */
    @Test
    fun `the choice is somewhere it can be made`() {
        val xml = context.resources.getXml(R.xml.advanced_preferences)
        var found = false
        var event = xml.next()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                for (i in 0 until xml.attributeCount) {
                    if (xml.getAttributeValue(i) == Prefs.BELL_PRIORITY) found = true
                }
            }
            event = xml.next()
        }
        assertTrue("there is no way to choose which one gives way", found)
    }
}
