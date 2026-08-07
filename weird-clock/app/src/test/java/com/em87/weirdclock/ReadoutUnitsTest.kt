package com.em87.weirdclock

import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The little marks over the digital readout — 01°:23′:45″.
 *
 * They have one job: saying which unit each pair of digits is standing in,
 * because on a chronograph that changes underneath you. Minutes, seconds
 * and hundredths below the hour; hours, minutes and seconds above it. Same
 * six digits, different meaning, and without the marks no way to tell.
 *
 * Everywhere else the digits mean the same thing from one end to the
 * other, and the marks were still being drawn: on a clock spelling out the
 * time because its hands are lying at the bottom of the case, on a time of
 * day being wound onto the face, on a length being wound the same way. A °
 * over a quarter past seven reads as a temperature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadoutUnitsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun dial(build: ClockView.() -> Unit = {}): ClockView =
        ClockView(context).apply {
            build()
            measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 720, 720)
        }

    private fun marksOn(view: ClockView): String = view.readoutUnits().joinToString("")

    /** The clock, spelling the time out because its hands are on the floor. */
    @Test
    fun `a clock reading itself out wears no marks`() {
        val clock = dial()
        clock.knockHandsOff()
        assertEquals("", marksOn(clock))
    }

    /** C0 borrowing the wind engine to set an alarm. */
    @Test
    fun `nor a time of day being wound onto the face`() {
        val setting = dial {
            chronoProvider = { 7 * 3_600_000L + 15 * 60_000L }
            chronoSettable = true
            chronoWrapsDay = true
            magnetProfile = ClockView.MagnetProfile.ALARM
        }
        assertEquals("", marksOn(setting))
    }

    /** And the same engine winding how long a thing lasts. */
    @Test
    fun `nor a length being wound the same way`() {
        val length = dial {
            chronoProvider = { 20 * 60_000L }
            chronoSettable = true
            chronoWrapsDay = true
            magnetProfile = ClockView.MagnetProfile.COUNTDOWN
            magnetOrigin = 18 * 3_600_000L
        }
        assertEquals("", marksOn(length))
    }

    // ------------------------------------------------- where they do belong

    /**
     * Under the hour a chronograph reads minutes, seconds and hundredths,
     * and the marks are the only thing saying so.
     */
    @Test
    fun `a chronograph under the hour keeps its marks`() {
        val watch = dial { chronoProvider = { 90_000L } }
        assertEquals("'\"", marksOn(watch))
    }

    /** Over it the same six digits mean something else, which is the point. */
    @Test
    fun `and says so differently once past the hour`() {
        val watch = dial { chronoProvider = { 2 * 3_600_000L } }
        assertTrue("the marks must change with the meaning", marksOn(watch).contains("°"))
    }

    /**
     * A stopped countdown can be wound too, but it is still a chronograph
     * and its digits still change meaning — so it is the wrong thing to
     * strip, and "is it settable" is the wrong question to ask alone.
     */
    @Test
    fun `a countdown waiting to be set is still a chronograph`() {
        val countdown = dial {
            chronoProvider = { 5 * 60_000L }
            chronoSettable = true
            magnetProfile = ClockView.MagnetProfile.COUNTDOWN
        }
        assertEquals("'\"", marksOn(countdown))
    }
}
