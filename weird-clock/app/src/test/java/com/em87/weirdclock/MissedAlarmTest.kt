package com.em87.weirdclock

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * What is left behind when nobody comes.
 *
 * The alarm has always given up after three minutes — that half was there
 * and documented. The other half was not: the ringing stopped, the
 * foreground notification went with the service, and an alarm that had done
 * its whole job into an empty room left no trace of ever having gone off.
 * A missed alarm you never find out about is the exact failure the app
 * exists to prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MissedAlarmTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun notifications() =
        shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun missed() =
        notifications().allNotifications.firstOrNull {
            shadowOf(it).contentTitle == context.getString(R.string.missed_title)
        }

    private fun ring(label: String = "Dentist"): org.robolectric.android.controller.ServiceController<AlarmService> {
        val intent = Intent(context, AlarmService::class.java)
            .putExtra(AlarmScheduler.EXTRA_LABEL, label)
            .putExtra(AlarmScheduler.EXTRA_VIBRATE, false)
            .putExtra(AlarmScheduler.EXTRA_FLASH, false)
        return Robolectric.buildService(AlarmService::class.java, intent).create().startCommand(0, 0)
    }

    @Test
    fun `while it is still ringing there is nothing to report`() {
        ring()
        ShadowLooper.idleMainLooper(1, TimeUnit.MINUTES)
        assertNull("it has not given up yet", missed())
    }

    @Test
    fun `an alarm that rings itself out leaves a note`() {
        ring()
        ShadowLooper.idleMainLooper(
            AlarmService.RING_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS
        )
        assertNotNull("nothing said it ever rang", missed())
    }

    /**
     * And an alarm somebody dealt with leaves nothing: the note is a record
     * of a failure, and one posted every morning after a normal wake-up
     * would be noise that teaches you to swipe it away without reading it.
     */
    @Test
    fun `an alarm that was stopped leaves nothing behind`() {
        val controller = ring()
        ShadowLooper.idleMainLooper(1, TimeUnit.MINUTES)
        controller.get().stopSelf()
        controller.destroy()
        ShadowLooper.idleMainLooper(5, TimeUnit.MINUTES)

        assertNull("it was attended to", missed())
    }
}
