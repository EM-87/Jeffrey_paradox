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
 * Home-screen countdown widget. While a countdown runs it shows the live
 * level and the remaining time (pushed by CountdownService in the
 * background, or by MainActivity while the app is open); idle, it sits
 * full and at rest. Tapping opens the app.
 *
 * What it draws follows the face. On the dial it is an hourglass, with
 * sand in the top bulb and a stream between them; on a screenful of digits
 * it is the same fraction as a progress bar under the time, because sand
 * in a glass is a picture of a fraction and a strip that empties is the
 * digital drawing of the same thing.
 */
class HourglassWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Fresh placements render idle; live pushes overwrite within seconds.
        pushIdle(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Resized: redraw at the new size rather than stretching the old one.
        pushIdle(context)
    }

    companion object {

        fun push(context: Context, remainingMs: Long, totalMs: Long) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, HourglassWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val density = context.resources.displayMetrics.density
            val open = PendingIntent.getActivity(
                context,
                5,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_TIMER, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Drawn at the size it is actually being shown at: a fixed
            // 110x165 bitmap was blurred up whenever the widget was
            // stretched. Capped, because every push crosses IPC whole.
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                    .takeIf { it > 0 } ?: 110
                val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    .takeIf { it > 0 } ?: 165
                val bitmap = renderBitmap(
                    context, remainingMs, totalMs,
                    (wDp.coerceIn(57, 200) * density).toInt(),
                    (hDp.coerceIn(80, 300) * density).toInt()
                )
                val views = RemoteViews(context.packageName, R.layout.widget_hourglass)
                views.setImageViewBitmap(R.id.widget_hourglass_image, bitmap)
                views.setOnClickPendingIntent(R.id.widget_hourglass_image, open)
                manager.updateAppWidget(id, views)
            }
        }

        fun pushIdle(context: Context) = push(context, 0L, 1L)

        /** For the tests: what this widget would actually put on the glass. */
        internal fun renderForTest(
            context: Context,
            remainingMs: Long,
            totalMs: Long,
            width: Int,
            height: Int
        ): Bitmap = renderBitmap(context, remainingMs, totalMs, width, height)

        /**
         * Repaints every countdown widget with whatever is on the timer.
         *
         * The other two providers have had one of these all along; this
         * one only ever repainted when the countdown itself moved, so a
         * change of opacity or of theme had to wait for the next tick of a
         * timer that might not be running.
         */
        fun refreshAll(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val endsAt = prefs.getLong(Prefs.COUNTDOWN_ENDS_AT, 0L)
            val total = prefs.getLong(Prefs.COUNTDOWN_TOTAL, 1L)
            val left = (endsAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            push(context, left, total.coerceAtLeast(1L))
        }

        private fun renderBitmap(
            context: Context,
            remainingMs: Long,
            totalMs: Long,
            width: Int,
            height: Int
        ): Bitmap {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val view = HourglassView(context).apply {
                theme = ClockThemes.resolve(context, prefs.getString(Prefs.THEME, "midnight"))
                // Its own answer, and the app's only until somebody gives
                // it one. Sand in a glass on a face that has no glass in
                // it was the first version of this and it was wrong the
                // other way round: the widget changed shape because the
                // clock inside the app had, which from the home screen is
                // a widget changing for no reason at all.
                lcd = prefs.getBoolean(
                    Prefs.widgetHourglassDigits, !Face.of(prefs).hands
                )
                plain = prefs.getBoolean(Prefs.WIDGET_SAND_PLAIN, false)
                this.totalMs = totalMs
                this.remainingMs = remainingMs
            }
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            // Faded once at the end, by the amount asked for, exactly as
            // the other two widgets are — see [WidgetRenderer.faded].
            return WidgetRenderer.faded(
                WidgetRenderer.grounded(context, WidgetKind.HOURGLASS, bitmap),
                WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA_HOURGLASS)
            )
        }
    }
}
