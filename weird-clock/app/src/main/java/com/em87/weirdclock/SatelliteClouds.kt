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
    fun url(nowMs: Long): String = url(dayFor(nowMs))

    /**
     * And for a day by name, which is how a week of them is fetched.
     *
     * The store keeps one picture per day so the globe can be wound
     * backwards through the week — see [CloudStore] — and asks for them
     * by the day rather than by an instant, because "thirty hours before
     * some time on that day" is a sum that has already been done by then.
     */
    fun url(day: String): String =
        "$HOST?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0" +
            "&LAYERS=$LAYER&CRS=EPSG:4326&BBOX=-90,-180,90,180" +
            "&WIDTH=$WIDTH&HEIGHT=$HEIGHT&FORMAT=image/jpeg" +
            "&TIME=$day"

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
     * Not a softening for the look of it. All three views are the same
     * projection now — a ball, from three places — and a ball has a limb:
     * at the rim you are looking along the surface, so a thin band of
     * screen carries an enormous amount of world and one pixel of the
     * photograph is smeared right round the picture. It is also where you
     * would be looking through the most air, so fading there is what a
     * photograph of a planet actually does.
     *
     * There used to be a second rule here, for the two flat views back
     * when they were charts that reached the far pole: their rim was one
     * point of the map stretched round a whole circle, which drew a white
     * halo the width of a finger out of one pixel's worth of answer. Those
     * views are hemispheres now and their rim is the equator seen
     * edge-on, which is the ordinary limb and wants the ordinary rule.
     *
     * The map underneath is not faded — it has the same distortion and
     * always has, and changing that would be redrawing the earth. What is
     * refused here is *adding* a claim at a place where the projection
     * cannot carry one.
     */
    fun edge(rho: Double): Float {
        if (rho >= 1.0) return 0f
        // How much of the surface is facing you.
        val facing = Math.sqrt(1.0 - rho * rho)
        return ((facing / LIMB).toFloat()).coerceIn(0f, 1f)
    }

    /** How far round the limb the veil is thinned. */
    const val LIMB = 0.35

    /** So much cloud, out of 255, at the strength that place of the disc can carry. */
    fun shade(cover: Int, rho: Double): Int {
        val alpha = (cover * edge(rho)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or 0x00FFFFFF
    }

    // -------------------------------------------------------- the field

    /**
     * Below this in all three colours, a pixel of the photograph is a
     * place nobody photographed. Not nought: the picture is a JPEG, and
     * its black has a grain.
     */
    const val NO_DATA = 12

    /**
     * How much of each row's cloud is kept, from the top row down: all
     * of it, except towards an end of the picture that is running out.
     *
     * The photograph is taken by daylight and in winter there is none
     * round the pole, so the picture stops. It does not stop at a line.
     * Each pass of the satellite runs out of light a little further
     * north or south than the one before, and the end of the picture is
     * a row of scallops ten degrees deep in one season and three in
     * another: on the globe, cloud that ends in bites. That was reported
     * as cuts where the image ends, which is what they are.
     *
     * So a row is thinned by how much of it is missing. Measured on two
     * real mosaics: the rows of the scallops are the ones with between
     * one part in a hundred and a quarter of their pixels unphotographed,
     * and past a quarter there is more hole than cloud. Asked of the
     * picture and not of the sun, because where the picture ends is the
     * service's rule and the satellite's orbit, and neither is this
     * app's to know.
     *
     * Only towards the poles. Between the tropics every row has a tenth
     * of itself missing — the gaps between one pass and the next — and
     * that is not the picture running out.
     */
    fun rowsKept(pixels: IntArray, width: Int, height: Int): FloatArray {
        val kept = FloatArray(height.coerceAtLeast(0)) { 1f }
        if (width <= 0 || height <= 0 || pixels.size != width * height) return kept
        for (y in 0 until height) {
            val latitude = 90.0 - (y + 0.5) * 180.0 / height
            if (Math.abs(latitude) < EDGE_FROM_DEG) continue
            var there = 0
            val base = y * width
            for (x in 0 until width) if (photographed(pixels[base + x])) there++
            val t = ((there.toFloat() / width - THERE_NONE) / (THERE_ALL - THERE_NONE)).coerceIn(0f, 1f)
            kept[y] = t * t * (3f - 2f * t)
        }
        return kept
    }

    private fun photographed(argb: Int): Boolean =
        maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF) > NO_DATA

    /** The latitude beyond which a row with holes in it is the picture ending. */
    const val EDGE_FROM_DEG = 45.0

    /** How much of a row has to be there for any of its cloud to be kept, and for all of it. */
    const val THERE_NONE = 0.75f
    const val THERE_ALL = 0.99f

    /**
     * A day's photograph as cloud and nothing else: how much veil each
     * of its pixels is, out of 255 — see [veil] — less what [rowsKept]
     * takes off the rows at the end of it.
     *
     * Worked out once for a photograph. It used to be worked out again
     * for every point of every disc projected from one, a quarter of a
     * million times a bake, with a power in it.
     */
    fun field(pixels: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(pixels.size)
        if (width <= 0 || height <= 0 || pixels.size != width * height) return out
        val kept = rowsKept(pixels, width, height)
        for (y in 0 until height) {
            val share = kept[y]
            if (share <= 0f) continue
            val base = y * width
            for (x in 0 until width) out[base + x] = (veil(pixels[base + x]) * share).toInt().toByte()
        }
        return out
    }

    /**
     * How much cloud is over a point of the map, out of 255: the four
     * pixels of the [field] round it, each weighed by how near it is.
     *
     * It was the one pixel the point fell in. A pixel of the photograph
     * is half a degree, which on a globe that fills a phone is five or
     * six pixels of glass, and cloud drawn that way is a mosaic of white
     * squares — looked at, on the first picture taken of it at the size
     * a phone shows it. Cloud has no edges that sharp to lose.
     *
     * The picture is the whole world: it goes round the back at the
     * date line, and stops at the poles.
     */
    fun cloudAt(field: ByteArray, width: Int, height: Int, u: Double, v: Double): Int {
        val x = u * width - 0.5
        val y = (v * height - 0.5).coerceIn(0.0, height - 1.0)
        val left = Math.floor(x).toInt()
        val top = y.toInt()
        val across = x - left
        val down = y - top
        val x0 = Math.floorMod(left, width)
        val x1 = Math.floorMod(left + 1, width)
        val row0 = top * width
        val row1 = minOf(top + 1, height - 1) * width
        val a = field[row0 + x0].toInt() and 0xFF
        val b = field[row0 + x1].toInt() and 0xFF
        val c = field[row1 + x0].toInt() and 0xFF
        val d = field[row1 + x1].toInt() and 0xFF
        val upper = a + (b - a) * across
        val lower = c + (d - c) * across
        return (upper + (lower - upper) * down + 0.5).toInt()
    }
}
