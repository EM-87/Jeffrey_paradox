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
 *    satellite is flying over. The one thing it saw that is taken out
 *    is the twilight at the edge of the summer — see [withoutTwilight].
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

    // ------------------------------------------------------ the twilight

    /**
     * The orbit the pictures are taken from, as far as it matters here.
     *
     * NOAA-20 and Suomi NPP go round the same sun-synchronous orbit:
     * tilted 98.7 degrees to the equator, crossing it northbound at
     * twenty-five past one in the afternoon by the sun and southbound,
     * on the night side, at twenty-five past one in the morning — half
     * past, as NASA rounds it and as this does. Read off NASA's page
     * for the mission and the WMO's register of satellites, not
     * remembered.
     */
    const val ORBIT_INCLINATION_DEG = 98.7

    /** The hour by the sun at which the satellite crosses the equator going south, in the night. */
    const val OVERPASS_HOUR = 1.5

    /**
     * The hour by the sun when the satellite stands over a latitude on
     * its way down the night side.
     *
     * Half past one at the equator and only there. The orbit leans
     * over the pole, so the satellite comes down out of the morning: it
     * is over seventy north a little after three, over eighty at half
     * past five, and at the top of its orbit — 81.3 degrees, which is
     * as far north as it goes — at half past seven. Going on south it
     * runs back into the evening before: seventy south at ten to
     * midnight. Beyond the top of the orbit there is no overhead, and
     * the hour is the hour at the top.
     */
    fun overpassHour(latitudeDeg: Double): Double {
        val round = Math.tan(Math.toRadians(latitudeDeg)) / Math.tan(Math.toRadians(ORBIT_INCLINATION_DEG))
        return OVERPASS_HOUR - Math.toDegrees(Math.asin(round.coerceIn(-1.0, 1.0))) / 15.0
    }

    /**
     * How far under the horizon the sun has to have been for a raw
     * picture to be a picture of the night, in degrees: astronomical
     * night, where the satellite stood.
     *
     * The raw night is not all night. Towards the summer pole the sun
     * is only just under the horizon when the satellite passes, and the
     * instrument, which can see by starlight, sees the sky itself
     * glowing. That was being painted as light: a solid ring of cyan
     * round Antarctica in November, at the latitude an aurora would be,
     * and in the north in spring and autumn a row of bright fins, one
     * for each pass, where the edge of the strip nearer the dawn is
     * later by the sun than the middle of it.
     *
     * Where it begins was read off real nights between March and
     * November 2026, both ends of the world. In the south, where the
     * pass is near midnight and every part of the strip sees much the
     * same sun, the picture's darkest tenth leaves the floor of the
     * scale with the sun between fourteen and thirteen degrees down by
     * this reckoning, and by nine the service has no picture at all.
     * In the north the fins reach further, and on the four nights that
     * were drawn with the line across them — in March, August and
     * twice in October — their tips came down to where the sun stood
     * eighteen degrees under the satellite, each time, and not past it.
     * So eighteen, which is also what an astronomer means by night.
     */
    const val TWILIGHT_LIMIT_DEG = -18.0

    /**
     * How high the sun stood at a latitude when the satellite came over
     * it on [day], in degrees above the horizon; null for a day that is
     * not one.
     *
     * The sun's declination at the middle of the UTC day the picture is
     * filed under, and the hour of [overpassHour].
     */
    fun sunAtOverpass(latitudeDeg: Double, day: String): Double? {
        val start = dayMs(day) ?: return null
        return sunAtOverpass(latitudeDeg, SolarTime.declinationDeg(start + DailyImageStore.DAY_MS / 2))
    }

    private fun sunAtOverpass(latitudeDeg: Double, declinationDeg: Double): Double =
        SolarTime.positionAt(latitudeDeg, (overpassHour(latitudeDeg) - 12.0) * 15.0, declinationDeg).altitudeDeg

    /**
     * Blanks the rows of a raw night's greys that were taken in
     * twilight, in place, and says how many there were.
     *
     * Blank is nought, which everything that reads these greys already
     * takes for "the satellite was not here": no glow, no residue, no
     * haze, not counted in any floor, and filled from the day before
     * where the day before has a night to offer.
     */
    fun withoutTwilight(lum: ByteArray, width: Int, height: Int, day: String): Int {
        if (width <= 0 || height <= 0 || lum.size != width * height) return 0
        val start = dayMs(day) ?: return 0
        val declination = SolarTime.declinationDeg(start + DailyImageStore.DAY_MS / 2)
        var blanked = 0
        for (y in 0 until height) {
            val latitude = 90.0 - (y + 0.5) * 180.0 / height
            if (sunAtOverpass(latitude, declination) <= TWILIGHT_LIMIT_DEG) continue
            java.util.Arrays.fill(lum, y * width, (y + 1) * width, 0.toByte())
            blanked++
        }
        return blanked
    }

    // ---------------------------------------------------------- the moon

    /**
     * How far under the horizon the moon has to have been for the sky
     * over a place to be read for its own glow, in degrees.
     *
     * Under the horizon, because moonlit snow is a surplus over the
     * clean night too, and a great deal brighter than an aurora: the
     * first moonlit nights the haze was tried on — the quarter moon of
     * the 2nd of November 2026, the gibbous one of the 30th of October
     * — it painted Greenland, whole, in the colour of the northern
     * lights. And six degrees under, because the moon is reckoned once
     * for a picture that took a day to take, in which time it moves
     * twelve degrees round the sky.
     */
    const val MOON_LIMIT_DEG = -6.0

    /**
     * How high the moon stood at a latitude when the satellite came
     * over it on [day], in degrees above the horizon; null for a day
     * that is not one. However thin: a crescent over an ice cap is
     * still more light than there is in an aurora.
     */
    fun moonAtOverpass(latitudeDeg: Double, day: String): Double? {
        val start = dayMs(day) ?: return null
        // The middle of the day, at the longitude where the sun's clock
        // then says the hour the satellite keeps over this latitude.
        val longitude = (overpassHour(latitudeDeg) - 12.0) * 15.0
        return SolarTime.moonPosition(latitudeDeg, longitude, start + DailyImageStore.DAY_MS / 2).altitudeDeg
    }

    /**
     * Which rows of a picture of [day] were taken with the moon down,
     * from the top; and with [also], on that day as well — the raw
     * night of a day in progress is filled in from the day before, and
     * a row has to have been moonless on both to be moonless.
     */
    fun moonlessRows(height: Int, day: String, also: String? = null): BooleanArray {
        val rows = BooleanArray(height.coerceAtLeast(0))
        for (y in rows.indices) {
            val latitude = 90.0 - (y + 0.5) * 180.0 / height
            val moon = moonAtOverpass(latitude, day) ?: continue
            val before = if (also == null) null else (moonAtOverpass(latitude, also) ?: continue)
            rows[y] = moon < MOON_LIMIT_DEG && (before == null || before < MOON_LIMIT_DEG)
        }
        return rows
    }

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

    // ----------------------------------------------------------- the haze

    /**
     * The glow of the sky itself: the raw night's surplus over the clean
     * one with the grain taken out of it, out of 255 for each pixel.
     *
     * [residue] finds the things that are a point: a fire, a fleet, a
     * town with its lights on. It does not find the aurora, and the
     * reason was measured on three moonless nights of November 2026,
     * against the pictures the app would have fetched. The oval was in
     * every one of them — a band right round the pole between sixty-two
     * and seventy-two north, over open sea as much as over land — and it
     * is faint: twenty-five greys over the clean night at its middle on
     * the most active of the three, where the floor that keeps the
     * oceans dark stood at seventy-seven. Four to nine parts in a hundred
     * of the band were painted at all.
     *
     * The floor was not wrong to be there. Pixel by pixel the raw night
     * is grainy, and anything lower speckles the Pacific. But the aurora
     * is not a pixel: it is a band a thousand miles long, and averaged
     * over a couple of degrees of sky it stands well clear of an average
     * of the same sky anywhere else. So this is that average — the mean
     * surplus over the flown-over, unlit places within [HAZE_REACH_DEG]
     * of each pixel — and it has a floor of its own, which can be much
     * lower: see [hazeFloor].
     *
     * Only unlit places are averaged, because a city's surplus is the
     * city's and would spread a halo round it; a place with fewer than a
     * quarter of its neighbours to go on has no haze at all. The picture
     * is the whole world, so the window runs round the back of it at the
     * date line and stops at the poles.
     *
     * And only places the clean night has a reading for — see [dark].
     * The first picture this rule drew had the oval in it and a disc of
     * the same colour sitting on the pole, streaked like a pinwheel,
     * and the disc was not light: the clean product is not made north
     * of eighty degrees, so everything the raw night read up there was
     * a surplus over nothing.
     *
     * And, given [rows], only rows of the picture that say true: the
     * ones the moon was down for — see [moonlessRows]. A row that does
     * not is neither averaged nor given a haze.
     *
     * Null if the two pictures are not the same shape, which they always
     * are: they are asked for at one size.
     */
    fun haze(
        rawLum: ByteArray,
        cleanLum: ByteArray,
        width: Int,
        height: Int,
        rows: BooleanArray? = null
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (rawLum.size != width * height || cleanLum.size != width * height) return null
        if (rows != null && rows.size != height) return null
        val reach = Math.round(HAZE_REACH_DEG * width / 360.0).toInt().coerceAtLeast(1)
        val across = 2 * reach + 1
        val enough = across * across / 4
        val out = ByteArray(width * height)
        // A window of rows, each already summed along itself, added to
        // and taken from a running total per column as the window moves
        // down: every pixel is touched a fixed number of times however
        // wide the window is.
        val rowSurplus = Array(across) { IntArray(width) }
        val rowCounted = Array(across) { IntArray(width) }
        val surplus = IntArray(width)
        val counted = IntArray(width)
        val oneSurplus = IntArray(width)
        val oneCounted = IntArray(width)
        for (y in 0 until height + reach) {
            val slot = y % across
            // The row leaving the window, if it ever held one.
            for (x in 0 until width) {
                surplus[x] -= rowSurplus[slot][x]
                counted[x] -= rowCounted[slot][x]
                rowSurplus[slot][x] = 0
                rowCounted[slot][x] = 0
            }
            if (y < height && (rows == null || rows[y])) {
                val base = y * width
                for (x in 0 until width) {
                    val raw = rawLum[base + x].toInt() and 0xFF
                    val clean = cleanLum[base + x].toInt() and 0xFF
                    val counts = dark(raw, clean)
                    oneCounted[x] = if (counts) 1 else 0
                    oneSurplus[x] = if (counts) (raw - clean).coerceAtLeast(0) else 0
                }
                // Along the row, round the back of the world.
                var s = 0
                var c = 0
                for (dx in -reach..reach) {
                    val x = Math.floorMod(dx, width)
                    s += oneSurplus[x]
                    c += oneCounted[x]
                }
                for (x in 0 until width) {
                    rowSurplus[slot][x] = s
                    rowCounted[slot][x] = c
                    surplus[x] += s
                    counted[x] += c
                    val leaving = Math.floorMod(x - reach, width)
                    val arriving = Math.floorMod(x + reach + 1, width)
                    s += oneSurplus[arriving] - oneSurplus[leaving]
                    c += oneCounted[arriving] - oneCounted[leaving]
                }
            }
            val centre = y - reach
            if (centre < 0 || (rows != null && !rows[centre])) continue
            val base = centre * width
            for (x in 0 until width) {
                if (counted[x] >= enough) {
                    out[base + x] = (surplus[x] / counted[x]).coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /** How far either side of a place the sky's glow is averaged over, in degrees. */
    const val HAZE_REACH_DEG = 1.0

    /**
     * Whether a place can say what the sky over it was doing: flown over
     * that night, read by the clean night, and unlit in it.
     *
     * Nought is "no reading" in both pictures — the colour map's floor
     * for no radiance is seven — and no reading is not darkness. In the
     * raw night it is a place the satellite has not been yet; in the
     * clean one it is everything north of eighty degrees in the months
     * that were looked at, where the product is not made at all.
     */
    private fun dark(raw: Int, clean: Int): Boolean =
        raw != 0 && clean != 0 && clean <= CLEAN_FLOOR

    /**
     * Where the haze's own background ends on this night: its
     * ninety-ninth percentile within [QUIET_LATITUDE] degrees of the
     * equator, and a little.
     *
     * Read off the part of the world where the only things in the raw
     * night that are not in the clean one are the air's own glow and
     * the moon — nobody has seen an aurora at fifty degrees and written
     * it off as noise. The floor for [residue] is read off the whole
     * world, the auroral zone included, which is the other half of why
     * it stood where it did: the thing it was meant to find was part of
     * what it measured as background.
     *
     * Of the places that can have a haze — see [dark], and [rows] as
     * [haze] takes them — and not of the whole band: the raw night of a
     * day in progress has only been flown over in strips, and a
     * percentile that counted the blanks between them as the darkest
     * sky there is would sink as the day began and speckle the strips
     * that are there. With next to nothing in the band to read — the
     * moon up over all of it, or the day hardly begun — the floor is
     * the top of the scale and nothing is painted: see
     * [HAZE_SAMPLE_LEAST].
     *
     * Measured on those three nights: twenty-six, twenty-five and
     * twenty-three, against an oval whose middle read twenty-five on the
     * most active of them and twelve on the quietest, and whose
     * brightest tenth read sixty-six and thirty-five. So 48, 30 and 22
     * parts in a hundred of the band are painted where 9, 4 and 4 were
     * — on an active night half the oval, in one piece, and on a quiet
     * one the brighter fifth of it, which is what somebody standing
     * under it would say too — and of the dark within fifty degrees of
     * the equator, one part in two hundred, faintly, on each. Where the
     * moon was up it is not read at all — see [MOON_LIMIT_DEG].
     */
    fun hazeFloor(
        haze: ByteArray,
        rawLum: ByteArray,
        cleanLum: ByteArray,
        width: Int,
        height: Int,
        rows: BooleanArray? = null
    ): Int {
        val size = width * height
        if (width <= 0 || height <= 0) return 255
        if (haze.size != size || rawLum.size != size || cleanLum.size != size) return 255
        if (rows != null && rows.size != height) return 255
        val histogram = IntArray(256)
        var n = 0
        val from = ((90.0 - QUIET_LATITUDE) / 180.0 * height).toInt().coerceIn(0, height)
        val until = ((90.0 + QUIET_LATITUDE) / 180.0 * height).toInt().coerceIn(0, height)
        for (y in from until until) {
            if (rows != null && !rows[y]) continue
            val base = y * width
            for (x in 0 until width) {
                if (!dark(rawLum[base + x].toInt() and 0xFF, cleanLum[base + x].toInt() and 0xFF)) continue
                histogram[haze[base + x].toInt() and 0xFF]++
                n++
            }
        }
        // Too little sky to say what its background is: a first strip
        // of a day in progress, or the last few rows the moon was down
        // for.
        if (n < size / HAZE_SAMPLE_LEAST) return 255
        return (percentile(histogram, n, HAZE_PERCENTILE) + HAZE_MARGIN).coerceIn(HAZE_FLOOR_LEAST, 255)
    }

    const val QUIET_LATITUDE = 50.0
    const val HAZE_PERCENTILE = 0.99
    const val HAZE_MARGIN = 2
    const val HAZE_FLOOR_LEAST = 8

    /** The least sky a floor is read off: one part in this many of the picture. */
    const val HAZE_SAMPLE_LEAST = 50

    /** The grey below which [share] of a histogram's [count] entries lie. */
    private fun percentile(histogram: IntArray, count: Int, share: Double): Int {
        val wanted = (count * share).toInt()
        var seen = 0
        for (grey in 0..255) {
            seen += histogram[grey]
            if (seen > wanted) return grey
        }
        return 255
    }

    /**
     * Where the sea ends and light begins in a raw night shown on its
     * own, read off that night: the ninety-fifth percentile of its grey
     * within [QUIET_LATITUDE] degrees of the equator, plus the same
     * margin as [residueFloor].
     *
     * It was one number, [RAW_FLOOR], measured on a waning-moon night
     * when open sea read 89 at worst. On the three moonless nights of
     * November this rule stood at 49 each time, and painted 38, 21 and
     * 16 parts in a hundred of the oval where a floor of 96 had painted
     * 6, 1 and 2 — of an oval the raw night is chosen for. Under the
     * full moon of the 26th of October a floor of 96 painted three
     * quarters of the dark between the tropics amber, and nine tenths
     * of it further north; the night's own floor was 245, and painted
     * one part in a hundred. The cities are in the count — there is no
     * clean night here to tell them by — and are too few to move a
     * ninety-fifth percentile.
     */
    fun rawFloor(rawLum: ByteArray, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0 || rawLum.size != width * height) return RAW_FLOOR
        val histogram = IntArray(256)
        var n = 0
        val from = ((90.0 - QUIET_LATITUDE) / 180.0 * height).toInt().coerceIn(0, height)
        val until = ((90.0 + QUIET_LATITUDE) / 180.0 * height).toInt().coerceIn(0, height)
        for (y in from until until) {
            val base = y * width
            for (x in 0 until width) {
                val raw = rawLum[base + x].toInt() and 0xFF
                if (raw == 0) continue
                histogram[raw]++
                n++
            }
        }
        if (n == 0) return RAW_FLOOR
        return (percentile(histogram, n, RESIDUE_PERCENTILE) + RESIDUE_MARGIN)
            .coerceIn(RESIDUE_FLOOR_LEAST, 255)
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
     *
     * And under the amber, the sky's own glow: [haze] above [hazeFloor]
     * on the same curve and in the same cyan, because it is the same
     * answer to "what is here only in the raw night" — the spread of it
     * where [residue] is the point. Under, because it is a wash a
     * thousand miles long and a town is a dot: laid over, it took the
     * towns beneath the oval off the map, which are how anybody knows
     * where the oval is. With no haze worked out its floor is the top of
     * the scale and it adds nothing.
     */
    fun pixel(
        mode: Mode,
        cleanLum: Int,
        rawLum: Int,
        rho: Double,
        residueFloor: Int,
        rawFloor: Int = RAW_FLOOR,
        haze: Int = 0,
        hazeFloor: Int = 255
    ): Int =
        when (mode) {
            Mode.NONE -> 0
            Mode.CLEAN -> tint(AMBER, glow(cleanLum, CLEAN_FLOOR), rho)
            Mode.RAW -> tint(AMBER, glow(rawLum, rawFloor), rho)
            Mode.BOTH -> {
                val point = residue(rawLum, cleanLum, residueFloor)
                val lamp = glow(cleanLum, CLEAN_FLOOR)
                val wash = residue(haze, 0, hazeFloor)
                // Most of the world is dark in all three, and this is
                // asked a quarter of a million times a bake.
                if (point == 0 && lamp == 0 && wash == 0) {
                    0
                } else {
                    val lamps = tint(AMBER, lamp, rho)
                    over(tint(CYAN, point, rho), if (wash == 0) lamps else over(lamps, tint(CYAN, wash, rho)))
                }
            }
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
