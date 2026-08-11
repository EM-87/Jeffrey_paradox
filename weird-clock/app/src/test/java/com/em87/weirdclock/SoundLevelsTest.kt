package com.em87.weirdclock

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
 * Measuring the sounds, since they cannot be heard from here.
 *
 * Every voice in this app is arithmetic, and until now the only way to
 * find out whether a new one was twice as loud as the bells it sits beside
 * — or an octave off what it was meant to be — was to install it and be
 * startled at six in the morning. The buffers can be built without an
 * audio device, so they can be looked at instead: peak, loudness while
 * sounding, and where the energy actually is in the spectrum.
 *
 * This is not the same as hearing it. It cannot tell whether a beep is
 * pleasant. It can tell whether it is the pitch it claims to be and
 * whether it will make somebody jump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SoundLevelsTest {

    private val player = ChimePlayer()
    private val rate = 44100

    /** The loudest sample in the buffer. */
    private fun peak(b: FloatArray): Float = b.maxOf { abs(it) }

    /** How loud it is while it is actually sounding, ignoring the silence. */
    private fun rms(b: FloatArray): Float {
        val loud = b.filter { abs(it) > 0.005f }
        if (loud.isEmpty()) return 0f
        return sqrt(loud.sumOf { (it * it).toDouble() } / loud.size).toFloat()
    }

    /**
     * Which of a set of candidate frequencies has the most energy, by
     * correlating the buffer against each one.
     *
     * A whole FFT is not needed to answer "is this the pitch it says it
     * is": a handful of probes either side of the claimed frequency
     * settles it, and this way there is nothing to get wrong.
     */
    private fun strongestOf(b: FloatArray, candidates: List<Double>): Double {
        val window = b.copyOfRange(0, minOf(b.size, rate / 4))
        return candidates.maxBy { hz ->
            var re = 0.0
            var im = 0.0
            for (n in window.indices) {
                val a = 2.0 * Math.PI * hz * n / rate
                re += window[n] * cos(a)
                im += window[n] * sin(a)
            }
            sqrt(re * re + im * im)
        }
    }

    // ------------------------------------------------------- the loudness

    /**
     * The bip bip must not be the loudest thing in the app.
     *
     * A square wave at full scale is a great deal louder than a bell of
     * the same peak — all its energy is at the extremes instead of
     * swinging through them — so matching the numbers is not enough;
     * what matters is that it does not stick out.
     */
    @Test
    fun `the digital beeps sit at the same level as the bells`() {
        val beeps = player.buffer(Bells.peal(Prefs.BELL_STYLE_BEEP, 15, 0, Bells.MARKS_HOUR)!!)
        val bells = player.buffer(Bells.peal(Prefs.BELL_STYLE_COUNT, 15, 0, Bells.MARKS_HOUR)!!)

        val ratio = rms(beeps) / rms(bells)
        assertTrue(
            "beeps ${rms(beeps)} against bells ${rms(bells)} — ratio $ratio",
            ratio in 0.5f..2.0f
        )
    }

    /** And nothing clips: a clipped buffer is a buzz, not a bell. */
    @Test
    fun `nothing is loud enough to clip`() {
        for (style in arrayOf(
            Prefs.BELL_STYLE_COUNT, Prefs.BELL_STYLE_SHIPS,
            Prefs.BELL_STYLE_SINGLE, Prefs.BELL_STYLE_BEEP
        )) {
            // Noon, which is the most strikes any of them ever plays and so
            // the most chance of two ringing at once and summing.
            val buffer = player.buffer(Bells.peal(style, 12, 0, Bells.MARKS_HOUR)!!)
            assertTrue("$style peaks at ${peak(buffer)}", peak(buffer) <= 1f)
            assertTrue("$style is silent", peak(buffer) > 0.05f)
        }
    }

    /** The quarters are quieter than the hour they lead up to. */
    @Test
    fun `a quarter does not shout louder than its hour`() {
        val quarter = player.buffer(Bells.peal(null, 9, 45, Bells.MARKS_QUARTERS)!!)
        val hour = player.buffer(Bells.peal(null, 9, 0, Bells.MARKS_QUARTERS)!!)
        assertTrue(
            "quarter ${peak(quarter)} vs hour ${peak(hour)}",
            peak(quarter) <= peak(hour) * 1.1f
        )
    }

    // -------------------------------------------------------- the pitches

    /**
     * The beep is where it says it is. A square wave's energy is at its
     * fundamental, so if this lands an octave down the timbre is wrong in
     * a way that no amount of listening to the code would reveal.
     */
    @Test
    fun `the beep really is up at Casio pitch`() {
        // Absolute numbers, not multiples of the constant being checked.
        // Probing at CASIO_HZ, CASIO_HZ/2 and so on measures only that a
        // buffer built at some frequency has its energy at that frequency,
        // which is true whatever the constant is set to — the test would
        // have sat there agreeing with an octave error for ever.
        val buffer = player.beepBuffer(1, Bells.CASIO_HZ, 0.055, 0.20)
        val found = strongestOf(buffer, listOf(523.0, 1046.0, 2093.0, 4186.0, 8372.0))
        assertEquals("a cheap watch beeps up near the top of the piano", 4186.0, found, 1.0)
        assertTrue("${Bells.CASIO_HZ} Hz is not a watch beep", Bells.CASIO_HZ > 3500.0)
    }

    /** And a bell is at its own, an octave and a half lower. */
    @Test
    fun `the grandfather bell is where it belongs`() {
        val buffer = player.bellBuffer(1, false, ChimePlayer.GRANDFATHER_HZ, 3.0, 1.3)
        val found = strongestOf(buffer, listOf(196.0, 392.0, 784.0, 4186.0))
        assertEquals("G above middle C", 392.0, found, 1.0)
        assertTrue("and well below the beep", ChimePlayer.GRANDFATHER_HZ < Bells.CASIO_HZ / 4)
    }

    // --------------------------------------------------------- the shapes

    /**
     * The edges of a beep are ramped. Without the ramp the discontinuity
     * itself clicks, and the whole thing sounds broken rather than cheap —
     * which is measurable as a sample that jumps most of the way to full
     * scale from one sample to the next.
     */
    @Test
    fun `a beep does not begin with a click`() {
        val buffer = player.beepBuffer(1, Bells.CASIO_HZ, 0.055, 0.20)
        var worst = 0f
        for (i in 1 until buffer.size) {
            worst = maxOf(worst, abs(buffer[i] - buffer[i - 1]))
        }
        // A square wave flips every half cycle, so the flips themselves are
        // large; what must not happen is the buffer *starting* at full.
        assertTrue("starts at ${buffer[0]}", abs(buffer[0]) < 0.01f)
        assertTrue("ends at ${buffer.last()}", abs(buffer.last()) < 0.01f)
        assertTrue("worst step $worst", worst <= 2f * peak(buffer) + 0.001f)
    }

    /**
     * Twelve beeps really are over quickly, in samples and not only in the
     * numbers the rule hands out.
     */
    @Test
    fun `twelve beeps take a few seconds, twelve strikes take many`() {
        val beeps = player.buffer(Bells.peal(Prefs.BELL_STYLE_BEEP, 12, 0, Bells.MARKS_HOUR)!!)
        val bells = player.buffer(Bells.peal(Prefs.BELL_STYLE_COUNT, 12, 0, Bells.MARKS_HOUR)!!)
        val beepSeconds = beeps.size.toFloat() / rate
        val bellSeconds = bells.size.toFloat() / rate
        assertTrue("$beepSeconds s", beepSeconds < 3f)
        assertTrue("$bellSeconds s", bellSeconds > 15f)
    }
}
