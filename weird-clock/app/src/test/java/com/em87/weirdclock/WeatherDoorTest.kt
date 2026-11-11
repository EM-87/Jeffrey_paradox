package com.em87.weirdclock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The token on the dial, and where it goes.
 *
 * It used to open the solar system, and it opens the weather's card now.
 * So this is tried on the app and not on a view: the token pressed as a
 * finger presses it, through the dial's own gesture detector; the card
 * left three ways; the two pictures on the home screen followed to where
 * they lead. Much of it is about the old room — a door that has been
 * given to somebody else has to be shown not to open it any more.
 *
 * The app's own clock cannot be stopped from a test, because applying
 * the settings puts it back, so the services here answer about whatever
 * hour the test is run in — see [answersFromNow]. The card's own
 * arithmetic is held against a real morning in [WeatherCardTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherDoorTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val hourMs = 3_600_000L
    private val real = WeatherStore.fetch
    private val outDir = File("build/screenshots").apply { mkdirs() }

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


    /** A place for the weather to be asked about. */
    private fun located() {
        prefs.edit()
            .putFloat(Prefs.LAST_LATITUDE, 40.42f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.70f)
            .commit()
    }

    private fun stamp(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(atMs))

    /**
     * Three services' answers about *now*, whenever the test is run.
     *
     * The app's own clock cannot be stopped from here — applying the
     * settings puts it back — so what the app is given is a forecast that
     * starts at the hour it really is: steady rain, twelve degrees and a
     * degree warmer every hour, from two services that agree.
     */
    private fun answersFromNow(): (String) -> String? {
        val hour0 = Math.floorDiv(System.currentTimeMillis(), hourMs) * hourMs
        val n = 144
        val times = (0 until n).joinToString(",") { ((hour0 + it * hourMs) / 1000L).toString() }
        val degrees = (0 until n).joinToString(",") { (12.0 + it % 24).toString() }
        val openMeteo = """{"current":{"interval":900,"temperature_2m":12.0,"pressure_msl":1009.0,
            "cloud_cover":100,"precipitation":0.5,"wind_speed_10m":20.0,"weather_code":61},
            "hourly":{"time":[$times],"temperature_2m":[$degrees],
            "cloud_cover":[${(0 until n).joinToString(",") { "100" }}],
            "precipitation":[${(0 until n).joinToString(",") { "2.0" }}],
            "weather_code":[${(0 until n).joinToString(",") { "61" }}]}}"""
        val series = (0 until n).joinToString(",") { i ->
            """{"time":"${stamp(hour0 + i * hourMs)}","data":{"instant":{"details":{
                "air_pressure_at_sea_level":1009.4,"air_temperature":${12.4 + i % 24},
                "cloud_area_fraction":98.0,"wind_speed":5.5}},
                "next_1_hours":{"summary":{"symbol_code":"rain"},"details":{"precipitation_amount":2.2}}}}"""
        }
        val metNorway = """{"properties":{"timeseries":[$series]}}"""
        val wttr = """{"current_condition":[{"cloudcover":"100","precipMM":"2.1","pressure":"1009",
            "temp_C":"12","weatherCode":"296","windspeedKmph":"20"}]}"""
        return { url ->
            when {
                "open-meteo" in url -> openMeteo
                "met.no" in url -> metNorway
                "wttr.in" in url -> wttr
                else -> null
            }
        }
    }

    /** The weather switched on, a place known, and an answer already written down. */
    private fun withWeather() {
        located()
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        val answers = answersFromNow()
        WeatherStore.fetch = WeatherStore.Fetch { answers(it) }
        assertTrue(WeatherStore.refresh(context, 40.42, -3.70).known)
        // And from here nobody is asked again unless the test says so.
        WeatherStore.fetch = WeatherStore.Fetch { null }
    }

    private fun idle() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    /** A whole tap on the dial's token, taken through its gesture detector. */
    private fun tapToken(app: MainActivity) {
        val clock = app.clockForTest()
        // In the view's own coordinates, straight to the dial.
        val at = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(at, at, action, clock.skyTokenXForTest(), clock.skyTokenYForTest(), 0)
            clock.onTouchEvent(e)
            e.recycle()
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(400))
    }

    /**
     * The token on the dial opens the weather's card, and does not open
     * the solar system.
     *
     * Pressed as a finger presses it, through the dial's own gesture
     * detector. With the solar system's old switch on, to be sure it is
     * the switch that has stopped mattering and not the test that forgot
     * to set it.
     */
    @Test
    fun `the token on the dial opens the weather and not the sky`() {
        withWeather()
        prefs.edit().putBoolean(Prefs.ORRERY, true).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            assertEquals(Card.CLOCK, app.showingCardForTest())
            val clock = app.clockForTest()
            assertTrue("there is no token on the dial", clock.showMoonPhase)
            assertEquals(Weather.Look.RAIN, clock.weather)

            tapToken(app)
            assertEquals("the token did not open the weather's card", Card.WEATHER, app.showingCardForTest())
            assertFalse("the token opened the solar system", clock.orreryShowing())
            assertEquals(View.VISIBLE, app.findViewById<View>(R.id.weather_container).visibility)
            assertEquals(
                "the month is showing under the weather",
                View.GONE, app.findViewById<View>(R.id.calendar_container).visibility
            )
            // And the card has that weather on it.
            val card = app.weatherCardForTest()!!
            assertEquals(WeatherCardView.Missing.NOTHING, card.missing)
            assertEquals(12.0, card.sky.temperatureC.value!!, 0.01)
            assertEquals(3, card.sky.answered)
            assertEquals(8, card.steps().size)
            assertEquals(4, card.days().size)
            assertEquals(clock.theme, card.theme)
            save(picture(app.findViewById(R.id.weather_container)), "weather-card-in-the-app")
        }
    }

    /** Three ways back to the clock: the token on the card, the button under it, and Back. */
    @Test
    fun `the card is left by its token, by its button and by going back`() {
        withWeather()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            val card = app.weatherCardForTest()!!

            tapToken(app)
            assertEquals(Card.WEATHER, app.showingCardForTest())
            press(card, card.tokenXForTest(), card.tokenYForTest())
            idle()
            assertEquals("the card's token did not go back", Card.CLOCK, app.showingCardForTest())
            assertEquals(View.GONE, app.findViewById<View>(R.id.weather_container).visibility)

            tapToken(app)
            assertEquals(Card.WEATHER, app.showingCardForTest())
            app.findViewById<View>(R.id.weather_back_button).performClick()
            idle()
            assertEquals("the clock button did not go back", Card.CLOCK, app.showingCardForTest())

            tapToken(app)
            assertEquals(Card.WEATHER, app.showingCardForTest())
            app.onBackPressedDispatcher.onBackPressed()
            idle()
            assertEquals("Back did not go back", Card.CLOCK, app.showingCardForTest())
            assertFalse("Back closed the app from the weather", app.isFinishing)
        }
    }

    /** The token and the phase on it, for each way the two switches can be set. */
    private fun token(weather: Boolean, moon: Boolean, sky: Boolean = false): Triple<Boolean, Boolean, Boolean> {
        prefs.edit()
            .putBoolean(Prefs.WEATHER, weather)
            .putBoolean(Prefs.MOON_PHASE, moon)
            .putBoolean(Prefs.ORRERY, sky)
            .commit()
        return Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val clock = c.get().clockForTest()
            Triple(
                clock.showMoonPhase,
                clock.moonPhaseShown,
                clock.skyTokenAt(clock.skyTokenXForTest(), clock.skyTokenYForTest())
            )
        }
    }

    /**
     * The token is on the dial for the weather or for the moon, and is a
     * door whenever it is there.
     *
     * It was there for the solar system or for the moon. The weather has
     * the solar system's place in that rule and the solar system's old
     * switch has none: with it on and the other two off there is no token
     * at all.
     */
    @Test
    fun `the token is there for the weather or for the moon`() {
        located()
        assertEquals("neither", Triple(false, false, false), token(weather = false, moon = false))
        assertEquals("the old switch alone", Triple(false, false, false), token(weather = false, moon = false, sky = true))
        // For the weather alone it is the sun or a plain moon; the phase
        // is the moon's own switch.
        assertEquals("the weather alone", Triple(true, false, true), token(weather = true, moon = false))
        assertEquals("the moon alone", Triple(true, true, true), token(weather = false, moon = true))
        assertEquals("both", Triple(true, true, true), token(weather = true, moon = true))
        // And the widget's dial is dressed on the same terms.
        prefs.edit().putBoolean(Prefs.WEATHER, true).putBoolean(Prefs.MOON_PHASE, false).commit()
        assertTrue(DialSettings.skyTokenWanted(prefs))
        assertFalse(DialSettings.moonPhaseWanted(prefs))
        prefs.edit().putBoolean(Prefs.WEATHER, false).putBoolean(Prefs.ORRERY, true).commit()
        assertFalse(DialSettings.skyTokenWanted(prefs))
    }

    /**
     * A dial borrowed to set a time is not on its way anywhere: the token
     * is there, as the sun or the moon the hands are being wound under,
     * and it is not a door until the time has been set.
     */
    @Test
    fun `while a time is being wound the token is not a door`() {
        withWeather()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            val clock = app.clockForTest()
            assertTrue(clock.skyTokenAt(clock.skyTokenXForTest(), clock.skyTokenYForTest()))
            app.windAlarmForTest(7, 30)
            idle()
            assertTrue("the sky went off the dial while a time was being wound", clock.showMoonPhase)
            assertFalse(
                "the token is a door while a time is being wound",
                clock.skyTokenAt(clock.skyTokenXForTest(), clock.skyTokenYForTest())
            )
            tapToken(app)
            assertEquals(Card.CLOCK, app.showingCardForTest())
            app.confirmAlarmSetForTest()
            idle()
            assertTrue(
                "the token did not go back to being a door",
                clock.skyTokenAt(clock.skyTokenXForTest(), clock.skyTokenYForTest())
            )
        }
        // And with neither switch on, the sky is on the dial for as long
        // as a time is being wound and no longer: it is what says which
        // seven the hands have landed on.
        prefs.edit().putBoolean(Prefs.WEATHER, false).putBoolean(Prefs.MOON_PHASE, false).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            val clock = app.clockForTest()
            assertFalse(clock.showMoonPhase)
            app.windAlarmForTest(7, 30)
            idle()
            assertTrue("no sky on the dial while a time is being wound", clock.showMoonPhase)
            assertFalse(clock.skyTokenAt(clock.skyTokenXForTest(), clock.skyTokenYForTest()))
            app.confirmAlarmSetForTest()
            idle()
            assertFalse("the sky stayed on the dial after the time was set", clock.showMoonPhase)
        }
    }

    /**
     * Coming to the card reads what is written down again, and asks
     * again if that is old.
     *
     * The card is dressed when the settings are applied, which is before
     * anybody has come to look at it — an hour before, or a day. So it
     * is dressed again on arrival: with whatever has been written down
     * since, and with a round of asking behind it if what is written
     * down is more than half an hour old.
     */
    @Test
    fun `coming to the card reads the weather again and asks if it is old`() {
        located()
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            val card = app.weatherCardForTest()!!
            // Nobody has answered, and nobody is going to on this
            // thread: the fetch in the fixture says nothing.
            until("the first round to give up") { card.missing == WeatherCardView.Missing.WAITING }
            Thread.sleep(100)
            idle()

            // Something else writes an answer down behind the app's back
            // — the widget, say — and then the card is visited.
            val answers = answersFromNow()
            WeatherStore.fetch = WeatherStore.Fetch { answers(it) }
            WeatherStore.refresh(context, 40.42, -3.70)
            val asked = AtomicInteger()
            WeatherStore.fetch = WeatherStore.Fetch { url -> asked.incrementAndGet(); answers(url) }
            assertEquals(WeatherCardView.Missing.WAITING, card.missing)
            tapToken(app)
            assertEquals(
                "the card was not dressed again on arrival",
                WeatherCardView.Missing.NOTHING, card.missing
            )
            assertEquals(12.0, card.sky.temperatureC.value!!, 0.01)
            Thread.sleep(150)
            idle()
            assertEquals("a fresh answer was asked for again", 0, asked.get())

            // Back to the clock, forty minutes pass, and the card is
            // visited again.
            app.goToForTest(Card.CLOCK)
            idle()
            TimeKeeper.solarOffsetMs = 40 * 60_000L
            tapToken(app)
            until("an old answer to be asked about again") { asked.get() >= 3 }
        }
    }

    /**
     * The dial on the home screen wears the token on the dial's own
     * terms: for the weather alone as well as for the moon.
     */
    @Test
    fun `the home screen's dial has the token for the weather too`() {
        withWeather()
        fun differ(a: Bitmap, b: Bitmap): Int {
            var n = 0
            for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
            return n
        }
        prefs.edit().putBoolean(Prefs.WEATHER, false).putBoolean(Prefs.MOON_PHASE, false).commit()
        val bare = WidgetRenderer.dialBitmap(context, 400)
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        val wet = WidgetRenderer.dialBitmap(context, 400)
        assertTrue(
            "the weather is on and the home screen's dial has no token for it",
            differ(bare, wet) > 40
        )
        // The old switch puts nothing there.
        prefs.edit().putBoolean(Prefs.WEATHER, false).putBoolean(Prefs.ORRERY, true).commit()
        assertEquals(0, differ(bare, WidgetRenderer.dialBitmap(context, 400)))
    }

    /** Only the dial has the card: the other three faces have neither it nor a way to it. */
    @Test
    fun `no other face has the weather's card`() {
        withWeather()
        for (face in Face.entries.filter { it != Face.ANALOG }) {
            prefs.edit().putString(Prefs.FACE, face.key).commit()
            Robolectric.buildActivity(MainActivity::class.java).use { c ->
                c.setup()
                val app = c.get()
                idle()
                app.goToForTest(Card.WEATHER)
                idle()
                assertEquals("$face went to a card it has not got", Card.CLOCK, app.showingCardForTest())
                // The page it is on exists on these three — the month and
                // the solar system live there — and the card is put away.
                app.goToForTest(Card.CALENDAR)
                idle()
                val container = app.findViewById<View>(R.id.weather_container)
                assertNotNull("$face never built the page the card is on", container)
                assertEquals("$face shows the weather's card", View.GONE, container.visibility)
            }
        }
    }

    // ------------------------------------------------- the pictures at home

    /** The weather on the home screen leads to the weather's card, where the face has one. */
    @Test
    fun `the weather widget opens the weather's card`() {
        withWeather()
        val asked = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_WEATHER, true)
        Robolectric.buildActivity(MainActivity::class.java, asked).use { c ->
            c.setup()
            idle()
            assertEquals(Card.WEATHER, c.get().showingCardForTest())
        }
        // Already open on the clock, and asked again from the home screen.
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            idle()
            assertEquals(Card.CLOCK, c.get().showingCardForTest())
            c.newIntent(asked)
            idle()
            assertEquals(Card.WEATHER, c.get().showingCardForTest())
        }
        // On a face with no such card it is the clock, and not a blank page.
        prefs.edit().putString(Prefs.FACE, Face.DIGITAL.key).commit()
        Robolectric.buildActivity(MainActivity::class.java, asked).use { c ->
            c.setup()
            idle()
            assertEquals(Card.CLOCK, c.get().showingCardForTest())
        }
        // And that is what the widget itself asks for when it is pressed.
        val views = WeatherWidgetProvider.viewsForTest(context, 1)
        val root = views.apply(context, android.widget.FrameLayout(context))
        val image = root.findViewById<View>(R.id.widget_weather_image)
        image.performClick()
        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull("pressing the weather on the home screen started nothing", started)
        assertTrue(
            "the weather on the home screen does not ask for the weather's card",
            started.getBooleanExtra(MainActivity.EXTRA_OPEN_WEATHER, false)
        )
    }

    /**
     * The picture of the solar system on the home screen asks for the
     * face that has the solar system, and arrives on its card.
     *
     * It used to open the sky on the dial, behind the token. On a phone
     * that keeps the dial it now borrows the turning world for the visit,
     * the way a widget pinned to a clock borrows its clock.
     */
    @Test
    fun `the solar system's picture opens the solar system's card`() {
        val views = OrreryWidgetProvider.viewsForTest(context, 1)
        val root = views.apply(context, android.widget.FrameLayout(context))
        root.findViewById<View>(R.id.widget_orrery_image).performClick()
        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull("pressing the solar system on the home screen started nothing", started)
        assertTrue(started.getBooleanExtra(MainActivity.EXTRA_OPEN_SKY, false))
        assertEquals(Face.HEMISPHERE.key, started.getStringExtra(MainActivity.EXTRA_SHOW_FACE))

        // On a phone that keeps the dial.
        Robolectric.buildActivity(MainActivity::class.java, started).use { c ->
            c.setup()
            idle()
            val app = c.get()
            assertEquals(Face.HEMISPHERE, app.faceForTest())
            assertEquals("the stored face was changed", Face.ANALOG, Face.of(prefs))
            assertEquals(Card.CALENDAR, app.showingCardForTest())
            val sky = app.orreryCardForTest()
            assertNotNull("the turning world has no card for the solar system", sky)
            assertTrue("the card is not showing the planets", sky!!.orreryShowing())
        }
        // On one that keeps the turning world, open on the globe and
        // asked again: the card, and not the globe it was already on.
        prefs.edit().putString(Prefs.FACE, Face.HEMISPHERE.key).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            idle()
            assertEquals(Card.CLOCK, c.get().showingCardForTest())
            c.newIntent(started)
            idle()
            assertEquals(Card.CALENDAR, c.get().showingCardForTest())
        }
        // And without the face that has it there is nowhere to go: a
        // request for the sky alone, on the dial, stays on the clock with
        // the sky shut.
        prefs.edit().putString(Prefs.FACE, Face.ANALOG.key).putBoolean(Prefs.ORRERY, true).commit()
        val bare = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SKY, true)
        Robolectric.buildActivity(MainActivity::class.java, bare).use { c ->
            c.setup()
            idle()
            assertEquals(Card.CLOCK, c.get().showingCardForTest())
            assertFalse(c.get().clockForTest().orreryShowing())
        }
    }

    // ------------------------------------------------- the switch, and asking

    /** Waits for a round of asking on its own thread to land on the main one. */
    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (done()) return
            Thread.sleep(20)
        }
        throw AssertionError("gave up waiting for $what")
    }

    /**
     * Off, the card says so, asks nobody, and offers the switch; the
     * switch turns the weather on, asks, and draws what comes back.
     */
    @Test
    fun `off, the card asks nobody and offers the switch`() {
        located()
        // The moon puts the token there; the weather is off.
        prefs.edit().putBoolean(Prefs.MOON_PHASE, true).commit()
        val asked = AtomicInteger()
        val answers = answersFromNow()
        WeatherStore.fetch = WeatherStore.Fetch { url -> asked.incrementAndGet(); answers(url) }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            tapToken(app)
            assertEquals(Card.WEATHER, app.showingCardForTest())
            val card = app.weatherCardForTest()!!
            assertEquals(WeatherCardView.Missing.OFF, card.missing)
            Thread.sleep(150)
            idle()
            assertEquals("the weather is off and somebody was asked", 0, asked.get())
            assertFalse(prefs.getBoolean(Prefs.WEATHER, false))

            press(card, card.switchXForTest(), card.switchYForTest())
            assertTrue("the switch on the card did not turn the weather on", prefs.getBoolean(Prefs.WEATHER, false))
            until("the answers to be drawn") { card.missing == WeatherCardView.Missing.NOTHING }
            assertEquals("each of the three was not asked once", 3, asked.get())
            assertEquals(12.0, card.sky.temperatureC.value!!, 0.01)
            assertEquals(8, card.steps().size)
            // And the dial got it too, without being opened again.
            assertEquals(Weather.Look.RAIN, app.clockForTest().weather)
            assertEquals(Card.WEATHER, app.showingCardForTest())
        }
    }

    /** With nowhere to ask about, the card says so and asks nobody; with nobody answering, it waits. */
    @Test
    fun `with no place the card says so, and with no answer it waits`() {
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        val asked = AtomicInteger()
        WeatherStore.fetch = WeatherStore.Fetch { asked.incrementAndGet(); null }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            tapToken(app)
            assertEquals(WeatherCardView.Missing.NOWHERE, app.weatherCardForTest()!!.missing)
            Thread.sleep(150)
            assertEquals("asked about nowhere", 0, asked.get())
        }
        located()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            tapToken(app)
            assertEquals(WeatherCardView.Missing.WAITING, app.weatherCardForTest()!!.missing)
            until("the three to be asked") { asked.get() >= 3 }
            idle()
            assertEquals(WeatherCardView.Missing.WAITING, app.weatherCardForTest()!!.missing)
        }
    }

    /**
     * An answer that lands while the clock is on screen is drawn when it
     * lands.
     *
     * Nothing used to be told. The dial was dressed from what was written
     * down when the settings were applied, the asking went off on a
     * thread of its own, and what it brought back was on the dial the
     * next time the app was opened.
     */
    @Test
    fun `an answer that lands is drawn when it lands`() {
        located()
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        val gate = CountDownLatch(1)
        val answers = answersFromNow()
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            gate.await(10, TimeUnit.SECONDS)
            answers(url)
        }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val app = c.get()
            idle()
            val clock = app.clockForTest()
            assertNull("the dial has weather nobody has sent yet", clock.weather)
            gate.countDown()
            until("the dial to be told") { clock.weather == Weather.Look.RAIN }
            assertEquals(WeatherCardView.Missing.NOTHING, app.weatherCardForTest()!!.missing)
        }
    }

    /**
     * One round of asking at a time, and everybody who asked is told.
     *
     * The settings are applied several times as the app opens, and each
     * time used to start a round of its own: three services asked three
     * times over in the same second.
     */
    @Test
    fun `three requests in a row are one round of asking`() {
        located()
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        val gate = CountDownLatch(1)
        val asked = AtomicInteger()
        val answers = answersFromNow()
        WeatherStore.fetch = WeatherStore.Fetch { url ->
            asked.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
            answers(url)
        }
        val told = AtomicInteger()
        repeat(3) { WeatherStore.refreshInBackground(context, 40.42, -3.70) { told.incrementAndGet() } }
        Thread.sleep(100)
        assertEquals("nobody is told before the answers are in", 0, told.get())
        gate.countDown()
        until("everybody to be told") { told.get() == 3 }
        assertEquals("the three services were asked more than once each", 3, asked.get())
        // And with a fresh answer written down, nobody is asked and
        // nobody needs telling.
        WeatherStore.refreshInBackground(context, 40.42, -3.70) { told.incrementAndGet() }
        Thread.sleep(100)
        idle()
        assertEquals(3, asked.get())
        assertEquals(3, told.get())
        // The round is over: a later one can start.
        prefs.edit().remove("pref_weather_cache").remove("pref_weather_forecast").commit()
        WeatherStore.refreshInBackground(context, 40.42, -3.70) { told.incrementAndGet() }
        until("the next round") { told.get() == 4 }
        assertEquals(6, asked.get())
    }

    /**
     * A sky with no hours beside it is asked about again, and not every
     * time the clock is looked at.
     *
     * That is what a phone has that had the weather on before there were
     * hours to keep — and also what it has when the two services that
     * send hours are down.
     */
    @Test
    fun `a sky with no hours is asked about again, five minutes apart`() {
        located()
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        // Only the one that says nothing about tomorrow answers.
        val answers = answersFromNow()
        WeatherStore.fetch = WeatherStore.Fetch { url -> if ("wttr.in" in url) answers(url) else null }
        assertTrue(WeatherStore.refresh(context, 40.42, -3.70).known)
        val now = TimeKeeper.nowMs()
        assertTrue(WeatherStore.forecast(context).isEmpty())
        assertFalse("asked again at once", WeatherStore.stale(context, now))
        assertFalse("asked again after four minutes", WeatherStore.stale(context, now + 4 * 60_000L))
        assertTrue("not asked again after six", WeatherStore.stale(context, now + 6 * 60_000L))
        // With the hours there, a sky six minutes old is not stale.
        WeatherStore.fetch = WeatherStore.Fetch { answers(it) }
        WeatherStore.refresh(context, 40.42, -3.70)
        assertFalse(WeatherStore.forecast(context).isEmpty())
        assertFalse(WeatherStore.stale(context, TimeKeeper.nowMs() + 6 * 60_000L))
        // And half an hour is half an hour, hours or no hours.
        assertTrue(WeatherStore.stale(context, TimeKeeper.nowMs() + 31 * 60_000L))
    }
}
