package com.em87.weirdclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Keeps a running countdown visible while the app is in the background: an
 * ongoing notification with a live remaining-time chronometer and a progress
 * bar, always one glance away in the status bar and on the lock screen. At
 * zero it rings the finish chime. The result (finished/cancelled) is left in
 * preferences for MainActivity to pick up on resume.
 */
class CountdownService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.em87.weirdclock.action.CANCEL_COUNTDOWN"
        private const val EXTRA_ENDS_AT = "extra_ends_at"
        private const val EXTRA_TOTAL = "extra_total"
        private const val CHANNEL_ID = "countdown"
        private const val NOTIFICATION_ID = 2
        private const val DONE_NOTIFICATION_ID = 3

        const val RESULT_FINISHED = "finished"
        const val RESULT_CANCELLED = "cancelled"

        fun start(context: Context, endsAtElapsed: Long, totalMs: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CountdownService::class.java)
                    .putExtra(EXTRA_ENDS_AT, endsAtElapsed)
                    .putExtra(EXTRA_TOTAL, totalMs)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CountdownService::class.java))
        }
    }

    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var endsAtElapsed = 0L
    private var totalMs = 1L
    private var finished = false

    private val updateLoop = object : Runnable {
        override fun run() {
            val remaining = endsAtElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                finish()
            } else {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(remaining))
                handler.postDelayed(this, 2000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            setResult(RESULT_CANCELLED)
            stopSelf()
            return START_NOT_STICKY
        }
        endsAtElapsed = intent?.getLongExtra(EXTRA_ENDS_AT, 0L) ?: 0L
        totalMs = (intent?.getLongExtra(EXTRA_TOTAL, 1L) ?: 1L).coerceAtLeast(1L)
        createChannel()
        val remaining = (endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val notification = buildNotification(remaining)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        handler.removeCallbacksAndMessages(null)
        handler.post(updateLoop)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        chimePlayer.release()
        super.onDestroy()
    }

    private fun finish() {
        if (finished) return
        finished = true
        setResult(RESULT_FINISHED)
        chimePlayer.playBellSequence(3, false, ChimePlayer.DAY_CHIME_HZ, 1.2, 0.3)
        getSystemService(NotificationManager::class.java)?.notify(
            DONE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.countdown_done))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        )
        // Give the chime a moment to ring before the service dies.
        handler.postDelayed({ stopSelf() }, 4000L)
    }

    private fun setResult(result: String) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(Prefs.COUNTDOWN_RESULT, result)
            .apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.countdown_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(remainingMs: Long): android.app.Notification {
        val cancel = PendingIntent.getService(
            this,
            3,
            Intent(this, CountdownService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.countdown_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + remainingMs)
            .setProgress(
                totalMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                remainingMs.coerceIn(0L, totalMs).toInt(),
                false
            )
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.countdown_cancel), cancel)
            .build()
    }
}
