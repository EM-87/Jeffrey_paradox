package com.em87.weirdclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

/**
 * Rings the alarm with the app's own synthesized bells: a foreground service
 * with a full-screen notification that opens [AlarmRingActivity]. Stops from
 * the notification action, the ring screen, or on its own after
 * [RING_TIMEOUT_MS] — and when it is the timeout that stops it, it leaves a
 * note behind saying so.
 */
class AlarmService : Service() {

    companion object {
        const val ACTION_STOP = "com.em87.weirdclock.action.STOP_ALARM"
        const val ACTION_SNOOZE = "com.em87.weirdclock.action.SNOOZE_ALARM"
        const val CHANNEL_ID = "alarm"

        /** All three of the app's channels live under one heading. */
        const val GROUP_ID = "clock"
        const val NOTIFICATION_ID = 1

        /** The note left behind when nobody came, on its own channel. */
        const val MISSED_CHANNEL_ID = "missed"
        const val MISSED_NOTIFICATION_ID = 6

        /**
         * How long an alarm rings with nobody attending to it, by default.
         *
         * Three minutes is enough to wake someone and short enough not to
         * flatten the battery of a phone left at home — but a ring that
         * gives up on its own and says nothing is a missed alarm you never
         * find out about, which is the failure the whole app exists to
         * prevent. So it leaves a note: see [noteMissed].
         */
        const val RING_TIMEOUT_MS = 3 * 60_000L

        /**
         * The limit actually in force, or 0 for "until somebody stops it".
         *
         * A heavy sleeper wants longer and a light one wants a minute; the
         * default is only a default. Zero is offered too, and honestly
         * labelled: an alarm that never gives up is a choice, not an
         * oversight, and the note it would have left is the thing being
         * traded away.
         */
        fun ringTimeoutMs(context: Context): Long {
            val minutes = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Prefs.RING_TIMEOUT_MIN, null)
                ?.toIntOrNull()
                ?: return RING_TIMEOUT_MS
            return minutes.coerceIn(0, 60) * 60_000L
        }

        /**
         * Set by the ring screen so it can close itself when the ringing
         * ends — from the notification's Stop, or from the three-minute
         * timeout. Otherwise it sat there over the lock screen, silent,
         * offering to stop an alarm that had already stopped.
         */
        var onStopped: (() -> Unit)? = null

        /**
         * Whether what is ringing right now is a finished countdown.
         *
         * The ring screen used to read this off the intent it was launched
         * with, and showed a bell over a finished countdown for three
         * versions running. The label arrived and the flag did not, from
         * what looked like the same putExtra chain — because it was not the
         * same one. MainActivity kept its own copy of the handover for when
         * the app is open, and that copy passed the label alone.
         *
         * The copy is gone, but the screen still asks here rather than
         * there: whoever is ringing knows what it is ringing for, and the
         * service is by definition alive while its own full-screen intent
         * is on display. One place to get it wrong instead of two.
         */
        var ringingFromTimer = false
            private set

