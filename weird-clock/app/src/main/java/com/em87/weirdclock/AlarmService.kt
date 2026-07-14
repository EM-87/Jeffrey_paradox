package com.em87.weirdclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.DateFormat
import java.util.Date

/**
 * Rings the alarm with the app's own synthesized bells: a foreground service
 * with a full-screen notification that opens [AlarmRingActivity]. Stops from
 * the notification action, the ring screen, or automatically after 3 minutes.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_STOP = "com.em87.weirdclock.action.STOP_ALARM"
        const val CHANNEL_ID = "alarm"
        const val NOTIFICATION_ID = 1
    }

    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var sound = Prefs.ALARM_SOUND_BELLS
    private var mediaPlayer: MediaPlayer? = null
    private val ringLoop = object : Runnable {
        override fun run() {
            when (sound) {
                Prefs.ALARM_SOUND_DIGITAL -> {
                    chimePlayer.playDigitalAlarm()
                    handler.postDelayed(this, 1300L)
                }
                Prefs.ALARM_SOUND_BABY -> {
                    // Synthesized fallback, used only if MediaPlayer failed.
                    chimePlayer.playBabyCry()
                    handler.postDelayed(this, 4200L)
                }
                else -> {
                    chimePlayer.playBellSequence(3, false, ChimePlayer.SHIPS_HZ, 1.6, 0.5)
                    handler.postDelayed(this, 5000L)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        sound = intent?.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: Prefs.ALARM_SOUND_BELLS

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        if (sound == Prefs.ALARM_SOUND_BABY) {
            // A real newborn recording (CC0). Humans are wired to react to
            // this; the synthesized wail is only a fallback.
            mediaPlayer = MediaPlayer.create(this, R.raw.baby_cry)?.apply {
                isLooping = true
                start()
            }
            if (mediaPlayer == null) handler.post(ringLoop)
        } else {
            handler.post(ringLoop)
        }
        handler.postDelayed({ stopSelf() }, 3 * 60_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        chimePlayer.release()
        super.onDestroy()
    }

    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // The service plays its own bells; no notification sound on top.
            setSound(null, null)
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val fullScreen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.alarm_ringing))
            .setContentText(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.alarm_stop), stop)
            .build()
    }
}
