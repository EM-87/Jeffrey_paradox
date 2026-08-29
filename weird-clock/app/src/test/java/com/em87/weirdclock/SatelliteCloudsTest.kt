package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The request that fetches a day's weather, and the rule that turns a
 * photograph into cloud.
 *
 * Two things worth pinning and they are different in kind. The request is
 * a promise about what leaves the phone: one picture of the whole planet,
 * with nothing in it that says who asked. The rule is a claim about a
 * photograph — that what it paints white is cloud and not ocean, land or
 * an unphotographed gap — and a rule that is slightly wrong there produces
 * a picture that still looks like weather, which is the reason it is
 * checked here rather than by eye.
 */
class SatelliteCloudsTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    /**
     * The day asked for is always one that has finished and been
     * processed, whatever hour it is asked at.
     *
     * The two ends are what this is for. At one minute past midnight UTC
     * "yesterday" is a day that ended a minute ago, and NASA have not
     * assembled it — a naive twenty-four hours would ask for a half-empty
     * world at exactly the hour somebody looks at a bedside clock.
     */
    @Test
    fun `the day asked for is one that is finished`() {
        // Just past midnight UTC: the day before yesterday.
        assertEquals("2026-06-19", SatelliteClouds.dayFor(utc(2026, 6, 21, 0)))
        // Late in the evening: yesterday, complete and hours old.
        assertEquals("2026-06-20", SatelliteClouds.dayFor(utc(2026, 6, 21, 23)))
        // And it rolls over months and years without help.
        assertEquals("2025-12-31", SatelliteClouds.dayFor(utc(2026, 1, 1, 12)))
        assertEquals("2026-02-28", SatelliteClouds.dayFor(utc(2026, 3, 1, 20)))
    }

    /**
     * The request is the whole planet and carries no place.
     *
     * Every other thing this app fetches carries a latitude and a
     * longitude — rounded, but still a place. This one does not, and that
     * is not an accident of the API: it is the reason this layer can be on
     * by default while the weather is not.
     */
    @Test
    fun `the request asks for the whole world and says nothing about where you are`() {
        val url = SatelliteClouds.url(utc(2026, 6, 21, 12))
        assertTrue(url.startsWith(SatelliteClouds.HOST))
        assertTrue("the wrong product", url.contains("LAYERS=${SatelliteClouds.LAYER}"))
        assertTrue("not the whole earth", url.contains("BBOX=-90,-180,90,180"))
        assertTrue(url.contains("WIDTH=${SatelliteClouds.WIDTH}"))
        assertTrue(url.contains("HEIGHT=${SatelliteClouds.HEIGHT}"))
        assertTrue("no day was asked for", url.contains("TIME=2026-06-20"))
        for (word in listOf("lat", "lon", "LAT", "LON")) {
            assertFalse("the request mentions $word", url.contains(word))
        }
    }

    /**
     * White is cloud; ocean, land and the gaps between orbits are not.
     *
     * The gaps matter most. A polar orbiter does not photograph the whole
     * equator in a day and the mosaic has black wedges where nobody flew
     * — and black has to come out as *no claim* rather than as clear sky,
     * because the map underneath showing through is the only way a clock
     * can say "nobody looked".
     */
    @Test
    fun `it finds cloud and leaves everything else alone`() {
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        assertEquals("an unphotographed wedge came out as weather",
            0, SatelliteClouds.veil(rgb(0, 0, 0)))
        assertTrue("cloud is not cloud", SatelliteClouds.veil(rgb(250, 250, 252)) > 180)
        assertEquals("the ocean grew clouds", 0, SatelliteClouds.veil(rgb(12, 26, 60)))
        assertEquals("a forest grew clouds", 0, SatelliteClouds.veil(rgb(56, 92, 44)))
        assertEquals("the Sahara grew clouds", 0, SatelliteClouds.veil(rgb(214, 178, 120)))
        // And haze is *much* thinner than cloud rather than absent or
        // solid. The bound is tight on purpose: a straight ramp puts thin
        // haze at a third of full cloud, which over the tropics is a flat
        // grey wash and a picture of nothing. Loose, this test passed a
        // linear curve and said nothing.
        val haze = SatelliteClouds.veil(rgb(190, 192, 196))
        assertTrue("haze vanished entirely: $haze", haze > 0)
        assertTrue("haze is nearly as solid as cloud: $haze", haze < 60)
        assertTrue("cloud is drawn opaque", SatelliteClouds.THICKEST < 255)
    }

    /**
     * The veil is refused where the projection cannot carry it.
     *
     * All three views are a ball now, and a ball has a limb: at the rim
     * you are looking along the surface, so a thin band of screen carries
     * an enormous amount of world and a bright pixel there becomes a
     * halo the width of a finger made out of one pixel's worth of answer.
     * That is what the first version drew, and it read as a scratch on
     * the lens.
     */
    @Test
    fun `the edge of the projection gets no cloud`() {
        assertEquals(1f, SatelliteClouds.edge(0.0), 0.0001f)
        assertEquals("the middle of the world lost its clouds",
            1f, SatelliteClouds.edge(0.8), 0.0001f)
        assertTrue("the limb kept its full veil", SatelliteClouds.edge(0.995) < 0.4f)
        assertEquals("something was drawn on the rim itself",
            0f, SatelliteClouds.edge(1.0), 0.0001f)
        // And it really fades rather than switching off: the reading
        // halfway down the limb is halfway down the veil.
        val facing = SatelliteClouds.LIMB / 2.0
        val half = SatelliteClouds.edge(Math.sqrt(1.0 - facing * facing))
        assertEquals("the fade is not a fade", 0.5f, half, 0.02f)
    }

    /** And the tint carries the two of them together, as white. */
    @Test
    fun `the tint is white at the strength that place has earned`() {
        val cloud = (0xFF shl 24) or 0xFAFAFC
        val middle = SatelliteClouds.tint(cloud, 0.1)
        assertEquals("it is not white", 0xFFFFFF, middle and 0x00FFFFFF)
        assertEquals(SatelliteClouds.veil(cloud), (middle ushr 24) and 0xFF)
        val rim = SatelliteClouds.tint(cloud, 1.0)
        assertEquals("the rim was painted after all", 0, (rim ushr 24) and 0xFF)
    }
}
