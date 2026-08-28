package com.em87.weirdclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A sundial, drawn from where the sun actually is.
 *
 * Nothing on this face is a decoration on a clock. The hour lines are a
 * projection that depends on the latitude you are standing at; the shadow
 * falls where the shadow falls; and after sunset there is no shadow and
 * therefore no time, which is the one thing about this instrument that
 * nobody would put in on purpose and that the whole face is here for.
 *
 * The arithmetic is [Sundial] and the sun is [SolarTime]. What is here is
 * the plate, the gnomon standing on it, the shadow and the furniture — the
 * numerals, the latitude cut into the rim and the motto, because a sundial
 * without a motto is a piece of garden equipment.
 *
 * Two ways to use it, and they are the two ways anybody uses a real one.
 * Standing still, the dial assumes it is laid out properly — noon towards
 * the pole — and just tells the time. In [compass] mode it is in your
 * hand: an arrow round the rim points at where the sun really is, goes
 * green when the phone is lined up with it, and the shadow is only honest
 * while it is green.
 */
class SundialView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    /** Which of the three instruments this is. */
    var kind: Sundial.Kind = Sundial.Kind.HORIZONTAL
        set(value) { field = value; invalidate() }

    /** And the shape of its plate, which is only ever cosmetic. */
    var plate: Sundial.Plate = Sundial.Plate.ROUND
        set(value) { field = value; invalidate() }

    /**
     * Where you are standing.
     *
     * The one number a sundial cannot do without: it is the angle the
     * style stands at and it is inside every hour line. Defaulted rather
     * than left at nought, because nought is the equator and a horizontal
     * dial on the equator is a dial with no hours on it — a first run with
     * no fix yet would have opened on a broken instrument.
     */
    var latitude: Double = 40.0
        set(value) { field = value.coerceIn(-89.0, 89.0); invalidate() }

    var longitude: Double = 0.0
        set(value) { field = value; invalidate() }

    /** Whether the hours are cut in Roman numerals, as they always were. */
    var roman: Boolean = true
        set(value) { field = value; invalidate() }

    /** Whether the Latin motto is cut round the rim. */
    var motto: Boolean = true
        set(value) { field = value; invalidate() }

    /** Whether the half hours are marked between the hour lines. */
    var halfHours: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * Whether the dial is in your hand rather than on a table.
     *
     * With it off, the dial takes it on trust that somebody has laid it
     * out properly and simply tells the time. With it on, the phone's own
     * compass is asked which way it is pointing and the dial says whether
     * that is any good — which is the honest version, and the one that
     * makes this a thing you go outside with.
     */
    var compass: Boolean = false
        set(value) { field = value; invalidate() }

    /** Which way the top of the phone is pointing, when anything knows. */
    var phoneBearing: Double? = null
        set(value) { field = value; invalidate() }

    /** For the tests and the widget: pretend it is this instant. */
    internal var atMs: Long? = null
        set(value) { field = value; invalidate() }

    private fun nowMs(): Long = atMs ?: TimeKeeper.nowMs()

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rim = RectF()
    private val CUT: Typeface = Typeface.create("serif", Typeface.BOLD)

    /**
     * What the dial knows this instant, worked out once.
     *
     * Every part of the drawing wants some of this and two of them want
     * the same thing, so it is asked once per frame rather than four
     * times — and, more to the point, so the shadow and the hour lines can
     * never be drawn from two different ideas of what time it is.
     */
    private class Sky(
        val hoursFromNoon: Double,
        val altitude: Double,
        val azimuth: Double,
        val declination: Double,
        val dayOfYear: Int
    )

    private fun sky(): Sky {
        val ms = nowMs()
        val at = SolarTime.position(latitude, longitude, ms)
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return Sky(
            hoursFromNoon = SolarTime.hourAngleDeg(longitude, ms) / Sundial.DEGREES_PER_HOUR,
            altitude = at.altitudeDeg,
            azimuth = at.azimuthDeg,
            declination = SolarTime.declinationDeg(ms),
            dayOfYear = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(theme.face)

        val sky = sky()
        val r = min(w, h) * 0.42f
        val cx = w / 2f
        // The gnomon stands on the noon line, and the whole fan opens away
        // from it — so the dial's centre is not the plate's centre. A
        // horizontal dial's foot sits low and its numerals run up and
        // round; a wall dial hangs the other way up, because the shadow
        // falls downward off a wall.
        val hangs = kind == Sundial.Kind.VERTICAL
        val cy = h / 2f + (if (hangs) -r * 0.30f else r * 0.30f)

        drawPlate(canvas, cx, h / 2f, r)
        if (Sundial.collapses(kind, latitude)) {
            drawNoDial(canvas, cx, h / 2f, r)
            return
        }
        drawHourLines(canvas, cx, cy, r, hangs)
        drawShadow(canvas, cx, cy, r, sky, hangs)
        drawGnomon(canvas, cx, cy, r, hangs)
        drawEngraving(canvas, cx, h / 2f, r, sky)
        if (compass) drawCompass(canvas, cx, h / 2f, r, sky)
    }

    // ------------------------------------------------------------- plate

    /**
     * The plate itself: round, square or eight-sided.
     *
     * Cosmetic, and the only thing on this face that is. All three turn up
     * on real dials for reasons of stone-cutting rather than astronomy —
     * an octagon is what you get when a square is too plain and a circle
     * too much work.
     */
    private fun drawPlate(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        platePath(cx, cy, r)
        fill.color = plateColour()
        canvas.drawPath(path, fill)
        line.color = theme.rim
        line.strokeWidth = r * 0.035f
        canvas.drawPath(path, line)
    }

    /**
     * The stone, which is the theme's face lifted a little.
     *
     * A dial that is exactly the colour of the page it is on is not an
     * object, it is a drawing on the page — and this face more than any
     * other has to read as a thing standing in the light.
     */
    private fun plateColour(): Int {
        val face = theme.face
        val pale = ClockThemes.isPaleFace(theme)
        fun mix(shift: Int): Int {
            val c = (face shr shift) and 0xFF
            val to = if (pale) 0 else 255
            return (c + (to - c) * 0.10f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun platePath(cx: Float, cy: Float, r: Float) {
        path.reset()
        when (plate) {
            Sundial.Plate.ROUND -> path.addCircle(cx, cy, r, Path.Direction.CW)
            Sundial.Plate.SQUARE -> {
                val s = r * 0.92f
                path.addRect(cx - s, cy - s, cx + s, cy + s, Path.Direction.CW)
            }
            Sundial.Plate.OCTAGON -> {
                for (i in 0 until 8) {
                    // Flat side at the top, which is how a cut stone sits.
                    val a = Math.toRadians(i * 45.0 + 22.5)
                    val x = cx + (r * cos(a)).toFloat()
                    val y = cy + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
    }

    // -------------------------------------------------------- hour lines

    /**
     * The fan, and the numbers at the end of it.
     *
     * Every line runs from the gnomon's foot outward, and where it lands
     * is [Sundial.lineAngle] and nothing else. The noon line is drawn
     * heavier because it is the one line on a real dial that is a
     * different thing — the meridian, cut deeper, often in a different
     * stone.
     */
    private fun drawHourLines(canvas: Canvas, cx: Float, cy: Float, r: Float, hangs: Boolean) {
        val reach = r * 1.6f
        for (h in Sundial.hourLines(kind, latitude)) {
            val a = Sundial.lineAngle(kind, latitude, h.toDouble())
            val noon = h == 0
            line.color = theme.tick
            line.alpha = if (noon) 255 else 190
            line.strokeWidth = r * (if (noon) 0.024f else 0.013f)
            strokeAlong(canvas, cx, cy, a, r * 0.10f, reach, hangs)
            drawHourNumber(canvas, cx, cy, r, a, h, hangs)
        }
        if (!halfHours) return
        line.color = theme.minorTick
        line.alpha = 150
        line.strokeWidth = r * 0.008f
        val most = Sundial.readableHours(kind, latitude)
        var h = -most + 0.5
        while (h < most) {
            if (abs(h % 1.0) > 0.4) {
                val a = Sundial.lineAngle(kind, latitude, h)
                strokeAlong(canvas, cx, cy, a, r * 0.55f, reach, hangs)
            }
            h += 1.0
        }
        line.alpha = 255
    }

    /**
     * A line out of the gnomon's foot at [degrees] from the noon line,
     * clipped to the plate.
     *
     * The clipping is what makes the fan look like it was cut into the
     * stone rather than drawn over it, and it is why the plate is a path
     * and not a rectangle.
     */
    private fun strokeAlong(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        degrees: Double,
        from: Float,
        to: Float,
        hangs: Boolean
    ) {
        // Noon points up the plate on a dial you look down at, and down
        // one you look up at: a wall dial's shadow falls off the bottom of
        // its gnomon.
        val a = Math.toRadians(degrees - 90.0 + if (hangs) 180.0 else 0.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        canvas.save()
        platePath(width / 2f, height / 2f, min(width, height) * 0.42f)
        canvas.clipPath(path)
        canvas.drawLine(cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to, line)
        canvas.restore()
    }

    /** The hour, in Roman if this face is being itself. */
    private fun drawHourNumber(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        degrees: Double,
        hoursFromNoon: Int,
        hangs: Boolean
    ) {
        val hour = ((12 + hoursFromNoon) + 11) % 12 + 1
        val text = if (roman) Roman.of(hour) else "$hour"
        val a = Math.toRadians(degrees - 90.0 + if (hangs) 180.0 else 0.0)
        // Just inside the rim, on the line, where a dial cuts them.
        val at = r * 0.80f
        val x = cx + (cos(a) * at).toFloat()
        val y = cy + (sin(a) * at).toFloat()
        // Off the plate: a line so bunched against the noon line that its
        // number would sit outside the stone gets no number, which is what
        // a mason would have done.
        if (kotlin.math.hypot(x - width / 2f, y - height / 2f) > r * 0.94f) return
        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = r * 0.13f
        ink.color = theme.numeral
        canvas.drawText(text, x, y + ink.textSize * 0.36f, ink)
    }

    // ------------------------------------------------------- the shadow

    /**
     * The shadow of the style, which is the only moving part.
     *
     * A wedge rather than a line, and it spreads as it goes: a real
     * shadow's edge is soft because the sun is half a degree wide, and the
     * further from the thing casting it the softer it gets. Drawn in three
     * passes for that, the same way the hands' shadows are — see
     * [HandShadow], and for the same reason, which is that a mask filter
     * is one call and is among the things a hardware canvas declines.
     */
    private fun drawShadow(
        canvas: Canvas, cx: Float, cy: Float, r: Float, sky: Sky, hangs: Boolean
    ) {
        val angle = Sundial.shadowAngle(
            kind, latitude, sky.hoursFromNoon, sky.altitude, sky.declination
        ) ?: return
        // In compass mode the dial is only honest while it is pointed the
        // right way, so a shadow that goes on being drawn while the phone
        // is facing the wrong way is a clock quietly making something up.
        val lined = !compass || aligned(sky)
        val reach = Sundial.shadowReach(sky.altitude) * r * 1.45f
        val a = Math.toRadians(angle - 90.0 + if (hangs) 180.0 else 0.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val nx = -dy
        val ny = dx
        canvas.save()
        platePath(width / 2f, height / 2f, r)
        canvas.clipPath(path)
        for (pass in 0 until 3) {
            val spread = r * (0.030f + pass * 0.022f)
            fill.color = 0xFF000000.toInt()
            fill.alpha = if (lined) 70 - pass * 18 else 26 - pass * 7
            path.reset()
            path.moveTo(cx + nx * spread * 0.55f, cy + ny * spread * 0.55f)
            path.lineTo(cx + dx * reach + nx * spread, cy + dy * reach + ny * spread)
            path.lineTo(cx + dx * reach - nx * spread, cy + dy * reach - ny * spread)
            path.lineTo(cx - nx * spread * 0.55f, cy - ny * spread * 0.55f)
            path.close()
            canvas.drawPath(path, fill)
        }
        fill.alpha = 255
        canvas.restore()
    }

    /** Whether the phone is pointed close enough to the sun to be read. */
    private fun aligned(sky: Sky): Boolean {
        val bearing = phoneBearing ?: return false
        return abs(Sundial.offBy(bearing, sky.azimuth)) <= Sundial.ALIGNED_DEGREES
    }

    // -------------------------------------------------------- the gnomon

    /**
     * The gnomon: a triangle standing on the noon line, its upper edge —
     * the style — at the latitude.
     *
     * Drawn flat rather than in perspective on purpose. A perspective
     * gnomon is a picture of a sundial; a flat one, standing on the line
     * it belongs to at the angle it belongs at, is a diagram of one, and
     * a diagram is what you can actually read a shadow off.
     */
    private fun drawGnomon(canvas: Canvas, cx: Float, cy: Float, r: Float, hangs: Boolean) {
        val rise = Math.toRadians(Sundial.styleAngle(kind, latitude))
        val base = r * 0.62f
        val up = if (hangs) 1f else -1f
        path.reset()
        path.moveTo(cx, cy)
        path.lineTo(cx, cy + up * base)
        path.lineTo(cx + (base / kotlin.math.tan(rise).coerceAtLeast(0.05)).toFloat(), cy + up * base)
        path.close()
        fill.color = theme.rim
        fill.alpha = 235
        canvas.save()
        platePath(width / 2f, height / 2f, r)
        canvas.clipPath(path.let { path })
        canvas.restore()
        canvas.drawPath(path, fill)
        line.color = theme.tick
        line.alpha = 255
        line.strokeWidth = r * 0.014f
        canvas.drawPath(path, line)
        fill.alpha = 255
    }

    // ---------------------------------------------------- the engraving

    /**
     * What is cut into the stone: the latitude, and the motto.
     *
     * Both are what a real dial has and neither tells the time. That is
     * the point of them — somebody who chooses a clock that stops working
     * when a cloud goes over is not choosing it to find out what time it
     * is, and the parts that say so are as much the instrument as the
     * hour lines.
     */
    private fun drawEngraving(canvas: Canvas, cx: Float, cy: Float, r: Float, sky: Sky) {
        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.color = theme.numeral
        ink.alpha = 150
        ink.textSize = r * 0.085f
        canvas.drawText(latitudeLabel(), cx, cy + r * 0.66f, ink)
        ink.alpha = 255
        if (!motto) return
        // Round the rim, the way it is cut. Upright text under a dial is a
        // caption; text following the edge is an inscription.
        rim.set(cx - r * 1.06f, cy - r * 1.06f, cx + r * 1.06f, cy + r * 1.06f)
        path.reset()
        path.addArc(rim, 130f, 280f)
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = r * 0.088f
        ink.color = theme.minorTick
        canvas.drawTextOnPath(Sundial.motto(sky.dayOfYear), path, 0f, 0f, ink)
    }

    /**
     * The latitude, cut the way a mason cuts it: degrees, minutes and
     * which side of the equator. Never a decimal — nobody has ever put a
     * decimal point on a sundial.
     */
    private fun latitudeLabel(): String {
        val south = latitude < 0
        val total = abs(latitude)
        val degrees = total.toInt()
        val minutes = ((total - degrees) * 60.0).toInt()
        return "$degrees° $minutes′ ${if (south) "S" else "N"}"
    }

    /** A dial that cannot work where you are standing, saying so. */
    private fun drawNoDial(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.color = theme.numeral
        ink.alpha = 190
        ink.textSize = r * 0.11f
        canvas.drawText(
            context.getString(R.string.sundial_flat_here), cx, cy - r * 0.05f, ink
        )
        ink.alpha = 130
        ink.textSize = r * 0.085f
        canvas.drawText(latitudeLabel(), cx, cy + r * 0.16f, ink)
        ink.alpha = 255
    }

    // --------------------------------------------------------- compass

    /**
     * The arrow that says which way to turn, and goes green when you have.
     *
     * The one piece of this face that is not an instrument: a real dial
     * is aligned once, with a compass, by somebody who then leaves it
     * where it is. A phone is picked up and put down again, so the dial
     * has to be able to say "not like that" — and going green is what
     * turns pointing a phone at the sun into something worth doing.
     */
    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, r: Float, sky: Sky) {
        if (sky.altitude <= 0.0) return
        val bearing = phoneBearing
        val off = if (bearing == null) null else Sundial.offBy(bearing, sky.azimuth)
        val good = off != null && abs(off) <= Sundial.ALIGNED_DEGREES
        val at = Math.toRadians((off ?: 0.0) - 90.0)
        val ring = r * 1.10f
        line.color = if (good) GREEN else theme.minorTick
        line.alpha = if (bearing == null) 90 else 255
        line.strokeWidth = r * 0.02f
        rim.set(cx - ring, cy - ring, cx + ring, cy + ring)
        canvas.drawArc(rim, 0f, 360f, false, line)
        // The head, sitting on the ring where the sun is.
        val hx = cx + (cos(at) * ring).toFloat()
        val hy = cy + (sin(at) * ring).toFloat()
        fill.color = if (good) GREEN else theme.secondHand
        fill.alpha = if (bearing == null) 110 else 255
        val nose = r * 0.09f
        path.reset()
        path.moveTo(hx + (cos(at) * nose).toFloat(), hy + (sin(at) * nose).toFloat())
        val left = at + Math.PI * 0.62
        val right = at - Math.PI * 0.62
        path.lineTo(hx + (cos(left) * nose).toFloat(), hy + (sin(left) * nose).toFloat())
        path.lineTo(hx + (cos(right) * nose).toFloat(), hy + (sin(right) * nose).toFloat())
        path.close()
        canvas.drawPath(path, fill)
        fill.alpha = 255
        line.alpha = 255
    }

    private companion object {
        /** The one colour on this face that is not the theme's: "yes". */
        const val GREEN = 0xFF43C463.toInt()
    }
}
