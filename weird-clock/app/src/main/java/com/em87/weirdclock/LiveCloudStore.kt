package com.em87.weirdclock

import android.content.Context
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The hourly frames of [LiveClouds], fetched as the service publishes
 * them and kept on the phone by the hour each is of.
 *
 * Not a [DailyImageStore], though it is the same kind of thing, because
 * of the one way it differs: nobody can say from the clock which frame
 * is the newest. A day's photograph is asked for by its date, a day and
 * a half behind, and is there. An hour's frame is published when five
 * satellites' worth of it has been put together, and the only one who
 * knows which hour that has got to is the service. So this asks — see
 * [LiveClouds.ask] — and then fetches the hours it is told exist and
 * has not got.
 *
 * An hour is two pictures, the infrared and the visible — see
 * [LiveClouds] for why — and is on the phone when its infrared is. The
 * visible one is fetched with it and put down first, so that the globe
 * is not handed the hour by halves; if it cannot be had, the hour is
 * the infrared's alone.
 *
 * Only with the weather switch on, the clouds on, and this kind of
 * cloud chosen: forty-five kilobytes an hour for as long as the globe
 * is on the screen is a thing to be asked for.
 */
object LiveCloudStore {

    /** What the service sent, and what it said about which hour it is of. */
    class Answer(val bytes: ByteArray, val warning: String?)

    /** One fetch, so the tests can watch without a network. */
    fun interface Fetch {
        fun get(url: String): Answer?
    }

    /** The real one, replaced in tests. */
    var fetch: Fetch = Fetch { url -> download(url) }

    /**
     * How long a frame is kept, which is how far back a globe turned by
     * hand finds the cloud turning with it. The service keeps eight
     * hours by its own account and had five on the day it was counted;
     * this keeps what it was given for half a day.
     */
    const val KEEP_MS = 12L * LiveClouds.HOUR_MS

    /**
     * How far from the instant shown a frame may be and still be the
     * cloud of that instant.
     *
     * Three hours. The newest frame is an hour or two old when it is
     * the newest, so less than that and a globe standing at now would
     * have no live cloud for part of every hour; and in three hours a
     * weather system has moved a degree or two, which is three pixels of
     * a picture this size. Past it the globe wears the daily
     * photograph's cloud for the day — see [CloudStore] — which is what
     * it wore before there was this.
     */
    const val NEAR_MS = 3L * LiveClouds.HOUR_MS

    /**
     * How old the newest frame on the phone is before the next is asked
     * after: an hour and forty-two minutes.
     *
     * The frames are an hour apart, and each is published some forty
     * minutes after the hour it is of. Watched for two hours on 24
     * November 2026, asking every four minutes: six o'clock's frame was
     * first there between 06:38 and 06:42, seven's between 07:37 and
     * 07:41, and both pictures of each arrived together. So the next
     * frame is due an hour and forty minutes after the one before it,
     * and asking two minutes after that finds it the first time. When
     * it does not, the asking was three hundred bytes — see
     * [LiveClouds.ask] — and is done again.
     */
    const val NEXT_MS = 102L * 60_000L

    /**
     * The least time between one asking and the next, whoever is
     * asking: four minutes, which is how long the service says its own
     * answer is good for (`Cache-Control: max-age=240`).
     */
    const val AGAIN_MS = 4L * 60_000L

    /** And for a clock asking by itself, once a minute, while it stands. */
    const val PATIENCE_MS = 10L * 60_000L

    /** How many hours one fetch will go and get: the newest, and two of the hours before it. */
    const val AT_A_TIME = 3

    private const val PREFIX = "live-"
    private const val SUFFIX = ".jpg"

    /** What comes after the minute in the name of an hour's visible picture. */
    private const val SEEN = "-v"
    private const val WHEN_KEY = "pref_live_clouds_at"

    /**
     * The newest hour the service has been found not to have.
     *
     * Written down because the finding costs a picture: asked for an
     * hour it no longer keeps, the service sends the nearest it does
     * keep, all twenty-seven kilobytes of it, and says so beside it. An
     * hour it has let go of does not come back, so nothing at or before
     * this is asked for again.
     */
    private const val FLOOR_KEY = "pref_live_clouds_floor"

