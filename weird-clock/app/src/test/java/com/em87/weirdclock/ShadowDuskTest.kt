package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shadows across a whole day, minute by minute, looking for the edge.
 *
 * Every previous test of this asked about one instant: is there a shadow at
 * noon, is there none at midnight, is the fade the right way round. All of
 * them passed while the thing somebody actually saw on the phone was the
 * shadows going out abruptly at dusk — because "abruptly" is not a
 * property of an instant. It is a property of two instants next to each
 * other, and nothing here was ever looking at two.
 *
 * So this walks the day a minute at a time and watches the darkness of the
 * shadow the dial would draw. It is the same question a finger asks when
 * it winds the hands round the dial, which is how it was found.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShadowDuskTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val lat = 40.4
    private val lon = -3.7

    @Before
    fun standInMadrid() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putFloat(Prefs.LAST_LATITUDE, lat.toFloat())
            .putFloat(Prefs.LAST_LONGITUDE, lon.toFloat())
            .commit()
        DayNight.configure(context)
    }

    /** Midnight local time on a fixed date, so nothing here reads the clock. */
    private fun midnight(year: Int, month: Int, day: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** How dark the dial's shadows are at that instant, from 0 to 1. */
    private fun darkness(atMs: Long): Float =
        HandShadow.lightAt(lat, lon, atMs)?.let { HandShadow.strength(it) } ?: 0f

    /**
     * Nothing jumps.
     *
     * A twentieth is the number, and it is not arbitrary: a shadow is laid
     * down at [HandShadow.PASS_ALPHA] over a hundred and twenty-eight, so a
     * twentieth of the range is about six of two hundred and fifty-five —
     * a step nobody can see. What was there before was a step of a fifth at
     * moonrise and, more to the point, a fade that finished at the horizon
     * while the sky above it was still being drawn lit.
     *
     * Four dates because the two lights move against each other: the moon
     * rises fifty minutes later every day, so the evening it comes up in
     * twilight is a different evening each month and one date could easily
     * miss it.
     */
    @Test
    fun `the shadows never change abruptly`() {
        for ((month, day) in listOf(1 to 14, 4 to 3, 7 to 21, 10 to 9)) {
            val from = midnight(2026, month, day)
            var was = darkness(from - 60_000L)
            for (m in 0 until 1440) {
                val now = darkness(from + m * 60_000L)
                val step = kotlin.math.abs(now - was)
                assertTrue(
                    "the shadow jumped by $step at %02d:%02d on %d/%d".format(
                        m / 60, m % 60, day, month
                    ),
                    step <= 0.05f
                )
                was = now
            }
        }
    }

    /**
     * And they are still there at sunset itself.
     *
     * The moment the sun's disc touches the horizon is when a real shadow
     * is at its longest and most obvious, and it was the moment this drew
     * nothing at all: the fade ran out exactly there. Half is what the
     * curve gives at the horizon now — see [HandShadow.FADE_FROM_DEG] —
     * and a third is the loosest reading of "still plainly there".
     */
    @Test
    fun `sunset still has a shadow on the dial`() {
        val from = midnight(2026, 4, 3)
        var best = 0f
        var bestAt = -1
        for (m in 0 until 1440) {
            val at = from + m * 60_000L
            val alt = SolarTime.position(lat, lon, at).altitudeDeg
            // The minute the sun is nearest the horizon on its way down.
            if (alt in -0.25..0.25 && m > 720) {
                best = darkness(at)
                bestAt = m
            }
        }
        assertTrue("no sunset was found to look at", bestAt > 0)
        assertTrue(
            "the dial is dark at sunset: $best at %02d:%02d".format(bestAt / 60, bestAt % 60),
            best > 0.33f
        )
    }

    /**
     * And gone by the time the sky is.
     *
     * The other half of the same rule, and the one that keeps this from
     * being answered by simply never fading: an hour after sunset there is
     * no sun left to cast anything, whatever the moon is doing.
     */
    @Test
    fun `an hour past sunset the sun casts nothing`() {
        val from = midnight(2026, 4, 3)
        for (m in 0 until 1440) {
            val at = from + m * 60_000L
            val alt = SolarTime.position(lat, lon, at).altitudeDeg
            if (alt < -12.0) {
                val light = HandShadow.lightAt(lat, lon, at)
                assertTrue(
                    "the sun is twelve degrees down and still casting",
                    light == null || light.moon
                )
            }
        }
    }
}
