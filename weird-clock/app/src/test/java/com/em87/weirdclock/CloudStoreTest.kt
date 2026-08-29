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
 * The largest thing this app ever downloads, and every reason not to.
 *
 * Eighty kilobytes against the weather's few hundred bytes, so the
 * questions are the same ones and the answers matter more: does the switch
 * mean anything, is it fetched again when it does not need to be, and what
 * happens when what comes back is not a photograph of the earth.
 *
 * That last one is the interesting failure. A picture is decoded lazily
 * every time the globe is baked, so a cache holding an error page is a
 * globe that quietly fails to draw clouds for six hours with nothing
 * anywhere saying why — which is why the decode happens before the file is
 * written and not after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val asked = ArrayList<String>()
    private val real = CloudStore.fetch

    /** A real day's mosaic, kept beside the tests rather than fetched. */
    private fun sample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("gibs-sample.jpg")!!.use { it.readBytes() }

    @Before
    fun watchTheWire() {
        asked.clear()
        CloudStore.fetch = CloudStore.Fetch { url ->
            asked += url
            sample()
        }
        switchedOn(weather = true, clouds = true)
        CloudStore.forget(context)
    }

    @After
    fun putTheSocketBack() {
        CloudStore.fetch = real
        CloudStore.forget(context)
    }

    private fun switchedOn(weather: Boolean, clouds: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.WEATHER, weather)
            .putBoolean(Prefs.HEMISPHERE_CLOUDS, clouds)
            .commit()
    }

    /**
     * With the weather off, nothing leaves the phone — whatever the
     * clouds row says.
     *
     * Two switches and the outer one wins. Somebody who has never turned
     * the weather on has a clock that has never opened a socket, and a
     * cloud layer defaulting to on must not be the thing that breaks that
     * promise.
     */
    @Test
    fun `with the weather off there is no picture and no request`() {
        switchedOn(weather = false, clouds = true)
        assertFalse(CloudStore.wanted(context))
        assertFalse(CloudStore.refresh(context))
        CloudStore.refreshInBackground(context)
        assertTrue("it went to NASA with the weather switch off", asked.isEmpty())
        assertNull(CloudStore.cached(context))
    }

    /** And with the row itself off, the same. */
    @Test
    fun `with the row off there is no picture either`() {
        switchedOn(weather = true, clouds = false)
        assertFalse(CloudStore.wanted(context))
        assertFalse(CloudStore.refresh(context))
        assertTrue(asked.isEmpty())
    }

    /** On, one fetch puts a picture on disc that the globe can read. */
    @Test
    fun `on, it fetches once and the picture decodes`() {
        assertTrue(CloudStore.refresh(context))
        assertEquals(1, asked.size)
        assertTrue("it asked somewhere else", asked.single().startsWith(SatelliteClouds.HOST))
        assertNotNull("what was kept is not a picture", CloudStore.cached(context))
    }

    /**
     * A fresh picture is not fetched again, and a stale one is.
     *
     * Six hours, which is well inside a day and outside anything a phone
     * does in a morning. The layer only changes once a day; the interval
     * is short of that so a phone that was asleep when the new one landed
     * does not wait until tomorrow for it.
     */
    @Test
    fun `a fresh picture is left alone and an old one is replaced`() {
        val now = TimeKeeper.nowMs()
        assertTrue(CloudStore.refresh(context, now))
        assertFalse("it went back for the same picture", CloudStore.stale(context, now))
        assertTrue(CloudStore.stale(context, now + CloudStore.EVERY_MS + 1))
        // And a clock set backwards is a reason to refetch, not to sulk
        // for six hours.
        assertTrue(CloudStore.stale(context, now - 60_000L))
    }

    /**
     * What comes back is decoded before it is kept.
     *
     * An error page with a picture on it, a redirect to something else, a
     * service having a bad day: all of them are bytes, and none of them is
     * the earth. Keeping one means the globe silently draws no clouds for
     * six hours.
     */
    @Test
    fun `something that is not a picture is not kept`() {
        CloudStore.fetch = CloudStore.Fetch { "<html><body>Service Unavailable</body></html>".toByteArray() }
        assertFalse("an error page was written to the cache", CloudStore.refresh(context))
        assertNull(CloudStore.cached(context))
        // And a service that is simply down leaves nothing behind either.
        CloudStore.fetch = CloudStore.Fetch { null }
        assertFalse(CloudStore.refresh(context))
        assertNull(CloudStore.cached(context))
    }

    /**
     * A good picture already on disc survives a bad fetch.
     *
     * The day NASA is down is a day the globe wears yesterday's clouds,
     * not a day it loses them — which is the same rule the weather
     * follows and for the same reason.
     */
    @Test
    fun `a bad fetch does not throw away a good picture`() {
        assertTrue(CloudStore.refresh(context))
        CloudStore.fetch = CloudStore.Fetch { null }
        assertFalse(CloudStore.refresh(context))
        assertNotNull("the outage took the picture with it", CloudStore.cached(context))
    }

    /** And switching it off puts the picture beyond reach. */
    @Test
    fun `forgetting it leaves nothing to read`() {
        assertTrue(CloudStore.refresh(context))
        assertNotNull(CloudStore.cached(context))
        CloudStore.forget(context)
        assertNull(CloudStore.cached(context))
        assertTrue("it thinks it still has one", CloudStore.stale(context))
    }
}
