package com.em87.weirdclock

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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lights' two layers, kept the way the clouds are, and the three
 * things the clouds never had to do: ask a second satellite when the
 * first has nothing, refuse a picture of nothing, and go back for a day
 * that is still being flown.
 *
 * Driven with real answers from the service, kept beside the tests: a
 * clean night, a raw night, and the transparent picture the service
 * serves for a day it has no data for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NightLightStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val asked = ArrayList<String>()
    private val real = NightLightStore.fetch

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private val clean by lazy { resource("gibs-lights-clean.png") }
    private val raw by lazy { resource("gibs-lights-raw.png") }
    private val empty by lazy { resource("gibs-lights-empty.png") }

    /** What the service says to a URL: by layer, with a way to blank one. */
    private var blankFor: Set<String> = emptySet()

    @Before
    fun watchTheWire() {
        asked.clear()
        NightLightStore.fetch = DailyImageStore.Fetch { url ->
            asked += url
            val layer = url.substringAfter("LAYERS=").substringBefore("&")
            when {
                layer in blankFor -> empty
                layer.contains("GapFilled") -> clean
                else -> raw
            }
        }
        mode("both")
        NightLightStore.forget(context)
    }

    @After
    fun putTheSocketBack() {
        NightLightStore.fetch = real
        NightLightStore.forget(context)
    }

    private fun mode(key: String, weather: Boolean = true) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WEATHER, weather)
            .putString(Prefs.HEMISPHERE_LIGHTS, key)
            .commit()
    }

    private fun utc(y: Int, m: Int, d: Int, h: Int): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d, h, 0, 0)
        }.timeInMillis

    private fun layers(urls: List<String>) =
        urls.map { it.substringAfter("LAYERS=").substringBefore("&") }

    /** With the weather off nothing leaves the phone, whatever the row says. */
    @Test
    fun `with the weather off there is nothing and no request`() {
        mode("both", weather = false)
        assertFalse(NightLightStore.wanted(context))
        assertFalse(NightLightStore.refresh(context, utc(2026, 11, 5, 15)))
        assertNull(NightLightStore.skyFor(context, utc(2026, 11, 5, 15)))
        assertTrue(asked.isEmpty())
    }

    /** And "none" means none, with the weather on. */
    @Test
    fun `none is none`() {
        mode("none")
        assertFalse(NightLightStore.wanted(context))
        assertNull(NightLightStore.skyFor(context, utc(2026, 11, 5, 15)))
    }

    /** Only the layers that are on are fetched. */
    @Test
    fun `clean alone asks only for the clean night`() {
        mode("clean")
        val now = utc(2026, 11, 5, 15)
        assertTrue(NightLightStore.refresh(context, now))
        assertTrue(layers(asked).all { it.contains("GapFilled") })
        val sky = NightLightStore.skyFor(context, now)
        assertNotNull(sky)
        assertNotNull(sky!!.clean)
        assertNull(sky.raw)
    }

    /** NOAA-20 is asked first and, answering, is what is kept. */
    @Test
    fun `NOAA-20 answers and Suomi is not asked`() {
        mode("clean")
        NightLightStore.refresh(context, utc(2026, 11, 5, 15))
        assertTrue(layers(asked).all { it == NightLights.CLEAN_NOAA20 })
    }

    /**
     * A day NOAA-20 has nothing for comes back as a transparent picture,
     * and Suomi NPP is asked for that day instead.
     */
    @Test
    fun `a blank answer from NOAA-20 falls back to Suomi NPP`() {
        mode("clean")
        blankFor = setOf(NightLights.CLEAN_NOAA20)
        val now = utc(2026, 11, 5, 15)
        assertTrue(NightLightStore.refresh(context, now))
        val seen = layers(asked)
        assertTrue("Suomi was never asked", NightLights.CLEAN_SNPP in seen)
        assertTrue("Suomi was asked before NOAA-20", seen.indexOf(NightLights.CLEAN_NOAA20) < seen.indexOf(NightLights.CLEAN_SNPP))
        assertNotNull(NightLightStore.skyFor(context, now)?.clean)
    }

    /** A day neither satellite has is not kept, and the day before shows. */
    @Test
    fun `a day nobody has wears the day before`() {
        mode("clean")
        blankFor = setOf(NightLights.CLEAN_NOAA20, NightLights.CLEAN_SNPP)
        val now = utc(2026, 11, 5, 15)
        assertFalse("a picture of nothing was kept", NightLightStore.refresh(context, now))
        assertNull(NightLightStore.skyFor(context, now))

        // Now the service has yesterday and nothing newer.
        blankFor = emptySet()
        NightLightStore.fetch = DailyImageStore.Fetch { url ->
            asked += url
            if (url.endsWith("TIME=2026-11-05")) empty else clean
        }
        assertTrue(NightLightStore.refresh(context, now))
        val sky = NightLightStore.skyFor(context, now)!!
        assertEquals("2026-11-04", sky.clean!!.day)
        assertEquals(1, sky.clean!!.lagDays)
    }

    /**
     * A day that is still being flown is asked for again once it is old
     * enough; a finished day is left alone.
     */
    @Test
    fun `today's raw night is fetched again as it fills in`() {
        mode("raw")
        val now = utc(2026, 11, 5, 15)
        assertTrue(NightLightStore.refresh(context, now))
        val first = asked.count { it.endsWith("TIME=2026-11-05") }
        assertEquals(1, first)
        // Too soon: nothing is stale.
        assertFalse(NightLightStore.stale(context, now + 60_000L))
        // Six hours on, today is worth another look and yesterday is not.
        val later = now + DailyImageStore.EVERY_MS
        assertTrue(NightLightStore.stale(context, later))
        // Age the file so the store can see it is old. The test clock and
        // the file system's are two different clocks.
        java.io.File(context.cacheDir, "lights-raw-2026-11-05.png")
            .setLastModified(System.currentTimeMillis() - DailyImageStore.EVERY_MS - 1)
        NightLightStore.refresh(context, System.currentTimeMillis())
        assertEquals(2, asked.count { it.endsWith("TIME=" + NightLights.dayFor(System.currentTimeMillis())) || it.endsWith("TIME=2026-11-05") })
    }

    /** The greys are the picture's, one byte a pixel, and a city is bright in them. */
    @Test
    fun `the layer is the picture's brightness`() {
        mode("both")
        val now = utc(2026, 11, 5, 15)
        NightLightStore.refresh(context, now)
        val sky = NightLightStore.skyFor(context, now)!!
        val c = sky.clean!!
        assertEquals(720, c.width)
        assertEquals(360, c.height)
        // Madrid, which is lit, against the middle of the Pacific, which
        // is not. The sample is 720 across, so a degree is two pixels.
        val madrid = c.at((180.0 - 3.7) / 360.0, (90.0 - 40.4) / 180.0)
        val pacific = c.at((180.0 - 150.0) / 360.0, (90.0 - 10.0) / 180.0)
        assertTrue("Madrid reads $madrid and the Pacific $pacific", madrid > pacific + 20)
        assertNotNull(sky.raw)
    }

    /**
     * The same picture is the same layer, and not copied out again; a
     * picture fetched again is read again.
     */
    @Test
    fun `the greys are copied out once per picture`() {
        mode("clean")
        val now = utc(2026, 11, 5, 15)
        NightLightStore.refresh(context, now)
        val a = NightLightStore.skyFor(context, now)!!.clean!!
        val b = NightLightStore.skyFor(context, now)!!.clean!!
        assertTrue("the greys were copied out again for the same picture", a.lum === b.lum)
        // The file written again — a night still filling in — is a new
        // picture, and the greys follow it.
        val file = java.io.File(context.cacheDir, "lights-clean-2026-11-05.png")
        file.setLastModified(file.lastModified() + 5_000L)
        val c = NightLightStore.skyFor(context, now)!!.clean!!
        assertFalse("a rewritten picture kept its old greys", a.lum === c.lum)
    }

    /** The clouds still fill their week through the shared store. */
    @Test
    fun `the clouds are unchanged by sharing the store`() {
        val realClouds = CloudStore.fetch
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(Prefs.HEMISPHERE_CLOUDS, true).commit()
            CloudStore.forget(context)
            CloudStore.fetch = CloudStore.Fetch { resource("gibs-sample.jpg") }
            val now = utc(2026, 11, 5, 15)
            assertTrue(CloudStore.refresh(context, now))
            assertNotNull(CloudStore.cached(context, now))
        } finally {
            CloudStore.fetch = realClouds
            CloudStore.forget(context)
        }
    }
}
