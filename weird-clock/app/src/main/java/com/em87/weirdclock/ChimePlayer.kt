package com.em87.weirdclock

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthesizes and plays all clock sounds at runtime, so the app needs no
 * bundled audio files. Bells are built from a handful of exponentially
 * decaying inharmonic partials, which is enough to read as "bell".
 */
class ChimePlayer {

    companion object {
        const val HOUR_BELL_HZ = 520.0
        const val HALF_HOUR_BELL_HZ = 780.0
        private const val SAMPLE_RATE = 44100
    }

    private var bellTrack: AudioTrack? = null
    private var tickTrack: AudioTrack? = null
    private val lock = Any()

    /**
     * Strikes the bell [count] times. With [pairGrouping] the strikes come in
     * nautical pairs (ding-ding ... ding-ding) as on a ship's bell.
     */
    fun playBellSequence(count: Int, pairGrouping: Boolean, frequency: Double = HOUR_BELL_HZ) {
        if (count <= 0) return
        thread(name = "bell-synth") {
            val strikeOffsets = ArrayList<Double>(count)
            var t = 0.0
            for (i in 0 until count) {
                strikeOffsets.add(t)
                t += if (pairGrouping && i % 2 == 0) 0.4 else 1.1
            }
            val ringSeconds = 2.5
            val total = strikeOffsets.last() + ringSeconds + 0.3
            val buffer = FloatArray((total * SAMPLE_RATE).toInt())
            for (offset in strikeOffsets) {
                addBellStrike(buffer, offset, frequency, ringSeconds)
            }
            synchronized(lock) {
                bellTrack?.release()
                bellTrack = buildStaticTrack(toPcm(buffer)).also { it.play() }
            }
        }
    }

    /** Short mechanical "tik" used for the once-per-second ticking option. */
    fun playTick() {
        synchronized(lock) {
            val existing = tickTrack
            if (existing != null) {
                existing.stop()
                existing.reloadStaticData()
                existing.play()
                return
            }
            val duration = 0.03
            val samples = FloatArray((duration * SAMPLE_RATE).toInt())
            for (n in samples.indices) {
                val t = n.toDouble() / SAMPLE_RATE
                samples[n] = (exp(-t * 260.0) * sin(2.0 * PI * 2100.0 * t) * 0.25).toFloat()
            }
            tickTrack = buildStaticTrack(toPcm(samples)).also { it.play() }
        }
    }

    fun release() {
        synchronized(lock) {
            bellTrack?.release()
            bellTrack = null
            tickTrack?.release()
            tickTrack = null
        }
    }

    private fun addBellStrike(buffer: FloatArray, offsetSeconds: Double, frequency: Double, duration: Double) {
        // Partials: frequency multiplier, relative amplitude, decay rate.
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
                sample += p[1] * exp(-p[2] * t) * sin(2.0 * PI * frequency * p[0] * t)
            }
            buffer[index] += (sample * 0.22).toFloat()
        }
    }

    private fun toPcm(samples: FloatArray): ShortArray {
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            pcm[i] = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
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
