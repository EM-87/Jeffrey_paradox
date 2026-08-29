package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Three services, read off responses shaped like the real ones.
 *
 * A parser is the part of a network feature that can be tested properly
 * and almost never is: everything else needs a server, and this needs a
 * string. So the fixtures below are the real shapes — the same nesting,
 * the same names, and the same three different ideas about what a number
 * is — and the assertions are that all three come out in the same units
 * saying the same weather.
 *
 * That last part is the one that matters. [Weather.agree] is arithmetic on
 * plain numbers and has no idea that one of these reports wind in metres a
 * second; if the conversion is missing, three services agreeing perfectly
 * about a light breeze look like two agreeing and one lying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherSourcesTest {

    private val now = 1_700_000_000_000L

    /** Twenty-one degrees, a quarter of the sky, a light breeze, no rain. */
    private val openMeteo = """
        {"latitude":40.4,"longitude":-3.7,
         "current":{"time":"2026-08-29T12:00","interval":900,
           "temperature_2m":21.3,"surface_pressure":1012.4,"cloud_cover":25,
           "precipitation":0.0,"wind_speed_10m":10.8,"weather_code":2}}
    """.trimIndent()

    private val metNorway = """
        {"type":"Feature","properties":{"meta":{"updated_at":"2026-08-29T11:40:00Z"},
          "timeseries":[
            {"time":"2026-08-29T12:00:00Z","data":{
              "instant":{"details":{"air_pressure_at_sea_level":1012.9,
                "air_temperature":21.1,"cloud_area_fraction":28.9,
                "relative_humidity":44.2,"wind_from_direction":210.4,
                "wind_speed":3.0}},
              "next_1_hours":{"summary":{"symbol_code":"partlycloudy_day"},
                "details":{"precipitation_amount":0.0}}}}]}}
    """.trimIndent()

    private val wttr = """
        {"current_condition":[{"FeelsLikeC":"21","cloudcover":"25",
          "humidity":"44","precipMM":"0.0","pressure":"1012",
          "temp_C":"21","weatherCode":"116","winddirDegree":"210",
          "windspeedKmph":"11"}],"nearest_area":[{"areaName":[{"value":"Madrid"}]}]}
    """.trimIndent()

    /**
     * All three describe the same afternoon, and come out agreeing.
     *
     * Which is the point of the conversions: read them and hand them
     * straight to the thing that compares numbers.
     */
    @Test
    fun `three services, one afternoon, one reading`() {
        val readings = listOf(
            WeatherSources.OPEN_METEO.read(openMeteo, now),
            WeatherSources.MET_NORWAY.read(metNorway, now),
            WeatherSources.WTTR.read(wttr, now)
        )
        for (r in readings) assertNotNull("a service came back as nothing", r)
        val sky = Weather.agree(readings.filterNotNull(), now)
        assertEquals(3, sky.answered)
        assertEquals(Weather.Trust.AGREED, sky.temperatureC.trust)
        assertEquals(21.1, sky.temperatureC.value!!, 0.3)
        assertEquals(Weather.Trust.AGREED, sky.cloudPercent.trust)
        assertEquals(25.0, sky.cloudPercent.value!!, 5.0)
        assertEquals(Weather.Trust.AGREED, sky.pressureHpa.trust)
        assertEquals(1012.0, sky.pressureHpa.value!!, 1.5)
        assertEquals(false, sky.thunder)
        assertEquals(Weather.Look.CLEAR, Weather.look(sky))
    }

    /**
     * The wind, which is the one they measure in different units.
     *
     * Three metres a second is just under eleven kilometres an hour, and
     * two of the three say so directly. Leave the conversion out and the
     * odd one reads as three, which is far enough from eleven to be thrown
     * away — so a perfectly working service quietly stops counting and
     * nothing anywhere says why.
     */
    @Test
    fun `the one that reports metres a second is converted`() {
        val norway = WeatherSources.MET_NORWAY.read(metNorway, now)!!
        assertEquals(10.8, norway.windKph!!, 0.01)
        val sky = Weather.agree(
            listOf(
                WeatherSources.OPEN_METEO.read(openMeteo, now)!!,
                norway,
                WeatherSources.WTTR.read(wttr, now)!!
            ),
            now
        )
        assertEquals(Weather.Trust.AGREED, sky.windKph.trust)
        assertEquals(10.8, sky.windKph.value!!, 1.0)
    }

    /**
     * The one that sends every number as a string.
     *
     * Read as numbers, every field of it comes back empty and the service
     * is silently useless — which looks exactly like the service being
     * down, on a clock built around telling those two apart.
     */
    @Test
    fun `a service that quotes its numbers is still read`() {
        val reading = WeatherSources.WTTR.read(wttr, now)!!
        assertEquals(21.0, reading.temperatureC!!, 0.001)
        assertEquals(1012.0, reading.pressureHpa!!, 0.001)
        assertEquals(25.0, reading.cloudPercent!!, 0.001)
        assertEquals(0.0, reading.rainMmPerHour!!, 0.001)
        assertEquals(11.0, reading.windKph!!, 0.001)
    }

    /** Each of the three says which weather has lightning in it. */
    @Test
    fun `lightning, in three different notations`() {
        fun openMeteoCode(code: Int) = WeatherSources.OPEN_METEO.read(
            openMeteo.replace("\"weather_code\":2", "\"weather_code\":$code"), now
        )!!.thunder
        assertEquals(true, openMeteoCode(95))
        assertEquals(false, WeatherSources.OPEN_METEO.read(openMeteo, now)!!.thunder)
        // The two either side of the line, which is the half of a
        // threshold that a test naming only 2 and 95 leaves unpinned: 96
        // and 99 are thunderstorms with hail in them, and 86 is a heavy
        // snow shower with no lightning anywhere near it.
        assertEquals(true, openMeteoCode(96))
        assertEquals(true, openMeteoCode(99))
        assertEquals(false, openMeteoCode(94))
        assertEquals(false, openMeteoCode(86))

        val norwayStorm = metNorway.replace("partlycloudy_day", "rainandthunder")
        assertEquals(true, WeatherSources.MET_NORWAY.read(norwayStorm, now)!!.thunder)
        assertEquals(false, WeatherSources.MET_NORWAY.read(metNorway, now)!!.thunder)

        val wttrStorm = wttr.replace("\"weatherCode\":\"116\"", "\"weatherCode\":\"389\"")
        assertEquals(true, WeatherSources.WTTR.read(wttrStorm, now)!!.thunder)
        assertEquals(false, WeatherSources.WTTR.read(wttr, now)!!.thunder)
    }

    /**
     * A service that answers with something unexpected answers with
     * nothing, rather than throwing.
     *
     * This is the case the whole design is about, and it is not "the
     * server is down" — a down server never reaches a parser. It is the
     * morning the JSON changes shape: a field renamed, a block moved, an
     * error page served with a 200. Every one of those has to come back as
     * one quiet missing service and not as a clock that crashes.
     */
    @Test
    fun `a service that changes its mind overnight is one missing service`() {
        for (source in WeatherSources.all()) {
            for (rubbish in listOf(
                "{}",
                """{"error":"gone"}""",
                """{"current":{}}""",
                """{"properties":{"timeseries":[]}}""",
                """{"current_condition":[]}"""
            )) {
                // Either nothing, or a reading with nothing in it. Never a
                // throw, and never a made-up number.
                val reading = source.read(rubbish, now)
                if (reading != null) {
                    assertNull("${source.name} invented a temperature", reading.temperatureC)
                }
            }
        }
    }

    /** And the addresses carry the place they are being asked about. */
    @Test
    fun `every service is asked about the same place`() {
        for (source in WeatherSources.all()) {
            val url = source.url(40.4, -3.7)
            assertTrue("${source.name}: $url", url.startsWith("https://"))
            assertTrue("${source.name} lost the latitude: $url", url.contains("40.4"))
            assertTrue("${source.name} lost the longitude: $url", url.contains("-3.7"))
        }
        assertEquals(3, WeatherSources.all().size)
        assertEquals(3, WeatherSources.all().map { it.name }.toSet().size)
    }
}
