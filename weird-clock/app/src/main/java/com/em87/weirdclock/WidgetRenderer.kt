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

    /** Sides and top-symmetric vertex offset per dial-shape preference. */
    private fun shapeSpec(prefs: android.content.SharedPreferences): Pair<Int, Float> =
        when (prefs.getString(Prefs.DIAL_SHAPE, Prefs.SHAPE_CIRCLE)) {
            Prefs.SHAPE_TRIANGLE -> 3 to 0f
            Prefs.SHAPE_SQUARE -> 4 to 45f
            Prefs.SHAPE_HEXAGON -> 6 to 0f
            Prefs.SHAPE_OCTAGON -> 8 to 22.5f
            else -> 0 to 0f
        }

    /**
     * How much of the dial radius the hands may safely use: on a polygonal
     * widget face the hands must fit the inscribed circle, since the system
     * rotates a fixed hand bitmap.
     */
    fun handFitFraction(context: Context): Float {
        val (sides, _) = shapeSpec(PreferenceManager.getDefaultSharedPreferences(context))
        val polygon = if (sides < 3) 1f else cos(Math.PI / sides).toFloat()
        // Hand bitmaps are laid out against the full bitmap; scale them to
        // the dial's own inset radius so they end where the ticks do.
        return polygon * (DIAL_FRACTION / 0.94f)
    }

    /** How much of the bitmap the dial itself occupies. */
    private const val DIAL_FRACTION = 0.90f

    fun dialBitmap(context: Context, sizePx: Int): Bitmap {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val theme = ClockThemes.resolve(context, prefs.getString(Prefs.THEME, "midnight"))
        val preset = prefs.getString(Prefs.HOURS_PRESET, "12") ?: "12"
        val hoursOnDial = (
            if (preset == Prefs.HOURS_CUSTOM_VALUE) prefs.getInt(Prefs.HOURS_CUSTOM, 12)
            else preset.toIntOrNull() ?: 12
            ).coerceIn(2, 24)
        val numeralStyle = prefs.getString(Prefs.NUMERALS, Prefs.NUMERALS_ARABIC)
        val showDate = prefs.getBoolean(Prefs.SHOW_DATE, false)
        val dateFormat = prefs.getString(Prefs.DATE_FORMAT, Prefs.DATE_FORMAT_NUMBER)
        val (sides, vertexOffsetDeg) = shapeSpec(prefs)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = sizePx / 2f
        // Inset a little, to leave the ring of bitmap the alarm dots need.
        val r = sizePx / 2f * DIAL_FRACTION

        // Same boundary math as the in-app dial: the polygon's edge distance
        // varies with angle, and everything on the rim follows it.
        fun boundary(angleDeg: Float): Float {
            if (sides < 3) return r
            val half = 180f / sides
            var psi = (angleDeg - vertexOffsetDeg) % (2f * half)
            if (psi < 0f) psi += 2f * half
            val apothem = cos(Math.toRadians(half.toDouble())).toFloat()
            return r * apothem / cos(Math.toRadians((psi - half).toDouble())).toFloat()
        }
        val apothemR = if (sides < 3) r else r * cos(Math.PI / sides).toFloat()

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Semi-transparent face so the wallpaper shows through.
            color = theme.face and 0x00FFFFFF or (0xA6 shl 24)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        stroke.color = theme.rim
        stroke.strokeWidth = r * 0.02f
        if (sides < 3) {
            canvas.drawCircle(c, c, r, fill)
            canvas.drawCircle(c, c, r, stroke)
        } else {
            val path = android.graphics.Path()
            for (k in 0 until sides) {
                val a = Math.toRadians((vertexOffsetDeg + k * 360.0 / sides))
                val px = c + sin(a).toFloat() * r
                val py = c - cos(a).toFloat() * r
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }

        for (i in 0 until 60) {
            val deg = i / 60f * 360f
            val b = boundary(deg)
            val angle = Math.toRadians(deg.toDouble())
            val isMajor = hoursOnDial == 12 && i % 5 == 0
            stroke.color = if (isMajor) theme.tick else theme.minorTick
            stroke.strokeWidth = if (isMajor) r * 0.018f else r * 0.008f
            val outerLen = if (isMajor) r * 0.08f else r * 0.045f
            val sx = sin(angle).toFloat()
            val cyy = cos(angle).toFloat()
            canvas.drawLine(
                c + sx * (b * 0.97f - outerLen), c - cyy * (b * 0.97f - outerLen),
                c + sx * b * 0.97f, c - cyy * b * 0.97f,
                stroke
            )
        }
        if (hoursOnDial != 12) {
            stroke.color = theme.tick
            stroke.strokeWidth = r * 0.018f
            for (i in 0 until hoursOnDial) {
                val deg = i.toFloat() / hoursOnDial * 360f
                val b = boundary(deg)
                val angle = Math.toRadians(deg.toDouble())
                val sx = sin(angle).toFloat()
                val cyy = cos(angle).toFloat()
                canvas.drawLine(
                    c + sx * b * 0.80f, c - cyy * b * 0.80f,
                    c + sx * b * 0.87f, c - cyy * b * 0.87f,
                    stroke
                )
            }
        }

        if (numeralStyle != Prefs.NUMERALS_NONE) {
            val selected = prefs.getStringSet(Prefs.SELECTED_HOURS, emptySet()).orEmpty()
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme.numeral
                textAlign = Paint.Align.CENTER
                textSize = if (hoursOnDial > 12) r * 0.11f else r * 0.16f
            }
            val radiusFactor = if (hoursOnDial == 12) 0.76f else 0.68f
            val step = if (hoursOnDial > 12) 2 else 1
            val hours = ArrayList<Int>()
            var h = step
            while (h <= hoursOnDial) {
                hours.add(h)
                h += step
            }
            if (hoursOnDial % step != 0) hours.add(hoursOnDial)
            for (hour in hours) {
                val deg = hour.toFloat() / hoursOnDial * 360f
                val radius = boundary(deg) * radiusFactor
                val angle = Math.toRadians(deg.toDouble())
                val x = c + sin(angle).toFloat() * radius
                val y = c - cos(angle).toFloat() * radius
                val label = if (numeralStyle == Prefs.NUMERALS_ROMAN) Roman.of(hour) else hour.toString()
                // Hours the user highlighted in the app glow here too.
                text.color = if (selected.contains(hour.toString())) {
                    theme.secondHand
                } else {
                    theme.numeral
                }
                canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text)
            }
        }

        // Enabled alarms as dots just outside the rim, and calendar events
        // as Sectograph wedges — the same language as the in-app dial.
        if (prefs.getBoolean(Prefs.ALARM_MARKERS, true)) {
            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = theme.decimal
                alpha = 230
            }
            val today = Calendar.getInstance()
            val reminders = ReminderStore.load(context).filter {
                it.year == today.get(Calendar.YEAR) &&
                    it.month == today.get(Calendar.MONTH) + 1 &&
                    it.day == today.get(Calendar.DAY_OF_MONTH)
            }
            for ((startDeg, sweepDeg) in reminders.filter { it.durationMinutes > 0 }.map {
                val start = (it.hour + it.minute / 60f) % hoursOnDial / hoursOnDial * 360f
                start to it.durationMinutes / 60f / hoursOnDial * 360f
            }) {
                val path = android.graphics.Path()
                val steps = kotlin.math.max(2, (sweepDeg / 3f).toInt())
                for (i in 0..steps) {
                    val a = Math.toRadians((startDeg + sweepDeg * i / steps).toDouble())
                    val b = boundary((startDeg + sweepDeg * i / steps)) * 0.885f
                    val px = c + sin(a).toFloat() * b
                    val py = c - cos(a).toFloat() * b
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                for (i in steps downTo 0) {
                    val a = Math.toRadians((startDeg + sweepDeg * i / steps).toDouble())
                    val b = boundary((startDeg + sweepDeg * i / steps)) * 0.965f
                    path.lineTo(c + sin(a).toFloat() * b, c - cos(a).toFloat() * b)
                }
                path.close()
                canvas.drawPath(path, markerPaint)
            }
            val dotAngles = AlarmStore.load(context).filter { it.enabled }.map {
                (it.hour + it.minute / 60f) % hoursOnDial / hoursOnDial * 360f
            } + reminders.filter { it.durationMinutes <= 0 }.map {
                (it.hour + it.minute / 60f) % hoursOnDial / hoursOnDial * 360f
            }
            for (deg in dotAngles) {
                val a = Math.toRadians(deg.toDouble())
                val b = boundary(deg) * 1.02f
                canvas.drawCircle(
                    c + sin(a).toFloat() * b, c - cos(a).toFloat() * b,
                    r * 0.022f, markerPaint
                )
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
                c - apothemR * 0.42f - (text.ascent() + text.descent()) / 2f,
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
