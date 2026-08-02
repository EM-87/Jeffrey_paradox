package com.em87.weirdclock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
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
 * The sky complication, checked by looking at the pixels.
 *
 * 14.1 shipped this as a second glyph that drew only when the dial had no
 * chrono provider — which is every dial except the ones that most needed it:
 * the little faces on the alarm cards and the big one while a time is being
 * wound. It appeared on C0 alone, stacked on top of the moon, and I did not
 * catch it because I reasoned about the flag instead of looking at the
 * drawing. So this test looks at the drawing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
// Robolectric's default canvas is a no-op that records nothing, so a test
// comparing pixels would pass whatever the view drew — including nothing.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyTokenTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        DayNight.configure(context)
    }

    private fun dial(fixedMs: Long? = null): ClockView =
        ClockView(context).apply {
            if (fixedMs != null) chronoProvider = { fixedMs }
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
        }

    /** How many pixels the view paints, as a crude but honest fingerprint. */
    private fun render(view: ClockView): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (x in 0 until 720 step 2) {
            for (y in 0 until 720 step 2) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return true
            }
        }
        return false
    }

    /** The bug 14.1 shipped: the glyph skipped every dial showing a fixed time. */
    @Test
    fun `the complication draws on a dial driven by a chrono provider`() {
        val seven = 7 * 3_600_000L
        val without = render(dial(seven).apply { showMoonPhase = false })
        val with = render(dial(seven).apply { showMoonPhase = true })
        assertTrue(
            "a mini dial with the sky switched on must not look identical to one without",
            differs(without, with)
        )
    }

    @Test
    fun `and on a plain clock, as it always did`() {
        val without = render(dial().apply { showMoonPhase = false })
        val with = render(dial().apply { showMoonPhase = true })
        assertTrue(differs(without, with))
    }

    /**
     * No location, no sun — ever. The moon's phase is arithmetic that works
     * anywhere; a sunrise is not, and inventing one would put a sun on the
     * dial at midnight for anyone who declined the permission.
     */
    @Test
    fun `without a fix the sky never shows the sun`() {
        for (minute in 0 until 1440 step 10) {
            assertNull("minute $minute", DayNight.sunIsUp(minute))
        }
    }

    @Test
    fun `with a fix it answers, and answers differently at noon and at midnight`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putFloat(Prefs.LAST_LATITUDE, 40.4f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.7f)
            .commit()
        DayNight.configure(context)
        // 21 June, when Madrid's day is longest and the answer is not close
        // to either edge at these two hours.
        val midsummer = java.util.Calendar.getInstance().apply {
            clear(); set(2026, java.util.Calendar.JUNE, 21)
        }.timeInMillis
        assertEquals(true, DayNight.sunIsUp(13 * 60, midsummer))
        assertEquals(false, DayNight.sunIsUp(1 * 60, midsummer))
        assertNotNull(DayNight.sunIsUp(0, midsummer))
    }

    /**
     * The two mark readings must not be confused for one another, so the
     * near-side colour differs between them. Blue is shared on purpose.
     */
    @Test
    fun `mark colours follow the chosen reading`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val theme = ClockThemes.MIDNIGHT

        prefs.edit().putString(Prefs.MARK_COLORS, DayNight.MARKS_CLOCK).commit()
        DayNight.configure(context)
        assertEquals(theme.amMark, DayNight.markColor(theme, false))
        assertEquals(theme.pmMark, DayNight.markColor(theme, true))

        prefs.edit()
            .putString(Prefs.MARK_COLORS, DayNight.MARKS_SUN)
            .putFloat(Prefs.LAST_LATITUDE, 40.4f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.7f)
            .commit()
        DayNight.configure(context)
        assertEquals(theme.sunMark, DayNight.markColor(theme, false))
        assertEquals(theme.pmMark, DayNight.markColor(theme, true))
        assertFalse("green and yellow must be distinguishable", theme.amMark == theme.sunMark)
    }

    /** Asking for solar marks with nowhere to stand falls back, it does not guess. */
    @Test
    fun `solar marks without a fix fall back to the turn of the dial`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Prefs.MARK_COLORS, DayNight.MARKS_SUN).commit()
        DayNight.configure(context)
        assertEquals(ClockThemes.MIDNIGHT.amMark, DayNight.markColor(ClockThemes.MIDNIGHT, false))
        assertFalse(DayNight.isDarkAt(9, 0))
        assertTrue(DayNight.isDarkAt(21, 0))
    }

    // ------------------------------------------------ the twilight glyph

    private fun located() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putFloat(Prefs.LAST_LATITUDE, 40.4f)
            .putFloat(Prefs.LAST_LONGITUDE, -3.7f)
            .commit()
        DayNight.configure(context)
    }

    private fun equinox(): Long = java.util.Calendar.getInstance().apply {
        clear(); set(2026, java.util.Calendar.MARCH, 20)
    }.timeInMillis

    /**
     * The step this was written to remove: at one minute a sun and at the
     * next a moon. Somewhere between full day and full night there has to
     * be a run of minutes that is neither.
     */
    @Test
    fun `there is a real twilight between the sun and the moon`() {
        located()
        val day = equinox()
        val twilit = (0 until 1440).filter { DayNight.sky(it, day) is DayNight.Sky.Twilight }
        assertTrue("found ${twilit.size} twilit minutes", twilit.size >= 40)
        // Two runs of them, morning and evening, not one smear.
        val runs = twilit.zipWithNext().count { (a, b) -> b - a > 1 } + 1
        assertEquals(2, runs)
    }

    /** Noon is day and the small hours are night, whatever else changes. */
    @Test
    fun `noon and the small hours are never twilight`() {
        located()
        val day = equinox()
        assertEquals(DayNight.Sky.Day, DayNight.sky(12 * 60, day))
        assertEquals(DayNight.Sky.Night, DayNight.sky(3 * 60, day))
    }

    /**
     * The sun has to go *down* through the evening, not wander. This is the
     * whole point of the glyph: it moves the way the thing it draws moves.
     */
    @Test
    fun `the sun sinks monotonically through the evening twilight`() {
        located()
        val day = equinox()
        val evening = (12 * 60 until 1440)
            .mapNotNull { DayNight.sky(it, day) as? DayNight.Sky.Twilight }
            .map { it.sunk }
        assertTrue(evening.isNotEmpty())
        for ((a, b) in evening.zipWithNext()) {
            assertTrue("$a then $b", b >= a)
        }
        assertTrue("starts at the horizon", evening.first() < 0.1f)
        assertTrue("ends fully down", evening.last() > 0.9f)
    }

    /** And rises through the morning, which is the same picture backwards. */
    @Test
    fun `and rises through the morning twilight`() {
        located()
        val day = equinox()
        val morning = (0 until 12 * 60)
            .mapNotNull { DayNight.sky(it, day) as? DayNight.Sky.Twilight }
            .map { it.sunk }
        assertTrue(morning.isNotEmpty())
        for ((a, b) in morning.zipWithNext()) {
            assertTrue("$a then $b", b <= a)
        }
        assertTrue(morning.first() > 0.9f)
        assertTrue(morning.last() < 0.1f)
    }

    /** No fix, no twilight either — the moon holds the dial all day. */
    @Test
    fun `without a fix there is no sky at all to report`() {
        for (minute in 0 until 1440 step 15) {
            assertNull(DayNight.sky(minute))
        }
    }
}
