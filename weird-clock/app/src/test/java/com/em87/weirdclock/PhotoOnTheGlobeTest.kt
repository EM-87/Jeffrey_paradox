package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The day's photograph kept, handed over, and on the glass.
 *
 * The rule is held in [SatellitePhotoTest]. This is everything round it:
 * which satellite is asked and which stands in for it, that the veil's
 * photograph is not fetched as well, that the clock hands the globe the
 * photograph of the day it is wound to and says so when that is not the
 * day it has, and what the settings do when the kind is changed.
 *
 * And pictures of it, since the one thing no count can say is whether a
 * day's photograph on a ball still looks like the earth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoOnTheGlobeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val realPhoto = PhotoStore.fetch
    private val realDaily = CloudStore.fetch
    private val realLive = LiveCloudStore.fetch

    private val day = DailyImageStore.DAY_MS

    /** Noon UTC on the 23rd: the day the globe asks for then is the 22nd. */
    private val now = DailyImageStore.msOf("2026-11-23")!! + 12 * 3_600_000L

    private val asked = CopyOnWriteArrayList<String>()

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun jpeg(colour: Int): ByteArray {
        val picture = Bitmap.createBitmap(SatellitePhoto.WIDTH, SatellitePhoto.HEIGHT, Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(colour) }
        return ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** The service: the real photograph for the 22nd from the first satellite, flat colours for other days. */
    private fun serve(first: Set<String>, second: Set<String> = emptySet()) {
        PhotoStore.fetch = DailyImageStore.Fetch { url ->
            asked += url
            val of = url.substringAfter("TIME=")
            val has = if (SatellitePhoto.SECOND in url) second else first
            when {
                of !in has -> jpeg(Color.BLACK)
                of == "2026-11-22" && SatellitePhoto.LAYER in url -> bytes("gibs-photo-2026-11-22.jpg")
                else -> jpeg(Color.rgb(90, 120, 30 + of.takeLast(2).toInt() * 5))
            }
        }
    }

    @Before
    fun aGlobeAskedForThePhotograph() {
        prefs.edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.FACE_ASKED, true)
            .putString(Prefs.FACE, Face.HEMISPHERE.key)
            .putBoolean(Prefs.WEATHER, true)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, true)
            .putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.PHOTO.key)
            .commit()
        PhotoStore.forget(context)
        CloudStore.forget(context)
        LiveCloudStore.forget(context)
        asked.clear()
        serve(setOf("2026-11-22", "2026-11-21", "2026-11-20"))
        CloudStore.fetch = CloudStore.Fetch { url -> asked += url; null }
        LiveCloudStore.fetch = LiveCloudStore.Fetch { url -> asked += url; null }
    }

    @After
    fun tidy() {
        TimeKeeper.solarOffsetMs = 0L
        PhotoStore.fetch = realPhoto
        CloudStore.fetch = realDaily
        LiveCloudStore.fetch = realLive
        PhotoStore.forget(context)
        CloudStore.forget(context)
        LiveCloudStore.forget(context)
    }

    private fun draw(v: View): Bitmap {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return bitmap
    }

    // ------------------------------------------------------------ the store

    /** Three days at a time, each asked of the first satellite, at the size of the map. */
    @Test
    fun `the photographs of the days are fetched from the afternoon satellite`() {
        assertEquals(context.getString(R.string.clouds_on_phone_none), PhotoStore.describe(context))
        assertTrue(PhotoStore.refresh(context, now))
        assertEquals(
            listOf("2026-11-22", "2026-11-21", "2026-11-20").map { SatellitePhoto.urls(it)[0] },
            asked.toList()
        )
        assertEquals(
            listOf("photo-2026-11-20.jpg", "photo-2026-11-21.jpg", "photo-2026-11-22.jpg"),
            context.cacheDir.list()!!.filter { it.startsWith("photo-") }.sorted()
        )
        assertEquals(context.getString(R.string.clouds_on_phone, "2026-11-20", "2026-11-22", 3), PhotoStore.describe(context))
        // The file is the photograph as it was sent.
        assertTrue(File(context.cacheDir, "photo-2026-11-22.jpg").readBytes().contentEquals(bytes("gibs-photo-2026-11-22.jpg")))
        // And the veil's own photograph is not fetched beside it.
        assertFalse(CloudStore.wanted(context))
        assertFalse(CloudStore.refresh(context, now))
        assertEquals(null, CloudStore.cached(context, now))
        assertEquals(null, CloudStore.worn(context, now))
        assertEquals(3, asked.size)
    }

    /** A day the first satellite has nothing of is asked of the second, and only that day. */
    @Test
    fun `the next satellite stands in for a day the first has nothing of`() {
        serve(first = setOf("2026-11-22", "2026-11-20"), second = setOf("2026-11-21"))
        assertTrue(PhotoStore.refresh(context, now))
        assertEquals(
            listOf(
                SatellitePhoto.urls("2026-11-22")[0],
                SatellitePhoto.urls("2026-11-21")[0], SatellitePhoto.urls("2026-11-21")[1],
                SatellitePhoto.urls("2026-11-20")[0]
            ),
            asked.toList()
        )
        assertEquals(3, context.cacheDir.list()!!.count { it.startsWith("photo-") })
        // A day neither has is left missing, and is not a black world on the globe.
        PhotoStore.forget(context)
        asked.clear()
        serve(first = setOf("2026-11-22"))
        PhotoStore.refresh(context, now)
        assertEquals(listOf("photo-2026-11-22.jpg"), context.cacheDir.list()!!.filter { it.startsWith("photo-") })
    }

    /** Only with the weather on, the clouds on, and this kind chosen. */
    @Test
    fun `nothing is asked unless the photograph is the kind chosen`() {
        for (off in listOf(
            { prefs.edit().putBoolean(Prefs.WEATHER, false).commit() },
            { prefs.edit().putBoolean(Prefs.HEMISPHERE_CLOUDS, false).commit() },
            { prefs.edit().putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.VEIL.key).commit() },
            { prefs.edit().putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.LIVE.key).commit() },
            { prefs.edit().remove(Prefs.HEMISPHERE_CLOUDS_KIND).commit() }
        )) {
            aGlobeAskedForThePhotograph()
            File(context.cacheDir, "photo-2026-11-22.jpg").writeBytes(bytes("gibs-photo-2026-11-22.jpg"))
            assertNotNull(PhotoStore.cached(context, now))
            assertNotNull(PhotoStore.worn(context, now))
            off()
            assertFalse(PhotoStore.wanted(context))
            assertFalse(PhotoStore.stale(context, now))
            assertFalse(PhotoStore.refresh(context, now))
            PhotoStore.refreshInBackground(context)
            Thread.sleep(100)
            assertEquals("the service was asked", 0, asked.count { "VIIRS" in it })
            assertEquals("a photograph of a kind not chosen", null, PhotoStore.cached(context, now))
            assertEquals(null, PhotoStore.worn(context, now))
        }
    }

    /** One photograph for one day's file, the day the world is wound to, and gone when forgotten. */
    @Test
    fun `the globe is handed the photograph of the day it is wound to`() {
        PhotoStore.refresh(context, now)
        val today = PhotoStore.cached(context, now)!!
        assertEquals(SatellitePhoto.WIDTH, today.width)
        assertEquals(SatellitePhoto.HEIGHT, today.height)
        assertSame("a second photograph for the same day and the same file", today, PhotoStore.cached(context, now))
        assertEquals("2026-11-22", PhotoStore.worn(context, now)!!.day)
        assertEquals(0, PhotoStore.worn(context, now)!!.offDays)
        // It is the rule's reading of the file.
        val raw = bytes("gibs-photo-2026-11-22.jpg")
        val sent = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)!!
        val pixels = IntArray(sent.width * sent.height).also { sent.getPixels(it, 0, sent.width, 0, 0, sent.width, sent.height) }
        assertTrue(today.pixels.contentEquals(SatellitePhoto.picture(pixels, sent.width, sent.height).pixels))
        // A day back, the day before's — told from today's by its day,
        // whatever dates are on the files: three days fetched in one go
        // can land in one tick of the clock. Past what the phone has,
        // the oldest, and how far off it is.
        val tick = File(context.cacheDir, "photo-2026-11-22.jpg").lastModified()
        for (name in context.cacheDir.list()!!.filter { it.startsWith("photo-") }) {
            File(context.cacheDir, name).setLastModified(tick)
        }
        assertSame(today, PhotoStore.cached(context, now))
        val before = PhotoStore.cached(context, now - day)!!
        assertNotSame(today, before)
        assertFalse(before.pixels.contentEquals(today.pixels))
        assertEquals("2026-11-21", PhotoStore.worn(context, now - day)!!.day)
        assertEquals("2026-11-20", PhotoStore.worn(context, now - 5 * day)!!.day)
        assertEquals(3, PhotoStore.worn(context, now - 5 * day)!!.offDays)
        // A file fetched again is a new photograph.
        val file = File(context.cacheDir, "photo-2026-11-22.jpg")
        val was = PhotoStore.cached(context, now)!!
        file.setLastModified(file.lastModified() + 5_000L)
        assertNotSame(was, PhotoStore.cached(context, now))
        // Forgotten: the files, and the photograph held.
        val stamp = file.lastModified()
        val held = PhotoStore.cached(context, now)!!
        PhotoStore.forget(context)
        assertEquals(0, context.cacheDir.list()!!.count { it.startsWith("photo-") })
        assertEquals(null, PhotoStore.cached(context, now))
        file.writeBytes(jpeg(Color.rgb(200, 200, 200)))
        file.setLastModified(stamp)
        assertNotSame("the photograph of a file that was thrown away", held, PhotoStore.cached(context, now))
    }

    // ------------------------------------------------------------ the clock

    @Test
    fun `the clock hands the globe the photograph, and no veil over it`() {
        PhotoStore.refresh(context, now)
        File(context.cacheDir, "clouds-2026-11-22.jpg").writeBytes(bytes("gibs-clouds-2026-11-22.jpg"))
        asked.clear()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            val screen = c.get().findViewById<View>(android.R.id.content)
            world.atMs = now
            draw(screen)
            assertSame("the globe is not wearing the photograph", PhotoStore.cached(context, now), world.photo)
            assertNotNull(world.photo)
            assertEquals("a veil over a photograph that has its cloud in it", null, world.clouds)
            assertEquals("a word on the face for the day's own photograph", null, world.legendForTest())
            // Wound back a day: the day before's.
            world.windForTest(-day)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1_100))
            draw(screen)
            assertSame(PhotoStore.cached(context, now - day), world.photo)
            // Wound back past what the phone has: the oldest, and the face says which day it is of.
            world.windForTest(-6 * day)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1_100))
            draw(screen)
            assertSame(PhotoStore.cached(context, now - 6 * day), world.photo)
            val said = world.legendForTest()
            val wanted = context.getString(R.string.hemisphere_photo_legend, "20 Nov")
            assertTrue("the face does not say which day's photograph it wears: $said", said != null && wanted in said)
        }
    }

    @Test
    fun `with another kind chosen the photograph on the phone is not worn`() {
        PhotoStore.refresh(context, now)
        File(context.cacheDir, "clouds-2026-11-22.jpg").writeBytes(bytes("gibs-clouds-2026-11-22.jpg"))
        prefs.edit().putString(Prefs.HEMISPHERE_CLOUDS_KIND, SatelliteClouds.Kind.VEIL.key).commit()
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            val world = c.get().hemisphereForTest()!!
            world.atMs = now
            draw(c.get().findViewById<View>(android.R.id.content))
            assertEquals(null, world.photo)
            assertNotNull(world.clouds)
        }
    }

    /** Coming to the glass sends for the photograph, and a clock left standing goes back for the next day's. */
    @Test
    fun `the photograph is sent for, and again when the day turns`() {
        fun standingAt(atMs: Long): List<String> {
            TimeKeeper.solarOffsetMs = atMs - System.currentTimeMillis()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))
            Thread.sleep(300)
            return asked.filter { "VIIRS" in it }.map { it.substringAfter("TIME=") + if (SatellitePhoto.SECOND in it) "b" else "" }
        }
        Robolectric.buildActivity(MainActivity::class.java).use { c ->
            c.setup()
            assertNotNull(c.get().hemisphereForTest())
            // Coming to the glass asks, by the phone's own clock, which is
            // not at that morning: that it asked is all that is held of it.
            val deadline = System.currentTimeMillis() + 10_000L
            while (asked.none { "VIIRS" in it } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("coming to the glass did not send for the photograph", asked.any { "VIIRS" in it })
            Thread.sleep(400)
            asked.clear()
            PhotoStore.forget(context)
            // Standing at noon on the 23rd with nothing on the phone.
            val first = standingAt(now)
            assertEquals(listOf("2026-11-22", "2026-11-21", "2026-11-20"), first)
            // A minute on: nothing new to go for.
            assertEquals(first, standingAt(now + 61_000L))
            // A day on, the next day's — which neither satellite has yet.
            val next = standingAt(now + day + 120_000L).drop(first.size)
            assertEquals(listOf("2026-11-23", "2026-11-23b"), next.take(2))
            // Not asked again the minute after for the day that did not
            // come: a clock asking by itself waits the hour.
            assertEquals("asked again a minute after the service had nothing", first.size + next.size, standingAt(now + day + 181_000L).size)
            assertEquals("the veil's photograph or the hours were sent for", 0, asked.count { "VIIRS" !in it })
        }
    }

    // ------------------------------------------------------------ the glass

    private fun globe(atMs: Long, photo: SatellitePhoto.Picture?, which: Hemisphere.View): HemisphereView =
        HemisphereView(context).apply {
            theme = ClockThemes.MIDNIGHT
            view = which
            this.atMs = atMs
            hourRing = false
            showSun = false
            showMoon = false
            moonlight = false
            located = false
            this.photo = photo
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
     * The map and the day, on all three views, at noon over Greenwich
     * on the day of the photograph.
     */
    @Test
    fun `the day's photograph on the glass`() {
        PhotoStore.refresh(context, now)
        val picture = PhotoStore.cached(context, now)!!
        val at = DailyImageStore.msOf("2026-11-22")!! + 12 * 3_600_000L
        for (which in Hemisphere.View.entries) {
            val bare = shoot(globe(at, null, which), "photo-${which.key}-map")
            val worn = shoot(globe(at, picture, which), "photo-${which.key}-day")
            var changed = 0
            var sampled = 0
            for (y in 0 until worn.height step 2) for (x in 0 until worn.width step 2) {
                sampled++
                val a = bare.getPixel(x, y)
                val b = worn.getPixel(x, y)
                if (Math.abs(Color.green(a) - Color.green(b)) > 24) changed++
            }
            println("MEASURE the day's photograph on the ${which.key} view: $changed of $sampled pixels sampled are another colour for it")
            assertTrue("the photograph is not on the ${which.key} view: $changed pixels", changed > 12_000)
        }
    }

    /**
     * The photograph is the daylit ground and nothing else: where it is
     * night on the ball, the ball is what it was without it.
     *
     * The ball is always seen with the sun to the right of it, so its
     * left side is the night; in November the north is tilted away, so
     * the upper left is night a long way from any twilight.
     */
    @Test
    fun `the photograph is the daylit ground and not the night`() {
        PhotoStore.refresh(context, now)
        val picture = PhotoStore.cached(context, now)!!
        val at = DailyImageStore.msOf("2026-11-22")!! + 12 * 3_600_000L
        val bare = draw(globe(at, null, Hemisphere.View.GLOBE))
        val worn = draw(globe(at, picture, Hemisphere.View.GLOBE))
        val world = globe(at, null, Hemisphere.View.GLOBE)
        val r = worn.width * Hemisphere.worldRadius(world.zoom, ringed = false, moonOutside = false)
        val disc = DiscProjection.of(Hemisphere.View.GLOBE, 512)
        var night = 0
        var nightChanged = 0
        var day = 0
        var dayChanged = 0
        for (py in 0 until 512) for (px in 0 until 512) {
            val i = py * 512 + px
            if (!disc.onWorld(i) || disc.rho[i] > 0.95f) continue
            val x = (worn.width / 2f + (px + 0.5f - 256f) / 256f * r).toInt()
            val y = (worn.height / 2f + (py + 0.5f - 256f) / 256f * r).toInt()
            val differs = bare.getPixel(x, y) != worn.getPixel(x, y)
            if (px < 128 && py < 256) {
                night++
                if (differs) nightChanged++
            } else if (px > 320) {
                day++
                if (differs) dayChanged++
            }
        }
        println("MEASURE the ball at noon UTC, 22 Nov 2026: of $night pixels deep in its night $nightChanged are changed by the photograph; of $day in its day, $dayChanged")
        assertTrue("no night to judge by: $night", night > 10_000)
        assertEquals("the photograph was laid on the night", 0, nightChanged)
        assertTrue("the photograph changed only $dayChanged of $day pixels of the day", dayChanged > day * 0.8)
    }

    /** A globe that is told only where to ask for its photograph asks, as it draws. */
    @Test
    fun `the globe asks for the photograph of the instant it shows`() {
        PhotoStore.refresh(context, now)
        val world = globe(now, null, Hemisphere.View.GLOBE)
        val askedFor = ArrayList<Long>()
        world.photoOn = { at -> askedFor += at; PhotoStore.cached(context, at) }
        draw(world)
        assertEquals(listOf(now), askedFor)
        assertSame(PhotoStore.cached(context, now), world.photo)
        // Not again the same second; again for another day at once.
        draw(world)
        assertEquals(1, askedFor.size)
        world.atMs = now - day
        draw(world)
        assertEquals(listOf(now, now - day), askedFor)
        assertSame(PhotoStore.cached(context, now - day), world.photo)
        // Told of another place to ask, it asks there at once and does
        // not wait out the second: that is the settings being changed.
        val elsewhere = ArrayList<Long>()
        world.photoOn = { at -> elsewhere += at; null }
        draw(world)
        assertEquals("a new place to ask was not asked", listOf(now - day), elsewhere)
        assertEquals(null, world.photo)
    }

    /** Half a million pixels are laid once for a photograph and a map, however far the ball is turned. */
    @Test
    fun `the photograph is laid once for a map and again for the next month's`() {
        PhotoStore.refresh(context, now)
        val picture = PhotoStore.cached(context, now)!!
        val start = DailyImageStore.msOf("2026-11-29")!! + 12 * 3_600_000L
        val world = globe(start, picture, Hemisphere.View.GLOBE)
        for (step in 0 until 12) {
            world.atMs = start + step * 6 * 60_000L
            draw(world)
        }
        assertTrue("only ${world.bakesForTest()} bakes for twelve turns", world.bakesForTest() >= 12)
        assertEquals("the photograph was laid again for a turn of the ball", 1, world.groundsForTest())
        // December: another map under the same photograph.
        world.atMs = start + 3 * day
        draw(world)
        assertEquals(12, world.dayMapMonthForTest())
        assertEquals("the photograph was not laid over the new month's map", 2, world.groundsForTest())
        // Another photograph over the same map: laid again, and on the glass.
        val was = draw(world)
        world.photo = SatellitePhoto.Picture(
            picture.width, picture.height, IntArray(picture.pixels.size) { (0xFF shl 24) or 0x7F7F7F }
        )
        val grey = draw(world)
        assertEquals("another photograph was not laid", 3, world.groundsForTest())
        assertFalse("the globe went on wearing the photograph it had", was.sameAs(grey))
        // Taken off, the map by itself, and nothing laid.
        world.photo = null
        val off = draw(world)
        assertEquals(3, world.groundsForTest())
        assertTrue(
            "the globe went on wearing a photograph it no longer has",
            off.sameAs(draw(globe(start + 3 * day, null, Hemisphere.View.GLOBE)))
        )
        // A photograph of another size is not laid, and the globe is the map's.
        val small = SatellitePhoto.Picture(720, 360, IntArray(720 * 360) { 0xFFFFFFFF.toInt() })
        val plain = draw(globe(start, null, Hemisphere.View.GLOBE))
        val odd = draw(globe(start, small, Hemisphere.View.GLOBE))
        assertTrue("a photograph of another size was laid", plain.sameAs(odd))
    }

    // -------------------------------------------------------------- the row

    private fun advanced(): SettingsActivity.AdvancedSettingsFragment {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val fragment = SettingsActivity.AdvancedSettingsFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment).commitNow()
        return fragment
    }

    @Test
    fun `choosing a kind lets go of what only the others use`() {
        fun on(prefix: String) = context.cacheDir.list()!!.count { it.startsWith(prefix) }
        fun fill() {
            // Two days of the photograph and one of the veil's, so that
            // what the phone has of the one cannot pass for the other's.
            File(context.cacheDir, "photo-2026-11-22.jpg").writeBytes(bytes("gibs-photo-2026-11-22.jpg"))
            File(context.cacheDir, "photo-2026-11-21.jpg").writeBytes(jpeg(Color.rgb(90, 120, 135)))
            File(context.cacheDir, "clouds-2026-11-22.jpg").writeBytes(bytes("gibs-clouds-2026-11-22.jpg"))
            File(context.cacheDir, "live-29734380.jpg").writeBytes(bytes("nowcoast-ir-2026-11-24T05.jpg"))
            File(context.cacheDir, "live-29734380-v.jpg").writeBytes(bytes("nowcoast-vis-2026-11-24T05.jpg"))
        }
        fill()
        val row = advanced().findPreference<ListPreference>(Prefs.HEMISPHERE_CLOUDS_KIND)!!
        assertEquals(SatelliteClouds.Kind.PHOTO.key, row.value)
        assertEquals(
            context.getString(R.string.clouds_kind_photo_summary) + "\n" +
                context.getString(R.string.clouds_on_phone, "2026-11-21", "2026-11-22", 2),
            row.summary.toString()
        )
        // The photograph: the veil's pictures and the hours go.
        assertTrue(row.callChangeListener(SatelliteClouds.Kind.PHOTO.key))
        assertEquals(Triple(2, 0, 0), Triple(on("photo-"), on("clouds-"), on("live-")))
        // The veil: the photographs and the hours go.
        fill()
        assertTrue(row.callChangeListener(SatelliteClouds.Kind.VEIL.key))
        assertEquals(Triple(0, 1, 0), Triple(on("photo-"), on("clouds-"), on("live-")))
        assertTrue(context.getString(R.string.clouds_kind_veil_summary) in row.summary.toString())
        // The hours: the photographs go, and the veil's stay, which the hours fall back on.
        fill()
        assertTrue(row.callChangeListener(SatelliteClouds.Kind.LIVE.key))
        assertEquals(Triple(0, 1, 2), Triple(on("photo-"), on("clouds-"), on("live-")))
        assertTrue(context.getString(R.string.clouds_kind_live_summary) in row.summary.toString())
        // The answers on offer are the kinds there are, in their order, with a name for each.
        assertEquals(
            SatelliteClouds.Kind.entries.map { it.key },
            context.resources.getStringArray(R.array.hemisphere_clouds_kind_values).toList()
        )
        assertEquals(3, context.resources.getStringArray(R.array.hemisphere_clouds_kind_entries).size)
    }
}
