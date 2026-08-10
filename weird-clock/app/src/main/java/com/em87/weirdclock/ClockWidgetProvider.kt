package com.em87.weirdclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.RemoteViews
import androidx.preference.PreferenceManager

/**
 * Home-screen widget. It uses the platform AnalogClock remote view, so the
 * system keeps it ticking all day with zero battery cost to the app. On
 * API 31+ the dial and hands are re-rendered from the app's current settings
 * (theme, hours on the dial, numerals, date), so the widget is a copy of the
 * in-app clock; older devices fall back to the static Midnight drawables.
 * Tapping it opens the full app.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(widgetId, buildViews(context, appWidgetManager, widgetId))
        }
        scheduleSkyTick(context)
    }

    /**
     * Stretched or squashed: draw it again at the size it is now.
     *
     * The widget has always declared itself resizable, so the launcher let
     * you pull it out — and then scaled a 512-pixel bitmap up to whatever
     * you had made it, which is how a clock face ends up with soft edges.
     * The hourglass widget has done this properly all along; this one never
     * heard that it had been resized at all.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        appWidgetManager.updateAppWidget(
            appWidgetId, buildViews(context, appWidgetManager, appWidgetId)
        )
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleSkyTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The last widget is gone; stop waking up for it.
        alarmManager(context)?.cancel(skyIntent(context))
    }

    /**
     * The hands are the system's own AnalogClock and tick by themselves, but
     * the dial — with the date complication painted on it — is a bitmap this
     * app renders. Nothing was ever repainting it, so a widget showing the
     * date sat on yesterday's until the app happened to be opened and left.
     * Midnight, a manual clock change and a flight across time zones each
     * ask for a fresh one.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> refreshAll(context)
            ACTION_SKY_TICK -> {
                refreshAll(context)
                scheduleSkyTick(context)
            }
        }
    }

    companion object {

        /** The size a widget nobody has stretched is drawn at, in dp. */
        private const val DEFAULT_DIAL_DP = 160

        /** And the ends of the range, so IPC never carries a poster. */
        private const val MIN_DIAL_DP = 64
        private const val MAX_DIAL_DP = 320

        /** Our own wake-up: the sky has changed and the dial is stale. */
        const val ACTION_SKY_TICK = "com.em87.weirdclock.SKY_TICK"

        private fun alarmManager(context: Context): android.app.AlarmManager? =
            context.getSystemService(android.app.AlarmManager::class.java)

        private fun skyIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, ClockWidgetProvider::class.java).setAction(ACTION_SKY_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Wakes the widget when the sky is next due to change.
         *
         * The hands are the system's own AnalogClock and tick by
         * themselves; everything else on the dial is a bitmap this app
         * paints, and nothing was asking for a fresh one at sunrise. So a
         * widget drew the moon all morning and the sun all night until the
         * app happened to be opened — the one complication whose whole
         * purpose is telling you it is light out, wrong for hours at a
         * time.
         *
         * There is no broadcast for "the sun came up", so the widget books
         * its own. One alarm, re-armed each time it fires: hours apart in
         * the middle of the day or the night, a few minutes apart while the
         * sun is actually crossing the horizon and the glyph is sinking
         * through it. Inexact on purpose — being a couple of minutes late
         * to a sunrise costs nothing, and an exact alarm needs a permission
         * a clock has no business asking for.
         */
        fun scheduleSkyTick(context: Context) {
            // Below 31 the dial is a static drawable with no sky on it, so
            // there would be nothing to repaint when the alarm went off.
            if (Build.VERSION.SDK_INT < 31) return
            val manager = alarmManager(context) ?: return
            val at = System.currentTimeMillis() + nextSkyChangeMs(context)
            manager.set(android.app.AlarmManager.RTC, at, skyIntent(context))
        }

        /** How long until the dial would draw something different, in ms. */
        internal fun nextSkyChangeMs(context: Context): Long {
            DayNight.configure(context)
            val now = java.util.Calendar.getInstance()
            val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                now.get(java.util.Calendar.MINUTE)
            val sky = DayNight.sky(minuteNow)
            // Nowhere to stand: the moon's phase is all there is to draw and
            // it moves too slowly to chase. Midnight will catch it.
            if (sky == null) return 6 * 60 * 60_000L
            // Mid-crossing the glyph slides continuously, so look again
            // soon enough that it is never far out of step.
            if (sky is DayNight.Sky.Twilight) return 4 * 60_000L
            // Otherwise sleep until the sky is a different thing, which is
            // hours away in either direction.
            for (ahead in 1..(24 * 60)) {
                val at = (minuteNow + ahead) % 1440
                if (DayNight.sky(at) != sky) return ahead * 60_000L
            }
            return 6 * 60 * 60_000L
        }

        /** Re-render all widgets after the in-app settings change. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClockWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, manager, id))
            }
        }

        /**
         * How many pixels square to render the dial at, for the widget
         * with this id.
         *
         * Capped at both ends: every push crosses process boundaries whole,
         * so a bitmap sized to a tablet's home screen is a bitmap being
         * copied through IPC several times a minute.
         */
        internal fun dialPixels(context: Context, manager: AppWidgetManager, id: Int): Int {
            val options = manager.getAppWidgetOptions(id)
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            // A dial is round, so the square it needs is the smaller side.
            val sideDp = minOf(
                wDp.takeIf { it > 0 } ?: DEFAULT_DIAL_DP,
                hDp.takeIf { it > 0 } ?: DEFAULT_DIAL_DP
            )
            val density = context.resources.displayMetrics.density
            return (sideDp.coerceIn(MIN_DIAL_DP, MAX_DIAL_DP) * density).toInt()
        }

        private fun buildViews(
            context: Context,
            manager: AppWidgetManager,
            id: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_clock)
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_analog_clock, openApp)

            if (Build.VERSION.SDK_INT >= 31) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val theme = ClockThemes.resolve(context, prefs.getString(Prefs.THEME, "midnight"))
                val size = dialPixels(context, manager, id)
                // On polygonal dials the rotating hand bitmaps must fit the
                // inscribed circle, or they'd poke through the flat edges.
                val fit = WidgetRenderer.handFitFraction(context)
                views.setIcon(
                    R.id.widget_analog_clock, "setDial",
                    Icon.createWithBitmap(WidgetRenderer.dialBitmap(context, size))
                )
                views.setIcon(
                    R.id.widget_analog_clock, "setHourHand",
                    Icon.createWithBitmap(WidgetRenderer.handBitmap(size, theme.hourHand, 0.52f * fit, 0.10f, 0.045f))
                )
                views.setIcon(
                    R.id.widget_analog_clock, "setMinuteHand",
                    Icon.createWithBitmap(WidgetRenderer.handBitmap(size, theme.minuteHand, 0.74f * fit, 0.12f, 0.03f))
                )
                val secondHand = if (prefs.getBoolean(Prefs.SECOND_HAND, true)) {
                    WidgetRenderer.handBitmap(size, theme.secondHand, 0.82f * fit, 0.18f, 0.012f)
                } else {
                    WidgetRenderer.emptyBitmap()
                }
                views.setIcon(
                    R.id.widget_analog_clock, "setSecondHand",
                    Icon.createWithBitmap(secondHand)
                )
            }
            return views
        }
    }
}
