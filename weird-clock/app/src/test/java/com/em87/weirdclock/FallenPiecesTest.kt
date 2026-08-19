package com.em87.weirdclock

import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the pieces on the floor of the case remember.
 *
 * A piece that has come off the dial has stopped being told the time — it
 * is a bead of glass rolling about — and the two things it must carry are
 * whatever it was showing at the moment it fell. Both were wrong, in
 * different ways. The date was read one line *after* the wound time was
 * thrown away, so a dial wound forward to tomorrow dropped a date that
 * said today: the piece that fell was not the piece that had been on the
 * face. And the sky token fell as a plain white bead rather than as the sun
 * or the moon it had been.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class FallenPiecesTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putBoolean(Prefs.OVERLAY_ASKED, true)
            .putBoolean(Prefs.SHOW_DATE, true)
            .putBoolean(Prefs.MOON_PHASE, true)
            .commit()
    }

    private fun dial(): ClockView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        return activity.clockForTest()
    }

    /**
     * A dial wound forward to another day drops that day's date.
     *
     * The whole bug in one line: the date was read after `visualOffsetSeconds`
     * had been set back to zero, so the wound day was thrown away one
     * instruction before it was needed.
     */
    @Test
    fun `the date that falls is the date that was showing`() {
        val clock = dial()
        val today = clock.dateTextForTest()
        clock.windForTest(26.0 * 60 * 60)
        val tomorrow = clock.dateTextForTest()
        assertTrue("winding a day did not change the date at all", tomorrow != today)

        clock.knockHandsOff()
        assertEquals(
            "the dial said $tomorrow and dropped $today",
            tomorrow, clock.fallenDateForTest()
        )
    }

    /** And an unwound dial drops today, which is the same rule. */
    @Test
    fun `an unwound dial drops today`() {
        val clock = dial()
        val today = clock.dateTextForTest()
        clock.knockHandsOff()
        assertEquals(today, clock.fallenDateForTest())
    }

    /**
     * The sky token falls as the thing it was.
     *
     * Checked by what it remembers rather than by the pixels: it carries
     * the hour it was showing, and that is what makes it a sun or a moon
     * on the floor instead of a white bead.
     */
    @Test
    fun `the sky token remembers which hour it fell in`() {
        val clock = dial()
        val showing = clock.shownWallMs()
        clock.knockHandsOff()
        val frozen = clock.fallenSkyMomentForTest()
        assertNotNull("the sky token did not come off at all", frozen)
        assertEquals(
            "it fell without being told what it was showing",
            showing.toDouble(), frozen!!.toDouble(), 5000.0
        )
    }

    /**
     * And it remembers the wound hour, not the real one.
     *
     * Wind the dial to the middle of the night and the token is a moon;
     * knock it off and it must stay a moon, however sunny it is outside.
     */
    @Test
    fun `a token knocked off a wound dial keeps the wound hour`() {
        val clock = dial()
        clock.windForTest(9.0 * 60 * 60)
        val wound = clock.shownWallMs()
        clock.knockHandsOff()
        assertEquals(
            "the token went back to the real hour on its way to the floor",
            wound.toDouble(), clock.fallenSkyMomentForTest()!!.toDouble(), 5000.0
        )
    }
}
