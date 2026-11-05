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

    /**
     * Whether the black of space is painted behind the world.
     *
     * On everywhere in the app, where the globe fills a card and the sky
     * is the card. Turnable off for the home screen, where what is behind
     * the world is somebody's wallpaper and whether they want to see it is
     * their business rather than this app's — see [WidgetKind].
     */
    var ground: Boolean = true
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
        set(value) {
            field = value
            keepPinging()
            invalidate()
        }

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

    /**
     * Where to ask for the clouds of a particular day, when the world is
     * being wound.
     *
     * The globe can be turned back through the week now, and the weather
     * has to go back with it or the winding is a lie: yesterday's earth
     * with today's cyclone on it. Handed in as a question rather than as
     * a picture because the answer depends on where the world has been
     * turned to, which this view is the only thing that knows — see
     * [CloudStore.cached], which keeps a week and hands back the nearest
     * day it has.
     *
     * Null on the widget and in the tests, and then [clouds] is whatever
     * was last set, which is what this did for everybody.
     */
    var cloudsOn: ((Long) -> Bitmap?)? = null
        set(value) {
            field = value
            cloudsAskedDay = Long.MIN_VALUE
            invalidate()
        }

    /**
     * And which day's picture that was, asked alongside — see
     * [CloudStore.worn]. The clouds are a photograph of one day, and a
     * globe wound to a day the phone has no photograph of wears the
     * nearest it has; this is how it knows to say so on its face.
     */
    var cloudsDayOn: ((Long) -> DailyImageStore.Worn?)? = null
        set(value) {
            field = value
            cloudsAskedDay = Long.MIN_VALUE
            invalidate()
        }

    /** The day the clouds are of, and how many days off the day asked for. */
    private var cloudsWorn: String? = null
    private var cloudsOff = 0

    /** For the tests: which day's picture the clouds are, as the store names it. */
    internal fun cloudsWornForTest(): String? = cloudsWorn

    /** When the clouds were last asked for, and for which day. */
    private var cloudsAskedDay = Long.MIN_VALUE
    private var cloudsAskedAt = 0L

    /**
     * Last night's lights, on the dark side — see [NightLightStore],
     * which hands them in as brightness, and [NightLights], which is the
     * rule that turns brightness into amber and cyan.
     *
     * Null is the ordinary state: nobody has switched them on, or they
     * have not arrived, and the night side wears the fixed Black Marble
     * of 2016 it always has.
     */
    var lights: NightLightStore.Sky? = null
        set(value) {
            if (sameLights(field, value)) return
            field = value
            discard()
            invalidate()
        }

    /** Whether two skies are the same pictures in the same mode, by identity. */
    private fun sameLights(a: NightLightStore.Sky?, b: NightLightStore.Sky?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.mode == b.mode && a.clean?.lum === b.clean?.lum && a.raw?.lum === b.raw?.lum &&
            a.clean?.lagDays == b.clean?.lagDays && a.raw?.lagDays == b.raw?.lagDays &&
            a.residueFloor == b.residueFloor
    }

    /**
     * Whether the dark side wears the moon's light — see [Moonlight]. No
     * data behind it, only the ephemeris, so it does not wait on the
     * weather switch the way the clouds and the lights do.
     */
    var moonlight: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Where to ask for the lights of a particular night, asked with the clouds. */
    var lightsOn: ((Long) -> NightLightStore.Sky?)? = null
        set(value) {
            field = value
            cloudsAskedDay = Long.MIN_VALUE
            invalidate()
        }

    /** The ring of hours round the world, and what is on it. */
    var hourRing: Boolean = true
        set(value) { field = value; invalidate() }

    var hourNumbers: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * Whether the sun is drawn outside the world.
     *
     * On by default and now able to be turned off, which is not a
     * cosmetic switch: the sun is the only thing standing outside the
     * disc, so with it gone the world can open all the way to the edge of
     * the screen — see [Hemisphere.WORLD_EDGE].
     */
    var showSun: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * And the moon, which was not drawn at all.
     *
     * It goes on the same rim as the sun, at the hour its own meridian is
     * keeping — see [Hemisphere.moonHour] — so the gap between the two
     * marks is the phase, drawn rather than stated. On a full moon they
     * are opposite each other, which is why a full moon rises as the sun
     * sets, and this face is the only place in the app where you can see
     * that being true rather than be told it.
     */
    var showMoon: Boolean = true
        set(value) { field = value; invalidate() }

    /**
     * The alarms that are armed, as milliseconds into the day.
     *
     * Marked on the ring of hours, where the hour they will ring at
     * falls. The dial has had them on its rim for a long time; this face
     * has a rim of its own and the same claim to make on it.
     */
    var alarmsAt: List<Long> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * How far the world has been opened out, from one to
     * [Hemisphere.ZOOM_MAX].
     *
     * Not stored anywhere. A zoom is a thing you do while looking, like
     * leaning in, and a clock that comes back tomorrow still leaning in
     * is a clock that has been left in a state rather than set to one.
     */
    var zoom: Float = Hemisphere.ZOOM_MIN
        set(value) {
            val next = value.coerceIn(Hemisphere.ZOOM_MIN, Hemisphere.ZOOM_MAX)
            if (next == field) return
            field = next
            invalidate()
        }

    /**
     * How far the world has been wound off the time it is, in
     * milliseconds.
     *
     * The earth turns once a day, so turning it *is* winding a clock: the
     * terminator goes round with it, the dot goes round with it, and
     * letting go springs back to now. That is the same gesture the dial
     * has and it is the same idea — this face simply has one hand, and it
     * is the planet.
     */
    private var wound = 0L
    private var springBack: android.animation.ValueAnimator? = null

    /** For the tests and the widget: pretend it is this instant. */
    internal var atMs: Long? = null
        set(value) { field = value; invalidate() }

    private fun nowMs(): Long = (atMs ?: TimeKeeper.nowMs()) + wound

    /** For the tests: how far the world has been turned off now. */
    internal fun woundForTest(): Long = wound

    // ------------------------------------------------------------- touch

    private var lastBearing = 0.0
    private var downX = 0f
    private var downY = 0f
    private var turning = false

    /** Where the finger was last, and when, so a fling has a speed. */
    private var lastMoveAt = 0L
    private var flungAt = 0.0

    /** What the world is doing on its own — see [WorldSpin]. */
    private var spin = WorldSpin.State()
    private var spinning = false
    private var spunAt = 0L

    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                zoom *= detector.scaleFactor
                return true
            }
        }
    )

    /** Which way round the middle a point is, in degrees. */
    private fun bearingAt(x: Float, y: Float): Double =
        Math.toDegrees(kotlin.math.atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble()))

    /** How far from the middle, as a share of the world's own radius. */
    private fun intoWorld(x: Float, y: Float): Float {
        val r = min(width, height) *
            Hemisphere.worldRadius(zoom, hourRing && Hemisphere.hasRimScale(view))
        if (r <= 0f) return Float.MAX_VALUE
        return kotlin.math.hypot(x - width / 2f, y - height / 2f) / r
    }

    /**
     * The world is put back when nobody is looking at it.
     *
     * Both of these are things you do while looking — leaning in, and
     * pushing the planet round — and neither is a setting. Coming back to
     * a clock still zoomed in on a world wound to three hours ago is
     * coming back to a clock that has stopped.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            keepPinging()
            return
        }
        pinging = false
        removeCallbacks(pinger)
        spinning = false
        spin = WorldSpin.State()
        wound = 0L
        zoom = Hemisphere.ZOOM_MIN
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        keepPinging()
    }

    /**
     * The frames the ping needs, and only those.
     *
     * Twenty a second while there is a dot to draw a ring round and the
     * window is up, and none at all otherwise — which is most of the time,
     * since three of the four faces are not this one. A loop that runs
     * whether or not anything is moving is the one thing in this app that
     * has already been found costing a battery for nothing.
     */
    private var pinging = false

    private val pinger = object : Runnable {
        override fun run() {
            if (!pinging) return
            invalidate()
            postDelayed(this, PING_FRAME_MS)
        }
    }

    private fun keepPinging() {
        val wanted = located && isAttachedToWindow && windowVisibility == VISIBLE
        if (wanted == pinging) return
        pinging = wanted
        removeCallbacks(pinger)
        if (wanted) postDelayed(pinger, PING_FRAME_MS)
    }

    /** For the tests: whether the ping is asking for frames. */
    internal fun pingingForTest(): Boolean = pinging

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            turning = false
            return true
        }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                spinning = false
                spin = WorldSpin.State()
                lastBearing = bearingAt(event.x, event.y)
                downX = event.x
                downY = event.y
                lastMoveAt = android.os.SystemClock.uptimeMillis()
                flungAt = 0.0
                // A finger on the world is turning the world, and that is
                // the whole of the rule — the same one the dial has, where
                // a finger on a hand is holding a hand. It used to have to
                // prove itself first by dragging in a curve, so a straight
                // sideways push on the equator turned the page instead,
                // and the world could only be turned up and down. Outside
                // the world there is nothing to hold and the swipe goes
                // through to the pager, which is what a margin is for.
                turning = intoWorld(event.x, event.y) <= 1f
                if (turning) parent?.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!turning) return true
                val now = bearingAt(event.x, event.y)
                var moved = now - lastBearing
                while (moved > 180.0) moved -= 360.0
                while (moved < -180.0) moved += 360.0
                lastBearing = now
                val at = android.os.SystemClock.uptimeMillis()
                val dt = (at - lastMoveAt).coerceAtLeast(1L) / 1000.0
                lastMoveAt = at
                // Smoothed, because one frame of a finger is a noisy
                // measurement and the last one before it is lifted is the
                // noisiest of all — that is the frame a fling is read
                // from, and unsmoothed it is as often a stop as a throw.
                flungAt = flungAt * 0.6 + (moved / dt) * 0.4
                wound += Hemisphere.windBy(view, moved)
                invalidate()
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (!turning) {
                    // A pinch takes the world out of the hand that was
                    // turning it, and when the last finger comes off
                    // nothing was letting it go: it stayed wound to
                    // whenever the pinch began, until the next touch.
                    if (wound != 0L && !spinning) letGo(0.0)
                    return true
                }
                turning = false
                // A finger that has stopped is not a throw, however fast
                // it was going a moment ago.
                val still = android.os.SystemClock.uptimeMillis() - lastMoveAt > STALE_MS
                letGo(if (still) 0.0 else flungAt)
            }
        }
        return true
    }

    /**
     * Lets the world go, and it finds its own way back.
     *
     * A clock that stays where it was pushed is a clock that has been
     * broken rather than played with, so it does come home — but it is a
     * planet and not a hand, and it takes its time about it. The
     * arithmetic is [WorldSpin]; this is the frame loop that runs it and
     * the conversion between a degree of turn and four minutes of clock.
     */
    private fun letGo(rateDegPerSec: Double) {
        val off = wound.toDouble() / Hemisphere.MS_PER_DEGREE * windSense()
        // [off] is in the finger's degrees and the physics counts the
        // other way — see the spinner below, which reads it back with a
        // minus — so the finger's speed has to go in the other way too.
        // It went in as it was, and a world thrown clockwise set off
        // anticlockwise at the speed it had been thrown, which was
        // reported as the world bouncing off something as it was let go.
        spin = WorldSpin.let(-off, -rateDegPerSec)
        if (WorldSpin.resting(spin)) {
            wound = 0L
            spinning = false
            invalidate()
            return
        }
        spinning = true
        spunAt = android.os.SystemClock.uptimeMillis()
        postOnAnimation(spinner)
    }

    /**
     * Which way a degree of finger is a millisecond of clock.
     *
     * [Hemisphere.windBy] knows it and hands back milliseconds; this is
     * the same fact the other way round, so that a wind measured in
     * milliseconds can be handed to a physics that thinks in degrees.
     */
    private fun windSense(): Double =
        Hemisphere.windBy(view, 1.0).toDouble() / Hemisphere.MS_PER_DEGREE

    private val spinner = object : Runnable {
        override fun run() {
            if (!spinning) return
            val at = android.os.SystemClock.uptimeMillis()
            spin = WorldSpin.step(spin, (at - spunAt) / 1000.0)
            spunAt = at
            if (WorldSpin.resting(spin)) {
                spinning = false
                wound = 0L
            } else {
                wound = (-spin.degrees * windSense() * Hemisphere.MS_PER_DEGREE).toLong()
                postOnAnimation(this)
            }
            invalidate()
        }
    }

    /** For the tests: whether the world is still going round on its own. */
    internal fun spinningForTest(): Boolean = spinning

    /** For the tests: one frame of the physics, without a clock to wait for. */
    internal fun stepSpinForTest(seconds: Double) {
        spin = WorldSpin.step(spin, seconds)
        wound =
            if (WorldSpin.resting(spin)) 0L
            else (-spin.degrees * windSense() * Hemisphere.MS_PER_DEGREE).toLong()
        spinning = !WorldSpin.resting(spin)
        invalidate()
    }

    /** For the tests: let it go from here, at this many degrees a second. */
    internal fun letGoForTest(rateDegPerSec: Double) = letGo(rateDegPerSec)

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
        lightDisc?.recycle()
        shadow?.recycle()
        veil?.recycle()
        dayDisc = null
        nightDisc = null
        cloudDisc = null
        lightDisc = null
        shadow = null
        veil = null
        bakedFor = null
        maskedFor = null
        veiledFor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pinging = false
        removeCallbacks(pinger)
        discard()
        // And the things kept between bakes, which are several megabytes
        // a face that is not on screen has no use for.
        disc = null
        for (i in mapPixelsHeld.indices) {
            mapPixelsHeld[i] = null
            mapPixelsFor[i] = null
        }
        for (i in scratch.indices) scratch[i] = null
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
        val disc = projection(size)
        val count = size * size
        // The three discs' pixels, written over each bake rather than
        // allocated for it: a megabyte each, sixty times a second, was a
        // garbage collection every few frames of spin. Pixels off the
        // world are never written and so stay the nothing they started
        // as.
        val lit = scratch(0, count)
        val dark = scratch(1, count)
        val dayPixels = mapPixels(day, 0)
        val nightPixels = mapPixels(night, 1)
        // And the satellite's picture, if there is one, through the same
        // inverse projection — a third disc rather than a second pass,
        // because working out which place a point of the disc is takes
        // longer than reading three maps at it.
        val sky = clouds
        val veil = if (sky == null) null else scratch(2, count)
        val skyPixels = if (sky == null) null else mapPixels(sky, 2)
        // And last night's lights, through the same projection again: a
        // fourth disc, drawn on the dark side only — see [drawWorld].
        val lamps = lights
        val glow = if (lamps == null) null else scratch(3, count)
        val cleanLights = lamps?.clean
        val rawLights = lamps?.raw
        // On the globe the turn is baked in; on the flat views it is not,
        // and the disc is turned when it is drawn.
        val turn = if (view == Hemisphere.View.GLOBE) quantise(spinDeg) else 0.0
        for (at in 0 until count) {
            if (!disc.onWorld(at)) continue
            val u = disc.u(at, turn)
            val v = disc.v[at].toDouble()
            lit[at] = sample(dayPixels, day.width, day.height, u, v)
            dark[at] = sample(nightPixels, night.width, night.height, u, v)
            if (veil != null && skyPixels != null && sky != null) {
                veil[at] = SatelliteClouds.tint(
                    sample(skyPixels, sky.width, sky.height, u, v),
                    disc.rho[at].toDouble()
                )
            }
            if (glow != null && lamps != null) {
                glow[at] = NightLights.pixel(
                    lamps.mode,
                    cleanLights?.at(u, v) ?: 0,
                    rawLights?.at(u, v) ?: 0,
                    disc.rho[at].toDouble(),
                    lamps.residueFloor
                )
            }
        }
        dayDisc = into(dayDisc, lit, size)
        nightDisc = into(nightDisc, dark, size)
        cloudDisc = if (veil == null) {
            cloudDisc?.recycle()
            null
        } else {
            into(cloudDisc, veil, size)
        }
        lightDisc = if (glow == null) {
            lightDisc?.recycle()
            null
        } else {
            into(lightDisc, glow, size)
        }
        bakedFor = key
        bakes++
    }

    /** The lights of last night, projected, for the dark side. */
    private var lightDisc: Bitmap? = null

    /**
     * The pixels written into [existing] if it is a disc of this size, or
     * into a new one. The discs used to be created afresh from their
     * pixels each bake, which is another three megabytes a frame.
     */
    private fun into(existing: Bitmap?, pixels: IntArray, size: Int): Bitmap {
        val disc = existing?.takeIf {
            !it.isRecycled && it.isMutable && it.width == size && it.height == size
        } ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        disc.setPixels(pixels, 0, size, 0, 0, size, size)
        return disc
    }

    /** The scratch pixels for one disc, kept between bakes. */
    private val scratch = arrayOfNulls<IntArray>(4)

    private fun scratch(slot: Int, count: Int): IntArray {
        scratch[slot]?.let { if (it.size == count) return it }
        return IntArray(count).also { scratch[slot] = it }
    }

    /**
     * The inverse projection, kept — see [DiscProjection], which says why.
     * One per view and size, which is one, since neither changes while
     * anybody is looking.
     */
    private var disc: DiscProjection? = null

    private fun projection(size: Int): DiscProjection {
        disc?.let { if (it.view == view && it.size == size) return it }
        return DiscProjection.of(view, size).also {
            disc = it
            projections++
        }
    }

    /**
     * A map's pixels, read out once rather than once per bake.
     *
     * Three maps, half a million pixels each, copied out of their bitmaps
     * every time the world turned a degree and a half. Kept by identity:
     * the clouds are a different bitmap when they are a different day's.
     */
    private val mapPixelsFor = arrayOfNulls<Bitmap>(3)
    private val mapPixelsHeld = arrayOfNulls<IntArray>(3)

    private fun mapPixels(map: Bitmap, slot: Int): IntArray {
        mapPixelsHeld[slot]?.let { if (mapPixelsFor[slot] === map) return it }
        return IntArray(map.width * map.height).also {
            map.getPixels(it, 0, map.width, 0, 0, map.width, map.height)
            mapPixelsFor[slot] = map
            mapPixelsHeld[slot] = it
        }
    }

    /** For the tests: how many times the world has been painted into the disc. */
    private var bakes = 0

    internal fun bakesForTest(): Int = bakes

    /** For the tests: how many times the projection itself was worked out. */
    private var projections = 0

    internal fun projectionsForTest(): Int = projections

    /**
     * The globe is redrawn every degree and a half, which is six minutes.
     *
     * It used to be every six degrees while the world was moving, because
     * a projection of a quarter of a million points cost more than a frame
     * and sixty of them a second was not something the thread that draws
     * could afford: the world stuttered exactly while it was being moved,
     * and came home through a series of stills. The projection is kept
     * now — see [DiscProjection] — and a frame of spin is a subtraction
     * per pixel, so the moving world is drawn at the same step as the
     * still one.
     */
    private fun quantise(spinDeg: Double): Double {
        val step = 1.5
        return Math.round(spinDeg / step).toDouble() * step
    }

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
        if (ground) canvas.drawColor(SPACE)

        val ms = nowMs()
        val sub = Hemisphere.subsolar(ms)
        val subLat = sub[0]
        val subLon = sub[1]
        // Nothing is drawn round the rim where the rim is not a bearing
        // — see [Hemisphere.hasRimScale]. On the ball the ring was out by
        // an hour and a half and labelled longitudes on the far side of
        // the world, and the notches were fifteen-degree meridians drawn
        // fifteen degrees apart on a projection where they are not.
        val rimScale = Hemisphere.hasRimScale(view)
        // And the world takes the room the ring is not using — but only
        // as much as leaves the sun somewhere to stand. At 0.47 the sun
        // mark was off the side of the view entirely, which is what
        // turning the ring off has quietly done on every view since the
        // switch existed.
        //
        // Opened out with a pinch, and the ring gives way as it goes: it
        // is furniture and the earth is the instrument. Its own radius
        // does not move — a scale that slides about is not a scale — it
        // simply fades, and is gone before the world reaches it.
        val fade = if (rimScale) Hemisphere.ringFade(zoom) else 0f
        val ring = hourRing && rimScale && fade > 0.01f
        val ringR = min(w, h) * Hemisphere.WORLD_RINGED
        val r = min(w, h) *
            Hemisphere.worldRadius(
                zoom, hourRing && rimScale,
                // The world only takes the last twentieth when there is
                // genuinely nothing standing in it. The moon lives out
                // there too, and a moon half off the top of the screen is
                // a worse trade than a slightly smaller earth.
                sunOutside = showSun || (showMoon && rimScale)
            )
        val cx = w / 2f
        val cy = h / 2f

        // The weather of the day the world is wound to, which on a globe
        // standing at now is simply today's. Asked once a second rather
        // than once a frame: the answer is a lookup on disk, and sixty of
        // those a second on the thread that draws is sixty too many.
        if (cloudsOn != null || lightsOn != null) {
            val day = ms / 86_400_000L
            val at = android.os.SystemClock.uptimeMillis()
            if (day != cloudsAskedDay || at - cloudsAskedAt >= CLOUDS_ASK_MS) {
                cloudsAskedDay = day
                cloudsAskedAt = at
                cloudsOn?.let { clouds = it(ms) }
                cloudsDayOn?.let {
                    val worn = it(ms)
                    cloudsWorn = worn?.day
                    cloudsOff = worn?.offDays ?: 0
                }
                lightsOn?.let { lights = it(ms) }
            }
        }
        bake(-subLon + sunAt)
        drawWorld(canvas, cx, cy, r, subLat, subLon, ms)
        if (showSun) drawSunMark(canvas, cx, cy, r, ring, ringR)
        if (showMoon && rimScale) drawMoonMark(canvas, cx, cy, r, ring, ringR, ms)
        if (ring) drawAlarms(canvas, cx, cy, ringR, fade)
        if (located) drawYou(canvas, cx, cy, r, subLat, subLon, ring)
        if (ring) drawHours(canvas, cx, cy, ringR, fade)
        drawLegend(canvas, cx, cy, r)
    }

    /**
     * A word on the face when what it wears is not what it seems: last
     * night's lights further behind than is ordinary — see
     * [NightLights.overdue] — or clouds of another day than the one the
     * world is wound to, because the phone has no picture of that one.
     *
     * Only then. The settings say which night the lights are of and which
     * days of clouds the phone has every time; the globe says so only when
     * the honest answer is "not the day you think", because a date on a
     * clock face is a thing that is read, and a date that is always there
     * stops being read.
     */
    private fun drawLegend(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val text = legendText() ?: return
        ink.typeface = PRINT
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = r * 0.062f
        val y = cy + r * 0.80f
        val half = ink.measureText(text) / 2f + ink.textSize * 0.6f
        fill.color = 0xB0000000.toInt()
        rim.set(cx - half, y - ink.textSize * 0.95f, cx + half, y + ink.textSize * 0.40f)
        canvas.drawRoundRect(rim, ink.textSize * 0.6f, ink.textSize * 0.6f, fill)
        ink.color = theme.numeral
        ink.alpha = 220
        canvas.drawText(text, cx, y, ink)
        ink.alpha = 255
    }

    /** For the tests: the word on the face, if there is one this frame. */
    internal fun legendForTest(): String? = legendText()

    /** What the legend says, or null for a face with nothing to own up to. */
    private fun legendText(): String? {
        val phrases = ArrayList<String>(2)
        lights?.let { sky ->
            val parts = ArrayList<String>(2)
            sky.clean?.let {
                if (NightLights.overdue(raw = false, lagDays = it.lagDays)) {
                    parts += context.getString(R.string.lights_clean_short, dayText(it.day))
                }
            }
            sky.raw?.let {
                if (NightLights.overdue(raw = true, lagDays = it.lagDays)) {
                    parts += context.getString(R.string.lights_raw_short, dayText(it.day))
                }
            }
            if (parts.isNotEmpty()) {
                phrases += context.getString(R.string.hemisphere_lights_legend, parts.joinToString(" · "))
            }
        }
        // The clouds, whenever they are not the day's own: a globe wound
        // to a day the phone has no photograph of is wearing another
        // day's weather, and says which.
        if (clouds != null && cloudsOff != 0) {
            cloudsWorn?.let { phrases += context.getString(R.string.hemisphere_clouds_legend, dayText(it)) }
        }
        return if (phrases.isEmpty()) null else phrases.joinToString(" · ")
    }

    /** A day of the lights as a person reads it: the day and the month. */
    private fun dayText(day: String): String {
        val ms = NightLights.dayMs(day) ?: return day
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = ms
        return android.text.format.DateFormat.format("d MMM", utc).toString()
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
        canvas: Canvas, cx: Float, cy: Float, r: Float, subLat: Double, subLon: Double, ms: Long
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
        // The moon's light over the half of the dark side the moon is
        // above — see [Moonlight]. Over the night map and its scrim, and
        // under the lights, so the cities stand on the moonlit ground
        // rather than being washed by it. In the same frame as the night
        // mask, so it needs no turning, and inside the layer, so the
        // mask cuts it to the solar night as it cuts everything else.
        moonVeil(ms)?.let {
            maskSrc.set(0, 0, it.width, it.height)
            canvas.drawBitmap(it, maskSrc, dst, blit)
        }
        // Last night's lights go on over the scrim, not under it: they
        // are light, and a scrim over a light is a dimmer. Inside the
        // layer, so the same mask that cuts the night map to where the
        // sun has set cuts them too — a city lit in daylight is a photo
        // of a city, not a city.
        lightDisc?.let {
            canvas.save()
            canvas.rotate(-spin, cx, cy)
            canvas.drawBitmap(it, src, dst, blit)
            canvas.restore()
        }
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

    /**
     * Where the moon is up, as a tinted veil over the disc, kept until the
     * moon or the sun has moved — the same size and the same arithmetic
     * as [nightMask], asked about the other light.
     */
    private fun moonVeil(ms: Long): Bitmap? {
        if (!moonlight) return null
        val lit = SolarTime.moonIllumination(ms)
        if (Moonlight.alpha(lit) * 255 < 1.0) return null
        val moon = Hemisphere.sublunarPoint(ms)
        // The mask's frame is longitude from the sun, so the moon goes
        // into it the same way: how far east of the sun it stands.
        val lonFromSun = Hemisphere.wrap(moon[1] - Hemisphere.subsolar(ms)[1])
        val key = "$view/${Math.round(moon[0] * 4.0)}/${Math.round(lonFromSun * 2.0)}" +
            "/${Math.round(sunAt * 2.0)}/${Math.round(lit * 100.0)}"
        if (key == veiledFor && veil != null) return veil
        val size = MASK
        val pixels = IntArray(size * size)
        val half = size / 2.0
        for (py in 0 until size) {
            val y = (py + 0.5 - half) / half
            for (px in 0 until size) {
                val x = (px + 0.5 - half) / half
                val place = Hemisphere.unproject(view, x, y, sunAt) ?: continue
                val cosZ = Hemisphere.cosZenith(place[0], place[1] - lonFromSun, moon[0])
                pixels[py * size + px] = Moonlight.pixel(lit, cosZ)
            }
        }
        veil?.recycle()
        veil = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        veiledFor = key
        return veil
    }

    private var veil: Bitmap? = null
    private var veiledFor: String? = null

    private var shadow: Bitmap? = null
    private var maskedFor: String? = null
    private val maskSrc = Rect()
    private val cut = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    /**
     * The sun, outside the world, where it has been pinned.
     *
     * It stands in the ring where the twelve would be, and the twelve is
     * not drawn — because they are the same thing. Noon *is* under the
     * sun; that is the definition the whole face is built on, and drawing
     * both was two marks fighting over one bearing.
     */
    private fun drawSunMark(
        canvas: Canvas, cx: Float, cy: Float, r: Float, ring: Boolean, ringR: Float
    ) {
        val a = Math.toRadians(sunAt)
        // Outside the ring when there is one, and just off the world when
        // there is not. 1.10 was the old answer and it was wrong: with the
        // ring off the disc grows, and 1.10 of the grown radius put the
        // sun past the edge of the view.
        val at = if (ring) ringR * 1.22f else r * 1.06f
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
     * The moon, on the same rim, at the hour its own meridian keeps.
     *
     * Smaller than the sun and grey, and it carries its phase: the disc is
     * drawn as the lit fraction actually is tonight, so a new moon is a
     * ring with nothing in it and a full one is a full circle. The mark's
     * *position* already says the same thing — it is the angle between the
     * two marks — and having it said twice is what makes it read as an
     * instrument rather than as a decoration.
     */
    private fun drawMoonMark(
        canvas: Canvas, cx: Float, cy: Float, r: Float, ring: Boolean, ringR: Float, ms: Long
    ) {
        val a = Math.toRadians(Hemisphere.bearingOfTime(view, Hemisphere.moonHour(ms)) + sunAt)
        val at = if (ring) ringR * 1.22f else r * 1.06f
        val x = cx + (cos(a) * at).toFloat()
        val y = cy - (sin(a) * at).toFloat()
        val lit = SolarTime.moonIllumination(ms).toFloat()
        line.color = MOON
        line.alpha = 200
        line.strokeWidth = r * 0.008f
        canvas.drawCircle(x, y, r * 0.042f, line)
        if (lit > 0.02f) {
            fill.color = MOON
            fill.alpha = (255 * lit).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r * 0.042f, fill)
            fill.alpha = 255
        }
        line.alpha = 255
    }

    /**
     * What is armed, marked on the ring where it will ring.
     *
     * The same claim the dial makes on its own rim, on the one face that
     * has a rim of hours rather than of half-days: an alarm at seven in
     * the morning and one at seven at night are two different places
     * here, which on a twelve-hour dial they are not.
     */
    private fun drawAlarms(canvas: Canvas, cx: Float, cy: Float, r: Float, fade: Float) {
        if (alarmsAt.isEmpty()) return
        line.color = ALARM
        line.strokeWidth = r * 0.014f
        line.alpha = (230 * fade).toInt().coerceIn(0, 255)
        val ringAt = r * 1.06f
        // Civil times on a solar ring, so they have to be converted: the
        // numbers round this rim are what the sun says over your own head,
        // and seven o'clock on a phone is not seven o'clock by the sun
        // anywhere except on your zone's own meridian at two moments in
        // the year. Half an hour of Spain is most of a numeral.
        val toSolar = SolarTime.offsetMs(longitude, nowMs())
        for (ms in alarmsAt) {
            val solar = ms + toSolar
            val hours = ((solar % CivilDays.DAY_MS + CivilDays.DAY_MS) % CivilDays.DAY_MS) /
                3_600_000.0
            val a = Math.toRadians(Hemisphere.bearingOfTime(view, hours) + sunAt)
            val dx = cos(a).toFloat()
            val dy = -sin(a).toFloat()
            canvas.drawLine(
                cx + dx * ringAt, cy + dy * ringAt,
                cx + dx * (ringAt + r * 0.055f), cy + dy * (ringAt + r * 0.055f), line
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
        canvas: Canvas, cx: Float, cy: Float, r: Float, subLat: Double, subLon: Double,
        ring: Boolean
    ) {
        val on = Hemisphere.project(view, latitude, longitude, subLon, sunAt)
        // Behind the world, on the globe.
        if (on[2] < 0.0) return
        val x = cx + (on[0] * r).toFloat()
        val y = cy + (on[1] * r).toFloat()
        drawPing(canvas, x, y, r)
        fill.color = YOU
        canvas.drawCircle(x, y, r * 0.024f, fill)
        line.color = YOU
        line.strokeWidth = r * 0.010f
        canvas.drawCircle(x, y, r * 0.055f, line)
        // And the two meridians: yours, out to the rim, and the one your
        // clock is actually keeping.
        //
        // Drawn at every zoom now. They used to go with the ring of
        // hours, on the argument that a line reaching for a scale that is
        // not there is worse than no line — which is true of a line and
        // false of these two, because they are not pointing at the scale.
        // They are the hands. Somebody opening the world out watched both
        // of them disappear a fifth of the way into the pinch and quite
        // reasonably reported it as a bug.
        val len = kotlin.math.hypot(on[0], on[1])
        if (len > 0.02) {
            beads(
                canvas, cx, cy, r,
                (on[0] / len).toFloat(), (on[1] / len).toFloat(),
                YOU, YOURS_FROM, (kotlin.math.hypot(x - cx, y - cy) / r)
            )
        }
        drawOfficial(canvas, cx, cy, r, subLon)
    }

    /**
     * A meridian, as a string of beads from the middle out to the rim.
     *
     * Dashes before, and a dash is a piece of a line: two dashed lines a
     * few degrees apart read as one broken line rather than as two
     * claims. Round beads read as marks laid along a bearing, which is
     * what these are — and they are what somebody asked for by name.
     *
     * [from] is where the string starts, as a share of the radius, so a
     * bead is never drawn under the dot at the middle of it.
     */
    private fun beads(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        ux: Float, uy: Float, colour: Int, from: Float, skipTo: Float = 0f
    ) {
        fill.color = colour
        var t = from
        while (t < 1f) {
            // Nothing under the ring round the dot: a bead there is a
            // bead on top of the one mark this face cannot spare.
            if (skipTo <= 0f || kotlin.math.abs(t - skipTo) > BEAD_CLEAR) {
                fill.alpha = 210
                canvas.drawCircle(cx + ux * r * t, cy + uy * r * t, r * BEAD, fill)
            }
            t += BEAD_STEP
        }
        fill.alpha = 255
    }

    /**
     * The radar ping under the dot.
     *
     * One ring a second, growing out and fading as it goes, because the
     * red dot is four pixels of red on a photograph of the earth and the
     * eye does not find it. It is the oldest trick there is for saying
     * "here" on a map of the world and it costs nothing to be honest
     * about: this is not a measurement of anything, it is a thing that
     * moves so that a thing that does not gets looked at.
     */
    private fun drawPing(canvas: Canvas, x: Float, y: Float, r: Float) {
        val into = ((android.os.SystemClock.uptimeMillis() % PING_MS).toFloat() / PING_MS)
        line.color = YOU
        line.strokeWidth = r * 0.006f
        for (ring in 0 until PINGS) {
            val at = (into + ring.toFloat() / PINGS) % 1f
            line.alpha = (150 * (1f - at) * (1f - at)).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r * (0.055f + at * PING_REACH), line)
        }
        line.alpha = 255
    }

    /**
     * And the meridian your clock is actually keeping.
     *
     * The red line is your longitude, so the hour it points at is your
     * *solar* time — the sun over your own head. Nobody's clock says that.
     * A time zone is a promise that everybody inside it will use the sun
     * of one line of longitude, and the blue line is that line: where the
     * hour on your phone is genuinely noon. The gap between the two is the
     * whole of what is strange about official time, drawn rather than
     * explained, and on this face it is a few degrees or most of an hour
     * depending on which side of a zone you live on.
     *
     * Dotted by drawing the dots, not by a path effect: a dash is one of
     * the things a hardware canvas quietly declines, and a line that is
     * dotted on one phone and solid on another is two different drawings.
     */
    private fun drawOfficial(canvas: Canvas, cx: Float, cy: Float, r: Float, subLon: Double) {
        val at = Hemisphere.project(
            view, latitude, Hemisphere.zoneMeridian(zoneOffsetMs()), subLon, sunAt
        )
        val len = kotlin.math.hypot(at[0], at[1])
        if (len < 1e-6) return
        beads(
            canvas, cx, cy, r,
            (at[0] / len).toFloat(), (at[1] / len).toFloat(), OFFICIAL, OFFICIAL_FROM
        )
    }

    /**
     * The phone's offset from Greenwich now, summer time included.
     *
     * Read here rather than passed in because it is a fact about the
     * phone and not a setting; overridable so a test can stand somewhere
     * other than wherever it is running.
     */
    private fun zoneOffsetMs(): Int =
        zoneOffset ?: java.util.TimeZone.getDefault().getOffset(nowMs())

    /** For the tests: pretend to be in this zone. */
    internal var zoneOffset: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The twenty-four hours round the outside.
     *
     * Solar hours: what the sun says where the dot is, not what a
     * government says. Noon is under the sun by construction, which is
     * the whole of the arithmetic — see [Hemisphere.hourAt].
     */
    private fun drawHours(canvas: Canvas, cx: Float, cy: Float, r: Float, fade: Float) {
        val ring = r * 1.06f
        line.color = theme.minorTick
        line.strokeWidth = r * 0.008f
        ink.typeface = PRINT
        ink.textAlign = Paint.Align.CENTER
        ink.textSize = r * 0.088f * fade
        for (hour in 0..23) {
            val a = Math.toRadians(Hemisphere.bearingOfHour(view, hour) + sunAt)
            val dx = cos(a).toFloat()
            val dy = -sin(a).toFloat()
            val major = hour % 6 == 0
            line.alpha = ((if (major) 235 else 120) * fade).toInt().coerceIn(0, 255)
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
            ink.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val at = r * 1.22f
            canvas.drawText(
                "$hour",
                cx + dx * at, cy + dy * at + ink.textSize * 0.35f, ink
            )
        }
        line.alpha = 255
        ink.alpha = 255
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

        /**
         * And the meridian your clock keeps, which is a different colour
         * because it is a different claim: the red one is where you are
         * and the blue one is where your hour comes from.
         */
        const val OFFICIAL = 0xFF5AA9FF.toInt()

        /**
         * How long a finger may have been still before letting go stops
         * counting as a throw.
         *
         * Somebody who drags the world round and then holds it there for
         * a moment before lifting has not thrown it, whatever the last
         * measured speed says.
         */
        const val STALE_MS = 90L

        /** How often the weather is looked up again while the view is drawn. */
        const val CLOUDS_ASK_MS = 1000L

        /** The moon on the rim, and an armed alarm on it. */
        const val MOON = 0xFFD8DEEA.toInt()
        const val ALARM = 0xFF7CE08A.toInt()

        /** How far out each string of beads starts. */
        const val OFFICIAL_FROM = 0.10f
        const val YOURS_FROM = 0.16f

        /** And how big a bead is, and how far apart they sit. */
        const val BEAD = 0.011f
        const val BEAD_STEP = 0.062f

        /** How close to the dot's own ring a bead may come. */
        const val BEAD_CLEAR = 0.05f

        /**
         * The ping: how long one ring takes to go out, how many are on
         * their way at once, and how far they get.
         */
        const val PING_MS = 2200L
        const val PINGS = 2
        const val PING_REACH = 0.13f

        /** Twenty a second, which is plenty for a ring growing slowly. */
        const val PING_FRAME_MS = 50L

        val PRINT: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
}