        /**
         * When the ringing started, on the elapsed-realtime clock.
         *
         * The ring screen counts up from here rather than showing the time.
         * A timer that has gone off is nearly always a timer that has gone
         * off while you were doing something else, and by then the useful
         * number is not what time it is — it is how far past it you are.
         */
        var ringingSince = 0L
            private set
    }

    private val chimePlayer = ChimePlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var sound = Prefs.ALARM_SOUND_BELLS
    private var soundUri = ""
    private var snoozeMinutes = 0
    private var snoozed = 0
    private var label = ""
    private var vibrateEnabled = true
    private var flashEnabled = false
    private var fromTimer = false
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
            AlarmScheduler.snooze(this, sound, snoozeMinutes.coerceAtLeast(5), soundUri, snoozed)
            stopSelf()
            return START_NOT_STICKY
        }
        sound = intent?.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: Prefs.ALARM_SOUND_BELLS
        soundUri = intent?.getStringExtra(AlarmScheduler.EXTRA_SOUND_URI) ?: ""
        snoozeMinutes = intent?.getIntExtra(AlarmScheduler.EXTRA_SNOOZE, 0) ?: 0
        snoozed = intent?.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0) ?: 0
        label = intent?.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        vibrateEnabled = intent?.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true) != false
        flashEnabled = intent?.getBooleanExtra(AlarmScheduler.EXTRA_FLASH, false) == true
        fromTimer = intent?.getBooleanExtra(AlarmScheduler.EXTRA_FROM_TIMER, false) == true
        ringingFromTimer = fromTimer
        ringingSince = android.os.SystemClock.elapsedRealtime()
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
        val timeout = ringTimeoutMs(this)
        if (timeout > 0L) handler.postDelayed(giveUp, timeout)
        return START_NOT_STICKY
    }

    /**
     * Nobody came.
     *
     * Stopping is the easy half and it was the only half: the ringing
     * ended, the foreground notification went with the service, and an
     * alarm that had done its whole job into an empty room left no trace of
     * having gone off at all. The note is what turns "it stopped" into
     * something you can find out about later.
     */
    private val giveUp = Runnable {
        noteMissed()
        stopSelf()
    }

    private fun noteMissed() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MISSED_CHANNEL_ID,
                    getString(R.string.missed_channel_name),
                    // Not high: this is a record of something that already
                    // happened, and a second alarm-loud interruption for it
                    // would be the app shouting about its own silence.
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    description = getString(R.string.missed_channel_desc)
                    group = GROUP_ID
                }
            )
        }
        val what = if (fromTimer) {
            getString(R.string.missed_timer)
        } else {
            label.ifBlank { getString(R.string.missed_alarm) }
        }
        val rang = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(System.currentTimeMillis() - ringTimeoutMs(this)))
        manager.notify(
            MISSED_NOTIFICATION_ID,
            NotificationCompat.Builder(this, MISSED_CHANNEL_ID)
                .setSmallIcon(if (fromTimer) R.drawable.ic_timer else R.drawable.ic_notification)
                .setContentTitle(getString(R.string.missed_title))
                .setContentText(getString(R.string.missed_text_fmt, what, rang))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        6,
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        chimePlayer.release()
        // Both cleared, not just the first: a ring screen opened by the
        // fallback path with no service behind it reads these, and a stale
        // start time would have it counting up from a previous alarm.
        ringingFromTimer = false
        ringingSince = 0L
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
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // One group, so the three channels sit together in system settings
        // instead of looking like three unrelated apps.
        manager.createNotificationChannelGroup(
            android.app.NotificationChannelGroup(
                GROUP_ID, getString(R.string.channel_group_clock)
            )
        )
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // The service plays its own bells; no notification sound on top.
            setSound(null, null)
            enableVibration(true)
            description = getString(R.string.alarm_channel_desc)
            group = GROUP_ID
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val fullScreen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AlarmRingActivity::class.java)
                // CLEAR_TASK, because NEW_TASK on its own will hand an old
                // ring task straight back — extras and all, since a task is
                // matched on component and never on what it is carrying. The
                // stale flag was the visible half of that; a stale label,
                // sound or snooze setting was the other.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(AlarmScheduler.EXTRA_SOUND, sound)
                .putExtra(AlarmScheduler.EXTRA_SOUND_URI, soundUri)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE, snoozeMinutes)
                .putExtra(AlarmScheduler.EXTRA_LABEL, label)
                // The full-screen ring needs to know what ran out, the same
                // way the notification icon does.
                .putExtra(AlarmScheduler.EXTRA_FROM_TIMER, fromTimer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val theme = ClockThemes.resolve(
            this,
            PreferenceManager.getDefaultSharedPreferences(this).getString(Prefs.THEME, "midnight")
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // A finished countdown is not an alarm, and should not wear its
            // icon: what ran out was a timer.
            .setSmallIcon(if (fromTimer) R.drawable.ic_timer else R.drawable.ic_notification)
            // Ringing is an ongoing foreground notification, so the shade
            // lets it wear the dial's colour across the whole card. At six in
            // the morning that is how you know at a glance whose alarm it is.
            .setColor(theme.face)
            .setColorized(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(label.ifBlank { getString(R.string.alarm_ringing) })
            // No content text, and the header counts up instead of stamping
            // the time: the shade was printing the same clock reading twice
            // over, beside a screen printing it a third time. What it says
            // now is how long this has been going off — the one number none
            // of the three were giving.
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
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
