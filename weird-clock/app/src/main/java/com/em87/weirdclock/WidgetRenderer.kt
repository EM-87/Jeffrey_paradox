package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders widget dial/hand bitmaps that mirror the in-app clock's current
 * settings (theme, hours on the dial, numeral style, date). Used on API 31+
 * where AnalogClock accepts icons via RemoteViews; older devices keep the
 * static Midnight drawables from the layout.
 */
object WidgetRenderer {

    fun dialBitmap(context: Context, sizePx: Int): Bitmap {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val theme = ClockThemes.byKey(prefs.getString(Prefs.THEME, "midnight"))
        val preset = prefs.getString(Prefs.HOURS_PRESET, "12") ?: "12"
        val hoursOnDial = (
            if (preset == Prefs.HOURS_CUSTOM_VALUE) prefs.getInt(Prefs.HOURS_CUSTOM, 12)
            else preset.toIntOrNull() ?: 12
            ).coerceIn(2, 24)
        val numeralStyle = prefs.getString(Prefs.NUMERALS, Prefs.NUMERALS_ARABIC)
        val showDate = prefs.getBoolean(Prefs.SHOW_DATE, false)
        val dateFormat = prefs.getString(Prefs.DATE_FORMAT, Prefs.DATE_FORMAT_NUMBER)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = sizePx / 2f
        val r = sizePx / 2f * 0.94f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Semi-transparent face so the wallpaper shows through.
            color = theme.face and 0x00FFFFFF or (0xA6 shl 24)
        }
        canvas.drawCircle(c, c, r, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        stroke.color = theme.rim
        stroke.strokeWidth = r * 0.02f
        canvas.drawCircle(c, c, r, stroke)

        for (i in 0 until 60) {
            val angle = Math.toRadians(i / 60.0 * 360.0)
            val isMajor = hoursOnDial == 12 && i % 5 == 0
            stroke.color = if (isMajor) theme.tick else theme.minorTick
            stroke.strokeWidth = if (isMajor) r * 0.018f else r * 0.008f
            val outerLen = if (isMajor) r * 0.08f else r * 0.045f
            val sx = sin(angle).toFloat()
            val cyy = cos(angle).toFloat()
            canvas.drawLine(
                c + sx * (r * 0.97f - outerLen), c - cyy * (r * 0.97f - outerLen),
                c + sx * r * 0.97f, c - cyy * r * 0.97f,
                stroke
            )
        }
        if (hoursOnDial != 12) {
            stroke.color = theme.tick
            stroke.strokeWidth = r * 0.018f
            for (i in 0 until hoursOnDial) {
                val angle = Math.toRadians(i.toDouble() / hoursOnDial * 360.0)
                val sx = sin(angle).toFloat()
                val cyy = cos(angle).toFloat()
                canvas.drawLine(
                    c + sx * r * 0.80f, c - cyy * r * 0.80f,
                    c + sx * r * 0.87f, c - cyy * r * 0.87f,
                    stroke
                )
            }
        }

        if (numeralStyle != Prefs.NUMERALS_NONE) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.numeral
                textAlign = Paint.Align.CENTER
                textSize = if (hoursOnDial > 12) r * 0.11f else r * 0.16f
            }
            val radius = if (hoursOnDial == 12) r * 0.76f else r * 0.68f
            val step = if (hoursOnDial > 12) 2 else 1
            val hours = ArrayList<Int>()
            var h = step
            while (h <= hoursOnDial) {
                hours.add(h)
                h += step
            }
            if (hoursOnDial % step != 0) hours.add(hoursOnDial)
            for (hour in hours) {
                val angle = Math.toRadians(hour.toDouble() / hoursOnDial * 360.0)
                val x = c + sin(angle).toFloat() * radius
                val y = c - cos(angle).toFloat() * radius
                val label = if (numeralStyle == Prefs.NUMERALS_ROMAN) Roman.of(hour) else hour.toString()
                canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text)
            }
        }

        if (showDate) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.numeral
                alpha = 210
                textAlign = Paint.Align.CENTER
                textSize = r * 0.085f
            }
            val now = Calendar.getInstance()
            val label = when (dateFormat) {
                Prefs.DATE_FORMAT_TEXT ->
                    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date())
                Prefs.DATE_FORMAT_ROMAN ->
                    Roman.of(now.get(Calendar.DAY_OF_MONTH)) + "·" +
                        Roman.of(now.get(Calendar.MONTH) + 1) + "·" +
                        Roman.of(now.get(Calendar.YEAR))
                else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            }
            canvas.drawText(
                label, c,
                c - r * 0.42f - (text.ascent() + text.descent()) / 2f,
                text
            )
        }

        return bitmap
    }

    fun handBitmap(sizePx: Int, color: Int, lengthFrac: Float, tailFrac: Float, widthFrac: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = sizePx / 2f
        val r = sizePx / 2f * 0.94f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.color = color
            strokeWidth = widthFrac * r * 2f
        }
        canvas.drawLine(c, c + tailFrac * r, c, c - lengthFrac * r, paint)
        return bitmap
    }

    /** Fully transparent bitmap, used to blank out the widget second hand. */
    fun emptyBitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
    }
}
