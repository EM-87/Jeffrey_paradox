package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widget's sky token carries the weather too.
 *
 * It did not, for a whole version. The dial grew cloud, rain and lightning
 * around its sun and the widget went on drawing a bare one — the same
 * complication, on the same clock, disagreeing with itself on the home
 * screen where most people actually look at it.
 *
 * Nothing here fetches. A widget is rendered in whatever process the
 * launcher chooses to wake, and a clock face that opens a socket to draw
 * itself is a clock face that sometimes does not draw; it reads what the
 * app last agreed on and nothing else. Which is also why this is worth a
 * test of its own: the reading is a different call in a different file
 * from the one the dial makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetWeatherTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val real = WeatherStore.fetch

    @After
    fun putTheSocketBack() {
        WeatherStore.fetch = real
    }

    /** A sky with lightning in it, agreed by all three services. */
    private val storm = mapOf(
        "open-meteo" to """{"current":{"temperature_2m":14.0,"surface_pressure":998.0,
            "cloud_cover":95,"precipitation":4.0,"wind_speed_10m":30.0,
            "weather_code":95}}""",
        "met.no" to """{"properties":{"timeseries":[{"data":{
            "instant":{"details":{"air_pressure_at_sea_level":998.5,
              "air_temperature":14.2,"cloud_area_fraction":93.0,"wind_speed":8.0}},
            "next_1_hours":{"summary":{"symbol_code":"heavyrainandthunder"},
              "details":{"precipitation_amount":4.2}}}}]}}""",
        "wttr.in" to """{"current_condition":[{"cloudcover":"96","precipMM":"4.1",
            "pressure":"998","temp_C":"14","weatherCode":"200",
            "windspeedKmph":"31"}]}"""
    )

    /** And one with nothing in it at all. */
    private val clear = mapOf(
        "open-meteo" to """{"current":{"temperature_2m":24.0,"surface_pressure":1021.0,
            "cloud_cover":3,"precipitation":0.0,"wind_speed_10m":6.0,
            "weather_code":0}}""",
        "met.no" to """{"properties":{"timeseries":[{"data":{
            "instant":{"details":{"air_pressure_at_sea_level":1021.2,
              "air_temperature":24.1,"cloud_area_fraction":2.0,"wind_speed":1.6}},
            "next_1_hours":{"summary":{"symbol_code":"clearsky_day"},
              "details":{"precipitation_amount":0.0}}}}]}}""",
        "wttr.in" to """{"current_condition":[{"cloudcover":"2","precipMM":"0.0",
            "pressure":"1021","temp_C":"24","weatherCode":"113",
            "windspeedKmph":"6"}]}"""
    )

    /** Puts one sky in the cache, the long way round, through the seam. */
    private fun remember(sky: Map<String, String>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.MOON_PHASE, true)
            .commit()
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            sky.entries.firstOrNull { it.key in url }?.value
        }
        WeatherStore.refresh(context, 40.42, -3.70)
    }

    /** How many pixels two widget bitmaps disagree about. */
    private fun differ(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        }
        return n
    }

    /**
     * A thunderstorm draws something the clear sky does not.
     *
     * Two whole widgets compared rather than a region sampled, because the
     * token's place on the dial is not this test's business — what is
     * claimed is that the weather reaches the widget at all, and if it
     * does the two pictures cannot be identical.
     */
    @Test
    fun `the widget draws the weather the app agreed on`() {
        remember(clear)
        val fair = WidgetRenderer.dialBitmap(context, 400)
        remember(storm)
        val foul = WidgetRenderer.dialBitmap(context, 400)
        val moved = differ(fair, foul)
        assertTrue("the widget's sky token ignored the weather", moved > 40)
    }

    /**
     * And with the weather switched off it is the bare token again.
     *
     * The switch is the promise, and it has to hold on the home screen as
     * well as in the app: nothing cached, nothing drawn, and no difference
     * left over from the last time it was on.
     */
    @Test
    fun `switched off, the widget's sky is bare again`() {
        remember(storm)
        val foul = WidgetRenderer.dialBitmap(context, 400)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WEATHER, false).commit()
        WeatherStore.forget(context)
        val bare = WidgetRenderer.dialBitmap(context, 400)
        assertTrue("the storm outlived the switch", differ(foul, bare) > 40)
    }
}
