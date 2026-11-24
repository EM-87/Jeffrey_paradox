package com.em87.weirdclock

import java.util.Locale
import java.util.TimeZone

/**
 * The clouds as they stood within the last hour or two, read off the
 * weather satellites.
 *
 * The daily photograph — see [SatelliteClouds] — is a day and a half old
 * by the time the globe wears it, and every place in it was taken at half
 * past ten of its own morning. The weather satellites that sit over the
 * equator look at the same side of the world all day and all night, and
 * NOAA lay five of them side by side every hour into one picture of the
 * earth: the GMGSI mosaic, served without a key by nowCOAST.
 *
 * Two pictures of it are used, because neither will do alone.
 *
 * The visible one is a photograph: cloud is bright in it, whatever
 * height it is at, and it is black wherever the sun was down — half the
 * world at any hour, and unsure of itself for some way short of that.
 *
 * The infrared one sees by the cloud's own cold, the same by night as by
 * day. What it shows is temperature, the colder the whiter: the top of a
 * cloud is cold because it is high, so high cloud is white and the sea
 * under a clear sky is dark. That is all there is to go on in it, and
 * two things follow that no rule can mend from one picture. Low cloud is
 * nearly as warm as the sea it lies on, and is missed — the decks that
 * cover most of the southern ocean are barely in it. And cold ground is
 * white too: a plateau four kilometres up, or the middle of a continent
 * in winter, wears a thin veil that is not there, mostly over snow,
 * which the map has white already.
 *
 * So the visible picture is believed where the sun was well up when it
 * was taken, the infrared where it was not, and each a share between —
 * see [field]. The globe draws cloud on its daylit side only, so what is
 * seen is the visible picture nearly everywhere, and the infrared in the
 * part of the morning the sun had not reached when the frame was made.
 *
 * Neither has any poles in it. The service answers for the mosaic as
 * far as sixty degrees; the pictures go on to seventy-three, each degree
 * seen more nearly along the ground, and stop. They are taken as far as
 * they go.
 *
 * Both rules were set and held against the photograph, on the one
 * morning all three could be had of the same places within the hour:
 * see [LOW] and [SEEN_LOW], which have the numbers.
 */
object LiveClouds {

    /** NOAA's nowCOAST, which serves the mosaic as a map of the whole world. */
    const val HOST = "https://nowcoast.noaa.gov/geoserver/satellite/wms"

    /** The longwave infrared mosaic: the one that sees at night. */
    const val LAYER = "global_longwave_imagery_mosaic"

    /** And the visible one: the one that sees low cloud, by day. */
    const val VISIBLE = "global_visible_imagery_mosaic"

    /**
     * Half a degree a pixel, which is the daily photograph's size and
     * about what the mosaic has to give: twenty-seven kilobytes for an
     * hour's infrared and eighteen for its visible, half of which is
     * night.
     */
    const val WIDTH = 720
    const val HEIGHT = 360

    /** A stamp: enough of a picture to be sent the hour with. */
    const val ASK_WIDTH = 16
    const val ASK_HEIGHT = 8

    /** The frames are an hour apart. */
    const val HOUR_MS = 3_600_000L

    /**
     * The request for the frame of an hour, or with no hour for the
     * newest frame the service has.
     *
     * The newest is not the hour just gone. A frame is put together
     * from five satellites and published when it is ready, most of an
     * hour after the hour it is of, and which hour that is cannot be
     * worked out from the clock: the service says — see [frameTime].
     */
    fun url(hourMs: Long? = null, width: Int = WIDTH, height: Int = HEIGHT, layer: String = LAYER): String =
        "$HOST?service=WMS&version=1.3.0&request=GetMap&styles=&crs=EPSG:4326" +
            "&bbox=-90,-180,90,180&width=$width&height=$height&layers=$layer&format=image/jpeg" +
            (hourMs?.let { "&time=" + stamp(it) } ?: "")

    /** The request for the visible picture of an hour. */
    fun visible(hourMs: Long): String = url(hourMs, layer = VISIBLE)

    /**
     * The question asked before a frame is: which hour is the newest.
     *
     * The same request for a picture sixteen pixels wide, three hundred
     * bytes of it, because the answer wanted is not in the picture — it
     * is in what the service says beside it. A clock asking after a
     * frame that has not been published yet is then told so for three
     * hundred bytes and not for twenty-seven thousand.
     */
    fun ask(): String = url(null, ASK_WIDTH, ASK_HEIGHT)

