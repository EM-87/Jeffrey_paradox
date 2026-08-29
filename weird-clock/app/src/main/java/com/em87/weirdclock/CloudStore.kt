package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Yesterday's photograph of the world, fetched once and kept on disk.
 *
 * The third thing in this app that opens a socket and the largest by far:
 * eighty kilobytes against the weather's few hundred bytes. So it is
 * fetched at most once a day, only with the weather switch on, and only
 * when the globe is the face being looked at — there is no reason to
 * download a picture of the earth for a clock that is drawing a dial.
 *
 * Kept as the JPEG NASA sent rather than as anything derived from it. The
 * turning of a photograph into a veil is [SatelliteClouds.veil], which is
 * cheap, happens while the disc is being projected anyway, and can be
 * changed without going back to the network.
 */
object CloudStore {

    /** How long one picture is worth keeping. */
    const val EVERY_MS = 6L * 60L * 60L * 1000L

    /** How long a fetch gets before it is abandoned. */
    const val TIMEOUT_MS = 20_000

    /**
     * The largest a picture of the earth may be before it is refused.
     *
     * A guard rather than a limit. This URL is fixed and NASA's answer is
     * about eighty kilobytes, so anything an order of magnitude past that
     * is a redirect to something else, an error page with a picture on it,
     * or a service having a bad day — and none of them belong in a
     * clock's cache.
     */
    const val TOO_BIG = 3 * 1024 * 1024

    /** Where the last one is, and when it came. */
    private const val WHEN = "pref_clouds_at"
    private const val FILE = "clouds.jpg"

    /**
     * One fetch of bytes, so the tests can watch without a network.
     *
     * Bytes rather than text, unlike [WeatherStore.Fetch]: what comes back
     * is a photograph.
     */
    fun interface Fetch {
        fun get(url: String): ByteArray?
    }

    /** The real one, replaced in tests. */
    var fetch: Fetch = Fetch { url ->
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", WeatherSources.AGENT)
            }
            if (connection.responseCode !in 200..299) return@Fetch null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > TOO_BIG) null else bytes
        } catch (e: Exception) {
            // A service that is down is one day with no clouds on the
            // globe, which is exactly what the globe looked like before
            // this existed.
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Whether anybody has asked for this at all. */
    fun wanted(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(Prefs.WEATHER, false) &&
            prefs.getBoolean(Prefs.HEMISPHERE_CLOUDS, true)
    }

    private fun file(context: Context): File = File(context.cacheDir, FILE)

    /** The picture on disk, or nothing. */
    fun cached(context: Context): Bitmap? {
        if (!wanted(context)) return null
        val on = file(context)
        if (!on.exists()) return null
        return try {
            BitmapFactory.decodeFile(on.path, BitmapFactory.Options().apply { inScaled = false })
        } catch (e: Exception) {
            // A half-written file from a fetch that was killed partway.
            null
        }
    }

    /** Whether what is on disk is old enough to be worth replacing. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        if (!file(context).exists()) return true
        val at = PreferenceManager.getDefaultSharedPreferences(context).getLong(WHEN, 0L)
        return at <= 0L || nowMs - at >= EVERY_MS || nowMs < at
    }

    /**
     * Fetch one, if it is wanted and what there is has gone off.
     *
     * Blocking. Returns whether anything new was written.
     */
    fun refresh(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        val bytes = fetch.get(SatelliteClouds.url(nowMs)) ?: return false
        // Decoded before it is kept. A file that turns out not to be a
        // picture is a globe that draws nothing every frame for six hours,
        // and finding that out now costs one decode.
        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return false
        return try {
            file(context).writeBytes(bytes)
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putLong(WHEN, nowMs).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** And the same on a thread nothing is waiting for. */
    fun refreshInBackground(context: Context) {
        if (!wanted(context) || !stale(context)) return
        Thread { refresh(context) }.apply {
            isDaemon = true
            start()
        }
    }

    /** Throws the picture away, for the switch being turned off. */
    fun forget(context: Context) {
        file(context).delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(WHEN).apply()
    }
}
