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
 * Home-screen hourglass widget. While a countdown runs it shows the live
 * sand level and remaining time (pushed by CountdownService in the
 * background, or by MainActivity while the app is open); idle, it sits with
 * all the sand at rest. Tapping opens the app.
 */
class HourglassWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Fresh placements render idle; live pushes overwrite within seconds.
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
            val bitmap = renderBitmap(
                context, remainingMs, totalMs,
                (110 * density).toInt(), (165 * density).toInt()
            )
            val views = RemoteViews(context.packageName, R.layout.widget_hourglass)
            views.setImageViewBitmap(R.id.widget_hourglass_image, bitmap)
            views.setOnClickPendingIntent(
                R.id.widget_hourglass_image,
                PendingIntent.getActivity(
                    context,
                    5,
                    Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TIMER, true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            for (id in ids) manager.updateAppWidget(id, views)
        }

        fun pushIdle(context: Context) = push(context, 0L, 1L)

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
                this.totalMs = totalMs
                this.remainingMs = remainingMs
            }
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }
    }
}
