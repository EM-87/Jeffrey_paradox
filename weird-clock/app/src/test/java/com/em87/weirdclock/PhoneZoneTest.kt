package com.em87.weirdclock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * That a test which moves the phone does not leave it there.
 *
 * See [PhoneZone]. What is done here by hand is what Robolectric does by
 * itself after every test: it puts the JVM's time zone back to the one
 * its stand-in for the alarm manager remembers. If that stand-in was
 * first loaded while a test had the phone somewhere else, somewhere else
 * is what it remembers, and it goes on putting every later test there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneZoneTest {

    @After
    fun home() = PhoneZone.goHome()

    @Test
    fun `a phone moved for one test is home for the next`() {
        val home = TimeZone.getDefault().id
        // Somewhere no machine running these tests is likely to be.
        val away = if (home == "Pacific/Chatham") "Pacific/Kiritimati" else "Pacific/Chatham"
        PhoneZone.move(away)
        assertEquals(away, TimeZone.getDefault().id)
        // The end of a test, as Robolectric sees to it — with the phone
        // still away, which is the worst moment for it to look.
        org.robolectric.shadows.ShadowAlarmManager.reset()
        assertEquals("Robolectric remembers the phone as being away", home, TimeZone.getDefault().id)
        // And again, the way a test ends when it tidies up after itself.
        PhoneZone.move(away)
        PhoneZone.goHome()
        assertEquals(home, TimeZone.getDefault().id)
        org.robolectric.shadows.ShadowAlarmManager.reset()
        assertEquals(home, TimeZone.getDefault().id)
    }
}
