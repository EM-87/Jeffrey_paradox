package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * The digits' beep: a quiet one, every second, for whoever asks for it.
 *
 * The dial has a tick and the digits were given nothing in its place when
 * the tick was taken off them — rightly, a screenful of numbers ticking
 * like an escapement is a costume. What a digital clock makes is a beep.
 * Off unless it is switched on, because a sound every second is company
 * to the one person who wanted it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DigitalBeepTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun settled(face: Face, beep: Boolean?) {
        val edit = prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, face.key)
        if (beep != null) edit.putBoolean(Prefs.DIGITAL_BEEP, beep)
        edit.commit()
        AlarmStore.forget()
        AlarmStore.all(context).clear()
        AlarmStore.save(context)
    }

    /** Lets the once-a-second loop go round a couple of times. */
    private fun aFewSeconds() =
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))

    // ---------------------------------------------------------- the sound

    /**
     * What the beep is, measured, since nobody here can listen to it.
     *
     * Short: under a twentieth of a second. Quiet: less than half the
     * height of the tick, which is 0.3 of full scale. A note and not a
     * knock: it starts from nothing and ends at nothing, with no step at
     * either end for a speaker to turn into a click, and its loudest
     * moment is a few milliseconds in rather than the first sample. And
     * the pitch it was given: counted off its own crossings of nought.
     *
     * Written out as a file too, for somebody with ears.
     */
    @Test
    fun `the beep is short, quiet and a note`() {
        val player = ChimePlayer()
        val samples = player.beepSamples()
        val rate = 44_100.0
        val seconds = samples.size / rate
        assertTrue("the beep lasts $seconds s", seconds in 0.03..0.05)

        val peak = samples.maxOf { abs(it) }
        assertTrue("the beep peaks at $peak of full scale", peak in 0.06f..0.14f)
        val loudestAt = samples.indices.maxByOrNull { abs(samples[it]) }!! / rate
        assertTrue(
            "the beep is loudest ${loudestAt * 1000} ms in: it starts with a step",
            loudestAt in 0.002..0.012
        )
        // The first turn of the tone is a small fraction of the loudest:
        // a tone that begins at full height begins with a knock, whatever
        // its first sample is — a sine starts at nought either way.
        val firstTurn = (rate / ChimePlayer.BEEP_HZ).toInt()
        val opening = (0 until firstTurn).maxOf { abs(samples[it]) }
        assertTrue(
            "the first turn of the beep is ${opening / peak} of its loudest: it starts with a knock",
            opening < peak * 0.3f
        )
        assertTrue("it starts at ${samples.first()}", abs(samples.first()) < 0.001f)
        assertTrue("it ends at ${samples.last()}", abs(samples.last()) < 0.001f)
        // No step anywhere: the biggest jump between two samples is what
        // the tone itself makes at its loudest, 2π·f/rate of its height.
        val biggestStep = (1 until samples.size).maxOf { abs(samples[it] - samples[it - 1]) }
        val toneStep = (2 * Math.PI * ChimePlayer.BEEP_HZ / rate * peak).toFloat()
        assertTrue("a jump of $biggestStep between two samples", biggestStep <= toneStep * 1.15f)

        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        }
        val hz = crossings / 2.0 / seconds
        assertEquals("the pitch of the beep", ChimePlayer.BEEP_HZ, hz, 40.0)
        assertTrue("$hz Hz is a whistle or a hum", hz in 800.0..2000.0)

        val out = File("build/sounds").apply { mkdirs() }
        player.writeWav(File(out, "digital-beep.wav"), player.toPcm(samples))
        println(
            "BEEP %.0f ms, peak %.3f at %.1f ms, %.0f Hz".format(seconds * 1000, peak, loudestAt * 1000, hz)
        )
    }

    // -------------------------------------------------------- the setting

    /** The row is the digits' and nobody else's, and it comes switched off. */
    @Test
    fun `the beep is a row on the digital face only, and off until asked for`() {
        for (face in Face.entries) {
            assertEquals(
                "the beep's row on $face",
                face == Face.DIGITAL, FaceOptions.shows(face, Prefs.DIGITAL_BEEP)
            )
        }
        settled(Face.DIGITAL, beep = null)
        Robolectric.buildActivity(SettingsActivity::class.java).use { c ->
            c.setup()
            val advanced = SettingsActivity.AdvancedSettingsFragment()
            c.get().supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, advanced).commitNow()
            val row = advanced.findPreference<androidx.preference.SwitchPreferenceCompat>(Prefs.DIGITAL_BEEP)
            assertTrue("the digital face has no row for its beep", row != null && row.isVisible)
            assertFalse("the beep comes switched on", row!!.isChecked)
        }
        assertFalse("and the setting was written as on", prefs.getBoolean(Prefs.DIGITAL_BEEP, false))
    }

    // ----------------------------------------------------------- the beat

    /** Off, there is no beep; on, there is one every second the clock is on the glass. */
    @Test
    fun `the digits beep when asked and not before`() {
        settled(Face.DIGITAL, beep = null)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            aFewSeconds()
            assertFalse("the digits beep without being asked", c.get().beepsWantedForTest())
        }
        settled(Face.DIGITAL, beep = true)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            aFewSeconds()
            assertTrue("asked for, the beep is not wanted", c.get().beepsWantedForTest())
            assertFalse("and the digits tick as well", c.get().ticksWantedForTest())
        }
    }

    /**
     * And what the beat plays is the beep, on the thread that keeps the
     * beat: wanted and never asked for is a switch that does nothing.
     */
    @Test
    fun `the beat plays a beep on the digits and a tick on the dial`() {
        try {
            for ((face, beeps) in listOf(Face.DIGITAL to true, Face.ANALOG to false)) {
                settled(face, beep = true)
                prefs.edit().putBoolean(Prefs.TICKING, true).putBoolean(Prefs.SECOND_HAND, true).commit()
                Robolectric.buildActivity(MainActivity::class.java).use { c ->
                    c.setup()
                    val app = c.get()
                    aFewSeconds()
                    val (ticksBefore, beepsBefore) = app.soundsAskedForTest()
                    // The beat reads the wall clock to see whether its
                    // second has gone; put it early in one.
                    TimeKeeper.solarOffsetMs = 300L - Math.floorMod(System.currentTimeMillis(), 1000L)
                    repeat(3) {
                        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
                        ticksLooper()?.let { Shadows.shadowOf(it).idle() }
                    }
                    val (ticks, beepsNow) = app.soundsAskedForTest()
                    if (beeps) {
                        assertTrue("the digits' beat asked for no beep", beepsNow - beepsBefore >= 2)
                        assertEquals("and it ticked", 0, ticks - ticksBefore)
                    } else {
                        assertTrue("the dial's beat asked for no tick", ticks - ticksBefore >= 2)
                        assertEquals("and it beeped", 0, beepsNow - beepsBefore)
                    }
                }
            }
        } finally {
            TimeKeeper.solarOffsetMs = 0L
        }
    }

    private fun ticksLooper() =
        (Thread.getAllStackTraces().keys.firstOrNull { it.name == "ticks" } as? android.os.HandlerThread)?.looper

    /** Only while the clock is the card on screen: not over the alarms, not over a chronograph. */
    @Test
    fun `the beep stops when the clock is not the card on screen`() {
        settled(Face.DIGITAL, beep = true)
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            aFewSeconds()
            assertTrue(app.beepsWantedForTest())
            for (card in listOf(Card.ALARM, Card.STOPWATCH)) {
                app.goToForTest(card)
                aFewSeconds()
                assertFalse("the digits beep over $card", app.beepsWantedForTest())
            }
            app.goToForTest(Card.CLOCK)
            aFewSeconds()
            assertTrue("back on the clock, the beep did not come back", app.beepsWantedForTest())
        }
    }

    /** And it is the digits' sound: a dial with the switch left on from another face does not beep. */
    @Test
    fun `no other face beeps`() {
        for (face in Face.entries.filter { it != Face.DIGITAL }) {
            settled(face, beep = true)
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                aFewSeconds()
                assertFalse("$face beeps", c.get().beepsWantedForTest())
            }
        }
    }
}
