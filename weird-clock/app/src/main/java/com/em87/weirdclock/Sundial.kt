package com.em87.weirdclock

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The oldest clock there is, and the only one in here whose hour lines are
 * not evenly spaced.
 *
 * A sundial is a shadow and a set of lines to read it against, and almost
 * everything about it follows from one decision that was made three
 * thousand years before anybody wrote it down: the thing casting the
 * shadow — the *style*, the edge of the gnomon — is laid parallel to the
 * earth's axis. Once it is, the shadow's direction depends only on how far
 * round the sun has got today and not at all on the time of year, which is
 * the whole reason a sundial can be read at all. It also means the hour
 * lines are a projection of an even fifteen degrees an hour onto a plate
 * that is not square to it, and the projection is what makes them uneven.
 *
 * Which is why latitude is not a decoration on this face. It is the angle
 * the style stands at, and it is inside the formula for every hour line.
 * A dial made for Edinburgh reads an hour wrong in Cairo, and every real
 * dial in the world has its latitude cut into it somewhere.
 *
 * Everything here is arithmetic and none of it needs a screen. The drawing
 * is [SundialView].
 */
object Sundial {

    /**
     * Which surface the plate lies on, which is the whole taxonomy of
     * sundials that matters.
     *
     * These are not styles of the same object. They are three different
     * instruments that happen to tell the same time, and each one's hour
     * lines come out of its own formula.
     */
    enum class Kind(val key: String) {

        /**
         * Flat on the ground: the garden sundial everybody pictures.
         *
         * The style stands at the latitude. `tan θ = sin φ · tan H`, which
         * has the consequence nobody expects until they try it — at the
         * equator sin φ is nought and every hour line lies on top of the
         * noon line. A horizontal dial does not work on the equator at
         * all, and this app says so rather than drawing a fan that has
         * quietly collapsed into a stick.
         */
        HORIZONTAL("floor"),

        /**
         * On a wall facing the equator: south in the northern hemisphere.
         *
         * `tan θ = cos φ · tan H`, so it is the horizontal dial's mirror
         * in every sense — it fails at the poles instead of at the
         * equator, and its morning is on the side the other one's
         * afternoon is.
         */
        VERTICAL("wall"),

        /**
         * The plate parallel to the equator, the style straight through
         * it.
         *
         * The simplest dial there is and the only one whose hours are
         * even: fifteen degrees each, everywhere on earth, because the
         * plate is already square to the sun's daily circle. The catch is
         * that the sun spends half the year on the other side of it, so
         * it has two faces and neither works at the equinox — which is
         * exactly the sort of thing this app should show rather than hide.
         */
        EQUATORIAL("equatorial")
    }

    /** The outline of the plate. All three are ordinary on real dials. */
    enum class Plate(val key: String) {
        ROUND("round"),
        SQUARE("square"),
        OCTAGON("octagon")
    }

    /** Fifteen degrees an hour, which is the earth turning. */
    const val DEGREES_PER_HOUR = 15.0

    /**
     * A dial whose hour lines have collapsed onto one another.
     *
     * A horizontal dial on the equator and a vertical one at the pole are
     * both real objects that tell no time at all, and both are one line of
     * arithmetic away from any latitude somebody might be standing at. The
     * face has to be able to say "this dial does not work here" rather
     * than draw a fan with nothing in it.
     */
    fun collapses(kind: Kind, latitudeDeg: Double): Boolean = when (kind) {
        Kind.HORIZONTAL -> abs(sin(Math.toRadians(latitudeDeg))) < 0.09
        Kind.VERTICAL -> abs(cos(Math.toRadians(latitudeDeg))) < 0.09
        Kind.EQUATORIAL -> false
    }

