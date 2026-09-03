package com.em87.weirdclock

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * How much of the wallpaper shows through a widget.
 *
 * One question, so one small panel with one slider on it. It serves all
 * three widgets — the clock, the solar system and the countdown — and
 * works out which one it was opened for from the id the launcher hands
 * it, because a configuration activity per widget would be three copies
 * of one slider. Each widget keeps its own stored percentage, so the sky
 * can be a ghost beside a solid clock.
 *
 * It is the widget's configuration screen in the Android sense — the
 * launcher opens it from the gear in the popup a long press brings up,
 * beside the bin — which is where every other widget keeps its options
 * and therefore where a thumb goes looking.
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

        // Which widget this was opened for. The launcher gives an id and
        // the manager turns it into a provider; a widget being placed for
        // the first time may not have one yet, and the clock is the
        // sensible thing to assume when nothing else is known.
        val provider = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        val kind = WidgetKind.of(provider)
        val key = kind.alphaKey

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            // The widget's own name, so a panel opened from a home screen
            // holding four of these says which one it is. It said "Widget
            // transparency" on all of them, which was true of the only
            // control there used to be and is not a name.
            text = getString(R.string.widget_settings_for, getString(nameOf(kind)))
            textSize = 19f
        }
        val reading = TextView(this).apply {
            textSize = 14f
            alpha = 0.7f
        }
        val slider = SeekBar(this).apply {
            max = 100 - WidgetRenderer.MIN_OPACITY_PERCENT
            progress = prefs.getInt(key, 100) - WidgetRenderer.MIN_OPACITY_PERCENT
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
                prefs.edit().putInt(key, percent).apply()
                // Only the kind being configured is repainted. All three
                // would work and would be three bitmaps pushed through IPC
                // for every step of a slider.
                repaint(kind)
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
                // The window is floating and translucent, so without this
                // the home screen shows through the rows — see
                // widget_settings_panel.xml.
                setBackgroundResource(R.drawable.widget_settings_panel)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(title)
                addView(reading)
                addView(slider)
                for (extra in extrasFor(kind)) addView(switchFor(extra, kind))
                mechanismRow(kind)?.let { addView(it) }
                addView(done)
            }
        )
    }

    /**
     * One switch on the widget's own panel.
     *
     * Widgets grew options that are about the widget rather than about
     * the clock inside the app — whether the date is worth a third of two
     * cells of somebody's home screen, whether a countdown the size of a
     * stamp is better set in type than in bars — and there was nowhere to
     * put them. They do not belong in the app's settings: they are
     * questions about a thing on the home screen, asked from the home
     * screen, which is exactly what a configuration activity is for.
     */
    private class Extra(val key: String, val title: Int, val summary: Int, val default: Boolean)

    /**
     * Which of them this widget has.
     *
     * By provider and not by face, with one exception that is about the
     * face: the clock widget's date only exists on the face that draws
     * its own bitmap, because the dial's widget is the system's own
     * AnalogClock with a dial painted behind it and has nowhere to put a
     * date that is not already on the dial.
     */
    private fun extrasFor(kind: WidgetKind): List<Extra> {
        val rows = ArrayList<Extra>()
        // Every one of them, because the thing somebody noticed is that
        // some had a background and some did not — see [WidgetKind]. The
        // default is what each of them already looked like.
        rows += Extra(
            kind.pref("ground"),
            R.string.pref_widget_ground_title,
            R.string.pref_widget_ground_summary,
            kind.groundByDefault
        )
        // The seconds, on the two kinds that have any: a hand that sweeps
        // on the dial, a counter beside the digits. Both default to what
        // the app is doing, so nothing changes until somebody says so.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val face = if (kind.pinned != null) kind.pinned else Face.of(prefs)
        if (kind == WidgetKind.DIAL || kind == WidgetKind.DIGITS ||
            kind == WidgetKind.FOLLOWING
        ) {
            rows += if (face != null && !face.hands) {
                Extra(
                    kind.pref("seconds"),
                    R.string.pref_widget_seconds_title,
                    R.string.pref_widget_seconds_summary,
                    false
                )
            } else {
                Extra(
                    kind.pref("seconds"),
                    R.string.pref_widget_second_hand_title,
                    R.string.pref_widget_second_hand_summary,
                    prefs.getBoolean(Prefs.SECOND_HAND, true)
                )
            }
        }
        when (kind) {
            WidgetKind.HOURGLASS -> {
                rows += Extra(
                    Prefs.WIDGET_SAND_PLAIN,
                    R.string.pref_widget_plain_title,
                    R.string.pref_widget_plain_summary,
                    false
                )
                // Its own answer rather than the app's. The hourglass on
                // the home screen used to be sand or a bar depending on
                // which face the app happened to be left on, which is a
                // widget changing shape for a reason nobody can see from
                // the home screen.
                rows += Extra(
                    Prefs.widgetHourglassDigits,
                    R.string.pref_widget_hourglass_digits_title,
                    R.string.pref_widget_hourglass_digits_summary,
                    false
                )
            }
            WidgetKind.SUNDIAL -> rows += Extra(
                Prefs.WIDGET_SUNDIAL_WALL,
                R.string.pref_widget_sundial_wall_title,
                R.string.pref_widget_sundial_wall_summary,
                false
            )
            WidgetKind.DIGITS -> rows += Extra(
                Prefs.WIDGET_DATE,
                R.string.pref_widget_date_title,
                R.string.pref_widget_date_summary,
                false
            )
            // The sun is the only thing standing outside the world, so on
            // a widget its switch is also what lets the world fill the
            // whole of it.
            WidgetKind.GLOBE -> rows += Extra(
                kind.pref("sun"),
                R.string.pref_widget_globe_sun_title,
                R.string.pref_widget_globe_sun_summary,
                true
            )
            // The one that follows the app answers the app's questions,
            // and the date is the one it has of its own — on the face
            // that draws a bitmap, since the dial's widget is the
            // system's own AnalogClock and has nowhere to put one.
            WidgetKind.FOLLOWING ->
                if (face != null && !face.hands) {
                    rows += Extra(
                        Prefs.WIDGET_DATE,
                        R.string.pref_widget_date_title,
                        R.string.pref_widget_date_summary,
                        false
                    )
                }
            else -> Unit
        }
        return rows
    }

    /**
     * The mechanism row, which only the digits have.
     *
     * A list rather than a switch, and the only control here that is not
     * one — so it is built separately rather than bent into [Extra]. Left
     * unset it follows the app, which is what it did before it could be
     * set at all.
     */
    private fun mechanismRow(kind: WidgetKind): View? {
        if (kind != WidgetKind.DIGITS) return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val names = resources.getStringArray(R.array.digit_style_entries)
        val values = resources.getStringArray(R.array.digit_style_values)
        val label = TextView(this).apply {
            setText(R.string.pref_mechanism_title)
            textSize = 16f
        }
        val choices = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@WidgetSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                names
            )
            val chosen = prefs.getString(kind.pref("mechanism"), null)
                ?: DigitStyle.of(prefs).key
            setSelection(values.indexOf(chosen).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    prefs.edit().putString(kind.pref("mechanism"), values[position]).apply()
                    repaint(kind)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
            addView(label)
            addView(choices)
        }
    }

    /** And the row it is drawn as, repainting the widget as it is flipped. */
    private fun switchFor(extra: Extra, kind: WidgetKind): View {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            setText(extra.title)
            textSize = 16f
        }
        val note = TextView(this).apply {
            setText(extra.summary)
            textSize = 13f
            alpha = 0.7f
        }
        val toggle = android.widget.Switch(this).apply {
            isChecked = prefs.getBoolean(extra.key, extra.default)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(extra.key, on).apply()
                repaint(kind)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
            addView(
                LinearLayout(this@WidgetSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label)
                    addView(note)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
            )
            addView(toggle)
        }
    }

    /** Repaints whichever widget this panel was opened for. */
    private fun repaint(kind: WidgetKind) {
        when (kind) {
            WidgetKind.ORRERY -> OrreryWidgetProvider.refreshAll(this)
            WidgetKind.HOURGLASS -> HourglassWidgetProvider.refreshAll(this)
            WidgetKind.WEATHER -> WeatherWidgetProvider.refreshAll(this)
            else -> ClockWidgetProvider.refreshAll(this)
        }
    }

    /** What this kind is called, which is what the launcher calls it. */
    private fun nameOf(kind: WidgetKind): Int = when (kind) {
        WidgetKind.FOLLOWING -> R.string.app_name
        WidgetKind.DIAL -> R.string.widget_dial_label
        WidgetKind.DIGITS -> R.string.widget_digits_label
        WidgetKind.SUNDIAL -> R.string.widget_sundial_label
        WidgetKind.GLOBE -> R.string.widget_globe_label
        WidgetKind.ORRERY -> R.string.widget_orrery_label
        WidgetKind.HOURGLASS -> R.string.widget_hourglass_label
        WidgetKind.WEATHER -> R.string.widget_weather_label
    }
}
