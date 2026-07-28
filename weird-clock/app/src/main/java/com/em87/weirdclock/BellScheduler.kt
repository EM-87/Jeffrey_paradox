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
        val halfHours = prefs.getBoolean(Prefs.HALF_HOUR, false) ||
            prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT) == Prefs.BELL_STYLE_SHIPS
        val slot = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (halfHours) {
                // Next :00 or :30, whichever comes first.
                val minute = get(Calendar.MINUTE)
                if (minute < 30) set(Calendar.MINUTE, 30) else {
                    set(Calendar.MINUTE, 0)
                    add(Calendar.HOUR_OF_DAY, 1)
                }
            } else {
                set(Calendar.MINUTE, 0)
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }
        val next = slot.timeInMillis
        // The strike is told which slot it belongs to. Working it out from
        // the clock when it fires was fine while alarms were punctual, but a
        // deferred one read the wrong minute and rang the half-hour bell on
        // the hour.
        val pending = pendingIntent(
            context,
            slot.get(Calendar.MINUTE) == 0,
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
        onTheHour: Boolean = true,
        hour: Int = 0
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BellReceiver::class.java)
            .putExtra(EXTRA_ON_THE_HOUR, onTheHour)
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
                        BellScheduler.EXTRA_ON_THE_HOUR,
                        intent.getBooleanExtra(BellScheduler.EXTRA_ON_THE_HOUR, true)
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
        val onTheHour = intent?.getBooleanExtra(
            BellScheduler.EXTRA_ON_THE_HOUR, now.get(Calendar.MINUTE) < 15
        ) ?: (now.get(Calendar.MINUTE) < 15)
        // Night mode keeps the house quiet.
        val quietHours = prefs.getBoolean(Prefs.NIGHT_DIM, false) && (hour >= 22 || hour < 7)
        if (!quietHours) {
            strike(prefs.getString(Prefs.BELL_STYLE, Prefs.BELL_STYLE_COUNT), hour, onTheHour)
        }
        handler.postDelayed({ stopSelf() }, 20_000L)
        return START_NOT_STICKY
    }

    private fun strike(style: String?, hourOfDay: Int, onTheHour: Boolean) {
        when (style) {
            Prefs.BELL_STYLE_SHIPS -> {
                val halfHours = (hourOfDay % 4) * 2 + if (onTheHour) 0 else 1
                chimePlayer.playBellSequence(
                    if (halfHours == 0) 8 else halfHours,
                    pairGrouping = true,
                    frequency = ChimePlayer.SHIPS_HZ,
                    ringSeconds = 2.0
                )
            }
            Prefs.BELL_STYLE_SINGLE -> chimePlayer.playBellSequence(
                1, false,
                if (onTheHour) ChimePlayer.GONG_HZ else ChimePlayer.HALF_HOUR_BELL_HZ,
                if (onTheHour) 4.5 else 1.5
            )
            else -> {
                if (onTheHour) {
                    val strikes = hourOfDay % 12
                    chimePlayer.playBellSequence(
                        if (strikes == 0) 12 else strikes, false,
                        ChimePlayer.GRANDFATHER_HZ, 3.0, 1.3
                    )
                } else {
                    chimePlayer.playBellSequence(
                        1, false, ChimePlayer.HALF_HOUR_BELL_HZ, 1.5
                    )
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bells_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setSound(null, null) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