    /**
     * Where the line for [hoursFromNoon] falls on the plate, in degrees
     * clockwise from the noon line.
     *
     * The noon line points away from the observer — up the plate — and the
     * afternoon is clockwise from it in the northern hemisphere. South of
     * the equator the sun goes the other way round the sky and so does the
     * dial, which falls out of the sign of the latitude and does not need
     * a rule of its own.
     */
    fun lineAngle(kind: Kind, latitudeDeg: Double, hoursFromNoon: Double): Double {
        val h = Math.toRadians(hoursFromNoon * DEGREES_PER_HOUR)
        return when (kind) {
            // Even, and the same everywhere. The plate is already square
            // to the sun's daily circle, so there is nothing to project.
            Kind.EQUATORIAL -> hoursFromNoon * DEGREES_PER_HOUR
            Kind.HORIZONTAL -> project(sin(Math.toRadians(latitudeDeg)), h)
            // The wall sees the sky the other way up, which is the minus.
            Kind.VERTICAL -> -project(cos(Math.toRadians(latitudeDeg)), h)
        }
    }

    /**
     * `tan θ = k · tan H`, done so it survives the two places tan does
     * not: six in the morning and six at night, where H is a right angle
     * and the line is at a right angle too whatever k is.
     */
    private fun project(k: Double, hourAngle: Double): Double {
        val x = cos(hourAngle)
        val y = k * sin(hourAngle)
        if (abs(x) < 1e-9 && abs(y) < 1e-9) return 0.0
        return Math.toDegrees(atan2(y, x))
    }

    /**
     * How far from noon a dial of this kind can still be read, in hours.
     *
     * Not a preference: it is where the sun goes behind the plate. A
     * horizontal dial loses the sun when it sets, which at a high enough
     * latitude in June is past nine at night; a wall facing the equator
     * loses it at six each way whatever the season, because after that
     * the sun is behind the wall. The equatorial plate is lit for as long
     * as the sun is up on its own side.
     */
    fun readableHours(kind: Kind, latitudeDeg: Double): Double = when (kind) {
        Kind.VERTICAL -> 6.0
        // Beyond eight hours from noon the lines are so bunched against
        // the noon line that they cannot be told apart, wherever the sun
        // happens to be.
        else -> {
            val lat = abs(latitudeDeg)
            (6.0 + lat / 15.0).coerceIn(6.0, 8.0)
        }
    }

    /**
     * Which whole hours a dial of this kind at this latitude gets a line
     * for, as hours from noon — negative in the morning.
     */
    fun hourLines(kind: Kind, latitudeDeg: Double): List<Int> {
        val reach = readableHours(kind, latitudeDeg)
        val most = reach.toInt()
        return (-most..most).toList()
    }

    /**
     * The angle the style stands at, above the plate, in degrees.
     *
     * The one number cut into every real dial. It is the latitude on a
     * horizontal plate — that is what makes the style parallel to the
     * axis — its complement on a wall, and a right angle on an equatorial
     * plate, where the style goes straight through.
     */
    fun styleAngle(kind: Kind, latitudeDeg: Double): Double = when (kind) {
        Kind.HORIZONTAL -> abs(latitudeDeg)
        Kind.VERTICAL -> 90.0 - abs(latitudeDeg)
        Kind.EQUATORIAL -> 90.0
    }

    /**
     * Where the shadow lies, given the time, or nothing if there is no
     * shadow to draw.
     *
     * The same function the hour lines come from, asked about a fractional
     * hour instead of a whole one — which is not a coincidence and is the
     * point of the whole instrument: the style is parallel to the axis, so
     * its shadow falls on the line for the hour and does not care what
     * month it is.
     *
     * Nothing when the sun is down, and nothing when the sun is behind the
     * plate: a wall dial is dark all morning if the wall faces the wrong
     * way, and the equatorial plate's top face is dark for the half of the
     * year the sun is south of the equator. A dial that draws a shadow it
     * could not have is a clock that lies in the one way a sundial never
     * does.
     */
    fun shadowAngle(
        kind: Kind,
        latitudeDeg: Double,
        hoursFromNoon: Double,
        sunAltitudeDeg: Double,
        sunDeclinationDeg: Double
    ): Double? {
        if (sunAltitudeDeg <= 0.0) return null
        if (abs(hoursFromNoon) > readableHours(kind, latitudeDeg)) return null
        if (kind == Kind.EQUATORIAL) {
            // The sun has to be on this side of the plate, which is the
            // same as saying its declination is on the observer's side.
            val north = latitudeDeg >= 0
            if ((sunDeclinationDeg >= 0) != north) return null
            if (abs(sunDeclinationDeg) < 0.4) return null
        }
        return lineAngle(kind, latitudeDeg, hoursFromNoon)
    }

