package com.em87.weirdclock

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
 * thunderstorm a number and another calls it a word; two of them have
 * pressure and one has it at sea level. Sorting that out is the whole job
 * of this file, and it is why [Weather.agree] can be arithmetic on plain
 * numbers.
 */
object WeatherSources {

    /**
     * Who the servers are talking to.
     *
     * One of these three asks for it in writing and will block a caller
     * that does not identify itself. It is a courtesy to the other two.
     */
    const val AGENT = "WeirdClock/1.0 (github.com/EM-87/Jeffrey_paradox)"

    /** One service: where to ask it, and how to read the answer. */
    class Source(
        val name: String,
        val url: (Double, Double) -> String,
        val read: (String, Long) -> Weather.Reading?
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

    /**
     * Open-Meteo, which answers everything in one object and in the units
     * everything else here uses.
     */
    val OPEN_METEO = Source(
        name = "open-meteo",
        url = { lat, lon ->
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,surface_pressure,cloud_cover," +
                "precipitation,wind_speed_10m,weather_code"
        },
        read = { body, atMs ->
            val current = JSONObject(body).optJSONObject("current")
            if (current == null) {
                null
            } else {
                // Their codes: 95 is a thunderstorm, 96 and 99 the same
                // with hail. Everything below 95 is weather without
                // lightning in it, which is a fact worth reporting as
                // false rather than as "do not know".
                val code = number(current, "weather_code")
                Weather.Reading(
                    source = "open-meteo",
                    atMs = atMs,
                    temperatureC = number(current, "temperature_2m"),
                    pressureHpa = number(current, "surface_pressure"),
                    cloudPercent = number(current, "cloud_cover"),
                    rainMmPerHour = number(current, "precipitation"),
                    windKph = number(current, "wind_speed_10m"),
                    thunder = code?.let { it >= 95.0 }
                )
            }
        }
    )

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
        }
    )

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
