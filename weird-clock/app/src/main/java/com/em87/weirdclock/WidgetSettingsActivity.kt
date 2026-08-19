package com.em87.weirdclock

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * How much of the wallpaper shows through the clock widget.
 *
 * One question, so one small panel with one slider on it. It is the
 * widget's configuration screen in the Android sense — the launcher opens
 * it from the gear in the popup a long press brings up, beside the bin —
 * which is where every other widget keeps its options and therefore where
 * a thumb goes looking.
 *
 * Two things it has to get right that a screen of the app's own would not
 * have to think about. It must look like something that opened *over* the
 * home screen rather than an app that has been launched, or backing out of
 * it feels like being thrown somewhere. And it must actually go back to
 * the home screen: it ran on the app's own task for a version, so leaving
 * it landed on the clock — the user was on the home screen a moment ago
 * and ends up inside an app they did not open.
 *
 * The widget is repainted as the slider moves, because choosing a number,
 * leaving, and finding out you meant a different number is exactly the
 * loop this exists to spare.
 */
class WidgetSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_WeirdClock_WidgetSettings)
        super.onCreate(savedInstanceState)

        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Said straight away and said again on the way out. A configuration
        // activity that has not answered by the time it closes tells the
        // launcher the widget was never wanted, and the launcher throws it
        // away — so a user who opened this to look and pressed Back would
        // lose the widget.
        setResult(Activity.RESULT_OK, Intent().putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId
        ))

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            setText(R.string.widget_settings_title)
            textSize = 19f
        }
        val reading = TextView(this).apply {
            textSize = 14f
            alpha = 0.7f
        }
        val slider = SeekBar(this).apply {
            max = 100 - WidgetRenderer.MIN_OPACITY_PERCENT
            progress = prefs.getInt(Prefs.WIDGET_ALPHA, 100) -
                WidgetRenderer.MIN_OPACITY_PERCENT
        }

        fun percentOf(progress: Int) = progress + WidgetRenderer.MIN_OPACITY_PERCENT
        fun say(percent: Int) {
            reading.text = getString(R.string.widget_alpha_reading, percent)
        }
        say(percentOf(slider.progress))

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val percent = percentOf(progress)
                say(percent)
                if (!fromUser) return
                prefs.edit().putInt(Prefs.WIDGET_ALPHA, percent).apply()
                ClockWidgetProvider.refreshAll(this@WidgetSettingsActivity)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val done = TextView(this).apply {
            setText(android.R.string.ok)
            textSize = 15f
            setPadding(pad / 2, pad / 2, pad / 2, 0)
            isAllCaps = true
            gravity = android.view.Gravity.END
            setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this@WidgetSettingsActivity, R.color.accent
                )
            )
            setOnClickListener { finish() }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(title)
                addView(reading)
                addView(slider)
                addView(done)
            }
        )
    }
}
