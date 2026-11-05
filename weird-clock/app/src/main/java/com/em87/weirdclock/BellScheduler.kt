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
 *
 * Which kind of alarm matters more than it looks, and the rules are in the
 * platform's source rather than its documentation. An ordinary alarm from
 * an app that has not been opened for a day is allowed ten deliveries an
 * hour and the rest are held to the end of the hour; the battery saver
 * and Doze hold it further. The one ordinary kind exempt from all three
 * is exact *and* allowed while idle, and that is what is booked here. The
 * one kind exempt from everything, including the makers' own battery
 * policies, is an alarm clock — which the lock screen then announces as
 * the next alarm, so that is a switch, see [Prefs.BELLS_AS_ALARM].
 *
 * The strike is delivered straight to [BellService] as a foreground
 * service, not to a receiver that then asks to start one. The hop was a
 * second place for the phone to say no, and a second to lose.
 */
object BellScheduler {

    private const val REQUEST_CODE = 200

    /** The alarm clock's "show me" intent, when the bells are one. */
    private const val SHOW_REQUEST_CODE = 201

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
     * The instant the strike was armed for, in wall-clock milliseconds.
     *
     * So the service can measure how late the phone delivered it instead
     * of anybody arguing about it — see [recordLateness]. A strike two
     * minutes late was heard, from an app whose whole job is the time, and
     * the one thing worse than that is not being able to show it.
     */
    const val EXTRA_ARMED_FOR = "extra_armed_for"

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
            cancel(context, manager)
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
        val pending = strikeIntent(
            context,
            slot.get(Calendar.MINUTE),
            slot.get(Calendar.HOUR_OF_DAY),
            next
        )
        // The receiver's own entry, from a build before this one, would
        // otherwise go on ringing beside the new one across the update.
        if (Build.VERSION.SDK_INT >= 26) manager.cancel(receiverIntent(context))
        try {
            if (prefs.getBoolean(Prefs.BELLS_AS_ALARM, false)) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(next, showIntent(context)), pending)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            }
        } catch (e: SecurityException) {
            // The phone will not let this app set an exact alarm at all.
            // The window is the best that is left, and the settings say so
            // — see [describe].
            manager.setWindow(AlarmManager.RTC_WAKEUP, next, 60_000L, pending)
        }
    }

    /** Both kinds of entry, because either build may have left one. */
    private fun cancel(context: Context, manager: AlarmManager) {
        manager.cancel(strikeIntent(context))
        if (Build.VERSION.SDK_INT >= 26) manager.cancel(receiverIntent(context))
    }

    /**
     * What the alarm fires: the service itself, where the platform lets a
     * pending intent start a foreground service, and the receiver before
     * that.
     */
    private fun strikeIntent(
        context: Context,
        minute: Int = 0,
        hour: Int = 0,
        armedFor: Long = 0L
    ): PendingIntent =
        if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(
                context,
                REQUEST_CODE,
                Intent(context, BellService::class.java).carrying(minute, hour, armedFor),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            receiverIntent(context, minute, hour, armedFor)
        }

    private fun receiverIntent(
        context: Context,
        minute: Int = 0,
        hour: Int = 0,
        armedFor: Long = 0L
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BellReceiver::class.java).carrying(minute, hour, armedFor),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun Intent.carrying(minute: Int, hour: Int, armedFor: Long): Intent = this
        .putExtra(EXTRA_MINUTE, minute)
        .putExtra(EXTRA_ON_THE_HOUR, minute == 0)
        .putExtra(EXTRA_HOUR, hour)
        .putExtra(EXTRA_ARMED_FOR, armedFor)

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        SHOW_REQUEST_CODE,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Writes down how late the phone delivered a strike: this one, and the
     * worst so far. Findings, not settings — see [Prefs.BELL_LAST_LATE_MS].
     */
    fun recordLateness(prefs: android.content.SharedPreferences, dueMs: Long, nowMs: Long) {
        val late = (nowMs - dueMs).coerceAtLeast(0L)
        prefs.edit()
            .putLong(Prefs.BELL_LAST_DUE, dueMs)
            .putLong(Prefs.BELL_LAST_LATE_MS, late)
            .putLong(
                Prefs.BELL_WORST_LATE_MS,
                maxOf(late, prefs.getLong(Prefs.BELL_WORST_LATE_MS, 0L))
            )
            .apply()
    }

    /**
     * What the "ring with the app closed" row says under its title: what
     * it does, how late the phone has been about it, and anything on the
     * phone that stands in its way.
     */
    fun describe(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lines = ArrayList<String>()
        lines += context.getString(R.string.pref_bells_background_summary)
        if (prefs.contains(Prefs.BELL_LAST_DUE)) {
            lines += context.getString(
                R.string.bells_last_strike,
                android.text.format.DateFormat.getTimeFormat(context)
                    .format(java.util.Date(prefs.getLong(Prefs.BELL_LAST_DUE, 0L))),
                Bells.lateText(prefs.getLong(Prefs.BELL_LAST_LATE_MS, 0L)),
                Bells.lateText(prefs.getLong(Prefs.BELL_WORST_LATE_MS, 0L))
            )
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false
        ) {
            lines += context.getString(R.string.exact_alarms_title)
        }
        if (Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(android.app.ActivityManager::class.java)
                ?.isBackgroundRestricted == true
        ) {
            lines += context.getString(R.string.bells_restricted)
        }
        return lines.joinToString("\n")
    }
}

/**
 * The strike's doorway on phones too old for a pending intent to start a
 * foreground service, and for an alarm armed by a build before this one,
 * which is still in the system's queue across the update and arrives here.
 */
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
                    .putExtra(
                        BellScheduler.EXTRA_ARMED_FOR,
                        intent.getLongExtra(BellScheduler.EXTRA_ARMED_FOR, 0L)
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
        // How late the phone was about this one, written down first — it is
        // the one fact here nothing else can reconstruct afterwards.
        intent?.getLongExtra(BellScheduler.EXTRA_ARMED_FOR, 0L)?.takeIf { it > 0L }?.let {
            BellScheduler.recordLateness(prefs, it, System.currentTimeMillis())
        }
        // And the next one armed before anything else, so the chain
        // survives whatever the strike itself runs into. The receiver used
        // to do this; the alarm comes straight here now.
        BellScheduler.update(this)
        // The app open on the hour strikes it itself, from its own loop,
        // and the chain underneath is still armed — it is re-armed every
        // time the app is left — so this service arrives on the same
        // second. The two used to ring over each other.
        if (MainActivity.inFront) {
            stopSelf()
            return START_NOT_STICKY
        }
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
        // And an alarm ringing right now keeps it quiet too, unless the
        // bells have been given the right of way. Seven in the morning is
        // an hour to strike and an hour to be woken at, and the two used
        // to sound over each other.
        val giveWay = !Bells.mayStrike(
            prefs.getString(Prefs.BELL_PRIORITY, Bells.PRIORITY_ALARM),
            AlarmService.ringing
        )
        if (!quietHours && !giveWay) {
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
