package com.em87.weirdclock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Synthesizes and plays all clock sounds at runtime, so the app needs no
 * bundled audio files. Bells are built from a handful of exponentially
 * decaying inharmonic partials. Each bell style has its own timbre:
 * grandfather clocks strike low and long, ship's bells bright and short,
 * the single-strike gong deep and very long. The per-second tick goes
 * through a SoundPool (loaded from a synthesized wav in the cache dir)
 * for low, consistent latency.
 */
class ChimePlayer {

    companion object {
        const val GRANDFATHER_HZ = 392.0
        const val SHIPS_HZ = 660.0
        const val GONG_HZ = 262.0
        const val HALF_HOUR_BELL_HZ = 784.0
        const val WINDING_BELL_HZ = 587.0
        const val DAY_CHIME_HZ = 880.0

        /**
         * The bell at the end of a round: high, bright and rung three times.
         *
         * Nearly an octave above the ship's bell and rung for a great deal
         * longer, which is the whole difference between "the watch has
         * changed" and "stop, that is the round". It is the same striking
         * arithmetic underneath — a bell is a bell — so it borrows
         * [bellBuffer] rather than getting a synthesiser of its own.
         */
        const val RING_BELL_HZ = 1174.7

        private const val SAMPLE_RATE = 44100

        /**
         * Whichever player has a peal in the air.
         *
         * There is one of these per service and the peal is nearly always
         * somebody else's: the bells ring from [BellService] and the alarm
         * that wants them out of the way is in [AlarmService], each with a
         * player of its own. Only one peal is ever sounding — the bells
         * strike one hour at a time — so one reference is enough.
         */
        @Volatile
        private var pealing: ChimePlayer? = null

        /**
         * Cuts short whatever peal is sounding, wherever it came from.
         *
         * For the alarm, when the alarm has the right of way. An alarm
         * that has to wait out four strikes of a gong before it can be
         * heard is an alarm the bells have overruled. Nothing else is
         * touched: ticks and pushers go through the sound pool, not this
         * track, so they are not in the line of fire.
         */
        fun silencePeal() {
            pealing?.stopPealTrack()
            pealing = null
            Bells.soundingUntil = 0L
        }
    }

    /** Master volume for bell buffers; ramped by the alarm service. */
    @Volatile
    var volume = 1f

    private var bellTrack: AudioTrack? = null
    /**
     * Volatile, and read without the lock — see [playTick].
     *
     * The lock is held while a bell is being built into an AudioTrack,
     * which is tens of milliseconds of work on a buffer. A tick that had to
     * wait for it arrived late, and late is the one thing a tick cannot be.
     */
    @Volatile
    private var soundPool: SoundPool? = null

    @Volatile
    private var tickSoundId = 0

    @Volatile
    private var tickReady = false
    private val lock = Any()

