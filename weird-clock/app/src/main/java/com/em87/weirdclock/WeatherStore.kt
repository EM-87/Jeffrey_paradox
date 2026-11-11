package com.em87.weirdclock

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place in this app that talks to the internet.
 *
 * It did not talk to anything at all until the weather arrived, and that
 * is worth keeping true in the narrowest possible sense: nothing here runs
 * unless somebody has switched the weather on, and when it does run it
 * asks three public forecast services for the sky over one rounded
 * location and reads nothing back but numbers.
 *
 * **Off by default, and it stays off until asked.** The permission itself
 * cannot be a question — Android grants the internet at install without a
 * prompt — so the question has to be a switch, and the switch has to say
 * what it is going to do. See [Prefs.WEATHER].
 *
 * The place is rounded to two decimals before it leaves the phone, which
 * is about a kilometre. A forecast is the same across a kilometre and a
 * request that carries a doorstep is a request that says more than it
 * needs to.
 */
object WeatherStore {

    /** How often the sky is worth asking about again. */
    const val EVERY_MS = 30L * 60L * 1000L

    /** How long any one service gets before it is counted as down. */
    const val TIMEOUT_MS = 8000

    /** Where the last agreed reading is kept between runs. */
    private const val CACHE = "pref_weather_cache"

    /** And the hours to come, as they were agreed at the same moment. */
    private const val FORECAST = "pref_weather_forecast"

    /**
     * Both of them, for the backup to leave behind: what the sky was
     * doing on one phone on one afternoon is not a setting.
     */
    internal val KEPT = setOf(CACHE, FORECAST)

    /**
     * How far ahead is kept: five days of hours.
     *
     * Enough for the day that is left and the four whole ones after it,
     * wherever in today "now" happens to fall.
     */
    const val FORECAST_HOURS = 120

    /**
     * One request, so the tests can be given the answers.
     *
     * The seam. Everything above this is arithmetic and everything below
     * it is a socket, and a network feature with no seam is a network
     * feature nobody ever tests — which is how a parser ships broken and
     * looks exactly like a service being down.
     */
    fun interface Fetch {
        fun get(url: String): String?
    }

    /** The real one, replaced in tests. */
    var fetch: Fetch = Fetch { url ->
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                // One of the three asks for this in writing and blocks
                // callers that do not identify themselves.
                setRequestProperty("User-Agent", WeatherSources.AGENT)
            }
            if (connection.responseCode != 200) return@Fetch null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Every network failure there is, and they are all the same
            // failure here: one service did not answer. The whole design
            // is that this costs confidence rather than the reading.
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Whether the owner of this phone has asked for any of this. */
    fun wanted(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Prefs.WEATHER, false)

    /**
     * The sky as far as anybody knows, without asking anybody.
     *
     * Read from what was written down last time. A clock that has to wait
     * for three servers before it can draw is a clock that is blank every
     * time it opens, so the cache is what the face uses and the fetching
     * only ever replaces it.
     */
    fun cached(context: Context): Weather.Sky {
        if (!wanted(context)) return Weather.Sky()
        val text = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(CACHE, null) ?: return Weather.Sky()
        return try {
            read(JSONObject(text))
        } catch (e: org.json.JSONException) {
            Weather.Sky()
        }
    }

    /**
     * Ask everybody, agree, and write it down. Blocking; call it off the
     * main thread.
     *
     * Every service is asked even when the first one answers. That is the
     * point: one answer is a lone reading and two are an agreement, and
     * the difference is drawn on the clock.
     */
    fun refresh(context: Context, latitude: Double, longitude: Double): Weather.Sky {
        if (!wanted(context)) return Weather.Sky()
        val at = TimeKeeper.nowMs()
        val lat = Math.round(latitude * 100.0) / 100.0
        val lon = Math.round(longitude * 100.0) / 100.0
        val readings = ArrayList<Weather.Reading>()
        val ahead = ArrayList<List<Weather.Hour>>()
        for (source in WeatherSources.all()) {
            // One request each, and both things read out of it: what the
            // sky is doing and what it is expected to do.
            val body = try {
                fetch.get(source.url(lat, lon))
            } catch (e: Exception) {
                null
            } ?: continue
            try {
                source.read(body, at)?.let { readings.add(it) }
            } catch (e: Exception) {
                // A service that changes the shape of its JSON overnight
                // is one missing service, not a crashed clock.
            }
            try {
                source.hours(body).takeIf { it.isNotEmpty() }?.let { ahead.add(it) }
            } catch (e: Exception) {
                // And the same for the half of its answer about tomorrow.
            }
        }
        val sky = Weather.agree(readings, at)
        val hours = Weather.forecast(ahead, at, FORECAST_HOURS)
        if (sky.known || hours.isNotEmpty()) {
            val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
            if (sky.known) edit.putString(CACHE, write(sky).toString())
            // Kept only when there is one: a morning with every service
            // down leaves yesterday's forecast for today where it was,
            // which is still a forecast for today.
            if (hours.isNotEmpty()) edit.putString(FORECAST, write(hours, at).toString())
            edit.apply()
        }
        return sky
    }

