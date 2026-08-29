package com.em87.weirdclock

import java.util.Locale
import java.util.TimeZone

/**
 * Yesterday's clouds, photographed from orbit, as a request and a rule.
 *
 * The globe on this clock has worn the same two maps since it arrived: the
 * Blue Marble by day and the city lights by night, both taken years ago
 * and both perfectly fixed. This puts weather on it — and not a drawing of
 * weather. NASA's Global Imagery Browse Services publish MODIS's
 * corrected-reflectance mosaic of the whole earth, free, keyless and
 * public domain, one complete picture per day. It is the same imagery
 * Worldview shows.
 *
 * **It is a day old, and every part of it was taken in the morning.**
 * Terra is in a polar orbit and crosses each place at about half past ten
 * local time, so a global mosaic is not an instant — it is a hundred and
 * some strips, each photographed as the satellite came over, sewn
 * together. The clouds on this globe are where they were yesterday
 * morning, over you and over everywhere else. That is what a daily
 * satellite mosaic *is*, and this app would rather say so than pretend to
 * a live picture nobody publishes for nothing.
 *
 * The seams show, and they are meant to. Near the equator the orbits do
 * not quite overlap and the mosaic has thin black wedges between them —
 * places nobody flew over that day. They come through as no cloud at all,
 * which is honest: the answer there is not "clear", it is "unphotographed",
 * and the map underneath showing through is the closest a clock can get to
 * saying that.
 *
 * The fetching and the keeping are [CloudStore]. What is here is the URL
 * and the one rule that turns a photograph into a veil.
 */
object SatelliteClouds {

    /** Where NASA serves it. */
    const val HOST = "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi"

    /**
     * Which product.
     *
     * Corrected reflectance rather than one of the cloud-fraction science
     * layers, which are rendered as a false-colour ramp and look like a
     * chart of the weather rather than a photograph of it. This is what
     * the earth looked like.
     */
    const val LAYER = "MODIS_Terra_CorrectedReflectance_TrueColor"

    /**
     * How big a picture to ask for.
     *
     * The disc is baked at 512 across and the globe shows half a world at
     * a time, so 720 by 360 is already more than can be seen. It is about
     * eighty kilobytes as a JPEG, once a day, and only with the weather
     * switched on.
     */
    const val WIDTH = 720
    const val HEIGHT = 360

    /**
     * How far back to ask, in milliseconds.
     *
     * Thirty hours rather than twenty-four, and the difference is the
     * point: a day is not published the moment it ends. At one minute past
     * midnight UTC, "yesterday" is a day that finished a minute ago and is
     * still being assembled, and the answer would be a half-empty world.
     * Thirty hours always lands on a day that is finished and processed,
     * whatever hour it is asked at.
     */
    const val BEHIND_MS = 30L * 60L * 60L * 1000L

    /** The UTC day to ask for, given what time it is now. */
    fun dayFor(nowMs: Long): String {
        val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = nowMs - BEHIND_MS
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH) + 1,
            utc.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * The whole request: one equirectangular picture of the world.
     *
     * No location in it, and that is worth saying out loud. Every other
     * thing this app fetches carries a latitude and a longitude, rounded
     * but still a place — this asks for the entire planet and works out
     * where you are afterwards, on the phone. There is nothing in this
     * request that says who is asking.
     */
    fun url(nowMs: Long): String =
        "$HOST?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0" +
            "&LAYERS=$LAYER&CRS=EPSG:4326&BBOX=-90,-180,90,180" +
            "&WIDTH=$WIDTH&HEIGHT=$HEIGHT&FORMAT=image/jpeg" +
            "&TIME=${dayFor(nowMs)}"

    // ------------------------------------------------------------- the veil

    /**
     * Where a pixel stops being sky and starts being cloud.
     *
     * Cloud in a true-colour photograph is bright and colourless, and
     * nothing else in the picture is both: ocean is dark, land is coloured,
     * and the unphotographed wedges are black. So the rule is two ramps
     * multiplied — how bright, and how little colour — rather than a
     * threshold, because a threshold gives cloud a hard edge and real
     * cloud has not got one.
     *
     * These numbers were not reasoned out. Three sets of them were tried
     * against a real day's mosaic laid over the real Blue Marble and looked
     * at side by side; this is the set where the cyclones read and the
     * continents still do.
     */
    const val DIM = 0.68f
    const val BRIGHT = 0.95f
    const val PURE = 0.04f
    const val COLOURED = 0.16f

    /**
     * The curve, which is not a straight line.
     *
     * Squared-and-a-bit, so thin haze stays thin. Linear made the whole
     * tropics a flat grey wash — technically the average brightness, and a
     * picture of nothing.
     */
    const val CURVE = 1.8

    /** And how solid the thickest cloud is allowed to be, out of 255. */
    const val THICKEST = 217

    /**
     * How much cloud is over this pixel of the photograph, out of 255.
     *
     * Snow and ice come out as cloud, and there is no fixing that from one
     * photograph — a white pixel over Greenland is white for both reasons
     * at once. It matters less than it sounds, because the map underneath
     * has Greenland white already.
     */
    fun veil(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val top = maxOf(r, g, b)
        if (top == 0) return 0
        val bottom = minOf(r, g, b)
        val value = top / 255f
        val saturation = (top - bottom).toFloat() / top
        val bright = ((value - DIM) / (BRIGHT - DIM)).coerceIn(0f, 1f)
        val plain = ((COLOURED - saturation) / (COLOURED - PURE)).coerceIn(0f, 1f)
        val cover = Math.pow((bright * plain).toDouble(), CURVE)
        return (cover * THICKEST).toInt().coerceIn(0, 255)
    }

    // -------------------------------------------------------- the edges

    /**
     * How much of the veil survives this far out from the middle of the
     * disc, where [rho] is nought at the centre and one at the rim.
     *
     * Not a softening for the look of it. Both projections on this face
     * fall apart at their edge, in the same way and for the same reason:
     * the far pole is a single point of the map and the rim is a whole
     * circle, so one pixel of the photograph is smeared right round the
     * picture. Drawn at full strength that is a white halo the width of a
     * finger made out of one pixel's worth of answer — which the flat
     * views did, and it looked like a scratch on the lens rather than
     * like weather.
     *
     * The globe has the milder version of the same problem: at the limb
     * you are looking along the surface, so a thin band of screen carries
     * an enormous amount of world. It is also where you would be looking
     * through the most air, so fading there is what a photograph of a
     * planet actually does.
     *
     * The map underneath is not faded — it has the same distortion and
     * always has, and changing that would be redrawing the earth. What is
     * refused here is *adding* a claim at a place where the projection
     * cannot carry one.
     */
    fun edge(flat: Boolean, rho: Double): Float {
        if (rho >= 1.0) return 0f
        if (flat) return (((FLAT_GONE - rho) / (FLAT_GONE - FLAT_FULL)).toFloat()).coerceIn(0f, 1f)
        // On the ball, how much of the surface is facing you.
        val facing = Math.sqrt(1.0 - rho * rho)
        return ((facing / LIMB).toFloat()).coerceIn(0f, 1f)
    }

    /** Whole out to here on the flat views, and gone by here. */
    const val FLAT_FULL = 0.72
    const val FLAT_GONE = 0.93

    /** And how far round the ball's limb the veil is thinned. */
    const val LIMB = 0.35

    /** One pixel of the photograph, at the strength that place can carry. */
    fun tint(argb: Int, flat: Boolean, rho: Double): Int {
        val alpha = (veil(argb) * edge(flat, rho)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or 0x00FFFFFF
    }
}
