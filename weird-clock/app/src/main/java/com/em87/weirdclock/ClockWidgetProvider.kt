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
            appWidgetManager.updateAppWidget(widgetId, buildViews(context))
        }
    }

    companion object {

        /** Re-render all widgets after the in-app settings change. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClockWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context))
            }
        }

        private fun buildViews(context: Context): RemoteViews {
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
                val size = 512
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
