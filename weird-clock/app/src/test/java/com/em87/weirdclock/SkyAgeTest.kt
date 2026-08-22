package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sky thins out as it is wound back, and what is left changes its name.
 *
 * Two claims, and they fail in opposite ways. A planet that stays on the
 * dial after it should have gone is a loud, obvious anachronism — Neptune
 * over Babylon — but it is loud only if you happen to be looking at that
 * part of the dial. A name that does not change is silent: the picture is
 * right and the label is four thousand years early, and nothing about the
 * screen says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SkyAgeTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.MOON_PHASE, true)
            .putBoolean(Prefs.ORRERY, true)
            .commit()
    }

    // ------------------------------------------------------- what is up there

    /** The two that were found by looking, and the years they were found in. */
    @Test
    fun `the telescope planets arrive when they were found`() {
        assertFalse(SkyAge.isKnownIn(Orrery.Body.URANUS, 1780))
        assertTrue(SkyAge.isKnownIn(Orrery.Body.URANUS, 1781))
        assertFalse(SkyAge.isKnownIn(Orrery.Body.NEPTUNE, 1845))
        assertTrue(SkyAge.isKnownIn(Orrery.Body.NEPTUNE, 1846))
        // And between the two there are seven planets, not six and not
        // eight — the sixty-five years when Uranus was the last one.
        assertEquals(
            "the sky between Herschel and Le Verrier is the wrong size",
            setOf(Orrery.Body.NEPTUNE), SkyAge.unknownIn(1800)
        )
    }

    /**
     * The five you can see with an eye go where the written records do,
     * and what is left is the ground, the Moon and the Sun.
     *
     * Nobody discovered Mars. The question is not when it appeared but
     * when somebody first wrote down where it had got to, because that is
     * the first moment a picture like this one means anything.
     */
    @Test
    fun `before the records only the earth and the moon are left`() {
        val early = SkyAge.unknownIn(SkyAge.NAKED_EYE_FROM - 1)
        assertEquals(
            "something other than the Earth and the Moon survived the wind back",
            setOf(Orrery.Body.EARTH, Orrery.Body.MOON),
            Orrery.Body.entries.toSet() - early
        )
        assertTrue(
            "the wandering stars were never written down",
            SkyAge.isKnownIn(Orrery.Body.MARS, SkyAge.NAKED_EYE_FROM)
        )
    }

    /** And today everything is up there. */
    @Test
    fun `nothing is missing from the sky now`() {
        assertTrue(SkyAge.unknownIn(2026).isEmpty())
    }

    /**
     * A Calendar never reports a negative year, and reading one without
     * the era flag puts the whole sky in the wrong millennium.
     *
     * The same trap the date already had. Sprung here it is much quieter:
     * 3000 BC comes back as 3001 AD, every planet is known, and the dial
     * draws a complete solar system over the Bronze Age without anything
     * looking wrong.
     */
    @Test
    fun `a year before the epoch is a negative year`() {
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getDefault())
        cal.clear()
        cal.set(java.util.Calendar.ERA, java.util.GregorianCalendar.BC)
        cal.set(1250, 5, 15)
        assertEquals(-1249, SkyAge.yearOf(cal.timeInMillis))
        cal.clear()
        cal.set(2026, 5, 15)
        assertEquals(2026, SkyAge.yearOf(cal.timeInMillis))
    }

    // ---------------------------------------------------------- what it is called

    /** Four sets of names, and the years the astronomy changed hands. */
    @Test
    fun `the names change with the astronomy`() {
        assertEquals(SkyAge.Era.BABYLONIAN, SkyAge.eraFor(-2000))
        assertEquals(SkyAge.Era.GREEK, SkyAge.eraFor(-400))
        assertEquals(SkyAge.Era.LATIN, SkyAge.eraFor(50))
        assertEquals(SkyAge.Era.LATIN, SkyAge.eraFor(1750))
        assertEquals(SkyAge.Era.MODERN, SkyAge.eraFor(2026))
    }

    /**
     * The names go modern exactly where the date does.
     *
     * Otherwise a wound sky shows a Latin caption over English planet
     * names, or English over Latin, which is the fault the mixed
     * Arabic-and-Roman date had.
     */
    @Test
    fun `the names and the numerals change on the same year`() {
        assertEquals(
            "the planets stop being Latin somewhere other than where the year does",
            SkyAge.MODERN_FROM, 2000
        )
        assertEquals(OrreryYear.Script.ROMAN, OrreryYear.scriptFor(SkyAge.MODERN_FROM - 1))
        assertEquals(SkyAge.Era.LATIN, SkyAge.eraFor(SkyAge.MODERN_FROM - 1))
        assertEquals(OrreryYear.Script.DIGITS, OrreryYear.scriptFor(SkyAge.MODERN_FROM))
        assertEquals(SkyAge.Era.MODERN, SkyAge.eraFor(SkyAge.MODERN_FROM))
    }

    /** And each era says something different for the same dot. */
    @Test
    fun `the same planet has four names`() {
        // Mercury and not Venus. Venus is Venus in Latin and in English —
        // the Romans named it and we kept the name — so it has three
        // names, not four, and asking it this question passes for a reason
        // that has nothing to do with the wiring.
        val said = listOf(-2000, -400, 50, 2026).map {
            context.getString(OrreryDial.nameKeyOf(Orrery.Body.MERCURY, it))
        }
        assertEquals("Mercury has fewer than four names: $said", 4, said.toSet().size)
        assertEquals("Gu-utu", said[0])
        assertEquals("Stilbon", said[1])
        assertEquals("Mercurius", said[2])
        assertEquals(context.getString(R.string.body_mercury), said[3])
    }

    /**
     * The two found with a telescope keep the only names they have ever
     * had.
     *
     * There is no Babylonian word for Neptune, and inventing one is what
     * put noise on this screen before. They are only ever asked for in
     * years they exist in anyway.
     */
    @Test
    fun `the telescope planets have no older names`() {
        for (year in listOf(-2000, -400, 50)) {
            assertEquals(
                "Uranus has been given a name nobody ever called it",
                context.getString(R.string.body_uranus),
                context.getString(OrreryDial.nameKeyOf(Orrery.Body.URANUS, year))
            )
        }
    }

    // ------------------------------------------------------------- on the dial

    private fun sky(): ClockView {
        val controller = org.robolectric.Robolectric
            .buildActivity(MainActivity::class.java).setup()
        val clock = controller.get().clockForTest()
        clock.toggleOrrery()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900))
        clock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
        )
        clock.layout(0, 0, 1080, 2000)
        return clock
    }

    /**
     * A planet that has not been found yet cannot be taken hold of.
     *
     * The hit test and the drawing are answered from the same place, and
     * this is the half that would rot silently: a Neptune that is not
     * drawn but is still grabbable hands out a grip on empty dial and
     * winds the whole sky by Neptune's year.
     */
    @Test
    fun `an undiscovered planet is not there to be grabbed`() {
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getDefault())
        cal.clear()
        cal.set(1700, 5, 15)
        val at = cal.timeInMillis
        assertTrue(
            "Neptune was found in 1846 and something is skipping the check",
            SkyAge.unknownAt(at).contains(Orrery.Body.NEPTUNE)
        )
        assertTrue(
            "Uranus was found in 1781 and is on the dial in 1700",
            SkyAge.unknownAt(at).contains(Orrery.Body.URANUS)
        )
        // Every point on the dial, walked: none of them is Neptune.
        var found = 0
        for (i in 0 until 360 step 3) {
            val rad = Math.toRadians(i.toDouble())
            for (frac in listOf(0.2f, 0.4f, 0.6f, 0.8f, 0.98f)) {
                val body = OrreryDial.bodyAt(
                    540f + (500f * frac * kotlin.math.cos(rad)).toFloat(),
                    540f + (500f * frac * kotlin.math.sin(rad)).toFloat(),
                    540f, 540f, 500f, at, 0.0
                )
                if (body == Orrery.Body.NEPTUNE || body == Orrery.Body.URANUS) found++
            }
        }
        assertEquals("a planet nobody had found was grabbable in 1700", 0, found)
    }

    /** And in a year they are both up, they can both be taken hold of. */
    @Test
    fun `a discovered planet is there to be grabbed`() {
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getDefault())
        cal.clear()
        cal.set(2026, 5, 15)
        val at = cal.timeInMillis
        var found = 0
        for (i in 0 until 360) {
            val rad = Math.toRadians(i.toDouble())
            for (frac in listOf(0.8f, 0.9f, 0.98f)) {
                val body = OrreryDial.bodyAt(
                    540f + (500f * frac * kotlin.math.cos(rad)).toFloat(),
                    540f + (500f * frac * kotlin.math.sin(rad)).toFloat(),
                    540f, 540f, 500f, at, 0.0
                )
                if (body == Orrery.Body.NEPTUNE || body == Orrery.Body.URANUS) found++
            }
        }
        assertTrue(
            "the outer planets cannot be grabbed at all, so the test above " +
                "proves nothing",
            found > 0
        )
    }

    /**
     * The label names a planet for the century the sky is wound to.
     *
     * On the dial and not merely in the table that could do it — this is
     * the wire that was missing, and with it cut the picture is right and
     * every label is four thousand years early. Asked at the seam the two
     * bubbles are built from rather than through a synthetic tap, because
     * a tap that lands on empty dial says nothing and a test that walks
     * the dial until one does would pass on the walk.
     */
    @Test
    fun `the label names a planet in the voice of its century`() {
        val clock = sky()
        clock.windOrreryToYearForTest(-2000)
        assertEquals(
            "the sky is naming its planets in English two thousand years " +
                "before Christ",
            "Dilbat", clock.bodyNameForTest(Orrery.Body.VENUS)
        )
        clock.windOrreryToYearForTest(-400)
        assertEquals("Phosphoros", clock.bodyNameForTest(Orrery.Body.VENUS))
        clock.windOrreryToYearForTest(1750)
        assertEquals("Iuppiter", clock.bodyNameForTest(Orrery.Body.JUPITER))
    }

    /** And in this century it says what we call them. */
    @Test
    fun `the label names a planet in English now`() {
        val clock = sky()
        clock.windOrreryToYearForTest(2026)
        assertEquals(
            "the sky is not using its ordinary names in this century",
            context.getString(R.string.body_venus),
            clock.bodyNameForTest(Orrery.Body.VENUS)
        )
    }

    /** No caption at all where there is no year to caption. */
    @Test
    fun `an undiscovered planet is not drawn either`() {
        val clock = sky()
        clock.windOrreryToYearForTest(1700)
        assertNull(
            "the dial still knows about a planet nobody had found",
            SkyAge.unknownIn(1700).firstOrNull { SkyAge.isKnownIn(it, 1700) }
        )
    }

}
