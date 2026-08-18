package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The solar system, drawn on the dial.
 *
 * [Orrery] knows where everything is; this knows where everything goes.
 * They are apart because the arithmetic is worth testing without a canvas
 * and the drawing is worth looking at without arithmetic — and because the
 * one thing both halves need to agree about, which body a finger has landed
 * on, is answered here from the same geometry that drew it. A hit test
 * written separately from the drawing is a hit test that will one day point
 * at the wrong planet.
 *
 * Angles go the way the sky goes: longitude zero to the right, and round
 * anticlockwise. The hands on this dial go the other way, which is the
 * point — nothing here is a clock hand, and it should not read as one.
 */
object OrreryDial {

    /**
     * The planets' own colours, which do not follow the theme.
     *
     * Everything else on this dial is themed, and these are not, for the
     * same reason the mark colours are not: they are not decoration, they
     * are how you tell Mars from Mercury without a label. Rust, straw,
     * blue, pale green — a person who has ever seen a diagram of the solar
     * system already knows this alphabet.
     */
    private val colours = mapOf(
        Orrery.Body.MERCURY to 0xFF9E9689.toInt(),
        Orrery.Body.VENUS to 0xFFE8C87A.toInt(),
        Orrery.Body.EARTH to 0xFF4E8FD6.toInt(),
        Orrery.Body.MARS to 0xFFC1553A.toInt(),
        Orrery.Body.JUPITER to 0xFFCBA37A.toInt(),
        Orrery.Body.SATURN to 0xFFDCC58C.toInt(),
        Orrery.Body.URANUS to 0xFF8FD3D8.toInt(),
        Orrery.Body.NEPTUNE to 0xFF4C63C4.toInt(),
        Orrery.Body.MOON to 0xFFCFD3DA.toInt()
    )

    private const val SUN = 0xFFFFC93C.toInt()

    /**
     * The same colours, dropped in tone on a pale dial.
     *
     * Half of the planets are naturally light — Venus is straw, Saturn is
     * straw, Uranus is a pale green — and on the white face they came out
     * as faint smudges that had to be hunted for. Darkening them keeps the
     * alphabet (straw is still straw next to Mars's rust) while giving each
     * one an edge against the face it sits on.
     */
    private fun colourOf(body: Orrery.Body, theme: ClockTheme): Int {
        val raw = colours.getValue(body)
        if (!isPale(theme.face)) return raw
        return darkened(raw, 0.42f)
    }

    private fun isPale(colour: Int): Boolean {
        val r = (colour shr 16) and 0xFF
        val g = (colour shr 8) and 0xFF
        val b = colour and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b > 140f
    }

