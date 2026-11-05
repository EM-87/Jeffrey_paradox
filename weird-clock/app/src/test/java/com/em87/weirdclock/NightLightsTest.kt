package com.em87.weirdclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn a grey picture of the night into light on a globe,
 * and the one rule about which night it is.
 *
 * No Android in here: a byte of grey in, a pixel of colour out, and a
 * date arithmetic that was wrong in the obvious way before it was checked
 * against the service.
 */
class NightLightsTest {

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    /**
     * The day is the UTC day, full stop.
     *
     * Checked against the service rather than reasoned out: a day's
     * picture fills in from the east, starting at the longitude where the
     * UTC day began, and Asia — whose local night is long over by the
     * afternoon UTC — stays blank in it until the UTC clock gets there. A
     * rule that filed each place's night under its local date would have
     * put Tokyo's night a day late.
     */
    @Test
    fun `the day is the UTC day, in Tokyo as in Lima`() {
        // 15:00 UTC on the 5th is already the 6th in Tokyo and still the
        // 5th in Lima; the file is the 5th's for both.
        val at = utc(2026, 11, 5, 15)
        assertEquals("2026-11-05", NightLights.dayFor(at))
        // And one minute before midnight UTC is still that day, whatever
        // the local clocks say.
        assertEquals("2026-11-05", NightLights.dayFor(utc(2026, 11, 5, 23, 59)))
        assertEquals("2026-11-06", NightLights.dayFor(utc(2026, 11, 6, 0, 0)))
    }

    /** Today's picture is the one still being flown; yesterday's is done. */
    @Test
    fun `today is still filling and yesterday is not`() {
        val now = utc(2026, 11, 5, 15)
        assertTrue(NightLights.stillFilling("2026-11-05", now))
        assertFalse(NightLights.stillFilling("2026-11-04", now))
    }

    /** How far behind a picture is, counted in UTC days. */
    @Test
    fun `lag is counted in whole UTC days`() {
        val at = utc(2026, 11, 5, 15)
        assertEquals(0, NightLights.lagDays("2026-11-05", at))
        assertEquals(1, NightLights.lagDays("2026-11-04", at))
        assertEquals(3, NightLights.lagDays("2026-11-02", at))
        assertEquals(null, NightLights.lagDays("yesterday", at))
    }

    /**
     * Two or three days is ordinary for the clean night and a day for the
     * raw one; past that the globe says so on its face.
     */
    @Test
    fun `a picture is overdue past what is ordinary for its layer`() {
        assertFalse(NightLights.overdue(raw = false, lagDays = 3))
        assertTrue(NightLights.overdue(raw = false, lagDays = 4))
        assertFalse(NightLights.overdue(raw = true, lagDays = 1))
        assertTrue(NightLights.overdue(raw = true, lagDays = 2))
    }

    /** The sea is dark, a village glints, a capital is bright, and the order holds. */
    @Test
    fun `glow rises with grey and starts above the sea`() {
        assertEquals(0, NightLights.glow(0))
        // The open sea's worst reading on a real night, which is not light.
        assertEquals(0, NightLights.glow(48, NightLights.CLEAN_FLOOR))
        assertEquals(0, NightLights.glow(89, NightLights.RAW_FLOOR))
        // The dimmest towns, which are.
        assertTrue("a town is invisible", NightLights.glow(98, NightLights.CLEAN_FLOOR) > 60)
        assertTrue("a town is invisible, raw", NightLights.glow(130, NightLights.RAW_FLOOR) > 60)
        var last = -1
        for (lum in 0..255) {
            val g = NightLights.glow(lum)
            assertTrue("glow fell from $last to $g at $lum", g >= last)
            last = g
        }
        assertEquals(NightLights.BRIGHTEST, NightLights.glow(255))
    }

    /**
     * The residue is what the raw night has over the clean one, after a
     * floor — so a town that reads the same in both is not cyan, and an
     * aurora over dark sea is.
     */
    @Test
    fun `residue is the raw night's surplus over the clean one, above a floor`() {
        assertEquals(0, NightLights.residue(120, 120))
        assertEquals(0, NightLights.residue(100, 120))
        assertEquals(0, NightLights.residue(120 + NightLights.RESIDUE_FLOOR, 120))
        assertTrue(NightLights.residue(120 + NightLights.RESIDUE_FLOOR + 40, 120) > 0)
        // The open sea on a real night: 89 at worst in the raw picture
        // over 48 at worst in the clean one is not an aurora.
        assertEquals("the sea is cyan", 0, NightLights.residue(89, 7))
        assertTrue("an aurora over the sea is not picked out", NightLights.residue(146, 7) > 60)
    }

