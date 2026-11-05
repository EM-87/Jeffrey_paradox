package com.em87.weirdclock

/**
 * Where each point of the disc is on the earth, worked out once.
 *
 * Painting the world into the disc asks, for each of a quarter of a million
 * pixels, which place on the earth is there — see [Hemisphere.unproject],
 * which answers with an arcsine and an arctangent per pixel. Those answers
 * do not change when the world turns. Turning the world adds the same
 * number of degrees to every longitude and leaves every latitude alone,
 * so the trigonometry can be done once per view and kept, and a turn of
 * the world costs a subtraction per pixel instead.
 *
 * That is the difference between a globe that is repainted every six
 * degrees while it is spinning because six was the most it could afford,
 * and a globe that is repainted at the frame rate. The spin used to be
 * reported as the world seizing up — and it was, on the thread that draws
 * it, for as long as the projection took.
 *
 * Pure, and measured in [DiscProjectionTest] against the slow answer it
 * replaces: for every pixel and any turn the two agree exactly.
 */
class DiscProjection(val view: Hemisphere.View, val size: Int) {

    /**
     * How far down the map each pixel is, from nought at the north pole to
     * one at the south, or [OFF] for a pixel outside the disc.
     */
    val v = FloatArray(size * size)

    /**
     * How far along the map each pixel is with the world unturned, from
     * nought at 180° west to one at 180° east.
     */
    val u0 = FloatArray(size * size)

    /** How far from the middle of the disc each pixel is, out of one. */
    val rho = FloatArray(size * size)

    /**
     * Which way a degree of turn moves the longitude on this view: the
     * other way round from underneath. Fixed by [Hemisphere.unproject],
     * which this is a copy of.
     */
    private val sense = if (view == Hemisphere.View.SOUTH) -1.0 else 1.0

    init {
        val half = size / 2.0
        for (py in 0 until size) {
            val y = (py + 0.5 - half) / half
            for (px in 0 until size) {
                val x = (px + 0.5 - half) / half
                val at = py * size + px
                rho[at] = kotlin.math.hypot(x, y).toFloat()
                val place = Hemisphere.unproject(view, x, y, 0.0)
                if (place == null) {
                    v[at] = OFF
                    u0[at] = 0f
                } else {
                    v[at] = ((90.0 - place[0]) / 180.0).coerceIn(0.0, 0.9999).toFloat()
                    u0[at] = ((place[1] + 180.0) / 360.0).toFloat()
                }
            }
        }
    }

    /** Whether the pixel is on the world at all. */
    fun onWorld(at: Int): Boolean = v[at] != OFF

    /**
     * How far along the map pixel [at] is with the world turned by
     * [turnDeg], from nought to just under one.
     *
     * The whole of the saving: [Hemisphere.unproject] takes the turn off
     * the bearing before wrapping it to a longitude, so a turn is a shift
     * of the same fraction of the map for every pixel, wrapped.
     */
    fun u(at: Int, turnDeg: Double): Double {
        val shifted = u0[at] - sense * turnDeg / 360.0
        val wrapped = shifted - kotlin.math.floor(shifted)
        return wrapped.coerceAtMost(0.9999)
    }

    companion object {
        /** The mark in [v] for a pixel off the disc. */
        const val OFF = -1f

        /**
         * The last projection worked out, shared.
         *
         * A widget paints the world through a view of its own, made for
         * the picture and thrown away, and every one of them used to do
         * the trigonometry again. The app's own view and the widgets are
         * nearly always looking the same way, so one kept answer serves
         * them all; a different view simply replaces it.
         */
        @Volatile
        private var last: DiscProjection? = null

        /** How many have been worked out, for the tests. */
        @Volatile
        var built = 0
            private set

        fun of(view: Hemisphere.View, size: Int): DiscProjection {
            last?.let { if (it.view == view && it.size == size) return it }
            return DiscProjection(view, size).also {
                last = it
                built++
            }
        }
    }
}
