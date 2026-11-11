package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The hours to come, and the two things about now that were wrong.
 *
 * Held against what the three services really said, about one place at
 * one minute: Madrid, at 10:53 in Greenwich on the 11th of November 2026,
 * a wet morning, each answer saved whole as it arrived. Everything in
 * here that says "theirs" is read out of those files by this test, by a
 * different road from the parser's, so a number in an assertion is a
 * number a service sent.
 *
 * It was that morning that showed the two faults. One service's pressure
 * read 930 where the other two said 1008, because it was being asked for
 * the pressure at the ground and Madrid is six hundred and fifty metres
 * up; and its rain read 0.3 where the other two said 1.4 and 1.5, because
 * its "now" is what fell in the last quarter of an hour. Neither showed
 * on the dial: with three services the odd one out is thrown away, which
 * is the design working and also the design hiding that one of its three
 * voices never counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherForecastTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /** When the three answers were fetched. */
    private val fetched = 1_794_394_404_000L

    /** Ten o'clock that morning, in Greenwich: the hour the fetch fell in. */
    private val ten = 1_794_391_200_000L

    private val hourMs = 3_600_000L

    private fun saved(name: String): String =
        javaClass.getResource("/weather/$name")!!.readText()

    private val openMeteo by lazy { saved("open-meteo.json") }
    private val metNorway by lazy { saved("met-norway.json") }
    private val wttr by lazy { saved("wttr.json") }

    private val real = WeatherStore.fetch

    @Before
    fun on() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.WEATHER, true).commit()
    }

    @After
    fun restore() {
        WeatherStore.fetch = real
        TimeKeeper.solarOffsetMs = 0L
    }

    // ---------------------------------------------------------------- now

    /**
     * All three voices count, about the pressure and about the rain.
     *
     * Not "the agreed number is right" — it was right before, with two
     * voices. Each service's own reading has to be within the tolerance
     * of the other two, which is what being listened to means.
     */
    @Test
    fun `the three real answers agree about that morning, every one of them`() {
        val readings = listOf(
            WeatherSources.OPEN_METEO.read(openMeteo, fetched)!!,
            WeatherSources.MET_NORWAY.read(metNorway, fetched)!!,
            WeatherSources.WTTR.read(wttr, fetched)!!
        )
        val sky = Weather.agree(readings, fetched)
        assertEquals(3, sky.answered)
        for (reading in readings) {
            val pressure = reading.pressureHpa
            assertNotNull("${reading.source} gave no pressure", pressure)
            assertTrue(
                "${reading.source} says $pressure hPa where the three agree on ${sky.pressureHpa.value}",
                abs(pressure!! - sky.pressureHpa.value!!) <= Weather.PRESSURE_SLACK
            )
            val rain = reading.rainMmPerHour
            assertNotNull("${reading.source} gave no rain", rain)
            assertTrue(
                "${reading.source} says $rain mm an hour where the three agree on ${sky.rainMmPerHour.value}",
                abs(rain!! - sky.rainMmPerHour.value!!) <= Weather.RAIN_SLACK
            )
        }
        // What they sent, read here off the files: a quarter of an hour's
        // rain and the seconds it fell in; sea level from all three.
        val current = JSONObject(openMeteo).getJSONObject("current")
        assertEquals(900, current.getInt("interval"))
        assertEquals(
            current.getDouble("precipitation") * 4.0,
            readings[0].rainMmPerHour!!, 0.0001
        )
        assertEquals(current.getDouble("pressure_msl"), readings[0].pressureHpa!!, 0.0001)
        assertEquals(1007.9, sky.pressureHpa.value!!, 0.01)
        assertEquals(1.4, sky.rainMmPerHour.value!!, 0.01)
        assertEquals(14.0, sky.temperatureC.value!!, 0.01)
        assertEquals(Weather.Look.RAIN, Weather.look(sky))
    }

    /** And it is asked for by name, because what is not asked for is not sent. */
    @Test
    fun `the address asks for sea level and for the hours`() {
        val url = WeatherSources.OPEN_METEO.url(40.42, -3.7)
        assertTrue("it asks for the pressure at the ground: $url", "pressure_msl" in url)
        assertFalse("it still asks for the pressure at the ground: $url", "surface_pressure" in url)
        assertTrue("it does not ask for the hours: $url", "&hourly=temperature_2m,cloud_cover,precipitation,weather_code" in url)
        // Seconds, not a date written in whichever zone they guessed.
        assertTrue("its times are not seconds: $url", "timeformat=unixtime" in url)
        assertTrue("it asks for fewer than six days: $url", "forecast_days=6" in url)
        // The address this test's saved answer came from.
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=40.42&longitude=-3.7" +
                "&current=temperature_2m,pressure_msl,cloud_cover,precipitation,wind_speed_10m,weather_code" +
                "&hourly=temperature_2m,cloud_cover,precipitation,weather_code" +
                "&timeformat=unixtime&forecast_days=6",
            url
        )
    }

    /** Rain with no length of time to it is not a rate, and is not guessed at. */
    @Test
    fun `rain that does not say how long it took is not read as an hour's`() {
        val unsaid = """{"current":{"temperature_2m":14.4,"precipitation":0.3}}"""
        val reading = WeatherSources.OPEN_METEO.read(unsaid, fetched)!!
        assertNull("0.3 of nobody knows what, read as a rate", reading.rainMmPerHour)
        assertEquals(14.4, reading.temperatureC!!, 0.0001)
        // An hour's worth is an hour's worth.
        val hourly = """{"current":{"interval":3600,"precipitation":0.3}}"""
        assertEquals(0.3, WeatherSources.OPEN_METEO.read(hourly, fetched)!!.rainMmPerHour!!, 0.0001)
        val none = """{"current":{"interval":0,"precipitation":0.3}}"""
        assertNull(WeatherSources.OPEN_METEO.read(none, fetched)!!.rainMmPerHour)
    }

    // ------------------------------------------------- one service's hours

    /**
     * Open-Meteo's hours, each with the rain of the hour that follows it.
     *
     * Theirs is counted backwards — every entry carries what fell in the
     * hour before — so the rain belonging to the hour that starts at ten
     * is the eleven o'clock entry's.
     */
    @Test
    fun `open-meteo's hours carry the rain that follows them`() {
        val hours = WeatherSources.OPEN_METEO.hours(openMeteo)
        val theirs = JSONObject(openMeteo).getJSONObject("hourly")
        val times = theirs.getJSONArray("time")
        assertEquals(144, times.length())
        assertEquals(144, hours.size)
        for (i in hours.indices) {
            assertEquals(times.getLong(i) * 1000L, hours[i].atMs)
            assertEquals(theirs.getJSONArray("temperature_2m").getDouble(i), hours[i].temperatureC!!, 0.0001)
            assertEquals(theirs.getJSONArray("cloud_cover").getDouble(i), hours[i].cloudPercent!!, 0.0001)
            if (i + 1 < hours.size) {
                assertEquals(
                    "the rain of hour $i",
                    theirs.getJSONArray("precipitation").getDouble(i + 1), hours[i].rainMm!!, 0.0001
                )
            } else {
                assertNull("the last hour has a rain nobody sent", hours[i].rainMm)
            }
            assertEquals(theirs.getJSONArray("weather_code").getInt(i) >= 95, hours[i].thunder)
        }
        // And the one this is all about, by hand: the hour from ten.
        val atTen = hours.first { it.atMs == ten }
        assertEquals(14.1, atTen.temperatureC!!, 0.0001)
        assertEquals(1.0, atTen.rainMm!!, 0.0001)
    }

    /**
     * MET Norway's hours: one an hour for two days and a half, and then
     * one every six with the rain of all six spread over them.
     */
    @Test
    fun `met norway's hours go on past where it stops speaking hourly`() {
        val hours = WeatherSources.MET_NORWAY.hours(metNorway)
        val series = JSONObject(metNorway).getJSONObject("properties").getJSONArray("timeseries")
        assertEquals(89, series.length())
        val byTime = hours.associateBy { it.atMs }
        assertEquals("an hour twice over", hours.size, byTime.size)

        // The first is the hour the fetch fell in.
        val first = hours.first()
        assertEquals(ten, first.atMs)
        assertEquals(13.6, first.temperatureC!!, 0.0001)
        assertEquals(100.0, first.cloudPercent!!, 0.0001)
        assertEquals(1.5, first.rainMm!!, 0.0001)
        assertEquals(false, first.thunder)

        var sixes = 0
        for (i in 0 until series.length()) {
            val entry = series.getJSONObject(i)
            val at = WeatherSources.instantMs(entry.getString("time"))!!
            val data = entry.getJSONObject("data")
            val mine = byTime[at]
            assertNotNull("entry $i is missing", mine)
            assertEquals(
                data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"),
                mine!!.temperatureC!!, 0.0001
            )
            val one = data.optJSONObject("next_1_hours")
            val six = data.optJSONObject("next_6_hours")
            if (one != null) {
                assertEquals(
                    one.getJSONObject("details").getDouble("precipitation_amount"),
                    mine.rainMm!!, 0.0001
                )
            } else if (six != null) {
                sixes++
                // The six hours from here add up to what they said, and
                // only the first of them has a temperature.
                val fell = six.getJSONObject("details").getDouble("precipitation_amount")
                var sum = 0.0
                for (later in 0 until 6) {
                    val hour = byTime[at + later * hourMs]
                    assertNotNull("hour $later of a six is missing", hour)
                    sum += hour!!.rainMm!!
                    if (later > 0) assertNull("a temperature nobody sent", hour.temperatureC)
                }
                assertEquals("six hours of rain", fell, sum, 0.0001)
            } else {
                assertNull("rain nobody sent", mine.rainMm)
            }
        }
        assertTrue("the answer had no six-hour stretches to test with", sixes > 10)
    }

    /** Their time is one particular way of writing one, and nothing else is read as it. */
    @Test
    fun `a time is read to the second or not at all`() {
        assertEquals(ten, WeatherSources.instantMs("2026-11-11T10:00:00Z"))
        assertEquals(ten + 59_000L, WeatherSources.instantMs("2026-11-11T10:00:59Z"))
        assertEquals(0L, WeatherSources.instantMs("1970-01-01T00:00:00Z"))
        // A leap day, which is where a calendar done by hand goes wrong.
        assertEquals(1_709_164_800_000L, WeatherSources.instantMs("2024-02-29T00:00:00Z"))
        assertNull(WeatherSources.instantMs("2026-11-11T10:00"))
        assertNull(WeatherSources.instantMs("2026-11-11T10:00:00+01:00"))
        assertNull(WeatherSources.instantMs(""))
    }

    /** And a service that has changed its mind overnight has no hours, quietly. */
    @Test
    fun `rubbish has no hours in it`() {
        for (source in WeatherSources.all()) {
            for (rubbish in listOf(
                "{}", """{"hourly":{}}""", """{"hourly":{"time":[1,2]}}""",
                """{"hourly":{"time":["2026-11-11T10:00"],"temperature_2m":[3]}}""",
                """{"properties":{"timeseries":[{"time":"soon","data":{}}]}}""",
                """{"properties":{"timeseries":[{"data":{}}]}}"""
            )) {
                val hours = source.hours(rubbish)
                for (hour in hours) {
                    assertNull("${source.name} invented a temperature from $rubbish", hour.temperatureC)
                }
            }
        }
        // The one of the three that says nothing about tomorrow says nothing.
        assertTrue(WeatherSources.WTTR.hours(wttr).isEmpty())
    }

    /** Lightning in an hour to come, on the same line as lightning now. */
    @Test
    fun `an hour's lightning starts at the same code as now's`() {
        val body = """{"hourly":{"time":[0,3600,7200,10800],"temperature_2m":[1,1,1,1],
            "weather_code":[94,95,96,99]}}"""
        assertEquals(
            listOf(false, true, true, true),
            WeatherSources.OPEN_METEO.hours(body).map { it.thunder }
        )
        val norway = """{"properties":{"timeseries":[
            {"time":"2026-11-11T10:00:00Z","data":{"instant":{"details":{"air_temperature":1}},
              "next_1_hours":{"summary":{"symbol_code":"heavyrainandthunder"},"details":{}}}},
            {"time":"2026-11-11T11:00:00Z","data":{"instant":{"details":{"air_temperature":1}},
              "next_6_hours":{"summary":{"symbol_code":"rainshowersandthunder_day"},
                "details":{"precipitation_amount":6.0}}}},
            {"time":"2026-11-11T17:00:00Z","data":{"instant":{"details":{"air_temperature":1}}}}]}}"""
        val hours = WeatherSources.MET_NORWAY.hours(norway)
        // One hour of its own, six from the stretch, and the last with
        // nothing said about lightning at all.
        assertEquals(listOf(true, true, true, true, true, true, true, null), hours.map { it.thunder })
        assertEquals(listOf(null, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, null), hours.map { it.rainMm })
    }

    // ------------------------------------------------------------ agreeing

    /**
     * Two models far apart are two models.
     *
     * Now, two readings four degrees apart mean a broken service, and the
     * answer is nothing. Ahead, they mean a forecast — and that morning
     * the two services were more than a millimetre apart about five of
     * the wettest hours of the day, which is exactly where a rule of
     * "nothing" would have left the card dry.
     */
    @Test
    fun `where two forecasts part company the middle is kept, and marked`() {
        assertEquals(Weather.Agreed(21.0, Weather.Trust.AGREED), Weather.expect(listOf(20.0, 22.0), 2.0))
        assertEquals(Weather.Agreed(25.0, Weather.Trust.LONE), Weather.expect(listOf(20.0, 30.0), 2.0))
        assertEquals(Weather.Agreed(20.0, Weather.Trust.LONE), Weather.expect(listOf(20.0), 2.0))
        assertEquals(Weather.Agreed.NOTHING, Weather.expect(emptyList(), 2.0))
        // Three, and the odd one out goes as it always has.
        assertEquals(Weather.Agreed(20.5, Weather.Trust.AGREED), Weather.expect(listOf(20.0, 21.0, 40.0), 2.0))
        // And the same two, asked about now: nothing.
        assertEquals(Weather.Agreed.NOTHING, Weather.agree(listOf(20.0, 30.0), 2.0))
    }

    /** The two real answers, hour by hour. */
    @Test
    fun `two services' hours become one run of them`() {
        val both = listOf(
            WeatherSources.OPEN_METEO.hours(openMeteo),
            WeatherSources.MET_NORWAY.hours(metNorway)
        )
        val hours = Weather.forecast(both, fetched, 120)
        assertEquals(120, hours.size)
        for (i in hours.indices) assertEquals(ten + i * hourMs, hours[i].atMs)

        // Ten o'clock: 14.1 and 13.6, 1.0 mm and 1.5.
        val first = hours.first()
        assertEquals(13.85, first.temperatureC.value!!, 0.0001)
        assertEquals(Weather.Trust.AGREED, first.temperatureC.trust)
        assertEquals(1.25, first.rainMm.value!!, 0.0001)
        assertEquals(Weather.Trust.AGREED, first.rainMm.trust)
        assertEquals(100.0, first.cloudPercent.value!!, 0.0001)
        assertEquals(false, first.thunder)
        assertEquals(Weather.Look.RAIN, first.look)
        assertTrue(first.sure)

        // Eight in the evening: 8.8 mm from one and 2.7 from the other.
        // Kept, as their middle, and not called agreed.
        val evening = hours.first { it.atMs == ten + 10 * hourMs }
        assertEquals(5.75, evening.rainMm.value!!, 0.0001)
        assertEquals(Weather.Trust.LONE, evening.rainMm.trust)
        assertEquals(Weather.Look.RAIN, evening.look)

        // Past two days and a half one of them speaks every six hours:
        // six in the morning on the 14th has both, seven has one.
        val six = hours.first { it.atMs == ten + 68 * hourMs }
        assertEquals(Weather.Trust.AGREED, six.temperatureC.trust)
        assertEquals((10.8 + 11.3) / 2, six.temperatureC.value!!, 0.0001)
        val seven = hours.first { it.atMs == ten + 69 * hourMs }
        assertEquals(Weather.Trust.LONE, seven.temperatureC.trust)
        assertFalse(seven.sure)
        // But the rain there is still two voices: 0.7 mm over six hours
        // from one, which is a sixth of that an hour, and the other's own
        // 2.8 for that hour. A model apart — twelve millimetres against
        // less than one for the same morning — and kept as their middle.
        assertEquals((2.8 + 0.7 / 6) / 2, seven.rainMm.value!!, 0.0001)
        assertEquals(Weather.Trust.LONE, seven.rainMm.trust)
        // Eleven o'clock: 0.4 against the same sixth, which is agreeing.
        val eleven = hours.first { it.atMs == ten + 73 * hourMs }
        assertEquals((0.4 + 0.7 / 6) / 2, eleven.rainMm.value!!, 0.0001)
        assertEquals(Weather.Trust.AGREED, eleven.rainMm.trust)
        assertEquals(Weather.Agreed(11.9, Weather.Trust.LONE), eleven.temperatureC)
    }

    /** Hours are matched by when they are, and an hour nobody mentioned is not there. */
    @Test
    fun `hours are lined up by the clock and gaps are left as gaps`() {
        val a = listOf(
            Weather.Hour(ten, temperatureC = 10.0),
            Weather.Hour(ten + 2 * hourMs, temperatureC = 12.0, rainMm = 0.4)
        )
        val b = listOf(
            // Out of order, and one that is not on the hour at all.
            Weather.Hour(ten + 2 * hourMs, temperatureC = 13.0),
            Weather.Hour(ten + 30 * 60_000L, temperatureC = 99.0),
            Weather.Hour(ten, temperatureC = 11.0, thunder = true)
        )
        val hours = Weather.forecast(listOf(a, b), ten + 59 * 60_000L, 4)
        assertEquals(listOf(ten, ten + 2 * hourMs), hours.map { it.atMs })
        assertEquals(10.5, hours[0].temperatureC.value!!, 0.0001)
        assertEquals(true, hours[0].thunder)
        assertEquals(Weather.Look.STORM, hours[0].look)
        assertEquals(12.5, hours[1].temperatureC.value!!, 0.0001)
        assertEquals(Weather.Agreed(0.4, Weather.Trust.LONE), hours[1].rainMm)
        // From the hour it is, not from the one before or after.
        assertEquals(listOf(ten + 2 * hourMs), Weather.forecast(listOf(a, b), ten + hourMs, 4).map { it.atMs })
        assertTrue(Weather.forecast(listOf(a, b), ten, 0).isEmpty())
        assertTrue(Weather.forecast(emptyList(), ten, 24).isEmpty())
        // An hour with nothing said about it is not an hour.
        assertTrue(Weather.forecast(listOf(listOf(Weather.Hour(ten))), ten, 2).isEmpty())
    }

    // ---------------------------------------------------------------- days

    private fun hour(
        at: Long, degrees: Double?, cloud: Double? = 0.0, rain: Double? = 0.0,
        thunder: Boolean? = false, sure: Boolean = true
    ) = Weather.AgreedHour(
        atMs = at,
        temperatureC = if (degrees == null) Weather.Agreed.NOTHING else
            Weather.Agreed(degrees, if (sure) Weather.Trust.AGREED else Weather.Trust.LONE),
        cloudPercent = if (cloud == null) Weather.Agreed.NOTHING else Weather.Agreed(cloud, Weather.Trust.AGREED),
        rainMm = if (rain == null) Weather.Agreed.NOTHING else Weather.Agreed(rain, Weather.Trust.AGREED),
        thunder = thunder
    )

    /** A day of twenty-four hours from [from], each made by [make] from its hour of the day. */
    private fun day(from: Long, make: (Int) -> Weather.AgreedHour?): List<Weather.AgreedHour> =
        (0 until 24).mapNotNull { make(it)?.copy(atMs = from + it * hourMs) }

    @Test
    fun `a day is its coldest hour, its warmest, and its rain added up`() {
        val midnight = ten - 10 * hourMs
        val hours = day(midnight) { h -> hour(0L, degrees = 5.0 + h, rain = if (h in 3..4) 0.3 else 0.0) } +
            day(midnight + 24 * hourMs) { h -> hour(0L, degrees = 40.0 - h, rain = 0.0) }
        val days = Weather.days(hours, listOf(midnight, midnight + 24 * hourMs, midnight + 48 * hourMs))
        assertEquals(2, days.size)
        assertEquals(midnight, days[0].startMs)
        assertEquals(5.0, days[0].minC!!, 0.0001)
        assertEquals(28.0, days[0].maxC!!, 0.0001)
        assertEquals(0.6, days[0].rainMm, 0.0001)
        assertEquals(17.0, days[1].minC!!, 0.0001)
        // The hour on the stroke of midnight belongs to the day it
        // starts: it is the second day's warmest, at forty, and the first
        // day's warmest is still twenty-eight.
        assertEquals(40.0, days[1].maxC!!, 0.0001)
        assertEquals(0.0, days[1].rainMm, 0.0001)
        assertEquals(midnight + 24 * hourMs, days[1].startMs)
    }

    /**
     * The one word for a day: lightning, then a millimetre of rain, then
     * the cloud while people are awake.
     */
    @Test
    fun `a day's word is what somebody would say about it`() {
        val midnight = ten - 10 * hourMs
        val starts = listOf(midnight, midnight + 24 * hourMs)
        fun word(make: (Int) -> Weather.AgreedHour?) = Weather.days(day(midnight, make), starts).single().look

        assertEquals(Weather.Look.CLEAR, word { hour(0L, 10.0, cloud = 10.0) })
        assertEquals(Weather.Look.CLOUDY, word { hour(0L, 10.0, cloud = 60.0) })
        assertEquals(Weather.Look.OVERCAST, word { hour(0L, 10.0, cloud = 90.0) })
        // Overcast before six and fair all day is a fair day, and so is
        // one that clouds over at nine at night…
        assertEquals(Weather.Look.CLEAR, word { h -> hour(0L, 10.0, cloud = if (h < 6) 100.0 else 40.0) })
        assertEquals(Weather.Look.CLEAR, word { h -> hour(0L, 10.0, cloud = if (h >= 21) 100.0 else 40.0) })
        // …and the edges are exactly six and nine: with nothing known
        // about the cloud between them, a solid lid over every other
        // hour is no cloud at all.
        assertEquals(
            Weather.Look.CLEAR,
            word { h -> hour(0L, 10.0, cloud = if (h in 6..20) null else 100.0) }
        )
        assertEquals(
            Weather.Look.OVERCAST,
            word { h -> hour(0L, 10.0, cloud = if (h in 6..20) 100.0 else 0.0) }
        )
        // A millimetre in the day is a wet day; less is not, however
        // overcast the hour it fell in.
        assertEquals(Weather.Look.RAIN, word { h -> hour(0L, 10.0, cloud = 10.0, rain = if (h < 5) 0.2 else 0.0) })
        assertEquals(Weather.Look.CLEAR, word { h -> hour(0L, 10.0, cloud = 10.0, rain = if (h < 4) 0.2 else 0.0) })
        // And one hour of lightning is a stormy day.
        assertEquals(
            Weather.Look.STORM,
            word { h -> hour(0L, 10.0, cloud = 10.0, thunder = h == 3) }
        )
    }

    /**
     * A day is sure when a second service agreed about one hour in six.
     *
     * Which is as often as one of the two speaks at all, past its first
     * two days and a half.
     */
    @Test
    fun `a day is sure when it was agreed as often as it could be`() {
        val midnight = ten - 10 * hourMs
        val starts = listOf(midnight, midnight + 24 * hourMs)
        fun sure(make: (Int) -> Weather.AgreedHour?) = Weather.days(day(midnight, make), starts).single().sure

        assertTrue(sure { hour(0L, 10.0) })
        assertTrue("four agreed hours in twenty-four", sure { h -> hour(0L, 10.0, sure = h % 6 == 0) })
        assertFalse("three agreed hours in twenty-four", sure { h -> hour(0L, 10.0, sure = h % 6 == 0 && h > 0) })
        assertFalse(sure { hour(0L, 10.0, sure = false) })
    }

    /** Half a day is not a day, and a day the clocks change in is still one. */
    @Test
    fun `a day needs twelve of its hours, and may have twenty-three`() {
        val midnight = ten - 10 * hourMs
        val eleven = (0 until 11).map { hour(midnight + it * hourMs, 10.0) }
        val twelve = (0 until 12).map { hour(midnight + it * hourMs, 10.0) }
        val starts = listOf(midnight, midnight + 24 * hourMs)
        assertTrue(Weather.days(eleven, starts).isEmpty())
        assertEquals(1, Weather.days(twelve, starts).size)
        // The day in spring that is twenty-three hours long, as its two
        // midnights say it is: the twenty-fourth hour is the next day's.
        val short = (0 until 24).map { hour(midnight + it * hourMs, it.toDouble()) }
        val spring = Weather.days(short, listOf(midnight, midnight + 23 * hourMs))
        assertEquals(22.0, spring.single().maxC!!, 0.0001)
        // No days without two midnights to put one between.
        assertTrue(Weather.days(short, listOf(midnight)).isEmpty())
        assertTrue(Weather.days(short, emptyList()).isEmpty())
        // And a day whose hours have no temperature has none, and is
        // still a day with rain in it.
        val wet = (0 until 24).map { hour(midnight + it * hourMs, null, rain = 0.5) }
        val soaked = Weather.days(wet, starts).single()
        assertNull(soaked.minC)
        assertNull(soaked.maxC)
        assertEquals(12.0, soaked.rainMm, 0.0001)
    }

    /** The real morning, as days: the 12th to the 15th, whole, in Madrid's own midnights. */
    @Test
    fun `the real answers make four whole days after the first`() {
        val hours = Weather.forecast(
            listOf(WeatherSources.OPEN_METEO.hours(openMeteo), WeatherSources.MET_NORWAY.hours(metNorway)),
            fetched, 120
        )
        // Madrid is an hour ahead of Greenwich in November.
        val midnight12 = ten + 13 * hourMs
        val starts = (0..4).map { midnight12 + it * 24 * hourMs }
        val days = Weather.days(hours, starts)
        assertEquals(4, days.size)
        for (day in days) {
            assertNotNull(day.minC)
            assertTrue("${day.minC} to ${day.maxC}", day.minC!! < day.maxC!!)
            assertTrue("a November day in Madrid at ${day.minC}", day.minC!! in 0.0..20.0)
            assertTrue("a November day in Madrid at ${day.maxC}", day.maxC!! in 5.0..25.0)
        }
        // Two days and a half of two voices an hour, and then one every
        // six: every one of the four is sure.
        assertEquals(listOf(true, true, true, true), days.map { it.sure })
        // And with one service gone none of them is, and they are all
        // still there.
        val alone = Weather.days(
            Weather.forecast(listOf(WeatherSources.OPEN_METEO.hours(openMeteo)), fetched, 120), starts
        )
        assertEquals(4, alone.size)
        assertEquals(listOf(false, false, false, false), alone.map { it.sure })
        // The other one alone, which speaks every six hours out there,
        // still has days: the rain is spread over the hours between.
        val norway = Weather.days(
            Weather.forecast(listOf(WeatherSources.MET_NORWAY.hours(metNorway)), fetched, 120), starts
        )
        assertEquals(4, norway.size)
    }

    // ----------------------------------------------------------- the store

    private fun answering(vararg up: String) {
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            when {
                "open-meteo" in url && "open-meteo" in up -> openMeteo
                "met.no" in url && "met.no" in up -> metNorway
                "wttr.in" in url && "wttr.in" in up -> wttr
                else -> null
            }
        }
    }

    /** The clock reads [atMs]. */
    private fun clockAt(atMs: Long) {
        TimeKeeper.solarOffsetMs = atMs - System.currentTimeMillis()
    }

    /**
     * One round of asking writes down both things, and what is read back
     * is what was agreed: every number, whether it was confirmed, and
     * lightning that is false kept apart from lightning nobody mentioned.
     */
    @Test
    fun `the hours are written down and read back as they were agreed`() {
        clockAt(fetched)
        answering("open-meteo", "met.no", "wttr.in")
        val sky = WeatherStore.refresh(context, 40.42, -3.7)
        assertTrue(sky.known)
        val agreed = Weather.forecast(
            listOf(WeatherSources.OPEN_METEO.hours(openMeteo), WeatherSources.MET_NORWAY.hours(metNorway)),
            fetched, WeatherStore.FORECAST_HOURS
        )
        val kept = WeatherStore.forecast(context, fetched)
        assertEquals(120, kept.size)
        assertEquals(agreed, kept)
        assertTrue("no hour kept its lightning as false", kept.any { it.thunder == false })
        assertTrue("nothing unconfirmed survived", kept.any { it.temperatureC.trust == Weather.Trust.LONE })
        assertTrue("nothing confirmed survived", kept.any { it.temperatureC.trust == Weather.Trust.AGREED })
        assertEquals(TimeKeeper.nowMs().toDouble(), WeatherStore.forecastAt(context).toDouble(), 5_000.0)

        // And the things that morning did not have in it: an hour with
        // no temperature, one with no cloud, one with no rain, lightning
        // nobody mentioned and lightning somebody did.
        val seconds = ten / 1000L
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            if ("open-meteo" !in url) {
                null
            } else {
                """{"hourly":{"time":[$seconds,${seconds + 3600}],"temperature_2m":[10.5,null],
                    "cloud_cover":[null,50],"precipitation":[9,0.4],"weather_code":[null,95]}}"""
            }
        }
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(
            listOf(
                Weather.AgreedHour(
                    atMs = ten,
                    temperatureC = Weather.Agreed(10.5, Weather.Trust.LONE),
                    rainMm = Weather.Agreed(0.4, Weather.Trust.LONE),
                    thunder = null
                ),
                Weather.AgreedHour(
                    atMs = ten + hourMs,
                    cloudPercent = Weather.Agreed(50.0, Weather.Trust.LONE),
                    thunder = true
                )
            ),
            WeatherStore.forecast(context, fetched)
        )
    }

    /** An hour that has gone is not a forecast. */
    @Test
    fun `hours that have passed are not handed out`() {
        clockAt(fetched)
        answering("open-meteo", "met.no", "wttr.in")
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(ten, WeatherStore.forecast(context, fetched).first().atMs)
        // Still the ten o'clock hour at one minute to eleven…
        assertEquals(ten, WeatherStore.forecast(context, ten + hourMs - 1).first().atMs)
        // …and not at eleven.
        assertEquals(ten + hourMs, WeatherStore.forecast(context, ten + hourMs).first().atMs)
        val later = WeatherStore.forecast(context, fetched + 5 * hourMs)
        assertEquals(ten + 5 * hourMs, later.first().atMs)
        assertEquals(115, later.size)
        assertTrue(WeatherStore.forecast(context, fetched + 500 * hourMs).isEmpty())
    }

    /** Off means off, for tomorrow as much as for today. */
    @Test
    fun `with the switch off there are no hours and nothing is asked`() {
        clockAt(fetched)
        answering("open-meteo", "met.no", "wttr.in")
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(120, WeatherStore.forecast(context, fetched).size)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WEATHER, false).commit()
        assertTrue(WeatherStore.forecast(context, fetched).isEmpty())
        assertEquals(0L, WeatherStore.forecastAt(context))
        var asked = 0
        WeatherStore.fetch = WeatherStore.Fetch { asked++; null }
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(0, asked)
    }

    /**
     * One service down is a forecast nobody confirmed; all of them down
     * leaves the last one where it was.
     */
    @Test
    fun `a service down costs confidence, and all of them down costs nothing`() {
        clockAt(fetched)
        answering("open-meteo")
        WeatherStore.refresh(context, 40.42, -3.7)
        val alone = WeatherStore.forecast(context, fetched)
        assertEquals(120, alone.size)
        assertTrue(alone.none { it.sure })

        answering("met.no")
        WeatherStore.refresh(context, 40.42, -3.7)
        val norway = WeatherStore.forecast(context, fetched)
        assertTrue(norway.isNotEmpty())
        assertEquals(13.6, norway.first().temperatureC.value!!, 0.0001)

        // Nobody answers: what was known is still known.
        answering()
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(norway, WeatherStore.forecast(context, fetched))

        // Only the one that says nothing about tomorrow answers: there is
        // a sky to write down and no hours, and the hours stay.
        answering("wttr.in")
        assertTrue(WeatherStore.refresh(context, 40.42, -3.7).known)
        assertEquals(norway, WeatherStore.forecast(context, fetched))

        // And an answer with the wrong thing in it is one more service down.
        WeatherStore.fetch = WeatherStore.Fetch { """{"hourly":"soon","properties":7}""" }
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(norway, WeatherStore.forecast(context, fetched))

        WeatherStore.forget(context)
        assertTrue(WeatherStore.forecast(context, fetched).isEmpty())
        assertFalse(WeatherStore.cached(context).known)
    }

    /** One request to each, as before: the hours ride on the answer already asked for. */
    @Test
    fun `the forecast costs no request that was not being made`() {
        clockAt(fetched)
        val asked = ArrayList<String>()
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            asked += url
            when {
                "open-meteo" in url -> openMeteo
                "met.no" in url -> metNorway
                else -> wttr
            }
        }
        WeatherStore.refresh(context, 40.42, -3.7)
        assertEquals(3, asked.size)
        assertEquals(3, asked.toSet().size)
    }

    /** And neither of the two things written down goes into a backup. */
    @Test
    fun `what the sky was doing is not a setting`() {
        clockAt(fetched)
        answering("open-meteo", "met.no", "wttr.in")
        WeatherStore.refresh(context, 40.42, -3.7)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val written = prefs.all.keys.filter { it.startsWith("pref_weather_") }
        assertEquals(setOf("pref_weather_cache", "pref_weather_forecast"), written.toSet())
        assertEquals(written.toSet(), WeatherStore.KEPT)
        val backup = Backup.export(context)
        for (key in written) assertFalse("$key is in the backup", key in backup)
        assertTrue("the switch itself is not in the backup", Prefs.WEATHER in backup)
    }
}
