package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import java.io.File

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
 * turning of a photograph into a veil is [SatelliteClouds.field], which
 * is done here once for a day's picture, with the day before's to fill
 * the places the satellite did not pass over, and can be changed without
 * going back to the network.
 *
 * The keeping itself is [DailyImageStore] now, which the night lights
 * share; what is left here is which switch it hangs off, which picture it
 * asks for, and a file the first version left behind.
 */
object CloudStore {

    /** How long one picture is worth keeping. */
    const val EVERY_MS = DailyImageStore.EVERY_MS

    /** How long a fetch gets before it is abandoned. */
    const val TIMEOUT_MS = DailyImageStore.TIMEOUT_MS

    /** The largest a picture of the earth may be before it is refused. */
    const val TOO_BIG = DailyImageStore.TOO_BIG

    /** And the one file the single-file version used, for tidying away. */
    private const val WAS = "clouds.jpg"

    /** How many days are worth keeping, which is how far back the globe goes. */
    const val KEEP_DAYS = DailyImageStore.KEEP_DAYS

    /** And how many of them one fetch will go and get. */
    const val AT_A_TIME = DailyImageStore.AT_A_TIME

    /**
     * One fetch of bytes, so the tests can watch without a network.
     *
     * The same thing as [DailyImageStore.Fetch], kept under this name
     * because the tests hold the socket by it.
     */
    fun interface Fetch {
        fun get(url: String): ByteArray?
    }

    private val store = DailyImageStore(
        prefix = "clouds-",
        suffix = ".jpg",
        whenKey = "pref_clouds_at",
        sources = { day -> listOf(SatelliteClouds.url(day)) },
        dayOf = { atMs -> SatelliteClouds.dayFor(atMs) }
    )

    /** The real one, replaced in tests. */
    var fetch: Fetch
        get() = Fetch { url -> store.fetch.get(url) }
        set(value) {
            store.fetch = DailyImageStore.Fetch { url -> value.get(url) }
        }

    /**
     * Which clouds the globe is to wear, or null for none at all: the
     * weather switch off, which is the one that lets anything here open
     * a socket, or the clouds' own.
     */
    fun kind(context: Context): SatelliteClouds.Kind? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(Prefs.WEATHER, false) || !prefs.getBoolean(Prefs.HEMISPHERE_CLOUDS, true)) return null
        return SatelliteClouds.Kind.of(prefs.getString(Prefs.HEMISPHERE_CLOUDS_KIND, null))
    }

    /**
     * Whether the daily photograph is wanted: with any clouds at all.
     * The hourly kind wants it too — it is what the globe wears wound to
     * a day the hours do not reach.
     */
    fun wanted(context: Context): Boolean = kind(context) != null

    /** Which day's photograph belongs on a globe wound to that instant. */
    fun dayOf(atMs: Long): String = store.dayOf(atMs)

    /**
     * Which day's photograph a globe wound to [atMs] actually wears, and
     * how many days before the one it should that is — see
     * [DailyImageStore.worn]. For the word on the globe's face.
     */
    fun worn(context: Context, atMs: Long = TimeKeeper.nowMs()): DailyImageStore.Worn? {
        if (!wanted(context)) return null
        return store.worn(context, atMs)
    }

    /**
     * Which days the phone has, for the settings row: the week is the
     * promise and this is what has been kept of it, which is the first
     * thing to look at when the clouds are seen not to change.
     */
    fun describe(context: Context): String {
        val days = store.onDisk(context)
        if (days.isEmpty()) return context.getString(R.string.clouds_on_phone_none)
        return context.getString(R.string.clouds_on_phone, days.first(), days.last(), days.size)
    }

    /**
     * The clouds for a globe showing [atMs], which is not always now.
     *
     * The nearest day at or before the one asked for, because that is what
     * the sky over a place actually was — see
     * [DailyImageStore.dayCached] — with the gaps between the
     * satellite's passes filled from the day before it, when the phone
     * has that day too: see [SatelliteClouds.fill].
     *
     * One field is held, and handed back for as long as it is asked for
     * the same day made of the same two files. The globe keeps its
     * projected disc against the *identity* of what it was projected
     * from, and a new field for every asking would be a new projection
     * for every frame.
     */
    fun cached(context: Context, atMs: Long = TimeKeeper.nowMs()): SatelliteClouds.Field? {
        if (!wanted(context)) return null
        val day = store.dayCached(context, atMs) ?: return null
        val before = DailyImageStore.before(day)
        // A file that is not there has no date on it, which is a date
        // like any other: the day it arrives, the key is another key.
        val key = day + "@" + store.stamp(context, day) + "+" + (before?.let { store.stamp(context, it) } ?: 0L)
        held?.let { if (heldFor == key) return it }
        val (w, h, pixels) = pixelsOf(store.decode(context, day)) ?: return null
        before?.let { pixelsOf(store.decode(context, it)) }?.let { SatelliteClouds.fill(pixels, it.third) }
        return SatelliteClouds.Field(w, h, SatelliteClouds.field(pixels, w, h)).also {
            held = it
            heldFor = key
        }
    }

    /** The field last made, and the day and the files it was made of. */
    private var held: SatelliteClouds.Field? = null
    private var heldFor: String? = null

    /** A picture's pixels and its size, the picture itself let go of. */
    private fun pixelsOf(picture: Bitmap?): Triple<Int, Int, IntArray>? {
        picture ?: return null
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        picture.recycle()
        return Triple(w, h, pixels)
    }

    /** Whether what is on disk is old enough to be worth adding to. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        return store.stale(context, nowMs)
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
        return store.refresh(context, nowMs)
    }

    /**
     * And the same on a thread nothing is waiting for; [patient] for a
     * clock asking by itself — see [DailyImageStore.due].
     */
    fun refreshInBackground(context: Context, patient: Boolean = false) {
        if (!wanted(context)) return
        File(context.cacheDir, WAS).delete()
        store.refreshInBackground(context, TimeKeeper.nowMs(), patient)
    }

    /** Throws the picture away, for the switch being turned off. */
    fun forget(context: Context) {
        File(context.cacheDir, WAS).delete()
        store.forget(context)
        held = null
    }
}
