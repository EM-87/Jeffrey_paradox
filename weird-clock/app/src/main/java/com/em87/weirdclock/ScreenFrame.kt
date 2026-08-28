package com.em87.weirdclock

import kotlin.math.hypot

/**
 * The window the digits sit behind on a chronograph with a screen in it.
 *
 * A row of lit bars floating in the middle of a bezel is a number printed
 * on a dial. What makes it read as a display is the thing round it: a
 * rectangle of slightly darker glass, its corners taken off, sitting a
 * little below the surface of the face. Every digital chronograph ever
 * built has one, and it is the whole difference between "this clock has a
 * screen" and "somebody wrote on the dial".
 *
 * The arithmetic is here, out of the drawing, because there is one thing
 * in it that is easy to get wrong and invisible until it happens: the
 * panel is a rectangle inside a circle, so it is its *corners* that decide
 * how big it can be, not its width. A panel sized by its width fits
 * perfectly until the day it gets tall enough for the corners to poke out
 * through the bezel.
 */
object ScreenFrame {

    /** How much clear glass there is round the digits, in digit heights. */
    const val PAD_X = 0.42f
    const val PAD_Y = 0.34f

    /** How round the corners are, as a share of the panel's short side. */
    const val CORNER = 0.20f

    /** How far inside the bezel the furthest corner is allowed to reach. */
    const val REACH = 0.93f

    /**
     * The panel's half-width and half-height, shrunk together until its
     * corners are inside the bezel.
     *
     * Both are scaled by the same amount rather than the width alone,
     * because a panel that keeps its height and loses its width stops
     * being the shape of a screen. Returns width first, height second.
     */
    fun fit(halfW: Float, halfH: Float, radius: Float): FloatArray {
        val corner = hypot(halfW, halfH)
        val limit = radius * REACH
        if (corner <= limit || corner <= 0f) return floatArrayOf(halfW, halfH)
        val shrink = limit / corner
        return floatArrayOf(halfW * shrink, halfH * shrink)
    }

    /**
     * How the recess is drawn: several rounded rectangles, each one
     * further out and fainter than the last.
     *
     * The same trick the hands' shadows use, and for the same reason — a
     * mask filter is one call and is among the things a hardware canvas
     * quietly declines, and a screen that is softly recessed on one phone
     * and outlined in hard black on another is worse than a flat one. The
     * spread is in fractions of the blur, not multiples of anything the
     * panel is, so the softness is the same on a big dial and a small one.
     */
    val SPREAD: FloatArray = floatArrayOf(1.00f, 0.72f, 0.48f, 0.28f, 0.12f, 0f)

    /** And how much of the shadow each of those passes lays down. */
    val WEIGHT: FloatArray = floatArrayOf(0.10f, 0.14f, 0.18f, 0.22f, 0.26f, 0.30f)

    /**
     * The glass itself: the face, taken down towards black.
     *
     * A share rather than a colour, so every theme keeps its own screen.
     * Terminal's is nearly black and Ivory's is a warm grey, and both are
     * darker than the dial they are cut into — which is what a piece of
     * polarised glass over a reflector actually looks like, on a white
     * watch as much as on a black one.
     */
    fun glass(face: Int): Int {
        val a = face ushr 24
        fun down(shift: Int) = (((face shr shift) and 0xFF) * 0.72f).toInt().coerceIn(0, 255)
        return (a shl 24) or (down(16) shl 16) or (down(8) shl 8) or down(0)
    }
}
