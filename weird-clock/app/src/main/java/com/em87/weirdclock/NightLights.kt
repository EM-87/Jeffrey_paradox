package com.em87.weirdclock

import java.util.Locale
import java.util.TimeZone

/**
 * The lights of the earth at night, photographed from orbit, as a
 * request and a set of rules.
 *
 * The globe's night side has worn the same picture of the city lights
 * since it arrived — the Black Marble of 2016, fixed. This puts last
 * night's on it. NASA's Global Imagery Browse Services publish the VIIRS
 * Day/Night Band every day, free and keyless, in two readings of the same
 * instrument:
 *
 *  - **Clean**: the Black Marble product proper (VNP46A2 and its NOAA-20
 *    twin), with the moon and the air taken out and the nights lost to
 *    cloud filled from the nights around them. Two or three days behind.
 *  - **Raw**: what the sensor saw that night (VNP46A1), aurora, moonlit
 *    cloud, snow and fire included. On purpose: those are things that
 *    happened, not noise. Within the day, and still filling in while the
 *    satellite is flying over.
 *
 * Both are served through the same colour map, which was looked at
 * before anything here trusted it: a hundred and eighty greys from 7 to
 * 255 against radiance, monotonic, and a transparent "no data". So a
 * pixel's brightness *is* its radiance, on one scale for both layers, and
 * the difference of the two is a difference of radiance — which is what
 * [residue] relies on.
 *
 * **Every pixel was taken at about half past one in the morning, local
 * time**, as the satellite came over; and the day a picture is filed
 * under is the UTC day the orbits flew in. That second fact was checked
 * rather than assumed: a day's picture fills in from the east, starting
 * where the UTC day began, and places whose local night is long over are
 * still blank until the UTC clock reaches them. See [dayFor].
 *
 * The fetching and the keeping are [NightLightStore]. What is here is the
 * URLs and the rules that turn a grey picture into light on a globe.
 */
object NightLights {

    /** Where NASA serves it: the same door the clouds come through. */
    const val HOST = SatelliteClouds.HOST

    /**
     * Which products, by satellite.
     *
     * NOAA-20 first because it is the one that is there: in the week this
     * was written the Suomi NPP layers had whole orbits missing and ran a
     * day further behind. Suomi NPP is the fallback for a day NOAA-20 has
     * nothing for. The identifiers were read off the service's own
     * capabilities, not typed from memory.
     */
    const val CLEAN_NOAA20 = "VIIRS_NOAA20_GapFilled_BRDF_Corrected_DayNightBand_Radiance"
    const val CLEAN_SNPP = "VIIRS_SNPP_GapFilled_BRDF_Corrected_DayNightBand_Radiance"
    const val RAW_NOAA20 = "VIIRS_NOAA20_DayNightBand_At_Sensor_Radiance"
    const val RAW_SNPP = "VIIRS_SNPP_DayNightBand_At_Sensor_Radiance"

    /**
     * How big a picture to ask for.
     *
     * Twice the clouds. A city at night is a point, and at 720 across the
     * points of a country run together into a smear; at 1440 they are
     * towns. A quarter of a megabyte for the clean night and half a one
     * for the raw, once a day each.
     */
    const val WIDTH = 1440
    const val HEIGHT = 720

    /** The whole request: one equirectangular picture of the world, in a layer. */
    fun url(layer: String, day: String): String =
        "$HOST?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0" +
            "&LAYERS=$layer&CRS=EPSG:4326&BBOX=-90,-180,90,180" +
            "&WIDTH=$WIDTH&HEIGHT=$HEIGHT&FORMAT=image/png" +
            "&TIME=$day"

    /** Where to ask for a day of the clean layer, in order. */
    fun cleanUrls(day: String): List<String> = listOf(url(CLEAN_NOAA20, day), url(CLEAN_SNPP, day))

    /** And of the raw one. */
    fun rawUrls(day: String): List<String> = listOf(url(RAW_NOAA20, day), url(RAW_SNPP, day))

    /**
     * The day a globe showing [atMs] wants, which is the UTC date of that
     * instant and nothing cleverer.
     *
     * A picture filed under a day holds the orbits flown during that UTC
     * day, each place photographed at its own half past one in the
     * morning. So the night that *ended* at any place on the morning of
     * day D, local time, is in day D's file for the places east of
     * Greenwich — their half past one falls before midnight UTC — and in
     * day D's file for the places west of it too, later in the same UTC
     * day. The one rule that was wrong was the obvious one: taking the
     * local date of the night, which files Tokyo's Tuesday night under
     * Wednesday and is a day off across all of Asia and Oceania.
     *
     * What the store does with a day that is not there yet is its own
     * business — it shows the day before — so this does not ask thirty
     * hours back the way the clouds do.
     */
    fun dayFor(atMs: Long): String {
        val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = atMs
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH) + 1,
            utc.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /** Whether a day is the one the satellite is still flying over. */
    fun stillFilling(day: String, nowMs: Long): Boolean = day == dayFor(nowMs)

