package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The zoo, measured.
 *
 * Five new voices — a cockerel, a rattlesnake, a wolf, a dog and the bell
 * at the end of a round — and none of them can be listened to from here.
 * They are all arithmetic, so they can be looked at instead: how loud, how
 * long, and what shape the energy is.
 *
 * The thing that matters most is not whether the cockerel is convincing.
 * It is not, particularly, and it never will be — an impression built out
 * of seven harmonics and a rasp is not a bird. What matters is that none
 * of them is the one that goes off twice as loud as the others at six in
 * the morning, that none of them clips into a buzz, and that each one
 * finishes before it starts again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MenagerieTest {

    private val player = ChimePlayer()
    private val rate = 44100

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun peak(b: FloatArray): Float = b.maxOf { abs(it) }

    /** How loud it is while it is actually sounding, ignoring the silence. */
    private fun rms(b: FloatArray): Float {
        val loud = b.filter { abs(it) > 0.005f }
        if (loud.isEmpty()) return 0f
        return sqrt(loud.sumOf { (it * it).toDouble() } / loud.size).toFloat()
    }

    /** How strongly [b] answers to [hz], over the window [from]..[to]. */
    private fun energyAt(b: FloatArray, hz: Double, from: Int = 0, to: Int = b.size): Double {
        var re = 0.0
        var im = 0.0
        for (n in from until minOf(to, b.size)) {
            val a = 2.0 * Math.PI * hz * n / rate
            re += b[n] * cos(a)
            im += b[n] * sin(a)
        }
        return sqrt(re * re + im * im) / maxOf(1, minOf(to, b.size) - from)
    }

    private fun strongestOf(
        b: FloatArray,
        candidates: List<Double>,
        from: Int = 0,
        to: Int = b.size
    ): Double = candidates.maxBy { energyAt(b, it, from, to) }

    /** The bells, which everything else is judged against. */
    private fun bells(): FloatArray =
        player.buffer(Bells.peal(Prefs.BELL_STYLE_COUNT, 15, 0, Bells.MARKS_HOUR)!!)

    // ------------------------------------------------- all of them at once

    /**
     * Every sound on the list is a sound, and none of them is a buzz.
     *
     * Walked off [Prefs.ALARM_SOUNDS] rather than written out here, so a
     * voice added tomorrow is measured the day it joins the picker rather
     * than the day somebody remembers to add it to a test.
     */
    @Test
    fun `every alarm sound makes a noise and none of them clips`() {
        for (sound in Prefs.ALARM_SOUNDS - Prefs.ALARM_SOUND_SILENT) {
            val b = player.namedBuffer(sound)
            assertTrue("$sound is silent", peak(b) > 0.05f)
            assertTrue("$sound peaks at ${peak(b)} and would clip", peak(b) <= 1f)
            assertTrue("$sound is over before it starts", b.size > rate / 4)
        }
    }

    /**
     * Except the one that is meant to be, which really is.
     *
     * "Silent" is a sound this app makes, and it has to be made properly:
     * not a quiet sound, not a sound at the bottom of the volume ramp —
     * nothing at all, so that an alarm chosen to wake one person without
     * waking the other stays that way however the volume is set.
     */
    @Test
    fun `silence is silent`() {
        assertEquals(
            0f, peak(player.namedBuffer(Prefs.ALARM_SOUND_SILENT)), 0f
        )
        assertTrue(
            "and it still comes round often enough to keep the torch going",
            player.gapAfter(Prefs.ALARM_SOUND_SILENT) <= 3000L
        )
    }

    /**
     * And none of them shouts over the others.
     *
     * This is the one that actually protects somebody. The alarm is a real
     * alarm on a real phone, and a new voice that happens to come out twice
     * as loud as the bells is not a bug you notice in code review — it is a
     * bug you notice at six in the morning, once.
     */
    @Test
    fun `no voice is much louder than the bells`() {
        val reference = rms(bells())
        for (sound in Prefs.ALARM_SOUNDS - Prefs.ALARM_SOUND_SILENT) {
            val ratio = rms(player.namedBuffer(sound)) / reference
            assertTrue(
                "$sound sits at ${"%.2f".format(ratio)}× the bells",
                ratio in 0.5f..2.0f
            )
        }
    }

    /**
     * Each one finishes before it comes round again.
     *
     * The gap is part of the sound: a cockerel that crows again the instant
     * it has stopped is not a cockerel, it is a fire alarm made of poultry.
     * Overlap is also the one failure here that gets worse the longer it
     * runs — each go piling onto the last until it is a wall.
     */
    @Test
    fun `every voice is over before it goes again`() {
        for (sound in Prefs.ALARM_SOUNDS - Prefs.ALARM_SOUND_SILENT) {
            val lengthMs = player.namedBuffer(sound).size * 1000L / rate
            val gap = player.gapAfter(sound)
            assertTrue(
                "$sound lasts ${lengthMs}ms and repeats every ${gap}ms",
                gap >= lengthMs
            )
            assertTrue("$sound leaves no pause at all", gap >= lengthMs + 200)
        }
    }

    /**
     * Every name on the list is a different sound, and every one of them
     * has a name of its own on screen.
     *
     * Both halves of the same mistake. A sound added to the list but not to
     * the dispatcher silently becomes the bells; one added to the list but
     * not to the labels silently becomes the *word* "Bells". Neither shows
     * up as an error anywhere — you pick "Wolf" and get church bells, and
     * everything in the code looks fine.
     */
    @Test
    fun `no sound quietly falls back to the bells`() {
        val fallback = player.namedBuffer("nothing by this name")
        val cards = AlarmCards(
            host = context,
            prefs = PreferenceManager.getDefaultSharedPreferences(context),
            alarms = emptyList(),
            dialTheme = { ClockThemes.MIDNIGHT },
            hoursOnDial = { 12 },
            dialShape = { ClockView.DialShape.CIRCLE },
            onToggled = { _, _ -> },
            onOpen = { }
        )
        val bellsLabel = cards.soundLabel(Prefs.ALARM_SOUND_BELLS)
        for (sound in Prefs.ALARM_SOUNDS) {
            if (sound == Prefs.ALARM_SOUND_BELLS) continue
            assertTrue(
                "$sound is not in the dispatcher, so it rings as the bells",
                !player.namedBuffer(sound).contentEquals(fallback)
            )
            assertTrue(
                "$sound has no name of its own, so the picker calls it '$bellsLabel'",
                cards.soundLabel(sound) != bellsLabel
            )
        }
        assertEquals(
            "and every one of them is named exactly once",
            Prefs.ALARM_SOUNDS.size,
            Prefs.ALARM_SOUNDS.map { cards.soundLabel(it) }.distinct().size
        )
    }

    // ---------------------------------------------- and each on its own

    /**
     * The rattle is noise, not a note.
     *
     * The one voice here that is not an impression: a rattlesnake really is
     * broadband noise chopped up sixty times a second, so this is the one
     * that can be *right* rather than merely recognisable. The way it would
     * go wrong is by becoming periodic — a buzz at some pitch — and that is
     * exactly what "no single frequency holds much of the energy" measures.
     */
    @Test
    fun `the rattle is broadband noise and not a tone`() {
        val rattle = player.rattleBuffer()
        val probes = listOf(220.0, 440.0, 880.0, 1760.0, 3520.0, 7040.0)
        val levels = probes.map { energyAt(rattle, it, 0, rate) }
        val loudest = levels.max()
        val average = levels.average()
        assertTrue(
            "one frequency holds ${"%.1f".format(loudest / average)}× the average — that is a tone",
            loudest / average < 4.0
        )

        // Against a bell, where one frequency very much does dominate, so
        // the measurement above is known to be able to tell the difference.
        // Struck at 880 so its fundamental lands on one of the probes: the
        // first version of this control rang a bell at 392 Hz, which none
        // of the probes was listening for, so the tone measured as flat as
        // the noise and the control quietly proved nothing.
        val bell = player.bellBuffer(1, false, 880.0, 3.0, 1.3)
        val bellLevels = probes.map { energyAt(bell, it, 0, rate) }
        assertTrue(
            "the probe cannot tell a tone from noise, so it proves nothing",
            bellLevels.max() / bellLevels.average() > 4.0
        )
    }

    /**
     * The howl rises and falls. A howl that held one note would be a siren,
     * and the whole point of it as an alarm is that it moves.
     */
    @Test
    fun `the howl climbs and comes back down`() {
        val howl = player.howlBuffer()
        val probes = listOf(360.0, 420.0, 500.0, 560.0, 620.0, 700.0)
        // Early in the first howl, in the middle of it, and at its end.
        val start = strongestOf(howl, probes, 0, (0.25 * rate).toInt())
        val middle = strongestOf(howl, probes, (0.9 * rate).toInt(), (1.4 * rate).toInt())
        val end = strongestOf(howl, probes, (2.1 * rate).toInt(), (2.35 * rate).toInt())
        assertTrue("it starts at $start and peaks at $middle", middle > start)
        assertTrue("it peaks at $middle and ends at $end", middle > end)
    }

    /**
     * The cockerel is four syllables and the last one is the long one.
     *
     * Measured as loudness over time rather than as pitch: ki-ki-ri-kí is a
     * rhythm before it is anything else, and a crow that came out as one
     * continuous note would be the thing that stopped it reading as a bird
     * at all.
     */
    @Test
    fun `the cockerel has four syllables, the last one held`() {
        val crow = player.roosterBuffer()
        // Ten-millisecond slices, marked as sounding or silent.
        val slice = rate / 100
        val sounding = (0 until crow.size / slice).map { s ->
            (0 until slice).any { abs(crow[s * slice + it]) > 0.02f }
        }
        var runs = 0
        var longest = 0
        var current = 0
        for ((i, on) in sounding.withIndex()) {
            if (on && (i == 0 || !sounding[i - 1])) runs++
            current = if (on) current + 1 else 0
            longest = maxOf(longest, current)
        }
        assertEquals("ki-ki-ri-kí is four", 4, runs)
        assertTrue("and the last one is held: ${longest * 10}ms", longest * 10 >= 400)
    }

    /**
     * The bell at the end of a round is the one it says it is, and well
     * above the ship's bell it would otherwise be mistaken for.
     */
    @Test
    fun `the ring bell is high and rung three times`() {
        val bell = player.ringBellBuffer()
        val found = strongestOf(bell, listOf(587.0, 660.0, 880.0, 1174.7, 1568.0), 0, rate / 4)
        assertEquals("D two octaves above middle C", 1174.7, found, 1.0)
        assertTrue(
            "and clear of the ship's bell",
            ChimePlayer.RING_BELL_HZ > ChimePlayer.SHIPS_HZ * 1.5
        )
        assertTrue("three strikes take a moment", bell.size.toFloat() / rate > 3f)
    }

    /**
     * And a timer that runs out sounds like a timer running out, whichever
     * of the two roads it takes to get there.
     */
    @Test
    fun `a finished timer rings the round bell`() {
        assertEquals(
            Prefs.ALARM_SOUND_RING_BELL,
            CountdownService.FINISHED_SOUND
        )
    }

    // ------------------------------------------------ and somewhere to listen

    /**
     * Every voice written out as a .wav, for somebody with ears.
     *
     * Measuring is not hearing. Everything above can tell you that the
     * cockerel is four syllables at the right level and none of it can tell
     * you that it sounds like a duck. So the buffers go to disk, exactly as
     * they would be played, and the one person on this project who can hear
     * them gets to listen before installing anything.
     *
     * It is the same trick as the screenshots: this end cannot look either,
     * so it renders the screen and hands it over. A test that writes files
     * and asserts almost nothing is a strange-looking test — the assertion
     * is only that the files are real — but what it produces is the only
     * way any of this gets judged on what it actually is.
     */
    @Test
    fun `every voice is written out where it can be listened to`() {
        val out = java.io.File("build/sounds").apply { mkdirs() }
        for (sound in Prefs.ALARM_SOUNDS) {
            val file = java.io.File(out, "$sound.wav")
            val buffer = player.namedBuffer(sound)
            player.writeWav(file, player.toPcm(buffer))
            assertTrue("$sound wrote nothing", file.length() > 44)
            assertEquals(
                "the header does not match the samples",
                44L + buffer.size * 2L, file.length()
            )
        }
    }
}
