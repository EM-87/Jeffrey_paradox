package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The only part of this app that opens a socket, tested without one.
 *
 * Two claims, and the first is the one that matters most: **nothing here
 * touches the network unless somebody has switched it on.** This clock
 * spent its whole life not talking to anything and the weather is the
 * first thing that wants to, so "off means off" is not a preference, it is
 * the promise the switch makes.
 *
 * The second is that everything downstream survives the servers behaving
 * badly, which is the ordinary state of servers: one down, all down, one
 * answering with an error page, one answering with a 200 and the wrong
 * JSON in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val asked = ArrayList<String>()
    private val real = WeatherStore.fetch

    private val openMeteo = """
        {"current":{"temperature_2m":21.3,"surface_pressure":1012.4,
          "cloud_cover":25,"precipitation":0.0,"wind_speed_10m":10.8,
          "weather_code":2}}
    """.trimIndent()

    private val metNorway = """
        {"properties":{"timeseries":[{"data":{
          "instant":{"details":{"air_pressure_at_sea_level":1012.9,
            "air_temperature":21.1,"cloud_area_fraction":28.9,"wind_speed":3.0}},
          "next_1_hours":{"summary":{"symbol_code":"fair_day"},
            "details":{"precipitation_amount":0.0}}}}]}}
    """.trimIndent()

    private val wttr = """
        {"current_condition":[{"cloudcover":"25","precipMM":"0.0",
          "pressure":"1012","temp_C":"21","weatherCode":"116",
          "windspeedKmph":"11"}]}
    """.trimIndent()

    /** Answers whichever service the URL belongs to, or nothing. */
    private fun answering(vararg up: String) {
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            asked += url
            when {
                "open-meteo" in url && "open-meteo" in up -> openMeteo
                "met.no" in url && "met.no" in up -> metNorway
                "wttr.in" in url && "wttr.in" in up -> wttr
                else -> null
            }
        }
    }

    private fun switchedOn(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.WEATHER, on).commit()
    }

    @Before
    fun clean() {
        asked.clear()
        switchedOn(true)
    }

    @After
    fun putTheSocketBack() {
        WeatherStore.fetch = real
    }

    /**
     * Off means nothing is asked. Not "asked and discarded" — nothing is
     * sent at all.
     */
    @Test
    fun `switched off, this clock talks to nobody`() {
        switchedOn(false)
        answering("open-meteo", "met.no", "wttr.in")
        val sky = WeatherStore.refresh(context, 40.4, -3.7)
        assertTrue("it went out on the network with the switch off", asked.isEmpty())
        assertFalse(sky.known)
        assertFalse(WeatherStore.cached(context).known)
        assertFalse("it wanted to refresh with the switch off", WeatherStore.stale(context))
    }

    /** And on, it asks every one of them, every time. */
    @Test
    fun `switched on, all three are asked and the answer is agreed`() {
        answering("open-meteo", "met.no", "wttr.in")
        val sky = WeatherStore.refresh(context, 40.4, -3.7)
        assertEquals(3, asked.size)
        assertEquals(3, sky.answered)
        assertEquals(Weather.Trust.AGREED, sky.temperatureC.trust)
        assertEquals(21.1, sky.temperatureC.value!!, 0.3)
        // Asked even after the first one answered, because one answer is a
        // lone reading and two are an agreement.
        assertTrue(asked.any { "open-meteo" in it })
        assertTrue(asked.any { "met.no" in it })
        assertTrue(asked.any { "wttr.in" in it })
    }

    /**
     * Two of the three down still tells you the weather, and says it is
     * alone.
     */
    @Test
    fun `two down leaves a reading, and it says so`() {
        answering("wttr.in")
        val sky = WeatherStore.refresh(context, 40.4, -3.7)
        assertEquals("it stopped asking after the failures", 3, asked.size)
        assertEquals(1, sky.answered)
        assertEquals(Weather.Trust.LONE, sky.temperatureC.trust)
        assertTrue(sky.known)
    }

    /** All three down is silence, and silence does not overwrite what was known. */
    @Test
    fun `a night when nobody answers does not erase yesterday`() {
        answering("open-meteo", "met.no", "wttr.in")
        WeatherStore.refresh(context, 40.4, -3.7)
        val remembered = WeatherStore.cached(context)
        assertTrue(remembered.known)

        answering()
        val nothing = WeatherStore.refresh(context, 40.4, -3.7)
        assertFalse(nothing.known)
        // The cache still holds the last thing anybody agreed on. A clock
        // that blanks its weather because the wifi dropped for a minute is
        // a clock that looks broken every time a train goes into a tunnel.
        val still = WeatherStore.cached(context)
        assertTrue("a failed refresh wiped what was known", still.known)
        assertEquals(
            remembered.temperatureC.value!!, still.temperatureC.value!!, 0.001
        )
    }

    /**
     * What is written down comes back the same, trust included.
     *
     * The confidence has to survive the cache. Reading it back as an
     * agreement when it was one service guessing is the clock quietly
     * promoting a rumour every time it restarts.
     */
    @Test
    fun `the cache remembers how sure it was`() {
        answering("wttr.in")
        val alone = WeatherStore.refresh(context, 40.4, -3.7)
        assertEquals(Weather.Trust.LONE, alone.temperatureC.trust)
        val back = WeatherStore.cached(context)
        assertEquals(Weather.Trust.LONE, back.temperatureC.trust)
        assertEquals(alone.temperatureC.value!!, back.temperatureC.value!!, 0.001)
        assertEquals(alone.answered, back.answered)
        assertEquals(alone.atMs, back.atMs)
        assertEquals(alone.thunder, back.thunder)
    }

    /**
     * A service answering nonsense with a 200 is one missing service.
     *
     * The failure that is not a failure: an error page, a rate-limit
     * notice, a login form, all served cheerfully as JSON. Every one of
     * them has to be one quiet gap and never a crash.
     */
    @Test
    fun `an error page served as JSON is one missing service`() {
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            asked += url
            if ("wttr.in" in url) wttr else """{"message":"rate limited"}"""
        }
        val sky = WeatherStore.refresh(context, 40.4, -3.7)
        assertEquals(1, sky.answered)
        assertTrue(sky.known)
    }

    /**
     * The place is rounded before it leaves the phone.
     *
     * Two decimals is about a kilometre, and a forecast is the same across
     * one. A request carrying a doorstep says more than it needs to, and
     * the whole of what this feature is allowed to send is one place and
     * nothing else.
     */
    @Test
    fun `the location is rounded to about a kilometre`() {
        answering("open-meteo", "met.no", "wttr.in")
        WeatherStore.refresh(context, 40.416775123, -3.703790456)
        assertTrue("nobody was asked anything", asked.isNotEmpty())
        for (url in asked) {
            assertTrue("a doorstep went out on the wire: $url", "40.42" in url)
            assertTrue("a doorstep went out on the wire: $url", "-3.7" in url)
            assertFalse("a doorstep went out on the wire: $url", "40.4167" in url)
        }
    }

    /** And an hour later it is worth asking again; a minute later it is not. */
    @Test
    fun `it asks again every half hour and not before`() {
        answering("open-meteo", "met.no", "wttr.in")
        val sky = WeatherStore.refresh(context, 40.4, -3.7)
        assertFalse(WeatherStore.stale(context, sky.atMs + 60_000L))
        assertTrue(WeatherStore.stale(context, sky.atMs + WeatherStore.EVERY_MS + 1L))
    }
}