    /** A transparent pixel is a place nobody flew over, and is dark. */
    @Test
    fun `an unflown pixel is dark`() {
        assertEquals(0, NightLights.luminance(0x00FFFFFF))
        assertEquals(200, NightLights.luminance(0xFFC8C8C8.toInt()))
    }

    /** Amber for the lights, cyan for the difference, and nothing for nothing. */
    @Test
    fun `each mode paints its own colours`() {
        assertEquals(0, NightLights.pixel(NightLights.Mode.NONE, 200, 250, 0.0))
        val clean = NightLights.pixel(NightLights.Mode.CLEAN, 200, 7, 0.0)
        assertEquals(NightLights.AMBER and 0xFFFFFF, clean and 0xFFFFFF)
        assertTrue(clean ushr 24 > 100)
        // Raw alone is amber too: it is the lights, read differently.
        val raw = NightLights.pixel(NightLights.Mode.RAW, 7, 200, 0.0)
        assertEquals(NightLights.AMBER and 0xFFFFFF, raw and 0xFFFFFF)
        // Both: a town the same in both is plain amber; an aurora over
        // dark sea is cyan; a town brighter last night is amber with cyan
        // over it, which is neither pure colour.
        val same = NightLights.pixel(NightLights.Mode.BOTH, 200, 200, 0.0)
        assertEquals(NightLights.AMBER and 0xFFFFFF, same and 0xFFFFFF)
        val aurora = NightLights.pixel(NightLights.Mode.BOTH, 7, 180, 0.0)
        assertEquals(NightLights.CYAN and 0xFFFFFF, aurora and 0xFFFFFF)
        val brighter = NightLights.pixel(NightLights.Mode.BOTH, 120, 240, 0.0)
        assertTrue(brighter and 0xFFFFFF != NightLights.AMBER and 0xFFFFFF)
        assertTrue(brighter and 0xFFFFFF != NightLights.CYAN and 0xFFFFFF)
        // And at the limb there is nothing, like the clouds.
        assertEquals(0, NightLights.pixel(NightLights.Mode.CLEAN, 255, 255, 1.0) ushr 24)
    }

    /** Source-over, checked at the two ends and in the middle. */
    @Test
    fun `over composes like a painter`() {
        val amber = 0xFFFFC46A.toInt()
        assertEquals(amber, NightLights.over(0, amber))
        assertEquals(amber, NightLights.over(amber, 0))
        val half = NightLights.over(0x80000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(255, half ushr 24)
        assertTrue("a half-black over white is not grey: ${Integer.toHexString(half)}", (half and 0xFF) in 120..136)
    }

    /** The request names the layer and the day and nothing about anybody. */
    @Test
    fun `the request is for the whole world on one day`() {
        val url = NightLights.url(NightLights.CLEAN_NOAA20, "2026-11-03")
        assertTrue(url.contains("LAYERS=" + NightLights.CLEAN_NOAA20))
        assertTrue(url.contains("TIME=2026-11-03"))
        assertTrue(url.contains("WIDTH=1440&HEIGHT=720"))
        assertTrue(url.contains("BBOX=-90,-180,90,180"))
        assertFalse(url.contains("lat", ignoreCase = true))
        assertEquals(listOf(NightLights.RAW_NOAA20, NightLights.RAW_SNPP),
            NightLights.rawUrls("2026-11-03").map { it.substringAfter("LAYERS=").substringBefore("&") })
    }

    /** The setting spells the modes and anything else is none. */
    @Test
    fun `the mode is read from its key`() {
        assertEquals(NightLights.Mode.BOTH, NightLights.Mode.of("both"))
        assertEquals(NightLights.Mode.NONE, NightLights.Mode.of(null))
        assertEquals(NightLights.Mode.NONE, NightLights.Mode.of("nonsense"))
        assertTrue(NightLights.Mode.BOTH.clean && NightLights.Mode.BOTH.raw)
        assertTrue(NightLights.Mode.RAW.raw && !NightLights.Mode.RAW.clean)
    }
}
