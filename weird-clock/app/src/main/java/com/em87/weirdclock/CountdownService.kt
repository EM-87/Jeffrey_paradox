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

        /** The long-lived shortcut a bubble has to hang from. */
        private const val BUBBLE_SHORTCUT = "timer_bubble"
        private const val BUBBLE_CHANNEL_ID = "timer_bubble"
        private const val BUBBLE_NOTIFICATION_ID = 5

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
                    postBubble(remaining)
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
            cancelBubble()
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
        postBubble(remaining)
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
        cancelBubble()
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
        if (Build.VERSION.SDK_INT >= 30) {
            // Bubbles need a channel of at least default importance, and one
            // that is allowed to bubble at all. The timer's own channel is
            // deliberately quiet, so the bubble gets its own.
            manager.createNotificationChannel(
                NotificationChannel(
                    BUBBLE_CHANNEL_ID,
                    getString(R.string.bubble_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    group = AlarmService.GROUP_ID
                    setAllowBubbles(true)
                }
            )
        }
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
                // Without this the platform will not bubble anything on this
                // channel, whatever metadata the notification carries.
                if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
            }
        )
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

    /**
     * The bubble goes on a notification of its own.
     *
     * The obvious thing was to bubble the timer notification itself, and that
     * was the mistake: that one belongs to a foreground service, is ongoing,
     * colorized, and carries a chronometer and a progress bar. The platform
     * will not float that — a bubble has to look like a conversation, and a
     * running service's notification is the opposite of one. So the service
     * keeps its notification and the bubble gets a second, silent one whose
     * only job is to be bubbled.
     *
     * The rest is the platform's price of entry: since Android 11 a bubble
     * must point at a long-lived shortcut with a person on it, and the icon
     * has to be a real bitmap rather than a vector.
     */
    private fun postBubble(remainingMs: Long) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getString(Prefs.COUNTDOWN_FLOAT, Prefs.FLOAT_OVERLAY) != Prefs.FLOAT_BUBBLE) return
        // If the system will not bubble for this app — the switch is off by
        // default on most phones — posting this anyway just leaves a second
        // row in the shade for one timer. Better nothing than clutter.
        if (Build.VERSION.SDK_INT >= 31 &&
            getSystemService(NotificationManager::class.java)?.areBubblesEnabled() != true
        ) {
            return
        }
        val theme = ClockThemes.resolve(this, prefs.getString(Prefs.THEME, "midnight"))
        val icon = bubbleIcon(theme)

        val person = androidx.core.app.Person.Builder()
            .setName(getString(R.string.chrono_label_countdown))
            .setKey(BUBBLE_SHORTCUT)
            .setIcon(icon)
            .setBot(true)
            .setImportant(true)
            .build()
        val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, BUBBLE_SHORTCUT)
            .setLongLived(true)
            .setIntent(Intent(this, BubbleActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setShortLabel(getString(R.string.chrono_label_countdown))
            .setIcon(icon)
            .setPerson(person)
            .build()
        androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        val bubbleIntent = PendingIntent.getActivity(
            this,
            9,
            Intent(this, BubbleActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val minutes = (remainingMs / 60_000L).toInt()
        val seconds = ((remainingMs / 1000L) % 60L).toInt()
        val notification = NotificationCompat.Builder(this, BUBBLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.chrono_label_countdown))
            .setContentText(String.format("%d:%02d", minutes, seconds))
            .setShortcutId(BUBBLE_SHORTCUT)
            .addPerson(person)
            .setLocusId(androidx.core.content.LocusIdCompat(BUBBLE_SHORTCUT))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
                    .setDesiredHeight(180)
                    .setAutoExpandBubble(false)
                    .setSuppressNotification(true)
                    .build()
            )
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(BUBBLE_NOTIFICATION_ID, notification)
    }

    private fun cancelBubble() {
        getSystemService(NotificationManager::class.java)?.cancel(BUBBLE_NOTIFICATION_ID)
    }

    /**
     * A bubble wants a bitmap it can mask into a circle, not a line drawing:
     * a vector resource here is silently refused on some versions.
     */
    private fun bubbleIcon(theme: ClockTheme): androidx.core.graphics.drawable.IconCompat {
        val size = (108 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(theme.face)
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_timer)?.apply {
            setTint(theme.decimal)
            val inset = size / 4
            setBounds(inset, inset, size - inset, size - inset)
            draw(canvas)
        }
        return androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
