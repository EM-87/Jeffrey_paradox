package com.em87.weirdclock

import kotlin.math.abs

/**
 * The weather, agreed rather than looked up.
 *
 * This clock has never touched the network. Adding weather to it is the
 * first time it will, and the shape of this file is the owner's own
 * instruction about how: *"lo principal yo creo es que lo consultes donde
 * lo consultes, igual mañana se cae"* — whichever service you ask, it may
 * be gone tomorrow. So there is no service. There is a list of them, and a
 * reading is what enough of them agree on.
 *
 * That is not belt and braces, it is the actual design. A clock that reads
 * one API is a clock that stops telling you the weather on the day that
 * API changes its JSON, moves behind a key, or dies — and the person it
 * stops working for has no way of knowing which. Ask three, keep what they
 * agree on, and losing two of them costs nothing but confidence.
 *
 * **Agreement is a median and a tolerance, never an average.** Three
 * sources saying 20°, 20° and 40° average to 26.7, which is a temperature
 * nobody reported and nowhere near the truth; the median is 20, which two
 * of them measured. The odd one out is then discarded rather than blended,
 * and how many were left is reported — see [Trust] — because "two services
 * agree" and "one service is guessing" are different claims and a clock
 * should not make them look the same.
 *
 * Everything here is arithmetic on numbers somebody else fetched. The
 * fetching, the caching and the asking-for-permission are not here.
 */
object Weather {

    /**
     * How long a reading is worth having, in milliseconds.
     *
     * Weather changes slowly and networks fail often, so an hour-old
     * reading is worth far more than no reading. Past that it is a
     * different afternoon.
     */
    const val FRESH_MS = 3L * 60L * 60L * 1000L

    /**
     * How far apart two sources may be and still be called agreeing.
     *
     * Each is about the width of a real disagreement between two forecast
     * models for the same hour and place, rather than a round number: two
     * degrees, three hectopascals, a quarter of the sky, half a millimetre
     * of rain in an hour, eight kilometres an hour of wind.
     */
    const val TEMPERATURE_SLACK = 2.0
    const val PRESSURE_SLACK = 3.0
    const val CLOUD_SLACK = 25.0
    const val RAIN_SLACK = 0.5
    const val WIND_SLACK = 8.0

    /** How much of a reading anybody should believe. */
    enum class Trust {

        /** Nobody answered, or nobody answered with this. */
        NONE,

        /**
         * One source said so and nothing confirmed it.
         *
         * Still worth showing — a lone reading is what is left on the day
         * the other two are down, and that is the day this whole design
         * exists for. Worth showing *differently*, though: the clock draws
         * it faintly, because a number nobody has checked is not the same
         * object as one two services measured.
         */
        LONE,

        /** Two or more agreed, and any that did not were thrown away. */
        AGREED
    }

    /**
     * One service's answer, as far as it went.
     *
     * Every field is optional because every service is: one of them has no
     * pressure, another has no thunder, and a fourth added rain last year.
     * A source that answers half the questions is still worth asking.
     */
    data class Reading(
        val source: String,
        val atMs: Long,
        val temperatureC: Double? = null,
        val pressureHpa: Double? = null,
        val cloudPercent: Double? = null,
        val rainMmPerHour: Double? = null,
        val windKph: Double? = null,
        val thunder: Boolean? = null
    )

    /** One agreed number, and how many said it. */
    data class Agreed(val value: Double?, val trust: Trust) {
        companion object {
            val NOTHING = Agreed(null, Trust.NONE)
        }
    }

    /**
     * What the sky is doing, as far as anybody can be got to agree.
     *
     * [answered] is how many services were heard from at all, which is the
     * number that says whether this clock is talking to anything.
     */
    data class Sky(
        val temperatureC: Agreed = Agreed.NOTHING,
        val pressureHpa: Agreed = Agreed.NOTHING,
        val cloudPercent: Agreed = Agreed.NOTHING,
        val rainMmPerHour: Agreed = Agreed.NOTHING,
        val windKph: Agreed = Agreed.NOTHING,
        val thunder: Boolean? = null,
        val answered: Int = 0,
        val atMs: Long = 0L
    ) {

        /** Whether there is anything here worth drawing. */
        val known: Boolean
            get() = answered > 0 && (
                temperatureC.value != null || cloudPercent.value != null ||
                    rainMmPerHour.value != null || pressureHpa.value != null
                )
    }

