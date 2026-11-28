package com.em87.weirdclock

import android.content.Context

/**
 * A week of [SatellitePhoto]'s daily photographs, kept the way the
 * veil's are — see [DailyImageStore] and [CloudStore] — and handed to
 * the globe ready to be laid over its map.
 *
 * Only with the weather switch on, the clouds on, and this kind chosen:
 * it is a hundred and fifty kilobytes a day, twice the veil's, and the
 * veil's photograph is not fetched while this one is.
 */
object PhotoStore {

    private val store = DailyImageStore(
        prefix = "photo-",
        suffix = ".jpg",
        whenKey = "pref_photo_at",
        sources = { day -> SatellitePhoto.urls(day) },
        dayOf = { atMs -> SatelliteClouds.dayFor(atMs) }
    )

    /** The real one, replaced in tests. */
    var fetch: DailyImageStore.Fetch
        get() = store.fetch
        set(value) {
            store.fetch = value
        }

    /** Whether this kind of cloud is the one asked for. */
    fun wanted(context: Context): Boolean = CloudStore.kind(context) == SatelliteClouds.Kind.PHOTO

    /**
     * Which day's photograph a globe wound to [atMs] wears, and how many
     * days off the one it should that is — see [DailyImageStore.worn].
     */
    fun worn(context: Context, atMs: Long = TimeKeeper.nowMs()): DailyImageStore.Worn? {
        if (!wanted(context)) return null
        return store.worn(context, atMs)
    }

    /** Which days the phone has, for the settings row. */
    fun describe(context: Context): String {
        val days = store.onDisk(context)
        if (days.isEmpty()) return context.getString(R.string.clouds_on_phone_none)
        return context.getString(R.string.clouds_on_phone, days.first(), days.last(), days.size)
    }

    /**
     * The photograph for a globe showing [atMs]: the nearest day at or
     * before the one asked for, as the veil's is. One held, and handed
     * back for as long as it is the same day of the same file — the
     * globe keeps what it makes of a photograph against which
     * photograph it was.
     */
    @Synchronized
    fun cached(context: Context, atMs: Long = TimeKeeper.nowMs()): SatellitePhoto.Picture? {
        if (!wanted(context)) return null
        val day = store.dayCached(context, atMs) ?: return null
        val key = day + "@" + store.stamp(context, day)
        held?.let { if (heldFor == key) return it }
        val sent = store.decode(context, day) ?: return null
        val w = sent.width
        val h = sent.height
        val pixels = IntArray(w * h)
        sent.getPixels(pixels, 0, w, 0, 0, w, h)
        sent.recycle()
        return SatellitePhoto.picture(pixels, w, h).also {
            held = it
            heldFor = key
        }
    }

    private var held: SatellitePhoto.Picture? = null
    private var heldFor: String? = null

    /** Whether what is on disk is old enough to be worth adding to. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean =
        wanted(context) && store.stale(context, nowMs)

    /** Fetch what is missing, newest first; blocking. Returns whether anything new was written. */
    fun refresh(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean =
        wanted(context) && store.refresh(context, nowMs)

    /** The same on a thread nothing is waiting for; [patient] for a clock asking by itself. */
    fun refreshInBackground(context: Context, patient: Boolean = false) {
        if (!wanted(context)) return
        store.refreshInBackground(context, TimeKeeper.nowMs(), patient)
    }

    /** Throws the photographs away, for another kind of cloud being chosen. */
    @Synchronized
    fun forget(context: Context) {
        store.forget(context)
        held = null
    }
}