    /**
     * How long the shadow is, as a share of the plate's radius.
     *
     * Not the real projection — the real one goes to infinity at sunrise,
     * which is true and useless. This is the honest shape of it, a shadow
     * that is short when the sun is high and reaches the rim when it is
     * low, clamped where a real dial's plate ends.
     */
    fun shadowReach(sunAltitudeDeg: Double): Float {
        if (sunAltitudeDeg <= 0.0) return 0f
        val reach = 1.0 / tan(Math.toRadians(sunAltitudeDeg.coerceAtLeast(4.0)))
        return (reach / 6.0).coerceIn(0.28, 1.0).toFloat()
    }

    /**
     * How far off the phone is from a bearing, in degrees, signed.
     *
     * Negative means the target is to the left. For the mode where the
     * dial is not on a table but in your hand — see [alignBearing], which
     * says what the target is and why it is not the sun.
     */
    fun offBy(phoneBearingDeg: Double, targetBearingDeg: Double): Double {
        var d = targetBearingDeg - phoneBearingDeg
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }

    /**
     * Which way the plate's noon line has to point for the dial to be
     * read at all.
     *
     * The meridian: north from the northern hemisphere, south from the
     * southern one, because the noon line points away from the observer
     * and at noon the sun is behind them. Every dial ever built is set
     * this way and then left alone; the whole trade of a dialist is
     * getting this one line right.
     *
     * It is not the sun, and this used to be. Pointing a phone *at the
     * sun* is a qibla compass with a dial drawn on it — it tells you
     * where the sun is, which you can see, rather than how to hold the
     * instrument so it tells you the time. And the shadow was softened
     * while you were not pointing at it, which is a drawing apologising
     * for itself. What a dial in your hand does instead is simpler and
     * true: the shadow falls where the sun really puts it, the engraved
     * hour lines turn with the plate, and the time is right at the one
     * heading where the two agree. This says which heading that is.
     */
    fun alignBearing(latitudeDeg: Double): Double = if (latitudeDeg >= 0.0) 0.0 else 180.0

