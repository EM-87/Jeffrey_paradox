package com.em87.weirdclock

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.util.Calendar

/**
 * Rings the hourly bells with the app closed. One exact alarm is armed for
 * the next hour (or half hour); when it fires it strikes and re-arms the
 * next one. Off by default — a novelty clock that chimes from your pocket
 * unasked would be a menace.
 */
object BellScheduler {

    private const val REQUEST_CODE = 200

    /** Which slot the strike was armed for, so a late one still rings right. */
    const val EXTRA_ON_THE_HOUR = "extra_on_the_hour"
    const val EXTRA_HOUR = "extra_hour"

    /**
     * And which minute of it, now that there are four to choose from.
     *
     * The boolean above is kept because it is not ours to retire: an alarm
     * armed by the previous build is still sitting in the system's queue
     * across the update, and it will arrive carrying only that.
     */
    const val EXTRA_MINUTE = "extra_minute"

    /**
     * The next minute past the hour that wants marking, given the minute
     * it is now and the [slots] that are wanted.
     *
     * Always strictly ahead: asked at exactly quarter past, the answer is
     * half past and not the quarter that is ringing as the question is
     * being asked. Comes back as minutes from the top of this hour, so 60
     * means the next hour.
     */
    internal fun nextSlot(minuteNow: Int, slots: Set<Int>): Int =
        slots.filter { it > minuteNow }.minOrNull() ?: 60

    fun update(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val on = prefs.getBoolean(Prefs.BELLS, false) &&
            prefs.getBoolean(Prefs.BELLS_BACKGROUND, false) &&
            prefs.getInt(Prefs.TIME_SPEED, 100) == 100
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        if (!on) {
            manager.cancel(pendingIntent(context))
            return
        }
        val marks = Bells.marksFrom(
            prefs.getString(Prefs.BELL_MARKS, null),
            prefs.getBoolean(Prefs.HALF_HOUR, false)
        )
        // A ship's bell marks half past whatever the setting says, so an
        // alarm has to be armed for it even when nobody asked for one.
        val slots = Bells.marked(marks) +
            if (prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) == Prefs.BELL_STYLE_SHIPS) {
                setOf(30)
            } else {
                emptySet()
            }
        val slot = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val ahead = nextSlot(get(Calendar.MINUTE), slots)
            set(Calendar.MINUTE, ahead % 60)
            if (ahead >= 60) add(Calendar.HOUR_OF_DAY, 1)
        }
        val next = slot.timeInMillis
        // The strike is told which slot it belongs to. Working it out from
        // the clock when it fires was fine while alarms were punctual, but a
        // deferred one read the wrong minute and rang the half-hour bell on
        // the hour.
        val pending = pendingIntent(
            context,
            slot.get(Calendar.MINUTE),
            slot.get(Calendar.HOUR_OF_DAY)
        )
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } catch (e: SecurityException) {
            manager.setWindow(AlarmManager.RTC_WAKEUP, next, 60_000L, pending)
        }
    }

    private fun pendingIntent(
        context: Context,
        minute: Int = 0,
        hour: Int = 0
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BellReceiver::class.java)
            .putExtra(EXTRA_MINUTE, minute)
            .putExtra(EXTRA_ON_THE_HOUR, minute == 0)
            .putExtra(EXTRA_HOUR, hour),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class BellReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BellService::class.java)
                    .putExtra(
                        // An alarm armed by the build before this one is
                        // still in the queue across the update, and carries
                        // only the boolean.
                        BellScheduler.EXTRA_MINUTE,
                        intent.getIntExtra(
                            BellScheduler.EXTRA_MINUTE,
                            if (intent.getBooleanExtra(BellScheduler.EXTRA_ON_THE_HOUR, true)) 0
                            else 30
                        )
                    )
                    .putExtra(
                        BellScheduler.EXTRA_HOUR,
                        intent.getIntExtra(BellScheduler.EXTRA_HOUR, -1)
                    )
            )
        } catch (e: Exception) {
            // Android only lets a background app start a foreground service
            // in certain windows, and an inexact fallback alarm is not one of
            // them. A missed chime is not worth crashing the clock over.
        }
        BellScheduler.update(context)
    }
}

/**
 * Strikes once and dies. A foreground service only because Android won't
 * let a background app open an audio track otherwise; its notification is
 * as quiet and low-priority as the platform allows.
 */
class BellService : Service() {

    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.bells_channel_name))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val now = Calendar.getInstance()
        val hour = intent?.getIntExtra(BellScheduler.EXTRA_HOUR, -1)
            ?.takeIf { it in 0..23 } ?: now.get(Calendar.HOUR_OF_DAY)
        // Rounded to the nearest quarter when nothing says otherwise: an
        // alarm that arrives a minute late is still the quarter it was
        // armed for.
        val minute = intent?.getIntExtra(BellScheduler.EXTRA_MINUTE, -1)
            ?.takeIf { it >= 0 }
            ?: ((now.get(Calendar.MINUTE) + 7) / 15 * 15) % 60
        // Night mode keeps the house quiet.
        val quietHours = Bells.quiet(
            prefs.getBoolean(Prefs.NIGHT_DIM, false),
            hour,
            prefs.getInt(Prefs.NIGHT_FROM, NightWindow.DEFAULT_FROM),
            prefs.getInt(Prefs.NIGHT_TO, NightWindow.DEFAULT_TO)
        )
        if (!quietHours) {
            // The same rule the app uses while it is open. It used to be a
            // second copy of it, living here.
            Bells.peal(
                prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT),
                hour,
                minute,
                Bells.marksFrom(
                    prefs.getString(Prefs.BELL_MARKS, null),
                    prefs.getBoolean(Prefs.HALF_HOUR, false)
                )
            )?.let { chimePlayer.play(it) }
        }
        handler.postDelayed({ stopSelf() }, 20_000L)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            android.app.NotificationChannelGroup(
                AlarmService.GROUP_ID, getString(R.string.channel_group_clock)
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bells_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                description = getString(R.string.bells_channel_desc)
                group = AlarmService.GROUP_ID
            }
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        chimePlayer.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "hourly_bells"
        private const val NOTIFICATION_ID = 4
    }
}
