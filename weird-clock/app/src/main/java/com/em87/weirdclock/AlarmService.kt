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
import androidx.preference.PreferenceManager
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
        const val ACTION_SNOOZE = "com.em87.weirdclock.action.SNOOZE_ALARM"
        const val CHANNEL_ID = "alarm"
        const val NOTIFICATION_ID = 1

        /**
         * Set by the ring screen so it can close itself when the ringing
         * ends — from the notification's Stop, or from the three-minute
         * timeout. Otherwise it sat there over the lock screen, silent,
         * offering to stop an alarm that had already stopped.
         */
        var onStopped: (() -> Unit)? = null
    }

    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var sound = Prefs.ALARM_SOUND_BELLS
    private var soundUri = ""
    private var snoozeMinutes = 0
    private var label = ""
    private var vibrateEnabled = true
    private var flashEnabled = false
    private var mediaPlayer: MediaPlayer? = null
    private var flashOn = false

    /** Which camera's torch is lit — not necessarily the first one. */
    private var torchCameraId: String? = null

    /** Strobes the camera torch while the alarm rings, if asked to. */
    private val flashLoop = object : Runnable {
        override fun run() {
            val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
            try {
                val id = manager?.cameraIdList?.firstOrNull { camId ->
                    manager.getCameraCharacteristics(camId)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id != null && Build.VERSION.SDK_INT >= 23) {
                    flashOn = !flashOn
                    torchCameraId = id
                    manager.setTorchMode(id, flashOn)
                }
            } catch (e: Exception) {
                // No torch, or it's busy: the bells carry on regardless.
                return
            }
            handler.postDelayed(this, 550L)
        }
    }

    private val vibrateLoop = object : Runnable {
        override fun run() {
            val vibrator = getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    android.os.VibrationEffect.createWaveform(
                        longArrayOf(0, 400, 250, 400), -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 400, 250, 400), -1)
            }
            handler.postDelayed(this, 2200L)
        }
    }

    /** Gradual wake: volume climbs from 15% to full over about a minute. */
    private var rampStep = 0
    private val rampLoop = object : Runnable {
        override fun run() {
            rampStep++
            val v = (0.15f + 0.85f * rampStep / 30f).coerceAtMost(1f)
            chimePlayer.volume = v
            mediaPlayer?.setVolume(v, v)
            if (v < 1f) handler.postDelayed(this, 2000L)
        }
    }
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
        if (intent?.action == ACTION_SNOOZE) {
            AlarmScheduler.snooze(this, sound, snoozeMinutes.coerceAtLeast(5), soundUri)
            stopSelf()
            return START_NOT_STICKY
        }
        sound = intent?.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: Prefs.ALARM_SOUND_BELLS
        soundUri = intent?.getStringExtra(AlarmScheduler.EXTRA_SOUND_URI) ?: ""
        snoozeMinutes = intent?.getIntExtra(AlarmScheduler.EXTRA_SNOOZE, 0) ?: 0
        label = intent?.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        vibrateEnabled = intent?.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true) != false
        flashEnabled = intent?.getBooleanExtra(AlarmScheduler.EXTRA_FLASH, false) == true
        val ramp = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(Prefs.ALARM_RAMP, true)
        chimePlayer.volume = if (ramp) 0.15f else 1f
        rampStep = 0

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        when {
            sound == Prefs.ALARM_SOUND_CUSTOM && soundUri.isNotBlank() -> {
                // A user-picked audio file (SAF, persisted read permission).
                // If it went away — file deleted, permission revoked — the
                // bells take over: an alarm must never fail silently.
                mediaPlayer = try {
                    MediaPlayer().apply {
                        setDataSource(this@AlarmService, android.net.Uri.parse(soundUri))
                        isLooping = true
                        setVolume(chimePlayer.volume, chimePlayer.volume)
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    null
                }
                if (mediaPlayer == null) {
                    sound = Prefs.ALARM_SOUND_BELLS
                    handler.post(ringLoop)
                }
            }
            sound == Prefs.ALARM_SOUND_BABY -> {
                // A real newborn recording (CC0). Humans are wired to react to
                // this; the synthesized wail is only a fallback.
                mediaPlayer = MediaPlayer.create(this, R.raw.baby_cry)?.apply {
                    isLooping = true
                    setVolume(chimePlayer.volume, chimePlayer.volume)
                    start()
                }
                if (mediaPlayer == null) handler.post(ringLoop)
            }
            else -> handler.post(ringLoop)
        }
        if (ramp) handler.postDelayed(rampLoop, 2000L)
        if (vibrateEnabled) handler.post(vibrateLoop)
        if (flashEnabled) handler.post(flashLoop)
        handler.postDelayed({ stopSelf() }, 3 * 60_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        chimePlayer.release()
        onStopped?.invoke()
        super.onDestroy()
    }

    private fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        getSystemService(android.os.Vibrator::class.java)?.cancel()
        if (flashOn && Build.VERSION.SDK_INT >= 23) {
            try {
                // The camera that was lit, not camera zero: on a phone whose
                // first camera is the flashless front one, the torch stayed
                // on after the alarm stopped.
                val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
                torchCameraId?.let { manager?.setTorchMode(it, false) }
            } catch (e: Exception) {
                // Torch already released.
            }
            flashOn = false
        }
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
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AlarmScheduler.EXTRA_SOUND, sound)
                .putExtra(AlarmScheduler.EXTRA_SOUND_URI, soundUri)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE, snoozeMinutes)
                .putExtra(AlarmScheduler.EXTRA_LABEL, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label.ifBlank { getString(R.string.alarm_ringing) })
            .setContentText(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, getString(R.string.alarm_stop), stop)
        if (snoozeMinutes > 0) {
            val snoozePi = PendingIntent.getService(
                this,
                4,
                Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.alarm_snooze_fmt, snoozeMinutes), snoozePi)
        }
        return builder.build()
    }
}