    /**
     * The middle value, which is the whole of how this works.
     *
     * Not the average. An average lets one broken service drag the answer
     * anywhere it likes; a median cannot be moved at all until half of
     * them are wrong together.
     */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * One quantity, agreed.
     *
     * Take the middle value, throw away anything further than [slack] from
     * it, and take the middle of what is left. One survivor is a lone
     * reading; two or more is an agreement; nothing is nothing.
     *
     * The second median matters. After the outliers go, the middle of the
     * survivors is a number the survivors actually bracket — where keeping
     * the first median would hand back a value that the one discarded
     * source had helped to choose.
     */
    fun agree(values: List<Double>, slack: Double): Agreed {
        val middle = median(values) ?: return Agreed.NOTHING
        val kept = values.filter { abs(it - middle) <= slack }
        val settled = median(kept) ?: return Agreed.NOTHING
        return Agreed(settled, if (kept.size >= 2) Trust.AGREED else Trust.LONE)
    }

    /**
     * And a yes-or-no, agreed: whichever answer more of them gave.
     *
     * A tie is not an answer. Two services saying there is lightning and
     * two saying there is not is exactly the case where a clock should
     * draw nothing rather than pick a side.
     */
    fun agree(votes: List<Boolean>): Boolean? {
        if (votes.isEmpty()) return null
        val yes = votes.count { it }
        val no = votes.size - yes
        return when {
            yes > no -> true
            no > yes -> false
            else -> null
        }
    }

    /**
     * Every reading anybody gave, boiled down to one sky.
     *
     * Stale readings are dropped first — see [FRESH_MS] — because a
     * service that answered three hours ago and has been down since is not
     * a service that agrees with anything.
     */
    fun agree(readings: List<Reading>, nowMs: Long, freshMs: Long = FRESH_MS): Sky {
        val fresh = readings.filter { nowMs - it.atMs in 0..freshMs }
        if (fresh.isEmpty()) return Sky()
        return Sky(
            temperatureC = agree(fresh.mapNotNull { it.temperatureC }, TEMPERATURE_SLACK),
            pressureHpa = agree(fresh.mapNotNull { it.pressureHpa }, PRESSURE_SLACK),
            cloudPercent = agree(fresh.mapNotNull { it.cloudPercent }, CLOUD_SLACK),
            rainMmPerHour = agree(fresh.mapNotNull { it.rainMmPerHour }, RAIN_SLACK),
            windKph = agree(fresh.mapNotNull { it.windKph }, WIND_SLACK),
            thunder = agree(fresh.mapNotNull { it.thunder }),
            answered = fresh.size,
            atMs = fresh.maxOf { it.atMs }
        )
    }

    /**
     * What the sky looks like, as one word, for the token that has to draw
     * it in ten pixels.
     *
     * Ordered by what somebody standing outside would say first. Lightning
     * beats rain, rain beats cloud, and cloud has to be more than half the
     * sky before it is worth mentioning — a clear day with one cloud on it
     * is a clear day.
     */
    enum class Look { CLEAR, CLOUDY, OVERCAST, RAIN, STORM }

    /** Which of those five this sky is, or nothing if nobody knows. */
    fun look(sky: Sky): Look? {
        if (!sky.known) return null
        return look(sky.thunder, sky.rainMmPerHour.value, sky.cloudPercent.value)
    }

    /** How much rain in an hour is rain: less than this is a damp pavement. */
    const val RAIN_WORTH_SAYING = 0.2

    /** The one word, from the three things it is made of. */
    fun look(thunder: Boolean?, rainMmPerHour: Double?, cloudPercent: Double?): Look {
        if (thunder == true) return Look.STORM
        if (rainMmPerHour != null && rainMmPerHour >= RAIN_WORTH_SAYING) return Look.RAIN
        val cloud = cloudPercent ?: return Look.CLEAR
        return when {
            cloud >= 85.0 -> Look.OVERCAST
            cloud >= 50.0 -> Look.CLOUDY
            else -> Look.CLEAR
        }
    }

    // ------------------------------------------------------- the forecast

    /**
     * One service's guess at one hour that has not happened yet.
     *
     * [atMs] is the start of the hour. The temperature and the cloud are
     * what it expects at that moment; the rain is what it expects to fall
     * in the hour that follows — which is not how either service sends
     * it, and is what [WeatherSources] is for.
     */
    data class Hour(
        val atMs: Long,
        val temperatureC: Double? = null,
        val cloudPercent: Double? = null,
        val rainMm: Double? = null,
        val thunder: Boolean? = null
    )

    /** One hour to come, as far as anybody can be got to agree. */
    data class AgreedHour(
        val atMs: Long,
        val temperatureC: Agreed = Agreed.NOTHING,
        val cloudPercent: Agreed = Agreed.NOTHING,
        val rainMm: Agreed = Agreed.NOTHING,
        val thunder: Boolean? = null
    ) {
        /** What that hour is expected to look like. */
        val look: Look
            get() = look(thunder, rainMm.value, cloudPercent.value)

        /**
         * Whether a second service said the same.
         *
         * Asked of the temperature, because that is the one number both
         * of them always carry: a service that agrees about the degrees
         * was heard from.
         */
        val sure: Boolean
            get() = temperatureC.trust == Trust.AGREED
    }