    /** An instant the way the service writes one: 2026-11-24T05:00:00.000Z. */
    fun stamp(ms: Long): String {
        val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = ms
        return String.format(
            Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d.000Z",
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH) + 1,
            utc.get(java.util.Calendar.DAY_OF_MONTH),
            utc.get(java.util.Calendar.HOUR_OF_DAY),
            utc.get(java.util.Calendar.MINUTE),
            utc.get(java.util.Calendar.SECOND)
        )
    }

    private val SAID = Regex("""time=(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z""")

    /**
     * Which instant a frame is of, from what the service said in the
     * `Warning` header beside it and the hour that was asked for, if one
     * was.
     *
     * The service answers every request with a picture, and says in
     * that header when the picture is not of the hour asked for. Read
     * off the service on 24 November 2026, all three:
     *
     *  - no hour asked: `99 Default value used: time=2026-11-24T05:00:00.000Z
     *    ISO8601 (satellite:global_longwave_imagery_mosaic)` — the newest
     *    frame, and that is its hour;
     *  - an hour it has: nothing said, and the picture is that hour's;
     *  - an hour it has not, near one it has: `99 Nearest value used:
     *    time=2026-11-24T01:00:00.000Z ISO8601 (…)`, with the picture of
     *    the hour it names, which is not the one asked for;
     *  - an hour nowhere near: `99 No nearest value found on
     *    satellite:global_longwave_imagery_mosaic: time`, with a blank
     *    picture.
     *
     * So: the hour it names when it names one; the hour asked for when
     * it says nothing; and no hour at all — null, a frame to be refused
     * — when it says something with no hour in it, or says nothing to a
     * request that named none. A picture of an hour nobody can name is
     * not live cloud, it is cloud.
     */
    fun frameTime(warning: String?, askedMs: Long?): Long? {
        if (warning.isNullOrBlank()) return askedMs
        val said = SAID.find(warning) ?: return null
        val (year, month, day, hour, minute, second) = said.destructured
        val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
        return utc.timeInMillis
    }

    // -------------------------------------------------------------- the rule

    /**
     * At this grey and above a pixel is a place the mosaic has nothing
     * for. The service paints those white, and the picture is a JPEG, so
     * its white has a grain: measured against the same frame asked for
     * as a PNG with its nothing left transparent, every transparent
     * pixel read 243 or more here, and one pixel in six hundred that was
     * not transparent read 250 or more.
     */
    const val NO_DATA = 240

    /**
     * How much of a row is its clear sky: the darkest tenth of it.
     *
     * A grey means nothing by itself. The sea under a clear sky is 60
     * at the equator and 125 at sixty-five north, because it is colder
     * there, and a cloud is a place colder than the clear sky *of its
     * own latitude*. So each row is asked what its clear sky looks like,
     * and the answer is its warmest places: somewhere along every
     * parallel the sky is clear over the sea.
     */
    const val CLEAR_SHARE = 0.10

    /** A row has to have this much of itself to be asked. */
    const val ROW_LEAST = 0.25

    /** And the answer is taken with its neighbours', this far either way. */
    const val REACH_DEG = 8.0

    /**
     * How much whiter than its row's clear sky a place is before it is
     * any cloud at all, and before it is the thickest there is.
     *
     * Set by the photograph. On 24 November 2026 the day's photograph
     * and five hourly frames were had at once, and where the satellite
     * that takes the photograph passed within the hour of a frame — a
     * strip fifteen degrees wide for each, between forty-five north and
     * south, twenty-five thousand pixels in all — these two numbers are
     * the pair at which the infrared finds as much cloud as the
     * photograph's rule does: a mean of 32 out of 255 against its 31.
     * With 18 and 75 it found 45, with 30 and 110 it found 23.
     *
     * As much, and not the same. Place by place the two agree as two
     * different instruments do: a correlation of 0.76 and 0.64 in the
     * two strips that are mostly Pacific, 0.30 to 0.55 in the three
     * over China and Borneo, Burma and Sumatra, and India, 0.53 over
     * all five. What this finds and the photograph does not is high
     * thin cloud and cold ground; what the photograph finds and this
     * does not is low cloud, and the sun on the sea.
     *
     * A fixed step above the row's clear sky, and not a share of how
     * far the row's greys run. That was tried, for the sake of the
     * southern ocean, where this finds a fifth of the photograph's
     * cloud: it found as much as the photograph there and in the wrong
     * places, agreeing at 0.04 where this agrees at 0.10. More cloud
     * and no more truth; the low cloud is the visible picture's to find.
     */
    const val LOW = 25
    const val HIGH = 90

    /** Over how many degrees the cloud is thinned towards where the mosaic stops. */
    const val FADE_DEG = 4.0

    /** A pixel's grey. The picture is a grey one, so any of the three will do. */
    fun grey(argb: Int): Int = (argb shr 8) and 0xFF

    /**
     * The clear sky of each row of a frame, as a grey, from the top row
     * down — see [CLEAR_SHARE] — or not a number for a row the mosaic
     * has too little of to say.
     */
    fun clearSky(pixels: IntArray, width: Int, height: Int): FloatArray {
        val own = FloatArray(height.coerceAtLeast(0)) { Float.NaN }
        if (width <= 0 || height <= 0 || pixels.size != width * height) return own
        val counts = IntArray(NO_DATA)
        for (y in 0 until height) {
            counts.fill(0)
            var there = 0
            val base = y * width
            for (x in 0 until width) {
                val g = grey(pixels[base + x])
                if (g < NO_DATA) {
                    counts[g]++
                    there++
                }
            }
            if (there <= width * ROW_LEAST) continue
            // The grey a tenth of the way up the row's own, darkest first.
            val past = (there * CLEAR_SHARE).toInt()
            var seen = 0
            var g = 0
            while (g < NO_DATA - 1 && seen + counts[g] <= past) {
                seen += counts[g]
                g++
            }
            own[y] = g.toFloat()
        }
        val reach = Math.round(REACH_DEG * height / 180.0).toInt()
        val clear = FloatArray(height) { Float.NaN }
        for (y in 0 until height) {
            if (own[y].isNaN()) continue
            var sum = 0f
            var n = 0
            for (k in maxOf(0, y - reach)..minOf(height - 1, y + reach)) {
                if (!own[k].isNaN()) {
                    sum += own[k]
                    n++
                }
            }
            clear[y] = sum / n
        }
        return clear
    }

    /**
     * How much cloud is over a pixel of this [grey] in a row whose clear
     * sky is [clear], out of 255: none up to [LOW] above it, the
     * thickest the daily veil ever is from [HIGH] above it, and a curve
     * with no corner in it between.
     */
    fun cover(grey: Int, clear: Float): Int {
        if (grey >= NO_DATA) return 0
        // A row with no clear sky to measure from is not a number, and
        // not a number goes through all of this to no cloud.
        val t = ((grey - clear - LOW) / (HIGH - LOW)).coerceIn(0f, 1f)
        return (t * t * (3f - 2f * t) * SatelliteClouds.THICKEST).toInt()
    }

    /**
     * How much of each row's cloud is kept: all of it, less towards a
     * row the mosaic has nothing for.
     *
     * The mosaic stops at a line, seventy-three degrees from the
     * equator, and cloud that stops at a line on a globe is a cap drawn
     * with a compass. Asked of the picture and not written down as a
     * latitude, because where it stops is the service's to change.
     */
    fun rowsKept(clear: FloatArray): FloatArray {
        val height = clear.size
        val reach = Math.round(FADE_DEG * height / 180.0).toInt().coerceAtLeast(1)
        val kept = FloatArray(height)
        for (y in 0 until height) {
            if (clear[y].isNaN()) continue
            var off = reach
            for (k in 1 until reach) {
                val above = y - k
                val below = y + k
                if ((above >= 0 && clear[above].isNaN()) || (below < height && clear[below].isNaN())) {
                    off = k
                    break
                }
            }
            val t = off.toFloat() / reach
            kept[y] = t * t * (3f - 2f * t)
        }
        return kept
    }

    // ------------------------------------------------------ the visible one

    /**
     * The grey of the visible picture at which a place starts to be
     * cloud, and at which it is the thickest there is.
     *
     * Set the way [LOW] and [HIGH] were, on the same morning in the
     * same five strips: at these two the visible picture finds as much
     * cloud as the photograph does — a mean of 30 out of 255 against
     * its 31 — and agrees with it place by place at 0.64, where the
     * infrared agreed at 0.53. Further south, in the storm track
     * between forty-five and fifty-five, it agrees at 0.67 where the
     * infrared managed 0.43, and between fifty-five and sixty-five at
     * 0.50 against 0.10: that is the low cloud.
     *
     * A grey by itself this time, with nothing taken off for the row.
     * The service has already evened the picture out for the height of
     * the sun — measured: the same greys from a sun twenty degrees up
     * to one overhead — and the clear sea is 55 to 65 in it at every
     * latitude. Tried with the brightness of the map underneath taken
     * off, so that a desert would not pass for thin cloud: it agreed no
     * better, 0.65, and is not done.
     */
    const val SEEN_LOW = 113
    const val SEEN_HIGH = 193

    /**
     * How high the sun has to have been for the visible picture to be
     * believed at all, and to be believed outright, as the cosine of
     * how far it stood from overhead: nine degrees up, and twenty.
     *
     * Measured on three frames: above twenty degrees the picture's
     * greys do not change with the sun's height, and below it they
     * sink — to four fifths at fifteen degrees, two thirds at nine,
     * and two fifths with the sun on the horizon.
     */
    const val SUN_LEAST = 0.15
    const val SUN_ENOUGH = 0.35

    /** How much cloud the visible picture shows at a pixel of this [grey], out of 255. */
    fun seen(grey: Int): Int {
        if (grey >= NO_DATA) return 0
        val t = ((grey - SEEN_LOW).toFloat() / (SEEN_HIGH - SEEN_LOW)).coerceIn(0f, 1f)
        return (t * t * (3f - 2f * t) * SatelliteClouds.THICKEST).toInt()
    }

    /**
     * How far the visible picture is believed where the sun stood this
     * far from overhead, as that angle's cosine: not at all, wholly, or
     * a share.
     */
    fun daylight(cosine: Double): Float {
        val t = ((cosine - SUN_LEAST) / (SUN_ENOUGH - SUN_LEAST)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    // ------------------------------------------------------------ the field

    /**
     * An hour as cloud and nothing else: how much there is over each
     * pixel, out of 255, read by [SatelliteClouds.cloudAt] exactly as
     * the daily photograph's is.
     *
     * From the [infrared] picture, and the [visible] one of the same
     * hour if there is one and it is the same size, with the place the
     * sun stood over when they were taken. Each pixel is the visible
     * picture's answer by as much as the sun was up there — see
     * [daylight] — and the infrared's by the rest; where the visible
     * picture has nothing, the infrared's alone. Thinned towards the
     * end of the mosaic, which is the same end for both.
     */
    fun field(
        infrared: IntArray,
        visible: IntArray?,
        width: Int,
        height: Int,
        sunLatitude: Double,
        sunLongitude: Double
    ): ByteArray {
        val out = ByteArray(infrared.size)
        if (width <= 0 || height <= 0 || infrared.size != width * height) return out
        val clear = clearSky(infrared, width, height)
        val kept = rowsKept(clear)
        val day = visible?.takeIf { it.size == infrared.size }
        // The sun's height at a place is two numbers of its latitude
        // and one of its longitude: worked out a row and a column at a
        // time, not a quarter of a million times.
        val across = DoubleArray(if (day == null) 0 else width) { x ->
            Math.cos(Math.toRadians(-180.0 + (x + 0.5) * 360.0 / width - sunLongitude))
        }
        val sunSin = Math.sin(Math.toRadians(sunLatitude))
        val sunCos = Math.cos(Math.toRadians(sunLatitude))
        for (y in 0 until height) {
            val share = kept[y]
            if (share <= 0f) continue
            val latitude = Math.toRadians(90.0 - (y + 0.5) * 180.0 / height)
            val up = Math.sin(latitude) * sunSin
            val round = Math.cos(latitude) * sunCos
            val base = y * width
            for (x in 0 until width) {
                var cloud = cover(grey(infrared[base + x]), clear[y]).toFloat()
                if (day != null) {
                    val g = grey(day[base + x])
                    if (g < NO_DATA) cloud += (seen(g) - cloud) * daylight(up + round * across[x])
                }
                out[base + x] = (cloud * share).toInt().toByte()
            }
        }
        return out
    }
}
