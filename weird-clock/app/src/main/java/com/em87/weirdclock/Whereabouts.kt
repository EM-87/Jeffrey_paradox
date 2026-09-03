package com.em87.weirdclock

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Where the phone is, asked once and written down.
 *
 * The whole app runs on one coarse fix. The sunrise equation needs a
 * latitude and a longitude and nothing else; a fix a hundred kilometres
 * out moves sunset by a few minutes, and one measurement serves the year.
 * So this asks as little and as rarely as it can, and what it gets goes
 * into two floats in the settings — see [Prefs.LAST_LATITUDE].
 *
 * Here rather than in the activity because two things need it now. The
 * app asks when it opens, and the weather widget has a button on it: a
 * home screen showing a temperature and no way to say "I have moved" is a
 * widget that is wrong in a new city until somebody thinks to open the
 * app, which is exactly the thing a widget exists to save you.
 */
object Whereabouts {

    /** Whether this phone has said we may ask at all. */
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * The best fix anything on the phone already has, written down.
     *
     * Free: every provider is asked for its last known position and the
     * newest one wins, which costs no radio and no battery because
     * somebody else's app has already paid for it. Returns whether there
     * was one.
     */
    fun lastKnown(context: Context): Boolean {
        if (!allowed(context)) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        var best: android.location.Location? = null
        for (provider in lm.allProviders) {
            try {
                val location = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || location.time > best!!.time) best = location
            } catch (e: SecurityException) {
                // A provider that needs the finer permission; skip it.
            }
        }
        val found = best ?: return false
        remember(context, found.latitude, found.longitude)
        return true
    }

    /**
     * And one real measurement, for a phone nothing has asked before.
     *
     * A single update, taken and then unsubscribed from: a clock has no
     * business holding a location subscription open. [then] runs on
     * whichever looper is given, with whether anything arrived.
     */
    fun oneFix(
        context: Context,
        looper: android.os.Looper,
        waitMs: Long = WAIT_MS,
        then: (Boolean) -> Unit
    ) {
        if (!allowed(context)) {
            then(false)
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val provider = when {
            lm == null -> null
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> null
        }
        if (lm == null || provider == null) {
            then(false)
            return
        }
        val handler = android.os.Handler(looper)
        var answered = false
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (answered) return
                answered = true
                stop(lm, this)
                remember(context, location.latitude, location.longitude)
                then(true)
            }

            // Required below API 30, where the default methods of
            // LocationListener do not exist yet.
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, looper)
        } catch (e: SecurityException) {
            then(false)
            return
        }
        // And a deadline, because a radio indoors can be asked politely
        // for ever. Whatever is written down already is the answer then,
        // which for this app is usually a perfectly good one.
        handler.postDelayed({
            if (answered) return@postDelayed
            answered = true
            stop(lm, listener)
            then(false)
        }, waitMs)
    }

    /**
     * How long one fix gets before we go with what we have.
     *
     * The widget's own button asks for less, because a broadcast receiver
     * is only allowed to keep the process alive for about ten seconds and
     * a button that gives up after the system has already stopped
     * listening is a button that does nothing.
     */
    const val WAIT_MS = 20_000L
    const val WAIT_FROM_A_WIDGET_MS = 7_000L

    private fun stop(lm: LocationManager, listener: android.location.LocationListener) {
        try {
            lm.removeUpdates(listener)
        } catch (e: SecurityException) {
            // Permission withdrawn mid-flight; nothing left to stop.
        }
    }

    /** Both halves of a fix, written down and handed to everything that reads it. */
    fun remember(context: Context, latitude: Double, longitude: Double) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putFloat(Prefs.LAST_LATITUDE, latitude.toFloat())
            .putFloat(Prefs.LAST_LONGITUDE, longitude.toFloat())
            .apply()
        DayNight.configure(context)
    }
}
