package com.em87.weirdclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The weather on the home screen, with no clock on it at all.
 *
 * Every other widget here is an instrument for telling the time. This one
 * is not, and it exists because the app already knows the answer and had
 * nowhere to put it: the weather is drawn as a token beside the hour on
 * three of the four faces, at about the size of a fingernail, and somebody
 * who wants to know whether to take a coat is not going to read it there.
 *
 * It draws what the app last agreed with its three services — see
 * [WeatherStore], which asks all three and takes the middle answer — and
 * asks for a fresh one when it wakes up, on the same terms the app does:
 * only with the weather switch on, only with a fix, and rounded to a
 * kilometre before it leaves the phone.
 *
 * Tapping it opens the app, on whichever card that face keeps the weather.
 */
class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        refreshAll(context)
        scheduleTick(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        alarmManager(context)?.cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WEATHER_TICK) {
            refreshAll(context)
            scheduleTick(context)
        }
    }

    companion object {

        /** Our own wake-up: the sky has had time to change. */
        const val ACTION_WEATHER_TICK = "com.em87.weirdclock.WEATHER_TICK"

        /**
         * How often it looks again.
         *
         * Half an hour, which is well inside the interval [WeatherStore]
         * will actually go to the network on — so most of these wake-ups
         * repaint what is already known and cost nothing, and the one that
         * lands after the reading has gone stale is the one that matters.
         * The alarm is inexact on purpose: an exact one needs a permission
         * a weather widget has no business asking for.
         */
        const val EVERY_MS = 30L * 60L * 1000L

        private fun alarmManager(context: Context): android.app.AlarmManager? =
            context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            "weather-widget".hashCode(),
            Intent(context, WeatherWidgetProvider::class.java).setAction(ACTION_WEATHER_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun scheduleTick(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(
                    ComponentName(context, WeatherWidgetProvider::class.java)
                ).isEmpty()
            ) {
                return
            }
            alarmManager(context)?.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + EVERY_MS,
                tickIntent(context)
            )
        }

        /** Repaints every weather widget, and asks for a fresh reading. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WeatherWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            // On the same terms the app asks on, which includes never
            // asking at all with the switch off.
            DayNight.configure(context)
            if (WeatherStore.wanted(context) && DayNight.hasFix()) {
                WeatherStore.refreshInBackground(
                    context, DayNight.latitudeNow(), DayNight.longitudeNow()
                )
            }
            for (id in ids) manager.updateAppWidget(id, buildViews(context, manager, id))
        }

        /** For the tests: the widget as it would be handed to a launcher. */
        internal fun viewsForTest(context: Context, id: Int): RemoteViews =
            buildViews(context, AppWidgetManager.getInstance(context), id)

        private fun buildViews(
            context: Context,
            manager: AppWidgetManager,
            id: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            views.setOnClickPendingIntent(
                R.id.widget_weather_image,
                PendingIntent.getActivity(
                    context,
                    "weather-open".hashCode(),
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            val (w, h) = WidgetRenderer.widgetPixels(context, manager, id)
            views.setImageViewBitmap(
                R.id.widget_weather_image,
                WidgetRenderer.faded(
                    WidgetRenderer.grounded(
                        context, WidgetKind.WEATHER, WidgetRenderer.weatherBitmap(context, w, h)
                    ),
                    WidgetRenderer.alphaOf(context, WidgetKind.WEATHER.alphaKey)
                )
            )
            return views
        }
    }
}
