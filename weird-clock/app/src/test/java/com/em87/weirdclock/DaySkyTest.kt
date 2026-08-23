package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sky on a dial that is not made of night.
 *
 * A black disc dropped into a white clock does not read as a window onto
 * space; it reads as a hole. So the pale themes get a blue sky, and
 * everything drawn on it has to survive the change of ground — which is
 * the part that is easy to get wrong, because a planet painted to glow on
 * black is nearly invisible on pale blue and nobody notices until they see
 * it on a phone in daylight.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class DaySkyTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun luma(c: Int): Float {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    // ---------------------------------------------------- which sky it gets

    /** Dark themes are dark and pale ones are pale, by the ink they need. */
    @Test
    fun `a pale face is the one that needs dark ink`() {
        assertFalse("midnight counts as a pale face", ClockThemes.isPaleFace(ClockThemes.MIDNIGHT))
        assertTrue("ivory does not count as a pale face", ClockThemes.isPaleFace(ClockThemes.IVORY))
        assertTrue("daylight does not count as a pale face", ClockThemes.isPaleFace(ClockThemes.DAYLIGHT))
    }

    /** The day sky is blue, and it is a sky rather than a wash. */
    @Test
    fun `the day sky is blue`() {
        val face = ClockThemes.DAY_SKY.face
        val r = (face shr 16) and 0xFF
        val g = (face shr 8) and 0xFF
        val b = face and 0xFF
        assertTrue("the day sky is not blue: it is $r $g $b", b > r && b > g)
        assertTrue("the day sky is not light enough to sit in a white case", luma(face) > 140f)
        assertTrue("the night sky stopped being dark", luma(ClockThemes.SKY.face) < 40f)
    }

    /** And its ink is dark, because pale ink on pale blue is nothing. */
    @Test
    fun `the day sky is written on in dark ink`() {
        val sky = ClockThemes.DAY_SKY
        assertTrue(
            "the day sky's ink is not dark enough to read",
            luma(sky.face) - luma(sky.numeral) > 80f
        )
        assertTrue(
            "even the faint ticks are the wrong way round on the day sky",
            luma(sky.minorTick) < luma(sky.face)
        )
    }

    // ------------------------------------------------------- the planets

    /**
     * Every planet is legible against whichever sky it is drawn on.
     *
     * This is the whole of what the user asked for and the reason the
     * colours are not simply the same eight on both: Venus is a pale
     * yellow-white chosen to glow on black, and on pale blue it is
     * effectively the background. The rule is one number — a planet must
     * differ from its sky by enough to be a dot — and it is asked of both
     * skies, so neither can be fixed by breaking the other.
     */
    @Test
    fun `every planet stands out from its own sky`() {
        for (sky in listOf(ClockThemes.SKY, ClockThemes.DAY_SKY)) {
            val ground = luma(sky.face)
            for (body in Orrery.planets) {
                val gap = kotlin.math.abs(luma(OrreryDial.colourOf(body, sky)) - ground)
                assertTrue(
                    "$body is invisible on the ${if (ground > 140f) "day" else "night"} sky: $gap",
                    gap > 45f
                )
            }
        }
    }

    /** They darken for the day sky rather than being repainted at random. */
    @Test
    fun `the planets keep who they are on the day sky`() {
        for (body in Orrery.planets) {
            val night = OrreryDial.colourOf(body, ClockThemes.SKY)
            val day = OrreryDial.colourOf(body, ClockThemes.DAY_SKY)
            assertNotEquals("$body was not adjusted for the day sky", night, day)
            assertTrue("$body did not darken for the day sky", luma(day) < luma(night))
            // The hue survives: a red planet is still redder than it is
            // blue, and a blue one still bluer. A version that simply
            // painted everything the same dark grey would pass the
            // legibility test above and fail this.
            val nightRedder = ((night shr 16) and 0xFF) - (night and 0xFF)
            val dayRedder = ((day shr 16) and 0xFF) - (day and 0xFF)
            assertEquals(
                "$body changed colour rather than brightness",
                Math.signum(nightRedder.toFloat()), Math.signum(dayRedder.toFloat()), 0f
            )
        }
    }

    // ------------------------------------------------------- on the dial

    /**
     * And what actually reaches the screen is that sky, not the night one.
     *
     * The theme is resolved in one place and used in another, so the
     * palette being right proves nothing on its own — the middle of a pale
     * dial with the sky open is the measurement.
     */
    @Test
    fun `a pale dial opens onto a blue sky`() {
        val pale = paintedSky("daylight")
        assertTrue("a daylight dial opened onto a black sky: $pale", luma(pale) > 120f)
        // Terminal rather than midnight: "midnight" is the key that follows
        // the system, and the system under a test is in daylight, so asking
        // for it here would have asked for the pale dial twice and passed
        // whatever the sky did.
        val dark = paintedSky("terminal")
        assertTrue("a dark dial did not open onto a dark sky: $dark", luma(dark) < 60f)
    }

    /** The middle pixel of a dial with the sky open, in the given theme. */
    private fun paintedSky(theme: String): Int {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Prefs.ORRERY, true)
            .putString(Prefs.THEME, theme)
            .commit()
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1200))
        val bitmap = android.graphics.Bitmap.createBitmap(
            clock.width, clock.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        clock.draw(android.graphics.Canvas(bitmap))
        // Off to one side of the middle, which is where the Sun is drawn.
        val at = bitmap.getPixel(clock.width / 2, clock.height / 2 + clock.height / 5)
        bitmap.recycle()
        return at
    }
}
