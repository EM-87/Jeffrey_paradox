package com.em87.weirdclock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The weather, drawn round the sun and the moon.
 *
 * The clock's owner asked for it in exactly those words: *"en el token del
 * orrery que esto figure como nubes, rayos, gotas de agua, etc alrededor
 * del sol/luna"*. So this is not a second complication beside the sky
 * token — it is the same token with weather over it, which is what a sky
 * actually looks like.
 *
 * It draws nothing on a clear day. That is the important half: a clock
 * that puts a little sun-with-no-cloud badge on itself has added a picture
 * of nothing, and the sun already there is a better drawing of a clear sky
 * than any glyph could be. What earns ink is a change — cloud, rain,
 * lightning — and the amount of ink goes up with how bad it is.
 *
 * Everything is measured from the moon's radius, the same as the rest of
 * the token, so the weather is the right size on a dial, on a widget, and
 * on the little sky inside a chronograph without any of them saying so.
 */
object WeatherGlyph {

    /** How big the cloud is against the moon's radius. */
    const val CLOUD = 1.15f

    /** And where it sits: down and to the right, so the sun still reads. */
    const val OFFSET_X = 0.55f
    const val OFFSET_Y = 0.62f

    /** How faint an unconfirmed reading is drawn. */
    const val LONE_ALPHA = 120

    /**
     * Whether this sky is worth drawing anything for.
     *
     * Split out because it is the one decision in here, and because two
     * callers need to know it before they lay anything out: a token with
     * weather on it is wider than one without.
     */
    fun marks(look: Weather.Look?): Boolean =
        look != null && look != Weather.Look.CLEAR

    /**
     * The sky's weather, over a token whose middle is [cx],[cy] and whose
     * moon is [mr] across.
     *
     * [sure] is false for a reading no second service confirmed — see
     * [Weather.Trust.LONE] — and it is drawn faintly rather than hidden.
     * A lone reading is what is left on the day the other services are
     * down, which is the day the whole design exists for; showing it at
     * full strength would be the clock claiming more than it knows.
     */
    fun draw(
        canvas: Canvas,
        look: Weather.Look?,
        cx: Float,
        cy: Float,
        mr: Float,
        lit: Paint,
        dark: Paint,
        sure: Boolean = true
    ) {
        if (!marks(look)) return
        val was = lit.alpha
        val wasStyle = lit.style
        if (!sure) lit.alpha = LONE_ALPHA
        val x = cx + mr * OFFSET_X
        val y = cy + mr * OFFSET_Y
        val r = mr * CLOUD
        // Overcast is the same cloud, bigger and darker, because that is
        // what the difference between the two actually looks like.
        val heavy = look == Weather.Look.OVERCAST || look == Weather.Look.RAIN ||
            look == Weather.Look.STORM
        cloud(canvas, x, y, r * (if (heavy) 1.18f else 1f), lit, dark)
        when (look) {
            Weather.Look.RAIN -> rain(canvas, x, y + r * 0.30f, r, lit)
            Weather.Look.STORM -> bolt(canvas, x, y + r * 0.28f, r, lit)
            else -> Unit
        }
        lit.alpha = was
        lit.style = wasStyle
    }

    /**
     * A cloud: three humps on a flat bottom, outlined so it reads against
     * the disc it is lying over.
     *
     * Filled in the dark ink and rimmed in the lit one, which is the only
     * way a small cloud can sit on top of a small sun and still be two
     * things. Filled in the lit ink it becomes a bite out of the sun.
     *
     * Built as a *union* and not as four shapes in one path. Stroking a
     * path made of overlapping pieces strokes every piece, so the first
     * version of this had each hump's own arc drawn across the inside of
     * the cloud — a lump of dark metal with a spider's web on it, which a
     * picture showed at once and which no assertion about how much ink is
     * on the token would ever have said.
     */
    private fun cloud(
        canvas: Canvas, cx: Float, cy: Float, r: Float, lit: Paint, dark: Paint
    ) {
        val path = Path()
        for (hump in HUMPS) {
            path.op(
                Path().apply {
                    addCircle(cx + r * hump[0], cy + r * hump[1], r * hump[2], Path.Direction.CW)
                },
                Path.Op.UNION
            )
        }
        path.op(
            Path().apply {
                addRect(
                    cx - r * 0.42f, cy - r * 0.04f, cx + r * 0.46f, cy + r * 0.26f,
                    Path.Direction.CW
                )
            },
            Path.Op.UNION
        )
        val wasStyle = dark.style
        val wasDark = dark.alpha
        dark.style = Paint.Style.FILL
        dark.alpha = 255
        canvas.drawPath(path, dark)
        dark.alpha = wasDark
        dark.style = wasStyle
        lit.style = Paint.Style.STROKE
        lit.strokeWidth = r * 0.13f
        canvas.drawPath(path, lit)
    }

    /**
     * The three humps, as offsets and radii in cloud widths.
     *
     * Wider than it is tall, because clouds are and because a round one
     * reads as a second sun behind the first.
     */
    private val HUMPS = arrayOf(
        floatArrayOf(-0.40f, 0.02f, 0.26f),
        floatArrayOf(-0.05f, -0.20f, 0.34f),
        floatArrayOf(0.36f, 0.00f, 0.28f)
    )

    /** Three drops under it, falling at a slant because rain does. */
    private fun rain(canvas: Canvas, cx: Float, cy: Float, r: Float, lit: Paint) {
        lit.style = Paint.Style.STROKE
        lit.strokeWidth = r * 0.12f
        lit.strokeCap = Paint.Cap.ROUND
        for (i in -1..1) {
            val x = cx + i * r * 0.30f
            canvas.drawLine(x, cy + r * 0.16f, x - r * 0.10f, cy + r * 0.44f, lit)
        }
    }

    /** And a bolt, which is a filled zig-zag and not a line. */
    private fun bolt(canvas: Canvas, cx: Float, cy: Float, r: Float, lit: Paint) {
        val path = Path().apply {
            moveTo(cx + r * 0.10f, cy + r * 0.10f)
            lineTo(cx - r * 0.14f, cy + r * 0.44f)
            lineTo(cx + r * 0.01f, cy + r * 0.44f)
            lineTo(cx - r * 0.10f, cy + r * 0.72f)
            lineTo(cx + r * 0.22f, cy + r * 0.32f)
            lineTo(cx + r * 0.06f, cy + r * 0.32f)
            lineTo(cx + r * 0.20f, cy + r * 0.10f)
            close()
        }
        lit.style = Paint.Style.FILL
        canvas.drawPath(path, lit)
    }
}
