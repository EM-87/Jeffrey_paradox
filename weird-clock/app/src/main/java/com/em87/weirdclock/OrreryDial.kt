package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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
     * The smallest a planet's touch target may be, as a fraction of the
     * dial — about twenty-two density-independent pixels on a phone.
     */
    private const val REACH_FLOOR = 0.115f

    /**
     * How much of the gap between two rings counts as "on this one",
     * when deciding which of several planets in reach was meant.
     *
     * The reach has to be wider than the space between two rings — a finger
     * is, and shrinking the target back below it is what made the planets
     * impossible to take hold of in the first place. So on a crowded part
     * of the dial two or three planets are within reach at once, and
     * something has to choose. It used to be whichever was nearest in plain
     * pixels, which is why grabbing worked perfectly with the planets
     * spread out and went wrong when they bunched up: a touch aimed at
     * Venus, a few pixels off towards Mercury, took Mercury and wound the
     * whole system by Mercury's year.
     *
     * Distance across an orbit counts for much more than distance along it,
     * because along an orbit there is nothing else to be confused with. So
     * the ring your finger is on decides, which is how anybody points at a
     * planet on a diagram — and being sloppy round the ring costs nothing.
     */
    private const val ACROSS_OF_GAP = 0.45f

    /**
     * The same colours, dropped in tone on a pale dial.
     *
     * Half of the planets are naturally light — Venus is straw, Saturn is
     * straw, Uranus is a pale green — and on the white face they came out
     * as faint smudges that had to be hunted for. Darkening them keeps the
     * alphabet (straw is still straw next to Mars's rust) while giving each
     * one an edge against the face it sits on.
     */
    internal fun colourOf(body: Orrery.Body, theme: ClockTheme): Int {
        val raw = colours.getValue(body)
        // Night first, and by the same thirty per cent the whole dial drops
        // to. These colours do not follow the theme — that is the point of
        // them — but "not themed" was being read as "not dimmed", and eight
        // bright planets over a dial turned down for the bedroom is worse
        // than no dial at all.
        if (theme.dimmed) return darkened(raw, 0.70f)
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

    /**
     * How far out a body's ring is, in pixels.
     *
     * [zoom] pushes the whole system outwards. At 1 the eight rings fill the
     * dial; at [Orrery.MAX_ZOOM] the Earth's is the one on the rim and the
     * four outer planets have gone off the edge of it.
     */
    fun ringRadius(body: Orrery.Body, r: Float, zoom: Float = 1f): Float =
        Orrery.ringFraction(body) * r * zoom

    /**
     * How big a body is drawn, in pixels.
     *
     * The planets grow with the zoom, which is the point of zooming: the
     * Earth is a dozen pixels across at rest and the Moon half that, and
     * two things that small are not things a finger can choose between.
     * Not the full factor, or Jupiter would fill a quarter of the face —
     * enough to make the small ones reachable.
     */
    fun dotRadius(body: Orrery.Body, r: Float, zoom: Float = 1f): Float =
        r * 0.052f * (sizes[body] ?: 0.4f) * (1f + (zoom - 1f) * 0.75f)

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
        moonLongitude: Double,
        zoom: Float = 1f
    ): PointF {
        if (body == Orrery.Body.MOON) {
            val earth = positionOf(Orrery.Body.EARTH, cx, cy, r, atMs, moonLongitude, zoom)
            val ring = Orrery.MOON_RING * r * (1f + (zoom - 1f) * 0.75f)
            return pointOn(earth.x, earth.y, ring, moonLongitude)
        }
        return pointOn(cx, cy, ringRadius(body, r, zoom), Orrery.longitude(body, atMs))
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
     * How far apart two neighbouring rings are, in pixels.
     *
     * Taken from the rings themselves rather than written down, so that
     * moving [Orrery.ringFraction] moves the targets with it.
     */
    private fun ringGap(r: Float, zoom: Float): Float {
        val planets = Orrery.planets
        return (Orrery.ringFraction(planets.last()) - Orrery.ringFraction(planets.first())) /
            (planets.size - 1) * r * zoom
    }

    /**
     * Which body a finger has landed on, or null.
     *
     * Nearest wins rather than first found, which is what makes the Moon
     * reachable at all: it stands on top of the Earth, and a search that
     * stopped at the first body within reach would answer "Earth" for every
     * touch aimed at the Moon on the near side of its orbit. The order the
     * list is walked in does not matter, and an earlier version of this
     * comment claimed it did.
     *
     * Reach and choice are two different questions and are answered
     * separately. Whether a body is in reach at all is a plain circle,
     * wide enough for a finger. Which of the ones in reach was meant is
     * decided by a distance that counts across an orbit far more heavily
     * than along it — see [ACROSS_OF_GAP].
     *
     * [skip] is whatever is not in the sky to be taken hold of. A planet
     * lying on the floor of the case still has a place in the arithmetic
     * and none on the glass, and hit testing that did not know the
     * difference handed out grabs of planets that were not there.
     */
    fun bodyAt(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        r: Float,
        atMs: Long,
        moonLongitude: Double,
        zoom: Float = 1f,
        skip: Set<Orrery.Body> = emptySet()
    ): Orrery.Body? {
        var best: Orrery.Body? = null
        var bestScore = Float.MAX_VALUE
        val across = maxOf(ringGap(r, zoom) * ACROSS_OF_GAP, 1f)
        val along = r * REACH_FLOOR
        val fingerR = hypot(x - cx, y - cy)
        // A planet nobody had found yet is not on the glass to be taken
        // hold of. Worked out here rather than passed in, so that what can
        // be grabbed and what is drawn can never disagree.
        val unknown = SkyAge.unknownAt(atMs)
        for (body in Orrery.planets + Orrery.Body.MOON) {
            if (body in skip || body in unknown) continue
            val p = positionOf(body, cx, cy, r, atMs, moonLongitude, zoom)
            val dot = dotRadius(body, r, zoom)
            // A planet the zoom has pushed clear off the edge is not there
            // to be taken hold of. One with half of it still showing is:
            // the clip cuts it at the rim and what is left is a thing on
            // the glass, so a finger on it should find it.
            if (hypot(p.x - cx, p.y - cy) > r + dot) continue
            // Room around the smallest ones: Mercury is four pixels across
            // on a phone, and a target has to be bigger than the thing it
            // is a target for. The floor was once a twentieth of the dial —
            // nine density-independent pixels, about a third of what a
            // finger can be expected to hit, and the reason taking hold of
            // a planet was "very difficult".
            val reach = maxOf(dot * 2.4f, r * REACH_FLOOR)
            val gap = hypot(x - p.x, y - p.y)
            if (gap >= reach) continue
            val score = if (body == Orrery.Body.MOON) {
                // The Moon is the one body not on a ring about the middle,
                // and it sits inside the Earth's own reach. Plain distance
                // for it, as a fraction of its reach so it can be set
                // against the others: on the Moon it wins, a little off it
                // the Earth does.
                gap / reach
            } else {
                val ring = ringRadius(body, r, zoom)
                val outward = kotlin.math.abs(fingerR - ring)
                val alongArc = ring * Math.toRadians(
                    Orrery.separation(
                        Orrery.longitude(body, atMs),
                        longitudeOf(cx, cy, x, y)
                    )
                ).toFloat()
                hypot(outward / across, alongArc / along)
            }
            if (score < bestScore) {
                best = body
                bestScore = score
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
        grabbed: Orrery.Body? = null,
        zoom: Float = 1f,
        busyDays: Set<Int> = emptySet(),
        fallen: Set<Orrery.Body> = emptySet(),
        sunIsDown: Boolean = false,
        comets: Boolean = true,
        face: android.graphics.Path? = null
    ) {
        if (alpha <= 0.01f) return
        val a = alpha.coerceIn(0f, 1f)

        // Wound back far enough the sky thins out: Neptune goes in 1846,
        // Uranus in 1781, and the five that can be seen with an eye go
        // where the records do, leaving the Earth, the Moon and the Sun —
        // see [SkyAge].
        val unknown = SkyAge.unknownAt(atMs)
        // The year of days is the one thing that belongs *outside* the
        // face: a ring of ticks on the rim with a dot beyond it for every
        // busy day. It is drawn before the clip goes on, and everything
        // else is drawn under it.
        val days = Orrery.dayMarkFade(zoom)
        if (days > 0f) drawYearOfDays(canvas, cx, cy, r, theme, atMs, a * days, busyDays)

        // Everything in the sky is cut off at the edge of the face, which
        // is what an edge is for. Zoomed out far enough the outer planets
        // ride over the rim and onto the case, and used to hang there
        // whole until their centre crossed the line and they vanished all
        // at once — a planet leaving by the door rather than over the
        // horizon. Now they slide under it.
        canvas.save()
        if (face != null) {
            canvas.clipPath(face)
        } else {
            clip.reset()
            clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            canvas.clipPath(clip)
        }

        drawRings(canvas, cx, cy, r, theme, a, zoom, unknown)
        if (comets) drawComets(canvas, cx, cy, r, theme, atMs, a, zoom)
        if (aligned.size >= 3) drawAlignment(canvas, cx, cy, r, theme, atMs, aligned, a, zoom)
        if (!sunIsDown) drawSun(canvas, cx, cy, r, theme, a)

        for (body in Orrery.planets) {
            // A planet that has been knocked off its orbit is lying in the
            // case, and the case draws it.
            if (body in fallen) continue
            // One that has not been discovered yet is not lying anywhere.
            if (body in unknown) continue
            val p = positionOf(body, cx, cy, r, atMs, moonLongitude, zoom)
            val dot = dotRadius(body, r, zoom)
            // Pushed clear off the edge by the zoom: not drawn at all,
            // which saves the work. Anything still touching the face is
            // drawn and the clip takes the half that is over the rim —
            // that used to be this same test on the centre alone, so a
            // planet stayed whole until its middle crossed the line and
            // then disappeared in one frame.
            if (hypot(p.x - cx, p.y - cy) > r + dot * 2f) continue
            if (body == Orrery.Body.SATURN) drawSaturnsRing(canvas, p.x, p.y, r, theme, a, zoom)
            drawBody(canvas, p.x, p.y, dot, colourOf(body, theme), a)
            if (body == grabbed) drawHeld(canvas, p.x, p.y, dot, theme, a)
            if (body == Orrery.Body.EARTH && Orrery.Body.MOON !in fallen) {
                drawMoon(canvas, cx, cy, r, theme, atMs, moonLongitude, detachedMoon, a, grabbed, zoom)
            }
        }
        canvas.restore()
    }

    /** Scratch, for the round face that has no path of its own. */
    private val clip = android.graphics.Path()

    private var quarterYear = Int.MIN_VALUE
    private var quarterCache: Set<Int> = emptySet()

    /**
     * The four days a year the Earth crosses into a new quarter of the
     * ecliptic: the solstices and the equinoxes.
     *
     * These are the answer to the question the ring kept raising — why the
     * first of January is not at the top. Twelve on this dial is ecliptic
     * longitude ninety, which is the December solstice by definition, and
     * the year ring starts wherever the calendar starts, which is ten days
     * later because Caesar put New Year where the consuls took office
     * rather than where the sun turns. Rotating the ring to hide that would
     * cost the one property that makes it worth drawing — that the Earth
     * always stands exactly on today's mark. So the four real dates are
     * marked instead, and the ten-day gap becomes something the dial says
     * rather than something it gets wrong.
     *
     * Found by walking the year and watching for the crossing rather than
     * by a formula, because the crossing is what is being drawn: the mark
     * has to land on a day the ring actually has a tick for, and the
     * nearest tick to an exact instant is not always the day the almanac
     * prints. Cached, because the answer only changes once a year and the
     * ring is redrawn many times a second.
     */
    fun quarterDays(year: Int): Set<Int> {
        if (year == quarterYear) return quarterCache
        val first = CivilDays.epochDay(year, 1, 1)
        val found = mutableSetOf<Int>()
        var wasQuadrant = -1
        for (i in 0 until Orrery.daysInYear(year)) {
            val day = first + i
            val quadrant =
                (Orrery.longitude(Orrery.Body.EARTH, day * CivilDays.DAY_MS) / 90.0).toInt()
            // The day before, not this one. Each day is sampled at its own
            // midnight, so finding the Earth past the boundary at the start
            // of a day means it crossed at some point during the day
            // before — and the day it crossed on is the date the almanac
            // prints. Marked as found it, the ring put every solstice and
            // equinox one tick late, which is invisible on the ring and
            // wrong in the only place it can be checked.
            if (wasQuadrant >= 0 && quadrant != wasQuadrant && i > 0) found += day - 1
            wasQuadrant = quadrant
        }
        quarterYear = year
        quarterCache = found
        return found
    }

    /**
     * The year the dial is standing in, marked out in days.
     *
     * Each mark sits where the Earth actually stands on that day, not at an
     * even three hundred and sixty-fifth of the circle. That costs a Kepler
     * solve per day and buys two things: the Earth is always exactly on
     * today's mark, and the leap year needs no special case at all — a leap
     * year simply has one more mark on it, in its own place.
     *
     * The first of each month is drawn longer, because a ring of identical
     * ticks is a texture rather than a calendar.
     */
    private fun drawYearOfDays(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        theme: ClockTheme,
        atMs: Long,
        alpha: Float,
        busyDays: Set<Int>
    ) {
        val year = CivilDays.dateOf(CivilDays.dayOf(atMs, 0)).first
        val first = CivilDays.epochDay(year, 1, 1)
        val today = CivilDays.dayOf(TimeKeeper.nowMs(), 0)
        val rim = r * 0.94f
        val quarters = quarterDays(year)
        for (i in 0 until Orrery.daysInYear(year)) {
            val day = first + i
            val angle = Orrery.longitude(Orrery.Body.EARTH, day * CivilDays.DAY_MS)
            val quarterDay = day in quarters
            val monthStart = CivilDays.dateOf(day).third == 1
            val length = when {
                quarterDay -> 0.075f
                monthStart -> 0.045f
                else -> 0.022f
            }
            val inner = rim - r * length
            val from = pointOn(cx, cy, inner, angle)
            val to = pointOn(cx, cy, rim, angle)
            stroke.color = theme.numeral
            stroke.alpha = ((if (quarterDay) 220 else if (monthStart) 150 else 70) * alpha).toInt()
            stroke.strokeWidth = r * (if (quarterDay) 0.009f else if (monthStart) 0.006f else 0.003f)
            canvas.drawLine(from.x, from.y, to.x, to.y, stroke)

            if (day in busyDays) {
                // Outside the rim, where nothing else is: how full the year
                // has been, and how full it is about to get, read round the
                // dial at a glance. Past days grey, days to come bright.
                val dot = pointOn(cx, cy, rim + r * 0.035f, angle)
                fill.color = theme.numeral
                fill.alpha = ((if (day < today) 90 else 230) * alpha).toInt()
                canvas.drawCircle(dot.x, dot.y, r * 0.011f, fill)
            }
        }
    }

    /**
     * Which day of the year a touch outside the rim is pointing at, or null
     * if it is nowhere near the ring of dots.
     */
    fun dayAt(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        r: Float,
        atMs: Long,
        zoom: Float
    ): Int? {
        if (Orrery.dayMarkFade(zoom) <= 0f) return null
        val out = hypot(x - cx, y - cy)
        val ring = r * 0.94f + r * 0.035f
        if (kotlin.math.abs(out - ring) > r * 0.06f) return null
        val angle = longitudeOf(cx, cy, x, y)
        val year = CivilDays.dateOf(CivilDays.dayOf(atMs, 0)).first
        val first = CivilDays.epochDay(year, 1, 1)
        var best: Int? = null
        var bestGap = 360.0
        for (i in 0 until Orrery.daysInYear(year)) {
            val day = first + i
            val gap = Orrery.separation(
                angle, Orrery.longitude(Orrery.Body.EARTH, day * CivilDays.DAY_MS)
            )
            if (gap < bestGap) { bestGap = gap; best = day }
        }
        // Within a day and a half of a mark, or it was a touch on the case.
        return if (bestGap < 1.5) best else null
    }

    private fun drawRings(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        theme: ClockTheme, a: Float, zoom: Float,
        unknown: Set<Orrery.Body> = emptySet()
    ) {
        stroke.color = theme.minorTick
        stroke.strokeWidth = r * 0.004f
        // Stronger on a daylit sky. A thin grey circle that reads against
        // black disappears against pale blue, and the orbits are the thing
        // that makes this a solar system rather than nine dots.
        val lift = if (isPale(theme.face)) 1.9f else 1f
        for (body in Orrery.planets) {
            // No orbit for a planet nobody knew was there. The ring is the
            // loudest part of a planet on this dial — a circle across the
            // whole face against a dot four pixels wide — so leaving them
            // in would say "eight planets" in the one year Herschel had
            // not looked yet.
            if (body in unknown) continue
            // The outer rings fainter than the inner ones, which is what
            // stops eight concentric circles reading as a target.
            val depth = Orrery.planets.indexOf(body) / (Orrery.planets.size - 1f)
            stroke.alpha = ((90 - 40 * depth) * a * lift).toInt().coerceAtMost(255)
            val radius = ringRadius(body, r, zoom)
            // A ring wider than the face has no part of it inside the face,
            // so there is nothing to draw. The whole sky is clipped to the
            // face now — see [draw] — which is what lets a ring that is
            // only *partly* outside be drawn as an arc rather than skipped
            // whole. On a polygonal dial that arc is what the corners were
            // always missing.
            if (radius > r * 1.45f) continue
            canvas.drawCircle(cx, cy, radius, stroke)
        }
    }

    /**
     * The visitors' orbits, and the visitors on them.
     *
     * Drawn under the planets rather than over them, because a comet's
     * orbit crosses every ring on the dial and a wire laid over Jupiter
     * would read as a thing attached to Jupiter.
     *
     * The ellipse is drawn about its own centre, which is not the Sun.
     * That offset is the whole picture: the Sun sits at one focus, well
     * off to one side, and it is why the comet spends a moment at the near
     * end and a lifetime at the far one.
     */
    private fun drawComets(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        theme: ClockTheme, atMs: Long, a: Float, zoom: Float
    ) {
        for (comet in Comets.all) {
            // Wound far enough out, a comet stops being a position and
            // becomes a decoration: the period is not a constant, and
            // counting whole orbits from a known passage drifts by a
            // return's worth of wander each time round — see
            // [Comets.trust]. So they fade out with the drift rather than
            // being drawn to five decimal places in 2000 BC.
            val sure = Comets.trust(comet, atMs)
            if (sure <= 0.02f) continue
            val fade = a * sure
            val o = Comets.orbitOf(comet)
            val semiMajor = o.aphelionRing / (1f + o.eccentricity.toFloat()) * r * zoom
            val semiMinor = semiMajor * sqrt(1.0 - o.eccentricity * o.eccentricity).toFloat()
            // The middle of the ellipse, away from the Sun along the axis
            // the near end points down.
            val offset = semiMajor * o.eccentricity.toFloat()
            val axis = Math.toRadians(o.perihelionLongitude)
            val mx = cx - (offset * cos(axis)).toFloat()
            val my = cy + (offset * sin(axis)).toFloat()
            if (semiMajor + offset > r * 1.35f) continue

            val near = Comets.nearness(comet, atMs)
            stroke.color = theme.minorTick
            stroke.strokeWidth = r * 0.003f
            stroke.alpha = ((34 + 46 * near) * fade).toInt()
            canvas.save()
            canvas.rotate(-o.perihelionLongitude.toFloat(), mx, my)
            oval.set(mx - semiMajor, my - semiMinor, mx + semiMajor, my + semiMinor)
            canvas.drawOval(oval, stroke)
            canvas.restore()

            val at = Comets.positionAt(comet, atMs)
            val d = at.radius * r * zoom
            if (d > r) continue
            val p = pointOn(cx, cy, d, at.longitude)

            // The tail points away from the Sun, never along the orbit —
            // it is the Sun blowing the thing apart, not a wake. It grows
            // as the visit comes on and is not there at all the rest of
            // the time, which is also true.
            if (near > 0f) {
                val tail = r * 0.11f * near
                val out = pointOn(cx, cy, d + tail, at.longitude)
                stroke.color = theme.minorTick
                stroke.strokeWidth = r * 0.008f
                stroke.alpha = (150 * near * fade).toInt()
                stroke.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(p.x, p.y, out.x, out.y, stroke)
                // Put back, because this paint is shared with the day
                // marks and a round cap on a tick makes it a lozenge.
                stroke.strokeCap = Paint.Cap.BUTT
            }

            fill.color = theme.minorTick
            fill.alpha = ((120 + 135 * near) * fade).toInt()
            canvas.drawCircle(p.x, p.y, r * 0.011f * (1f + near), fill)
        }
    }

    /** The Sun's colour, night included — the case needs it when it falls. */
    internal fun sunColour(theme: ClockTheme): Int =
        if (theme.dimmed) darkened(SUN, 0.70f) else SUN

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, theme: ClockTheme, a: Float) {
        val sr = r * 0.055f
        fill.color = sunColour(theme)
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
    private fun drawSaturnsRing(
        canvas: Canvas, x: Float, y: Float, r: Float,
        theme: ClockTheme, a: Float, zoom: Float
    ) {
        val rad = dotRadius(Orrery.Body.SATURN, r, zoom)
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
        grabbed: Orrery.Body?,
        zoom: Float
    ) {
        val earth = positionOf(Orrery.Body.EARTH, cx, cy, r, atMs, moonLongitude, zoom)
        stroke.color = theme.minorTick
        stroke.alpha = ((if (detached) 40 else 70) * a).toInt()
        stroke.strokeWidth = r * 0.003f
        val moonRing = Orrery.MOON_RING * r * (1f + (zoom - 1f) * 0.75f)
        canvas.drawCircle(earth.x, earth.y, moonRing, stroke)

        val p = positionOf(Orrery.Body.MOON, cx, cy, r, atMs, moonLongitude, zoom)
        val rad = dotRadius(Orrery.Body.MOON, r, zoom)
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
        a: Float,
        zoom: Float
    ) {
        val first = Orrery.longitude(bodies.first(), atMs)
        val middle = Orrery.wrap(
            first + bodies.sumOf { Orrery.shortWay(first, Orrery.longitude(it, atMs)) } / bodies.size
        )
        val out = (bodies.maxOf { ringRadius(it, r, zoom) } * 1.06f).coerceAtMost(r * 0.98f)
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
        aligned: List<Orrery.Body>,
        comets: Boolean = false,
        latin: Boolean = false
    ): String? {
        // Before the sky events, because a comet at its closest is rarer
        // than anything on that list — which includes the full moon, and
        // a full moon would shut the comet up one time in four.
        if (comets) Comets.visiting(atMs)?.let {
            return resources.getString(
                R.string.comet_visit,
                resources.getString(Comets.nameKeyOf(it)),
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                    .format(java.util.Date(Comets.nearestPerihelion(it, atMs)))
            )
        }
        val day = CivilDays.dayOf(atMs, zoneOffsetMs)
        SkyEvents.on(day).firstOrNull()?.let {
            return if (latin) latinNameOf(resources, it) else nameOf(resources, it)
        }
        if (aligned.size >= 3) {
            val names = aligned.joinToString(", ") {
                resources.getString(if (latin) latinNameOf(it) else nameKeyOf(it))
            }
            return resources.getString(
                if (latin) R.string.lat_aligned else R.string.orrery_aligned, names
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

    /**
     * What a body is called, in Latin.
     *
     * The dial writes its Roman years in Roman, and a caption in English
     * under a date in Roman numerals is the same mistake the date itself
     * used to make: half of it in one voice and half in another. So the
     * whole caption goes with the script — the planets, the alignments,
     * the eclipses, all of them — and Latin needs no translating because
     * being Latin is the entire point of it.
     */
    private fun latinNameOf(body: Orrery.Body): Int = when (body) {
        Orrery.Body.MERCURY -> R.string.lat_mercury
        Orrery.Body.VENUS -> R.string.lat_venus
        Orrery.Body.EARTH -> R.string.lat_earth
        Orrery.Body.MARS -> R.string.lat_mars
        Orrery.Body.JUPITER -> R.string.lat_jupiter
        Orrery.Body.SATURN -> R.string.lat_saturn
        Orrery.Body.URANUS -> R.string.lat_uranus
        Orrery.Body.NEPTUNE -> R.string.lat_neptune
        Orrery.Body.MOON -> R.string.lat_moon
    }

    private fun latinShowerName(shower: SkyEvents.Shower?): Int = when (shower) {
        SkyEvents.Shower.QUADRANTIDS -> R.string.lat_quadrantids
        SkyEvents.Shower.LYRIDS -> R.string.lat_lyrids
        SkyEvents.Shower.ETA_AQUARIIDS -> R.string.lat_eta_aquariids
        SkyEvents.Shower.ORIONIDS -> R.string.lat_orionids
        SkyEvents.Shower.LEONIDS -> R.string.lat_leonids
        SkyEvents.Shower.GEMINIDS -> R.string.lat_geminids
        SkyEvents.Shower.URSIDS -> R.string.lat_ursids
        else -> R.string.lat_perseids
    }

    /** One event, said in Latin. */
    private fun latinNameOf(
        resources: android.content.res.Resources,
        event: SkyEvents.Event
    ): String = when (event.kind) {
        SkyEvents.Kind.SOLAR_ECLIPSE -> resources.getString(
            when (event.grade) {
                SkyEvents.Grade.TOTAL -> R.string.lat_solar_total
                SkyEvents.Grade.ANNULAR -> R.string.lat_solar_annular
                else -> R.string.lat_solar_partial
            }
        )
        SkyEvents.Kind.LUNAR_ECLIPSE -> resources.getString(
            when (event.grade) {
                SkyEvents.Grade.TOTAL -> R.string.lat_lunar_total
                else -> R.string.lat_lunar_partial
            }
        )
        SkyEvents.Kind.METEORS -> resources.getString(
            R.string.lat_meteors, resources.getString(latinShowerName(event.shower))
        )
        SkyEvents.Kind.COMET -> resources.getString(R.string.lat_comet)
        SkyEvents.Kind.FULL_MOON -> resources.getString(R.string.lat_full_moon)
        SkyEvents.Kind.NEW_MOON -> resources.getString(R.string.lat_new_moon)
        SkyEvents.Kind.OPPOSITION -> resources.getString(
            R.string.lat_opposition,
            resources.getString(latinNameOf(event.body ?: Orrery.Body.MARS))
        )
    }

    /**
     * What a body was called in a given year — see [SkyAge].
     *
     * The names on this dial are the third or fourth set anybody has used.
     * Wound back far enough Neptune is not there to be named at all, and
     * the five that are there answer to Gu-utu and Dilbat rather than to
     * Mercury and Venus. Uranus and Neptune have no older names for the
     * plain reason that they had not been found yet — they keep the ones
     * they were given, which are the only ones they have ever had.
     */
    fun nameKeyOf(body: Orrery.Body, year: Int): Int = when (SkyAge.eraFor(year)) {
        SkyAge.Era.BABYLONIAN -> when (body) {
            Orrery.Body.MERCURY -> R.string.bab_mercury
            Orrery.Body.VENUS -> R.string.bab_venus
            Orrery.Body.EARTH -> R.string.bab_earth
            Orrery.Body.MARS -> R.string.bab_mars
            Orrery.Body.JUPITER -> R.string.bab_jupiter
            Orrery.Body.SATURN -> R.string.bab_saturn
            Orrery.Body.MOON -> R.string.bab_moon
            else -> nameKeyOf(body)
        }
        SkyAge.Era.GREEK -> when (body) {
            Orrery.Body.MERCURY -> R.string.grk_mercury
            Orrery.Body.VENUS -> R.string.grk_venus
            Orrery.Body.EARTH -> R.string.grk_earth
            Orrery.Body.MARS -> R.string.grk_mars
            Orrery.Body.JUPITER -> R.string.grk_jupiter
            Orrery.Body.SATURN -> R.string.grk_saturn
            Orrery.Body.MOON -> R.string.grk_moon
            else -> nameKeyOf(body)
        }
        SkyAge.Era.LATIN -> latinNameOf(body)
        SkyAge.Era.MODERN -> nameKeyOf(body)
    }

    /**
     * What a comet was called in a given year.
     *
     * All four are named for men who worked out that the thing in the sky
     * was the *same* thing that had been there before — which is what a
     * periodic comet's name commemorates, and which happened long after
     * anybody first saw them. Before that a comet had no name at all: it
     * was the comet, the one in the sky that year, and every language on
     * this dial has a word for that and nothing more. Halley's own returns
     * were watched and written down for two thousand years under no name
     * but that one.
     */
    fun cometNameKeyOf(comet: Comets.Comet, year: Int): Int {
        if (Comets.wasNamedIn(comet, year)) return Comets.nameKeyOf(comet)
        return when (SkyAge.eraFor(year)) {
            SkyAge.Era.BABYLONIAN -> R.string.bab_comet
            SkyAge.Era.GREEK -> R.string.grk_comet
            SkyAge.Era.LATIN -> R.string.lat_comet
            SkyAge.Era.MODERN -> R.string.sky_comet
        }
    }

    /**
     * Which comet a finger has landed on, if any.
     *
     * Answered from the same arithmetic that drew it, for the reason given
     * at the top of this file. A comet the dial has stopped believing in —
     * wound far enough out that a fixed period is no longer a position — is
     * not on the glass and is not there to be tapped either.
     */
    fun cometAt(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        r: Float,
        atMs: Long,
        zoom: Float = 1f
    ): Comets.Comet? {
        var best: Comets.Comet? = null
        var bestGap = Float.MAX_VALUE
        val reach = r * REACH_FLOOR
        for (comet in Comets.all) {
            if (Comets.trust(comet, atMs) <= 0.02f) continue
            val at = Comets.positionAt(comet, atMs)
            val d = at.radius * r * zoom
            if (d > r) continue
            val p = pointOn(cx, cy, d, at.longitude)
            val gap = hypot(x - p.x, y - p.y)
            if (gap >= reach || gap >= bestGap) continue
            best = comet
            bestGap = gap
        }
        return best
    }

    /** What a body is called now. */
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
