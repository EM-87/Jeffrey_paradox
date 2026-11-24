package com.em87.weirdclock

/**
 * The day's photograph itself, laid over the month's map.
 *
 * Asked for as the monthly picture joined to the slices of the new daily
 * scans. The map the globe wears is a composite of a month, of some year:
 * no cloud in it, the snow where it usually is. The photograph is one
 * day — its cloud, its haze, its smoke, the snow where it fell — and the
 * third kind of cloud the globe can wear is not a veil made out of the
 * photograph but the photograph, wherever there is any, with the map
 * left showing where there is none.
 *
 * Not the same photograph the veil is made of. That one is MODIS on
 * Terra, whose passes leave a wedge of nothing between them all the way
 * round the tropics — see [SatelliteClouds.fill] — and are bright with
 * the sun's reflection off the sea down the middle of every one: under
 * a veil neither matters, and as a picture of the earth it is thirteen
 * stripes. VIIRS on NOAA-20 sees a wider strip at each pass. Counted on
 * the mosaics of 21, 22 and 23 November 2026: a quarter of one part in
 * a hundred of the belt within fifteen degrees of the equator
 * unphotographed, against twelve parts in a hundred of the other's, and
 * nothing to fill. It is the afternoon satellite — it crosses the
 * equator northbound at about half past one, local time, by NASA's
 * account of it — so each place is as it was early that afternoon.
 *
 * What is left unphotographed is the night round the winter pole, where
 * the photograph simply stops; there the map carries on, and the one is
 * faded into the other over a few degrees so that the join is not a
 * circle drawn on the world.
 */
object SatellitePhoto {

    /** NOAA-20's, and NOAA-21's for a day the first has nothing of: the same instrument on the next satellite. */
    const val LAYER = "VIIRS_NOAA20_CorrectedReflectance_TrueColor"
    const val SECOND = "VIIRS_NOAA21_CorrectedReflectance_TrueColor"

    /**
     * The size it is asked for at, which is the size of the maps it is
     * laid over — see [over], which lays it pixel on pixel and will not
     * lay it at all at any other. A hundred and fifty kilobytes a day.
     */
    const val WIDTH = 1024
    const val HEIGHT = 512

    /**
     * Where to ask for a day, in order: the same service the veil's
     * photograph comes from, and asked for as late — see
     * [SatelliteClouds.BEHIND_MS], which has a day asked for six hours
     * after it ends. Looked at once, at 11:18 UTC on 24 November 2026:
     * that day's mosaic had got as far as the pass of about ten to
     * seven, four and a half hours behind the satellite.
     */
    fun urls(day: String): List<String> = listOf(LAYER, SECOND).map { layer ->
        "${SatelliteClouds.HOST}?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0" +
            "&LAYERS=$layer&CRS=EPSG:4326&BBOX=-90,-180,90,180" +
            "&WIDTH=$WIDTH&HEIGHT=$HEIGHT&FORMAT=image/jpeg" +
            "&TIME=$day"
    }

    /** Over how many degrees the photograph is faded into the map towards where it stops. */
    const val FADE_DEG = 4.0

    /**
     * A photograph ready to be laid over a map: its colours, and in the
     * top byte of each pixel how much of it is to be laid there, out of
     * 255 — see [picture].
     */
    class Picture(val width: Int, val height: Int, val pixels: IntArray)

    /**
     * A day's photograph with how much of it to lay down worked out for
     * every pixel: all of it, none where nobody photographed, and less
     * and less towards the end of the picture.
     *
     * None beside a place nobody photographed, either. The picture is a
     * JPEG, and the pixels along the edge of a hole are the hole's black
     * run into the photograph: laid over the map they are a dark rim
     * round a patch of map.
     */
    fun picture(pixels: IntArray, width: Int, height: Int): Picture {
        val out = IntArray(pixels.size)
        if (width <= 0 || height <= 0 || pixels.size != width * height) return Picture(width, height, out)
        val kept = slope(SatelliteClouds.rowsKept(pixels, width, height))
        for (y in 0 until height) {
            val share = Math.round(kept[y] * 255f)
            if (share == 0) continue
            val base = y * width
            val above = if (y == 0) base else base - width
            val below = if (y == height - 1) base else base + width
            for (x in 0 until width) {
                val left = if (x == 0) width - 1 else x - 1
                val right = if (x == width - 1) 0 else x + 1
                val whole = SatelliteClouds.photographed(pixels[base + x]) &&
                    SatelliteClouds.photographed(pixels[base + left]) &&
                    SatelliteClouds.photographed(pixels[base + right]) &&
                    SatelliteClouds.photographed(pixels[above + x]) &&
                    SatelliteClouds.photographed(pixels[below + x])
                if (whole) out[base + x] = (share shl 24) or (pixels[base + x] and 0xFFFFFF)
            }
        }
        return Picture(width, height, out)
    }

    /**
     * How much of each row of the photograph is laid down, from how much
     * of each row is there — see [SatelliteClouds.rowsKept], which is the
     * same question asked for the veil. No row is more than its distance
     * from a row that is running out allows: a slope [FADE_DEG] long
     * down to the end of the picture, where the veil has a step.
     *
     * A step does for a veil, which is cloud ending. It will not do for
     * a photograph, which is the whole look of the world changing — sea
     * of one blue to sea of another — along a parallel.
     */
    fun slope(kept: FloatArray): FloatArray {
        val height = kept.size
        val reach = Math.round(FADE_DEG * height / 180.0).toInt().coerceAtLeast(1)
        val out = FloatArray(height)
        for (y in 0 until height) {
            var most = kept[y]
            for (k in 1 until reach) {
                val rise = k.toFloat() / reach
                if (y - k >= 0) most = minOf(most, kept[y - k] + rise)
                if (y + k < height) most = minOf(most, kept[y + k] + rise)
            }
            out[y] = most * most * (3f - 2f * most)
        }
        return out
    }

    /**
     * A map with the photograph laid over it, pixel on pixel: the
     * photograph's colour by its share, the map's by the rest. A new
     * picture, the map itself left as it was.
     *
     * Null for a photograph that is not the map's size. It would have to
     * be stretched to be laid, and a stretched photograph of the earth
     * has its coasts beside the map's and not on them.
     */
    fun over(map: IntArray, width: Int, height: Int, picture: Picture): IntArray? {
        if (picture.width != width || picture.height != height) return null
        if (map.size != width * height || picture.pixels.size != map.size) return null
        val out = IntArray(map.size)
        for (i in map.indices) {
            val laid = picture.pixels[i]
            val share = laid ushr 24
            val rest = 255 - share
            val under = map[i]
            // Out of 255 and not 256, so that none of it is the map to
            // the last shade and all of it the photograph to the last.
            val red = (((under shr 16) and 0xFF) * rest + ((laid shr 16) and 0xFF) * share) / 255
            val green = (((under shr 8) and 0xFF) * rest + ((laid shr 8) and 0xFF) * share) / 255
            val blue = ((under and 0xFF) * rest + (laid and 0xFF) * share) / 255
            out[i] = OPAQUE or (red shl 16) or (green shl 8) or blue
        }
        return out
    }

    private const val OPAQUE = 0xFF shl 24
}
