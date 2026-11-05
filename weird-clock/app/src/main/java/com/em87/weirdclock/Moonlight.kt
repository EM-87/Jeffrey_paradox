package com.em87.weirdclock

/**
 * Moonlight on the dark side of the world, as a rule.
 *
 * The night half of the globe is not all one dark. Where the moon is up
 * the sea and the land are lit by it, enough that a satellite's picture
 * of the night reads two hundred greys brighter on a full moon than on a
 * new one — see [NightLights.residueFloor], which measures exactly that.
 * So the globe wears a veil: a cold blue-grey over the half of the world
 * the moon is above, only where it is also solar night, as strong as the
 * moon is full.
 *
 * No new data: the moon's place comes from the ephemeris the hand shadows
 * and the orrery already use — see [Hemisphere.sublunarPoint] — and its
 * fullness from [SolarTime.moonIllumination]. Pure, so the numbers can be
 * held against the night-light pictures, which is what [MoonlightTest]
 * does.
 */
object Moonlight {

    /**
     * The veil's colour and how far it goes at the full.
     *
     * A fifth at most: moonlight is bright enough to read by and dim
     * enough that the city lights still stand out on top of it, which is
     * the order they are drawn in.
     */
    const val TINT = 0xFF9FB4D8.toInt()
    const val MAX_ALPHA = 0.20

    /** How much of the veil there is, out of one, for a moon this [lit]. */
    fun alpha(lit: Double): Double = (MAX_ALPHA * lit).coerceIn(0.0, MAX_ALPHA)

    /**
     * Where the moon is up, softly: nought with the moon well down, one
     * with it well up, and the same few degrees of twilight between as
     * the sun's terminator has — see [HemisphereView.DAWN].
     */
    const val RISE = 0.03
    const val SET = -0.10

    fun up(cosZenith: Double): Double = ((cosZenith - SET) / (RISE - SET)).coerceIn(0.0, 1.0)

    /** One pixel of the veil at this [lit] moon, where the moon stands this high. */
    fun pixel(lit: Double, cosZenith: Double): Int {
        val a = Math.round(alpha(lit) * up(cosZenith) * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (TINT and 0x00FFFFFF)
    }
}
