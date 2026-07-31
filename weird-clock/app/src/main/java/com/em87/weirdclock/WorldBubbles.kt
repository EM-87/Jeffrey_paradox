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

    private inner class Bubble(val tzId: String, val view: View, val clock: ClockView) {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var moving = false
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
        for (tz in tzs) {
            val clock = ClockView(host).apply {
                touchHandsEnabled = false
                pinchZoomEnabled = false
                shakeDropEnabled = false
                showDate = false
                // The city rides inside the dial, where the date sits on the
                // main clock — no caption hanging off the bubble.
                dialLabel = tz.substringAfterLast('/').replace('_', ' ')
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

    /** The bubbles wear whatever the main dial is wearing. */
    fun applyStyle(cv: ClockView) {
        for (b in bubbles) {
            b.clock.theme = cv.theme
            b.clock.hoursOnDial = cv.hoursOnDial
            b.clock.dialShape = cv.dialShape
            b.clock.numeralStyle = cv.numeralStyle
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
            if (b.clock.timeScale == 1f) {
                b.clock.timeScale = if (Math.random() < 0.5) 0f else -1f
            }
        }
    }

    /** A third of them lose their hands, as the main dial does. */
    fun knockSomeHandsOff() {
        for (b in bubbles.shuffled().take((bubbles.size + 2) / 3)) {
            b.clock.knockHandsOff()
        }
    }

    fun heal() {
        for (b in bubbles) b.clock.timeScale = 1f
    }

    /** The panic button reaches the bubbles' own fallen hands as well. */
    fun reassembleAll() {
        for (b in bubbles) b.clock.reassembleAll()
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
    private fun attachTouch(b: Bubble) {
        var lastX = 0f
        var lastY = 0f
        var lastT = 0L
        b.view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    b.moving = false
                    b.vx = 0f
                    b.vy = 0f
                    lastX = e.rawX
                    lastY = e.rawY
                    lastT = SystemClock.uptimeMillis()
                    layer?.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastT).coerceAtLeast(1L) / 1000f
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    b.x += dx
                    b.y += dy
                    b.vx = b.vx * 0.6f + (dx / dt) * 0.4f
                    b.vy = b.vy * 0.6f + (dy / dt) * 0.4f
                    lastX = e.rawX
                    lastY = e.rawY
                    lastT = now
                    b.place()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // A real fling turns it into a bubble; a gentle drop
                    // leaves it parked where you put it.
                    if (hypot(b.vx, b.vy) > 260f) {
                        b.moving = true
                    } else {
                        b.vx = 0f
                        b.vy = 0f
                    }
                    true
                }
                else -> false
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

    private fun cushion(v: Float) {
        if (abs(v) > 150f && allowCollisionSound()) {
            chimePlayer.playCushion((abs(v) / 1400f).coerceIn(0.08f, 1f))
        }
    }

    fun step() {
        if (bubbles.isEmpty() || bubbles.none { it.moving }) return
        val layer = this.layer ?: return
        val w = layer.width.toFloat()
        val h = layer.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dt = 0.016f
        val dialR = mainDial()?.currentDialRadius() ?: 0f
        val dialCx = w / 2f
        val dialCy = h / 2f

        for (b in bubbles) {
            if (!b.moving) continue
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
            if (b.x < 0f) { b.x = 0f; cushion(b.vx); b.vx = -b.vx * 0.9f }
            if (b.y < 0f) { b.y = 0f; cushion(b.vy); b.vy = -b.vy * 0.9f }
            if (b.x + b.sizePx > w) { b.x = w - b.sizePx; cushion(b.vx); b.vx = -b.vx * 0.9f }
            if (b.y + b.sizePx > h) { b.y = h - b.sizePx; cushion(b.vy); b.vy = -b.vy * 0.9f }
            // The main dial is a fixed obstacle — and it rings when struck.
            if (dialR > 0f && dialIsObstacle()) {
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
                    }
                }
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
                    }
                }
            }
        }

        for (b in bubbles) if (b.moving) b.place()
    }

    private companion object {
        const val BUOYANCY = 300f
    }
}
