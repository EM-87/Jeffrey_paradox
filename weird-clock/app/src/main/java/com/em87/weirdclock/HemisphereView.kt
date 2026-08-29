package com.em87.weirdclock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The earth, turning under a nailed-down sun, with you on it.
 *
 * The clock is the planet. The sun is fixed to one side of the screen, the
 * world turns beneath it once a day, and the red dot where you are standing
 * is the hand — which is not a metaphor. Reading it is reading your own
 * longitude against the sun, and that is what noon has always meant.
 *
 * The arithmetic is [Hemisphere]. What is here is the painting: the earth
 * itself, the line where the sun is setting, the ring of hours and the dot.
 *
 * The two maps are NASA's — the Blue Marble by day and the city lights by
 * night, both public domain — projected into the disc pixel by pixel. That
 * is expensive and is done once and kept: the projection does not change
 * when the time does, only the turn does, and a turn is a rotation of
 * something already drawn.
 */
class HemisphereView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    var theme: ClockTheme = ClockThemes.MIDNIGHT
        set(value) { field = value; invalidate() }

    /** Which way the world is being looked at. */
    var view: Hemisphere.View = Hemisphere.View.NORTH
        set(value) {
            if (field == value) return
            field = value
            discard()
            invalidate()
        }

    /** Where the sun is pinned, in degrees anticlockwise from the right. */
    var sunAt: Double = 0.0
        set(value) { field = value; invalidate() }

    /** Where you are, which is the hand. */
    var latitude: Double = 40.0
        set(value) { field = value; invalidate() }

    var longitude: Double = 0.0
        set(value) { field = value; invalidate() }

    /** Whether the app has any business drawing that dot at all. */
    var located: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * Yesterday's clouds, photographed from orbit.
     *
     * An equirectangular picture of the whole earth, handed in the same
     * way as everything else this view draws from — see [CloudStore],
     * which is what fetches it, and [SatelliteClouds], which is the rule
     * that turns a photograph into a veil.
     *
     * Null is the ordinary state: nobody has switched the weather on, or
     * the picture has not arrived yet, and the globe is the globe it has
     * always been.
     */
    var clouds: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            discard()
            invalidate()
        }

    /** The ring of hours round the world, and what is on it. */
    var hourRing: Boolean = true
        set(value) { field = value; invalidate() }

    var hourNumbers: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * Notches inside the rim at every fifteenth meridian.
     *
     * Meridians and not time zones: a zone map has a hundred and
     * thirty-eight edges and most of them follow a river, and a turning
     * globe cannot honestly draw those. These are the meridians the zones
     * were meant to be, so a dot crossing one is the moment the hour
     * changes where you are standing if nobody had ever drawn a border.
     */
    var meridians: Boolean = true
        set(value) { field = value; invalidate() }

    /** For the tests and the widget: pretend it is this instant. */
    internal var atMs: Long? = null
        set(value) { field = value; invalidate() }

    private fun nowMs(): Long = atMs ?: TimeKeeper.nowMs()

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rim = RectF()
    private val src = Rect()
    private val dst = RectF()

    // ------------------------------------------------------- the two maps

    /**
     * The world, twice: lit and unlit.
     *
     * Held as one pair for the whole class rather than per view, because
     * they are the same two pictures whichever way the world is being
     * looked at — what changes is where each pixel of them goes.
     */
    private var dayMap: Bitmap? = null
    private var nightMap: Bitmap? = null

    private fun maps(): Boolean {
        if (dayMap != null && nightMap != null) return true
        val options = BitmapFactory.Options().apply { inScaled = false }
        dayMap = BitmapFactory.decodeResource(resources, R.drawable.earth_day, options)
        nightMap = BitmapFactory.decodeResource(resources, R.drawable.earth_night, options)
        return dayMap != null && nightMap != null
    }

    /**
     * The two discs, projected, and what they were projected for.
     *
     * Kept because projecting a quarter of a million pixels is not
     * something to do sixty times a second, and does not need to be: the
     * map does not change when the time does. On the flat views the turn
     * is a rotation of the finished disc; on the globe it is not, so
     * those are redrawn when the world has moved a degree and a half,
     * which is six minutes.
     */
    private var dayDisc: Bitmap? = null
    private var nightDisc: Bitmap? = null
    private var cloudDisc: Bitmap? = null
    private var bakedFor: String? = null

    private fun discard() {
        dayDisc?.recycle()
        nightDisc?.recycle()
        cloudDisc?.recycle()
        shadow?.recycle()
        dayDisc = null
        nightDisc = null
        cloudDisc = null
        shadow = null
        bakedFor = null
        maskedFor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        discard()
    }

    /** How big the projected discs are drawn, whatever size the view is. */
    private fun bakeSize(): Int = 512

    /**
     * Projects the two maps into two discs.
     *
     * The inverse projection, once per pixel: where on the earth is this
     * point of the disc, and what colour is the map there. Done in
     * geographic longitude rather than in longitude from the sun, so that
     * on the flat views the turning of the world is a rotation of a
     * finished picture and not a reason to do this again.
     */
    private fun bake(spinDeg: Double) {
        val size = bakeSize()
        val key = "$view/$size/${if (view == Hemisphere.View.GLOBE) quantise(spinDeg) else 0}"
        if (key == bakedFor && dayDisc != null) return
        if (!maps()) return
        val day = dayMap ?: return
        val night = nightMap ?: return
        discard()
        val lit = IntArray(size * size)
        val dark = IntArray(size * size)
        val dayPixels = IntArray(day.width * day.height)
        day.getPixels(dayPixels, 0, day.width, 0, 0, day.width, day.height)
        val nightPixels = IntArray(night.width * night.height)
        night.getPixels(nightPixels, 0, night.width, 0, 0, night.width, night.height)
        // And the satellite's picture, if there is one, through the same
        // inverse projection — a third disc rather than a second pass,
        // because working out which place a point of the disc is takes
        // longer than reading three maps at it.
        val sky = clouds
        val veil = if (sky == null) null else IntArray(size * size)
        val skyPixels = if (sky == null) null else IntArray(sky.width * sky.height).also {
            sky.getPixels(it, 0, sky.width, 0, 0, sky.width, sky.height)
        }
        // On the globe the turn is baked in; on the flat views it is not,
        // and the disc is turned when it is drawn.
        val turn = if (view == Hemisphere.View.GLOBE) quantise(spinDeg) else 0.0
        val half = size / 2.0
        for (py in 0 until size) {
            val y = (py + 0.5 - half) / half
            for (px in 0 until size) {
                val x = (px + 0.5 - half) / half
                val place = Hemisphere.unproject(view, x, y, turn) ?: continue
                val u = ((place[1] + 180.0) / 360.0).coerceIn(0.0, 0.9999)
                val v = ((90.0 - place[0]) / 180.0).coerceIn(0.0, 0.9999)
                val at = py * size + px
                lit[at] = sample(dayPixels, day.width, day.height, u, v)
                dark[at] = sample(nightPixels, night.width, night.height, u, v)
                if (veil != null && skyPixels != null && sky != null) {
                    veil[at] = SatelliteClouds.tint(
                        sample(skyPixels, sky.width, sky.height, u, v),
                        view != Hemisphere.View.GLOBE,
                        kotlin.math.hypot(x, y)
                    )
                }
            }
        }
        dayDisc = Bitmap.createBitmap(lit, size, size, Bitmap.Config.ARGB_8888)
        nightDisc = Bitmap.createBitmap(dark, size, size, Bitmap.Config.ARGB_8888)
        cloudDisc = veil?.let { Bitmap.createBitmap(it, size, size, Bitmap.Config.ARGB_8888) }
        bakedFor = key
    }

    /** The globe is redrawn every degree and a half, which is six minutes. */
    private fun quantise(spinDeg: Double): Double =
        Math.round(spinDeg / 1.5).toDouble() * 1.5

    private fun sample(pixels: IntArray, w: Int, h: Int, u: Double, v: Double): Int {
        val x = (u * w).toInt().coerceIn(0, w - 1)
        val y = (v * h).toInt().coerceIn(0, h - 1)
        return pixels[y * w + x] or (0xFF shl 24)
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Space is black on this face whatever the theme, for the same
        // reason it is on the solar system: a white ground behind the
        // earth is a diagram of a planet and the point of this one is
        // that it is a window.
        canvas.drawColor(SPACE)

        val ms = nowMs()
        val sub = Hemisphere.subsolar(ms)
        val subLat = sub[0]
        val subLon = sub[1]
        val r = min(w, h) * (if (hourRing) 0.355f else 0.47f)
        val cx = w / 2f
        val cy = h / 2f

        bake(-subLon + sunAt)
        drawWorld(canvas, cx, cy, r, subLat, subLon)
        if (meridians) drawMeridians(canvas, cx, cy, r, subLon)
        drawSunMark(canvas, cx, cy, r)
        if (located) drawYou(canvas, cx, cy, r, subLat, subLon)
        if (hourRing) drawHours(canvas, cx, cy, r)
    }

    /**
     * The earth, lit on one side.
     *
     * The day disc, then the night disc clipped to the shadow. The line
     * between them is the terminator — where the sun is on the horizon —
     * and it is drawn as a real curve rather than a straight edge because
     * on any of these projections it is not one, except twice a year.
     */
    private fun drawWorld(
        canvas: Canvas, cx: Float, cy: Float, r: Float, subLat: Double, subLon: Double
    ) {
        val day = dayDisc
        val night = nightDisc
        dst.set(cx - r, cy - r, cx + r, cy + r)
        if (day == null || night == null) {
            // No maps: a plain ball rather than nothing, so the clock is
            // still a clock on a device that would not decode them.
            fill.color = 0xFF1B3A5C.toInt()
            canvas.drawCircle(cx, cy, r, fill)
            return
        }
        src.set(0, 0, day.width, day.height)
        // The flat views turn; the globe was baked already turned.
        val spin = if (view == Hemisphere.View.GLOBE) 0f else (-subLon + sunAt).toFloat()
        canvas.save()
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(path)

        canvas.save()
        canvas.rotate(-spin, cx, cy)
        canvas.drawBitmap(day, src, dst, blit)
        // The clouds go on the daylit map and under the night one, which
        // is not an ordering trick — corrected reflectance is sunlight
        // coming back off the top of the cloud, so there is no night half
        // of this photograph to draw. Laying it under the night layer
        // means the dark side covers it exactly where the satellite had
        // nothing to see.
        cloudDisc?.let { canvas.drawBitmap(it, src, dst, blit) }
        canvas.restore()

        // The night side, over the day one, through a mask of where the
        // sun has set.
        //
        // A mask rather than a path, which is what this was. The
        // terminator is a closed curve on the flat views, and it encloses
        // the *lit* half in summer and the *dark* half in winter — so a
        // path that always subtracted it drew midwinter inside out, with
        // Europe dark at noon. At the equinox it is worse: the curve
        // collapses onto a straight line through both poles, encloses
        // nothing at all, and the whole world went dark. None of that can
        // happen to an answer worked out per pixel.
        val mask = nightMask(subLat) ?: run { canvas.restore(); return }
        val layer = canvas.saveLayer(dst, null)
        canvas.save()
        canvas.rotate(-spin, cx, cy)
        canvas.drawBitmap(night, src, dst, blit)
        canvas.restore()
        // And a scrim, so the unlit half reads as unlit rather than as a
        // differently coloured map.
        fill.color = 0xFF000000.toInt()
        fill.alpha = 0x8A
        canvas.drawCircle(cx, cy, r, fill)
        fill.alpha = 255
        maskSrc.set(0, 0, mask.width, mask.height)
        canvas.drawBitmap(mask, maskSrc, dst, cut)
        canvas.restoreToCount(layer)
        canvas.restore()

        // The edge of the world, which is what makes it a ball.
        line.color = theme.rim
        line.alpha = 190
        line.strokeWidth = r * 0.012f
        canvas.drawCircle(cx, cy, r, line)
        line.alpha = 255
    }

    /**
     * Where the sun has set, as an alpha mask over the disc.
     *
     * Worked out per pixel and kept until the sun has moved: a hundred
     * and twenty-eight squared is sixteen thousand answers, which is a
     * few milliseconds, against a projection of a quarter of a million.
     *
     * Soft on purpose. The terminator on the real earth is not a line —
     * the sun is half a degree wide and the air carries the light some way
     * past the horizon — so the mask fades over a couple of degrees, which
     * is also what stops it looking like a shape laid on a photograph.
     */
    private fun nightMask(subLat: Double): Bitmap? {
        val key = "$view/${Math.round(subLat * 4.0)}/${Math.round(sunAt * 2.0)}"
        if (key == maskedFor && shadow != null) return shadow
        val size = MASK
        val pixels = IntArray(size * size)
        val half = size / 2.0
        for (py in 0 until size) {
            val y = (py + 0.5 - half) / half
            for (px in 0 until size) {
                val x = (px + 0.5 - half) / half
                val place = Hemisphere.unproject(view, x, y, sunAt) ?: continue
                val cosZ = Hemisphere.cosZenith(place[0], place[1], subLat)
                // One where the sun is well down and nought where it is
                // up, with the fade between. Written the other way round
                // first, which made the whole world dark at noon and lit
                // at midnight — a mistake that looks like a working
                // clock until you notice Africa is asleep at lunchtime.
                val dark = ((DAWN - cosZ) / (DAWN - DUSK)).coerceIn(0.0, 1.0)
                pixels[py * size + px] = ((dark * 255).toInt() shl 24)
            }
        }
        shadow?.recycle()
        shadow = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        maskedFor = key
        return shadow
    }

    private var shadow: Bitmap? = null
    private var maskedFor: String? = null
    private val maskSrc = Rect()
    private val cut = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    /**
     * The twenty-four meridians, as notches inside the rim.
     *
     * Short, and inside: they are a scale on the world rather than a grid
     * over it, and a full set of lines across the map would be a diagram
     * again.
     */
    private fun drawMeridians(canvas: Canvas, cx: Float, cy: Float, r: Float, subLon: Double) {
        line.color = 0xFFFFFFFF.toInt()
        line.alpha = 90
        line.strokeWidth = r * 0.006f
        for (hour in Hemisphere.meridians()) {
            val bearing = Hemisphere.bearingOfHour(view, hour)
            val a = Math.toRadians(bearing + sunAt)
            val dx = cos(a).toFloat()
            val dy = -sin(a).toFloat()
            canvas.drawLine(
                cx + dx * r * 0.93f, cy + dy * r * 0.93f,
                cx + dx * r, cy + dy * r, line
            )
        }
        line.alpha = 255
    }

    /**
     * The sun, outside the world, where it has been pinned.
     *
     * It stands in the ring where the twelve would be, and the twelve is
     * not drawn — because they are the same thing. Noon *is* under the
     * sun; that is the definition the whole face is built on, and drawing
     * both was two marks fighting over one bearing.
     */
    private fun drawSunMark(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val a = Math.toRadians(sunAt)
        val at = if (hourRing) r * 1.22f else r * 1.10f
        val x = cx + (cos(a) * at).toFloat()
        val y = cy - (sin(a) * at).toFloat()
        fill.color = SUN
        canvas.drawCircle(x, y, r * 0.055f, fill)
        line.color = SUN
        line.alpha = 150
        line.strokeWidth = r * 0.010f
        for (i in 0 until 8) {
            val ray = Math.toRadians(i * 45.0)
            canvas.drawLine(
                x + (cos(ray) * r * 0.075f).toFloat(), y + (sin(ray) * r * 0.075f).toFloat(),
                x + (cos(ray) * r * 0.105f).toFloat(), y + (sin(ray) * r * 0.105f).toFloat(),
                line
            )
        }
        line.alpha = 255
    }

    /**
     * You, which is the hand.
     *
     * A dot and a ring round it, because a dot alone on a photograph of
     * the earth is a dust mark. Not drawn at all when the app has never
     * had a fix: a clock whose hand is at nought because it does not know
     * is a clock that is confidently wrong.
     */
    private fun drawYou(
        canvas: Canvas, cx: Float, cy: Float, r: Float, subLat: Double, subLon: Double
    ) {
        val on = Hemisphere.project(view, latitude, longitude, subLon, sunAt)
        // Behind the world, on the globe.
        if (on[2] < 0.0) return
        val x = cx + (on[0] * r).toFloat()
        val y = cy + (on[1] * r).toFloat()
        fill.color = YOU
        canvas.drawCircle(x, y, r * 0.024f, fill)
        line.color = YOU
        line.strokeWidth = r * 0.010f
        canvas.drawCircle(x, y, r * 0.055f, line)
        // And the line out to the hour it is pointing at, which is the
        // only thing on the face that says this is a clock and not a map.
        line.alpha = 110
        line.strokeWidth = r * 0.006f
        val len = kotlin.math.hypot(on[0], on[1])
        if (len > 0.02) {
            canvas.drawLine(
                x, y,
                cx + (on[0] / len * r).toFloat(), cy + (on[1] / len * r).toFloat(),
                line
            )
        }
        line.alpha = 255
    }

    /**
     * The twenty-four hours round the outside.
     *
     * Solar hours: what the sun says where the dot is, not what a
     * government says. Noon is under the sun by construction, which is
     * the whole of the arithmetic — see [Hemisphere.hourAt].
     */
    private fun drawHours(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val ring = r * 1.06f
        line.color = theme.minorTick
        line.strokeWidth = r * 0.008f
        ink.typeface = PRINT
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = r * 0.088f
        for (hour in 0..23) {
            val a = Math.toRadians(Hemisphere.bearingOfHour(view, hour) + sunAt)
            val dx = cos(a).toFloat()
            val dy = -sin(a).toFloat()
            val major = hour % 6 == 0
            line.alpha = if (major) 235 else 120
            canvas.drawLine(
                cx + dx * ring, cy + dy * ring,
                cx + dx * (ring + r * (if (major) 0.055f else 0.030f)),
                cy + dy * (ring + r * (if (major) 0.055f else 0.030f)),
                line
            )
            // Every third hour, and never the twelve: the sun is drawn
            // there and the sun is what noon means.
            if (!hourNumbers || hour % 3 != 0 || hour == 12) continue
            ink.color = theme.numeral
            val at = r * 1.22f
            canvas.drawText(
                "$hour",
                cx + dx * at, cy + dy * at + ink.textSize * 0.35f, ink
            )
        }
        line.alpha = 255
        rim.set(cx - ring, cy - ring, cx + ring, cy + ring)
    }

    private companion object {
        /** Space, whatever the theme says. */
        const val SPACE = 0xFF05070D.toInt()

        /**
         * Where the shadow starts and where it is complete, as cosines of
         * the sun's angle from straight up.
         *
         * A degree or so above the horizon to a few below it, which is
         * roughly what twilight is and what stops the edge of the night
         * looking like a shape laid over a photograph.
         */
        const val DAWN = 0.03
        const val DUSK = -0.10

        /** How fine the mask is. Sixteen thousand answers, not a quarter
         * of a million. */
        const val MASK = 128

        /** The sun, and you. */
        const val SUN = 0xFFFFC93C.toInt()
        const val YOU = 0xFFFF4B4B.toInt()

        val PRINT: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
}
