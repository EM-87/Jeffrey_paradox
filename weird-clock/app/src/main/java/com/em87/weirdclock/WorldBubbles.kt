package com.em87.weirdclock

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.hypot

/**
 * World clocks as bubbles: mini dials floating over the main clock.
 *
 * Newly added ones dock in an orderly column and stay put — until you drag
 * one and give it momentum, or a moving bubble crashes into it. Then they
 * bounce off the screen edges, off each other and off the main dial (shrink
 * the dial and the bubbles get more room).
 *
 * Lifted out of MainActivity, which had grown past the point where anyone
 * could see the whole of it — a duplicated countdown handover had already
 * hidden in there for three versions. This is a move and nothing else: the
 * physics, the constants and the order of every operation are as they were.
 *
 * What it needs from its host it is handed: the layer to live in, a way to
 * ask where the main dial is and whether it is currently an obstacle, and
 * which way is down.
 */
class WorldBubbles(
    private val host: Context,
    private val prefs: SharedPreferences,
    private val chimePlayer: ChimePlayer,
    private val mainDial: () -> ClockView?,
    private val dialIsObstacle: () -> Boolean,
    private val gravityX: () -> Float,
    private val gravityY: () -> Float
) {

    /** Set once the home page has been bound; null before that. */
    var layer: FrameLayout? = null
        set(value) {
            field = value
            // Two fingers on two bubbles is two children of this layer each
            // getting their own pointer, which a ViewGroup only does while
            // it is splitting motion events. On by default and turnable off
            // by a theme, which is too quiet a way to lose a two-player
            // game.
            value?.isMotionEventSplittingEnabled = true
        }

    /**
     * One floating city.
     *
     * [clock] is null on a face with no hands: the bubble is a readout
     * there, and everything that only makes sense against a moving hand —
     * seizing, running backwards, shedding a hand on a hard knock — has
     * nothing to act on. The physics, the drag and the docking all work
     * on [view] and never had an opinion about what was inside it.
     */
    private inner class Bubble(val tzId: String, val view: View, val clock: ClockView?) {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var moving = false

        /** Its velocity at the end of the last step, so this one has a delta. */
        var prevVx = 0f
        var prevVy = 0f

        /** What being thrown about has done to it so far. */
        val damage = BubbleDamage()

        /** True while a finger has hold of it — see [DialMembrane]. */
        var dragged = false
        var sizePx = 0f

        fun centerX() = x + sizePx / 2f
        fun centerY() = y + sizePx / 2f
        fun place() {
            view.translationX = x
            view.translationY = y
        }
    }

    private val bubbles = ArrayList<Bubble>()
    private var tzsApplied: List<String> = emptyList()

    /** True while anything is still in flight, so the scene counts as untidy. */
    fun anyMoving(): Boolean = bubbles.any { it.moving }

    /** For the tests: the little dials themselves. */
    internal fun clocksForTest(): List<ClockView> = bubbles.mapNotNull { it.clock }

    /** For the tests: the little readouts, on the face that has those. */
    internal fun readoutsForTest(): List<DigitalClockView> =
        bubbles.mapNotNull { it.view as? DigitalClockView }

    private fun selectedWorldTzs(): List<String> {
        if (!prefs.getBoolean(Prefs.WORLD_CLOCK, false)) return emptyList()
        val set = prefs.getStringSet(Prefs.WORLD_TZS, null)
        if (set != null) return set.toList().sorted()
        // Migration from the old single-city preference.
        return listOf(prefs.getString(Prefs.WORLD_TZ, "UTC") ?: "UTC")
    }

    fun rebuild() {
        val layer = this.layer ?: return
        // At full zoom about six bubbles fit; the picker enforces the cap.
        val tzs = selectedWorldTzs().take(6)
        if (tzs == tzsApplied) return
        tzsApplied = tzs
        layer.removeAllViews()
        bubbles.clear()
        val density = host.resources.displayMetrics.density
        val size = (108 * density).toInt()
        val hands = Face.of(prefs).hands
        for (tz in tzs) {
            val city = tz.substringAfterLast('/').replace('_', ' ')
            if (!hands) {
                // On a face with no hands a bubble is a small readout, in
                // the same idiom the big one is drawn in: a dial floating
                // over a screenful of digits is two clocks disagreeing
                // about what kind of clock this is.
                val panel = DigitalClockView(host).apply {
                    zone = TimeZone.getTimeZone(tz)
                    caption = city
                    chip = true
                    showSeconds = false
                    showDate = false
                    yautja = Yautja.face(host)
                }
                layer.addView(panel, FrameLayout.LayoutParams(size, size))
                val floating = Bubble(tz, panel, null)
                floating.sizePx = size.toFloat()
                attachTouch(floating)
                bubbles.add(floating)
                continue
            }
            val clock = ClockView(host).apply {
                touchHandsEnabled = false
                pinchZoomEnabled = false
                shakeDropEnabled = false
                showDate = false
                // A clock whose hands are on the floor spells the time out
                // in digits underneath, which is the right answer on the
                // big dial and absurd on a bubble: a hundred-pixel disc
                // with a broken watch inside does not also need a readout.
                showDigitalReadout = false
                // The city rides inside the dial, where the date sits on the
                // main clock — no caption hanging off the bubble.
                dialLabel = city
            }
            clock.timeZone = TimeZone.getTimeZone(tz)
            layer.addView(clock, FrameLayout.LayoutParams(size, size))
            val bubble = Bubble(tz, clock, clock)
            bubble.sizePx = size.toFloat()
            attachTouch(bubble)
            bubbles.add(bubble)
        }
        dock()
    }

    /**
     * Whether the little clocks carry second hands of their own.
     *
     * Six sweeping hands on six bubbles is six things moving on a screen
     * that already has three, and on a clock two centimetres across a
     * second hand is a hair going round: it says nothing you could read
     * and costs a frame every sixteen milliseconds to say it. Its own
     * switch rather than the dial's, because the big face and the little
     * ones are asking different questions — the big one can be read to the
     * second and these cannot.
     */
    var secondHands = true
        set(value) {
            field = value
            for (b in bubbles) b.clock?.showSecondHand = value
        }

    /**
     * What the little readouts are made of, on a face with no hands.
     *
     * Read from the same settings the big one uses rather than copied off
     * it: the big face is not always there to copy from, and two clocks on
     * one screen made of different digits is the thing this whole
     * arrangement exists to avoid.
     */
    var digitStyle: DigitStyle = DigitStyle.SEGMENT
    var digitScript: DigitScript = DigitScript.ARABIC
    var hour24 = true
    var segmentWeight = 0.055f
    var segmentGhosts = true

    /** The bubbles wear whatever the main dial is wearing. */
    fun applyStyle(cv: ClockView) {
        for (b in bubbles) {
            val panel = b.view as? DigitalClockView
            if (panel != null) {
                panel.theme = cv.theme
                panel.style = digitStyle
                panel.script = digitScript
                panel.hour24 = hour24
                panel.weight = segmentWeight
                panel.ghosts = segmentGhosts
                continue
            }
            b.clock?.theme = cv.theme
            b.clock?.hoursOnDial = cv.hoursOnDial
            b.clock?.dialShape = cv.dialShape
            b.clock?.numeralStyle = cv.numeralStyle
            b.clock?.showSecondHand = secondHands
        }
    }

    /**
     * Parks the bubbles clear of the dial: up to three centered in a row
     * above the clock, the rest in a second row below it. One bubble sits
     * dead center of its row, two straddle it symmetrically, and so on.
     */
    fun dock() {
        val layer = this.layer ?: return
        layer.post {
            val density = host.resources.displayMetrics.density
            val gap = 8 * density
            val size = bubbles.firstOrNull()?.sizePx ?: return@post
            val top = bubbles.take(3)
            val bottom = bubbles.drop(3)

            fun layoutRow(row: List<Bubble>, y: Float) {
                if (row.isEmpty()) return
                val rowW = row.size * size + (row.size - 1) * gap
                val startX = ((layer.width - rowW) / 2f).coerceAtLeast(4 * density)
                for ((i, b) in row.withIndex()) {
                    b.moving = false
                    b.vx = 0f
                    b.vy = 0f
                    b.x = startX + i * (size + gap)
                    b.y = y
                    b.place()
                }
            }
            layoutRow(top, 8 * density)
            layoutRow(bottom, layer.height - size - 64 * density)
        }
    }

    /** A knock on the main dial shakes every bubble loose too. */
    fun free() {
        for (b in bubbles) {
            b.moving = true
            b.vx = (Math.random().toFloat() - 0.5f) * 400f
            b.vy = -Math.random().toFloat() * 250f
        }
    }

    /** Freezes (or reverses) a fraction of the world clocks, at random. */
    fun seize(fraction: Float) {
        val count = (bubbles.size * fraction).toInt().coerceAtLeast(1)
        for (b in bubbles.shuffled().take(count)) {
            if (b.clock?.timeScale == 1f) {
                b.clock?.timeScale = if (Math.random() < 0.5) 0f else -1f
            }
        }
    }

    /** A third of them lose their hands, as the main dial does. */
    fun knockSomeHandsOff() {
        for (b in bubbles.shuffled().take((bubbles.size + 2) / 3)) {
            b.clock?.knockHandsOff()
        }
    }

    fun heal() {
        for (b in bubbles) {
            b.clock?.timeScale = 1f
            b.damage.heal()
        }
    }

    /** The panic button reaches the bubbles' own fallen hands as well. */
    fun reassembleAll() {
        for (b in bubbles) b.clock?.reassembleAll()
    }

    /**
     * A shove in the direction the phone was struck. Only bubbles already in
     * flight take it; the parked ones need a real knock to come loose.
     */
    fun jolt(devX: Float, devY: Float) {
        for (b in bubbles) {
            if (!b.moving) continue
            b.vx += -devX * 26f
            b.vy += devY * 26f
        }
    }

    /**
     * Growing the main dial can swallow a bubble sitting too close: shove it
     * out with an impulse proportional to how fast the dial is growing.
     */
    fun kickFromDial() {
        val layer = this.layer ?: return
        val r = mainDial()?.currentDialRadius() ?: return
        val dialCx = layer.width / 2f
        val dialCy = layer.height / 2f
        for (b in bubbles) {
            val dx = b.centerX() - dialCx
            val dy = b.centerY() - dialCy
            val d = hypot(dx, dy)
            val minD = r + b.sizePx / 2f
            if (d < minD && d > 0.001f) {
                b.moving = true
                val nx = dx / d
                val ny = dy / d
                val overlap = minD - d
                b.x += nx * overlap
                b.y += ny * overlap
                b.vx += nx * overlap * 8f
                b.vy += ny * overlap * 8f
                b.place()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    /**
     * A finger's position in the layer's own coordinates, for any pointer.
     *
     * getRawX(index) only arrives in API 29 and this app goes back to 24,
     * so the offset between the view's coordinates and the screen's is
     * taken from pointer zero — where the two are known to correspond —
     * and applied to whichever pointer is being asked about.
     */
    private fun rawXOf(e: MotionEvent, index: Int): Float = e.getX(index) + (e.rawX - e.getX(0))

    private fun rawYOf(e: MotionEvent, index: Int): Float = e.getY(index) + (e.rawY - e.getY(0))

    private fun attachTouch(b: Bubble) {
        val drag = BubbleDrag()
        b.view.setOnTouchListener { _, e ->
            val index = e.actionIndex
            val id = e.getPointerId(index)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (drag.down(id, rawXOf(e, index), rawYOf(e, index), SystemClock.uptimeMillis())) {
                        b.dragged = true
                        b.moving = false
                        b.vx = 0f
                        b.vy = 0f
                        layer?.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Every pointer in the gesture arrives in one event, so
                    // the bubble has to go looking for its own rather than
                    // taking the first: two bubbles held at once would
                    // otherwise both chase whichever finger went down first.
                    val mine = e.findPointerIndex(drag.pointer)
                    if (mine >= 0) {
                        drag.move(
                            drag.pointer,
                            rawXOf(e, mine), rawYOf(e, mine),
                            SystemClock.uptimeMillis()
                        )?.let { step ->
                            b.x += step.dx
                            b.y += step.dy
                            b.vx = b.vx * 0.6f + step.vx * 0.4f
                            b.vy = b.vy * 0.6f + step.vy * 0.4f
                            b.place()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (drag.up(id)) { b.dragged = false; release(b) }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    drag.cancel()
                    b.dragged = false
                    release(b)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * A real fling turns a bubble loose; a gentle drop leaves it parked
     * where you put it.
     */
    private fun release(b: Bubble) {
        if (hypot(b.vx, b.vy) > 260f) {
            b.moving = true
        } else {
            b.vx = 0f
            b.vy = 0f
        }
    }

    /** Where each hand's tip was last frame, for working out its speed. */
    private var lastHands: List<FloatArray> = emptyList()

    /**
     * A bubble meeting one hand.
     *
     * The bubble is pushed clear along the perpendicular and given the part
     * of the hand's own speed that points that way — a hand sweeping past
     * carries things with it, and a hand coming back on its spring sends
     * them off. Standing still it is a wall, which is also right: a parked
     * hand should not suck anything anywhere.
     */
    private fun strikeWithHand(b: Bubble, bar: ClockView.HandBar, tip: FloatArray?) {
        val hit = HandStrike.contact(bar, b.centerX(), b.centerY(), b.sizePx / 2f) ?: return
        val nx = hit.nx
        val ny = hit.ny
        b.x += nx * hit.push
        b.y += ny * hit.push
        // How fast the hand is closing on the bubble at the point of
        // contact, scaled down the arm: the tip moves fastest and the boss
        // hardly at all.
        val armed = (tip?.let { (it[0] * nx + it[1] * ny) * hit.alongArm } ?: 0f)
            .coerceAtLeast(0f)
        val vn = b.vx * nx + b.vy * ny
        if (vn < 0f) {
            b.vx -= 1.85f * vn * nx
            b.vy -= 1.85f * vn * ny
        }
        if (armed > 0f) {
            b.vx += armed * nx * STROKE
            b.vy += armed * ny * STROKE
            if (armed > 200f) {
                if (!b.moving) b.moving = true
                bruise(b, armed)
                if (allowCollisionSound()) {
                    chimePlayer.playClack((armed / 1400f).coerceIn(0.08f, 1f))
                }
            }
        }
    }

    /** Rate-limits collision audio so a pile-up doesn't machine-gun. */
    private var lastCollisionSoundAt = 0L

    private fun allowCollisionSound(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastCollisionSoundAt < 60L) return false
        lastCollisionSoundAt = now
        return true
    }

    /**
     * A knock, wherever it came from: a cushion, the main dial, another
     * bubble. The ladder is the same one shaking the phone climbs, so a
     * clock battered around the table ends up in the same state as one
     * shaken there — which is the point, and was not true of either before.
     */
    private fun bruise(b: Bubble, force: Float) {
        when (b.damage.hit(abs(force), seizes = Math.random() < 0.5)) {
            BubbleDamage.Effect.SEIZE -> b.clock?.timeScale = 0f
            BubbleDamage.Effect.REVERSE -> b.clock?.timeScale = -1f
            BubbleDamage.Effect.BREAK ->
                if (b.clock?.isDisarranged() == false) b.clock.knockHandsOff()
            null -> Unit
        }
    }

    private fun cushion(v: Float) {
        if (abs(v) > 150f && allowCollisionSound()) {
            chimePlayer.playCushion((abs(v) / 1400f).coerceIn(0.08f, 1f))
        }
    }

    fun step() {
        if (bubbles.isEmpty()) return
        // A parked bubble out on the edge costs nothing to skip, but one
        // sitting on the face is in reach of the hands and has to be
        // stepped — a bubble standing still in the arc of a wound hand is
        // the whole of the shot.
        val reach = (mainDial()?.currentDialRadius() ?: 0f)
        val layerNow = this.layer
        val withinReach = layerNow != null && bubbles.any { b ->
            hypot(b.centerX() - layerNow.width / 2f, b.centerY() - layerNow.height / 2f) <
                reach + b.sizePx / 2f
        }
        if (!withinReach && bubbles.none { it.moving }) return
        val layer = this.layer ?: return
        val w = layer.width.toFloat()
        val h = layer.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dt = 0.016f
        val dialR = mainDial()?.currentDialRadius() ?: 0f
        val dialCx = w / 2f
        val dialCy = h / 2f
        // The hands, and how fast each tip is travelling — worked out from
        // where it was last frame rather than asked of the dial, because a
        // hand under a finger and a hand on its spring get their speed from
        // completely different places and the answer here is the same.
        // The hands are always solid now, ticking or wound or lying on the
        // floor: a bar of metal does not stop being one because nobody is
        // holding it.
        val hands = mainDial()?.mountedHands().orEmpty()
        val handSpeed = ArrayList<FloatArray>(hands.size)
        for ((i, bar) in hands.withIndex()) {
            val was = lastHands.getOrNull(i)
            handSpeed.add(
                if (was == null) floatArrayOf(0f, 0f)
                else floatArrayOf((bar.x2 - was[0]) / dt, (bar.y2 - was[1]) / dt)
            )
        }
        lastHands = hands.map { floatArrayOf(it.x2, it.y2) }

        for (b in bubbles) {
            if (!b.moving) {
                // Parked, but still hittable: a club does not care whether
                // the ball was going anywhere.
                for ((i, bar) in hands.withIndex()) {
                    strikeWithHand(b, bar, handSpeed.getOrNull(i))
                }
                b.place()
                continue
            }
            // Bubbles are buoyant: free ones drift against gravity, so they
            // bob toward whatever edge is currently "up" as you tilt.
            b.vx += -gravityX() * BUOYANCY * dt
            b.vy += -gravityY() * BUOYANCY * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.vx *= 0.985f
            b.vy *= 0.985f
            val r = b.sizePx / 2f
            // Screen edges: the cushions of the table.
            if (b.x < 0f) { b.x = 0f; cushion(b.vx); bruise(b, b.vx); b.vx = -b.vx * 0.9f }
            if (b.y < 0f) { b.y = 0f; cushion(b.vy); bruise(b, b.vy); b.vy = -b.vy * 0.9f }
            if (b.x + b.sizePx > w) { b.x = w - b.sizePx; cushion(b.vx); bruise(b, b.vx); b.vx = -b.vx * 0.9f }
            if (b.y + b.sizePx > h) { b.y = h - b.sizePx; cushion(b.vy); bruise(b, b.vy); b.vy = -b.vy * 0.9f }
            // The rim is a membrane rather than a wall — see
            // [DialMembrane]. Thrown at the clock a bubble bounces and the
            // clock rings; carried in by a finger it goes in; and once
            // inside it may leave whenever it likes.
            val letThrough = DialMembrane.verdict(
                hypot(b.centerX() - dialCx, b.centerY() - dialCy), dialR, b.dragged
            ) == DialMembrane.Verdict.PASS
            if (dialR > 0f && dialIsObstacle() && !letThrough) {
                val dx = b.centerX() - dialCx
                val dy = b.centerY() - dialCy
                val d = hypot(dx, dy)
                val minD = dialR + r
                if (d < minD && d > 0.001f) {
                    val nx = dx / d
                    val ny = dy / d
                    b.x += nx * (minD - d)
                    b.y += ny * (minD - d)
                    val vn = b.vx * nx + b.vy * ny
                    if (vn < 0f) {
                        if (-vn > 220f && allowCollisionSound()) {
                            chimePlayer.playBellSequence(
                                1, false, ChimePlayer.DAY_CHIME_HZ, 0.5, 0.1
                            )
                        }
                        b.vx -= 1.85f * vn * nx
                        b.vy -= 1.85f * vn * ny
                        // Hitting the big dial counts, and it is the
                        // hardest thing on the table to hit.
                        bruise(b, vn)
                    }
                }
            }
            // And the hands themselves, which is the whole of the golf: a
            // wound hand let go of comes back fast, and whatever is lying
            // in its arc goes with it. A ticking one barely nudges; a
            // fallen one is a bar of metal in the way.
            for ((i, bar) in hands.withIndex()) {
                strikeWithHand(b, bar, handSpeed.getOrNull(i))
            }
            // Free bubbles never park themselves: buoyancy keeps them
            // bobbing until "put everything back" pins them again.
        }

        // Bubble-bubble collisions; a resting bubble that gets hit wakes up.
        for (i in 0 until bubbles.size - 1) {
            for (j in i + 1 until bubbles.size) {
                val a = bubbles[i]
                val c = bubbles[j]
                if (!a.moving && !c.moving) continue
                val dx = c.centerX() - a.centerX()
                val dy = c.centerY() - a.centerY()
                val d = hypot(dx, dy)
                val minD = (a.sizePx + c.sizePx) / 2f
                if (d < minD && d > 0.001f) {
                    val nx = dx / d
                    val ny = dy / d
                    val push = (minD - d) / 2f
                    a.x -= nx * push
                    a.y -= ny * push
                    c.x += nx * push
                    c.y += ny * push
                    val relVn = (a.vx - c.vx) * nx + (a.vy - c.vy) * ny
                    if (relVn > 0f) {
                        // Billiard clack, loud and bright in proportion to
                        // how hard the two balls met.
                        if (relVn > 90f && allowCollisionSound()) {
                            chimePlayer.playClack((relVn / 1400f).coerceIn(0.08f, 1f))
                        }
                        val impulse = relVn * 0.92f
                        a.vx -= impulse * nx
                        a.vy -= impulse * ny
                        c.vx += impulse * nx
                        c.vy += impulse * ny
                        if (!a.moving && hypot(a.vx, a.vy) > 30f) a.moving = true
                        if (!c.moving && hypot(c.vx, c.vy) > 30f) c.moving = true
                        // Both of them felt that.
                        bruise(a, relVn)
                        bruise(c, relVn)
                    }
                }
            }
        }

        for (b in bubbles) {
            if (!b.moving) {
                // Parked: nothing is carrying it anywhere, so the contents
                // feel only the phone. Cleared rather than left at whatever
                // the last bounce was, or a settled bubble would go on
                // pushing its own hands sideways for ever.
                b.clock?.setCarrierAcceleration(0f, 0f)
                continue
            }
            b.place()
            // Whatever is loose inside feels the bubble's own manoeuvres:
            // brake against a cushion and the fallen hands pitch into it.
            // The force on the contents is minus the carrier's
            // acceleration, and a cushion reverses a velocity inside one
            // step — which as a raw acceleration is tens of g and would
            // fire the hands through the case. Scaled and capped: this is
            // for the look of the thing, not for the ledger.
            val ax = (-(b.vx - b.prevVx) / dt * CARRIER_SHARE)
                .coerceIn(-CARRIER_MAX, CARRIER_MAX)
            val ay = (-(b.vy - b.prevVy) / dt * CARRIER_SHARE)
                .coerceIn(-CARRIER_MAX, CARRIER_MAX)
            b.clock?.setCarrierAcceleration(ax, ay)
            b.prevVx = b.vx
            b.prevVy = b.vy
        }
    }

    private companion object {
        const val BUOYANCY = 300f

        /** How much of the bubble's own acceleration its contents feel. */
        const val CARRIER_SHARE = 0.3f

        /** How much of a hand's speed a struck bubble takes away with it. */
        const val STROKE = 1.4f

        /** And never more than this, however hard it hits a cushion. */
        const val CARRIER_MAX = 3f * DialDebris.BASE_GRAVITY
    }
}
