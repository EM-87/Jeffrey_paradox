package com.em87.weirdclock

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the weather is asked, and how each answer is read.
 *
 * Three services, chosen on one rule: none of them wants a key. A clock
 * that needs an account somewhere is a clock that stops working when the
 * account does, and a key checked into a repository is not a secret — so
 * the list is the keyless ones, which is also the list that can be handed
 * to somebody else's phone and simply work.
 *
 * Each is a URL and a way of reading what comes back, and nothing else.
 * No fetching happens here: these are strings in and one [Weather.Reading]
 * out, so every one of them can be held against a real response and
 * checked without a network, which is the only way a parser ever gets
 * tested at all.
 *
 * They disagree about everything except the sky. One reports wind in
 * metres a second and another in kilometres an hour; one calls a
 * thunderstorm a number and another calls it a word; one counts its rain
 * by the quarter of an hour and backwards. Sorting that out is the whole
 * job of this file, and it is why [Weather.agree] can be arithmetic on
 * plain numbers.
 *
 * Two of them also say what is coming, in the same answer: an hour at a
 * time for days ahead. That is read here too — see [Source.hours] — and
 * costs no request that was not being made already.
 */
object WeatherSources {

    /**
     * Who the servers are talking to.
     *
     * One of these three asks for it in writing and will block a caller
     * that does not identify itself. It is a courtesy to the other two.
     */
    const val AGENT = "WeirdClock/1.0 (github.com/EM-87/Jeffrey_paradox)"

    /**
     * One service: where to ask it, how to read the answer — and, for
     * the ones that send it, how to read the hours to come out of the
     * same answer.
     */
    class Source(
        val name: String,
        val url: (Double, Double) -> String,
        val read: (String, Long) -> Weather.Reading?,
        val hours: (String) -> List<Weather.Hour> = { emptyList() }
    )

    /** Every service this clock knows how to ask. */
    fun all(): List<Source> = listOf(OPEN_METEO, MET_NORWAY, WTTR)