    /** Whether this kind of cloud is the one asked for. */
    fun wanted(context: Context): Boolean = CloudStore.kind(context) == SatelliteClouds.Kind.LIVE

    /** An hour's infrared picture: named by the minute it is of, counted from 1970. */
    private fun file(context: Context, atMs: Long): File =
        File(context.cacheDir, PREFIX + atMs / 60_000L + SUFFIX)

    /** And its visible one. */
    private fun seenFile(context: Context, atMs: Long): File =
        File(context.cacheDir, PREFIX + atMs / 60_000L + SEEN + SUFFIX)

    /** Every picture of this store's on the phone, of either kind, with the instant it is of. */
    private fun pictures(context: Context): List<Pair<File, Long>> =
        (context.cacheDir.listFiles() ?: emptyArray()).mapNotNull { on ->
            val name = on.name
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return@mapNotNull null
            name.removePrefix(PREFIX).removeSuffix(SUFFIX).removeSuffix(SEEN).toLongOrNull()
                ?.let { on to it * 60_000L }
        }

    /** The instants the phone has frames of, oldest first. */
    fun onDisk(context: Context): List<Long> =
        pictures(context).filter { !it.first.name.endsWith(SEEN + SUFFIX) }.map { it.second }.sorted()

    /** Whether it is worth asking, with [gapMs] the least since anybody last did. */
    private fun worthAsking(context: Context, nowMs: Long, gapMs: Long): Boolean {
        if (!wanted(context)) return false
        val newest = onDisk(context).lastOrNull()
        if (newest != null && nowMs - newest in 0 until NEXT_MS) return false
        val at = PreferenceManager.getDefaultSharedPreferences(context).getLong(WHEN_KEY, 0L)
        return nowMs < at || nowMs - at >= gapMs
    }

