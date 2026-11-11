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
 * turning of a photograph into a veil is [SatelliteClouds.veil], which is
 * cheap, happens while the disc is being projected anyway, and can be
 * changed without going back to the network.
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

    /** Whether anybody has asked for this at all. */
    fun wanted(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(Prefs.WEATHER, false) &&
            prefs.getBoolean(Prefs.HEMISPHERE_CLOUDS, true)
    }

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
     * the sky over a place actually was — see [DailyImageStore.cached].
     */
    fun cached(context: Context, atMs: Long = TimeKeeper.nowMs()): Bitmap? {
        if (!wanted(context)) return null
        return store.cached(context, atMs)
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
    }
}