    /**
     * A number out of JSON that might be a number, a string, or missing.
     *
     * One of these three sends every value as a string — `"21"` for
     * twenty-one degrees — so reading them as numbers throws away the
     * whole service. Missing and unparseable both come back as nothing,
     * which is what a field a service does not carry looks like.
     */
    private fun number(from: JSONObject?, key: String): Double? {
        val value = from?.opt(key) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    /** The same, out of a list, where a missing entry is a JSON null. */
    private fun number(from: JSONArray?, index: Int): Double? {
        if (from == null || index !in 0 until from.length()) return null
        return when (val value = from.opt(index)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    /** Their code for a thunderstorm; 96 and 99 are the same with hail. */
    private const val WMO_THUNDER = 95.0

    /**
     * Open-Meteo, which answers everything in one object and very nearly
     * in the units everything else here uses.
     *
     * Two things about it were wrong here for as long as it has been
     * asked, and both were found by holding one real answer against the
     * other two services' for the same minute.
     *
     * The pressure asked for was the pressure at the ground, which is the
     * pressure at sea level less a hectopascal for every eight metres you
     * live above it: 930 in Madrid on a morning the other two said 1008.
     * The other two give it at sea level, a barometer's legend is written
     * for sea level, and so that is the one asked for now.
     *
     * And the rain "now" is what fell in the last [interval][INTERVAL]
     * seconds — a quarter of an hour — not in an hour: 0.3 while the
     * other two said 1.4 and 1.5. It is scaled to an hour by the interval
     * the answer itself carries, and an answer that does not say how long
     * its rain took to fall has not said how hard it is raining.
     */
    val OPEN_METEO = Source(
        name = "open-meteo",
        url = { lat, lon ->
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,pressure_msl,cloud_cover," +
                "precipitation,wind_speed_10m,weather_code" +
                "&hourly=temperature_2m,cloud_cover,precipitation,weather_code" +
                "&timeformat=unixtime&forecast_days=$OPEN_METEO_DAYS"
        },
        read = { body, atMs ->
            val current = JSONObject(body).optJSONObject("current")
            if (current == null) {
                null
            } else {
                // Everything below a thunderstorm's code is weather
                // without lightning in it, which is a fact worth
                // reporting as false rather than as "do not know".
                val code = number(current, "weather_code")
                val fell = number(current, "precipitation")
                val over = number(current, INTERVAL)?.takeIf { it > 0.0 }
                Weather.Reading(
                    source = "open-meteo",
                    atMs = atMs,
                    temperatureC = number(current, "temperature_2m"),
                    pressureHpa = number(current, "pressure_msl"),
                    cloudPercent = number(current, "cloud_cover"),
                    rainMmPerHour = if (fell == null || over == null) null else fell * 3600.0 / over,
                    windKph = number(current, "wind_speed_10m"),
                    thunder = code?.let { it >= WMO_THUNDER }
                )
            }
        },
        hours = { body ->
            val hourly = JSONObject(body).optJSONObject("hourly")
            val times = hourly?.optJSONArray("time")
            if (hourly == null || times == null) {
                emptyList()
            } else {
                val degrees = hourly.optJSONArray("temperature_2m")
                val cloud = hourly.optJSONArray("cloud_cover")
                val rain = hourly.optJSONArray("precipitation")
                val code = hourly.optJSONArray("weather_code")
                (0 until times.length()).mapNotNull { i ->
                    // Seconds since 1970, because the address asks for
                    // that: a date written out is a date in a time zone,
                    // and theirs would be whichever one they guessed.
                    val seconds = number(times, i) ?: return@mapNotNull null
                    Weather.Hour(
                        atMs = seconds.toLong() * 1000L,
                        temperatureC = number(degrees, i),
                        cloudPercent = number(cloud, i),
                        // Each of their hours carries what fell in the
                        // hour *before* it. The rain of the hour that
                        // starts here is therefore the next one's.
                        rainMm = number(rain, i + 1),
                        thunder = number(code, i)?.let { it >= WMO_THUNDER }
                    )
                }
            }
        }
    )

    /** The field that says how many seconds their "now" was added up over. */
    private const val INTERVAL = "interval"

    /**
     * How many days of hours are asked for.
     *
     * Their days start at midnight in Greenwich and today is the first,
     * so six of them is never less than five days from now — which is
     * what [WeatherStore.FORECAST_HOURS] keeps.
     */
    const val OPEN_METEO_DAYS = 6

    /**
     * MET Norway, whose answer is a forecast and whose first entry is now.
     *
     * Wind comes in metres a second here and nowhere else, and the rain is
     * in a separate block that only exists when there is a next hour to
     * talk about — which there is not, at the very end of their range.
     */
    val MET_NORWAY = Source(
        name = "met.no",
        url = { lat, lon ->
            "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
                "?lat=$lat&lon=$lon"
        },
        read = { body, atMs ->
            val series = JSONObject(body)
                .optJSONObject("properties")
                ?.optJSONArray("timeseries")
            val first = series?.optJSONObject(0)?.optJSONObject("data")
            val instant = first?.optJSONObject("instant")?.optJSONObject("details")
            if (instant == null) {
                null
            } else {
                val hour = first.optJSONObject("next_1_hours")
                val symbol = hour?.optJSONObject("summary")?.optString("symbol_code")
                Weather.Reading(
                    source = "met.no",
                    atMs = atMs,
                    temperatureC = number(instant, "air_temperature"),
                    pressureHpa = number(instant, "air_pressure_at_sea_level"),
                    cloudPercent = number(instant, "cloud_area_fraction"),
                    rainMmPerHour = number(
                        hour?.optJSONObject("details"), "precipitation_amount"
                    ),
                    // Metres a second, alone among the three.
                    windKph = number(instant, "wind_speed")?.let { it * 3.6 },
                    thunder = symbol?.takeIf { it.isNotEmpty() }?.contains("thunder")
                )
            }
        },
        hours = { body ->
            val series = JSONObject(body)
                .optJSONObject("properties")
                ?.optJSONArray("timeseries")
            val out = ArrayList<Weather.Hour>()
            for (i in 0 until (series?.length() ?: 0)) {
                val entry = series?.optJSONObject(i) ?: continue
                val at = instantMs(entry.optString("time")) ?: continue
                val data = entry.optJSONObject("data")
                val instant = data?.optJSONObject("instant")?.optJSONObject("details")
                val one = data?.optJSONObject("next_1_hours")
                val six = data?.optJSONObject("next_6_hours")
                if (one != null) {
                    out.add(
                        Weather.Hour(
                            atMs = at,
                            temperatureC = number(instant, "air_temperature"),
                            cloudPercent = number(instant, "cloud_area_fraction"),
                            rainMm = number(one.optJSONObject("details"), "precipitation_amount"),
                            thunder = thunderIn(one)
                        )
                    )
                    continue
                }
                // Past two days and a half they speak every six hours,
                // and the rain is one figure for all six. Spread evenly
                // over them it is the same rain, and the day it falls in
                // adds up to what they said; the degrees and the cloud
                // are of the moment they were given for and no other.
                val fell = number(six?.optJSONObject("details"), "precipitation_amount")
                out.add(
                    Weather.Hour(
                        atMs = at,
                        temperatureC = number(instant, "air_temperature"),
                        cloudPercent = number(instant, "cloud_area_fraction"),
                        rainMm = fell?.let { it / SIX },
                        thunder = six?.let { thunderIn(it) }
                    )
                )
                if (fell != null) {
                    for (later in 1 until SIX) {
                        out.add(
                            Weather.Hour(
                                atMs = at + later * Weather.HOUR_MS,
                                rainMm = fell / SIX,
                                thunder = six?.let { thunderIn(it) }
                            )
                        )
                    }
                }
            }
            out
        }
    )

    /** How many hours their longer figure covers. */
    private const val SIX = 6

    /** Whether their word for a stretch of weather has lightning in it. */
    private fun thunderIn(stretch: JSONObject): Boolean? =
        stretch.optJSONObject("summary")?.optString("symbol_code")
            ?.takeIf { it.isNotEmpty() }?.contains("thunder")

    private val STAMP = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z""")

    /**
     * Their time — `2026-11-11T10:00:00Z` — as milliseconds, or nothing
     * if it is written any other way.
     */
    internal fun instantMs(text: String): Long? {
        val parts = STAMP.matchEntire(text)?.groupValues ?: return null
        val calendar = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(
            parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt(),
            parts[4].toInt(), parts[5].toInt(), parts[6].toInt()
        )
        return calendar.timeInMillis
    }

    /**
     * wttr.in, which sends every number as a string and its cloud cover as
     * a percentage that is already a percentage.
     */
    val WTTR = Source(
        name = "wttr.in",
        url = { lat, lon -> "https://wttr.in/$lat,$lon?format=j1" },
        read = { body, atMs ->
            val current = JSONObject(body)
                .optJSONArray("current_condition")
                ?.optJSONObject(0)
            if (current == null) {
                null
            } else {
                // Their codes are the old WWO set, where the two hundreds
                // and the three-eighties are the ones with lightning.
                val code = number(current, "weatherCode")
                Weather.Reading(
                    source = "wttr.in",
                    atMs = atMs,
                    temperatureC = number(current, "temp_C"),
                    pressureHpa = number(current, "pressure"),
                    cloudPercent = number(current, "cloudcover"),
                    rainMmPerHour = number(current, "precipMM"),
                    windKph = number(current, "windspeedKmph"),
                    thunder = code?.let { it.toInt() in THUNDER_CODES }
                )
            }
        }
    )

    /** The codes wttr.in uses for weather with lightning in it. */
    private val THUNDER_CODES = setOf(200, 386, 389, 392, 395)
}