    /** Whether the newest frame is old enough for the next to be asked after. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean =
        worthAsking(context, nowMs, AGAIN_MS)

    /** The same for a clock that has only been standing there — see [PATIENCE_MS]. */
    fun due(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean =
        worthAsking(context, nowMs, PATIENCE_MS)

    /**
     * Asks which hour is the newest, and fetches it and the hours before
     * it that the phone has not got, newest first.
     *
     * Blocking. Returns whether anything new was written. Each picture
     * is asked for by its hour and kept only if the service says that is
     * the hour it sent: the first hour it has not got ends the going
     * back, since it has nothing older either, and is remembered — see
     * [FLOOR_KEY].
     */
    fun refresh(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putLong(WHEN_KEY, nowMs).apply()
        val newest = fetch.get(LiveClouds.ask())?.let { LiveClouds.frameTime(it.warning, null) }
            ?: return false
        val floor = prefs.getLong(FLOOR_KEY, Long.MIN_VALUE)
        var got = 0
        var hour = newest
        while (got < AT_A_TIME && newest - hour < KEEP_MS && hour > floor) {
            if (!file(context, hour).exists()) {
                val answer = fetch.get(LiveClouds.url(hour)) ?: break
                if (LiveClouds.frameTime(answer.warning, hour) != hour) {
                    prefs.edit().putLong(FLOOR_KEY, hour).apply()
                    break
                }
                // Decoded before it is kept, as the daily pictures are:
                // a file that is not a picture is a globe with no cloud
                // for as long as it is the newest.
                if (DailyImageStore.blank(answer.bytes)) break
                // The visible picture of the same hour, and first: the
                // hour is on the phone when its infrared is, and the
                // globe asks once a second.
                fetch.get(LiveClouds.visible(hour))?.let { seen ->
                    if (LiveClouds.frameTime(seen.warning, hour) == hour && !DailyImageStore.blank(seen.bytes)) {
                        write(seenFile(context, hour), seen.bytes)
                    }
                }
                if (!write(file(context, hour), answer.bytes)) break
                got++
            }
            hour -= LiveClouds.HOUR_MS
        }
        for ((on, at) in pictures(context)) {
            if (newest - at >= KEEP_MS) on.delete()
        }
        return got > 0
    }

    /** Written beside itself and moved into place — see [DailyImageStore]. */
    private fun write(to: File, bytes: ByteArray): Boolean = try {
        val part = File(to.path + ".part")
        part.writeBytes(bytes)
        part.renameTo(to) || run { part.delete(); false }
    } catch (e: Exception) {
        false
    }

    @Volatile
    private var fetching = false

    /**
     * The same on a thread nothing is waiting for, one at a time;
     * [patient] for a clock asking by itself.
     */
    fun refreshInBackground(context: Context, patient: Boolean = false) {
        val now = TimeKeeper.nowMs()
        if (!(if (patient) due(context, now) else stale(context, now))) return
        synchronized(this) {
            if (fetching) return
            fetching = true
        }
        val app = context.applicationContext
        Thread {
            try {
                refresh(app, now)
            } finally {
                fetching = false
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The cloud for a globe showing [atMs]: the frame nearest that
     * instant, if the phone has one within [NEAR_MS] of it, and nothing
     * if it has not — the globe then wears the day's photograph.
     *
     * Made of the hour's two pictures and of where the sun stood at
     * that hour — see [LiveClouds.field]. One field held, and handed
     * back for as long as it is the same hour made of the same files
     * that is asked for: see [CloudStore.cached], which says why.
     */
    @Synchronized
    fun cached(context: Context, atMs: Long = TimeKeeper.nowMs()): SatelliteClouds.Field? {
        if (!wanted(context)) return null
        val of = onDisk(context).minByOrNull { Math.abs(it - atMs) } ?: return null
        if (Math.abs(of - atMs) > NEAR_MS) return null
        val on = file(context, of)
        val seen = seenFile(context, of)
        val key = "$of@${on.lastModified()}+${seen.lastModified()}"
        held?.let { if (heldFor == key) return it }
        val (w, h, infrared) = pixelsOf(on) ?: return null
        val visible = pixelsOf(seen)?.third
        val sun = Hemisphere.subsolar(of)
        return SatelliteClouds.Field(w, h, LiveClouds.field(infrared, visible, w, h, sun[0], sun[1])).also {
            held = it
            heldFor = key
        }
    }

    private var held: SatelliteClouds.Field? = null
    private var heldFor: String? = null

    /** A picture's size and pixels, or nothing for a file that is not there or is not one. */
    private fun pixelsOf(on: File): Triple<Int, Int, IntArray>? {
        val picture = BitmapFactory.decodeFile(on.path) ?: return null
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        picture.recycle()
        return Triple(w, h, pixels)
    }

    /**
     * Which frames the phone has, for the settings row: how many, and
     * the hours of the first and the last by the clock they are kept by.
     */
    fun describe(context: Context): String {
        val frames = onDisk(context)
        if (frames.isEmpty()) return context.getString(R.string.clouds_on_phone_none)
        return context.getString(R.string.live_on_phone, frames.size, hourText(frames.first()), hourText(frames.last()))
    }

    private fun hourText(atMs: Long): String = LiveClouds.stamp(atMs).substring(11, 16)

    /** Throws the frames away, for another kind of cloud being chosen. */
    @Synchronized
    fun forget(context: Context) {
        held = null
        for ((on, _) in pictures(context)) on.delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(WHEN_KEY).remove(FLOOR_KEY).apply()
    }

    private fun download(url: String): Answer? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = DailyImageStore.TIMEOUT_MS
                readTimeout = DailyImageStore.TIMEOUT_MS
                setRequestProperty("User-Agent", WeatherSources.AGENT)
            }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > DailyImageStore.TOO_BIG) null else Answer(bytes, connection.getHeaderField("Warning"))
        } catch (e: Exception) {
            // A service that is down is an hour with yesterday's cloud
            // on the globe, which is what the globe wore before this.
            null
        } finally {
            connection?.disconnect()
        }
    }
}
