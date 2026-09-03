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

    /**
     * Where they are kept, and when the last look for a new one was.
     *
     * One file per day rather than one file, which is two features and a
     * bug fix in the same change. The bug: a single file was written in
     * place, so a second fetch landing while the first was half done left
     * a truncated JPEG on disk, and a truncated JPEG decodes to nothing —
     * which is a globe with no clouds on it until the next fetch six
     * hours later, reported as the clouds sometimes not being there. The
     * feature: the world can be wound backwards, and a week of days on
     * disk means winding it back a day changes the weather with it.
     */
    private const val WHEN = "pref_clouds_at"
    private const val PREFIX = "clouds-"
    private const val SUFFIX = ".jpg"

    /** And the one file the single-file version used, for tidying away. */
    private const val WAS = "clouds.jpg"

    /** How many days are worth keeping, which is how far back the globe goes. */
    const val KEEP_DAYS = 7

    /**
     * And how many of them one fetch will go and get.
     *
     * A week of pictures is half a megabyte, which is not a thing to do
     * to somebody's data allowance in one go the first time they open the
     * globe. Three at a time fills the week in over two or three days of
     * ordinary use, and the days that are not there yet simply show the
     * nearest one that is.
     */
    const val AT_A_TIME = 3

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

    private fun file(context: Context, day: String): File =
        File(context.cacheDir, "$PREFIX$day$SUFFIX")

    /** Which day's photograph belongs on a globe wound to that instant. */
    fun dayOf(atMs: Long): String = SatelliteClouds.dayFor(atMs)

    /** That day, and the six before it, freshest first. */
    private fun daysBack(fromMs: Long): List<String> =
        (0 until KEEP_DAYS).map { dayOf(fromMs - it * DAY_MS) }

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * The one decoded copy of the picture on disk, or nothing.
     *
     * Held rather than decoded each time, and that is not an optimisation
     * — it is the difference between the clouds working and not. The
     * globe keeps its projected disc against the *identity* of the
     * picture it was projected from, because projecting a quarter of a
     * million points is not something to do twice for the same photograph.
     * This handed back a brand-new bitmap on every call, so every time
     * anything reapplied the settings — coming back to the app, swiping to
     * another card, night falling — the globe decided its clouds had
     * changed, threw away three baked discs and did the whole projection
     * again from a fresh eighty-kilobyte JPEG decode.
     *
     * One held copy and not a week of them: a day is a megabyte decoded,
     * and winding the world back through a week would otherwise be seven
     * of them sitting in memory for the sake of a gesture that is over in
     * a second.
     */
    private var held: Bitmap? = null
    private var heldDay: String? = null
    private var heldAt = 0L

    /**
     * The clouds for a globe showing [atMs], which is not always now.
     *
     * The nearest day at or before the one asked for, because that is what
     * the sky over a place actually was. Falling forward to a later day
     * only when there is nothing earlier at all — a globe wound back to
     * before the first picture we ever fetched, which is better served by
     * the oldest weather we have than by none.
     */
    fun cached(context: Context, atMs: Long = TimeKeeper.nowMs()): Bitmap? {
        if (!wanted(context)) return null
        val wanted = daysBack(atMs).firstOrNull { file(context, it).exists() }
            ?: onDisk(context).lastOrNull()
            ?: return null
        val on = file(context, wanted)
        val stamp = on.lastModified()
        held?.let { if (heldDay == wanted && heldAt == stamp && !it.isRecycled) return it }
        return try {
            BitmapFactory.decodeFile(on.path, BitmapFactory.Options().apply { inScaled = false })
                ?.also {
                    held = it
                    heldDay = wanted
                    heldAt = stamp
                }
        } catch (e: Exception) {
            // A half-written file from a fetch that was killed partway.
            null
        }
    }

    /** Which days are on disk, oldest first. */
    private fun onDisk(context: Context): List<String> =
        (context.cacheDir.listFiles() ?: emptyArray())
            .mapNotNull {
                val name = it.name
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) {
                    name.removePrefix(PREFIX).removeSuffix(SUFFIX)
                } else {
                    null
                }
            }
            .sorted()

    /** Whether what is on disk is old enough to be worth adding to. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        // Today's is missing: go now, whatever the throttle says. This is
        // the first run and the morning after every night.
        if (!file(context, dayOf(nowMs)).exists()) return true
        if (daysBack(nowMs).any { !file(context, it).exists() }) {
            val at = PreferenceManager.getDefaultSharedPreferences(context).getLong(WHEN, 0L)
            return at <= 0L || nowMs - at >= EVERY_MS || nowMs < at
        }
        return false
    }

    /**
     * Fetch what is missing, newest first, and throw away what is too old.
     *
     * Blocking. Returns whether anything new was written.
     */
    fun refresh(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        // The single file the first version kept, which is now nobody's
        // day and would sit in the cache for ever.
        File(context.cacheDir, WAS).delete()
        var got = 0
        for (day in daysBack(nowMs)) {
            if (got >= AT_A_TIME) break
            if (file(context, day).exists()) continue
            val bytes = fetch.get(SatelliteClouds.url(day)) ?: continue
            // Decoded before it is kept. A file that turns out not to be a
            // picture is a globe that draws nothing every frame for six
            // hours, and finding that out now costs one decode.
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) continue
            if (write(context, day, bytes)) got++
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putLong(WHEN, nowMs).apply()
        prune(context, nowMs)
        return got > 0
    }

    /**
     * Written beside itself and then moved into place.
     *
     * A rename is atomic and a write is not, and the difference is the
     * whole of one bug: two fetches overlapping wrote the same file at
     * once, and half a JPEG decodes to nothing at all.
     */
    private fun write(context: Context, day: String, bytes: ByteArray): Boolean = try {
        val part = File(context.cacheDir, "$PREFIX$day$SUFFIX.part")
        part.writeBytes(bytes)
        part.renameTo(file(context, day)) || run { part.delete(); false }
    } catch (e: Exception) {
        false
    }

    /** Anything older than the week goes. */
    private fun prune(context: Context, nowMs: Long) {
        val keep = daysBack(nowMs).toSet()
        for (day in onDisk(context)) {
            if (day !in keep) file(context, day).delete()
        }
    }

    /**
     * And the same on a thread nothing is waiting for.
     *
     * One at a time. It used to start a thread every time the settings
     * were applied — coming back to the app, swiping a card, night falling
     * — so three or four of them could be writing the same file at once.
     */
    @Volatile
    private var fetching = false

    fun refreshInBackground(context: Context) {
        if (!wanted(context) || !stale(context)) return
        synchronized(this) {
            if (fetching) return
            fetching = true
        }
        val app = context.applicationContext
        Thread {
            try {
                refresh(app)
            } finally {
                fetching = false
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Throws the picture away, for the switch being turned off. */
    fun forget(context: Context) {
        held = null
        heldDay = null
        heldAt = 0L
        File(context.cacheDir, WAS).delete()
        for (day in onDisk(context)) file(context, day).delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(WHEN).apply()
    }
}
