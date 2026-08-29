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

    /**
     * Whether the date is cut under the plate at all.
     *
     * Which calendar it is read in is [reckoning], and the two are
     * separate switches on separate screens — which is exactly how one of
     * them once came to govern the other by accident.
     */
    var showDate: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * Which calendar it is read in — see [Sundial.Reckoning].
     *
     * Three, and none of them offered as better than ours: the Julian is
     * what the stone would have been cut under, and the Egyptian is the
     * calendar that made arithmetic on dates possible at all.
     */
    var reckoning: Sundial.Reckoning = Sundial.Reckoning.GREGORIAN
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

    /**
     * Whether the glass and the thermometer stand beside the dial.
     *
     * A garden dial was rarely on its own: the pedestal carried a weather
     * glass and a thermometer too, because between them the three answered
     * the three questions somebody stepping outside actually had. The
     * arithmetic is [WeatherGlass].
     */
    var instruments: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * What the sky is doing, handed in rather than looked up.
     *
     * The same rule every other view in this app follows — see
     * [ClockView.weather]. Nothing on this face ever touches the network;
     * [WeatherStore] is the one thing that does, and the activity is what
     * asks it.
     */
    var outside: Weather.Sky? = null
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

    /**
     * A second path, for the shapes that are made of two shapes.
     *
     * The thermometer's glass and its liquid are each a tube joined to a
     * bulb, and each has to be one outline rather than two — a rounded
     * rectangle and a circle drawn separately leave the tube's bottom arc
     * running across the bulb. Unioning needs somewhere to put the other
     * half, and allocating a Path per frame is not that.
     */
    private val glassBulb = Path()
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
        // Smaller in the hand than on the table, and for a reason that is
        // not taste. The motto is cut round the rim at 1.06 of the plate
        // and its letters stand out to about 1.15; the compass ring was
        // drawn at 1.10, straight through the middle of the Latin, with
        // the arrow head sitting on a letter. There is no room to put the
        // ring outside the motto at full size — 1.20 of the plate plus the
        // arrow's nose is past the edge of the view — so the plate gives
        // up the difference. Nothing is dropped: outside, pointing a phone
        // at the sun, both are still there.
        val r = min(w, h) * (if (compass) 0.37f else 0.42f)
        val cx = w / 2f
        // The gnomon stands on the noon line, and the whole fan opens away
        // from it — so the dial's centre is not the plate's centre. A
        // horizontal dial's foot sits low and its numerals run up and
        // round; a wall dial hangs the other way up, because the shadow
        // falls downward off a wall.
        val hangs = kind == Sundial.Kind.VERTICAL
        val cy = h / 2f + (if (hangs) -r * 0.30f else r * 0.30f)

        drawPlate(canvas, cx, h / 2f, r)
        // Before the early return below, because a dial that cannot work
        // where you are standing is still a pedestal with two working
        // instruments on it. Somebody on the equator with a flat dial has
        // more use for a thermometer than anybody.
        drawInstruments(canvas, cx, h / 2f, r, h)
        if (Sundial.collapses(kind, latitude)) {
            drawNoDial(canvas, cx, h / 2f, r)
            return
        }
        drawHourLines(canvas, cx, cy, r, hangs)
        drawShadow(canvas, cx, cy, r, sky, hangs)
        drawGnomon(canvas, cx, cy, r, hangs)
        drawEngraving(canvas, cx, h / 2f, r, sky, hangs)
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
        val px = width / 2f
        val py = height / 2f
        for (h in Sundial.hourLines(kind, latitude)) {
            val a = Sundial.lineAngle(kind, latitude, h.toDouble())
            val noon = h == 0
            val reach = edge(cx, cy, a, hangs, px, py, r * RIM)
            line.color = theme.tick
            line.alpha = if (noon) 255 else 190
            line.strokeWidth = r * (if (noon) 0.024f else 0.013f)
            strokeAlong(canvas, cx, cy, a, r * 0.05f, reach, hangs)
            drawHourNumber(canvas, cx, cy, a, h, hangs, reach + r * 0.075f)
        }
        if (!halfHours) return
        // Short marks near the rim rather than a second fan out of the
        // middle: half hours are a subdivision of the scale and belong on
        // the scale, and a full-length line for each was a dial with
        // twice as many hours on it as it has.
        line.color = theme.minorTick
        line.alpha = 160
        line.strokeWidth = r * 0.009f
        val most = Sundial.readableHours(kind, latitude)
        var h = -most + 0.5
        while (h < most) {
            val a = Sundial.lineAngle(kind, latitude, h)
            val to = edge(cx, cy, a, hangs, px, py, r * RIM)
            strokeAlong(canvas, cx, cy, a, to - r * 0.10f, to, hangs)
            h += 1.0
        }
        line.alpha = 255
    }

    /**
     * How far along a line from the gnomon's foot the plate's edge is.
     *
     * The reason this exists rather than a fixed radius: the foot is not
     * the middle of the plate. It sits well off centre — that is what
     * makes the fan open away from it — so a numeral placed at the same
     * distance along every line lands near the rim at six o'clock and in
     * a heap in the middle at noon, which is what it did. Every numeral
     * belongs where its own line reaches the edge, which is where a mason
     * would have cut it.
     *
     * The plate is treated as its inscribed circle even when it is a
     * square: the difference is a few per cent at the corners, and the
     * numerals sit inside the rim in either case.
     */
    private fun edge(
        cx: Float, cy: Float, degrees: Double, hangs: Boolean,
        px: Float, py: Float, radius: Float
    ): Float {
        val a = Math.toRadians(degrees - 90.0 + if (hangs) 180.0 else 0.0)
        val dx = cos(a)
        val dy = sin(a)
        val ox = (cx - px).toDouble()
        val oy = (cy - py).toDouble()
        // |o + t·d| = radius, with d a unit vector, so t is the positive
        // root of t² + 2(o·d)t + (o·o − radius²).
        val b = ox * dx + oy * dy
        val c = ox * ox + oy * oy - radius.toDouble() * radius
        val disc = b * b - c
        if (disc <= 0.0) return radius
        return (-b + kotlin.math.sqrt(disc)).toFloat()
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
        degrees: Double,
        hoursFromNoon: Int,
        hangs: Boolean,
        at: Float
    ) {
        val hour = ((12 + hoursFromNoon) + 11) % 12 + 1
        val text = if (roman) Roman.of(hour) else "$hour"
        val a = Math.toRadians(degrees - 90.0 + if (hangs) 180.0 else 0.0)
        val r = min(width, height) * 0.42f
        val x = cx + (cos(a) * at).toFloat()
        val y = cy + (sin(a) * at).toFloat()
        // Off the stone: a numeral whose line reaches the edge so close to
        // the corner that its number would sit outside gets no number,
        // which is what a mason would have done.
        if (kotlin.math.hypot(x - width / 2f, y - height / 2f) > r * 0.99f) return
        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = r * 0.11f
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
        // The plate is the clip and the gnomon is what gets drawn, so the
        // two cannot share the scratch path — they did once, and what
        // came out was the plate's own outline stroked in the gnomon's
        // ink, which looks enough like a design to survive a glance.
        canvas.save()
        platePath(width / 2f, height / 2f, r)
        canvas.clipPath(path)

        // Seen from above, which is how the rest of the plate is drawn: a
        // gnomon in plan is a thin wedge lying along the noon line, wide
        // at the foot and coming to a point where the style ends. Drawn
        // in elevation instead — a right triangle standing up off the
        // plate — it covered a quarter of the dial and pointed at three
        // o'clock, which is a picture of a sundial photographed from the
        // side laid over a plan of one.
        val a = Math.toRadians(-90.0 + if (hangs) 180.0 else 0.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        val nx = -dy
        val ny = dx
        val long = r * 0.52f
        val wide = r * 0.055f
        gnomon.reset()
        gnomon.moveTo(cx + nx * wide, cy + ny * wide)
        gnomon.lineTo(cx + dx * long, cy + dy * long)
        gnomon.lineTo(cx - nx * wide, cy - ny * wide)
        gnomon.close()
        fill.color = theme.rim
        fill.alpha = 235
        canvas.drawPath(gnomon, fill)
        line.color = theme.tick
        line.alpha = 255
        line.strokeWidth = r * 0.010f
        canvas.drawPath(gnomon, line)
        fill.alpha = 255
        canvas.restore()
        // The foot itself, which is the point every hour line runs from
        // and the one place on the plate a reader has to be able to find.
        fill.color = theme.tick
        canvas.drawCircle(cx, cy, r * 0.020f, fill)
    }

    private val gnomon = Path()

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
    private fun drawEngraving(
        canvas: Canvas, cx: Float, cy: Float, r: Float, sky: Sky, hangs: Boolean
    ) {
        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.color = theme.numeral
        ink.alpha = 150
        ink.textSize = r * 0.085f
        // Opposite the fan. The fan opens downward on a wall and upward on
        // the ground, so a latitude cut at a fixed place under the middle
        // sat on top of the wall dial's noon numeral.
        canvas.drawText(latitudeLabel(), cx, cy + (if (hangs) -r * 0.60f else r * 0.66f), ink)
        ink.alpha = 255
        // Before the motto and not after it. The early return below stood
        // in front of this for as long as both rows have existed, so a
        // dial with the Latin switched off lost its date as well — two
        // rows on two different screens, one silently governing the other,
        // and the only clue that anything had happened was the Julian
        // calendar row still working on a date nobody could see.
        if (showDate) {
            ink.alpha = 190
            ink.textSize = r * 0.10f
            canvas.drawText(
                dateLabel(), cx, cy + (if (hangs) -r * 0.60f else r * 0.66f) +
                    (if (hangs) -r * 0.14f else r * 0.14f), ink
            )
            ink.alpha = 255
        }
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
     * The date, in Roman numerals and in whichever calendar the plate is
     * being read under.
     *
     * Day and month only. A sundial that carried the year would be a dial
     * that had to be recut every January, and no dial anywhere has ever
     * had one on it.
     */
    private fun dateLabel(): String {
        val ms = nowMs()
        return Sundial.dateLabel(
            ms,
            java.util.TimeZone.getDefault().getOffset(ms),
            reckoning,
            roman,
            resources.getStringArray(R.array.egyptian_seasons).toList(),
            context.getString(R.string.egyptian_epagomenal)
        )
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

    // ----------------------------------------------------- the pedestal

    /**
     * The weather glass and the thermometer, standing under the plate.
     *
     * Under it and not on it. Everything already cut into the plate is
     * part of the instrument — the hour lines are a projection, the
     * latitude is what the projection was made for — and a needle sitting
     * among them would read as one more thing the sun does. On the
     * pedestal below, they read as what they are: two other instruments,
     * bolted to the same post, answering the two other questions somebody
     * stepping outside has.
     *
     * Below on a wall dial as well, where the fan hangs the other way
     * up — the same two instruments in the same place, because the
     * alternative was above the plate and above the plate is where the
     * motto is cut.
     *
     * The arithmetic — where the needle points, how full the tube is, and
     * which of the five words is engraved under the needle — is
     * [WeatherGlass], so all of it can be checked without a screen.
     */
    private fun drawInstruments(
        canvas: Canvas, cx: Float, cy: Float, r: Float, h: Float
    ) {
        if (!instruments) return
        val sky = outside ?: return
        if (!WeatherGlass.readable(sky)) return
        // What is left under the plate. A view no taller than it is wide
        // has none, and two instruments squeezed into nothing is worse
        // than two instruments nobody asked for.
        val room = h - (cy + r)
        if (room < r * 0.42f) return
        val box = min(r * 0.62f, room * 0.78f)
        val top = cy + r + (room - box) * 0.40f
        val bottom = top + box
        val alpha = WeatherGlass.ink(sky)
        drawGlass(canvas, cx - r * 0.46f, top, bottom, box, sky, alpha)
        drawThermometer(canvas, cx + r * 0.46f, top, bottom, box, sky, alpha)
    }

    /**
     * The glass: an arc, five marks, and a needle over them.
     *
     * The five words are what was engraved on English aneroids from about
     * 1850 and they are a rule of thumb about pressure over the British
     * Isles — wrong at altitude, wrong in the tropics, half right
     * anywhere. They are here because they were there. The honest part is
     * the needle and the number under the pivot.
     */
    private fun drawGlass(
        canvas: Canvas, x: Float, top: Float, bottom: Float, box: Float,
        sky: Weather.Sky, alpha: Int
    ) {
        val hPa = sky.pressureHpa.value ?: return
        val pivotY = bottom - box * 0.30f
        val radius = box * 0.40f
        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.color = theme.numeral
        // The word above the arc, where a maker's name went, and not
        // inside it: inside is where the needle sweeps, and the first
        // version put the pressure there and drew both of them through
        // each other.
        ink.alpha = (alpha * 0.85f).toInt()
        ink.textSize = box * 0.155f
        canvas.drawText(
            resources.getStringArray(R.array.barometer_legend)[WeatherGlass.legend(hPa)],
            x, top + box * 0.16f, ink
        )
        line.color = theme.numeral
        line.alpha = alpha
        line.strokeWidth = box * 0.026f
        rim.set(x - radius, pivotY - radius, x + radius, pivotY + radius)
        canvas.drawArc(rim, 180f, 180f, false, line)
        // The five marks the words are engraved at, cut through the arc
        // the way they are on a real face.
        line.strokeWidth = box * 0.020f
        for (mark in WeatherGlass.MARKS) {
            val at = Math.toRadians(180.0 + 180.0 * WeatherGlass.swing(mark))
            val inner = radius * 0.80f
            canvas.drawLine(
                x + (cos(at) * inner).toFloat(), pivotY + (sin(at) * inner).toFloat(),
                x + (cos(at) * radius).toFloat(), pivotY + (sin(at) * radius).toFloat(),
                line
            )
        }
        val at = Math.toRadians(180.0 + 180.0 * WeatherGlass.swing(hPa))
        line.strokeWidth = box * 0.034f
        canvas.drawLine(
            x, pivotY,
            x + (cos(at) * radius * 0.86f).toFloat(),
            pivotY + (sin(at) * radius * 0.86f).toFloat(),
            line
        )
        fill.color = theme.numeral
        fill.alpha = alpha
        canvas.drawCircle(x, pivotY, box * 0.048f, fill)
        ink.alpha = alpha
        ink.textSize = box * 0.20f
        canvas.drawText(
            context.getString(R.string.barometer_hpa, Math.round(hPa)), x, bottom, ink
        )
        ink.alpha = 255
        line.alpha = 255
        fill.alpha = 255
    }

    /**
     * The thermometer: a tube, a bulb, and a column standing in it.
     *
     * Drawn as the object rather than as a second needle. Two round gauges
     * side by side are two things nobody can tell apart at a glance, and a
     * column of liquid is legible before it is read — you can see it is
     * cold from across the room, which is the whole use of a thermometer
     * on a wall.
     *
     * Its reading sits on the same line as the glass's, which is what
     * makes the two of them read as one pedestal rather than as two
     * drawings that happen to be next to each other.
     */
    private fun drawThermometer(
        canvas: Canvas, x: Float, top: Float, bottom: Float, box: Float,
        sky: Weather.Sky, alpha: Int
    ) {
        val celsius = sky.temperatureC.value ?: return
        val bulb = box * 0.115f
        val bulbY = bottom - box * 0.30f
        val tubeTop = top + box * 0.14f
        val wide = box * 0.050f
        line.color = theme.numeral
        line.alpha = alpha
        val glass = box * 0.022f
        line.strokeWidth = glass

        // The outline is one shape, and that is the whole of this fix.
        //
        // Drawn as a rounded rectangle and then a circle, the two outlines
        // cross: the tube's bottom arc runs across the top of the bulb, so
        // the join has a line through it that no thermometer has. And the
        // liquid was a disc smaller than the outline, which left a ring of
        // background between the two and made the bulb read as a washer.
        // Unioned first and stroked once, there is nothing to cross.
        path.reset()
        rim.set(x - wide, tubeTop, x + wide, bulbY)
        path.addRoundRect(rim, wide, wide, Path.Direction.CW)
        glassBulb.reset()
        glassBulb.addCircle(x, bulbY, bulb, Path.Direction.CW)
        path.op(glassBulb, Path.Op.UNION)
        canvas.drawPath(path, line)

        // The scale runs from the top of the bulb to the top of the tube,
        // so nought degrees is where the liquid actually starts and the
        // lowest mark sits on the bulb's shoulder instead of across it.
        val zero = bulbY - bulb
        val height = zero - tubeTop
        line.strokeWidth = box * 0.015f
        for (tick in WeatherGlass.ticks()) {
            val y = zero - height * tick
            canvas.drawLine(x - wide * 2.6f, y, x - wide * 1.25f, y, line)
        }

        // And the liquid is the bulb and the column as one shape too,
        // inset by half the outline so it sits inside the glass rather
        // than under it. The bulb is always full: a bulb with nothing in
        // it is a broken thermometer, not a cold one.
        fill.color = theme.numeral
        fill.alpha = alpha
        // Half the outline, not all of it. A whole stroke width left a
        // ring of background three pixels wide between the mercury and
        // the glass — invisible on a phone and not invisible to the test
        // that counts marks across the bulb, which is the point of having
        // one: a stroke is centred on its path, so only half of it is
        // inside the shape.
        val inset = glass * 0.5f
        val stands = zero - height * WeatherGlass.column(celsius)
        path.reset()
        rim.set(x - wide * 0.42f, stands, x + wide * 0.42f, bulbY)
        path.addRoundRect(rim, wide * 0.42f, wide * 0.42f, Path.Direction.CW)
        glassBulb.reset()
        glassBulb.addCircle(x, bulbY, bulb - inset, Path.Direction.CW)
        path.op(glassBulb, Path.Op.UNION)
        canvas.drawPath(path, fill)

        ink.typeface = CUT
        ink.textAlign = Paint.Align.CENTER
        ink.color = theme.numeral
        ink.alpha = alpha
        ink.textSize = box * 0.20f
        canvas.drawText(
            context.getString(R.string.thermometer_degrees, Math.round(celsius)), x, bottom, ink
        )
        ink.alpha = 255
        line.alpha = 255
        fill.alpha = 255
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
        // Outside the motto, which reaches about 1.15 — see the plate's
        // own radius in onDraw, which shrinks to leave room for this.
        val ring = r * 1.20f
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

        /**
         * How far out the hour lines run, as a share of the plate.
         *
         * Short of the rim, because the rim is where the motto is cut and
         * a line running into an inscription is a line somebody has to
         * read past.
         */
        const val RIM = 0.80f
    }
}