    /** Midnight UTC at the start of [day], or null for a day that is not one. */
    fun dayMs(day: String): Long? = DailyImageStore.msOf(day)

    /**
     * How many days behind the instant shown a picture of [day] is, or
     * null for a day that is not a day.
     */
    fun lagDays(day: String, atMs: Long): Int? {
        val start = dayMs(day) ?: return null
        val shown = dayMs(dayFor(atMs)) ?: return null
        return ((shown - start) / DailyImageStore.DAY_MS).toInt()
    }

    /**
     * How far behind is ordinary, in days, for each layer.
     *
     * The clean product takes two or three days to make; the raw one is
     * last night's. Further behind than this and the globe says so on its
     * face rather than only in the settings — see [overdue].
     */
    const val CLEAN_LAG_DAYS = 3
    const val RAW_LAG_DAYS = 1

    /** Whether a picture this many days behind is worth a word on the globe. */
    fun overdue(raw: Boolean, lagDays: Int): Boolean =
        lagDays > if (raw) RAW_LAG_DAYS else CLEAN_LAG_DAYS

    // ---------------------------------------------------------- the glow

    /** Which of the layers are on, as the setting spells it. */
    enum class Mode(val key: String, val clean: Boolean, val raw: Boolean) {
        NONE("none", false, false),
        CLEAN("clean", true, false),
        RAW("raw", false, true),
        BOTH("both", true, true);

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: NONE
        }
    }

    /**
     * How bright a pixel of the picture is, out of 255.
     *
     * Grey in, grey out: the colour map is grey, so the three channels
     * agree and the green one is as good as any. A transparent pixel is a
     * place nobody flew over, which is dark.
     */
    fun luminance(argb: Int): Int =
        if (argb ushr 24 == 0) 0 else (argb shr 8) and 0xFF

    /**
     * Where the sea ends and light begins, on the grey scale, for each
     * reading of the night.
     *
     * Measured, not chosen: the colour map's floor is 7 for no radiance,
     * and on a real night's pictures the open ocean reads up to 48 in
     * the clean product (its own noise) and up to 89 in the raw one (the
     * air's glow and the moon), while the dimmest towns read from the
     * sixties and the nineties respectively. A floor of 14 was tried
     * first and painted the whole Pacific with orange and cyan speckle.
     */
    const val CLEAN_FLOOR = 56
    const val RAW_FLOOR = 96

    /**
     * The curve from grey to glow, which is not a straight line.
     *
     * Under one, so a village is a glint and not nothing: the scale runs
     * to 255 for the brightest city centres and almost everything that is
     * lit at all is in its bottom fifth. A straight line drew the capitals
     * and nothing else.
     */
    const val CURVE = 0.55

    /** And how solid the brightest light is allowed to be, out of 255. */
    const val BRIGHTEST = 235

    /** How much light is in a pixel of the picture, out of 255, above [floor]. */
    fun glow(lum: Int, floor: Int = CLEAN_FLOOR): Int {
        if (lum <= floor) return 0
        val lit = (lum - floor) / (255.0 - floor)
        return (Math.pow(lit, CURVE) * BRIGHTEST).toInt().coerceIn(0, 255)
    }

    /**
     * What is in the raw night and not in the clean one: aurora, moonlit
     * cloud, snow, fire, and a city whose lights were on last night and
     * not on average — out of 255, above a [floor].
     *
     * A difference and not a ratio, because both are on the same scale of
     * radiance; and above a floor, because the raw night is brighter
     * everywhere than the clean one — the air's glow, the sensor's stray
     * light and, above all, the moon. The floor is not a constant. It was
     * one, 88, measured on a waning-moon night where open sea read 89 at
     * worst; on the full moon a fortnight before, the same sea read 223
     * at the ninety-fifth percentile, and a fixed floor would have turned
     * the whole dark side cyan. So it is read off each night's own
     * picture — see [residueFloor].
     */
    fun residue(rawLum: Int, cleanLum: Int, floor: Int): Int {
        val over = rawLum - cleanLum - floor
        if (over <= 0) return 0
        val lit = over / (255.0 - floor).coerceAtLeast(1.0)
        return (Math.pow(lit, CURVE) * BRIGHTEST).toInt().coerceIn(0, 255)
    }

    /**
     * Where the raw night's background ends, on this night: the
     * ninety-fifth percentile of its grey over places the clean night
     * says are unlit, plus a margin.
     *
     * "Unlit in the clean night" is the sea and the dark land, which is
     * what the moon and the air light up without anybody's help, and it
     * is read off the clean picture rather than off a map because the
     * clean picture is to hand and a map is not. Only places the
     * satellite flew over count — the raw picture of a day still in
     * progress is transparent where it has not been yet. The margin is
     * two steps of the colour map; the least floor is for a picture
     * with almost nothing in it, where a percentile means little.
     *
     * Measured: on the new moon of October 2026 this gives 68, on the
     * waning moon of the 2nd of November 93, and on the full moon of the
     * 26th of October 240 — which is right, because on a full-moon night
     * the raw layer's extras are drowned in moonlight and nothing but
     * the very brightest of them is a difference worth painting.
     */
    const val RESIDUE_PERCENTILE = 0.95
    const val RESIDUE_MARGIN = 16
    const val RESIDUE_FLOOR_LEAST = 32

    fun residueFloor(
        rawLum: ByteArray,
        rawWidth: Int,
        rawHeight: Int,
        cleanLumAt: (u: Double, v: Double) -> Int
    ): Int {
        val histogram = IntArray(256)
        var counted = 0
        for (y in 0 until rawHeight) {
            val v = (y + 0.5) / rawHeight
            val row = y * rawWidth
            for (x in 0 until rawWidth) {
                val raw = rawLum[row + x].toInt() and 0xFF
                if (raw == 0) continue
                if (cleanLumAt((x + 0.5) / rawWidth, v) > CLEAN_FLOOR) continue
                histogram[raw]++
                counted++
            }
        }
        if (counted == 0) return RESIDUE_FLOOR_LEAST
        val wanted = (counted * RESIDUE_PERCENTILE).toInt()
        var seen = 0
        var percentile = 255
        for (grey in 0..255) {
            seen += histogram[grey]
            if (seen > wanted) {
                percentile = grey
                break
            }
        }
        return (percentile + RESIDUE_MARGIN).coerceIn(RESIDUE_FLOOR_LEAST, 255)
    }

    /**
     * The colour of a lit window from four hundred miles up, and the
     * colour of the things that are only in the raw night.
     *
     * Amber because that is what sodium and the warm end of LED look like
     * from orbit, and because it is not a colour the day map has anywhere.
     * The residue is the other way round the wheel, so the two can never
     * be mistaken for each other on the same square of ground.
     */
    const val AMBER = 0xFFFFC46A.toInt()
    const val CYAN = 0xFF6AFFD2.toInt()

    /** One pixel of glow at [alpha], in [colour], faded at the limb like the clouds. */
    fun tint(colour: Int, alpha: Int, rho: Double): Int {
        val a = (alpha * SatelliteClouds.edge(rho)).toInt().coerceIn(0, 255)
        return (a shl 24) or (colour and 0x00FFFFFF)
    }

    /**
     * One pixel of the lights disc, for a mode.
     *
     * The amber from whichever layer is the lights — the clean one when
     * it is on, the raw one when it is on alone — and, with both on, the
     * residue in cyan laid over the amber. Laid *over*: a place that is
     * both lit and different shows the difference, which is the thing
     * worth seeing.
     */
    fun pixel(mode: Mode, cleanLum: Int, rawLum: Int, rho: Double, residueFloor: Int): Int =
        when (mode) {
            Mode.NONE -> 0
            Mode.CLEAN -> tint(AMBER, glow(cleanLum, CLEAN_FLOOR), rho)
            Mode.RAW -> tint(AMBER, glow(rawLum, RAW_FLOOR), rho)
            Mode.BOTH -> over(
                tint(CYAN, residue(rawLum, cleanLum, residueFloor), rho),
                tint(AMBER, glow(cleanLum, CLEAN_FLOOR), rho)
            )
        }

    /** [top] laid over [bottom], both premultiplied by nothing: plain source-over. */
    fun over(top: Int, bottom: Int): Int {
        val ta = (top ushr 24) / 255.0
        val ba = (bottom ushr 24) / 255.0
        val a = ta + ba * (1 - ta)
        if (a <= 0.0) return 0
        fun channel(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return Math.round((t * ta + b * ba * (1 - ta)) / a).toInt().coerceIn(0, 255)
        }
        return (Math.round(a * 255).toInt().coerceIn(0, 255) shl 24) or
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