    /**
     * Prepares the low-latency tick sound. Call once (e.g. from onCreate);
     * safe to call repeatedly.
     */
    fun prepareTick(context: Context) {
        synchronized(lock) {
            if (soundPool != null) return
            val pool = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                synchronized(lock) { if (status == 0 && sampleId == tickSoundId) tickReady = true }
            }
            soundPool = pool
            thread(name = "tick-synth") {
                val file = File(context.cacheDir, "tick.wav")
                if (!file.exists()) {
                    val duration = 0.03
                    val samples = FloatArray((duration * SAMPLE_RATE).toInt())
                    for (n in samples.indices) {
                        val t = n.toDouble() / SAMPLE_RATE
                        samples[n] = (exp(-t * 260.0) * sin(2.0 * PI * 2100.0 * t) * 0.3).toFloat()
                    }
                    writeWav(file, toPcm(samples))
                }
                synchronized(lock) { tickSoundId = pool.load(file.path, 1) }
            }
        }
    }

    /**
     * Short mechanical "tik", prepared via [prepareTick] and played without
     * taking the lock.
     *
     * It used to take it, and the lock is also held while a bell is being
     * synthesised into an AudioTrack — so an hourly chime, or a countdown
     * finishing, stalled the tick behind it. SoundPool.play is safe to call
     * from any thread; the three fields it needs are volatile, and the
     * worst a race can do is play a tick a moment after release, which
     * SoundPool ignores.
     */
    fun playTick(volume: Float = Ticker.tickVolume(night = false)): Boolean {
        val pool = soundPool ?: return false
        if (!tickReady) return false
        // Non-zero is a stream that started. Zero means the pool refused —
        // out of streams, or the sample gone — and that is worth counting
        // rather than shrugging at: it is the other half of "why did that
        // tick not sound".
        val level = volume.coerceIn(0f, 1f)
        return pool.play(tickSoundId, level, level, 1, 0, 1f) != 0
    }

    /**
     * Strikes a bell [count] times. With [pairGrouping] the strikes come in
     * nautical pairs (ding-ding ... ding-ding) as on a ship's bell.
     * [ringSeconds] controls how long each strike resonates and [interval]
     * the spacing between strikes.
     */
    fun playBellSequence(
        count: Int,
        pairGrouping: Boolean,
        frequency: Double = SHIPS_HZ,
        ringSeconds: Double = 2.5,
        interval: Double = 1.1
    ) {
        if (count <= 0) return
        thread(name = "bell-synth") {
            playFloatBuffer(bellBuffer(count, pairGrouping, frequency, ringSeconds, interval))
        }
    }

    /**
     * The samples a bell peal is made of, without playing them.
     *
     * Split out so the sound can be measured rather than only heard. Every
     * voice in here is synthesised from arithmetic, and until now the only
     * way to find out whether a new one was twice as loud as the bells it
     * sits beside was to install it and be startled at six in the morning.
     */
    internal fun bellBuffer(
        count: Int,
        pairGrouping: Boolean,
        frequency: Double,
        ringSeconds: Double,
        interval: Double
    ): FloatArray {
        val strikeOffsets = ArrayList<Double>(count)
        var t = 0.0
        for (i in 0 until count) {
            strikeOffsets.add(t)
            t += if (pairGrouping && i % 2 == 0) 0.4 else interval
        }
        val total = strikeOffsets.last() + ringSeconds + 0.3
        val buffer = FloatArray((total * SAMPLE_RATE).toInt())
        for (offset in strikeOffsets) {
            addBellStrike(buffer, offset, frequency, ringSeconds)
        }
        return buffer
    }

    /** Rings whatever [Bells] decided on, on whichever voice it chose. */
    fun play(peal: Bells.Peal) {
        // Written down before a note of it is made, because the thing that
        // reads it is an alarm in another service deciding whether to wait,
        // and it may ask half a second from now.
        Bells.soundingUntil = SystemClock.elapsedRealtime() +
            (Bells.pealSeconds(peal) * 1000).toLong()
        pealing = this
        when (peal.voice) {
            Bells.Voice.BEEP ->
                playBeepSequence(peal.count, peal.frequency, peal.ringSeconds, peal.interval)
            Bells.Voice.QUARTER_CHIME -> playQuarters(peal.count)
            Bells.Voice.BELL -> playBellSequence(
                peal.count, peal.pairGrouping, peal.frequency, peal.ringSeconds, peal.interval
            )
        }
    }

    /**
     * The hourly signal off a cheap digital watch.
     *
     * Not a bell with the resonance turned down: a piezo disc has no body
     * to ring, so there are no inharmonic partials to decay and no decay
     * either — a beep is square, flat and over. What it does have is edges,
     * and they need a millisecond or two of ramp at each end, or the
     * discontinuity itself clicks and the whole thing sounds broken rather
     * than cheap.
     */
    fun playBeepSequence(
        count: Int,
        frequency: Double = Bells.CASIO_HZ,
        beepSeconds: Double = 0.055,
        interval: Double = 0.20
    ) {
        if (count <= 0) return
        thread(name = "beep-synth") {
            playFloatBuffer(beepBuffer(count, frequency, beepSeconds, interval))
        }
    }

    /** The samples a beep peal is made of, without playing them. */
    internal fun beepBuffer(
        count: Int,
        frequency: Double,
        beepSeconds: Double,
        interval: Double
    ): FloatArray {
        val total = (count - 1) * interval + beepSeconds + 0.05
        val buffer = FloatArray((total * SAMPLE_RATE).toInt())
        for (i in 0 until count) {
            addBeep(buffer, i * interval, frequency, beepSeconds)
        }
        return buffer
    }

    private fun addBeep(buffer: FloatArray, offsetSeconds: Double, hz: Double, duration: Double) {
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        for (n in 0 until length) {
            val i = start + n
            if (i >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val edge = min(t / 0.0015, (duration - t) / 0.0015).coerceIn(0.0, 1.0)
            val square = if (sin(2.0 * PI * hz * t) >= 0) 1.0 else -1.0
            // Mostly the square wave, with a little of the octave above so
            // it reads as a small thing buzzing rather than a test tone.
            val sample = square * 0.8 + sin(4.0 * PI * hz * t) * 0.2
            buffer[i] += (sample * 0.18 * edge).toFloat()
        }
    }

    /** Classic digital alarm clock: four short square-wave beeps. */
    fun playDigitalAlarm() {
        thread(name = "digital-synth") { playFloatBuffer(digitalBuffer()) }
    }

    internal fun digitalBuffer(): FloatArray {
        val buffer = FloatArray((1.0 * SAMPLE_RATE).toInt())
        val beepLen = 0.09
        for (b in 0 until 4) {
            val start = (b * 0.16 * SAMPLE_RATE).toInt()
            val samples = (beepLen * SAMPLE_RATE).toInt()
            for (n in 0 until samples) {
                val i = start + n
                if (i >= buffer.size) break
                val t = n.toDouble() / SAMPLE_RATE
                val edge = min(t / 0.004, (beepLen - t) / 0.004).coerceIn(0.0, 1.0)
                val square = if (sin(2.0 * PI * 1870.0 * t) >= 0) 1.0 else -1.0
                // Was 0.20, which measured two and a half times the level
                // of the bells sitting next to it in the same picker — the
                // sort of thing you find out once, at six in the morning.
                buffer[i] += (square * 0.095 * edge).toFloat()
            }
        }
        return buffer
    }

    /**
     * Billiard-ball clack: a very short, bright noise burst with a couple of
     * high inharmonic partials. [intensity] (0–1) drives both the loudness
     * and the pitch, so a gentle nudge sounds nothing like a hard smack.
     */
    fun playClack(intensity: Float) {
        val energy = intensity.coerceIn(0.05f, 1f).toDouble()
        thread(name = "clack-synth") {
            val duration = 0.055 + 0.02 * energy
            val buffer = FloatArray((duration * SAMPLE_RATE).toInt())
            val hz = 1500.0 + 900.0 * energy
            for (i in buffer.indices) {
                val t = i.toDouble() / SAMPLE_RATE
                val decay = exp(-t * 150.0)
                val body = sin(2.0 * PI * hz * t) * 0.6 +
                    sin(2.0 * PI * hz * 2.41 * t) * 0.3
                val click = (Math.random() * 2.0 - 1.0) * exp(-t * 900.0) * 0.5
                buffer[i] = ((body * decay + click) * 0.30 * energy).toFloat()
            }
            playFloatBuffer(buffer)
        }
    }

    /** Ball into the table cushion: a soft, low, damped thud. */
    fun playCushion(intensity: Float) {
        val energy = intensity.coerceIn(0.05f, 1f).toDouble()
        thread(name = "cushion-synth") {
            val buffer = FloatArray((0.10 * SAMPLE_RATE).toInt())
            for (i in buffer.indices) {
                val t = i.toDouble() / SAMPLE_RATE
                val decay = exp(-t * 60.0)
                val body = sin(2.0 * PI * (150.0 + 60.0 * energy) * t)
                val thud = (Math.random() * 2.0 - 1.0) * exp(-t * 200.0) * 0.35
                buffer[i] = ((body * decay + thud) * 0.22 * energy).toFloat()
            }
            playFloatBuffer(buffer)
        }
    }

    /** Quarter chimes: quick, bright double strikes — clearly not the hour. */
    fun playQuarters(rounds: Int = 3) {
        if (rounds <= 0) return
        thread(name = "quarters-synth") { playFloatBuffer(quarterBuffer(rounds)) }
    }

    internal fun quarterBuffer(rounds: Int): FloatArray {
        val total = rounds * 0.62 + 1.2
        val buffer = FloatArray((total * SAMPLE_RATE).toInt())
        for (r in 0 until rounds) {
            val base = r * 0.62
            addBellStrike(buffer, base, 1046.5, 0.55)
            addBellStrike(buffer, base + 0.17, 1318.5, 0.55)
        }
        return buffer
    }

    /** Cuckoo-clock call: two soft flute notes, a falling third. Cu-coo. */
    fun playCuckoo() {
        thread(name = "cuckoo-synth") {
            val buffer = FloatArray((0.9 * SAMPLE_RATE).toInt())
            addFluteNote(buffer, 0.0, 0.22, 740.0)
            addFluteNote(buffer, 0.32, 0.30, 588.0)
            playFloatBuffer(buffer)
        }
    }

    private fun addFluteNote(buffer: FloatArray, offsetSeconds: Double, duration: Double, hz: Double) {
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        for (n in 0 until length) {
            val i = start + n
            if (i >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val x = t / duration
            val envelope = sin(PI * x)
            val sample = sin(2.0 * PI * hz * t) +
                0.35 * sin(4.0 * PI * hz * t) +
                0.10 * sin(6.0 * PI * hz * t)
            buffer[i] += (sample * envelope * 0.22).toFloat()
        }
    }

    /** Two synthesized baby wails: swept, wavering, saturated harmonics. */
    fun playBabyCry() {
        thread(name = "cry-synth") { playFloatBuffer(babyCryBuffer()) }
    }

    internal fun babyCryBuffer(): FloatArray {
        val buffer = FloatArray((3.6 * SAMPLE_RATE).toInt())
        addCryWail(buffer, 0.0, 1.4, 430.0)
        addCryWail(buffer, 1.9, 1.5, 470.0)
        return buffer
    }

    private fun addCryWail(buffer: FloatArray, offsetSeconds: Double, duration: Double, baseHz: Double) {
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        val amps = doubleArrayOf(1.0, 0.55, 0.40, 0.28, 0.18, 0.10)
        var phase = 0.0
        for (n in 0 until length) {
            val i = start + n
            if (i >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val x = t / duration
            // Pitch rises then falls, with a waver that grows over the wail.
            val contour = 1.0 + 0.28 * sin(PI * x)
            val vibrato = 1.0 + 0.04 * x * sin(2.0 * PI * 7.0 * t)
            phase += 2.0 * PI * baseHz * contour * vibrato / SAMPLE_RATE
            val envelope = (min(x / 0.08, 1.0) * min((1.0 - x) / 0.15, 1.0)).coerceIn(0.0, 1.0) *
                (0.75 + 0.25 * sin(2.0 * PI * 5.5 * t))
            var sample = 0.0
            for (h in amps.indices) {
                sample += amps[h] * sin(phase * (h + 1))
            }
            // Soft clipping gives the strained, vocal quality.
            sample = tanh(sample * 1.6)
            buffer[i] += (sample * envelope * 0.24).toFloat()
        }
    }

    // ------------------------------------------------------------ the zoo

    /*
     * Four animals, made of arithmetic like everything else here.
     *
     * They are synthesised and not recorded, and that is a real limitation
     * rather than a preference: a convincing cockerel is a recording of a
     * cockerel, and the app has exactly one recording in it (a newborn,
     * CC0) because a recording has a licence attached and arithmetic does
     * not. So these are impressions. The rattle is the closest to the real
     * thing — a rattlesnake is literally noise switched on and off sixty
     * times a second, which is a thing arithmetic is good at — and the dog
     * is the furthest, because a bark is a vocal tract and this is four
     * harmonics and a formant.
     *
     * What they are all good at is the job: being unignorable at six in the
     * morning and not being a bell. Each is measured against the bells for
     * loudness, so none of them can be the one that makes you jump.
     */

    /**
     * A hen-house at dawn: ki-ki-ri-kí, four syllables and the last one held.
     *
     * The crow is a harmonic stack driven hard enough to saturate, which is
     * what gives a real one its brassy edge, with a breath of noise riding
     * the envelope so it does not read as an organ.
     */
    fun playRooster() {
        thread(name = "rooster-synth") { playFloatBuffer(roosterBuffer()) }
    }

    internal fun roosterBuffer(seed: Long = 7L): FloatArray {
        val random = java.util.Random(seed)
        val buffer = FloatArray((2.0 * SAMPLE_RATE).toInt())
        // start, length, opening pitch, closing pitch. The first three are
        // clipped syllables at much the same pitch; the fourth is the one
        // everybody imitates, and it falls all the way through.
        val syllables = arrayOf(
            doubleArrayOf(0.00, 0.13, 720.0, 700.0),
            doubleArrayOf(0.20, 0.13, 760.0, 730.0),
            doubleArrayOf(0.40, 0.15, 640.0, 620.0),
            doubleArrayOf(0.62, 0.80, 900.0, 520.0)
        )
        for (s in syllables) {
            addCrow(buffer, s[0], s[1], s[2], s[3], random)
        }
        return buffer
    }

    private fun addCrow(
        buffer: FloatArray,
        offsetSeconds: Double,
        duration: Double,
        fromHz: Double,
        toHz: Double,
        random: java.util.Random
    ) {
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        val amps = doubleArrayOf(1.0, 0.85, 0.62, 0.44, 0.30, 0.20, 0.13)
        var phase = 0.0
        for (n in 0 until length) {
            val i = start + n
            if (i >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val x = t / duration
            val hz = fromHz + (toHz - fromHz) * x
            phase += 2.0 * PI * hz / SAMPLE_RATE
            // Struck on, held, and let go: a crow starts abruptly and ends
            // by running out of bird.
            val envelope = (min(x / 0.04, 1.0) * min((1.0 - x) / 0.30, 1.0)).coerceIn(0.0, 1.0)
            var sample = 0.0
            for (h in amps.indices) sample += amps[h] * sin(phase * (h + 1))
            // Driven into saturation, which is where the brassy edge comes
            // from, plus a little rasp so it is a throat and not a pipe.
            sample = tanh(sample * 1.9) + (random.nextDouble() * 2.0 - 1.0) * 0.10
            buffer[i] += (sample * envelope * 0.13).toFloat()
        }
    }

    /**
     * A rattlesnake: the rattle, with the hiss underneath it.
     *
     * The one in here that is not an impression. A rattle really is
     * broadband noise switched on and off about sixty times a second, so
     * this is that — noise, brightened by differencing it, chopped by a
     * sharp modulator — and the hiss is the same noise dulled instead and
     * swelled slowly. Nothing about it is periodic in pitch, which is why
     * it is so much harder to sleep through than a bell.
     */
    fun playRattle() {
        thread(name = "rattle-synth") { playFloatBuffer(rattleBuffer()) }
    }

    internal fun rattleBuffer(seed: Long = 11L): FloatArray {
        val random = java.util.Random(seed)
        val duration = 2.4
        val buffer = FloatArray((duration * SAMPLE_RATE).toInt())
        var previous = 0.0
        var lowpass = 0.0
        for (i in buffer.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val x = t / duration
            val noise = random.nextDouble() * 2.0 - 1.0
            // Differencing is a high pass: it leaves the top of the noise,
            // which is where a rattle lives.
            val bright = noise - previous
            previous = noise
            // And a one-pole low pass leaves the bottom, for the hiss.
            lowpass += (noise - lowpass) * 0.06
            // Sixty a second, shaped so each burst has an edge rather than
            // fading in — a rattle clatters, it does not throb.
            val chop = (0.5 + 0.5 * sin(2.0 * PI * 58.0 * t)).let { it * it * it }
            val swell = min(x / 0.12, 1.0) * min((1.0 - x) / 0.25, 1.0)
            val sample = bright * chop * 0.55 + lowpass * 1.6 * (0.35 + 0.3 * sin(PI * x))
            buffer[i] = (sample * swell * 0.40).toFloat()
        }
        return buffer
    }

    /**
     * Something large, a long way off: two howls, rising and falling.
     *
     * A slow glissando with a waver in it, which is most of what a howl is.
     * The point of it as an alarm is that it is the only voice here with no
     * attack at all — it arrives out of nothing, and a sound that arrives
     * out of nothing is a great deal harder to weave into a dream.
     */
    fun playHowl() {
        thread(name = "howl-synth") { playFloatBuffer(howlBuffer()) }
    }

    internal fun howlBuffer(seed: Long = 13L): FloatArray {
        val random = java.util.Random(seed)
        val buffer = FloatArray((5.4 * SAMPLE_RATE).toInt())
        addHowl(buffer, 0.0, 2.4, 380.0, 620.0, random)
        addHowl(buffer, 2.8, 2.3, 420.0, 680.0, random)
        return buffer
    }

    private fun addHowl(
        buffer: FloatArray,
        offsetSeconds: Double,
        duration: Double,
        lowHz: Double,
        highHz: Double,
        random: java.util.Random
    ) {
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        val amps = doubleArrayOf(1.0, 0.70, 0.44, 0.26, 0.14, 0.07)
        var phase = 0.0
        for (n in 0 until length) {
            val i = start + n
            if (i >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val x = t / duration
            // Up in the first third, held across the middle, down at the
            // end — and never a straight line, or it is a siren.
            val climb = when {
                x < 0.30 -> x / 0.30
                x < 0.65 -> 1.0
                else -> 1.0 - (x - 0.65) / 0.35 * 0.75
            }
            val vibrato = 1.0 + 0.02 * sin(2.0 * PI * 5.2 * t)
            val hz = (lowHz + (highHz - lowHz) * climb) * vibrato
            phase += 2.0 * PI * hz / SAMPLE_RATE
            var sample = 0.0
            for (h in amps.indices) sample += amps[h] * sin(phase * (h + 1))
            sample += (random.nextDouble() * 2.0 - 1.0) * 0.06
            // No attack worth the name at either end: it fades up out of
            // nothing and back into it.
            val envelope = (min(x / 0.18, 1.0) * min((1.0 - x) / 0.22, 1.0)).coerceIn(0.0, 1.0)
            buffer[i] += (sample * envelope * 0.10).toFloat()
        }
    }

    /**
     * A dog, three times, at the door.
     *
     * The weakest impression of the four, and worth saying so: a bark is a
     * vocal tract slamming shut, and this is a handful of harmonics under a
     * formant with a burst of noise on the front. It reads as a dog because
     * of its *rhythm* — short, hard, irregularly spaced — more than because
     * of its timbre.
     */
    fun playBark() {
        thread(name = "bark-synth") { playFloatBuffer(barkBuffer()) }
    }

    internal fun barkBuffer(seed: Long = 17L): FloatArray {
        val random = java.util.Random(seed)
        val buffer = FloatArray((1.6 * SAMPLE_RATE).toInt())
        // Unevenly spaced. Three barks at a metronome's spacing is a
        // machine; a dog is never quite regular.
        addBark(buffer, 0.00, 250.0, random)
        addBark(buffer, 0.34, 232.0, random)
        addBark(buffer, 0.80, 244.0, random)
        return buffer
    }

    private fun addBark(
        buffer: FloatArray,
        offsetSeconds: Double,
        baseHz: Double,
        random: java.util.Random
    ) {
        val duration = 0.19
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        // The formant: which harmonic gets the emphasis, which is what
        // makes a vowel a vowel and a "woof" not a hum.
        val formantHz = 900.0
        var phase = 0.0
        for (n in 0 until length) {
            val i = start + n
            if (i >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val x = t / duration
            val hz = baseHz * (1.0 - 0.22 * x)
            phase += 2.0 * PI * hz / SAMPLE_RATE
            var sample = 0.0
            for (h in 1..10) {
                val partialHz = hz * h
                // A resonance around the formant, falling away either side.
                val gain = 1.0 / (1.0 + ((partialHz - formantHz) / 520.0).let { it * it })
                sample += gain * sin(phase * h) / h
            }
            // The consonant on the front: a dog is a burst before it is a note.
            val burst = (random.nextDouble() * 2.0 - 1.0) * exp(-t * 220.0) * 0.7
            val envelope = min(x / 0.03, 1.0) * exp(-x * 3.2)
            buffer[i] += ((tanh(sample * 2.2) + burst) * envelope * 0.27).toFloat()
        }
    }

    /** The end of the round. Three strikes, high and long. */
    fun playRingBell() {
        playBellSequence(3, false, RING_BELL_HZ, 3.4, 0.42)
    }

    /** The same, as samples, so it can be measured beside the others. */
    internal fun ringBellBuffer(): FloatArray =
        bellBuffer(3, false, RING_BELL_HZ, 3.4, 0.42)

    /**
     * One go of the alarm sound called [sound]; returns how long to leave
     * before the next one, in milliseconds.
     *
     * One place that knows what each name sounds like. There were two — the
     * ringing loop and, once the list grew past three, the preview in the
     * picker — and two spellings of "what does a cockerel sound like" is
     * how you end up choosing one sound in the evening and being woken by
     * another.
     *
     * The gap is part of the sound and not an afterthought. A cockerel that
     * crows again the instant it has finished is not a cockerel, it is a
     * fire alarm made of poultry; a wolf needs the silence between howls
     * more than it needs the howl. So each gap is its own buffer's length
     * plus enough room to be a pause, which is why they are all different.
     *
     * The crying baby is the one exception in the app: it has a real
     * recording behind it, played by the service through a MediaPlayer.
     * What is here is the synthesised fallback, used when that file cannot
     * be opened — and, for the same reason, what the picker previews.
     */
    fun playNamed(sound: String): Long {
        // Silence is a sound this app can make, and it has to be made
        // properly: nothing played, and a gap short enough that the loop
        // still comes round often — because the vibration and the torch
        // are driven by their own loops and the ringing has to stay
        // "ringing" for as long as they do.
        if (sound == Prefs.ALARM_SOUND_SILENT) return gapAfter(sound)
        val buffer = namedBuffer(sound)
        thread(name = "alarm-synth") { playFloatBuffer(buffer) }
        return gapAfter(sound)
    }

    /**
     * The samples of one go of [sound] — the very ones [playNamed] plays.
     *
     * Not a second recipe for the test to look at. A measurement of
     * something built alongside the thing that runs is a measurement of
     * nothing, and this app has already had one test pass for exactly that
     * reason.
     */
    internal fun namedBuffer(sound: String): FloatArray = when (sound) {
        // A second of nothing, which is what silence sounds like.
        Prefs.ALARM_SOUND_SILENT -> FloatArray(SAMPLE_RATE)
        Prefs.ALARM_SOUND_DIGITAL -> digitalBuffer()
        Prefs.ALARM_SOUND_BABY -> babyCryBuffer()
        Prefs.ALARM_SOUND_RING_BELL -> ringBellBuffer()
        Prefs.ALARM_SOUND_ROOSTER -> roosterBuffer()
        Prefs.ALARM_SOUND_SNAKE -> rattleBuffer()
        Prefs.ALARM_SOUND_WOLF -> howlBuffer()
        Prefs.ALARM_SOUND_DOG -> barkBuffer()
        else -> bellBuffer(3, false, SHIPS_HZ, 1.6, 0.5)
    }

    /** And how long to leave before going again. */
    internal fun gapAfter(sound: String): Long = when (sound) {
        Prefs.ALARM_SOUND_SILENT -> 2000L
        Prefs.ALARM_SOUND_DIGITAL -> 1300L
        Prefs.ALARM_SOUND_BABY -> 4200L
        Prefs.ALARM_SOUND_RING_BELL -> 5200L
        Prefs.ALARM_SOUND_ROOSTER -> 3200L
        Prefs.ALARM_SOUND_SNAKE -> 3000L
        Prefs.ALARM_SOUND_WOLF -> 6500L
        Prefs.ALARM_SOUND_DOG -> 2200L
        else -> 5000L
    }

    /** Whichever buffer [peal] calls for, ready to be measured or played. */
    internal fun buffer(peal: Bells.Peal): FloatArray = when (peal.voice) {
        Bells.Voice.BEEP ->
            beepBuffer(peal.count, peal.frequency, peal.ringSeconds, peal.interval)
        Bells.Voice.QUARTER_CHIME -> quarterBuffer(peal.count)
        Bells.Voice.BELL -> bellBuffer(
            peal.count, peal.pairGrouping, peal.frequency, peal.ringSeconds, peal.interval
        )
    }

    private fun playFloatBuffer(buffer: FloatArray) {
        synchronized(lock) {
            bellTrack?.release()
            bellTrack = buildStaticTrack(toPcm(buffer)).also {
                it.setVolume(volume.coerceIn(0f, 1f))
                it.play()
            }
        }
    }

    /** Stops this player's own peal. See [silencePeal], which finds it. */
    private fun stopPealTrack() {
        synchronized(lock) {
            try {
                bellTrack?.pause()
                bellTrack?.flush()
            } catch (e: IllegalStateException) {
                // Already finished on its own, which is the outcome we
                // wanted anyway.
            }
            bellTrack?.release()
            bellTrack = null
        }
    }

    fun release() {
        synchronized(lock) {
            bellTrack?.release()
            bellTrack = null
            soundPool?.release()
            soundPool = null
            tickReady = false
        }
    }

    private fun addBellStrike(buffer: FloatArray, offsetSeconds: Double, frequency: Double, duration: Double) {
        // Partials: frequency multiplier, relative amplitude, decay rate.
        // Decay rates scale with the requested ring time so a long gong
        // actually hums instead of just being a stretched buffer of silence.
        val decayScale = 2.5 / duration
        val partials = arrayOf(
            doubleArrayOf(1.0, 1.0, 1.8),
            doubleArrayOf(2.0, 0.6, 3.5),
            doubleArrayOf(2.92, 0.35, 5.0),
            doubleArrayOf(4.2, 0.15, 7.0)
        )
        val start = (offsetSeconds * SAMPLE_RATE).toInt()
        val length = (duration * SAMPLE_RATE).toInt()
        for (n in 0 until length) {
            val index = start + n
            if (index >= buffer.size) break
            val t = n.toDouble() / SAMPLE_RATE
            var sample = 0.0
            for (p in partials) {
                sample += p[1] * exp(-p[2] * decayScale * t) * sin(2.0 * PI * frequency * p[0] * t)
            }
            buffer[index] += (sample * 0.22).toFloat()
        }
    }

    internal fun toPcm(samples: FloatArray): ShortArray {
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            pcm[i] = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    /**
     * A buffer as a .wav file.
     *
     * Used by the tick, which needs a file to hand to the SoundPool — and
     * by the tests, which use it to write every voice out so that somebody
     * with ears can listen to them without waiting for an alarm to go off.
     * That is the nearest thing to hearing them this end has.
     */
    internal fun writeWav(file: File, pcm: ShortArray) {
        val dataSize = pcm.size * 2
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun writeIntLE(v: Int) {
                out.write(v and 0xFF)
                out.write(v shr 8 and 0xFF)
                out.write(v shr 16 and 0xFF)
                out.write(v shr 24 and 0xFF)
            }
            fun writeShortLE(v: Int) {
                out.write(v and 0xFF)
                out.write(v shr 8 and 0xFF)
            }
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(16)
            writeShortLE(1) // PCM
            writeShortLE(1) // mono
            writeIntLE(SAMPLE_RATE)
            writeIntLE(SAMPLE_RATE * 2)
            writeShortLE(2)
            writeShortLE(16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(dataSize)
            for (s in pcm) {
                writeShortLE(s.toInt())
            }
        }
    }

    private fun buildStaticTrack(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }
}
