package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The weather's card.
 *
 * A view, held against the same morning as [WeatherForecastTest] — the
 * three services' real answers for Madrid at 10:53 in Greenwich on the
 * 11th of November 2026 — with its clock stopped at that minute, so that
 * what it says is checked against what they sent, and what it draws is
 * looked at as well as counted. How the card is reached and left is the
 * app's business and is in [WeatherDoorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherCardTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    /** When the three answers were fetched, and the hour that minute was in. */
    private val fetched = 1_794_394_404_000L
    private val ten = 1_794_391_200_000L
    private val hourMs = 3_600_000L

    /** Where the card is: handed to it, and never to the machine running the test. */
    private val madrid: TimeZone = TimeZone.getTimeZone("Europe/Madrid")
    private val real = WeatherStore.fetch
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun saved(name: String): String =
        javaClass.getResource("/weather/$name")!!.readText()

    private val openMeteo by lazy { saved("open-meteo.json") }
    private val metNorway by lazy { saved("met-norway.json") }
    private val wttr by lazy { saved("wttr.json") }

    @Before
    fun settle() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.ANALOG.key)
            .commit()
        AlarmStore.forget()
        AlarmStore.all(context).clear()
        AlarmStore.save(context)
        WeatherStore.forget(context)
        // Nobody answers unless a test says who does: a test of where a
        // tap goes must not be asking three real services about Madrid.
        WeatherStore.fetch = WeatherStore.Fetch { null }
    }

    @After
    fun restore() {
        WeatherStore.fetch = real
        TimeKeeper.solarOffsetMs = 0L
    }

    /** A place for the weather to be asked about. */
    private fun located() {
        prefs.edit()
            .putFloat(Prefs.LAST_LATITUDE, 40.42f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.70f)
            .commit()
    }

    // ============================================================ the card

    /** That morning's sky, agreed by all three. */
    private fun morningSky(): Weather.Sky = Weather.agree(
        listOf(
            WeatherSources.OPEN_METEO.read(openMeteo, fetched)!!,
            WeatherSources.MET_NORWAY.read(metNorway, fetched)!!,
            WeatherSources.WTTR.read(wttr, fetched)!!
        ),
        fetched
    )

    /** And the hours after it, from both services that send them or from one. */
    private fun morningHours(both: Boolean = true): List<Weather.AgreedHour> = Weather.forecast(
        listOfNotNull(
            WeatherSources.OPEN_METEO.hours(openMeteo),
            WeatherSources.MET_NORWAY.hours(metNorway).takeIf { both }
        ),
        fetched, WeatherStore.FORECAST_HOURS
    )

    /** A card with that morning on it, its clock stopped at the minute of the fetch. */
    private fun card(
        sky: Weather.Sky = morningSky(),
        hours: List<Weather.AgreedHour> = morningHours(),
        missing: WeatherCardView.Missing = WeatherCardView.Missing.NOTHING,
        at: Long = fetched,
        width: Int = 1080,
        height: Int = 2200,
        fix: Boolean = true
    ): WeatherCardView {
        // Somewhere to stand, so that the sun is up when it is.
        if (fix) {
            located()
        } else {
            prefs.edit().remove(Prefs.LAST_LATITUDE).remove(Prefs.LAST_LONGITUDE).commit()
        }
        DayNight.configure(context)
        val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_WeirdClock)
        return WeatherCardView(themed).apply {
            zone = madrid
            atMs = at
            this.sky = sky
            this.hours = hours
            this.missing = missing
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, width, height)
        }
    }

    private fun picture(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("SHOT $name ${bitmap.width}x${bitmap.height}")
    }

    /** How many pixels in a band of the picture are not the card's own colour. */
    private fun inked(
        bitmap: Bitmap, fromShare: Float, toShare: Float, face: Int,
        across: ClosedFloatingPointRange<Float> = 0.04f..0.96f
    ): Int {
        var n = 0
        val from = (bitmap.height * fromShare).toInt()
        val to = (bitmap.height * toShare).toInt()
        for (y in from until to step 2) {
            for (x in (bitmap.width * across.start).toInt() until (bitmap.width * across.endInclusive).toInt() step 2) {
                val p = bitmap.getPixel(x, y)
                if (Color.alpha(p) > 0 && p != face) n++
            }
        }
        return n
    }

    /** How many pixels in a band are nearly [colour]: a thing drawn in it at full strength. */
    private fun coloured(
        bitmap: Bitmap, fromShare: Float, toShare: Float, colour: Int, slack: Int = 40,
        across: ClosedFloatingPointRange<Float> = 0f..1f
    ): Int {
        var n = 0
        val from = (bitmap.height * fromShare).toInt()
        val to = (bitmap.height * toShare).toInt()
        for (y in from until to) {
            for (x in (bitmap.width * across.start).toInt() until (bitmap.width * across.endInclusive).toInt()) {
                val p = bitmap.getPixel(x, y)
                if (abs(Color.red(p) - Color.red(colour)) < slack &&
                    abs(Color.green(p) - Color.green(colour)) < slack &&
                    abs(Color.blue(p) - Color.blue(colour)) < slack
                ) {
                    n++
                }
            }
        }
        return n
    }

    /**
     * What the top of the card says about that morning is what the three
     * services said.
     */
    @Test
    fun `the card says what the three services agreed on`() {
        val card = card()
        assertEquals("14°", card.degrees(card.sky.temperatureC.value))
        assertEquals(context.getString(R.string.weather_look_rain), card.word(Weather.look(card.sky)))
        assertEquals(
            listOf("13 km/h", "1.4 mm/h", "100 %", "1008 hPa"),
            card.readings().map { it.first }
        )
        assertEquals(
            listOf(
                context.getString(R.string.weather_wind), context.getString(R.string.weather_rain),
                context.getString(R.string.weather_cloud), context.getString(R.string.weather_pressure)
            ),
            card.readings().map { it.second }
        )
        assertEquals("3 of 3 services answered · just now", card.standing())
        assertFalse(card.old())
        // Somebody who cannot see it is told the same three things.
        assertEquals("Weather, 14°, Rain", card.contentDescription.toString())
        // And a number nobody sent is a dash, not a nought.
        val bare = card(sky = Weather.Sky(temperatureC = Weather.Agreed(3.4, Weather.Trust.LONE), answered = 1))
        assertEquals(listOf("–", "–", "–", "–"), bare.readings().map { it.first })
        assertEquals("3°", bare.degrees(bare.sky.temperatureC.value))
        assertEquals("--°", bare.degrees(null))
        // To the nearest degree, on both sides of nought.
        assertEquals("-7°", bare.degrees(-7.4))
        assertEquals("-8°", bare.degrees(-7.6))
        assertEquals("14°", bare.degrees(13.6))
    }

    /** How old the reading is, said in the largest unit that fits; and when it stops being said plainly. */
    @Test
    fun `the card says how old its reading is`() {
        fun at(minutes: Long) = card(at = fetched + minutes * 60_000L)
        assertEquals("just now", at(0).age())
        assertEquals("1 min ago", at(1).age())
        assertEquals("59 min ago", at(59).age())
        assertEquals("1 h ago", at(60).age())
        assertEquals("5 h ago", at(5 * 60 + 59).age())
        // A clock put back does not make a reading from the future.
        assertEquals("just now", at(-30).age())
        // Three hours is how long an answer counts for anything.
        assertFalse(at(180).old())
        assertTrue(at(181).old())
    }

    /**
     * The next day in eight steps of three hours, from the next hour to
     * strike: noon in Madrid, the fetch having been at 11:53 there.
     */
    @Test
    fun `the next day is eight steps of three hours from the next hour`() {
        val card = card()
        val steps = card.steps()
        assertEquals(8, steps.size)
        assertEquals(
            (0 until 8).map { ten + hourMs + it * 3 * hourMs }, steps.map { it.atMs }
        )
        assertEquals(listOf("12h", "15h", "18h", "21h", "0h", "3h", "6h", "9h"), steps.map { it.label })
        // The first, by hand: 14.5° and 13.0° at eleven in Greenwich;
        // 1.3 and 0.7, 1.2 and 1.1, 1.8 and 2.2 millimetres in the three
        // hours from it.
        assertEquals(13.75, steps[0].degrees!!, 0.0001)
        assertEquals(1.0 + 1.15 + 2.0, steps[0].rainMm, 0.0001)
        assertEquals(Weather.Look.RAIN, steps[0].look)
        assertTrue(steps[0].sure)
        // And every one of them against the hours it was made from.
        val hours = morningHours().associateBy { it.atMs }
        for (step in steps) {
            val block = (0 until 3).map { hours[step.atMs + it * hourMs]!! }
            assertEquals(block[0].temperatureC.value!!, step.degrees!!, 0.0001)
            assertEquals(block.sumOf { it.rainMm.value ?: 0.0 }, step.rainMm, 0.0001)
            assertEquals(block.maxOf { it.look }, step.look)
            assertEquals(block[0].sure, step.sure)
        }
        // Written the other way for a phone that keeps twelve hours.
        card.hour24 = false
        assertEquals(listOf("12p", "3p", "6p", "9p", "12a", "3a", "6a", "9a"), card.steps().map { it.label })
    }

    /** A step is the worst of its three hours, and nothing where its first hour is missing. */
    @Test
    fun `a step is the worst of its three hours`() {
        val next = ten + hourMs
        fun hour(i: Int, cloud: Double = 0.0, rain: Double = 0.0, thunder: Boolean = false, degrees: Double? = 10.0 + i) =
            Weather.AgreedHour(
                atMs = next + i * hourMs,
                temperatureC = if (degrees == null) Weather.Agreed.NOTHING else Weather.Agreed(degrees, Weather.Trust.AGREED),
                cloudPercent = Weather.Agreed(cloud, Weather.Trust.AGREED),
                rainMm = Weather.Agreed(rain, Weather.Trust.AGREED),
                thunder = thunder
            )
        val hours = listOf(
            // Clear, then one wet hour at the end of the step.
            hour(0), hour(1), hour(2, rain = 0.5),
            // Overcast in the middle hour only.
            hour(3), hour(4, cloud = 90.0), hour(5),
            // Lightning in the first.
            hour(6, thunder = true), hour(7), hour(8),
            // The first hour missing: no step, though the other two are there.
            hour(10), hour(11),
            // And one with no temperature in its first hour is still a step.
            hour(12, degrees = null, cloud = 60.0), hour(13), hour(14)
        )
        val steps = card(hours = hours).steps()
        assertEquals(listOf(0, 3, 6, 12).map { next + it * hourMs }, steps.map { it.atMs })
        assertEquals(
            listOf(Weather.Look.RAIN, Weather.Look.OVERCAST, Weather.Look.STORM, Weather.Look.CLOUDY),
            steps.map { it.look }
        )
        assertEquals(listOf(10.0, 13.0, 16.0, null), steps.map { it.degrees })
        assertEquals(listOf(0.5, 0.0, 0.0, 0.0), steps.map { it.rainMm })
        assertEquals(listOf(true, true, true, false), steps.map { it.sure })
    }

    /**
     * The four days after, in Madrid's own midnights: Thursday the 12th
     * to Sunday the 15th.
     */
    @Test
    fun `the four days after today run between this place's midnights`() {
        val card = card()
        val days = card.days()
        assertEquals(4, days.size)
        // Madrid is an hour ahead of Greenwich in November: its midnight
        // is eleven at night there.
        val midnight12 = ten + 13 * hourMs
        assertEquals((0 until 4).map { midnight12 + it * 24 * hourMs }, days.map { it.startMs })
        assertEquals(listOf("Thu", "Fri", "Sat", "Sun"), days.map { card.dayName(it.startMs) })
        val hours = morningHours()
        for (day in days) {
            val within = hours.filter { it.atMs in day.startMs until day.startMs + 24 * hourMs }
            assertEquals(24, within.size)
            assertEquals(within.mapNotNull { it.temperatureC.value }.min(), day.minC!!, 0.0001)
            assertEquals(within.mapNotNull { it.temperatureC.value }.max(), day.maxC!!, 0.0001)
            assertEquals(within.sumOf { it.rainMm.value ?: 0.0 }, day.rainMm, 0.0001)
        }
        // The same hours somewhere eight hours behind are other days.
        card.zone = TimeZone.getTimeZone("America/Los_Angeles")
        val pacific = card.days()
        assertEquals(midnight12 + 9 * hourMs, pacific.first().startMs)
        assertEquals("Thu", card.dayName(pacific.first().startMs))
    }

    /**
     * A day the clocks change in is twenty-three hours long, and the days
     * are still whole.
     *
     * The clocks go forward in Madrid on the 28th of March 2027. A card
     * that counted its days in twenty-fours would start every day after
     * it an hour late.
     */
    @Test
    fun `the days are counted on the calendar and not in twenty-fours`() {
        fun utc(text: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)!!.time
        // Noon on Friday the 26th, Madrid still an hour ahead.
        val now = utc("2027-03-26 11:00")
        val hours = (0 until 120).map { i ->
            Weather.AgreedHour(
                atMs = now + i * hourMs,
                temperatureC = Weather.Agreed(i.toDouble(), Weather.Trust.AGREED)
            )
        }
        val card = card(hours = hours, at = now)
        val days = card.days()
        assertEquals(
            listOf(
                utc("2027-03-26 23:00"), utc("2027-03-27 23:00"),
                // Sunday is the short one, and Monday starts at ten.
                utc("2027-03-28 22:00"), utc("2027-03-29 22:00")
            ),
            days.map { it.startMs }
        )
        // Twenty-three hours on the day itself: 36 to 58, and no more.
        assertEquals(36.0, days[1].minC!!, 0.0001)
        assertEquals(58.0, days[1].maxC!!, 0.0001)
        assertEquals(59.0, days[2].minC!!, 0.0001)
    }

    /**
     * Every part of the card is drawn, in the theme's own colours, and
     * the rain is the colour the dial keeps for the dark side.
     */
    @Test
    fun `every part of the card is drawn`() {
        val card = card()
        val theme = card.theme
        val bitmap = picture(card)
        save(bitmap, "weather-card-rain")
        // The card itself, where there is nothing on it.
        assertEquals(theme.face, bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.905f).toInt()))
        val bands = mapOf(
            "the token and the temperature" to (0.05f to 0.24f),
            "the four readings" to (0.27f to 0.32f),
            "the next day" to (0.36f to 0.55f),
            "the days after" to (0.58f to 0.80f),
            "who was asked" to (0.83f to 0.90f)
        )
        for ((what, band) in bands) {
            val ink = inked(bitmap, band.first, band.second, theme.face)
            println("CARD ink in $what: $ink")
            assertTrue("$what: nothing drawn ($ink)", ink > 150)
        }
        // Rain in the dark side's blue, under the steps and in the days.
        val stepsRain = coloured(bitmap, 0.50f, 0.56f, theme.pmMark)
        val daysRain = coloured(bitmap, 0.58f, 0.80f, theme.pmMark)
        println("CARD blue under the steps $stepsRain, in the days $daysRain")
        assertTrue("no rain is written under the steps", stepsRain > 60)
        assertTrue("no rain is written beside the days", daysRain > 60)
        // And the four bars, in the sun's yellow.
        val bars = coloured(bitmap, 0.58f, 0.80f, theme.sunMark)
        assertTrue("the days have no bars ($bars)", bars > 400)
        // None of that above the first rule.
        assertEquals(0, coloured(bitmap, 0.0f, 0.33f, theme.pmMark))
        assertEquals(0, coloured(bitmap, 0.0f, 0.33f, theme.sunMark))
    }

    /**
     * The sun by day and the moon by night, at the top and in every step.
     *
     * The token is the dial's, so it keeps the dial's hours: at 11:53 in
     * Madrid it has rays, and twelve hours later it has none. Asked of
     * one spot to the left of the disc, where a ray is and the cloud in
     * front of it never reaches.
     */
    @Test
    fun `the token is the sun by day and the moon by night`() {
        fun ray(card: WeatherCardView, x: Float, y: Float, radius: Float): Boolean {
            val bitmap = picture(card)
            val face = card.theme.face
            // Along the ray that points at nine o'clock, past where the
            // rim of a moon would be.
            return (108..116).any { hundredths ->
                bitmap.getPixel((x - radius * hundredths / 100f).toInt(), y.toInt()) != face
            }
        }
        val noon = card()
        val radius = noon.tokenRadiusForTest()
        assertTrue(
            "no rays at noon",
            ray(noon, noon.tokenXForTest(), noon.tokenYForTest(), radius)
        )
        val midnight = card(at = fetched + 12 * hourMs)
        assertFalse(
            "rays at midnight",
            ray(midnight, midnight.tokenXForTest(), midnight.tokenYForTest(), radius)
        )
        // And with nowhere to stand it is the moon at noon too, as it is
        // on the dial.
        val nowhere = card(fix = false)
        assertFalse(
            "a sun with nowhere to rise over",
            ray(nowhere, nowhere.tokenXForTest(), nowhere.tokenYForTest(), radius)
        )
    }

    /** A dry spell is drawn dry: no blue anywhere on the card. */
    @Test
    fun `a dry forecast has no rain written on it`() {
        val dry = morningHours().map {
            it.copy(rainMm = Weather.Agreed(0.0, Weather.Trust.AGREED), thunder = false)
        }
        val sky = morningSky().copy(
            rainMmPerHour = Weather.Agreed(0.0, Weather.Trust.AGREED),
            cloudPercent = Weather.Agreed(20.0, Weather.Trust.AGREED)
        )
        val card = card(sky = sky, hours = dry)
        val bitmap = picture(card)
        save(bitmap, "weather-card-dry")
        assertEquals(0, coloured(bitmap, 0.0f, 1.0f, card.theme.pmMark))
        assertEquals(context.getString(R.string.weather_look_clear), card.word(Weather.look(sky)))
        // A wet hour under the line of what is worth saying is not said.
        val damp = dry.map { it.copy(rainMm = Weather.Agreed(0.05, Weather.Trust.AGREED)) }
        assertEquals(0, coloured(picture(card(sky = sky, hours = damp)), 0.34f, 0.57f, card.theme.pmMark))
    }

    /**
     * What only one service said is drawn faintly.
     *
     * The same hours from one service and from two, and the full-strength
     * ink counted: with one, the steps and the days have none of it in
     * their temperatures, where with two they are written plainly.
     */
    @Test
    fun `what nobody confirmed is drawn faintly`() {
        val two = card()
        val one = card(hours = morningHours(both = false))
        assertTrue(two.steps().all { it.sure })
        assertTrue(one.steps().none { it.sure })
        assertTrue(one.days().none { it.sure })
        val plain = picture(two)
        val faint = picture(one)
        save(faint, "weather-card-one-service")
        val numeral = two.theme.numeral
        // The band the eight temperatures ride in, and the warm end of
        // the four days: numbers, and none of the suns beside them, which
        // are the sun whoever said what.
        val bands = mapOf(
            "the steps" to Triple(0.455f, 0.51f, 0f..1f),
            "the days" to Triple(0.58f, 0.80f, 0.84f..0.97f)
        )
        for ((what, band) in bands) {
            val strong = coloured(plain, band.first, band.second, numeral, slack = 12, across = band.third)
            val weak = coloured(faint, band.first, band.second, numeral, slack = 12, across = band.third)
            println("CARD full-strength ink in $what: two services $strong, one $weak")
            assertTrue("$what are not written plainly with two services ($strong)", strong > 300)
            assertTrue("$what are as strong with one service as with two ($weak of $strong)", weak < strong / 4)
        }
        // And a reading too old to count is faint at the top as well.
        val stale = picture(card(at = fetched + 4 * hourMs))
        val fresh = coloured(plain, 0.05f, 0.24f, numeral, slack = 12)
        val old = coloured(stale, 0.05f, 0.24f, numeral, slack = 12)
        println("CARD full-strength ink at the top: fresh $fresh, four hours old $old")
        assertTrue("an old reading is written as plainly as a fresh one ($old of $fresh)", old < fresh / 2)
    }

    /**
     * With nothing to show the card says why, and when the why is the
     * switch it offers the switch.
     */
    @Test
    fun `with no weather the card says why`() {
        val off = card(missing = WeatherCardView.Missing.OFF)
        val nowhere = card(missing = WeatherCardView.Missing.NOWHERE)
        val waiting = card(missing = WeatherCardView.Missing.WAITING)
        assertEquals(context.getString(R.string.weather_card_off), off.apology())
        assertEquals(context.getString(R.string.weather_card_nowhere), nowhere.apology())
        assertEquals(context.getString(R.string.weather_card_waiting), waiting.apology())
        assertEquals("", card().apology())
        // What turning it on will do, in the settings' own words: the
        // three services by name, the kilometre, the half hour.
        for (said in listOf("Open-Meteo", "MET Norway", "wttr.in", "kilometre", "half hour", "network")) {
            assertTrue("the card does not say \"$said\" before offering the switch", said in off.apology())
        }
        assertTrue(off.contentDescription.toString().startsWith("Weather. The weather is off"))

        val theme = off.theme
        val pictures = mapOf("off" to picture(off), "nowhere" to picture(nowhere), "waiting" to picture(waiting))
        for ((name, bitmap) in pictures) {
            save(bitmap, "weather-card-$name")
            assertTrue("$name: no words", inked(bitmap, 0.24f, 0.47f, theme.face) > 150)
            // The token, in the middle, and nothing where a temperature
            // would be: two dashes are a card that looks broken.
            assertTrue("$name: no token", inked(bitmap, 0.06f, 0.20f, theme.face) > 100)
            assertEquals(
                "$name: something where the temperature goes",
                0, inked(bitmap, 0.05f, 0.24f, theme.face, across = 0.62f..0.96f)
            )
            // Nothing of a forecast: no rain, no bars, nobody credited
            // with an answer they did not give.
            assertEquals(0, coloured(bitmap, 0.0f, 1.0f, theme.pmMark))
            assertEquals(0, coloured(bitmap, 0.0f, 1.0f, theme.sunMark))
            assertEquals("$name: something under the words", 0, inked(bitmap, 0.70f, 0.92f, theme.face))
        }
        // The switch is drawn on the one that is off and on no other.
        val where = (off.switchYForTest() / off.height)
        assertTrue(inked(pictures["off"]!!, where - 0.02f, where + 0.02f, theme.face) > 150)
        assertEquals(0, inked(pictures["nowhere"]!!, where - 0.02f, where + 0.02f, theme.face))
        assertEquals(0, inked(pictures["waiting"]!!, where - 0.02f, where + 0.02f, theme.face))
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val at = android.os.SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(at, at, action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun press(view: View, x: Float, y: Float) {
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
    }

    /** The token at the top is a way out, the switch is the switch, and nothing else is anything. */
    @Test
    fun `the token leaves, the switch switches, and the rest of the card is paper`() {
        var left = 0
        var switched = 0
        val card = card(missing = WeatherCardView.Missing.OFF).apply {
            onLeave = { left++ }
            onSwitchOn = { switched++ }
        }
        press(card, card.tokenXForTest(), card.tokenYForTest())
        assertEquals("the token did not leave", 1, left)
        assertEquals(0, switched)
        press(card, card.switchXForTest(), card.switchYForTest())
        assertEquals("the switch did not switch", 1, switched)
        assertEquals(1, left)
        // Paper: the temperature, the words, the foot of the card.
        for ((x, y) in listOf(0.66f to 0.14f, 0.5f to 0.40f, 0.5f to 0.85f, 0.05f to 0.5f)) {
            press(card, card.width * x, card.height * y)
        }
        assertEquals(1, left)
        assertEquals(1, switched)
        // A finger that comes down on the token and lifts somewhere else
        // has changed its mind — even if where it lifts is the switch.
        touch(card, MotionEvent.ACTION_DOWN, card.tokenXForTest(), card.tokenYForTest())
        touch(card, MotionEvent.ACTION_UP, card.width * 0.5f, card.height * 0.5f)
        assertEquals(1, left)
        touch(card, MotionEvent.ACTION_DOWN, card.tokenXForTest(), card.tokenYForTest())
        touch(card, MotionEvent.ACTION_UP, card.switchXForTest(), card.switchYForTest())
        assertEquals(1, left)
        assertEquals(1, switched)
        touch(card, MotionEvent.ACTION_DOWN, card.switchXForTest(), card.switchYForTest())
        touch(card, MotionEvent.ACTION_UP, card.tokenXForTest(), card.tokenYForTest())
        assertEquals(1, left)
        assertEquals(1, switched)
        // With nothing but the token at the top, the token is in the
        // middle; with a temperature beside it, it is to the left.
        assertEquals(card.width / 2f, card.tokenXForTest(), 1f)
        assertTrue(card().tokenXForTest() < card.width * 0.35f)
        // And with the weather there, where the switch was is paper too.
        val known = card().apply { onSwitchOn = { switched++ } }
        press(known, known.switchXForTest(), known.switchYForTest())
        assertEquals(1, switched)
    }

    /** The card is the same card lying on its side: nothing off the edge, nothing on top of anything. */
    @Test
    fun `the card fits a phone on its side`() {
        val wide = card(width = 2200, height = 1000)
        val bitmap = picture(wide)
        save(bitmap, "weather-card-sideways")
        val theme = wide.theme
        // All of it inside the card: the days' bars at the bottom and the
        // token at the top, with clear paper above and below.
        assertTrue(coloured(bitmap, 0.05f, 0.90f, theme.sunMark) > 100)
        assertEquals(0, inked(bitmap, 0.0f, 0.012f, theme.face))
        assertTrue(wide.tokenYForTest() > wide.height * 0.03f)
        assertTrue(wide.tokenXForTest() in wide.width * 0.2f..wide.width * 0.8f)
    }
}
