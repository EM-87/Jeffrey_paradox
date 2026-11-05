package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A week of daily pictures of the whole earth, fetched one day at a time
 * and kept on disk by the day they are of.
 *
 * This is what [CloudStore] was, taken out of it so the night lights can
 * have the same thing without a second copy of it: one file per day, a
 * few fetched at a time, an atomic write, a decode before anything is
 * kept, one held decoded copy, and a prune of anything older than the
 * week. The two differ in what they ask for and where, which is the
 * [sources] handed in, and in one rule the lights have that the clouds do
 * not — a day that is still filling in and is worth asking for again.
 *
 * A day may have more than one place to ask, tried in order: the first
 * answer that is a picture and not a blank one is the day's picture. The
 * lights come from two satellites and the newer one is not always there,
 * and a blank answer from a service is not an error — it is a day the
 * service has nothing for, served as a transparent picture.
 */
class DailyImageStore(
    /** What the files are called, before the day. */
    private val prefix: String,
    /** And after it. */
    private val suffix: String,
    /** The preference that remembers when the last fetch ran. */
    private val whenKey: String,
    /** Where to ask for a day, in order of preference. */
    private val sources: (day: String) -> List<String>,
    /** Which day a globe showing an instant wants. */
    private val dayOf: (atMs: Long) -> String,
    /**
     * Whether a day already on disk may still be filling in, and so is
     * worth fetching again once it is [everyMs] old. True of the day the
     * satellite is still flying over; never true of the clouds, which are
     * asked for a day late on purpose.
     */
    private val stillFilling: (day: String, nowMs: Long) -> Boolean = { _, _ -> false },
    /** How long one fetch is good for before the next is allowed. */
    val everyMs: Long = EVERY_MS,
    /** How many days are worth keeping, which is how far back the globe goes. */
    val keepDays: Int = KEEP_DAYS,
    /** And how many of them one fetch will go and get. */
    val atATime: Int = AT_A_TIME
) {

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
    var fetch: Fetch = Fetch { url -> download(url) }

    fun file(context: Context, day: String): File =
        File(context.cacheDir, "$prefix$day$suffix")

    /** Which day's picture belongs on a globe wound to that instant. */
    fun dayOf(atMs: Long): String = dayOf.invoke(atMs)

    /** That day, and the six before it, freshest first. */
    fun daysBack(fromMs: Long): List<String> =
        (0 until keepDays).map { dayOf(fromMs - it * DAY_MS) }

    /**
     * The one decoded copy of the picture on disk, or nothing.
     *
     * Held rather than decoded each time, and that is not an optimisation
     * — it is the difference between the clouds working and not. The
     * globe keeps its projected disc against the *identity* of the
     * picture it was projected from, because projecting a quarter of a
     * million points is not something to do twice for the same photograph.
     * Handing back a brand-new bitmap on every call made the globe throw
     * away its discs every time anything reapplied the settings.
     *
     * One held copy and not a week of them: a day is megabytes decoded,
     * and winding the world back through a week would otherwise be seven
     * of them in memory for the sake of a gesture that is over in a
     * second.
     */
    private var held: Bitmap? = null
    private var heldDay: String? = null
    private var heldAt = 0L

    /**
     * Which day's picture a globe showing [atMs] would wear: the nearest
     * day at or before the one asked for, because that is what the sky
     * over a place actually was — or, when there is nothing earlier at
     * all, the oldest there is, which is the nearest.
     *
     * The oldest, not the newest. It used to be the newest, so a globe
     * wound back past the week — or past however much of it the phone had
     * — quietly wore this morning's weather, and the clouds were seen not
     * to change while the night went on moving. See [worn], which says
     * how far off the day worn is, so the globe can say so too.
     */
    fun dayCached(context: Context, atMs: Long): String? =
        daysBack(atMs).firstOrNull { file(context, it).exists() }
            ?: onDisk(context).firstOrNull()

    /** A day worn in place of the one asked for, and how far off it is. */
    class Worn(val day: String, val offDays: Int)

    /**
     * The day a globe showing [atMs] would wear and how many days off the
     * day asked for it is, either way — nought when it is the day itself,
     * more when the phone has nothing nearer: an earlier day for a gap in
     * the week, a later one for a world wound back past it. Null with
     * nothing on disk.
     */
    fun worn(context: Context, atMs: Long): Worn? {
        val day = dayCached(context, atMs) ?: return null
        val asked = msOf(dayOf(atMs)) ?: return Worn(day, 0)
        val got = msOf(day) ?: return Worn(day, 0)
        return Worn(day, kotlin.math.abs((asked - got) / DAY_MS).toInt())
    }

    /** The picture for a globe showing [atMs], which is not always now. */
    fun cached(context: Context, atMs: Long): Bitmap? {
        val wanted = dayCached(context, atMs) ?: return null
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

    /**
     * Lets go of the held decoded copy.
     *
     * For a caller that has copied what it needs out of the picture and
     * keeps that instead — the lights keep a byte of grey per pixel and
     * have no use for the four bytes of colour it came from. The next
     * [cached] decodes again, which is the caller's business to avoid by
     * asking [dayCached] and [stamp] first.
     */
    fun release() {
        held = null
        heldDay = null
        heldAt = 0L
    }

    /** When the file for [day] was last written, or 0 for none. */
    fun stamp(context: Context, day: String): Long = file(context, day).lastModified()

    /** Which days are on disk, oldest first. */
    fun onDisk(context: Context): List<String> =
        (context.cacheDir.listFiles() ?: emptyArray())
            .mapNotNull {
                val name = it.name
                if (name.startsWith(prefix) && name.endsWith(suffix)) {
                    name.removePrefix(prefix).removeSuffix(suffix)
                } else {
                    null
                }
            }
            .sorted()

    /** Whether a day on disk is old enough, and still filling, to ask for again. */
    private fun worthAskingAgain(context: Context, day: String, nowMs: Long): Boolean =
        stillFilling(day, nowMs) &&
            nowMs - file(context, day).lastModified() >= everyMs

    /** Whether what is on disk is old enough to be worth adding to. */
    fun stale(context: Context, nowMs: Long): Boolean {
        val today = dayOf(nowMs)
        // Today's is missing: go now, whatever the throttle says. This is
        // the first run and the morning after every night.
        if (!file(context, today).exists()) return true
        if (worthAskingAgain(context, today, nowMs)) return true
        if (daysBack(nowMs).any { !file(context, it).exists() }) {
            val at = PreferenceManager.getDefaultSharedPreferences(context).getLong(whenKey, 0L)
            return at <= 0L || nowMs - at >= everyMs || nowMs < at
        }
        return false
    }

    /**
     * Fetch what is missing, newest first, and throw away what is too old.
     *
     * Blocking. Returns whether anything new was written. A day that no
     * source has a picture for is left missing and asked for again next
     * time, which is how the lights' two-day lag is lived with; it does
     * not use up one of the [atATime], or a service running behind would
     * keep the week from ever filling in behind it.
     */
    fun refresh(context: Context, nowMs: Long): Boolean {
        var got = 0
        var asked = 0
        for (day in daysBack(nowMs)) {
            if (got >= atATime || asked >= keepDays) break
            if (file(context, day).exists() && !worthAskingAgain(context, day, nowMs)) continue
            asked++
            for (url in sources(day)) {
                val bytes = fetch.get(url) ?: continue
                // Decoded before it is kept. A file that turns out not to
                // be a picture is a globe that draws nothing every frame
                // for six hours, and finding that out now costs one
                // decode. And a picture of nothing is not kept either: a
                // service with no data for a day serves a transparent
                // one, and the day before it is the honest thing to show.
                if (blank(bytes)) continue
                if (write(context, day, bytes)) {
                    got++
                    break
                }
            }
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putLong(whenKey, nowMs).apply()
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
        val part = File(context.cacheDir, "$prefix$day$suffix.part")
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
     * One at a time. The clouds used to start a thread every time the
     * settings were applied — coming back to the app, swiping a card,
     * night falling — so three or four of them could be writing the same
     * file at once. The thread touches files and a preference and nothing
     * else: nothing it does reaches a view, so there is nothing for it to
     * reach after the view has gone.
     */
    @Volatile
    private var fetching = false

    fun refreshInBackground(context: Context, nowMs: Long) {
        if (!stale(context, nowMs)) return
        synchronized(this) {
            if (fetching) return
            fetching = true
        }
        val app = context.applicationContext
        Thread {
            try {
                refresh(app, nowMs)
            } finally {
                fetching = false
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Throws the pictures away, for the switch being turned off. */
    fun forget(context: Context) {
        held = null
        heldDay = null
        heldAt = 0L
        for (day in onDisk(context)) file(context, day).delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(whenKey).apply()
    }

    companion object {

        /** How long one picture is worth keeping. */
        const val EVERY_MS = 6L * 60L * 60L * 1000L

        const val KEEP_DAYS = 7
        const val AT_A_TIME = 3
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Midnight UTC at the start of a day named the way the files are, or null for a name that is not one. */
        fun msOf(day: String): Long? {
            val parts = day.split("-")
            if (parts.size != 3) return null
            val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            utc.clear()
            utc.set(
                parts[0].toIntOrNull() ?: return null,
                (parts[1].toIntOrNull() ?: return null) - 1,
                parts[2].toIntOrNull() ?: return null
            )
            return utc.timeInMillis
        }

        /** How long a fetch gets before it is abandoned. */
        const val TIMEOUT_MS = 20_000

        /**
         * The largest a picture of the earth may be before it is refused.
         *
         * A guard rather than a limit. These URLs are fixed and NASA's
         * answers are under a megabyte, so anything an order of magnitude
         * past that is a redirect to something else, an error page with a
         * picture on it, or a service having a bad day — and none of them
         * belong in a clock's cache.
         */
        const val TOO_BIG = 3 * 1024 * 1024

        /**
         * Whether these bytes are not a picture, or a picture of nothing.
         *
         * Looked at small: one pixel in eight each way is enough to tell a
         * transparent or black world from one with anything on it, and a
         * full decode of a megapixel for a yes-or-no is not worth the
         * memory on a thread that is holding the bytes already.
         */
        fun blank(bytes: ByteArray): Boolean {
            val small = try {
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 }
                )
            } catch (e: Exception) {
                null
            } ?: return true
            val w = small.width
            val h = small.height
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            small.recycle()
            return pixels.none { it ushr 24 > 0 && (it and 0xFFFFFF) > 0x020202 }
        }

        private fun download(url: String): ByteArray? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", WeatherSources.AGENT)
                }
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.size > TOO_BIG) null else bytes
            } catch (e: Exception) {
                // A service that is down is one day with no picture on the
                // globe, which is exactly what the globe looked like before
                // this existed.
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
