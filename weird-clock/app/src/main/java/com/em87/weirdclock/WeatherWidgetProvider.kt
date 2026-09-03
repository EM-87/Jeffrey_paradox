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
        when (intent.action) {
            ACTION_WEATHER_TICK -> {
                refreshAll(context)
                scheduleTick(context)
            }
            ACTION_WEATHER_LOCATE -> locate(context, goAsync())
        }
    }

    companion object {

        /** Our own wake-up: the sky has had time to change. */
        const val ACTION_WEATHER_TICK = "com.em87.weirdclock.WEATHER_TICK"

        /** And the button on it: ask again where we are. */
        const val ACTION_WEATHER_LOCATE = "com.em87.weirdclock.WEATHER_LOCATE"

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

        /**
         * Repaints every weather widget, and asks for a fresh reading.
         *
         * Both halves, and the second one used to be half a thing: the
         * fetch was started on a thread and nothing repainted when it
         * landed, so a newly placed widget drew the empty cache — two
         * dashes where the temperature goes — and kept drawing it until
         * the next half-hourly tick. Reported, correctly, as the widget
         * not working. The repaint now waits for the fetch.
         */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WeatherWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            paint(context, manager, ids)
            // On the same terms the app asks on, which includes never
            // asking at all with the switch off.
            DayNight.configure(context)
            if (!WeatherStore.wanted(context) || !DayNight.hasFix()) return
            if (!WeatherStore.stale(context)) return
            val app = context.applicationContext
            Thread {
                WeatherStore.refresh(app, DayNight.latitudeNow(), DayNight.longitudeNow())
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val again = AppWidgetManager.getInstance(app)
                    paint(app, again, again.getAppWidgetIds(
                        ComponentName(app, WeatherWidgetProvider::class.java)
                    ))
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun paint(context: Context, manager: AppWidgetManager, ids: IntArray) {
            for (id in ids) manager.updateAppWidget(id, buildViews(context, manager, id))
        }

        /**
         * The button: find out where we are, and tell the whole app.
         *
         * A fix is written down once and read by everything — the sunrise,
         * the shadows, the globe, the weather — so asking again from here
         * is not a weather-widget thing, it is the app's one measurement
         * being taken again. Every widget is repainted afterwards for that
         * reason, and the app itself picks it up the next time it is
         * looked at, because it reads the same two floats.
         *
         * A broadcast receiver is allowed about ten seconds of life, which
         * is why the fix gets seven — see
         * [Whereabouts.WAIT_FROM_A_WIDGET_MS].
         */
        private fun locate(
            context: Context,
            pending: android.content.BroadcastReceiver.PendingResult?
        ) {
            val app = context.applicationContext
            fun done() {
                DayNight.configure(app)
                val manager = AppWidgetManager.getInstance(app)
                paint(app, manager, manager.getAppWidgetIds(
                    ComponentName(app, WeatherWidgetProvider::class.java)
                ))
                // Everything else the fix is read by. The whole point of
                // the button is that one measurement serves the lot.
                ClockWidgetProvider.refreshAll(app)
                OrreryWidgetProvider.refreshAll(app)
                pending?.finish()
            }
            fun fetchThenDone() {
                if (!WeatherStore.wanted(app) || !DayNight.hasFix()) {
                    done()
                    return
                }
                Thread {
                    WeatherStore.refresh(app, DayNight.latitudeNow(), DayNight.longitudeNow())
                    android.os.Handler(android.os.Looper.getMainLooper()).post { done() }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
            // What something else has already paid for first, and only
            // then the radio.
            Whereabouts.lastKnown(app)
            DayNight.configure(app)
            Whereabouts.oneFix(
                app, android.os.Looper.getMainLooper(),
                Whereabouts.WAIT_FROM_A_WIDGET_MS
            ) { fetchThenDone() }
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
            views.setOnClickPendingIntent(
                R.id.widget_weather_locate,
                PendingIntent.getBroadcast(
                    context,
                    "weather-locate".hashCode(),
                    Intent(context, WeatherWidgetProvider::class.java)
                        .setAction(ACTION_WEATHER_LOCATE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            val (w, h) = WidgetRenderer.widgetPixels(context, manager, id)
            views.setImageViewBitmap(
                R.id.widget_weather_image,
                WidgetRenderer.dress(
                    context, WidgetKind.WEATHER, WidgetRenderer.weatherBitmap(context, w, h)
                )
            )
            return views
        }
    }
}
