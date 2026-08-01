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

        /**
         * Left over from the system-bubble attempt of 11.3–11.6. Kept only so
         * the row it posted can be swept out of shades that still have one.
         */
        private const val RETIRED_BUBBLE_NOTIFICATION_ID = 5
        private const val RETIRED_BUBBLE_SHORTCUT = "timer_bubble"

        const val RESULT_FINISHED = "finished"

        /** Notification action: one more minute, without opening anything. */
        const val ACTION_ADD_MINUTE = "com.em87.weirdclock.action.ADD_MINUTE"
        const val RESULT_CANCELLED = "cancelled"

        /** The shade added a minute; the app resyncs from the service. */
        const val RESULT_EXTENDED = "extended"

        /**
         * Writes down where the running countdown ends, so that anything
         * outside the app — the Quick Settings tile above all — can answer
         * "how long is left" without waking a service or opening a screen.
         */
        fun publish(context: Context, endsAtElapsed: Long, totalMs: Long) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(Prefs.COUNTDOWN_ENDS_AT, endsAtElapsed)
                .putLong(Prefs.COUNTDOWN_TOTAL, totalMs)
                .apply()
        }

        fun clearPublished(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(Prefs.COUNTDOWN_ENDS_AT, 0L)
                .apply()
        }

        fun start(context: Context, endsAtElapsed: Long, totalMs: Long) {
            publish(context, endsAtElapsed, totalMs)
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
    private var lastWidgetPush = 0L

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
                // The overlay wants a smooth half-second tick; the widget
                // does not — every push is a full bitmap across IPC, and
                // once a second is already more than the sand shows.
                val now = SystemClock.elapsedRealtime()
                if (now - lastWidgetPush >= 1000L) {
                    lastWidgetPush = now
                    HourglassWidgetProvider.push(this@CountdownService, remaining, totalMs)
                }
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
        if (prefs.getString(Prefs.COUNTDOWN_FLOAT, Prefs.FLOAT_OVERLAY) != Prefs.FLOAT_OVERLAY) return
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
            clearPublished(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADD_MINUTE) {
            // Straight from the shade, without opening anything: the pot
            // boils over, you buy another minute with one tap.
            endsAtElapsed += 60_000L
            totalMs += 60_000L
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(Prefs.COUNTDOWN_RESULT, RESULT_EXTENDED)
                .putLong(Prefs.COUNTDOWN_ENDS_AT, endsAtElapsed)
                .putLong(Prefs.COUNTDOWN_TOTAL, totalMs)
                .apply()
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                buildNotification((endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            )
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
        clearPublished(this)
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
                    .putExtra(AlarmScheduler.EXTRA_FROM_TIMER, true)
            )
            stopSelf()
            return
        }
        chimePlayer.playQuarters()
        getSystemService(NotificationManager::class.java)?.notify(
            DONE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_timer)
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
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            android.app.NotificationChannelGroup(
                AlarmService.GROUP_ID, getString(R.string.channel_group_clock)
            )
        )
        retireBubble(manager)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.countdown_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                description = getString(R.string.countdown_channel_desc)
                group = AlarmService.GROUP_ID
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /**
     * Sweeps up after the system bubble, which shipped in 11.3 and never once
     * floated.
     *
     * Three versions were spent on it and the diagnosis only landed on the way
     * out: a notification is only bubbled if Android first classes it as a
     * conversation, and that takes MessagingStyle — a shortcut id and a Person
     * are necessary but not sufficient. Ours had the latter and not the
     * former, so every timer put a second, ordinary row in the shade instead
     * of a bubble. That much was fixable. What is not is the setting behind
     * it: since Android 11 the default is "selected conversations can bubble",
     * and a conversation the user has never promoted appears as — a row in the
     * shade. Even done right, the first sight of it is the bug being reported.
     * A feature whose success case is indistinguishable from its failure case
     * is not a feature, so it is gone.
     *
     * What is left is other people's phones: a stale channel in Android's
     * settings, a dynamic shortcut in the launcher, and possibly a frozen row
     * still sitting in the shade. All three go here.
     */
    @androidx.annotation.RequiresApi(26)
    private fun retireBubble(manager: NotificationManager) {
        manager.cancel(RETIRED_BUBBLE_NOTIFICATION_ID)
        manager.deleteNotificationChannel(RETIRED_BUBBLE_SHORTCUT)
        try {
            androidx.core.content.pm.ShortcutManagerCompat.removeLongLivedShortcuts(
                this, listOf(RETIRED_BUBBLE_SHORTCUT)
            )
        } catch (e: Exception) {
            // A launcher that will not give the shortcut back is not worth
            // taking the timer down for.
        }
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
        // An ongoing foreground notification is allowed to take a colour and
        // wear it across its whole card in the shade. Ours takes the dial's
        // own face colour, so the timer in the shade is recognisably a piece
        // of the same clock rather than a grey system row.
        val theme = ClockThemes.resolve(
            this,
            PreferenceManager.getDefaultSharedPreferences(this).getString(Prefs.THEME, "midnight")
        )
        val plusOne = PendingIntent.getService(
            this,
            7,
            Intent(this, CountdownService::class.java).setAction(ACTION_ADD_MINUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.countdown_notification_title))
            .setColor(theme.face)
            .setColorized(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The alarm's notification says what it is and this one said
            // nothing, which is what Do Not Disturb and the shade's ranking
            // read to decide how to treat it.
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.countdown_add_minute), plusOne)
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
        return builder.build()
    }

}
