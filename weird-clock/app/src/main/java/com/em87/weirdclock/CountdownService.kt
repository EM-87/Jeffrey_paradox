package com.em87.weirdclock

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlin.math.hypot
import kotlin.math.roundToInt

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

    private var overlay: HourglassView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    private val updateLoop = object : Runnable {
        override fun run() {
            val remaining = endsAtElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                finish()
            } else {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(remaining))
                overlay?.remainingMs = remaining
                HourglassWidgetProvider.push(this@CountdownService, remaining, totalMs)
                handler.postDelayed(this, if (overlay != null) 500L else 2000L)
            }
        }
    }

    /**
     * The floating hourglass: a draggable overlay bubble that keeps the
     * countdown literally on screen over other apps. Optional (pref, on by
     * default) and gated by the draw-over-apps permission; without it the
     * notification progress bar carries the load alone.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun maybeShowOverlay() {
        if (overlay != null) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(Prefs.COUNTDOWN_BUBBLE, true)) return
        if (!Settings.canDrawOverlays(this)) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val view = HourglassView(this).apply {
            totalMs = this@CountdownService.totalMs
            theme = ClockThemes.resolve(this@CountdownService, prefs.getString(Prefs.THEME, "midnight"))
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            (84 * density).toInt(),
            (128 * density).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.BUBBLE_X, (16 * density).toInt())
            y = prefs.getInt(Prefs.BUBBLE_Y, (120 * density).toInt())
        }

        val slop = 12 * density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downRawX).roundToInt()
                    params.y = startY + (event.rawY - downRawY).roundToInt()
                    try {
                        wm.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // View already detached; nothing to move.
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (hypot(event.rawX - downRawX, event.rawY - downRawY) < slop) {
                        // A tap opens the app on the countdown.
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_TIMER, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        prefs.edit()
                            .putInt(Prefs.BUBBLE_X, params.x)
                            .putInt(Prefs.BUBBLE_Y, params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, params)
            overlay = view
            overlayParams = params
            windowManager = wm
        } catch (e: Exception) {
            // Permission races or exotic ROMs: fall back to the notification.
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        overlayParams = null
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // Already gone.
        }
        windowManager = null
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
        maybeShowOverlay()
        overlay?.totalMs = totalMs
        handler.removeCallbacksAndMessages(null)
        handler.post(updateLoop)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        chimePlayer.release()
        super.onDestroy()
    }

    private fun finish() {
        if (finished) return
        finished = true
        removeOverlay()
        HourglassWidgetProvider.pushIdle(this)
        setResult(RESULT_FINISHED)
        val persistent = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(Prefs.COUNTDOWN_PERSISTENT, true)
        if (persistent) {
            // Hand over to the alarm service: it loops the bells with a
            // full-screen stop button until the user validates. Labeled so
            // the notification says what finished, not what time it is.
            ContextCompat.startForegroundService(
                this,
                Intent(this, AlarmService::class.java)
                    .putExtra(AlarmScheduler.EXTRA_LABEL, getString(R.string.countdown_finished))
            )
            stopSelf()
            return
        }
        chimePlayer.playBellSequence(3, false, ChimePlayer.DAY_CHIME_HZ, 1.2, 0.3)
        getSystemService(NotificationManager::class.java)?.notify(
            DONE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
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
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TIMER, true),
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
            .setSmallIcon(R.drawable.ic_notification)
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
