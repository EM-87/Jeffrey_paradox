package com.em87.weirdclock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

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
        private const val SAMPLE_RATE = 44100
    }

    private var bellTrack: AudioTrack? = null
    private var soundPool: SoundPool? = null
    private var tickSoundId = 0
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

    /** Short mechanical "tik". Prepared via [prepareTick]; cheap to spam. */
    fun playTick() {
        synchronized(lock) {
            if (tickReady) soundPool?.play(tickSoundId, 0.8f, 0.8f, 1, 0, 1f)
        }
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
            synchronized(lock) {
                bellTrack?.release()
                bellTrack = buildStaticTrack(toPcm(buffer)).also { it.play() }
            }
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

    private fun toPcm(samples: FloatArray): ShortArray {
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            pcm[i] = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    private fun writeWav(file: File, pcm: ShortArray) {
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