    /**
     * Where the style's shadow really falls on a plate held like this.
     *
     * [shadowInHand] answers the same question for a plate that is
     * *level* and only turned — which is most of the way there and is
     * where this started, because turning is the part somebody does on
     * purpose. But nobody holds a phone flat. It sits at twenty or thirty
     * degrees in the hand, and on a real dial that is not a small error:
     * tilt the plate and the style tilts with it, so it stops being
     * parallel to the earth's axis and its shadow stops falling on the
     * hour lines at all. The dial being wrong when it is not held level
     * is not a defect to hide — it is the reason a dial is set in stone
     * and left alone, and it is the one thing this mode is for showing.
     *
     * So the shadow is worked out from where the style actually is. The
     * style is a rod through the middle of the plate, lying in the noon
     * plane at the dial's own latitude above the face; the phone says
     * which way the plate's own three axes point in the world; the sun is
     * where the sun is. The shadow of the rod's tip is where the ray from
     * it away from the sun meets the plate, and the answer is that point's
     * bearing on the plate, clockwise from the noon line.
     *
     * Null when the sun is behind the plate or grazing it — a phone
     * turned face down has no shadow on its face, and the divisor here is
     * how far the sun is above the plate rather than above the horizon.
     *
     * [right], [top] and [normal] are the plate's own axes in world
     * coordinates — east, north and up — which is what a rotation matrix
     * from the phone's own sensor is three columns of. Handed in rather
     * than derived from three angles, because Euler angles have a sign
     * convention per platform and a matrix does not.
     */
    fun shadowOnPlate(
        sunAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        right: DoubleArray,
        top: DoubleArray,
        normal: DoubleArray,
        latitudeDeg: Double
    ): Double? {
        val alt = Math.toRadians(sunAltitudeDeg)
        val az = Math.toRadians(sunAzimuthDeg)
        val sun = doubleArrayOf(
            cos(alt) * sin(az), cos(alt) * cos(az), sin(alt)
        )
        val onto = dot(sun, normal)
        // Level with the plate or under it: nothing is cast on this face.
        if (onto <= GRAZING) return null
        // The style, in the plate's own axes: up the noon line and
        // inclined at the latitude the plate is engraved for. Its whole
        // point is to be parallel to the earth's axis when the plate is
        // level and pointing at the pole, and this is that vector written
        // in the plate's terms so that tilting the plate takes it with it.
        val lean = Math.toRadians(abs(latitudeDeg))
        val style = DoubleArray(3) { i ->
            top[i] * cos(lean) + normal[i] * sin(lean)
        }
        // Where the ray from the style's tip, away from the sun, crosses
        // the face of the plate.
        val t = dot(style, normal) / onto
        val landed = DoubleArray(3) { i -> style[i] - t * sun[i] }
        val x = dot(landed, right)
        val y = dot(landed, top)
        if (abs(x) < 1e-12 && abs(y) < 1e-12) return null
        return Math.toDegrees(atan2(x, y))
    }

