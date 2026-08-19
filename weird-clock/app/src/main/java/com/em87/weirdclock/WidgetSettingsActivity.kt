package com.em87.weirdclock

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * How much of the wallpaper shows through the clock widget.
 *
 * One question, so one screen with one slider on it and no menu around it.
 * It is reached from a gear on the widget rather than from the app's
 * settings, because it is a decision you can only make while looking at the
 * home screen — and because a widget configuration screen that only opens
 * when the widget is first dropped is a screen nobody ever sees twice.
 *
 * The widget is repainted as the slider moves. Choosing a number, going
 * back, and finding out you meant a different number is exactly the loop
 * this is meant to spare.
 */
class WidgetSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            setText(R.string.widget_settings_title)
            textSize = 20f
        }
        val reading = TextView(this).apply {
            textSize = 15f
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

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(title)
                addView(reading)
                addView(slider)
            }
        )
        SystemChrome.paint(this)
    }
}