    /**
     * One number about an hour to come, settled.
     *
     * The same middle value and the same tolerance as [agree], with one
     * difference at the end. Two readings of *now* that are far apart
     * mean one service is broken, and the honest answer is nothing. Two
     * forecasts that are far apart are two models, which is what a
     * forecast is — so what is left is their middle, marked as nobody's
     * confirmed number and drawn as faintly as a lone one. A forecast
     * with a hole in it wherever two models part company would have its
     * holes exactly where the weather is doing something.
     */
    fun expect(values: List<Double>, slack: Double): Agreed {
        val agreed = agree(values, slack)
        if (agreed.value != null) return agreed
        val middle = median(values) ?: return Agreed.NOTHING
        return Agreed(middle, Trust.LONE)
    }

    /** One hour is this long. */
    const val HOUR_MS = 3_600_000L

    /**
     * Every service's hours, boiled down to one run of them.
     *
     * Hour by hour from [fromMs] — rounded down to the hour it is in —
     * for [hours] of them. An hour nobody said anything about is left
     * out rather than carried as a blank, so the end of the list is the
     * end of what is known.
     */
    fun forecast(perSource: List<List<Hour>>, fromMs: Long, hours: Int): List<AgreedHour> {
        val first = Math.floorDiv(fromMs, HOUR_MS) * HOUR_MS
        val byHour = perSource.map { source -> source.associateBy { it.atMs } }
        val out = ArrayList<AgreedHour>(hours)
        for (i in 0 until hours) {
            val at = first + i * HOUR_MS
            val said = byHour.mapNotNull { it[at] }
            val hour = AgreedHour(
                atMs = at,
                temperatureC = expect(said.mapNotNull { it.temperatureC }, TEMPERATURE_SLACK),
                cloudPercent = expect(said.mapNotNull { it.cloudPercent }, CLOUD_SLACK),
                rainMm = expect(said.mapNotNull { it.rainMm }, RAIN_SLACK),
                thunder = agree(said.mapNotNull { it.thunder })
            )
            if (hour.temperatureC.value == null && hour.cloudPercent.value == null &&
                hour.rainMm.value == null
            ) {
                continue
            }
            out.add(hour)
        }
        return out
    }

    /**
     * One day to come: how cold, how warm, how wet, and the one word.
     *
     * [sure] is whether a second service had anything to say about it —
     * see [days].
     */
    data class Day(
        val startMs: Long,
        val minC: Double?,
        val maxC: Double?,
        val rainMm: Double,
        val look: Look,
        val sure: Boolean
    )

    /** How much rain in a day makes it a wet one. */
    const val WET_DAY_MM = 1.0

    /** How many of a day's hours have to be there before it is a day. */
    const val HOURS_FOR_A_DAY = 12

    /**
     * The days in [hours], each from one local midnight to the next.
     *
     * [dayStarts] are those midnights, in order, with one more at the end
     * to close the last day — handed in rather than worked out here,
     * because a day is twenty-three hours long twice a year and only a
     * calendar knows which.
     *
     * The cold end and the warm end are the lowest and highest hour.
     * The rain is every hour's added up. The word is the worst thing in
     * the day when that is lightning; rain when a millimetre or more is
     * expected across it; and otherwise how much cloud there is between
     * six in the morning and nine at night, because nobody asking what
     * tomorrow looks like means three in the morning.
     *
     * A day is sure when a second service agreed about one hour in six
     * of it or more. One of the two says something every hour for two
     * days and a half and every six hours after that, so further out
     * than that a day agreed four times is a day agreed as often as it
     * could be.
     */
    fun days(hours: List<AgreedHour>, dayStarts: List<Long>): List<Day> {
        val out = ArrayList<Day>()
        for (i in 0 until dayStarts.size - 1) {
            val from = dayStarts[i]
            val until = dayStarts[i + 1]
            val within = hours.filter { it.atMs in from until until }
            if (within.size < HOURS_FOR_A_DAY) continue
            val degrees = within.mapNotNull { it.temperatureC.value }
            val rain = within.sumOf { it.rainMm.value ?: 0.0 }
            val waking = within.filter { (it.atMs - from) in WAKING_FROM_MS until WAKING_UNTIL_MS }
            val cloud = waking.mapNotNull { it.cloudPercent.value }
            val look = when {
                within.any { it.thunder == true } -> Look.STORM
                rain >= WET_DAY_MM -> Look.RAIN
                else -> look(null, null, if (cloud.isEmpty()) null else cloud.average())
            }
            out.add(
                Day(
                    startMs = from,
                    minC = degrees.minOrNull(),
                    maxC = degrees.maxOrNull(),
                    rainMm = rain,
                    look = look,
                    sure = within.count { it.sure } * 6 >= within.size
                )
            )
        }
        return out
    }

    private const val WAKING_FROM_MS = 6L * HOUR_MS
    private const val WAKING_UNTIL_MS = 21L * HOUR_MS
}
