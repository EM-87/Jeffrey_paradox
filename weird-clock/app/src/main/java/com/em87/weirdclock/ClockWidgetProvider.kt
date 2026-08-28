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
 * Home-screen widget, in whichever of the two clocks this app is.
 *
 * On the dial it is the platform's AnalogClock remote view, so the system
 * keeps it ticking all day at no cost to the app; on API 31+ the dial and
 * hands are re-rendered from the app's own settings — theme, hours on the
 * dial, numerals, date — so the widget is a copy of the in-app clock, and
 * older devices fall back to the static Midnight drawables.
 *
 * On the face with no hands it is a readout on a panel, drawn by this app
 * — see [WidgetRenderer.digitalBitmap]. Somebody who chose a screenful of
 * digits and then found an analogue dial on their home screen was being
 * shown a clock the app itself no longer has, in the one place they see it
 * without opening anything.
 *
 * The two are not the same kind of object and the difference is the whole
 * design of this file. The AnalogClock ticks itself; a bitmap has to be
 * pushed to the launcher whole, once a minute, on an alarm this widget
 * books for itself. So the digital one shows no seconds — see the
 * renderer, which says why — and the alarm asks [nextRepaintMs] how long
 * it may sleep rather than assuming.
 *
 * Tapping either opens the full app.
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

        /**
         * Our own wake-up: what is drawn has gone stale.
         *
         * Two quite different staleness. On the dial it is the sky — the
         * sun-or-moon glyph and the shading, which the system has no
         * broadcast for and which change hours apart. On the face with no
         * hands it is the time itself, once a minute. One alarm either
         * way, re-armed each time it fires, because a repeating alarm
         * cannot change its own period and this one has to.
         */
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
            val digits = !Face.of(
                PreferenceManager.getDefaultSharedPreferences(context)
            ).hands
            // Below 31 the dial is a static drawable with no sky on it, so
            // there would be nothing to repaint when the alarm went off.
            // The digital widget is a bitmap at every version and always
            // has something to repaint.
            if (Build.VERSION.SDK_INT < 31 && !digits) return
            val manager = alarmManager(context) ?: return
            val at = System.currentTimeMillis() + nextRepaintMs(context)
            manager.set(android.app.AlarmManager.RTC, at, skyIntent(context))
        }

        /**
         * How long the widget may sleep before what it draws is wrong.
         *
         * A minute on the face with no hands, to the boundary rather than
         * a minute from now — a clock that repaints half a second late
         * every time drifts a whole minute behind inside an hour. The
         * alarm is inexact on purpose: an exact one needs a permission a
         * clock widget has no business asking for, and the cost of being a
         * few seconds late is that the minute changes a few seconds late
         * on a screen nobody is looking at, because the launcher is not on
         * top when the phone is idle.
         */
        internal fun nextRepaintMs(context: Context): Long = when (
            Face.of(PreferenceManager.getDefaultSharedPreferences(context))
        ) {
            Face.ANALOG -> nextSkyChangeMs(context)
            // A bitmap of the time is wrong the moment the minute turns.
            // To the boundary and not a minute from now, or a clock that
            // repaints half a second late every time is a minute behind
            // within the hour.
            Face.DIGITAL -> 60_000L - System.currentTimeMillis() % 60_000L
            // A shadow moves fifteen degrees an hour, so a quarter of a
            // degree a minute: ten minutes is two and a half degrees,
            // which is smaller than the shadow's own soft edge. And there
            // is nothing at all to repaint after sunset, so it sleeps
            // until the sky is a different thing.
            Face.SUNDIAL -> {
                DayNight.configure(context)
                val now = java.util.Calendar.getInstance()
                val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    now.get(java.util.Calendar.MINUTE)
                // Not knowing counts as daylight: a widget that goes to
                // sleep for six hours because it has never had a fix is a
                // widget that is wrong all morning.
                if (DayNight.sunIsUp(minute) != false) SHADOW_TICK_MS
                else maxOf(nextSkyChangeMs(context), SHADOW_TICK_MS)
            }
        }

        /** How often the shadow is worth redrawing while the sun is up. */
        private const val SHADOW_TICK_MS = 10 * 60_000L

        /**
         * The longest the widget may sleep while the dial is shaded.
         *
         * The dome follows the light round the sky, and the light moves
         * fifteen degrees an hour. Sleeping until the *sky* next changes is
         * right for the sun-or-moon glyph, which looks the same all
         * afternoon, and quite wrong for a bevel that should have crept
         * round a quarter of a turn by teatime. Half an hour is seven
         * degrees of error, which is less than the width of the gradient's
         * own falloff.
         */
        private const val SHADED_TICK_MS = 30 * 60_000L

        /** How long until the dial would draw something different, in ms. */
        internal fun nextSkyChangeMs(context: Context): Long {
            DayNight.configure(context)
            val shaded = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Prefs.HAND_SHADOWS, false)
            fun capped(ms: Long) = if (shaded) minOf(ms, SHADED_TICK_MS) else ms
            val now = java.util.Calendar.getInstance()
            val minuteNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                now.get(java.util.Calendar.MINUTE)
            val sky = DayNight.sky(minuteNow)
            // Nowhere to stand: the moon's phase is all there is to draw and
            // it moves too slowly to chase. Midnight will catch it.
            if (sky == null) return capped(6 * 60 * 60_000L)
            // Mid-crossing the glyph slides continuously, so look again
            // soon enough that it is never far out of step.
            if (sky is DayNight.Sky.Twilight) return 4 * 60_000L

            // Otherwise sleep until the sky is a different thing, which is
            // hours away in either direction.
            for (ahead in 1..(24 * 60)) {
                val at = (minuteNow + ahead) % 1440
                if (DayNight.sky(at) != sky) return capped(ahead * 60_000L)
            }
            return capped(6 * 60 * 60_000L)
        }

        /**
         * Re-render all widgets after the in-app settings change.
         *
         * And re-book the wake-up, because one of the settings that can
         * have changed is which clock this is — and the two want to be
         * woken hours apart and once a minute. A widget that changed face
         * and kept the dial's schedule sat on the same minute until
         * sunset.
         */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClockWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, manager, id))
            }
            if (ids.isNotEmpty()) scheduleSkyTick(context)
        }


        /** For the tests: the widget as it would be handed to a launcher. */
        internal fun viewsForTest(context: Context, id: Int): RemoteViews =
            buildViews(context, AppWidgetManager.getInstance(context), id)

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
            views.setOnClickPendingIntent(R.id.widget_digital_clock, openApp)

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val face = Face.of(prefs)
            if (!face.hands) {
                // The face with no hands. One of the two children is shown
                // and the other hidden, rather than two providers with two
                // entries in the launcher's list: it is one clock, and
                // nobody should have to find and place a different widget
                // to stop looking at a dial they did not choose.
                views.setViewVisibility(R.id.widget_analog_clock, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_digital_clock, android.view.View.VISIBLE)
                val (w, h) = WidgetRenderer.widgetPixels(context, manager, id)
                val drawn =
                    if (face == Face.SUNDIAL) WidgetRenderer.sundialBitmap(context, w, h)
                    else WidgetRenderer.digitalBitmap(context, w, h)
                views.setImageViewBitmap(
                    R.id.widget_digital_clock,
                    WidgetRenderer.faded(
                        drawn, WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA)
                    )
                )
                return views
            }
            views.setViewVisibility(R.id.widget_analog_clock, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_digital_clock, android.view.View.GONE)

            if (Build.VERSION.SDK_INT >= 31) {
                val theme = ClockThemes.resolve(context, prefs.getString(Prefs.THEME, "midnight"))
                val size = WidgetRenderer.dialPixels(context, manager, id)
                // On polygonal dials the rotating hand bitmaps must fit the
                // inscribed circle, or they'd poke through the flat edges.
                val fit = WidgetRenderer.handFitFraction(context)
                // Everything fades together. A solid hand over a ghost of a
                // face is not a transparent clock, it is a broken one.
                val alpha = WidgetRenderer.alphaOf(context, Prefs.WIDGET_ALPHA)
                fun faded(bitmap: android.graphics.Bitmap) =
                    Icon.createWithBitmap(WidgetRenderer.faded(bitmap, alpha))
                views.setIcon(
                    R.id.widget_analog_clock, "setDial",
                    faded(WidgetRenderer.dialBitmap(context, size))
                )
                views.setIcon(
                    R.id.widget_analog_clock, "setHourHand",
                    faded(WidgetRenderer.handBitmap(size, theme.hourHand, 0.52f * fit, 0.10f, 0.045f))
                )
                views.setIcon(
                    R.id.widget_analog_clock, "setMinuteHand",
                    faded(WidgetRenderer.handBitmap(size, theme.minuteHand, 0.74f * fit, 0.12f, 0.03f))
                )
                val secondHand = if (prefs.getBoolean(Prefs.SECOND_HAND, true)) {
                    WidgetRenderer.handBitmap(size, theme.secondHand, 0.82f * fit, 0.18f, 0.012f)
                } else {
                    WidgetRenderer.emptyBitmap()
                }
                views.setIcon(R.id.widget_analog_clock, "setSecondHand", faded(secondHand))
            }
            return views
        }
    }
}
