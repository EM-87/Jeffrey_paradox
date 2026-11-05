package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import androidx.preference.PreferenceManager

/**
 * Last night's lights, fetched once a day and kept on disk — two layers
 * of them, each its own [DailyImageStore].
 *
 * What the globe is handed is not the pictures but their brightness: one
 * byte a pixel, worked out once per day's picture and kept, because the
 * bake reads the lights a quarter of a million times a frame and a byte
 * read is the cheapest thing it can do. The decoded picture is let go of
 * as soon as its greys are copied out — at 1440 by 720 it is four
 * megabytes, and there are two of them.
 */
object NightLightStore {

    private val clean = DailyImageStore(
        prefix = "lights-clean-",
        suffix = ".png",
        whenKey = "pref_lights_clean_at",
        sources = { day -> NightLights.cleanUrls(day) },
        dayOf = { atMs -> NightLights.dayFor(atMs) }
    )

    private val raw = DailyImageStore(
        prefix = "lights-raw-",
        suffix = ".png",
        whenKey = "pref_lights_raw_at",
        sources = { day -> NightLights.rawUrls(day) },
        dayOf = { atMs -> NightLights.dayFor(atMs) },
        stillFilling = { day, nowMs -> NightLights.stillFilling(day, nowMs) }
    )

    /** The real one, replaced in tests, for both layers at once. */
    var fetch: DailyImageStore.Fetch
        get() = clean.fetch
        set(value) {
            clean.fetch = value
            raw.fetch = value
        }

    /** Which layers are on, which is nothing without the weather switch. */
    fun mode(context: Context): NightLights.Mode {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(Prefs.WEATHER, false)) return NightLights.Mode.NONE
        return NightLights.Mode.of(prefs.getString(Prefs.HEMISPHERE_LIGHTS, null))
    }

    /** Whether anybody has asked for this at all. */
    fun wanted(context: Context): Boolean = mode(context) != NightLights.Mode.NONE

    /**
     * One layer's brightness, the day it is of, and how many days that
     * is behind the instant it is being shown at.
     *
     * [stamp] is when the file it was read from was written, so a day's
     * picture that has been fetched again — the raw night fills in
     * through the day — is read again, and one that has not is not.
     */
    class Layer(
        val width: Int,
        val height: Int,
        val lum: ByteArray,
        val day: String,
        val lagDays: Int,
        val stamp: Long = 0L
    ) {
        /** The brightness at a point of the map, out of 255. */
        fun at(u: Double, v: Double): Int {
            val x = (u * width).toInt().coerceIn(0, width - 1)
            val y = (v * height).toInt().coerceIn(0, height - 1)
            return lum[y * width + x].toInt() and 0xFF
        }
    }

    /** Both layers, or whichever of them is on and there. */
    class Sky(val mode: NightLights.Mode, val clean: Layer?, val raw: Layer?)

    private var heldClean: Layer? = null
    private var heldRaw: Layer? = null

    /**
     * The greys of a picture, copied out once per picture.
     *
     * Once: the decoded picture is four megabytes and is let go of the
     * moment its greys are out, and the greys are kept against the day
     * and the file's own timestamp rather than against the picture, so
     * the question "is this still the same night" costs a stat and not a
     * decode.
     */
    private fun layerOf(store: DailyImageStore, context: Context, atMs: Long, held: Layer?): Layer? {
        val day = store.dayCached(context, atMs) ?: return null
        val lag = NightLights.lagDays(day, atMs) ?: 0
        val stamp = store.stamp(context, day)
        if (held != null && held.day == day && held.stamp == stamp) {
            return if (held.lagDays == lag) held
            else Layer(held.width, held.height, held.lum, day, lag, stamp)
        }
        val picture: Bitmap = store.cached(context, atMs) ?: return null
        val w = picture.width
        val h = picture.height
        val pixels = IntArray(w * h)
        picture.getPixels(pixels, 0, w, 0, 0, w, h)
        store.release()
        val lum = ByteArray(w * h)
        for (i in pixels.indices) lum[i] = NightLights.luminance(pixels[i]).toByte()
        return Layer(w, h, lum, day, lag, stamp)
    }

    /**
     * The lights for a globe showing [atMs], which is not always now.
     *
     * Each layer is the nearest day at or before the one asked for — see
     * [DailyImageStore.dayCached] — and says how far behind that is, so
     * the globe can decide whether that is worth a word.
     */
    fun skyFor(context: Context, atMs: Long = TimeKeeper.nowMs()): Sky? {
        val mode = mode(context)
        if (mode == NightLights.Mode.NONE) return null
        var cleanLayer: Layer? = null
        var rawLayer: Layer? = null
        if (mode.clean) {
            cleanLayer = layerOf(clean, context, atMs, heldClean)
            heldClean = cleanLayer
        }
        if (mode.raw) {
            rawLayer = layerOf(raw, context, atMs, heldRaw)
            heldRaw = rawLayer
        }
        if (cleanLayer == null && rawLayer == null) return null
        return Sky(mode, cleanLayer, rawLayer)
    }

    /** Which day each layer on disk would show now, for the settings row. */
    fun daysNow(context: Context, nowMs: Long = TimeKeeper.nowMs()): Pair<String?, String?> =
        clean.dayCached(context, nowMs) to raw.dayCached(context, nowMs)

    /**
     * What the settings row says under its title: what the layers are,
     * and which night each one on the phone is of — always, whatever the
     * globe chooses to say.
     */
    fun describe(context: Context, nowMs: Long = TimeKeeper.nowMs()): String {
        val lines = ArrayList<String>(3)
        lines += context.getString(R.string.pref_hemisphere_lights_summary)
        val (cleanDay, rawDay) = daysNow(context, nowMs)
        fun line(name: Int, day: String?): String {
            val label = context.getString(name)
            if (day == null) return context.getString(R.string.lights_layer_none, label)
            val lag = NightLights.lagDays(day, nowMs) ?: 0
            return context.getString(R.string.lights_layer_summary, label, day, lag)
        }
        val mode = mode(context)
        if (mode.clean) lines += line(R.string.lights_clean_name, cleanDay)
        if (mode.raw) lines += line(R.string.lights_raw_name, rawDay)
        return lines.joinToString("\n")
    }

    /** Whether what is on disk is old enough to be worth adding to. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        val mode = mode(context)
        return (mode.clean && clean.stale(context, nowMs)) ||
            (mode.raw && raw.stale(context, nowMs))
    }

    /** Fetch what is missing for the layers that are on. Blocking. */
    fun refresh(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        val mode = mode(context)
        var got = false
        if (mode.clean) got = clean.refresh(context, nowMs) || got
        if (mode.raw) got = raw.refresh(context, nowMs) || got
        return got
    }

    /** And the same on threads nothing is waiting for, one per layer. */
    fun refreshInBackground(context: Context) {
        val mode = mode(context)
        val now = TimeKeeper.nowMs()
        if (mode.clean) clean.refreshInBackground(context, now)
        if (mode.raw) raw.refreshInBackground(context, now)
    }

    /** Throws the pictures away, for the switch being turned off. */
    fun forget(context: Context) {
        clean.forget(context)
        raw.forget(context)
        heldClean = null
        heldRaw = null
    }
}
