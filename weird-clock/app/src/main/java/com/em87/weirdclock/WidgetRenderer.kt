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

        // The widget draws from its own entry point, so it configures the
        // day/night rule for itself rather than inheriting one. Up here
        // rather than inside the markers block: the sky glyph needs it too,
        // and it is drawn whether or not the marks are switched on.
        DayNight.configure(context)

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
            // Solid. The face used to be baked at two-thirds alpha so the
            // wallpaper showed through, which was fine until there was a
            // slider called opacity: turned to a hundred per cent the
            // widget was still a third transparent, because the two were
            // multiplied and only one of them was the user's. The whole
            // picture is faded once, at the end, by the amount asked for —
            // see [faded].
            color = theme.face or (0xFF shl 24)
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
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }
            // Repeats included, and asked of occursOn rather than compared
            // by hand — the widget's dial and the app's have to agree, and
            // for repeating reminders they did not.
            val today = Calendar.getInstance()
            val ty = today.get(Calendar.YEAR)
            val tm = today.get(Calendar.MONTH) + 1
            val td = today.get(Calendar.DAY_OF_MONTH)
            val reminders = ReminderStore.all(context).filter { it.occursOn(ty, tm, td) }
            val dow = today.get(Calendar.DAY_OF_WEEK)
            // An alarm with a duration is an event too, and reads as a
            // wedge — on this dial as on the app's. The widget was drawing
            // the calendar's wedges and silently dropping the alarms', so a
            // three-hour block set as an alarm was on one face and not the
            // other.
            val alarmWedges = AlarmStore.all(context)
                .filter { it.enabled && it.durationMinutes > 0 && it.ringsOn(dow) }
                .flatMap { alarm ->
                    alarm.allTimes().map { (h, m) ->
                        DialArc(
                            (h + m / 60f) % hoursOnDial / hoursOnDial * 360f,
                            alarm.durationMinutes / 60f / hoursOnDial * 360f,
                            DayNight.isDarkAt(h, m)
                        )
                    }
                }
            val reminderWedges = reminders
                .filter { it.durationMinutes > 0 }
                .map {
                    DialArc(
                        (it.hour + it.minute / 60f) % hoursOnDial / hoursOnDial * 360f,
                        it.durationMinutes / 60f / hoursOnDial * 360f,
                        DayNight.isDarkAt(it.hour, it.minute),
                        fromCalendar = true
                    )
                }
            for ((startDeg, sweepDeg, wedgePm, wedgeDated) in alarmWedges + reminderWedges) {
                markerPaint.color = DayNight.markColor(theme, wedgePm)
                markerPaint.alpha = 230
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
                // The ring that says "today only" belongs to the calendar's
                // wedges and not to the alarms' — the widget's dial and the
                // app's have to say the same thing.
                if (wedgeDated) {
                    ringPaint.color = ClockThemes.contrastInk(theme)
                    ringPaint.strokeWidth = r * 0.008f
                    canvas.drawPath(path, ringPaint)
                }
            }
            // Every time an alarm rings at, not just the first, and none for
            // the ones drawn as wedges — which is what the app's own dial
            // does. The widget was showing one dot for a concept that happens
            // four times a day, and a dot as well as a wedge for the rest.
            val dots = AlarmStore.all(context)
                .filter { it.enabled && it.durationMinutes <= 0 }
                .flatMap { alarm -> alarm.allTimes() }
                .map { (h, m) ->
                    DialMark(
                        (h + m / 60f) % hoursOnDial / hoursOnDial * 360f,
                        DayNight.isDarkAt(h, m)
                    )
                } + reminders.filter { it.durationMinutes <= 0 }.map {
                DialMark(
                    (it.hour + it.minute / 60f) % hoursOnDial / hoursOnDial * 360f,
                    DayNight.isDarkAt(it.hour, it.minute),
                    fromCalendar = true
                )
            }
            for ((deg, dotPm, dotFromCalendar) in dots) {
                markerPaint.color = DayNight.markColor(theme, dotPm)
                markerPaint.alpha = 230
                val a = Math.toRadians(deg.toDouble())
                val b = boundary(deg) * 1.02f
                val dx = c + sin(a).toFloat() * b
                val dy = c - cos(a).toFloat() * b
                canvas.drawCircle(dx, dy, r * 0.022f, markerPaint)
                if (dotFromCalendar) {
                    ringPaint.color = ClockThemes.contrastInk(theme)
                    ringPaint.strokeWidth = r * 0.009f
                    canvas.drawCircle(dx, dy, r * 0.028f, ringPaint)
                }
            }
        }

        // The sky, in the same place and to the same rules as the app's own
        // dial: the widget is the face most people look at, and the whole
        // point of the complication is knowing whether it is light out
        // without going to a window.
        // The dial's own curve, lit from wherever the sun or the moon
        // actually is. The hands cannot have their shadows here — the
        // system rotates a fixed bitmap for each one, so a shadow drawn
        // into it would swing round with the hand instead of staying where
        // the light put it — but the dome does not depend on the time at
        // all, only on where the light is, and the widget already wakes
        // itself when the sky changes. See [DialDome].
        if (prefs.getBoolean(Prefs.HAND_SHADOWS, false)) {
            val lat = if (DayNight.hasFix()) DayNight.latitudeNow() else HandShadow.NO_FIX_LATITUDE
            val lon = if (DayNight.hasFix()) {
                DayNight.longitudeNow()
            } else {
                HandShadow.longitudeFromZone(
                    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
                )
            }
            HandShadow.lightAt(lat, lon, System.currentTimeMillis())?.let { light ->
                DialDome.draw(
                    canvas, c, c, r,
                    HandShadow.domeStrength(light.altitudeDeg) * light.brightness,
                    light.azimuthDeg.toFloat(),
                    tint = if (light.moon) HandShadow.MOON_SHEEN else 0xFFFFFF
                )
            }
        }

        if (prefs.getBoolean(Prefs.MOON_PHASE, false)) {
            val now = Calendar.getInstance()
            val timeOfDay = now.get(Calendar.HOUR_OF_DAY) * 3_600_000L +
                now.get(Calendar.MINUTE) * 60_000L
            SkyGlyph.draw(
                canvas, c, c + apothemR * 0.45f, r * 0.07f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.numeral },
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.minorTick },
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.minorTick
                    style = Paint.Style.STROKE
                    strokeWidth = r * 0.008f
                },
                timeOfDay
            )
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

    /**
     * How opaque the widget is drawn, out of 255, from a percentage.
     *
     * There is a floor, and it is not nought. A widget you can set to
     * invisible is a widget you then cannot find to set back — it is still
     * on the home screen, still taking a square of it, and the only way
     * out is to guess where it was and long-press the wallpaper. So the
     * bottom of the slider is faint rather than absent.
     */
    fun opacity(percent: Int): Int =
        (percent.coerceIn(MIN_OPACITY_PERCENT, 100) * 255 / 100)

    /** The faintest a widget may be made, as a percentage. */
    const val MIN_OPACITY_PERCENT = 15

    /**
     * The same picture, drawn through at [alpha] out of 255.
     *
     * One place rather than a parameter threaded through every bitmap this
     * file makes: the dial, three hands and whatever comes next all fade
     * together or the widget comes apart — a solid hand over a ghost of a
     * face is not a transparent clock, it is a broken one.
     */
    fun faded(source: Bitmap, alpha: Int): Bitmap {
        if (alpha >= 255) return source
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            source, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha.coerceIn(0, 255) }
        )
        return out
    }
}
