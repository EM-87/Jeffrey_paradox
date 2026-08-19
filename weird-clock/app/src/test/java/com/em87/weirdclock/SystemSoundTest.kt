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

/**
 * Waking up to something the phone already had.
 *
 * Everything this clock rings with, it synthesises: bells, beeps, a wolf, a
 * baby. There has been a way to point at a file of your own for a while,
 * but not at the phone's own alarms and ringtones — which are not files
 * anybody can browse to, and are what most people mean by "a real sound".
 *
 * What is worth pinning is not the picker. It is that a second voice which
 * plays from a URI exists at all: every place that asked "is this the
 * custom one" was really asking "does this play from a file", and a
 * question written the first way is the shape of thing that gets missed
 * when a second one turns up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemSoundTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun wipe() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AlarmStore.forget()
    }

    /** Both voices that come from a URI are known to be such. */
    @Test
    fun `the two voices that play from a file are both recognised`() {
        assertTrue(Prefs.playsFromUri(Prefs.ALARM_SOUND_CUSTOM))
        assertTrue(Prefs.playsFromUri(Prefs.ALARM_SOUND_SYSTEM))
    }

    /** And none of the synthesised ones is mistaken for one. */
    @Test
    fun `nothing the app makes itself plays from a file`() {
        for (sound in Prefs.ALARM_SOUNDS) {
            assertFalse(
                "$sound was taken for something with a file behind it",
                Prefs.playsFromUri(sound)
            )
        }
        assertFalse("and neither does nothing at all", Prefs.playsFromUri(null))
    }

    /** It is offered where the other voices are, and it has a name of its own. */
    @Test
    fun `the phone's own sounds are on the list, under their own name`() {
        val cards = AlarmCards(
            host = context,
            prefs = PreferenceManager.getDefaultSharedPreferences(context),
            alarms = emptyList(),
            dialTheme = { ClockThemes.MIDNIGHT },
            hoursOnDial = { 12 },
            dialShape = { ClockView.DialShape.CIRCLE },
            onToggled = { _, _ -> },
            onOpen = { }
        )
        val name = cards.soundLabel(Prefs.ALARM_SOUND_SYSTEM)
        assertTrue("it has no name of its own", name.isNotBlank())
        assertNotEquals(
            "it is called the same thing as a file you went and found",
            cards.soundLabel(Prefs.ALARM_SOUND_CUSTOM), name
        )
        assertNotEquals(
            "and the same thing as the bells",
            cards.soundLabel(Prefs.ALARM_SOUND_BELLS), name
        )
    }

    /** The chosen sound and where it came from survive a restart together. */
    @Test
    fun `a phone sound is remembered with its uri`() {
        val alarm = Alarm(1, 7, 0, true, Prefs.ALARM_SOUND_SYSTEM).apply {
            soundUri = "content://settings/system/alarm_alert"
        }
        val list = AlarmStore.all(context)
        list.clear()
        list.add(alarm)
        AlarmStore.save(context)
        AlarmStore.forget()

        val read = AlarmStore.all(context).first()
        assertEquals(Prefs.ALARM_SOUND_SYSTEM, read.sound)
        assertEquals(
            "the sound was remembered and the sound itself was not",
            alarm.soundUri, read.soundUri
        )
    }

    /**
     * And it is carried to whatever does the ringing.
     *
     * The voice and its URI travel as separate extras through three hops —
     * scheduler, receiver, service — and a voice that arrives without its
     * URI is an alarm that falls back to the bells at six in the morning
     * with no way to tell why.
     */
    @Test
    fun `the voice and its uri both reach the ringing`() {
        assertTrue(AlarmScheduler.EXTRA_SOUND in AlarmScheduler.CARRIED)
        assertTrue(AlarmScheduler.EXTRA_SOUND_URI in AlarmScheduler.CARRIED)

        val from = android.content.Intent()
            .putExtra(AlarmScheduler.EXTRA_SOUND, Prefs.ALARM_SOUND_SYSTEM)
            .putExtra(AlarmScheduler.EXTRA_SOUND_URI, "content://settings/system/alarm_alert")
        val to = AlarmScheduler.carryOver(from, android.content.Intent())
        assertEquals(
            Prefs.ALARM_SOUND_SYSTEM, to.getStringExtra(AlarmScheduler.EXTRA_SOUND)
        )
        assertEquals(
            "content://settings/system/alarm_alert",
            to.getStringExtra(AlarmScheduler.EXTRA_SOUND_URI)
        )
    }
}