    /**
     * The hours to come as they were last agreed, without asking anybody.
     *
     * From the hour [nowMs] is in onwards: an hour that has gone is not
     * a forecast. Empty with the switch off, like everything else here.
     */
    fun forecast(context: Context, nowMs: Long = TimeKeeper.nowMs()): List<Weather.AgreedHour> {
        if (!wanted(context)) return emptyList()
        val text = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(FORECAST, null) ?: return emptyList()
        val from = Math.floorDiv(nowMs, Weather.HOUR_MS) * Weather.HOUR_MS
        return try {
            readHours(JSONObject(text)).filter { it.atMs >= from }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    /** When those hours were agreed, or nought if they never were. */
    fun forecastAt(context: Context): Long {
        if (!wanted(context)) return 0L
        val text = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(FORECAST, null) ?: return 0L
        return try {
            JSONObject(text).optLong("at")
        } catch (e: org.json.JSONException) {
            0L
        }
    }

    /** Whether what is written down is old enough to be worth replacing. */
    fun stale(context: Context, nowMs: Long = TimeKeeper.nowMs()): Boolean {
        if (!wanted(context)) return false
        val sky = cached(context)
        return !sky.known || nowMs - sky.atMs >= EVERY_MS
    }

    /** And a fetch on a thread of its own, for the places that only draw. */
    fun refreshInBackground(context: Context, latitude: Double, longitude: Double) {
        if (!wanted(context) || !stale(context)) return
        Thread { refresh(context, latitude, longitude) }.apply {
            isDaemon = true
            start()
        }
    }

    /** Forgets everything, for the switch being turned off. */
    fun forget(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(CACHE).remove(FORECAST).apply()
    }

    // ------------------------------------------------------- writing down

    private fun write(sky: Weather.Sky): JSONObject = JSONObject().apply {
        put("at", sky.atMs)
        put("answered", sky.answered)
        agreed("t", sky.temperatureC)
        agreed("p", sky.pressureHpa)
        agreed("c", sky.cloudPercent)
        agreed("r", sky.rainMmPerHour)
        agreed("w", sky.windKph)
        sky.thunder?.let { put("k", it) }
    }

    private fun JSONObject.agreed(key: String, value: Weather.Agreed) {
        val number = value.value ?: return
        put(key, number)
        put("${key}n", value.trust.name)
    }

    private fun read(from: JSONObject): Weather.Sky = Weather.Sky(
        temperatureC = from.agreed("t"),
        pressureHpa = from.agreed("p"),
        cloudPercent = from.agreed("c"),
        rainMmPerHour = from.agreed("r"),
        windKph = from.agreed("w"),
        thunder = if (from.has("k")) from.optBoolean("k") else null,
        answered = from.optInt("answered"),
        atMs = from.optLong("at")
    )

    private fun JSONObject.agreed(key: String): Weather.Agreed {
        if (!has(key)) return Weather.Agreed.NOTHING
        val trust = runCatching {
            Weather.Trust.valueOf(optString("${key}n", Weather.Trust.LONE.name))
        }.getOrDefault(Weather.Trust.LONE)
        return Weather.Agreed(optDouble(key), trust)
    }

    /**
     * A hundred and twenty hours, each a short row of numbers.
     *
     * In the order: when, degrees, cloud, rain, lightning — and after
     * each of the three numbers whether a second service agreed with it.
     * Rows rather than objects because there are a hundred and twenty of
     * them and the names would be nine tenths of what is stored.
     */
    private fun write(hours: List<Weather.AgreedHour>, atMs: Long): JSONObject = JSONObject().apply {
        put("at", atMs)
        put(
            "h",
            org.json.JSONArray().also { rows ->
                for (hour in hours) {
                    rows.put(
                        org.json.JSONArray().apply {
                            put(hour.atMs)
                            agreed(hour.temperatureC)
                            agreed(hour.cloudPercent)
                            agreed(hour.rainMm)
                            put(hour.thunder ?: JSONObject.NULL)
                        }
                    )
                }
            }
        )
    }

    private fun org.json.JSONArray.agreed(value: Weather.Agreed) {
        put(value.value ?: JSONObject.NULL)
        put(value.trust == Weather.Trust.AGREED)
    }

    private fun readHours(from: JSONObject): List<Weather.AgreedHour> {
        val rows = from.optJSONArray("h") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONArray(i) ?: return@mapNotNull null
            if (row.length() < 8) return@mapNotNull null
            Weather.AgreedHour(
                atMs = row.optLong(0),
                temperatureC = row.agreedAt(1),
                cloudPercent = row.agreedAt(3),
                rainMm = row.agreedAt(5),
                thunder = if (row.isNull(7)) null else row.optBoolean(7)
            )
        }
    }

    private fun org.json.JSONArray.agreedAt(index: Int): Weather.Agreed {
        if (isNull(index)) return Weather.Agreed.NOTHING
        return Weather.Agreed(
            optDouble(index),
            if (optBoolean(index + 1)) Weather.Trust.AGREED else Weather.Trust.LONE
        )
    }
}
