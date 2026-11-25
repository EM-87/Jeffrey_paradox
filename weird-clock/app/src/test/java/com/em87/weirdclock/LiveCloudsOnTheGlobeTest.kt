package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The hour's cloud on the glass, and the row that asks for it.
 *
 * The rule and the keeping are held elsewhere — see [LiveCloudsTest] and
 * [LiveCloudStoreTest]. This is the last yard: that the clock itself
 * hands the globe the hour's frame when that kind is chosen, the frame
 * of another hour when the world is turned to it, and the day's
 * photograph when it is turned further than the hours reach; and that
 * the settings ask the question where the answer has an effect.
 *
 * With pictures, because cloud drawn from a temperature is a thing to
 * be looked at before it is believed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCloudsOnTheGlobeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val realLive = LiveCloudStore.fetch
    private val realDaily = CloudStore.fetch

    private val hour = LiveClouds.HOUR_MS

    private fun at(h: Int): Long = DailyImageStore.msOf("2026-11-24")!! + h * hour

    /** Ten past six on the morning the frames are of. */
    private val now = at(6) + 10 * 60_000L

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun pixels(name: String): Triple<Int, Int, IntArray> {
        val raw = bytes(name)
        val picture = BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
        val out = IntArray(picture.width * picture.height)
        picture.getPixels(out, 0, picture.width, 0, 0, picture.width, picture.height)
        return Triple(picture.width, picture.height, out)
    }

    private fun liveField(h: Int): SatelliteClouds.Field {
        val (w, ht, infrared) = pixels("nowcoast-ir-2026-11-24T%02d.jpg".format(h))
        val visible = pixels("nowcoast-vis-2026-11-24T%02d.jpg".format(h)).third
        val sun = Hemisphere.subsolar(at(h))
        return SatelliteClouds.Field(w, ht, LiveClouds.field(infrared, visible, w, ht, sun[0], sun[1]))
    }

    @Before
    fun aGlobeWithTheHoursOnThePhone() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.HEMISPHERE.key)
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, true)
            .putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.LIVE.key)
            .commit()
        CloudStore.forget(context)
        LiveCloudStore.forget(context)
        // Nobody answers: what is on the phone is what was put there.
        LiveCloudStore.fetch = LiveCloudStore.Fetch { null }
        CloudStore.fetch = CloudStore.Fetch { null }
        for (h in 1..5) {
            File(context.cacheDir, "live-${at(h) / 60_000L}-v.jpg").writeBytes(bytes("nowcoast-vis-2026-11-24T%02d.jpg".format(h)))
            File(context.cacheDir, "live-${at(h) / 60_000L}.jpg").writeBytes(bytes("nowcoast-ir-2026-11-24T%02d.jpg".format(h)))
        }
        File(context.cacheDir, "clouds-2026-11-22.jpg").writeBytes(bytes("gibs-clouds-2026-11-22.jpg"))
        TimeKeeper.solarOffsetMs = now - System.currentTimeMillis()
    }

    @After
    fun tidy() {
        TimeKeeper.solarOffsetMs = 0L
        LiveCloudStore.fetch = realLive
        CloudStore.fetch = realDaily
        CloudStore.forget(context)
        LiveCloudStore.forget(context)
    }

    private fun draw(v: View): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return bitmap
    }

    /** A second of the phone's own clock, which is how often the globe asks for its cloud. */
    private fun aSecond() = org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1_100))

    // ------------------------------------------------------------- the clock

    @Test
    fun `the clock hands the globe the hour's cloud, and the day's where the hours do not reach`() {
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            val screen = c.get().findViewById<View>(android.R.id.content)
            world.atMs = now
            draw(screen)
            // Now: five o'clock's frame, the newest there is.
            val newest = LiveCloudStore.cached(context, now)
            assertNotNull(newest)
            assertSame("the globe is not wearing the hourly frame", newest, world.clouds)
            assertTrue(world.clouds!!.cover.contentEquals(liveField(5).cover))
            assertEquals("a word about the day's clouds over an hourly frame", null, world.legendForTest())

            // Turned back three hours: three o'clock's.
            world.windForTest(-3 * hour)
            aSecond()
            draw(screen)
            assertNotSame(newest, world.clouds)
            assertTrue("not the frame of the hour the world was turned to", world.clouds!!.cover.contentEquals(liveField(3).cover))

            // Turned back two days, which the hours do not reach: the
            // day's photograph, and a word on the face for which day.
            world.windForTest(-48 * hour)
            aSecond()
            draw(screen)
            val daily = CloudStore.cached(context, now - 48 * hour)
            assertNotNull(daily)
            assertSame("past the hours the globe should wear the day's photograph", daily, world.clouds)
            val said = world.legendForTest()
            // The day's clouds named as that, and not only the day the
            // world is wound to, which the face says for its own reasons.
            val wanted = context.getString(R.string.hemisphere_clouds_legend, "22 Nov")
            assertTrue("no word for which day's clouds: $said", said != null && wanted in said)

            // And back to now: five o'clock's again, made again — one
            // field is held at a time, not every hour's.
            world.windForTest(0L)
            aSecond()
            draw(screen)
            assertTrue(world.clouds!!.cover.contentEquals(liveField(5).cover))
            assertEquals(null, world.legendForTest())
        }
    }

    @Test
    fun `with the daily kind chosen the hours on the phone are not worn`() {
        prefs.edit().putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.VEIL.key).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            world.atMs = now
            draw(c.get().findViewById<View>(android.R.id.content))
            assertSame(CloudStore.cached(context, now), world.clouds)
            assertNotNull(world.clouds)
        }
    }

    /** With no frame on the phone the globe wears what it always did, and the hour is sent for. */
    @Test
    fun `with no frame yet the globe wears the day's and the hour is asked after`() {
        LiveCloudStore.forget(context)
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        LiveCloudStore.fetch = LiveCloudStore.Fetch { url -> asked += url; null }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            world.atMs = now
            draw(c.get().findViewById<View>(android.R.id.content))
            assertSame(CloudStore.cached(context, now), world.clouds)
            val deadline = System.currentTimeMillis() + 10_000L
            while (asked.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals("the hour was not asked after", LiveClouds.ask(), asked.firstOrNull())
        }
    }

    /**
     * A clock left standing asks after the next hour by itself: when it
     * is due, which is forty-two minutes past the hour after the newest
     * it has, and then every ten minutes and not every one.
     */
    @Test
    fun `a clock left standing asks after the next hour when it is due`() {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        LiveCloudStore.fetch = LiveCloudStore.Fetch { url -> asked += url; null }
        fun standingAt(atMs: Long): Int {
            TimeKeeper.solarOffsetMs = atMs - System.currentTimeMillis()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))
            // The asking is on a thread of its own: give it time to be heard.
            Thread.sleep(250)
            return asked.size
        }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNotNull("the globe is not the face", c.get().hemisphereForTest())
            // Coming to the glass sets the clock by the phone, which is
            // not at that morning, and asks by it: let that go by.
            Thread.sleep(300)
            asked.clear()
            // Ten past six with five o'clock's frame on the phone, and for
            // the half hour after it: nothing to ask.
            assertEquals("asked with nothing due", 0, standingAt(now))
            assertEquals(0, standingAt(now + 61_000L))
            assertEquals(0, standingAt(at(6) + 41 * 60_000L))
            // Seventeen minutes to seven: the next frame is due.
            assertEquals("the next hour was not asked after", 1, standingAt(at(6) + 43 * 60_000L))
            assertEquals(LiveClouds.ask(), asked.first())
            // The service had nothing. Not again the minute after, nor for ten.
            assertEquals("asked again a minute after", 1, standingAt(at(6) + 44 * 60_000L + 1_000L))
            assertEquals(1, standingAt(at(6) + 52 * 60_000L))
            assertEquals("not asked again after ten minutes", 2, standingAt(at(6) + 54 * 60_000L))
        }
    }

    // ------------------------------------------------------------- the glass

    private fun globe(atMs: Long, clouds: SatelliteClouds.Field?, which: Hemisphere.View): HemisphereView =
        HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            view = which
            this.atMs = atMs
            hourRing = false
            showSun = false
            showMoon = false
            moonlight = false
            located = false
            this.clouds = clouds
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1080, 1080)
        }

    private fun shoot(v: View, name: String): Bitmap = draw(v).also { bitmap ->
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name ${bitmap.width}x${bitmap.height}")
    }

    /**
     * The same morning three ways: bare, under the day's photograph of
     * two days before, and under the frame of that hour. On the ball
     * and from over the south pole, where it is summer and the end of
     * the mosaic is in daylight.
     */
    @Test
    fun `the hour's cloud on the glass`() {
        val three = at(3)
        val (w, h, day) = pixels("gibs-clouds-2026-11-22.jpg")
        val before = pixels("gibs-clouds-2026-11-21.jpg").third
        SatelliteClouds.fill(day, before)
        val daily = SatelliteClouds.Field(w, h, SatelliteClouds.field(day, w, h))
        val live = liveField(3)
        for (which in listOf(Hemisphere.View.GLOBE, Hemisphere.View.SOUTH)) {
            val bare = shoot(globe(three, null, which), "live-${which.key}-bare")
            shoot(globe(three, daily, which), "live-${which.key}-daily")
            val worn = shoot(globe(three, live, which), "live-${which.key}-live")
            var whiter = 0
            var darker = 0
            for (y in 0 until worn.height step 2) for (x in 0 until worn.width step 2) {
                val a = (bare.getPixel(x, y) shr 8) and 0xFF
                val b = (worn.getPixel(x, y) shr 8) and 0xFF
                if (b - a > 30) whiter++
                if (a - b > 30) darker++
            }
            // Darker is ground in a cloud's shadow, now that cloud throws
            // one. With the sun's light off the cloud is the veil it was,
            // and a veil darkens nothing.
            val flatBare = draw(globe(three, null, which).apply { lit = false })
            val flatWorn = draw(globe(three, live, which).apply { lit = false })
            var veiled = 0
            for (y in 0 until flatWorn.height step 2) for (x in 0 until flatWorn.width step 2) {
                val a = (flatBare.getPixel(x, y) shr 8) and 0xFF
                val b = (flatWorn.getPixel(x, y) shr 8) and 0xFF
                if (a - b > 30) veiled++
            }
            println("MEASURE the frame of 03:00 on the ${which.key} view: $whiter of ${worn.width * worn.height / 4} pixels sampled are whiter for it, $darker darker in its shadow, $veiled darker with the light off")
            assertTrue("the hour's cloud is not on the glass: $whiter pixels", whiter > 5_000)
            assertEquals("cloud made something darker", 0, veiled)
            assertTrue("more of the glass is in shadow than in cloud: $darker against $whiter", darker * 20 < whiter)
        }
    }

    /**
     * From over the south pole: cloud up to the end of the mosaic and
     * none beyond it. That it thins towards the end is the rule's to
     * show — see [LiveCloudsTest] — and cannot be read off the glass,
     * where the end lies over the ice and ice is as bright as cloud.
     */
    @Test
    fun `there is no cloud on the glass beyond the end of the mosaic`() {
        val world = globe(at(3), liveField(3), Hemisphere.View.SOUTH)
        val worn = draw(world)
        val bare = draw(globe(at(3), null, Hemisphere.View.SOUTH))
        // Rings round the pole, by how far out they are: the pole is the
        // middle of this view and the equator its rim. The world is not
        // the whole of the view: it is drawn this much of it across.
        val r = worn.width * Hemisphere.worldRadius(world.zoom, ringed = false, moonOutside = false)
        val disc = DiscProjection.of(Hemisphere.View.SOUTH, 512)
        val added = DoubleArray(91)
        val counted = IntArray(91)
        for (py in 0 until 512) for (px in 0 until 512) {
            val i = py * 512 + px
            if (!disc.onWorld(i)) continue
            val south = (disc.v[i] * 180.0 - 90.0).toInt()
            if (south !in 0..90) continue
            val x = (worn.width / 2f + (px + 0.5f - 256f) / 256f * r).toInt()
            val y = (worn.height / 2f + (py + 0.5f - 256f) / 256f * r).toInt()
            val a = (bare.getPixel(x, y) shr 8) and 0xFF
            val b = (worn.getPixel(x, y) shr 8) and 0xFF
            added[south] += (b - a).coerceAtLeast(0).toDouble()
            counted[south]++
        }
        fun mean(from: Int, to: Int): Double {
            var sum = 0.0
            var n = 0
            for (d in from..to) {
                sum += added[d]
                n += counted[d]
            }
            return sum / n
        }
        val well = mean(45, 65)
        val past = mean(75, 89)
        println("MEASURE whitening by the hour's cloud from over the south pole: %.1f at 45-65 S, %.3f beyond 75 S".format(well, past))
        assertTrue("no cloud to judge by: $well", well > 3.0)
        assertTrue("cloud beyond the end of the mosaic: $past", past < 0.02)
    }

    // -------------------------------------------------------------- the row

    private fun advanced(): SettingsActivity.AdvancedSettingsFragment {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val fragment = SettingsActivity.AdvancedSettingsFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment).commitNow()
        return fragment
    }

    /**
     * The alpha the row with this title is drawn at — see [FadedRowTest],
     * which this is taken from. Laid out taller than there, and after
     * the screen has settled and not before: the globe's rows are a long
     * way down a long page, a row that is not on screen is not painted,
     * and a layout pass still waiting its turn puts the page back to
     * the height of a phone.
     */
    private fun alphaOf(fragment: SettingsActivity.AdvancedSettingsFragment, title: String): Float? {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val list = fragment.listView
        val tall = 40_000
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, tall)
        fun walk(view: View): Float? {
            if (view is android.view.ViewGroup) {
                val label = view.findViewById<android.widget.TextView>(android.R.id.title)
                if (label?.text?.toString() == title) return view.alpha
                for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(list)
    }

    @Test
    fun `the row says what the kind chosen is, and choosing the other lets the frames go`() {
        val row = advanced().findPreference<ListPreference>(Prefs.HEMISPHERE_CLOUDS_KIND)
        assertNotNull("the globe's settings do not ask which clouds", row)
        assertEquals(SatelliteClouds.Kind.LIVE.key, row!!.value)
        val live = row.summary.toString()
        assertTrue(live, context.getString(R.string.clouds_kind_live_summary) in live)
        assertTrue("the row does not say what the phone has: $live", context.getString(R.string.live_on_phone, 5, "01:00", "05:00") in live)
        // The answers on offer are the kinds there are, in their order.
        assertEquals(
            SatelliteClouds.Kind.entries.map { it.key },
            context.resources.getStringArray(R.array.hemisphere_clouds_kind_values).toList()
        )
        assertEquals(
            SatelliteClouds.Kind.entries.size,
            context.resources.getStringArray(R.array.hemisphere_clouds_kind_entries).size
        )
        // Choosing the daily kind: the row says that instead, and the frames go.
        assertTrue(row.callChangeListener(SatelliteClouds.Kind.VEIL.key))
        assertEquals(
            context.getString(R.string.clouds_kind_veil_summary) + "\n" + CloudStore.describe(context),
            row.summary.toString()
        )
        assertEquals("the frames of a kind no longer worn were kept", emptyList<Long>(), LiveCloudStore.onDisk(context))
        // Choosing the hourly one again does not throw anything away.
        File(context.cacheDir, "live-${at(5) / 60_000L}.jpg").writeBytes(bytes("nowcoast-ir-2026-11-24T05.jpg"))
        assertTrue(row.callChangeListener(SatelliteClouds.Kind.LIVE.key))
        assertEquals(1, LiveCloudStore.onDisk(context).size)
        assertTrue(context.getString(R.string.live_on_phone, 1, "05:00", "05:00") in row.summary.toString())
    }

    @Test
    fun `a phone that was never asked wears the daily kind`() {
        prefs.edit().remove(Prefs.HEMISPHERE_CLOUDS_KIND).commit()
        assertEquals(SatelliteClouds.Kind.VEIL, CloudStore.kind(context))
        assertEquals(SatelliteClouds.Kind.VEIL, SatelliteClouds.Kind.of("something else"))
        val row = advanced().findPreference<ListPreference>(Prefs.HEMISPHERE_CLOUDS_KIND)!!
        assertEquals(SatelliteClouds.Kind.VEIL.key, row.value)
        // And says which days of the photograph the phone has: the one put there.
        assertEquals(
            context.getString(R.string.clouds_kind_veil_summary) + "\n" +
                context.getString(R.string.clouds_on_phone, "2026-11-22", "2026-11-22", 1),
            row.summary.toString()
        )
    }

    @Test
    fun `the row fades while there are no clouds to choose between`() {
        val title = context.getString(R.string.pref_hemisphere_clouds_kind_title)
        assertEquals("faded with the clouds on", 1f, alphaOf(advanced(), title)!!, 0.001f)
        prefs.edit().putBoolean(Prefs.HEMISPHERE_CLOUDS, false).commit()
        assertTrue("bright with the clouds off", alphaOf(advanced(), title)!! < 0.99f)
        prefs.edit().putBoolean(Prefs.HEMISPHERE_CLOUDS, true).putBoolean(Prefs.WEATHER, false).commit()
        assertTrue("bright with the weather off", alphaOf(advanced(), title)!! < 0.99f)
        prefs.edit().putBoolean(Prefs.WEATHER, true).commit()
        assertEquals(1f, alphaOf(advanced(), title)!!, 0.001f)
    }

    @Test
    fun `only the globe's settings ask`() {
        for (face in Face.entries) {
            prefs.edit().putString(Prefs.FACE, face.key).commit()
            val row = advanced().findPreference<ListPreference>(Prefs.HEMISPHERE_CLOUDS_KIND)
            assertEquals("on the ${face.key} face", face == Face.HEMISPHERE, row != null)
        }
    }
}
