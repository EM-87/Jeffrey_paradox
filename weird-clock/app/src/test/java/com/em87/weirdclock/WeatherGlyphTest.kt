package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The weather on the sky token, counted in pixels and written to disk.
 *
 * The claim worth checking here is the one that is easy to get backwards:
 * **a clear sky draws nothing.** A clock that puts a sun-with-no-cloud
 * badge on itself has added a picture of nothing, and the sun already
 * there is a better drawing of a clear day than any glyph. So the test is
 * that the token with clear weather is pixel-for-pixel the token with no
 * weather at all, and that each step worse than clear puts more ink on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherGlyphTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }

    /** Noon, so the token underneath is the sun rather than the moon. */
    private val noon = java.util.Calendar.getInstance().apply {
        set(2026, java.util.Calendar.AUGUST, 29, 12, 0, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun token(look: Weather.Look?, sure: Boolean = true): Bitmap {
        val size = 220
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF10141C.toInt())
        val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD873.toInt() }
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3040.toInt() }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD873.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        SkyGlyph.draw(
            canvas, size / 2f, size / 2f, size * 0.16f, lit, dark, rim,
            timeOfDayMs = 12L * 3_600_000L, whenMs = noon,
            weather = look, weatherSure = sure
        )
        return bitmap
    }

    private fun ink(bitmap: Bitmap): Int {
        var lit = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != 0xFF10141C.toInt()) lit++
            }
        }
        return lit
    }

    private fun shoot(look: Weather.Look?, name: String): Bitmap {
        val bitmap = token(look)
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    /**
     * A clear sky is the sun, and nothing whatever added to it.
     *
     * Pixel for pixel with the token that was never told about weather —
     * which is the only way to say "nothing" and mean it.
     */
    @Test
    fun `a clear sky draws nothing at all`() {
        val plain = shoot(null, "sky-token-plain")
        val clear = shoot(Weather.Look.CLEAR, "sky-token-clear")
        assertFalse(WeatherGlyph.marks(Weather.Look.CLEAR))
        assertFalse(WeatherGlyph.marks(null))
        assertTrue(plain.sameAs(clear))
        assertEquals(ink(plain), ink(clear))
    }

    /**
     * And every step worse than clear puts more on the token than the one
     * before it.
     *
     * Not a golden image — a smoke alarm. Cloud is a cloud, overcast is a
     * bigger one, rain is that plus drops, and a storm is that plus a
     * bolt, so the ink can only go up. It went *down* on the first
     * attempt, because the cloud was being filled in the lit ink and was
     * therefore a bite taken out of the sun.
     */
    @Test
    fun `each worse sky is more ink than the last`() {
        val clear = ink(shoot(Weather.Look.CLEAR, "sky-token-clear"))
        val cloudy = ink(shoot(Weather.Look.CLOUDY, "sky-token-cloudy"))
        val overcast = ink(shoot(Weather.Look.OVERCAST, "sky-token-overcast"))
        val rain = ink(shoot(Weather.Look.RAIN, "sky-token-rain"))
        val storm = ink(shoot(Weather.Look.STORM, "sky-token-storm"))
        assertTrue("a cloudy sky is not drawn: $cloudy against $clear", cloudy > clear)
        assertTrue("overcast is not heavier than cloudy", overcast > cloudy)
        assertTrue("rain adds nothing to overcast", rain > overcast)
        assertTrue("a storm adds nothing to overcast: $storm", storm > overcast)
        // A storm is not more *ink* than rain — a bolt is one shape and
        // three drops are three — so counting pixels is the wrong question
        // for that pair. What has to be true is that no two of the five
        // are the same picture: five skies drawn four ways is a clock that
        // cannot tell you which of two it is.
        val skies = Weather.Look.entries.map { token(it) }
        for (i in skies.indices) {
            for (j in i + 1 until skies.size) {
                assertFalse(
                    "${Weather.Look.entries[i]} and ${Weather.Look.entries[j]} " +
                        "are the same picture",
                    skies[i].sameAs(skies[j])
                )
            }
        }
    }

    /**
     * A reading nothing confirmed is drawn faintly rather than hidden.
     *
     * It is what is left on the day the other services are down, which is
     * the day this whole design exists for. Hiding it would throw away the
     * reading; drawing it at full strength would be the clock claiming
     * more than it knows.
     */
    @Test
    fun `an unconfirmed sky is drawn faintly and not hidden`() {
        val sure = token(Weather.Look.RAIN, sure = true)
        val lone = token(Weather.Look.RAIN, sure = false)
        File(outDir, "sky-token-rain-lone.png").outputStream().use {
            lone.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertFalse("a lone reading is drawn the same as an agreed one", sure.sameAs(lone))
        // Still there — the same shapes, just quieter.
        assertTrue("a lone reading was not drawn at all", ink(lone) > ink(token(null)))
    }
}
