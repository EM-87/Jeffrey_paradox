package com.em87.weirdclock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import androidx.preference.PreferenceManager

/**
 * The solar system on the home screen: where the planets actually are,
 * today.
 *
 * The other two widgets are a clock and a countdown, and both are about
 * the next few minutes. This one is not about minutes at all. Mercury
 * moves four degrees in a day and Neptune six hundredths of one, so
 * nothing on it will have visibly changed by the time you look again —
 * which sounds like an argument against a widget and is the point of it.
 * It is a window, and the interesting thing about a window is that it is
 * the same as it was and one day will not be.
 *
 * That slowness is also the whole battery story. The clock widget hands
 * the ticking to the platform's own AnalogClock; this one has nothing to
 * hand over, so it books its own wake-up — every few hours, inexact, plus
 * the date and time-zone broadcasts. The Moon is the fastest thing on it
 * at half a degree an hour, and half a degree on a widget-sized dial is
 * less than a pixel.
 */
class OrreryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, appWidgetManager, id))
        }
        scheduleTick(context)
    }

    /** Stretched or squashed: drawn again at the size it is now. */
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
        scheduleTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The last one is gone; stop waking up for it.
        alarmManager(context)?.cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> refreshAll(context)
            ACTION_ORRERY_TICK -> {
                refreshAll(context)
                scheduleTick(context)
            }
        }
    }

    companion object {

        /** Our own wake-up: the sky has moved enough to be worth redrawing. */
        const val ACTION_ORRERY_TICK = "com.em87.weirdclock.ORRERY_TICK"

        /**
         * How long between redraws.
         *
         * Chosen from the fastest thing on the dial rather than picked as
         * a round number. The Moon goes round in twenty-seven days, which
         * is half a degree an hour; six hours of that is three degrees,
         * and three degrees of a moon orbit drawn at widget size is about
         * a pixel. Anything more often is burning a wake-up to move
         * nothing.
         */
        private const val TICK_MS = 6 * 60 * 60_000L


        private fun alarmManager(context: Context): android.app.AlarmManager? =
            context.getSystemService(android.app.AlarmManager::class.java)

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            7,
            Intent(context, OrreryWidgetProvider::class.java).setAction(ACTION_ORRERY_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Books the next redraw.
         *
         * Inexact, deliberately: being an hour late to a planet that moves
         * four degrees a day costs nothing anybody could see, and an exact
         * alarm needs a permission a picture of the sky has no business
         * asking for.
         */
        fun scheduleTick(context: Context) {
            val manager = alarmManager(context) ?: return
            manager.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + TICK_MS,
                tickIntent(context)
            )
        }

        /** Every placement of this widget, drawn again. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, OrreryWidgetProvider::class.java)
            )
            for (id in ids) manager.updateAppWidget(id, buildViews(context, manager, id))
        }


        /**
         * The sky as a bitmap, at [atMs].
         *
         * The same [OrreryDial] the in-app card draws through, at the same
         * settings — theme, comets, the lot — because a widget that is
         * nearly the app is worse than one that is not it at all.
         */
        internal fun bitmap(context: Context, size: Int, atMs: Long): Bitmap {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val theme = ClockThemes.resolve(context, prefs.getString(Prefs.THEME, "midnight"))
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val alpha = WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA_ORRERY)
            val face = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.face
            }
            val half = size / 2f
            // The same fraction of the bitmap the clock widget's dial
            // takes, so the two are the same circle at the same widget
            // size and can be stood side by side on a home screen. They
            // were 0.94 and 0.90, which nobody would spot apart and which
            // made that impossible.
            val r = half * WidgetRenderer.DIAL_FRACTION
            canvas.drawCircle(half, half, r, face)
            OrreryDial.draw(
                canvas, half, half, r, theme, atMs, 1f,
                Orrery.longitude(Orrery.Body.MOON, atMs),
                comets = prefs.getBoolean(Prefs.COMETS, false)
            )
            return WidgetRenderer.faded(bitmap, alpha)
        }

        /** For the tests: the widget as it would be handed to a launcher. */
        internal fun viewsForTest(context: Context, id: Int): RemoteViews =
            buildViews(context, AppWidgetManager.getInstance(context), id)

        private fun buildViews(
            context: Context,
            manager: AppWidgetManager,
            id: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_orrery)
            // Straight to the sky rather than to the front page: somebody
            // tapping a picture of the solar system is asking for the
            // solar system.
            val open = PendingIntent.getActivity(
                context,
                7,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SKY, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setImageViewBitmap(
                R.id.widget_orrery_image,
                bitmap(context, WidgetRenderer.dialPixels(context, manager, id), System.currentTimeMillis())
            )
            views.setOnClickPendingIntent(R.id.widget_orrery_image, open)
            return views
        }
    }
}