    /**
     * The three axes of a plate lying flat with its noon line at that
     * bearing.
     *
     * For a phone that can say which way it is facing and not which way
     * it is leaning — an old one, or one whose rotation vector is not
     * worth reading. Flat is the assumption the whole compass mode used
     * to make about every phone.
     */
    fun levelPlate(bearingDeg: Double): Array<DoubleArray> {
        val a = Math.toRadians(bearingDeg)
        return arrayOf(
            doubleArrayOf(cos(a), -sin(a), 0.0),
            doubleArrayOf(sin(a), cos(a), 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )
    }

    /** How far above the plate the sun has to be to cast anything at all. */
    private const val GRAZING = 0.02

    private fun dot(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Whether a dial of this kind can be aligned by turning the phone.
     *
     * Only the one you lay flat. A wall dial is set on a wall and an
     * equatorial plate is tilted to the equator; neither is in the plane
     * a phone held level is in, so turning the phone tells you nothing
     * about either.
     */
    fun pointable(kind: Kind): Boolean = kind == Kind.HORIZONTAL

    /** Close enough to read the dial by. A real one is not fussier. */
    const val ALIGNED_DEGREES = 10.0

    /**
     * What is cut round the edge of it.
     *
     * Every sundial ever built has a motto and most of them are about how
     * little time you have left. That is not decoration on this face — it
     * is the face: somebody who chooses a clock that stops working when a
     * cloud goes over is not choosing it to find out what time it is.
     *
     * One a day, by the day of the year, so it changes without ever being
     * random — a motto that shuffles every time you look at the clock is a
     * fortune cookie, and a motto is supposed to be carved.
     */
    private val MOTTOES = arrayOf(
        "HORAS NON NVMERO NISI SERENAS",
        "VLTIMA LATET",
        "TEMPVS FVGIT",
        "SOL ME PROBAT VNVM",
        "VMBRA SVMVS",
        "SINE SOLE SILEO",
        "HORA FVGIT STAT IVS",
        "LENTE HORA CELERITER ANNI",
        "DVM SPECTAS FVGIO",
        "SERIVS EST QVAM COGITAS",
        "NVNC EST BIBENDVM",
        "VITA IN MOTV"
    )

    /** The motto for a given day of the year. */
    fun motto(dayOfYear: Int): String =
        MOTTOES[((dayOfYear - 1).coerceAtLeast(0)) % MOTTOES.size]

    /** All of them, for the test that checks nobody has typed a J or a U. */
    internal fun mottoes(): Array<String> = MOTTOES

    // ------------------------------------------------ what day it is

    /**
     * Which calendar the date under the plate is read in.
     *
     * Three, and not one of them is offered as a better calendar than
     * ours. The Julian is the one the plate would have been cut under —
     * every dial older than 1582 was made in it, Britain read dials by it
     * until 1752 — and the Egyptian is the oldest calendar anybody ever
     * did arithmetic in, which is what a shadow clock is for. Ptolemy
     * computed in it and so did Copernicus, a thousand years after the
     * last person in Egypt used it for anything.
     */
    enum class Reckoning(val key: String) {
        GREGORIAN("gregorian"),
        JULIAN("julian"),
        EGYPTIAN("egyptian");

        companion object {
            /**
             * Reading the stored answer, including the old yes-or-no.
             *
             * This was a switch called "Julian calendar" for eleven
             * versions, so every phone that ever turned it on has a
             * boolean written down and no string at all. [was] is that
             * boolean, and it is only consulted when there is no newer
             * answer.
             */
            fun of(key: String?, was: Boolean): Reckoning =
                entries.firstOrNull { it.key == key }
                    ?: if (was) JULIAN else GREGORIAN
        }
    }

    /**
     * The date cut under the plate, in whichever calendar it is read in.
     *
     * Day and month only, in ours and in Rome's — a dial carrying a year
     * would be a dial that had to be recut every January, and no dial
     * anywhere has ever had one on it. Egypt is the exception and cannot
     * help being one: its dates are *regnal*, counted from whichever king
     * is on the throne, so the year is not a number you can leave off. It
     * is written the way Egyptology writes it and the way the stone does
     * — the month of the season in Roman numerals, then the season, then
     * the day.
     *
     * [seasons] and [epagomenal] are handed in rather than looked up,
     * because this file is arithmetic and those are words in whatever
     * language the phone is set to.
     */
    fun dateLabel(
        atMs: Long,
        zoneOffsetMs: Int,
        reckoning: Reckoning,
        roman: Boolean,
        seasons: List<String> = emptyList(),
        epagomenal: String = ""
    ): String {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
        val year = calendar.get(java.util.Calendar.YEAR)
        var day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        var month = calendar.get(java.util.Calendar.MONTH) + 1
        fun figure(n: Int) = if (roman) Roman.of(n) else n.toString()
        when (reckoning) {
            Reckoning.JULIAN -> {
                val old = JulianCalendar.of(year, month, day)
                day = old.day
                month = old.month
            }
            Reckoning.EGYPTIAN -> {
                val date = EgyptianCalendar.dateOf(atMs, zoneOffsetMs, year)
                // The five days over at the end belong to no month at
                // all — the "days upon the year", on which five gods were
                // born — so they are named rather than numbered into one.
                if (date.epagomenal || date.season == null) {
                    return "$epagomenal ${figure(date.day)}"
                }
                val season = seasons.getOrNull(date.season.ordinal) ?: date.season.name
                // The month of the season is in Roman numerals whichever
                // way the numerals switch is set, because that is not this
                // app's choice: I Akhet, II Akhet, III Akhet, IV Akhet is
                // how every Egyptologist since Champollion has written it.
                return "${Roman.of(date.monthOfSeason)} $season ${figure(date.day)}"
            }
            Reckoning.GREGORIAN -> Unit
        }
        return if (roman) "${Roman.of(day)} \u00b7 ${Roman.of(month)}" else "$day / $month"
    }
}