    private fun darkened(colour: Int, by: Float): Int {
        val k = 1f - by
        val r = (((colour shr 16) and 0xFF) * k).toInt()
        val g = (((colour shr 8) and 0xFF) * k).toInt()
        val b = ((colour and 0xFF) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * How big each planet is drawn, against the biggest.
     *
     * Compressed hard — Jupiter is eleven Earths across and drawing it so
     * would leave the Earth a speck nobody could put a finger on. What
     * survives the compression is the order and the grouping: four small
     * rocky ones, two big ones, two middling ones, which is the shape of
     * the thing.
     */
    private val sizes = mapOf(
        Orrery.Body.MERCURY to 0.31f,
        Orrery.Body.VENUS to 0.43f,
        Orrery.Body.EARTH to 0.44f,
        Orrery.Body.MARS to 0.35f,
        Orrery.Body.JUPITER to 1.00f,
        Orrery.Body.SATURN to 0.94f,
        Orrery.Body.URANUS to 0.70f,
        Orrery.Body.NEPTUNE to 0.69f,
        Orrery.Body.MOON to 0.34f
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    // ------------------------------------------------------------ geometry

    /** How far out a body's ring is, in pixels. */
    fun ringRadius(body: Orrery.Body, r: Float): Float = Orrery.ringFraction(body) * r * 0.94f

    /** How big a body is drawn, in pixels. */
    fun dotRadius(body: Orrery.Body, r: Float): Float =
        r * 0.052f * (sizes[body] ?: 0.4f)

    /**
     * Where a body sits on the dial.
     *
     * The Moon is the one that is not simply an angle on a ring: it goes
     * round the Earth, so it is found by finding the Earth first. Its
     * longitude comes in from outside rather than being read from the clock
     * because it is not always on the clock — while another planet is being
     * carried the Moon has let go, and it stays where it was.
     */
    fun positionOf(
        body: Orrery.Body,
        cx: Float,
        cy: Float,
        r: Float,
        atMs: Long,
        moonLongitude: Double
    ): PointF {
        if (body == Orrery.Body.MOON) {
            val earth = positionOf(Orrery.Body.EARTH, cx, cy, r, atMs, moonLongitude)
            val ring = Orrery.MOON_RING * r
            return pointOn(earth.x, earth.y, ring, moonLongitude)
        }
        return pointOn(cx, cy, ringRadius(body, r), Orrery.longitude(body, atMs))
    }

    private fun pointOn(cx: Float, cy: Float, radius: Float, longitudeDeg: Double): PointF {
        val a = Math.toRadians(longitudeDeg)
        return PointF(
            cx + (radius * cos(a)).toFloat(),
            cy - (radius * sin(a)).toFloat()
        )
    }

    /** Which way a touch lies from a centre, as a longitude. */
    fun longitudeOf(cx: Float, cy: Float, x: Float, y: Float): Double =
        Orrery.wrap(Math.toDegrees(kotlin.math.atan2((cy - y).toDouble(), (x - cx).toDouble())))

    /**
     * Which body a finger has landed on, or null.
     *
     * Nearest wins rather than first found, which is what makes the Moon
     * reachable at all: it stands on top of the Earth, and a search that
     * stopped at the first body within reach would answer "Earth" for every
     * touch aimed at the Moon on the near side of its orbit. The order the
     * list is walked in does not matter, and an earlier version of this
     * comment claimed it did.
     */
    fun bodyAt(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        r: Float,
        atMs: Long,
        moonLongitude: Double
    ): Orrery.Body? {
        var best: Orrery.Body? = null
        var bestGap = Float.MAX_VALUE
        for (body in Orrery.planets + Orrery.Body.MOON) {
            val p = positionOf(body, cx, cy, r, atMs, moonLongitude)
            val gap = hypot(x - p.x, y - p.y)
            // Room around the smallest ones: Mercury is four pixels across
            // on a phone, and a target has to be bigger than the thing it
            // is a target for.
            val reach = maxOf(dotRadius(body, r) * 2.4f, r * 0.055f)
            if (gap < reach && gap < bestGap) {
                best = body
                bestGap = gap
            }
        }
        return best
    }

    // ------------------------------------------------------------ drawing

    /**
     * The whole system, at [alpha] — 0 while the hands still have the dial,
     * 1 once they are gone.
     *
     * [moonLongitude] is passed rather than worked out for the reason given
     * on [positionOf]. [detachedMoon] says whether it is currently let go,
     * and it is drawn hollow while it is: a Moon that has stopped obeying
     * the mechanism and does not say so is just a Moon that looks broken.
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        theme: ClockTheme,
        atMs: Long,
        alpha: Float,
        moonLongitude: Double,
        aligned: List<Orrery.Body> = emptyList(),
        detachedMoon: Boolean = false,
        grabbed: Orrery.Body? = null
    ) {
        if (alpha <= 0.01f) return
        val a = alpha.coerceIn(0f, 1f)

        drawRings(canvas, cx, cy, r, theme, a)
        if (aligned.size >= 3) drawAlignment(canvas, cx, cy, r, theme, atMs, aligned, a)
        drawSun(canvas, cx, cy, r, a)

        for (body in Orrery.planets) {
            val p = positionOf(body, cx, cy, r, atMs, moonLongitude)
            if (body == Orrery.Body.SATURN) drawSaturnsRing(canvas, p.x, p.y, r, theme, a)
            drawBody(canvas, p.x, p.y, dotRadius(body, r), colourOf(body, theme), a)
            if (body == grabbed) drawHeld(canvas, p.x, p.y, dotRadius(body, r), theme, a)
            if (body == Orrery.Body.EARTH) {
                drawMoon(canvas, cx, cy, r, theme, atMs, moonLongitude, detachedMoon, a, grabbed)
            }
        }
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, r: Float, theme: ClockTheme, a: Float) {
        stroke.color = theme.minorTick
        stroke.strokeWidth = r * 0.004f
        for (body in Orrery.planets) {
            // The outer rings fainter than the inner ones, which is what
            // stops eight concentric circles reading as a target.
            val depth = Orrery.planets.indexOf(body) / (Orrery.planets.size - 1f)
            stroke.alpha = ((90 - 40 * depth) * a).toInt()
            canvas.drawCircle(cx, cy, ringRadius(body, r), stroke)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, a: Float) {
        val sr = r * 0.055f
        fill.color = SUN
        fill.alpha = (60 * a).toInt()
        canvas.drawCircle(cx, cy, sr * 1.75f, fill)
        fill.alpha = (255 * a).toInt()
        canvas.drawCircle(cx, cy, sr, fill)
    }

    private fun drawBody(canvas: Canvas, x: Float, y: Float, radius: Float, colour: Int, a: Float) {
        fill.color = colour
        fill.alpha = (255 * a).toInt()
        canvas.drawCircle(x, y, radius, fill)
    }

    /**
     * Saturn's ring, which is the only reason anybody can tell Saturn from
     * Jupiter at four pixels across.
     */
    private fun drawSaturnsRing(canvas: Canvas, x: Float, y: Float, r: Float, theme: ClockTheme, a: Float) {
        val rad = dotRadius(Orrery.Body.SATURN, r)
        oval.set(x - rad * 2.1f, y - rad * 0.55f, x + rad * 2.1f, y + rad * 0.55f)
        stroke.color = colourOf(Orrery.Body.SATURN, theme)
        stroke.alpha = (210 * a).toInt()
        stroke.strokeWidth = rad * 0.34f
        canvas.drawOval(oval, stroke)
    }

    /**
     * The Moon, and its phase, on its little ring around the Earth.
     *
     * Drawn as a half-lit disc facing away from the Sun rather than as a
     * dot, because the phase is the whole reason for winding the Earth in
     * the first place: the lit side always points at the middle of the
     * dial, so a full moon is the one on the far side of the Earth from the
     * Sun and it can be read without counting anything.
     */
    private fun drawMoon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        theme: ClockTheme,
        atMs: Long,
        moonLongitude: Double,
        detached: Boolean,
        a: Float,
        grabbed: Orrery.Body?
    ) {
        val earth = positionOf(Orrery.Body.EARTH, cx, cy, r, atMs, moonLongitude)
        stroke.color = theme.minorTick
        stroke.alpha = ((if (detached) 40 else 70) * a).toInt()
        stroke.strokeWidth = r * 0.003f
        canvas.drawCircle(earth.x, earth.y, Orrery.MOON_RING * r, stroke)

        val p = positionOf(Orrery.Body.MOON, cx, cy, r, atMs, moonLongitude)
        val rad = dotRadius(Orrery.Body.MOON, r)
        if (detached) {
            // Let go of the mechanism: an outline, so it is plainly not
            // being driven rather than plainly broken.
            stroke.color = colourOf(Orrery.Body.MOON, theme)
            stroke.alpha = (150 * a).toInt()
            stroke.strokeWidth = rad * 0.5f
            canvas.drawCircle(p.x, p.y, rad, stroke)
            return
        }
        drawBody(canvas, p.x, p.y, rad, colourOf(Orrery.Body.MOON, theme), a)
        // The dark half, on the side away from the Sun — which is the side
        // away from the middle of the dial.
        val away = longitudeOf(cx, cy, p.x, p.y)
        val shade = pointOn(p.x, p.y, rad * 0.62f, away)
        fill.color = theme.face
        fill.alpha = (210 * a).toInt()
        canvas.drawCircle(shade.x, shade.y, rad * 0.86f, fill)
        if (grabbed == Orrery.Body.MOON) drawHeld(canvas, p.x, p.y, rad, theme, a)
    }

    /** A ring round whatever is under the finger. */
    private fun drawHeld(canvas: Canvas, x: Float, y: Float, radius: Float, theme: ClockTheme, a: Float) {
        stroke.color = theme.numeral
        stroke.alpha = (180 * a).toInt()
        stroke.strokeWidth = radius * 0.22f
        canvas.drawCircle(x, y, radius * 2.1f, stroke)
    }

    /**
     * The line through an alignment, from the Sun out past the furthest
     * body in it.
     *
     * Drawn down the middle of the run rather than through any one planet:
     * an alignment is a fact about all of them, and a line pinned to one
     * would swing about as that one wandered inside the group.
     */
    private fun drawAlignment(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        theme: ClockTheme,
        atMs: Long,
        bodies: List<Orrery.Body>,
        a: Float
    ) {
        val first = Orrery.longitude(bodies.first(), atMs)
        val middle = Orrery.wrap(
            first + bodies.sumOf { Orrery.shortWay(first, Orrery.longitude(it, atMs)) } / bodies.size
        )
        val out = bodies.maxOf { ringRadius(it, r) } * 1.06f
        val end = pointOn(cx, cy, out, middle)
        stroke.color = theme.secondHand
        stroke.alpha = (120 * a).toInt()
        stroke.strokeWidth = r * 0.006f
        canvas.drawLine(cx, cy, end.x, end.y, stroke)
    }

    // --------------------------------------------------------- the readout

    /**
     * The line under the date: what is worth going outside for on the day
     * being shown, or what is standing in line, or nothing.
     *
     * Returns null when there is nothing to say, which is most days — a
     * readout that always says something is a readout nobody reads.
     */
    fun caption(
        resources: android.content.res.Resources,
        atMs: Long,
        zoneOffsetMs: Int,
        aligned: List<Orrery.Body>
    ): String? {
        val day = CivilDays.dayOf(atMs, zoneOffsetMs)
        SkyEvents.on(day).firstOrNull()?.let { return nameOf(resources, it) }
        if (aligned.size >= 3) {
            return resources.getString(
                R.string.orrery_aligned,
                aligned.joinToString(", ") { resources.getString(nameKeyOf(it)) }
            )
        }
        return null
    }

    /** What one event is called. */
    fun nameOf(resources: android.content.res.Resources, event: SkyEvents.Event): String =
        when (event.kind) {
            SkyEvents.Kind.SOLAR_ECLIPSE -> resources.getString(
                when (event.grade) {
                    SkyEvents.Grade.TOTAL -> R.string.sky_solar_total
                    SkyEvents.Grade.ANNULAR -> R.string.sky_solar_annular
                    else -> R.string.sky_solar_partial
                }
            )
            SkyEvents.Kind.LUNAR_ECLIPSE -> resources.getString(
                when (event.grade) {
                    SkyEvents.Grade.TOTAL -> R.string.sky_lunar_total
                    else -> R.string.sky_lunar_partial
                }
            )
            SkyEvents.Kind.METEORS -> resources.getString(
                R.string.sky_meteors,
                resources.getString(showerName(event.shower))
            )
            SkyEvents.Kind.COMET -> resources.getString(R.string.sky_comet)
            SkyEvents.Kind.FULL_MOON -> resources.getString(R.string.sky_full_moon)
            SkyEvents.Kind.NEW_MOON -> resources.getString(R.string.sky_new_moon)
            SkyEvents.Kind.OPPOSITION -> resources.getString(
                R.string.sky_opposition,
                resources.getString(nameKeyOf(event.body ?: Orrery.Body.MARS))
            )
        }

    private fun showerName(shower: SkyEvents.Shower?): Int = when (shower) {
        SkyEvents.Shower.QUADRANTIDS -> R.string.shower_quadrantids
        SkyEvents.Shower.LYRIDS -> R.string.shower_lyrids
        SkyEvents.Shower.ETA_AQUARIIDS -> R.string.shower_eta_aquariids
        SkyEvents.Shower.ORIONIDS -> R.string.shower_orionids
        SkyEvents.Shower.LEONIDS -> R.string.shower_leonids
        SkyEvents.Shower.GEMINIDS -> R.string.shower_geminids
        SkyEvents.Shower.URSIDS -> R.string.shower_ursids
        else -> R.string.shower_perseids
    }

    /** What a body is called. */
    fun nameKeyOf(body: Orrery.Body): Int = when (body) {
        Orrery.Body.MERCURY -> R.string.body_mercury
        Orrery.Body.VENUS -> R.string.body_venus
        Orrery.Body.EARTH -> R.string.body_earth
        Orrery.Body.MARS -> R.string.body_mars
        Orrery.Body.JUPITER -> R.string.body_jupiter
        Orrery.Body.SATURN -> R.string.body_saturn
        Orrery.Body.URANUS -> R.string.body_uranus
        Orrery.Body.NEPTUNE -> R.string.body_neptune
        Orrery.Body.MOON -> R.string.body_moon
    }

    /** Draws the caption under the date, in the dial's own voice. */
    fun drawCaption(
        canvas: Canvas,
        cx: Float,
        baselineY: Float,
        r: Float,
        theme: ClockTheme,
        caption: String,
        alpha: Float
    ) {
        text.color = theme.numeral
        text.alpha = (170 * alpha.coerceIn(0f, 1f)).toInt()
        text.textSize = r * 0.062f
        canvas.drawText(caption, cx, baselineY, text)
    }
}
