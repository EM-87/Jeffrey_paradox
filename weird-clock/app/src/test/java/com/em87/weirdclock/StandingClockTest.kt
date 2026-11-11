package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A clock left standing goes back for the weather by itself.
 *
 * Asked as "are we fetching the map every day at all", and the answer
 * was: only if somebody picks the phone up. The clouds, the night's
 * lights and the weather were sent for when the settings were applied
 * — coming back to the app, building a card, night falling — and by
 * nothing else. A clock keeps its screen on. Put down on its stand
 * with the globe showing, it stood for as long as it was left with the
 * clouds of the afternoon it was put down, and said so in small print.
 *
 * So once a minute it asks each store whether it has anything worth
 * going back for, and each store answers from its own files. What is
 * held here is both halves of that: that it goes, when a day has
 * turned, and that it does not go every minute to a service that has
 * nothing yet — the clean night is two or three days behind on every
 * day of the year, and a weather service can be down for an afternoon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StandingClockTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val clouds = CopyOnWriteArrayList<String>()
    private val lights = CopyOnWriteArrayList<String>()
    private val weather = CopyOnWriteArrayList<String>()

    private val realClouds = CloudStore.fetch
    private val realLights = NightLightStore.fetch
    private val realWeather = WeatherStore.fetch

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Before
    fun aGlobeWithEverythingOn() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.HEMISPHERE.key)
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, true)
            .putString(Prefs.HEMISPHERE_LIGHTS, NightLights.Mode.BOTH.key)
            .putFloat(Prefs.LAST_LATITUDE, 40.42f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.70f)
            .commit()
        AlarmStore.forget()
        AlarmStore.all(context).clear()
        AlarmStore.save(context)
        CloudStore.forget(context)
        NightLightStore.forget(context)
        WeatherStore.forget(context)
        clouds.clear()
        lights.clear()
        weather.clear()
        val day = bytes("gibs-sample.jpg")
        val night = bytes("gibs-lights-raw.png")
        val nothing = bytes("gibs-lights-empty.png")
        CloudStore.fetch = CloudStore.Fetch { url ->
            clouds += url
            day
        }
        // The raw night is there for the asking. The clean one is not,
        // which is how it is on every day of the year for the two or
        // three days nearest now: the service answers with a blank.
        NightLightStore.fetch = DailyImageStore.Fetch { url ->
            lights += url
            if ("GapFilled" in url) nothing else night
        }
        // And nobody answers about the weather: an afternoon with the services down.
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            weather += url
            null
        }
    }

    @After
    fun putTheSocketsBack() {
        TimeKeeper.solarOffsetMs = 0L
        CloudStore.fetch = realClouds
        NightLightStore.fetch = realLights
        WeatherStore.fetch = realWeather
        CloudStore.forget(context)
        NightLightStore.forget(context)
        WeatherStore.forget(context)
    }

    /** Lets the once-a-second loop go round a couple of times. */
    private fun aFewSeconds() =
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))

    private fun asked() = Triple(clouds.size, lights.size, weather.size)

    /** Waits until the threads that fetch have gone quiet, and says what had been asked by then. */
    private fun quiet(): Triple<Int, Int, Int> {
        var last = asked()
        var still = 0
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(40)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val now = asked()
            if (now == last) still++ else still = 0
            last = now
            if (still >= 12) return now
        }
        throw AssertionError("the fetching never went quiet: $last")
    }

    private val minute = 60_000L
    private val day = DailyImageStore.DAY_MS

    @Test
    fun `a clock left standing goes back for what has gone old, and not every minute`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNotNull("the globe is not the face", c.get().hemisphereForTest())
            // Coming to the glass asks once, as it always did.
            val first = quiet()
            assertTrue("coming to the glass asked for no clouds, lights or weather: $first", first.first > 0 && first.second > 0 && first.third > 0)
            val today = CloudStore.dayOf(TimeKeeper.nowMs())
            assertEquals(today, CloudStore.worn(context, TimeKeeper.nowMs())?.day)

            // A minute on the stand. Nothing is old; the clean night is
            // still not there and the weather still does not answer,
            // and neither is asked again for that.
            TimeKeeper.solarOffsetMs = minute + 1_000L
            aFewSeconds()
            assertEquals("a minute on, somebody was asked again", first, quiet())

            // Two days on the stand, and nobody has touched it.
            TimeKeeper.solarOffsetMs = 2 * day + 2 * minute
            aFewSeconds()
            val later = quiet()
            val wanted = CloudStore.dayOf(TimeKeeper.nowMs())
            assertTrue("the day did not turn: $today then $wanted", wanted != today)
            assertTrue("two days on, the clouds of $wanted were not sent for: ${clouds.drop(first.first)}",
                clouds.drop(first.first).any { it.endsWith("TIME=$wanted") })
            assertEquals("and are not what the globe would wear", wanted, CloudStore.worn(context, TimeKeeper.nowMs())?.day)
            val night = NightLights.dayFor(TimeKeeper.nowMs())
            assertTrue("two days on, the night of $night was not sent for",
                lights.drop(first.second).any { it.endsWith("TIME=$night") && "GapFilled" !in it })
            assertTrue("two days on, the weather was not asked about", later.third > first.third)

            // The minute after that, with the clean night still a blank
            // and the weather still silent: not again.
            TimeKeeper.solarOffsetMs = 2 * day + 3 * minute + 1_000L
            aFewSeconds()
            assertEquals("the next minute, somebody was asked again", later, quiet())

            // The weather is asked about again when five minutes have gone...
            TimeKeeper.solarOffsetMs = 2 * day + 8 * minute
            aFewSeconds()
            val fiveOn = quiet()
            assertTrue("five minutes on, the weather was not asked about again", fiveOn.third > later.third)
            assertEquals("and the pictures were", later.first to later.second, fiveOn.first to fiveOn.second)

            // ...and the night that was not there, when an hour has.
            TimeKeeper.solarOffsetMs = 2 * day + 64 * minute
            aFewSeconds()
            val hourOn = quiet()
            assertTrue("an hour on, the clean night was not asked for again",
                lights.drop(fiveOn.second).any { "GapFilled" in it })
            assertTrue(hourOn.third > fiveOn.third)

            // And the clouds the same way, on the day their service has
            // nothing: sent for when the day turns, and not again the
            // minute after for the picture that did not come.
            CloudStore.fetch = CloudStore.Fetch { url ->
                clouds += url
                null
            }
            TimeKeeper.solarOffsetMs = 4 * day + 2 * minute
            aFewSeconds()
            val empty = quiet()
            val missing = CloudStore.dayOf(TimeKeeper.nowMs())
            assertTrue("the clouds of $missing were not sent for", clouds.drop(hourOn.first).any { it.endsWith("TIME=$missing") })
            TimeKeeper.solarOffsetMs = 4 * day + 3 * minute + 1_000L
            aFewSeconds()
            assertEquals("a minute after NASA had nothing, it was asked again", empty.first, quiet().first)
        }
    }

    /**
     * And only for what the face on the glass can show. The pictures
     * are the globe's: on the dial nobody goes back for them, however
     * long it stands — the weather, which the dial does wear, is asked
     * about all the same.
     */
    @Test
    fun `a dial left standing is not sent for the globe's pictures`() {
        prefs.edit().putString(Prefs.FACE, Face.ANALOG.key).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val first = quiet()
            assertEquals("the dial sent for clouds", 0, first.first)
            assertEquals("the dial sent for the night's lights", 0, first.second)
            assertTrue("the dial did not ask about the weather", first.third > 0)
            TimeKeeper.solarOffsetMs = 2 * day + 2 * minute
            aFewSeconds()
            val later = quiet()
            assertEquals("two days on, the dial sent for clouds", 0, later.first)
            assertEquals("two days on, the dial sent for the night's lights", 0, later.second)
            assertTrue("two days on, the dial did not ask about the weather", later.third > first.third)
        }
    }
}
